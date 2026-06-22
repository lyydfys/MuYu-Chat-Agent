package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningContentFilterTest {
    @Test
    fun separatesThinkTags() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<think>draft")
        val second = filter.filter("</think>final")
        val tail = filter.finish()

        assertEquals("", first.visible)
        assertEquals("draft", first.reasoning + second.reasoning + tail.reasoning)
        assertEquals("final", second.visible + tail.visible)
    }

    @Test
    fun separatesThinkTagsAcrossChunks() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<thi")
        val second = filter.filter("nk>plan</thi")
        val third = filter.filter("nk>answer")
        val tail = filter.finish()

        assertEquals("", first.visible)
        assertEquals("plan", first.reasoning + second.reasoning + third.reasoning + tail.reasoning)
        assertEquals("answer", first.visible + second.visible + third.visible + tail.visible)
    }

    @Test
    fun separatesLeadingPlainThinkingProcess() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("Thinking Process: analyze the greeting. ")
        val second = filter.filter("Final Answer: hello")
        val tail = filter.finish()

        assertEquals("analyze the greeting.", (first.reasoning + second.reasoning + tail.reasoning).trim())
        assertEquals("hello", (first.visible + second.visible + tail.visible).trim())
    }

    @Test
    fun leavesMidAnswerAnalysisTextVisible() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("Here is the answer. Analysis: this is a section title.")
        val tail = filter.finish()

        assertEquals("", first.reasoning + tail.reasoning)
        assertEquals("Here is the answer. Analysis: this is a section title.", (first.visible + tail.visible).trim())
    }

    @Test
    fun keepsNumberedEnglishReasoningAsVisibleText() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("1. **Analyze the Request:** user says hi. ")
        val second = filter.filter("Final Answer: Hello")
        val tail = filter.finish()

        assertEquals("", first.reasoning + second.reasoning + tail.reasoning)
        assertEquals(
            "1. **Analyze the Request:** user says hi. Final Answer: Hello",
            (first.visible + second.visible + tail.visible).trim()
        )
    }

    @Test
    fun keepsUnfinishedLeadingPlainThinkingInReasoningContent() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("Thinking Process:1. **Analyze the Request:** user asks identity.")
        val tail = filter.finish()

        assertEquals("", first.visible + tail.visible)
        assertTrue((first.reasoning + tail.reasoning).contains("Analyze the Request"))
    }

    @Test
    fun separatesGemmaChannelThought() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<|channel>thoughtThe user asks identity.<channel|>I am Gemma.")
        val tail = filter.finish()

        val reasoning = first.reasoning + tail.reasoning
        val visible = first.visible + tail.visible

        assertTrue(reasoning.contains("user asks identity"))
        assertFalse(visible.contains("<|channel>"))
        assertFalse(visible.contains("<channel"))
        assertEquals("I am Gemma.", visible.trim())
    }

    @Test
    fun separatesGemmaChannelThoughtAcrossChunks() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<|channel>tho")
        val second = filter.filter("ughtPlan.")
        val third = filter.filter("<|channel|>finalAnswer.")
        val tail = filter.finish()

        assertEquals("Plan.", (first.reasoning + second.reasoning + third.reasoning + tail.reasoning).trim())
        assertEquals("Answer.", (first.visible + second.visible + third.visible + tail.visible).trim())
    }

    @Test
    fun separatesGemmaChannelThoughtWithLegacyCloseSpelling() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<|channel>thoughtPlan briefly.<channel>Final answer.")
        val tail = filter.finish()

        assertEquals("Plan briefly.", (first.reasoning + tail.reasoning).trim())
        assertEquals("Final answer.", (first.visible + tail.visible).trim())
    }

    @Test
    fun leavesUnfinishedTaggedReasoningInReasoningContent() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<think>analyze without close marker.")
        val tail = filter.finish()

        assertEquals("", first.visible + tail.visible)
        assertTrue((first.reasoning + tail.reasoning).contains("analyze without close marker"))
    }

    @Test
    fun separatesDeepSeekThinkBlockWithFinalAnswer() {
        val filter = ReasoningContentFilter()

        val first = filter.filter("<think>\n先判断用户意图。\n</think>\n我是 MCA。")
        val tail = filter.finish()

        assertEquals("先判断用户意图。", (first.reasoning + tail.reasoning).trim())
        assertEquals("我是 MCA。", (first.visible + tail.visible).trim())
    }

    @Test
    fun chatRequestDoesNotSendReasoningBackIntoContext() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(Role.USER, "Who are you?"),
                ChatMessage(
                    role = Role.ASSISTANT,
                    content = "I am MCA.",
                    reasoningContent = "secret internal reasoning that must not be replayed"
                )
            )
        )

        val messagesJson = request.messagesJson()

        assertTrue(messagesJson.contains("I am MCA."))
        assertFalse(messagesJson.contains("secret internal reasoning"))
    }
}
