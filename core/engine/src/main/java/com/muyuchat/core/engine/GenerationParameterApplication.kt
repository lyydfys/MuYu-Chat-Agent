package com.muyuchat.core.engine

import com.geniex.sdk.bean.GenerationConfig
import org.json.JSONArray
import org.json.JSONObject

private val GENERATION_TRACE_FIELDS = listOf(
    "n_predict", "temperature", "top_k", "top_p", "min_p", "repeat_penalty",
    "presence_penalty", "frequency_penalty", "seed", "stop_words", "reasoning_mode",
    "enable_thinking", "thinking_budget", "hide_reasoning"
)

/** Records the adapter boundary; submitted values are never treated as runtime acknowledgement. */
internal fun generationParameterApplication(
    runtime: LocalChatRuntime,
    backend: String,
    requestedJson: String,
    submitted: JSONObject,
    source: String,
    ignored: Map<String, String> = emptyMap(),
    normalizedReasons: Map<String, String> = emptyMap(),
    acknowledged: JSONObject = JSONObject(),
    submissionState: String = "submitted"
): JSONObject {
    val raw = JSONObject(requestedJson)
    val requested = JSONObject()
    GENERATION_TRACE_FIELDS.forEach { field ->
        val value = when (field) {
            "n_predict" -> raw.opt(field) ?: raw.opt("max_tokens")
            "repeat_penalty" -> raw.opt(field) ?: raw.opt("repetition_penalty")
            "stop_words" -> raw.opt(field) ?: raw.opt("stop")
            else -> raw.opt(field)
        }
        if (value != null || field == "seed") requested.put(field, value ?: JSONObject.NULL)
    }
    val fields = JSONArray()
    requested.keys().forEach { field ->
        val hostReason = when (field) {
            "reasoning_mode" -> "mapped_to_template_and_thinking_configuration"
            "hide_reasoning" -> "mca_reasoning_visibility_filter"
            "stop_words" -> "mca_stream_stop_matcher"
            else -> null
        }
        val ignoredReason = ignored[field]
        val hasSubmitted = submitted.has(field)
        val hasAcknowledgement = acknowledged.has(field) && !acknowledged.isNull(field)
        val changed = hasSubmitted && !sameParameterValue(requested.opt(field), submitted.opt(field))
        val disposition = when {
            ignoredReason != null -> "ignored"
            hostReason != null -> "host_enforced"
            !hasSubmitted -> "unknown"
            changed -> "normalized"
            else -> "submitted"
        }
        fields.put(JSONObject()
            .put("field", field)
            .put("requested", requested.opt(field) ?: JSONObject.NULL)
            .put("submitted", if (hasSubmitted) submitted.opt(field) else JSONObject.NULL)
            .put("nativeAcknowledged", if (hasAcknowledgement) acknowledged.opt(field) else JSONObject.NULL)
            .put("disposition", disposition)
            .put("support", when {
                ignoredReason != null -> ParameterExecutionSupport.UNSUPPORTED.name
                hostReason != null -> ParameterExecutionSupport.HOST_ENFORCED.name
                hasSubmitted -> ParameterExecutionSupport.NATIVE.name
                else -> ParameterExecutionSupport.UNKNOWN.name
            })
            .put("reason", ignoredReason ?: hostReason ?: normalizedReasons[field]
                ?: if (hasAcknowledgement) "runtime_readback" else if (hasSubmitted) {
                    "submitted_without_native_readback"
                } else "adapter_did_not_submit_field"))
    }
    return JSONObject()
        .put("schemaVersion", 1)
        .put("runtime", runtime.backendId)
        .put("backend", backend)
        .put("source", source)
        .put("submissionState", submissionState)
        .put("requested", requested)
        .put("submitted", submitted)
        .put("nativeAcknowledged", acknowledged)
        .put("fields", fields)
}

private fun sameParameterValue(first: Any?, second: Any?): Boolean = when {
    first is Number && second is Number -> first.toDouble() == second.toDouble()
    else -> first?.toString() == second?.toString()
}

internal fun GenerationParams.requireFiniteSamplerValues() {
    mapOf(
        "temperature" to temperature, "top_p" to topP, "min_p" to minP,
        "repeat_penalty" to repeatPenalty, "presence_penalty" to presencePenalty,
        "frequency_penalty" to frequencyPenalty
    ).forEach { (field, value) -> require(value.isFinite()) { "$field must be finite." } }
}

internal fun nativeGenerationParameterApplication(
    runtime: LocalChatRuntime,
    requestedJson: String,
    stats: JSONObject
): JSONObject {
    val submitted = JSONObject()
    val acknowledged = JSONObject()
    val isMnn = runtime == LocalChatRuntime.MNN_CPU
    val lastConfig = stats.optJSONObject("lastConfigJson")
        ?: stats.optString("lastConfigJson").takeIf(String::isNotBlank)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
    val sampler = stats.optJSONObject("samplerConfig")
    val aliases = mapOf(
        "temperature" to "temperature", "top_k" to "topK", "top_p" to "topP", "min_p" to "minP",
        "repeat_penalty" to "repeatPenalty", "presence_penalty" to "presencePenalty",
        "frequency_penalty" to "frequencyPenalty", "n_predict" to "maxNewTokens",
        "enable_thinking" to "enableThinking"
    )
    GENERATION_TRACE_FIELDS.filterNot { it in setOf("reasoning_mode", "hide_reasoning", "stop_words") }
        .forEach { field ->
            val nativeKey = if (field == "repeat_penalty") "repetition_penalty" else field
            val submittedValue = if (isMnn) {
                sampler?.opt(nativeKey) ?: lastConfig?.opt(nativeKey)
                    ?: if (field == "n_predict") lastConfig?.opt("max_new_tokens") else null
                    ?: if (field == "enable_thinking") {
                        lastConfig?.optJSONObject("jinja")?.optJSONObject("context")
                            ?.opt("enable_thinking")
                    } else if (field == "thinking_budget") {
                        lastConfig?.optJSONObject("jinja")?.optJSONObject("context")
                            ?.opt("thinking_budget")
                    } else null
            } else aliases[field]?.let(stats::opt)
            val acknowledgedValue = if (isMnn) sampler?.opt(nativeKey)
                else aliases[field]?.let(stats::opt)
            if (submittedValue != null && submittedValue != JSONObject.NULL) {
                submitted.put(field, submittedValue)
            }
            if (acknowledgedValue != null && acknowledgedValue != JSONObject.NULL) {
                acknowledged.put(field, acknowledgedValue)
            }
        }
    return generationParameterApplication(
        runtime = runtime,
        backend = stats.optString(
            "backendMode",
            stats.optString("backendType", runtime.backendId)
        ),
        requestedJson = requestedJson,
        submitted = submitted,
        acknowledged = acknowledged,
        source = if (isMnn) "mnn-jni-config-and-sampler-readback" else "llama-jni-sampler-readback",
        normalizedReasons = mapOf("seed" to "null_seed_uses_native_random_seed")
    )
}

internal fun genieXGenerationParameterApplication(
    requestedJson: String,
    config: GenerationConfig,
    templateEnableThinking: Boolean,
    runtime: LocalChatRuntime = LocalChatRuntime.GENIEX_QAIRT,
    attempt: Int = 1
): JSONObject {
    val sampler = config.samplerConfig
    val submitted = JSONObject()
        .put("n_predict", config.maxTokens)
        .apply {
            sampler?.let {
                put("temperature", it.temperature)
                put("top_k", it.topK)
                put("top_p", it.topP)
                put("min_p", it.minP)
                put("repeat_penalty", it.repetitionPenalty)
                put("presence_penalty", it.presencePenalty)
                put("frequency_penalty", it.frequencyPenalty)
                put("seed", it.seed)
            }
        }
        .put("stop_words", JSONArray(config.stopWords.orEmpty().take(config.stopCount.coerceAtLeast(0))))
        .put("enable_thinking", templateEnableThinking)
    return generationParameterApplication(
        runtime = runtime,
        backend = "geniex",
        requestedJson = requestedJson,
        submitted = submitted,
        ignored = mapOf("thinking_budget" to "geniex-0.3.12_exposes_template_enable_thinking_without_budget"),
        source = "geniex-0.3.12:GenerationConfig",
        normalizedReasons = mapOf(
            "seed" to "null_seed_uses_geniex_default_zero",
            "enable_thinking" to "applied_to_native_chat_template",
            "stop_words" to "submitted_to_geniex_generation_config",
            "temperature" to if (attempt > 1) "greedy_collapse_rescue" else "sdk_value",
            "top_k" to if (attempt > 1) "greedy_collapse_rescue" else "sdk_value",
            "top_p" to if (attempt > 1) "greedy_collapse_rescue" else "sdk_value"
        )
    ).put("attempt", attempt)
}
