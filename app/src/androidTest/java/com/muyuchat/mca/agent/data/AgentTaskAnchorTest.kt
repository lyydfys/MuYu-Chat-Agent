package com.muyuchat.mca.agent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import com.muyuchat.mca.agent.memory.data.RoomAgentMemoryPersistence
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N01 / WF-02: the production create command must return a durable, replayable task anchor.
 *
 * The real Room transaction is required here: the command ledger row, the task row and the frozen
 * creation context are written together, and the receipt must survive a second repository instance
 * over the same database (the equivalent of a process restart). A goal+unseen-id scan is impossible
 * to verify as correct, so these cases assert the command -> task mapping directly.
 *
 * Requires a connected device/emulator; NOT_RUN on hosts with no device.
 */
@RunWith(AndroidJUnit4::class)
class AgentTaskAnchorTest {
    private lateinit var database: McaRoomDatabase
    private lateinit var repository: AgentTaskRepository
    private lateinit var memoryPersistence: RoomAgentMemoryPersistence
    private lateinit var selections: AgentMemorySelectionService
    private val ids = AtomicInteger(0)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, McaRoomDatabase::class.java).build()
        memoryPersistence = RoomAgentMemoryPersistence(
            database = database,
            dao = database.agentMemoryDao()
        )
        selections = AgentMemorySelectionService(memoryPersistence, clock = { NOW })
        repository = newRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createdCommandWritesACommandToTaskAnchor() = runBlocking {
        val created = repository.createTask(command(COMMAND_ID)) as AgentTaskCreationOutcome.Created

        assertFalse(created.idempotentReplay)
        val anchor = database.agentCommandDao().find(COMMAND_ID)
        assertNotNull(anchor)
        assertEquals(created.task.taskId, anchor!!.scopeId)
        assertEquals("CREATE_TASK_COMMAND", anchor.commandType)
        assertEquals(created.task.workspaceId, database.agentTaskDao().find(created.task.taskId)!!.workspaceId)
    }

    @Test
    fun replayingTheSameCommandReturnsTheSameTaskIdempotently() = runBlocking {
        val first = repository.createTask(command(COMMAND_ID)) as AgentTaskCreationOutcome.Created
        val replay = repository.createTask(command(COMMAND_ID)) as AgentTaskCreationOutcome.Created

        assertTrue(replay.idempotentReplay)
        assertEquals(first.task.taskId, replay.task.taskId)
        assertEquals(first.task.workspaceId, replay.task.workspaceId)
        assertEquals(1, database.agentTaskDao().page(includeArchived = false, limit = 10).size)
    }

    @Test
    fun aSecondRepositoryOverTheSameDatabaseStillFindsTheAnchor() = runBlocking {
        val first = repository.createTask(command(COMMAND_ID)) as AgentTaskCreationOutcome.Created

        val restarted = newRepository()
        val replay = restarted.createTask(command(COMMAND_ID)) as AgentTaskCreationOutcome.Created

        assertTrue(replay.idempotentReplay)
        assertEquals(first.task.taskId, replay.task.taskId)
        assertEquals(first.task.workspaceId, replay.task.workspaceId)
    }

    @Test
    fun changedPayloadUnderTheSameCommandIdIsRejectedWithoutASecondTask() = runBlocking {
        repository.createTask(command(COMMAND_ID))

        val rejected = repository.createTask(
            command(COMMAND_ID, goal = "a different goal")
        ) as AgentTaskCreationOutcome.Rejected

        assertEquals(AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT, rejected.error.code)
        assertEquals(1, database.agentTaskDao().page(includeArchived = false, limit = 10).size)
    }

    @Test
    fun fiftyConcurrentSameGoalCommandsProduceFiftyDistinctAnchors() = runBlocking {
        val commandIds = (1..50).map { "concurrent-command-$it" }
        val outcomes = coroutineScope {
            commandIds.map { commandId ->
                async(Dispatchers.IO) { repository.createTask(command(commandId)) }
            }.awaitAll()
        }

        val created = outcomes.map { outcome ->
            assertTrue("expected Created, got $outcome", outcome is AgentTaskCreationOutcome.Created)
            (outcome as AgentTaskCreationOutcome.Created).task
        }
        assertEquals(50, created.map { it.taskId }.toSet().size)
        assertEquals(50, database.agentTaskDao().page(includeArchived = false, limit = 100).size)
        commandIds.forEachIndexed { index, commandId ->
            assertEquals(created[index].taskId, database.agentCommandDao().find(commandId)!!.scopeId)
        }
    }

    private fun newRepository(): AgentTaskRepository = AgentTaskRepository(
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
        grantDao = database.agentGrantDao(),
        transactions = RoomAgentTransactionRunner(database),
        clock = AgentClock { NOW },
        idGenerator = { nextId("id") },
        memorySelectionService = selections
    )

    private fun command(
        commandId: String,
        goal: String = "Summarize the selected workspace"
    ): CreateAgentTask = CreateAgentTask(
        commandId = commandId,
        goal = goal,
        displayTitle = "Anchor task",
        modelBinding = AgentModelBinding(
            providerId = "local",
            modelId = "model",
            runtime = "mnn",
            assetFingerprint = "asset",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Model"
        ),
        policy = AgentRunPolicy(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY),
        budget = AgentBudget()
    )

    private fun nextId(prefix: String): String = "$prefix-${ids.incrementAndGet()}"

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val COMMAND_ID = "anchor-command"
    }
}
