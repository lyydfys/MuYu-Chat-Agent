package com.muyuchat.api.local

import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.RuntimeStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.util.UUID

    internal suspend fun McaLoopbackServer.streamResponses(
        socket: Socket,
        request: LocalResponsesRequest,
        lease: GenerationLease
    ) = coroutineScope {
        lease.job = currentCoroutineContext()[Job]
        if (lease.stopRequested) throw CancellationException("Local API request was stopped before execution.")
        val responseId = "resp-${UUID.randomUUID().toString().replace("-", "")}";
        lease.requestId = responseId
        val messageId = "msg-${responseId.removePrefix("resp-")}";
        val eventEncoder = LocalResponsesEventEncoder(messageId)
        val created = System.currentTimeMillis() / 1000L
        val stream = LocalApiRuntime.streamChat(
            request.chatRequest,
            LocalChatExecutionContext(requestId = responseId)
        )
        if (stream == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "engine_unavailable",
                "MCA engine is not attached."
            )
            return@coroutineScope
        }
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var finalStats: RuntimeStats? = null
        var generationError: GenerateEvent.Error? = null
        var generationSequence: Long? = null
        val sequenceBefore = LocalApiRuntime.generationSequence()
        fun capture(event: GenerateEvent) {
            generationSequence = generationSequence
                ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                    sequenceBefore?.let { current > it } ?: (current > 0L)
                }
            generationSequence?.let { LocalApiRuntime.recordGenerationSequence(responseId, it) }
            when (event) {
                is GenerateEvent.Phase -> finalStats = event.stats
                is GenerateEvent.Persist -> Unit
                is GenerateEvent.Chunk -> {
                    finalStats = event.stats
                    if (event.text.isNotEmpty()) text.append(event.text)
                    reasoning.append(event.reasoning)
                }
                is GenerateEvent.Done -> finalStats = event.stats
                is GenerateEvent.Error -> generationError = event
            }
        }
        fun sendEvent(type: String, payload: JSONObject) {
            output.write("event: $type\n")
            output.write("data: ${payload}\n\n")
            output.flush()
        }
        withChatClientDisconnectCancellation(socket, responseId) {
            synchronized(clientSocketLock) { responseStartedSockets.add(socket) }
            output.write("HTTP/1.1 200 OK\r\n")
            output.write("X-MCA-Request-Id: ${lease.clientRequestId.ifBlank { responseId }}\r\n")
            output.write("Content-Type: text/event-stream; charset=utf-8\r\n")
            output.write("Cache-Control: no-cache\r\n")
            output.write("X-Accel-Buffering: no\r\n")
            output.write(corsHeaders())
            output.write("Connection: close\r\n\r\n")
            output.flush()
            sendEvent(
                "response.created",
                eventEncoder.response(
                    "response.created",
                    responsesEnvelope(responseId, created, request.model, "in_progress", "", "", null)
                )
            )
            sendEvent(
                "response.in_progress",
                eventEncoder.response(
                    "response.in_progress",
                    responsesEnvelope(responseId, created, request.model, "in_progress", "", "", null)
                )
            )
            sendEvent(
                "response.output_item.added",
                eventEncoder.item("response.output_item.added", responsesOutputItem(messageId, "in_progress", ""))
                    .put("response_id", responseId)
            )
            sendEvent(
                "response.content_part.added",
                eventEncoder.part(
                    "response.content_part.added",
                    JSONObject().put("type", "output_text").put("text", "").put("annotations", JSONArray())
                )
                    .put("response_id", responseId)
            )
            var terminalCaptured = false
            try {
                stream.collect { event ->
                    if (terminalCaptured) return@collect
                    capture(event)
                    when (event) {
                        is GenerateEvent.Chunk -> {
                            if (event.text.isNotEmpty()) {
                                sendEvent(
                                    "response.output_text.delta",
                                    eventEncoder.textDelta(event.text).put("response_id", responseId)
                                )
                            }
                        }
                        is GenerateEvent.Phase,
                        is GenerateEvent.Persist -> Unit
                        is GenerateEvent.Done,
                        is GenerateEvent.Error -> terminalCaptured = true
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        sendEvent(
                            "response.failed",
                            eventEncoder.response(
                                "response.failed",
                                responsesEnvelope(responseId, created, request.model, "failed", text.toString(), "", finalStats)
                                    .put("error", errorJson("generation_cancelled", "Local API generation was cancelled.").getJSONObject("error"))
                            )
                        )
                    }
                }
                throw cancelled
            } catch (error: IOException) {
                throw error
            } catch (error: Throwable) {
                generationError = GenerateEvent.Error(
                    message = error.message ?: "Local API generation failed.",
                    stats = finalStats ?: RuntimeStats(),
                    code = "generation_failed"
                )
            }
            if (generationError != null) {
                val error = requireNotNull(generationError)
                val failedResponse = responsesEnvelope(
                    responseId, created, request.model, "failed", text.toString(), reasoning.toString(), finalStats
                ).put("error", errorJson(generationErrorCode(error), error.message).getJSONObject("error"))
                sendEvent(
                    "response.failed",
                    eventEncoder.response("response.failed", failedResponse)
                )
                return@withChatClientDisconnectCancellation
            }
            if (text.isEmpty()) {
                val failedResponse = responsesEnvelope(
                    responseId, created, request.model, "failed", "", reasoning.toString(), finalStats
                ).put(
                    "error",
                    errorJson("generation_empty_visible_output", emptyVisibleOutputMessage(finalStats, request.chatRequest.params))
                        .getJSONObject("error")
                )
                sendEvent(
                    "response.failed",
                    eventEncoder.response("response.failed", failedResponse)
                )
                return@withChatClientDisconnectCancellation
            }
            sendEvent(
                "response.output_text.done",
                eventEncoder.textDone(text.toString()).put("response_id", responseId)
            )
            sendEvent(
                "response.content_part.done",
                eventEncoder.part(
                    "response.content_part.done",
                    JSONObject().put("type", "output_text").put("text", text.toString()).put("annotations", JSONArray())
                ).put("response_id", responseId)
            )
            sendEvent(
                "response.output_item.done",
                eventEncoder.item("response.output_item.done", responsesOutputItem(messageId, "completed", text.toString()))
                    .put("response_id", responseId)
            )
            sendEvent(
                "response.completed",
                eventEncoder.response(
                    "response.completed",
                    responsesEnvelope(responseId, created, request.model, "completed", text.toString(), reasoning.toString(), finalStats)
                )
            )
        }
    }

    internal suspend fun McaLoopbackServer.completeResponses(
        socket: Socket,
        request: LocalResponsesRequest,
        lease: GenerationLease
    ) = coroutineScope {
        lease.job = currentCoroutineContext()[Job]
        if (lease.stopRequested) throw CancellationException("Local API request was stopped before execution.")
        val responseId = "resp-${UUID.randomUUID().toString().replace("-", "")}";
        lease.requestId = responseId
        val created = System.currentTimeMillis() / 1000L
        val stream = LocalApiRuntime.streamChat(
            request.chatRequest,
            LocalChatExecutionContext(requestId = responseId)
        )
        if (stream == null) {
            writeError(socket, "503 Service Unavailable", "engine_unavailable", "MCA engine is not attached.")
            return@coroutineScope
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var finalStats: RuntimeStats? = null
        var generationError: GenerateEvent.Error? = null
        var generationSequence: Long? = null
        val sequenceBefore = LocalApiRuntime.generationSequence()
        withChatClientDisconnectCancellation(socket, responseId) {
            var terminalCaptured = false
            stream.collect { event ->
                if (terminalCaptured) return@collect
                generationSequence = generationSequence
                    ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                        sequenceBefore?.let { current > it } ?: (current > 0L)
                    }
                generationSequence?.let { LocalApiRuntime.recordGenerationSequence(responseId, it) }
                when (event) {
                    is GenerateEvent.Phase -> finalStats = event.stats
                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Chunk -> {
                        finalStats = event.stats
                        text.append(event.text)
                        reasoning.append(event.reasoning)
                    }
                    is GenerateEvent.Done -> {
                        finalStats = event.stats
                        terminalCaptured = true
                    }
                    is GenerateEvent.Error -> {
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
                    generationErrorDetails(error, responseId, generationSequence).toString()
                )
                return@withChatClientDisconnectCancellation
            }
            if (text.isEmpty()) {
                writeError(
                    socket,
                    "500 Internal Server Error",
                    "generation_empty_visible_output",
                    emptyVisibleOutputMessage(finalStats, request.chatRequest.params),
                    generationTraceJson(responseId, generationSequence).toString()
                )
                return@withChatClientDisconnectCancellation
            }
            writeJson(
                socket,
                responsesEnvelope(
                    responseId,
                    created,
                    request.model,
                    "completed",
                    text.toString(),
                    reasoning.toString(),
                    finalStats
                ).toString(),
                extraHeaders = mapOf("X-MCA-Request-Id" to lease.clientRequestId.ifBlank { responseId })
            )
        }
    }

    private fun responsesEnvelope(
        responseId: String,
        created: Long,
        model: String,
        status: String,
        text: String,
        reasoning: String,
        stats: RuntimeStats?
    ): JSONObject {
        val response = JSONObject()
            .put("id", responseId)
            .put("object", "response")
            .put("created_at", created)
            .put("status", status)
            .put("model", model)
            .put("output", JSONArray())
        if (text.isNotEmpty() || status == "completed" || status == "failed") {
            response.getJSONArray("output").put(
                responsesOutputItem(
                    "msg-${responseId.removePrefix("resp-")}",
                    if (status == "completed") "completed" else status,
                    text
                )
            )
        }
        stats?.let { value ->
            response.put(
                "usage",
                JSONObject()
                    .put("input_tokens", tokenOrNull(value.promptTokens))
                    .put("output_tokens", tokenOrNull(value.completionTokens))
                    .put(
                        "total_tokens",
                        (value.promptTokens + value.completionTokens).takeIf { it > 0 } ?: JSONObject.NULL
                    )
            )
        }
        return response
    }

    private fun responsesOutputItem(id: String, status: String, text: String): JSONObject =
        JSONObject()
            .put("type", "message")
            .put("id", id)
            .put("status", status)
            .put("role", "assistant")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "output_text")
                        .put("text", text)
                        .put("annotations", JSONArray())
                )
            )
