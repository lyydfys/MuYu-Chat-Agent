package com.muyuchat.mca

import com.muyuchat.mca.presentation.ChatContextAvailabilityPort
import com.muyuchat.mca.presentation.ChatContextChange
import com.muyuchat.mca.presentation.ChatContextOperations
import com.muyuchat.mca.presentation.ChatContextSnapshot
import com.muyuchat.mca.presentation.chatContextSnapshot
import com.muyuchat.mca.presentation.withChatContextChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the context-source operations now that they live outside `MainViewModel`.
 *
 * These guard the migration that moved the `contextStatus` block and adds the availability check:
 * they pin the user-visible wording, the "an inapplicable source says nothing" rule, the
 * "nothing to report publishes nothing" rule, and -- the one that is easy to get wrong -- that a
 * selection is never pruned when availability could not be read.
 */
class ChatContextOperationsTest {

    private class FakeAvailabilityPort(
        var availableIds: Set<String> = emptySet(),
        var throwOnRead: Throwable? = null
    ) : ChatContextAvailabilityPort {
        var calls = 0

        override fun availableKnowledgeBaseIds(): Set<String> {
            calls++
            throwOnRead?.let { throw it }
            return availableIds
        }
    }

    private fun worldBook(
        id: String,
        scope: WorldBookScope = WorldBookScope.GLOBAL,
        assistantId: String? = null,
        chatSessionId: String? = null,
        enabled: Boolean = true
    ) = WorldBookRecord(
        id = id,
        name = "book-$id",
        scope = scope,
        assistantId = assistantId,
        chatSessionId = chatSessionId,
        // WorldBookRecord requires at least one entry; the operations under test never read them.
        entries = listOf(WorldBookEntry(content = "entry for $id")),
        enabled = enabled
    )

    private fun chunk(id: String) = KnowledgeChunkRecord(
        id = id,
        knowledgeBaseId = "kb",
        documentId = "doc",
        position = 0,
        content = "content of $id",
        contentHash = "hash-$id",
        estimatedTokens = 8
    )

    private fun plan(knowledgeChunks: Int = 0, worldBookEntries: Int = 0) = ChatRuntimeContextPlan(
        knowledge = KnowledgeRetrieval(chunks = List(knowledgeChunks) { chunk("chunk-$it") }),
        worldBook = WorldBookSelection(selectedEntryIds = List(worldBookEntries) { "entry-$it" })
    )

    private fun snapshot(
        selected: Set<String> = emptySet(),
        fileContextEnabled: Boolean = true,
        worldBooks: List<WorldBookRecord> = emptyList(),
        assistantId: String = "assistant-1",
        activeChatSessionId: String? = "session-1"
    ) = ChatContextSnapshot(
        selectedKnowledgeBaseIds = selected,
        fileContextEnabled = fileContextEnabled,
        worldBooks = worldBooks,
        assistantId = assistantId,
        activeChatSessionId = activeChatSessionId
    )

    // ---- the status line, wording pinned ----------------------------------------------------

    @Test
    fun `a knowledge hit reports how many chunks this turn carried`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(availableIds = setOf("kb")),
            snapshot(selected = setOf("kb")),
            plan(knowledgeChunks = 2)
        )
        assertEquals("本轮知识库命中 2 段", change.statusMessage)
    }

    @Test
    fun `a knowledge miss says it searched with the current question`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(availableIds = setOf("kb")),
            snapshot(selected = setOf("kb")),
            plan(knowledgeChunks = 0)
        )
        assertEquals("本轮知识库未命中（已按当前问题检索）", change.statusMessage)
    }

    @Test
    fun `switching the assistant file-context off is reported instead of a miss`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(availableIds = setOf("kb")),
            snapshot(selected = setOf("kb"), fileContextEnabled = false),
            plan(knowledgeChunks = 0)
        )
        assertEquals("知识库检索已关闭（当前助手的文件上下文开关已关闭）", change.statusMessage)
    }

    @Test
    fun `a world book hit reports the selected entry count`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(),
            snapshot(worldBooks = listOf(worldBook("b"))),
            plan(worldBookEntries = 3)
        )
        assertEquals("本轮世界书命中 3 条", change.statusMessage)
    }

    @Test
    fun `a world book miss is reported when a book applies but no keyword matched`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(),
            snapshot(worldBooks = listOf(worldBook("b"))),
            plan(worldBookEntries = 0)
        )
        assertEquals("本轮世界书未命中关键词", change.statusMessage)
    }

    @Test
    fun `both sources are reported in one line, knowledge first`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(availableIds = setOf("kb")),
            snapshot(selected = setOf("kb"), worldBooks = listOf(worldBook("b"))),
            plan(knowledgeChunks = 1, worldBookEntries = 2)
        )
        assertEquals("本轮知识库命中 1 段；本轮世界书命中 2 条", change.statusMessage)
    }

    @Test
    fun `an inapplicable world book says nothing, even when an entry was selected`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(),
            // The only book belongs to another assistant.
            snapshot(worldBooks = listOf(worldBook("other", scope = WorldBookScope.ASSISTANT, assistantId = "assistant-2"))),
            plan(worldBookEntries = 1)
        )
        assertNull("a book that does not apply must not produce a line", change.statusMessage)
        assertTrue(change.isNoOp)
    }

    @Test
    fun `nothing selected and nothing applicable is a silent no-op`() {
        val change = ChatContextOperations.planTurnContext(FakeAvailabilityPort(), snapshot(), plan())
        assertNull(change.statusMessage)
        assertNull("an untouched selection must not be written back", change.selectedKnowledgeBaseIds)
        assertTrue(change.isNoOp)
    }

    // ---- the availability check -------------------------------------------------------------

    @Test
    fun `a selected knowledge base that no longer exists is reported for removal`() {
        val port = FakeAvailabilityPort(availableIds = setOf("alive"))
        val change = ChatContextOperations.planTurnContext(
            port,
            snapshot(selected = setOf("alive", "deleted")),
            plan(knowledgeChunks = 0)
        )
        assertEquals(setOf("alive"), change.selectedKnowledgeBaseIds)
        assertEquals(1, port.calls)
    }

    @Test
    fun `pruning a stale selection leaves this turn's wording untouched`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(availableIds = setOf("alive")),
            snapshot(selected = setOf("alive", "deleted")),
            plan(knowledgeChunks = 0)
        )
        assertEquals(
            "the turn must still report its own result, not the repaired selection",
            "本轮知识库未命中（已按当前问题检索）",
            change.statusMessage
        )
    }

    @Test
    fun `a selection whose knowledge bases are all gone is reported as empty`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(availableIds = emptySet()),
            snapshot(selected = setOf("deleted-a", "deleted-b")),
            plan(knowledgeChunks = 0)
        )
        assertEquals(emptySet<String>(), change.selectedKnowledgeBaseIds)
        assertFalse(change.isNoOp)
    }

    @Test
    fun `a fully usable selection reports no selection change`() {
        val port = FakeAvailabilityPort(availableIds = setOf("a", "b", "unrelated"))
        val change = ChatContextOperations.planTurnContext(
            port,
            snapshot(selected = setOf("a", "b")),
            plan(knowledgeChunks = 1)
        )
        assertNull("an all-usable selection must not be written back", change.selectedKnowledgeBaseIds)
        assertEquals(1, port.calls)
    }

    @Test
    fun `an empty selection never reads storage`() {
        val port = FakeAvailabilityPort(availableIds = setOf("a"))
        val change = ChatContextOperations.planTurnContext(port, snapshot(), plan(worldBookEntries = 1))
        assertEquals("there is nothing to verify, so there is nothing to read", 0, port.calls)
        assertNull(change.selectedKnowledgeBaseIds)
    }

    @Test
    fun `a port failure keeps the selection instead of silently disabling retrieval`() {
        val port = FakeAvailabilityPort(throwOnRead = IllegalStateException("db locked"))
        val change = ChatContextOperations.planTurnContext(
            port,
            snapshot(selected = setOf("a", "b")),
            plan(knowledgeChunks = 0)
        )
        assertNull("an unreadable availability must not drop the user's selection", change.selectedKnowledgeBaseIds)
        assertEquals("the turn still reports its own result", "本轮知识库未命中（已按当前问题检索）", change.statusMessage)
        assertEquals(1, port.calls)
    }

    @Test
    fun `a port failure with no selection is still a silent no-op`() {
        val change = ChatContextOperations.planTurnContext(
            FakeAvailabilityPort(throwOnRead = IllegalStateException("db locked")),
            snapshot(),
            plan()
        )
        assertTrue(change.isNoOp)
    }

    // ---- world-book applicability ------------------------------------------------------------

    @Test
    fun `world book applicability follows scope, ownership and the enabled flag`() {
        val books = listOf(
            worldBook("global"),
            worldBook("mine", scope = WorldBookScope.ASSISTANT, assistantId = "assistant-1"),
            worldBook("theirs", scope = WorldBookScope.ASSISTANT, assistantId = "assistant-2"),
            worldBook("session", scope = WorldBookScope.CHAT, chatSessionId = "session-1"),
            worldBook("other-session", scope = WorldBookScope.CHAT, chatSessionId = "session-2"),
            worldBook("disabled", enabled = false)
        )
        assertEquals(3, ChatContextOperations.applicableWorldBookCount(snapshot(worldBooks = books)))
    }

    @Test
    fun `a chat-scoped book does not apply when no conversation is active`() {
        val books = listOf(worldBook("session", scope = WorldBookScope.CHAT, chatSessionId = "session-1"))
        assertEquals(
            0,
            ChatContextOperations.applicableWorldBookCount(snapshot(worldBooks = books, activeChatSessionId = null))
        )
    }

    // ---- applying a change -------------------------------------------------------------------

    @Test
    fun `a null field in a change means unchanged, not cleared`() {
        val original = MainUiState(
            selectedKnowledgeBaseIds = setOf("kb"),
            statusMessage = "上一条状态",
            worldBooks = listOf(worldBook("b"))
        )
        val applied = original.withChatContextChange(ChatContextChange(selectedKnowledgeBaseIds = setOf("kb", "new")))
        assertEquals(setOf("kb", "new"), applied.selectedKnowledgeBaseIds)
        assertEquals("上一条状态", applied.statusMessage)
        assertSame(original.worldBooks, applied.worldBooks)
    }

    @Test
    fun `a non-null field in a change is applied`() {
        val original = MainUiState(selectedKnowledgeBaseIds = setOf("stale"), statusMessage = "旧")
        val applied = original.withChatContextChange(
            ChatContextChange(selectedKnowledgeBaseIds = emptySet(), statusMessage = "本轮知识库命中 1 段")
        )
        assertTrue(applied.selectedKnowledgeBaseIds.isEmpty())
        assertEquals("本轮知识库命中 1 段", applied.statusMessage)
    }

    @Test
    fun `a no-op change returns the same state instance so nothing is published`() {
        val original = MainUiState(selectedKnowledgeBaseIds = setOf("kb"), statusMessage = "保留")
        assertSame(original, original.withChatContextChange(ChatContextChange()))
        assertSame(original, original.withChatContextChange(ChatContextChange(statusMessage = null)))
    }

    @Test
    fun `the snapshot reads exactly the fields the operations decide on`() {
        val books = listOf(worldBook("b"))
        val state = MainUiState(
            selectedKnowledgeBaseIds = setOf("kb"),
            worldBooks = books,
            activeChatSessionId = "session-9"
        )
        val snapshot = state.chatContextSnapshot(assistantId = "assistant-9", fileContextEnabled = false)
        assertEquals(setOf("kb"), snapshot.selectedKnowledgeBaseIds)
        assertEquals(books, snapshot.worldBooks)
        assertEquals("session-9", snapshot.activeChatSessionId)
        assertEquals("assistant-9", snapshot.assistantId)
        assertFalse(snapshot.fileContextEnabled)
    }
}
