package com.muyuchat.core.designsystem

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Locks the motion contract of the shared page primitive.
 *
 * The defaults replaced hard-coded 240 ms (enter), 140 ms (fade) and 180 ms (dismiss) curves.
 * A spring has no duration, so the contract is expressed as the time at which a critically
 * damped step response reaches 99% of its travel: for damping ratio 1 the remaining travel is
 * (1 + wt)e^-wt, which is 1% when wt = 6.638. That keeps the hand-feel of the previous curves
 * while allowing callers to supply their own [androidx.compose.animation.core.FiniteAnimationSpec].
 */
class McaPageMotionContractTest {

    private val motion = McaPageMotion.Default

    @Test
    fun `enter slide is a non-bouncy spring near the old 240 ms hand-feel`() {
        val spec = motion.enterSlide as SpringSpec<*>
        assertEquals(McaPageMotion.SPRING_DAMPING_RATIO, spec.dampingRatio, 0.0001f)
        assertEquals(McaPageMotion.ENTER_STIFFNESS, spec.stiffness, 0.0001f)
        assertEquals(McaPageMotion.ENTER_VISIBILITY_THRESHOLD, spec.visibilityThreshold)
        assertTrue(
            "critical damping must not overshoot",
            spec.dampingRatio >= 1f
        )
        val settledMillis = settleMillis(spec)
        assertTrue(
            "enter should reach 99% of travel near 240 ms but reached it at $settledMillis ms",
            settledMillis in 225.0..255.0
        )
    }

    @Test
    fun `dismiss slide is a non-bouncy spring near the old 180 ms hand-feel`() {
        val spec = motion.dismissSlide as SpringSpec<*>
        assertEquals(McaPageMotion.SPRING_DAMPING_RATIO, spec.dampingRatio, 0.0001f)
        assertEquals(McaPageMotion.DISMISS_STIFFNESS, spec.stiffness, 0.0001f)
        assertEquals(McaPageMotion.DISMISS_VISIBILITY_THRESHOLD, spec.visibilityThreshold)
        assertTrue(
            "critical damping must not overshoot",
            spec.dampingRatio >= 1f
        )
        val settledMillis = settleMillis(spec)
        assertTrue(
            "dismiss should reach 99% of travel near 180 ms but reached it at $settledMillis ms",
            settledMillis in 170.0..190.0
        )
    }

    @Test
    fun `enter fade keeps the original 140 ms tween`() {
        val spec = motion.enterFade as TweenSpec<*>
        assertEquals(McaPageMotion.ENTER_FADE_MILLIS, spec.durationMillis)
        assertEquals(0, spec.delay)
    }

    @Test
    fun `callers can replace every motion primitive`() {
        val custom = McaPageMotion(
            enterSlide = spring(dampingRatio = 0.8f, stiffness = 500f),
            enterFade = tween(durationMillis = 90, delayMillis = 10),
            dismissSlide = spring(dampingRatio = 0.9f, stiffness = 900f)
        )

        assertNotSame(McaPageMotion.Default, custom)
        assertEquals(0.8f, (custom.enterSlide as SpringSpec<*>).dampingRatio, 0.0001f)
        assertEquals(500f, (custom.enterSlide as SpringSpec<*>).stiffness, 0.0001f)
        assertEquals(90, (custom.enterFade as TweenSpec<*>).durationMillis)
        assertEquals(10, (custom.enterFade as TweenSpec<*>).delay)
        assertEquals(0.9f, (custom.dismissSlide as SpringSpec<*>).dampingRatio, 0.0001f)
        assertEquals(900f, (custom.dismissSlide as SpringSpec<*>).stiffness, 0.0001f)
    }

    private fun settleMillis(spec: SpringSpec<*>): Double {
        assertEquals(
            "the 99% settle contract is only defined for critical damping",
            1.0f,
            spec.dampingRatio,
            0.0001f
        )
        val naturalFrequency = sqrt(spec.stiffness.toDouble())
        return CRITICAL_DAMPING_ONE_PERCENT_UNITS / naturalFrequency * 1_000.0
    }

    private companion object {
        /** Solves (1 + wt)e^-wt = 0.01 for a dense, non-overshooting spring. */
        const val CRITICAL_DAMPING_ONE_PERCENT_UNITS = 6.6383
    }
}
