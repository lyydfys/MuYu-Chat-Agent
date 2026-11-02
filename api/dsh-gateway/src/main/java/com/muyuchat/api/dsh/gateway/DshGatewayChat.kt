package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes one chat completion call for a fully read and validated HTTP request.
 *
 * The backend flow is always consumed under an explicit child job so that a disconnect, an
 * explicit cancel or a server stop can cancel exactly this call. The gateway enforces exactly one
 * terminal outcome: a backend flow that completes without a terminal event becomes
 * `backend_stream_closed`, and no path maps `length`, `error`, EOF or cancellation onto `stop`.
 */
internal class DshGatewayChatRunner(
    private val backend: DshGatewayBackend,
    private val limits: DshGatewayLimits,
    private val logger: DshGatewayLogger
) {
    /**
     * Executes one chat completion call for a fully read and validated HTTP request.
     *
     * [scope] is the running gateway's own scope: the backend collector is launched there (not as
     * a child of the HTTP handler), so a collector blocked inside native work cannot hold the
     * response or the call lease after a cancel. The handler waits at most
     * [DshGatewayLimits.collectorDrainTimeoutMillis] for it to unwind.
     */
    suspend fun execute(
        socket: Socket,
        parsed: DshOpenAiParse.Success,
        request: DshModelRequest,
        call: DshGatewayCall,
        scope: CoroutineScope
    ) {
        if (request.stream) {
            streamResponse(socket, parsed.includeUsage, request, call, scope)
        } else {
            bufferedResponse(socket, request, call, scope)
        }
    }

    private suspend fun streamResponse(
        socket: Socket,
        includeUsage: Boolean,
        request: DshModelRequest,
        call: DshGatewayCall,
        scope: CoroutineScope
    ) = coroutineScope {
        val writer = DshGatewaySseWriter(socket)
        val completionId = DshOpenAiWire.newCompletionId()
        val created = System.currentTimeMillis() / 1000L
        val monitorArmed = AtomicBoolean(true)
        var heartbeat: Job? = null
        var monitor: Job? = null
        try {
            writer.start(completionId, created, request.modelId, call.callId)
            heartbeat = launch { heartbeatLoop(writer, call) }
            monitor = launchDisconnectMonitor(socket, call, monitorArmed)
            val outcome = collectOutcome(request, call, scope) { delta ->
                writeDelta(writer, completionId, created, request.modelId, delta)
            }
            if (call.cancelReason == DshCallCancelReason.CLIENT_DISCONNECT) {
                return@coroutineScope
            }
            when (outcome) {
                is DshStreamOutcome.Completed -> {
                    writer.frame(
                        DshOpenAiWire.finishChunk(
                            completionId, created, request.modelId,
                            DshOpenAiWire.finishReason(outcome.finishReason)
                        )
                    )
                    if (includeUsage) {
                        DshOpenAiWire.usageChunk(
                            completionId, created, request.modelId,
                            outcome.promptTokens, outcome.completionTokens
                        )?.let(writer::frame)
                    }
                    writer.done()
                }
                is DshStreamOutcome.Failed -> {
                    writer.frame(
                        DshOpenAiWire.finishChunk(
                            completionId, created, request.modelId,
                            DshOpenAiWire.finishReason(DshFinishReason.ERROR)
                        )
                    )
                    writer.frame(
                        DshOpenAiWire.errorChunk("server_error", outcome.code, outcome.message)
                    )
                    writer.done()
                }
                is DshStreamOutcome.Cancelled -> {
                    writer.frame(
                        DshOpenAiWire.finishChunk(
                            completionId, created, request.modelId,
                            DshOpenAiWire.finishReason(DshFinishReason.CANCELLED)
                        )
                    )
                    writer.done()
                }
            }
        } catch (io: IOException) {
            call.requestCancel(DshCallCancelReason.CLIENT_DISCONNECT)
        } finally {
            monitorArmed.set(false)
            withContext(NonCancellable) {
                heartbeat?.cancelAndJoin()
                // Closing the socket is what wakes a monitor blocked in a blocking
                // InputStream.read(); cancellation alone does not interrupt blocking socket I/O.
                runCatching { socket.close() }
                monitor?.cancelAndJoin()
            }
            writer.closeQuietly()
        }
    }

    private suspend fun bufferedResponse(
        socket: Socket,
        request: DshModelRequest,
        call: DshGatewayCall,
        scope: CoroutineScope
    ) = coroutineScope {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val monitorArmed = AtomicBoolean(true)
        var monitor: Job? = null
        try {
            monitor = launchDisconnectMonitor(socket, call, monitorArmed)
            val outcome = collectOutcome(request, call, scope) { delta ->
                when (delta) {
                    is DshEmittedDelta.Text -> text.append(delta.text)
                    is DshEmittedDelta.Reasoning -> reasoning.append(delta.text)
                    is DshEmittedDelta.ToolCall -> Unit
                }
            }
            if (call.cancelReason == DshCallCancelReason.CLIENT_DISCONNECT) {
                return@coroutineScope
            }
            val callHeaders = mapOf("X-MCA-Call-Id" to call.callId)
            when (outcome) {
                is DshStreamOutcome.Completed -> {
                    val body = DshOpenAiWire.completionJson(
                        id = DshOpenAiWire.newCompletionId(),
                        created = System.currentTimeMillis() / 1000L,
                        model = request.modelId,
                        content = text.toString(),
                        reasoning = reasoning.toString(),
                        toolCalls = outcome.toolCalls,
                        finishReason = outcome.finishReason,
                        promptTokens = outcome.promptTokens,
                        completionTokens = outcome.completionTokens
                    )
                    DshGatewayHttp.writeJson(socket, 200, body, callHeaders)
                }
                is DshStreamOutcome.Failed -> {
                    DshGatewayHttp.writeError(
                        socket = socket,
                        status = backendErrorStatus(outcome.code),
                        type = "server_error",
                        code = outcome.code,
                        message = outcome.message,
                        extraHeaders = callHeaders
                    )
                }
                is DshStreamOutcome.Cancelled -> {
                    DshGatewayHttp.writeError(
                        socket = socket,
                        status = 499,
                        type = "server_error",
                        code = outcome.code,
                        message = "The model call was cancelled.",
                        extraHeaders = callHeaders
                    )
                }
            }
        } catch (io: IOException) {
            call.requestCancel(DshCallCancelReason.CLIENT_DISCONNECT)
        } finally {
            monitorArmed.set(false)
            withContext(NonCancellable) {
                runCatching { socket.close() }
                monitor?.cancelAndJoin()
            }
        }
    }

    /**
     * Consumes the backend flow until exactly one terminal outcome or a cancellation.
     *
     * The terminal outcome is written into a deferred by the collector; the handler races that
     * deferred against the call's cancellation signal. The collector runs on [scope] (the running
     * gateway's scope), not on the HTTP handler's coroutine, so a collector blocked inside native
     * work cannot keep the response or the call lease alive: after a cancel the handler gives the
     * collector at most [DshGatewayLimits.collectorDrainTimeoutMillis] to unwind and then settles
     * the call anyway. The backend work itself is stopped out of band through
     * [DshGatewayBackend.cancel]; no path waits for native settle evidence.
     */
    private suspend fun collectOutcome(
        request: DshModelRequest,
        call: DshGatewayCall,
        scope: CoroutineScope,
        onDelta: (DshEmittedDelta) -> Unit
    ): DshStreamOutcome {
        val done = CompletableDeferred<DshStreamOutcome>()
        val assembler = DshToolCallAssembler(
            maxCalls = limits.maxToolCallsPerResponse,
            callId = request.callId,
            maxArgumentChars = limits.maxToolArgumentChars
        )
        var emittedChars = 0L

        fun account(text: String) {
            emittedChars += text.length
            if (emittedChars > limits.maxResponseChars) {
                throw DshResponseTooLargeException(
                    "The model response exceeded ${limits.maxResponseChars} characters."
                )
            }
        }

        val collector = scope.launch {
            try {
                backend.stream(request).takeWhile { !done.isCompleted }.collect { event ->
                    when (event) {
                        is DshModelEvent.TextDelta -> if (event.text.isNotEmpty()) {
                            account(event.text)
                            onDelta(DshEmittedDelta.Text(event.text))
                        }
                        is DshModelEvent.ReasoningDelta -> if (event.text.isNotEmpty()) {
                            account(event.text)
                            onDelta(DshEmittedDelta.Reasoning(event.text))
                        }
                        is DshModelEvent.ToolCallDelta -> {
                            event.argumentsFragment?.let(::account)
                            assembler.onDelta(event).forEach(onDelta)
                        }
                        is DshModelEvent.Completed -> done.complete(assembler.terminate(event))
                        is DshModelEvent.Failed -> done.complete(
                            DshStreamOutcome.Failed(
                                code = safeCode(event.code),
                                message = safeMessage(event.message),
                                retryable = event.retryable
                            )
                        )
                    }
                }
                if (!done.isCompleted) {
                    done.complete(
                        DshStreamOutcome.Failed(
                            code = "backend_stream_closed",
                            message = "The model backend stream ended without a terminal event.",
                            retryable = true
                        )
                    )
                }
            } catch (tooLarge: DshResponseTooLargeException) {
                warn("DSH gateway response limit reached.")
                if (!done.isCompleted) {
                    done.complete(
                        DshStreamOutcome.Failed(
                            code = "response_too_large",
                            message = tooLarge.message ?: "The model response exceeded the gateway limit.",
                            retryable = false
                        )
                    )
                }
            } catch (io: IOException) {
                call.requestCancel(DshCallCancelReason.CLIENT_DISCONNECT)
                if (!done.isCompleted) done.complete(DshStreamOutcome.Cancelled("client_disconnect"))
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!done.isCompleted) done.complete(DshStreamOutcome.Cancelled("backend_cancelled"))
            } catch (protocol: DshToolCallProtocolException) {
                warn("DSH gateway backend tool-call protocol failure (${protocol.code}).")
                if (!done.isCompleted) {
                    done.complete(
                        DshStreamOutcome.Failed(
                            code = protocol.code,
                            message = protocol.message ?: "The model backend produced an invalid tool call.",
                            retryable = false
                        )
                    )
                }
            } catch (other: Throwable) {
                warn("DSH gateway backend stream failed (${other.javaClass.simpleName}).")
                if (!done.isCompleted) {
                    done.complete(
                        DshStreamOutcome.Failed(
                            code = "backend_error",
                            message = safeMessage(other.message ?: "The model backend failed."),
                            retryable = true
                        )
                    )
                }
            }
        }
        call.attachCollector(collector)
        val cancelledFirst = select<DshStreamOutcome?> {
            done.onAwait { outcome -> outcome }
            call.cancellationSignal.onAwait { null }
        }
        // Bound how long the HTTP handler waits for the collector to unwind. A native prefill
        // blocked in the worker's beginCompletion Binder call ignores coroutine cancellation for
        // minutes (device evidence 2026-10-26); without this bound the registry entry, the SSE
        // response and `/healthz active_calls` would linger exactly that long.
        collector.cancel(CancellationException("DshGateway call collector is being settled."))
        val drained = withTimeoutOrNull(limits.collectorDrainTimeoutMillis) { collector.join() }
        if (drained == null) {
            warn(
                "DSH gateway backend collector did not unwind within " +
                    "${limits.collectorDrainTimeoutMillis} ms; the call is settled without " +
                    "waiting for native work."
            )
        }
        return when {
            cancelledFirst != null -> cancelledFirst
            call.cancelReason != null -> DshStreamOutcome.Cancelled("cancelled")
            else -> done.await()
        }
    }

    private suspend fun heartbeatLoop(writer: DshGatewaySseWriter, call: DshGatewayCall) {
        while (currentCoroutineContext().isActive) {
            delay(limits.streamHeartbeatMillis)
            try {
                // A data event, not only a comment: the official client's idle watchdog ignores
                // SSE comments and aborts a >300 s quiet prefill as TIMEOUT.
                writer.idleHeartbeat("mca-heartbeat")
            } catch (io: IOException) {
                call.requestCancel(DshCallCancelReason.CLIENT_DISCONNECT)
                return
            }
        }
    }

    /**
     * Watches the request half of the socket for a vanished peer.
     *
     * Once the request body has been fully read the socket has no further expected input, so an
     * EOF or reset means the caller is gone. [armed] is cleared by the request coroutine before
     * it closes the socket during its own cleanup, so an orderly close is never mistaken for a
     * client disconnect.
     */
    private fun CoroutineScope.launchDisconnectMonitor(
        socket: Socket,
        call: DshGatewayCall,
        armed: AtomicBoolean
    ): Job = launch {
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(1)
            while (isActive) {
                val read = try {
                    input.read(buffer)
                } catch (timeout: SocketTimeoutException) {
                    continue
                }
                if (read < 0) {
                    if (armed.get()) call.requestCancel(DshCallCancelReason.CLIENT_DISCONNECT)
                    return@launch
                }
            }
        } catch (io: IOException) {
            if (armed.get() && isActive) {
                call.requestCancel(DshCallCancelReason.CLIENT_DISCONNECT)
            }
        }
    }

    private fun writeDelta(
        writer: DshGatewaySseWriter,
        completionId: String,
        created: Long,
        model: String,
        delta: DshEmittedDelta
    ) {
        when (delta) {
            is DshEmittedDelta.Text -> writer.frame(
                DshOpenAiWire.contentChunk(completionId, created, model, delta.text)
            )
            is DshEmittedDelta.Reasoning -> writer.frame(
                DshOpenAiWire.reasoningChunk(completionId, created, model, delta.text)
            )
            is DshEmittedDelta.ToolCall -> writer.frame(
                DshOpenAiWire.toolCallChunk(
                    id = completionId,
                    created = created,
                    model = model,
                    index = delta.index,
                    toolCallId = delta.toolCallId,
                    name = delta.name,
                    argumentsFragment = delta.argumentsFragment
                )
            )
        }
    }

    private fun backendErrorStatus(code: String): Int = when {
        code.equals("context_length_exceeded", ignoreCase = true) ||
            code.equals("context_window_exceeded", ignoreCase = true) -> 413
        code.equals("model_not_loaded", ignoreCase = true) ||
            code.equals("engine_unavailable", ignoreCase = true) ||
            code.equals("server_busy", ignoreCase = true) -> 503
        else -> 502
    }

    private fun safeCode(code: String): String {
        val normalized = code.trim()
        return if (normalized.isEmpty()) "backend_failed" else normalized.take(64)
    }

    private fun safeMessage(message: String): String =
        message.replace(Regex("[\\r\\n\\t]"), " ").trim().take(300)

    private fun warn(message: String) {
        runCatching { logger.log(DshGatewayLogLevel.WARN, message) }
    }
}
