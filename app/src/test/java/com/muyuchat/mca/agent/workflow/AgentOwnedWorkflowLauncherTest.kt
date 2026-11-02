package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentCreationReceipt
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentTaskPage
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeState
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N18 entry slice: a workflow run is created through the task gateway and mapped from the owner run.
 *
 * The entry receipt is only a cache. These tests drive the launcher twice with the same request and
 * no shared receipt to prove that the deterministic create/start command ids let the owner's command
 * ledger return the original task/run, and that a content conflict is a typed rejection.
 */
class AgentOwnedWorkflowLauncherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val definition = WorkflowDefinition(
        definitionId = "wf-1",
        version = 1,
        displayName = "Demo",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(
            WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
            WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
        ),
        edges = listOf(WorkflowEdge("trigger", "done"))
    )

    @Test
    fun completedRunIsMappedToItsCheckpointOutputs() = runBlocking {
        val checkpointRoot = folder.newFolder("checkpoints")
        val gateway = FakeGateway()
        val queries = FakeQueries(snapshot(AgentRunState.SUCCEEDED))
        val launcher = launcher(gateway, queries, checkpointRoot)
        FileWorkflowCheckpointStore(checkpointRoot, "run-wf-1").save(checkpoint())

        val result = launcher.run(WorkflowExecutionRequest("wf-1", 1))

        assertTrue("expected completion, got $result", result is WorkflowExecutionResult.Completed)
        val completed = result as WorkflowExecutionResult.Completed
        assertEquals("run-wf-1", completed.runId)
        assertEquals(
            mapOf("result" to AgentJsonValue.Str("done")),
            completed.outputs
        )
        assertEquals("task-wf", gateway.createdTasks.single().taskId)
        assertEquals("task-wf", gateway.startCommands.single().taskId)
        assertEquals(
            AgentWorkflowRunSpec("wf-1", 1),
            gateway.createCommands.single().policy.workflow
        )
        assertEquals(AgentProtocolMode.NONE, gateway.createCommands.single().modelBinding.protocolMode)
    }

    @Test
    fun aWaitingRunIsReturnedAsAParkedWorkflowResult() = runBlocking {
        val checkpointRoot = folder.newFolder("checkpoints")
        val approval = AgentApproval(
            approvalId = "approval-1",
            invocationId = "invocation-1",
            principal = "test",
            argumentsHash = "a".repeat(64),
            toolSchemaHash = "schema",
            resourceFingerprint = "resource",
            policyVersion = 1,
            runEpoch = 1,
            expiresAtMillis = 2_000L,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = 1_000L
        )
        val launcher = launcher(
            FakeGateway(),
            FakeQueries(snapshot(AgentRunState.WAITING_APPROVAL, approval)),
            checkpointRoot
        )

        val result = launcher.run(WorkflowExecutionRequest("wf-1", 1))

        assertTrue("expected suspension, got $result", result is WorkflowExecutionResult.Suspended)
        val suspended = result as WorkflowExecutionResult.Suspended
        assertEquals("invocation-1", suspended.suspension.invocationId)
        assertEquals(approval, suspended.suspension.approval)
    }

    @Test
    fun aMissingCreationReceiptIsRejectedWithoutStartingARun() = runBlocking {
        val gateway = FakeGateway(
            forcedCreation = AgentWorkflowTaskCreation.AnchorMissing("cmd-create")
        )
        val launcher = launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), folder.newFolder())

        val result = launcher.run(WorkflowExecutionRequest("wf-1", 1))

        assertTrue(result is WorkflowExecutionResult.Rejected)
        assertEquals("WF_TASK_ANCHOR_MISSING", (result as WorkflowExecutionResult.Rejected).errorCode)
        assertTrue(gateway.startCommands.isEmpty())
    }

    /**
     * The scheduled entry receipt may never be written (store outage) or may be lost with the
     * process. A second launcher instance with the same request must therefore land on the same
     * create/start commands and the ledger must resolve them to the first task and run.
     */
    @Test
    fun twoLaunchersWithTheSameRequestAndNoReceiptCreateOneTaskAndOneRun() = runBlocking {
        val checkpointRoot = folder.newFolder("checkpoints")
        val gateway = FakeGateway()
        val request = WorkflowExecutionRequest("wf-1", 1, runId = "wfrun-sched-1")
        FileWorkflowCheckpointStore(checkpointRoot, "run-wf-1").save(checkpoint())

        val first = launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), checkpointRoot)
            .run(request)
        val replay = launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), checkpointRoot)
            .run(request)

        assertTrue("first call must complete, got $first", first is WorkflowExecutionResult.Completed)
        assertTrue("the replay must complete, got $replay", replay is WorkflowExecutionResult.Completed)
        assertEquals(
            (first as WorkflowExecutionResult.Completed).runId,
            (replay as WorkflowExecutionResult.Completed).runId
        )
        assertEquals("one task", 1, gateway.createdTasks.size)
        assertEquals("one run", 1, gateway.runIds.size)
        assertEquals(2, gateway.createCommands.size)
        assertEquals(2, gateway.startCommands.size)
        assertEquals(
            gateway.createCommands.first().commandId,
            gateway.createCommands.last().commandId
        )
        assertEquals(
            gateway.startCommands.first().commandId,
            gateway.startCommands.last().commandId
        )
        assertNotEquals(
            gateway.createCommands.first().commandId,
            gateway.startCommands.first().commandId
        )
        assertTrue(gateway.startCommands.all { it.taskId == gateway.createdTasks.single().taskId })
    }

    /**
     * The process may die after the create was accepted and before the start command was applied.
     * The retry must replay the create (same command id, same task) and then start that task.
     */
    @Test
    fun aCrashAfterCreateBeforeStartRetriesTheSameCreateAndStartsThatTask() = runBlocking {
        val checkpointRoot = folder.newFolder("checkpoints")
        val gateway = FakeGateway(failFirstStart = true)
        val request = WorkflowExecutionRequest("wf-1", 1, runId = "wfrun-sched-crash")
        FileWorkflowCheckpointStore(checkpointRoot, "run-wf-1").save(checkpoint())

        var crashed = false
        try {
            launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), checkpointRoot).run(request)
        } catch (_: IllegalStateException) {
            crashed = true
        }
        assertTrue("the fake process crash must surface", crashed)
        assertEquals("the task was created before the crash", 1, gateway.createdTasks.size)
        assertTrue("no run existed before the retry", gateway.runIds.isEmpty())

        val retried = launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), checkpointRoot)
            .run(request)

        assertTrue("the retry must complete, got $retried", retried is WorkflowExecutionResult.Completed)
        assertEquals(1, gateway.createdTasks.size)
        assertEquals(1, gateway.runIds.size)
        assertEquals("run-wf-1", (retried as WorkflowExecutionResult.Completed).runId)
        assertEquals(
            gateway.createCommands.first().commandId,
            gateway.createCommands.last().commandId
        )
        assertEquals(
            gateway.startCommands.first().commandId,
            gateway.startCommands.last().commandId
        )
        assertEquals(
            gateway.createdTasks.single().taskId,
            gateway.startCommands.last().taskId
        )
    }

    /**
     * Reusing one run key with different frozen content is a caller bug. The ledger's fingerprint
     * conflict must become a typed rejection before any start is attempted for that request.
     */
    @Test
    fun aCommandFingerprintConflictIsRejectedWithoutStartingASecondRun() = runBlocking {
        val checkpointRoot = folder.newFolder("checkpoints")
        val gateway = FakeGateway()
        val launcher = launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), checkpointRoot)
        val request = WorkflowExecutionRequest("wf-1", 1, runId = "wfrun-sched-conflict")
        FileWorkflowCheckpointStore(checkpointRoot, "run-wf-1").save(checkpoint())
        assertTrue(launcher.run(request) is WorkflowExecutionResult.Completed)
        val startsBefore = gateway.startCommands.size

        val conflicting = request.copy(
            agentTaskPlan = WorkflowAgentTaskPlan(
                modelBinding = testWorkflowBinding(modelId = "another-model"),
                policy = AgentRunPolicy()
            )
        )
        val rejected = launcher.run(conflicting)

        assertTrue("expected a typed rejection, got $rejected", rejected is WorkflowExecutionResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
            (rejected as WorkflowExecutionResult.Rejected).errorCode
        )
        assertEquals("the conflicting request must not start", startsBefore, gateway.startCommands.size)
        assertEquals(1, gateway.createdTasks.size)
        assertEquals(1, gateway.runIds.size)
    }

    @Test
    fun aSucceededRunWithAnUnreadableCheckpointFailsInsteadOfCompletingEmpty() = runBlocking {
        val checkpointRoot = folder.newFolder("checkpoints")
        val gateway = FakeGateway()
        val request = WorkflowExecutionRequest("wf-1", 1, runId = "wfrun-sched-missing")

        val result = launcher(gateway, FakeQueries(snapshot(AgentRunState.SUCCEEDED)), checkpointRoot).run(request)

        assertTrue("expected a typed failure, got $result", result is WorkflowExecutionResult.Failed)
        assertEquals(
            "WF_OUTPUT_UNAVAILABLE",
            (result as WorkflowExecutionResult.Failed).errorCode
        )
        assertEquals("the run was started exactly once", 1, gateway.createdTasks.size)
    }

    /**
     * No typed cancel command exists on [AgentWorkflowTaskGateway], so the timeout path is unchanged:
     * it returns the existing typed rejection and never re-issues create/start. The live run remains
     * owned by the Agent run and can be stopped from its run detail.
     */
    @Test
    fun aLauncherTimeoutReturnsItsTypedRejectionWithoutStartingASecondRun() = runBlocking {
        val gateway = FakeGateway()
        val launcher = AgentOwnedWorkflowLauncher(
            gateway = gateway,
            queries = FakeQueries(snapshot(AgentRunState.RUNNING), neverSettles = true),
            definitions = FakeDefinitions(definition),
            checkpointRoot = folder.newFolder(),
            timeoutMillis = 50L
        )

        val result = launcher.run(WorkflowExecutionRequest("wf-1", 1, runId = "wfrun-sched-timeout"))

        assertTrue(result is WorkflowExecutionResult.Rejected)
        assertEquals("WF_RUN_TIMEOUT", (result as WorkflowExecutionResult.Rejected).errorCode)
        assertEquals(1, gateway.createCommands.size)
        assertEquals(1, gateway.startCommands.size)
        assertEquals(1, gateway.createdTasks.size)
        assertEquals(1, gateway.runIds.size)
    }

    private fun launcher(
        gateway: FakeGateway,
        queries: AgentWorkspaceQueries,
        checkpointRoot: File
    ) = AgentOwnedWorkflowLauncher(
        gateway = gateway,
        queries = queries,
        definitions = FakeDefinitions(definition),
        checkpointRoot = checkpointRoot
    )

    private fun snapshot(state: AgentRunState, approval: AgentApproval? = null): AgentRunSnapshot {
        val now = 1_000L
        val task = AgentTask(
            taskId = "task-wf",
            workspaceId = "workspace-wf",
            goal = "Workflow: Demo v1",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now,
            displayStatus = AgentDisplayStatus.RUNNING,
            lastRunId = "run-wf-1",
            revision = 1
        )
        val run = AgentRun(
            runId = "run-wf-1",
            taskId = task.taskId,
            runOrdinal = 1,
            state = state,
            reason = AgentPauseReason.NONE,
            epoch = 1,
            modelBinding = AgentModelBinding(
                providerId = "workflow",
                modelId = "workflow",
                runtime = "none",
                assetFingerprint = "workflow",
                protocolMode = AgentProtocolMode.NONE,
                modelDisplayName = "Workflow"
            ),
            policy = AgentRunPolicy(workflow = AgentWorkflowRunSpec("wf-1", 1)),
            budget = com.muyuchat.core.agent.contract.AgentBudget(),
            spentBudget = AgentSpentBudget(),
            lastSequence = 0,
            revision = 1,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        return AgentRunSnapshot(run = run, task = task, pendingApproval = approval)
    }

    private fun checkpoint(): WorkflowRunCheckpoint = WorkflowRunCheckpoint(
        definitionId = "wf-1",
        definitionVersion = 1,
        revision = 1,
        outcomes = listOf(
            WorkflowNodeOutcome("trigger", WorkflowNodeState.SUCCEEDED, AgentJsonValue.Obj(emptyList())),
            WorkflowNodeOutcome("done", WorkflowNodeState.SUCCEEDED, AgentJsonValue.Str("done"))
        ),
        nodeOutputs = mapOf(
            "trigger" to AgentJsonValue.Obj(emptyList()),
            "done" to AgentJsonValue.Str("done")
        ),
        outputs = mapOf("result" to AgentJsonValue.Str("done")),
        activeEdges = setOf("trigger->done"),
        skipped = emptySet()
    )

    /**
     * Ledger-shaped gateway fake.
     *
     * It replays a recorded command only when the id AND the content match, and answers a content
     * mismatch with [AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT], mirroring the owner's command
     * ledger. It never executes a node and creates no second owner/engine.
     */
    private class FakeGateway(
        /** When set, createTask returns this fixed outcome instead of the ledger result. */
        private val forcedCreation: AgentWorkflowTaskCreation? = null,
        /** Simulates the process dying while the first start command is being issued. */
        private val failFirstStart: Boolean = false
    ) : AgentWorkflowTaskGateway {
        val createCommands = mutableListOf<CreateAgentTask>()
        val startCommands = mutableListOf<StartAgentRun>()
        val createdTasks = mutableListOf<AgentCreationReceipt>()
        val runIds = mutableListOf<String>()

        private val createLedger = LinkedHashMap<String, CreateAgentTask>()
        private val startLedger = LinkedHashMap<String, StartAgentRun>()

        override suspend fun createTask(command: CreateAgentTask): AgentWorkflowTaskCreation {
            createCommands += command
            forcedCreation?.let { return it }
            val recorded = createLedger[command.commandId]
            if (recorded != null) {
                if (recorded != command) return AgentWorkflowTaskCreation.Rejected(conflict(command.commandId))
                return AgentWorkflowTaskCreation.Created(
                    createdTasks.single { it.creationCommandId == command.commandId }
                )
            }
            createLedger[command.commandId] = command
            val receipt = AgentCreationReceipt(
                creationCommandId = command.commandId,
                taskId = TASK_ID,
                workspaceId = WORKSPACE_ID,
                appliedRevision = 0L,
                idempotentReplay = false
            )
            createdTasks += receipt
            return AgentWorkflowTaskCreation.Created(receipt)
        }

        override suspend fun startRun(command: StartAgentRun): AgentCommandResult {
            startCommands += command
            if (failFirstStart && startCommands.size == 1) {
                throw IllegalStateException("simulated process death before the start was applied")
            }
            val recorded = startLedger[command.commandId]
            if (recorded != null) {
                if (recorded != command) return AgentCommandResult.Rejected(conflict(command.commandId))
                return AgentCommandResult.Accepted(
                    appliedRevision = 2L,
                    runId = RUN_ID,
                    idempotentReplay = true
                )
            }
            startLedger[command.commandId] = command
            runIds += RUN_ID
            return AgentCommandResult.Accepted(appliedRevision = 2L, runId = RUN_ID)
        }

        private fun conflict(commandId: String): AgentError = AgentError(
            code = AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
            category = AgentErrorCategory.INTERNAL,
            retryability = AgentRetryability.NEVER,
            safeMessage = "command $commandId was already applied with different content",
            correlationId = "fake-ledger"
        )

        private companion object {
            const val TASK_ID = "task-wf"
            const val WORKSPACE_ID = "workspace-wf"
            const val RUN_ID = "run-wf-1"
        }
    }

    private class FakeQueries(
        private val snapshot: AgentRunSnapshot,
        private val neverSettles: Boolean = false
    ) : AgentWorkspaceQueries {
        override fun observeTasks(query: com.muyuchat.core.agent.contract.AgentTaskQuery): Flow<AgentTaskPage> =
            flowOf(AgentTaskPage(emptyList()))

        override fun observeRun(runId: String): Flow<AgentRunSnapshot> =
            if (neverSettles) {
                flow { awaitCancellation() }
            } else {
                flowOf(snapshot)
            }

        override fun observeEvents(
            runId: String,
            afterSequence: Long,
            limit: Int
        ): Flow<com.muyuchat.core.agent.contract.AgentEventPage> =
            flowOf(com.muyuchat.core.agent.contract.AgentEventPage(emptyList()))
    }

    private class FakeDefinitions(private val definition: WorkflowDefinition) : AgentWorkflowDefinitionStore {
        override fun list(): List<AgentWorkflowDefinitionSummary> = emptyList()

        override fun draft(definitionId: String): AgentWorkflowDefinitionDraft? = null

        override fun saveDraft(
            definition: WorkflowDefinition,
            expectedRevision: Long?,
            nowMillis: Long
        ): AgentWorkflowDefinitionDraft = error("not used")

        override fun publish(
            definitionId: String,
            expectedDraftRevision: Long,
            nowMillis: Long,
            referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>
        ): WorkflowDefinition = error("not used")

        override fun latest(definitionId: String): WorkflowDefinition? = definition

        override fun version(definitionId: String, version: Long): WorkflowDefinition? =
            definition.takeIf { it.definitionId == definitionId && it.version == version }
    }
}
