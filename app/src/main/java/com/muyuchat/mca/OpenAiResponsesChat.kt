package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import java.io.BufferedReader
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal fun responsesHttpRequest(config: CloudApiConfig, request: ChatRequest, stream: Boolean = true): Request =
    Request.Builder().url(responsesEndpointUrl(config.baseUrl)).addCloudApiKeyHeaders(config)
        .header("Accept", if (stream) "text/event-stream" else "application/json")
        .post(buildOpenAiResponsesJson(config, request, stream).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

/** Cancellation closes both a pending connect/headers call and a blocked SSE body read. */
private suspend fun <T> withResponsesCall(call: Call, block: suspend (Response) -> T): T = coroutineScope {
    val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        currentCoroutineContext().ensureActive()
        call.execute().use { response ->
            currentCoroutineContext().ensureActive()
            block(response)
        }
    } finally {
        canceller.cancel()
    }
}

internal fun streamOpenAiResponsesChat(client: OkHttpClient, config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent> = flow {
    require(config.configured) { "云端模型未配置完整。请填写 Base URL、模型名和必要的 API Key。" }
    val started = System.nanoTime() / 1_000_000L
    var firstChunk = 0L
    var visibleChars = 0
    val estimatedPromptTokens = estimateCloudPromptTokens(request)
    var streaming = false
    var hasVisibleText = false
    val decoder = ResponsesDecoder()
    val stop = ResponsesStopFilter(request.params.stopWords)
    val showReasoning = request.params.reasoningMode != ReasoningMode.OFF && !request.params.hideReasoning
    fun stats(): RuntimeStats {
        val now = System.nanoTime() / 1_000_000L
        val elapsed = (now - (firstChunk.takeIf { it > 0 } ?: started)).coerceAtLeast(0)
        val outputTokens = decoder.outputTokens ?: estimateCloudTokens(visibleChars)
        val totalMs = (now - started).coerceAtLeast(0L)
        val rateMs = if (streaming && elapsed > 0) elapsed else totalMs
        return RuntimeStats(
            loaded = true, backend = "cloud", modelPath = "${config.apiFormat.label}/${config.chatModel}",
            promptTokens = decoder.inputTokens ?: estimatedPromptTokens,
            completionTokens = outputTokens, ttftMs = if (firstChunk > 0) firstChunk - started else 0,
            decodeMs = elapsed, decodeTps = if (rateMs > 0) outputTokens * 1000.0 / rateMs else 0.0,
            e2eTps = if (now > started) outputTokens * 1000.0 / (now - started) else 0.0,
            cacheReuseHit = (decoder.cachedTokens ?: 0) > 0, cacheReusedTokens = decoder.cachedTokens ?: 0,
            cacheReuseReason = decoder.cachedTokens?.let { "provider_prompt_cache" },
            promptTokensEstimated = decoder.inputTokens == null,
            completionTokensEstimated = decoder.outputTokens == null
        )
    }
    suspend fun publish(deltas: List<ResponsesDelta>) {
        for (delta in deltas) {
            currentCoroutineContext().ensureActive()
            val text = stop.accept(delta.text)
            val reasoning = if (showReasoning && !stop.stopped) delta.reasoning else ""
            visibleChars += text.length + delta.reasoning.length
            if (text.isEmpty() && reasoning.isEmpty()) continue
            if (firstChunk == 0L) firstChunk = System.nanoTime() / 1_000_000L
            if (text.isNotBlank()) hasVisibleText = true
            emit(GenerateEvent.Chunk(text = text, reasoning = reasoning, reasoningDurationMs = 0L, stats = stats()))
        }
    }
    withResponsesCall(client.newCall(responsesHttpRequest(config, request))) { response ->
        val body = response.body ?: throw IOException("云端接口没有返回内容，请检查服务状态。")
        if (!response.isSuccessful) {
            val raw = body.charStream().buffered().readBoundedText()
            val message = runCatching { responsesError(JSONObject(raw)) }.getOrDefault(raw.take(300))
            throw IOException("云端 Responses 接口错误 ${response.code}: $message")
        }
        body.charStream().buffered().use { reader ->
            var sawStream = false
            val json = StringBuilder()
            val data = StringBuilder()
            var eventName = ""
            suspend fun dispatch() {
                if (data.isEmpty()) return
                streaming = true
                publish(decoder.event(data.toString().removeSuffix("\n"), eventName))
                data.setLength(0)
                eventName = ""
            }
            while (!decoder.terminal && !stop.stopped) {
                currentCoroutineContext().ensureActive()
                val line = reader.readBoundedLine()?.removePrefix("\uFEFF") ?: break
                when {
                    line.isEmpty() -> { dispatch(); eventName = "" }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> { sawStream = true; eventName = line.substringAfter(':').trim() }
                    line == "data" || line.startsWith("data:") -> {
                        sawStream = true
                        data.append(if (line == "data") "" else line.substringAfter(':').removePrefix(" ")).append('\n')
                        require(data.length <= RESPONSES_MAX_BODY_CHARS) { "Responses 事件过大，请降低输出长度。" }
                    }
                    line.startsWith("id:") || line.startsWith("retry:") -> Unit
                    !sawStream -> {
                        json.append(line).append('\n')
                        require(json.length <= RESPONSES_MAX_BODY_CHARS) { "云端响应过大，请降低输出长度。" }
                    }
                }
            }
            if (!decoder.terminal && !stop.stopped) {
                if (sawStream) dispatch() else publish(decoder.finish(JSONObject(json.toString())))
            }
            if (!stop.stopped && !decoder.terminal) throw IOException("Responses 流意外中断，未收到完成事件；请检查网络后重试。")
            // Flush an unmatched marker prefix without feeding it back through the filter.
            val tail = stop.finish()
            if (tail.isNotEmpty()) {
                if (firstChunk == 0L) firstChunk = System.nanoTime() / 1_000_000L
                visibleChars += tail.length
                if (tail.isNotBlank()) hasVisibleText = true
                emit(GenerateEvent.Chunk(text = tail, stats = stats()))
            }
            // A locally matched stop marker can end an otherwise non-terminal stream.
            // Never execute tool calls until the provider has completed the response.
            val acceptedToolCalls = if (decoder.terminal && !stop.stopped) decoder.toolCalls else emptyList()
            if (!stop.stopped && decoder.error != null) emit(GenerateEvent.Error(requireNotNull(decoder.error), stats()))
            else if (!stop.stopped && acceptedToolCalls.any { call -> request.tools.none { it.name == call.name } })
                emit(GenerateEvent.Error("Responses 返回了本次请求未授权的工具，请检查模型和服务商配置。", stats()))
            else if (!hasVisibleText && acceptedToolCalls.isEmpty() && !stop.stopped)
                emit(GenerateEvent.Error("云端模型未返回可见正文或工具调用，请检查输出上限、模型权限或服务商响应。", stats()))
            else emit(GenerateEvent.Done(stats(), toolCalls = acceptedToolCalls))
        }
    }
}.catch { error ->
    currentCoroutineContext().ensureActive()
    if (error is CancellationException) throw error
    emit(GenerateEvent.Error(error.message ?: "Responses 请求失败，请检查网络和接口配置。",
        RuntimeStats(loaded = true, backend = "cloud", modelPath = "${config.apiFormat.label}/${config.chatModel}", lastError = error.message)))
}.flowOn(Dispatchers.IO)

internal suspend fun quickTestOpenAiResponses(client: OkHttpClient, config: CloudApiConfig): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        require(config.configured) { "请先填写 Responses 接口地址、模型名和必要的 API Key。" }
        val request = ChatRequest(listOf(ChatMessage(Role.USER, "Reply OK.")),
            GenerationParams(systemPrompt = "", nPredict = 16, reasoningMode = ReasoningMode.OFF))
        withResponsesCall(client.newCall(responsesHttpRequest(config, request, stream = false))) { response ->
            val body = response.body?.charStream()?.buffered()?.readBoundedText().orEmpty()
            if (!response.isSuccessful) throw IOException("云端接口错误 ${response.code}: " +
                runCatching { responsesError(JSONObject(body)) }.getOrDefault(body.take(300)))
            val decoder = ResponsesDecoder()
            val deltas = decoder.finish(JSONObject(body))
            // A small connectivity probe may legitimately exhaust its reasoning/output allowance.
            if (decoder.incompleteReason != "max_output_tokens") {
                decoder.error?.let { throw IOException(it) }
                require(deltas.any { it.text.isNotBlank() }) { "接口未返回有效的 Responses 正文，请检查所选协议。" }
            }
        }
        Result.success(Unit)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        Result.failure(error)
    }
}

private fun BufferedReader.readBoundedLine(): String? {
    val line = StringBuilder()
    while (true) {
        val value = read()
        if (value < 0) return line.toString().takeIf { it.isNotEmpty() }
        if (value == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(value.toChar())
        require(line.length <= RESPONSES_MAX_BODY_CHARS) { "云端响应行过大，请降低输出长度。" }
    }
}

private fun BufferedReader.readBoundedText(): String {
    val text = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) return text.toString()
        text.append(buffer, 0, read)
        require(text.length <= RESPONSES_MAX_BODY_CHARS) { "云端响应过大，请降低输出长度。" }
    }
}
