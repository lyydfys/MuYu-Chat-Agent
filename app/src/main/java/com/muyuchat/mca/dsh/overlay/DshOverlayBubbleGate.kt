package com.muyuchat.mca.dsh.overlay

import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview

/** Why a reply boundary did or did not raise a bubble; kept typed for tests and diagnostics. */
internal enum class DshOverlayBubbleDecision {
    /** A bubble must be shown for this boundary. */
    SHOW,

    /** The reply carried no visible text (for example a tool-call-only turn). */
    EMPTY,

    /** The user turned reply bubbles off, or set the line count to zero. */
    DISABLED,

    /** Another bubble was raised moments ago; this boundary is dropped instead of queued. */
    THROTTLED,

    /** The same text was already announced recently (retry/replay of the same turn). */
    DUPLICATE
}

/**
 * Decides whether one completed DSH turn may raise a bubble.
 *
 * Only turn boundaries reach this gate (see [DshOverlayReplyObserver]); streaming chunks never do,
 * so an assistant answer can raise exactly one bubble. On top of that, two independent guards keep
 * the overlay quiet:
 *  - [minIntervalMillis] between two bubbles, which absorbs tool-loop turns that finish back to
 *    back;
 *  - [dedupeWindowMillis] for the identical text, which absorbs a replayed/retried turn.
 *
 * A dropped boundary is dropped, not queued: an overlay that unloads a backlog of bubbles after a
 * long turn is worse than showing the newest one.
 */
internal class DshOverlayBubbleGate(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS
) {
    private var lastShownAtMillis: Long? = null
    private var lastText: String? = null
    private var lastTextAtMillis: Long = 0L

    fun decide(
        preview: DshOverlayReplyPreview?,
        appearance: DshOverlayAppearance,
        nowMillis: Long
    ): DshOverlayBubbleDecision {
        if (preview == null || preview.text.isBlank()) return DshOverlayBubbleDecision.EMPTY
        if (!appearance.notifyReplies || appearance.bubbleLines <= 0) {
            return DshOverlayBubbleDecision.DISABLED
        }
        val text = preview.text.trim()
        val shownAt = lastShownAtMillis
        // A clock that jumped backwards (time zone/manual change) must not silence the overlay for
        // the rest of the process: an out-of-order timestamp simply skips the throttle.
        if (shownAt != null && nowMillis >= shownAt && nowMillis - shownAt < minIntervalMillis) {
            return DshOverlayBubbleDecision.THROTTLED
        }
        val previousText = lastText
        if (previousText != null && previousText == text &&
            nowMillis >= lastTextAtMillis && nowMillis - lastTextAtMillis < dedupeWindowMillis
        ) {
            return DshOverlayBubbleDecision.DUPLICATE
        }
        lastShownAtMillis = nowMillis
        lastText = text
        lastTextAtMillis = nowMillis
        return DshOverlayBubbleDecision.SHOW
    }

    /** Forgets the dedupe/throttle history, e.g. after the overlay is re-enabled. */
    fun reset() {
        lastShownAtMillis = null
        lastText = null
        lastTextAtMillis = 0L
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_DEDUPE_WINDOW_MILLIS = 30_000L
    }
}
