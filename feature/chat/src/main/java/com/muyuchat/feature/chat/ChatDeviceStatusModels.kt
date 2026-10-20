package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

internal data class ChatDeviceStatusItem(
    val key: String,
    val label: String,
    val value: String,
    val detail: String,
    val active: Boolean,
    val known: Boolean = true,
    val tone: ChatDeviceStatusTone =
        if (known) ChatDeviceStatusTone.NEUTRAL else ChatDeviceStatusTone.UNKNOWN
)

/**
 * How loudly one status row is drawn.
 *
 * Deliberately separate from [ChatDeviceStatusItem.active]: `active` records whether an
 * accelerator is executing *with native proof*, while `tone` decides emphasis.  Keeping
 * them apart is what lets unprobed evidence be drawn as unknown instead of being painted
 * like a reading, so the panel cannot dress configuration up as execution.
 */
internal enum class ChatDeviceStatusTone { NEUTRAL, ACTIVE, WARN, CRITICAL, UNKNOWN }

internal data class ChatDeviceMemorySample(
    val totalKb: Long,
    val availableKb: Long,
    val lowMemory: Boolean
)

internal data class ChatDeviceTemperatureSample(
    val celsius: Float,
    val source: String
)

/**
 * Absolute ball-size presets.
 *
 * The chosen size is stored as an absolute dp value rather than a fraction of the screen.
 * A screen fraction made the same setting produce 21.6dp on a 360dp phone and 26.9dp on a
 * 448dp one, and let the soft keyboard resize the ball whenever it changed the overlay
 * host's height.  An absolute value means the number the user picks is the size they get,
 * on every device, in every orientation.
 */
internal enum class FloatingDeviceStatusSize(val dp: Float, val label: String) {
    SMALL(28f, "小"),
    MEDIUM(36f, "中"),
    LARGE(44f, "大"),
    EXTRA_LARGE(52f, "特大");

    companion object {
        fun nearest(storedDp: Float): FloatingDeviceStatusSize =
            entries.minByOrNull { kotlin.math.abs(it.dp - storedDp) } ?: LARGE
    }
}

internal const val DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP = 44f
// Keep the default park left of the message action column (48dp) so copy is never covered.
internal const val DEFAULT_FLOATING_X_FRACTION = 0.82f

/**
 * Content tiers, one per preset.
 *
 * The previous single `ballSize >= 32.dp` branch made the ball's contents reflow all at
 * once part-way through the range.  Each tier now has a designed layout, so growing the
 * ball adds detail progressively instead of jumping.
 */
internal enum class FloatingDeviceStatusBallTier { COMPACT, STANDARD, FULL }
