package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointJson
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointDao
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomWorkflowCheckpointStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dao = FakeCheckpointDao()
    private val runner = DirectRunner()
    private val definition = WorkflowDefinition(
        definitionId = "wf-frame",
        version = 1,
        displayName = "frame",
        outputs = emptyList(),
        nodes = listOf(
            com.muyuchat.core.agent.contract.workflow.WorkflowNode(
                "trigger",
                com.muyuchat.core.agent.contract.workflow.WorkflowNodeType.TRIGGER
            )
        ),
        edges = emptyList()
    )

    @Test
    fun saveIsAStrictRevisionCas() {
        val store = roomStore()
        assertTrue("first write", store.save(checkpoint(1)))
        assertFalse("same revision loses", store.save(checkpoint(1)))
        assertTrue("newer revision wins", store.save(checkpoint(2)))
        val loaded = store.load(definition) as WorkflowCheckpointLoad.Loaded
        assertEquals(2L, loaded.checkpoint.revision)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun aCorruptOrForeignRowIsRejectedNotFallenBack() {
        dao.rows["wftask-a"] = AgentWorkflowCheckpointEntity(
            frameKey = "wftask-a",
            revision = 1,
            definitionId = "wf-frame",
            definitionVersion = 1,
            payloadJson = "{ not json",
            updatedAtMillis = 1L
        )
        val corrupt = roomStore("wftask-a").load(definition)
        assertTrue(corrupt is WorkflowCheckpointLoad.Rejected)
        assertEquals("WF_CHECKPOINT_CORRUPT", (corrupt as WorkflowCheckpointLoad.Rejected).errorCode)

        dao.rows["wftask-b"] = AgentWorkflowCheckpointEntity(
            frameKey = "wftask-b",
            revision = 1,
            definitionId = "other",
            definitionVersion = 1,
            payloadJson = WorkflowCheckpointJson.encode(checkpoint(1).copy(definitionId = "other")),
            updatedAtMillis = 1L
        )
        val mismatch = roomStore("wftask-b").load(definition)
        assertTrue(mismatch is WorkflowCheckpointLoad.Rejected)
        assertEquals("WF_CHECKPOINT_MISMATCH", (mismatch as WorkflowCheckpointLoad.Rejected).errorCode)
    }

    @Test
    fun aLegacyRunIdRowIsImportedIntoTheTaskRootKey() {
        val legacy = checkpoint(3)
        dao.rows["run-legacy"] = AgentWorkflowCheckpointEntity(
            frameKey = "run-legacy",
            revision = legacy.revision,
            definitionId = legacy.definitionId,
            definitionVersion = legacy.definitionVersion,
            payloadJson = WorkflowCheckpointJson.encode(legacy),
            updatedAtMillis = 1L
        )
        val store = roomStore(key = "wftask-new", legacyKey = "run-legacy")

        val loaded = store.load(definition) as WorkflowCheckpointLoad.Loaded
        assertEquals(3L, loaded.checkpoint.revision)
        assertNotNull("the legacy row is imported once", dao.rows["wftask-new"])
        assertEquals(3L, dao.rows.getValue("wftask-new").revision)
    }

    @Test
    fun aLegacyFileFrameIsImportedOnceAndLaterSavesUseRoom() {
        val legacyRoot = folder.newFolder("checkpoints")
        val files = FileWorkflowCheckpointStore(legacyRoot, key = "wftask-file", legacyKey = "run-file")
        assertTrue(files.save(checkpoint(5)))

        val store = roomStore(
            key = "wftask-file",
            legacyKey = "run-file",
            legacyRoot = legacyRoot
        )
        val loaded = store.load(definition) as WorkflowCheckpointLoad.Loaded
        assertEquals(5L, loaded.checkpoint.revision)
        assertNotNull("the file frame is imported into Room", dao.rows["wftask-file"])

        assertTrue("a newer Room save wins after the import", store.save(checkpoint(6)))
        assertFalse("the file revision no longer wins", store.save(checkpoint(5)))
        val roomLoaded = store.load(definition) as WorkflowCheckpointLoad.Loaded
        assertEquals(6L, reloadRoomRow(store))
        assertEquals(6L, roomLoaded.checkpoint.revision)
    }

    private fun reloadRoomRow(store: WorkflowCheckpointStore): Long =
        (store.load(definition) as WorkflowCheckpointLoad.Loaded).checkpoint.revision

    private fun roomStore(
        key: String = "wftask-a",
        legacyKey: String? = null,
        legacyRoot: File? = null
    ): RoomWorkflowCheckpointStore = RoomWorkflowCheckpointStore(
        dao = dao,
        transactions = runner,
        key = key,
        legacyKey = legacyKey,
        legacyFiles = legacyRoot?.let {
            FileWorkflowCheckpointStore(it, key = key, legacyKey = legacyKey)
        },
        clock = { 1_000L }
    )

    private fun checkpoint(revision: Long): WorkflowRunCheckpoint = WorkflowRunCheckpoint(
        definitionId = "wf-frame",
        definitionVersion = 1,
        revision = revision,
        outcomes = emptyList(),
        nodeOutputs = emptyMap(),
        outputs = emptyMap(),
        activeEdges = emptySet(),
        skipped = emptySet()
    )

    private class DirectRunner : AgentTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private class FakeCheckpointDao : AgentWorkflowCheckpointDao {
        val rows = LinkedHashMap<String, AgentWorkflowCheckpointEntity>()

        override suspend fun find(frameKey: String): AgentWorkflowCheckpointEntity? = rows[frameKey]

        override suspend fun upsert(entity: AgentWorkflowCheckpointEntity) {
            rows[entity.frameKey] = entity
        }

        override suspend fun delete(frameKey: String): Int =
            if (rows.remove(frameKey) != null) 1 else 0
    }
}
