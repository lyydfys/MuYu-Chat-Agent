package com.muyuchat.mca.dsh.overlay

import com.muyuchat.core.designsystem.McaMascot
import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.MascotId
import org.json.JSONObject

/**
 * Where the floating mark sits, expressed as fractions of the available travel instead of pixels.
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
 * [DshOverlayAppearance] is the contract type the workspace panel edits; [enabled], [streamingEnabled]
 * and [position] are app-owned facts the contract deliberately does not carry (a contract type is
 * shared by the renderer and the picker, while "is it on" and "do I want live text" are app lifecycle
 * decisions). A missing or broken document decodes to these defaults, which never throws and never
 * blocks the window from opening.
 */
data class DshOverlaySettings(
    val enabled: Boolean = false,
    val appearance: DshOverlayAppearance = DshOverlayAppearance(),
    val position: DshOverlayPosition = DshOverlayPosition(),
    /**
     * Live "AI output on screen": show assistant text while it streams, plus a tool label and a
     * progress arc. On by default because it is the point of the app-outside window; off restores the
     * finished-bubble-only behaviour exactly.
     */
    val streamingEnabled: Boolean = DEFAULT_STREAMING_ENABLED
) {
    fun normalized(): DshOverlaySettings =
        copy(appearance = appearance.normalized(), position = position.normalized())

    companion object {
        const val DEFAULT_STREAMING_ENABLED = true
    }
}

/**
 * JSON codec for [DshOverlaySettings].
 *
 * The contract module may not import a JSON library (architecture Rule 15), so the format is owned
 * here. Every decode is total: unknown fields are ignored, out-of-range numbers are clamped by
 * [DshOverlaySettings.normalized], and any parse failure yields the defaults.
 */
internal object DshOverlaySettingsCodec {
    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MASCOT = "mascot"
    private const val KEY_IMAGE_PATH = "imagePath"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_MARK_SIZE = "markSizeDp"
    private const val KEY_BUBBLE_LINES = "bubbleLines"
    private const val KEY_NOTIFY_REPLIES = "notifyReplies"
    private const val KEY_DRAGGABLE = "draggable"
    private const val KEY_STREAMING = "streaming"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    fun encode(settings: DshOverlaySettings): String {
        val normalized = settings.normalized()
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_ENABLED, normalized.enabled)
            .put(KEY_MASCOT, normalized.appearance.mascotId)
            .put(KEY_IMAGE_PATH, normalized.appearance.customImagePath ?: JSONObject.NULL)
            .put(KEY_OPACITY, normalized.appearance.opacity.toDouble())
            .put(KEY_MARK_SIZE, normalized.appearance.markSizeDp)
            .put(KEY_BUBBLE_LINES, normalized.appearance.bubbleLines)
            .put(KEY_NOTIFY_REPLIES, normalized.appearance.notifyReplies)
            .put(KEY_DRAGGABLE, normalized.appearance.draggable)
            .put(KEY_STREAMING, normalized.streamingEnabled)
            .put(KEY_X, normalized.position.xFraction.toDouble())
            .put(KEY_Y, normalized.position.yFraction.toDouble())
            .toString()
    }

    fun decode(raw: String?): DshOverlaySettings {
        if (raw.isNullOrBlank()) return DshOverlaySettings()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return DshOverlaySettings()
        val defaults = DshOverlayAppearance()
        return runCatching {
            DshOverlaySettings(
                enabled = root.optBoolean(KEY_ENABLED, false),
                appearance = DshOverlayAppearance(
                    mascotId = root.optString(KEY_MASCOT, defaults.mascotId),
                    customImagePath = root.customImagePath(),
                    opacity = root.optDouble(KEY_OPACITY, defaults.opacity.toDouble())
                        .toFloat()
                        .takeIf { it.isFinite() }
                        ?: defaults.opacity,
                    markSizeDp = root.optInt(KEY_MARK_SIZE, defaults.markSizeDp),
                    bubbleLines = root.optInt(KEY_BUBBLE_LINES, defaults.bubbleLines),
                    notifyReplies = root.optBoolean(KEY_NOTIFY_REPLIES, defaults.notifyReplies),
                    draggable = root.optBoolean(KEY_DRAGGABLE, defaults.draggable)
                ),
                position = DshOverlayPosition(
                    xFraction = root.optDouble(KEY_X, DshOverlayPosition.DEFAULT_X_FRACTION.toDouble())
                        .toFloat(),
                    yFraction = root.optDouble(KEY_Y, DshOverlayPosition.DEFAULT_Y_FRACTION.toDouble())
                        .toFloat()
                ),
                streamingEnabled = root.optBoolean(
                    KEY_STREAMING,
                    DshOverlaySettings.DEFAULT_STREAMING_ENABLED
                )
            ).normalized()
        }.getOrElse { DshOverlaySettings() }
    }

    /**
     * `optString` renders a JSON null as the literal "null", which is not a path. A stored empty or
     * placeholder value therefore decodes to "no custom image".
     */
    private fun JSONObject.customImagePath(): String? {
        if (isNull(KEY_IMAGE_PATH)) return null
        return optString(KEY_IMAGE_PATH, "")
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" }
    }
}

/** The design-system mark a stored contract id paints; unknown ids fall back to the whale. */
internal fun MascotId.toMcaMascot(): McaMascot = when (this) {
    MascotId.WHALE -> McaMascot.WHALE
    MascotId.WHALE_GIRL_ART -> McaMascot.WHALE_GIRL_ART
    MascotId.WHALE_GIRL_Q -> McaMascot.WHALE_GIRL_Q
    MascotId.WHALE_GIRL_MATURE -> McaMascot.WHALE_GIRL_MATURE
    MascotId.FATTY_FISH -> McaMascot.FATTY_FISH
    MascotId.CAT -> McaMascot.CAT
    MascotId.CUSTOM -> McaMascot.CUSTOM
}

/** Inverse of [toMcaMascot], used when the picker writes a new appearance. */
internal fun McaMascot.toMascotId(): MascotId = when (this) {
    McaMascot.WHALE -> MascotId.WHALE
    McaMascot.WHALE_GIRL_ART -> MascotId.WHALE_GIRL_ART
    McaMascot.WHALE_GIRL_Q -> MascotId.WHALE_GIRL_Q
    McaMascot.WHALE_GIRL_MATURE -> MascotId.WHALE_GIRL_MATURE
    McaMascot.FATTY_FISH -> MascotId.FATTY_FISH
    McaMascot.CAT -> MascotId.CAT
    McaMascot.CUSTOM -> MascotId.CUSTOM
}
