package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.*
import org.junit.Test

class ChatGenerationMetricsUiTest {
    @Test fun displaysAllFourValuesUnderTheReply() {
        val message = ChatMessage(Role.ASSISTANT, "reply",
            generationMetrics = ChatGenerationMetrics(1245, 86, 12.4, 8000))
        assertEquals("上行 1,245 tokens · 下行 86 tokens · 12.4 tok/s · 用时 8秒", message.generationMetricsSummary())
    }

    @Test fun distinguishesMissingValuesAndEstimates() {
        val message = ChatMessage(Role.ASSISTANT, "reply",
            generationMetrics = ChatGenerationMetrics(120, 30, 2.0, 15_000, true, true))
        assertTrue(message.generationMetricsSummary()!!.contains("上行 约 120"))
        assertTrue(message.generationMetricsSummary()!!.contains("约 2.0 tok/s"))
        val missing = message.copy(generationMetrics = ChatGenerationMetrics(null, null, null, 2000))
        assertEquals("上行 — tokens · 下行 — tokens · — tok/s · 用时 2秒", missing.generationMetricsSummary())
    }

    @Test fun oldMessagesAndUserMessagesHaveNoStatistics() {
        assertNull(ChatMessage(Role.ASSISTANT, "old reply").generationMetricsSummary())
        assertNull(ChatMessage(Role.USER, "question", generationMetrics = ChatGenerationMetrics(1, 2, 3.0, 4))
            .generationMetricsSummary())
    }

    @Test fun elapsedTimeSupportsSecondsMinutesAndHours() {
        assertEquals("0秒", formatGenerationElapsed(0))
        assertEquals("不足1秒", formatGenerationElapsed(750))
        assertEquals("59秒", formatGenerationElapsed(59_999))
        assertEquals("1分0秒", formatGenerationElapsed(60_000))
        assertEquals("1分12秒", formatGenerationElapsed(72_000))
        assertEquals("1小时3分8秒", formatGenerationElapsed(3_788_000))
        assertEquals("25小时0分0秒", formatGenerationElapsed(90_000_000))
    }
}
