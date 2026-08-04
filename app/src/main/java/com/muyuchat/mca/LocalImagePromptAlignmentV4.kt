package com.muyuchat.mca

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

internal const val LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION = 4
internal const val LOCAL_IMAGE_PROMPT_ALIGNMENT_MAX_RESPONSE_CHARS = 64 * 1_024

internal enum class LocalImagePromptAlignmentRole(val wireName: String) {
    POSITIVE("positive"),
    NEGATIVE("negative")
}

internal enum class LocalImagePromptAlignmentOrigin(
    val role: LocalImagePromptAlignmentRole
) {
    USER_PROMPT(LocalImagePromptAlignmentRole.POSITIVE),
    USER_NEGATIVE_PROMPT(LocalImagePromptAlignmentRole.NEGATIVE),
    CANARY_PROMPT(LocalImagePromptAlignmentRole.POSITIVE),
    CANARY_NEGATIVE_PROMPT(LocalImagePromptAlignmentRole.NEGATIVE)
}

/**
 * Clause boundaries remain owned by the prompt parser. This helper only receives already-split,
 * top-level clauses so it cannot accidentally reinterpret protected diffusion syntax.
 *
 * A nullable negative list distinguishes an absent user negative prompt from a present-but-empty
 * one. Canary clauses are mandatory and are deliberately mixed into both alignment phases without
 * revealing which entries are canaries to the local model.
 */
internal data class LocalImagePromptAlignmentClauseSet(
    val promptClauses: List<String>,
    val negativePromptClauses: List<String>?,
    val canaryPromptClauses: List<String>,
    val canaryNegativePromptClauses: List<String>
) {
    init {
        require(promptClauses.isNotEmpty()) { "Prompt alignment requires a positive prompt." }
        require(canaryPromptClauses.isNotEmpty() && canaryNegativePromptClauses.isNotEmpty()) {
            "Prompt alignment requires positive and negative canary clauses."
        }
        val allClauses = buildList {
            addAll(promptClauses)
            negativePromptClauses?.let { addAll(it) }
            addAll(canaryPromptClauses)
            addAll(canaryNegativePromptClauses)
        }
        val translatableUnits = buildSet {
            promptClauses.filter(String::containsHanScript).forEach { add("positive" to it) }
            canaryPromptClauses.filter(String::containsHanScript).forEach { add("positive" to it) }
            negativePromptClauses.orEmpty().filter(String::containsHanScript)
                .forEach { add("negative" to it) }
            canaryNegativePromptClauses.filter(String::containsHanScript)
                .forEach { add("negative" to it) }
        }
        require(translatableUnits.size <= MAX_ALIGNMENT_CLAUSE_COUNT) {
            "Prompt alignment contains too many clauses."
        }
        require(allClauses.all { clause ->
            clause.isNotBlank() && clause.length <= MAX_ALIGNMENT_SOURCE_CLAUSE_CHARS
        }) { "Prompt alignment contains an invalid clause." }
        require(translatableUnits.sumOf { (_, clause) -> clause.length } <=
            MAX_ALIGNMENT_SOURCE_CHARS
        ) {
            "Prompt alignment source is too large."
        }
    }
}

internal fun interface LocalImagePromptAlignmentEntropy {
    fun nextBytes(destination: ByteArray)
}

internal object SecureLocalImagePromptAlignmentEntropy : LocalImagePromptAlignmentEntropy {
    private val random = SecureRandom()

    override fun nextBytes(destination: ByteArray) {
        random.nextBytes(destination)
    }
}

internal data class LocalImagePromptAlignmentOccurrence(
    val origin: LocalImagePromptAlignmentOrigin,
    val index: Int
)

internal data class LocalImagePromptTranslationUnitV4(
    val ordinal: Int,
    val role: LocalImagePromptAlignmentRole,
    val sourceText: String,
    val occurrences: List<LocalImagePromptAlignmentOccurrence>,
    val phaseASlotId: String
)

internal class LocalImagePromptTranslationPlanV4 internal constructor(
    val clauses: LocalImagePromptAlignmentClauseSet,
    internal val units: List<LocalImagePromptTranslationUnitV4>,
    private val requestOrder: List<Int>,
    val planSha256: String
) {
    init {
        require(units.isNotEmpty())
        require(units.indices.toList() == units.map { unit -> unit.ordinal })
        require(units.map { unit -> unit.phaseASlotId }.toSet().size == units.size)
        require(units.all { unit -> OPAQUE_ID_PATTERN.matches(unit.phaseASlotId) })
        require(requestOrder.toSet() == units.indices.toSet() && requestOrder.size == units.size)
        require(SHA256_PATTERN.matches(planSha256))
    }

    fun phaseARequestJson(): JSONObject = JSONObject()
        .put("contract_version", LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
        .put("items", JSONArray().apply {
            requestOrder.forEach { ordinal ->
                val unit = units[ordinal]
                put(JSONObject()
                    .put("slot_id", unit.phaseASlotId)
                    .put("role", unit.role.wireName)
                    .put("source", unit.sourceText)
                )
            }
        })
}

internal class LocalImagePromptTranslationResultV4 internal constructor(
    internal val plan: LocalImagePromptTranslationPlanV4,
    internal val translationsByUnit: Map<Int, String>
) {
    init {
        require(translationsByUnit.keys == plan.units.indices.toSet())
    }

    val planSha256: String get() = plan.planSha256

    fun translatedPromptClauses(): List<String> = translatedClauses(
        LocalImagePromptAlignmentOrigin.USER_PROMPT,
        plan.clauses.promptClauses.size
    )

    fun translatedNegativePromptClauses(): List<String>? =
        plan.clauses.negativePromptClauses?.let { clauses ->
            translatedClauses(LocalImagePromptAlignmentOrigin.USER_NEGATIVE_PROMPT, clauses.size)
        }

    fun translatedCanaryPromptClauses(): List<String> = translatedClauses(
        LocalImagePromptAlignmentOrigin.CANARY_PROMPT,
        plan.clauses.canaryPromptClauses.size
    )

    fun translatedCanaryNegativePromptClauses(): List<String> = translatedClauses(
        LocalImagePromptAlignmentOrigin.CANARY_NEGATIVE_PROMPT,
        plan.clauses.canaryNegativePromptClauses.size
    )

    internal fun translationForUnit(ordinal: Int): String =
        requireNotNull(translationsByUnit[ordinal])

    private fun translatedClauses(
        origin: LocalImagePromptAlignmentOrigin,
        expectedCount: Int
    ): List<String> {
        val originalClauses = when (origin) {
            LocalImagePromptAlignmentOrigin.USER_PROMPT -> plan.clauses.promptClauses
            LocalImagePromptAlignmentOrigin.USER_NEGATIVE_PROMPT ->
                plan.clauses.negativePromptClauses.orEmpty()
            LocalImagePromptAlignmentOrigin.CANARY_PROMPT -> plan.clauses.canaryPromptClauses
            LocalImagePromptAlignmentOrigin.CANARY_NEGATIVE_PROMPT ->
                plan.clauses.canaryNegativePromptClauses
        }
        require(originalClauses.size == expectedCount)
        val byOccurrence = buildMap {
            plan.units.forEach { unit ->
                unit.occurrences.forEach { occurrence ->
                    put(occurrence, translationForUnit(unit.ordinal))
                }
            }
        }
        return List(expectedCount) { index ->
            byOccurrence[LocalImagePromptAlignmentOccurrence(origin, index)]
                ?: originalClauses[index].also { clause ->
                    require(!clause.containsHanScript()) {
                        "Prompt alignment omitted a clause that requires translation."
                    }
                }
        }
    }
}

internal data class LocalImagePromptVerificationSourceV4(
    val sourceId: String,
    val unitOrdinal: Int,
    val role: LocalImagePromptAlignmentRole,
    val sourceText: String
)

internal data class LocalImagePromptVerificationCandidateV4(
    val candidateId: String,
    val unitOrdinal: Int,
    val role: LocalImagePromptAlignmentRole,
    val translation: String
)

internal class LocalImagePromptVerificationChallengeV4 internal constructor(
    internal val translationResult: LocalImagePromptTranslationResultV4,
    internal val sources: List<LocalImagePromptVerificationSourceV4>,
    internal val candidates: List<LocalImagePromptVerificationCandidateV4>
) {
    init {
        val expectedOrdinals = translationResult.plan.units.indices.toSet()
        require(sources.size == expectedOrdinals.size && candidates.size == expectedOrdinals.size)
        require(sources.map { source -> source.unitOrdinal }.toSet() == expectedOrdinals)
        require(candidates.map { candidate -> candidate.unitOrdinal }.toSet() == expectedOrdinals)
        require(sources.map { source -> source.sourceId }.toSet().size == sources.size)
        require(candidates.map { candidate -> candidate.candidateId }.toSet().size == candidates.size)
    }

    fun phaseBRequestJson(): JSONObject = JSONObject()
        .put("contract_version", LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
        .put("sources", JSONArray().apply {
            sources.forEach { source ->
                put(JSONObject()
                    .put("source_id", source.sourceId)
                    .put("role", source.role.wireName)
                    .put("source", source.sourceText)
                )
            }
        })
        .put("candidates", JSONArray().apply {
            candidates.forEach { candidate ->
                put(JSONObject()
                    .put("candidate_id", candidate.candidateId)
                    .put("role", candidate.role.wireName)
                    .put("translation", candidate.translation)
                )
            }
        })
}

internal data class LocalImagePromptVerificationReceiptV4(
    val planSha256: String,
    val receiptSha256: String,
    val verifiedUnitCount: Int
) {
    init {
        require(SHA256_PATTERN.matches(planSha256))
        require(SHA256_PATTERN.matches(receiptSha256))
        require(verifiedUnitCount > 0)
    }
}

internal enum class LocalImagePromptAlignmentSystemPhase(val wireName: String) {
    TRANSLATION("translation"),
    VERIFICATION("verification")
}

/** Two-phase opaque-slot planner. One instance may safely be scoped to one translation request. */
internal class LocalImagePromptAlignmentV4(
    private val entropy: LocalImagePromptAlignmentEntropy =
        SecureLocalImagePromptAlignmentEntropy
) {
    fun createTranslationPlan(
        clauses: LocalImagePromptAlignmentClauseSet
    ): LocalImagePromptTranslationPlanV4 {
        val occurrences = clauses.canonicalOccurrences()
        val grouped = LinkedHashMap<Pair<LocalImagePromptAlignmentRole, String>, MutableList<LocalImagePromptAlignmentOccurrence>>()
        occurrences.forEach { occurrence ->
            grouped.getOrPut(occurrence.role to occurrence.text) { mutableListOf() }
                .add(LocalImagePromptAlignmentOccurrence(occurrence.origin, occurrence.index))
        }
        val usedIds = mutableSetOf<String>()
        val units = grouped.entries.mapIndexed { ordinal, (key, unitOccurrences) ->
            LocalImagePromptTranslationUnitV4(
                ordinal = ordinal,
                role = key.first,
                sourceText = key.second,
                occurrences = unitOccurrences.toList(),
                phaseASlotId = newOpaqueId("a", usedIds)
            )
        }
        return LocalImagePromptTranslationPlanV4(
            clauses = clauses,
            units = units,
            requestOrder = units.indices.toList().shuffledWith(entropy),
            planSha256 = clauses.canonicalPlanSha256()
        )
    }

    fun parsePhaseAResponse(
        plan: LocalImagePromptTranslationPlanV4,
        raw: String
    ): LocalImagePromptTranslationResultV4 {
        val root = strictJsonObject(raw, PHASE_A_ROOT_FIELDS)
        require(root.requiredInt("contract_version") ==
            LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION
        ) { "Prompt translation returned the wrong contract version." }
        val translations = root.requiredArray("translations")
        require(translations.length() == plan.units.size) {
            "Prompt translation returned the wrong slot count."
        }
        val bySlot = plan.units.associateBy(LocalImagePromptTranslationUnitV4::phaseASlotId)
        val seen = mutableSetOf<String>()
        val translated = mutableMapOf<Int, String>()
        for (index in 0 until translations.length()) {
            val item = translations.requiredObject(index, PHASE_A_ITEM_FIELDS)
            val slotId = item.requiredString("slot_id")
            require(seen.add(slotId)) { "Prompt translation repeated a slot id." }
            val unit = requireNotNull(bySlot[slotId]) {
                "Prompt translation returned an unknown slot id."
            }
            val value = item.requiredString("translation").trim()
            require(value.isNotBlank() && value.length <= MAX_ALIGNMENT_TRANSLATED_CLAUSE_CHARS) {
                "Prompt translation returned an invalid clause."
            }
            require(value.isVisibleAsciiAlignmentText()) {
                "Prompt translation returned non-ASCII or control characters."
            }
            if (!unit.sourceText.containsHanScript()) {
                require(value == unit.sourceText) {
                    "Prompt translation rewrote a clause that did not require translation."
                }
            }
            translated[unit.ordinal] = value
        }
        require(seen == bySlot.keys) { "Prompt translation omitted a slot." }
        require(translated.values.sumOf { value -> value.length } <= MAX_ALIGNMENT_TRANSLATED_CHARS) {
            "Prompt translation output is too large."
        }
        return LocalImagePromptTranslationResultV4(plan, translated.toMap())
    }

    fun createVerificationChallenge(
        result: LocalImagePromptTranslationResultV4
    ): LocalImagePromptVerificationChallengeV4 {
        val forbiddenIds = result.plan.units.mapTo(mutableSetOf()) { unit -> unit.phaseASlotId }
        val sources = result.plan.units.map { unit ->
            LocalImagePromptVerificationSourceV4(
                sourceId = newOpaqueId("s", forbiddenIds),
                unitOrdinal = unit.ordinal,
                role = unit.role,
                sourceText = unit.sourceText
            )
        }.shuffledWith(entropy)
        val candidates = result.plan.units.map { unit ->
            LocalImagePromptVerificationCandidateV4(
                candidateId = newOpaqueId("c", forbiddenIds),
                unitOrdinal = unit.ordinal,
                role = unit.role,
                translation = result.translationForUnit(unit.ordinal)
            )
        }.shuffledWith(entropy)
        return LocalImagePromptVerificationChallengeV4(result, sources, candidates)
    }

    fun verifyPhaseBResponse(
        challenge: LocalImagePromptVerificationChallengeV4,
        raw: String
    ): LocalImagePromptVerificationReceiptV4 {
        val root = strictJsonObject(raw, PHASE_B_ROOT_FIELDS)
        require(root.requiredInt("contract_version") ==
            LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION
        ) { "Prompt verification returned the wrong contract version." }
        val matches = root.requiredArray("matches")
        require(matches.length() == challenge.sources.size) {
            "Prompt verification returned the wrong match count."
        }
        val sourceById = challenge.sources.associateBy(LocalImagePromptVerificationSourceV4::sourceId)
        val candidateById = challenge.candidates.associateBy(
            LocalImagePromptVerificationCandidateV4::candidateId
        )
        val seenSources = mutableSetOf<String>()
        val seenCandidates = mutableSetOf<String>()
        for (index in 0 until matches.length()) {
            val item = matches.requiredObject(index, PHASE_B_ITEM_FIELDS)
            val sourceId = item.requiredString("source_id")
            val candidateId = item.requiredString("candidate_id")
            require(item.requiredString("verdict") == PHASE_B_EQUIVALENT_VERDICT) {
                "Prompt verification returned an unsupported verdict."
            }
            require(seenSources.add(sourceId)) { "Prompt verification repeated a source id." }
            require(seenCandidates.add(candidateId)) { "Prompt verification repeated a candidate id." }
            val source = requireNotNull(sourceById[sourceId]) {
                "Prompt verification returned an unknown source id."
            }
            val candidate = requireNotNull(candidateById[candidateId]) {
                "Prompt verification returned an unknown candidate id."
            }
            require(candidate.role == source.role && candidate.unitOrdinal == source.unitOrdinal) {
                "Prompt verification did not preserve a source-to-translation binding."
            }
        }
        require(seenSources == sourceById.keys && seenCandidates == candidateById.keys) {
            "Prompt verification did not return a complete bijection."
        }
        return LocalImagePromptVerificationReceiptV4(
            planSha256 = challenge.translationResult.planSha256,
            receiptSha256 = challenge.translationResult.canonicalReceiptSha256(),
            verifiedUnitCount = challenge.sources.size
        )
    }

    private fun newOpaqueId(prefix: String, usedIds: MutableSet<String>): String {
        repeat(MAX_OPAQUE_ID_ATTEMPTS) {
            val bytes = ByteArray(OPAQUE_ID_BYTES)
            entropy.nextBytes(bytes)
            val candidate = "${prefix}_${bytes.toLowerHex()}"
            if (usedIds.add(candidate)) return candidate
        }
        error("Prompt alignment entropy did not produce a unique opaque id.")
    }
}

internal fun localImagePromptAlignmentSystemPromptSha256V4(
    phase: LocalImagePromptAlignmentSystemPhase,
    systemPrompt: String
): String {
    require(systemPrompt.isNotBlank())
    return CanonicalSha256("mca.local-image.prompt-alignment.v4.system-prompt")
        .string(phase.wireName)
        .string(systemPrompt)
        .finish()
}

private data class CanonicalClauseOccurrence(
    val origin: LocalImagePromptAlignmentOrigin,
    val index: Int,
    val text: String
) {
    val role: LocalImagePromptAlignmentRole get() = origin.role
}

private fun LocalImagePromptAlignmentClauseSet.canonicalOccurrences(): List<CanonicalClauseOccurrence> =
    buildList {
        promptClauses.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(LocalImagePromptAlignmentOrigin.USER_PROMPT, index, text))
        }
        negativePromptClauses?.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(
                LocalImagePromptAlignmentOrigin.USER_NEGATIVE_PROMPT,
                index,
                text
            ))
        }
        canaryPromptClauses.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(LocalImagePromptAlignmentOrigin.CANARY_PROMPT, index, text))
        }
        canaryNegativePromptClauses.forEachIndexed { index, text ->
            add(CanonicalClauseOccurrence(
                LocalImagePromptAlignmentOrigin.CANARY_NEGATIVE_PROMPT,
                index,
                text
            ))
        }
    }.filter { occurrence -> occurrence.text.containsHanScript() }

private fun LocalImagePromptAlignmentClauseSet.canonicalPlanSha256(): String {
    val digest = CanonicalSha256("mca.local-image.prompt-alignment.v4.plan")
        .integer(LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
        .boolean(negativePromptClauses != null)
    listOf(
        LocalImagePromptAlignmentOrigin.USER_PROMPT to promptClauses,
        LocalImagePromptAlignmentOrigin.USER_NEGATIVE_PROMPT to negativePromptClauses.orEmpty(),
        LocalImagePromptAlignmentOrigin.CANARY_PROMPT to canaryPromptClauses,
        LocalImagePromptAlignmentOrigin.CANARY_NEGATIVE_PROMPT to canaryNegativePromptClauses
    ).forEach { (origin, clauses) ->
        digest.string(origin.name).integer(clauses.size)
        clauses.forEachIndexed { index, clause ->
            digest.integer(index).string(clause)
        }
    }
    return digest.finish()
}

private fun LocalImagePromptTranslationResultV4.canonicalReceiptSha256(): String {
    val digest = CanonicalSha256("mca.local-image.prompt-alignment.v4.receipt")
        .integer(LOCAL_IMAGE_PROMPT_ALIGNMENT_CONTRACT_VERSION)
        .string(planSha256)
        .integer(plan.units.size)
    plan.units.forEach { unit ->
        digest.integer(unit.ordinal)
            .string(unit.role.name)
            .string(unit.sourceText)
            .string(translationForUnit(unit.ordinal))
    }
    return digest.string("strict-bijection-verified").finish()
}

private class CanonicalSha256(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false

    init {
        string(domain)
    }

    fun string(value: String): CanonicalSha256 = apply {
        check(!finished)
        val bytes = value.toByteArray(Charsets.UTF_8)
        integer(bytes.size)
        digest.update(bytes)
    }

    fun integer(value: Int): CanonicalSha256 = apply {
        check(!finished)
        digest.update(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        ))
    }

    fun boolean(value: Boolean): CanonicalSha256 = apply {
        check(!finished)
        digest.update(if (value) 1.toByte() else 0.toByte())
    }

    fun finish(): String {
        check(!finished)
        finished = true
        return digest.digest().toLowerHex()
    }
}

private fun strictJsonObject(raw: String, expectedFields: Set<String>): JSONObject {
    require(raw.length <= LOCAL_IMAGE_PROMPT_ALIGNMENT_MAX_RESPONSE_CHARS) {
        "Prompt alignment response is too large."
    }
    val trimmed = raw.trim()
    require(trimmed.startsWith('{') && trimmed.endsWith('}')) {
        "Prompt alignment response must be one JSON object."
    }
    return JSONObject(trimmed).also { value ->
        require(value.keys().asSequence().toSet() == expectedFields) {
            "Prompt alignment response contains unsupported fields."
        }
    }
}

private fun JSONObject.requiredArray(name: String): JSONArray {
    require(has(name) && !isNull(name) && get(name) is JSONArray) {
        "Prompt alignment field $name must be an array."
    }
    return getJSONArray(name)
}

private fun JSONObject.requiredInt(name: String): Int {
    require(has(name) && !isNull(name) && get(name) is Number) {
        "Prompt alignment field $name must be an integer."
    }
    val number = get(name) as Number
    val longValue = number.toLong()
    require(number.toDouble().isFinite() && number.toDouble() == longValue.toDouble()) {
        "Prompt alignment field $name must be an integer."
    }
    require(longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Prompt alignment field $name must be a 32-bit integer."
    }
    return longValue.toInt()
}

private fun JSONObject.requiredString(name: String): String {
    require(has(name) && !isNull(name) && get(name) is String) {
        "Prompt alignment field $name must be a string."
    }
    return getString(name)
}

private fun JSONArray.requiredObject(index: Int, expectedFields: Set<String>): JSONObject {
    require(get(index) is JSONObject) { "Prompt alignment array item must be an object." }
    return getJSONObject(index).also { value ->
        require(value.keys().asSequence().toSet() == expectedFields) {
            "Prompt alignment array item contains unsupported fields."
        }
    }
}

private fun String.isVisibleAsciiAlignmentText(): Boolean =
    isNotEmpty() && all { character -> character.code in 0x20..0x7e }

private fun <T> List<T>.shuffledWith(entropy: LocalImagePromptAlignmentEntropy): List<T> {
    val result = toMutableList()
    for (index in result.lastIndex downTo 1) {
        val randomBytes = ByteArray(4)
        entropy.nextBytes(randomBytes)
        val unsigned = ((randomBytes[0].toLong() and 0xffL) shl 24) or
            ((randomBytes[1].toLong() and 0xffL) shl 16) or
            ((randomBytes[2].toLong() and 0xffL) shl 8) or
            (randomBytes[3].toLong() and 0xffL)
        val target = (unsigned % (index + 1).toLong()).toInt()
        val current = result[index]
        result[index] = result[target]
        result[target] = current
    }
    return result.toList()
}

private fun ByteArray.toLowerHex(): String = joinToString("") { byte ->
    HEX[(byte.toInt() ushr 4) and 0x0f].toString() + HEX[byte.toInt() and 0x0f]
}

private const val OPAQUE_ID_BYTES = 16
private const val MAX_OPAQUE_ID_ATTEMPTS = 32
private const val MAX_ALIGNMENT_CLAUSE_COUNT = 256
private const val MAX_ALIGNMENT_SOURCE_CLAUSE_CHARS = 4_096
private const val MAX_ALIGNMENT_SOURCE_CHARS = 16_384
private const val MAX_ALIGNMENT_TRANSLATED_CLAUSE_CHARS = 4_096
private const val MAX_ALIGNMENT_TRANSLATED_CHARS = 16_384
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val OPAQUE_ID_PATTERN = Regex("[asc]_[0-9a-f]{32}")
private val PHASE_A_ROOT_FIELDS = setOf("contract_version", "translations")
private val PHASE_A_ITEM_FIELDS = setOf("slot_id", "translation")
private val PHASE_B_ROOT_FIELDS = setOf("contract_version", "matches")
private val PHASE_B_ITEM_FIELDS = setOf("source_id", "candidate_id", "verdict")
private const val PHASE_B_EQUIVALENT_VERDICT = "equivalent"
private val HEX = "0123456789abcdef".toCharArray()
