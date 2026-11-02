package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.contract.workflow.WorkflowBudget
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowFailurePolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.mca.agent.workflow.FileAgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.FileWorkflowEntryReceiptStore
import com.muyuchat.mca.agent.workflow.FileWorkflowRunHistoryStore
import com.muyuchat.mca.agent.workflow.RegisteredWorkflowToolGateway
import com.muyuchat.mca.agent.workflow.WorkflowEntryReceipt
import com.muyuchat.mca.agent.workflow.WorkflowEntryService
import com.muyuchat.mca.agent.workflow.WorkflowEntryStatus
import com.muyuchat.mca.agent.workflow.WorkflowExecutionService
import com.muyuchat.mca.agent.workflow.WorkflowManualRunCommand
import com.muyuchat.mca.agent.workflow.WorkflowRunHistoryEntry
import com.muyuchat.mca.agent.workflow.WorkflowRunHistoryStore
import com.muyuchat.mca.agent.workflow.WorkflowToolScope
import com.muyuchat.mca.agent.workflow.WorkflowTriggerFacade
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N23 integration: production entry chain over the real definition store, real gateway bridge,
 * catalog-backed tool executor and file checkpoint/history/receipt stores.
 *
 * This is JVM-only evidence; Android owner/model/device layers remain NOT_RUN.
 */
class WorkflowEntryScenarioTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun definition() = WorkflowDefinition(
        definitionId = "wf-scenario",
        version = 1,
        displayName = "scenario",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(
            WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
            WorkflowNode(
                "gather",
                WorkflowNodeType.TOOL,
                configJson = """{"toolKey":"${AgentV1ToolSpecs.WORKSPACE_READ_TEXT}","arguments":{"path":"inputs/brief.md"}}"""
            ),
            WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
        ),
        edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done")),
        failurePolicy = WorkflowFailurePolicy.FAIL_FAST,
        budget = WorkflowBudget()
    )

    @Test
    fun `a manual run publishes fixed version executes once and replays its receipt`() = runBlocking {
        val port = FakeAgentWorkspaceFilePort()
        port.putText("inputs/brief.md", "hello workflow")
        val root = folder.root
        val definitions = FileAgentWorkflowDefinitionStore(File(root, "definitions"))
        val draft = definitions.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)
        definitions.publish("wf-scenario", draft.revision, nowMillis = 2_000L)

        val catalog = extendedAgentToolCatalog(port)
        val execution = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = File(root, "checkpoints"),
            toolGateway = RegisteredWorkflowToolGateway(CatalogWorkflowToolRegistry(catalog))
        )
        val history: WorkflowRunHistoryStore = FileWorkflowRunHistoryStore(File(root, "runs"))
        val entry = WorkflowEntryService(execution::execute, history, clock = { 10_000L })
        val facade = WorkflowTriggerFacade(
            entry = entry,
            receipts = FileWorkflowEntryReceiptStore(File(root, "receipts")),
            clock = { 20_000L }
        )

        val first = facade.runManual(
            WorkflowManualRunCommand(
                commandId = "cmd-1",
                definitionId = "wf-scenario",
                version = 1,
                toolScope = WorkflowToolScope(taskId = "task-1", workspaceId = "workspace-1")
            )
        )
        assertEquals("${first.errorCode}: ${first.safeMessage}", WorkflowEntryStatus.COMPLETED, first.status)
        assertNotNull(first.runId)
        assertTrue(first.outputsJson.orEmpty().contains("hello workflow"))
        assertEquals(1, port.readCalls)

        val replay = facade.runManual(
            WorkflowManualRunCommand(
                commandId = "cmd-1",
                definitionId = "wf-scenario",
                version = 1,
                toolScope = WorkflowToolScope(taskId = "task-1", workspaceId = "workspace-1")
            )
        )
        assertEquals(first.runId, replay.runId)
        assertEquals(true, replay.idempotentReplay)
        assertEquals("a replay must not execute the tool again", 1, port.readCalls)

        val rows: List<WorkflowRunHistoryEntry> = history.list(10)
        assertEquals(1, rows.size)
        assertEquals(WorkflowEntryStatus.COMPLETED.name, rows.single().status.name)
        assertEquals(first.runId, rows.single().runId)
    }
}
