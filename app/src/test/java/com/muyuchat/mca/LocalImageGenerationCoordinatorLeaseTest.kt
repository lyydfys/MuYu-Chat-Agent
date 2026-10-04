package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LocalImageGenerationCoordinator] -- the lease that serialises product image
 * requests across the UI and the authenticated Local API.
 *
 * The rules pinned here are the ones a release path depends on:
 *
 *  - at most one live lease, so two requests can never both believe they own the worker;
 *  - release is bound to **lease identity**, so a stale or copied lease cannot free a newer
 *    request's lease (the epoch is why: `release(aLaterLease)` must not matter);
 *  - the pairing is exactly once: acquire -> release succeeds once, a second release is a no-op.
 *
 * All pure JVM, no Android, no coroutines.
 */
class LocalImageGenerationCoordinatorLeaseTest {

    @Test
    fun `a second acquire is refused while a lease is live`() {
        val coordinator = LocalImageGenerationCoordinator()

        val first = coordinator.tryAcquire("req-a")

        assertNotNull(first)
        assertNull("only one image request may own the worker", coordinator.tryAcquire("req-b"))
        assertEquals("req-a", coordinator.activeRequestId())
    }

    @Test
    fun `release frees the lease exactly once`() {
        val coordinator = LocalImageGenerationCoordinator()
        val lease = requireNotNull(coordinator.tryAcquire("req-a"))

        assertTrue("the owner releases its own lease", coordinator.release(lease))
        assertNull(coordinator.activeRequestId())
        assertFalse("a repeated release must not report a second release",
            coordinator.release(lease))
    }

    @Test
    fun `release is bound to lease identity not to value equality`() {
        val coordinator = LocalImageGenerationCoordinator()
        val lease = requireNotNull(coordinator.tryAcquire("req-a"))

        val copy = lease.copy()
        assertEquals("precondition: the copy is value-equal", lease, copy)
        assertFalse("a value-equal copy must not free the real lease",
            coordinator.release(copy))
        assertNotNull("the real lease is still held", coordinator.activeRequestId())
        assertTrue(coordinator.release(lease))
    }

    @Test
    fun `a stale lease cannot release the request that replaced it`() {
        val coordinator = LocalImageGenerationCoordinator()
        val first = requireNotNull(coordinator.tryAcquire("req-a"))
        assertTrue(coordinator.release(first))

        val second = requireNotNull(coordinator.tryAcquire("req-b"))

        assertFalse("the retired lease must not free the new one",
            coordinator.release(first))
        assertEquals("req-b", coordinator.activeRequestId())
        assertTrue(coordinator.release(second))
    }

    @Test
    fun `each acquire advances the lease epoch`() {
        val coordinator = LocalImageGenerationCoordinator()
        val first = requireNotNull(coordinator.tryAcquire("req-a"))
        coordinator.release(first)
        val second = requireNotNull(coordinator.tryAcquire("req-a"))

        assertTrue("a re-acquired lease is a new epoch, so its epoch must differ",
            second.epoch > first.epoch)
        assertFalse(coordinator.release(first))
    }

    @Test
    fun `releasing after a full acquire release cycle leaves the coordinator idle`() {
        val coordinator = LocalImageGenerationCoordinator()
        repeat(3) { index ->
            val lease = requireNotNull(coordinator.tryAcquire("req-$index"))
            assertEquals("req-$index", coordinator.activeRequestId())
            assertTrue(coordinator.release(lease))
            assertNull(coordinator.activeRequestId())
        }
    }

    @Test
    fun `an idle coordinator reports no active request`() {
        assertNull(LocalImageGenerationCoordinator().activeRequestId())
    }

    @Test
    fun `a blank request id is rejected before a lease is granted`() {
        val coordinator = LocalImageGenerationCoordinator()

        val failure = runCatching { coordinator.tryAcquire("   ") }

        assertTrue("blank ids must not be able to own the worker",
            failure.exceptionOrNull() is IllegalArgumentException)
        assertNull(coordinator.activeRequestId())
    }

    @Test
    fun `independent coordinators do not share the lease`() {
        val ui = LocalImageGenerationCoordinator()
        val localApi = LocalImageGenerationCoordinator()

        assertNotNull(ui.tryAcquire("ui-1"))
        assertNotNull("separate coordinators are independent by construction",
            localApi.tryAcquire("api-1"))
        assertEquals("ui-1", ui.activeRequestId())
        assertEquals("api-1", localApi.activeRequestId())
    }
}
