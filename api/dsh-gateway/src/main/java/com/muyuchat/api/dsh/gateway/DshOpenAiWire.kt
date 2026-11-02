package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshGatewayModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One complete tool call assembled from streaming deltas. */
internal data class DshAssembledToolCall(
    val index: Int,
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * OpenAI wire encoding for the gateway.
 *
 * Streaming uses `chat.completion.chunk` objects terminated by `data: [DONE]`; the non-stream
 * path uses a `chat.completion` object. Reasoning travels on the `reasoning_content` delta field
 * (the field the official DSH pi-ai OpenAI-completions adapter reads first), tool arguments are
 * opaque concatenated strings, and `finish_reason` preserves every distinct MCA terminal state:
 * `stop`, `tool_calls`, `length`, plus the gateway's explicit `error` and `cancelled`.
 */
internal object DshOpenAiWire {

    const val DONE_FRAME: String = "data: [DONE]\n\n"

    fun frame(json: String): String = "data: $json\n\n"

    fun newCompletionId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")

    fun finishReason(reason: DshFinishReason): String = when (reason) {
        DshFinishReason.STOP -> "stop"
        DshFinishReason.TOOL_CALLS -> "tool_calls"
        DshFinishReason.LENGTH -> "length"
        DshFinishReason.ERROR -> "error"
        DshFinishReason.CANCELLED -> "cancelled"
    }

    fun roleChunk(id: String, created: Long, model: String): String = chunk(
        id = id,
        created = created,
        model = model,
        delta = JSONObject().put("role", "assistant"),
        finishReason = null
    )

    /**
     * An empty-delta chunk used as an idle keep-alive during a long local prefill.
     *
     * SSE comments do not reset every OpenAI client's stream watchdog (the official pi-ai adapter
     * aborts after 300 s without a data event), while a valid `delta: {}` chunk does. It carries no
     * content, no tool call and no finish_reason, so an assembler must ignore it.
     */
    fun emptyDeltaChunk(id: String, created: Long, model: String): String = chunk(
        id = id,
        created = created,
        model = model,
        delta = JSONObject(),
        finishReason = null
    )

    fun contentChunk(id: String, created: Long, model: String, text: String): String = chunk(
        id = id,
        created = created,
        model = model,
        delta = JSONObject().put("content", text),
        finishReason = null
    )

    fun reasoningChunk(id: String, created: Long, model: String, text: String): String = chunk(
        id = id,
        created = created,
        model = model,
        delta = JSONObject().put("reasoning_content", text),
        finishReason = null
    )

    fun toolCallChunk(
        id: String,
        created: Long,
        model: String,
        index: Int,
        toolCallId: String?,
        name: String?,
        argumentsFragment: String?
    ): String {
        val call = JSONObject().put("index", index).put("type", "function")
        if (toolCallId != null) call.put("id", toolCallId)
        val function = JSONObject()
        if (name != null) function.put("name", name)
        if (argumentsFragment != null) function.put("arguments", argumentsFragment)
        if (function.length() > 0) call.put("function", function)
        return chunk(
            id = id,
            created = created,
            model = model,
            delta = JSONObject().put("tool_calls", JSONArray().put(call)),
            finishReason = null
        )
    }

    fun finishChunk(
        id: String,
        created: Long,
        model: String,
        finishReason: String
    ): String = chunk(
        id = id,
        created = created,
        model = model,
        delta = JSONObject(),
        finishReason = finishReason
    )

    /** Final usage-only chunk; null when the backend reported no token counts at all. */
    fun usageChunk(
        id: String,
        created: Long,
        model: String,
        promptTokens: Int?,
        completionTokens: Int?
    ): String? {
        val usage = usageJson(promptTokens, completionTokens) ?: return null
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray())
            .put("usage", usage)
            .toString()
    }

    fun errorChunk(type: String, code: String, message: String): String {
        val error = JSONObject()
            .put("message", message)
            .put("type", type)
            .put("code", code)
        return JSONObject().put("error", error).toString()
    }

    fun completionJson(
        id: String,
        created: Long,
        model: String,
        content: String,
        reasoning: String,
        toolCalls: List<DshAssembledToolCall>,
        finishReason: DshFinishReason,
        promptTokens: Int?,
        completionTokens: Int?
    ): String {
        val message = JSONObject().put("role", "assistant")
        val contentValue: Any = if (content.isEmpty() && toolCalls.isNotEmpty()) JSONObject.NULL else content
        message.put("content", contentValue)
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning)
        if (toolCalls.isNotEmpty()) {
            val array = JSONArray()
            toolCalls.sortedBy { it.index }.forEach { call ->
                array.put(
                    JSONObject()
                        .put("id", call.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", call.name)
                                .put("arguments", call.argumentsJson)
                        )
                )
            }
            message.put("tool_calls", array)
        }
        val response = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", finishReason(finishReason))
                )
            )
        usageJson(promptTokens, completionTokens)?.let { response.put("usage", it) }
        return response.toString()
    }

    /**
     * Model listing in the shape the official DSH model-discovery parser reads (`data` array,
     * `name`, `context_window`, `max_output_tokens`).
     *
     * Honest tool-mode labelling: a model that only supports the prompted tool protocol never
     * advertises native tool calling, and its user-visible listing name carries the bracketed
     * protocol label. `mca_display_name` keeps MCA's unlabelled display name for the host.
     *
     * ECO-5: every entry also carries a non-breaking `mca_context` sidecar and the listing root a
     * `mca_context_warnings` array. A row without a declared context window cannot be compacted by
     * the official backend; the sidecar records that fact instead of leaving an unusable row
     * silently indistinguishable from a healthy one. Official field names and values are unchanged.
     */
    fun modelsJson(models: List<DshGatewayModel>, catalogRevision: String, created: Long): String {
        val data = JSONArray()
        models.forEach { model ->
            val promptCompat = model.supportsTools && !model.nativeToolCalling
            val declaredLabel = model.toolProtocolLabel
            val effectiveLabel = when {
                !declaredLabel.isNullOrBlank() -> declaredLabel.trim()
                promptCompat -> "prompt-compat"
                else -> null
            }
            val listingName = if (promptCompat && effectiveLabel != null) {
                "${model.displayName} [$effectiveLabel]"
            } else {
                model.displayName
            }
            val entry = JSONObject()
                .put("id", model.id)
                .put("object", "model")
                .put("created", created)
                .put("owned_by", "mca")
                .put("name", listingName)
                .put("display_name", listingName)
                .put("mca_display_name", model.displayName)
                .put("supports_tools", model.supportsTools)
                .put("native_tool_calling", model.nativeToolCalling)
            model.contextWindow?.takeIf { it > 0 }?.let {
                entry.put("context_window", it)
                entry.put("context_length", it)
            }
            model.maxOutputTokens?.takeIf { it > 0 }?.let {
                entry.put("max_output_tokens", it)
                entry.put("max_tokens", it)
            }
            if (effectiveLabel != null) entry.put("tool_protocol_label", effectiveLabel)
            // D09 honesty: one backend/model/transport capability row per served model. Official
            // DSH clients ignore unknown fields; the MCA console reads the same row the gateway
            // routing uses, so an unsupported vision/reasoning capability is never assumed.
            entry.put(
                "mca_capabilities",
                DshBackendCapabilityMatrix.toJson(DshBackendCapabilityMatrix.describe(model))
            )
            // ECO-5: typed compaction-capacity sidecar (see DshContextCapacity). Additive only.
            entry.put(
                "mca_context",
                DshContextCapacityProbe.toJson(DshContextCapacityProbe.of(model))
            )
            data.put(entry)
        }
        return JSONObject()
            .put("object", "list")
            .put("data", data)
            .put("mca_catalog_revision", catalogRevision)
            .put("mca_context_warnings", DshContextCapacityProbe.warningsJson(models))
            .toString()
    }

    fun usageJson(promptTokens: Int?, completionTokens: Int?): JSONObject? {
        if (promptTokens == null && completionTokens == null) return null
        return JSONObject()
            .put("prompt_tokens", promptTokens ?: JSONObject.NULL)
            .put("completion_tokens", completionTokens ?: JSONObject.NULL)
            .put(
                "total_tokens",
                if (promptTokens != null && completionTokens != null) {
                    promptTokens + completionTokens
                } else {
                    JSONObject.NULL
                }
            )
    }

    private fun chunk(
        id: String,
        created: Long,
        model: String,
        delta: JSONObject,
        finishReason: String?
    ): String = JSONObject()
        .put("id", id)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", model)
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", delta)
                    .put("finish_reason", finishReason ?: JSONObject.NULL)
            )
        )
        .toString()
}
