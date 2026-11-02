package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParamOverrideFields
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONArray
import org.json.JSONObject

internal const val ASSISTANT_GENERATION_OVERRIDE_FIELDS_KEY = "override_fields"

/** Assistant cards own generation semantics, never model/runtime execution fields. */
internal fun GenerationParams.toAssistantGenerationJson(): String =
    JSONObject()
        .put("schema", "mca.assistant.generation.v2")
        .put("n_predict", nPredict.coerceAtLeast(1))
        .put("max_tokens", nPredict.coerceAtLeast(1))
        .put("temperature", temperature)
        .put("top_k", topK)
        .put("top_p", topP)
        .put("min_p", minP)
        .put("repeat_penalty", repeatPenalty)
        .put("repetition_penalty", repeatPenalty)
        .put("presence_penalty", presencePenalty)
        .put("frequency_penalty", frequencyPenalty)
        .put("system_prompt", systemPrompt)
        .put("stop_words", JSONArray(stopWords))
        .put("reasoning_mode", reasoningMode.name.lowercase())
        .put("enable_thinking", reasoningMode != ReasoningMode.OFF && !hideReasoning)
        .put("hide_reasoning", hideReasoning)
        .apply { seed?.let { put("seed", it) } }
        .toString()

internal fun GenerationParams.toAssistantGenerationJsonWithOverrides(
    overrideFields: Set<String>
): String = JSONObject(toAssistantGenerationJson())
    .put(
        ASSISTANT_GENERATION_OVERRIDE_FIELDS_KEY,
        JSONArray(
            overrideFields
                .filter { it in GenerationParamOverrideFields.semanticFields }
                .sorted()
        )
    )
    .toString()

/** Returns null for cards written before field-level overrides existed. */
internal fun assistantGenerationOverrideFieldsOrNull(json: String): Set<String>? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val values = root.optJSONArray(ASSISTANT_GENERATION_OVERRIDE_FIELDS_KEY) ?: return null
    return buildSet {
        for (index in 0 until values.length()) {
            values.optString(index)
                .takeIf { it in GenerationParamOverrideFields.semanticFields }
                ?.let(::add)
        }
    }
}

internal fun assistantGenerationParamsFromJson(
    json: String,
    defaults: GenerationParams,
    systemPrompt: String
): GenerationParams {
    val parsed = GenerationParams.fromJson(json, defaults)
    return defaults.copy(
        nPredict = parsed.nPredict,
        temperature = parsed.temperature,
        topK = parsed.topK,
        topP = parsed.topP,
        minP = parsed.minP,
        repeatPenalty = parsed.repeatPenalty,
        presencePenalty = parsed.presencePenalty,
        frequencyPenalty = parsed.frequencyPenalty,
        seed = parsed.seed,
        systemPrompt = systemPrompt.ifBlank { parsed.systemPrompt.ifBlank { defaults.systemPrompt } },
        stopWords = parsed.stopWords,
        reasoningMode = parsed.reasoningMode,
        hideReasoning = parsed.hideReasoning
    )
}

/**
 * Resolves a role card on top of the model preset.  Cards with override metadata apply only
 * their explicit fields; legacy cards keep their full snapshot so existing users see no change.
 */
internal fun assistantGenerationParamsResolved(
    json: String,
    modelDefaults: GenerationParams,
    systemPrompt: String
): GenerationParams {
    val overrideFields = assistantGenerationOverrideFieldsOrNull(json)
        ?: return assistantGenerationParamsFromJson(json, modelDefaults, systemPrompt)
    val base = modelDefaults.copy(systemPrompt = systemPrompt)
    if (overrideFields.isEmpty()) return base
    val stored = GenerationParams.fromJson(json, modelDefaults)
    return GenerationParamOverrideFields.apply(base, stored, overrideFields)
        .copy(systemPrompt = systemPrompt)
}

internal fun sanitizeAssistantParamsJson(
    json: String,
    defaults: GenerationParams,
    systemPrompt: String
): String = assistantGenerationParamsFromJson(json, defaults, systemPrompt).toAssistantGenerationJson()

/** Copies only values the user actually changed over an existing model preset. */
internal fun GenerationParams.withChangedFieldsFrom(
    previous: GenerationParams,
    candidate: GenerationParams
): GenerationParams = copy(
    nCtx = if (previous.nCtx != candidate.nCtx) candidate.nCtx else nCtx,
    nPredict = if (previous.nPredict != candidate.nPredict) candidate.nPredict else nPredict,
    nThreads = if (previous.nThreads != candidate.nThreads) candidate.nThreads else nThreads,
    temperature = if (previous.temperature != candidate.temperature) candidate.temperature else temperature,
    topK = if (previous.topK != candidate.topK) candidate.topK else topK,
    topP = if (previous.topP != candidate.topP) candidate.topP else topP,
    minP = if (previous.minP != candidate.minP) candidate.minP else minP,
    repeatPenalty = if (previous.repeatPenalty != candidate.repeatPenalty) candidate.repeatPenalty else repeatPenalty,
    presencePenalty = if (previous.presencePenalty != candidate.presencePenalty) candidate.presencePenalty else presencePenalty,
    frequencyPenalty = if (previous.frequencyPenalty != candidate.frequencyPenalty) candidate.frequencyPenalty else frequencyPenalty,
    seed = if (previous.seed != candidate.seed) candidate.seed else seed,
    stopWords = if (previous.stopWords != candidate.stopWords) candidate.stopWords else stopWords,
    chatTemplateMode = if (previous.chatTemplateMode != candidate.chatTemplateMode) candidate.chatTemplateMode else chatTemplateMode,
    advancedJson = if (previous.advancedJson != candidate.advancedJson) candidate.advancedJson else advancedJson,
    reasoningMode = if (previous.reasoningMode != candidate.reasoningMode) candidate.reasoningMode else reasoningMode,
    hideReasoning = if (previous.hideReasoning != candidate.hideReasoning) candidate.hideReasoning else hideReasoning
)
