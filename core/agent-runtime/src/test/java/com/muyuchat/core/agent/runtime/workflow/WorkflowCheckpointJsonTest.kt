package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N19: the runtime owns a strict checkpoint encoding. */
class WorkflowCheckpointJsonTest {

    private fun checkpoint() = WorkflowRunCheckpoint(
        definitionId = "wf-1",
        definitionVersion = 3L,
        revision = 7L,
        outcomes = listOf(
            WorkflowNodeOutcome("trigger", WorkflowNodeState.SUCCEEDED, AgentJsonValue.Obj(emptyList())),
            WorkflowNodeOutcome("skipped", WorkflowNodeState.SKIPPED),
            WorkflowNodeOutcome(
                "done",
                WorkflowNodeState.SUCCEEDED,
                AgentJsonValue.Obj(listOf("result" to AgentJsonValue.Str("ok")))
            )
        ),
        nodeOutputs = mapOf("done" to AgentJsonValue.Obj(listOf("result" to AgentJsonValue.Str("ok")))),
        outputs = mapOf("result" to AgentJsonValue.Str("ok")),
        activeEdges = setOf("trigger->done:"),
        skipped = setOf("skipped")
    )

    @Test
    fun aCheckpointRoundTripsThroughCanonicalJson() {
        assertEquals(checkpoint(), WorkflowCheckpointJson.decode(WorkflowCheckpointJson.encode(checkpoint())))
    }

    @Test
    fun malformedOrUnknownContentIsRejectedInsteadOfBecomingEmpty() {
        assertNull(WorkflowCheckpointJson.decode("not json"))
        assertNull(WorkflowCheckpointJson.decode("""{"definitionId":"wf-1"}"""))
        assertNull(
            WorkflowCheckpointJson.decode(
                WorkflowCheckpointJson.encode(checkpoint())
                    .replace("\"state\":\"SKIPPED\"", "\"state\":\"BROKEN\"")
            )
        )
        assertNull(
            WorkflowCheckpointJson.decode(
                WorkflowCheckpointJson.encode(checkpoint()).replace("\"revision\":7", "\"revision\":-1")
            )
        )
    }
}
