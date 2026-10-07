package com.muyuchat.api.local.adapter

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.CallbackEventType
import com.muyuchat.core.engine.contract.ErrorPayload
import com.muyuchat.core.engine.contract.PreviewPayload
import com.muyuchat.core.engine.contract.ProgressPayload
import com.muyuchat.core.engine.contract.Retryability
import com.muyuchat.core.engine.contract.TerminalPayload
import com.muyuchat.core.engine.contract.TokenPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates an individual Server-Sent Event (SSE) frame.
 */
data class SseFrame(
    val id: String? = null,
    val event: String? = null,
    val data: String,
    val retryMs: Long? = null
) {
    /**
     * Serializes this frame into wire-level HTTP SSE protocol text.
     */
    fun toWire(): String = buildString {
        if (!id.isNullOrBlank()) {
            append("id: ").append(id).append("\n")
        }
        if (!event.isNullOrBlank()) {
            append("event: ").append(event).append("\n")
        }
        if (retryMs != null && retryMs > 0L) {
            append("retry: ").append(retryMs).append("\n")
        }
        data.lines().forEach { line ->
            append("data: ").append(line).append("\n")
        }
        append("\n")
    }
}

/**
 * Bridges domain CallbackEvents into wire-level Server-Sent Events (SSE).
 *
 * Enforces:
 * 1. SSE frame serialization conforming to OpenAI chat completion chunk wire formats.
 * 2. Reconnection support via Last-Event-ID / resume sequence filtering.
 * 3. Exactly-Once terminal guarantees: after the first terminal or error event is emitted,
 *    subsequent terminal events are dropped.
 * 4. Heartbeat/ping generation to prevent proxy socket read timeouts.
 */
class LocalApiEventBridge(
    private val resumeAfterSequence: Long = -1L
) {
    private val terminalEmitted = AtomicBoolean(false)

    /**
     * Returns true if a terminal or error event has already been emitted.
     */
    val isTerminalEmitted: Boolean
        get() = terminalEmitted.get()

    /**
     * Transforms a single domain CallbackEvent into a corresponding SseFrame.
     * Returns null if the event should be skipped (e.g. prior to resumeSequence,
     * or redundant terminal event).
     */
    fun mapEventToSse(event: CallbackEvent): SseFrame? {
        // Reconnect filtering: if client provided last-event-id, drop older/duplicate events
        if (event.sequence <= resumeAfterSequence) {
            return null
        }

        // Terminal exactly-once enforcement
        if (terminalEmitted.get()) {
            return null
        }

        return when (event.eventType) {
            CallbackEventType.Token -> {
                val payload = event.payload as? TokenPayload ?: return null
                val dataJson = formatTokenChunkJson(
                    requestId = event.identity.requestId,
                    text = payload.text,
                    index = payload.index
                )
                SseFrame(
                    id = event.sequence.toString(),
                    event = "token",
                    data = dataJson
                )
            }
            CallbackEventType.Progress -> {
                val payload = event.payload as? ProgressPayload ?: return null
                val json = JSONObject().apply {
                    put("current", payload.current)
                    put("total", payload.total)
                    put("stage", payload.stage)
                    put("percentage", payload.percentage)
                    if (payload.detail.isNotBlank()) {
                        put("detail", payload.detail)
                    }
                }
                SseFrame(
                    id = event.sequence.toString(),
                    event = "progress",
                    data = json.toString()
                )
            }
            CallbackEventType.Preview -> {
                val payload = event.payload as? PreviewPayload ?: return null
                val json = JSONObject().apply {
                    put("frame_index", payload.frameIndex)
                    put("width", payload.width)
                    put("height", payload.height)
                    put("format", payload.format)
                    put("data_sha256", payload.dataSha256)
                }
                SseFrame(
                    id = event.sequence.toString(),
                    event = "preview",
                    data = json.toString()
                )
            }
            CallbackEventType.Error -> {
                if (terminalEmitted.compareAndSet(false, true)) {
                    val payload = event.payload as? ErrorPayload
                    val json = JSONObject().apply {
                        val errObj = JSONObject().apply {
                            put("code", payload?.error?.code?.name ?: "INTERNAL_ERROR")
                            put("category", payload?.error?.category?.name ?: "Internal")
                            put("message", payload?.error?.publicMessage ?: "Execution error")
                            put("recoverable", payload?.error?.retryability != Retryability.Never)
                        }
                        put("error", errObj)
                    }
                    SseFrame(
                        id = event.sequence.toString(),
                        event = "error",
                        data = json.toString()
                    )
                } else null
            }
            CallbackEventType.Terminal -> {
                if (terminalEmitted.compareAndSet(false, true)) {
                    SseFrame(
                        id = event.sequence.toString(),
                        event = "done",
                        data = "[DONE]"
                    )
                } else null
            }
            CallbackEventType.Heartbeat -> {
                SseFrame(
                    id = null,
                    event = "ping",
                    data = "{\"ping\":${System.currentTimeMillis()}}"
                )
            }
            CallbackEventType.StateChanged -> null
        }
    }

    /**
     * Bridges an upstream CallbackEvent Flow into an SseFrame Flow,
     * maintaining exactly-once terminal boundaries and resume sequence filtering.
     */
    fun bridgeFlow(events: Flow<CallbackEvent>): Flow<SseFrame> = flow {
        events.collect { event ->
            mapEventToSse(event)?.let { frame ->
                emit(frame)
            }
        }
    }

    companion object {
        fun formatTokenChunkJson(requestId: String, text: String, index: Long): String {
            val delta = JSONObject().apply {
                put("content", text)
            }
            val choice = JSONObject().apply {
                put("index", 0)
                put("delta", delta)
                put("finish_reason", JSONObject.NULL)
            }
            val root = JSONObject().apply {
                put("id", requestId)
                put("object", "chat.completion.chunk")
                put("created", System.currentTimeMillis() / 1000L)
                put("choices", JSONArray().put(choice))
            }
            return root.toString()
        }
    }
}
