package com.muyuchat.core.engine.contract

import com.muyuchat.core.engine.contract.error.StableErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RequestStateMachineTest {

    private val baseIdentity = RequestIdentity(
        requestId = "test-req-001",
        attemptId = 0L,
        sessionGeneration = 1L,
        runtimeEpoch = 1L,
        workerIncarnation = 1L,
        nativeSequence = 0L,
        traceId = "trace-001"
    )

    @Test
    fun `initial state is Created`() {
        val sm = RequestStateMachine(baseIdentity)
        assertEquals(RequestState.Created, sm.currentState)
        assertFalse(sm.isTerminal)
    }

    @Test
    fun `standard lifecycle progression to Succeeded`() {
        val sm = RequestStateMachine(baseIdentity)
        assertTrue(sm.transitionTo(RequestState.Accepted))
        assertTrue(sm.transitionTo(RequestState.Preparing))
        assertTrue(sm.transitionTo(RequestState.Running))
        assertTrue(sm.transitionTo(RequestState.Succeeded))

        assertEquals(RequestState.Succeeded, sm.currentState)
        assertTrue(sm.isTerminal)

        // Cannot transition from terminal state
        assertFalse(sm.transitionTo(RequestState.Running))
        assertFalse(sm.transitionTo(RequestState.Failed))
    }

    @Test
    fun `invariant 1 - terminal exactly once rejects duplicate terminal callbacks`() {
        val sm = RequestStateMachine(baseIdentity)
        sm.transitionTo(RequestState.Accepted)
        sm.transitionTo(RequestState.Preparing)
        sm.transitionTo(RequestState.Running)

        // Process first terminal event
        val terminalEvent = CallbackEvent.terminal(
            identity = baseIdentity,
            sequence = 10L,
            finalState = RequestState.Succeeded,
            totalTokens = 128L
        )

        val result1 = sm.processEvent(terminalEvent, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
        assertTrue(result1 is EventProcessResult.Accepted)
        assertEquals(RequestState.Succeeded, sm.currentState)
        assertEquals(0L, sm.duplicateTerminalCount)

        // Process duplicate terminal event arriving late
        val lateDuplicate = CallbackEvent.terminal(
            identity = baseIdentity,
            sequence = 11L,
            finalState = RequestState.Failed
        )

        val result2 = sm.processEvent(lateDuplicate, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
        assertTrue(result2 is EventProcessResult.DuplicateTerminalIgnored)
        // State remains Succeeded
        assertEquals(RequestState.Succeeded, sm.currentState)
        assertEquals(1L, sm.duplicateTerminalCount)
    }

    @Test
    fun `invariant 2 - stale callback reject rejects older epoch generation and sequence`() {
        val sm = RequestStateMachine(baseIdentity)
        sm.transitionTo(RequestState.Accepted)
        sm.transitionTo(RequestState.Preparing)
        sm.transitionTo(RequestState.Running)

        // 1. Stale epoch event
        val staleEpochIdentity = baseIdentity.copy(runtimeEpoch = 0L)
        val staleEpochEvent = CallbackEvent.token(staleEpochIdentity, sequence = 1L, text = "hi", tokenIndex = 0L)
        val resultEpoch = sm.processEvent(staleEpochEvent, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
        assertTrue(resultEpoch is EventProcessResult.StaleRejected)

        // 2. Stale generation event
        val staleGenIdentity = baseIdentity.copy(sessionGeneration = 0L)
        val staleGenEvent = CallbackEvent.token(staleGenIdentity, sequence = 1L, text = "hi", tokenIndex = 0L)
        val resultGen = sm.processEvent(staleGenEvent, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
        assertTrue(resultGen is EventProcessResult.StaleRejected)

        // 3. Stale non-monotonic sequence
        val eventSeq5 = CallbackEvent.token(baseIdentity, sequence = 5L, text = "tok5", tokenIndex = 0L)
        val accepted5 = sm.processEvent(eventSeq5, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
        assertTrue(accepted5 is EventProcessResult.Accepted)

        // Out-of-order sequence <= 5
        val eventSeq4 = CallbackEvent.token(baseIdentity, sequence = 4L, text = "tok4", tokenIndex = 1L)
        val rejected4 = sm.processEvent(eventSeq4, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
        assertTrue(rejected4 is EventProcessResult.StaleRejected)
    }

    @Test
    fun `invariant 3 - cancel drain requires draining before terminal Cancelled`() {
        val sm = RequestStateMachine(baseIdentity)
        sm.transitionTo(RequestState.Accepted)
        sm.transitionTo(RequestState.Preparing)
        sm.transitionTo(RequestState.Running)

        // Request cancel
        assertTrue(sm.requestCancel())
        assertEquals(RequestState.CancelRequested, sm.currentState)
        assertFalse(sm.isTerminal) // CancelRequested is NOT terminal yet

        // Begin draining in-flight callbacks
        assertTrue(sm.beginDraining())
        assertEquals(RequestState.DrainingCallbacks, sm.currentState)
        assertFalse(sm.isTerminal)

        // Complete cancel
        assertTrue(sm.completeCancel())
        assertEquals(RequestState.Cancelled, sm.currentState)
        assertTrue(sm.isTerminal)
    }

    @Test
    fun `invariant 4 - timeout abort terminates safely`() {
        val sm = RequestStateMachine(baseIdentity)
        sm.transitionTo(RequestState.Accepted)
        sm.transitionTo(RequestState.Preparing)
        sm.transitionTo(RequestState.Running)

        assertTrue(sm.timeout())
        assertEquals(RequestState.TimedOut, sm.currentState)
        assertTrue(sm.isTerminal)

        // Abort on another SM
        val sm2 = RequestStateMachine(baseIdentity)
        sm2.transitionTo(RequestState.Accepted)
        assertTrue(sm2.abort())
        assertEquals(RequestState.Aborted, sm2.currentState)
        assertTrue(sm2.isTerminal)
    }

    @Test
    fun `concurrent cancel and terminal race yields exactly one terminal state`() {
        val iterations = 50
        val winnersCount = AtomicInteger(0)

        for (i in 0 until iterations) {
            val sm = RequestStateMachine(baseIdentity.copy(requestId = "race-$i"))
            sm.transitionTo(RequestState.Accepted)
            sm.transitionTo(RequestState.Running)

            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(2)
            var cancelledWin = false
            var succeededWin = false

            val t1 = Thread {
                startLatch.await()
                if (sm.requestCancel() && sm.beginDraining() && sm.completeCancel()) {
                    cancelledWin = true
                }
                doneLatch.countDown()
            }

            val t2 = Thread {
                startLatch.await()
                val terminalEvt = CallbackEvent.terminal(
                    identity = baseIdentity,
                    sequence = 100L,
                    finalState = RequestState.Succeeded
                )
                val res = sm.processEvent(terminalEvt, activeEpoch = 1L, activeGeneration = 1L, activeWorker = 1L)
                if (res is EventProcessResult.Accepted) {
                    succeededWin = true
                }
                doneLatch.countDown()
            }

            t1.start()
            t2.start()
            startLatch.countDown()
            assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
            t1.join()
            t2.join()

            assertTrue(sm.isTerminal)
            if (cancelledWin xor succeededWin) {
                winnersCount.incrementAndGet()
            }
        }

        assertEquals(iterations, winnersCount.get())
    }
}
