package com.muyuchat.core.dsh.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityThrottleTest {

    private class FakeClock(var nowMillis: Long = 1_000L) {
        operator fun invoke(): Long = nowMillis
    }

    @Test
    fun admitsRequestsUpToTheWindowBudget() {
        val clock = FakeClock()
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 2,
            windowMillis = 1_000L,
            maxConcurrent = 4,
            cooldownMillis = 0L,
            clock = clock::invoke
        )

        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.Admitted)
        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.Admitted)
        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.RateLimited)
    }

    @Test
    fun rateLimitReportsAPositiveRetryDelay() {
        val clock = FakeClock()
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 1,
            windowMillis = 1_000L,
            maxConcurrent = 4,
            cooldownMillis = 0L,
            clock = clock::invoke
        )
        throttle.tryAcquire()

        val limited = throttle.tryAcquire() as DshCapabilityThrottleDecision.RateLimited

        assertEquals(1_000L, limited.retryAfterMillis)
    }

    @Test
    fun windowSlidesAfterTheConfiguredTime() {
        val clock = FakeClock()
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 1,
            windowMillis = 1_000L,
            maxConcurrent = 4,
            cooldownMillis = 0L,
            clock = clock::invoke
        )
        throttle.tryAcquire()
        clock.nowMillis += 1_001L

        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.Admitted)
    }

    @Test
    fun refusesBeyondTheConcurrencyCeiling() {
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 10,
            windowMillis = 1_000L,
            maxConcurrent = 1,
            cooldownMillis = 0L,
            clock = FakeClock()::invoke
        )
        throttle.tryAcquire()

        assertEquals(
            DshCapabilityThrottleDecision.ConcurrencyLimited,
            throttle.tryAcquire()
        )
    }

    @Test
    fun releasingALeaseFreesAConcurrencySlot() {
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 10,
            windowMillis = 1_000L,
            maxConcurrent = 1,
            cooldownMillis = 0L,
            clock = FakeClock()::invoke
        )
        val first = throttle.tryAcquire() as DshCapabilityThrottleDecision.Admitted

        assertTrue(throttle.release(first.leaseId))
        assertEquals(0, throttle.activeLeaseCount())
        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.Admitted)
    }

    @Test
    fun releasingAnUnknownLeaseIsHarmless() {
        val throttle = DshCapabilityThrottle(clock = FakeClock()::invoke)

        assertFalse(throttle.release(9_999L))
        assertEquals(0, throttle.activeLeaseCount())
    }

    @Test
    fun cooldownBlocksEvenAfterTheWindowDrains() {
        val clock = FakeClock()
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 1,
            windowMillis = 10L,
            maxConcurrent = 4,
            cooldownMillis = 500L,
            clock = clock::invoke
        )
        throttle.tryAcquire()
        clock.nowMillis += 5L
        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.RateLimited)

        clock.nowMillis += 100L
        val stillCooling = throttle.tryAcquire() as DshCapabilityThrottleDecision.RateLimited
        assertTrue(stillCooling.retryAfterMillis > 0L)

        clock.nowMillis += 500L
        assertTrue(throttle.tryAcquire() is DshCapabilityThrottleDecision.Admitted)
    }

    @Test
    fun projectionsReflectTheLiveWindow() {
        val clock = FakeClock()
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 5,
            windowMillis = 1_000L,
            maxConcurrent = 5,
            cooldownMillis = 0L,
            clock = clock::invoke
        )
        throttle.tryAcquire()
        throttle.tryAcquire()

        assertEquals(2, throttle.windowRequestCount())
        assertEquals(2, throttle.activeLeaseCount())

        clock.nowMillis += 1_500L
        assertEquals(0, throttle.windowRequestCount())
    }
}
