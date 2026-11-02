package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshModelMessage
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshModelRole
import com.muyuchat.core.dsh.contract.DshModelToolCall
import com.muyuchat.core.dsh.contract.DshModelToolDefinition
import com.muyuchat.core.dsh.contract.DshToolChoice
import org.json.JSONArray
import org.json.JSONObject

/** Result of mapping one OpenAI chat-completions body onto the frozen DSH model contract. */
internal sealed interface DshOpenAiParse {
    data class Success(
        val request: DshModelRequest,
        val includeUsage: Boolean
    ) : DshOpenAiParse

    data class Rejected(
        val status: Int,
        val type: String,
        val code: String,
        val message: String,
        val param: String? = null
    ) : DshOpenAiParse
}

/**
 * Strict mapper from the OpenAI chat-completions request shape the official DSH provider emits
 * onto the frozen [DshModelRequest] contract.
 *
 * Field fidelity is the point of this file: roles, assistant `tool_calls` (id/type/name/arguments
 * string), `tool_call_id`, tool definitions and `tool_choice` all survive the mapping; nothing is
 * flattened into user prose and no prompt text is added or rewritten.
 *
 * Known, documented lossy mapping (frozen contract limitation): a named
 * `tool_choice: {type:function, function:{name}}` is mapped to [DshToolChoice.REQUIRED] because
 * the contract has only AUTO/NONE/REQUIRED. The requested function is validated against `tools`
 * first; the serving adapter must apply the name pin itself when it needs it.
 */
internal object DshOpenAiRequest {

    private const val MAX_OUTPUT_TOKENS = 1_000_000

    fun parse(body: String, callId: String, limits: DshGatewayLimits): DshOpenAiParse {
        return try {
            mapRequest(body, callId, limits)
        } catch (failure: ParseFailure) {
            DshOpenAiParse.Rejected(
                status = failure.status,
                type = failure.type,
                code = failure.code,
                message = failure.message,
                param = failure.param
            )
        }
    }

    private fun mapRequest(body: String, callId: String, limits: DshGatewayLimits): DshOpenAiParse.Success {
        val root = DshGatewayJson.parseObject(body)
            ?: fail(400, "invalid_request_error", "invalid_request", "Request body must be a valid JSON object.", "body")
        val model = (root.opt("model") as? String)?.trim()
        if (model.isNullOrEmpty()) {
            fail(400, "invalid_request_error", "invalid_request", "The request must include a non-empty string 'model'.", "model")
        }
        val messages = parseMessages(root)
        val tools = parseTools(root, limits)
        val toolChoice = parseToolChoice(root, tools)
        validateUnsupportedFields(root)
        val temperature = parseTemperature(root)
        val maxTokens = parseMaxTokens(root)
        val stream = parseStream(root)
        val includeUsage = parseIncludeUsage(root)
        return DshOpenAiParse.Success(
            request = DshModelRequest(
                callId = callId,
                modelId = model,
                messages = messages,
                tools = tools,
                toolChoice = toolChoice,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = stream
            ),
            includeUsage = includeUsage
        )
    }

    private fun parseMessages(root: JSONObject): List<DshModelMessage> {
        if (!root.has("messages") || root.isNull("messages")) {
            fail(400, "invalid_request_error", "invalid_request", "The request must include a non-empty array 'messages'.", "messages")
        }
        val array = root.optJSONArray("messages")
            ?: fail(400, "invalid_request_error", "invalid_request", "'messages' must be an array.", "messages")
        if (array.length() == 0) {
            fail(400, "invalid_request_error", "invalid_request", "'messages' must not be empty.", "messages")
        }
        val messages = ArrayList<DshModelMessage>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: fail(
                    400, "invalid_request_error", "invalid_request",
                    "messages[$index] must be a JSON object.", "messages[$index]"
                )
            val roleText = (item.opt("role") as? String)?.trim()?.lowercase()
                ?: fail(
                    400, "invalid_request_error", "invalid_request",
                    "messages[$index].role must be a string.", "messages[$index].role"
                )
            val role = when (roleText) {
                "system", "developer" -> DshModelRole.SYSTEM
                "user" -> DshModelRole.USER
                "assistant" -> DshModelRole.ASSISTANT
                "tool" -> DshModelRole.TOOL
                "function" -> fail(
                    400, "invalid_request_error", "unsupported_role",
                    "Legacy role 'function' is not supported; use role 'tool'.", "messages[$index].role"
                )
                else -> fail(
                    400, "invalid_request_error", "unsupported_role",
                    "Unsupported message role '$roleText'.", "messages[$index].role"
                )
            }
            val content = parseContent(item, index)
            if (role != DshModelRole.ASSISTANT && item.has("tool_calls") && !item.isNull("tool_calls")) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "Only assistant messages may carry 'tool_calls'.", "messages[$index].tool_calls"
                )
            }
            val toolCalls = if (role == DshModelRole.ASSISTANT) parseToolCalls(item, index) else emptyList()
            val toolCallId = when (role) {
                DshModelRole.TOOL -> {
                    val value = (item.opt("tool_call_id") as? String)?.trim()
                    if (value.isNullOrEmpty()) {
                        fail(
                            400, "invalid_request_error", "invalid_request",
                            "Tool messages require a non-empty 'tool_call_id'.",
                            "messages[$index].tool_call_id"
                        )
                    }
                    value
                }
                else -> {
                    if (item.has("tool_call_id") && !item.isNull("tool_call_id")) {
                        fail(
                            400, "invalid_request_error", "invalid_request",
                            "'tool_call_id' is only valid on tool messages.", "messages[$index].tool_call_id"
                        )
                    }
                    null
                }
            }
            if (role == DshModelRole.ASSISTANT && content == null && toolCalls.isEmpty()) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "An assistant message must include content or tool_calls.", "messages[$index]"
                )
            }
            if (role == DshModelRole.TOOL && content == null) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "A tool message must include text content.", "messages[$index].content"
                )
            }
            messages += DshModelMessage(
                role = role,
                content = content,
                toolCallId = toolCallId,
                toolCalls = toolCalls
            )
        }
        return messages
    }

    private fun parseContent(item: JSONObject, messageIndex: Int): String? {
        if (!item.has("content") || item.isNull("content")) return null
        return when (val value = item.opt("content")) {
            is String -> value
            is JSONArray -> parseContentParts(value, messageIndex)
            else -> fail(
                400, "invalid_request_error", "invalid_request",
                "messages[$messageIndex].content must be a string, null, or an array of text parts.",
                "messages[$messageIndex].content"
            )
        }
    }

    private fun parseContentParts(parts: JSONArray, messageIndex: Int): String {
        val text = StringBuilder()
        for (partIndex in 0 until parts.length()) {
            val part = parts.optJSONObject(partIndex)
            if (part == null) {
                val scalar = parts.opt(partIndex) as? String
                    ?: fail(
                        400, "invalid_request_error", "invalid_request",
                        "messages[$messageIndex].content[$partIndex] must be a text part.",
                        "messages[$messageIndex].content[$partIndex]"
                    )
                text.append(scalar)
                continue
            }
            when (val type = part.opt("type") as? String) {
                "text", "input_text" -> {
                    val value = part.opt("text") as? String
                        ?: fail(
                            400, "invalid_request_error", "invalid_request",
                            "messages[$messageIndex].content[$partIndex].text must be a string.",
                            "messages[$messageIndex].content[$partIndex].text"
                        )
                    text.append(value)
                }
                "image_url", "input_image", "image" -> fail(
                    400, "invalid_request_error", "unsupported_content_part",
                    "Image content is not supported by the internal DSH model gateway V1; " +
                        "the frozen model contract carries text only.",
                    "messages[$messageIndex].content[$partIndex]"
                )
                else -> fail(
                    400, "invalid_request_error", "unsupported_content_part",
                    "Unsupported content part type '${type ?: "<missing>"}'.",
                    "messages[$messageIndex].content[$partIndex]"
                )
            }
        }
        return text.toString()
    }

    private fun parseToolCalls(item: JSONObject, messageIndex: Int): List<DshModelToolCall> {
        if (!item.has("tool_calls") || item.isNull("tool_calls")) return emptyList()
        val array = item.optJSONArray("tool_calls")
            ?: fail(
                400, "invalid_request_error", "invalid_request",
                "'tool_calls' must be an array.", "messages[$messageIndex].tool_calls"
            )
        val calls = ArrayList<DshModelToolCall>(array.length())
        for (index in 0 until array.length()) {
            val call = array.optJSONObject(index)
                ?: fail(
                    400, "invalid_request_error", "invalid_request",
                    "messages[$messageIndex].tool_calls[$index] must be an object.",
                    "messages[$messageIndex].tool_calls[$index]"
                )
            val type = call.opt("type") as? String
            if (type != "function") {
                fail(
                    400, "invalid_request_error", "unsupported_tool_call",
                    "Only tool calls with type 'function' are supported.",
                    "messages[$messageIndex].tool_calls[$index].type"
                )
            }
            val id = (call.opt("id") as? String)?.trim()
            if (id.isNullOrEmpty()) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "A tool call requires a non-empty 'id'.",
                    "messages[$messageIndex].tool_calls[$index].id"
                )
            }
            val function = call.optJSONObject("function")
                ?: fail(
                    400, "invalid_request_error", "invalid_request",
                    "A tool call requires a 'function' object.",
                    "messages[$messageIndex].tool_calls[$index].function"
                )
            val name = (function.opt("name") as? String)?.trim()
            if (name.isNullOrEmpty()) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "A tool call requires a non-empty function name.",
                    "messages[$messageIndex].tool_calls[$index].function.name"
                )
            }
            val arguments = function.opt("arguments")
            if (arguments !is String || !DshGatewayJson.isStrictJsonObject(arguments)) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "A tool call's function.arguments must be a strict JSON object string.",
                    "messages[$messageIndex].tool_calls[$index].function.arguments"
                )
            }
            calls += DshModelToolCall(id = id, name = name, argumentsJson = arguments)
        }
        return calls
    }

    private fun parseTools(root: JSONObject, limits: DshGatewayLimits): List<DshModelToolDefinition> {
        if (!root.has("tools") || root.isNull("tools")) return emptyList()
        val array = root.optJSONArray("tools")
            ?: fail(400, "invalid_request_error", "invalid_request", "'tools' must be an array.", "tools")
        if (array.length() > limits.maxToolsPerRequest) {
            fail(
                400, "invalid_request_error", "too_many_tools",
                "The request carries more than ${limits.maxToolsPerRequest} tools.", "tools"
            )
        }
        val tools = ArrayList<DshModelToolDefinition>(array.length())
        val names = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: fail(
                    400, "invalid_request_error", "invalid_request",
                    "tools[$index] must be an object.", "tools[$index]"
                )
            val type = item.opt("type") as? String
            if (type != "function") {
                fail(
                    400, "invalid_request_error", "unsupported_tool_definition",
                    "Only tools with type 'function' are supported.", "tools[$index].type"
                )
            }
            val function = item.optJSONObject("function")
                ?: fail(
                    400, "invalid_request_error", "invalid_request",
                    "tools[$index] requires a 'function' object.", "tools[$index].function"
                )
            val name = (function.opt("name") as? String)?.trim()
            if (name.isNullOrEmpty()) {
                fail(
                    400, "invalid_request_error", "invalid_request",
                    "tools[$index].function.name must be a non-empty string.",
                    "tools[$index].function.name"
                )
            }
            if (!names.add(name)) {
                fail(
                    400, "invalid_request_error", "duplicate_tool_name",
                    "Duplicate tool definition name '$name'.", "tools[$index].function.name"
                )
            }
            val description = when (val value = function.opt("description")) {
                null, JSONObject.NULL -> ""
                is String -> value
                else -> fail(
                    400, "invalid_request_error", "invalid_request",
                    "tools[$index].function.description must be a string.",
                    "tools[$index].function.description"
                )
            }
            val parametersJson = when (val parameters = function.opt("parameters")) {
                null, JSONObject.NULL -> "{}"
                is JSONObject -> DshGatewayJson.canonical(parameters)
                is String -> {
                    if (!DshGatewayJson.isStrictJsonObject(parameters)) {
                        fail(
                            400, "invalid_request_error", "invalid_request",
                            "tools[$index].function.parameters must be a JSON object or a strict JSON object string.",
                            "tools[$index].function.parameters"
                        )
                    }
                    parameters.trim()
                }
                else -> fail(
                    400, "invalid_request_error", "invalid_request",
                    "tools[$index].function.parameters must be a JSON object or a JSON string.",
                    "tools[$index].function.parameters"
                )
            }
            tools += DshModelToolDefinition(
                name = name,
                description = description,
                parametersJson = parametersJson
            )
        }
        return tools
    }

    private fun parseToolChoice(root: JSONObject, tools: List<DshModelToolDefinition>): DshToolChoice {
        if (!root.has("tool_choice") || root.isNull("tool_choice")) return DshToolChoice.AUTO
        val choice = when (val value = root.opt("tool_choice")) {
            is String -> when (value.trim().lowercase()) {
                "auto" -> DshToolChoice.AUTO
                "none" -> DshToolChoice.NONE
                "required" -> DshToolChoice.REQUIRED
                else -> fail(
                    400, "invalid_request_error", "invalid_tool_choice",
                    "Unsupported tool_choice '$value'.", "tool_choice"
                )
            }
            is JSONObject -> {
                val type = value.opt("type") as? String
                if (type != "function") {
                    fail(
                        400, "invalid_request_error", "invalid_tool_choice",
                        "A named tool_choice must have type 'function'.", "tool_choice.type"
                    )
                }
                val function = value.optJSONObject("function")
                    ?: fail(
                        400, "invalid_request_error", "invalid_tool_choice",
                        "A named tool_choice requires a 'function' object.", "tool_choice.function"
                    )
                val name = (function.opt("name") as? String)?.trim()
                if (name.isNullOrEmpty()) {
                    fail(
                        400, "invalid_request_error", "invalid_tool_choice",
                        "A named tool_choice requires a non-empty function name.", "tool_choice.function.name"
                    )
                }
                if (tools.none { it.name == name }) {
                    fail(
                        400, "invalid_request_error", "invalid_tool_choice",
                        "tool_choice names function '$name', which is not in 'tools'.", "tool_choice.function.name"
                    )
                }
                // Frozen contract limitation: the name pin is validated but only REQUIRED travels.
                DshToolChoice.REQUIRED
            }
            else -> fail(
                400, "invalid_request_error", "invalid_tool_choice",
                "'tool_choice' must be a string or an object.", "tool_choice"
            )
        }
        if (choice == DshToolChoice.REQUIRED && tools.isEmpty()) {
            fail(
                400, "invalid_request_error", "invalid_tool_choice",
                "tool_choice 'required' needs at least one tool definition.", "tool_choice"
            )
        }
        return choice
    }

    private fun parseTemperature(root: JSONObject): Double? {
        if (!root.has("temperature") || root.isNull("temperature")) return null
        val number = root.opt("temperature") as? Number
            ?: fail(
                400, "invalid_request_error", "invalid_temperature",
                "'temperature' must be a number.", "temperature"
            )
        val value = number.toDouble()
        if (!value.isFinite() || value < 0.0 || value > 2.0) {
            fail(
                400, "invalid_request_error", "invalid_temperature",
                "'temperature' must be between 0 and 2.", "temperature"
            )
        }
        return value
    }

    private fun parseMaxTokens(root: JSONObject): Int? {
        val completion = if (root.has("max_completion_tokens") && !root.isNull("max_completion_tokens")) {
            root.opt("max_completion_tokens")
        } else {
            null
        }
        val legacy = if (root.has("max_tokens") && !root.isNull("max_tokens")) root.opt("max_tokens") else null
        if (completion != null && legacy != null) {
            fail(
                400, "invalid_request_error", "invalid_request",
                "Specify only one of 'max_tokens' and 'max_completion_tokens'.", "max_tokens"
            )
        }
        val raw = completion ?: legacy ?: return null
        val param = if (completion != null) "max_completion_tokens" else "max_tokens"
        val number = raw as? Number
            ?: fail(400, "invalid_request_error", "invalid_request", "'$param' must be an integer.", param)
        val value = number.toDouble()
        if (!value.isFinite() || value != Math.floor(value) || value < 1.0 || value > MAX_OUTPUT_TOKENS.toDouble()) {
            fail(
                400, "invalid_request_error", "invalid_request",
                "'$param' must be an integer between 1 and $MAX_OUTPUT_TOKENS.", param
            )
        }
        return value.toInt()
    }

    private fun parseStream(root: JSONObject): Boolean {
        if (!root.has("stream") || root.isNull("stream")) return false
        return root.opt("stream") as? Boolean
            ?: fail(400, "invalid_request_error", "invalid_request", "'stream' must be a boolean.", "stream")
    }

    private fun parseIncludeUsage(root: JSONObject): Boolean {
        if (!root.has("stream_options") || root.isNull("stream_options")) return false
        val options = root.optJSONObject("stream_options")
            ?: fail(
                400, "invalid_request_error", "invalid_request",
                "'stream_options' must be an object.", "stream_options"
            )
        if (!options.has("include_usage") || options.isNull("include_usage")) return false
        return options.opt("include_usage") as? Boolean
            ?: fail(
                400, "invalid_request_error", "invalid_request",
                "'stream_options.include_usage' must be a boolean.", "stream_options.include_usage"
            )
    }

    /**
     * Rejects request fields that would silently change semantics this protocol adapter cannot
     * represent. Sampling knobs the contract does not carry (`top_p`, `stop`, `seed`, penalties)
     * are accepted and ignored because the DSH provider does not send them; they are documented
     * as out of contract in the module README rather than silently half-applied.
     */
    private fun validateUnsupportedFields(root: JSONObject) {
        val blocked = listOf(
            "response_format" to "Structured output cannot be represented by the frozen model contract.",
            "functions" to "Legacy 'functions' is not supported; use 'tools'.",
            "function_call" to "Legacy 'function_call' is not supported; use 'tool_choice'.",
            "audio" to "Audio output is not supported.",
            "modalities" to "Non-text modalities are not supported.",
            "prediction" to "Predicted output is not supported."
        )
        blocked.firstOrNull { (name, _) -> root.has(name) && !root.isNull(name) }?.let { (name, message) ->
            fail(400, "invalid_request_error", "unsupported_parameter", message, name)
        }
        if (root.has("n") && !root.isNull("n")) {
            val n = root.opt("n") as? Number
            if (n == null || n.toDouble() != 1.0) {
                fail(
                    400, "invalid_request_error", "unsupported_parameter",
                    "Only n=1 is supported; multiple choices cannot be represented.", "n"
                )
            }
        }
        if (root.optBoolean("logprobs", false) || (root.has("top_logprobs") && !root.isNull("top_logprobs"))) {
            fail(
                400, "invalid_request_error", "unsupported_parameter",
                "Log probabilities are not supported.", "logprobs"
            )
        }
        if (root.has("parallel_tool_calls") &&
            !root.isNull("parallel_tool_calls") &&
            root.opt("parallel_tool_calls") == false
        ) {
            fail(
                400, "invalid_request_error", "unsupported_parameter",
                "Disabling parallel tool calls cannot be represented by the frozen model contract.",
                "parallel_tool_calls"
            )
        }
    }

    private fun fail(status: Int, type: String, code: String, message: String, param: String? = null): Nothing =
        throw ParseFailure(status, type, code, message, param)

    private class ParseFailure(
        val status: Int,
        val type: String,
        val code: String,
        override val message: String,
        val param: String?
    ) : IllegalArgumentException(message)
}
