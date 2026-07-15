package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONArray
import org.json.JSONObject

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

internal fun sanitizeAssistantParamsJson(
    json: String,
    defaults: GenerationParams,
    systemPrompt: String
): String = assistantGenerationParamsFromJson(json, defaults, systemPrompt).toAssistantGenerationJson()
