package com.muyuchat.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behavioural tests for the shared page motion owner.
 *
 * These drive the real [SmoothPageMotionState] on a deterministic frame clock instead of
 * asserting that the source contains a channel or an Animatable. They cover the interruption
 * paths that caused visible regressions: a gesture taking over a running entrance, a committed
 * exit that late samples must not reverse, a callback swapped while the page is still moving,
 * a resize, disposal, and zero-duration system animations.
 */
class SmoothPageMotionStateTest {

    @Test
    fun `gesture takes over the current entrance position and cancellation returns to open`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var dismissCount = 0
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.enter()
            drainCommands()
            clock.advance(5)
            val current = state.offsetX.value.x
            assertTrue("entrance should be part way on screen", current in 1..999)

            state.progress(0f)
            drainCommands()
            assertEquals("gesture takeover must not pre-jump", current, state.offsetX.value.x)
            state.progress(0.5f)
            drainCommands()
            assertEquals((current + (1_000 - current) * 0.5f).roundToInt(), state.offsetX.value.x)

            state.cancel()
            drainCommands()
            clock.advance(90)
            assertEquals(0, state.offsetX.value.x)
            assertEquals(0, dismissCount)
            owner.cancelAndJoin()
        }

    @Test
    fun `repeated back commits one dismissal and late progress cannot interrupt it`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(0)
            var dismissCount = 0
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.dismiss()
            // These happen before the consumer runs, exercising the conflated mailbox boundary.
            state.progress(0.1f)
            state.cancel()
            drainCommands()
            clock.advance(2)
            var previous = state.offsetX.value.x
            assertTrue("the committed exit must move away from the open position", previous > 0)
            repeat(4) { state.dismiss(); drainCommands() }
            state.progress(0.1f)
            drainCommands()
            state.cancel()
            drainCommands()
            repeat(6) {
                clock.step()
                drainCommands()
                val current = state.offsetX.value.x
                assertTrue(
                    "a committed exit must not hover or reverse ($previous -> $current)",
                    current >= previous
                )
                previous = current
            }
            clock.advance(90)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            owner.cancelAndJoin()
        }

    @Test
    fun `mixed start cancel and progress burst keeps one position owner`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var dismissCount = 0
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { dismissCount++ }
            }

            // Commands interleaved with running settles: every new command must take over the
            // current offset instead of stacking a second horizontal translation.
            repeat(4) {
                state.enter()
                drainCommands()
                state.progress(0f)
                drainCommands()
                state.progress(0.4f)
                drainCommands()
                state.cancel()
                drainCommands()
                clock.advance(2)
            }
            clock.advance(60)
            assertEquals(0, state.offsetX.value.x)

            // Commands posted inside one UI frame are conflated by design; the owner still ends
            // in a defined state and never leaves two animators running.
            state.progress(0.2f)
            state.enter()
            state.cancel()
            state.progress(0.8f)
            state.enter()
            drainCommands()
            clock.advance(60)
            assertEquals(0, state.offsetX.value.x)
            assertEquals(0, dismissCount)

            state.dismiss()
            drainCommands()
            clock.advance(90)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            owner.cancelAndJoin()
        }

    @Test
    fun `completion callback replacement during the entrance is honoured`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var original = 0
            var replacement = 0
            // Mirrors the composable: the owner holds a lambda that reads the latest callback.
            var activeCallback: () -> Unit = { original++ }
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { activeCallback() }
            }
            state.enter()
            drainCommands()
            clock.advance(3)
            activeCallback = { replacement++ }

            state.dismiss()
            drainCommands()
            clock.advance(90)
            assertEquals("the callback swapped during the entrance must be invoked", 1, replacement)
            assertEquals("the stale callback must never run", 0, original)
            owner.cancelAndJoin()
        }

    @Test
    fun `window width changes apply to the next gesture and dismissal`() =
        motionTest { clock ->
            var width = 600
            val state = SmoothPageMotionState(width)
            var dismissCount = 0
            val owner = launch {
                state.run({ width }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.enter()
            drainCommands()
            clock.advance(3)
            val beforeResize = state.offsetX.value.x
            assertTrue("entrance should be part way on screen", beforeResize in 1..599)

            // A resize must not restart or jump the running entrance: the collector is keyed on
            // the stable owner, and the animation keeps its original target.
            width = 900
            drainCommands()
            assertEquals(beforeResize, state.offsetX.value.x)
            clock.advance(60)
            assertEquals(0, state.offsetX.value.x)

            state.progress(0f)
            drainCommands()
            state.progress(0.5f)
            drainCommands()
            assertEquals("gesture progress must use the new width", 450, state.offsetX.value.x)
            state.cancel()
            drainCommands()
            clock.advance(60)
            assertEquals(0, state.offsetX.value.x)

            state.dismiss()
            drainCommands()
            clock.advance(90)
            assertEquals("dismissal must use the new width", 900, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            owner.cancelAndJoin()
        }

    @Test
    fun `back during the entrance continues from the current offset without a pre-jump`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var dismissCount = 0
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.enter()
            drainCommands()
            clock.advance(5)
            val before = state.offsetX.value.x
            assertTrue("entrance should be part way on screen", before in 1..999)

            state.dismiss()
            drainCommands()
            assertEquals("committing the exit must not pre-jump", before, state.offsetX.value.x)

            var previous = before
            repeat(6) {
                clock.step()
                drainCommands()
                val current = state.offsetX.value.x
                assertTrue("the exit must keep moving out, not reverse", current >= previous)
                previous = current
            }
            clock.advance(90)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            owner.cancelAndJoin()
        }

    @Test
    fun `a cancelled gesture can still be dismissed afterwards`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(0)
            var dismissCount = 0
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.enter()
            drainCommands()
            clock.advance(3)
            state.progress(0f)
            drainCommands()
            state.progress(0.7f)
            drainCommands()
            state.cancel()
            drainCommands()
            clock.advance(60)
            assertEquals(0, state.offsetX.value.x)
            assertEquals(0, dismissCount)

            state.dismiss()
            drainCommands()
            clock.advance(90)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            owner.cancelAndJoin()
        }

    @Test
    fun `disabled system animation still finishes and uses latest width`() =
        motionTest(durationScale = 0f) { clock ->
            val state = SmoothPageMotionState(500)
            var width = 500
            var dismissCount = 0
            val owner = launch {
                state.run({ width }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.enter()
            drainCommands()
            // No frame may be required to reach the terminal state when animations are off.
            assertEquals(0, state.offsetX.value.x)
            width = 700
            state.dismiss()
            drainCommands()
            assertEquals(700, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            clock.advance(3)
            owner.cancelAndJoin()
        }

    @Test
    fun `disposal cancels animation without calling dismissed or hanging a frame wait`() =
        motionTest { clock ->
            val state = SmoothPageMotionState(0)
            var dismissCount = 0
            val owner = launch {
                state.run({ 1_000 }, { McaPageMotion.Default }) { dismissCount++ }
            }
            state.dismiss()
            drainCommands()
            clock.step()
            drainCommands()
            withTimeout(1_000) { owner.cancelAndJoin() }
            assertTrue("the owner must finish instead of waiting for another frame", owner.isCompleted)
            clock.advance(3)
            assertEquals(0, dismissCount)
        }

    @Test
    fun `float tween and spring specs keep their trajectory on IntOffset`() =
        motionTest { clock ->
            // The dismiss contract is a Float spec while the owner animates IntOffset. These
            // checks prove the conversion keeps the same curve instead of swapping in a default.
            assertConversionMatches(clock, tween<Float>(durationMillis = 180))
            assertConversionMatches(clock, spring<Float>(dampingRatio = 1f, stiffness = 1_360f))
        }

    /**
     * Runs the same Float spec twice: once on a Float animator and once through
     * [horizontalOffsetSpec] on the IntOffset animator the page uses, and checks every frame.
     */
    private suspend fun CoroutineScope.assertConversionMatches(
        clock: ManualClock,
        floatSpec: FiniteAnimationSpec<Float>
    ) {
        val floatAnimator = Animatable(0f)
        val intAnimator = Animatable(IntOffset(0, 0), IntOffset.VectorConverter)
        val floatJob = launch { floatAnimator.animateTo(1_000f, floatSpec) }
        val intSpec = floatSpec.horizontalOffsetSpec()
        val intJob = launch { intAnimator.animateTo(IntOffset(1_000, 0), intSpec) }
        drainCommands()
        var frames = 0
        while (!floatJob.isCompleted || !intJob.isCompleted) {
            clock.step()
            drainCommands()
            assertTrue(
                "IntOffset ${intAnimator.value.x} drifted from float ${floatAnimator.value}",
                abs(intAnimator.value.x - floatAnimator.value) <= 1f
            )
            frames++
            if (frames > 240) fail("the converted animation never finished")
        }
        assertEquals(1_000, intAnimator.value.x)
        assertEquals(1_000f, floatAnimator.value, 0.5f)
    }

    private fun motionTest(
        durationScale: Float? = null,
        body: suspend CoroutineScope.(ManualClock) -> Unit
    ) {
        val clock = ManualClock()
        val context: CoroutineContext =
            if (durationScale == null) clock else clock + FixedMotionScale(durationScale)
        runBlocking(context) {
            withTimeout(5_000) { body(clock) }
        }
    }

    private suspend fun ManualClock.advance(frames: Int) {
        repeat(frames) {
            step()
            drainCommands()
        }
    }

    private class FixedMotionScale(override val scaleFactor: Float) : MotionDurationScale

    /**
     * Deterministic frame clock. Unlike a single-consumer channel it delivers each frame to
     * every awaiter, which is required when two animators are compared on the same timeline.
     */
    private class ManualClock : MonotonicFrameClock {
        private val clock = BroadcastFrameClock()
        private var nowNanos = 0L

        fun step() {
            nowNanos += FRAME_NANOS
            clock.sendFrame(nowNanos)
        }

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            clock.withFrameNanos(onFrame)
    }
}

private const val FRAME_NANOS = 16_666_667L

private suspend fun drainCommands() {
    repeat(8) { yield() }
}
