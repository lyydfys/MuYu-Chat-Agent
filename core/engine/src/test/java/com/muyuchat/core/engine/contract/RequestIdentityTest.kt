package com.muyuchat.core.engine.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestIdentityTest {

    @Test
    fun `valid identity creation succeeds`() {
        val identity = RequestIdentity(
            requestId = "req-101",
            attemptId = 0L,
            sessionGeneration = 1L,
            runtimeEpoch = 2L,
            workerIncarnation = 0L,
            nativeSequence = 5L,
            traceId = "trace-xyz"
        )
        assertEquals("req-101", identity.requestId)
        assertEquals(0L, identity.attemptId)
        assertEquals(1L, identity.sessionGeneration)
        assertEquals(2L, identity.runtimeEpoch)
        assertEquals(5L, identity.nativeSequence)
        assertEquals("trace-xyz", identity.traceId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank requestId is rejected`() {
        RequestIdentity(requestId = "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative attemptId is rejected`() {
        RequestIdentity(requestId = "req-1", attemptId = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative nativeSequence is rejected`() {
        RequestIdentity(requestId = "req-1", nativeSequence = -5L)
    }

    @Test
    fun `nextAttempt increments attempt and resets sequence`() {
        val initial = RequestIdentity(
            requestId = "req-retry",
            attemptId = 0L,
            nativeSequence = 100L
        )
        val second = initial.nextAttempt()
        assertEquals(1L, second.attemptId)
        assertEquals(0L, second.nativeSequence)
        assertEquals("req-retry", second.requestId)
    }

    @Test
    fun `nextSequence increments nativeSequence`() {
        val initial = RequestIdentity(requestId = "req-1", nativeSequence = 41L)
        val next = initial.nextSequence()
        assertEquals(42L, next.nativeSequence)
    }

    @Test
    fun `isStaleComparedTo correctly detects stale epoch generation and worker`() {
        val identity = RequestIdentity(
            requestId = "req-1",
            runtimeEpoch = 1L,
            sessionGeneration = 2L,
            workerIncarnation = 3L
        )

        // Matching active values: not stale
        assertFalse(identity.isStaleComparedTo(currentEpoch = 1L, currentGeneration = 2L, currentWorker = 3L))

        // Newer runtime epoch: stale
        assertTrue(identity.isStaleComparedTo(currentEpoch = 2L, currentGeneration = 2L, currentWorker = 3L))

        // Newer session generation: stale
        assertTrue(identity.isStaleComparedTo(currentEpoch = 1L, currentGeneration = 3L, currentWorker = 3L))

        // Newer worker incarnation: stale
        assertTrue(identity.isStaleComparedTo(currentEpoch = 1L, currentGeneration = 2L, currentWorker = 4L))
    }
}
