package com.muyuchat.core.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeCancellationGateTest {
    @Test fun blockedCancelDoesNotBlockRequestOrAllowClose() {
        val gate = NativeCancellationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            gate.request { { calls.incrementAndGet(); entered.countDown(); release.await() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.request { { calls.incrementAndGet() } }
            assertFalse(gate.await(20))
            var closed = false
            assertFalse(gate.whenIdle { closed = true })
            assertFalse(closed)
            assertTrue(gate.isPending())
            assertEquals(1, calls.get())
        } finally { release.countDown() }
        assertTrue(gate.await(2000))
        assertTrue(gate.whenIdle {})
    }

    @Test fun throwingCancelReleasesOwnershipAndIdleRequestsAreNoOps() {
        val gate = NativeCancellationGate()
        gate.request { null }
        assertFalse(gate.isPending())
        gate.request { { throw IllegalStateException("SDK cancel failed") } }
        assertTrue(gate.await(2000))
        assertTrue(gate.whenIdle {})
    }

    @Test fun blockedCloseDoesNotHoldCancellationOrWatchdogMonitor() {
        val gate = NativeCancellationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closer = Thread { gate.whenIdle { entered.countDown(); release.await() } }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val returned = CountDownLatch(1)
            var cancelledClosedHandle = false
            Thread { gate.request { { cancelledClosedHandle = true } }; returned.countDown() }.start()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertTrue(gate.isPending())
            assertFalse(gate.await(20))
            assertFalse(cancelledClosedHandle)
        } finally { release.countDown(); closer.join(2000) }
        assertFalse(gate.isPending())
    }
}
