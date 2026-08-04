package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowAdmissionTest {
    @Test
    fun footprintCountsUtf8BytesAndCodePointsWithoutTreatingEmojiAsUtf16Pairs() {
        val footprint = localPromptTextFootprint("A\u4e2d\uD83D\uDE42")

        assertEquals(8L, footprint.utf8Bytes)
        assertEquals(3L, footprint.codePointCount)
        assertEquals(4, footprint.estimatedTokens)
    }

    @Test
    fun denseCodeIsEstimatedMoreConservativelyThanPlainLatinText() {
        val prose = estimateLocalPromptTokens("a".repeat(120))
        val code = estimateLocalPromptTokens("x=1;".repeat(30))

        assertEquals(40, prose)
        assertTrue("code=$code prose=$prose", code > prose)
    }

    @Test
    fun acceptedRequestReturnsTheOriginalRequestWithoutCopyingItsMessages() {
        val request = requestOf(ChatMessage(Role.USER, "brief question"))

        val admission = localContextWindowAdmission(request)

        assertEquals(ContextWindowAdmissionStatus.ACCEPTED, admission.status)
        assertSame(request, admission.request)
        assertEquals(0, admission.trimmedMessageCount)
        assertTrue(admission.admittedUsage.estimatedTokens <= admission.limits.promptTokenLimit)
    }

    @Test
    fun oversizedHistoricalTurnIsTrimmedWhileNewestUserInputAndOutputReserveRemain() {
        val oldUser = "old-user:" + "a".repeat(3_000)
        val latestUser = "latest user input"
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system policy"),
            ChatMessage(Role.USER, oldUser),
            ChatMessage(Role.ASSISTANT, "old answer"),
            ChatMessage(Role.USER, latestUser)
        )

        val first = localContextWindowAdmission(request)
        val second = localContextWindowAdmission(request)

        assertEquals(ContextWindowAdmissionStatus.TRIMMED, first.status)
        assertEquals(first, second)
        assertTrue(first.trimmedMessageCount >= 1)
        assertFalse(first.request.messages.any { it.content == oldUser })
        assertTrue(first.request.messages.any { it.role == Role.USER && it.content == latestUser })
        assertEquals(64, first.budget.reservedOutputTokens)
        assertTrue(first.admittedUsage.estimatedTokens <= first.limits.promptTokenLimit)
    }

    @Test
    fun newestUserIsReservedEvenWhenAFollowingAssistantTurnMustBeDropped() {
        val latestUser = "u".repeat(600)
        val trailingAssistant = "a".repeat(900)
        val request = requestOf(
            ChatMessage(Role.USER, latestUser),
            ChatMessage(Role.ASSISTANT, trailingAssistant)
        )

        val admission = localContextWindowAdmission(request)

        assertEquals(ContextWindowAdmissionStatus.TRIMMED, admission.status)
        assertEquals(listOf(latestUser), admission.request.messages.map { it.content })
        assertEquals(64, admission.budget.reservedOutputTokens)
    }

    @Test
    fun oversizedLatestCjkInputIsRejectedInsteadOfSilentlyChangingUserInput() {
        val latestUser = "\u4e2d".repeat(400)

        val admission = localContextWindowAdmission(requestOf(ChatMessage(Role.USER, latestUser)))

        assertEquals(ContextWindowAdmissionStatus.REJECTED, admission.status)
        assertEquals(ContextWindowRejectionCode.LATEST_USER_INPUT_TOO_LARGE, admission.rejectionCode)
        assertEquals(ContextWindowLimit.TOKENS, admission.limitExceeded)
        assertSame(latestUser, admission.request.messages.single().content)
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
