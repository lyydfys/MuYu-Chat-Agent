package com.muyuchat.mca

import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.feature.agent.AgentEngineLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatReadinessTest {
    private fun readyState() = MainUiState(
        selectedChatBackend = ChatBackend.LOCAL,
        loadedModelId = "model-a",
        loadedModelName = "Model A",
        stats = RuntimeStats(loaded = true),
        engineLifecycle = AgentEngineLifecycle.READY
    )

    @Test
    fun retainedWorkerRecoveryIdentityIsNotPresentedAsReady() {
        val state = readyState().copy(
            stats = RuntimeStats(loaded = false, lastError = "worker session lost"),
            engineLifecycle = AgentEngineLifecycle.ERROR
        )

        assertEquals("需要重载 · 点击模型重试", state.localChatReadinessLabel())
        assertFalse(state.isLocalChatModelLoaded("model-a"))
        assertEquals("model-a", state.loadedModelId)
        assertEquals("Model A", state.loadedModelName)
    }

    @Test
    fun aDifferentModelCannotBorrowTheCurrentModelsLoadedFlag() {
        val state = readyState()

        assertTrue(state.isLocalChatModelLoaded("model-a"))
        assertFalse(state.isLocalChatModelLoaded("model-b"))
        assertEquals("已就绪", state.localChatReadinessLabel())
    }

    @Test
    fun aCloudSelectionDoesNotHighlightTheResidentLocalModelAsCurrent() {
        val state = readyState().copy(selectedChatBackend = ChatBackend.CLOUD)

        assertFalse(state.isLocalChatModelLoaded("model-a"))
    }

    @Test
    fun loadingAndSwitchingStagesOverrideOldLoadedStats() {
        val stages = mapOf(
            AgentEngineLifecycle.LOADING to "加载中",
            AgentEngineLifecycle.RELOADING to "重新加载中",
            AgentEngineLifecycle.ROLLING_BACK to "恢复参数中",
            AgentEngineLifecycle.STOPPING to "停止中"
        )
        stages.forEach { (stage, label) ->
            val state = readyState().copy(engineLifecycle = stage)
            assertEquals(label, state.localChatReadinessLabel())
            assertFalse(state.isLocalChatModelLoaded("model-a"))
        }
    }

    @Test
    fun aFailedRequestDoesNotMislabelAStillLoadedModelAsUnloaded() {
        val state = readyState().copy(
            stats = RuntimeStats(loaded = true, lastError = "request exceeded context")
        )

        assertEquals("已就绪", state.localChatReadinessLabel())
        assertTrue(state.isLocalChatModelLoaded("model-a"))
    }

    @Test
    fun pendingAndFailedInitialLoadsHaveDifferentActionableLabels() {
        val pending = MainUiState(selectedChatBackend = ChatBackend.LOCAL)
        val failed = pending.copy(
            engineLifecycle = AgentEngineLifecycle.ERROR,
            stats = RuntimeStats(loaded = false, lastError = "model corrupt")
        )

        assertEquals("待加载 · 点击选择模型", pending.localChatReadinessLabel())
        assertEquals("加载失败 · 打开模型页重试", failed.localChatReadinessLabel())
        assertFalse(failed.isLocalChatModelLoaded("model-a"))
    }

    @Test
    fun activeGenerationRetainsReadyIdentityAndShowsGenerationState() {
        val state = readyState().copy(
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        )

        assertEquals("生成中", state.localChatReadinessLabel())
        assertTrue(state.isLocalChatModelLoaded("model-a"))
    }
}
