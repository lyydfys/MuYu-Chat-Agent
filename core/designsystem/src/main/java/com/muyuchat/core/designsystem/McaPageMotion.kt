package com.muyuchat.core.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * The motion contract for [SmoothRightToLeftPage].
 *
 * Defaults keep the hand-feel of the curves they replace: both slides are critically damped
 * (no overshoot, like the tweens they replace), and reach 99% of their travel in roughly
 * 240 ms on enter and 180 ms on exit. The enter fade keeps its original 140 ms tween.
 *
 * The explicit visibility thresholds bound how long the animator keeps running after the page
 * is visually in place: 1 px for both slides, so the dismiss callback fires once the page is
 * fully off screen instead of waiting for an unobservable sub-pixel tail.
 *
 * These are plain spec values, not animations. [SmoothRightToLeftPage] reads the animated
 * offset only inside `graphicsLayer`, i.e. in the draw/layer phase, never during composition.
 *
 * Callers can pass any [FiniteAnimationSpec] - a different spring, a tween, or a shared
 * instance - without touching the page wrapper.
 */
public data class McaPageMotion(
    /** Horizontal slide used when the page appears. */
    public val enterSlide: FiniteAnimationSpec<IntOffset>,
    /** Fade paired with [enterSlide]; the previous wrapper used a 140 ms tween. */
    public val enterFade: FiniteAnimationSpec<Float>,
    /** Horizontal slide used when the page is dismissed through its own motion. */
    public val dismissSlide: FiniteAnimationSpec<Float>
) {
    public companion object {
        /** 1.0 is critical damping: the page settles without bouncing past its target. */
        public const val SPRING_DAMPING_RATIO: Float = 1.0f

        /**
         * Natural frequency sqrt(stiffness) = 27.57 rad/s. For critical damping the remaining
         * travel is (1 + wt)e^-wt, which is 1% at wt = 6.638, i.e. ~241 ms.
         */
        public const val ENTER_STIFFNESS: Float = 760f

        /**
         * sqrt(stiffness) = 36.88 rad/s, so the same 1% point lands at ~180 ms.
         */
        public const val DISMISS_STIFFNESS: Float = 1360f

        /** Unchanged from the previous hard-coded fade length. */
        public const val ENTER_FADE_MILLIS: Int = 140

        /** Stop the enter slide within one pixel of the target before the animator settles. */
        public val ENTER_VISIBILITY_THRESHOLD: IntOffset = IntOffset(1, 1)

        /** Stop the dismiss slide within one pixel of fully off screen. */
        public const val DISMISS_VISIBILITY_THRESHOLD: Float = 1f

        public val Default: McaPageMotion = McaPageMotion(
            enterSlide = spring(
                dampingRatio = SPRING_DAMPING_RATIO,
                stiffness = ENTER_STIFFNESS,
                visibilityThreshold = ENTER_VISIBILITY_THRESHOLD
            ),
            enterFade = tween(durationMillis = ENTER_FADE_MILLIS),
            dismissSlide = spring(
                dampingRatio = SPRING_DAMPING_RATIO,
                stiffness = DISMISS_STIFFNESS,
                visibilityThreshold = DISMISS_VISIBILITY_THRESHOLD
            )
        )
    }
}
