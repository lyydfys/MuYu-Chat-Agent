package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionHasher
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N17b: draft CAS, immutable published versions and canonical hashes on disk. */
class FileAgentWorkflowDefinitionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun definition(
        id: String = "wf-1",
        nodes: List<WorkflowNode> = listOf(trigger(), tool(), output()),
        edges: List<WorkflowEdge> = listOf(
            WorkflowEdge("trigger", "gather"),
            WorkflowEdge("gather", "done")
        )
    ): WorkflowDefinition = WorkflowDefinition(
        definitionId = id,
        version = 1,
        displayName = "测试流程",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = nodes,
        edges = edges
    )

    private fun trigger() = WorkflowNode("trigger", WorkflowNodeType.TRIGGER)

    private fun tool(id: String = "gather") = WorkflowNode(
        id,
        WorkflowNodeType.TOOL,
        configJson = """{"toolKey":"workspace.read_text"}"""
    )

    private fun output() = WorkflowNode(
        "done",
        WorkflowNodeType.OUTPUT,
        configJson = """{"port":"result"}"""
    )

    private fun store(): FileAgentWorkflowDefinitionStore =
        FileAgentWorkflowDefinitionStore(folder.root)

    @Test
    fun aDraftPersistsAcrossReloadWithAMonotonicRevision() {
        val first = store()
        val draft = first.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)
        assertEquals(1L, draft.revision)
        assertEquals(1L, draft.definition.version)
        assertEquals("", draft.definition.definitionHash)

        val reloaded = store()
        val again = reloaded.draft("wf-1")!!
        assertEquals(1L, again.revision)
        assertEquals("测试流程", again.definition.displayName)
        assertEquals(1, reloaded.list().size)
    }

    @Test
    fun draftUpdatesAreCompareAndSet() {
        val store = store()
        store.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)

        val conflict = runCatching {
            store.saveDraft(definition(), expectedRevision = 99L, nowMillis = 1_100L)
        }.exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.VERSION_CONFLICT, conflict.code)

        val updated = store.saveDraft(definition(), expectedRevision = 1L, nowMillis = 1_200L)
        assertEquals(2L, updated.revision)
    }

    @Test
    fun publishingFreezesAVersionWithACanonicalHash() {
        val store = store()
        store.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)

        val published = store.publish("wf-1", expectedDraftRevision = 1L, nowMillis = 1_100L)

        assertEquals(1L, published.version)
        assertEquals(64, published.definitionHash.length)
        assertEquals(WorkflowDefinitionHasher.hash(published), published.definitionHash)
        assertEquals(1L, store.latest("wf-1")!!.version)
        assertEquals(published.definitionHash, store.version("wf-1", 1L)!!.definitionHash)

        val reloaded = store()
        assertEquals(published.definitionHash, reloaded.latest("wf-1")!!.definitionHash)
    }

    @Test
    fun laterDraftEditsNeverRewriteAPublishedVersion() {
        val store = store()
        store.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)
        val v1 = store.publish("wf-1", expectedDraftRevision = 1L, nowMillis = 1_100L)

        store.saveDraft(
            definition().copy(displayName = "改名流程"),
            expectedRevision = 1L,
            nowMillis = 1_200L
        )
        assertEquals("a draft edit must not touch v1", v1.definitionHash, store.version("wf-1", 1L)!!.definitionHash)

        val v2 = store.publish("wf-1", expectedDraftRevision = 2L, nowMillis = 1_300L)
        assertEquals(2L, v2.version)
        assertNotEquals(v1.definitionHash, v2.definitionHash)
        assertEquals("改名流程", v2.displayName)
        assertEquals(v1.definitionHash, store.version("wf-1", 1L)!!.definitionHash)
    }

    @Test
    fun anInvalidGraphIsNeverPublished() {
        val cycle = definition(
            nodes = listOf(trigger(), tool(), output(), tool("extra")),
            edges = listOf(
                WorkflowEdge("trigger", "gather"),
                WorkflowEdge("gather", "extra"),
                WorkflowEdge("extra", "gather"),
                WorkflowEdge("gather", "done")
            )
        )
        val store = store()
        store.saveDraft(cycle, expectedRevision = null, nowMillis = 1_000L)

        val failure = runCatching {
            store.publish("wf-1", expectedDraftRevision = 1L, nowMillis = 1_100L)
        }.exceptionOrNull() as AgentWorkflowException

        assertEquals(AgentWorkflowErrorCodes.RECIPE_INVALID, failure.code)
        assertNull(store.latest("wf-1"))
    }

    @Test
    fun publishingWithoutADraftIsNotFound() {
        val store = store()
        val failure = runCatching {
            store.publish("missing", expectedDraftRevision = 1L, nowMillis = 1_000L)
        }.exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.NOT_FOUND, failure.code)
    }

    @Test
    fun aCorruptOrTamperedFileFailsClosed() {
        val store = store()
        store.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)
        store.publish("wf-1", expectedDraftRevision = 1L, nowMillis = 1_100L)
        val file = File(folder.root, "workflow-definitions.json")
        val json = JSONObject(file.readText())
        val record = json.getJSONArray("definitions").getJSONObject(0)
        record.getJSONArray("published").getJSONObject(0)
            .put("definitionHash", "0".repeat(64))
        file.writeText(json.toString())

        val failure = runCatching { store() }.exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.STORE_CORRUPT, failure.code)

        val corrupt = File(folder.root, "workflow-definitions.json")
        corrupt.writeText("{not json")
        val corruptFailure = runCatching { FileAgentWorkflowDefinitionStore(folder.root) }
            .exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.STORE_CORRUPT, corruptFailure.code)
    }

    @Test
    fun aDraftForANewDefinitionRequiresCreateSemantics() {
        val store = store()
        store.saveDraft(definition("wf-2"), expectedRevision = null, nowMillis = 1_000L)
        val failure = runCatching {
            store.saveDraft(definition("wf-2"), expectedRevision = 0L, nowMillis = 1_100L)
        }.exceptionOrNull() as AgentWorkflowException
        assertEquals(AgentWorkflowErrorCodes.VERSION_CONFLICT, failure.code)
    }

    @Test
    fun theStoredFileHoldsCanonicalHashAndNeverDraftHashes() {
        val store = store()
        store.saveDraft(definition(), expectedRevision = null, nowMillis = 1_000L)
        store.publish("wf-1", expectedDraftRevision = 1L, nowMillis = 1_100L)
        val json = JSONObject(File(folder.root, "workflow-definitions.json").readText())
        val record = json.getJSONArray("definitions").getJSONObject(0)
        assertTrue(record.getJSONObject("draft").getString("definitionHash").isEmpty())
        assertEquals(
            64,
            record.getJSONArray("published").getJSONObject(0).getString("definitionHash").length
        )
    }
}
