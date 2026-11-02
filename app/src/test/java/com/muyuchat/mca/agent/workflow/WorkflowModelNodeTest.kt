package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentModelUsage
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.mca.agent.tools.WorkflowToolRunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N18: a MODEL node performs one bounded completion through the shared AgentModelPort. */
class WorkflowModelNodeTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun modelNodeSendsTheRunBindingAndReturnsBoundedText() = runBlocking {
        val port = FakeModelPort()
        val (run, task, service) = fixture(port)
        val state = WorkflowToolRunState(run)

        val result = service.executeFrame(run, task, null, state)

        assertTrue("expected completion, got $result", result is WorkflowFrameOutcome.Completed)
        assertEquals(
            com.muyuchat.core.agent.runtime.AgentJsonValue.Str("answer"),
            (result as WorkflowFrameOutcome.Completed).outputs["result"]
        )
        val request = port.requests.single()
        assertEquals(run.modelBinding, request.binding)
        assertEquals(128, request.maxOutputTokens)
        assertTrue(request.tools.isEmpty())
        assertTrue(request.messages.single().content.contains("Summarize the input"))
        assertEquals(1, state.run.spentBudget.modelAttempts)
        assertEquals(7, state.run.spentBudget.generatedTokens)
    }

    @Test
    fun aModelFailureIsTypedAndNeverBecomesNodeSuccess() = runBlocking {
        val port = FakeModelPort(
            result = AgentModelResult.Failure(
                AgentError(
                    code = "MODEL_PROTOCOL_ERROR",
                    category = AgentErrorCategory.MODEL,
                    retryability = AgentRetryability.AFTER_USER_ACTION,
                    safeMessage = "bad response",
                    correlationId = "run-model"
                )
            )
        )
        val (run, task, service) = fixture(port)
        val state = WorkflowToolRunState(run)

        val result = service.executeFrame(run, task, null, state)

        assertTrue("expected failure, got $result", result is WorkflowFrameOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.MODEL_FAILED,
            (result as WorkflowFrameOutcome.Failed).errorCode
        )
        assertEquals(1, state.run.spentBudget.modelAttempts)
    }

    @Test
    fun aWorkflowWithoutARunnableBindingFailsClosedBeforeCallingThePort() = runBlocking {
        val port = FakeModelPort()
        val (run, task, service) = fixture(
            port,
            binding = AgentModelBinding(
                providerId = "workflow",
                modelId = "workflow",
                runtime = "none",
                assetFingerprint = "workflow",
                protocolMode = AgentProtocolMode.NONE,
                modelDisplayName = "Workflow"
            )
        )

        val result = service.executeFrame(run, task, null, WorkflowToolRunState(run))

        assertTrue("expected rejection, got $result", result is WorkflowFrameOutcome.Failed)
        assertEquals(
            WorkflowExecutionErrorCodes.MODEL_BINDING_MISSING,
            (result as WorkflowFrameOutcome.Failed).errorCode
        )
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun aSuccessorRunOfTheSameTaskResumesTheTaskCheckpointInsteadOfCallingTheModelAgain() = runBlocking {
        val port = FakeModelPort()
        val (run, task, service) = fixture(port)

        val first = service.executeFrame(run, task, null, WorkflowToolRunState(run))
        assertTrue("expected completion, got $first", first is WorkflowFrameOutcome.Completed)
        assertEquals(1, port.requests.size)

        val successor = run.copy(runId = "run-model-2", runOrdinal = 2)
        val successorState = WorkflowToolRunState(successor)
        val second = service.executeFrame(successor, task, null, successorState)

        assertTrue("expected completion, got $second", second is WorkflowFrameOutcome.Completed)
        assertEquals(
            (first as WorkflowFrameOutcome.Completed).outputs,
            (second as WorkflowFrameOutcome.Completed).outputs
        )
        assertEquals("the model must not run twice for the same task", 1, port.requests.size)
        assertEquals(0, successorState.run.spentBudget.modelAttempts)
    }

    @Test
    fun cancellingTheModelNodeCallsTheExactStop() = runBlocking {
        val port = FakeModelPort(throwCancellation = true)
        val (run, task, service) = fixture(port)
        var cancelled = false

        try {
            service.executeFrame(run, task, null, WorkflowToolRunState(run))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must propagate", cancelled)
        assertEquals(1, port.stopCalls)
    }

    private suspend fun fixture(
        port: FakeModelPort,
        binding: AgentModelBinding = AgentModelBinding(
            providerId = "test",
            modelId = "test-model",
            runtime = "unit",
            assetFingerprint = "fp",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Test"
        )
    ): Triple<AgentRun, AgentTask, WorkflowExecutionService> {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-model",
            version = 1,
            displayName = "model",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "model",
                    WorkflowNodeType.MODEL,
                    configJson = """{"prompt":"Summarize the input","maxOutputTokens":128}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "model"),
                WorkflowEdge("model", "done")
            )
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)
        val run = run(published.definitionId, published.version, binding)
        val task = task()
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("checkpoints"),
            toolGateway = WorkflowToolGateway {
                WorkflowToolOutcome.Failed(
                    WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                    "This graph has no tool node."
                )
            },
            modelPort = port
        )
        return Triple(run, task, service)
    }

    private fun run(
        definitionId: String,
        version: Long,
        binding: AgentModelBinding
    ): AgentRun {
        val now = 1_000L
        return AgentRun(
            runId = "run-model",
            taskId = "task-model",
            runOrdinal = 1,
            state = AgentRunState.RUNNING,
            reason = AgentPauseReason.NONE,
            epoch = 1,
            modelBinding = binding,
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
            taskId = "task-model",
            workspaceId = "workspace-model",
            goal = "model",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now,
            displayStatus = AgentDisplayStatus.RUNNING,
            lastRunId = "run-model",
            revision = 1
        )
    }

    private class FakeModelPort(
        private val result: AgentModelResult = AgentModelResult.Success(
            visibleText = "answer",
            usage = AgentModelUsage(outputTokens = 7, estimated = false)
        ),
        private val throwCancellation: Boolean = false
    ) : AgentModelPort {
        val requests = mutableListOf<AgentModelRequest>()
        var stopCalls = 0

        override suspend fun infer(
            request: AgentModelRequest,
            progress: AgentModelProgressSink
        ): AgentModelResult {
            requests += request
            if (throwCancellation) throw CancellationException("cancelled")
            return result
        }

        override suspend fun requestStop(attempt: com.muyuchat.core.agent.contract.AgentModelAttemptRef): AgentStopDispatch {
            stopCalls += 1
            return AgentStopDispatch(true)
        }
    }
}
