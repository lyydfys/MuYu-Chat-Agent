package com.muyuchat.mca

import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：V4 对齐协议的契约类型（版本常量、角色/来源/系统阶段枚举、
 * ClauseSet / Entropy / Plan / Result / Challenge / Receipt）自 LocalImagePromptAlignmentV4.kt
 * 逐字节平移（全部已是 internal，零改写）。
 */

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

