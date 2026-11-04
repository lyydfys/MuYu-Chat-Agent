package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuoteModelsTest {

    @Test
    fun `preview keeps short text and caps long text at sixty characters`() {
        val short = ChatQuotedMessage(content = "短消息")
        assertEquals("短消息", short.preview)

        val long = ChatQuotedMessage(content = "引".repeat(120))
        assertEquals(CHAT_QUOTE_PREVIEW_CHARS, long.preview.length)
        assertTrue(long.preview.endsWith("…"))
    }

    @Test
    fun `preview collapses whitespace so the bar stays one compact block`() {
        val quote = ChatQuotedMessage(content = "第一行\n\n第二行\t  第三行")
        assertEquals("第一行 第二行 第三行", quote.preview)
    }

    @Test
    fun `factory rejects a message without visible text or an image`() {
        assertNull(chatQuotedMessageFor(ChatMessage(Role.ASSISTANT, "   "), "助手"))
        assertNull(chatQuotedMessageFor(ChatMessage(Role.USER, ""), "我"))
    }

    @Test
    fun `an image-only user message is still quotable`() {
        val quote = requireNotNull(
            chatQuotedMessageFor(
                ChatMessage(
                    Role.USER,
                    "",
                    imageAttachments = listOf(
                        ChatImageAttachment(name = "a.png", uriString = "content://a")
                    )
                ),
                "我"
            )
        )
        assertEquals("（图片）", quote.content)
    }

    @Test
    fun `factory bounds the quote and keeps the author label`() {
        val quote = requireNotNull(
            chatQuotedMessageFor(ChatMessage(Role.USER, "x".repeat(5_000)), "我")
        )
        assertEquals(CHAT_QUOTE_CONTEXT_MAX_CHARS, quote.content.length)
        assertEquals("我", quote.authorLabel)
        assertEquals(Role.USER, quote.role)
    }

    @Test
    fun `no quote returns the exact same message instance`() {
        val message = ChatMessage(Role.USER, "你好")
        assertSame(message, message.withQuotedContext(null))
        assertSame(message, message.withQuotedContext(ChatQuotedMessage(content = "  ")))
    }

    @Test
    fun `quote is a delimited structured block prepended to the user text`() {
        val message = ChatMessage(Role.USER, "请继续解释")
        val quote = ChatQuotedMessage(
            messageId = "m-1",
            role = Role.ASSISTANT,
            authorLabel = "小助手",
            content = "上一轮回答"
        )

        val request = message.withQuotedContext(quote)
        assertTrue(request.content.startsWith("[引用消息]"))
        assertTrue(request.content.contains("作者：小助手（助手）"))
        assertTrue(request.content.contains("上一轮回答"))
        assertTrue(request.content.contains("[/引用消息]"))
        assertTrue(request.content.endsWith("请继续解释"))
        assertFalse("the quote must not be paraphrased into a chat sentence", request.content.contains("他刚才说"))
    }

    @Test
    fun `assistant quote carries the assistant role label`() {
        val quote = ChatQuotedMessage(
            role = Role.ASSISTANT,
            authorLabel = "  ",
            content = "回答"
        )
        assertTrue(quote.toModelContext().contains("（助手）"))
        assertTrue(quote.toModelContext().contains("作者：助手"))
    }
}
