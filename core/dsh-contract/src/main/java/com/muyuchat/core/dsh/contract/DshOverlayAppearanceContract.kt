package com.muyuchat.core.dsh.contract

/**
 * How the app-outside DSH window looks and behaves.
 *
 * The overlay is the one surface that is visible over *other* apps, so its appearance is a user
 * preference rather than a design decision: someone who keeps it on all day needs it small and
 * quiet, while someone who only wants reply notifications wants it larger and louder. The model
 * lives in the contract module so the workspace UI (picker) and the overlay owner (renderer) agree
 * on one shape without either importing the other.
 *
 * This file deliberately carries no serialization: the contract module stays platform- and
 * json-lib-free (architecture Rule 15), and the overlay owner owns the storage format.
 */
data class DshOverlayAppearance(
    /** One of [MascotId]; unknown stored values fall back to [MascotId.WHALE]. */
    val mascotId: String = MascotId.WHALE.id,
    /** Absolute path of a user-picked image, only meaningful for [MascotId.CUSTOM]. */
    val customImagePath: String? = null,
    /** [MIN_OPACITY]..1 window alpha; below this the mark becomes hard to see on a bright wallpaper. */
    val opacity: Float = 1f,
    /** Mark size in dp; the overlay clamps this so it can never cover the status bar. */
    val markSizeDp: Int = DEFAULT_MARK_SIZE_DP,
    /** How many reply-bubble lines are shown; 0 disables the bubble entirely. */
    val bubbleLines: Int = 3,
    /** Whether an incoming DSH reply raises a bubble at all. */
    val notifyReplies: Boolean = true,
    /** Whether the mark may be dragged by the user. */
    val draggable: Boolean = true
) {

    fun normalized(): DshOverlayAppearance = copy(
        mascotId = MascotId.fromId(mascotId).id,
        customImagePath = customImagePath?.trim()?.takeIf { it.isNotBlank() },
        opacity = opacity.coerceIn(MIN_OPACITY, 1f),
        markSizeDp = markSizeDp.coerceIn(MIN_MARK_SIZE_DP, MAX_MARK_SIZE_DP),
        bubbleLines = bubbleLines.coerceIn(0, MAX_BUBBLE_LINES)
    )

    companion object {
        const val DEFAULT_MARK_SIZE_DP = 56
        const val MIN_MARK_SIZE_DP = 40
        const val MAX_MARK_SIZE_DP = 96
        const val MIN_OPACITY = 0.35f
        const val MAX_BUBBLE_LINES = 5
    }
}

/** Stable ids for the marks the design system can paint. Unknown ids decode to [WHALE]. */
enum class MascotId(val id: String) {
    WHALE("whale"),
    FATTY_FISH("fatty_fish"),
    CAT("cat"),
    CUSTOM("custom");

    companion object {
        fun fromId(value: String?): MascotId =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) } ?: WHALE
    }
}

/**
 * Where the overlay owner stores [DshOverlayAppearance].
 *
 * Kept as a port so the picker (feature module) and the store (app module) are independent, and so
 * every read has one documented failure mode: a broken document returns defaults rather than an
 * exception, because an unreadable preference must never stop the overlay from starting.
 */
interface DshOverlayAppearanceStore {
    fun load(): DshOverlayAppearance
    fun save(appearance: DshOverlayAppearance)
}

/** Reply text plus the run it belongs to, as delivered to the overlay bubble. */
data class DshOverlayReplyPreview(
    val sessionId: String?,
    val text: String,
    val truncated: Boolean = false,
    val atEpochMs: Long = 0L
) {
    companion object {
        /** Bubble text is clamped at the source so the overlay never receives a wall of text. */
        const val MAX_PREVIEW_CHARS = 280

        fun of(sessionId: String?, reply: String, atEpochMs: Long = 0L): DshOverlayReplyPreview? {
            val trimmed = reply.trim()
            if (trimmed.isEmpty()) return null
            val clipped = trimmed.length > MAX_PREVIEW_CHARS
            return DshOverlayReplyPreview(
                sessionId = sessionId,
                text = if (clipped) trimmed.take(MAX_PREVIEW_CHARS) else trimmed,
                truncated = clipped,
                atEpochMs = atEpochMs
            )
        }
    }
}
