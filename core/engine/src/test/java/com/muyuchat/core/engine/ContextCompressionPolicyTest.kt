package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionPolicyTest {
    @Test
    fun memoryMarkerIsLiteralAndOrdinaryProseIsNotPinned() {
        assertTrue(isContextMemoryMessage(ChatMessage(Role.USER, "$CONTEXT_MEMORY_KEEP_MARKER exact memory")))
        assertFalse(isContextMemoryMessage(ChatMessage(Role.USER, "old question about memory")))
        assertFalse(isContextMemoryMessage(ChatMessage(Role.ASSISTANT, "Maybe we should continue")))
    }

    @Test
    fun automaticCompressionKeepsSystemRoleCardAndRecentTurns() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "role card: always speak as Ada"),
            ChatMessage(Role.USER, "old question 1"),
            ChatMessage(Role.ASSISTANT, "old answer 1"),
            ChatMessage(Role.USER, "old question 2"),
            ChatMessage(Role.ASSISTANT, "old answer 2"),
            ChatMessage(Role.USER, "latest question"),
            ChatMessage(Role.ASSISTANT, "latest answer")
        ).copy(params = GenerationParams(nCtx = 400, systemPrompt = "short"))

        val result = compressChatRequestContext(
            request = request,
            settings = ContextCompressionSettings(
                threshold = ContextCompressionThreshold.SEVENTY,
                keepRecentMessages = 2,
                minimumMessagesToCompress = 2
            ),
            estimatedTokens = 320
        )

        assertTrue(result.status == ContextCompressionStatus.COMPRESSED || result.status == ContextCompressionStatus.FALLBACK_ADMISSION)
        assertEquals(4, result.compressedMessageCount)
        assertTrue(result.request.messages.first().content.contains("role card"))
        assertNotNull(result.summaryMessage)
        assertTrue(result.request.messages.any { it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) })
        assertTrue(result.request.messages.any { it.content == "latest question" })
        assertTrue(result.request.messages.any { it.content == "latest answer" })
        assertFalse(result.request.messages.any { it.content == "old question 1" })
    }

    @Test
    fun automaticCompressionDoesNothingBelowConfiguredThreshold() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "keep"),
            ChatMessage(Role.USER, "one"),
            ChatMessage(Role.ASSISTANT, "two"),
            ChatMessage(Role.USER, "three")
        ).copy(params = GenerationParams(nCtx = 1_024))

        val result = compressChatRequestContext(
            request = request,
            settings = ContextCompressionSettings(threshold = ContextCompressionThreshold.NINETY),
            estimatedTokens = 100
        )

        assertEquals(ContextCompressionStatus.NOT_NEEDED, result.status)
        assertSame(request, result.request)
    }

    @Test
    fun manualCompressionCanRunBelowThresholdAndRetainsProtectedIndices() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system"),
            ChatMessage(Role.USER, "protected lore"),
            ChatMessage(Role.ASSISTANT, "old answer"),
            ChatMessage(Role.USER, "latest")
        ).copy(params = GenerationParams(nCtx = 8_192))

        val result = compressChatRequestContext(
            request = request,
            settings = ContextCompressionSettings(
                keepRecentMessages = 1,
                minimumMessagesToCompress = 1,
                protectedMessageIndices = setOf(1)
            ),
            trigger = ContextCompressionTrigger.MANUAL,
            estimatedTokens = 50
        )

        assertEquals(ContextCompressionStatus.COMPRESSED, result.status)
        assertTrue(result.request.messages.any { it.content == "protected lore" })
        assertTrue(result.request.messages.any { it.content == "latest" })
        assertFalse(result.request.messages.any { it.content == "old answer" })
    }

    @Test
    fun oldMultimodalTurnsAreRetainedUntilVisionAwareSummaryExists() {
        val image = ChatImageAttachment(
            uriString = "content://image/1"
        )
        val request = requestOf(
            ChatMessage(Role.USER, "describe this", imageAttachments = listOf(image)),
            ChatMessage(Role.ASSISTANT, "old description"),
            ChatMessage(Role.USER, "new question")
        ).copy(params = GenerationParams(nCtx = 512, systemPrompt = "short"))

        val result = compressChatRequestContext(
            request = request,
            settings = ContextCompressionSettings(
                threshold = ContextCompressionThreshold.SEVENTY,
                keepRecentMessages = 1,
                minimumMessagesToCompress = 1
            ),
            trigger = ContextCompressionTrigger.MANUAL,
            estimatedTokens = 230
        )

        assertEquals(ContextCompressionStatus.COMPRESSED, result.status)
        assertTrue(result.request.messages.any { it.imageAttachments.isNotEmpty() })
    }

    @Test
    fun summaryIsDeterministicAndSecondPassDoesNotSummarizeItAgain() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "system"),
            ChatMessage(Role.USER, "old user"),
            ChatMessage(Role.ASSISTANT, "old assistant"),
            ChatMessage(Role.USER, "latest")
        ).copy(params = GenerationParams(nCtx = 4_096))
        val settings = ContextCompressionSettings(
            threshold = ContextCompressionThreshold.SEVENTY,
            keepRecentMessages = 1,
            minimumMessagesToCompress = 1
        )

        val first = compressChatRequestContext(request, settings, ContextCompressionTrigger.MANUAL, 230)
        val second = compressChatRequestContext(first.request, settings, ContextCompressionTrigger.MANUAL, 230)

        assertNotNull(first.summaryMessage)
        assertTrue(second.summaryMessage == null)
        assertEquals(ContextCompressionStatus.NOT_NEEDED, second.status)
        assertEquals(1, second.request.messages.count { it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) })
    }

    @Test
    fun pluggableSummarizerReceivesHistoricalTurnsAndPreviousSummary() {
        var captured: ContextSummaryInput? = null
        val summarizer = ContextSummarizer { input ->
            captured = input
            "Key facts: user prefers concise Chinese answers."
        }
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "role card"),
            ChatMessage(Role.USER, "older question"),
            ChatMessage(Role.ASSISTANT, "older answer"),
            ChatMessage(Role.USER, "newest question"),
            ChatMessage(Role.ASSISTANT, "newest answer")
        ).copy(params = GenerationParams(nCtx = 4_096))
        val result = compressChatRequestContext(
            request,
            ContextCompressionSettings(
                keepRecentMessages = 2,
                minimumMessagesToCompress = 1,
                summarizer = summarizer
            ),
            ContextCompressionTrigger.MANUAL
        )

        assertNotNull(captured)
        assertEquals(listOf(1, 2), captured?.historicalMessageIndices)
        assertTrue(result.request.messages.any { it.content == "$CONTEXT_COMPRESSION_SUMMARY_MARKER\nKey facts: user prefers concise Chinese answers." })
        assertTrue(result.request.messages.any { it.content == "role card" })
        assertTrue(result.request.messages.any { it.content == "newest question" })
    }

    @Test
    fun throwingSummarizerUsesDeterministicFallback() {
        val request = requestOf(
            ChatMessage(Role.USER, "older question"),
            ChatMessage(Role.ASSISTANT, "older answer"),
            ChatMessage(Role.USER, "newest question")
        ).copy(params = GenerationParams(nCtx = 4_096))
        val result = compressChatRequestContext(
            request,
            ContextCompressionSettings(
                keepRecentMessages = 1,
                minimumMessagesToCompress = 1,
                summarizer = ContextSummarizer { error("summarizer unavailable") }
            ),
            ContextCompressionTrigger.MANUAL
        )

        assertEquals(ContextCompressionStatus.COMPRESSED, result.status)
        assertTrue(result.summaryMessage?.content?.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) == true)
        assertTrue(result.summaryMessage?.content?.contains("older question") == true)
    }

    @Test
    fun impossibleAdmissionReturnsStableRejectedFallback() {
        val request = requestOf(
            ChatMessage(Role.USER, "older question"),
            ChatMessage(Role.ASSISTANT, "older answer"),
            ChatMessage(Role.USER, "newest question")
        ).copy(params = GenerationParams(nCtx = 4_096))
        val rejected = localContextWindowAdmission(request).copy(
            status = ContextWindowAdmissionStatus.REJECTED,
            userMessage = "deterministic rejection"
        )
        val result = compressChatRequestContext(
            request,
            ContextCompressionSettings(keepRecentMessages = 1, minimumMessagesToCompress = 1),
            ContextCompressionTrigger.MANUAL,
            fallbackAdmission = { rejected }
        )

        assertEquals(ContextCompressionStatus.FALLBACK_ADMISSION, result.status)
        assertEquals("deterministic rejection", result.failureReason)
        assertSame(request, result.request)
    }

    @Test
    fun forcedCompressionAllowsAnOverWindowHistoryToReachFinalAdmission() {
        val request = requestOf(
            ChatMessage(Role.SYSTEM, "role card: preserve this instruction"),
            ChatMessage(Role.USER, "old question " + "x".repeat(1_200)),
            ChatMessage(Role.ASSISTANT, "old answer " + "y".repeat(1_200)),
            ChatMessage(Role.USER, "another old question " + "z".repeat(1_200)),
            ChatMessage(Role.ASSISTANT, "another old answer " + "w".repeat(1_200)),
            ChatMessage(Role.USER, "latest question")
        ).copy(params = GenerationParams(nCtx = 512, nPredict = 8))

        // Simulate a conservative estimator that has not yet observed the
        // actual byte/code-point footprint.  A forced preflight compression
        // must still fold historical turns before the final admission check.
        val result = compressChatRequestContext(
            request = request,
            settings = ContextCompressionSettings(
                threshold = ContextCompressionThreshold.NINETY,
                keepRecentMessages = 1,
                minimumMessagesToCompress = 1
            ),
            trigger = ContextCompressionTrigger.MANUAL,
            estimatedTokens = 1
        )

        assertEquals(ContextCompressionStatus.COMPRESSED, result.status)
        assertTrue(result.request.messages.any { it.role == Role.SYSTEM && it.content.contains("role card") })
        assertTrue(result.request.messages.any { it.content == "latest question" })
        assertFalse(result.request.messages.any { it.content.startsWith("old question") })
        assertTrue(localContextWindowAdmission(result.request).isAccepted)
    }

    @Test
    fun protectedStableIdAndPinnedTurnSurviveCompressionAndFinalAdmission() {
        val pinned = ChatMessage(Role.USER, "relationship: Ada is my sister", pinned = true, id = "pinned")
        val protected = ChatMessage(Role.USER, "preference: use concise answers", id = "protected")
        val old = ChatMessage(Role.ASSISTANT, "long history " + "x".repeat(1_000), id = "old")
        val latest = ChatMessage(Role.USER, "latest", id = "latest")
        val request = requestOf(pinned, protected, old, latest).copy(
            params = GenerationParams(nCtx = 512, nPredict = 8, systemPrompt = ""),
            protectedMessageIds = setOf("protected")
        )
        val result = compressChatRequestContext(
            request,
            ContextCompressionSettings(keepRecentMessages = 1, minimumMessagesToCompress = 1),
            ContextCompressionTrigger.MANUAL
        )

        assertEquals(ContextCompressionStatus.COMPRESSED, result.status)
        assertTrue(result.request.messages.any { it.id == "pinned" })
        assertTrue(result.request.messages.any { it.id == "protected" })
        assertFalse("pinned" in result.summarySourceMessageIds)
        assertFalse("protected" in result.summarySourceMessageIds)
        assertTrue(localContextWindowAdmission(result.request).isAccepted)
    }

    @Test
    fun deterministicSummaryFindsLatePreferenceAndReportsCoverageLimits() {
        val old = ChatMessage(
            Role.USER,
            "background " + "x".repeat(700) + ". I prefer short answers. Need to call Ada tomorrow.",
            id = "old",
            createdAt = 10L
        )
        val input = ContextSummaryInput(
            messages = listOf(old), historicalMessageIndices = listOf(0), maxChars = 600
        )
        val structured = deterministicContextEvidence(input)
        val text = DeterministicContextSummarizer.summarize(input)

        assertTrue(structured.evidence.any {
            it.kind == ContextSummaryKind.PREFERENCE && "prefer short answers" in it.text &&
                it.sourceOffset > 420 && it.sourceMessageIds == listOf("old")
        })
        assertTrue(structured.evidence.any { it.kind == ContextSummaryKind.OPEN_TASK })
        assertTrue(structured.coverageLimited)
        assertTrue(text.contains("[覆盖有限"))
        assertTrue(old.content.contains(structured.evidence.first { it.kind == ContextSummaryKind.PREFERENCE }.text))
    }

    private fun requestOf(vararg messages: ChatMessage): ChatRequest =
        ChatRequest(messages = messages.toList())
}
