package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Render throttle for live stream text.
 *
 * The gate exists so a fast model cannot recompose a floating window over another app at 60 fps; both
 * limits must hold independently, and the turn end is flushed elsewhere (so a throttled tail is never
 * lost, it is simply shown by the finished bubble).
 */
class DshOverlayStreamThrottleTest {

    @Test
    fun theFirstPushNeedsTheMinimumCharacterGain() {
        val throttle = DshOverlayStreamThrottle()

        assertFalse("one character is not worth a redraw", throttle.shouldEmit(1, nowMs = 0L))
        assertTrue(throttle.shouldEmit(2, nowMs = 0L))
    }

    @Test
    fun theIntervalAndTheCharacterGainMustBothBeSatisfied() {
        val throttle = DshOverlayStreamThrottle(minIntervalMillis = 100L, minCharDelta = 2)

        assertTrue(throttle.shouldEmit(textLength = 10, nowMs = 1_000L))
        // Enough characters, but inside the interval.
        assertFalse(throttle.shouldEmit(textLength = 30, nowMs = 1_050L))
        // Outside the interval, but not enough new characters.
        assertFalse(throttle.shouldEmit(textLength = 11, nowMs = 1_200L))
        // Both satisfied.
        assertTrue(throttle.shouldEmit(textLength = 12, nowMs = 1_200L))
    }

    @Test
    fun oneHundredMillisecondsIsEnoughForTheDefaultInterval() {
        val throttle = DshOverlayStreamThrottle()

        assertTrue(throttle.shouldEmit(textLength = 4, nowMs = 0L))
        assertFalse(throttle.shouldEmit(textLength = 8, nowMs = 99L))
        assertTrue(throttle.shouldEmit(textLength = 8, nowMs = 100L))
    }

    @Test
    fun consecutiveDeltasOnlyLetSomePushesThrough() {
        val throttle = DshOverlayStreamThrottle(minIntervalMillis = 100L, minCharDelta = 2)
        val pushes = mutableListOf<Long>()

        // 40 deltas of 1 char each, 16 ms apart (a fast token stream).
        for (index in 1..40) {
            val now = index * 16L
            if (throttle.shouldEmit(textLength = index, nowMs = now)) pushes += now
        }

        assertTrue("at least a few frames get through: $pushes", pushes.size >= 4)
        assertTrue("far fewer than one per delta: $pushes", pushes.size <= 10)
        assertTrue("the gap honours the interval", pushes.zipWithNext().all { (a, b) -> b - a >= 100L })
    }

    @Test
    fun anUnchangedLengthIsNeverPushed() {
        val throttle = DshOverlayStreamThrottle(minIntervalMillis = 1L, minCharDelta = 2)

        assertTrue(throttle.shouldEmit(textLength = 5, nowMs = 0L))
        assertFalse(throttle.shouldEmit(textLength = 5, nowMs = 1_000L))
    }

    @Test
    fun aBackwardsClockDoesNotFreezeTheOverlay() {
        val throttle = DshOverlayStreamThrottle(minIntervalMillis = 100L, minCharDelta = 2)

        assertTrue(throttle.shouldEmit(textLength = 5, nowMs = 10_000L))
        assertTrue(throttle.shouldEmit(textLength = 20, nowMs = 1_000L))
    }

    @Test
    fun resetMakesTheNextPushImmediate() {
        val throttle = DshOverlayStreamThrottle(minIntervalMillis = 1_000L, minCharDelta = 2)

        assertTrue(throttle.shouldEmit(textLength = 4, nowMs = 0L))
        assertFalse(throttle.shouldEmit(textLength = 12, nowMs = 10L))
        throttle.reset()
        assertTrue(throttle.shouldEmit(textLength = 12, nowMs = 20L))
    }
}
