package com.muyuchat.api.local

import com.muyuchat.core.engine.contract.ChatRequest
import com.muyuchat.core.engine.contract.GenerateEvent
import com.muyuchat.core.engine.contract.GenerateEventPhase
import com.muyuchat.core.engine.contract.GenerateEventPersist
import com.muyuchat.core.engine.contract.GenerateEventChunk
import com.muyuchat.core.engine.contract.GenerateEventDone
import com.muyuchat.core.engine.contract.GenerateEventError
import com.muyuchat.core.engine.contract.GenerationParams
import com.muyuchat.core.engine.contract.LocalChatExecutionContext
import com.muyuchat.core.engine.contract.RuntimeStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

    internal suspend fun McaLoopbackServer.streamChat(
        socket: Socket,
        request: ChatRequest,
        lease: GenerationLease
    ) = coroutineScope {
        lease.job = currentCoroutineContext()[Job]
        if (lease.stopRequested) throw CancellationException("Local API request was stopped before execution.")
        val requestId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        lease.requestId = requestId
        val sequenceBefore = LocalApiRuntime.generationSequence()
        val stream = LocalApiRuntime.streamChat(
            request,
            LocalChatExecutionContext(requestId = requestId)
        )
        if (stream == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "engine_unavailable",
                "MCA engine is not attached.",
                generationTraceJson(requestId, null).toString()
            )
            return@coroutineScope
        }
        val created = System.currentTimeMillis() / 1000
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        var hasVisibleContent = false
        var terminalSent = false
        var finalStats: RuntimeStats? = null
        var generationSequence: Long? = null
        fun captureGenerationSequence() {
            generationSequence = generationSequence
                ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                    sequenceBefore?.let { current > it } ?: (current > 0L)
                }
            generationSequence?.let { LocalApiRuntime.recordGenerationSequence(requestId, it) }
        }
        fun writeTerminalFrame(includeEmptyVisibleError: Boolean = true, completed: Boolean = true) {
            if (terminalSent) return
            if (includeEmptyVisibleError && !hasVisibleContent) {
                output.write(
                    "data: ${errorJson(
                        "generation_empty_visible_output",
                        emptyVisibleOutputMessage(finalStats, request.params),
                        generationTraceJson(requestId, generationSequence).toString()
                    )}\n\n"
                )
            }
            if (completed && (!includeEmptyVisibleError || hasVisibleContent)) {
                output.write("data: ${finishSseJson(requestId, created, generationSequence)}\n\n")
            }
            output.write("data: [DONE]\n\n")
            terminalSent = true
        }
        synchronized(clientSocketLock) { responseStartedSockets.add(socket) }
        output.write("HTTP/1.1 200 OK\r\n")
        output.write("X-MCA-Request-Id: ${lease.clientRequestId.ifBlank { requestId }}\r\n")
        output.write("Content-Type: text/event-stream; charset=utf-8\r\n")
        output.write("Cache-Control: no-cache\r\n")
        output.write("X-Accel-Buffering: no\r\n")
        output.write(corsHeaders())
        output.write("Connection: close\r\n\r\n")
        output.write("data: ${roleSseJson(requestId, created)}\n\n")
        output.flush()
        val heartbeat = launch {
            while (isActive) {
                delay(SSE_HEARTBEAT_INTERVAL_MS)
                synchronized(output) {
                    output.write(": mca-prefill\n\n")
                    output.flush()
                }
            }
        }
        withChatClientDisconnectCancellation(socket, requestId) {
            try {
                stream.collect { event ->
                    if (terminalSent) return@collect
                    captureGenerationSequence()
                    synchronized(output) {
                        when (event) {
                            is GenerateEventPhase -> finalStats = event.stats
                            is GenerateEventPersist -> Unit
                            is GenerateEventChunk -> {
                                finalStats = event.stats
                                if (event.reasoning.isNotEmpty()) {
                                    output.write(
                                        "data: ${event.reasoning.toSseJson(requestId, created, generationSequence, reasoning = true)}\n\n"
                                    )
                                }
                                if (event.text.isNotEmpty()) {
                                    hasVisibleContent = true
                                    output.write("data: ${event.text.toSseJson(requestId, created, generationSequence)}\n\n")
                                }
                            }
                            is GenerateEventDone -> {
                                finalStats = event.stats
                                writeTerminalFrame()
                            }
                            is GenerateEventError -> {
                                output.write(
                                    "data: ${errorJson(
                                        generationErrorCode(event),
                                        event.message,
                                        generationErrorDetails(event, requestId, generationSequence).toString()
                                    )}\n\n"
                                )
                                writeTerminalFrame(includeEmptyVisibleError = false, completed = false)
                            }
                        }
                        output.flush()
                    }
                }
            } catch (cancelled: CancellationException) {
                // Client stop (/v1/generate/stop), server stop and peer-disconnect cancel this
                // coroutine. The SSE protocol still has to close with its terminal frame
                // instead of a bare FIN: finish_reason + data: [DONE] (BUG-3 / B45-S1).
                heartbeat.cancel()
                withContext(NonCancellable) {
                    runCatching {
                        synchronized(output) {
                            writeTerminalFrame(includeEmptyVisibleError = false)
                            output.flush()
                        }
                    }
                }
                throw cancelled
            } finally {
                withContext(NonCancellable) { heartbeat.cancelAndJoin() }
            }
            synchronized(output) {
                writeTerminalFrame()
                output.flush()
            }
        }
    }

    internal suspend fun McaLoopbackServer.completeChat(
        socket: Socket,
        request: ChatRequest,
        lease: GenerationLease
    ) {
        lease.job = currentCoroutineContext()[Job]
        if (lease.stopRequested) throw CancellationException("Local API request was stopped before execution.")
        val requestId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        lease.requestId = requestId
        val sequenceBefore = LocalApiRuntime.generationSequence()
        val stream = LocalApiRuntime.streamChat(
            request,
            LocalChatExecutionContext(requestId = requestId)
        )
        if (stream == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "engine_unavailable",
                "MCA engine is not attached.",
                generationTraceJson(requestId, null).toString()
            )
            return
        }
        val created = System.currentTimeMillis() / 1000
        val builder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var generationError: GenerateEventError? = null
        var finalStats: RuntimeStats? = null
        var generationSequence: Long? = null
        withChatClientDisconnectCancellation(socket, requestId) {
            var terminalCaptured = false
            stream.collect { event ->
                if (terminalCaptured) return@collect
                generationSequence = generationSequence
                    ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                        sequenceBefore?.let { current > it } ?: (current > 0L)
                    }
                generationSequence?.let { LocalApiRuntime.recordGenerationSequence(requestId, it) }
                when (event) {
                    is GenerateEventPhase -> finalStats = event.stats
                    is GenerateEventPersist -> Unit
                    is GenerateEventChunk -> {
                        finalStats = event.stats
                        if (event.text.isNotEmpty()) builder.append(event.text)
                        if (event.reasoning.isNotEmpty()) reasoningBuilder.append(event.reasoning)
                    }
                    is GenerateEventDone -> {
                        finalStats = event.stats
                        terminalCaptured = true
                    }
                    is GenerateEventError -> {
                        generationError = event
                        terminalCaptured = true
                    }
                }
            }
            if (generationError != null) {
                val error = requireNotNull(generationError)
                writeError(
                    socket,
                    generationErrorStatus(error),
                    generationErrorCode(error),
                    error.message,
                    generationErrorDetails(error, requestId, generationSequence).toString()
                )
                return@withChatClientDisconnectCancellation
            }
            if (builder.isEmpty()) {
                writeError(
                    socket,
                    "500 Internal Server Error",
                    "generation_empty_visible_output",
                    emptyVisibleOutputMessage(finalStats, request.params),
                    generationTraceJson(requestId, generationSequence).toString()
                )
                return@withChatClientDisconnectCancellation
            }
            val response = JSONObject()
                .put("id", requestId)
                .put("object", "chat.completion")
                .put("created", created)
                .put("model", currentModelName())
                .put("mca_trace", generationTraceJson(requestId, generationSequence))
                .put(
                    "choices",
                    JSONArray().put(
                        run {
                            val message = JSONObject()
                                .put("role", "assistant")
                                .put("content", builder.toString())
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
            writeJson(socket, response.toString(), extraHeaders = mapOf("X-MCA-Request-Id" to lease.clientRequestId.ifBlank { requestId }))
        }
    }

    /**
     * Detect a client that has closed an in-flight chat connection and stop the exact native
     * request before the generation lease is released. Without this bridge an SSE consumer can
     * disappear after the first chunk while the worker keeps the single local generation slot
     * occupied until its natural completion.
     */
    internal suspend fun <T> McaLoopbackServer.withChatClientDisconnectCancellation(
        socket: Socket,
        requestId: String,
        block: suspend () -> T
    ): T = coroutineScope {
        val requestJob = requireNotNull(currentCoroutineContext()[Job]) {
            "Local API chat generation requires a request coroutine."
        }
        val completed = AtomicBoolean(false)
        val disconnected = AtomicBoolean(false)
        val input = socket.getInputStream()
        val monitor = launch {
            while (isActive && !completed.get()) {
                try {
                    if (input.read() < 0) {
                        // A client may half-close its request output while continuing to read the
                        // response (curl and the JVM contract tests do this). Probe the peer's
                        // write side before treating EOF as a vanished response consumer.
                        while (isActive && !completed.get()) {
                            delay(200L)
                            if (completed.get()) return@launch
                            try {
                                socket.sendUrgentData(0)
                            } catch (error: IOException) {
                                disconnected.set(true)
                                requestJob.cancel(
                                    CancellationException(
                                        "Local API chat client disconnected: ${error.javaClass.simpleName}."
                                    )
                                )
                                return@launch
                            }
                        }
                        return@launch
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue polling until the request completes or the client resets it.
                } catch (error: IOException) {
                    if (!completed.get()) {
                        disconnected.set(true)
                        requestJob.cancel(
                            CancellationException(
                                "Local API chat client disconnected: ${error.javaClass.simpleName}."
                            )
                        )
                    }
                    return@launch
                }
            }
        }
        // A client that stops reading an SSE response often keeps its request side open. In
        // that case the input reader above can remain blocked until the socket timeout, leaving
        // the single local generation lease occupied for several seconds. A lightweight TCP
        // urgent-data probe detects a peer FIN/RST independently of the request half-close. It
        // does not write into the HTTP response stream and therefore cannot corrupt SSE frames.
        val peerProbe = launch {
            while (isActive && !completed.get()) {
                delay(250L)
                if (completed.get()) return@launch
                try {
                    socket.sendUrgentData(0)
                } catch (error: IOException) {
                    if (error.isPeerDisconnect()) {
                        disconnected.set(true)
                        requestJob.cancel(
                            CancellationException(
                                "Local API chat client disconnected: ${error.javaClass.simpleName}."
                            )
                        )
                        return@launch
                    }
                    // Some Android/desktop TCP stacks reject OOB probes while the connection is
                    // still healthy. Keep the input monitor and normal write path authoritative
                    // for those stacks instead of treating the capability error as a disconnect.
                }
            }
        }
        try {
            block()
        } catch (error: IOException) {
            withContext(NonCancellable) {
                disconnected.set(true)
                runCatching { LocalApiRuntime.stopGenerationIfRequestActive(requestId) }
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                completed.set(true)
                runCatching { if (!socket.isInputShutdown) socket.shutdownInput() }
                monitor.cancelAndJoin()
                peerProbe.cancelAndJoin()
                if (disconnected.get()) {
                    runCatching {
                        LocalApiRuntime.stopGenerationIfRequestActive(requestId)
                    }
                }
            }
        }
    }

    internal fun emptyVisibleOutputMessage(stats: RuntimeStats?, params: GenerationParams): String {
        val generated = stats?.completionTokens?.takeIf { it > 0 }
        val limit = params.nPredict
        val recommended = when {
            limit < 2048 -> "4096"
            limit < 4096 -> "8192"
            limit < 8192 -> "16384"
            else -> "更高的输出长度"
        }
        return buildString {
            append("模型本轮")
            if (generated != null) append("已生成 ").append(generated).append(" token，")
            append("但没有产出可见正文。通常是思考模型把输出预算消耗在推理阶段，或思考内容被隐藏。")
            append("当前 max_tokens/n_predict=").append(limit).append("；")
            append("建议提高到 ").append(recommended).append("，或设置 reasoning_mode=off、hide_reasoning=true 后重试。")
        }
    }

        // Keep a disconnected SSE client observable before the native request can hold the
        // single generation slot for several seconds.
        internal const val SSE_HEARTBEAT_INTERVAL_MS = 1_000L
