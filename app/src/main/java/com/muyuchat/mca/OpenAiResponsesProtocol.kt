package com.muyuchat.mca

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ReasoningMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Reuse MCA's assembled persona/retrieval/history; never rely on a provider-side conversation. */
internal fun buildOpenAiResponsesJson(config: CloudApiConfig, request: ChatRequest, stream: Boolean = true): JSONObject {
    val messages = JSONArray(request.messagesJson(multimodal = true))
    val input = JSONArray()
    for (index in 0 until messages.length()) {
        val message = messages.getJSONObject(index)
        val content = message.get("content")
        val converted: Any = if (content is JSONArray) JSONArray().apply {
            for (partIndex in 0 until content.length()) {
                val part = content.getJSONObject(partIndex)
                when (part.getString("type")) {
                    "text" -> put(JSONObject().put("type", "input_text").put("text", part.getString("text")))
                    "image_url" -> {
                        require(config.supportsVision) { "此云端模型未开启图片输入，请在模型配置中确认并开启。" }
                        val image = part.getJSONObject("image_url")
                        val url = image.getString("url")
                        require(url.startsWith("data:image/", true) || url.startsWith("https://", true) || url.startsWith("http://", true)) {
                            "图片尚未转换为可发送的数据，请重新添加图片。"
                        }
                        put(JSONObject().put("type", "input_image").put("image_url", url).put("detail", "auto"))
                    }
                }
            }
        } else content.toString()
        // created_at and Chat Completions image_url objects are not Responses fields.
        input.put(JSONObject().put("role", message.getString("role")).put("content", converted))
    }
    // Tool exchanges are protocol-neutral in ChatRequest. Responses requires the
    // assistant's function_call item immediately followed by its function result.
    for (exchange in request.toolExchanges) {
        input.put(JSONObject()
            .put("type", "function_call")
            .put("call_id", exchange.call.id)
            .put("name", exchange.call.name)
            .put("arguments", exchange.call.argumentsJson))
        input.put(JSONObject()
            .put("type", "function_call_output")
            .put("call_id", exchange.call.id)
            .put("output", exchange.output))
    }
    return JSONObject().put("model", config.chatModel.trim()).put("input", input)
        .put("stream", stream).put("store", false)
        .put("max_output_tokens", request.params.effectiveNPredict().coerceAtLeast(16))
        .apply {
            if (config.responsesReasoningEnabled) {
                val reasoning = JSONObject().put("effort", if (request.params.reasoningMode == ReasoningMode.ADVANCED) "high" else "medium")
                if (request.params.reasoningMode != ReasoningMode.OFF && !request.params.hideReasoning) reasoning.put("summary", "auto")
                put("reasoning", reasoning)
            } else {
                put("temperature", request.params.temperature.toDouble().coerceIn(0.0, 2.0))
                put("top_p", request.params.topP.toDouble().coerceIn(0.0, 1.0))
            }
            if (request.tools.isNotEmpty()) {
                require(config.apiFormat == CloudApiFormat.OPENAI_RESPONSES) {
                    "当前聊天协议不支持 Responses 工具调用，请切换到 OpenAI Responses 并在模型设置中启用工具调用。"
                }
                require(config.supportsTools) {
                    "当前云端模型未启用工具调用，请在 Responses 模型设置中确认该能力后重试。"
                }
                val tools = JSONArray()
                request.tools.forEach { definition ->
                    require(definition.name.isNotBlank()) { "工具名称不能为空。" }
                    val parameters = JSONObject(definition.parametersJson)
                    require(parameters.length() > 0 && parameters.optString("type") == "object") {
                        "工具 ${definition.name} 的参数必须是 JSON Schema object。"
                    }
                    tools.put(JSONObject()
                        .put("type", "function")
                        .put("name", definition.name)
                        .put("description", definition.description)
                        .put("parameters", parameters))
                }
                put("tools", tools)
                put("tool_choice", "auto")
            }
            // Responses has no stop/seed/top_k/min_p/repetition penalties. Stop words are
            // applied to streamed visible text locally, including markers split across events.
        }
}

internal fun responsesEndpointUrl(baseUrl: String): String {
    val url = baseUrl.trim().toHttpUrl()
    val path = url.encodedPath.trimEnd('/').let {
        when {
            it.endsWith("/responses") -> it.removeSuffix("/responses")
            it.endsWith("/chat/completions") -> it.removeSuffix("/chat/completions")
            it.endsWith("/messages") -> it.removeSuffix("/messages")
            else -> it
        }
    }
    return url.newBuilder().encodedPath("$path/responses").build().toString()
}

internal data class ResponsesDelta(val text: String = "", val reasoning: String = "")

private data class ResponsesToolAccumulator(
    val outputIndex: Int,
    var itemId: String = "",
    var callId: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
    var complete: Boolean = false
)

/** One decoder per request. Done snapshots fill missing suffixes; they never repeat prior deltas. */
internal class ResponsesDecoder {
    private val parts = linkedMapOf<String, StringBuilder>()
    private val toolCallsByOutput = linkedMapOf<Int, ResponsesToolAccumulator>()
    private var terminalToolOutputIndexes: Set<Int> = emptySet()
    private var terminalSuccessful = false
    var terminal = false
        private set
    var error: String? = null
        private set
    var inputTokens: Int? = null
        private set
    var outputTokens: Int? = null
        private set
    var cachedTokens: Int? = null
        private set
    var incompleteReason: String? = null
        private set
    val toolCalls: List<ChatToolCall>
        get() = if (!terminal || !terminalSuccessful || error != null) emptyList() else
            toolCallsByOutput.values.map { item -> ChatToolCall(item.callId, item.name, item.arguments.toString()) }

    fun event(data: String, eventName: String = ""): List<ResponsesDelta> {
        if (terminal) return emptyList()
        if (data.trim() == "[DONE]") {
            error("Responses 流缺少 response.completed 终态，请检查服务商的协议实现。")
        }
        val root = JSONObject(data)
        val type = root.text("type").ifEmpty { eventName }
        val output = root.optInt("output_index", 0)
        val content = root.optInt("content_index", 0)
        return when (type) {
            "response.output_text.delta", "response.refusal.delta" ->
                append("text:$output:$content", root.text("delta"), false, snapshot = false)
            "response.output_text.done" -> append("text:$output:$content", root.text("text"), false, snapshot = true)
            "response.refusal.done" -> append("text:$output:$content", root.text("refusal"), false, snapshot = true)
            "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done" ->
                append("summary:$output:${root.optInt("summary_index", 0)}",
                    root.text(if (type.endsWith(".delta")) "delta" else "text"), true, type.endsWith(".done"))
            "response.reasoning_text.delta", "response.reasoning_text.done" ->
                append("reasoning:$output:$content", root.text(if (type.endsWith(".delta")) "delta" else "text"), true, type.endsWith(".done"))
            "response.output_item.added" -> {
                root.optJSONObject("item")?.let { item ->
                    val index = if (item.text("type") == "function_call") requiredOutputIndex(root) else output
                    toolItem(item, index, snapshot = false, complete = false)
                }
                emptyList()
            }
            "response.function_call_arguments.delta" -> {
                val index = requiredOutputIndex(root)
                val tool = toolCallsByOutput.getOrPut(index) { ResponsesToolAccumulator(index) }
                observeToolItemId(tool, root.text("item_id"))
                appendToolArguments(tool, root.text("delta"), snapshot = false)
                emptyList()
            }
            "response.function_call_arguments.done" -> {
                val index = requiredOutputIndex(root)
                val tool = toolCallsByOutput.getOrPut(index) { ResponsesToolAccumulator(index) }
                observeToolItemId(tool, root.text("item_id"))
                appendToolArguments(tool, root.text("arguments"), snapshot = true)
                tool.complete = true
                emptyList()
            }
            "response.output_item.done" -> {
                val item = root.getJSONObject("item")
                val index = if (item.text("type") == "function_call") requiredOutputIndex(root) else output
                item(item, index)
            }
            "response.completed", "response.failed", "response.incomplete", "response.cancelled" -> {
                val response = root.getJSONObject("response")
                val eventStatus = type.substringAfter("response.")
                val responseStatus = response.text("status")
                if (responseStatus.isNotBlank() && responseStatus != eventStatus) {
                    terminal = true
                    error = "Responses 终态事件与 response.status 不一致，请检查服务商响应。"
                    emptyList()
                } else finish(response, eventStatus)
            }
            "error" -> {
                terminal = true
                error = responsesError(root)
                emptyList()
            }
            else -> emptyList() // lifecycle/annotations/tool metadata are never assistant prose
        }
    }

    fun finish(response: JSONObject, fallbackStatus: String = ""): List<ResponsesDelta> {
        if (terminal) return emptyList()
        terminal = true
        response.optJSONObject("usage")?.let { usage ->
            inputTokens = usage.nonnegativeInt("input_tokens")
            outputTokens = usage.nonnegativeInt("output_tokens")
            cachedTokens = usage.optJSONObject("input_tokens_details")?.nonnegativeInt("cached_tokens")
        }
        val status = response.text("status").ifEmpty { fallbackStatus }
        if (response.optJSONObject("error") != null || status == "failed" || status == "cancelled") {
            error = responsesError(response)
            return emptyList()
        }
        if (status == "incomplete") {
            incompleteReason = response.optJSONObject("incomplete_details")?.text("reason").orEmpty()
            error = if (incompleteReason == "max_output_tokens") "回答已达到输出上限，内容可能不完整；请提高最大输出长度后重试。"
                else "云端回答未完成（${incompleteReason.orEmpty().ifBlank { "原因未提供" }}），请检查服务商限制后重试。"
        } else if (status != "completed") {
            error = "Responses 接口未返回有效完成状态（${status.ifBlank { "缺少 status" }}），请检查接口地址与协议。"
            return emptyList()
        }
        val output = response.optJSONArray("output") ?: JSONArray()
        return buildList {
            for (index in 0 until output.length()) {
                val value = output.getJSONObject(index)
                if (value.text("type") == "function_call") {
                    if (hasCompleteTerminalToolFields(value)) {
                        terminalToolOutputIndexes += index
                        addAll(item(value, index))
                    } else if (error == null) {
                        error = "Responses 终态工具调用缺少有效的 call_id、name 或 arguments。"
                    }
                } else addAll(item(value, index))
            }
        }.also {
            val incompleteTool = toolCallsByOutput.values.firstOrNull { tool -> !tool.complete || tool.callId.isBlank() || tool.name.isBlank() }
            if (incompleteTool != null && error == null) {
                error = "Responses 工具调用未完整返回，请检查服务商的 function_call 事件。"
            } else if (toolCallsByOutput.keys.any { it !in terminalToolOutputIndexes } && error == null) {
                error = "Responses 终态中缺少已流式返回的工具调用，请检查服务商响应。"
            } else if (toolCallsByOutput.values.map { it.callId }.filter(String::isNotBlank).distinct().size !=
                toolCallsByOutput.values.count { it.callId.isNotBlank() } && error == null) {
                error = "Responses 返回了重复的工具调用 ID，请检查服务商响应。"
            } else if (toolCallsByOutput.values.any { tool -> runCatching { JSONObject(tool.arguments.toString()) }.isFailure } && error == null) {
                error = "Responses 工具参数不是有效的 JSON object，请检查服务商响应。"
            }
            if (error == null) terminalSuccessful = true
        }
    }

    private fun item(value: JSONObject, index: Int): List<ResponsesDelta> = buildList {
        when (value.text("type")) {
            "message" -> {
                val content = value.optJSONArray("content") ?: JSONArray()
                for (i in 0 until content.length()) {
                    val part = content.getJSONObject(i)
                    when (part.text("type")) {
                        "output_text" -> addAll(append("text:$index:$i", part.text("text"), false, true))
                        "refusal" -> addAll(append("text:$index:$i", part.text("refusal"), false, true))
                    }
                }
            }
            "reasoning" -> {
                val summary = value.optJSONArray("summary") ?: JSONArray()
                for (i in 0 until summary.length()) addAll(append("summary:$index:$i", summary.getJSONObject(i).text("text"), true, true))
                // Official summaries and provider reasoning are separate parts, neither is stored
                // as a user/assistant history message or replayed as raw hidden reasoning.
                val content = value.optJSONArray("content") ?: JSONArray()
                for (i in 0 until content.length()) {
                    val part = content.getJSONObject(i)
                    if (part.text("type") == "reasoning_text") addAll(append("reasoning:$index:$i", part.text("text"), true, true))
                }
            }
            "function_call" -> toolItem(value, index, snapshot = true, complete = true)
        }
    }

    private fun toolItem(value: JSONObject, index: Int, snapshot: Boolean, complete: Boolean) {
        if (value.text("type") != "function_call") return
        val tool = toolCallsByOutput.getOrPut(index) { ResponsesToolAccumulator(index) }
        observeToolItemId(tool, value.text("id"))
        val callId = value.text("call_id")
        if (callId.isNotEmpty()) {
            require(tool.callId.isEmpty() || tool.callId == callId) { "Responses 工具调用 ID 前后不一致。" }
            tool.callId = callId
        }
        val name = value.text("name")
        if (name.isNotEmpty()) {
            require(tool.name.isEmpty() || tool.name == name) { "Responses 工具调用名称前后不一致。" }
            tool.name = name
        }
        if (value.has("arguments") && !value.isNull("arguments")) {
            appendToolArguments(tool, value.optString("arguments", ""), snapshot)
        }
        if (complete) tool.complete = true
    }

    private fun observeToolItemId(tool: ResponsesToolAccumulator, itemId: String) {
        if (itemId.isBlank()) return
        require(tool.itemId.isEmpty() || tool.itemId == itemId) { "Responses 工具调用项目 ID 前后不一致。" }
        tool.itemId = itemId
    }

    private fun requiredOutputIndex(root: JSONObject): Int {
        val value = root.opt("output_index")
        val numeric = value as? Number
        val asDouble = numeric?.toDouble()
        require(numeric != null && asDouble != null && asDouble.isFinite() && asDouble % 1.0 == 0.0 &&
            numeric.toLong() in 0..Int.MAX_VALUE.toLong()) {
            "Responses 工具事件缺少有效的 output_index。"
        }
        return numeric.toInt()
    }

    private fun hasCompleteTerminalToolFields(value: JSONObject): Boolean =
        (value.opt("call_id") as? String)?.isNotBlank() == true &&
            (value.opt("name") as? String)?.isNotBlank() == true &&
            value.opt("arguments") is String

    private fun appendToolArguments(tool: ResponsesToolAccumulator, value: String, snapshot: Boolean) {
        if (!snapshot && value.isEmpty()) return
        if (snapshot) {
            val current = tool.arguments.toString()
            require(value.startsWith(current)) { "Responses 最终工具参数与流式参数不一致，请检查服务商响应。" }
            tool.arguments.append(value.substring(current.length))
        } else {
            tool.arguments.append(value)
        }
        require(parts.values.sumOf { it.length } + toolCallsByOutput.values.sumOf { it.arguments.length } <= RESPONSES_MAX_BODY_CHARS) {
            "云端回答超过接收上限，请降低最大输出长度。"
        }
    }

    private fun append(key: String, text: String, reasoning: Boolean, snapshot: Boolean): List<ResponsesDelta> {
        if (text.isEmpty()) return emptyList()
        val seen = parts.getOrPut(key) { StringBuilder() }
        val delta = if (snapshot) {
            require(text.startsWith(seen.toString())) { "Responses 最终正文与流式内容不一致，请检查服务商响应。" }
            text.substring(seen.length)
        } else text
        seen.append(delta)
        require(parts.values.sumOf { it.length } + toolCallsByOutput.values.sumOf { it.arguments.length } <= RESPONSES_MAX_BODY_CHARS) {
            "云端回答超过接收上限，请降低最大输出长度。"
        }
        return if (delta.isEmpty()) emptyList() else listOf(if (reasoning) ResponsesDelta(reasoning = delta) else ResponsesDelta(text = delta))
    }
}

internal const val RESPONSES_MAX_BODY_CHARS = 4 * 1024 * 1024
internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
private fun JSONObject.nonnegativeInt(key: String): Int? = optLong(key, -1).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()

internal fun responsesError(root: JSONObject): String {
    val error = root.optJSONObject("error") ?: root
    return error.text("message").ifBlank { error.text("code") }.ifBlank { "云端 Responses 请求失败，请检查模型权限与服务状态。" }
}

internal typealias ResponsesStopFilter = com.muyuchat.core.engine.StreamingStopFilter
