package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.workflow.WorkflowBudget
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowFailurePolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N18 base: in-memory DAG walker, condition routing and node facts. */
class WorkflowGraphRunnerTest {

    private val requested = ArrayList<String>()

    private fun executor(
        outputs: Map<String, WorkflowNodeExecution> = emptyMap(),
        default: WorkflowNodeExecution = WorkflowNodeExecution.Succeeded(
            AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str("ok")))
        )
    ): WorkflowNodeExecutor = WorkflowNodeExecutor { request ->
        requested += request.node.nodeId
        outputs[request.node.nodeId] ?: default
    }

    private fun trigger() = WorkflowNode("trigger", WorkflowNodeType.TRIGGER)

    private fun tool(id: String) = WorkflowNode(
        id,
        WorkflowNodeType.TOOL,
        configJson = """{"toolKey":"workspace.read_text"}"""
    )

    private fun condition(expression: String) = WorkflowNode(
        "check",
        WorkflowNodeType.CONDITION,
        configJson = """{"expression":$expression}"""
    )

    private fun output(id: String = "done", port: String = "result") = WorkflowNode(
        id,
        WorkflowNodeType.OUTPUT,
        configJson = """{"port":"$port"}"""
    )

    private fun definition(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        policy: WorkflowFailurePolicy = WorkflowFailurePolicy.FAIL_FAST,
        outputs: List<WorkflowPort> = listOf(WorkflowPort("result", WorkflowPortType.STRING))
    ) = WorkflowDefinition(
        definitionId = "wf-run",
        version = 1,
        displayName = "runner",
        outputs = outputs,
        nodes = nodes,
        edges = edges,
        failurePolicy = policy,
        budget = WorkflowBudget()
    )

    @Test
    fun checkpointAfterEachNodeAllowsAnIdempotentResume() = runBlocking {
        val definition = definition(
            nodes = listOf(trigger(), tool("gather"), output()),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        val saved = ArrayList<WorkflowRunCheckpoint>()
        val first = WorkflowGraphRunner(executor()).run(definition, sink = { checkpoint ->
            saved += checkpoint
            true
        }) as WorkflowGraphResult.Completed

        requested.clear()
        val resumedRunner = WorkflowGraphRunner(
            WorkflowNodeExecutor { error("a resumed node must not execute again") }
        )
        val resumed = resumedRunner.run(
            definition,
            checkpoint = saved.last(),
            sink = { true }
        ) as WorkflowGraphResult.Completed

        assertTrue("no node may re-execute on resume", requested.isEmpty())
        assertEquals(first.outputs, resumed.outputs)
        assertTrue(saved.map { it.revision }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun aLostCheckpointCasStopsTheAttempt() = runBlocking {
        val result = WorkflowGraphRunner(executor()).run(
            definition(
                nodes = listOf(trigger(), tool("gather"), output()),
                edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
            ),
            sink = { false }
        )

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("WF_CHECKPOINT_CONFLICT", failed.errorCode)
        assertTrue("the trigger checkpoint is refused before any node runs", requested.isEmpty())
    }

    @Test
    fun aThrowingSinkStopsAfterTheProducedNodeFact() = runBlocking {
        var saves = 0
        val result = WorkflowGraphRunner(executor()).run(
            definition(
                nodes = listOf(trigger(), tool("gather"), output()),
                edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
            ),
            sink = {
                saves += 1
                if (saves == 2) throw IllegalStateException("storage down")
                true
            }
        )

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("WF_CHECKPOINT_FAILED", failed.errorCode)
        assertEquals(listOf("gather"), requested)
    }

    @Test
    fun aCheckpointFromAnotherDefinitionVersionIsRejected() = runBlocking {
        val definition = definition(
            nodes = listOf(trigger(), tool("gather"), output()),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        val foreign = WorkflowRunCheckpoint(
            definitionId = definition.definitionId,
            definitionVersion = definition.version + 1,
            revision = 3L,
            outcomes = emptyList(),
            nodeOutputs = emptyMap(),
            outputs = emptyMap(),
            activeEdges = emptySet(),
            skipped = emptySet()
        )

        val result = WorkflowGraphRunner(executor()).run(definition, checkpoint = foreign)

        assertTrue(result is WorkflowGraphResult.Rejected)
        assertEquals("WF_CHECKPOINT_MISMATCH", (result as WorkflowGraphResult.Rejected).errorCode)
    }

    @Test
    fun aLinearGraphExecutesEachNodeOnceAndProducesItsOutput() = runBlocking {
        val result = WorkflowGraphRunner(
            executor(
                outputs = mapOf(
                    "gather" to WorkflowNodeExecution.Succeeded(
                        AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str("text")))
                    ),
                    "done" to WorkflowNodeExecution.Succeeded(
                        AgentJsonValue.Obj(listOf("result" to AgentJsonValue.Str("final")))
                    )
                )
            )
        ).run(
            definition(
                nodes = listOf(trigger(), tool("gather"), output()),
                edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
            )
        )

        val completed = result as WorkflowGraphResult.Completed
        assertEquals(listOf("gather", "done"), requested)
        assertEquals(listOf("trigger", "gather", "done"), completed.outcomes.map { it.nodeId })
        assertEquals("final", (completed.outputs.getValue("result") as AgentJsonValue.Obj)
            .fields.first { it.first == "result" }.second.let { (it as AgentJsonValue.Str).value })
    }

    @Test
    fun aConditionOnlyActivatesItsChosenRouteAndSkipsTheOther() = runBlocking {
        val nodes = listOf(
            trigger(),
            tool("gather"),
            condition(
                """{"op":"EQ","left":{"ref":"nodes.gather.outputs.value"},"right":{"literal":"yes"}}"""
            ),
            tool("accept"),
            tool("reject"),
            output()
        )
        val edges = listOf(
            WorkflowEdge("trigger", "gather"),
            WorkflowEdge("gather", "check"),
            WorkflowEdge("check", "accept", whenLabel = "true"),
            WorkflowEdge("check", "reject", whenLabel = "false"),
            WorkflowEdge("accept", "done"),
            WorkflowEdge("reject", "done")
        )
        val result = WorkflowGraphRunner(
            executor(
                outputs = mapOf(
                    "gather" to WorkflowNodeExecution.Succeeded(
                        AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str("yes")))
                    )
                )
            )
        ).run(definition(nodes, edges))

        val completed = result as WorkflowGraphResult.Completed
        assertTrue("accept branch runs", requested.contains("accept"))
        assertTrue("reject branch must not run", requested.none { it == "reject" })
        assertEquals(
            WorkflowNodeState.SKIPPED,
            completed.outcomes.single { it.nodeId == "reject" }.state
        )
        assertTrue(completed.outputs.containsKey("result"))
    }

    @Test
    fun aFalseConditionActivatesTheFalseRoute() = runBlocking {
        val nodes = listOf(
            trigger(),
            tool("gather"),
            condition(
                """{"op":"EQ","left":{"ref":"nodes.gather.outputs.value"},"right":{"literal":"no"}}"""
            ),
            tool("accept"),
            tool("reject"),
            output()
        )
        val edges = listOf(
            WorkflowEdge("trigger", "gather"),
            WorkflowEdge("gather", "check"),
            WorkflowEdge("check", "accept", whenLabel = "true"),
            WorkflowEdge("check", "reject", whenLabel = "false"),
            WorkflowEdge("accept", "done"),
            WorkflowEdge("reject", "done")
        )
        val result = WorkflowGraphRunner(
            executor(
                outputs = mapOf(
                    "gather" to WorkflowNodeExecution.Succeeded(
                        AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str("yes")))
                    )
                )
            )
        ).run(definition(nodes, edges))

        assertTrue(result is WorkflowGraphResult.Completed)
        assertTrue(requested.contains("reject"))
        assertTrue(requested.none { it == "accept" })
    }

    @Test
    fun aConditionThatCannotBeEvaluatedFailsClosed() = runBlocking {
        val nodes = listOf(
            trigger(),
            tool("gather"),
            condition("""{"ref":"nodes.missing.outputs.value"}"""),
            tool("accept"),
            tool("reject"),
            output()
        )
        val edges = listOf(
            WorkflowEdge("trigger", "gather"),
            WorkflowEdge("gather", "check"),
            WorkflowEdge("check", "accept", whenLabel = "true"),
            WorkflowEdge("check", "reject", whenLabel = "false"),
            WorkflowEdge("accept", "done"),
            WorkflowEdge("reject", "done")
        )
        val result = WorkflowGraphRunner(executor()).run(definition(nodes, edges))

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("WF_EXPR_ERROR", failed.errorCode)
        assertTrue(requested.none { it == "accept" || it == "reject" })
    }

    @Test
    fun failFastStopsBeforeLaterNodes() = runBlocking {
        val result = WorkflowGraphRunner(
            executor(
                outputs = mapOf(
                    "gather" to WorkflowNodeExecution.Failed("TOOL_FAILED", "boom")
                )
            )
        ).run(
            definition(
                nodes = listOf(trigger(), tool("gather"), output()),
                edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
            )
        )

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("TOOL_FAILED", failed.errorCode)
        assertTrue(requested.none { it == "done" })
        assertEquals(WorkflowNodeState.FAILED, failed.outcomes.last().state)
    }

    @Test
    fun continuePolicyRunsIndependentBranchesButStillReportsTheFailure() = runBlocking {
        val nodes = listOf(trigger(), tool("a"), tool("b"), output())
        val edges = listOf(
            WorkflowEdge("trigger", "a"),
            WorkflowEdge("trigger", "b"),
            WorkflowEdge("a", "done"),
            WorkflowEdge("b", "done")
        )
        val result = WorkflowGraphRunner(
            executor(outputs = mapOf("a" to WorkflowNodeExecution.Failed("TOOL_FAILED", "boom")))
        ).run(definition(nodes, edges, policy = WorkflowFailurePolicy.CONTINUE))

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("TOOL_FAILED", failed.errorCode)
        assertTrue(requested.contains("b"))
    }

    @Test
    fun anInvalidGraphNeverReachesTheExecutor() = runBlocking {
        val result = WorkflowGraphRunner(executor()).run(
            definition(
                nodes = listOf(trigger(), tool("a"), tool("b"), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "a"),
                    WorkflowEdge("a", "b"),
                    WorkflowEdge("b", "a"),
                    WorkflowEdge("b", "done")
                )
            )
        )

        assertTrue(result is WorkflowGraphResult.Rejected)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun aWaitApprovalNodeParksWithoutCallingTheExecutorAndApprovalCompletesIt() = runBlocking {
        val wait = WorkflowNode(
            "wait",
            WorkflowNodeType.WAIT_APPROVAL,
            configJson = """{"prompt":"approve?"}"""
        )
        val definition = definition(
            nodes = listOf(trigger(), wait, output()),
            edges = listOf(WorkflowEdge("trigger", "wait"), WorkflowEdge("wait", "done"))
        )
        val runner = WorkflowGraphRunner(executor())

        val parked = runner.run(definition)
        val suspended = parked as WorkflowGraphResult.SuspendedForUser
        assertEquals(WorkflowUserWait.Approval("wait"), suspended.wait)
        assertEquals("a wait node has no executor and no side effect", emptyList<String>(), requested)

        val completed = runner.run(definition, userDecision = WorkflowUserDecisions.APPROVE)
        assertTrue("expected completion, got $completed", completed is WorkflowGraphResult.Completed)
        assertEquals(
            "the wait node itself is never dispatched to a node executor",
            listOf("done"),
            requested
        )
    }

    @Test
    fun denyFailsTheWaitNodeWithoutRunningItsSuccessor() = runBlocking {
        val wait = WorkflowNode(
            "wait",
            WorkflowNodeType.WAIT_APPROVAL,
            configJson = """{"prompt":"approve?"}"""
        )
        val definition = definition(
            nodes = listOf(trigger(), wait, output()),
            edges = listOf(WorkflowEdge("trigger", "wait"), WorkflowEdge("wait", "done"))
        )

        val denied = WorkflowGraphRunner(executor()).run(
            definition,
            userDecision = WorkflowUserDecisions.DENY
        )

        val failed = denied as WorkflowGraphResult.Failed
        assertEquals("WF_WAIT_DENIED", failed.errorCode)
        assertTrue("a denied wait must not run its successor", requested.isEmpty())
    }

    @Test
    fun waitForUserPolicyParksTheFailedNodeAndContinueSkipsItsBranch() = runBlocking {
        val definition = definition(
            nodes = listOf(trigger(), tool("gather"), output()),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done")),
            policy = WorkflowFailurePolicy.WAIT_FOR_USER
        )
        val saved = ArrayList<WorkflowRunCheckpoint>()
        val failing = WorkflowNodeExecutor {
            requested += it.node.nodeId
            WorkflowNodeExecution.Failed("WF_TOOL_FAILED", "tool failed")
        }

        val parked = WorkflowGraphRunner(failing).run(definition, sink = { saved += it; true })
        val suspended = parked as WorkflowGraphResult.SuspendedForUser
        assertEquals(
            WorkflowUserWait.NodeFailure("gather", "WF_TOOL_FAILED", "tool failed"),
            suspended.wait
        )
        assertEquals(listOf("gather"), requested)

        requested.clear()
        val continued = WorkflowGraphRunner(executor()).run(
            definition,
            checkpoint = saved.last(),
            sink = { true },
            userDecision = WorkflowUserDecisions.CONTINUE
        )

        // "continue" behaves like CONTINUE: a failed node has no output, so its own branch is
        // not activated (the successor is SKIPPED), and the run reports the original failure.
        val failed = continued as WorkflowGraphResult.Failed
        assertEquals("WF_TOOL_FAILED", failed.errorCode)
        assertTrue("the failed node is never re-executed", "gather" !in requested)
        assertEquals(
            WorkflowNodeState.SKIPPED,
            failed.outcomes.single { it.nodeId == "done" }.state
        )
    }

    @Test
    fun everyDeclaredOutputMustBeProduced() = runBlocking {
        val result = WorkflowGraphRunner(executor()).run(
            definition(
                nodes = listOf(trigger(), output()),
                edges = listOf(WorkflowEdge("trigger", "done")),
                outputs = listOf(
                    WorkflowPort("result", WorkflowPortType.STRING),
                    WorkflowPort("extra", WorkflowPortType.STRING)
                )
            )
        )

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("WF_OUTPUT_MISSING", failed.errorCode)
    }
    @Test
    fun fanOutRunsEachBranchOnceAndTheJoinSeesEveryActivatedInput() = runBlocking {
        val inputsByNode = LinkedHashMap<String, List<com.muyuchat.core.agent.runtime.AgentJsonValue>>()
        val joinExecutor = WorkflowNodeExecutor { request ->
            inputsByNode[request.node.nodeId] = request.inputs
            requested += request.node.nodeId
            WorkflowNodeExecution.Succeeded(
                AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str(request.node.nodeId)))
            )
        }
        val definition = definition(
            nodes = listOf(trigger(), tool("a"), tool("b"), tool("join"), output()),
            edges = listOf(
                WorkflowEdge("trigger", "a"),
                WorkflowEdge("trigger", "b"),
                WorkflowEdge("a", "join"),
                WorkflowEdge("b", "join"),
                WorkflowEdge("join", "done")
            )
        )

        val result = WorkflowGraphRunner(joinExecutor).run(definition)

        assertTrue("expected completion, got $result", result is WorkflowGraphResult.Completed)
        assertEquals(listOf("a", "b", "join", "done"), requested)
        assertEquals(2, inputsByNode.getValue("join").size)
    }

    @Test
    fun aJoinWaitsOnlyForActivatedUpstreamsAndSkippedBranchesNeverRun() = runBlocking {
        val definition = definition(
            nodes = listOf(
                trigger(),
                condition("""{"literal":true}"""),
                tool("yes"),
                tool("no"),
                tool("join"),
                output()
            ),
            edges = listOf(
                WorkflowEdge("trigger", "check"),
                WorkflowEdge("check", "yes", whenLabel = "true"),
                WorkflowEdge("check", "no", whenLabel = "false"),
                WorkflowEdge("yes", "join"),
                WorkflowEdge("no", "join"),
                WorkflowEdge("join", "done")
            )
        )

        val result = WorkflowGraphRunner(executor()).run(definition)

        assertTrue("expected completion, got $result", result is WorkflowGraphResult.Completed)
        assertTrue("selected branch must run", requested.contains("yes"))
        assertFalse("inactive branch must be skipped", requested.contains("no"))
        assertEquals(1, requested.count { it == "join" })
        assertTrue("join must run after activation", requested.contains("join"))
    }

    @Test
    fun continuePolicyJoinsOnlyTheActivatedSurvivingBranchAfterAFailure() = runBlocking {
        val inputsByNode = LinkedHashMap<String, Int>()
        val failing = WorkflowNodeExecutor { request ->
            requested += request.node.nodeId
            inputsByNode[request.node.nodeId] = request.inputs.size
            when (request.node.nodeId) {
                "a" -> WorkflowNodeExecution.Failed("E_A", "a failed")
                else -> WorkflowNodeExecution.Succeeded(
                    AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str(request.node.nodeId)))
                )
            }
        }
        val definition = definition(
            nodes = listOf(trigger(), tool("a"), tool("b"), tool("join"), output()),
            edges = listOf(
                WorkflowEdge("trigger", "a"),
                WorkflowEdge("trigger", "b"),
                WorkflowEdge("a", "join"),
                WorkflowEdge("b", "join"),
                WorkflowEdge("join", "done")
            ),
            policy = WorkflowFailurePolicy.CONTINUE
        )

        val result = WorkflowGraphRunner(failing).run(definition)

        assertTrue("failure must still be reported, got $result", result is WorkflowGraphResult.Failed)
        assertEquals(1, requested.count { it == "join" })
        assertTrue("join must run with only the surviving branch", requested.contains("join"))
        assertTrue("failed branch must not be re-run", requested.count { it == "a" } == 1)
        assertEquals(1, inputsByNode["join"])
    }

    @Test
    fun extractNodeRunsAndAResumeKeepsTheSameRandomValue() = runBlocking {
        val extract = WorkflowNode(
            "draw",
            WorkflowNodeType.EXTRACT,
            configJson = """{"op":"RANDOM_INT","source":"unused","path":"1,1000000"}"""
        )
        val definition = definition(
            nodes = listOf(trigger(), extract, output()),
            edges = listOf(WorkflowEdge("trigger", "draw"), WorkflowEdge("draw", "done"))
        )
        var saved: WorkflowRunCheckpoint? = null
        val first = WorkflowGraphRunner(executor()).run(definition, sink = { checkpoint ->
            saved = checkpoint
            true
        })
        assertTrue("extract run must complete, got $first", first is WorkflowGraphResult.Completed)
        val firstOutcome = saved!!.outcomes.single { it.nodeId == "draw" }
        val firstValue = firstOutcome.output as AgentJsonValue.Num

        requested.clear()
        val resumed = WorkflowGraphRunner(executor()).run(definition, checkpoint = saved)
        assertTrue("resumed run must complete, got $resumed", resumed is WorkflowGraphResult.Completed)
        val resumedRewrite = (resumed as WorkflowGraphResult.Completed).outcomes.single { it.nodeId == "draw" }
        assertEquals(firstValue.literal, (resumedRewrite.output as AgentJsonValue.Num).literal)
        assertTrue("extract node must not be re-executed on resume", !requested.contains("draw"))
    }

    @Test
    fun aLogicNodeRunsInTheRunnerWithoutCallingTheNodeExecutor() = runBlocking {
        val logic = WorkflowNode(
            "transform",
            WorkflowNodeType.LOGIC,
            configJson = """{"operation":"SET","value":{"value":"ok"}}"""
        )
        val definition = definition(
            nodes = listOf(trigger(), logic, output("done")),
            edges = listOf(WorkflowEdge("trigger", "transform"), WorkflowEdge("transform", "done"))
        )
        val result = WorkflowGraphRunner(executor()).run(definition)

        assertTrue("expected completion, got $result", result is WorkflowGraphResult.Completed)
        assertTrue("the logic node must not reach the executor", !requested.contains("transform"))
        assertEquals(
            AgentJsonValue.Obj(listOf("value" to AgentJsonValue.Str("ok"))),
            (result as WorkflowGraphResult.Completed).outputs["result"]
        )
    }

    @Test
    fun aSuspendedNodeParksWithoutBeingCommittedOrActivatingItsSuccessor() = runBlocking {
        val approval = AgentApproval(
            approvalId = "approval-1",
            invocationId = "inv-1",
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
        val suspension = WorkflowNodeSuspension("inv-1", "call-1", approval)
        val definition = definition(
            nodes = listOf(trigger(), tool("gather"), output("done")),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        var saved: WorkflowRunCheckpoint? = null
        val result = WorkflowGraphRunner(
            executor(outputs = mapOf("gather" to WorkflowNodeExecution.Suspended(suspension)))
        ).run(definition, sink = { checkpoint -> saved = checkpoint; true })

        assertTrue("expected suspension, got $result", result is WorkflowGraphResult.Suspended)
        val parked = result as WorkflowGraphResult.Suspended
        assertEquals("inv-1", parked.suspension.invocationId)
        assertEquals(listOf("trigger"), parked.outcomes.map { it.nodeId })
        val checkpoint = requireNotNull(saved)
        assertEquals(listOf("trigger"), checkpoint.outcomes.map { it.nodeId })
        assertFalse("the parked node must not be committed as succeeded", "gather" in checkpoint.nodeOutputs)
        assertFalse("the parked node must not activate its successor",
            checkpoint.activeEdges.any { it.startsWith("gather") })
    }

    private fun compensation(id: String, target: String) = WorkflowNode(
        id,
        WorkflowNodeType.COMPENSATE,
        configJson = "{\"compensates\":\"$target\",\"toolKey\":\"workspace.write_text\"}"
    )

    private fun failingGraph() = definition(
        nodes = listOf(trigger(), tool("a"), tool("b"), compensation("undo-a", "a"), output()),
        edges = listOf(
            WorkflowEdge("trigger", "a"),
            WorkflowEdge("a", "b"),
            WorkflowEdge("b", "done")
        )
    )

    @Test
    fun aFailFastRunCompensatesOnlyTheNodesThatSucceeded() = runBlocking {
        val result = WorkflowGraphRunner(
            executor(outputs = mapOf("b" to WorkflowNodeExecution.Failed("TOOL_FAILED", "b failed")))
        ).run(failingGraph())

        assertTrue(result is WorkflowGraphResult.Failed)
        assertEquals("TOOL_FAILED", (result as WorkflowGraphResult.Failed).errorCode)
        assertEquals(listOf("a", "b", "undo-a"), requested)
        assertEquals(
            WorkflowNodeState.SUCCEEDED,
            result.outcomes.single { it.nodeId == "undo-a" }.state
        )
    }

    @Test
    fun aContinueRunCompensatesAtTheEndAndStillReportsTheFailure() = runBlocking {
        val definition = definition(
            nodes = listOf(trigger(), tool("a"), tool("b"), compensation("undo-a", "a"), output()),
            edges = listOf(
                WorkflowEdge("trigger", "a"),
                WorkflowEdge("a", "b"),
                WorkflowEdge("b", "done")
            ),
            policy = WorkflowFailurePolicy.CONTINUE
        )
        val result = WorkflowGraphRunner(
            executor(outputs = mapOf("b" to WorkflowNodeExecution.Failed("TOOL_FAILED", "b failed")))
        ).run(definition)

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("TOOL_FAILED", failed.errorCode)
        assertEquals(listOf("a", "b", "undo-a"), requested)
    }

    @Test
    fun aFailedCompensationIsRecordedWithoutMaskingTheOriginalFailure() = runBlocking {
        val result = WorkflowGraphRunner(
            executor(
                outputs = mapOf(
                    "b" to WorkflowNodeExecution.Failed("TOOL_FAILED", "b failed"),
                    "undo-a" to WorkflowNodeExecution.Failed("UNDO_FAILED", "undo failed")
                )
            )
        ).run(failingGraph())

        val failed = result as WorkflowGraphResult.Failed
        assertEquals("the terminal code stays the original failure", "TOOL_FAILED", failed.errorCode)
        val undo = failed.outcomes.single { it.nodeId == "undo-a" }
        assertEquals(WorkflowNodeState.FAILED, undo.state)
        assertEquals("WF_COMPENSATION_FAILED", undo.errorCode)
    }

    @Test
    fun aCompensationThatNeedsApprovalParksAndResumesWithoutReplayingTheGraph() = runBlocking {
        val approval = AgentApproval(
            approvalId = "approval-undo",
            invocationId = "inv-undo",
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
        var approved = false
        val runner = WorkflowGraphRunner(
            WorkflowNodeExecutor { request ->
                requested += request.node.nodeId
                when (request.node.nodeId) {
                    "b" -> WorkflowNodeExecution.Failed("TOOL_FAILED", "b failed")
                    "undo-a" -> if (approved) {
                        WorkflowNodeExecution.Succeeded(AgentJsonValue.Str("undone"))
                    } else {
                        WorkflowNodeExecution.Suspended(
                            WorkflowNodeSuspension("inv-undo", "hash", approval)
                        )
                    }
                    else -> WorkflowNodeExecution.Succeeded(AgentJsonValue.Obj(emptyList()))
                }
            }
        )
        var checkpoint: WorkflowRunCheckpoint? = null
        val sink = WorkflowCheckpointSink { saved -> checkpoint = saved; true }

        val parked = runner.run(failingGraph(), sink = sink)
        assertTrue(parked is WorkflowGraphResult.Suspended)
        assertTrue("the parked checkpoint records the compensation phase", checkpoint!!.compensating)

        approved = true
        val resumed = runner.run(failingGraph(), checkpoint = checkpoint, sink = sink)
        val failed = resumed as WorkflowGraphResult.Failed
        assertEquals("TOOL_FAILED", failed.errorCode)
        assertEquals(
            WorkflowNodeState.SUCCEEDED,
            failed.outcomes.single { it.nodeId == "undo-a" }.state
        )
        assertEquals("a graph node must not run twice on a compensation resume", 1, requested.count { it == "a" })
        assertEquals(1, requested.count { it == "b" })
    }

    @Test
    fun aResumedCompensationNeverRetriesTargetsAlreadyCompensated() = runBlocking {
        val approval = AgentApproval(
            approvalId = "approval-undo-a",
            invocationId = "inv-undo-a",
            principal = "test",
            argumentsHash = "b".repeat(64),
            toolSchemaHash = "schema",
            resourceFingerprint = "resource",
            policyVersion = 1,
            runEpoch = 1,
            expiresAtMillis = 2_000L,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = 1_000L
        )
        val definition = definition(
            nodes = listOf(
                trigger(), tool("a"), tool("c"), tool("b"),
                compensation("undo-c", "c"), compensation("undo-a", "a"), output()
            ),
            edges = listOf(
                WorkflowEdge("trigger", "a"),
                WorkflowEdge("a", "c"),
                WorkflowEdge("c", "b"),
                WorkflowEdge("b", "done")
            )
        )
        val calls = ArrayList<String>()
        var approved = false
        val executor = WorkflowNodeExecutor { request ->
            calls += request.node.nodeId
            when (request.node.nodeId) {
                "b" -> WorkflowNodeExecution.Failed("TOOL_FAILED", "b failed")
                "undo-c" -> WorkflowNodeExecution.Failed("UNDO_FAILED", "undo-c failed")
                "undo-a" -> if (approved) {
                    WorkflowNodeExecution.Succeeded(AgentJsonValue.Str("undone"))
                } else {
                    WorkflowNodeExecution.Suspended(
                        WorkflowNodeSuspension("inv-undo-a", "hash", approval)
                    )
                }
                else -> WorkflowNodeExecution.Succeeded(AgentJsonValue.Obj(emptyList()))
            }
        }
        var checkpoint: WorkflowRunCheckpoint? = null
        val runner = WorkflowGraphRunner(executor)

        val parked = runner.run(definition, sink = { saved -> checkpoint = saved; true })

        assertTrue("expected the compensation to park, got $parked", parked is WorkflowGraphResult.Suspended)
        val saved = requireNotNull(checkpoint)
        assertTrue("the checkpoint records the compensation phase", saved.compensating)
        assertEquals("c was compensated (and failed) before the park", setOf("c"), saved.compensated)
        assertEquals(listOf("a", "c", "b", "undo-c", "undo-a"), calls)

        approved = true
        val resumed = runner.run(definition, checkpoint = saved, sink = { true })

        val failed = resumed as WorkflowGraphResult.Failed
        assertEquals("the original failure stays terminal", "TOOL_FAILED", failed.errorCode)
        assertEquals(
            "an already-compensated target must never be retried",
            1,
            calls.count { it == "undo-c" }
        )
        assertEquals("graph nodes must not rerun", 1, calls.count { it == "a" })
        assertEquals(1, calls.count { it == "b" })
        assertEquals(
            WorkflowNodeState.SUCCEEDED,
            failed.outcomes.single { it.nodeId == "undo-a" }.state
        )
    }

}
