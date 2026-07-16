package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import org.json.JSONArray
import org.json.JSONObject

internal fun restoreGenerationParams(
    semanticJson: String?,
    runtimeJson: String?
): GenerationParams {
    val parsed = semanticJson?.let { GenerationParams.fromJson(it) } ?: GenerationParams()
    val semanticParams = assistantGenerationParamsFromJson(
        json = semanticJson ?: GenerationParams().toAssistantGenerationJson(),
        defaults = GenerationParams(),
        systemPrompt = parsed.systemPrompt
    )
    return runtimeJson?.let { GenerationParams.fromJson(it, semanticParams) } ?: semanticParams
}

internal fun runtimeParameterChanges(
    before: GenerationParams,
    after: GenerationParams
): Set<String> {
    val beforeJson = runtimeParameterDocument(before)
    val afterJson = runtimeParameterDocument(after)
    val fields = buildSet {
        beforeJson.keys().forEachRemaining(::add)
        afterJson.keys().forEachRemaining(::add)
    } - setOf("schema", "advanced_json")
    return fields.filterTo(linkedSetOf()) { field ->
        runtimeCanonicalJsonValue(beforeJson.opt(field)) != runtimeCanonicalJsonValue(afterJson.opt(field))
    }
}

internal fun runtimeParameterDocument(params: GenerationParams): JSONObject {
    val advanced = LlamaAdvancedParams.parse(params.advancedJson)
    return JSONObject()
        .put("schema", "mca.runtime.parameters.v1")
        .put("n_ctx", params.nCtx)
        .put("n_threads", params.nThreads)
        .put("chat_template_mode", params.chatTemplateMode)
        .apply {
            advanced.putCanonicalFields(this)
            val advancedObject = advanced.advancedJsonValue()
            put("advanced_json", advancedObject)
            if (advancedObject is JSONObject) {
                advancedObject.keys().forEachRemaining { field ->
                    if (!has(field)) put(field, advancedObject.opt(field))
                }
            }
        }
}

private fun runtimeCanonicalJsonValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
        prefix = "{",
        postfix = "}"
    ) { key -> "$key:${runtimeCanonicalJsonValue(value.opt(key))}" }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
        runtimeCanonicalJsonValue(value.opt(index))
    }
    is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        ?: value.toString()
    else -> value.toString()
}
