package com.muyuchat.api.dsh.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DshGatewayCallsTest {

    @Test
    fun callIdsUseTheRuntimeEpochAndMonotonicOrdinal() = runBlocking {
        val registry = DshGatewayCallRegistry(7L, 2) { }
        val first = requireNotNull(registry.admit())
        val second = requireNotNull(registry.admit())
        assertNull(registry.admit())
        assertEquals("dsh:7:1", first.callId)
        assertEquals("dsh:7:2", second.callId)
        registry.release(first)
        assertEquals("dsh:7:3", requireNotNull(registry.admit()).callId)
        assertEquals(2, registry.activeCount())
    }

    @Test
    fun cancellationIsPerCallAndObservedOnce() = runBlocking {
        val cancelled = CopyOnWriteArrayList<String>()
        val registry = DshGatewayCallRegistry(1L, 4) { callId -> cancelled += callId }
        val first = requireNotNull(registry.admit())
        val second = requireNotNull(registry.admit())

        assertTrue(registry.cancel(first.callId, DshCallCancelReason.EXPLICIT_REQUEST))
        // A repeated cancel of a still-live call is acknowledged idempotently: the first transition
        // may have come from a peer disconnect and the explicit route must not answer 404 while the
        // backend collector is still unwinding. The backend hook still fires exactly once.
        assertTrue(registry.cancel(first.callId, DshCallCancelReason.SERVER_STOP))
        assertTrue(first.cancellationSignal.isCompleted)
        assertEquals(DshCallCancelReason.EXPLICIT_REQUEST, first.cancelReason)
        assertFalse(second.cancellationSignal.isCompleted)
        assertEquals(listOf(first.callId), cancelled)

        assertFalse(registry.cancel("dsh:1:999", DshCallCancelReason.EXPLICIT_REQUEST))
        assertEquals(listOf(first.callId), cancelled)
    }

    @Test
    fun cancelAllCancelsEachActiveCallIndividually() = runBlocking {
        val cancelled = CopyOnWriteArrayList<String>()
        val registry = DshGatewayCallRegistry(2L, 4) { callId -> cancelled += callId }
        val first = requireNotNull(registry.admit())
        val second = requireNotNull(registry.admit())
        registry.cancelAll(DshCallCancelReason.SERVER_STOP)
        assertEquals(setOf(first.callId, second.callId), cancelled.toSet())
        assertEquals(DshCallCancelReason.SERVER_STOP, first.cancelReason)
        assertEquals(DshCallCancelReason.SERVER_STOP, second.cancelReason)
        registry.cancelAll(DshCallCancelReason.SERVER_STOP)
        assertEquals(2, cancelled.size)
    }

    @Test
    fun aThrowingCancelHookDoesNotEscape() = runBlocking {
        val registry = DshGatewayCallRegistry(3L, 1) { error("native cancel failed") }
        val call = requireNotNull(registry.admit())
        assertTrue(registry.cancel(call.callId, DshCallCancelReason.EXPLICIT_REQUEST))
        assertEquals(DshCallCancelReason.EXPLICIT_REQUEST, call.cancelReason)
        registry.release(call)
        assertFalse(registry.cancel(call.callId, DshCallCancelReason.EXPLICIT_REQUEST))
    }

    @Test
    fun aRepeatedCancelOfALiveCallIsAcknowledgedAndDispatchesOnce() = runBlocking {
        val cancelled = CopyOnWriteArrayList<String>()
        val registry = DshGatewayCallRegistry(5L, 2) { callId -> cancelled += callId }
        val call = requireNotNull(registry.admit())

        assertTrue(registry.cancel(call.callId, DshCallCancelReason.CLIENT_DISCONNECT))
        // The common device sequence: the host closed the SSE socket (first transition), then the
        // explicit cancel route arrives while the backend collector is still unwinding. That call
        // is live and cancelled; answering 404 "call_not_found" would be false.
        assertTrue(
            "a live call that is already cancelling must be acknowledged idempotently",
            registry.cancel(call.callId, DshCallCancelReason.EXPLICIT_REQUEST)
        )
        assertEquals("the exact stop is still dispatched at most once", listOf(call.callId), cancelled)

        registry.release(call)
        assertFalse(
            "after release the same id is unknown again",
            registry.cancel(call.callId, DshCallCancelReason.EXPLICIT_REQUEST)
        )
    }
}
