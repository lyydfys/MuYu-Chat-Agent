package com.muyuchat.mca.dsh.overlay

import org.json.JSONObject

/**
 * Where the floating DSH ball sits, expressed as fractions of the available travel instead of pixels.
 *
 * Fractions are the only rotation-safe representation: the window is re-measured with the new screen
 * size after a rotation or a display-size change, and the same fraction then lands inside the new
 * bounds without needing a saved pixel value. [DshOverlayGeometry] converts, and clamps.
 */
data class DshOverlayPosition(
    val xFraction: Float = DEFAULT_X_FRACTION,
    val yFraction: Float = DEFAULT_Y_FRACTION
) {
    fun normalized(): DshOverlayPosition = DshOverlayPosition(
        xFraction = DshOverlayGeometry.clampFraction(xFraction),
        yFraction = DshOverlayGeometry.clampFraction(yFraction)
    )

    companion object {
        /** Right edge: the status-bar band the system normally leaves free. */
        const val DEFAULT_X_FRACTION = 1f
        const val DEFAULT_Y_FRACTION = 0.18f
    }
}

/**
 * Everything the app-outside display owner persists.
 *
 * Deliberately small: the floating window is **the DSH whale ball**, so there is no mascot, no custom
 * image and no per-window opacity any more. What is left is the set of choices a user can actually
 * make about that window — on/off, how loud the reply bubble is, whether live text is echoed, whether
 * the ball may be dragged — plus the two machine-owned facts needed to restore it (size and position).
 *
 * A missing or corrupted document decodes to these defaults, which never throws and never blocks the
 * window from opening.
 */
data class DshOverlaySettings(
    val enabled: Boolean = false,
    /** Reply-bubble lines; 0 disables the finished bubble entirely. */
    val bubbleLines: Int = DEFAULT_BUBBLE_LINES,
    /** Whether a finished DSH reply raises a bubble at all. */
    val notifyReplies: Boolean = true,
    /** Whether the ball may be dragged by the user. */
    val draggable: Boolean = true,
    /**
     * Live "AI output on screen": show assistant text while it streams, plus a tool label and a
     * progress ring. On by default; off restores the finished-bubble-only behaviour exactly.
     */
    val streamingEnabled: Boolean = DEFAULT_STREAMING_ENABLED,
    /**
     * Whether a dangerous device command must be confirmed on the floating window before it runs.
     *
     * Off does **not** allow those commands: it restores the previous strict behaviour, where the
     * command policy refuses them outright with `policy_blocked`.
     */
    val commandApprovalEnabled: Boolean = DEFAULT_COMMAND_APPROVAL_ENABLED,
    /**
     * Diameter of the DSH ball. Persisted and clamped to [MIN_MARK_SIZE_DP]..[MAX_MARK_SIZE_DP] so the
     * window can be tuned without a UI control; the settings panel does not expose it.
     */
    val markSizeDp: Int = DEFAULT_MARK_SIZE_DP,
    val position: DshOverlayPosition = DshOverlayPosition()
) {
    fun normalized(): DshOverlaySettings = copy(
        bubbleLines = bubbleLines.coerceIn(0, MAX_BUBBLE_LINES),
        markSizeDp = markSizeDp.coerceIn(MIN_MARK_SIZE_DP, MAX_MARK_SIZE_DP),
        position = position.normalized()
    )

    companion object {
        const val DEFAULT_BUBBLE_LINES = 3
        const val MAX_BUBBLE_LINES = 5
        const val DEFAULT_STREAMING_ENABLED = true

        /** Ask on the floating window by default; the panel can switch it off back to a hard refusal. */
        const val DEFAULT_COMMAND_APPROVAL_ENABLED = true

        /** Matches the in-app DSH floating ball, so the system window reads as the same control. */
        const val DEFAULT_MARK_SIZE_DP = 48
        const val MIN_MARK_SIZE_DP = 44
        const val MAX_MARK_SIZE_DP = 64
    }
}

/**
 * JSON codec for [DshOverlaySettings].
 *
 * The contract module may not import a JSON library (architecture Rule 15), so the format is owned
 * here. Every decode is total: unknown fields are ignored — a document written by the previous
 * mascot-era version still decodes and simply loses its unused keys — out-of-range numbers are clamped
 * by [DshOverlaySettings.normalized], and any parse failure yields the defaults.
 */
internal object DshOverlaySettingsCodec {
    const val VERSION = 2

    private const val KEY_VERSION = "version"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BUBBLE_LINES = "bubbleLines"
    private const val KEY_NOTIFY_REPLIES = "notifyReplies"
    private const val KEY_DRAGGABLE = "draggable"
    private const val KEY_STREAMING = "streaming"
    private const val KEY_COMMAND_APPROVAL = "commandApproval"
    private const val KEY_MARK_SIZE = "markSizeDp"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    fun encode(settings: DshOverlaySettings): String {
        val normalized = settings.normalized()
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_ENABLED, normalized.enabled)
            .put(KEY_BUBBLE_LINES, normalized.bubbleLines)
            .put(KEY_NOTIFY_REPLIES, normalized.notifyReplies)
            .put(KEY_DRAGGABLE, normalized.draggable)
            .put(KEY_STREAMING, normalized.streamingEnabled)
            .put(KEY_COMMAND_APPROVAL, normalized.commandApprovalEnabled)
            .put(KEY_MARK_SIZE, normalized.markSizeDp)
            .put(KEY_X, normalized.position.xFraction.toDouble())
            .put(KEY_Y, normalized.position.yFraction.toDouble())
            .toString()
    }

    fun decode(raw: String?): DshOverlaySettings {
        if (raw.isNullOrBlank()) return DshOverlaySettings()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return DshOverlaySettings()
        return runCatching {
            DshOverlaySettings(
                enabled = root.optBoolean(KEY_ENABLED, false),
                bubbleLines = root.optInt(KEY_BUBBLE_LINES, DshOverlaySettings.DEFAULT_BUBBLE_LINES),
                notifyReplies = root.optBoolean(KEY_NOTIFY_REPLIES, true),
                draggable = root.optBoolean(KEY_DRAGGABLE, true),
                streamingEnabled = root.optBoolean(
                    KEY_STREAMING,
                    DshOverlaySettings.DEFAULT_STREAMING_ENABLED
                ),
                commandApprovalEnabled = root.optBoolean(
                    KEY_COMMAND_APPROVAL,
                    DshOverlaySettings.DEFAULT_COMMAND_APPROVAL_ENABLED
                ),
                markSizeDp = root.optInt(KEY_MARK_SIZE, DshOverlaySettings.DEFAULT_MARK_SIZE_DP),
                position = DshOverlayPosition(
                    xFraction = root.optDouble(KEY_X, DshOverlayPosition.DEFAULT_X_FRACTION.toDouble())
                        .toFloat(),
                    yFraction = root.optDouble(KEY_Y, DshOverlayPosition.DEFAULT_Y_FRACTION.toDouble())
                        .toFloat()
                )
            ).normalized()
        }.getOrElse { DshOverlaySettings() }
    }
}
