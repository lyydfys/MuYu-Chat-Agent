package com.muyuchat.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Tactile feedback primitives aligned with flagship linear-actuator choreography.
 *
 * All feedback calls route through [View.performHapticFeedback] so system haptic settings and
 * Do-Not-Disturb preferences are respected automatically without requiring the VIBRATE permission.
 */
@Immutable
public class McaHaptics internal constructor(
    private val view: View,
) {
    /** Crisp micro-tick for lightweight toggles, icon taps, and menu switches. */
    public fun tick() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** Positive confirmation pulse for Send, Copy, Pin, and Scroll-to-Bottom actions. */
    public fun confirm() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    /** Firm interruption pulse when stopping an active generation stream. */
    public fun stop() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    /** Switch / checkbox style state flip; distinguishes turning on from turning off on API 34+. */
    public fun toggle(on: Boolean) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** One detent of a stepped slider or picker. */
    public fun segmentTick() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** Press-and-hold recognised (context menu, selection mode). */
    public fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Action refused (blocked, invalid input). Shares the firm pulse of [stop]. */
    public fun reject() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

@Composable
public fun rememberMcaHaptics(): McaHaptics {
    val view = LocalView.current
    return remember(view) { McaHaptics(view) }
}
