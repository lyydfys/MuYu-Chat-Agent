package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageGenerationCoordinatorTest {
    @Test
    fun uiAndApiRequestsShareOneLease() {
        val coordinator = LocalImageGenerationCoordinator()
        val ui = requireNotNull(coordinator.tryAcquire("ui-img-1"))

        assertEquals("ui-img-1", coordinator.activeRequestId())
        assertNull(coordinator.tryAcquire("api-img-1"))
        assertTrue(coordinator.release(ui))

        val api = requireNotNull(coordinator.tryAcquire("api-img-1"))
        assertEquals("api-img-1", coordinator.activeRequestId())
        assertTrue(coordinator.release(api))
        assertNull(coordinator.activeRequestId())
    }

    @Test
    fun staleLeaseCannotReleaseNewRequest() {
        val coordinator = LocalImageGenerationCoordinator()
        val first = requireNotNull(coordinator.tryAcquire("first"))
        assertTrue(coordinator.release(first))
        val second = requireNotNull(coordinator.tryAcquire("second"))

        assertFalse(coordinator.release(first))
        assertEquals("second", coordinator.activeRequestId())
        assertTrue(coordinator.release(second))
    }
}
