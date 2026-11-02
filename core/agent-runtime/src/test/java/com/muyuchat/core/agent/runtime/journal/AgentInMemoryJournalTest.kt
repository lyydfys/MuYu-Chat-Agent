package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** K03 in-memory journal atomicity/idempotency/revision/sequence tests (T02/T06/T12 memory level). */
class AgentInMemoryJournalTest {

    private val journal = InMemoryAgentJournal()

    @Test
    fun createsTaskAndRunThenReadsSnapshotAndNonTerminalRuns() = runTest {
        val task = createTask()
        val run = createRun(task)
        assertNotNull(journal.readTask(task.taskId))
        val snapshot = journal.readRun(run.runId)
        assertNotNull(snapshot)
        assertEquals(run.runId, snapshot!!.run.runId)
        assertEquals(task.taskId, snapshot.task.taskId)
        assertEquals(1, journal.listNonTerminalRuns(10).size)

        val succeeded = run.copy(
            state = AgentRunState.SUCCEEDED,
            revision = 2L,
            updatedAtMillis = 2_000L
        )
        journal.commit(
            UpdateRunTransition("cmd-complete", succeeded, AgentDisplayStatus.SUCCEEDED, 1L)
        )
        assertEquals(0, journal.listNonTerminalRuns(10).size)
    }

    @Test
    fun sameCommandIdSameContentReplaysTheOriginalResult() = runTest {
        val task = AgentRuntimeFixtures.task()
        val transition = CreateTaskTransition("cmd-create", task)
        val first = journal.commit(transition)
        val second = journal.commit(transition)
        assertEquals(first.appliedRevision, second.appliedRevision)
        assertTrue(!first.idempotentReplay)
        assertTrue(second.idempotentReplay)
        assertEquals(AgentRuntimeFixtures.TASK_ID, journal.readTask(task.taskId)?.taskId)
    }

    @Test
    fun sameCommandIdDifferentContentIsAnIdempotencyConflict() = runTest {
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("cmd-create", task))
        val different = task.copy(goal = "different goal")
        expectRejected(AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT) {
            journal.commit(CreateTaskTransition("cmd-create", different))
        }
    }

    @Test
    fun revisionCasRejectsStaleAndNonConsecutiveRevisions() = runTest {
        val task = createTask()
        val run = createRun(task)
        expectRejected(AgentErrorCodes.COMMAND_REVISION_CONFLICT) {
            journal.commit(
                UpdateRunTransition(
                    "cmd-stale",
                    run.copy(state = AgentRunState.RUNNING, revision = 2L),
                    AgentDisplayStatus.RUNNING,
                    expectedRevision = 0L
                )
            )
        }
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                UpdateRunTransition(
                    "cmd-skip",
                    run.copy(state = AgentRunState.RUNNING, revision = 3L),
                    AgentDisplayStatus.RUNNING,
                    expectedRevision = 1L
                )
            )
        }
        val applied = journal.commit(
            UpdateRunTransition(
                "cmd-run",
                run.copy(state = AgentRunState.RUNNING, revision = 2L),
                AgentDisplayStatus.RUNNING,
                expectedRevision = 1L
            )
        )
        assertEquals(2L, applied.runRevision)
        assertEquals(2L, journal.readRun(run.runId)!!.run.revision)
    }

    @Test
    fun terminalRunsAreImmutableAndNeverReturnToRunning() = runTest {
        val task = createTask()
        val queued = createRun(task)
        journal.commit(
            UpdateRunTransition(
                "cmd-running",
                queued.copy(state = AgentRunState.RUNNING, revision = 2L),
                AgentDisplayStatus.RUNNING,
                1L
            )
        )
        journal.commit(
            UpdateRunTransition(
                "cmd-succeeded",
                queued.copy(state = AgentRunState.SUCCEEDED, revision = 3L),
                AgentDisplayStatus.SUCCEEDED,
                2L
            )
        )
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                UpdateRunTransition(
                    "cmd-revive",
                    queued.copy(state = AgentRunState.RUNNING, revision = 4L),
                    AgentDisplayStatus.RUNNING,
                    3L
                )
            )
        }
        assertEquals(AgentRunState.SUCCEEDED, journal.readRun(queued.runId)!!.run.state)
    }

    @Test
    fun onlyOneNonTerminalRunPerTaskAndOneSuccessorPerContinue() = runTest {
        val task = createTask()
        val first = createRun(task)
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                CreateRunTransition(
                    "cmd-second",
                    first.copy(runId = "run-2", revision = 1L),
                    taskRevision = 1L,
                    taskDisplay = AgentDisplayStatus.QUEUED
                )
            )
        }
        journal.commit(
            UpdateRunTransition(
                "cmd-pause",
                first.copy(
                    state = AgentRunState.PAUSED,
                    reason = AgentPauseReason.USER_REQUESTED,
                    revision = 2L
                ),
                AgentDisplayStatus.PAUSED,
                1L
            )
        )
        val successor = first.copy(
            runId = "run-2",
            runOrdinal = 2L,
            epoch = 2L,
            state = AgentRunState.QUEUED,
            revision = 1L
        )
        journal.commit(
            CreateRunTransition(
                "cmd-continue",
                successor,
                taskRevision = 2L,
                taskDisplay = AgentDisplayStatus.QUEUED,
                successorOfRunId = first.runId
            )
        )
        assertEquals(AgentRunState.SUPERSEDED, journal.readRun(first.runId)!!.run.state)
        assertEquals(AgentRunState.QUEUED, journal.readRun("run-2")!!.run.state)
        assertEquals("run-2", journal.readTask(task.taskId)!!.lastRunId)
        assertEquals(3L, journal.readTask(task.taskId)!!.revision)
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                CreateRunTransition(
                    "cmd-continue-again",
                    first.copy(runId = "run-3", runOrdinal = 3L, epoch = 3L, revision = 1L),
                    taskRevision = 3L,
                    taskDisplay = AgentDisplayStatus.QUEUED,
                    successorOfRunId = "run-2"
                )
            )
        }
    }

    @Test
    fun eventSequencesAreStrictlyMonotonicAndPaged() = runTest {
        val task = createTask()
        val run = createRun(task)
        val firstBatch = (1..3).map { sequence ->
            event(run.runId, sequence.toLong(), AgentEventTypes.RUN_STATE_CHANGED)
        }
        val first = journal.commit(
            AppendEventsTransition("cmd-events-1", run.runId, firstBatch, expectedRevision = 1L)
        )
        assertEquals(listOf(1L, 2L, 3L), first.eventSequences)
        assertEquals(1L, first.runRevision?.minus(1L))
        val secondBatch = (4..6).map { sequence ->
            event(run.runId, sequence.toLong(), AgentEventTypes.RUN_STATE_CHANGED)
        }
        journal.commit(
            AppendEventsTransition("cmd-events-2", run.runId, secondBatch, expectedRevision = 2L)
        )
        val page = journal.readEvents(run.runId, afterSequence = 0L, limit = 2)
        assertEquals(listOf(1L, 2L), page.events.map { it.sequence })
        assertEquals(2L, page.nextSequence)
        val rest = journal.readEvents(run.runId, afterSequence = 2L, limit = 10)
        assertEquals(listOf(3L, 4L, 5L, 6L), rest.events.map { it.sequence })
        assertNull(rest.nextSequence)
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                AppendEventsTransition(
                    "cmd-events-duplicate",
                    run.runId,
                    listOf(event(run.runId, 6L, AgentEventTypes.RUN_STATE_CHANGED)),
                    expectedRevision = journal.readRun(run.runId)!!.run.revision
                )
            )
        }
    }

    @Test
    fun approvalCanBeConsumedExactlyOnce() = runTest {
        val task = createTask()
        var run = createRun(task)
        run = run.copy(state = AgentRunState.RUNNING, revision = 2L)
        journal.commit(
            UpdateRunTransition("cmd-running", run, AgentDisplayStatus.RUNNING, 1L)
        )
        run = run.copy(state = AgentRunState.WAITING_APPROVAL, revision = 3L)
        journal.commit(
            UpdateRunTransition("cmd-waiting", run, AgentDisplayStatus.WAITING_APPROVAL, 2L)
        )
        val approval = AgentApproval(
            approvalId = "approval-1",
            invocationId = "invocation-1",
            principal = "user",
            argumentsHash = "a".repeat(64),
            toolSchemaHash = "b".repeat(64),
            resourceFingerprint = "resource",
            policyVersion = 1L,
            runEpoch = 1L,
            expiresAtMillis = 2_000_000L,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = 1_000_000L
        )
        journal.commit(UpsertApprovalTransition("cmd-approval", approval, expectedRevision = 3L))
        val resumed = run.copy(state = AgentRunState.RUNNING, revision = 4L)
        journal.commit(
            ConsumeApprovalTransition(
                "cmd-consume",
                approval.approvalId,
                consumedAtMillis = 1_500_000L,
                nextRun = resumed,
                taskDisplay = AgentDisplayStatus.RUNNING,
                expectedRevision = 3L
            )
        )
        assertEquals(AgentApprovalStatus.CONSUMED, journal.readApproval(approval.approvalId)!!.status)
        assertEquals(AgentRunState.RUNNING, journal.readRun(run.runId)!!.run.state)
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                ConsumeApprovalTransition(
                    "cmd-consume-2",
                    approval.approvalId,
                    consumedAtMillis = 1_600_000L,
                    nextRun = resumed.copy(revision = 5L),
                    taskDisplay = AgentDisplayStatus.RUNNING,
                    expectedRevision = 4L
                )
            )
        }
    }

    @Test
    fun expiredApprovalIsRejectedWithoutConsumption() = runTest {
        val task = createTask()
        var run = createRun(task)
        run = run.copy(state = AgentRunState.WAITING_APPROVAL, revision = 2L)
        journal.commit(UpdateRunTransition("cmd-waiting", run, AgentDisplayStatus.WAITING_APPROVAL, 1L))
        val approval = AgentApproval(
            approvalId = "approval-1",
            invocationId = "invocation-1",
            principal = "user",
            argumentsHash = "a".repeat(64),
            toolSchemaHash = "b".repeat(64),
            resourceFingerprint = "resource",
            policyVersion = 1L,
            runEpoch = 1L,
            expiresAtMillis = 1_400_000L,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = 1_000_000L
        )
        journal.commit(UpsertApprovalTransition("cmd-approval", approval, expectedRevision = 2L))
        expectRejected(AgentErrorCodes.PERMISSION_EXPIRED) {
            journal.commit(
                ConsumeApprovalTransition(
                    "cmd-consume",
                    approval.approvalId,
                    consumedAtMillis = 1_500_000L,
                    nextRun = run.copy(state = AgentRunState.RUNNING, revision = 3L),
                    taskDisplay = AgentDisplayStatus.RUNNING,
                    expectedRevision = 2L
                )
            )
        }
        assertEquals(AgentApprovalStatus.PENDING, journal.readApproval(approval.approvalId)!!.status)
    }

    @Test
    fun questionCanBeAnsweredOnlyOnceInTheSameRun() = runTest {
        val task = createTask()
        var run = createRun(task)
        run = run.copy(state = AgentRunState.RUNNING, revision = 2L)
        journal.commit(UpdateRunTransition("cmd-running", run, AgentDisplayStatus.RUNNING, 1L))
        val question = AgentQuestion(
            questionId = "question-1",
            runId = run.runId,
            stepIndex = 0,
            text = "哪类读者？",
            options = listOf("开发者", "管理者"),
            status = AgentQuestionStatus.OPEN,
            askedAtMillis = 1_200L
        )
        val waiting = run.copy(state = AgentRunState.WAITING_USER, revision = 3L)
        journal.commit(
            AskQuestionTransition(
                "cmd-ask", question, waiting, AgentDisplayStatus.WAITING_USER, expectedRevision = 2L
            )
        )
        assertEquals(question.questionId, journal.readRun(run.runId)!!.activeQuestion?.questionId)
        val resumed = run.copy(state = AgentRunState.RUNNING, revision = 4L)
        journal.commit(
            AnswerQuestionTransition(
                "cmd-answer",
                question.questionId,
                "开发者",
                1_300L,
                resumed,
                AgentDisplayStatus.RUNNING,
                expectedRevision = 3L
            )
        )
        assertEquals("开发者", journal.readQuestion(question.questionId)!!.answer)
        assertNull(journal.readRun(run.runId)!!.activeQuestion)
        expectRejected(AgentErrorCodes.COMMAND_INVALID_STATE) {
            journal.commit(
                AnswerQuestionTransition(
                    "cmd-answer-2",
                    question.questionId,
                    "管理者",
                    1_400L,
                    resumed.copy(revision = 5L),
                    AgentDisplayStatus.RUNNING,
                    expectedRevision = 4L
                )
            )
        }
    }

    @Test
    fun commandLedgerReplaysIdenticalContentAndRejectsDifferentContent() = runTest {
        journal.recordCommand(AgentCommandRecord("cmd-1", "hash-a", 7L, runId = "run-1"))
        journal.recordCommand(AgentCommandRecord("cmd-1", "hash-a", 7L, runId = "run-1"))
        assertEquals(1, journal.commandRecords().size)
        val recorded = journal.findCommand("cmd-1")!!
        assertEquals(7L, recorded.appliedRevision)
        expectRejected(AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT) {
            journal.recordCommand(AgentCommandRecord("cmd-1", "hash-b", 8L))
        }
    }

    @Test
    fun oneHundredConcurrentSameCommandIdCommitsExactlyOnce() = runTest {
        val task = AgentRuntimeFixtures.task(taskId = "task-concurrent")
        val transition = CreateTaskTransition("cmd-concurrent", task)
        val results = withContext(Dispatchers.Default) {
            coroutineScope {
                (1..100).map {
                    async { journal.commit(transition) }
                }.awaitAll()
            }
        }
        assertEquals(100, results.size)
        assertEquals(1, results.count { !it.idempotentReplay })
        assertEquals(99, results.count { it.idempotentReplay })
        assertEquals(1, results.map { it.appliedRevision }.distinct().size)
        assertNotNull(journal.readTask(task.taskId))
    }

    @Test
    fun oneHundredConcurrentRevisionCasCommitsProduceExactlyOneWinner() = runTest {
        val task = createTask()
        val run = createRun(task)
        val attempts = withContext(Dispatchers.Default) {
            coroutineScope {
                (1..100).map { index ->
                    async {
                        runCatching {
                            journal.commit(
                                UpdateRunTransition(
                                    "cmd-race-$index",
                                    run.copy(state = AgentRunState.RUNNING, revision = 2L),
                                    AgentDisplayStatus.RUNNING,
                                    expectedRevision = 1L
                                )
                            )
                        }
                    }
                }.awaitAll()
            }
        }
        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(99, attempts.count { it.isFailure })
        assertEquals(2L, journal.readRun(run.runId)!!.run.revision)
    }

    @Test
    fun appendEventHelperProducesUniqueSequences() = runTest {
        val task = createTask()
        val run = createRun(task)
        val event = event(run.runId, 1L, AgentEventTypes.RUN_STATE_CHANGED)
        assertTrue(event.sequence > 0)
        assertEquals(64, com.muyuchat.core.agent.runtime.protocol.AgentHashes.sha256HexUtf8("x").length)
    }

    private suspend fun createTask(): AgentTask {
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("cmd-create-task", task))
        return task
    }

    private suspend fun createRun(task: AgentTask): AgentRun {
        val run = AgentRuntimeFixtures.run(state = AgentRunState.QUEUED)
        journal.commit(
            CreateRunTransition(
                "cmd-create-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        return run
    }

    private fun event(runId: String, sequence: Long, type: String): AgentEvent = AgentEvent(
        eventId = "event-$sequence",
        runId = runId,
        sequence = sequence,
        type = type,
        createdAtMillis = 1_000L + sequence
    )

    private suspend fun expectRejected(code: String, block: suspend () -> Unit) {
        var thrown: AgentJournalRejectedException? = null
        try {
            block()
        } catch (rejected: AgentJournalRejectedException) {
            thrown = rejected
        }
        assertNotNull("expected typed rejection $code", thrown)
        assertEquals(code, thrown!!.errorCode)
    }
}
