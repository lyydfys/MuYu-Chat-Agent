package com.muyuchat.feature.dsh.workspace.web

/**
 * Limits of the account-path reply probe.
 *
 * The numbers are deliberately small: the probe only reports a *finished-looking* burst of new text
 * (a reply), never a partial token-by-token stream.
 */
object DshReplyDomLimits {

    /** New visible text needed before a report is even considered. */
    const val MIN_GROWTH_CHARS: Int = 24

    /** Quiet period after the last growth; only then may the burst be reported. */
    const val SILENCE_MILLIS: Long = 1_200L

    /** Reported text is truncated to the most recent N characters. */
    const val MAX_REPORT_CHARS: Int = 280

    /** One DOM batch larger than this is clamped before it reaches the gate. */
    const val MAX_BATCH_CHARS: Int = 2_000

    /** A gateway reply this recent wins over a DOM report (the bubble already fired). */
    const val GATEWAY_DEDUP_MILLIS: Long = 1_000L
}

/**
 * One observation of the DSH page.
 *
 * [text] is the text that newly appeared since the previous observation (empty on a pure silence or
 * reset tick); [nowMillis] is the observation clock; [inputFocused] means the user is typing and any
 * growth in that tick must be ignored; [pageReset] means the document changed and the accumulator must
 * be dropped.
 */
data class DshReplyDomTick(
    val text: String = "",
    val nowMillis: Long,
    val inputFocused: Boolean = false,
    val pageReset: Boolean = false
)

/** What the gate wants to do with one observation. */
sealed interface DshReplyDomDecision {
    data object None : DshReplyDomDecision

    /** [text] is the trimmed, truncated burst that should reach the overlay bubble. */
    data class Report(val text: String) : DshReplyDomDecision
}

/**
 * Pure gate behind the injected DOM observer.
 *
 * It is the single source of truth for "is this new text a reply?": the page sends batches, the gate
 * accumulates them, waits for silence, ignores typing, resets on navigation and re-arms only after
 * another [minGrowthChars] of new text — so one growing burst reports once.
 *
 * Not thread-safe by design: the host feeds it from the main thread (a `@JavascriptInterface` callback
 * posts to the view, and the silence tick is a main-dispatcher coroutine).
 */
class DshReplyDomGate(
    private val minGrowthChars: Int = DshReplyDomLimits.MIN_GROWTH_CHARS,
    private val silenceMillis: Long = DshReplyDomLimits.SILENCE_MILLIS,
    private val maxReportChars: Int = DshReplyDomLimits.MAX_REPORT_CHARS
) {

    private val recent = StringBuilder()

    private var pendingNewChars: Int = 0

    private var lastGrowthAtMillis: Long = 0L

    /** True while a reported burst has not yet been re-armed by fresh growth. */
    private var awaitingNewBurst: Boolean = false

    fun reset() {
        recent.setLength(0)
        pendingNewChars = 0
        lastGrowthAtMillis = 0L
        awaitingNewBurst = false
    }

    fun observe(tick: DshReplyDomTick): DshReplyDomDecision {
        if (tick.pageReset) {
            reset()
            return DshReplyDomDecision.None
        }
        // Typing is not a reply: do not accumulate while an input/textarea/contenteditable has focus.
        if (tick.inputFocused) return DshReplyDomDecision.None

        val added = tick.text.take(DshReplyDomLimits.MAX_BATCH_CHARS)
        if (added.isNotEmpty()) {
            pendingNewChars += added.length
            lastGrowthAtMillis = tick.nowMillis
            recent.append(added)
            if (recent.length > maxReportChars) {
                recent.delete(0, recent.length - maxReportChars)
            }
            awaitingNewBurst = false
        }
        if (awaitingNewBurst) return DshReplyDomDecision.None
        if (pendingNewChars < minGrowthChars) return DshReplyDomDecision.None
        if (tick.nowMillis - lastGrowthAtMillis < silenceMillis) return DshReplyDomDecision.None
        val text = recent.toString().trim()
        if (text.isEmpty()) return DshReplyDomDecision.None
        // One report describes exactly one burst: the next report needs another minGrowthChars of new
        // text, and it must not repeat the tail of the burst that was just shown.
        pendingNewChars = 0
        awaitingNewBurst = true
        recent.setLength(0)
        return DshReplyDomDecision.Report(text)
    }
}

/**
 * True when a DOM-detected reply may still be shown, i.e. the MCA gateway did not just report one.
 *
 * Pure and clock-injected; the app wires its own "last gateway reply" timestamp here so the two paths
 * share one bubble instead of racing. A future timestamp (clock skew) counts as "just now" and is
 * suppressed, because a duplicate bubble is worse than a missed one.
 */
fun dshAccountReplyAllowed(
    nowMillis: Long,
    lastGatewayReplyAtMillis: Long?,
    windowMillis: Long = DshReplyDomLimits.GATEWAY_DEDUP_MILLIS
): Boolean {
    if (lastGatewayReplyAtMillis == null) return true
    val elapsed = nowMillis - lastGatewayReplyAtMillis
    return elapsed >= windowMillis
}

/** Stateful wrapper of [dshAccountReplyAllowed] with an injectable clock. */
class DshAccountReplyDeduper(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = DshReplyDomLimits.GATEWAY_DEDUP_MILLIS
) {

    /**
     * Returns true when the DOM text should be forwarded.
     *
     * [lastGatewayReplyAtMillis] is supplied by the caller on every call so the same object can be
     * shared with the gateway observer without owning its state.
     */
    fun accept(text: String, lastGatewayReplyAtMillis: Long?): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return dshAccountReplyAllowed(clock(), lastGatewayReplyAtMillis, windowMillis)
    }
}

/** Decoded control field of the injected bridge: `"<jsMillis>|<focus 0|1>|<reset 0|1>"`. */
data class DshReplyProbeControl(
    val jsMillis: Long,
    val inputFocused: Boolean,
    val pageReset: Boolean
)

/**
 * Parses the bridge's control string defensively.
 *
 * The page can call the interface with anything, so every field has a safe default and an unparseable
 * string degrades to "plain observation" rather than throwing into the WebView callback thread.
 */
fun dshParseReplyProbeControl(control: String): DshReplyProbeControl {
    val parts = control.split('|')
    val millis = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
    val focused = parts.getOrNull(1)?.trim() == "1"
    val reset = parts.getOrNull(2)?.trim() == "1"
    return DshReplyProbeControl(
        jsMillis = millis.coerceAtLeast(0L),
        inputFocused = focused,
        pageReset = reset
    )
}
