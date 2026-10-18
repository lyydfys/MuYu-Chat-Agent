package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式 commit 的降本契约：
 * - 标题只由第一条 USER 消息决定，流式期间追加 ASSISTANT 不应触发重算；
 * - 首条用户消息被编辑时必须重算；
 * - updatedAt 使用调用方传入的 occurredAt，reducer 内不再隐式读时钟。
 */
class ChatSessionTitleReuseTest {

    private val sessionId = "session-1"

    private fun legacySessions(messages: List<ChatMessage>) = listOf(
        ChatSessionRecord(id = sessionId, title = "LEGACY TITLE", messages = messages)
    )

    @Test
    fun `streaming assistant append reuses existing title`() {
        val existingMessages = listOf(
            ChatMessage(Role.USER, "Hello world", id = "u1"),
            ChatMessage(Role.ASSISTANT, "partial", id = "a1"),
        )
        val streamed = existingMessages.toMutableList().also {
            it[1] = it[1].copy(content = "partial + more tokens")
        }

        val updated = legacySessions(existingMessages).upsertSessionFor(
            state = MainUiState(),
            sessionId = sessionId,
            messages = streamed,
            occurredAt = 1_000L,
        )

        val record = updated.first { it.id == sessionId }
        // 若发生重算，标题会变成 "Hello world"；保持 LEGACY TITLE 证明复用了已有标题。
        assertEquals("LEGACY TITLE", record.title)
        assertEquals(1_000L, record.updatedAt)
        assertEquals("partial + more tokens", record.messages.last().content)
    }

    @Test
    fun `editing the first user message recomputes the title`() {
        val existingMessages = listOf(
            ChatMessage(Role.USER, "Hello world", id = "u1"),
            ChatMessage(Role.ASSISTANT, "reply", id = "a1"),
        )
        val edited = listOf(
            ChatMessage(Role.USER, "Brand new topic", id = "u1"),
            ChatMessage(Role.ASSISTANT, "reply", id = "a1"),
        )

        val updated = legacySessions(existingMessages).upsertSessionFor(
            state = MainUiState(),
            sessionId = sessionId,
            messages = edited,
            occurredAt = 2_000L,
        )

        assertEquals("Brand new topic", updated.first { it.id == sessionId }.title)
    }

    @Test
    fun `new session derives title and uses the provided timestamp`() {
        val messages = listOf(ChatMessage(Role.USER, "# Heading", id = "u1"))

        val updated = emptyList<ChatSessionRecord>().upsertSessionFor(
            state = MainUiState(),
            sessionId = sessionId,
            messages = messages,
            occurredAt = 3_000L,
        )

        val record = updated.first { it.id == sessionId }
        assertEquals("Heading", record.title)
        assertEquals(3_000L, record.updatedAt)
    }

    @Test
    fun `manual title stays authoritative and is never recomputed`() {
        val messages = listOf(ChatMessage(Role.USER, "Hello world", id = "u1"))
        val sessions = listOf(
            ChatSessionRecord(id = sessionId, title = "My pinned name", messages = messages, manualTitle = true)
        )

        val updated = sessions.upsertSessionFor(
            state = MainUiState(),
            sessionId = sessionId,
            messages = messages + ChatMessage(Role.ASSISTANT, "reply", id = "a1"),
            occurredAt = 4_000L,
        )

        assertEquals("My pinned name", updated.first { it.id == sessionId }.title)
        assertEquals(4_000L, updated.first { it.id == sessionId }.updatedAt)
    }
}
