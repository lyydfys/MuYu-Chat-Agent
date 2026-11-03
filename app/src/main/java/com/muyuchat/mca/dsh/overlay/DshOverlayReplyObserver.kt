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
 * The one app-side observation point for "DSH produced a reply".
 *
 * It decorates the existing gateway backend instead of adding a second runtime, a second port or a
 * WebView hook: the DSH host already sends every model turn through this backend, so the reply
 * text is exactly the assistant text the official UI receives. Three facts are reported:
 *  - [onBusy] on the first visible delta of a turn (the mark switches to the working mood);
 *  - [onIdle] when that turn stops producing (completed, failed or cancelled);
 *  - [onReply] once per completed turn that produced visible text.
 *
 * Bubble policy is NOT decided here: [DshOverlayHost] owns the appearance and the throttle gate, so
 * turning bubbles off never touches the transport.
 *
 * Scope limit, stated rather than hidden: the official DeepSeek-account provider does not route
 * through the MCA gateway, so turns served by that provider raise no bubble. Everything the user
 * runs on MCA models (local or cloud) is covered.
 */
internal class DshOverlayReplyObserver(
    private val delegate: DshGatewayBackend,
    private val onBusy: () -> Unit,
    private val onIdle: () -> Unit,
    private val onReply: (DshOverlayReplyPreview) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) : DshGatewayBackend {

    override suspend fun catalog(): List<DshGatewayModel> = delegate.catalog()

    override suspend fun cancel(callId: String) = delegate.cancel(callId)

    override fun stream(request: DshModelRequest): Flow<DshModelEvent> = flow {
        val visible = StringBuilder()
        var active = false
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
                    }

                    is DshModelEvent.Completed -> {
                        if (active) {
                            active = false
                            onIdle()
                        }
                        publish(event.finishReason, visible.toString())
                    }

                    is DshModelEvent.Failed -> if (active) {
                        active = false
                        onIdle()
                    }

                    else -> Unit
                }
                emit(event)
            }
        } finally {
            // A cancelled stream (peer disconnect / host stop) has no terminal event; the mark must
            // still leave the working mood instead of pulsing forever.
            if (active) onIdle()
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
