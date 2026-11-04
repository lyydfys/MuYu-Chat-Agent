package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.estimateChatRequestContextTokens
import com.muyuchat.feature.chat.ChatQuotedMessage
import com.muyuchat.feature.chat.withQuotedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuoteContextBudgetTest {

    @Test
    fun `a quote increases the existing context token estimate`() {
        val message = ChatMessage(Role.USER, "请解释这一点")
        val quote = ChatQuotedMessage(
            role = Role.ASSISTANT,
            authorLabel = "助手",
            content = "被引用的回答内容。".repeat(40)
        )
        val plain = estimateChatRequestContextTokens(ChatRequest(messages = listOf(message)))
        val quoted = estimateChatRequestContextTokens(
            ChatRequest(messages = listOf(message.withQuotedContext(quote)))
        )

        assertTrue("the quote must be counted by the existing estimator", quoted > plain)
    }

    @Test
    fun `no quote leaves the existing context estimate unchanged`() {
        val message = ChatMessage(Role.USER, "你好")
        assertEquals(
            estimateChatRequestContextTokens(ChatRequest(messages = listOf(message))),
            estimateChatRequestContextTokens(
                ChatRequest(messages = listOf(message.withQuotedContext(null)))
            )
        )
    }

    @Test
    fun `the prompt draft writer owns both setting and clearing the quote`() {
        val quote = ChatQuotedMessage(role = Role.USER, authorLabel = "我", content = "引用")
        val base = MainUiState(input = "草稿")
        val withQuote = base.toChatPromptDraftFields().copy(quotedMessage = quote).mergeInto(base)
        assertEquals(quote, withQuote.quotedMessage)
        assertEquals("草稿", withQuote.input)

        val cleared = withQuote.toChatPromptDraftFields().copy(quotedMessage = null).mergeInto(withQuote)
        assertNull(cleared.quotedMessage)
        assertEquals("草稿", cleared.input)
    }

    @Test
    fun `copying unrelated state keeps the pending quote until it is cleared`() {
        val quote = ChatQuotedMessage(role = Role.USER, authorLabel = "我", content = "引用")
        val state = MainUiState(quotedMessage = quote)
        val afterAnotherDomainWrite = state.copy(statusMessage = "已保存")
        assertEquals(quote, afterAnotherDomainWrite.quotedMessage)
        assertNull(afterAnotherDomainWrite.copy(quotedMessage = null).quotedMessage)
    }
}
