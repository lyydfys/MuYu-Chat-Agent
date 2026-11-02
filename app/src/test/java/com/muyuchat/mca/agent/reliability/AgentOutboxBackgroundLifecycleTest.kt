package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxDispatcher
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchStartupReconciler
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentDispatchOutboxStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit background lifecycle for the dispatch outbox (N03/N19 skeleton).
 *
 * The loop is a single child job of the provided scope. Tests assert single-flight start, coalesced
 * wakes (no duplicate pass/effect), cooperative stop (waits for the in-flight pass) and abrupt
 * cancel (leaves a fenced row instead of pretending the dispatch succeeded).
 */
class AgentOutboxBackgroundLifecycleTest {

    private var nowMillis: Long = 1_000L

    private val store = InMemoryAgentDispatchOutboxStore()
    private val ledger = TestDispatchLedger()

    @Test
    fun startIsSingleFlightAndRepeatedWakesCoalesceIntoOneExtraPass() = runTest {
        val entry = ledger.seedApproved()
        store.enqueue(entry)
        val executor = TestDispatchExecutor(ledger)
        val loop = AgentOutboxBackgroundLoop(
            dispatcher = dispatcher(executor),
            scope = backgroundScope,
            pollIntervalMillis = 1_000L
        )

        assertEquals(AgentOutboxStartResult.Started, loop.start())
        assertEquals(AgentOutboxStartResult.AlreadyRunning, loop.start())
        runCurrent()
        assertEquals("the first pass runs immediately", 1, loop.passes)
        assertEquals(1, executor.effects.size)

        assertEquals(AgentOutboxWakeResult.Accepted, loop.wake())
        assertEquals(AgentOutboxWakeResult.Coalesced, loop.wake())
        runCurrent()

        assertEquals("a wake during the wait runs one extra pass", 2, loop.passes)
        assertEquals("the second pass must not repeat the side effect", 1, executor.effects.size)
        assertEquals(
            AgentDispatchOutboxStatus.SETTLED,
            store.read(entry.idempotencyKey)!!.status
        )
        assertEquals(AgentOutboxStopResult.Stopped, loop.stop())
        assertFalse(loop.isRunning())
    }

    @Test
    fun stopWaitsForTheInFlightPassInsteadOfAbandoningIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entry = ledger.seedApproved()
        store.enqueue(entry)
        val executor = TestDispatchExecutor(ledger, gate = gate)
        val loop = AgentOutboxBackgroundLoop(
            dispatcher = dispatcher(executor),
            scope = backgroundScope,
            pollIntervalMillis = 1_000L
        )
        loop.start()
        runCurrent()
        assertEquals(1, executor.dispatchCalls)

        val stopping = async { loop.stop() }
        runCurrent()
        assertFalse("stop must await the in-flight dispatch, not claim it stopped", stopping.isCompleted)
        assertTrue(loop.isRunning())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AgentOutboxStopResult.Stopped, stopping.await())
        assertEquals(1, executor.effects.size)
        assertEquals(ToolInvocationStatus.SUCCEEDED, ledger.statusOf(entry.invocationId))
    }

    @Test
    fun cancelAbandonsThePassAndLeavesAFenceForTheNextDispatcher() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entry = ledger.seedApproved()
        store.enqueue(entry)
        val executor = TestDispatchExecutor(ledger, gate = gate)
        val loop = AgentOutboxBackgroundLoop(
            dispatcher = dispatcher(executor),
            scope = backgroundScope,
            pollIntervalMillis = 1_000L
        )
        loop.start()
        runCurrent()
        assertEquals(
            "the dispatch intent is durable before the executor runs",
            AgentDispatchOutboxStatus.DISPATCHING,
            store.read(entry.idempotencyKey)!!.status
        )

        assertEquals(AgentOutboxCancelResult.Cancelled, loop.cancel())
        assertFalse(loop.isRunning())
        assertEquals("no effect happened before the execution gate", 0, executor.effects.size)
        assertEquals(ToolInvocationStatus.APPROVED, ledger.statusOf(entry.invocationId))
        assertEquals(AgentOutboxCancelResult.AlreadyStopped, loop.cancel())

        nowMillis += 2_000L
        val restarted = TestDispatchExecutor(ledger)
        val recovered = reconciler().reconcile()
        val report = dispatcher(restarted, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals("an abandoned dispatch is never resent", 0, report.dispatched)
        assertEquals(0, restarted.effects.size)
        assertEquals(
            AgentDispatchOutboxStatus.NEEDS_REVIEW,
            store.read(entry.idempotencyKey)!!.status
        )
    }

    @Test
    fun wakeAndStopBeforeStartAreTypedIdempotentNoOps() = runTest {
        val loop = AgentOutboxBackgroundLoop(
            dispatcher = dispatcher(TestDispatchExecutor(ledger)),
            scope = backgroundScope,
            pollIntervalMillis = 1_000L
        )

        assertEquals(AgentOutboxWakeResult.NotRunning, loop.wake())
        assertEquals(AgentOutboxStopResult.AlreadyStopped, loop.stop())
        assertEquals(AgentOutboxCancelResult.AlreadyStopped, loop.cancel())
        assertEquals(0, loop.passes)
        assertEquals(null, loop.lastReport)
    }

    private fun dispatcher(
        executor: TestDispatchExecutor,
        dispatcherId: String = "background-dispatcher"
    ): AgentDispatchOutboxDispatcher = AgentDispatchOutboxDispatcher(
        store = store,
        ledger = ledger,
        executor = executor,
        dispatcherId = dispatcherId,
        clock = { nowMillis },
        leaseMillis = 1_000L,
        approvalGraceMillis = 0L
    )

    private fun reconciler(): AgentDispatchStartupReconciler = AgentDispatchStartupReconciler(
        store = store,
        ledger = ledger,
        clock = { nowMillis },
        approvalGraceMillis = 0L
    )
}
