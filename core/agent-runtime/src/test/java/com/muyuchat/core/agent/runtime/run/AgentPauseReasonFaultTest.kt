package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.FixedRunSpecProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K11/T typed pause reason: FGS-unavailable must persist as FOREGROUND_SERVICE_UNAVAILABLE through
 * PAUSING -> PAUSED instead of always degrading to USER_REQUESTED, while the source-compatible
 * default stays USER_REQUESTED and command idempotency includes the reason.
 *
 * Test-only; no app/platform/build edits.
 */
class AgentPauseReasonFaultTest {

    @Test
    fun foregroundServiceUnavailableReasonPersistsFromPausingToPaused() = runTest {
        val harness = pauseHarness()
        val runId = harness.startRun()
        val running = harness.journal.readRun(runId)!!.run
        assertEquals(AgentRunState.RUNNING, running.state)

        val pause = harness.owner.requestPause(
            PauseAgentRun(
                commandId = harness.ids.nextId("cmd"),
                runId = runId,
                expectedRunRevision = running.revision,
                reason = AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
            )
        )
        assertTrue(pause is AgentCommandResult.Accepted)
        runCurrent()
        val pausing = harness.journal.readRun(runId)!!.run
        assertEquals(AgentRunState.PAUSING, pausing.state)
        assertEquals(AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE, pausing.reason)
        assertNotEquals(AgentPauseReason.USER_REQUESTED, pausing.reason)

        harness.executor.release.complete(Unit)
        runCurrent()
        val paused = harness.journal.readRun(runId)!!.run
        assertEquals(AgentRunState.PAUSED, paused.state)
        assertEquals(AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE, paused.reason)
        assertEquals(
            com.muyuchat.core.agent.contract.AgentDisplayStatus.PAUSED,
            harness.journal.readRun(runId)!!.task.displayStatus
        )
    }

    @Test
    fun defaultPauseStillSettlesAsUserRequested() = runTest {
        val harness = pauseHarness()
        val runId = harness.startRun()
        val running = harness.journal.readRun(runId)!!.run
        val pause = harness.owner.requestPause(
            PauseAgentRun(
                commandId = harness.ids.nextId("cmd"),
                runId = runId,
                expectedRunRevision = running.revision
            )
        )
        assertTrue(pause is AgentCommandResult.Accepted)
        harness.executor.release.complete(Unit)
        runCurrent()
        val paused = harness.journal.readRun(runId)!!.run
        assertEquals(AgentRunState.PAUSED, paused.state)
        assertEquals(AgentPauseReason.USER_REQUESTED, paused.reason)
    }

    @Test
    fun sameCommandReplaysSameReasonAndConflictsOnDifferentReason() = runTest {
        val harness = pauseHarness()
        val runId = harness.startRun()
        val running = harness.journal.readRun(runId)!!.run
        val command = PauseAgentRun(
            commandId = "cmd-pause-typed-replay",
            runId = runId,
            expectedRunRevision = running.revision,
            reason = AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
        )
        val first = harness.owner.requestPause(command)
        assertTrue(first is AgentCommandResult.Accepted)
        assertTrue(!(first as AgentCommandResult.Accepted).idempotentReplay)
        runCurrent()
        assertEquals(AgentRunState.PAUSING, harness.journal.readRun(runId)!!.run.state)

        val replay = harness.owner.requestPause(command)
        assertTrue(replay is AgentCommandResult.Accepted)
        assertTrue((replay as AgentCommandResult.Accepted).idempotentReplay)

        val differentReason = harness.owner.requestPause(
            command.copy(reason = AgentPauseReason.USER_REQUESTED)
        )
        assertTrue(differentReason is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
            (differentReason as AgentCommandResult.Rejected).error.code
        )

        harness.executor.release.complete(Unit)
        runCurrent()
        assertEquals(
            AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE,
            harness.journal.readRun(runId)!!.run.reason
        )
    }

    @Test
    fun nonPausableReasonIsRejectedBeforeAnyJournalCommit() = runTest {
        val harness = pauseHarness()
        val runId = harness.startRun()
        val before = harness.journal.readRun(runId)!!.run
        var threw = false
        try {
            PauseAgentRun(
                commandId = harness.ids.nextId("cmd"),
                runId = runId,
                expectedRunRevision = before.revision,
                reason = AgentPauseReason.APPROVAL_EXPIRED
            )
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("invalid pause reason must be rejected at command construction", threw)
        val after = harness.journal.readRun(runId)!!.run
        assertEquals(before.state, after.state)
        assertEquals(before.revision, after.revision)
        assertEquals(before.reason, after.reason)

        harness.executor.release.complete(Unit)
        runCurrent()
    }

    private suspend fun TestScope.pauseHarness(): PauseHarness {
        val journal = InMemoryAgentJournal()
        val clock = ManualAgentMonotonicClock()
        val ids = SequentialAgentIdGenerator("pause-reason")
        val executor = GatedExecutor()
        val owner = AgentRunOwner(
            journal = journal,
            ledger = journal,
            taskReader = journal,
            questionReader = journal,
            approvalReader = journal,
            specProvider = FixedRunSpecProvider(),
            executor = executor,
            processor = AgentCommandProcessor(backgroundScope),
            clock = clock,
            ids = ids,
            scope = backgroundScope,
            attemptRegistry = AgentActiveAttemptRegistry()
        )
        val create = owner.createTask(
            CreateAgentTask(
                commandId = ids.nextId("cmd"),
                goal = "pause reason fixture",
                displayTitle = "pause reason fixture",
                modelBinding = AgentRuntimeFixtures.binding(),
                policy = AgentRunPolicy()
            )
        )
        assertTrue(create is AgentCommandResult.Accepted)
        val task = journal.allTasks().single()
        val started = owner.startRun(
            StartAgentRun(ids.nextId("cmd"), task.taskId, task.revision)
        ) as AgentCommandResult.Accepted
        runCurrent()
        return PauseHarness(owner, journal, executor, ids, started.runId!!)
    }

    private class PauseHarness(
        val owner: AgentRunOwner,
        val journal: InMemoryAgentJournal,
        val executor: GatedExecutor,
        val ids: SequentialAgentIdGenerator,
        private val runId: String
    ) {
        suspend fun startRun(): String {
            val run = journal.readRun(runId)!!.run
            assertEquals(AgentRunState.RUNNING, run.state)
            return runId
        }
    }

    private class GatedExecutor : AgentRunExecutor {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executeCount: Int = 0

        override suspend fun execute(
            run: AgentRun,
            task: AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            executeCount += 1
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            return AgentRuntimeFixtures.completed(run)
        }
    }

}
