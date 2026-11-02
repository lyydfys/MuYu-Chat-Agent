package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRequest
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.mca.agent.tools.WorkflowToolGatewayFactory
import com.muyuchat.mca.agent.tools.WorkflowToolRunState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S1 guard: an owner-executed workflow frame rejects `AGENT_TASK` before any side effect.
 *
 * The production child-task adapter would create a second task/run and queue it behind the parent
 * that is waiting for it (the owner admits one active run). This suite proves the owner frame
 * returns a typed `WF_FRAME_NOT_SUPPORTED` rejection before the checkpoint is loaded, before the
 * run-scoped tool gateway is created and before the child runner can issue CreateAgentTask/StartAgentRun.
 *
 * The guard is temporary; same-root AGENT_TASK frame execution (S3, ADR 0007 decision 4) replaces it.
 */
class WorkflowFramePreflightTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun ownerFrameWithAgentTaskRejectsBeforeToolOrChildRun() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-frame-guard",
            version = 1,
            displayName = "frame guard",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"workspace.read_text","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode(
                    "agent",
                    WorkflowNodeType.AGENT_TASK,
                    configJson = """{"goal":"summarize the brief"}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "agent"),
                WorkflowEdge("agent", "done")
            )
        )
        publish(store, definition)
        val run = run(definition.definitionId, definition.version)
        val task = task()
        val checkpoints = folder.newFolder("checkpoints")
        val gatewayFactory = CountingGatewayFactory()
        val runner = CountingAgentTaskRunner()
        var legacyGatewayCalls = 0
        val service = WorkflowExecutionService(
            definitions = store,
            checkpointRoot = checkpoints,
            toolGateway = WorkflowToolGateway {
                legacyGatewayCalls += 1
                WorkflowToolOutcome.Failed("WF_TOOL_NOT_EXECUTED", "the legacy gateway must not run")
            },
            agentTaskRunner = runner,
            runScopedToolGatewayFactory = gatewayFactory
        )

        val result = service.executeFrame(run, task, null, WorkflowToolRunState(run))

        assertTrue("expected a frame rejection, got $result", result is WorkflowFrameOutcome.Rejected)
        val rejected = result as WorkflowFrameOutcome.Rejected
        assertEquals(WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED, rejected.errorCode)
        assertTrue(
            "the rejection must name the unsupported node type: ${rejected.safeMessage}",
            rejected.safeMessage.contains("AGENT_TASK")
        )
        assertEquals("no run-scoped tool gateway may be created", 0, gatewayFactory.createCalls)
        assertEquals("no tool may be invoked", 0, gatewayFactory.invokeCalls)
        assertEquals("no child CreateAgentTask/StartAgentRun may be issued", 0, runner.calls)
        assertEquals("the legacy gateway must not be consulted", 0, legacyGatewayCalls)
        assertFalse("no checkpoint file may be written", checkpointFile(checkpoints, task.taskId).isFile)
    }

    @Test
    fun referencedSubworkflowAgentTaskAlsoRejectsTheOwnerFrame() = runBlocking {
        val store = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val child = WorkflowDefinition(
            definitionId = "wf-frame-child",
            version = 1,
            displayName = "frame child",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("child-trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "child-agent",
                    WorkflowNodeType.AGENT_TASK,
                    configJson = """{"goal":"summarize the child input"}"""
                ),
                WorkflowNode(
                    "child-done",
                    WorkflowNodeType.OUTPUT,
                    configJson = """{"port":"result"}"""
                )
            ),
            edges = listOf(
                WorkflowEdge("child-trigger", "child-agent"),
                WorkflowEdge("child-agent", "child-done")
            )
        )
        publish(store, child)
        val root = WorkflowDefinition(
            definitionId = "wf-frame-root",
            version = 1,
            displayName = "frame root",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"workspace.read_text","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode(
                    "child",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = child.definitionId,
                    subworkflowVersion = child.version
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "child"),
                WorkflowEdge("child", "done")
            )
        )
        publish(
            store,
            root,
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef(child.definitionId, child.version) to child
            )
        )
        val run = run(root.definitionId, root.version)
        val task = task()
        val checkpoints = folder.newFolder("checkpoints")
        val gatewayFactory = CountingGatewayFactory()
        val runner = CountingAgentTaskRunner()
        val service = WorkflowExecutionService(
            definitions = store,
            checkpointRoot = checkpoints,
            toolGateway = WorkflowToolGateway {
                WorkflowToolOutcome.Failed("WF_TOOL_NOT_EXECUTED", "the legacy gateway must not run")
            },
            agentTaskRunner = runner,
            runScopedToolGatewayFactory = gatewayFactory
        )

        val result = service.executeFrame(run, task, null, WorkflowToolRunState(run))

        assertTrue("expected a frame rejection, got $result", result is WorkflowFrameOutcome.Rejected)
        assertEquals(
            WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED,
            (result as WorkflowFrameOutcome.Rejected).errorCode
        )
        assertEquals("no run-scoped tool gateway may be created", 0, gatewayFactory.createCalls)
        assertEquals("no tool may be invoked", 0, gatewayFactory.invokeCalls)
        assertEquals("no child CreateAgentTask/StartAgentRun may be issued", 0, runner.calls)
        assertFalse("no checkpoint file may be written", checkpointFile(checkpoints, task.taskId).isFile)
    }

    private fun publish(
        store: AgentWorkflowDefinitionStore,
        definition: WorkflowDefinition,
        referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition> =
            emptyMap()
    ): WorkflowDefinition {
        store.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(store.draft(definition.definitionId))
        return store.publish(definition.definitionId, draft.revision, 2_000L, referencedDefinitions)
    }

    private fun checkpointFile(checkpoints: File, taskId: String): File =
        File(
            checkpoints,
            "workflow-checkpoints/" + WorkflowCheckpointKeys.taskRoot(taskId) + ".json"
        )

    private fun run(definitionId: String, version: Long): AgentRun {
        val now = 1_000L
        return AgentRun(
            runId = "run-frame-guard",
            taskId = "task-frame-guard",
            runOrdinal = 1,
            state = AgentRunState.RUNNING,
            reason = AgentPauseReason.NONE,
            epoch = 1,
            modelBinding = com.muyuchat.core.agent.contract.AgentModelBinding(
                providerId = "workflow",
                modelId = "workflow",
                runtime = "none",
                assetFingerprint = "workflow",
                protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.NONE,
                modelDisplayName = "Workflow"
            ),
            policy = AgentRunPolicy(workflow = AgentWorkflowRunSpec(definitionId, version)),
            budget = com.muyuchat.core.agent.contract.AgentBudget(),
            spentBudget = AgentSpentBudget(),
            lastSequence = 0,
            revision = 1,
            createdAtMillis = now,
            updatedAtMillis = now
        )
    }

    private fun task(): AgentTask {
        val now = 1_000L
        return AgentTask(
            taskId = "task-frame-guard",
            workspaceId = "workspace-frame-guard",
            goal = "frame guard",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now,
            displayStatus = AgentDisplayStatus.RUNNING,
            lastRunId = "run-frame-guard",
            revision = 1
        )
    }

    private class CountingGatewayFactory : WorkflowToolGatewayFactory {
        var createCalls = 0
        var invokeCalls = 0

        override suspend fun create(
            run: AgentRun,
            task: AgentTask,
            state: WorkflowToolRunState,
            resume: AgentLoopResume?
        ): WorkflowToolGateway {
            createCalls += 1
            return WorkflowToolGateway {
                invokeCalls += 1
                WorkflowToolOutcome.Failed("WF_TOOL_NOT_EXECUTED", "the S1 guard must reject first")
            }
        }
    }

    private class CountingAgentTaskRunner : WorkflowAgentTaskRunner {
        var calls = 0

        override suspend fun run(request: WorkflowAgentTaskRequest): WorkflowAgentTaskOutcome {
            calls += 1
            return WorkflowAgentTaskOutcome.Failed(
                "WF_AGENT_TASK_FAILED",
                "the S1 guard must reject before a child run is requested"
            )
        }
    }
}
