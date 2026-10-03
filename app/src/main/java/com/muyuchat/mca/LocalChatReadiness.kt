package com.muyuchat.mca

import com.muyuchat.feature.agent.AgentEngineLifecycle

/** UI projection only: the retained model identity remains available for worker recovery. */
internal fun MainUiState.localChatReadinessLabel(): String = when (engineLifecycle) {
    AgentEngineLifecycle.LOADING -> "加载中"
    AgentEngineLifecycle.RELOADING -> "重新加载中"
    AgentEngineLifecycle.ROLLING_BACK -> "恢复参数中"
    AgentEngineLifecycle.STOPPING -> "停止中"
    else -> when {
        stats.loaded && loadedModelId != null -> if (isGenerating) "生成中" else "已就绪"
        loadedModelId != null -> "需要重载 · 点击模型重试"
        engineLifecycle == AgentEngineLifecycle.ERROR || !stats.lastError.isNullOrBlank() ->
            "加载失败 · 打开模型页重试"
        else -> "待加载 · 点击选择模型"
    }
}

/** A retained recovery target or another model's loaded stats are not a loaded choice. */
internal fun MainUiState.isLocalChatModelLoaded(modelId: String): Boolean =
    selectedChatBackend == ChatBackend.LOCAL &&
        loadedModelId == modelId &&
        stats.loaded &&
        engineLifecycle !in setOf(
            AgentEngineLifecycle.LOADING,
            AgentEngineLifecycle.RELOADING,
            AgentEngineLifecycle.ROLLING_BACK,
            AgentEngineLifecycle.STOPPING
        )

/**
 * Whether the local chat engine currently holds a usable loaded model.
 *
 * [MainUiState.loadedModelId] is deliberately **retained** as a recovery target: when the isolated
 * local text worker dies (crash, reclaim, force-stop) the id stays set so the user can reload it,
 * while [com.muyuchat.core.engine.RuntimeStats.loaded] goes false. UI surfaces that only compare ids
 * therefore keep claiming "已加载" after the worker is gone, and — because the reload affordance is
 * gated on the same flag — leave the user with no way to recover inside the app.
 *
 * Pass this into feature state alongside the id so those surfaces agree with
 * [localChatReadinessLabel].
 */
internal fun MainUiState.isLocalChatEngineReady(): Boolean =
    stats.loaded &&
        engineLifecycle !in setOf(
            AgentEngineLifecycle.LOADING,
            AgentEngineLifecycle.RELOADING,
            AgentEngineLifecycle.ROLLING_BACK,
            AgentEngineLifecycle.STOPPING
        )
