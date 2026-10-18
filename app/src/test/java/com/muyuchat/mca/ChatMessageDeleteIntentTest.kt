package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.ChatMessageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageDeleteIntentTest {
    @Test
    fun `authoritative delete relocates identity after prepend instead of using stale index`() {
        val first = message("a")
        val targetMessage = message("b")
        val third = message("c")
        val currentMessages = listOf(message("new"), first, targetMessage, third)
        val store = UiStateStore(stateFor(currentMessages))
        var persisted = false

        deleteMessageFor(
            store = store,
            rejectWhileConversationMutationInProgress = { false },
            showStatus = {},
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            persistConversationMutation = { _, _ ->
                persisted = true
                null
            },
            target = ChatMessageIdentity(
                conversationId = CONVERSATION_ID,
                messageId = "b"
            )
        )

        assertEquals(listOf("new", "a", "c"), store.current.messages.map { it.id })
        assertEquals(
            listOf("new", "a", "c"),
            store.current.chatSessions.single { it.id == CONVERSATION_ID }.messages.map { it.id }
        )
        assertTrue(persisted)
    }

    @Test
    fun `old menu target cannot delete a message after conversation switch`() {
        val messages = listOf(message("a"), message("b"))
        val store = UiStateStore(stateFor(messages, activeSessionId = "other-conversation"))
        var persisted = false

        deleteMessageFor(
            store = store,
            rejectWhileConversationMutationInProgress = { false },
            showStatus = {},
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            persistConversationMutation = { _, _ ->
                persisted = true
                null
            },
            target = ChatMessageIdentity(CONVERSATION_ID, "b")
        )

        assertEquals(listOf("a", "b"), store.current.messages.map { it.id })
        assertFalse(persisted)
    }

    @Test
    fun `already deleted target is rejected without touching a same index replacement`() {
        val store = UiStateStore(stateFor(listOf(message("replacement"))))
        var persisted = false

        deleteMessageFor(
            store = store,
            rejectWhileConversationMutationInProgress = { false },
            showStatus = {},
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            persistConversationMutation = { _, _ ->
                persisted = true
                null
            },
            target = ChatMessageIdentity(CONVERSATION_ID, "deleted")
        )

        assertEquals(listOf("replacement"), store.current.messages.map { it.id })
        assertFalse(persisted)
    }

    private fun stateFor(
        messages: List<ChatMessage>,
        activeSessionId: String = CONVERSATION_ID
    ): MainUiState {
        val assistant = AssistantRecord(id = ASSISTANT_ID)
        val snapshot = AssistantConversationSnapshot(
            assistantId = ASSISTANT_ID,
            name = "Assistant",
            systemPrompt = "System",
            memoryEnabled = false,
            webSearchEnabled = false,
            fileContextEnabled = true,
            capturedAt = 1L
        )
        val conversations = listOf(CONVERSATION_ID, "other-conversation")
            .distinct()
            .map { sessionId ->
                ChatSessionRecord(
                    id = sessionId,
                    title = sessionId,
                    messages = messages,
                    assistantId = ASSISTANT_ID,
                    assistantSnapshot = snapshot
                )
            }
        return MainUiState(
            messages = messages,
            chatSessions = conversations,
            activeChatSessionId = activeSessionId,
            assistants = listOf(assistant),
            selectedAssistantId = ASSISTANT_ID
        )
    }

    private fun message(id: String): ChatMessage = ChatMessage(
        role = Role.USER,
        content = "same body",
        createdAt = 1L,
        id = id
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val ASSISTANT_ID = "assistant"
    }
}
