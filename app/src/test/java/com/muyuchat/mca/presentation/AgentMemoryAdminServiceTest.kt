package com.muyuchat.mca.presentation

import com.muyuchat.feature.agent.workspace.AgentMemoryExportResult
import com.muyuchat.feature.agent.workspace.AgentMemoryImportResult
import com.muyuchat.feature.agent.workspace.AgentMemoryListResult
import com.muyuchat.feature.agent.workspace.AgentMemorySpaceCreateResult
import com.muyuchat.feature.agent.workspace.AgentMemoryTextIngestResult
import com.muyuchat.mca.agent.memory.AgentMemoryLimits
import com.muyuchat.mca.agent.memory.AgentMemoryPersistence
import com.muyuchat.mca.agent.memory.AgentMemorySourceKind
import com.muyuchat.mca.agent.memory.AgentMemorySemanticCandidateSource
import com.muyuchat.mca.agent.memory.AgentMemorySemanticQuery
import com.muyuchat.mca.agent.memory.AgentMemorySemanticStatus
import com.muyuchat.mca.agent.memory.AgentMemorySpaceRecord
import com.muyuchat.mca.agent.memory.AgentMemoryStore
import com.muyuchat.mca.agent.memory.InMemoryAgentMemoryPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-mapping tests for the memory-spaces settings adapter.
 *
 * They prove the adapter projects real space records and live (non-revoked) item counts, keeps the
 * absent store metadata null instead of inventing it, and maps a failed read/count to the existing
 * safe error copy rather than a false empty state. They do not prove Room/device rendering; those
 * remain unverified.
 */
class AgentMemoryAdminServiceTest {

    private fun service(persistence: AgentMemoryPersistence): AgentMemoryAdminService =
        AgentMemoryAdminService(
            store = AgentMemoryStore(persistence),
            persistence = persistence,
            ioDispatcher = Dispatchers.Unconfined
        )

    @Test
    fun `a created space accepts user text once and deduplicates the same content`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        val service = service(persistence)

        val created = service.createSpace("  工作笔记  ")
        assertTrue("expected Created, got $created", created is AgentMemorySpaceCreateResult.Created)
        val space = created as AgentMemorySpaceCreateResult.Created
        assertEquals("工作笔记", space.displayName)
        assertEquals(1, persistence.listSpaces(AgentMemoryLimits.MAX_SPACES).size)

        val stored = service.ingestText(
            spaceId = space.spaceId,
            title = "接口约束",
            text = "memory content is authoritative"
        )
        assertTrue("expected Stored, got $stored", stored is AgentMemoryTextIngestResult.Stored)
        val first = stored as AgentMemoryTextIngestResult.Stored
        assertTrue(first.chunkCount >= 1)
        val item = requireNotNull(persistence.findItem(first.memoryId))
        assertEquals("memory content is authoritative", item.text)
        assertEquals(AgentMemorySourceKind.USER_TEXT, item.sourceKind)
        assertEquals(space.spaceId, item.spaceId)

        val duplicate = service.ingestText(
            spaceId = space.spaceId,
            title = "ignored",
            text = "memory content is authoritative"
        )
        assertTrue(
            "expected Deduplicated, got $duplicate",
            duplicate is AgentMemoryTextIngestResult.Deduplicated
        )
        assertEquals(
            first.memoryId,
            (duplicate as AgentMemoryTextIngestResult.Deduplicated).memoryId
        )
        assertEquals(1, persistence.liveItemCount(space.spaceId))
    }

    @Test
    fun `export and import roundtrip creates new spaces and keeps only live content`() = runBlocking {
        val source = InMemoryAgentMemoryPersistence()
        source.seedSpace("source-a")
        source.seedSpace("source-b")
        source.seedItem("m1", "source-a", "alpha content")
        source.seedItem("m2", "source-a", "revoked content", revoked = true)
        source.seedItem("m3", "source-b", "beta content")

        val exported = service(source).exportMemories() as AgentMemoryExportResult.Exported
        assertEquals(2, exported.spaceCount)
        assertEquals(2, exported.itemCount)
        assertTrue(exported.packageJson.contains("alpha content"))
        assertTrue(exported.packageJson.contains("beta content"))
        assertTrue("revoked content must not be exported", !exported.packageJson.contains("revoked content"))

        val target = InMemoryAgentMemoryPersistence()
        val imported = service(target).importMemories(exported.packageJson) as AgentMemoryImportResult.Imported

        assertEquals(2, imported.spaceCount)
        assertEquals(2, imported.itemCount)
        assertEquals(0, imported.deduplicated)
        val targetSpaces = target.listSpaces(AgentMemoryLimits.MAX_SPACES)
        assertEquals(2, targetSpaces.size)
        val texts = targetSpaces.flatMap { space ->
            target.listItemPage(space.spaceId, null, false, 100, 0).map { it.text }
        }.sorted()
        assertEquals(listOf("alpha content", "beta content"), texts)
        val stored = targetSpaces.flatMap { space ->
            target.listItemPage(space.spaceId, null, false, 100, 0)
        }
        assertEquals(
            AgentMemorySourceKind.USER_TEXT,
            stored.first().sourceKind
        )
    }

    @Test
    fun `import counts duplicate content and rejects malformed packages without writing`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        val service = service(persistence)
        val duplicatePackage = """
            {"schema":1,"spaces":[{"displayName":"Imported","items":[
              {"title":"same","text":"duplicate body","sourceRef":null},
              {"title":"same","text":"duplicate body","sourceRef":null}
            ]}]}
        """.trimIndent()

        val imported = service.importMemories(duplicatePackage) as AgentMemoryImportResult.Imported
        assertEquals(1, imported.spaceCount)
        assertEquals(1, imported.itemCount)
        assertEquals(1, imported.deduplicated)
        assertEquals(1, persistence.listSpaces(AgentMemoryLimits.MAX_SPACES).size)

        val before = persistence.listSpaces(AgentMemoryLimits.MAX_SPACES).size
        val malformed = listOf(
            "{not json",
            """{"schema":2,"spaces":[]}""",
            """{"schema":1,"spaces":[{"displayName":"x","items":[],"extra":1}]}""",
            """{"schema":1,"spaces":[{"displayName":"x","items":[{"title":"t","text":"   ","sourceRef":null}]}]}"""
        )
        malformed.forEach { json ->
            val rejected = service.importMemories(json)
            assertTrue("must reject: $json", rejected is AgentMemoryImportResult.Rejected)
        }
        assertEquals(before, persistence.listSpaces(AgentMemoryLimits.MAX_SPACES).size)
    }

    @Test
    fun `export of an unknown space is rejected`() = runBlocking {
        val result = service(InMemoryAgentMemoryPersistence())
            .exportMemories(listOf("missing"))

        assertTrue(result is AgentMemoryExportResult.Rejected)
        assertEquals(
            AgentMemoryAdminService.EXPORT_EMPTY_CODE,
            (result as AgentMemoryExportResult.Rejected).code
        )
    }

    @Test
    fun `blank names and blank text are rejected without writing`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        val service = service(persistence)

        val blankName = service.createSpace("   ")
        assertTrue(blankName is AgentMemorySpaceCreateResult.Rejected)
        assertTrue(persistence.listSpaces(AgentMemoryLimits.MAX_SPACES).isEmpty())

        persistence.seedSpace("space-a")
        val blankText = service.ingestText("space-a", "title", "   ")
        assertTrue(blankText is AgentMemoryTextIngestResult.Rejected)
        assertEquals(0, persistence.liveItemCount("space-a"))
    }

    @Test
    fun `a user edit updates the item once under the current revision`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        persistence.seedItem("mem-1", "space-a", "old text")
        val service = service(persistence)

        val result = service.updateItem(
            spaceId = "space-a",
            itemId = "mem-1",
            expectedRevision = 1L,
            title = "新标题",
            text = "new text"
        )

        val updated = result as com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult.Updated
        assertEquals("space-a", updated.spaceId)
        assertEquals("mem-1", updated.itemId)
        assertEquals(2L, updated.revision)
        val item = persistence.findItem("mem-1")!!
        assertEquals("new text", item.text)
        assertEquals("新标题", item.title)
        assertEquals(2L, item.revision)
    }

    @Test
    fun `a stale or foreign edit is rejected without overwriting`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        persistence.seedSpace("space-b")
        persistence.seedItem("mem-1", "space-a", "old text")
        persistence.seedItem("mem-2", "space-b", "other")
        val service = service(persistence)

        val stale = service.updateItem("space-a", "mem-1", 9L, null, "new text")
                as com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult.Rejected
        assertEquals("MEMORY_REVISION_CONFLICT", stale.code)

        val foreign = service.updateItem("space-a", "mem-2", 1L, null, "new text")
                as com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult.Rejected
        assertEquals("MEMORY_ITEM_NOT_FOUND", foreign.code)
        assertEquals("old text", persistence.findItem("mem-1")!!.text)
        assertEquals("other", persistence.findItem("mem-2")!!.text)
    }

    @Test
    fun `maps every real space with its live item count and no invented metadata`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-b", "Beta", createdAt = 2L)
        persistence.seedSpace("space-a", "Alpha", createdAt = 1L)
        persistence.seedItem("mem-1", "space-a", "first")
        persistence.seedItem("mem-2", "space-a", "second")
        persistence.seedItem("mem-3", "space-b", "revoked", revoked = true)

        val result = service(persistence).list()

        assertTrue("expected a loaded list, got $result", result is AgentMemoryListResult.Loaded)
        val spaces = (result as AgentMemoryListResult.Loaded).spaces
        assertEquals(listOf("space-a", "space-b"), spaces.map { it.spaceId })
        assertEquals(listOf("Alpha", "Beta"), spaces.map { it.displayName })
        assertEquals(listOf(2, 0), spaces.map { it.itemCount })
        spaces.forEach { space ->
            assertEquals("", space.description)
            assertNull(space.egressLabel)
            assertNull(space.updatedLabel)
        }
    }

    @Test
    fun `an empty store is an explicit empty list and never a placeholder space`() = runBlocking {
        val result = service(InMemoryAgentMemoryPersistence()).list()

        assertTrue(result is AgentMemoryListResult.Loaded)
        assertTrue((result as AgentMemoryListResult.Loaded).spaces.isEmpty())
    }

    @Test
    fun `a failed count maps to the existing safe error copy`() = runBlocking {
        val persistence = CountFailingPersistence(InMemoryAgentMemoryPersistence().also { it.seedSpace("space-a", "Alpha") })

        val result = service(persistence).list()

        assertTrue("expected a rejection, got $result", result is AgentMemoryListResult.Rejected)
        val rejected = result as AgentMemoryListResult.Rejected
        assertEquals(AgentMemoryAdminService.FAILURE_CODE, rejected.code)
        assertEquals(AgentMemoryAdminService.FAILURE_MESSAGE, rejected.safeMessage)
    }

    @Test
    fun `a failed space read is an error and never a false empty state`() = runBlocking {
        val result = service(ListFailingPersistence()).list()

        assertTrue("expected a rejection, got $result", result is AgentMemoryListResult.Rejected)
        val rejected = result as AgentMemoryListResult.Rejected
        assertEquals(AgentMemoryAdminService.FAILURE_CODE, rejected.code)
        assertEquals(AgentMemoryAdminService.FAILURE_MESSAGE, rejected.safeMessage)
    }

    @Test
    fun `the failure copy matches the coordinator fallback`() {
        assertEquals("MEMORY_ADMIN_FAILED", AgentMemoryAdminService.FAILURE_CODE)
        assertEquals("记忆空间列表暂时无法载入，请稍后重试。", AgentMemoryAdminService.FAILURE_MESSAGE)
    }

    @Test
    fun `item pages are bounded, keyword filtered and keep revoke visibility explicit`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        persistence.seedSpace("space-b")
        persistence.seedItem("m-1", "space-a", "alpha refund policy")
        persistence.seedItem("m-2", "space-a", "beta shipping policy")
        persistence.seedItem("m-3", "space-a", "gamma refund archived", revoked = true)
        persistence.seedItem("m-4", "space-b", "refund elsewhere")
        val service = service(persistence)

        val loaded = service.listItems("space-a", "refund", offset = 0, limit = 10)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemPageResult.Loaded
        assertEquals(1, loaded.total)
        assertEquals(listOf("m-1"), loaded.items.map { it.itemId })
        assertEquals("space-a", loaded.spaceId)
        assertTrue(loaded.items.all { !it.revoked })

        val withRevoked = service.listItems("space-a", "refund", 0, 10, includeRevoked = true)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemPageResult.Loaded
        assertEquals(2, withRevoked.total)
        assertEquals(listOf("m-1", "m-3"), withRevoked.items.map { it.itemId }.sorted())
        assertTrue(withRevoked.items.any { it.itemId == "m-3" && it.revoked })

        val paged = service.listItems("space-a", null, offset = 0, limit = 1)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemPageResult.Loaded
        assertEquals(2, paged.total)
        assertTrue(paged.hasMore)
        assertEquals(1, paged.items.size)

        val rejected = service.listItems("space-a", null, 0, 0)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemPageResult.Rejected
        assertEquals("MEMORY_PAGE_INVALID", rejected.code)
    }

    @Test
    fun `delete tombstones under CAS and restore brings the item back`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        persistence.seedItem("m-1", "space-a", "refund policy: five days")
        val service = service(persistence)

        val deleted = service.deleteItem("space-a", "m-1", expectedRevision = 1L)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult.Applied
        assertEquals(2L, deleted.revision)
        assertTrue(persistence.findItem("m-1")!!.revoked)

        val staleDelete = service.deleteItem("space-a", "m-1", expectedRevision = 1L)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult.Rejected
        assertEquals("MEMORY_ITEM_NOT_FOUND", staleDelete.code)

        val restored = service.restoreItem("space-a", "m-1", expectedRevision = 2L)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult.Applied
        assertEquals(3L, restored.revision)
        assertTrue(!persistence.findItem("m-1")!!.revoked)

        val staleRestore = service.restoreItem("space-a", "m-1", expectedRevision = 2L)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult.Rejected
        assertEquals("MEMORY_ITEM_NOT_FOUND", staleRestore.code)
    }

    @Test
    fun `a stale or foreign delete is rejected without touching another space`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        persistence.seedSpace("space-b")
        persistence.seedItem("m-1", "space-a", "alpha text")
        persistence.seedItem("m-2", "space-b", "beta text")
        val service = service(persistence)

        val stale = service.deleteItem("space-a", "m-1", expectedRevision = 9L)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult.Rejected
        assertEquals("MEMORY_REVISION_CONFLICT", stale.code)

        val foreign = service.deleteItem("space-a", "m-2", expectedRevision = 1L)
            as com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult.Rejected
        assertEquals("MEMORY_ITEM_NOT_FOUND", foreign.code)

        assertTrue(!persistence.findItem("m-1")!!.revoked)
        assertTrue(!persistence.findItem("m-2")!!.revoked)
    }

    @Test
    fun `extraction candidates are reviewable and only acceptance writes an item`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        val service = service(persistence)

        val submitted = service.submitExtraction(
            spaceId = "space-a",
            text = "请记住：明天九点开会。我喜欢喝乌龙茶。",
            sourceRef = "chat-1"
        ) as com.muyuchat.feature.agent.workspace.AgentMemoryExtractionSubmitResult.Proposed
        assertEquals(2, submitted.candidates.size)
        assertEquals("DISABLED", submitted.modelStatus)
        assertTrue(submitted.candidates.all { it.duplicateOfItemId == null })
        assertEquals(0, persistence.liveItemCount("space-a"))

        val pending = service.listExtractionCandidates("space-a")
            as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateListResult.Loaded
        assertEquals(2, pending.candidates.size)
        assertEquals(submitted.candidates.map { it.candidateId }, pending.candidates.map { it.candidateId })
        assertTrue(pending.submittedAt != null)

        val accepted = service.acceptExtractionCandidates(
            "space-a",
            listOf(pending.candidates.first().candidateId)
        ) as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult.Applied
        assertEquals(1, accepted.stored)
        assertEquals(0, accepted.rejected)
        val storedId = accepted.entries.single().itemId
        assertEquals("明天九点开会", persistence.findItem(requireNotNull(storedId))!!.text)
        assertEquals(1, persistence.liveItemCount("space-a"))

        val remaining = service.listExtractionCandidates("space-a")
            as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateListResult.Loaded
        assertEquals(1, remaining.candidates.size)

        val dismissed = service.rejectExtractionCandidates(
            "space-a",
            listOf(remaining.candidates.single().candidateId)
        ) as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult.Applied
        assertEquals(1, dismissed.dismissed)
        assertEquals(1, persistence.liveItemCount("space-a"))
        val empty = service.listExtractionCandidates("space-a")
            as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateListResult.Loaded
        assertTrue(empty.candidates.isEmpty())
        assertNull(empty.submittedAt)
    }

    @Test
    fun `accepting ids that were never issued writes nothing`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        val service = service(persistence)
        service.submitExtraction("space-a", "记住：周五发版。")

        val unknown = service.acceptExtractionCandidates("space-a", listOf("candidate-bogus"))
            as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult.Rejected
        assertEquals("MEMORY_CANDIDATE_INVALID", unknown.code)

        val empty = service.acceptExtractionCandidates("space-a", emptyList())
            as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult.Rejected
        assertEquals("MEMORY_CANDIDATE_INVALID", empty.code)

        assertEquals(0, persistence.liveItemCount("space-a"))
    }

    @Test
    fun `a new extraction submission replaces the previous pending batch`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        val service = service(persistence)

        val first = service.submitExtraction("space-a", "记住：周一交周报。")
            as com.muyuchat.feature.agent.workspace.AgentMemoryExtractionSubmitResult.Proposed
        val second = service.submitExtraction("space-a", "记住：周二交月报。")
            as com.muyuchat.feature.agent.workspace.AgentMemoryExtractionSubmitResult.Proposed
        assertTrue(first.candidates.single().candidateId != second.candidates.single().candidateId)

        val stale = service.acceptExtractionCandidates(
            "space-a",
            listOf(first.candidates.single().candidateId)
        ) as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult.Rejected
        assertEquals("MEMORY_CANDIDATE_INVALID", stale.code)

        val current = service.listExtractionCandidates("space-a")
            as com.muyuchat.feature.agent.workspace.AgentMemoryCandidateListResult.Loaded
        assertEquals(listOf(second.candidates.single().candidateId), current.candidates.map { it.candidateId })
        assertEquals(0, persistence.liveItemCount("space-a"))
    }

    @Test
    fun `an empty extraction source is rejected with a typed error`() = runBlocking {
        val service = service(InMemoryAgentMemoryPersistence().also { it.seedSpace("space-a") })

        val rejected = service.submitExtraction("space-a", "   ")
            as com.muyuchat.feature.agent.workspace.AgentMemoryExtractionSubmitResult.Rejected
        assertEquals("MEMORY_TEXT_EMPTY", rejected.code)
    }

    @Test
    fun `semantic diagnostics explain the lexical fallback without faking vectors`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")

        val lexical = service(persistence).semanticDiagnostics()

        assertEquals("LEXICAL", lexical.mode)
        assertEquals("NOT_CONFIGURED", lexical.degradedReason)
        assertNull(lexical.fingerprint)
        assertNull(lexical.indexedChunks)
    }

    @Test
    fun `a wired semantic source is reported as hybrid with its fingerprint`() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace("space-a")
        val service = AgentMemoryAdminService(
            store = AgentMemoryStore(persistence, semantic = ReadySemanticSource()),
            persistence = persistence,
            ioDispatcher = Dispatchers.Unconfined
        )

        val diagnostics = service.semanticDiagnostics()

        assertEquals("HYBRID", diagnostics.mode)
        assertEquals("fake-local|d8", diagnostics.fingerprint)
        assertEquals(3, diagnostics.indexedChunks)
        assertNull(diagnostics.degradedReason)
    }

    private class ReadySemanticSource : AgentMemorySemanticCandidateSource {
        override suspend fun candidates(
            spaceIds: List<String>,
            query: String,
            limit: Int
        ): AgentMemorySemanticQuery = AgentMemorySemanticQuery.Available(emptyList(), "fake-local|d8")

        override suspend fun status(): AgentMemorySemanticStatus =
            AgentMemorySemanticStatus.Ready("fake-local|d8", recordCount = 3)
    }

    private class CountFailingPersistence(
        private val delegate: AgentMemoryPersistence
    ) : AgentMemoryPersistence by delegate {
        override suspend fun liveItemCount(spaceId: String): Int = error("count unavailable")
    }

    private class ListFailingPersistence : AgentMemoryPersistence by InMemoryAgentMemoryPersistence() {
        override suspend fun findSpace(spaceId: String): AgentMemorySpaceRecord? =
            error("space read unavailable")

        override suspend fun listSpaces(limit: Int): List<AgentMemorySpaceRecord> =
            error("space read unavailable")
    }
}
