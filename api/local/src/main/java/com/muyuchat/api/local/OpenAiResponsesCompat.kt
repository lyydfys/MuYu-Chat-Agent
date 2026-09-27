package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import org.json.JSONArray
import org.json.JSONObject

/** Parsed request for the local OpenAI Responses-compatible endpoint. */
internal data class LocalResponsesRequest(
    val model: String,
    val chatRequest: ChatRequest,
    val streaming: Boolean
)

internal sealed interface LocalResponsesParseResult {
    data class Success(val request: LocalResponsesRequest) : LocalResponsesParseResult
    data class Rejected(val rejection: OpenAiRequestRejection) : LocalResponsesParseResult
}

/**
 * The local Responses endpoint deliberately translates only the text/message subset into the
 * same ChatRequest path used by Chat Completions. This keeps model admission, generation-only
 * parameter policy, cancellation and usage accounting identical across both protocols.
 */
internal object LocalResponsesCompat {
    fun parseRequest(
        body: String,
        baseParams: GenerationParams
    ): LocalResponsesParseResult {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return reject(
                message = "Request body must be a valid JSON object.",
                param = "body"
            )
        }
        val model = root.opt("model")
        if (model !is String || model.trim().isBlank()) {
            return reject(
                message = "The request body must include a non-empty string 'model'.",
                param = "model"
            )
        }
        if (!root.has("input") || root.isNull("input")) {
            return reject(
                message = "The request body must include a non-empty 'input'.",
                param = "input"
            )
        }
        if (root.inputIsEmpty()) {
            return reject(
                message = "The request body's 'input' must not be empty.",
                param = "input"
            )
        }
        val restrictedFields = OpenAiApiCompat.restrictedParameterPaths(root)
        if (restrictedFields.isNotEmpty()) {
            return LocalResponsesParseResult.Rejected(
                OpenAiRequestRejection(
                    code = "parameter_scope_conflict",
                    message = "Local API Responses requests may only override generation parameters. " +
                        "Model load, execution, template, GPU/MoE/MTP, and native advanced fields must be applied through an authorized runtime profile.",
                    detailsJson = JSONObject()
                        .put("allowed_scope", "generation_only")
                        .put("restricted_fields", JSONArray(restrictedFields))
                        .toString()
                )
            )
        }

        val translated = JSONObject()
            .put("model", model.trim())
            .put("input", root.opt("input"))
            .put("stream", OpenAiApiCompat.isStreamingRequest(body))
        root.optString("instructions", "")
            .takeIf(String::isNotBlank)
            ?.let { translated.put("system_prompt", it) }
        if (root.has("max_output_tokens") && !root.isNull("max_output_tokens")) {
            translated.put("max_tokens", root.opt("max_output_tokens"))
        }
        listOf(
            "temperature",
            "top_p",
            "stop",
            "reasoning_mode",
            "thinking_mode",
            "enable_thinking",
            "hide_reasoning"
        ).forEach { key ->
            if (root.has(key) && !root.isNull(key)) translated.put(key, root.opt(key))
        }

        val parsed = OpenAiApiCompat.parseChatRequestChecked(
            body = translated.toString(),
            baseParams = baseParams,
            requireJsonObject = true,
            requireModel = true
        )
        return when (parsed) {
            is OpenAiChatParseResult.Success -> {
                if (parsed.request.messages.none { it.content.isNotBlank() || it.imageAttachments.isNotEmpty() }) {
                    reject(
                        message = "The request body's 'input' must contain text or an image.",
                        param = "input"
                    )
                } else {
                    LocalResponsesParseResult.Success(
                        LocalResponsesRequest(
                            model = model.trim(),
                            chatRequest = parsed.request,
                            streaming = OpenAiApiCompat.isStreamingRequest(body)
                        )
                    )
                }
            }
            is OpenAiChatParseResult.Rejected -> LocalResponsesParseResult.Rejected(parsed.rejection)
        }
    }

    private fun reject(message: String, param: String): LocalResponsesParseResult.Rejected =
        LocalResponsesParseResult.Rejected(
            OpenAiRequestRejection(
                code = "invalid_request",
                message = message,
                detailsJson = JSONObject().put("param", param).toString(),
                httpStatus = 400
            )
        )

    private fun JSONObject.inputIsEmpty(): Boolean {
        val value = opt("input")
        return when (value) {
            is String -> value.isBlank()
            is JSONArray -> value.length() == 0 || (0 until value.length()).all { index ->
                val item = value.opt(index)
                when (item) {
                    is String -> item.isBlank()
                    is JSONObject -> item.contentIsEmpty()
                    else -> true
                }
            }
            is JSONObject -> value.contentIsEmpty()
            else -> true
        }
    }

    private fun JSONObject.contentIsEmpty(): Boolean {
        val content = opt("content")
        if (content is JSONArray) {
            return content.length() == 0 || (0 until content.length()).all { index ->
                val part = content.opt(index)
                when (part) {
                    is String -> part.isBlank()
                    is JSONObject -> part.optString("text", part.optString("content")).isBlank() &&
                        part.optString("image_url", part.optString("url")).isBlank()
                    else -> true
                }
            }
        }
        return content == null || content.toString().isBlank()
    }
}
