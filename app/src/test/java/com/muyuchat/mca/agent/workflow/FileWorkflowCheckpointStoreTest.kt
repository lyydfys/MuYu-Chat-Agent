package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeState
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N19: file-backed checkpoint CAS, strict decode and fail-closed corruption handling. */
class FileWorkflowCheckpointStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun definition(version: Long = 1L) = WorkflowDefinition(
        definitionId = "wf-1",
        version = version,
        displayName = "checkpoint",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(
            WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
            WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
        ),
        edges = listOf(
            com.muyuchat.core.agent.contract.workflow.WorkflowEdge("trigger", "done")
        )
    )

    private fun checkpoint(revision: Long, output: String = "v") = WorkflowRunCheckpoint(
        definitionId = "wf-1",
        definitionVersion = 1L,
        revision = revision,
        outcomes = listOf(
            WorkflowNodeOutcome(
                "trigger",
                WorkflowNodeState.SUCCEEDED,
                AgentJsonValue.Obj(emptyList())
            ),
            WorkflowNodeOutcome(
                "done",
                WorkflowNodeState.SUCCEEDED,
                AgentJsonValue.Obj(listOf("result" to AgentJsonValue.Str(output)))
            )
        ),
        nodeOutputs = mapOf("trigger" to AgentJsonValue.Obj(emptyList())),
        outputs = mapOf("result" to AgentJsonValue.Obj(listOf("result" to AgentJsonValue.Str(output)))),
        activeEdges = setOf("trigger->done:"),
        skipped = emptySet()
    )

    private fun store(key: String = "run-1") =
        FileWorkflowCheckpointStore(folder.root, key)

    @Test
    fun aCheckpointRoundTripsWithExactValues() {
        val store = store()
        assertTrue(store.save(checkpoint(1L, "hello")))

        val loaded = store.load(definition()) as WorkflowCheckpointLoad.Loaded
        assertEquals(checkpoint(1L, "hello"), loaded.checkpoint)
        assertFalse(File(folder.root, "workflow-checkpoints/run-1.json.tmp").exists())
    }

    @Test
    fun theStoreEnforcesRevisionCas() {
        val store = store()
        assertTrue(store.save(checkpoint(1L)))
        assertFalse("same revision must lose", store.save(checkpoint(1L)))
        assertTrue(store.save(checkpoint(2L)))
        assertEquals(2L, (store.load(definition()) as WorkflowCheckpointLoad.Loaded).checkpoint.revision)
    }

    @Test
    fun aCorruptFileFailsClosedOnLoadAndRefusesSave() {
        val store = store()
        store.save(checkpoint(1L))
        File(folder.root, "workflow-checkpoints/run-1.json").writeText("{not json")

        val loaded = store.load(definition())
        assertTrue(loaded is WorkflowCheckpointLoad.Rejected)
        assertEquals("WF_CHECKPOINT_CORRUPT", (loaded as WorkflowCheckpointLoad.Rejected).errorCode)
        assertTrue(
            runCatching { store.save(checkpoint(2L)) }.isFailure
        )
    }

    @Test
    fun aCheckpointFromAnotherVersionIsRejectedOnLoad() {
        store().save(checkpoint(1L))
        val loaded = store().load(definition(version = 2L))
        assertTrue(loaded is WorkflowCheckpointLoad.Rejected)
        assertEquals("WF_CHECKPOINT_MISMATCH", (loaded as WorkflowCheckpointLoad.Rejected).errorCode)
    }

    @Test
    fun anUnsafeKeyIsRejected() {
        assertTrue(runCatching { FileWorkflowCheckpointStore(folder.root, "../escape") }.isFailure)
        assertTrue(runCatching { FileWorkflowCheckpointStore(folder.root, "") }.isFailure)
    }

    @Test
    fun aLegacyRunKeyStillResumesAndNewWritesLandOnTheTaskKey() {
        store("run-1").save(checkpoint(1L, "legacy"))
        val taskKey = WorkflowCheckpointKeys.taskRoot("task-1")
        val migrated = FileWorkflowCheckpointStore(folder.root, taskKey, legacyKey = "run-1")

        val loaded = migrated.load(definition()) as WorkflowCheckpointLoad.Loaded
        assertEquals(checkpoint(1L, "legacy"), loaded.checkpoint)

        assertTrue(migrated.save(checkpoint(2L, "task")))
        assertFalse(File(folder.root, "workflow-checkpoints/$taskKey.json").readText().isEmpty())
        val legacy = store("run-1").load(definition()) as WorkflowCheckpointLoad.Loaded
        assertEquals("the legacy file must stay untouched", 1L, legacy.checkpoint.revision)
        val resumed = migrated.load(definition()) as WorkflowCheckpointLoad.Loaded
        assertEquals(2L, resumed.checkpoint.revision)
    }

    @Test
    fun aCorruptPrimaryNeverFallsBackToTheLegacyRunKey() {
        store("run-1").save(checkpoint(1L, "legacy"))
        val taskKey = WorkflowCheckpointKeys.taskRoot("task-1")
        File(folder.root, "workflow-checkpoints/$taskKey.json").writeText("{broken")

        val loaded = FileWorkflowCheckpointStore(folder.root, taskKey, legacyKey = "run-1")
            .load(definition())

        assertTrue(loaded is WorkflowCheckpointLoad.Rejected)
        assertEquals("WF_CHECKPOINT_CORRUPT", (loaded as WorkflowCheckpointLoad.Rejected).errorCode)
    }
}
