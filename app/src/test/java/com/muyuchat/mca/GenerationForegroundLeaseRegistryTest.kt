package com.muyuchat.mca

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationForegroundLeaseRegistryTest {
    @Test
    fun oldCompletionCannotStopReplacementOrConcurrentImageRequest() {
        val registry = GenerationForegroundLeaseRegistry()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val first = registry.acquire("chat") { starts.incrementAndGet() }
        val replacement = registry.acquire("chat") { starts.incrementAndGet() }
        val image = registry.acquire("image") { starts.incrementAndGet() }

        assertNotSame(first, replacement)
        assertTrue(registry.release(first) { stops.incrementAndGet() })
        assertEquals(0, stops.get())
        assertFalse(registry.release(first) { stops.incrementAndGet() })
        assertEquals(GenerationForegroundLeaseRegistry.Snapshot("image", 2), registry.withCurrentTask { it })
        assertTrue(registry.release(replacement) { stops.incrementAndGet() })
        assertEquals(0, stops.get())
        assertTrue(registry.release(image) { stops.incrementAndGet() })
        assertEquals(3, starts.get())
        assertEquals(1, stops.get())
        assertNull(registry.withCurrentTask { it })
    }

    @Test
    fun failedForegroundAdmissionRollsBackOnlyItsOwnLease() {
        val registry = GenerationForegroundLeaseRegistry()
        val current = registry.acquire("image") {}
        val failure = runCatching {
            registry.acquire("chat") { throw IllegalStateException("denied") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(GenerationForegroundLeaseRegistry.Snapshot("image", 1), registry.withCurrentTask { it })
        registry.release(current) {}
        assertNull(registry.withCurrentTask { it })
    }

    @Test
    fun delayedStartUsesCurrentOwnersAndCannotReviveFinishedWork() {
        val registry = GenerationForegroundLeaseRegistry()
        val old = registry.acquire("chat") {}
        registry.release(old) {}
        assertNull(registry.withCurrentTask { it })

        val image = registry.acquire("image") {}
        assertEquals(GenerationForegroundLeaseRegistry.Snapshot("image", 1), registry.withCurrentTask { it })
        registry.release(image) {}
    }

    @Test
    fun finalStopDispatchCannotOvertakeAReplacementStart() {
        val registry = GenerationForegroundLeaseRegistry()
        val old = registry.acquire("chat") {}
        val executor = Executors.newFixedThreadPool(2)
        val stopEntered = CountDownLatch(1)
        val allowStopReturn = CountDownLatch(1)
        val acquireAttempted = CountDownLatch(1)
        val startDispatched = CountDownLatch(1)
        try {
            val release = executor.submit<Boolean> {
                registry.release(old) {
                    stopEntered.countDown()
                    check(allowStopReturn.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
            val acquire = executor.submit<GenerationForegroundLease> {
                acquireAttempted.countDown()
                registry.acquire("image") { startDispatched.countDown() }
            }
            assertTrue(acquireAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(startDispatched.await(100, TimeUnit.MILLISECONDS))
            allowStopReturn.countDown()
            assertTrue(release.get(5, TimeUnit.SECONDS))
            val replacement = acquire.get(5, TimeUnit.SECONDS)
            assertEquals(0L, startDispatched.count)
            assertEquals(GenerationForegroundLeaseRegistry.Snapshot("image", 1), registry.withCurrentTask { it })
            registry.release(replacement) {}
        } finally {
            allowStopReturn.countDown()
            executor.shutdownNow()
        }
    }
}
