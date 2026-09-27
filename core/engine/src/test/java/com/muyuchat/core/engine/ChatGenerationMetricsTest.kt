package com.muyuchat.core.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGenerationMetricsTest {
    @Test fun cloudRateUsesTheWholeRequestInsteadOfBufferedChunkArrivalSpeed() {
        val metrics = RuntimeStats(backend = "cloud", promptTokens = 40, completionTokens = 20,
            decodeTps = 20_000.0, e2eTps = 2.0).toChatGenerationMetrics(10_000)
        assertEquals(2.0, metrics.tokensPerSecond!!, 0.0)
    }

    @Test fun snapshotUsesOutputRateAndKeepsLogicalInputIncludingCache() {
        val stats = RuntimeStats(promptTokens = 4000, completionTokens = 32, decodeTps = 12.5,
            e2eTps = 3.0, prefillTokens = 100, cacheReusedTokens = 3900)
        val metrics = stats.toChatGenerationMetrics(10_500)
        assertEquals(4000, metrics.promptTokens)
        assertEquals(32, metrics.completionTokens)
        assertEquals(12.5, metrics.tokensPerSecond!!, 0.0)
        assertEquals(10_500L, metrics.elapsedMs)
    }

    @Test fun invalidRateFallsBackWithoutPersistingNanOrInfinity() {
        assertEquals(4.5, RuntimeStats(decodeTps = Double.NaN, e2eTps = 4.5)
            .toChatGenerationMetrics(1).tokensPerSecond!!, 0.0)
        val metrics = RuntimeStats(decodeTps = Double.POSITIVE_INFINITY, e2eTps = -1.0)
            .toChatGenerationMetrics(-2)
        assertNull(metrics.tokensPerSecond)
        assertEquals(0L, metrics.elapsedMs)
        assertEquals(metrics, ChatGenerationMetrics.fromJson(metrics.toJson()))
    }

    @Test fun jsonPreservesPartialEstimatesAndMissingLegacyMetrics() {
        val metrics = ChatGenerationMetrics(900, 51, 12.75, 3_600_001, true, false)
        assertEquals(metrics, ChatGenerationMetrics.fromJson(JSONObject(metrics.toJson().toString())))
        assertNull(ChatGenerationMetrics.fromJson(null))
        assertNull(ChatGenerationMetrics.fromJson(JSONObject()))
    }

    @Test fun statisticsNeverBecomeModelInput() {
        val message = ChatMessage(Role.ASSISTANT, "reply",
            generationMetrics = ChatGenerationMetrics(900, 51, 12.0, 4000))
        val input = ChatRequest(listOf(message)).messagesJson()
        assertFalse(input.contains("generationMetrics"))
        assertFalse(input.contains("tokensPerSecond"))
    }
}
