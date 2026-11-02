package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
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
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRequest
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRunner
import com.muyuchat.mca.agent.tools.WorkflowToolRunState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S3a/S3b: an AGENT_TASK executes as a same-root frame on the one model loop.
 *
 * The node must not create a child task/run; the loop receives the parent run/task identities and
 * the run policy without a workflow pin. A read-only tool/memory policy is allowed and reaches the
 * loop unchanged; the loop side refuses any write/network catalog before the first model request
 * (see `AgentPromptedLoopCoordinatorTest`). Nested approval checkpointing is still not wired.
 */
class WorkflowSameRootAgentTaskTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a prompt-only agent task runs on the parent run without a child task`() = runBlocking {
        val store = definitionStore()
        val definition = definition()
        publish(store, definition)
        val run = run(definition, enabledToolKeys = emptySet())
        val task = task()
        val spent = AgentSpentBudget(modelAttempts = 1, generatedTokens = 7)
        val frame = RecordingFrameExecutor(
            AgentLoopResult.Completed(
                run = run,
                transcript = transcript(),
                finalText = "same-root answer",
                artifactIds = emptyList()
            ),
            spent = spent
        )
        val child = CountingChildRunner()
        val service = service(store, frame, child)
        val state = WorkflowToolRunState(run)

        val result = service.executeFrame(run, task, null, state)

        val completed = result as WorkflowFrameOutcome.Completed
        assertEquals(AgentJsonValue.Str("same-root answer"), completed.outputs["result"])
        assertEquals("the frame loop must run exactly once", 1, frame.calls)
        assertEquals("the parent run id must be preserved", run.runId, frame.run?.runId)
        assertEquals("the parent task id must be preserved", task.taskId, frame.task?.taskId)
        assertEquals("the node goal must reach the loop", "read the brief", frame.task?.goal)
        assertEquals(
            "the frame policy must not pin the workflow again",
            null,
            frame.run?.policy?.workflow
        )
        assertEquals(
            "the prompt-only policy must stay tool-free",
            emptySet<String>(),
            frame.run?.policy?.enabledToolKeys
        )
        assertEquals("a prompt-only frame must not consume a workflow tool resume", null, frame.resume)
        assertEquals(
            "the frame's model spend must reach the parent run state",
            spent,
            state.run.spentBudget
        )
        assertEquals("no child CreateAgentTask/StartAgentRun may be issued", 0, child.calls)
    }

    @Test
    fun `a read-only tool plan runs in the parent frame and keeps its policy`() = runBlocking {
        val store = definitionStore()
        val definition = definition()
        publish(store, definition)
        val run = run(definition, enabledToolKeys = setOf("workspace.read_text"))
        val frame = RecordingFrameExecutor(
            AgentLoopResult.Completed(run, transcript(), "read-only answer", emptyList())
        )
        val child = CountingChildRunner()
        val service = service(store, frame, child)

        val result = service.executeFrame(run, task(), null, WorkflowToolRunState(run))

        val completed = result as WorkflowFrameOutcome.Completed
        assertEquals(AgentJsonValue.Str("read-only answer"), completed.outputs["result"])
        assertEquals("the frame loop must run exactly once", 1, frame.calls)
        assertEquals(
            "a read-only tool policy must reach the same-root loop unchanged",
            setOf("workspace.read_text"),
            frame.run?.policy?.enabledToolKeys
        )
        assertEquals("no child task/run may be created", 0, child.calls)
    }

    @Test
    fun `an agent frame that asks for the user is a typed failure, not a success`() = runBlocking {
        val store = definitionStore()
        val definition = definition()
        publish(store, definition)
        val run = run(definition, enabledToolKeys = emptySet())
        val frame = RecordingFrameExecutor(
            AgentLoopResult.WaitingUser(run, transcript(), "Which file?", emptyList())
        )
        val child = CountingChildRunner()
        val service = service(store, frame, child)

        val result = service.executeFrame(run, task(), null, WorkflowToolRunState(run))

        val failed = result as WorkflowFrameOutcome.Failed
        assertEquals(WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED, failed.errorCode)
        assertEquals("no child task/run may be created", 0, child.calls)
    }

    private fun definition(): WorkflowDefinition = WorkflowDefinition(
        definitionId = "wf-same-root-agent",
        version = 1,
        displayName = "same-root agent",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(
            WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
            WorkflowNode(
                "agent",
                WorkflowNodeType.AGENT_TASK,
                configJson = """{"goal":"read the brief"}"""
            ),
            WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
        ),
        edges = listOf(
            WorkflowEdge("trigger", "agent"),
            WorkflowEdge("agent", "done")
        )
    )

    private fun definitionStore(): FileAgentWorkflowDefinitionStore =
        FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))

    private fun publish(
        store: AgentWorkflowDefinitionStore,
        definition: WorkflowDefinition
    ): WorkflowDefinition {
        store.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(store.draft(definition.definitionId))
        return store.publish(definition.definitionId, draft.revision, 2_000L, emptyMap())
    }

    private fun service(
        store: AgentWorkflowDefinitionStore,
        frame: WorkflowAgentFrameExecutor,
        child: WorkflowAgentTaskRunner
    ): WorkflowExecutionService = WorkflowExecutionService(
        definitions = store,
        checkpointRoot = folder.newFolder("checkpoints"),
        toolGateway = WorkflowToolGateway {
            WorkflowToolOutcome.Failed("WF_TOOL_NOT_EXECUTED", "this workflow has no tool node")
        },
        agentTaskRunner = child,
        agentFrameExecutor = frame
    )

    private fun run(definition: WorkflowDefinition, enabledToolKeys: Set<String>): AgentRun {
        val now = 1_000L
        return AgentRun(
            runId = "run-same-root",
            taskId = "task-same-root",
            runOrdinal = 1,
            state = AgentRunState.RUNNING,
            reason = AgentPauseReason.NONE,
            epoch = 1,
            modelBinding = AgentModelBinding(
                providerId = "local",
                modelId = "qwen-local",
                runtime = "llama.cpp",
                assetFingerprint = "a".repeat(64),
                protocolMode = AgentProtocolMode.PROMPTED_V1,
                modelDisplayName = "Qwen Local"
            ),
            policy = AgentRunPolicy(
                workflow = AgentWorkflowRunSpec(definition.definitionId, definition.version),
                enabledToolKeys = enabledToolKeys
            ),
            budget = AgentBudget(),
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
            taskId = "task-same-root",
            workspaceId = "workspace-same-root",
            goal = "workflow task",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now,
            displayStatus = AgentDisplayStatus.RUNNING,
            lastRunId = "run-same-root",
            revision = 1
        )
    }

    private fun transcript(): AgentLoopTranscript =
        AgentLoopTranscript(emptyList(), 0, 0)

    private class RecordingFrameExecutor(
        private val result: AgentLoopResult,
        private val spent: AgentSpentBudget? = null
    ) : WorkflowAgentFrameExecutor {
        var calls = 0
        var run: AgentRun? = null
        var task: AgentTask? = null
        var resume: AgentLoopResume? = null

        override suspend fun execute(
            run: AgentRun,
            task: AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            calls += 1
            this.run = run
            this.task = task
            this.resume = resume
            return if (spent != null && result is AgentLoopResult.Completed) {
                result.copy(run = run.copy(spentBudget = spent))
            } else {
                result
            }
        }
    }

    private class CountingChildRunner : WorkflowAgentTaskRunner {
        var calls = 0

        override suspend fun run(request: WorkflowAgentTaskRequest): WorkflowAgentTaskOutcome {
            calls += 1
            return WorkflowAgentTaskOutcome.Failed(
                "WF_AGENT_TASK_FAILED",
                "the same-root frame must not request a child run"
            )
        }
    }
}
