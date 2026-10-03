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

    // ── isLocalChatEngineReady：真机故障注入后新增的契约 ──────────────────────
    //
    // 背景（设备 f9d98aa3 实测）：生成中杀掉 :local_chat 隔离工作进程后，
    // loadedModelId 仍保留（作为恢复目标），stats.loaded 变为 false，顶栏显示"需要重载"。
    // 但"本地"页只比较 id，因此仍显示"已加载"，而重新加载入口又以同一标记为条件 ⇒ 恢复路径不可达。

    @Test
    fun aReadyEngineIsReportedAsReady() {
        assertTrue(readyState().isLocalChatEngineReady())
    }

    @Test
    fun aDeadWorkerKeepsTheRecoveryIdentityButStopsReportingAReadyEngine() {
        val state = readyState().copy(
            stats = RuntimeStats(loaded = false, lastError = "worker session lost"),
            engineLifecycle = AgentEngineLifecycle.ERROR
        )

        // 身份仍保留（供恢复使用）……
        assertEquals("model-a", state.loadedModelId)
        assertEquals("需要重载 · 点击模型重试", state.localChatReadinessLabel())
        // ……但引擎不再被报告为就绪 ⇒ 模型页必须据此展示重新加载入口
        assertFalse(state.isLocalChatEngineReady())
    }

    @Test
    fun transitionalStagesAreNeverReportedAsReady() {
        val stages = listOf(
            AgentEngineLifecycle.LOADING,
            AgentEngineLifecycle.RELOADING,
            AgentEngineLifecycle.ROLLING_BACK,
            AgentEngineLifecycle.STOPPING
        )
        stages.forEach { stage ->
            val state = readyState().copy(engineLifecycle = stage)
            assertFalse("stage=$stage", state.isLocalChatEngineReady())
        }
    }

    @Test
    fun aRequestErrorOnAStillLoadedEngineStaysReady() {
        // 请求失败（例如超出上下文）不等于引擎不可用 ⇒ 仍应报告就绪（与 isLocalChatModelLoaded 一致）
        val state = readyState().copy(
            stats = RuntimeStats(loaded = true, lastError = "request exceeded context")
        )

        assertTrue(state.isLocalChatEngineReady())
    }

    @Test
    fun anEngineWithoutALoadedModelIsNotReady() {
        val pending = MainUiState(selectedChatBackend = ChatBackend.LOCAL)

        assertFalse(pending.isLocalChatEngineReady())
    }
}
