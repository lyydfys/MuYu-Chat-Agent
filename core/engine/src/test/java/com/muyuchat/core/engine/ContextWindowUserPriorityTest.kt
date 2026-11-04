package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Admission must not silently drop the user's own messages while a long assistant reply survives.
 *
 * The backward walk alone keeps whatever is newest, so a big reply can push out an older question.
 * These cases pin the rebalance pass that trades an older retained reply for a dropped user message
 * and prove it never breaks the budget or the message order.
 */
class ContextWindowUserPriorityTest {

    @Test
    fun `a dropped user message replaces an older retained reply when the budget allows`() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system policy"),
            ChatMessage(Role.USER, "first question " + "u".repeat(120)),
            ChatMessage(Role.ASSISTANT, "long answer " + "a".repeat(2_400)),
            ChatMessage(Role.USER, "second question " + "v".repeat(120)),
            ChatMessage(Role.ASSISTANT, "short answer"),
            ChatMessage(Role.USER, "latest question")
        )

        val admission = localContextWindowAdmission(request)

        assertEquals(ContextWindowAdmissionStatus.TRIMMED, admission.status)
        val retainedIndices = admission.messageRetention.filter { it.retained }.map { it.originalIndex }
        // The long reply is the cheapest thing to lose, so the first question survives instead.
        assertFalse("long reply should be dropped: $retainedIndices", 2 in retainedIndices)
        assertTrue("first question should be kept: $retainedIndices", 1 in retainedIndices)
        // The newest user message is always kept.
        assertTrue(retainedIndices.contains(5))
    }

    @Test
    fun `the kept messages stay in their original order`() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system policy"),
            ChatMessage(Role.USER, "first question " + "u".repeat(120)),
            ChatMessage(Role.ASSISTANT, "long answer " + "a".repeat(2_400)),
            ChatMessage(Role.USER, "latest question")
        )

        val admission = localContextWindowAdmission(request)
        val kept = admission.request.messages

        val expected = request.messages.filterIndexed { index, _ ->
            admission.messageRetention.firstOrNull { it.originalIndex == index }?.retained == true
        }
        assertEquals(expected.map { it.content }, kept.map { it.content })
    }

    @Test
    fun `the rebalanced request still fits the budget`() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system policy"),
            ChatMessage(Role.USER, "first question " + "u".repeat(400)),
            ChatMessage(Role.ASSISTANT, "long answer " + "a".repeat(3_000)),
            ChatMessage(Role.USER, "latest question")
        )

        val admission = localContextWindowAdmission(request)

        assertTrue(
            "usage=${admission.admittedUsage.estimatedTokens} limit=${admission.limits.promptTokenLimit}",
            admission.admittedUsage.estimatedTokens <= admission.limits.promptTokenLimit
        )
    }

    @Test
    fun `messages that already fit are never reordered or dropped`() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system policy"),
            ChatMessage(Role.USER, "question"),
            ChatMessage(Role.ASSISTANT, "answer"),
            ChatMessage(Role.USER, "latest question")
        )

        val admission = localContextWindowAdmission(request)

        assertEquals(ContextWindowAdmissionStatus.ACCEPTED, admission.status)
        assertEquals(request, admission.request)
        assertTrue(admission.messageRetention.all { it.retained })
    }

    private fun requestOf(vararg messages: ChatMessage): ChatRequest = ChatRequest(
        messages = messages.toList(),
        params = GenerationParams(
            nCtx = 512,
            nPredict = 8,
            nThreads = 1,
            systemPrompt = "",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
    )
}
