package com.muyuchat.mca.agent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.SupersedeRunTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Room journal semantics (transaction rollback, durable idempotency, sequence append,
 * recovery scan). Requires a connected device/emulator and the Lead's @Database v28 integration; it is
 * NOT_RUN in the K05 implementation session and is the evidence to execute afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AgentRoomJournalTest {
    private lateinit var database: McaRoomDatabase
    private lateinit var journal: RoomAgentJournal

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, McaRoomDatabase::class.java).build()
        journal = RoomAgentJournal(
            daos = AgentJournalDaos(
                task = database.agentTaskDao(),
                workspace = database.agentWorkspaceDao(),
                run = database.agentRunDao(),
                step = database.agentStepDao(),
                attempt = database.agentModelAttemptDao(),
                invocation = database.agentInvocationDao(),
                approval = database.agentApprovalDao(),
                event = database.agentEventDao(),
                artifact = database.agentArtifactDao(),
                artifactCommit = database.agentArtifactCommitDao(),
                question = database.agentQuestionDao(),
                command = database.agentCommandDao()
            ),
            transactions = RoomAgentTransactionRunner(database),
            clock = AgentClock { NOW }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createTaskIsIdempotentAndConflictsOnChangedContent() = runBlocking {
        val create = CreateTaskTransition("command-create", task())
        val first = journal.commit(create)
        val replay = journal.commit(create)
        assertTrue(replay.idempotentReplay)
        assertEquals(first.appliedRevision, replay.appliedRevision)
        assertEquals(task(), journalTask())
        try {
            journal.commit(
                CreateTaskTransition("command-create", task().copy(goal = "different goal"))
            )
            fail("expected idempotency conflict")
        } catch (error: AgentJournalRejectedException) {
            assertEquals(AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT, error.errorCode)
        }
    }

    @Test
    fun runRevisionCasAndEventGapRollBackAtomically() = runBlocking {
        journal.commit(CreateTaskTransition("command-create", task()))
        journal.commit(
            CreateRunTransition(
                commandId = "command-run",
                run = run(state = AgentRunState.QUEUED),
                taskRevision = 0L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        val update = UpdateRunTransition(
            commandId = "command-update",
            run = run(state = AgentRunState.RUNNING),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = 0L
        )
        journal.commit(update)
        assertEquals(1L, journal.readRun("run-1")!!.run.revision)
        try {
            journal.commit(update.copy(commandId = "command-stale", expectedRevision = 0L))
            fail("expected stale revision rejection")
        } catch (error: AgentJournalRejectedException) {
            assertEquals(AgentDataErrorCodes.COMMAND_REVISION_CONFLICT, error.errorCode)
        }
        // Event gap must roll back both the event row and lastSequence.
        try {
            journal.commit(
                AppendEventsTransition(
                    commandId = "command-gap",
                    runIdValue = "run-1",
                    events = listOf(event(sequence = 2L)),
                    expectedRevision = 1L
                )
            )
            fail("expected sequence gap rejection")
        } catch (error: AgentJournalRejectedException) {
            assertEquals(AgentDataErrorCodes.COMMAND_INVALID_STATE, error.errorCode)
        }
        val snapshot = journal.readRun("run-1")!!
        assertEquals(1L, snapshot.run.revision)
        assertEquals(0L, snapshot.run.lastSequence)
        assertTrue(journal.readEvents("run-1", 0L, 10).events.isEmpty())
        val append = journal.commit(
            AppendEventsTransition(
                commandId = "command-append",
                runIdValue = "run-1",
                events = listOf(event(sequence = 1L)),
                expectedRevision = 1L
            )
        )
        assertEquals(listOf(1L), append.eventSequences)
        assertEquals(1L, journal.readRun("run-1")!!.run.lastSequence)
    }

    @Test
    fun recoveryIncludesStoppingAndExcludesArchivedTasks() = runBlocking {
        journal.commit(CreateTaskTransition("command-create-active", task(taskId = "task-active")))
        journal.commit(
            CreateRunTransition(
                commandId = "command-run-active",
                run = run(runId = "run-active", taskId = "task-active", state = AgentRunState.QUEUED),
                taskRevision = 0L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        journal.commit(
            UpdateRunTransition(
                commandId = "command-stopping",
                run = run(runId = "run-active", taskId = "task-active", state = AgentRunState.STOPPING),
                taskDisplay = AgentDisplayStatus.STOPPING,
                expectedRevision = 0L
            )
        )
        journal.commit(
            CreateTaskTransition(
                "command-create-archived",
                task(taskId = "task-archived", lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED)
            )
        )
        // Archived provenance rows are seeded directly: the executable transition path deliberately
        // refuses to create a run for a non-ACTIVE task.
        database.agentRunDao().insert(
            AgentEntityMappers.toEntity(
                run(
                    runId = "run-archived",
                    taskId = "task-archived",
                    state = AgentRunState.PAUSED,
                    reason = AgentPauseReason.INTERRUPTED
                )
            )
        )
        val recovery = journal.listNonTerminalRuns(20)
        assertEquals(listOf("run-active"), recovery.map { it.run.runId })
        assertEquals(AgentRunState.STOPPING, recovery.single().run.state)
    }

    @Test
    fun terminalRunClosesTheOpenQuestionInTheSameCommit() = runBlocking {
        journal.commit(CreateTaskTransition("command-create-q", task(taskId = "task-q")))
        journal.commit(
            CreateRunTransition(
                commandId = "command-run-q",
                run = run(runId = "run-q", taskId = "task-q", state = AgentRunState.QUEUED),
                taskRevision = 0L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        journal.commit(
            UpdateRunTransition(
                commandId = "command-running-q",
                run = run(runId = "run-q", taskId = "task-q", state = AgentRunState.RUNNING),
                taskDisplay = AgentDisplayStatus.RUNNING,
                expectedRevision = 0L
            )
        )
        journal.commit(
            UpdateRunTransition(
                commandId = "command-waiting-q",
                run = run(runId = "run-q", taskId = "task-q", state = AgentRunState.WAITING_USER),
                taskDisplay = AgentDisplayStatus.WAITING_USER,
                expectedRevision = 1L
            )
        )
        journal.commit(
            AskQuestionTransition(
                commandId = "command-ask-q",
                question = AgentQuestion(
                    questionId = "question-q",
                    runId = "run-q",
                    stepIndex = 0,
                    text = "Which files should I list?",
                    options = listOf("all"),
                    status = AgentQuestionStatus.OPEN,
                    askedAtMillis = NOW
                ),
                nextRun = run(runId = "run-q", taskId = "task-q", state = AgentRunState.WAITING_USER),
                taskDisplay = AgentDisplayStatus.WAITING_USER,
                expectedRevision = 2L
            )
        )
        assertEquals("question-q", database.agentQuestionDao().findOpenForRun("run-q")?.questionId)

        journal.commit(
            UpdateRunTransition(
                commandId = "command-cancel-q",
                run = run(runId = "run-q", taskId = "task-q", state = AgentRunState.CANCELLED),
                taskDisplay = AgentDisplayStatus.CANCELLED,
                expectedRevision = 3L
            )
        )

        assertNull(database.agentQuestionDao().findOpenForRun("run-q"))
        assertEquals(
            AgentQuestionStatus.CANCELLED.name,
            database.agentQuestionDao().find("question-q")!!.status
        )
    }

    @Test
    fun supersededRunAlsoClosesItsOpenQuestion() = runBlocking {
        journal.commit(CreateTaskTransition("command-create-s", task(taskId = "task-s")))
        journal.commit(
            CreateRunTransition(
                commandId = "command-run-s",
                run = run(runId = "run-s", taskId = "task-s", state = AgentRunState.QUEUED),
                taskRevision = 0L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        journal.commit(
            UpdateRunTransition(
                commandId = "command-running-s",
                run = run(runId = "run-s", taskId = "task-s", state = AgentRunState.RUNNING),
                taskDisplay = AgentDisplayStatus.RUNNING,
                expectedRevision = 0L
            )
        )
        journal.commit(
            UpdateRunTransition(
                commandId = "command-waiting-s",
                run = run(runId = "run-s", taskId = "task-s", state = AgentRunState.WAITING_USER),
                taskDisplay = AgentDisplayStatus.WAITING_USER,
                expectedRevision = 1L
            )
        )
        journal.commit(
            AskQuestionTransition(
                commandId = "command-ask-s",
                question = AgentQuestion(
                    questionId = "question-s",
                    runId = "run-s",
                    stepIndex = 0,
                    text = "Confirm the target file",
                    options = emptyList(),
                    status = AgentQuestionStatus.OPEN,
                    askedAtMillis = NOW
                ),
                nextRun = run(runId = "run-s", taskId = "task-s", state = AgentRunState.WAITING_USER),
                taskDisplay = AgentDisplayStatus.WAITING_USER,
                expectedRevision = 2L
            )
        )
        assertEquals("question-s", database.agentQuestionDao().findOpenForRun("run-s")?.questionId)

        journal.commit(
            SupersedeRunTransition(
                commandId = "command-supersede-s",
                previousRunId = "run-s",
                successorRunId = "run-s2",
                updatedRun = run(runId = "run-s", taskId = "task-s", state = AgentRunState.SUPERSEDED),
                expectedRevision = 3L
            )
        )

        assertNull(database.agentQuestionDao().findOpenForRun("run-s"))
        assertEquals(
            AgentQuestionStatus.CANCELLED.name,
            database.agentQuestionDao().find("question-s")!!.status
        )
    }

    private suspend fun journalTask(): AgentTask? = database.agentTaskDao().find("task-1")?.let(
        AgentEntityMappers::toContract
    )

    private fun task(
        taskId: String = "task-1",
        lifecycle: AgentTaskLifecycle = AgentTaskLifecycle.ACTIVE
    ): AgentTask = AgentTask(
        taskId = taskId,
        workspaceId = "workspace-$taskId",
        goal = "Write the brief",
        lifecycle = lifecycle,
        createdAtMillis = NOW,
        updatedAtMillis = NOW,
        displayStatus = AgentDisplayStatus.IDLE,
        lastRunId = null,
        revision = 0L
    )

    private fun run(
        runId: String = "run-1",
        taskId: String = "task-1",
        state: AgentRunState,
        reason: AgentPauseReason = AgentPauseReason.NONE
    ): AgentRun = AgentRun(
        runId = runId,
        taskId = taskId,
        runOrdinal = 1L,
        state = state,
        reason = reason,
        epoch = 1L,
        modelBinding = AgentModelBinding(
            providerId = "local",
            modelId = "model",
            runtime = "mnn",
            assetFingerprint = "asset",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Model"
        ),
        policy = AgentRunPolicy(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY),
        budget = com.muyuchat.core.agent.contract.AgentBudget(),
        spentBudget = com.muyuchat.core.agent.contract.AgentSpentBudget(),
        lastSequence = 0L,
        revision = 0L,
        createdAtMillis = NOW,
        updatedAtMillis = NOW
    )

    private fun event(sequence: Long): AgentEvent = AgentEvent(
        eventId = "event-$sequence",
        runId = "run-1",
        sequence = sequence,
        type = "run.state_changed",
        createdAtMillis = NOW + sequence,
        summary = "state changed"
    )

    companion object {
        private const val NOW = 1_000L
    }
}
