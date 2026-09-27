package com.muyuchat.feature.chat

import com.muyuchat.core.engine.RuntimeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelReadinessUiTest {
    private fun readyState() = ChatUiState(
        selectedModelId = "model-a",
        selectedModelName = "Model A",
        selectedModelRuntimeLabel = "MNN",
        modelReadinessLabel = "已就绪",
        localModels = listOf(ChatModelChoice("model-a", "Model A", loaded = true)),
        stats = RuntimeStats(loaded = true, decodeTps = 12.5, cacheReuseHit = true, cacheReusedTokens = 42)
    )

    @Test
    fun lostRuntimeHidesOldPerformanceAndCacheEvenWhenItsNameIsRetained() {
        val state = readyState().copy(
            stats = readyState().stats.copy(loaded = false),
            modelReadinessLabel = "需要重载 · 点击模型重试"
        )

        assertFalse(state.hasReadyLocalModelForHeader())
        assertEquals("需要重载 · 点击模型重试 · MNN", state.modelHeaderSubtitle())
        assertEquals("Model A", state.selectedModelName)
    }

    @Test
    fun anotherLoadedChoiceCannotSupplySelectedModelsPerformance() {
        val state = readyState().copy(
            localModels = listOf(ChatModelChoice("model-b", "Model B", loaded = true)),
            modelReadinessLabel = "需要重载"
        )

        assertFalse(state.hasReadyLocalModelForHeader())
        assertEquals("需要重载 · MNN", state.modelHeaderSubtitle())
    }

    @Test
    fun loadingSelectionHidesOldMetricsAndUsesRealLoadStage() {
        val state = readyState().copy(
            localModels = listOf(ChatModelChoice("model-a", "Model A", loaded = false)),
            modelReadinessLabel = "加载中",
            modelLoadMessage = "正在读取模型权重"
        )

        assertFalse(state.hasReadyLocalModelForHeader())
        assertEquals("正在读取模型权重", state.modelHeaderSubtitle())
    }

    @Test
    fun readyLocalSelectionDisplaysReadinessBeforePerformance() {
        val state = readyState()

        assertTrue(state.hasReadyLocalModelForHeader())
        assertTrue(state.modelHeaderSubtitle().startsWith("已就绪 · MNN"))
        assertTrue(state.modelHeaderSubtitle().contains("12.5"))
    }

    @Test
    fun cloudSelectionDoesNotDisplayResidentLocalRuntimeMetrics() {
        val state = readyState().copy(
            selectedModelIsCloud = true,
            selectedModelRuntimeLabel = "OpenAI 兼容",
            modelReadinessLabel = null
        )

        assertFalse(state.hasReadyLocalModelForHeader())
        assertEquals("OpenAI 兼容", state.modelHeaderSubtitle())
    }

    @Test
    fun freshStateExplainsHowToBegin() {
        assertEquals("待加载 · 点击选择模型", ChatUiState().modelHeaderSubtitle())
        assertFalse(ChatUiState().hasReadyLocalModelForHeader())
    }
}
