package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C01: STOPPING must survive until a typed resource settlement says the exact run is quiet.
 *
 * These are owner-level deterministic tests: they do not touch a native engine. They prove the
 * propagation contract (STOPPING retention, late proof, timeout, repeated stop, cross-run
 * isolation) that the app-scope settlement source must honor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunStopSettlementTest {

    @Test
    fun stoppingIsHeldUntilAReleasedReceiptArrives() = runTest {
        val executor = com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor()
            .apply { suspendForever = true }
        val source = RecordingSettlementSource { runId, epoch ->
            receipt(runId, epoch, AgentRunStopSettlement.StoppingUnknown("native worker not quiet"))
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = requireNotNull(harness.run(runId))
        assertEquals(AgentRunState.RUNNING, running.state)

        val cancel = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
        )
        assertTrue(cancel is AgentCommandResult.Accepted)
        runCurrent()

        assertEquals(AgentRunState.STOPPING, harness.run(runId)!!.state)
        assertFalse(harness.run(runId)!!.state.isTerminal)
        assertEquals(
            "the persisted task projection must also stay in STOPPING",
            com.muyuchat.core.agent.contract.AgentDisplayStatus.STOPPING,
            harness.journal.readRun(runId)!!.task.displayStatus
        )
        assertEquals("the active slot must stay reserved by the stopping run", runId, harness.owner.activeRunId())
        assertEquals(1, source.calls)
        assertEquals(1, executor.cancellationCount)

        val late = harness.owner.reportStopSettlement(
            receipt(runId, running.epoch, AgentRunStopSettlement.Released("native idle proven later"))
        )
        assertTrue(late is AgentCommandResult.Accepted)
        runCurrent()

        val cancelled = harness.run(runId)!!
        assertEquals(AgentRunState.CANCELLED, cancelled.state)
        assertEquals(AgentPauseReason.NONE, cancelled.reason)
        assertNull(harness.owner.activeRunId())

        val revisionBeforeReplay = cancelled.revision
        val replay = harness.owner.reportStopSettlement(
            receipt(runId, running.epoch, AgentRunStopSettlement.Released("duplicate late proof"))
        )
        runCurrent()
        assertTrue(replay is AgentCommandResult.Accepted)
        assertTrue((replay as AgentCommandResult.Accepted).idempotentReplay)
        assertEquals("the terminal write must happen exactly once", revisionBeforeReplay, harness.run(runId)!!.revision)
    }

    @Test
    fun releasedFirstSettlementCancelsExactlyOnce() = runTest {
        val executor = com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor()
            .apply { suspendForever = true }
        val source = RecordingSettlementSource { runId, epoch ->
            receipt(runId, epoch, AgentRunStopSettlement.Released("quiescence proven"))
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = requireNotNull(harness.run(runId))

        harness.owner.requestCancel(CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision))
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, harness.run(runId)!!.state)
        assertEquals(1, source.calls)
        assertEquals(1, executor.cancellationCount)
        assertNull(harness.owner.activeRunId())

        val revision = harness.run(runId)!!.revision
        harness.owner.reportStopSettlement(
            receipt(runId, running.epoch, AgentRunStopSettlement.Released("again"))
        )
        runCurrent()
        assertEquals(revision, harness.run(runId)!!.revision)
    }

    @Test
    fun neverQuiescingExecutorTimesOutIntoStoppingAndAcceptsALateProof() = runTest {
        val executor = StubbornExecutor()
        val source = RecordingSettlementSource { runId, epoch ->
            receipt(runId, epoch, AgentRunStopSettlement.Released("should not be asked yet"))
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source,
            stopJoinTimeoutMillis = 100L
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = requireNotNull(harness.run(runId))
        assertTrue(executor.started.isCompleted)

        harness.owner.requestCancel(CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision))
        advanceTimeBy(101L)
        runCurrent()

        assertEquals(AgentRunState.STOPPING, harness.run(runId)!!.state)
        assertEquals(runId, harness.owner.activeRunId())
        assertEquals("a timed-out join must not query/re-use the release path", 0, source.calls)

        executor.release.complete(Unit)
        runCurrent()
        assertEquals(AgentRunState.STOPPING, harness.run(runId)!!.state)

        harness.owner.reportStopSettlement(
            receipt(runId, running.epoch, AgentRunStopSettlement.Released("native idle after late exit"))
        )
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, harness.run(runId)!!.state)
        assertNull(harness.owner.activeRunId())
    }

    @Test
    fun repeatedCancelStartsOneSettlementAndNoPrematureTerminal() = runTest {
        val executor = com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor()
            .apply { suspendForever = true }
        val source = RecordingSettlementSource { runId, epoch ->
            receipt(runId, epoch, AgentRunStopSettlement.StoppingUnknown("still active"))
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = requireNotNull(harness.run(runId))

        val first = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
        )
        assertTrue(first is AgentCommandResult.Accepted)
        runCurrent()
        val second = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, harness.run(runId)!!.revision)
        )
        assertTrue(second is AgentCommandResult.Accepted)
        runCurrent()

        assertEquals(1, source.calls)
        assertEquals(AgentRunState.STOPPING, harness.run(runId)!!.state)
        assertEquals(runId, harness.owner.activeRunId())
        assertEquals(1, executor.cancellationCount)

        harness.owner.reportStopSettlement(
            receipt(runId, running.epoch, AgentRunStopSettlement.Released("released once"))
        )
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, harness.run(runId)!!.state)
    }

    @Test
    fun sourceFailureKeepsStoppingInsteadOfSilentSuccess() = runTest {
        val executor = com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor()
            .apply { suspendForever = true }
        val source = RecordingSettlementSource { runId, epoch ->
            receipt(runId, epoch, AgentRunStopSettlement.Failed("settlement probe crashed"))
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = requireNotNull(harness.run(runId))

        harness.owner.requestCancel(CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision))
        runCurrent()

        assertEquals(AgentRunState.STOPPING, harness.run(runId)!!.state)
        assertEquals(runId, harness.owner.activeRunId())
    }

    @Test
    fun foreignReceiptIdentityCannotSettleTheRun() = runTest {
        val executor = com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor()
            .apply { suspendForever = true }
        val source = RecordingSettlementSource { _, epoch ->
            AgentStopSettlementReceipt(
                runId = "run-foreign",
                runEpoch = epoch,
                settlement = AgentRunStopSettlement.Released("foreign proof")
            )
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = requireNotNull(harness.run(runId))

        harness.owner.requestCancel(CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision))
        runCurrent()

        assertEquals(AgentRunState.STOPPING, harness.run(runId)!!.state)
        assertEquals(runId, harness.owner.activeRunId())
    }

    @Test
    fun lateSettlementForAnOldRunCannotTouchTheReplacementRun() = runTest {
        val executor = com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor()
            .apply { suspendForever = true }
        val source = RecordingSettlementSource { runId, epoch ->
            receipt(runId, epoch, AgentRunStopSettlement.StoppingUnknown("not proven"))
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            stopSettlementSource = source
        )
        val firstTask = harness.createTask(goal = "first")
        val firstStart = harness.start(firstTask) as AgentCommandResult.Accepted
        runCurrent()
        val firstRunId = firstStart.runId!!
        val firstRun = requireNotNull(harness.run(firstRunId))

        harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), firstRunId, firstRun.revision)
        )
        runCurrent()
        assertEquals(AgentRunState.STOPPING, harness.run(firstRunId)!!.state)

        // Wrong epoch for the same run is a stale identity and must be rejected, not applied.
        val staleEpoch = harness.owner.reportStopSettlement(
            receipt(firstRunId, firstRun.epoch + 1L, AgentRunStopSettlement.Released("wrong epoch"))
        )
        assertTrue(staleEpoch is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            (staleEpoch as AgentCommandResult.Rejected).error.code
        )
        assertEquals(AgentRunState.STOPPING, harness.run(firstRunId)!!.state)

        harness.owner.reportStopSettlement(
            receipt(firstRunId, firstRun.epoch, AgentRunStopSettlement.Released("real proof"))
        )
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, harness.run(firstRunId)!!.state)
        assertNull(harness.owner.activeRunId())

        // A successor run is a different identity. Its own state must not be touched by an old
        // receipt, and a receipt for a RUNNING run is not a stop settlement.
        val secondTask = harness.createTask(goal = "second")
        val secondStart = harness.start(secondTask) as AgentCommandResult.Accepted
        runCurrent()
        val secondRunId = secondStart.runId!!
        val secondRun = requireNotNull(harness.run(secondRunId))
        assertEquals(AgentRunState.RUNNING, secondRun.state)

        val oldReplay = harness.owner.reportStopSettlement(
            receipt(firstRunId, firstRun.epoch, AgentRunStopSettlement.Released("old proof"))
        )
        assertTrue(oldReplay is AgentCommandResult.Accepted)
        assertTrue((oldReplay as AgentCommandResult.Accepted).idempotentReplay)
        assertEquals(AgentRunState.RUNNING, harness.run(secondRunId)!!.state)

        val wrongTarget = harness.owner.reportStopSettlement(
            receipt(secondRunId, secondRun.epoch, AgentRunStopSettlement.Released("not stopped"))
        )
        assertTrue(wrongTarget is AgentCommandResult.Rejected)
        assertEquals(AgentRunState.RUNNING, harness.run(secondRunId)!!.state)
        assertEquals(secondRunId, harness.owner.activeRunId())
    }

    private class RecordingSettlementSource(
        private val respond: suspend (String, Long) -> AgentStopSettlementReceipt
    ) : AgentRunStopSettlementSource {
        var calls: Int = 0
            private set

        override suspend fun settlementFor(runId: String, runEpoch: Long): AgentStopSettlementReceipt {
            calls += 1
            return respond(runId, runEpoch)
        }
    }

    private class StubbornExecutor : com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(
            run: AgentRun,
            task: AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            started.complete(Unit)
            // Deliberately ignore cancellation; only the test can supply the real stop proof.
            withContext(NonCancellable) { release.await() }
            return AgentRuntimeFixtures.completed(run)
        }
    }

    private fun receipt(
        runId: String,
        epoch: Long,
        settlement: AgentRunStopSettlement
    ): AgentStopSettlementReceipt = AgentStopSettlementReceipt(
        runId = runId,
        runEpoch = epoch,
        settlement = settlement
    )
}
