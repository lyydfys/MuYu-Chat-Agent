package com.muyuchat.mca

import com.muyuchat.mca.presentation.KnowledgeLibraryChange
import com.muyuchat.mca.presentation.KnowledgeLibraryOperations
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import com.muyuchat.mca.presentation.KnowledgeLibrarySnapshot
import com.muyuchat.mca.presentation.knowledgeLibrarySnapshot
import com.muyuchat.mca.presentation.withKnowledgeLibraryChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the knowledge-library operations now that they live outside `MainViewModel`.
 *
 * These are the guards for the migration that moved them: they pin the success messages, the
 * failure messages, the "unknown id is a silent no-op" rule, and -- most importantly -- that a
 * failure publishes *only* a status line. That last one is easy to get wrong when a change object is
 * applied mechanically, and it is the difference between "the delete failed" and "the delete failed
 * and the list silently emptied itself".
 */
class KnowledgeLibraryOperationsTest {

    private class FakePort(
        var worldBooks: List<WorldBookRecord> = emptyList(),
        var created: KnowledgeBaseRecord? = null,
        var throwOnWorldBookRemove: Throwable? = null,
        var throwOnCreate: Throwable? = null,
        var throwOnKnowledgeRemove: Throwable? = null,
        var throwOnUpsert: Throwable? = null
    ) : KnowledgeLibraryPort {
        var removeWorldBookCalls = 0
        var createCalls = 0
        var removeKnowledgeCalls = 0
        var upsertCalls = 0
        var upserted: WorldBookRecord? = null

        override fun removeWorldBook(id: String): List<WorldBookRecord> {
            removeWorldBookCalls++
            throwOnWorldBookRemove?.let { throw it }
            return worldBooks
        }

        override fun createKnowledgeBase(name: String, description: String): KnowledgeBaseRecord {
            createCalls++
            throwOnCreate?.let { throw it }
            return created ?: error("test port has no created record")
        }

        override fun removeKnowledgeBase(id: String) {
            removeKnowledgeCalls++
            throwOnKnowledgeRemove?.let { throw it }
        }

        override fun upsertWorldBook(book: WorldBookRecord): List<WorldBookRecord> {
            upsertCalls++
            throwOnUpsert?.let { throw it }
            upserted = book
            return worldBooks
        }
    }

    private fun worldBook(id: String, name: String) = WorldBookRecord(
        id = id,
        name = name,
        // WorldBookRecord requires at least one entry; the operations under test never read them.
        entries = listOf(WorldBookEntry(content = "entry for $name"))
    )

    private fun knowledgeBase(id: String, name: String) = KnowledgeBaseRecord(id = id, name = name)

    private fun snapshot(
        worldBooks: List<WorldBookRecord> = emptyList(),
        knowledgeBases: List<KnowledgeBaseRecord> = emptyList(),
        counts: Map<String, Int> = emptyMap(),
        importing: Set<String> = emptySet(),
        selected: Set<String> = emptySet()
    ) = KnowledgeLibrarySnapshot(worldBooks, knowledgeBases, counts, importing, selected)

    // ---- deleteWorldBook -------------------------------------------------------------------

    @Test
    fun `deleting an unknown world book is a silent no-op that never touches storage`() {
        val port = FakePort()
        val change = KnowledgeLibraryOperations.deleteWorldBook(port, snapshot(), "missing")
        assertNull("an unknown id must not produce a change", change)
        assertEquals("storage must not be called for an unknown id", 0, port.removeWorldBookCalls)
    }

    @Test
    fun `deleting a world book publishes the store result and the removed name`() {
        val remaining = listOf(worldBook("b", "别的书"))
        val port = FakePort(worldBooks = remaining)
        val change = KnowledgeLibraryOperations.deleteWorldBook(
            port,
            snapshot(worldBooks = listOf(worldBook("a", "被删的书"), worldBook("b", "别的书"))),
            "a"
        )
        assertNotNull(change)
        assertEquals(remaining, change!!.worldBooks)
        assertEquals("已删除世界书：被删的书", change.statusMessage)
        assertNull("only the world books may change", change.knowledgeBases)
        assertEquals(1, port.removeWorldBookCalls)
    }

    @Test
    fun `a failing world book delete reports only a status line and keeps the list`() {
        val port = FakePort(throwOnWorldBookRemove = IllegalStateException("disk gone"))
        val change = KnowledgeLibraryOperations.deleteWorldBook(
            port,
            snapshot(worldBooks = listOf(worldBook("a", "书"))),
            "a"
        )
        assertNotNull(change)
        assertEquals("世界书删除失败：disk gone", change!!.statusMessage)
        assertNull("a failure must not publish a new list", change.worldBooks)
    }

    @Test
    fun `a world book delete failure without a message uses the documented fallback wording`() {
        val port = FakePort(throwOnWorldBookRemove = IllegalStateException())
        val change = KnowledgeLibraryOperations.deleteWorldBook(
            port,
            snapshot(worldBooks = listOf(worldBook("a", "书"))),
            "a"
        )
        assertEquals("世界书删除失败：请重试", change!!.statusMessage)
    }

    // ---- createKnowledgeBase ---------------------------------------------------------------

    @Test
    fun `creating a knowledge base merges, sorts case-insensitively and seeds the count at zero`() {
        val created = knowledgeBase("new", "beta")
        val port = FakePort(created = created)
        val change = KnowledgeLibraryOperations.createKnowledgeBase(
            port,
            snapshot(
                knowledgeBases = listOf(knowledgeBase("x", "Zeta"), knowledgeBase("y", "alpha")),
                counts = mapOf("x" to 3, "y" to 1)
            ),
            "beta",
            ""
        )
        assertEquals(listOf("alpha", "beta", "Zeta"), change.knowledgeBases!!.map { it.name })
        assertEquals(mapOf("x" to 3, "y" to 1, "new" to 0), change.knowledgeDocumentCounts)
        assertEquals("已创建知识库：beta", change.statusMessage)
    }

    @Test
    fun `creating a knowledge base that already exists replaces the previous entry`() {
        val created = knowledgeBase("dup", "同名")
        val port = FakePort(created = created)
        val change = KnowledgeLibraryOperations.createKnowledgeBase(
            port,
            snapshot(knowledgeBases = listOf(knowledgeBase("dup", "同名"), knowledgeBase("z", "其它"))),
            "同名",
            ""
        )
        assertEquals("the replacement must not duplicate the id", 1, change.knowledgeBases!!.count { it.id == "dup" })
        assertEquals(2, change.knowledgeBases!!.size)
    }

    @Test
    fun `a failing create publishes only the status line, not an empty library`() {
        val port = FakePort(throwOnCreate = IllegalArgumentException("名称无效"))
        val change = KnowledgeLibraryOperations.createKnowledgeBase(port, snapshot(), "", "")
        assertEquals("知识库创建失败：名称无效", change.statusMessage)
        assertNull("a failure must not touch the list", change.knowledgeBases)
        assertNull("a failure must not touch the counts", change.knowledgeDocumentCounts)
    }

    @Test
    fun `a create failure without a message uses the documented fallback wording`() {
        val port = FakePort(throwOnCreate = IllegalArgumentException())
        val change = KnowledgeLibraryOperations.createKnowledgeBase(port, snapshot(), "x", "")
        assertEquals("知识库创建失败：名称无效", change.statusMessage)
    }

    // ---- deleteKnowledgeBase ---------------------------------------------------------------

    @Test
    fun `deleting an unknown knowledge base is a silent no-op`() {
        val port = FakePort()
        assertNull(KnowledgeLibraryOperations.deleteKnowledgeBase(port, snapshot(), "missing"))
        assertEquals(0, port.removeKnowledgeCalls)
    }

    @Test
    fun `deleting a knowledge base clears all four dependent fields in one change`() {
        val port = FakePort()
        val change = KnowledgeLibraryOperations.deleteKnowledgeBase(
            port,
            snapshot(
                knowledgeBases = listOf(knowledgeBase("keep", "保留"), knowledgeBase("doomed", "删除")),
                counts = mapOf("keep" to 2, "doomed" to 7),
                importing = setOf("keep", "doomed"),
                selected = setOf("doomed")
            ),
            "doomed"
        )
        assertNotNull(change)
        assertEquals(listOf("keep"), change!!.knowledgeBases!!.map { it.id })
        assertEquals(mapOf("keep" to 2), change.knowledgeDocumentCounts)
        assertEquals(setOf("keep"), change.knowledgeBaseImportingIds)
        assertTrue("the deleted base must leave the selection", change.selectedKnowledgeBaseIds!!.isEmpty())
        assertEquals("已删除知识库：删除", change.statusMessage)
    }

    @Test
    fun `a failing knowledge base delete reports only a status line`() {
        val port = FakePort(throwOnKnowledgeRemove = IllegalStateException("locked"))
        val change = KnowledgeLibraryOperations.deleteKnowledgeBase(
            port,
            snapshot(knowledgeBases = listOf(knowledgeBase("a", "书"))),
            "a"
        )
        assertEquals("知识库删除失败：locked", change!!.statusMessage)
        assertNull(change.knowledgeBases)
        assertNull(change.knowledgeDocumentCounts)
    }

    @Test
    fun `a knowledge base delete failure without a message uses the documented fallback wording`() {
        val port = FakePort(throwOnKnowledgeRemove = IllegalStateException())
        val change = KnowledgeLibraryOperations.deleteKnowledgeBase(
            port,
            snapshot(knowledgeBases = listOf(knowledgeBase("a", "书"))),
            "a"
        )
        assertEquals("知识库删除失败：请重试", change!!.statusMessage)
    }

    // ---- applying a change ----------------------------------------------------------------

    @Test
    fun `a null field in a change means unchanged, not cleared`() {
        val original = MainUiState(
            worldBooks = listOf(worldBook("keep", "保留")),
            knowledgeBases = listOf(knowledgeBase("kb", "库")),
            knowledgeDocumentCounts = mapOf("kb" to 4),
            knowledgeBaseImportingIds = setOf("kb"),
            selectedKnowledgeBaseIds = setOf("kb")
        )
        val applied = original.withKnowledgeLibraryChange(KnowledgeLibraryChange(statusMessage = "仅提示"))
        assertEquals("仅提示", applied.statusMessage)
        assertSame(original.worldBooks, applied.worldBooks)
        assertSame(original.knowledgeBases, applied.knowledgeBases)
        assertSame(original.knowledgeDocumentCounts, applied.knowledgeDocumentCounts)
        assertSame(original.knowledgeBaseImportingIds, applied.knowledgeBaseImportingIds)
        assertSame(original.selectedKnowledgeBaseIds, applied.selectedKnowledgeBaseIds)
    }

    @Test
    fun `a non-null field in a change is applied`() {
        val original = MainUiState(worldBooks = listOf(worldBook("old", "旧")))
        val replacement = listOf(worldBook("new", "新"))
        val applied = original.withKnowledgeLibraryChange(
            KnowledgeLibraryChange(worldBooks = replacement, statusMessage = "done")
        )
        assertEquals(replacement, applied.worldBooks)
        assertEquals("done", applied.statusMessage)
    }

    @Test
    fun `the snapshot reads exactly the five fields the operations decide on`() {
        val state = MainUiState(
            worldBooks = listOf(worldBook("w", "书")),
            knowledgeBases = listOf(knowledgeBase("k", "库")),
            knowledgeDocumentCounts = mapOf("k" to 9),
            knowledgeBaseImportingIds = setOf("k"),
            selectedKnowledgeBaseIds = setOf("k")
        )
        val snapshot = state.knowledgeLibrarySnapshot()
        assertEquals(state.worldBooks, snapshot.worldBooks)
        assertEquals(state.knowledgeBases, snapshot.knowledgeBases)
        assertEquals(state.knowledgeDocumentCounts, snapshot.knowledgeDocumentCounts)
        assertEquals(state.knowledgeBaseImportingIds, snapshot.knowledgeBaseImportingIds)
        assertEquals(state.selectedKnowledgeBaseIds, snapshot.selectedKnowledgeBaseIds)
    }
}
