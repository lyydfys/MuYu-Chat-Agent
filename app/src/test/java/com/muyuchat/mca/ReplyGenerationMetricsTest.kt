package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import org.junit.Assert.*
import org.junit.Test

class ReplyGenerationMetricsTest {
    @Test fun terminalSnapshotIsFrozenAndNewRequestsDoNotInheritOldCounts() {
        val tracker = ReplyGenerationMetrics(1, 1_000_000_000)
        assertNull(tracker.finish(2_000_000_000))
        tracker.record(RuntimeStats(promptTokens = 120, completionTokens = 8, decodeTps = 4.0))
        val stopped = tracker.finish(3_000_000_000)!!
        assertEquals(2000L, stopped.elapsedMs)
        assertEquals(8, stopped.completionTokens)
        assertEquals(stopped, tracker.finish(9_000_000_000))
        assertNull(ReplyGenerationMetrics(2).finish())
    }

    @Test fun completionUpdatesOnlyCurrentReplyAndItsStoredSession() {
        val oldMetrics = ChatGenerationMetrics(20, 3, 1.0, 3000)
        val messages = listOf(ChatMessage(Role.ASSISTANT, "old", generationMetrics = oldMetrics),
            ChatMessage(Role.USER, "new"), ChatMessage(Role.ASSISTANT, "answer"))
        val session = ChatSessionRecord(id = "active", title = "chat", messages = messages)
        val other = session.copy(id = "other")
        val metrics = ChatGenerationMetrics(120, 8, 4.0, 2000)
        val state = MainUiState(messages = messages, activeChatSessionId = "active", chatSessions = listOf(session, other))
            .withReplyGenerationMetrics(metrics)
        assertEquals(oldMetrics, state.messages.first().generationMetrics)
        assertEquals(metrics, state.messages.last().generationMetrics)
        assertEquals(state.messages, state.chatSessions.first().messages)
        assertEquals(other, state.chatSessions.last())
        val userTail = state.copy(messages = messages.dropLast(1))
        assertSame(userTail, userTail.withReplyGenerationMetrics(metrics))
    }

    @Test fun roomRepresentationRoundTripsMetricsAndToleratesOldOrDamagedOptionalData() {
        val reply = ChatMessage(Role.ASSISTANT, "answer", generationMetrics = ChatGenerationMetrics(40, 8, 4.0, 2000, true))
        val row = reply.toEntity("chat", 1)
        assertEquals(reply, row.toChatMessage())
        assertNull(row.copy(generationMetricsJson = null).toChatMessage().generationMetrics)
        assertEquals("answer", row.copy(generationMetricsJson = "{broken").toChatMessage().content)
        assertNull(row.copy(generationMetricsJson = "{broken").toChatMessage().generationMetrics)
    }
}
