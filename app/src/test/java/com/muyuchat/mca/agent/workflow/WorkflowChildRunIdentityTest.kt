package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Child-run identity and the FOREACH seed checkpoint decide whether a restarted parent resumes an
 * existing child or silently starts a second one, so both are pinned here directly. They used to be
 * private helpers inside `WorkflowExecutionService`.
 */
class WorkflowChildRunIdentityTest {

    @Test
    fun childRunIdIsDeterministicAndScopedToParentAndNode() {
        val id = deriveChildRunId("run-1", "child-node")
        assertTrue(id, id.startsWith("wfsub-"))
        assertEquals("wfsub- prefix plus a 48-hex digest", 6 + 48, id.length)
        assertTrue(id.drop(6).all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(id, deriveChildRunId("run-1", "child-node"))
        assertNotEquals(id, deriveChildRunId("run-2", "child-node"))
        assertNotEquals(id, deriveChildRunId("run-1", "other-node"))
        assertNotEquals(deriveChildRunId("run-1", "ab"), deriveChildRunId("run-1a", "b"))
    }

    @Test
    fun foreachChildRunIdIsDistinctPerIndex() {
        val base = deriveForeachChildRunId("run-1", "each", 0)
        assertTrue(base, base.startsWith("wfforeach-"))
        assertEquals("wfforeach- prefix plus a 48-hex digest", 10 + 48, base.length)
        assertEquals(base, deriveForeachChildRunId("run-1", "each", 0))
        assertNotEquals(base, deriveForeachChildRunId("run-1", "each", 1))
        assertNotEquals(base, deriveForeachChildRunId("run-2", "each", 0))
        val indexes = (0 until 32).map { deriveForeachChildRunId("run-1", "each", it) }
        assertEquals("index must not collide", indexes.size, indexes.toSet().size)
        assertNotEquals(
            "foreach and subworkflow ids may not alias",
            deriveChildRunId("run-1", "each"),
            base
        )
    }

    @Test
    fun foreachCheckpointSeedsTheTriggerAsSucceededWithTheItem() {
        val item = AgentJsonValue.Obj(
            listOf("n" to AgentJsonValue.Num("3"), "text" to AgentJsonValue.Str("a"))
        )
        val checkpoint = newForeachCheckpoint(childDefinition(), childDefinition().nodes.first(), item)

        assertEquals("foreach-child", checkpoint.definitionId)
        assertEquals(7L, checkpoint.definitionVersion)
        assertEquals("a fresh checkpoint starts at revision 0", 0L, checkpoint.revision)
        assertEquals(
            "only the trigger is recorded, already SUCCEEDED",
            listOf("child-trigger"),
            checkpoint.outcomes.map { it.nodeId }
        )
        assertEquals(WorkflowNodeState.SUCCEEDED, checkpoint.outcomes.single().state)
        assertEquals(item, checkpoint.outcomes.single().output)
        assertEquals(mapOf("child-trigger" to item), checkpoint.nodeOutputs)
        assertEquals("no port is filled before the child runs", emptyMap<String, AgentJsonValue>(), checkpoint.outputs)
        assertEquals(
            "only the trigger's outgoing edges are active, with their labels",
            setOf("child-trigger->child-step:ok", "child-trigger->child-out:"),
            checkpoint.activeEdges
        )
        assertEquals("nothing is skipped yet", emptySet<String>(), checkpoint.skipped)
    }

    private fun childDefinition() = WorkflowDefinition(
        definitionId = "foreach-child",
        version = 7,
        displayName = "child",
        nodes = listOf(
            WorkflowNode("child-trigger", WorkflowNodeType.TRIGGER),
            WorkflowNode("child-step", WorkflowNodeType.LOGIC),
            WorkflowNode("child-out", WorkflowNodeType.OUTPUT)
        ),
        edges = listOf(
            WorkflowEdge("child-trigger", "child-step", "ok"),
            WorkflowEdge("child-trigger", "child-out"),
            WorkflowEdge("child-step", "child-out")
        )
    )
}
