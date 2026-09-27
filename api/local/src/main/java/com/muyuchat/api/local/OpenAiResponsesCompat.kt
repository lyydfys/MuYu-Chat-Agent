package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalResponsesRequest(
    val model: String,
    val chatRequest: ChatRequest,
    val streaming: Boolean
)

internal sealed interface LocalResponsesParseResult {
    data class Success(val request: LocalResponsesRequest) : LocalResponsesParseResult
    data class Rejected(val rejection: OpenAiRequestRejection) : LocalResponsesParseResult
}

/** Validates the original Responses JSON before the more permissive shared chat parser sees it. */
internal object LocalResponsesCompat {
    val acceptedFields = listOf(
        "model", "instructions", "input", "stream", "max_output_tokens", "temperature",
        "top_p", "stop"
    )

    private const val MAX_TEXT_LENGTH = 1_048_576
    private const val MAX_MESSAGES = 128
    private const val MAX_PARTS = 128

    fun parseRequest(
        body: String,
        baseParams: GenerationParams,
        streaming: Boolean = OpenAiApiCompat.isStreamingRequest(body)
    ): LocalResponsesParseResult {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return reject("Request body must be a valid JSON object.", "body")
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
        root.keys().asSequence().firstOrNull { it !in acceptedFields }?.let { return unsupported(it) }
        val model = root.opt("model") as? String
        if (model.isNullOrBlank() || model.length > 256) {
            return reject("'model' must be a non-empty string of at most 256 characters.", "model")
        }
        val input = root.opt("input")
        if (input !is String && input !is JSONArray) {
            return reject("'input' must be text or an array of text messages.", "input")
        }
        when (input) {
            is String -> if (input.isBlank() || input.length > MAX_TEXT_LENGTH) {
                return reject("'input' must contain at most $MAX_TEXT_LENGTH characters of non-empty text.", "input")
            }
            is JSONArray -> {
                if (input.length() !in 1..MAX_MESSAGES) {
                    return reject("'input' must contain 1 to $MAX_MESSAGES messages.", "input")
                }
                var totalLength = 0L
                for (index in 0 until input.length()) {
                    val message = input.opt(index) as? JSONObject
                        ?: return reject("Each input message must be an object.", "input[$index]")
                    message.keys().asSequence().firstOrNull { it != "role" && it != "content" }
                        ?.let { return unsupported("input[$index].$it") }
                    if (message.opt("role") !in listOf("system", "developer", "user", "assistant")) {
                        return reject("Unsupported input message role.", "input[$index].role")
                    }
                    val content = message.opt("content")
                    when (content) {
                        is String -> {
                            if (content.isBlank()) return reject("Message content must not be empty.", "input[$index].content")
                            totalLength += content.length
                        }
                        is JSONArray -> {
                            if (content.length() !in 1..MAX_PARTS) {
                                return reject("Message content must contain 1 to $MAX_PARTS text parts.", "input[$index].content")
                            }
                            for (partIndex in 0 until content.length()) {
                                val path = "input[$index].content[$partIndex]"
                                val part = content.opt(partIndex) as? JSONObject
                                    ?: return reject("Each content part must be an object.", path)
                                part.keys().asSequence().firstOrNull { it != "type" && it != "text" }
                                    ?.let { return unsupported("$path.$it") }
                                if (part.opt("type") != "input_text") return unsupported("$path.type")
                                val text = part.opt("text") as? String
                                    ?: return reject("Text parts require a string 'text'.", "$path.text")
                                if (text.isBlank()) return reject("Text parts must not be empty.", "$path.text")
                                totalLength += text.length
                            }
                        }
                        else -> return reject("Message content must be text or text parts.", "input[$index].content")
                    }
                    if (totalLength > MAX_TEXT_LENGTH) {
                        return reject("Input text exceeds $MAX_TEXT_LENGTH characters.", "input")
                    }
                }
            }
        }
        if (root.has("instructions")) {
            val value = root.opt("instructions") as? String
                ?: return reject("'instructions' must be a string.", "instructions")
            if (value.length > MAX_TEXT_LENGTH) return reject("'instructions' is too long.", "instructions")
        }
        if (root.has("stream") && root.opt("stream") !is Boolean) {
            return reject("'stream' must be a boolean.", "stream")
        }
        if (root.has("max_output_tokens")) {
            val value = root.opt("max_output_tokens") as? Number
            if (value == null || value.toDouble() % 1.0 != 0.0 || value.toLong() !in 1..65_536) {
                return reject("'max_output_tokens' must be an integer from 1 to 65536.", "max_output_tokens")
            }
        }
        for ((field, range) in listOf("temperature" to 0.0..2.0, "top_p" to 0.0..1.0)) {
            if (root.has(field)) {
                val value = (root.opt(field) as? Number)?.toDouble()
                if (value == null || !value.isFinite() || value !in range || field == "top_p" && value == 0.0) {
                    return reject("'$field' is outside its supported range.", field)
                }
            }
        }
        if (root.has("stop")) {
            val value = root.opt("stop")
            val stops = when (value) {
                is String -> listOf(value)
                is JSONArray -> (0 until value.length()).map { value.opt(it) as? String
                    ?: return reject("'stop' must contain only strings.", "stop[$it]") }
                else -> return reject("'stop' must be a string or an array of strings.", "stop")
            }
            if (stops.size !in 1..8 || stops.any { it.isEmpty() || it.length > 256 }) {
                return reject("'stop' supports 1 to 8 non-empty strings of at most 256 characters.", "stop")
            }
        }
        val translated = JSONObject()
            .put("model", model.trim())
            .put("input", input)
            .put("stream", streaming)
        (root.opt("instructions") as? String)?.takeIf(String::isNotBlank)
            ?.let { translated.put("system_prompt", it) }
        if (root.has("max_output_tokens")) translated.put("max_tokens", root.opt("max_output_tokens"))
        listOf("temperature", "top_p", "stop")
            .forEach { field -> if (root.has(field)) translated.put(field, root.opt(field)) }
        return when (val parsed = OpenAiApiCompat.parseChatRequestChecked(
            body = translated.toString(),
            baseParams = baseParams,
            requireJsonObject = true,
            requireModel = true
        )) {
            is OpenAiChatParseResult.Success -> LocalResponsesParseResult.Success(
                LocalResponsesRequest(model.trim(), parsed.request, streaming)
            )
            is OpenAiChatParseResult.Rejected -> LocalResponsesParseResult.Rejected(parsed.rejection)
        }
    }

    private fun unsupported(param: String): LocalResponsesParseResult.Rejected =
        LocalResponsesParseResult.Rejected(
            OpenAiRequestRejection(
                code = "unsupported_parameter",
                message = "The Local API Responses text subset does not support '$param'.",
                detailsJson = JSONObject().put("param", param).toString(),
                httpStatus = 400
            )
        )

    private fun reject(message: String, param: String): LocalResponsesParseResult.Rejected =
        LocalResponsesParseResult.Rejected(
            OpenAiRequestRejection(
                code = "invalid_request",
                message = message,
                detailsJson = JSONObject().put("param", param).toString(),
                httpStatus = 400
            )
        )
}

/** Typed semantic events for the supported, single assistant text item lifecycle. */
internal class LocalResponsesEventEncoder(private val itemId: String) {
    private var nextSequence = 0L

    fun response(type: String, response: JSONObject): JSONObject = base(type).put("response", response)

    fun item(type: String, item: JSONObject): JSONObject = base(type)
        .put("output_index", 0)
        .put("item", item)

    fun part(type: String, part: JSONObject): JSONObject = base(type)
        .put("item_id", itemId)
        .put("output_index", 0)
        .put("content_index", 0)
        .put("part", part)

    fun textDelta(delta: String): JSONObject = textBase("response.output_text.delta").put("delta", delta)

    fun textDone(value: String): JSONObject = textBase("response.output_text.done").put("text", value)

    private fun textBase(type: String): JSONObject = base(type)
        .put("item_id", itemId)
        .put("output_index", 0)
        .put("content_index", 0)

    private fun base(type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("sequence_number", nextSequence++)
}
