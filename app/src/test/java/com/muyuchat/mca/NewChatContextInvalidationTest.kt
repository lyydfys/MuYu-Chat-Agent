package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatContextInvalidationTest {
    private val assistant = AssistantRecord(id = "qa-persona", name = "QA persona", systemPrompt = "QA prompt")

    @Test
    fun `a new conversation persists its snapshot before invalidating context once`() {
        val previous = assistant.newConversationSession(modelMode = "local", modelId = "qa-model")
        val store = UiStateStore(MainUiState(
            assistants = listOf(assistant), selectedAssistantId = assistant.id,
            chatSessions = listOf(previous), activeChatSessionId = previous.id
        ))
        val events = mutableListOf<String>()
        val created = newChatForAssistantFor(
            store = store,
            rejectWhileConversationMutationInProgress = { false },
            hasActiveAssistantImageExecution = { false },
            showStatus = { error(it) },
            invalidatePendingChatImagePreparation = {},
            cancelActiveWebSearch = {},
            bumpContentImportSelectionRevision = {},
            markLocalConversationContextInvalid = { events += "invalidate" },
            persistChatSessions = { sessions ->
                assertEquals(store.current.activeChatSessionId, sessions.first().id)
                events += "persist"
                null
            },
            assistant = assistant
        )

        assertTrue(created)
        assertEquals(listOf("persist", "invalidate"), events)
        assertNotEquals(previous.id, store.current.activeChatSessionId)
        assertTrue(store.current.chatSessions.any { it.id == previous.id })
        assertEquals(assistant.id, store.current.selectedAssistantId)
    }

    @Test
    fun `a rejected new conversation preserves state without invalidation or persistence`() {
        val initial = MainUiState(assistants = listOf(assistant), selectedAssistantId = assistant.id, isGenerating = true)
        val store = UiStateStore(initial)
        var invalidations = 0
        var writes = 0
        val created = newChatForAssistantFor(
            store = store,
            rejectWhileConversationMutationInProgress = { false },
            hasActiveAssistantImageExecution = { false },
            showStatus = {},
            invalidatePendingChatImagePreparation = {},
            cancelActiveWebSearch = {},
            bumpContentImportSelectionRevision = {},
            markLocalConversationContextInvalid = { invalidations++ },
            persistChatSessions = { writes++; null },
            assistant = assistant
        )

        assertFalse(created)
        assertEquals(initial, store.current)
        assertEquals(0, invalidations)
        assertEquals(0, writes)
    }
}
