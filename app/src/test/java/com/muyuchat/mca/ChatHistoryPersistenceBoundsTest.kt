package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPersistenceBoundsTest {
    @Test
    fun sessionLimitPrefersPinnedThenNewestAndDropsDuplicateIds() {
        val bounded = ChatHistoryPersistenceBounds.bound(
            records = listOf(
                session(id = "older", updatedAt = 10L),
                session(id = "duplicate", updatedAt = 20L),
                session(id = "pinned", updatedAt = 1L, pinned = true),
                session(id = "duplicate", updatedAt = 30L),
                session(id = "newest", updatedAt = 40L)
            ),
            limits = limits(maxSessions = 3)
        )

        assertEquals(listOf("pinned", "newest", "duplicate"), bounded.map { it.id })
        assertEquals(3, bounded.map { it.id }.distinct().size)
    }

    @Test
    fun messageLimitKeepsEachRetainedTranscriptTailInChronologicalOrder() {
        val bounded = ChatHistoryPersistenceBounds.bound(
            records = listOf(
                session(
                    id = "thread",
                    messages = listOf(message("first"), message("second"), message("third"), message("fourth"))
                )
            ),
            limits = limits(maxMessages = 2)
        )

        assertEquals(listOf("third", "fourth"), bounded.single().messages.map { it.content })
    }

    @Test
    fun byteLimitDoesNotPersistAPartialTailAfterAnOversizedNewestMessage() {
        val emptySession = session(id = "thread", messages = emptyList())
        val maxBytes = ChatHistoryPersistenceBounds.serializedByteCount(listOf(emptySession))
        val bounded = ChatHistoryPersistenceBounds.bound(
            records = listOf(
                session(
                    id = "thread",
                    messages = listOf(message("older"), message("x".repeat(4_096)))
                )
            ),
            limits = limits(maxSerializedBytes = maxBytes)
        )

        assertEquals(listOf("thread"), bounded.map { it.id })
        assertTrue(bounded.single().messages.isEmpty())
        assertTrue(ChatHistoryPersistenceBounds.serializedByteCount(bounded) <= maxBytes)
    }

    private fun limits(
        maxSessions: Int = 8,
        maxMessages: Int = 32,
        maxSerializedBytes: Long = 128L * 1024L
    ) = ChatHistoryPersistenceLimits(
        maxSessions = maxSessions,
        maxMessages = maxMessages,
        maxSerializedBytes = maxSerializedBytes
    )

    private fun session(
        id: String,
        updatedAt: Long = 1L,
        pinned: Boolean = false,
        messages: List<ChatMessage> = emptyList()
    ) = ChatSessionRecord(
        id = id,
        title = id,
        messages = messages,
        pinned = pinned,
        updatedAt = updatedAt
    )

    private fun message(content: String) = ChatMessage(
        role = Role.USER,
        content = content,
        createdAt = 1L
    )
}
