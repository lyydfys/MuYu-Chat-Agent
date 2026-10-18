package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIntentIdentityTest {
    @Test
    fun `old menu target follows its message after prepend and reorder`() {
        val first = message("a")
        val second = message("b")
        val third = message("c")
        val target = chatMessageRows(listOf(first, second, third), "conversation")
            .first { row -> row.message.id == "b" }
            .identity

        val prepended = listOf(message("new"), first, second, third)
        assertEquals(2, target.resolveIndex(prepended, "conversation"))

        val reordered = listOf(third, second, first)
        assertEquals(1, target.resolveIndex(reordered, "conversation"))
    }

    @Test
    fun `deleted target and conversation switch are rejected`() {
        val target = chatMessageRows(listOf(message("a"), message("b")), "conversation-a")
            .last()
            .identity

        assertNull(target.resolveIndex(listOf(message("a")), "conversation-a"))
        assertNull(target.resolveIndex(listOf(message("a"), message("b")), "conversation-b"))
    }

    @Test
    fun `legacy index callback receives relocated index not stale menu index`() {
        var messages = listOf(message("a"), message("b"), message("c"))
        val target = chatMessageRows(messages, "conversation").first { it.message.id == "b" }.identity
        messages = listOf(message("new"), message("a"), message("b"), message("c"))
        var deletedIndex = -1
        val dispatcher = dispatcher(
            messages = { messages },
            conversationId = { "conversation" },
            legacyDelete = { deletedIndex = it }
        )

        dispatcher.delete(target)

        assertEquals(2, deletedIndex)
        assertEquals("b", messages[deletedIndex].id)
    }

    @Test
    fun `legacy regenerate and delete turn stay on the last assistant target`() {
        val messages = listOf(
            message("user"),
            message("assistant", Role.ASSISTANT),
            message("new-user", Role.USER),
            message("new-assistant", Role.ASSISTANT)
        )
        val oldAssistant = chatMessageRows(messages, "conversation")
            .first { row -> row.message.id == "assistant" }
            .identity
        var regenerateCount = 0
        var deleteLastTurnCount = 0
        val dispatcher = dispatcher(
            messages = { messages },
            conversationId = { "conversation" },
            legacyRegenerate = { regenerateCount += 1 },
            legacyDeleteLastTurn = { deleteLastTurnCount += 1 }
        )

        dispatcher.regenerate(oldAssistant)
        dispatcher.deleteLastTurn(oldAssistant)
        assertEquals(0, regenerateCount)
        assertEquals(0, deleteLastTurnCount)

        val currentAssistant = chatMessageRows(messages, "conversation").last().identity
        dispatcher.regenerate(currentAssistant)
        dispatcher.deleteLastTurn(currentAssistant)
        assertEquals(1, regenerateCount)
        assertEquals(1, deleteLastTurnCount)
    }

    @Test
    fun `typed intent forwards and copy guard rejects switched session`() {
        val messages = listOf(message("a"), message("b"))
        var conversationId = "conversation-a"
        val target = chatMessageRows(messages, conversationId).last().identity
        var forwarded: ChatMessageIntent? = null
        val dispatcher = dispatcher(
            messages = { messages },
            conversationId = { conversationId },
            onIntent = { forwarded = it }
        )

        assertTrue(dispatcher.isCurrent(target))
        dispatcher.delete(target)
        assertEquals(ChatMessageIntent.Delete(target), forwarded)

        conversationId = "conversation-b"
        assertFalse(dispatcher.isCurrent(target))
    }

    private fun dispatcher(
        messages: () -> List<ChatMessage>,
        conversationId: () -> String?,
        onIntent: ((ChatMessageIntent) -> Unit)? = null,
        legacyDelete: (Int) -> Unit = {},
        legacyRegenerate: () -> Unit = {},
        legacyDeleteLastTurn: () -> Unit = {}
    ): ChatMessageIntentDispatcher = ChatMessageIntentDispatcher(
        currentMessages = messages,
        activeConversationId = conversationId,
        onIntent = { onIntent },
        legacyOnDeleteMessage = legacyDelete,
        legacyOnRegenerate = legacyRegenerate,
        legacyOnDeleteLastTurn = legacyDeleteLastTurn
    )

    private fun message(id: String, role: Role = Role.USER): ChatMessage = ChatMessage(
        role = role,
        content = "same",
        createdAt = 7L,
        id = id
    )

}
