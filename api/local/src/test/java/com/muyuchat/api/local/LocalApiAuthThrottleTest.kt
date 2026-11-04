package com.muyuchat.api.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure-based throttle of the LAN-facing Local API.
 *
 * These cases are pure: the clock is an argument, so the cool-down is asserted instead of slept.
 */
class LocalApiAuthThrottleTest {

    private val throttle = LocalApiAuthThrottle()

    @Test
    fun `failures below the limit do not block`() {
        repeat(LocalApiAuthThrottle.MAX_FAILURES - 1) { index ->
            assertFalse(throttle.recordFailure("10.0.0.2", 1_000L + index))
            assertFalse(throttle.isBlocked("10.0.0.2", 1_000L + index))
        }
    }

    @Test
    fun `the limit trips the block`() {
        var tripped = false
        repeat(LocalApiAuthThrottle.MAX_FAILURES) { index ->
            tripped = throttle.recordFailure("10.0.0.2", 1_000L + index)
        }
        assertTrue(tripped)
        assertTrue(throttle.isBlocked("10.0.0.2", 2_000L))
    }

    @Test
    fun `the block expires after the cool-down`() {
        repeat(LocalApiAuthThrottle.MAX_FAILURES) { throttle.recordFailure("10.0.0.2", 1_000L) }
        assertTrue(throttle.isBlocked("10.0.0.2", 2_000L))
        val afterCoolDown = 1_000L + LocalApiAuthThrottle.BLOCK_MILLIS + 1L
        assertFalse(throttle.isBlocked("10.0.0.2", afterCoolDown))
    }

    @Test
    fun `retry after reports the remaining cool-down in whole seconds`() {
        repeat(LocalApiAuthThrottle.MAX_FAILURES) { throttle.recordFailure("10.0.0.2", 1_000L) }
        val blockedUntil = 1_000L + LocalApiAuthThrottle.BLOCK_MILLIS
        assertEquals(
            ((blockedUntil - 2_000L) + 999L) / 1_000L,
            throttle.retryAfterSeconds("10.0.0.2", 2_000L)
        )
        assertEquals(0L, throttle.retryAfterSeconds("10.0.0.2", blockedUntil))
        assertEquals(0L, throttle.retryAfterSeconds("10.0.0.9", 2_000L))
    }

    @Test
    fun `a success clears the history`() {
        repeat(LocalApiAuthThrottle.MAX_FAILURES - 1) { throttle.recordFailure("10.0.0.2", 1_000L) }
        throttle.recordSuccess("10.0.0.2")
        // After the reset it takes the full budget again to trip the block.
        assertFalse(throttle.recordFailure("10.0.0.2", 2_000L))
        assertFalse(throttle.isBlocked("10.0.0.2", 2_000L))
    }

    @Test
    fun `failures older than the window do not accumulate`() {
        repeat(LocalApiAuthThrottle.MAX_FAILURES - 1) { throttle.recordFailure("10.0.0.2", 1_000L) }
        // The next failure lands after the window, so the streak restarts at one.
        assertFalse(throttle.recordFailure("10.0.0.2", 1_000L + LocalApiAuthThrottle.WINDOW_MILLIS + 1L))
    }

    @Test
    fun `one blocked address does not affect another`() {
        repeat(LocalApiAuthThrottle.MAX_FAILURES) { throttle.recordFailure("10.0.0.2", 1_000L) }
        assertTrue(throttle.isBlocked("10.0.0.2", 2_000L))
        assertFalse(throttle.isBlocked("10.0.0.3", 2_000L))
        assertFalse(throttle.recordFailure("10.0.0.3", 2_000L))
    }
}
