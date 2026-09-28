package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantSessionPresentationTest {
    @Test
    fun sameRoleSessionsKeepCreationOrderAndShowLatestContent() {
        val assistant = AssistantRecord(id = "role", name = "阿澜")
        val first = ChatSessionRecord(
            id = "first", title = "旧标题", updatedAt = 300L,
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(capturedAt = 100L),
            messages = listOf(ChatMessage(role = Role.USER, content = "  第一段\n内容  "))
        )
        val second = ChatSessionRecord(
            id = "second", title = "新标题", updatedAt = 200L,
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(capturedAt = 200L),
            messages = listOf(ChatMessage(role = Role.ASSISTANT, content = "第二段回答"))
        )
        val third = ChatSessionRecord(
            id = "third", title = "新对话", updatedAt = 400L,
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(capturedAt = 300L),
            messages = emptyList()
        )

        val labels = listOf(third, first, second).assistantSessionPresentations(listOf(assistant))

        assertEquals("阿澜", labels["first"]?.name)
        assertEquals("阿澜Ⅱ", labels["second"]?.name)
        assertEquals("阿澜Ⅲ", labels["third"]?.name)
        assertEquals("", labels["third"]?.summary)
        assertEquals("第一段 内容", labels["first"]?.summary)
        assertEquals("第二段回答", labels["second"]?.summary)
        assertEquals("旧标题", first.title)
    }

    @Test
    fun uncertainHistoryDoesNotClaimACharacterCard() {
        val assistant = AssistantRecord(id = "role", name = "阿澜")
        val mixed = ChatSessionRecord(
            id = "mixed", title = "原始记录", messages = emptyList(),
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(capturedAt = 100L),
            mixedAssistantHistory = true
        )

        val label = listOf(mixed).assistantSessionPresentations(listOf(assistant))["mixed"]

        assertNull(label?.assistantId)
        assertEquals("原始记录", label?.name)
    }

    @Test
    fun deletedCardStillUsesItsSavedSessionName() {
        val assistant = AssistantRecord(id = "role", name = "阿澜")
        val session = ChatSessionRecord(
            id = "old", title = "旧标题", messages = emptyList(),
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(capturedAt = 1L)
        )

        val label = listOf(session).assistantSessionPresentations(emptyList())["old"]

        assertEquals("阿澜", label?.name)
        assertEquals("role", label?.assistantId)
    }
}
