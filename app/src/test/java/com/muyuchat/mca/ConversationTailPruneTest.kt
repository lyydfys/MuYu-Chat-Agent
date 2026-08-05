package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.TokenProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTailPruneTest {
    @Test
    fun removesTheLatestUserAndItsAssistantReplyTogether() {
        val messages = listOf(
            message(Role.USER, "first user"),
            message(Role.ASSISTANT, "first reply"),
            message(Role.USER, "second user"),
            message(Role.ASSISTANT, "second reply")
        )

        val result = requireNotNull(messages.pruneLastConversationTurn())

        assertEquals(2, result.removedMessageCount)
        assertEquals(listOf("first user", "first reply"), result.messages.map(ChatMessage::content))
    }

    @Test
    fun removesOnlyADanglingFinalUserTurn() {
        val messages = listOf(
            message(Role.USER, "first user"),
            message(Role.ASSISTANT, "first reply"),
            message(Role.USER, "unfinished user")
        )

        val result = requireNotNull(messages.pruneLastConversationTurn())

        assertEquals(1, result.removedMessageCount)
        assertEquals(listOf("first user", "first reply"), result.messages.map(ChatMessage::content))
    }

    @Test
    fun doesNotRemoveAnAssistantWithoutAUserTurn() {
        assertNull(listOf(message(Role.ASSISTANT, "orphan")).pruneLastConversationTurn())
    }

    @Test
    fun roomFailureRestoresTheLastDurableConversationSnapshot() {
        val durableMessages = listOf(
            message(Role.USER, "durable user"),
            message(Role.ASSISTANT, "durable answer")
        )
        val durableSession = session("active", durableMessages)
        val usage = PromptContextUsage(
            retainedMessageCount = 2,
            trimmedMessageCount = 1,
            roleTokens = 4,
            worldBookTokens = 5,
            knowledgeTokens = 6,
            totalEstimatedTokens = 20
        )
        val rollback = ConversationMutationRollbackState(
            messages = durableMessages,
            activeChatSessionId = "active",
            chatSessions = listOf(durableSession),
            selectedKnowledgeBaseIds = setOf("kb"),
            generationPhase = GenerationPhase.PERSIST,
            generationTokenProgress = TokenProgress(3, 3),
            promptContextUsage = usage
        )
        val mutated = MainUiState(
            messages = emptyList(),
            chatSessions = emptyList(),
            activeChatSessionId = null,
            selectedKnowledgeBaseIds = setOf("kb"),
            isGenerating = true
        )

        val restored = mutated.restoreAfterConversationMutationFailure(
            durableSessions = listOf(durableSession),
            rollback = rollback,
            statusMessage = "rolled back"
        )

        assertEquals(durableMessages, restored.messages)
        assertEquals(listOf(durableSession), restored.chatSessions)
        assertEquals("active", restored.activeChatSessionId)
        assertEquals(setOf("kb"), restored.selectedKnowledgeBaseIds)
        assertEquals(GenerationPhase.PERSIST, restored.generationPhase)
        assertEquals(TokenProgress(3, 3), restored.generationTokenProgress)
        assertEquals(usage, restored.promptContextUsage)
        assertEquals("rolled back", restored.statusMessage)
        assertFalse(restored.isGenerating)
    }

    @Test
    fun roomFailureDoesNotRestoreAnActiveSessionThatNeverCommitted() {
        val transientMessages = listOf(message(Role.USER, "not durable"))
        val rollback = ConversationMutationRollbackState(
            messages = transientMessages,
            activeChatSessionId = "transient",
            chatSessions = listOf(session("transient", transientMessages)),
            selectedKnowledgeBaseIds = setOf("kb"),
            generationPhase = null,
            generationTokenProgress = null,
            promptContextUsage = null
        )

        val restored = MainUiState(
            messages = transientMessages,
            chatSessions = rollback.chatSessions,
            activeChatSessionId = "transient",
            selectedKnowledgeBaseIds = setOf("kb")
        ).restoreAfterConversationMutationFailure(
            durableSessions = emptyList(),
            rollback = rollback,
            statusMessage = "rolled back"
        )

        assertTrue(restored.messages.isEmpty())
        assertTrue(restored.chatSessions.isEmpty())
        assertNull(restored.activeChatSessionId)
        assertTrue(restored.selectedKnowledgeBaseIds.isEmpty())
    }

    private fun message(role: Role, content: String): ChatMessage = ChatMessage(
        role = role,
        content = content
    )

    private fun session(id: String, messages: List<ChatMessage>) = ChatSessionRecord(
        id = id,
        title = id,
        messages = messages
    )
}
