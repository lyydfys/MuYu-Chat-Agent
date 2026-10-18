package com.muyuchat.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shared motion language introduced by [McaMotion] and its interaction with the page
 * primitive defaults.
 *
 * The numeric assertions are contract values, not "close enough" checks: [McaMotion] exists so
 * every module gets the same hand-feel, so a silent change to a stiffness or a duration has to
 * fail here and be reviewed. The MotionDurationScale=0 tests run the real [Animatable] on a
 * deterministic JVM frame clock to prove that disabled system animations land on the final
 * value instead of suspending on a hidden intermediate state.
 */
class McaMotionContractTest {

    @Test
    fun `motion specs are explicit and differ from the compose defaults`() {
        val librarySpring = spring<Float>()
        val press = McaMotion.press as SpringSpec<*>
        val contentEnter = McaMotion.contentEnter as SpringSpec<*>
        val sizeChange = McaMotion.sizeChange as SpringSpec<*>
        val pageSlide = McaMotion.pageSlide as SpringSpec<*>

        assertTrue(
            "press must not fall back to the library stiffness",
            press.stiffness != librarySpring.stiffness
        )
        assertTrue(
            "contentEnter must lower damping for its slight overshoot",
            contentEnter.dampingRatio != librarySpring.dampingRatio
        )
        assertTrue(
            "sizeChange must not be the library spring",
            sizeChange.dampingRatio != librarySpring.dampingRatio
        )
        assertTrue(
            "pageSlide must not fall back to the library stiffness",
            pageSlide.stiffness != librarySpring.stiffness
        )
        assertNotEquals(librarySpring.visibilityThreshold, pageSlide.visibilityThreshold)

        val defaultFade = McaMotion.fade() as TweenSpec<*>
        assertEquals(160, defaultFade.durationMillis)
        assertTrue(
            "fade() must not silently become Compose's 300 ms default",
            defaultFade.durationMillis != tween<Float>().durationMillis
        )
        assertEquals(220, (McaMotion.fade(220) as TweenSpec<*>).durationMillis)
    }

    @Test
    fun `spec parameters match the documented hand-feel`() {
        assertEquals(1.0f, (McaMotion.press as SpringSpec<*>).dampingRatio, 0.0001f)
        assertEquals(1100f, (McaMotion.press as SpringSpec<*>).stiffness, 0.0001f)

        assertEquals(0.9f, (McaMotion.contentEnter as SpringSpec<*>).dampingRatio, 0.0001f)
        assertEquals(380f, (McaMotion.contentEnter as SpringSpec<*>).stiffness, 0.0001f)

        assertEquals(0.86f, (McaMotion.sizeChange as SpringSpec<*>).dampingRatio, 0.0001f)
        assertEquals(420f, (McaMotion.sizeChange as SpringSpec<*>).stiffness, 0.0001f)

        assertEquals(1.0f, (McaMotion.pageSlide as SpringSpec<*>).dampingRatio, 0.0001f)
        assertEquals(760f, (McaMotion.pageSlide as SpringSpec<*>).stiffness, 0.0001f)
        assertEquals(
            IntOffset(1, 1),
            (McaMotion.pageSlide as SpringSpec<*>).visibilityThreshold
        )
    }

    @Test
    fun `size change pixel spec mirrors the float spring for IntSize animation`() {
        val sizeChange = McaMotion.sizeChange as SpringSpec<*>
        val sizeChangePx = McaMotion.sizeChangePx as SpringSpec<*>

        assertEquals(0.86f, sizeChangePx.dampingRatio, 0.0001f)
        assertEquals(420f, sizeChangePx.stiffness, 0.0001f)
        assertEquals(
            "the pixel and float variants must keep the same spring parameters",
            sizeChange.dampingRatio,
            sizeChangePx.dampingRatio,
            0.0001f
        )
        assertEquals(
            sizeChange.stiffness,
            sizeChangePx.stiffness,
            0.0001f
        )
        assertTrue(
            "IntSize animations need an explicit visibility threshold",
            sizeChangePx.visibilityThreshold != null
        )
        assertEquals(IntSize(1, 1), sizeChangePx.visibilityThreshold)
    }

    @Test
    fun `press scale is subtle but still visible`() {
        assertEquals(0.97f, McaMotion.PRESS_SCALE, 0f)
        assertTrue(
            "below 0.90 reads as zoom rather than weight: ${McaMotion.PRESS_SCALE}",
            McaMotion.PRESS_SCALE >= 0.90f
        )
        assertTrue(
            "at or above 1.0 the press feedback disappears: ${McaMotion.PRESS_SCALE}",
            McaMotion.PRESS_SCALE < 1f
        )
    }

    @Test
    fun `stagger is bounded and clamps at the last step`() {
        assertEquals(26, McaMotion.STAGGER_MILLIS)
        assertEquals(8, McaMotion.MAX_STAGGER_STEPS)

        assertEquals(0L, enterRevealDelayMillis(-5))
        assertEquals(0L, enterRevealDelayMillis(0))
        assertEquals(26L, enterRevealDelayMillis(1))
        assertEquals(208L, enterRevealDelayMillis(McaMotion.MAX_STAGGER_STEPS))
        assertEquals(
            enterRevealDelayMillis(McaMotion.MAX_STAGGER_STEPS),
            enterRevealDelayMillis(McaMotion.MAX_STAGGER_STEPS + 1)
        )
        assertEquals(
            enterRevealDelayMillis(McaMotion.MAX_STAGGER_STEPS),
            enterRevealDelayMillis(10_000)
        )
        assertTrue(
            "a stagger must never delay the list by more than ~250 ms",
            enterRevealDelayMillis(McaMotion.MAX_STAGGER_STEPS) <= 250L
        )
    }

    @Test
    fun `page motion defaults are unchanged by the motion token work`() {
        val page = McaPageMotion.Default
        val enter = page.enterSlide as SpringSpec<*>
        val dismiss = page.dismissSlide as SpringSpec<*>
        val fade = page.enterFade as TweenSpec<*>

        assertEquals(1.0f, enter.dampingRatio, 0.0001f)
        assertEquals(760f, enter.stiffness, 0.0001f)
        assertEquals(IntOffset(1, 1), enter.visibilityThreshold)

        assertEquals(1.0f, dismiss.dampingRatio, 0.0001f)
        assertEquals(1360f, dismiss.stiffness, 0.0001f)
        assertEquals(1f, dismiss.visibilityThreshold)

        assertEquals(140, fade.durationMillis)
        assertEquals(
            "McaMotion.pageSlide must stay aligned with the page primitive",
            enter.dampingRatio,
            (McaMotion.pageSlide as SpringSpec<*>).dampingRatio,
            0.0001f
        )
        assertEquals(
            enter.stiffness,
            (McaMotion.pageSlide as SpringSpec<*>).stiffness,
            0.0001f
        )
        assertEquals(
            enter.visibilityThreshold,
            (McaMotion.pageSlide as SpringSpec<*>).visibilityThreshold
        )
    }

    @Test
    fun `system animations disabled leaves the press animation on its target`() = runBlocking(
        FixedFrameClock() + FixedDurationScale(0f)
    ) {
        val press = Animatable(0f)
        press.animateTo(1f, McaMotion.press)
        assertEquals(1f, press.value, 0f)
    }

    @Test
    fun `enter reveal snaps to final state and skips stagger when animations are disabled`() =
        runBlocking(FixedFrameClock() + FixedDurationScale(0f)) {
            val progress = Animatable(0f)
            // The clamped stagger would wait 208 ms; snapping must finish long before that.
            withTimeout(100) {
                animateEnterReveal(
                    progress = progress,
                    index = McaMotion.MAX_STAGGER_STEPS,
                    animate = true
                )
            }
            assertEquals(1f, progress.value, 0f)
        }

    @Test
    fun `enter reveal with animate false snaps immediately`() = runBlocking(
        FixedFrameClock() + FixedDurationScale(1f)
    ) {
        val progress = Animatable(0f)
        withTimeout(100) {
            animateEnterReveal(progress = progress, index = 1, animate = false)
        }
        assertEquals(1f, progress.value, 0f)
    }

    @Test
    fun `animated values are only read inside graphicsLayer`() {
        val source = designSystemSource("McaMotion.kt")

        assertTrue(
            "press scale must be read in graphicsLayer",
            Regex("""graphicsLayer\s*\{[^}]*scaleX = scale\.value[^}]*scaleY = scale\.value[^}]*\}""")
                .containsMatchIn(source)
        )
        assertEquals(
            "scale.value must be read exactly twice, both inside graphicsLayer",
            2,
            source.split("scale.value").size - 1
        )

        assertTrue(
            "enter reveal must read progress in graphicsLayer",
            Regex(
                """graphicsLayer\s*\{[^}]*alpha = progress\.value""" +
                    """[^}]*translationY = \(1f - progress\.value\)[^}]*\}"""
            ).containsMatchIn(source)
        )
        assertEquals(
            "progress.value must be read exactly twice, both inside graphicsLayer",
            2,
            source.split("progress.value").size - 1
        )
    }

    @Test
    fun `reduced motion is handled before the stagger delay`() {
        val source = designSystemSource("McaMotion.kt")

        val scaleRead = source.indexOf("currentCoroutineContext()[MotionDurationScale]")
        val zeroCheck = source.indexOf("durationScale == 0f")
        val snap = source.indexOf("progress.snapTo(1f)")
        val delay = source.indexOf("delay(enterRevealDelayMillis(index))")
        val animate = source.indexOf("progress.animateTo(1f, McaMotion.contentEnter)")

        assertTrue("mcaEnterReveal must read MotionDurationScale", scaleRead >= 0)
        assertTrue("mcaEnterReveal must special-case a zero duration scale", zeroCheck >= 0)
        assertTrue("mcaEnterReveal must snap to the final state", snap >= 0)
        assertTrue("the stagger delay must still exist for normal motion", delay >= 0)
        assertTrue("the normal path must still animate to 1f", animate >= 0)
        assertTrue(
            "the zero-scale check must run before the delay",
            scaleRead < delay
        )
        assertTrue(
            "the snap must be reachable before the normal animation",
            snap < animate
        )
    }

    @Test
    fun `enter visibility remains hidden first`() {
        val source = designSystemSource("McaMotion.kt")
        assertTrue(
            "rememberEnterVisibility must start hidden and then target visibility",
            Regex(
                """remember\s*\{\s*MutableTransitionState\(false\)\s*\}\s*""" +
                    """\.also\s*\{\s*it\.targetState = visible\s*\}"""
            ).containsMatchIn(source)
        )
    }

    private class FixedDurationScale(
        override val scaleFactor: Float
    ) : MotionDurationScale

    private class FixedFrameClock : MonotonicFrameClock {
        var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 16_666_667L
            return onFrame(nanos)
        }
    }
}
