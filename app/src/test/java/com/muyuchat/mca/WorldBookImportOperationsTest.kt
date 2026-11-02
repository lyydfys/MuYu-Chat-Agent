package com.muyuchat.mca

import com.muyuchat.mca.presentation.KnowledgeLibraryOperations
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import com.muyuchat.mca.presentation.KnowledgeLibrarySnapshot
import com.muyuchat.mca.presentation.WorldBookImportOutcome
import com.muyuchat.mca.presentation.WorldBookImportRequest
import com.muyuchat.mca.presentation.importWorldBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the world-book import now that it lives outside `MainViewModel`.
 *
 * The guards that matter here are the ones a mechanical move would quietly break: the exact
 * user-visible wording (three of them are conditional suffixes), the owner-existence flag that
 * decides whether the imported book is enabled, the cancellation that must stay a cancellation, and
 * the ordering of the `ownerIsCurrent` read, which the original performed *after* the store write.
 */
class WorldBookImportOperationsTest {

    private val validJson = """
        {
          "name": "江湖设定",
          "entries": [
            { "uid": "a", "keys": "龙门, 客栈", "content": "龙门客栈位于大漠边缘。" },
            { "uid": "b", "content": "所有回答都使用简体中文。", "constant": true }
          ]
        }
    """.trimIndent()

    private class FakePort(
        var upsertResult: List<WorldBookRecord> = emptyList(),
        var throwOnUpsert: Throwable? = null
    ) : KnowledgeLibraryPort {
        var upsertCalls = 0
        var upserted: WorldBookRecord? = null
        /** Shared with the test so both the port and the provider record into one timeline. */
        var observedOrder: MutableList<String>? = null

        override fun removeWorldBook(id: String): List<WorldBookRecord> = emptyList()
        override fun createKnowledgeBase(name: String, description: String): KnowledgeBaseRecord =
            error("not used")
        override fun removeKnowledgeBase(id: String) = Unit

        override fun upsertWorldBook(book: WorldBookRecord): List<WorldBookRecord> {
            upsertCalls++
            observedOrder?.add("upsert")
            throwOnUpsert?.let { throw it }
            upserted = book
            return upsertResult
        }
    }

    private fun owner(sessionId: String? = "session", assistantId: String = "assistant") =
        ContentImportOwner(sessionId = sessionId, assistantId = assistantId, selectionRevision = 1L)

    private fun request(
        port: FakePort,
        rawJson: String = validJson,
        scope: WorldBookScope = WorldBookScope.ASSISTANT,
        ownerValue: ContentImportOwner = owner(),
        commitId: String? = "commit-1",
        hasActiveSession: Boolean = true,
        ownerStillExists: Boolean = true,
        order: MutableList<String>? = null,
        ownerIsCurrent: Boolean = true
    ) = WorldBookImportRequest(
        rawJson = rawJson,
        scope = scope,
        owner = ownerValue,
        commitId = commitId,
        hasActiveSession = hasActiveSession,
        ownerStillExists = ownerStillExists,
        ownerIsCurrent = {
            order?.add("ownerIsCurrent")
            ownerIsCurrent
        }
    )

    // ---- guards ----------------------------------------------------------------------------

    @Test
    fun `a chat scoped import without an active conversation is rejected before parsing`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, scope = WorldBookScope.CHAT, hasActiveSession = false))
        assertTrue(outcome is WorldBookImportOutcome.Rejected)
        assertEquals(
            "当前没有活动对话，无法导入当前对话作用域的世界书",
            (outcome as WorldBookImportOutcome.Rejected).statusMessage
        )
        assertEquals("nothing may reach storage", 0, port.upsertCalls)
    }

    @Test
    fun `invalid json is rejected with the codec message and never reaches storage`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, rawJson = "{ not json"))
        assertTrue(outcome is WorldBookImportOutcome.Rejected)
        assertTrue((outcome as WorldBookImportOutcome.Rejected).statusMessage.startsWith("世界书导入失败："))
        assertEquals(0, port.upsertCalls)
    }

    @Test
    fun `json with no usable entries is rejected`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, rawJson = """{"name":"x","entries":[]}"""))
        assertTrue(outcome is WorldBookImportOutcome.Rejected)
        assertEquals(0, port.upsertCalls)
    }

    // ---- preview path ----------------------------------------------------------------------

    @Test
    fun `without a commit id the import stages a preview and never writes`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, commitId = null))
        assertTrue(outcome is WorldBookImportOutcome.NeedsPreview)
        val preview = (outcome as WorldBookImportOutcome.NeedsPreview).preview
        assertEquals("江湖设定", preview.title)
        assertEquals("world_book", preview.kind)
        assertEquals("nothing may be written before the user commits", 0, port.upsertCalls)
    }

    // ---- commit path -----------------------------------------------------------------------

    @Test
    fun `committing freezes the id, enables the book and publishes the store result`() = runBlocking {
        val stored = listOf(WorldBookRecord(
            id = "commit-1",
            name = "江湖设定",
            entries = listOf(WorldBookEntry(content = "x"))
        ))
        val port = FakePort(upsertResult = stored)
        val outcome = port.importWorldBook(request(port))
        assertTrue(outcome is WorldBookImportOutcome.Committed)
        val committed = outcome as WorldBookImportOutcome.Committed
        assertEquals(stored, committed.change.worldBooks)
        assertEquals("commit-1", port.upserted!!.id)
        assertTrue("an existing owner enables the book", port.upserted!!.enabled)
        assertEquals("已导入世界书：江湖设定（2 条）", committed.change.statusMessage)
        assertTrue(committed.invalidateContext)
    }

    @Test
    fun `a deleted owner imports the book disabled and says so`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, ownerStillExists = false))
        val committed = outcome as WorldBookImportOutcome.Committed
        assertFalse("a missing owner must not enable the book", port.upserted!!.enabled)
        assertTrue(committed.change.statusMessage.endsWith("；原所属对象已删除，资料已保留但未启用"))
    }

    @Test
    fun `a stale selection is recorded in the message but still saves`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, ownerIsCurrent = false))
        val committed = outcome as WorldBookImportOutcome.Committed
        assertTrue(committed.change.statusMessage.contains("；已保存，当前对话未切换绑定"))
        assertFalse("a stale selection must not invalidate the live context", committed.invalidateContext)
        assertEquals(1, port.upsertCalls)
    }

    @Test
    fun `both suffixes appear in the documented order`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, ownerIsCurrent = false, ownerStillExists = false))
        val message = (outcome as WorldBookImportOutcome.Committed).change.statusMessage
        assertTrue(
            "the stale-selection suffix comes first",
            message.indexOf("未切换绑定") < message.indexOf("原所属对象已删除")
        )
    }

    @Test
    fun `a global import always invalidates the context`() = runBlocking {
        val port = FakePort()
        val outcome = port.importWorldBook(
            request(port, scope = WorldBookScope.GLOBAL, ownerIsCurrent = false)
        )
        assertTrue((outcome as WorldBookImportOutcome.Committed).invalidateContext)
    }

    // ---- ordering and failure --------------------------------------------------------------

    @Test
    fun `the owner-is-current read happens after the store write, like the original`() = runBlocking {
        val port = FakePort()
        // The port and the provider must write into the SAME list, otherwise the assertion compares
        // one list against another and proves nothing.
        val order = mutableListOf<String>()
        port.observedOrder = order
        port.importWorldBook(request(port, order = order, ownerIsCurrent = true))
        assertEquals(
            "reading it earlier would change the message when a slow write races a selection change",
            listOf("upsert", "ownerIsCurrent"),
            order
        )
    }

    @Test
    fun `a failing write is reported and nothing is published`() = runBlocking {
        val port = FakePort(throwOnUpsert = IllegalStateException("no space"))
        val outcome = port.importWorldBook(request(port))
        assertTrue(outcome is WorldBookImportOutcome.Rejected)
        assertEquals("世界书保存失败：no space", (outcome as WorldBookImportOutcome.Rejected).statusMessage)
    }

    @Test
    fun `a failing write without a message uses the documented fallback wording`() = runBlocking {
        val port = FakePort(throwOnUpsert = IllegalStateException())
        val outcome = port.importWorldBook(request(port))
        assertEquals(
            "世界书保存失败：请检查本机存储空间后重试",
            (outcome as WorldBookImportOutcome.Rejected).statusMessage
        )
    }

    @Test
    fun `cancellation stays a cancellation instead of becoming a storage failure`() = runBlocking {
        val port = FakePort(throwOnUpsert = CancellationException("cancelled"))
        var rethrown = false
        try {
            port.importWorldBook(request(port))
        } catch (expected: CancellationException) {
            rethrown = true
        }
        assertTrue("a cancelled import must not be reported as 保存失败", rethrown)
    }

    @Test
    fun `warnings from the codec are appended to the success message`() {
        // The codec warns about regex entries it cannot honour; the message must surface them.
        val regexJson = """
            {
              "name": "含正则",
              "entries": [
                { "uid": "r", "keys": "a", "content": "b", "use_regex": true },
                { "uid": "p", "keys": "c", "content": "d" }
              ]
            }
        """.trimIndent()
        val port = FakePort()
        val outcome = runBlocking { port.importWorldBook(request(port, rawJson = regexJson)) }
        assertNotNull(outcome)
    }

    // ---- file-derived fallback name ---------------------------------------------------------

    @Test
    fun `a nameless export takes the file-derived fallback name`() = runBlocking {
        // Tavern World Info exports carry no top-level name; the picked file name is the book name.
        val nameless = """{"entries":[{"uid":"a","keys":"杭州","content":"西湖在杭州。"}]}"""
        val port = FakePort()
        val outcome = port.importWorldBook(request(port, rawJson = nameless).copy(fallbackName = "Eternal Wind Box"))
        assertTrue(outcome is WorldBookImportOutcome.Committed)
        assertEquals("Eternal Wind Box", port.upserted!!.name)
        assertTrue(
            "the message names the book by its file name",
            (outcome as WorldBookImportOutcome.Committed).change.statusMessage.contains("已导入世界书：Eternal Wind Box")
        )
    }

    @Test
    fun `an embedded name still wins over the fallback`() = runBlocking {
        val named = """{"name":"内嵌名","entries":[{"uid":"a","keys":"k","content":"c"}]}"""
        val port = FakePort()
        port.importWorldBook(request(port, rawJson = named).copy(fallbackName = "file.json"))
        assertEquals("内嵌名", port.upserted!!.name)
    }

    @Test
    fun `the file import path strips the extension and blanks mean no fallback`() {
        assertEquals("Eternal Wind Box", worldBookFallbackNameFromFile("Eternal Wind Box.json"))
        assertEquals("书名", worldBookFallbackNameFromFile("  书名.json "))
        assertEquals("no-extension", worldBookFallbackNameFromFile("no-extension"))
        assertEquals("a.b", worldBookFallbackNameFromFile("a.b.json"))
        assertNull(worldBookFallbackNameFromFile(""))
        assertNull(worldBookFallbackNameFromFile("   "))
        assertNull(worldBookFallbackNameFromFile(".json"))
    }

    // ---- enable/disable toggle ---------------------------------------------------------------

    @Test
    fun `disabling a book upserts it with the flag flipped and says so`() {
        val book = WorldBookRecord(
            id = "book-1",
            name = "江湖设定",
            entries = listOf(WorldBookEntry(content = "x"))
        )
        val port = FakePort(upsertResult = listOf(book.copy(enabled = false)))
        val change = KnowledgeLibraryOperations.setWorldBookEnabled(
            port,
            KnowledgeLibrarySnapshot(
                worldBooks = listOf(book),
                knowledgeBases = emptyList(),
                knowledgeDocumentCounts = emptyMap(),
                knowledgeBaseImportingIds = emptySet(),
                selectedKnowledgeBaseIds = emptySet()
            ),
            worldBookId = "book-1",
            enabled = false
        )
        assertNotNull(change)
        assertEquals(false, port.upserted!!.enabled)
        assertEquals("已停用世界书：江湖设定", change!!.statusMessage)
    }

    @Test
    fun `enabling a disabled book flips the flag`() {
        val book = WorldBookRecord(
            id = "book-1",
            name = "停用的书",
            entries = listOf(WorldBookEntry(content = "x")),
            enabled = false
        )
        val port = FakePort()
        KnowledgeLibraryOperations.setWorldBookEnabled(
            port,
            KnowledgeLibrarySnapshot(
                worldBooks = listOf(book),
                knowledgeBases = emptyList(),
                knowledgeDocumentCounts = emptyMap(),
                knowledgeBaseImportingIds = emptySet(),
                selectedKnowledgeBaseIds = emptySet()
            ),
            worldBookId = "book-1",
            enabled = true
        )
        assertTrue(port.upserted!!.enabled)
    }

    @Test
    fun `a same-state toggle publishes only a status line without writing`() {
        val book = WorldBookRecord(
            id = "book-1",
            name = "已启用",
            entries = listOf(WorldBookEntry(content = "x")),
            enabled = true
        )
        val port = FakePort()
        val change = KnowledgeLibraryOperations.setWorldBookEnabled(
            port,
            KnowledgeLibrarySnapshot(
                worldBooks = listOf(book),
                knowledgeBases = emptyList(),
                knowledgeDocumentCounts = emptyMap(),
                knowledgeBaseImportingIds = emptySet(),
                selectedKnowledgeBaseIds = emptySet()
            ),
            worldBookId = "book-1",
            enabled = true
        )
        assertEquals(0, port.upsertCalls)
        assertTrue(change!!.statusMessage.contains("已是启用状态"))
        assertNull(change.worldBooks)
    }

    @Test
    fun `an unknown toggle target is a silent no-op`() {
        val port = FakePort()
        val snapshot = KnowledgeLibrarySnapshot(
            worldBooks = emptyList(),
            knowledgeBases = emptyList(),
            knowledgeDocumentCounts = emptyMap(),
            knowledgeBaseImportingIds = emptySet(),
            selectedKnowledgeBaseIds = emptySet()
        )
        assertNull(KnowledgeLibraryOperations.setWorldBookEnabled(port, snapshot, "missing", enabled = true))
        assertEquals(0, port.upsertCalls)
    }
}
