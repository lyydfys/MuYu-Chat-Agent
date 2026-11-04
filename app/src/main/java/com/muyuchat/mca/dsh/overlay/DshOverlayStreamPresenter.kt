package com.muyuchat.mca.dsh.overlay

/**
 * Where the transport's streaming events go.
 *
 * The gateway decorator ([DshOverlayReplyObserver]) speaks this interface; [DshOverlayStreamPresenter]
 * is the only implementation that turns it into window state. Keeping the seam explicit is what lets
 * the "streaming off" behaviour be asserted with a recording fake: when the switch is off the fake
 * must record **zero** calls, not "calls that were later ignored".
 */
internal interface DshOverlayStreamSink {
    fun onDelta(text: String)

    fun onToolStart(toolName: String?)

    fun onTurnEnd(finalText: String?)

    fun onAbort()
}

/**
 * Turns gateway deltas into the one [DshOverlayStream] the window renders.
 *
 * Responsibilities, all deliberately here instead of in the service:
 *  - the "流式上屏" switch: off means no state change and no callback at all (the old behaviour);
 *  - the 300 ms end debounce: a turn that just finished must not flash a new streaming bubble while
 *    the finished bubble is still being read;
 *  - the render throttle: [DshOverlayStreamThrottle] limits how often Compose is asked to redraw;
 *  - lifecycle: end/abort always settle to a terminal state, so the progress arc can never spin
 *    forever after a disconnected stream.
 *
 * Pure Kotlin: the clock and the state callback are injected, so every rule is unit-tested on the JVM
 * without Compose, a device or a model.
 */
internal class DshOverlayStreamPresenter(
    private val onState: (DshOverlayStream) -> Unit,
    private val streamingEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttle: DshOverlayStreamThrottle = DshOverlayStreamThrottle(),
    private val endDebounceMillis: Long = DEFAULT_END_DEBOUNCE_MILLIS
) : DshOverlayStreamSink {

    /** The current state; exposed for tests and for a late-attached window to render the truth. */
    var state: DshOverlayStream = DshOverlayStream.Idle
        private set

    private var lastEndedAtMs: Long? = null

    override fun onDelta(text: String) {
        if (!streamingEnabled()) return
        val now = clock()
        if (state !is DshOverlayStream.Streaming && isEndDebounced(now)) return
        val next = DshOverlayStreamReducer.onDelta(state, text, now)
        state = next
        val streaming = next as? DshOverlayStream.Streaming ?: return
        if (throttle.shouldEmit(streaming.text.length, now)) onState(streaming)
    }

    override fun onToolStart(toolName: String?) {
        if (!streamingEnabled()) return
        val now = clock()
        if (state !is DshOverlayStream.Streaming && isEndDebounced(now)) return
        state = DshOverlayStreamReducer.onToolStart(state, toolName)
        onState(state)
    }

    override fun onTurnEnd(finalText: String?) {
        if (!streamingEnabled()) return
        state = DshOverlayStreamReducer.onTurnEnd(state, finalText)
        settle(clock())
    }

    override fun onAbort() {
        if (!streamingEnabled()) return
        // An abort before anything was shown must not poke the UI at all.
        if (state is DshOverlayStream.Idle) return
        state = DshOverlayStreamReducer.onAbort(state)
        settle(clock())
    }

    /** The window was detached: forget any half turn so a re-attached window never shows stale text. */
    fun reset() {
        state = DshOverlayStream.Idle
        lastEndedAtMs = null
        throttle.reset()
    }

    private fun settle(nowMs: Long) {
        lastEndedAtMs = nowMs
        throttle.reset()
        onState(state)
    }

    private fun isEndDebounced(nowMs: Long): Boolean {
        val ended = lastEndedAtMs ?: return false
        return nowMs >= ended && nowMs - ended < endDebounceMillis
    }

    companion object {
        /** After a turn ends, the same window keeps the finished bubble for this long before a new
         * stream may start drawing again. */
        const val DEFAULT_END_DEBOUNCE_MILLIS = 300L
    }
}
