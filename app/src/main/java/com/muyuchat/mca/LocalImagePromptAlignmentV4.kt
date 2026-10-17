package com.muyuchat.mca

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

internal enum class LocalImagePromptAlignmentSystemPhase(val wireName: String) {
    TRANSLATION("translation"),
    VERIFICATION("verification")
}

/** Two-phase opaque-slot planner. One instance may safely be scoped to one translation request. */
internal class LocalImagePromptAlignmentV4(
    internal val entropy: LocalImagePromptAlignmentEntropy =
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
private fun JSONObject.requiredArray(name: String): JSONArray {
    require(has(name) && !isNull(name) && get(name) is JSONArray) {
        "Prompt alignment field $name must be an array."
    }
    return getJSONArray(name)
}
private fun JSONObject.requiredString(name: String): String {
    require(has(name) && !isNull(name) && get(name) is String) {
        "Prompt alignment field $name must be a string."
    }
    return getString(name)
}
