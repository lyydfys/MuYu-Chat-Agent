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
import com.muyuchat.mca.agent.memory.AgentMemoryErrorCodes
import com.muyuchat.mca.agent.memory.AgentMemoryLimits
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import com.muyuchat.mca.agent.memory.AgentMemorySpaceRecord
import com.muyuchat.mca.agent.memory.data.RoomAgentMemoryPersistence
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof that the production task-creation transaction freezes a memory selection.
 *
 * This is the only level that can run the real Room transaction, the real
 * [RoomAgentMemoryPersistence] and the real [AgentTaskRepository] together:
 *
 * - a valid selection is frozen and mirrored into the persisted run policy;
 * - replaying the same command is idempotent and keeps one task/selection;
 * - changing the selection under the same command id is an idempotency conflict;
 * - an unknown, blank-only or oversized selection rejects without a task/command row.
 *
 * Requires a connected device/emulator; it is NOT_RUN when only static/JVM checks are available.
 */
@RunWith(AndroidJUnit4::class)
class AgentTaskCreationMemoryTest {
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
        repository = AgentTaskRepository(
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createTaskFreezesSelectionAndReplaysIdempotently() = runBlocking {
        seedSpace("space-a")

        val first = repository.createTask(command()) as AgentTaskCreationOutcome.Created
        assertFalse(first.idempotentReplay)
        val taskId = first.task.taskId
        assertEquals(listOf("space-a"), selections.grantForTask(taskId)?.spaceIds)
        val storedPolicy = persistedPolicy(taskId)
        assertEquals(setOf("space-a"), storedPolicy.memorySpaceIds)

        val replay = repository.createTask(command()) as AgentTaskCreationOutcome.Created
        assertTrue(replay.idempotentReplay)
        assertEquals(taskId, replay.task.taskId)
        assertEquals(1, database.agentTaskDao().page(includeArchived = false, limit = 10).size)
        assertEquals(listOf("space-a"), selections.grantForTask(taskId)?.spaceIds)
    }

    @Test
    fun changedSelectionUnderTheSameCommandIdIsRejected() = runBlocking {
        seedSpace("space-a")
        seedSpace("space-b")
        repository.createTask(command(memorySpaceIds = setOf("space-a")))

        val rejected = repository.createTask(
            command(memorySpaceIds = setOf("space-b"))
        ) as AgentTaskCreationOutcome.Rejected

        assertEquals(AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT, rejected.error.code)
        assertEquals(1, database.agentTaskDao().page(includeArchived = false, limit = 10).size)
    }

    @Test
    fun policyOnlySelectionIsFrozenAndMirrored() = runBlocking {
        seedSpace("space-a")

        val created = repository.createTask(
            command(memorySpaceIds = emptySet(), policyMemorySpaceIds = setOf("space-a"))
        ) as AgentTaskCreationOutcome.Created

        assertEquals(setOf("space-a"), persistedPolicy(created.task.taskId).memorySpaceIds)
        assertEquals(listOf("space-a"), selections.grantForTask(created.task.taskId)?.spaceIds)
    }

    @Test
    fun noMemorySelectionCreatesATaskWithoutAMemoryGrant() = runBlocking {
        val created = repository.createTask(
            command(memorySpaceIds = emptySet())
        ) as AgentTaskCreationOutcome.Created

        assertEquals(emptySet<String>(), persistedPolicy(created.task.taskId).memorySpaceIds)
        assertNull(selections.grantForTask(created.task.taskId))
    }

    @Test
    fun unknownSpaceRejectsWithoutTaskOrCommandRow() = runBlocking {
        val rejected = repository.createTask(
            command(memorySpaceIds = setOf("space-missing"))
        ) as AgentTaskCreationOutcome.Rejected

        assertEquals(AgentMemoryErrorCodes.SPACE_NOT_FOUND, rejected.error.code)
        assertTrue(database.agentTaskDao().page(includeArchived = false, limit = 10).isEmpty())
        assertNull(database.agentCommandDao().find(COMMAND_ID))
    }

    @Test
    fun blankOnlySelectionRejectsWithoutTaskRow() = runBlocking {
        val rejected = repository.createTask(
            command(memorySpaceIds = setOf(" ", ""))
        ) as AgentTaskCreationOutcome.Rejected

        assertEquals(AgentMemoryErrorCodes.SELECTION_EMPTY, rejected.error.code)
        assertTrue(database.agentTaskDao().page(includeArchived = false, limit = 10).isEmpty())
    }

    @Test
    fun disagreeingCommandAndPolicySelectionsRejectWithoutTaskRow() = runBlocking {
        val rejected = repository.createTask(
            command(memorySpaceIds = setOf("space-a"), policyMemorySpaceIds = setOf("space-b"))
        ) as AgentTaskCreationOutcome.Rejected

        assertEquals(AgentErrorCodes.TOOL_INPUT_INVALID, rejected.error.code)
        assertTrue(database.agentTaskDao().page(includeArchived = false, limit = 10).isEmpty())
    }

    @Test
    fun oversizedSelectionRejectsWithoutTaskRow() = runBlocking {
        val tooMany = (1..AgentMemoryLimits.MAX_SELECTED_SPACES + 1)
            .map { "space-$it" }
            .toSet()

        val rejected = repository.createTask(
            command(memorySpaceIds = tooMany)
        ) as AgentTaskCreationOutcome.Rejected

        assertEquals(AgentMemoryErrorCodes.SELECTION_TOO_LARGE, rejected.error.code)
        assertTrue(database.agentTaskDao().page(includeArchived = false, limit = 10).isEmpty())
    }

    private suspend fun seedSpace(spaceId: String) {
        memoryPersistence.insertSpace(
            AgentMemorySpaceRecord(
                spaceId = spaceId,
                displayName = "Space $spaceId",
                createdAt = NOW,
                updatedAt = NOW,
                revision = 1L
            )
        )
    }

    private suspend fun persistedPolicy(taskId: String): AgentRunPolicy =
        AgentRecordJson.decodePolicy(
            requireNotNull(requireNotNull(database.agentTaskDao().find(taskId)).policyJson)
        )

    private fun command(
        memorySpaceIds: Set<String> = setOf("space-a"),
        policyMemorySpaceIds: Set<String> = emptySet()
    ): CreateAgentTask = CreateAgentTask(
        commandId = COMMAND_ID,
        goal = "Use the selected memory",
        displayTitle = "Memory task",
        modelBinding = AgentModelBinding(
            providerId = "local",
            modelId = "model",
            runtime = "mnn",
            assetFingerprint = "asset",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Model"
        ),
        policy = AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            memorySpaceIds = policyMemorySpaceIds
        ),
        budget = AgentBudget(),
        memorySpaceIds = memorySpaceIds
    )

    private fun nextId(prefix: String): String = "$prefix-${ids.incrementAndGet()}"

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val COMMAND_ID = "create-memory-task"
    }
}
