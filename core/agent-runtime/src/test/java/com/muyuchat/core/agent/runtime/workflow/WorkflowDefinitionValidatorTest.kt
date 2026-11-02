package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowBudget
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowFailurePolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N17: static workflow graph validation. No node is executed and no expression is evaluated. */
class WorkflowDefinitionValidatorTest {

    private val validator = WorkflowDefinitionValidator()

    private fun trigger() = WorkflowNode("trigger", WorkflowNodeType.TRIGGER)

    private fun tool(id: String = "gather") = WorkflowNode(
        id,
        WorkflowNodeType.TOOL,
        configJson = """{"toolKey":"workspace.read_text"}"""
    )

    private fun output(id: String = "done", port: String = "result") = WorkflowNode(
        id,
        WorkflowNodeType.OUTPUT,
        configJson = """{"port":"$port"}"""
    )

    private fun definition(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        outputs: List<WorkflowPort> = listOf(
            WorkflowPort("result", WorkflowPortType.STRING)
        ),
        hash: String = ""
    ) = WorkflowDefinition(
        definitionId = "wf-1",
        version = 1,
        displayName = "测试流程",
        outputs = outputs,
        nodes = nodes,
        edges = edges,
        failurePolicy = WorkflowFailurePolicy.FAIL_FAST,
        definitionHash = hash
    )

    private fun invalidCodes(result: WorkflowValidationResult): List<String> =
        (result as WorkflowValidationResult.Invalid).issues.map { it.code }

    @Test
    fun aMinimalDagIsValidAndTopologicallyOrdered() {
        val result = validator.validate(
            definition(
                nodes = listOf(trigger(), tool(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "done")
                )
            )
        )

        val valid = result as WorkflowValidationResult.Valid
        assertEquals(listOf("trigger", "gather", "done"), valid.topologicalOrder)
    }

    @Test
    fun aCycleIsRejected() {
        val result = validator.validate(
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

        assertTrue("expected cycle, got $result", "WF_CYCLE" in invalidCodes(result))
    }

    @Test
    fun unknownEndpointsAreRejected() {
        val result = validator.validate(
            definition(
                nodes = listOf(trigger(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "ghost"),
                    WorkflowEdge("ghost", "done")
                )
            )
        )

        assertEquals(2, invalidCodes(result).count { it == "WF_UNKNOWN_EDGE_ENDPOINT" })
    }

    @Test
    fun unreachableNodesAreRejected() {
        val result = validator.validate(
            definition(
                nodes = listOf(trigger(), tool(), output(), tool("orphan")),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "done")
                )
            )
        )

        val issues = (result as WorkflowValidationResult.Invalid).issues
        assertTrue(
            "expected unreachable orphan, got $issues",
            issues.any { it.code == "WF_UNREACHABLE_NODE" && it.nodeId == "orphan" }
        )
        assertTrue(issues.any { it.code == "WF_DEAD_END" && it.nodeId == "orphan" })
    }

    @Test
    fun nodesThatCannotReachAnOutputAreDeadEnds() {
        val result = validator.validate(
            definition(
                nodes = listOf(trigger(), tool(), tool("stuck"), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("trigger", "stuck"),
                    WorkflowEdge("gather", "done")
                )
            )
        )

        assertTrue(
            "expected a dead end, got ${invalidCodes(result)}",
            invalidCodes(result).contains("WF_DEAD_END")
        )
    }

    @Test
    fun duplicateNodeIdsAndMissingOutputsAreRejected() {
        val result = validator.validate(
            definition(
                nodes = listOf(trigger(), tool(), tool(), tool("done")),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "done")
                )
            )
        )

        val codes = invalidCodes(result)
        assertTrue(codes.contains("WF_DUPLICATE_NODE"))
        assertTrue(codes.contains("WF_NO_OUTPUT"))
    }

    @Test
    fun triggerCountAndOutputPortAreChecked() {
        val noTrigger = validator.validate(
            definition(
                nodes = listOf(tool(), output()),
                edges = listOf(WorkflowEdge("gather", "done"))
            )
        )
        assertTrue(invalidCodes(noTrigger).contains("WF_TRIGGER_COUNT"))

        val badPort = validator.validate(
            definition(
                nodes = listOf(trigger(), output(port = "missing")),
                edges = listOf(WorkflowEdge("trigger", "done"))
            )
        )
        assertTrue(invalidCodes(badPort).contains("WF_UNKNOWN_OUTPUT_PORT"))
    }

    @Test
    fun conditionRoutingMustBeCompleteAndUniqueAcrossConditionNodesOnly() {
        val condition = WorkflowNode(
            "check",
            WorkflowNodeType.CONDITION,
            configJson = """{"expression":"$.ok == true"}"""
        )
        val valid = validator.validate(
            definition(
                nodes = listOf(trigger(), condition, tool("yes"), tool("no"), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "check"),
                    WorkflowEdge("check", "yes", whenLabel = "true"),
                    WorkflowEdge("check", "no", whenLabel = "false"),
                    WorkflowEdge("yes", "done"),
                    WorkflowEdge("no", "done")
                )
            )
        )
        assertTrue(valid is WorkflowValidationResult.Valid)

        val unlabeled = validator.validate(
            definition(
                nodes = listOf(trigger(), condition, tool("yes"), tool("no"), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "check"),
                    WorkflowEdge("check", "yes"),
                    WorkflowEdge("check", "no", whenLabel = "false"),
                    WorkflowEdge("yes", "done"),
                    WorkflowEdge("no", "done")
                )
            )
        )
        assertTrue(invalidCodes(unlabeled).contains("WF_CONDITION_UNLABELED"))

        val labeledNonCondition = validator.validate(
            definition(
                nodes = listOf(trigger(), tool(), output()),
                edges = listOf(
                    WorkflowEdge("trigger", "gather", whenLabel = "x"),
                    WorkflowEdge("gather", "done")
                )
            )
        )
        assertTrue(invalidCodes(labeledNonCondition).contains("WF_EDGE_LABEL_ON_NON_CONDITION"))
    }

    @Test
    fun typedNodeConfigIsRequired() {
        val result = validator.validate(
            definition(
                nodes = listOf(
                    trigger(),
                    WorkflowNode("gather", WorkflowNodeType.TOOL),
                    output()
                ),
                edges = listOf(
                    WorkflowEdge("trigger", "gather"),
                    WorkflowEdge("gather", "done")
                )
            )
        )

        assertTrue(invalidCodes(result).contains("WF_CONFIG_MISSING"))
    }

    @Test
    fun subworkflowMustBePinnedAndAvailable() {
        val node = WorkflowNode(
            "sub",
            WorkflowNodeType.SUBWORKFLOW,
            subworkflowDefinitionId = "wf-child",
            subworkflowVersion = 2
        )
        val unresolved = validator.validate(
            definition(
                nodes = listOf(trigger(), node, output()),
                edges = listOf(
                    WorkflowEdge("trigger", "sub"),
                    WorkflowEdge("sub", "done")
                )
            )
        )
        assertTrue(invalidCodes(unresolved).contains("WF_SUBWORKFLOW_UNRESOLVED"))

        val child = definition(
            nodes = listOf(trigger(), tool(), output()),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        ).copy(definitionId = "wf-child", version = 2)
        val resolved = validator.validate(
            definition(
                nodes = listOf(trigger(), node, output()),
                edges = listOf(
                    WorkflowEdge("trigger", "sub"),
                    WorkflowEdge("sub", "done")
                )
            ),
            referencedDefinitions = mapOf(
                WorkflowDefinitionValidator.SubworkflowRef("wf-child", 2) to child
            )
        )
        assertTrue("expected valid, got $resolved", resolved is WorkflowValidationResult.Valid)
    }

    @Test
    fun nodeLimitIsEnforced() {
        val many = buildList {
            add(trigger())
            repeat(100) { index -> add(tool("tool-$index")) }
            add(output())
        }
        val result = validator.validate(
            definition(
                nodes = many,
                edges = emptyList()
            )
        )
        assertTrue(invalidCodes(result).contains("WF_TOO_MANY_NODES"))
    }

    @Test
    fun theDefinitionHashIsCanonicalAndDetectsTampering() {
        val definition = definition(
            nodes = listOf(trigger(), tool(), output()),
            edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
        )
        val hash = WorkflowDefinitionHasher.hash(definition)
        assertEquals(hash, WorkflowDefinitionHasher.hash(definition))
        assertTrue(validator.validate(definition.copy(definitionHash = hash)) is WorkflowValidationResult.Valid)

        val mismatch = validator.validate(definition.copy(definitionHash = "a".repeat(64)))
        assertTrue(invalidCodes(mismatch).contains("WF_HASH_MISMATCH"))
    }
    @Test
    fun selfRecursiveSubworkflowIsRejectedWithoutUnboundedRecursion() {
        val selfRef = WorkflowDefinitionValidator.SubworkflowRef("wf-self", 1)
        val sub = WorkflowNode(
            "sub",
            WorkflowNodeType.SUBWORKFLOW,
            subworkflowDefinitionId = "wf-self",
            subworkflowVersion = 1
        )
        val self = definition(
            nodes = listOf(trigger(), sub, output()),
            edges = listOf(WorkflowEdge("trigger", "sub"), WorkflowEdge("sub", "done"))
        ).copy(definitionId = "wf-self", version = 1)

        val result = validator.validate(
            self,
            referencedDefinitions = mapOf(selfRef to self)
        )

        assertTrue("self recursion must be invalid, got $result", result is WorkflowValidationResult.Invalid)
    }


    @Test
    fun aCompensationMustTargetOneExecutableNodeAndStayOutsideTheFlow() {
        val validator = WorkflowDefinitionValidator()
        fun graph(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>) = WorkflowDefinition(
            definitionId = "wf-comp",
            version = 1,
            displayName = "comp",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = nodes,
            edges = edges,
            budget = WorkflowBudget()
        )
        fun codes(definition: WorkflowDefinition): List<String> =
            when (val result = validator.validate(definition)) {
                is WorkflowValidationResult.Valid -> emptyList()
                is WorkflowValidationResult.Invalid -> result.issues.map { it.code }
            }
        val trigger = WorkflowNode("trigger", WorkflowNodeType.TRIGGER)
        val a = WorkflowNode("a", WorkflowNodeType.TOOL, configJson = "{\"toolKey\":\"workspace.read_text\"}")
        val done = WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = "{\"port\":\"result\"}")
        fun undo(id: String, target: String) = WorkflowNode(
            id,
            WorkflowNodeType.COMPENSATE,
            configJson = "{\"compensates\":\"$target\",\"toolKey\":\"workspace.write_text\"}"
        )
        val flow = listOf(WorkflowEdge("trigger", "a"), WorkflowEdge("a", "done"))

        assertTrue(codes(graph(listOf(trigger, a, undo("undo-a", "a"), done), flow)).isEmpty())

        assertTrue(
            "one compensation per target",
            "WF_COMPENSATION_DUPLICATE" in codes(
                graph(listOf(trigger, a, undo("undo-a", "a"), undo("undo-b", "a"), done), flow)
            )
        )
        assertTrue(
            "the target must exist",
            "WF_COMPENSATION_TARGET" in codes(
                graph(listOf(trigger, a, undo("undo-x", "missing"), done), flow)
            )
        )
        assertTrue(
            "a compensation is never wired into the flow",
            "WF_COMPENSATION_EDGE" in codes(
                graph(
                    listOf(trigger, a, undo("undo-a", "a"), done),
                    flow + WorkflowEdge("a", "undo-a")
                )
            )
        )
    }
}
