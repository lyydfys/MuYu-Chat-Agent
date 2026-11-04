package com.muyuchat.mca.dsh.overlay

import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The one app-side observation point for "DSH is answering".
 *
 * It decorates the existing gateway backend instead of adding a second runtime, a second port or a
 * WebView hook: the DSH host already sends every model turn through this backend, so the text is
 * exactly the assistant text the official UI receives. Facts reported per turn:
 *  - [onBusy] on the first visible delta (the mark switches to the working mood);
 *  - [onIdle] when the turn stops producing (completed, failed or cancelled);
 *  - [onReply] once per completed turn that produced visible text (finished-bubble path, unchanged);
 *  - [stream] the live boundaries for the "AI output on screen" view: every delta, every tool start,
 *    the turn end and an abort on failure/cancel.
 *
 * The stream sink is optional and gated by [streamingEnabled]: with streaming switched off the sink is
 * never called (not called-then-ignored), so the previous behaviour is preserved exactly.
 *
 * Scope limit, stated rather than hidden: the official DeepSeek-account provider does not route
 * through the MCA gateway, so turns served by that provider raise neither a stream nor a bubble.
 * Everything the user runs on MCA models (local or cloud) is covered.
 */
internal class DshOverlayReplyObserver(
    private val delegate: DshGatewayBackend,
    private val onBusy: () -> Unit,
    private val onIdle: () -> Unit,
    private val onReply: (DshOverlayReplyPreview) -> Unit,
    /** Live stream seam; null keeps the observer a pure finished-bubble observer. */
    private val stream: DshOverlayStreamSink? = null,
    /** Read once per event: the user can switch streaming off in the middle of a turn. */
    private val streamingEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis
) : DshGatewayBackend {

    override suspend fun catalog(): List<DshGatewayModel> = delegate.catalog()

    override suspend fun cancel(callId: String) = delegate.cancel(callId)

    override fun stream(request: DshModelRequest): Flow<DshModelEvent> = flow {
        val visible = StringBuilder()
        var active = false
        var settled = false
        try {
            delegate.stream(request).collect { event ->
                when (event) {
                    is DshModelEvent.TextDelta -> {
                        if (!active) {
                            active = true
                            onBusy()
                        }
                        val remaining = MAX_ACCUMULATED_CHARS - visible.length
                        if (remaining > 0) visible.append(event.text.take(remaining))
                        if (streamingEnabled()) stream?.onDelta(event.text)
                    }

                    // Only the tool *name* leaves the transport; arguments stay here, so a path or a
                    // shell command can never be rendered over another app.
                    is DshModelEvent.ToolCallDelta -> if (streamingEnabled()) {
                        event.name?.takeIf { it.isNotBlank() }?.let { name -> stream?.onToolStart(name) }
                    }

                    is DshModelEvent.Completed -> {
                        settled = true
                        if (active) {
                            active = false
                            onIdle()
                        }
                        publish(event.finishReason, visible.toString())
                        if (streamingEnabled()) stream?.onTurnEnd(visible.toString())
                    }

                    is DshModelEvent.Failed -> {
                        settled = true
                        if (active) {
                            active = false
                            onIdle()
                        }
                        if (streamingEnabled()) stream?.onAbort()
                    }

                    else -> Unit
                }
                emit(event)
            }
        } finally {
            // A cancelled stream (peer disconnect / host stop) has no terminal event; the mark must
            // still leave the working mood and the half answer must not stay on screen.
            if (active) onIdle()
            if (!settled && streamingEnabled()) stream?.onAbort()
        }
    }

    private fun publish(finishReason: DshFinishReason, text: String) {
        when (finishReason) {
            // A truncated answer is still an answer; a tool-call turn carries no assistant text and
            // therefore cannot produce a preview.
            DshFinishReason.STOP, DshFinishReason.LENGTH -> Unit
            else -> return
        }
        val preview = DshOverlayReplyPreview.of(sessionId = null, reply = text, atEpochMs = clock())
            ?: return
        onReply(preview)
    }

    companion object {
        /**
         * The bubble only shows the first [DshOverlayReplyPreview.MAX_PREVIEW_CHARS] characters; the
         * cap keeps a runaway answer from growing the accumulator without bound.
         */
        const val MAX_ACCUMULATED_CHARS = 8_192
    }
}
