package com.muyuchat.mca.agent.memory

import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Automatic memory extraction (N16a/N16b backend slice): source text -> reviewable candidates ->
 * explicit, approved commit.
 *
 * Two hard separations are the whole point of this file:
 *
 * 1. **Candidates are not items.** [extract] only reads; it never touches [AgentMemoryStore]. A
 *    candidate exists so a user (or the settings surface) can review, edit and select it. Only
 *    [AgentMemoryExtractor.commit] writes, and it writes through the store's normal
 *    `ingest`/approval transaction, so a candidate cannot bypass the store's bounds, dedupe or
 *    approval rules.
 * 2. **The model is optional.** [AgentMemoryExtractionModel] is an injectable port; there is no
 *    default implementation and therefore no network call. A missing or failing model still leaves
 *    the deterministic rules working. Model output is untrusted input: it goes through the same
 *    normalization, bounds and credential filter as rule output, and model output can never name a
 *    different space because candidates carry no space id — the commit approval binds exactly one.
 *
 * Provenance: the candidate records its origin (rule id or model fingerprint). The persisted item
 * keeps [AgentMemorySourceKind.AUTO_EXTRACTION] plus the caller's source reference; a per-candidate
 * origin column would need a Room migration and is declared NOT_RUN in the N16 card.
 */
data class AgentMemoryExtractionCandidate(
    val candidateId: String,
    val text: String,
    val title: String,
    val contentHash: String,
    val origin: AgentMemoryExtractionOrigin,
    val confidence: AgentMemoryCandidateConfidence,
    val sourceRef: String?,
    /** Set when the same normalized text already exists in the target space. */
    val duplicateOf: AgentMemoryExtractionDuplicate? = null
) {
    init {
        require(candidateId.isNotBlank()) { "candidate id must not be blank" }
        require(text.isNotBlank()) { "candidate text must not be blank" }
        require(contentHash.isNotBlank()) { "candidate content hash must not be blank" }
    }
}

/** Where a candidate came from. A rule id is deterministic; a model proposal is labelled. */
sealed interface AgentMemoryExtractionOrigin {
    data class Rule(val ruleId: String) : AgentMemoryExtractionOrigin

    data class Model(val fingerprint: String) : AgentMemoryExtractionOrigin
}

/** Coarse review aid; never a substitute for the user's decision. */
enum class AgentMemoryCandidateConfidence {
    LOW,
    MEDIUM,
    HIGH
}

/** Existing item that already holds the same normalized text in the target space. */
data class AgentMemoryExtractionDuplicate(
    val memoryId: String,
    val revoked: Boolean
)

/** One extraction run's input. The source text is bounded by [AgentMemoryLimits.MAX_TEXT_BYTES]. */
data class AgentMemoryExtractionRequest(
    val spaceId: String,
    val text: String,
    val sourceRef: String? = null
)

/**
 * Optional model-assisted extraction.
 *
 * Implementations must be local by default: this port is not a network client, and a caller that
 * wants a remote provider must keep the existing Agent egress rules. The fingerprint identifies the
 * model/prompt pair so a candidate can say which producer proposed it.
 */
interface AgentMemoryExtractionModel {
    val fingerprint: String

    suspend fun propose(text: String, maxProposals: Int): List<AgentMemoryExtractionModelProposal>
}

/** One model proposal. `confidence` is an advisory 0..100 score, clamped by the extractor. */
data class AgentMemoryExtractionModelProposal(
    val text: String,
    val title: String? = null,
    val confidence: Int = 50
)

enum class AgentMemoryExtractionModelStatus {
    /** No model port was configured; only deterministic rules ran. */
    DISABLED,

    /** The model answered and its proposals went through the normal filters. */
    APPLIED,

    /** The model threw, returned a blank fingerprint or an invalid shape; rules still ran. */
    FAILED
}

/** One extraction run's reviewable output. Nothing here is persisted yet. */
data class AgentMemoryExtractionResult(
    val candidates: List<AgentMemoryExtractionCandidate>,
    val truncated: Boolean,
    val droppedSecrets: Int,
    val droppedForBounds: Int,
    val droppedDuplicates: Int,
    val modelStatus: AgentMemoryExtractionModelStatus
)

sealed interface AgentMemoryExtractionOutcome {
    data class Proposed(val result: AgentMemoryExtractionResult) : AgentMemoryExtractionOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemoryExtractionOutcome
}

enum class AgentMemoryExtractionCommitStatus {
    /** A new item was written. */
    STORED,

    /** The store already held this content (including a revoked item); the no-op is reported. */
    ALREADY_PRESENT,

    /** The candidate failed integrity/bounds/credential checks or the store rejected it. */
    REJECTED
}

/** Per-candidate commit fact. [memoryId] is set only when the store knows the item. */
data class AgentMemoryExtractionCommitEntry(
    val candidateId: String,
    val status: AgentMemoryExtractionCommitStatus,
    val memoryId: String? = null,
    val errorCode: String? = null,
    val safeMessage: String? = null
)

data class AgentMemoryExtractionCommitResult(
    val entries: List<AgentMemoryExtractionCommitEntry>
) {
    val stored: Int get() = entries.count { it.status == AgentMemoryExtractionCommitStatus.STORED }

    val alreadyPresent: Int
        get() = entries.count { it.status == AgentMemoryExtractionCommitStatus.ALREADY_PRESENT }

    val rejected: Int get() = entries.count { it.status == AgentMemoryExtractionCommitStatus.REJECTED }

    /** True when every reviewed candidate ended up as either a new item or an existing item. */
    val allAccepted: Boolean get() = entries.isNotEmpty() && rejected == 0
}

sealed interface AgentMemoryExtractionCommitOutcome {
    data class Applied(val result: AgentMemoryExtractionCommitResult) : AgentMemoryExtractionCommitOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemoryExtractionCommitOutcome
}

/**
 * Deterministic rule-based extraction plus an optional model port.
 *
 * Rules run first, in source order, so a repeated run over unchanged text produces the same
 * candidate order and the same content hashes. Each rule only *selects* a sentence; it never
 * rewrites one, except that an explicit remember trigger is stripped so the candidate is the fact
 * rather than the instruction ("请记住：明天九点开会" -> "明天九点开会").
 *
 * [store] is the only writer: the extractor itself calls only its content-hash lookup during
 * extraction and delegates the write to `AgentMemoryStore.ingest` during commit.
 */
class AgentMemoryExtractor(
    private val store: AgentMemoryStore,
    private val model: AgentMemoryExtractionModel? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    /** Reads only: proposes candidates and never writes to the store. */
    suspend fun extract(request: AgentMemoryExtractionRequest): AgentMemoryExtractionOutcome {
        val spaceId = request.spaceId.trim()
        if (!AgentMemorySelection.isValidSpaceId(spaceId)) {
            return rejectedExtraction(
                AgentMemoryErrorCodes.EXTRACTION_REQUEST_INVALID,
                "The extraction request must name one valid target memory space."
            )
        }
        val text = AgentMemoryText.normalize(request.text)
        if (text.isBlank()) {
            return rejectedExtraction(AgentMemoryErrorCodes.TEXT_EMPTY, "Extraction source text must not be empty.")
        }
        if (AgentMemoryText.utf8Size(text) > AgentMemoryLimits.MAX_TEXT_BYTES) {
            return rejectedExtraction(
                AgentMemoryErrorCodes.EXTRACTION_SOURCE_TOO_LARGE,
                "Extraction source exceeds the ${AgentMemoryLimits.MAX_TEXT_BYTES / 1024} KiB limit."
            )
        }
        val sourceRef = request.sourceRef?.trim()?.takeIf(String::isNotEmpty)
        if (sourceRef != null && sourceRef.length > AgentMemoryLimits.MAX_SOURCE_REF_CHARS) {
            return rejectedExtraction(
                AgentMemoryErrorCodes.SOURCE_REF_TOO_LARGE,
                "The extraction source reference is too long."
            )
        }

        val tally = Tally()
        val seen = LinkedHashSet<String>()
        val candidates = ArrayList<AgentMemoryExtractionCandidate>(AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES)

        for (sentence in sentences(text)) {
            val rule = ExtractionRule.entries.firstOrNull { it.matches(sentence) } ?: continue
            val candidateText = if (rule.stripsTrigger) rule.stripTrigger(sentence) else sentence
            val candidate = buildCandidate(
                text = candidateText,
                explicitTitle = null,
                origin = AgentMemoryExtractionOrigin.Rule(rule.id),
                confidence = rule.confidence,
                sourceRef = sourceRef,
                seen = seen,
                tally = tally
            ) ?: continue
            if (candidates.size >= AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES) {
                tally.truncated = true
                break
            }
            seen += candidate.contentHash
            candidates += candidate
        }

        var modelStatus = AgentMemoryExtractionModelStatus.DISABLED
        if (model != null) {
            if (candidates.size >= AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES) {
                // There may be proposals the budget cannot consider; say so instead of dropping them
                // silently, but do not spend a model call with no room to show the result.
                tally.truncated = true
            } else {
                val remaining = AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES - candidates.size
                val proposals = try {
                    model.propose(text, minOf(remaining, AgentMemoryLimits.MAX_EXTRACTION_MODEL_PROPOSALS))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val fingerprint = model.fingerprint.trim()
                if (proposals == null || fingerprint.isEmpty()) {
                    modelStatus = AgentMemoryExtractionModelStatus.FAILED
                } else {
                    modelStatus = AgentMemoryExtractionModelStatus.APPLIED
                    if (proposals.size > AgentMemoryLimits.MAX_EXTRACTION_MODEL_PROPOSALS) {
                        // The port ignored its budget; the extra proposals are not reviewable.
                        tally.truncated = true
                    }
                    for (proposal in proposals.take(AgentMemoryLimits.MAX_EXTRACTION_MODEL_PROPOSALS)) {
                        val candidate = buildCandidate(
                            text = proposal.text,
                            explicitTitle = proposal.title,
                            origin = AgentMemoryExtractionOrigin.Model(fingerprint),
                            confidence = confidenceOf(proposal.confidence),
                            sourceRef = sourceRef,
                            seen = seen,
                            tally = tally
                        ) ?: continue
                        if (candidates.size >= AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES) {
                            tally.truncated = true
                            break
                        }
                        seen += candidate.contentHash
                        candidates += candidate
                    }
                }
            }
        }

        val annotated = annotateDuplicates(spaceId, candidates)
        return AgentMemoryExtractionOutcome.Proposed(
            AgentMemoryExtractionResult(
                candidates = annotated,
                truncated = tally.truncated,
                droppedSecrets = tally.secrets,
                droppedForBounds = tally.bounds,
                droppedDuplicates = tally.duplicates,
                modelStatus = modelStatus
            )
        )
    }

    /**
     * Commits reviewed candidates through the same store transaction the manual import uses.
     *
     * The approval must name exactly [spaceId]; a candidate never carries authority of its own. Each
     * candidate is re-validated (canonical text, hash, bounds, credential filter) before the store
     * sees it, so a caller cannot smuggle arbitrary text by editing a candidate in transit.
     */
    suspend fun commit(
        spaceId: String,
        candidates: List<AgentMemoryExtractionCandidate>,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryExtractionCommitOutcome {
        if (!approval.authorizesItems(spaceId)) {
            return rejectedCommit(
                AgentMemoryErrorCodes.WRITE_NOT_APPROVED,
                "Committing extracted memory requires an explicit approval for the target space."
            )
        }
        val maximum = AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES +
            AgentMemoryLimits.MAX_EXTRACTION_MODEL_PROPOSALS
        if (candidates.isEmpty() || candidates.size > maximum) {
            return rejectedCommit(
                AgentMemoryErrorCodes.CANDIDATE_INVALID,
                "The commit must select between 1 and $maximum extracted candidates."
            )
        }
        val entries = ArrayList<AgentMemoryExtractionCommitEntry>(candidates.size)
        for (candidate in candidates) {
            val invalid = validateCandidate(candidate)
            if (invalid != null) {
                entries += AgentMemoryExtractionCommitEntry(
                    candidateId = candidate.candidateId,
                    status = AgentMemoryExtractionCommitStatus.REJECTED,
                    errorCode = invalid.code,
                    safeMessage = invalid.safeMessage
                )
                continue
            }
            val request = AgentMemoryIngestRequest(
                title = candidate.title,
                text = candidate.text,
                sourceKind = AgentMemorySourceKind.AUTO_EXTRACTION,
                sourceRef = candidate.sourceRef
            )
            when (val outcome = store.ingest(spaceId, request, approval)) {
                is AgentMemoryIngestOutcome.Ingested -> entries += AgentMemoryExtractionCommitEntry(
                    candidateId = candidate.candidateId,
                    status = AgentMemoryExtractionCommitStatus.STORED,
                    memoryId = outcome.item.memoryId
                )
                is AgentMemoryIngestOutcome.Deduplicated -> entries += AgentMemoryExtractionCommitEntry(
                    candidateId = candidate.candidateId,
                    status = AgentMemoryExtractionCommitStatus.ALREADY_PRESENT,
                    memoryId = outcome.memoryId
                )
                is AgentMemoryIngestOutcome.Rejected -> entries += AgentMemoryExtractionCommitEntry(
                    candidateId = candidate.candidateId,
                    status = AgentMemoryExtractionCommitStatus.REJECTED,
                    errorCode = outcome.error.code,
                    safeMessage = outcome.error.safeMessage
                )
            }
        }
        return AgentMemoryExtractionCommitOutcome.Applied(AgentMemoryExtractionCommitResult(entries))
    }

    private fun buildCandidate(
        text: String,
        explicitTitle: String?,
        origin: AgentMemoryExtractionOrigin,
        confidence: AgentMemoryCandidateConfidence,
        sourceRef: String?,
        seen: MutableSet<String>,
        tally: Tally
    ): AgentMemoryExtractionCandidate? {
        val normalized = AgentMemoryText.normalize(text)
        if (normalized.length < AgentMemoryLimits.MIN_EXTRACTION_CANDIDATE_CHARS ||
            normalized.length > AgentMemoryLimits.MAX_EXTRACTION_CANDIDATE_CHARS
        ) {
            tally.bounds++
            return null
        }
        if (AgentMemorySecretFilter.looksLikeCredential(normalized)) {
            tally.secrets++
            return null
        }
        val contentHash = AgentMemoryText.sha256(normalized)
        if (contentHash in seen) {
            tally.duplicates++
            return null
        }
        return AgentMemoryExtractionCandidate(
            candidateId = idFactory(),
            text = normalized,
            title = resolveTitle(explicitTitle, normalized),
            contentHash = contentHash,
            origin = origin,
            confidence = confidence,
            sourceRef = sourceRef
        )
    }

    private fun validateCandidate(candidate: AgentMemoryExtractionCandidate): AgentMemoryError? {
        val normalized = AgentMemoryText.normalize(candidate.text)
        if (normalized != candidate.text) return invalidCandidate()
        if (candidate.text.length < AgentMemoryLimits.MIN_EXTRACTION_CANDIDATE_CHARS ||
            candidate.text.length > AgentMemoryLimits.MAX_EXTRACTION_CANDIDATE_CHARS
        ) {
            return invalidCandidate()
        }
        if (candidate.contentHash != AgentMemoryText.sha256(candidate.text)) return invalidCandidate()
        if (AgentMemorySecretFilter.looksLikeCredential(candidate.text)) return invalidCandidate()
        if (candidate.sourceRef != null && candidate.sourceRef.length > AgentMemoryLimits.MAX_SOURCE_REF_CHARS) {
            return invalidCandidate()
        }
        return null
    }

    private suspend fun annotateDuplicates(
        spaceId: String,
        candidates: List<AgentMemoryExtractionCandidate>
    ): List<AgentMemoryExtractionCandidate> = candidates.map { candidate ->
        val existing = try {
            store.findByContentHash(spaceId, candidate.contentHash)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (existing == null) {
            candidate
        } else {
            candidate.copy(
                duplicateOf = AgentMemoryExtractionDuplicate(existing.memoryId, existing.revoked)
            )
        }
    }

    private fun resolveTitle(explicitTitle: String?, text: String): String {
        val explicit = explicitTitle?.let(AgentMemoryText::normalize)?.takeIf(String::isNotEmpty)
        if (explicit != null) {
            return if (explicit.length <= AgentMemoryLimits.MAX_TITLE_CHARS) {
                explicit
            } else {
                explicit.take(AgentMemoryLimits.MAX_TITLE_CHARS)
            }
        }
        val singleLine = text.replace('\n', ' ').trim()
        return if (singleLine.length <= CANDIDATE_TITLE_CHARS) {
            singleLine
        } else {
            singleLine.take(CANDIDATE_TITLE_CHARS - 1) + "\u2026"
        }
    }

    private fun sentences(text: String): List<String> = text.split(SENTENCE_BOUNDARY)
        .map { raw -> raw.replace(LIST_PREFIX, "").trim() }
        .filter(String::isNotEmpty)

    private fun confidenceOf(score: Int): AgentMemoryCandidateConfidence = when {
        score >= 70 -> AgentMemoryCandidateConfidence.HIGH
        score >= 40 -> AgentMemoryCandidateConfidence.MEDIUM
        else -> AgentMemoryCandidateConfidence.LOW
    }

    private fun invalidCandidate(): AgentMemoryError = AgentMemoryError(
        AgentMemoryErrorCodes.CANDIDATE_INVALID,
        "The selected candidate is no longer a valid extraction result; extract it again."
    )

    private fun rejectedExtraction(code: String, message: String): AgentMemoryExtractionOutcome =
        AgentMemoryExtractionOutcome.Rejected(AgentMemoryError(code, message))

    private fun rejectedCommit(code: String, message: String): AgentMemoryExtractionCommitOutcome =
        AgentMemoryExtractionCommitOutcome.Rejected(AgentMemoryError(code, message))

    private class Tally {
        var secrets = 0
        var bounds = 0
        var duplicates = 0
        var truncated = false
    }

    private companion object {
        const val CANDIDATE_TITLE_CHARS = 60
        val SENTENCE_BOUNDARY = Regex("[。！？!?；;\\n]+")
        val LIST_PREFIX = Regex("^\\s*(?:[-*•+]\\s+|\\d{1,3}[.)]\\s+)")
    }
}

/**
 * The deterministic extraction rules.
 *
 * A rule only says "this sentence looks like a memory worth reviewing"; the user decides. The order
 * of the enum is the priority order used when one sentence matches several rules.
 */
private enum class ExtractionRule(
    val id: String,
    val confidence: AgentMemoryCandidateConfidence,
    private val triggers: List<String>,
    val stripsTrigger: Boolean = false
) {
    REMEMBER(
        id = "remember_explicit",
        confidence = AgentMemoryCandidateConfidence.HIGH,
        triggers = listOf(
            "记住",
            "记一下",
            "记下来",
            "记下",
            "别忘了",
            "牢记",
            "remember this",
            "remember that",
            "don't forget",
            "do not forget"
        ),
        stripsTrigger = true
    ),
    PROFILE(
        id = "profile_fact",
        confidence = AgentMemoryCandidateConfidence.HIGH,
        triggers = listOf(
            "我叫",
            "我的名字",
            "我的生日",
            "我住在",
            "我的邮箱",
            "我的电话",
            "我的地址",
            "my name is",
            "i live in",
            "my birthday",
            "my email",
            "my phone"
        )
    ),
    PREFERENCE(
        id = "preference",
        confidence = AgentMemoryCandidateConfidence.MEDIUM,
        triggers = listOf(
            "我喜欢",
            "我不喜欢",
            "我更喜欢",
            "我偏好",
            "我讨厌",
            "我的偏好",
            "我习惯",
            "我常用",
            "my favorite",
            "i prefer",
            "i like",
            "i don't like",
            "i dislike"
        )
    ),
    CONSTRAINT(
        id = "constraint",
        confidence = AgentMemoryCandidateConfidence.MEDIUM,
        triggers = listOf(
            "不要",
            "禁止",
            "必须",
            "务必",
            "只能",
            "不允许",
            "must not",
            "never ",
            "always ",
            "do not "
        )
    );

    fun matches(sentence: String): Boolean = firstTriggerIndex(sentence) != null

    /**
     * Strips the leading remember instruction so the candidate is the fact. The first matching
     * trigger in declaration order is used, which keeps the result stable across runs.
     */
    fun stripTrigger(sentence: String): String {
        for (trigger in triggers) {
            val index = sentence.indexOf(trigger, ignoreCase = true)
            if (index >= 0) {
                return sentence.substring(index + trigger.length)
                    .trimStart(':', '：', ',', '，', ' ', '\t', '-', '—', '、')
                    .trim()
            }
        }
        return sentence
    }

    private fun firstTriggerIndex(sentence: String): Int? {
        triggers.forEach { trigger ->
            val index = sentence.indexOf(trigger, ignoreCase = true)
            if (index >= 0) return index
        }
        return null
    }
}

/**
 * Credential filter shared by rule and model output.
 *
 * The rule is deliberately conservative in the other direction: a bare word such as "token" is not a
 * credential, only an assignment shape (`api_key=`, `密码：`) or a recognizable secret value is.
 */
internal object AgentMemorySecretFilter {
    private val ASSIGNMENT = Regex(
        "(?i)(api[_ -]?key|access[_ -]?token|auth[_ -]?token|client[_ -]?secret|secret|" +
            "password|passwd|密码|口令|密钥|私钥|验证码)\\s*(?:[:=：]|是|为)"
    )
    private val SECRET_VALUE = Regex(
        "(?i)(sk-[a-z0-9_-]{16,}|gh[pousr]_[a-z0-9]{20,}|akia[0-9a-z]{16}|" +
            "bearer\\s+[a-z0-9._-]{16,}|-----begin [a-z ]+-----)"
    )

    fun looksLikeCredential(text: String): Boolean =
        ASSIGNMENT.containsMatchIn(text) || SECRET_VALUE.containsMatchIn(text)
}
