package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.runtime.workflow.WorkflowUserWait
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.canStartExecution
import com.muyuchat.core.agent.runtime.run.AgentBudgetPolicy
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandDecision
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandHook
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandRequest
import com.muyuchat.core.agent.runtime.run.AgentIdGenerator
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.runtime.run.AgentMonotonicClock
import com.muyuchat.core.agent.runtime.run.AgentRunEventSink
import com.muyuchat.core.agent.runtime.run.AgentResumeToolDecision
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationBasis
import com.muyuchat.core.agent.runtime.run.AuthorizedToolCommand
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import com.muyuchat.mca.agent.security.AgentFreezeRequest
import com.muyuchat.mca.agent.security.AgentFreezeResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import com.muyuchat.mca.agent.workflow.FileAgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.WorkflowExecutionErrorCodes
import com.muyuchat.mca.agent.workflow.WorkflowExecutionRequest
import com.muyuchat.mca.agent.workflow.WorkflowExecutionService
import com.muyuchat.mca.agent.workflow.WorkflowFrameOutcome
import com.muyuchat.mca.agent.tools.WorkflowToolGatewayFactory
import com.muyuchat.mca.agent.workflow.WorkflowToolCall
import com.muyuchat.mca.agent.workflow.WorkflowToolGateway
import com.muyuchat.mca.agent.workflow.WorkflowToolOutcome
import com.muyuchat.mca.agent.workflow.WorkflowToolReceiptStore
import com.muyuchat.mca.agent.workflow.WorkflowToolScope
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N03/N18: a workflow tool node executes through the same invocation/execution pipeline as the
 * model loop and not through a locally built frozen command.
 *
 * The fakes below implement the same narrow ports the production owner uses; the assertions are on
 * ledger facts and real executor calls, not on source text.
 */
class PipelineWorkflowToolGatewayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val receipts by lazy { WorkflowToolReceiptStore(folder.root) }
    private val events = RecordingEvents()

    private val clockMillis = 1_000_000L
    private val schemaValidator = SupportedToolSchemaValidator()
    private val descriptor: AgentToolDescriptor = AgentV1ToolSpecs
        .descriptors(schemaValidator)
        .single { it.toolKey == AgentV1ToolSpecs.WORKSPACE_READ_TEXT }
    private val executor = FakeExecutor(descriptor)
    private val catalog = object : AgentToolCatalog {
        override fun find(toolKey: String): AgentToolExecutor? =
            executor.takeIf { toolKey == descriptor.toolKey }

        override fun snapshot(): List<AgentToolDescriptor> = listOf(descriptor)
    }
    private val ledger = FakeLedger()
    private val factory = AgentFrozenCommandFactory()
    private val clock = AgentMonotonicClock { clockMillis }
    private var ids = 0
    private val idGenerator = AgentIdGenerator { prefix -> "$prefix-${++ids}" }

    private val run = AgentRun(
        runId = "run-1",
        taskId = "task-1",
        runOrdinal = 1,
        state = AgentRunState.RUNNING,
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
        budget = AgentBudget(),
        spentBudget = AgentSpentBudget(),
        lastSequence = 0,
        revision = 1,
        createdAtMillis = clockMillis,
        updatedAtMillis = clockMillis
    )

    private val task = AgentTask(
        taskId = "task-1",
        workspaceId = "workspace-1",
        goal = "workflow",
        lifecycle = AgentTaskLifecycle.ACTIVE,
        createdAtMillis = clockMillis,
        updatedAtMillis = clockMillis,
        displayStatus = AgentDisplayStatus.RUNNING,
        lastRunId = "run-1",
        revision = 1
    )

    private val call = WorkflowToolCall(
        runId = "run-1",
        nodeId = "read-node",
        toolKey = descriptor.toolKey,
        argumentsJson = """{"path":"inputs/brief.md"}""",
        scope = WorkflowToolScope("task-1", "workspace-1")
    )

    @Test
    fun readOnlyNodeExecutesOnceThroughTheSharedLedgerAndPipeline() = runBlocking {
        val state = WorkflowToolRunState(run)
        val outcome = gateway(state, approvalRequired = false).invoke(call)

        assertTrue(outcome is WorkflowToolOutcome.Succeeded)
        assertEquals(1, executor.calls)
        assertEquals(
            listOf(
                ToolInvocationStatus.PROPOSED,
                ToolInvocationStatus.VALIDATED,
                ToolInvocationStatus.RUNNING,
                ToolInvocationStatus.SUCCEEDED
            ),
            ledger.transitions
        )
        assertEquals(1, state.run.spentBudget.toolInvocations)
        assertEquals(1, state.transcriptSnapshot().size)
        assertEquals(
            listOf(
                AgentEventTypes.TOOL_PROPOSED,
                AgentEventTypes.TOOL_STARTED,
                AgentEventTypes.TOOL_RESULT
            ),
            events.types
        )
    }

    @Test
    fun aFrozenInvocationThatNeedsApprovalParksWithoutCallingTheExecutor() = runBlocking {
        val state = WorkflowToolRunState(run)
        val outcome = gateway(state, approvalRequired = true).invoke(call)

        assertTrue(outcome is WorkflowToolOutcome.WaitingApproval)
        assertEquals(0, executor.calls)
        assertEquals(ToolInvocationStatus.WAITING_APPROVAL, ledger.only().status)
        assertEquals(
            listOf(
                AgentEventTypes.TOOL_PROPOSED,
                AgentEventTypes.TOOL_APPROVAL_REQUESTED
            ),
            events.types
        )
    }

    @Test
    fun anApprovedResumeExecutesTheExactFrozenCommandOnce() = runBlocking {
        val waitingState = WorkflowToolRunState(run)
        val waiting = gateway(waitingState, approvalRequired = true).invoke(call)
        assertTrue(waiting is WorkflowToolOutcome.WaitingApproval)
        val pending = waiting as WorkflowToolOutcome.WaitingApproval
        val invocation = ledger.only()
        assertEquals(
            AgentToolInvocationUpdate.Updated,
            ledger.markApproved(invocation.invocationId, clockMillis)
        )
        val frozen = requireNotNull(ledger.frozen[invocation.invocationId]).command

        val resume = AgentLoopResume(
            transcript = AgentLoopTranscript(emptyList(), 0, 0),
            toolDecision = AgentResumeToolDecision.Authorized(
                authorization = AuthorizedToolCommand(
                    command = frozen,
                    approvalId = pending.approval.approvalId,
                    basis = AgentToolAuthorizationBasis.APPROVED_ONCE
                ),
                callHash = pending.callHash
            )
        )
        val resumedState = WorkflowToolRunState(run, resume.transcript.entries)
        val outcome = gateway(resumedState, approvalRequired = true, resume = resume).invoke(call)

        assertTrue(outcome is WorkflowToolOutcome.Succeeded)
        assertEquals(1, executor.calls)
        assertEquals(ToolInvocationStatus.SUCCEEDED, ledger.only().status)
        assertEquals(1, ledger.runningTransitions)
    }

    @Test
    fun aRetryAfterAnUnknownResultNeverReExecutesTheTool() = runBlocking {
        executor.alwaysFail = true
        val state = WorkflowToolRunState(run)

        val first = gateway(state, approvalRequired = false).invoke(call)
        assertTrue(first is WorkflowToolOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
            (first as WorkflowToolOutcome.Failed).errorCode
        )
        assertEquals(ToolInvocationStatus.UNKNOWN, ledger.only().status)

        val second = gateway(state, approvalRequired = false).invoke(call)
        assertTrue(second is WorkflowToolOutcome.Failed)
        assertEquals(1, executor.calls)
    }

    @Test
    fun aSuccessorRunOfTheSameTaskReusesThePredecessorReceiptWithoutASecondSideEffect() = runBlocking {
        val first = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(call)
        assertTrue(first is WorkflowToolOutcome.Succeeded)
        assertEquals(1, executor.calls)

        // continueTask creates a new run for the same task; no new executor call may happen.
        val successorRun = run.copy(runId = "run-2", runOrdinal = 2)
        val successorCall = call.copy(runId = "run-2")
        val successorLedger = FakeLedger()
        val successorState = WorkflowToolRunState(successorRun)
        val second = gateway(
            successorState,
            approvalRequired = false,
            toolLedger = successorLedger
        ).invoke(successorCall)

        assertTrue("expected reuse, got $second", second is WorkflowToolOutcome.Succeeded)
        assertEquals(1, executor.calls)
        assertTrue("a receipt reuse must not mint a new invocation", successorLedger.invocations.isEmpty())
        assertEquals(0, successorState.run.spentBudget.toolInvocations)
    }

    @Test
    fun aChangedCallForTheSameNodeNeverReusesTheStaleReceiptOrDispatchesBlindly() = runBlocking {
        val first = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(call)
        assertTrue(first is WorkflowToolOutcome.Succeeded)
        assertEquals(1, executor.calls)

        val changed = call.copy(argumentsJson = """{"path":"inputs/other.md"}""")
        val outcome = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(changed)

        assertTrue(outcome is WorkflowToolOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_RECEIPT_MISMATCH,
            (outcome as WorkflowToolOutcome.Failed).errorCode
        )
        assertEquals("a mismatched receipt must not dispatch the tool", 1, executor.calls)
    }

    @Test
    fun distinctExecutionFramesKeepDistinctReceiptsForTheSameNode() = runBlocking {
        val first = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(call)
        assertTrue(first is WorkflowToolOutcome.Succeeded)

        val foreachItem = call.copy(runId = "wfchild-item-0", frameKey = "wfchild-item-0")
        val second = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(foreachItem)

        assertTrue("expected a fresh execution, got $second", second is WorkflowToolOutcome.Succeeded)
        assertEquals("two frames must not share one receipt", 2, executor.calls)
    }

    @Test
    fun aPersistedResultReceiptIsReusedAfterRestartWithoutAnotherExecution() = runBlocking {
        val first = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(call)
        assertTrue(first is WorkflowToolOutcome.Succeeded)
        assertEquals(1, executor.calls)

        // Process restart: a fresh ledger, the same durable receipt, and no new side effect.
        executor.calls = 0
        events.types.clear()
        val restartedLedger = FakeLedger()
        val restarted = gateway(
            WorkflowToolRunState(run),
            approvalRequired = false,
            toolLedger = restartedLedger
        ).invoke(call)

        assertTrue(restarted is WorkflowToolOutcome.Succeeded)
        assertEquals(0, executor.calls)
        assertTrue(restartedLedger.invocations.isEmpty())
        assertEquals(listOf(AgentEventTypes.TOOL_RESULT), events.types)
    }

    private class RecordingEvents : AgentRunEventSink {
        val types = mutableListOf<String>()

        override suspend fun append(
            run: AgentRun,
            type: String,
            summary: String?,
            causationId: String?
        ): AgentRun {
            types += type
            return run.copy(
                lastSequence = run.lastSequence + 1,
                revision = run.revision + 1
            )
        }
    }

    @Test
    fun anUnknownResultReceiptIsReusedAsUnknownWithoutAResend() = runBlocking {
        executor.alwaysFail = true
        val first = gateway(WorkflowToolRunState(run), approvalRequired = false).invoke(call)
        assertTrue(first is WorkflowToolOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
            (first as WorkflowToolOutcome.Failed).errorCode
        )

        executor.calls = 0
        val restartedLedger = FakeLedger()
        val restarted = gateway(
            WorkflowToolRunState(run),
            approvalRequired = false,
            toolLedger = restartedLedger
        ).invoke(call)

        assertTrue(restarted is WorkflowToolOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
            (restarted as WorkflowToolOutcome.Failed).errorCode
        )
        assertEquals(0, executor.calls)
        assertTrue(restartedLedger.invocations.isEmpty())
    }

    @Test
    fun anAuthorizedCommandForAnotherNodeIsRefusedWithZeroCalls() = runBlocking {
        val waitingState = WorkflowToolRunState(run)
        val waiting = gateway(waitingState, approvalRequired = true).invoke(call)
        val pending = waiting as WorkflowToolOutcome.WaitingApproval
        val frozen = requireNotNull(ledger.frozen[pending.invocationId]).command
        val foreign = frozen.copy(stepId = "wfnode-another")

        val resume = AgentLoopResume(
            transcript = AgentLoopTranscript(emptyList(), 0, 0),
            toolDecision = AgentResumeToolDecision.Authorized(
                authorization = AuthorizedToolCommand(
                    command = foreign,
                    approvalId = pending.approval.approvalId,
                    basis = AgentToolAuthorizationBasis.APPROVED_ONCE
                ),
                callHash = pending.callHash
            )
        )
        val outcome = gateway(
            WorkflowToolRunState(run, resume.transcript.entries),
            approvalRequired = true,
            resume = resume
        ).invoke(call)

        assertTrue(outcome is WorkflowToolOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_APPROVAL_MISMATCH,
            (outcome as WorkflowToolOutcome.Failed).errorCode
        )
        assertEquals(0, executor.calls)
    }

    @Test
    fun aSuspendedWorkflowNodeResumesThroughTheRealFrameAndCheckpoint() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-1",
            version = 1,
            displayName = "resume",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"${descriptor.toolKey}","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)
        val frameRun = run.copy(
            policy = run.policy.copy(
                workflow = AgentWorkflowRunSpec(published.definitionId, published.version)
            )
        )
        val state = WorkflowToolRunState(frameRun)
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("checkpoints"),
            toolGateway = WorkflowToolGateway {
                WorkflowToolOutcome.Failed(
                    WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                    "This test uses the run-scoped gateway."
                )
            },
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: AgentTask,
                    state: WorkflowToolRunState,
                    resume: AgentLoopResume?
                ): WorkflowToolGateway = gateway(
                    state = state,
                    approvalRequired = true,
                    resume = resume
                )
            }
        )

        val first = service.executeFrame(frameRun, task, null, state)
        assertTrue("expected suspension, got $first", first is WorkflowFrameOutcome.Suspended)
        val pending = first as WorkflowFrameOutcome.Suspended
        assertEquals(0, executor.calls)
        val invocation = ledger.only()
        assertEquals(ToolInvocationStatus.WAITING_APPROVAL, invocation.status)
        assertEquals(
            AgentToolInvocationUpdate.Updated,
            ledger.markApproved(invocation.invocationId, clockMillis)
        )
        val frozen = requireNotNull(ledger.frozen[invocation.invocationId]).command
        val resume = AgentLoopResume(
            transcript = AgentLoopTranscript(state.transcriptSnapshot(), state.transcriptSnapshot().size, 0),
            toolDecision = AgentResumeToolDecision.Authorized(
                authorization = AuthorizedToolCommand(
                    command = frozen,
                    approvalId = pending.approval.approvalId,
                    basis = AgentToolAuthorizationBasis.APPROVED_ONCE
                ),
                callHash = pending.callHash
            )
        )

        val second = service.executeFrame(frameRun, task, resume, state)
        assertTrue("expected completion, got $second", second is WorkflowFrameOutcome.Completed)
        assertEquals(1, executor.calls)
        assertEquals(
            AgentJsonValue.Obj(listOf("text" to AgentJsonValue.Str("hello"))),
            (second as WorkflowFrameOutcome.Completed).outputs["result"]
        )
    }

    @Test
    fun aSuccessorRunWithoutTheToolCheckpointReusesTheTaskReceiptInsteadOfReRunning() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-1",
            version = 1,
            displayName = "successor",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"${descriptor.toolKey}","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)
        val frameRun = run.copy(
            policy = run.policy.copy(
                workflow = AgentWorkflowRunSpec(published.definitionId, published.version)
            )
        )
        val checkpointRoot = folder.newFolder("checkpoints")
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = checkpointRoot,
            toolGateway = WorkflowToolGateway {
                WorkflowToolOutcome.Failed(
                    WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                    "This test uses the run-scoped gateway."
                )
            },
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: AgentTask,
                    state: WorkflowToolRunState,
                    resume: AgentLoopResume?
                ): WorkflowToolGateway = gateway(
                    state = state,
                    approvalRequired = false,
                    resume = resume,
                    toolLedger = FakeLedger(),
                    run = run
                )
            }
        )

        val first = service.executeFrame(frameRun, task, null, WorkflowToolRunState(frameRun))
        assertTrue("expected completion, got $first", first is WorkflowFrameOutcome.Completed)
        assertEquals(1, executor.calls)

        // Process died after the ledger settled and before the node checkpoint was written.
        File(checkpointRoot, "workflow-checkpoints").listFiles()?.forEach { it.delete() }

        val successor = frameRun.copy(runId = "run-2", runOrdinal = 2)
        val successorState = WorkflowToolRunState(successor)
        val second = service.executeFrame(successor, task, null, successorState)

        assertTrue("expected completion, got $second", second is WorkflowFrameOutcome.Completed)
        assertEquals("the tool must not run twice for the same task", 1, executor.calls)
        assertEquals(
            AgentJsonValue.Obj(listOf("text" to AgentJsonValue.Str("hello"))),
            (second as WorkflowFrameOutcome.Completed).outputs["result"]
        )
        assertEquals(1, successorState.transcriptSnapshot().size)
    }

    @Test
    fun aWaitNodeParksAfterTheEarlierToolWithoutRejectingTheFrame() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-wait",
            version = 1,
            displayName = "wait",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"${descriptor.toolKey}","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode(
                    "wait",
                    WorkflowNodeType.WAIT_APPROVAL,
                    configJson = """{"prompt":"approve the next step"}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "wait"),
                WorkflowEdge("wait", "done")
            )
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)
        val frameRun = run.copy(
            policy = run.policy.copy(
                workflow = AgentWorkflowRunSpec(published.definitionId, published.version)
            )
        )
        val gatherCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val frameGateway = WorkflowToolGateway {
            gatherCalls.incrementAndGet()
            WorkflowToolOutcome.Succeeded(
                AgentJsonValue.Obj(listOf("text" to AgentJsonValue.Str("hello")))
            )
        }
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("checkpoints"),
            toolGateway = frameGateway,
            // executeFrame resolves its gateway from this factory, never from the legacy field.
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: AgentTask,
                    state: com.muyuchat.mca.agent.tools.WorkflowToolRunState,
                    resume: com.muyuchat.core.agent.runtime.run.AgentLoopResume?
                ): WorkflowToolGateway = frameGateway
            }
        )

        val result = service.executeFrame(
            frameRun,
            task,
            null,
            WorkflowToolRunState(frameRun)
        )

        val parked = result as WorkflowFrameOutcome.SuspendedForUser
        assertEquals(WorkflowUserWait.Approval("wait"), parked.wait)
        assertEquals("the earlier tool runs exactly once before the park", 1, gatherCalls.get())
        assertTrue(
            "the waiting node must not be committed before the decision",
            parked.outcomes.none { it.nodeId == "wait" }
        )
    }

    private fun gateway(
        state: WorkflowToolRunState,
        approvalRequired: Boolean,
        resume: AgentLoopResume? = null,
        toolLedger: FakeLedger = ledger,
        run: AgentRun = this.run
    ): PipelineWorkflowToolGateway = PipelineWorkflowToolGateway(
        run = run,
        task = task,
        state = state,
        catalog = catalog,
        toolLedger = toolLedger,
        commandHook = hook(approvalRequired, toolLedger),
        budgetPolicy = AgentBudgetPolicy(clock),
        ids = idGenerator,
        clock = clock,
        receipts = receipts,
        events = events,
        resume = resume
    )

    private fun hook(
        approvalRequired: Boolean,
        ledger: FakeLedger = this.ledger
    ): AgentFrozenCommandHook =
        AgentFrozenCommandHook { request -> freezeAndDecide(request, approvalRequired, ledger) }

    private suspend fun freezeAndDecide(
        request: AgentFrozenCommandRequest,
        approvalRequired: Boolean,
        ledger: FakeLedger
    ): AgentFrozenCommandDecision {
        val frozen = when (
            val result = factory.freeze(
                AgentFreezeRequest(
                    descriptor = request.descriptor,
                    rawArgumentsJson = request.normalizedArgumentsJson,
                    invocationId = request.invocationId,
                    runId = request.run.runId,
                    stepId = request.stepId,
                    callOrdinal = request.callOrdinal,
                    workspaceId = request.task.workspaceId,
                    taskId = request.task.taskId,
                    runEpoch = request.run.epoch,
                    policyVersion = request.run.policy.policyVersion,
                    nowMillis = clockMillis
                )
            )
        ) {
            is AgentFreezeResult.Frozen -> result
            is AgentFreezeResult.Rejected -> return AgentFrozenCommandDecision.Denied(
                result.error.code,
                result.error.safeMessage
            )
        }
        val recorded = ledger.recordFrozen(
            AgentFrozenInvocation(
                command = frozen.command,
                descriptor = request.descriptor,
                scope = AgentToolScope(
                    taskRoot = frozen.scope.taskRoot,
                    logicalPaths = frozen.scope.logicalPaths,
                    expectedOldHash = frozen.scope.expectedOldHash,
                    networkDestinations = frozen.scope.networkDestinations,
                    typedPreconditionJson = frozen.scope.typedPreconditionJson
                ),
                recordedAtMillis = clockMillis
            )
        )
        if (recorded !is AgentToolInvocationRecord.Recorded) {
            val rejected = recorded as AgentToolInvocationRecord.Rejected
            return AgentFrozenCommandDecision.Failed(rejected.errorCode, rejected.safeMessage)
        }
        if (!approvalRequired) {
            return AgentFrozenCommandDecision.ReadyToExecute(frozen.command)
        }
        val approval = AgentApproval(
            approvalId = "approval-${frozen.command.invocationId}",
            invocationId = frozen.command.invocationId,
            principal = "test",
            argumentsHash = frozen.command.argumentsHash,
            toolSchemaHash = frozen.command.schemaHash,
            resourceFingerprint = frozen.command.resourceFingerprint,
            policyVersion = frozen.command.policyVersion,
            runEpoch = frozen.command.runEpoch,
            expiresAtMillis = clockMillis + 300_000L,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = clockMillis
        )
        // The shared pipeline CASes VALIDATED -> WAITING_APPROVAL itself; a hook must not do it.
        return AgentFrozenCommandDecision.WaitingApproval(frozen.command, approval)
    }

    private class FakeExecutor(
        override val descriptor: AgentToolDescriptor
    ) : AgentToolExecutor {
        var calls = 0
        var alwaysFail = false

        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult {
            calls += 1
            if (alwaysFail) error("simulated executor crash")
            return AgentToolResult(
                status = AgentToolResultStatus.SUCCEEDED,
                summary = "read ok",
                dataJson = """{"text":"hello"}"""
            )
        }
    }

    private class FakeLedger : AgentToolInvocationLedger {
        val invocations = LinkedHashMap<String, ToolInvocation>()
        val frozen = HashMap<String, AgentFrozenInvocation>()
        val transitions = ArrayList<ToolInvocationStatus>()
        var runningTransitions = 0

        fun only(): ToolInvocation = invocations.values.single()

        private fun row(
            proposal: AgentToolProposal,
            status: ToolInvocationStatus
        ) = ToolInvocation(
            invocationId = proposal.invocationId,
            runId = proposal.runId,
            stepId = proposal.stepId,
            callOrdinal = proposal.callOrdinal,
            toolKey = proposal.toolKey,
            toolVersion = proposal.toolVersion,
            schemaHash = proposal.schemaHash,
            frozenArgumentsRef = null,
            argumentsHash = proposal.argumentsHash,
            resourceFingerprint = "pending",
            status = status,
            idempotencyKey = proposal.idempotencyKey,
            resultRef = null,
            createdAtMillis = proposal.proposedAtMillis,
            updatedAtMillis = proposal.proposedAtMillis
        )

        override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord {
            invocations.values.firstOrNull {
                it.runId == proposal.runId &&
                    it.stepId == proposal.stepId &&
                    it.callOrdinal == proposal.callOrdinal
            }?.let { return AgentToolInvocationRecord.Recorded(it) }
            val created = row(proposal, ToolInvocationStatus.PROPOSED)
            invocations[created.invocationId] = created
            transitions += ToolInvocationStatus.PROPOSED
            return AgentToolInvocationRecord.Recorded(created)
        }

        override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord {
            val current = invocations[frozen.command.invocationId]
                ?: return AgentToolInvocationRecord.Rejected("COMMAND_NOT_FOUND", "missing")
            if (current.status != ToolInvocationStatus.PROPOSED) {
                return AgentToolInvocationRecord.Rejected(
                    "COMMAND_INVALID_STATE",
                    "The invocation cannot be frozen from ${current.status}."
                )
            }
            this.frozen[current.invocationId] = frozen
            val validated = current.copy(
                status = ToolInvocationStatus.VALIDATED,
                argumentsHash = frozen.command.argumentsHash,
                resourceFingerprint = frozen.command.resourceFingerprint,
                frozenArgumentsRef = "test"
            )
            invocations[current.invocationId] = validated
            transitions += ToolInvocationStatus.VALIDATED
            return AgentToolInvocationRecord.Recorded(validated)
        }

        override suspend fun markWaitingApproval(
            invocationId: String,
            atMillis: Long
        ): AgentToolInvocationUpdate {
            val current = invocations[invocationId]
                ?: return AgentToolInvocationUpdate.Rejected("COMMAND_NOT_FOUND", "missing")
            if (current.status != ToolInvocationStatus.VALIDATED) {
                return AgentToolInvocationUpdate.Rejected("COMMAND_INVALID_STATE", "not validated")
            }
            invocations[invocationId] = current.copy(status = ToolInvocationStatus.WAITING_APPROVAL)
            transitions += ToolInvocationStatus.WAITING_APPROVAL
            return AgentToolInvocationUpdate.Updated
        }

        override suspend fun markApproved(
            invocationId: String,
            atMillis: Long
        ): AgentToolInvocationUpdate {
            val current = invocations[invocationId]
                ?: return AgentToolInvocationUpdate.Rejected("COMMAND_NOT_FOUND", "missing")
            invocations[invocationId] = current.copy(status = ToolInvocationStatus.APPROVED)
            transitions += ToolInvocationStatus.APPROVED
            return AgentToolInvocationUpdate.Updated
        }

        override suspend fun markRunning(
            invocationId: String,
            atMillis: Long
        ): AgentToolInvocationUpdate {
            val current = invocations[invocationId]
                ?: return AgentToolInvocationUpdate.Rejected("COMMAND_NOT_FOUND", "missing")
            if (!current.status.canStartExecution()) {
                return AgentToolInvocationUpdate.Rejected("COMMAND_INVALID_STATE", "not executable")
            }
            invocations[invocationId] = current.copy(status = ToolInvocationStatus.RUNNING)
            transitions += ToolInvocationStatus.RUNNING
            runningTransitions += 1
            return AgentToolInvocationUpdate.Updated
        }

        override suspend fun markTerminal(
            invocationId: String,
            status: ToolInvocationStatus,
            possibleSideEffects: Boolean,
            resultRef: String?,
            atMillis: Long
        ): AgentToolInvocationUpdate {
            val current = invocations[invocationId]
                ?: return AgentToolInvocationUpdate.Rejected("COMMAND_NOT_FOUND", "missing")
            invocations[invocationId] = current.copy(status = status, resultRef = resultRef)
            transitions += status
            return AgentToolInvocationUpdate.Updated
        }

        override suspend fun readInvocation(invocationId: String): ToolInvocation? =
            invocations[invocationId]

        override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? =
            frozen[invocationId]
    }
}
