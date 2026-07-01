package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class McaLoopbackServer(
    private val port: Int = 11435,
    private val bindHost: String = "0.0.0.0",
    private val apiKey: String = ""
) {
    private var dispatcher: ExecutorCoroutineDispatcher = newDispatcher()
    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start() {
        if (isRunning) return
        if (!scope.isActive) {
            dispatcher.close()
            dispatcher = newDispatcher()
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), port), SERVER_BACKLOG)
        serverSocket = socket
        logInfo("Local API listening on $bindHost:$port")
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = CLIENT_READ_TIMEOUT_MS
                    client.tcpNoDelay = true
                    logDebug("Accepted ${client.inetAddress?.hostAddress}:${client.port}")
                    launch {
                        runCatching { handle(client) }
                            .onFailure { error ->
                                logWarning("Request failed: ${error.message}", error)
                                runCatching {
                                    if (!client.isClosed) {
                                        writeError(
                                            client,
                                            "500 Internal Server Error",
                                            "request_failed",
                                            error.message ?: "Local API request failed."
                                        )
                                    }
                                }
                                runCatching { client.close() }
                            }
                    }
                } catch (error: SocketException) {
                    if (!socket.isClosed) logWarning("Accept failed: ${error.message}", error)
                } catch (error: Throwable) {
                    logWarning("Accept loop failed: ${error.message}", error)
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        logInfo("Local API stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
        dispatcher.close()
    }

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            val request = readHttpRequest(client)
            val headers = request.headers
            val body = request.body
            val parts = request.requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val path = parts.getOrNull(1).orEmpty().substringBefore("?")
            logDebug("$method $path")
            if (method == "OPTIONS") {
                writeNoContent(client)
                return
            }
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                writeHtml(client, chatPageHtml())
                return
            }
            if (!isPublicRoute(method, path) && !isAuthorized(headers)) {
                writeError(client, "401 Unauthorized", "unauthorized", "API Key 不正确或缺失。")
                return
            }
            when {
                method == "HEAD" && path == "/health" -> writeNoContent(client)
                method == "GET" && path == "/health" -> writeJson(client, """{"status":"ok","name":"MuYu Chat Agent"}""")
                method == "GET" && (path == "/v1/models" || path == "/models") -> writeJson(client, LocalApiRuntime.modelsJsonProvider())
                method == "GET" && path == "/v1/mca/device" -> writeJson(client, LocalApiRuntime.deviceProfileJsonProvider())
                method == "POST" && path == "/v1/mca/recommend" -> writeJson(client, LocalApiRuntime.agentRecommendationJsonProvider(body))
                method == "POST" && path == "/v1/mca/benchmark" -> writeJson(client, LocalApiRuntime.benchmarkJsonProvider(body))
                method == "GET" && path == "/metrics" -> writeText(client, LocalApiRuntime.engine?.nativeStatsJson() ?: "{}")
                method == "POST" && path == "/v1/generate/stop" -> {
                    LocalApiRuntime.engine?.stopGeneration()
                    writeJson(client, """{"stopped":true}""")
                }
                method == "POST" && path in GENERATION_PATHS -> {
                    val streaming = body.isStreamingRequest(headers)
                    if (streaming) {
                        streamChat(client, body)
                    } else {
                        completeChat(client, body)
                    }
                }
                else -> writeError(client, "404 Not Found", "not_found", "接口不存在：$path")
            }
        }
    }

    private suspend fun streamChat(socket: Socket, body: String) {
        val engine = LocalApiRuntime.engine
        if (engine == null) {
            writeError(socket, "503 Service Unavailable", "engine_unavailable", "MCA engine is not attached.")
            return
        }
        val request = parseChatRequest(body)
        val requestId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        var hasVisibleContent = false
        var emptyHintSent = false
        var finalStats: RuntimeStats? = null
        output.write("HTTP/1.1 200 OK\r\n")
        output.write("Content-Type: text/event-stream; charset=utf-8\r\n")
        output.write("Cache-Control: no-cache\r\n")
        output.write("X-Accel-Buffering: no\r\n")
        output.write(corsHeaders())
        output.write("Connection: close\r\n\r\n")
        output.write("data: ${roleSseJson(requestId, created)}\n\n")
        output.flush()
        engine.streamChat(request).collect { event ->
            when (event) {
                is GenerateEvent.Chunk -> {
                    finalStats = event.stats
                    if (event.reasoning.isNotBlank()) {
                        output.write("data: ${event.reasoning.toSseJson(requestId, created, reasoning = true)}\n\n")
                    }
                    if (event.text.isNotBlank()) {
                        hasVisibleContent = true
                        output.write("data: ${event.text.toSseJson(requestId, created)}\n\n")
                    } else if (
                        event.hiddenReasoning &&
                        !hasVisibleContent &&
                        !emptyHintSent &&
                        event.stats.completionTokens >= STREAM_HIDDEN_REASONING_HINT_TOKENS
                    ) {
                        emptyHintSent = true
                        output.write("data: ${emptyVisibleContentHint(event.stats, request.params).toSseJson(requestId, created)}\n\n")
                        engine.stopGeneration()
                    }
                }
                is GenerateEvent.Done -> {
                    finalStats = event.stats
                    if (!hasVisibleContent && !emptyHintSent && finalStats.generatedSomething()) {
                        output.write("data: ${emptyVisibleContentHint(finalStats, request.params).toSseJson(requestId, created)}\n\n")
                    }
                    output.write("data: ${finishSseJson(requestId, created)}\n\n")
                    output.write("data: [DONE]\n\n")
                }
                is GenerateEvent.Error -> output.write("data: ${errorJson("generation_failed", event.message)}\n\n")
            }
            output.flush()
        }
    }

    private suspend fun completeChat(socket: Socket, body: String) {
        val engine = LocalApiRuntime.engine
        if (engine == null) {
            writeError(socket, "503 Service Unavailable", "engine_unavailable", "MCA engine is not attached.")
            return
        }
        val requestId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        val request = parseChatRequest(body)
        val builder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var errorMessage: String? = null
        var finalStats: RuntimeStats? = null
        var emptyHintUsed = false
        engine.streamChat(request).collect { event ->
            when (event) {
                is GenerateEvent.Chunk -> {
                    finalStats = event.stats
                    if (event.text.isNotBlank()) builder.append(event.text)
                    if (event.reasoning.isNotBlank()) reasoningBuilder.append(event.reasoning)
                    if (
                        builder.isBlank() &&
                        event.hiddenReasoning &&
                        !emptyHintUsed &&
                        event.stats.completionTokens >= STREAM_HIDDEN_REASONING_HINT_TOKENS
                    ) {
                        emptyHintUsed = true
                        builder.append(emptyVisibleContentHint(event.stats, request.params))
                        engine.stopGeneration()
                    }
                }
                is GenerateEvent.Done -> finalStats = event.stats
                is GenerateEvent.Error -> errorMessage = event.message
            }
        }
        if (errorMessage != null) {
            writeError(socket, "500 Internal Server Error", "generation_failed", errorMessage.orEmpty())
            return
        }
        val response = JSONObject()
            .put("id", requestId)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", currentModelName())
            .put(
                "choices",
                JSONArray().put(
                    run {
                        val visibleContent = builder.toString().ifBlank {
                            if (finalStats.generatedSomething()) emptyVisibleContentHint(finalStats, request.params) else ""
                        }
                        val message = JSONObject()
                            .put("role", "assistant")
                            .put("content", visibleContent)
                        if (reasoningBuilder.isNotBlank()) {
                            message.put("reasoning_content", reasoningBuilder.toString())
                        }
                        JSONObject()
                            .put("index", 0)
                            .put("message", message)
                            .put("finish_reason", "stop")
                    }
                )
            )
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", tokenOrNull(finalStats?.promptTokens))
                    .put("completion_tokens", tokenOrNull(finalStats?.completionTokens))
                    .put(
                        "total_tokens",
                        finalStats?.let { stats ->
                            val total = stats.promptTokens + stats.completionTokens
                            if (total > 0) total else JSONObject.NULL
                        } ?: JSONObject.NULL
                    )
            )
        writeJson(socket, response.toString())
    }

    private fun isPublicRoute(method: String, path: String): Boolean =
        method == "HEAD" && path == "/health" ||
            method == "GET" && (
                path == "/health" ||
                    path == "/" ||
                    path == "/index.html" ||
                    path == "/v1/models" ||
                    path == "/models"
                )

    private fun writeJson(socket: Socket, body: String, status: String = "200 OK") {
        writeText(socket, body, status, "application/json; charset=utf-8")
    }

    private fun writeHtml(socket: Socket, body: String, status: String = "200 OK") {
        writeText(socket, body, status, "text/html; charset=utf-8")
    }

    private fun writeError(socket: Socket, status: String, code: String, message: String) {
        writeJson(socket, errorJson(code, message).toString(), status)
    }

    private fun errorJson(code: String, message: String): JSONObject =
        OpenAiApiCompat.errorJson(code, message)

    private fun writeNoContent(socket: Socket) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 204 No Content\r\n".toByteArray())
        output.write(corsHeaders().toByteArray())
        output.write("Content-Length: 0\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        if (apiKey.isBlank()) return true
        val authorization = headers["authorization"].orEmpty()
        val bearer = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substringAfter(' ').trim()
        } else {
            authorization.trim()
        }
        val headerKey = headers["x-api-key"]?.trim()
        return bearer == apiKey || headerKey == apiKey
    }

    private fun writeText(
        socket: Socket,
        body: String,
        status: String = "200 OK",
        contentType: String = "text/plain; charset=utf-8"
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status\r\n".toByteArray())
        output.write("Content-Type: $contentType\r\n".toByteArray())
        output.write(corsHeaders().toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun corsHeaders(): String = buildString {
        append(OpenAiApiCompat.corsHeaders())
    }

    private fun parseChatRequest(body: String): ChatRequest {
        return OpenAiApiCompat.parseChatRequest(body)
    }

    private fun String.isStreamingRequest(headers: Map<String, String>): Boolean {
        val accept = headers["accept"].orEmpty()
        return OpenAiApiCompat.isStreamingRequest(this) ||
            STREAM_TRUE_PATTERN.containsMatchIn(this) ||
            accept.contains("text/event-stream", ignoreCase = true)
    }

    private fun String.toSseJson(requestId: String, created: Long, reasoning: Boolean = false): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put(
                        "delta",
                        if (reasoning) {
                            JSONObject().put("reasoning_content", this)
                        } else {
                            JSONObject().put("content", this)
                        }
                    )
                    .put("finish_reason", JSONObject.NULL)
            )
        )
        .toString()

    private fun roleSseJson(requestId: String, created: Long): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject().put("role", "assistant"))
                    .put("finish_reason", JSONObject.NULL)
            )
        )
        .toString()

    private fun finishSseJson(requestId: String, created: Long): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject())
                    .put("finish_reason", "stop")
            )
        )
        .toString()

    private fun currentModelName(): String =
        runCatching {
            val json = JSONObject(LocalApiRuntime.loadedModelJsonProvider())
            json.optString("displayName")
                .ifBlank { json.optString("id") }
                .ifBlank { "mca-local-model" }
        }.getOrDefault("mca-local-model")

    private fun tokenOrNull(value: Int?): Any =
        value?.takeIf { it > 0 } ?: JSONObject.NULL

    private fun RuntimeStats?.generatedSomething(): Boolean =
        (this?.completionTokens ?: 0) > 0

    private fun emptyVisibleContentHint(stats: RuntimeStats?, params: GenerationParams): String {
        val generated = stats?.completionTokens?.takeIf { it > 0 }
        val limit = params.nPredict
        val recommended = when {
            limit < 2048 -> "4096"
            limit < 4096 -> "8192"
            limit < 8192 -> "16384"
            else -> "更高的输出长度"
        }
        return buildString {
            append("[MCA 提示] 模型本轮")
            if (generated != null) append("已生成 ").append(generated).append(" token，")
            append("但没有产出可见正文。通常是思考模型把输出预算消耗在推理阶段，或思考内容被隐藏。")
            append("当前 max_tokens/n_predict=").append(limit).append("；")
            append("建议提高到 ").append(recommended).append("，或设置 reasoning_mode=off、hide_reasoning=true 后重试。")
        }
    }

    private fun readHttpRequest(socket: Socket): HttpRequest {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        var headerEnd = -1
        while (headerEnd < 0) {
            val read = input.read()
            if (read < 0) break
            buffer.write(read)
            headerEnd = findHeaderEnd(buffer.toByteArray())
            if (buffer.size() > MAX_HEADER_BYTES) error("HTTP header too large.")
        }

        val raw = buffer.toByteArray()
        val safeHeaderEnd = headerEnd.takeIf { it >= 0 } ?: raw.size
        val bodyStart = when {
            raw.copyOfRange(0, safeHeaderEnd).endsWithBytes(CRLFCRLF) -> safeHeaderEnd
            raw.copyOfRange(0, safeHeaderEnd).endsWithBytes(LFLF) -> safeHeaderEnd
            else -> safeHeaderEnd
        }
        val headerText = String(raw.copyOfRange(0, bodyStart), Charsets.ISO_8859_1)
            .trimEnd('\r', '\n')
        val headerLines = headerText.split(Regex("\r?\n"))
        val requestLine = headerLines.firstOrNull().orEmpty()
        val headers = mutableMapOf<String, String>()
        headerLines.drop(1).forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val alreadyRead = raw.copyOfRange(bodyStart, raw.size)
        val bodyBytes = ByteArray(contentLength)
        val copied = alreadyRead.size.coerceAtMost(contentLength)
        alreadyRead.copyInto(bodyBytes, endIndex = copied)
        var offset = copied
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            if (read <= 0) break
            offset += read
        }
        return HttpRequest(
            requestLine = requestLine,
            headers = headers,
            body = String(bodyBytes.copyOf(offset), Charsets.UTF_8)
        )
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        val crlf = bytes.indexOfSequence(CRLFCRLF)
        if (crlf >= 0) return crlf + CRLFCRLF.size
        val lf = bytes.indexOfSequence(LFLF)
        return if (lf >= 0) lf + LFLF.size else -1
    }

    private fun ByteArray.indexOfSequence(target: ByteArray): Int {
        if (target.isEmpty() || size < target.size) return -1
        for (index in 0..(size - target.size)) {
            var matched = true
            for (targetIndex in target.indices) {
                if (this[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    private fun ByteArray.endsWithBytes(target: ByteArray): Boolean {
        if (size < target.size) return false
        for (index in target.indices) {
            if (this[size - target.size + index] != target[index]) return false
        }
        return true
    }

    private data class HttpRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private companion object {
        private val GENERATION_PATHS = setOf("/v1/chat/completions", "/chat/completions", "/v1/completions", "/completion")
        private val CRLFCRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val LFLF = byteArrayOf('\n'.code.toByte(), '\n'.code.toByte())
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val STREAM_HIDDEN_REASONING_HINT_TOKENS = 128
        private const val CLIENT_READ_TIMEOUT_MS = 15_000
        private const val SERVER_BACKLOG = 128
        private const val TAG = "McaLoopbackServer"
        private val STREAM_TRUE_PATTERN = Regex(""""stream"\s*:\s*(true|1|"true")""", RegexOption.IGNORE_CASE)
        private val threadIds = AtomicInteger(1)

        private fun newDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "MCA-Local-API-${threadIds.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()

        private fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logDebug(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logWarning(message: String, error: Throwable) {
            runCatching { Log.w(TAG, message, error) }
        }
    }

    private fun chatPageHtml(): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>MCA Web Chat</title>
            <style>
                :root {
                    color-scheme: light;
                    --primary: #1a73e8;
                    --primary-soft: #e8f0fe;
                    --bg: #f8f9fa;
                    --surface: #ffffff;
                    --text: #202124;
                    --muted: #5f6368;
                    --line: #e5e7eb;
                    --danger: #d93025;
                }
                * { box-sizing: border-box; }
                body {
                    margin: 0;
                    background: var(--bg);
                    color: var(--text);
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
                }
                .app {
                    max-width: 900px;
                    height: 100vh;
                    margin: 0 auto;
                    display: flex;
                    flex-direction: column;
                    background: var(--surface);
                    border-left: 1px solid var(--line);
                    border-right: 1px solid var(--line);
                }
                header {
                    padding: 14px 18px;
                    border-bottom: 1px solid var(--line);
                    display: grid;
                    grid-template-columns: 1fr auto;
                    gap: 12px;
                    align-items: center;
                }
                h1 { margin: 0; font-size: 18px; }
                .sub { margin-top: 4px; color: var(--muted); font-size: 12px; }
                .keybar {
                    display: flex;
                    gap: 8px;
                    align-items: center;
                    flex-wrap: wrap;
                    justify-content: flex-end;
                }
                input, textarea, button {
                    font: inherit;
                }
                input {
                    width: 260px;
                    padding: 9px 10px;
                    border: 1px solid var(--line);
                    border-radius: 10px;
                    outline: none;
                }
                input:focus, textarea:focus { border-color: var(--primary); }
                button {
                    border: 0;
                    border-radius: 10px;
                    padding: 9px 14px;
                    background: var(--primary);
                    color: white;
                    cursor: pointer;
                    font-weight: 600;
                }
                button.secondary {
                    background: var(--primary-soft);
                    color: var(--primary);
                }
                button.danger {
                    background: #fce8e6;
                    color: var(--danger);
                }
                button:disabled {
                    opacity: .55;
                    cursor: not-allowed;
                }
                .messages {
                    flex: 1;
                    overflow: auto;
                    padding: 18px;
                    display: flex;
                    flex-direction: column;
                    gap: 14px;
                }
                .msg {
                    max-width: 78%;
                    padding: 12px 14px;
                    border-radius: 16px;
                    white-space: pre-wrap;
                    line-height: 1.55;
                    word-break: break-word;
                }
                .user {
                    align-self: flex-end;
                    background: var(--primary);
                    color: white;
                    border-bottom-right-radius: 4px;
                }
                .assistant {
                    align-self: flex-start;
                    background: #f1f3f4;
                    color: var(--text);
                    border-bottom-left-radius: 4px;
                }
                .reasoning {
                    align-self: flex-start;
                    max-width: 78%;
                    color: var(--muted);
                    background: transparent;
                    border-left: 3px solid var(--line);
                    padding: 4px 0 4px 12px;
                    font-size: 13px;
                    line-height: 1.55;
                    white-space: pre-wrap;
                    word-break: break-word;
                }
                .reasoning summary {
                    cursor: pointer;
                    font-weight: 600;
                    color: var(--muted);
                    margin-bottom: 6px;
                }
                .empty {
                    margin: auto;
                    text-align: center;
                    color: var(--muted);
                    max-width: 520px;
                }
                .composer {
                    border-top: 1px solid var(--line);
                    padding: 14px;
                    display: grid;
                    grid-template-columns: 1fr auto auto;
                    gap: 10px;
                    align-items: end;
                    background: var(--surface);
                }
                textarea {
                    width: 100%;
                    min-height: 54px;
                    max-height: 180px;
                    resize: vertical;
                    border: 1px solid var(--line);
                    border-radius: 14px;
                    padding: 12px;
                    outline: none;
                }
                .status {
                    display: inline-flex;
                    gap: 6px;
                    align-items: center;
                    padding: 5px 8px;
                    border-radius: 999px;
                    background: var(--primary-soft);
                    color: var(--primary);
                    font-size: 12px;
                    font-weight: 600;
                }
                .dot {
                    width: 7px;
                    height: 7px;
                    border-radius: 50%;
                    background: #34a853;
                }
                @media (max-width: 700px) {
                    header { grid-template-columns: 1fr; }
                    .keybar { justify-content: stretch; }
                    input { width: 100%; }
                    .composer { grid-template-columns: 1fr; }
                    .msg { max-width: 92%; }
                }
            </style>
        </head>
        <body>
            <div class="app">
                <header>
                    <div>
                        <h1>MCA Web Chat</h1>
                        <div class="sub">
                            <span class="status"><span class="dot"></span><span id="modelText">连接中...</span></span>
                            <span id="apiText"> 请填写 MCA 页面显示的 API Key</span>
                        </div>
                    </div>
                    <div class="keybar">
                        <input id="apiKey" type="password" placeholder="API Key">
                        <button class="secondary" id="saveKey">保存 Key</button>
                        <button class="secondary" id="clearChat">清空</button>
                    </div>
                </header>
                <main id="messages" class="messages">
                    <div class="empty">
                        在手机 MCA 中开启“开放端口”后，这个页面就可以从电脑访问手机端本地模型。<br>
                        这里不会调用云端推理，消息会发送到当前手机上的 MCA 服务。
                    </div>
                </main>
                <div class="composer">
                    <textarea id="input" placeholder="输入消息，Enter 发送，Shift + Enter 换行"></textarea>
                    <button id="send">发送</button>
                    <button id="stop" class="danger" disabled>停止</button>
                </div>
            </div>
            <script>
                var apiKeyInput = document.getElementById('apiKey');
                var messagesEl = document.getElementById('messages');
                var inputEl = document.getElementById('input');
                var sendBtn = document.getElementById('send');
                var stopBtn = document.getElementById('stop');
                var modelText = document.getElementById('modelText');
                var apiText = document.getElementById('apiText');
                var controller = null;
                var chatMessages = [];

                apiKeyInput.value = localStorage.getItem('mca_api_key') || '';

                document.getElementById('saveKey').onclick = function () {
                    localStorage.setItem('mca_api_key', apiKeyInput.value.trim());
                    loadModels();
                };
                document.getElementById('clearChat').onclick = function () {
                    chatMessages = [];
                    messagesEl.innerHTML = '<div class="empty">对话已清空。</div>';
                };
                stopBtn.onclick = async function () {
                    if (controller) controller.abort();
                    await fetch('/v1/generate/stop', {
                        method: 'POST',
                        headers: authHeaders()
                    }).catch(function () {});
                    setBusy(false);
                };
                sendBtn.onclick = sendMessage;
                inputEl.addEventListener('keydown', function (event) {
                    if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault();
                        sendMessage();
                    }
                });

                function authHeaders(extra) {
                    var headers = extra || {};
                    var key = apiKeyInput.value.trim();
                    if (key) headers['Authorization'] = 'Bearer ' + key;
                    return headers;
                }

                async function loadModels() {
                    if (!apiKeyInput.value.trim()) {
                        modelText.textContent = '未填写 Key';
                        return;
                    }
                    try {
                        var res = await fetch('/v1/models', { headers: authHeaders() });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var json = await res.json();
                        var first = json.data && json.data.length ? json.data[0] : null;
                        modelText.textContent = first ? (first.display_name || first.id) : '未发现模型';
                        apiText.textContent = ' 已连接本机 MCA API';
                    } catch (error) {
                        modelText.textContent = '连接失败';
                        apiText.textContent = ' 请检查 Key、手机端开放端口和同网段网络';
                    }
                }

                function addBubble(role, text) {
                    var empty = messagesEl.querySelector('.empty');
                    if (empty) empty.remove();
                    var div = document.createElement('div');
                    div.className = 'msg ' + (role === 'user' ? 'user' : 'assistant');
                    div.textContent = text || '';
                    messagesEl.appendChild(div);
                    messagesEl.scrollTop = messagesEl.scrollHeight;
                    return div;
                }

                function addReasoningBox(beforeEl) {
                    var empty = messagesEl.querySelector('.empty');
                    if (empty) empty.remove();
                    var details = document.createElement('details');
                    details.className = 'reasoning';
                    var summary = document.createElement('summary');
                    summary.textContent = '思考过程';
                    var body = document.createElement('div');
                    details.appendChild(summary);
                    details.appendChild(body);
                    if (beforeEl && beforeEl.parentNode === messagesEl) {
                        messagesEl.insertBefore(details, beforeEl);
                    } else {
                        messagesEl.appendChild(details);
                    }
                    messagesEl.scrollTop = messagesEl.scrollHeight;
                    return body;
                }

                function setBusy(busy) {
                    sendBtn.disabled = busy;
                    stopBtn.disabled = !busy;
                    inputEl.disabled = busy;
                }

                async function sendMessage() {
                    var text = inputEl.value.trim();
                    if (!text || sendBtn.disabled) return;
                    if (!apiKeyInput.value.trim()) {
                        alert('请先填写 MCA 页面里的 API Key');
                        return;
                    }
                    inputEl.value = '';
                    addBubble('user', text);
                    chatMessages.push({ role: 'user', content: text });
                    var reasoningBody = null;
                    var reasoningText = '';
                    var assistantBubble = addBubble('assistant', '');
                    var assistantText = '';
                    controller = new AbortController();
                    setBusy(true);
                    try {
                        var res = await fetch('/v1/chat/completions', {
                            method: 'POST',
                            headers: authHeaders({ 'Content-Type': 'application/json' }),
                            body: JSON.stringify({ messages: chatMessages, stream: true }),
                            signal: controller.signal
                        });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var reader = res.body.getReader();
                        var decoder = new TextDecoder('utf-8');
                        var buffer = '';
                        while (true) {
                            var chunk = await reader.read();
                            if (chunk.done) break;
                            buffer += decoder.decode(chunk.value, { stream: true });
                            var lines = buffer.split('\n');
                            buffer = lines.pop() || '';
                            for (var i = 0; i < lines.length; i++) {
                                var line = lines[i].trim();
                                if (!line.startsWith('data:')) continue;
                                var data = line.slice(5).trim();
                                if (data === '[DONE]') {
                                    buffer = '';
                                    break;
                                }
                                try {
                                    var json = JSON.parse(data);
                                    if (json.error) {
                                        assistantText += '\n[错误] ' + json.error;
                                    } else {
                                        var delta = (((json.choices || [])[0] || {}).delta || {});
                                        var reasoning = delta.reasoning_content || '';
                                        if (reasoning) {
                                            if (!reasoningBody) reasoningBody = addReasoningBox(assistantBubble);
                                            reasoningText += reasoning;
                                            reasoningBody.textContent = reasoningText;
                                        }
                                        assistantText += delta.content || '';
                                    }
                                    assistantBubble.textContent = assistantText;
                                    messagesEl.scrollTop = messagesEl.scrollHeight;
                                } catch (ignore) {}
                            }
                        }
                        chatMessages.push({ role: 'assistant', content: assistantText });
                    } catch (error) {
                        if (error.name !== 'AbortError') {
                            assistantBubble.textContent = assistantText || ('请求失败：' + error.message);
                        }
                    } finally {
                        setBusy(false);
                    }
                }

                loadModels();
            </script>
        </body>
        </html>
    """.trimIndent()
}

