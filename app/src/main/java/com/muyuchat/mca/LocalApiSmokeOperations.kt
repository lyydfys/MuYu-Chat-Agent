package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.api.local.McaLoopbackServer
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal fun freeLoopbackPort(): Int =
    ServerSocket(0).use { it.localPort }

internal fun rawHttp(port: Int, request: String, readTimeoutMs: Int = 15_000): String =
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = readTimeoutMs
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        socket.shutdownOutput()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

internal suspend fun runLoopbackApiStreamSmokeFor(): LocalApiStreamSmokeResult = withContext(Dispatchers.IO) {
    val previousStreamProvider = LocalApiRuntime.streamChatProvider
    val previousStopProvider = LocalApiRuntime.stopGenerationProvider
    val stopCalls = AtomicInteger(0)
    val port = freeLoopbackPort()
    val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = "")
    try {
        LocalApiRuntime.streamChatProvider = {
            flowOf(
                GenerateEvent.Chunk(
                    text = "",
                    stats = RuntimeStats(completionTokens = 128),
                    hiddenReasoning = true
                ),
                GenerateEvent.Chunk(
                    text = "visible answer",
                    stats = RuntimeStats(completionTokens = 130)
                ),
            )
        }
        LocalApiRuntime.stopGenerationProvider = { stopCalls.incrementAndGet() }
        server.start()
        val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"hide_reasoning":true}"""
        val response = rawHttp(
            port = port,
            request = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                body
        )
        val visibleSeen = response.contains("visible answer")
        val doneSeen = response.contains("data: [DONE]")
        if (!response.startsWith("HTTP/1.1 200 OK")) error("本机 API smoke 返回异常")
        if (!visibleSeen) error("本机 API smoke 未收到可见正文")
        if (!doneSeen) error("本机 API smoke 未收到 [DONE] 终止帧")
        if (response.contains("[MCA 提示]")) error("本机 API smoke 仍出现隐藏思考占位提示")
        if (stopCalls.get() != 0) error("本机 API smoke 触发了 stopGeneration")
        LocalApiStreamSmokeResult(
            visibleContentSeen = true,
            doneSeen = true,
            stopCalls = stopCalls.get()
        )
    } finally {
        server.shutdown()
        LocalApiRuntime.streamChatProvider = previousStreamProvider
        LocalApiRuntime.stopGenerationProvider = previousStopProvider
    }
}

internal suspend fun runLoopbackEngineStreamSmokeFor(params: GenerationParams): LocalApiStreamSmokeResult = withContext(Dispatchers.IO) {
    val port = freeLoopbackPort()
    val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = "")
    try {
        server.start()
        val body = JSONObject()
            .put("model", "mca-local")
            .put("stream", true)
            .put("hide_reasoning", true)
            .put("reasoning_mode", "off")
            .put("max_tokens", 32)
            .put("n_ctx", params.nCtx)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "用一句中文回答：本机 API 实流自检完成。")
                )
            )
            .toString()
        val response = withTimeout(90_000L) {
            rawHttp(
                port = port,
                request = "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                    body,
                readTimeoutMs = 90_000
            )
        }
        if (!response.startsWith("HTTP/1.1 200 OK")) {
            error("本机 API 实流 smoke 返回异常：${response.lineSequence().firstOrNull().orEmpty()}")
        }
        if (!response.contains("data: [DONE]")) error("本机 API 实流 smoke 未收到 [DONE] 终止帧")
        if (response.contains("[MCA 提示]")) error("本机 API 实流 smoke 仍出现隐藏思考占位提示")
        val visibleSeen = Regex(""""content"\s*:\s*"(?!")""").containsMatchIn(response)
        if (!visibleSeen) error("本机 API 实流 smoke 未收到可见正文")
        LocalApiStreamSmokeResult(
            visibleContentSeen = true,
            doneSeen = true,
            stopCalls = 0
        )
    } finally {
        server.shutdown()
    }
}
