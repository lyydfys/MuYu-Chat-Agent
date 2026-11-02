package com.muyuchat.mca.agent.workflow

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
import com.muyuchat.core.agent.contract.workflow.WorkflowBudget
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
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.mca.agent.tools.WorkflowToolGatewayFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N19: an unprovable workflow tool effect parks the real run in NEEDS_REVIEW. */
class AgentWorkflowGraphRunExecutorTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun anUnknownToolResultIsReturnedAsNeedsReview() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-unknown",
            version = 1,
            displayName = "unknown effect",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"workspace.read_text","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "done")
            ),
            budget = WorkflowBudget()
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)

        val unknownGateway = WorkflowToolGateway {
            WorkflowToolOutcome.Failed(
                WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
                "The effect is unknown."
            )
        }
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("checkpoints"),
            toolGateway = unknownGateway,
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: AgentTask,
                    state: com.muyuchat.mca.agent.tools.WorkflowToolRunState,
                    resume: com.muyuchat.core.agent.runtime.run.AgentLoopResume?
                ): WorkflowToolGateway = unknownGateway
            }
        )
        val run = run(published.definitionId, published.version)
        val result = AgentWorkflowGraphRunExecutor(service).execute(
            run = run,
            task = task(),
            resume = null,
            progress = AgentModelProgressSink { }
        )

        assertTrue("expected NEEDS_REVIEW, got $result", result is AgentLoopResult.NeedsReview)
        assertEquals(
            WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
            (result as AgentLoopResult.NeedsReview).error.code
        )
        assertTrue(result.error.possibleSideEffects)
    }

    @Test
    fun aWaitApprovalNodeBecomesARealUserParkBeforeAnyNodeExecutorRuns() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("wait-definitions"))
        val definition = WorkflowDefinition(
            definitionId = "wf-wait-park",
            version = 1,
            displayName = "wait park",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "wait",
                    WorkflowNodeType.WAIT_APPROVAL,
                    configJson = """{"prompt":"approve the next step"}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "wait"),
                WorkflowEdge("wait", "done")
            ),
            budget = WorkflowBudget()
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)

        val gatewayCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gateway = WorkflowToolGateway {
            gatewayCalls.incrementAndGet()
            WorkflowToolOutcome.Succeeded(
                com.muyuchat.core.agent.runtime.AgentJsonValue.Obj(
                    listOf("text" to com.muyuchat.core.agent.runtime.AgentJsonValue.Str("hello"))
                )
            )
        }
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("wait-checkpoints"),
            toolGateway = gateway,
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: AgentTask,
                    state: com.muyuchat.mca.agent.tools.WorkflowToolRunState,
                    resume: com.muyuchat.core.agent.runtime.run.AgentLoopResume?
                ): WorkflowToolGateway = gateway
            }
        )

        val result = AgentWorkflowGraphRunExecutor(service).execute(
            run = run(published.definitionId, published.version),
            task = task(),
            resume = null,
            progress = AgentModelProgressSink { }
        )

        val waiting = result as AgentLoopResult.WaitingUser
        assertTrue(
            "the question must name the waiting node: ${waiting.questionText}",
            waiting.questionText.contains("wait")
        )
        assertEquals(listOf("批准", "拒绝"), waiting.options)
        assertEquals("a wait node must not call the tool gateway", 0, gatewayCalls.get())
    }

    @Test
    fun aUserWaitInsideASubworkflowParksTheRealRunAndTheAnswerResumesTheChild() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("nested-wait-definitions"))
        val child = WorkflowDefinition(
            definitionId = "wf-nested-wait-child",
            version = 1,
            displayName = "child with a wait",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("child-trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "child-wait",
                    WorkflowNodeType.WAIT_APPROVAL,
                    configJson = """{"prompt":"approve the child step"}"""
                ),
                WorkflowNode("child-done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("child-trigger", "child-wait"),
                WorkflowEdge("child-wait", "child-done")
            ),
            budget = WorkflowBudget()
        )
        definitions.saveDraft(child, null, 1_000L)
        val childDraft = requireNotNull(definitions.draft(child.definitionId))
        val publishedChild = definitions.publish(child.definitionId, childDraft.revision, 2_000L)
        val parent = WorkflowDefinition(
            definitionId = "wf-nested-wait-parent",
            version = 1,
            displayName = "parent with a pinned child",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "sub",
                    WorkflowNodeType.SUBWORKFLOW,
                    subworkflowDefinitionId = publishedChild.definitionId,
                    subworkflowVersion = publishedChild.version
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done")),
            budget = WorkflowBudget()
        )
        definitions.saveDraft(parent, null, 3_000L)
        val parentDraft = requireNotNull(definitions.draft(parent.definitionId))
        val publishedParent = definitions.publish(
            parent.definitionId,
            parentDraft.revision,
            4_000L,
            mapOf(
                WorkflowDefinitionValidator.SubworkflowRef(
                    publishedChild.definitionId,
                    publishedChild.version
                ) to publishedChild
            )
        )

        val gatewayCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("nested-wait-checkpoints"),
            toolGateway = WorkflowToolGateway {
                gatewayCalls.incrementAndGet()
                WorkflowToolOutcome.Failed(
                    WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                    "The nested wait test must not call a tool."
                )
            }
        )
        val executor = AgentWorkflowGraphRunExecutor(service)
        val run = run(publishedParent.definitionId, publishedParent.version)

        val waiting = executor.execute(run, task(), null, AgentModelProgressSink { })

        val parked = waiting as AgentLoopResult.WaitingUser
        assertTrue(
            "the question must name the child's own wait node: ${parked.questionText}",
            parked.questionText.contains("child-wait")
        )
        assertEquals(listOf("批准", "拒绝"), parked.options)
        assertEquals("parking a nested wait must not call a tool", 0, gatewayCalls.get())

        val resumed = executor.execute(
            run,
            task(),
            AgentLoopResume(
                transcript = AgentLoopTranscript(emptyList(), 0, 0),
                userAnswer = "批准"
            ),
            AgentModelProgressSink { }
        )

        assertTrue("expected completion after the answer, got $resumed", resumed is AgentLoopResult.Completed)
    }

    @Test
    fun aFailedCompensationMarksTheRunErrorAsPossiblySideEffecting() = runBlocking {
        val definitions = FileAgentWorkflowDefinitionStore(folder.newFolder("definitions-comp"))
        val definition = WorkflowDefinition(
            definitionId = "wf-comp",
            version = 1,
            displayName = "compensation",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode(
                    "gather",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"workspace.read_text","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode(
                    "publish",
                    WorkflowNodeType.TOOL,
                    configJson = """{"toolKey":"workspace.publish","arguments":{"path":"inputs/brief.md"}}"""
                ),
                WorkflowNode(
                    "undo",
                    WorkflowNodeType.COMPENSATE,
                    configJson = """{"compensates":"gather","toolKey":"workspace.write_text"}"""
                ),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "publish"),
                WorkflowEdge("publish", "done")
            ),
            budget = WorkflowBudget()
        )
        definitions.saveDraft(definition, null, 1_000L)
        val draft = requireNotNull(definitions.draft(definition.definitionId))
        val published = definitions.publish(definition.definitionId, draft.revision, 2_000L)

        val gateway = WorkflowToolGateway { call ->
            if (call.toolKey == "workspace.publish") {
                WorkflowToolOutcome.Failed("TOOL_FAILED", "The publish step failed.")
            } else if (call.toolKey == "workspace.write_text") {
                WorkflowToolOutcome.Failed("UNDO_FAILED", "The compensation failed.")
            } else {
                WorkflowToolOutcome.Succeeded(AgentJsonValue.Str("read"))
            }
        }
        val service = WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = folder.newFolder("checkpoints-comp"),
            toolGateway = gateway,
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: AgentTask,
                    state: com.muyuchat.mca.agent.tools.WorkflowToolRunState,
                    resume: com.muyuchat.core.agent.runtime.run.AgentLoopResume?
                ): WorkflowToolGateway = gateway
            }
        )
        val result = AgentWorkflowGraphRunExecutor(service).execute(
            run = run(published.definitionId, published.version),
            task = task(),
            resume = null,
            progress = AgentModelProgressSink { }
        )

        assertTrue("expected a failed run, got $result", result is AgentLoopResult.Failed)
        val failed = result as AgentLoopResult.Failed
        assertEquals("TOOL_FAILED", failed.error.code)
        assertTrue(
            "an incomplete compensation must be reported as a possible side effect",
            failed.error.possibleSideEffects
        )
    }

    private fun run(definitionId: String, version: Long): AgentRun {
        val now = 1_000L
        return AgentRun(
            runId = "run-unknown",
            taskId = "task-unknown",
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
            taskId = "task-unknown",
            workspaceId = "workspace-unknown",
            goal = "unknown effect",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now,
            displayStatus = AgentDisplayStatus.RUNNING,
            lastRunId = "run-unknown",
            revision = 1
        )
    }
}
