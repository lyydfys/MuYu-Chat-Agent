package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store-level behavior: bounded ingest, source-hash dedupe, revoke/delete semantics and the
 * explicit-write-approval model.
 *
 * These are pure JVM tests over [InMemoryAgentMemoryPersistence]; the Room mapping and migration are
 * covered by `AgentMemorySchemaSqlTest` and the instrumentation migration test.
 */
class AgentMemoryStoreTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val store = AgentMemoryTestFixtures.store(persistence, AgentMemoryTestFixtures.sequentialIds())

    @Test
    fun `item updates use revision CAS and replace chunks atomically`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "old text")
        val approval = AgentMemoryTestFixtures.itemsApproval("space-1")
        val before = persistence.findItem("memory-1")!!

        val applied = store.updateItem(
            spaceId = "space-1",
            memoryId = "memory-1",
            expectedRevision = before.revision,
            title = "新标题",
            text = "new text",
            approval = approval
        )
        assertEquals(AgentMemoryMutationOutcome.Applied, applied)

        val updated = persistence.findItem("memory-1")!!
        assertEquals("new text", updated.text)
        assertEquals("新标题", updated.title)
        assertEquals(before.revision + 1, updated.revision)
        assertEquals(AgentMemoryText.sha256("new text"), updated.contentHash)
        val chunks = persistence.liveChunkPage(listOf("space-1"), 10, 0)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.text.contains("new text") })
        assertTrue("old chunks must be gone", chunks.none { it.text.contains("old text") })
    }

    @Test
    fun `a stale revision is a typed conflict and leaves the item untouched`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "old text")
        val approval = AgentMemoryTestFixtures.itemsApproval("space-1")

        val rejected = store.updateItem(
            spaceId = "space-1",
            memoryId = "memory-1",
            expectedRevision = 99L,
            title = null,
            text = "new text",
            approval = approval
        ) as AgentMemoryMutationOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.REVISION_CONFLICT, rejected.error.code)
        assertEquals("old text", persistence.findItem("memory-1")!!.text)
    }

    @Test
    fun `foreign revoked or duplicate updates are rejected`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-1", "space-1", "first")
        persistence.seedItem("memory-2", "space-1", "second")
        persistence.seedItem("memory-3", "space-2", "other space")
        persistence.seedItem("memory-4", "space-1", "revoked", revoked = true)
        val approval = AgentMemoryTestFixtures.itemsApproval("space-1")

        val foreign = store.updateItem("space-1", "memory-3", 1L, null, "x", approval)
                as AgentMemoryMutationOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.ITEM_NOT_FOUND, foreign.error.code)

        val revoked = store.updateItem("space-1", "memory-4", 1L, null, "x", approval)
                as AgentMemoryMutationOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.ITEM_NOT_FOUND, revoked.error.code)

        val duplicate = store.updateItem("space-1", "memory-1", 1L, null, "second", approval)
                as AgentMemoryMutationOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.DUPLICATE_CONTENT, duplicate.error.code)

        val noApproval = store.updateItem(
            "space-1",
            "memory-1",
            1L,
            null,
            "third",
            AgentMemoryTestFixtures.itemsApproval("space-2")
        ) as AgentMemoryMutationOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, noApproval.error.code)
        assertEquals("first", persistence.findItem("memory-1")!!.text)
    }

    @Test
    fun `space creation needs the space-admin scope and rejects an item approval`() = runBlocking {
        val rejected = store.createSpace("Work", AgentMemoryTestFixtures.itemsApproval("space-1"))
        assertTrue(rejected is AgentMemorySpaceOutcome.Rejected)
        assertEquals(
            AgentMemoryErrorCodes.WRITE_NOT_APPROVED,
            (rejected as AgentMemorySpaceOutcome.Rejected).error.code
        )
        assertTrue(persistence.listSpaces(10).isEmpty())

        val created = store.createSpace("  Work  ", AgentMemoryTestFixtures.adminApproval())
        assertTrue(created is AgentMemorySpaceOutcome.Created)
        val space = (created as AgentMemorySpaceOutcome.Created).space
        assertEquals("Work", space.displayName)
        assertEquals(AgentMemoryTestFixtures.NOW, space.createdAt)
        assertEquals(listOf(space), store.listSpaces())
    }

    @Test
    fun `space name bounds are enforced`() = runBlocking {
        val blank = store.createSpace("   ", AgentMemoryTestFixtures.adminApproval())
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NAME_INVALID,
            (blank as AgentMemorySpaceOutcome.Rejected).error.code
        )
        val long = store.createSpace(
            "x".repeat(AgentMemoryLimits.MAX_SPACE_NAME_CHARS + 1),
            AgentMemoryTestFixtures.adminApproval()
        )
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NAME_INVALID,
            (long as AgentMemorySpaceOutcome.Rejected).error.code
        )
    }

    @Test
    fun `ingest stores one item and its chunks with a stable source hash`() = runBlocking {
        val space = createSpace("Notes")
        val text = "Alpha note about the launch plan.\nBeta note about cancellation."
        val outcome = store.ingest(
            space,
            AgentMemoryIngestRequest("Launch", text, AgentMemorySourceKind.USER_TEXT, "notes.txt"),
            AgentMemoryTestFixtures.itemsApproval(space)
        )
        assertTrue(outcome is AgentMemoryIngestOutcome.Ingested)
        val item = (outcome as AgentMemoryIngestOutcome.Ingested).item
        assertEquals(space, item.spaceId)
        assertEquals("Launch", item.title)
        assertEquals(AgentMemoryText.sha256(text), item.contentHash)
        assertTrue(item.contentHash.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(1, item.chunkCount)
        assertTrue(item.tokenCount > 0)
        assertFalse(item.revoked)
        assertEquals(1, persistence.liveItemCount(space))
        assertEquals(1, persistence.liveChunkCount(listOf(space)))
    }

    @Test
    fun `ingest normalizes CRLF and BOM before hashing so a re-import dedupes`() = runBlocking {
        val space = createSpace("Notes")
        val first = store.ingest(
            space,
            AgentMemoryIngestRequest("", "line one\r\nline two", AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(space)
        )
        val item = (first as AgentMemoryIngestOutcome.Ingested).item
        assertEquals("line one\nline two", item.text)
        assertEquals("line one", item.title)

        val second = store.ingest(
            space,
            AgentMemoryIngestRequest("", "\uFEFFline one\nline two\n", AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(space)
        )
        assertTrue(second is AgentMemoryIngestOutcome.Deduplicated)
        val duplicate = second as AgentMemoryIngestOutcome.Deduplicated
        assertEquals(item.memoryId, duplicate.memoryId)
        assertFalse(duplicate.revoked)
        assertEquals(1, persistence.liveItemCount(space))
    }

    @Test
    fun `oversized text is rejected, never truncated or stored`() = runBlocking {
        val space = createSpace("Notes")
        val oversized = "a".repeat(AgentMemoryLimits.MAX_TEXT_BYTES + 1)
        val outcome = store.ingest(
            space,
            AgentMemoryIngestRequest("Big", oversized, AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(space)
        )
        assertTrue(outcome is AgentMemoryIngestOutcome.Rejected)
        assertEquals(
            AgentMemoryErrorCodes.ITEM_TEXT_TOO_LARGE,
            (outcome as AgentMemoryIngestOutcome.Rejected).error.code
        )
        assertEquals(0, persistence.liveItemCount(space))
    }

    @Test
    fun `blank text, oversized title, oversized source ref and empty space are rejected`() = runBlocking {
        val space = createSpace("Notes")
        val approval = AgentMemoryTestFixtures.itemsApproval(space)
        assertEquals(
            AgentMemoryErrorCodes.TEXT_EMPTY,
            rejectedIngest(store.ingest(space, AgentMemoryIngestRequest("t", "   ", AgentMemorySourceKind.USER_TEXT), approval))
        )
        assertEquals(
            AgentMemoryErrorCodes.TITLE_TOO_LARGE,
            rejectedIngest(
                store.ingest(
                    space,
                    AgentMemoryIngestRequest(
                        "t".repeat(AgentMemoryLimits.MAX_TITLE_CHARS + 1),
                        "text",
                        AgentMemorySourceKind.USER_TEXT
                    ),
                    approval
                )
            )
        )
        assertEquals(
            AgentMemoryErrorCodes.SOURCE_REF_TOO_LARGE,
            rejectedIngest(
                store.ingest(
                    space,
                    AgentMemoryIngestRequest(
                        "t",
                        "text",
                        AgentMemorySourceKind.FILE_IMPORT,
                        "r".repeat(AgentMemoryLimits.MAX_SOURCE_REF_CHARS + 1)
                    ),
                    approval
                )
            )
        )
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NOT_FOUND,
            rejectedIngest(
                store.ingest(
                    "missing",
                    AgentMemoryIngestRequest("t", "text", AgentMemorySourceKind.USER_TEXT),
                    AgentMemoryTestFixtures.itemsApproval("missing")
                )
            )
        )
    }

    @Test
    fun `a write approval only authorizes its own space`() = runBlocking {
        val first = createSpace("One")
        val second = createSpace("Two")
        val outcome = store.ingest(
            second,
            AgentMemoryIngestRequest("t", "text", AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(first)
        )
        assertEquals(
            AgentMemoryErrorCodes.WRITE_NOT_APPROVED,
            rejectedIngest(outcome)
        )
        assertEquals(0, persistence.liveItemCount(second))
    }

    @Test
    fun `revoke hides an item from retrieval and keeps it addressable for audit`() = runBlocking {
        val space = createSpace("Notes")
        val item = ingest(space, "Refund policy: refunds take five days.")
        assertEquals(
            AgentMemoryMutationOutcome.Applied,
            store.revokeItem(
                space,
                item.memoryId,
                item.revision,
                AgentMemoryTestFixtures.itemsApproval(space)
            )
        )
        val stored = requireNotNull(persistence.findItem(item.memoryId))
        assertTrue(stored.revoked)
        assertEquals(AgentMemoryTestFixtures.NOW, stored.revokedAt)
        assertEquals(0, persistence.liveItemCount(space))
        assertEquals(0, persistence.liveChunkCount(listOf(space)))
        assertTrue(
            persistence.liveChunkPage(listOf(space), 10, 0).isEmpty()
        )
        // A second revoke is not an implicit success.
        assertEquals(
            AgentMemoryErrorCodes.ITEM_NOT_FOUND,
            rejectedMutation(
                store.revokeItem(
                    space,
                    item.memoryId,
                    item.revision,
                    AgentMemoryTestFixtures.itemsApproval(space)
                )
            )
        )
        // Re-importing the same source reports the tombstone instead of duplicating it.
        val again = store.ingest(
            space,
            AgentMemoryIngestRequest("", "Refund policy: refunds take five days.", AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(space)
        )
        assertTrue(again is AgentMemoryIngestOutcome.Deduplicated)
        assertTrue((again as AgentMemoryIngestOutcome.Deduplicated).revoked)
        assertEquals(item.memoryId, again.memoryId)
    }

    @Test
    fun `delete removes the item and its chunks, and a space delete removes both`() = runBlocking {
        val space = createSpace("Notes")
        val first = ingest(space, "first memory")
        val second = ingest(space, "second memory")
        assertEquals(
            AgentMemoryMutationOutcome.Applied,
            store.deleteItem(space, first.memoryId, AgentMemoryTestFixtures.itemsApproval(space))
        )
        assertEquals(1, persistence.liveItemCount(space))
        assertNull(persistence.findItem(first.memoryId))
        assertNotNull(persistence.findItem(second.memoryId))
        assertTrue(
            persistence.liveChunkPage(listOf(space), 100, 0).none { it.memoryId == first.memoryId }
        )

        assertEquals(
            AgentMemoryMutationOutcome.Applied,
            store.deleteSpace(space, AgentMemoryTestFixtures.adminApproval())
        )
        assertNull(persistence.findSpace(space))
        assertEquals(0, persistence.liveItemCount(space))
        assertTrue(persistence.existingSpaceIds(listOf(space)).isEmpty())
    }

    @Test
    fun `deleting a space requires the space-admin scope`() = runBlocking {
        val space = createSpace("Notes")
        assertEquals(
            AgentMemoryErrorCodes.WRITE_NOT_APPROVED,
            rejectedMutation(store.deleteSpace(space, AgentMemoryTestFixtures.itemsApproval(space)))
        )
        assertNotNull(persistence.findSpace(space))
    }

    @Test
    fun `delete requires the current revision and never crosses the space boundary`() = runBlocking {
        val space = createSpace("Notes")
        val other = createSpace("Other")
        val item = ingest(space, "refund policy: five days")
        val approval = AgentMemoryTestFixtures.itemsApproval(space)

        assertEquals(
            AgentMemoryErrorCodes.REVISION_CONFLICT,
            rejectedMutation(store.revokeItem(space, item.memoryId, item.revision + 9, approval))
        )
        assertFalse(persistence.findItem(item.memoryId)!!.revoked)

        assertEquals(
            AgentMemoryErrorCodes.ITEM_NOT_FOUND,
            rejectedMutation(
                store.revokeItem(other, item.memoryId, item.revision, AgentMemoryTestFixtures.itemsApproval(other))
            )
        )
        assertFalse(persistence.findItem(item.memoryId)!!.revoked)

        assertEquals(
            AgentMemoryErrorCodes.WRITE_NOT_APPROVED,
            rejectedMutation(store.revokeItem(space, item.memoryId, item.revision, AgentMemoryTestFixtures.adminApproval()))
        )
        assertEquals(
            AgentMemoryMutationOutcome.Applied,
            store.revokeItem(space, item.memoryId, item.revision, approval)
        )
    }

    @Test
    fun `restore revives a revoked item under CAS and makes it searchable again`() = runBlocking {
        val space = createSpace("Notes")
        val item = ingest(space, "refund policy: five days")
        val approval = AgentMemoryTestFixtures.itemsApproval(space)
        val grant = AgentMemoryReadGrant("task-1", listOf(space))

        store.revokeItem(space, item.memoryId, item.revision, approval)
        val revoked = requireNotNull(persistence.findItem(item.memoryId))
        assertTrue(revoked.revoked)
        assertTrue(searchMemory(grant, "refund").citations.isEmpty())

        assertEquals(
            AgentMemoryErrorCodes.REVISION_CONFLICT,
            rejectedMutation(store.restoreItem(space, item.memoryId, item.revision, approval))
        )
        assertEquals(
            AgentMemoryMutationOutcome.Applied,
            store.restoreItem(space, item.memoryId, revoked.revision, approval)
        )
        val restored = requireNotNull(persistence.findItem(item.memoryId))
        assertFalse(restored.revoked)
        assertNull(restored.revokedAt)
        assertEquals(revoked.revision + 1, restored.revision)
        assertEquals(1, persistence.liveItemCount(space))
        assertEquals(listOf(item.memoryId), searchMemory(grant, "refund").citations.map { it.memoryId })

        // Restoring an already-live item is not an implicit success.
        assertEquals(
            AgentMemoryErrorCodes.ITEM_NOT_FOUND,
            rejectedMutation(store.restoreItem(space, item.memoryId, restored.revision, approval))
        )
    }

    @Test
    fun `the management page filters, pages and scopes to one space`() = runBlocking {
        val space = createSpace("Notes")
        val other = createSpace("Other")
        val alpha = ingest(space, "alpha refund policy")
        ingest(space, "beta shipping policy")
        ingest(space, "gamma refund deadline")
        val revoked = ingest(space, "delta refund archived")
        store.revokeItem(space, revoked.memoryId, revoked.revision, AgentMemoryTestFixtures.itemsApproval(space))
        ingest(other, "refund in another space")

        val firstPage = store.listItemPage(space, "refund", includeRevoked = false, offset = 0, limit = 1)
        val page = (firstPage as AgentMemoryItemPageOutcome.Found).page
        assertEquals(2, page.total)
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("alpha", page.items.single().title.take(5))
        assertTrue(page.items.none { it.revoked })

        val withRevoked = (
            store.listItemPage(space, "refund", includeRevoked = true, offset = 0, limit = 10)
                as AgentMemoryItemPageOutcome.Found
            ).page
        assertEquals(3, withRevoked.total)
        assertEquals(3, withRevoked.items.size)
        assertTrue(withRevoked.items.any { it.memoryId == revoked.memoryId && it.revoked })
        assertTrue(withRevoked.items.none { it.spaceId == other })
        assertTrue(withRevoked.items.all { it.excerpt.length <= AgentMemoryLimits.MAX_ADMIN_EXCERPT_CHARS })
        assertTrue(withRevoked.items.first { it.memoryId == alpha.memoryId }.hasSourceRef.not())
    }

    @Test
    fun `invalid management page requests are typed and never read storage`() = runBlocking {
        val space = createSpace("Notes")
        ingest(space, "alpha refund policy")

        assertEquals(
            AgentMemoryErrorCodes.PAGE_INVALID,
            rejectedPage(store.listItemPage(space, null, false, 0, 0))
        )
        assertEquals(
            AgentMemoryErrorCodes.PAGE_INVALID,
            rejectedPage(store.listItemPage(space, null, false, 0, AgentMemoryLimits.MAX_ADMIN_PAGE_SIZE + 1))
        )
        assertEquals(
            AgentMemoryErrorCodes.PAGE_INVALID,
            rejectedPage(store.listItemPage(space, null, false, AgentMemoryLimits.MAX_ADMIN_OFFSET + 1, 5))
        )
        assertEquals(
            AgentMemoryErrorCodes.QUERY_TOO_LONG,
            rejectedPage(
                store.listItemPage(space, "q".repeat(AgentMemoryLimits.MAX_ADMIN_QUERY_CHARS + 1), false, 0, 5)
            )
        )
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NOT_FOUND,
            rejectedPage(store.listItemPage("space-missing", null, false, 0, 5))
        )
    }

    @Test
    fun `chunker rejects a text that would exceed the chunk budget`() {
        val huge = "word ".repeat(100_000)
        val failure = runCatching { AgentMemoryChunker.chunk("memory-1", "space-1", huge, 1L) }.exceptionOrNull()
        assertTrue(failure is AgentMemoryTextException)
        assertEquals(
            AgentMemoryErrorCodes.CHUNK_LIMIT,
            (failure as AgentMemoryTextException).error.code
        )
    }

    @Test
    fun `storage failure is reported as a typed error and does not become a partial success`() = runBlocking {
        val space = createSpace("Notes")
        persistence.failWrites = true
        val outcome = store.ingest(
            space,
            AgentMemoryIngestRequest("t", "text", AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(space)
        )
        assertEquals(
            AgentMemoryErrorCodes.STORAGE_FAILED,
            rejectedIngest(outcome)
        )
        assertEquals(0, persistence.liveItemCount(space))
    }

    @Test
    fun `source kind round-trips and unknown stored kinds fall back to user text`() {
        assertEquals(AgentMemorySourceKind.FILE_IMPORT, AgentMemorySourceKind.fromStored("FILE_IMPORT"))
        assertEquals(AgentMemorySourceKind.USER_TEXT, AgentMemorySourceKind.fromStored("SOMETHING_ELSE"))
    }

    private suspend fun createSpace(name: String): String {
        val outcome = store.createSpace(name, AgentMemoryTestFixtures.adminApproval())
        return (outcome as AgentMemorySpaceOutcome.Created).space.spaceId
    }

    private suspend fun ingest(spaceId: String, text: String): AgentMemoryItemRecord {
        val outcome = store.ingest(
            spaceId,
            AgentMemoryIngestRequest("", text, AgentMemorySourceKind.USER_TEXT),
            AgentMemoryTestFixtures.itemsApproval(spaceId)
        )
        return (outcome as AgentMemoryIngestOutcome.Ingested).item
    }

    private fun rejectedIngest(outcome: AgentMemoryIngestOutcome): String =
        (outcome as AgentMemoryIngestOutcome.Rejected).error.code

    private fun rejectedMutation(outcome: AgentMemoryMutationOutcome): String =
        (outcome as AgentMemoryMutationOutcome.Rejected).error.code

    private fun rejectedPage(outcome: AgentMemoryItemPageOutcome): String =
        (outcome as AgentMemoryItemPageOutcome.Rejected).error.code

    private suspend fun searchMemory(
        grant: AgentMemoryReadGrant,
        query: String
    ): AgentMemorySearchResult =
        (store.search(grant, query, null, AgentMemoryLimits.DEFAULT_MAX_ITEMS) as
            AgentMemorySearchOutcome.Found).result
}
