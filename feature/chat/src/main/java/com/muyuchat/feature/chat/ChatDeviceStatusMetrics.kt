package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun chatDeviceStatusToneColor(tone: ChatDeviceStatusTone): Color = when (tone) {
    ChatDeviceStatusTone.ACTIVE -> McaPrimaryBlue
    ChatDeviceStatusTone.WARN -> McaStatusAmber
    ChatDeviceStatusTone.CRITICAL -> MaterialTheme.colorScheme.error
    ChatDeviceStatusTone.UNKNOWN -> MaterialTheme.colorScheme.outline
    ChatDeviceStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Ceiling on how much of the short side the ball may take on an unusually small host. */
private const val MAX_FLOATING_DEVICE_STATUS_SHORT_SIDE_SHARE = 0.16f
private const val MIN_FLOATING_DEVICE_STATUS_SIZE_DP = 16f

/**
 * Resolves the drawn size from the user's absolute choice.
 *
 * The available area only acts as a safety clamp for very small or split-screen hosts.
 * There is deliberately no fixed lower/upper bound inside the selectable range: the old
 * `coerceIn(18f, 56f)` around a screen fraction made the first 12.5% of the slider dead
 * on a 360dp phone and left the 56dp ceiling unreachable on every phone.
 */
internal fun floatingDeviceStatusBallSize(
    chosenDp: Float,
    availableShortSideDp: Float
): Dp {
    val ceiling = (availableShortSideDp * MAX_FLOATING_DEVICE_STATUS_SHORT_SIDE_SHARE)
        .takeIf { it.isFinite() && it > 0f }
        ?: chosenDp
    return chosenDp.coerceAtMost(ceiling).coerceAtLeast(MIN_FLOATING_DEVICE_STATUS_SIZE_DP).dp
}

internal fun floatingDeviceStatusBallTier(ballDp: Float): FloatingDeviceStatusBallTier = when {
    ballDp < 40f -> FloatingDeviceStatusBallTier.COMPACT
    ballDp < 48f -> FloatingDeviceStatusBallTier.STANDARD
    else -> FloatingDeviceStatusBallTier.FULL
}

/**
 * Ring weight scales with the ball so the small presets stay legible and the large ones
 * stay elegant.  The old fixed 2.5dp stroke was invisible on a 52dp ball.
 */
internal fun floatingDeviceStatusRingStrokeDp(ballDp: Float): Float =
    (ballDp * 0.085f).coerceIn(2.5f, 4.5f)

/**
 * "4.7 GB" -> "4.7G".  The compact form is what lets the 28dp and 36dp presets render the
 * value inside the ring without clipping; the full form measured ~24dp against ~21.6dp of
 * available width at the old default size.
 */
internal fun compactMemoryLabel(label: String): String =
    label.replace(" ", "").replace("GB", "G").replace("MB", "M")

internal fun chatTemperatureTone(celsius: Float): ChatDeviceStatusTone = when {
    celsius >= 65f -> ChatDeviceStatusTone.CRITICAL
    celsius >= 45f -> ChatDeviceStatusTone.WARN
    else -> ChatDeviceStatusTone.NEUTRAL
}

internal fun chatTemperatureBandLabel(celsius: Float): String = when {
    celsius >= 65f -> "偏热，可能接近降频"
    celsius >= 45f -> "偏热"
    else -> "正常"
}

internal fun memoryUsedFraction(totalKb: Long, availableKb: Long): Float? =
    if (totalKb > 0L) {
        (1f - availableKb.coerceIn(0L, totalKb).toFloat() / totalKb.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

internal fun formatMemoryMb(kb: Long): String {
    val mb = kb / 1024.0
    return if (mb >= 1024.0) {
        "%.1f GB".format(java.util.Locale.US, mb / 1024.0)
    } else {
        "%.0f MB".format(java.util.Locale.US, mb)
    }
}
