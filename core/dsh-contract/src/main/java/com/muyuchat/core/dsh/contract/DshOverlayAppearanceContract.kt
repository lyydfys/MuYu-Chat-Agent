package com.muyuchat.core.dsh.contract

/**
 * How the app-outside DSH window behaves.
 *
 * The overlay is the one surface that is visible over *other* apps, so its behaviour is a user
 * preference rather than a design decision: someone who keeps it on all day needs it quiet, while
 * someone who only wants reply notifications wants the bubble louder. The model lives in the contract
 * module so the workspace UI (picker) and the overlay owner (renderer) agree on one shape without
 * either importing the other.
 *
 * The window is the DSH whale ball again (the same control as the in-app floating ball), so the
 * mascot-era fields ([mascotId], [customImagePath], [markSizeDp], [opacity]) are **retired**
 * (已停用，仅为已存配置兼容): they are kept so previously stored documents still decode, and every
 * renderer ignores them. The three live choices are [bubbleLines], [notifyReplies] and [draggable].
 *
 * This file deliberately carries no serialization: the contract module stays platform- and
 * json-lib-free (architecture Rule 15), and the overlay owner owns the storage format.
 */
data class DshOverlayAppearance(
    /** 已停用（仅为已存配置兼容）：悬浮窗固定为 DSH 鲸鱼圆球，不再按此渲染。 */
    val mascotId: String = MascotId.WHALE.id,
    /** 已停用（仅为已存配置兼容）：悬浮窗不再显示用户自选图片。 */
    val customImagePath: String? = null,
    /** 已停用（仅为已存配置兼容）：窗口不透明度由应用自身决定，此值被忽略。 */
    val opacity: Float = 1f,
    /** 已停用（仅为已存配置兼容）：圆球尺寸由应用自身决定，此值被忽略。 */
    val markSizeDp: Int = DEFAULT_MARK_SIZE_DP,
    /** How many reply-bubble lines are shown; 0 disables the bubble entirely. */
    val bubbleLines: Int = 3,
    /** Whether an incoming DSH reply raises a bubble at all. */
    val notifyReplies: Boolean = true,
    /** Whether the ball may be dragged by the user. */
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

/**
 * 已停用（仅为已存配置兼容）：形象 id 保留在契约里只为让旧配置文档仍能解码，窗口固定为 DSH 鲸鱼圆球。
 */
enum class MascotId(val id: String) {
    WHALE("whale"),
    WHALE_GIRL_ART("whale_girl_art"),
    WHALE_GIRL_Q("whale_girl_q"),
    WHALE_GIRL_MATURE("whale_girl_mature"),
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
