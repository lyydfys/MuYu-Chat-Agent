package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.workflow.WorkflowBudget
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowFailurePolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import com.muyuchat.core.agent.runtime.workflow.NoWorkflowAgentTaskRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRequest
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeState
import com.muyuchat.core.agent.runtime.workflow.WorkflowUserDecisions
import com.muyuchat.core.agent.runtime.workflow.WorkflowUserWait
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N19/N20 service slice: published-version pinning, durable checkpoint resume, honest typed
 * rejection for WAIT/unsupported nodes, read-only catalog binding and fail-closed errors.
 *
 * The catalog/executor fakes are the existing [AgentToolExecutor] contract; the service itself never
 * declares a second executor.
 */
class WorkflowExecutionServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val schemaValidator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val descriptors = AgentV1ToolSpecs
        .descriptors(schemaValidator)
        .associateBy { it.toolKey }

    private val scope = WorkflowToolScope(taskId = "task-1", workspaceId = "workspace-1")

    // ------------------------------------------------------------------ graph fixtures

    private fun trigger(id: String = "trigger") = WorkflowNode(id, WorkflowNodeType.TRIGGER)

    private fun tool(
        id: String = "gather",
        toolKey: String = AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
        arguments: String = """{"path":"inputs/brief.md"}"""
    ) = WorkflowNode(
        id,
        WorkflowNodeType.TOOL,
        configJson = """{"toolKey":"$toolKey","arguments":$arguments}"""
    )

    private fun waitNode(id: String = "wait") = WorkflowNode(
        id,
        WorkflowNodeType.WAIT_APPROVAL,
        configJson = """{"prompt":"approve the next step"}"""
    )

    private fun agentTask(
        id: String = "agent",
        configJson: String = """{"goal":"summarize the brief"}"""
    ) = WorkflowNode(
        id,
        WorkflowNodeType.AGENT_TASK,
        configJson = configJson
    )

    private fun output(id: String = "done", port: String = "result") = WorkflowNode(
        id,
        WorkflowNodeType.OUTPUT,
        configJson = """{"port":"$port"}"""
    )

    private fun graph(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        policy: WorkflowFailurePolicy = WorkflowFailurePolicy.FAIL_FAST,
        budget: WorkflowBudget = WorkflowBudget()
    ) = WorkflowDefinition(
        definitionId = "wf-exec",
        version = 1,
        displayName = "execution",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = nodes,
        edges = edges,
        budget = budget,
        failurePolicy = policy
    )

    private fun linearGraph(
        toolKey: String = AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
        arguments: String = """{"path":"inputs/brief.md"}"""
    ) = graph(
        nodes = listOf(trigger(), tool(toolKey = toolKey, arguments = arguments), output()),
        edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
    )

    private fun publish(
        store: AgentWorkflowDefinitionStore,
        definition: WorkflowDefinition,
        expectedDraftRevision: Long? = null,
        referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition> =
            emptyMap()
    ): WorkflowDefinition {
        store.saveDraft(definition, expectedDraftRevision, 1_000L)
        val draft = requireNotNull(store.draft(definition.definitionId))
        return store.publish(
            definition.definitionId,
            draft.revision,
            2_000L,
            referencedDefinitions
        )
    }

    @Test
    fun foreachRunsAPinnedChildPerItemAndReusesItsCheckpoint() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = childIdentity()
        publish(store, child)
        val parent = foreachParent(child, maxItems = 100)
        publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("foreach-child", 1) to child
            )
        )

        val result = service(store).execute(
            WorkflowExecutionRequest("foreach-parent", 1, runId = "run-foreach", toolScope = scope)
        )

        assertTrue("expected completion, got $result", result is WorkflowExecutionResult.Completed)
        val items = (result as WorkflowExecutionResult.Completed).outputs["result"] as AgentJsonValue.Arr
        assertEquals(listOf("1", "2", "3"), items.items.map { (it as AgentJsonValue.Num).literal })

        val again = service(store).execute(
            WorkflowExecutionRequest("foreach-parent", 1, runId = "run-foreach", toolScope = scope)
        )
        assertTrue("resume must complete, got $again", again is WorkflowExecutionResult.Completed)
        assertEquals(
            items,
            (again as WorkflowExecutionResult.Completed).outputs["result"]
        )
    }

    @Test
    fun aUserWaitInsideAForeachItemParksTheParentAndResumesOnce() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = WorkflowDefinition(
            definitionId = "foreach-wait-child",
            version = 1,
            displayName = "child with a wait",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger("child-trigger"),
                waitNode("child-wait"),
                output("child-out")
            ),
            edges = listOf(
                WorkflowEdge("child-trigger", "child-wait"),
                WorkflowEdge("child-wait", "child-out")
            )
        )
        publish(store, child)
        val parent = WorkflowDefinition(
            definitionId = "foreach-wait-parent",
            version = 1,
            displayName = "parent",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "items",
                    WorkflowNodeType.LOGIC,
                    configJson = """{"operation":"SET","value":[1]}"""
                ),
                WorkflowNode(
                    "each",
                    WorkflowNodeType.FOREACH,
                    configJson = """{"subworkflowDefinitionId":"foreach-wait-child",""" +
                        """"subworkflowVersion":1,"itemsPath":"$","maxItems":10}"""
                ),
                output("done")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "items"),
                WorkflowEdge("items", "each"),
                WorkflowEdge("each", "done")
            )
        )
        publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("foreach-wait-child", 1) to child
            )
        )

        val first = service(store).execute(
            WorkflowExecutionRequest("foreach-wait-parent", 1, runId = "run-foreach-wait", toolScope = scope)
        )

        val parked = first as WorkflowExecutionResult.SuspendedForUser
        assertEquals(
            "the parent parks with the item's own wait",
            WorkflowUserWait.Approval("child-wait"),
            parked.wait
        )
        assertTrue(
            "the foreach node must stay uncommitted while an item waits",
            parked.outcomes.none { it.nodeId == "each" }
        )

        val resumed = service(store).execute(
            WorkflowExecutionRequest(
                "foreach-wait-parent",
                1,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        )

        val completed = resumed as WorkflowExecutionResult.Completed
        assertEquals(WorkflowNodeState.SUCCEEDED, completed.outcomes.single { it.nodeId == "each" }.state)
        val items = completed.outputs.getValue("result") as AgentJsonValue.Arr
        assertEquals("the single item still produces one result", 1, items.items.size)
    }

    @Test
    fun foreachRejectsCollectionsAboveTheBoundedLimit() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = childIdentity()
        publish(store, child)
        val parent = foreachParent(child, maxItems = 2)
        publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("foreach-child", 1) to child
            )
        )

        val result = service(store).execute(
            WorkflowExecutionRequest("foreach-parent", 1, runId = "run-foreach-limit", toolScope = scope)
        )

        assertTrue("expected limit failure, got $result", result is WorkflowExecutionResult.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.FOREACH_LIMIT,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
    }

    @Test
    fun foreachReportsTheFailingItemIndexWithoutReportingSuccess() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = WorkflowDefinition(
            definitionId = "foreach-child",
            version = 1,
            displayName = "child",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger("child-trigger"),
                WorkflowNode(
                    "child-pick",
                    WorkflowNodeType.LOGIC,
                    configJson = """{"operation":"PICK","path":"missing"}"""
                ),
                output("child-out")
            ),
            edges = listOf(
                WorkflowEdge("child-trigger", "child-pick"),
                WorkflowEdge("child-pick", "child-out")
            )
        )
        publish(store, child)
        val parent = foreachParent(child, maxItems = 100)
        publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("foreach-child", 1) to child
            )
        )

        val result = service(store).execute(
            WorkflowExecutionRequest("foreach-parent", 1, runId = "run-foreach-fail", toolScope = scope)
        )

        assertTrue("expected item failure, got $result", result is WorkflowExecutionResult.Failed)
        val failed = result as WorkflowExecutionResult.Failed
        assertEquals(WorkflowExecutionErrorCodes.FOREACH_ITEM_FAILED, failed.errorCode)
        assertTrue("failure must name the item index", failed.safeMessage.contains("item 0"))
    }

    private fun childIdentity() = WorkflowDefinition(
        definitionId = "foreach-child",
        version = 1,
        displayName = "child",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(trigger("child-trigger"), output("child-out")),
        edges = listOf(WorkflowEdge("child-trigger", "child-out"))
    )

    private fun foreachParent(child: WorkflowDefinition, maxItems: Int) = WorkflowDefinition(
        definitionId = "foreach-parent",
        version = 1,
        displayName = "parent",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(
            trigger(),
            WorkflowNode(
                "items",
                WorkflowNodeType.LOGIC,
                configJson = """{"operation":"SET","value":[1,2,3]}"""
            ),
            WorkflowNode(
                "each",
                WorkflowNodeType.FOREACH,
                configJson = """{"subworkflowDefinitionId":"${child.definitionId}",""" +
                    """"subworkflowVersion":${child.version},"itemsPath":"$","maxItems":$maxItems}"""
            ),
            output("done")
        ),
        edges = listOf(
            WorkflowEdge("trigger", "items"),
            WorkflowEdge("items", "each"),
            WorkflowEdge("each", "done")
        )
    )

    private fun service(
        store: AgentWorkflowDefinitionStore,
        vararg executors: RecordingToolExecutor,
        runIdFactory: () -> String = { "run-generated" },
        agentTaskRunner: WorkflowAgentTaskRunner = NoWorkflowAgentTaskRunner
    ) = WorkflowExecutionService(
        definitions = store,
        checkpointRoot = folder.root,
        toolGateway = RegisteredWorkflowToolGateway(registry(*executors)),
        agentTaskRunner = agentTaskRunner,
        runIdFactory = runIdFactory
    )

    private fun registry(vararg executors: RecordingToolExecutor): WorkflowToolRegistry =
        WorkflowToolRegistry { toolKey ->
            executors.firstOrNull { it.descriptor.toolKey == toolKey }?.let { executor ->
                WorkflowToolTarget(executor.descriptor) { command, context ->
                    executor.execute(command, context)
                }
            }
        }

    // ------------------------------------------------------------------ linear start

    @Test
    fun aLinearGraphRunsThroughTheRegisteredCatalogDescriptor() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(store, linearGraph())
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool, runIdFactory = { "run-linear" }).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )

        val completed = result as WorkflowExecutionResult.Completed
        assertEquals("run-linear", completed.runId)
        assertEquals(listOf("trigger", "gather", "done"), completed.outcomes.map { it.nodeId })
        assertTrue(completed.outcomes.all { it.state == WorkflowNodeState.SUCCEEDED })
        assertEquals(
            AgentJsonValue.Obj(listOf("content" to AgentJsonValue.Str("hello"))),
            completed.outputs.getValue("result")
        )
        assertEquals(1, tool.calls)
        assertEquals("run-linear", tool.commands.single().runId)
        assertEquals("task-1", tool.contexts.single().taskId)
        assertEquals("workspace-1", tool.contexts.single().workspaceId)
        assertEquals(
            codec.parse("""{"limit":4096,"offset":0,"path":"inputs/brief.md"}"""),
            codec.parse(tool.commands.single().normalizedArgumentsJson)
        )
    }

    // ------------------------------------------------------------------ fixed version

    @Test
    fun anExplicitVersionIsPinnedAndNeverFollowsTheLatestVersion() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val v1 = publish(store, linearGraph())
        val v1Tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val v2Tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT))
        val executionService = service(store, v1Tool, v2Tool)

        val first = executionService.execute(
            WorkflowExecutionRequest(v1.definitionId, 1L, runId = "run-pinned-a", toolScope = scope)
        )
        assertTrue(first is WorkflowExecutionResult.Completed)

        val v2 = publish(
            store,
            linearGraph(
                toolKey = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT,
                arguments = """{"query":"brief"}"""
            ),
            expectedDraftRevision = 1L
        )
        assertEquals(2L, v2.version)

        val second = executionService.execute(
            WorkflowExecutionRequest(v1.definitionId, 1L, runId = "run-pinned-b", toolScope = scope)
        )

        assertTrue("version 1 keeps running the version-1 graph", second is WorkflowExecutionResult.Completed)
        assertEquals("v1 tool ran for both runs", 2, v1Tool.calls)
        assertEquals("the version-2 tool must not run for a version-1 request", 0, v2Tool.calls)

        val mismatch = executionService.execute(
            WorkflowExecutionRequest(v1.definitionId, 2L, runId = "run-pinned-a", toolScope = scope)
        )
        assertEquals(
            "WF_CHECKPOINT_MISMATCH",
            (mismatch as WorkflowExecutionResult.Rejected).errorCode
        )
    }

    @Test
    fun aCheckpointOfAnotherDefinitionVersionIsRejected() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val v1 = publish(store, linearGraph())
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val executionService = service(store, tool)

        assertTrue(
            executionService.execute(
                WorkflowExecutionRequest(v1.definitionId, 1L, runId = "run-versioned", toolScope = scope)
            ) is WorkflowExecutionResult.Completed
        )

        publish(
            store,
            linearGraph(
                toolKey = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT,
                arguments = """{"query":"brief"}"""
            ),
            expectedDraftRevision = 1L
        )

        val rejected = executionService.execute(
            WorkflowExecutionRequest(v1.definitionId, 2L, runId = "run-versioned", toolScope = scope)
        )
        assertEquals(
            "WF_CHECKPOINT_MISMATCH",
            (rejected as WorkflowExecutionResult.Rejected).errorCode
        )
        assertEquals("the rejected version-2 attempt must not run a node", 1, tool.calls)
    }

    // ------------------------------------------------------------------ checkpoint resume

    @Test
    fun aRestartedServiceResumesWithoutReexecutingSavedNodes() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(store, linearGraph())
        val firstTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val first = service(store, firstTool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, runId = "run-resume", toolScope = scope)
        ) as WorkflowExecutionResult.Completed
        assertEquals(1, firstTool.calls)

        // A new service instance over the same checkpoint root is the process-restart stand-in.
        val noCallTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val resumed = service(store, noCallTool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, runId = "run-resume", toolScope = scope)
        ) as WorkflowExecutionResult.Completed

        assertEquals("no saved node may run again", 0, noCallTool.calls)
        assertEquals(first.outputs, resumed.outputs)
        assertEquals(first.outcomes, resumed.outcomes)
    }

    @Test
    fun aCorruptCheckpointFailsClosedBeforeAnyNodeRuns() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(store, linearGraph())
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val checkpointDirectory = File(folder.root, "workflow-checkpoints").also { it.mkdirs() }
        File(checkpointDirectory, "run-corrupt.json").writeText("{not-json", Charsets.UTF_8)

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, runId = "run-corrupt", toolScope = scope)
        )

        assertEquals(
            "WF_CHECKPOINT_CORRUPT",
            (result as WorkflowExecutionResult.Rejected).errorCode
        )
        assertEquals("a corrupt checkpoint must not be treated as empty", 0, tool.calls)
    }

    @Test
    fun anUnsafeRunIdIsRejectedBeforeTheStoreIsTouched() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(store, linearGraph())
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, runId = "../escape", toolScope = scope)
        )

        assertEquals(
            WorkflowExecutionErrorCodes.RUN_ID_INVALID,
            (result as WorkflowExecutionResult.Rejected).errorCode
        )
        assertEquals(0, tool.calls)
    }

    // ------------------------------------------------------------------ typed rejection

    @Test
    fun aWaitNodeParksAndOnlyADurableApprovalCompletesIt() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), waitNode(), output()),
                edges = listOf(WorkflowEdge("trigger", "wait"), WorkflowEdge("wait", "done"))
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val service = service(store, tool)

        val first = service.execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )
        val parked = first as WorkflowExecutionResult.SuspendedForUser
        assertEquals(WorkflowUserWait.Approval("wait"), parked.wait)
        assertTrue(
            "the waiting node must not be committed before the decision",
            parked.outcomes.none { it.nodeId == "wait" }
        )

        val resumed = service.execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        )
        val completed = resumed as WorkflowExecutionResult.Completed
        assertEquals(WorkflowNodeState.SUCCEEDED, completed.outcomes.single { it.nodeId == "wait" }.state)
        assertTrue(completed.outputs.containsKey("result"))
        assertEquals(0, tool.calls)
    }

    @Test
    fun aWaitNodeParksAfterTheEarlierToolAndResumeNeverReplaysIt() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), tool(), waitNode(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "wait"),
                    WorkflowEdge("wait", "done")
                )
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )

        val parked = result as WorkflowExecutionResult.SuspendedForUser
        assertEquals(
            "the tool before the wait runs exactly once before the park",
            1,
            tool.calls
        )

        val resumed = service(store, tool).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        )
        assertTrue("expected completion, got $resumed", resumed is WorkflowExecutionResult.Completed)
        assertEquals(
            "a resume must not replay a committed tool node",
            1,
            tool.calls
        )
    }

    @Test
    fun aDuplicateWakeWithoutADecisionReparksWithoutRunningAnythingAgain() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), tool(), waitNode(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "wait"),
                    WorkflowEdge("wait", "done")
                )
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val service = service(store, tool)

        val first = service.execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )
        val parked = first as WorkflowExecutionResult.SuspendedForUser
        assertEquals(1, tool.calls)

        // A platform wake can fire before the user decides; it must be a no-op park, never a
        // second execution of the committed tool node and never a synthetic approval.
        val again = service.execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                toolScope = scope,
                runId = parked.runId
            )
        )
        val reparked = again as WorkflowExecutionResult.SuspendedForUser
        assertEquals(WorkflowUserWait.Approval("wait"), reparked.wait)
        assertEquals("a wake without an answer must not re-run the tool", 1, tool.calls)
    }

    @Test
    fun anAgentTaskNodeIsTypedNotSupportedWithoutTheOwnerFrame() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), agentTask(), output()),
                edges = listOf(WorkflowEdge("trigger", "agent"), WorkflowEdge("agent", "done"))
            )
        )

        val result = service(store).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )

        assertEquals(
            WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
    }

    @Test
    fun theWaitForUserPolicyParksAFailedNodeAndContinueRunsTheRest() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            linearGraph().copy(failurePolicy = WorkflowFailurePolicy.WAIT_FOR_USER)
        )
        val failing = RecordingToolExecutor(
            descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT),
            AgentToolResult(
                status = AgentToolResultStatus.FAILED,
                summary = "read failed",
                errorCode = "WORKSPACE_READ_FAILED"
            )
        )
        val service = service(store, failing)

        val first = service.execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )
        val parked = first as WorkflowExecutionResult.SuspendedForUser
        val failure = parked.wait as WorkflowUserWait.NodeFailure
        assertEquals("gather", failure.nodeId)
        assertEquals(1, failing.calls)

        val continued = service.execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.CONTINUE
            )
        )
        // "continue" is the CONTINUE policy for this node: the failed node's branch has no value
        // to carry, so its successor is SKIPPED and the run reports the failure.
        val failedRun = continued as WorkflowExecutionResult.Failed
        assertTrue("the run must still report a failure: ${failedRun.errorCode}", failedRun.errorCode.isNotBlank())
        assertEquals("the failed node is never re-executed", 1, failing.calls)
        assertEquals(
            WorkflowNodeState.FAILED,
            failedRun.outcomes.single { it.nodeId == "gather" }.state
        )
        assertEquals(
            WorkflowNodeState.SKIPPED,
            failedRun.outcomes.single { it.nodeId == "done" }.state
        )
    }

    // ------------------------------------------------------------------ catalog binding

    @Test
    fun anUnregisteredToolKeyIsRejectedWithoutRunningAnyExecutor() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            linearGraph(
                toolKey = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT,
                arguments = """{"query":"brief"}"""
            )
        )
        val readTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, readTool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )

        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
        assertEquals(0, readTool.calls)
    }

    @Test
    fun aWriteToolIsPolicyDeniedAndNeverExecuted() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            linearGraph(
                toolKey = AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT,
                arguments = """{"path":"artifacts/out.txt","content":"x","expectedOldHash":null}"""
            )
        )
        val writeTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT))

        val result = service(store, writeTool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, toolScope = scope)
        )

        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_POLICY_DENIED,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
        assertEquals(0, writeTool.calls)
    }

    @Test
    fun aToolNodeWithoutTheOwnerScopeIsRejected() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(store, linearGraph())
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(published.definitionId, published.version)
        )

        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_CONTEXT_UNAVAILABLE,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
        assertEquals(0, tool.calls)
    }

    // ------------------------------------------------------------------ agent-task owner port

    private fun agentPlan(): WorkflowAgentTaskPlan = WorkflowAgentTaskPlan(
        modelBinding = testWorkflowBinding(),
        policy = AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            offlineForced = true
        ),
        displayTitle = "workflow child"
    )

    @Test
    fun anAgentTaskNodeStartsAChildThroughTheOwnerPortAndUsesItsOutput() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), agentTask(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "agent"),
                    WorkflowEdge("agent", "done")
                )
            )
        )
        val childOutput = AgentJsonValue.Obj(
            listOf("summary" to AgentJsonValue.Str("ok"))
        )
        val runner = RecordingAgentTaskRunner(
            WorkflowAgentTaskOutcome.Succeeded(
                runId = "run-child",
                taskId = "task-child",
                output = childOutput,
                spent = AgentSpentBudget(modelAttempts = 2, generatedTokens = 120)
            )
        )

        val result = service(store, agentTaskRunner = runner).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                runId = "run-parent",
                agentTaskPlan = agentPlan()
            )
        )

        val completed = result as WorkflowExecutionResult.Completed
        assertEquals(childOutput, completed.outputs.getValue("result"))
        assertEquals(1, runner.requests.size)
        val request = runner.requests.single()
        assertEquals("run-parent", request.workflowRunId)
        assertEquals("agent", request.nodeId)
        assertEquals("summarize the brief", request.goal)
        assertEquals("workflow child", request.displayTitle)
        assertEquals(testWorkflowBinding(), request.modelBinding)
        assertEquals(12, request.budget.modelAttempts)
        assertTrue("the parent remaining duration bounds the child wait", request.waitTimeoutMillis > 0L)
        assertTrue(
            "a node without memorySpaceIds keeps the plan policy unchanged",
            request.policy.memorySpaceIds.isEmpty()
        )
    }

    @Test
    fun anAgentTaskNodeCanFreezeItsOwnMemorySpaceSelection() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(
                    trigger(),
                    agentTask(
                        configJson = """{"goal":"summarize the brief","memorySpaceIds":["space-b","space-a"]}"""
                    ),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "agent"),
                    WorkflowEdge("agent", "done")
                )
            )
        )
        val runner = RecordingAgentTaskRunner(
            WorkflowAgentTaskOutcome.Succeeded(
                runId = "run-child",
                taskId = "task-child",
                output = AgentJsonValue.Str("ok"),
                spent = AgentSpentBudget(modelAttempts = 1)
            )
        )

        val result = service(store, agentTaskRunner = runner).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                agentTaskPlan = agentPlan()
            )
        )

        assertTrue("expected Completed, got $result", result is WorkflowExecutionResult.Completed)
        assertEquals(
            setOf("space-a", "space-b"),
            runner.requests.single().policy.memorySpaceIds
        )
    }

    @Test
    fun anAgentTaskMemorySelectionCannotContradictTheRunPlan() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(
                    trigger(),
                    agentTask(
                        configJson = """{"goal":"summarize the brief","memorySpaceIds":["space-b"]}"""
                    ),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "agent"),
                    WorkflowEdge("agent", "done")
                )
            )
        )
        val runner = RecordingAgentTaskRunner(
            WorkflowAgentTaskOutcome.Succeeded(
                runId = "run-child",
                taskId = "task-child",
                output = AgentJsonValue.Str("ok"),
                spent = AgentSpentBudget(modelAttempts = 1)
            )
        )
        val plan = agentPlan().let { plan ->
            plan.copy(policy = plan.policy.copy(memorySpaceIds = setOf("space-a")))
        }

        val result = service(store, agentTaskRunner = runner).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                agentTaskPlan = plan
            )
        )

        val failed = result as WorkflowExecutionResult.Failed
        assertEquals(WorkflowExecutionErrorCodes.CONFIG_INVALID, failed.errorCode)
        assertTrue("a mismatched selection must not start the child", runner.requests.isEmpty())
    }

    @Test
    fun anInvalidAgentTaskMemorySelectionIsRejectedBeforeTheChildStarts() = runBlocking {
        val invalid = listOf(
            """{"goal":"g","memorySpaceIds":[]}""",
            """{"goal":"g","memorySpaceIds":["space-a","space-a"]}""",
            """{"goal":"g","memorySpaceIds":[" "]}""",
            """{"goal":"g","memorySpaceIds":[1]}""",
            "{\"goal\":\"g\",\"memorySpaceIds\":[" +
                (1..17).joinToString(",") { "\"space-$it\"" } + "]}"
        )
        invalid.forEachIndexed { index, config ->
            val store = FileAgentWorkflowDefinitionStore(folder.newFolder("invalid-$index"))
            val published = publish(
                store,
                graph(
                    nodes = listOf(trigger(), agentTask(configJson = config), output()),
                    edges = listOf(
                        WorkflowEdge("trigger", "agent"),
                        WorkflowEdge("agent", "done")
                    )
                )
            )
            val runner = RecordingAgentTaskRunner(
                WorkflowAgentTaskOutcome.Succeeded(
                    runId = "run-child",
                    taskId = "task-child",
                    output = AgentJsonValue.Str("ok"),
                    spent = AgentSpentBudget(modelAttempts = 1)
                )
            )

            val result = service(store, agentTaskRunner = runner).execute(
                WorkflowExecutionRequest(
                    published.definitionId,
                    published.version,
                    agentTaskPlan = agentPlan()
                )
            )

            val failed = result as WorkflowExecutionResult.Failed
            assertEquals(
                "config $index must be rejected",
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                failed.errorCode
            )
            assertTrue("config $index must not start the child", runner.requests.isEmpty())
        }
    }

    @Test
    fun aSecondAgentTaskWithAnExhaustedSharedBudgetNeverStarts() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(
                    trigger(),
                    agentTask("agent-1"),
                    agentTask("agent-2"),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "agent-1"),
                    WorkflowEdge("agent-1", "agent-2"),
                    WorkflowEdge("agent-2", "done")
                ),
                budget = WorkflowBudget(
                    maxModelCalls = 1,
                    maxToolCalls = 8,
                    maxEstimatedTokens = 10_000,
                    maxDurationMillis = 600_000L
                )
            )
        )
        val runner = RecordingAgentTaskRunner(
            WorkflowAgentTaskOutcome.Succeeded(
                runId = "run-child",
                taskId = "task-child",
                output = AgentJsonValue.Str("ok"),
                spent = AgentSpentBudget(modelAttempts = 1, generatedTokens = 50)
            )
        )

        val result = service(store, agentTaskRunner = runner).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                agentTaskPlan = agentPlan()
            )
        )

        val failed = result as WorkflowExecutionResult.Failed
        assertEquals(WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED, failed.errorCode)
        assertEquals("only the first child may start", 1, runner.requests.size)
        assertEquals(
            "SUCCEEDED",
            failed.outcomes.single { it.nodeId == "agent-1" }.state.name
        )
        assertEquals(
            WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
            failed.outcomes.single { it.nodeId == "agent-2" }.errorCode
        )
    }

    @Test
    fun anAgentTaskNodeStaysTypedNotSupportedWhenTheRunnerHasNoOwner() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), agentTask(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "agent"),
                    WorkflowEdge("agent", "done")
                )
            )
        )

        val result = service(store).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                agentTaskPlan = agentPlan()
            )
        )

        assertEquals(
            WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
    }

    @Test
    fun anAgentTaskRunnerThatRefusesIsTypedNotSupportedAndNeverSucceeds() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(trigger(), agentTask(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "agent"),
                    WorkflowEdge("agent", "done")
                )
            )
        )
        val runner = RecordingAgentTaskRunner(
            WorkflowAgentTaskOutcome.NotSupported("owner unavailable")
        )

        val result = service(store, agentTaskRunner = runner).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                agentTaskPlan = agentPlan()
            )
        )

        assertEquals(
            WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
    }

    // ------------------------------------------------------------------ other node boundaries

    @Test
    fun modelNodeIsTypedNotSupportedOnTheLegacyDirectPath() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val cases = listOf(
            WorkflowNode("model", WorkflowNodeType.MODEL, configJson = """{"prompt":"hi"}""")
        )
        cases.forEachIndexed { index, node ->
            val definition = graph(
                nodes = listOf(trigger(), node, output()),
                edges = listOf(
                    WorkflowEdge("trigger", node.nodeId),
                    WorkflowEdge(node.nodeId, "done")
                )
            ).copy(definitionId = "wf-node-${node.nodeId}")
            val published = publish(store, definition)

            val result = service(store).execute(
                WorkflowExecutionRequest(
                    published.definitionId,
                    published.version,
                    runId = "run-node-${node.nodeId}"
                )
            )

            val failed = result as WorkflowExecutionResult.Failed
            assertEquals(
                "case $index (${node.type}) must be typed NOT_SUPPORTED",
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                failed.errorCode
            )
            assertEquals(
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                failed.outcomes.single { it.nodeId == node.nodeId }.errorCode
            )
        }
    }

    @Test
    fun aScriptNodeRunsAPinnedCatalogExportThroughTheSameToolGateway() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val definition = graph(
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "script",
                    WorkflowNodeType.SCRIPT,
                    configJson = """{"scriptRef":"${AgentV1ToolSpecs.WORKSPACE_READ_TEXT}","arguments":{"path":"inputs/brief.md"}}"""
                ),
                output("done")
            ),
            edges = listOf(WorkflowEdge("trigger", "script"), WorkflowEdge("script", "done"))
        ).copy(definitionId = "wf-script")
        val published = publish(store, definition)
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                runId = "run-script",
                toolScope = scope
            )
        )

        assertTrue("expected completion, got $result", result is WorkflowExecutionResult.Completed)
        assertEquals(
            AgentJsonValue.Obj(listOf("content" to AgentJsonValue.Str("hello"))),
            (result as WorkflowExecutionResult.Completed).outputs["result"]
        )
        assertEquals(1, tool.calls)
        assertEquals("run-script", tool.commands.single().runId)
    }

    @Test
    fun anUnregisteredScriptExportFailsClosedWithoutExecutingAnything() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val definition = graph(
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "script",
                    WorkflowNodeType.SCRIPT,
                    configJson = """{"scriptRef":"package.export.missing","arguments":{}}"""
                ),
                output("done")
            ),
            edges = listOf(WorkflowEdge("trigger", "script"), WorkflowEdge("script", "done"))
        ).copy(definitionId = "wf-script-missing")
        val published = publish(store, definition)

        val result = service(store).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                runId = "run-script-missing",
                toolScope = scope
            )
        )

        assertTrue("expected failure, got $result", result is WorkflowExecutionResult.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
    }

    @Test
    fun logicNodeExecutesInTheGraphStrategyInsteadOfBeingUnsupported() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(
                    trigger(),
                    WorkflowNode(
                        "logic",
                        WorkflowNodeType.LOGIC,
                        configJson = """{"operation":"SET","value":"ok"}"""
                    ),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "logic"),
                    WorkflowEdge("logic", "done")
                )
            ).copy(definitionId = "wf-logic-supported")
        )

        val result = service(store).execute(
            WorkflowExecutionRequest(published.definitionId, published.version, runId = "run-logic")
        )

        assertTrue("expected completion, got $result", result is WorkflowExecutionResult.Completed)
        assertEquals(
            AgentJsonValue.Str("ok"),
            (result as WorkflowExecutionResult.Completed).outputs["result"]
        )
    }

    @Test
    fun anExtractNodeResolvesABoundedDotPathFromOneCommittedInput() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(
                    trigger(),
                    tool(),
                    WorkflowNode(
                        "extract",
                        WorkflowNodeType.EXTRACT,
                        configJson = """{"source":"input","path":"content"}"""
                    ),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "extract"),
                    WorkflowEdge("extract", "done")
                )
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                runId = "run-extract",
                toolScope = scope
            )
        )

        val completed = result as WorkflowExecutionResult.Completed
        assertEquals(AgentJsonValue.Str("hello"), completed.outputs.getValue("result"))
        assertEquals(1, tool.calls)
    }

    @Test
    fun anExtractNodeFailsClosedWhenThePathDoesNotExist() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val published = publish(
            store,
            graph(
                nodes = listOf(
                    trigger(),
                    tool(),
                    WorkflowNode(
                        "extract",
                        WorkflowNodeType.EXTRACT,
                        configJson = """{"source":"input","path":"missing"}"""
                    ),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "extract"),
                    WorkflowEdge("extract", "done")
                )
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))

        val result = service(store, tool).execute(
            WorkflowExecutionRequest(
                published.definitionId,
                published.version,
                runId = "run-extract-missing",
                toolScope = scope
            )
        )

        assertEquals(
            WorkflowExecutionErrorCodes.EXTRACT_PATH_MISSING,
            (result as WorkflowExecutionResult.Failed).errorCode
        )
    }

    @Test
    fun aPinnedSubworkflowRunsItsOwnFixedVersion() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val childV1 = WorkflowDefinition(
            definitionId = "child-wf",
            version = 1,
            displayName = "child v1",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(trigger(), tool(), output()),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        val publishedChildV1 = publish(store, childV1)
        val childV2 = childV1.copy(
            version = 2,
            displayName = "child v2",
            nodes = listOf(
                trigger(),
                tool(toolKey = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT, arguments = """{"query":"x"}"""),
                output()
            )
        )
        publish(store, childV2, expectedDraftRevision = 1L, referencedDefinitions = emptyMap())

        val parent = WorkflowDefinition(
            definitionId = "parent-wf",
            version = 1,
            displayName = "parent",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-wf",
                    subworkflowVersion = publishedChildV1.version
                ),
                output()
            ),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done"))
        )
        val publishedParent = publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("child-wf", 1L) to publishedChildV1
            )
        )

        val readTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val searchTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT))

        val result = service(store, readTool, searchTool).execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                runId = "run-parent-sub",
                toolScope = scope
            )
        )

        val completed = result as WorkflowExecutionResult.Completed
        assertEquals("the pinned child v1 tool runs", 1, readTool.calls)
        assertEquals("the later child v2 tool never runs", 0, searchTool.calls)
        val subOutput = completed.outputs.getValue("result") as AgentJsonValue.Obj
        val childResult = subOutput.fields.toMap().getValue("result") as AgentJsonValue.Obj
        assertEquals(AgentJsonValue.Str("hello"), childResult.fields.toMap().getValue("content"))
    }

    @Test
    fun aUserWaitInsideAPinnedSubworkflowParksTheParentAndResumesTheChildOnce() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = WorkflowDefinition(
            definitionId = "child-wait-wf",
            version = 1,
            displayName = "child with a wait",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(trigger(), tool(), waitNode(), output()),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "wait"),
                WorkflowEdge("wait", "done")
            )
        )
        val publishedChild = publish(store, child)
        val parent = WorkflowDefinition(
            definitionId = "parent-nested-wait",
            version = 1,
            displayName = "parent",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-wait-wf",
                    subworkflowVersion = publishedChild.version
                ),
                output()
            ),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done"))
        )
        val publishedParent = publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("child-wait-wf", 1L) to publishedChild
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val service = service(store, tool)

        val first = service.execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                runId = "run-nested-wait",
                toolScope = scope
            )
        )

        val parked = first as WorkflowExecutionResult.SuspendedForUser
        assertEquals(
            "the parent parks with the child's own wait",
            WorkflowUserWait.Approval("wait"),
            parked.wait
        )
        assertTrue(
            "the composite node must stay uncommitted while the child waits",
            parked.outcomes.none { it.nodeId == "sub" }
        )
        assertEquals("the child node before the wait ran once", 1, tool.calls)

        val resumed = service.execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        )

        val completed = resumed as WorkflowExecutionResult.Completed
        assertEquals(
            "a nested resume must not replay the committed child node",
            1,
            tool.calls
        )
        assertTrue(
            "the parent output must carry the completed child result",
            completed.outputs.containsKey("result")
        )
        assertEquals(WorkflowNodeState.SUCCEEDED, completed.outcomes.single { it.nodeId == "sub" }.state)
    }

    @Test
    fun aToolApprovalInsideASubworkflowParksTheParentAndResumesOnce() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = WorkflowDefinition(
            definitionId = "child-approval-wf",
            version = 1,
            displayName = "child with a tool that needs approval",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(trigger("child-trigger"), tool("child-gather"), output("child-out")),
            edges = listOf(
                WorkflowEdge("child-trigger", "child-gather"),
                WorkflowEdge("child-gather", "child-out")
            )
        )
        val publishedChild = publish(store, child)
        val parent = WorkflowDefinition(
            definitionId = "parent-nested-approval",
            version = 1,
            displayName = "parent",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-approval-wf",
                    subworkflowVersion = publishedChild.version
                ),
                output()
            ),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done"))
        )
        val publishedParent = publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("child-approval-wf", 1L) to publishedChild
            )
        )

        var invocations = 0
        val gateway = WorkflowToolGateway {
            invocations += 1
            if (invocations == 1) {
                WorkflowToolOutcome.WaitingApproval(
                    invocationId = "inv-child-1",
                    callHash = "hash-child-1",
                    approval = AgentApproval(
                        approvalId = "appr-1",
                        invocationId = "inv-child-1",
                        principal = "agent",
                        argumentsHash = "args-1",
                        toolSchemaHash = "schema-1",
                        resourceFingerprint = "fp-1",
                        policyVersion = 1,
                        runEpoch = 1,
                        expiresAtMillis = 10_000L,
                        status = AgentApprovalStatus.PENDING,
                        createdAtMillis = 1_000L
                    )
                )
            } else {
                WorkflowToolOutcome.Succeeded(
                    AgentJsonValue.Obj(listOf("content" to AgentJsonValue.Str("hello")))
                )
            }
        }
        val service = WorkflowExecutionService(
            definitions = store,
            checkpointRoot = folder.root,
            toolGateway = gateway
        )

        val first = service.execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                runId = "run-nested-approval",
                toolScope = scope
            )
        )

        val parked = first as WorkflowExecutionResult.Suspended
        assertEquals("inv-child-1", parked.suspension.invocationId)
        assertEquals("hash-child-1", parked.suspension.callHash)
        assertTrue(
            "the composite node must stay uncommitted while the child approval is pending",
            parked.outcomes.none { it.nodeId == "sub" }
        )

        val resumed = service.execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                toolScope = scope,
                runId = parked.runId
            )
        )

        val completed = resumed as WorkflowExecutionResult.Completed
        assertEquals("the child tool runs once before the park and once on the approved resume", 2, invocations)
        assertEquals(WorkflowNodeState.SUCCEEDED, completed.outcomes.single { it.nodeId == "sub" }.state)
        assertTrue(completed.outputs.containsKey("result"))
    }

    @Test
    fun aChildSubworkflowConsumesTheParentsSharedToolBudget() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = WorkflowDefinition(
            definitionId = "child-budget-wf",
            version = 1,
            displayName = "child with two tools",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger("child-trigger"),
                tool("child-first"),
                tool("child-second", toolKey = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT, arguments = """{"query":"x"}"""),
                output("child-out")
            ),
            edges = listOf(
                WorkflowEdge("child-trigger", "child-first"),
                WorkflowEdge("child-first", "child-second"),
                WorkflowEdge("child-second", "child-out")
            )
        )
        val publishedChild = publish(store, child)
        val parent = WorkflowDefinition(
            definitionId = "parent-budget-wf",
            version = 1,
            displayName = "parent with a single tool call of budget",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-budget-wf",
                    subworkflowVersion = publishedChild.version
                ),
                output()
            ),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done")),
            budget = WorkflowBudget(maxToolCalls = 1)
        )
        val publishedParent = publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("child-budget-wf", 1L) to publishedChild
            )
        )

        val readTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val searchTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT))
        val result = service(store, readTool, searchTool).execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                runId = "run-shared-budget",
                toolScope = scope
            )
        )

        val failed = result as WorkflowExecutionResult.Failed
        assertEquals(
            "the child must stop on the parent's exhausted tool budget (got ${failed.errorCode}: ${failed.safeMessage})",
            WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
            failed.errorCode
        )
        assertEquals("the first child tool consumed the only parent slot", 1, readTool.calls)
        assertEquals("the second child tool never ran", 0, searchTool.calls)
    }

    @Test
    fun aUserWaitTwoLevelsDeepParksTheParentAndResumesThroughBothLayers() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val inner = WorkflowDefinition(
            definitionId = "deep-inner-wf",
            version = 1,
            displayName = "inner with the wait",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger("inner-trigger"),
                tool("inner-gather"),
                waitNode("deep-wait"),
                output("inner-out")
            ),
            edges = listOf(
                WorkflowEdge("inner-trigger", "inner-gather"),
                WorkflowEdge("inner-gather", "deep-wait"),
                WorkflowEdge("deep-wait", "inner-out")
            )
        )
        val publishedInner = publish(store, inner)
        val middle = WorkflowDefinition(
            definitionId = "deep-middle-wf",
            version = 1,
            displayName = "middle with a pinned subworkflow",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger("middle-trigger"),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "deep-inner-wf",
                    subworkflowVersion = publishedInner.version
                ),
                output("middle-out")
            ),
            edges = listOf(
                WorkflowEdge("middle-trigger", "sub"),
                WorkflowEdge("sub", "middle-out")
            )
        )
        val publishedMiddle = publish(
            store,
            middle,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("deep-inner-wf", 1L) to publishedInner
            )
        )
        val parent = WorkflowDefinition(
            definitionId = "deep-foreach-parent",
            version = 1,
            displayName = "foreach over a subworkflow that waits",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "items",
                    WorkflowNodeType.LOGIC,
                    configJson = """{"operation":"SET","value":[1]}"""
                ),
                WorkflowNode(
                    "each",
                    WorkflowNodeType.FOREACH,
                    configJson = """{"subworkflowDefinitionId":"deep-middle-wf",""" +
                        """"subworkflowVersion":1,"itemsPath":"$","maxItems":10}"""
                ),
                output("done")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "items"),
                WorkflowEdge("items", "each"),
                WorkflowEdge("each", "done")
            )
        )
        publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("deep-middle-wf", 1L) to publishedMiddle,
                WorkflowDefinitionValidator.SubworkflowRef("deep-inner-wf", 1L) to publishedInner
            )
        )

        val innerTool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val first = service(store, innerTool).execute(
            WorkflowExecutionRequest("deep-foreach-parent", 1, runId = "run-deep-wait", toolScope = scope)
        )

        val parked = first as WorkflowExecutionResult.SuspendedForUser
        assertEquals(
            "the decision must surface from the grandchild's wait",
            WorkflowUserWait.Approval("deep-wait"),
            parked.wait
        )
        assertTrue(
            "neither the foreach nor the sub node may commit while the grandchild waits",
            parked.outcomes.none { it.nodeId == "each" || it.nodeId == "sub" }
        )
        assertEquals("the grandchild tool before its wait ran exactly once", 1, innerTool.calls)

        val resumed = service(store, innerTool).execute(
            WorkflowExecutionRequest(
                "deep-foreach-parent",
                1,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        )

        val completed = resumed as WorkflowExecutionResult.Completed
        assertEquals(WorkflowNodeState.SUCCEEDED, completed.outcomes.single { it.nodeId == "each" }.state)
        val items = completed.outputs.getValue("result") as AgentJsonValue.Arr
        assertEquals("one item, resumed once through both layers", 1, items.items.size)
        assertEquals("the grandchild tool must not replay on the two-level resume", 1, innerTool.calls)
    }

    @Test
    fun aDuplicateNestedWakeIsIdempotentAndNeverReplaysCommittedNodes() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.root)
        val child = WorkflowDefinition(
            definitionId = "child-wait-wf",
            version = 1,
            displayName = "child with a wait",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(trigger(), tool(), waitNode(), output()),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "wait"),
                WorkflowEdge("wait", "done")
            )
        )
        val publishedChild = publish(store, child)
        val parent = WorkflowDefinition(
            definitionId = "parent-duplicate-wake",
            version = 1,
            displayName = "parent",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                trigger(),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = "child-wait-wf",
                    subworkflowVersion = publishedChild.version
                ),
                output()
            ),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done"))
        )
        val publishedParent = publish(
            store,
            parent,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("child-wait-wf", 1L) to publishedChild
            )
        )
        val tool = RecordingToolExecutor(descriptors.getValue(AgentV1ToolSpecs.WORKSPACE_READ_TEXT))
        val service = service(store, tool)

        val parked = service.execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                runId = "run-duplicate-wake",
                toolScope = scope
            )
        ) as WorkflowExecutionResult.SuspendedForUser

        val resumed = service.execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        ) as WorkflowExecutionResult.Completed
        assertEquals("the first wake consumed the decision", 1, tool.calls)

        val duplicate = this@WorkflowExecutionServiceTest.service(store, tool).execute(
            WorkflowExecutionRequest(
                publishedParent.definitionId,
                publishedParent.version,
                toolScope = scope,
                runId = parked.runId,
                userDecision = WorkflowUserDecisions.APPROVE
            )
        )

        val replayed = duplicate as WorkflowExecutionResult.Completed
        assertEquals(
            "a duplicate wake must not run the committed child tool again",
            1,
            tool.calls
        )
        assertEquals(
            "a duplicate wake must settle on the same outputs",
            resumed.outputs,
            replayed.outputs
        )
    }

    // ------------------------------------------------------------------ fake executor

    private class RecordingAgentTaskRunner(
        private val outcome: WorkflowAgentTaskOutcome
    ) : WorkflowAgentTaskRunner {
        val requests = mutableListOf<WorkflowAgentTaskRequest>()

        override suspend fun run(request: WorkflowAgentTaskRequest): WorkflowAgentTaskOutcome {
            requests += request
            return outcome
        }
    }

    private class RecordingToolExecutor(
        override val descriptor: AgentToolDescriptor,
        private val result: AgentToolResult = AgentToolResult(
            status = AgentToolResultStatus.SUCCEEDED,
            summary = "ok",
            dataJson = """{"content":"hello"}"""
        )
    ) : AgentToolExecutor {
        val commands = mutableListOf<FrozenToolCommand>()
        val contexts = mutableListOf<AgentToolContext>()
        var calls: Int = 0

        override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult {
            calls += 1
            commands += command
            contexts += context
            return result
        }
    }
}
