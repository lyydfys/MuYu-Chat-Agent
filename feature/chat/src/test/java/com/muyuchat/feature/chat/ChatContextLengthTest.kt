package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the chat model `n_ctx` dialog contract: range plus the output-reservation rule. */
class ChatContextLengthTest {

    @Test
    fun acceptsAContextAboveTheOutputBudget() {
        assertNull(validateChatContextLength("16384", 1024))
        assertNull(validateChatContextLength(" 8193 ", 8192))
        assertNull(validateChatContextLength(MIN_CHAT_CONTEXT_LENGTH.toString(), 16))
        assertNull(validateChatContextLength(MAX_CHAT_CONTEXT_LENGTH.toString(), 65_536))
    }

    @Test
    fun rejectsAContextThatLeavesNoPromptBudget() {
        assertEquals(
            "上下文 n_ctx 必须大于最大输出 tokens（当前 8192）",
            validateChatContextLength("8192", 8192)
        )
        assertEquals(
            "上下文 n_ctx 必须大于最大输出 tokens（当前 4096）",
            validateChatContextLength("2048", 4096)
        )
    }

    @Test
    fun rejectsNonNumericAndOutOfRangeValues() {
        assertEquals(
            "上下文 n_ctx 请输入 512 到 1048576 之间的整数",
            validateChatContextLength("abc", 1024)
        )
        assertEquals(
            "上下文 n_ctx 请输入 512 到 1048576 之间的整数",
            validateChatContextLength("128", 16)
        )
        assertEquals(
            "上下文 n_ctx 请输入 512 到 1048576 之间的整数",
            validateChatContextLength("2000000", 1024)
        )
    }
}
