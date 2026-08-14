package com.muyuchat.feature.chat

import com.muyuchat.core.engine.RuntimeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationPerformanceUiTest {
    @Test
    fun `shows prefill and output rates in a stable order`() {
        assertEquals(
            "预填充 8.4 token/s · 输出 2.0 token/s",
            generationPerformanceSummary(
                RuntimeStats(
                    prefillTps = 8.38162,
                    decodeTps = 2.02634
                )
            )
        )
    }

    @Test
    fun `omits unavailable and nonfinite rates`() {
        assertEquals(
            "输出 12.5 token/s",
            generationPerformanceSummary(RuntimeStats(prefillTps = Double.NaN, decodeTps = 12.5))
        )
        assertNull(generationPerformanceSummary(RuntimeStats(prefillTps = 0.0, decodeTps = 0.0)))
        assertNull(generationPerformanceSummary(null))
    }

    @Test
    fun `shows effective throughput separately after a cache hit`() {
        assertEquals(
            "预填充 8.0 token/s · 缓存有效 40.0 token/s · 输出 12.0 token/s",
            generationPerformanceSummary(
                RuntimeStats(
                    prefillTps = 8.0,
                    effectivePromptTps = 40.0,
                    cacheReusedTokens = 96,
                    decodeTps = 12.0
                )
            )
        )
    }

    @Test
    fun `composer feedback promotes blocking send failures`() {
        assertTrue(composerStatusIsError("当前文件内容超过模型上下文，消息未发送。"))
        assertTrue(composerStatusIsError("知识库导入失败：无法读取文件"))
        assertFalse(composerStatusIsError("已导入知识库文档：资料.txt（2 段），已启用"))
    }
}
