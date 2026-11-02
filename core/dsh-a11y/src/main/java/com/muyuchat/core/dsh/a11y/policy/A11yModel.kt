package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLimits

/**
 * One prompt-free, secret-free refusal produced by the accessibility policy.
 *
 * The message is what the official tool package shows to the model, so it must be a single line,
 * must never contain the typed text or clipboard content, and must not contain the capability
 * token. [code] is an MCA extension: the official packages ignore unknown JSON keys, while native
 * callers can branch on it.
 */
data class A11yRejection(
    val code: DshCapabilityErrorCode,
    val message: String
) {
    init {
        require(message.isNotBlank() && message.length <= DshCapabilityLimits.MAX_FAILURE_MESSAGE_CHARS) {
            "accessibility rejection message must be 1..${DshCapabilityLimits.MAX_FAILURE_MESSAGE_CHARS} characters"
        }
        require(message.none { it.code <= 0x1F || it.code in 0x7F..0x9F }) {
            "accessibility rejection message must be a single line"
        }
    }

    companion object {
        /**
         * The enabled-service guidance the official package uses. Kept identical in meaning so a
         * disabled service always tells the user where to turn it on.
         */
        const val NOT_ENABLED_GUIDE =
            "无障碍服务未开启或不可用：请在手机系统设置 → 无障碍 →（已下载的服务/服务）→ 开启「DeepSeek Harness 屏幕助手」，然后打开 App 后重试。"

        fun notEnabled(): A11yRejection =
            A11yRejection(DshCapabilityErrorCode.NOT_ENABLED, NOT_ENABLED_GUIDE)

        fun invalid(message: String): A11yRejection =
            A11yRejection(DshCapabilityErrorCode.INVALID_REQUEST, message)

        fun tooLarge(message: String): A11yRejection =
            A11yRejection(DshCapabilityErrorCode.TOO_LARGE, message)

        fun targetChanged(message: String): A11yRejection =
            A11yRejection(DshCapabilityErrorCode.TARGET_CHANGED, message)

        fun timedOut(message: String): A11yRejection =
            A11yRejection(DshCapabilityErrorCode.TIMEOUT, message)

        fun internal(message: String): A11yRejection =
            A11yRejection(DshCapabilityErrorCode.INTERNAL, message)
    }
}

/** The current display geometry in physical pixels, the coordinate space of every a11y action. */
data class A11yScreenGeometry(val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx >= 0 && heightPx >= 0) { "screen geometry must not be negative" }
    }

    val isUsable: Boolean
        get() = widthPx > 0 && heightPx > 0

    /** Fraction (0..1) to pixels, clamped into the display. Used only with a usable geometry. */
    fun resolveFraction(fx: Double, fy: Double): A11yPoint? {
        if (!isUsable || !fx.isFinite() || !fy.isFinite()) return null
        if (fx < 0.0 || fx > 1.0 || fy < 0.0 || fy > 1.0) return null
        val x = (fx * widthPx).toInt().coerceIn(0, widthPx - 1)
        val y = (fy * heightPx).toInt().coerceIn(0, heightPx - 1)
        return A11yPoint(x, y)
    }
}

/** Absolute physical-pixel point. */
data class A11yPoint(val x: Int, val y: Int)

/** Screen rectangle in physical pixels; `w == 0 || h == 0` means "not laid out". */
data class A11yRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val area: Long
        get() = w.toLong() * h.toLong()

    fun contains(point: A11yPoint): Boolean =
        w > 0 && h > 0 && point.x >= x && point.x < x + w && point.y >= y && point.y < y + h
}

/**
 * A request coordinate. The parser only validates the representation (fraction 0..1, pixel inside
 * the contract cap); resolution against the real screen happens on the service thread so a request
 * can never act on a stale geometry captured at parse time.
 */
sealed interface A11yCoord {
    data class Pixels(val x: Int, val y: Int) : A11yCoord
    data class Fraction(val fx: Double, val fy: Double) : A11yCoord

    fun resolve(geometry: A11yScreenGeometry): A11yPoint? = when (this) {
        is Pixels -> if (geometry.isUsable && x in 0 until geometry.widthPx && y in 0 until geometry.heightPx) {
            A11yPoint(x, y)
        } else {
            null
        }

        is Fraction -> geometry.resolveFraction(fx, fy)
    }
}

/** Stable reference to one node of one captured tree generation. */
data class A11yNodeRef(val generation: Long, val index: Int) {
    init {
        require(generation >= 0L) { "node ref generation must be non-negative" }
        require(index in 0 until A11yLimits.MAX_NODES) { "node ref index is out of the captured range" }
    }

    /** Wire form `generation:index`; opaque to the model. */
    val wire: String
        get() = "$generation:$index"

    companion object {
        fun parse(raw: String?): A11yNodeRef? {
            if (raw == null) return null
            val parts = raw.trim().split(':')
            if (parts.size != 2) return null
            val generation = parts[0].toLongOrNull() ?: return null
            val index = parts[1].toIntOrNull() ?: return null
            if (generation < 0L || index !in 0 until A11yLimits.MAX_NODES) return null
            return A11yNodeRef(generation, index)
        }
    }
}

/**
 * One readable node. [text] and [desc] are already capped and password-redacted; [ref] binds the
 * node to the [A11yTreeSnapshot.generation] it was captured in.
 */
data class A11yNode(
    val index: Int,
    val depth: Int,
    val text: String,
    val desc: String,
    val cls: String,
    val rect: A11yRect,
    val clickable: Boolean,
    val input: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val redacted: Boolean,
    val ref: A11yNodeRef
)

/**
 * One captured screen. [generation] advances on every `/dump` and on every window/configuration
 * change, so a node reference from an older capture is rejected instead of being used on a new UI.
 */
data class A11yTreeSnapshot(
    val generation: Long,
    val packageName: String,
    val windowId: Int,
    val nodes: List<A11yNode>,
    val truncated: Boolean,
    val textTruncated: Boolean,
    val redactedCount: Int
) {
    val isEmpty: Boolean
        get() = nodes.isEmpty()
}

/** Raw node draft produced by the Android adapter before the caps/redaction policy is applied. */
data class A11yNodeDraft(
    val depth: Int,
    val text: String,
    val desc: String,
    val cls: String,
    val rect: A11yRect,
    val clickable: Boolean,
    val input: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val scrollable: Boolean,
    val password: Boolean
)
