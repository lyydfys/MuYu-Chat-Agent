package com.muyuchat.mca.dsh.overlay

import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * A top-of-screen "lyrics" strip that echoes the live DSH turn next to the existing floating ball.
 *
 * The strip is a **second, independent window** owned by the same overlay service: it is only created
 * when both the global app-outside switch and [DshOverlayStripSettings.enabled] are on, and the ball
 * window stays exactly as it was. Everything in this file is pure data / arithmetic so the layout,
 * truncation, progress and approval-routing rules can be asserted on the JVM.
 */

/** Background treatment of the strip; all three keep the same layout and only change colour/alpha. */
enum class DshOverlayStripSurface {
    LIGHT,
    DARK,
    TRANSLUCENT;

    companion object {
        fun fromName(raw: String?): DshOverlayStripSurface =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DARK
    }
}

/**
 * Persisted strip preferences.
 *
 * A separate document from [DshOverlaySettings] because the strip is its own window with its own
 * lifecycle: turning it off must not disturb the ball, and a corrupted strip document must fall back
 * to defaults instead of preventing the ball from opening.
 */
data class DshOverlayStripSettings(
    val enabled: Boolean = false,
    val surface: DshOverlayStripSurface = DshOverlayStripSurface.DARK,
    val opacity: Float = MAX_OPACITY,
    val lines: Int = DEFAULT_LINES,
    /** Keep the strip below the status bar instead of drawing under it. */
    val safeArea: Boolean = true
) {
    fun normalized(): DshOverlayStripSettings = copy(
        opacity = if (opacity.isFinite()) opacity.coerceIn(MIN_OPACITY, MAX_OPACITY) else MAX_OPACITY,
        lines = lines.coerceIn(MIN_LINES, MAX_LINES)
    )

    companion object {
        const val MIN_OPACITY = 0.35f
        const val MAX_OPACITY = 1f
        const val MIN_LINES = 1
        const val MAX_LINES = 2
        const val DEFAULT_LINES = 2

        /** Text-mode strip height envelope; the approval card grows beyond it, the text row never does. */
        const val MIN_HEIGHT_DP = 44
        const val MAX_HEIGHT_DP = 64

        /** Horizontal budget used to tail-clamp the visible text; the tail is what the user has not read. */
        const val CHARS_PER_LINE = 30
    }
}

/** Total-function codec; a missing/corrupt document yields [DshOverlayStripSettings] defaults. */
internal object DshOverlayStripCodec {
    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SURFACE = "surface"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_LINES = "lines"
    private const val KEY_SAFE_AREA = "safeArea"

    fun encode(settings: DshOverlayStripSettings): String {
        val normalized = settings.normalized()
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_ENABLED, normalized.enabled)
            .put(KEY_SURFACE, normalized.surface.name)
            .put(KEY_OPACITY, normalized.opacity.toDouble())
            .put(KEY_LINES, normalized.lines)
            .put(KEY_SAFE_AREA, normalized.safeArea)
            .toString()
    }

    fun decode(raw: String?): DshOverlayStripSettings {
        if (raw.isNullOrBlank()) return DshOverlayStripSettings()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return DshOverlayStripSettings()
        return runCatching {
            DshOverlayStripSettings(
                enabled = root.optBoolean(KEY_ENABLED, false),
                surface = DshOverlayStripSurface.fromName(root.optString(KEY_SURFACE, "")),
                opacity = root.optDouble(KEY_OPACITY, 1.0).toFloat(),
                lines = root.optInt(KEY_LINES, DshOverlayStripSettings.DEFAULT_LINES),
                safeArea = root.optBoolean(KEY_SAFE_AREA, true)
            ).normalized()
        }.getOrElse { DshOverlayStripSettings() }
    }
}

/** Which live one-line content the strip should render, already clamped for its visible budget. */
data class DshOverlayStripLine(
    val text: String,
    /** True for a tool phrase ("▸ 运行命令…"), false for assistant text. */
    val tool: Boolean,
    /** A turn is still running; drives the left progress bar. */
    val active: Boolean
)

/** The one place that decides where a command approval card appears. */
enum class DshOverlayStripOwner {
    STRIP,
    BALL,
    NONE
}

/**
 * Pure strip rules: height, tail truncation, elapsed progress, fade, tool wording and approval routing.
 *
 * The service owns the timers; this object owns the meaning, so every branch is unit-testable and the
 * Compose layer only consumes the results.
 */
internal object DshOverlayStripLogic {

    /** Elapsed progress for the 3 dp left bar; the bar fills and holds instead of spinning forever. */
    const val PROGRESS_FULL_MILLIS = 30_000L

    /** Same visible window as the finished reply bubble, so the two never disagree. */
    const val FADE_VISIBLE_MILLIS = 6_500L

    /** Short cross-fade at the end of [FADE_VISIBLE_MILLIS]. */
    const val FADE_MILLIS = 320L

    private const val ELLIPSIS = "…"

    /**
     * Text-mode height in dp: 44 dp for one line, 56 dp for two, then scaled by the user font size and
     * clamped to [DshOverlayStripSettings.MIN_HEIGHT_DP]..[DshOverlayStripSettings.MAX_HEIGHT_DP] so the
     * strip can never overlap the whole screen on an accessibility font scale.
     */
    fun heightDp(
        lines: Int,
        fontScale: Float
    ): Int {
        val safeLines = lines.coerceIn(
            DshOverlayStripSettings.MIN_LINES,
            DshOverlayStripSettings.MAX_LINES
        )
        val safeScale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val base = 44f + (safeLines - 1) * 12f
        return (base * safeScale).roundToInt().coerceIn(
            DshOverlayStripSettings.MIN_HEIGHT_DP,
            DshOverlayStripSettings.MAX_HEIGHT_DP
        )
    }

    /** Visible character budget for [lines] lines; used with [tailText]. */
    fun tailBudget(lines: Int): Int =
        lines.coerceIn(
            DshOverlayStripSettings.MIN_LINES,
            DshOverlayStripSettings.MAX_LINES
        ) * DshOverlayStripSettings.CHARS_PER_LINE

    /**
     * Keeps the newest [maxChars] characters (the part still being written), prefixed with `…` when
     * anything was dropped. Never cuts inside a surrogate pair.
     */
    fun tailText(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var tail = text.takeLast(maxChars - 1)
        if (tail.isNotEmpty() && tail.first().isLowSurrogate()) tail = tail.drop(1)
        return ELLIPSIS + tail
    }

    /** 0→1 elapsed progress, clamped; a backwards/absent clock reads as zero, never negative. */
    fun progress(elapsedMs: Long, fullMs: Long = PROGRESS_FULL_MILLIS): Float {
        if (fullMs <= 0L) return 0f
        return (elapsedMs.toFloat() / fullMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Alpha during the 6.5 s hold and its short cross-fade; `null` means "not fading yet". */
    fun alphaAt(nowMs: Long, fadeStartMs: Long?, fadeMs: Long = FADE_MILLIS): Float {
        if (fadeStartMs == null) return 1f
        if (nowMs <= fadeStartMs) return 1f
        if (fadeMs <= 0L) return 0f
        val fadeEnd = fadeStartMs + fadeMs
        if (nowMs >= fadeEnd) return 0f
        return 1f - (nowMs - fadeStartMs).toFloat() / fadeMs.toFloat()
    }

    /** Deadline for the automatic hide after a turn finished; same 6.5 s as the reply bubble. */
    fun fadeDeadline(finishedAtMs: Long, visibleMs: Long = FADE_VISIBLE_MILLIS): Long =
        finishedAtMs + visibleMs

    /** Human tool wording; the raw tool name never reaches the strip. */
    fun toolText(label: String): String {
        val safe = label.trim().ifBlank { DshOverlayToolLabels.UNKNOWN }
        return "▸ $safe$ELLIPSIS"
    }

    /**
     * Which window may show a pending approval card.
     *
     * The strip wins whenever it is switched on **and** actually attached; otherwise the ball takes the
     * card; if neither window exists the request is refused so the gate denies instead of waiting for a
     * prompt nobody can see.
     */
    fun approvalOwner(
        stripEnabled: Boolean,
        stripAttached: Boolean,
        ballAttached: Boolean
    ): DshOverlayStripOwner = when {
        stripEnabled && stripAttached -> DshOverlayStripOwner.STRIP
        ballAttached -> DshOverlayStripOwner.BALL
        else -> DshOverlayStripOwner.NONE
    }

    /** Content for one live stream state; `null` means "hide the strip". */
    fun lineFor(stream: DshOverlayStream, lines: Int = DshOverlayStripSettings.DEFAULT_LINES): DshOverlayStripLine? =
        when (stream) {
            DshOverlayStream.Idle -> null
            is DshOverlayStream.Streaming -> DshOverlayStripLine(
                text = tailText(stream.text, tailBudget(lines)),
                tool = false,
                active = true
            )

            is DshOverlayStream.ToolRunning -> DshOverlayStripLine(
                text = toolText(stream.label),
                tool = true,
                active = true
            )

            is DshOverlayStream.Finished -> DshOverlayStripLine(
                text = tailText(stream.text, tailBudget(lines)),
                tool = false,
                active = false
            )
        }
}

/**
 * Idempotent one-request approval queue used by the strip.
 *
 * The service clears the card before delivering the decision, but a double tap (or a late decision from
 * a recycled window) must still be harmless: only the pending id may be decided once, a mismatched id
 * is ignored, and a cancelled request can no longer be authorised.
 */
internal class DshOverlayStripApprovalQueue {
    private var pendingId: Long? = null
    private val decided = mutableSetOf<Long>()

    fun present(id: Long): Boolean {
        if (id in decided || pendingId == id) return false
        pendingId = id
        return true
    }

    /** True exactly once per pending id; the first decision wins. */
    fun decide(id: Long): Boolean {
        if (pendingId != id) return false
        if (!decided.add(id)) return false
        pendingId = null
        return true
    }

    fun cancel(id: Long) {
        if (pendingId == id) pendingId = null
    }

    fun dismissAny() {
        pendingId = null
    }

    fun isPending(id: Long): Boolean = pendingId == id

    fun reset() {
        pendingId = null
        decided.clear()
    }
}
