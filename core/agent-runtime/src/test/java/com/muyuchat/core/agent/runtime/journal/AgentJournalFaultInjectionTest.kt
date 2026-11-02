package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K15 journal fault-injection tests.
 *
 * The frozen [AgentJournal] contract is atomic: a commit either fully applies and is recorded for
 * commandId replay, or it applies nothing. A failure raised *before* delegation must therefore
 * leave zero partial state; a failure raised *after* a durable apply must be safe to retry and must
 * replay without duplicating state/events. True mid-SQL-transaction rollback is a K05 Room concern;
 * this class pins the interface-level expectation.
 */
class AgentJournalFaultInjectionTest {

    private enum class FaultPoint { BEFORE_DELEGATE, AFTER_DELEGATE }

    private class FaultInjectingJournal(
        private val delegate: InMemoryAgentJournal,
        private val failCommandId: String,
        private val point: FaultPoint,
        private var failures: Int = 1
    ) : AgentJournal {
        var commitAttempts: Int = 0
            private set

        override suspend fun readRun(runId: String): AgentRunSnapshot? = delegate.readRun(runId)

        override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> =
            delegate.listNonTerminalRuns(limit)

        override suspend fun readEvents(
            runId: String,
            afterSequence: Long,
            limit: Int
        ): AgentEventPage = delegate.readEvents(runId, afterSequence, limit)

        override suspend fun commit(command: AgentTransition): AgentCommitResult {
            commitAttempts += 1
            if (command.commandId == failCommandId && failures > 0) {
                failures -= 1
                if (point == FaultPoint.AFTER_DELEGATE) {
                    delegate.commit(command)
                    throw AgentJournalRejectedException(
                        INJECTED_FAULT,
                        "simulated failure after the durable apply"
                    )
                }
                throw AgentJournalRejectedException(
                    INJECTED_FAULT,
                    "simulated failure before the durable apply"
                )
            }
            return delegate.commit(command)
        }

        companion object {
            const val INJECTED_FAULT = "INJECTED_FAULT"
        }
    }

    @Test
    fun preCommitFailureLeavesNoPartialRunUpdateAndRetryAppliesExactlyOnce() = runTest {
        val delegate = InMemoryAgentJournal()
        val task = createTask(delegate)
        val run = createRun(delegate, task)
        val update = UpdateRunTransition(
            commandId = "cmd-fault-update",
            run = run.copy(state = AgentRunState.RUNNING, revision = run.revision + 1),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = run.revision
        )
        val taskBefore = delegate.readTask(task.taskId)!!
        val faulty = FaultInjectingJournal(delegate, update.commandId, FaultPoint.BEFORE_DELEGATE)

        expectFault { faulty.commit(update) }

        val afterFault = delegate.readRun(run.runId)!!
        assertEquals(run.state, afterFault.run.state)
        assertEquals(run.revision, afterFault.run.revision)
        assertEquals(taskBefore.revision, afterFault.task.revision)
        assertNull(delegate.readEvents(run.runId, 0L, 10).events.firstOrNull())

        val retried = faulty.commit(update)
        assertEquals(run.revision + 1, retried.runRevision)
        assertEquals(AgentRunState.RUNNING, delegate.readRun(run.runId)!!.run.state)
        assertEquals(run.revision + 1, delegate.readRun(run.runId)!!.run.revision)
        assertEquals(taskBefore.revision + 1, delegate.readTask(task.taskId)!!.revision)
        assertEquals(2, faulty.commitAttempts)
    }

    @Test
    fun preCommitFailureOnCreateRunLeavesNoRunOrTaskAdvance() = runTest {
        val delegate = InMemoryAgentJournal()
        val task = createTask(delegate)
        val run = AgentRuntimeFixtures.run(taskId = task.taskId, state = AgentRunState.QUEUED)
        val create = CreateRunTransition(
            commandId = "cmd-fault-create-run",
            run = run,
            taskRevision = task.revision,
            taskDisplay = AgentDisplayStatus.QUEUED
        )
        val faulty = FaultInjectingJournal(delegate, create.commandId, FaultPoint.BEFORE_DELEGATE)

        expectFault { faulty.commit(create) }

        assertNull(delegate.readRun(run.runId))
        assertEquals(0, delegate.listNonTerminalRuns(10).size)
        assertEquals(task.revision, delegate.readTask(task.taskId)!!.revision)
        assertNull(delegate.readTask(task.taskId)!!.lastRunId)

        val applied = faulty.commit(create)
        assertEquals(1L, applied.runRevision)
        assertNotNull(delegate.readRun(run.runId))
        assertEquals(1, delegate.listNonTerminalRuns(10).size)
        val replay = faulty.commit(create)
        assertTrue(replay.idempotentReplay)
        assertEquals(1, delegate.listNonTerminalRuns(10).size)
        assertEquals(1L, delegate.readTask(task.taskId)!!.revision)
    }

    @Test
    fun postCommitFailureIsReplayableWithoutDuplicatingEvents() = runTest {
        val delegate = InMemoryAgentJournal()
        val task = createTask(delegate)
        val run = createRun(delegate, task)
        val events = listOf(
            event(run.runId, 1L, AgentEventTypes.MODEL_ATTEMPT_STARTED),
            event(run.runId, 2L, AgentEventTypes.MODEL_ATTEMPT_SETTLED)
        )
        val append = AppendEventsTransition(
            commandId = "cmd-fault-events",
            runIdValue = run.runId,
            events = events,
            expectedRevision = run.revision
        )
        val faulty = FaultInjectingJournal(delegate, append.commandId, FaultPoint.AFTER_DELEGATE)

        expectFault { faulty.commit(append) }

        assertEquals(2, delegate.readEvents(run.runId, 0L, 10).events.size)
        assertEquals(run.revision + 1, delegate.readRun(run.runId)!!.run.revision)
        val replay = faulty.commit(append)
        assertTrue(replay.idempotentReplay)
        assertEquals(2, delegate.allEvents(run.runId).size)
        assertEquals(run.revision + 1, delegate.readRun(run.runId)!!.run.revision)
        assertEquals(listOf(1L, 2L), delegate.allEvents(run.runId).map { it.sequence })
    }

    @Test
    fun postCommitFailureOnCreateTaskDoesNotDuplicateTheTask() = runTest {
        val delegate = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task(taskId = "task-fault-create")
        val create = CreateTaskTransition("cmd-fault-task", task)
        val faulty = FaultInjectingJournal(delegate, create.commandId, FaultPoint.AFTER_DELEGATE)

        expectFault { faulty.commit(create) }

        assertNotNull(delegate.readTask(task.taskId))
        assertEquals(1, delegate.allTasks().size)
        val replay = faulty.commit(create)
        assertTrue(replay.idempotentReplay)
        assertEquals(1, delegate.allTasks().size)
    }

    @Test
    fun approvalConsumeReplaysTheSameTransitionAndRejectsASecondCommand() = runTest {
        val delegate = InMemoryAgentJournal()
        val task = createTask(delegate)
        var run = createRun(delegate, task)
        run = run.copy(state = AgentRunState.RUNNING, revision = 2L)
        delegate.commit(UpdateRunTransition("cmd-run", run, AgentDisplayStatus.RUNNING, 1L))
        run = run.copy(state = AgentRunState.WAITING_APPROVAL, revision = 3L)
        delegate.commit(UpdateRunTransition("cmd-wait", run, AgentDisplayStatus.WAITING_APPROVAL, 2L))
        val approval = AgentRuntimeFixtures.pendingApproval(
            approvalId = "approval-journal-fault",
            invocationId = "invocation-journal-fault"
        )
        delegate.commit(UpsertApprovalTransition("cmd-approval", approval, expectedRevision = 3L))
        val consume = ConsumeApprovalTransition(
            commandId = "cmd-consume-fault",
            approvalId = approval.approvalId,
            consumedAtMillis = 1_500_000L,
            nextRun = run.copy(state = AgentRunState.RUNNING, revision = 4L),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = 3L,
            decision = AgentApprovalDecision.ALLOW_ONCE
        )
        val first = delegate.commit(consume)
        assertEquals(4L, first.runRevision)
        assertEquals(
            AgentApprovalStatus.CONSUMED,
            delegate.readApproval(approval.approvalId)!!.status
        )
        val replay = delegate.commit(consume)
        assertTrue(replay.idempotentReplay)
        assertEquals(first.appliedRevision, replay.appliedRevision)
        assertEquals(
            AgentApprovalStatus.CONSUMED,
            delegate.readApproval(approval.approvalId)!!.status
        )
        expectJournalRejection(AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT) {
            delegate.commit(consume.copy(decision = AgentApprovalDecision.DENY))
        }
        expectJournalRejection(AgentErrorCodes.COMMAND_INVALID_STATE) {
            delegate.commit(consume.copy(commandId = "cmd-consume-second"))
        }
        assertEquals(
            AgentApprovalStatus.CONSUMED,
            delegate.readApproval(approval.approvalId)!!.status
        )
    }

    @Test
    fun replayedCommandsSurviveAJournalWrapperRestart() = runTest {
        val delegate = InMemoryAgentJournal()
        val task = createTask(delegate)
        val run = createRun(delegate, task)
        val update = UpdateRunTransition(
            commandId = "cmd-restart-update",
            run = run.copy(state = AgentRunState.RUNNING, revision = 2L),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = 1L
        )
        val firstWrapper = FaultInjectingJournal(
            delegate,
            failCommandId = "never-fails",
            point = FaultPoint.BEFORE_DELEGATE,
            failures = 0
        )
        val first = firstWrapper.commit(update)
        assertEquals(2L, first.runRevision)

        // "Process restart": a fresh journal wrapper and fresh caller, same durable delegate.
        val restartedWrapper = FaultInjectingJournal(
            delegate,
            failCommandId = "never-fails",
            point = FaultPoint.BEFORE_DELEGATE,
            failures = 0
        )
        val replay = restartedWrapper.commit(update)
        assertTrue(replay.idempotentReplay)
        assertEquals(first.appliedRevision, replay.appliedRevision)
        assertEquals(2L, delegate.readRun(run.runId)!!.run.revision)
        assertEquals(1, delegate.listNonTerminalRuns(10).size)
    }

    private suspend fun createTask(delegate: InMemoryAgentJournal): AgentTask {
        val task = AgentRuntimeFixtures.task(taskId = "task-fault")
        delegate.commit(CreateTaskTransition("cmd-setup-task", task))
        return task
    }

    private suspend fun createRun(delegate: InMemoryAgentJournal, task: AgentTask): AgentRun {
        val run = AgentRuntimeFixtures.run(taskId = task.taskId, state = AgentRunState.QUEUED)
        delegate.commit(
            CreateRunTransition(
                "cmd-setup-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        return run
    }

    private fun event(runId: String, sequence: Long, type: String): AgentEvent = AgentEvent(
        eventId = "event-fault-$sequence",
        runId = runId,
        sequence = sequence,
        type = type,
        createdAtMillis = 1_000L + sequence
    )

    private suspend fun expectFault(block: suspend () -> Unit) {
        var fault: AgentJournalRejectedException? = null
        try {
            block()
        } catch (rejected: AgentJournalRejectedException) {
            fault = rejected
        }
        assertNotNull("expected an injected fault", fault)
        assertEquals(FaultInjectingJournal.INJECTED_FAULT, fault!!.errorCode)
    }

    private suspend fun expectJournalRejection(code: String, block: suspend () -> Unit) {
        var rejection: AgentJournalRejectedException? = null
        try {
            block()
        } catch (rejected: AgentJournalRejectedException) {
            rejection = rejected
        }
        assertNotNull("expected typed rejection $code", rejection)
        assertEquals(code, rejection!!.errorCode)
    }
}
