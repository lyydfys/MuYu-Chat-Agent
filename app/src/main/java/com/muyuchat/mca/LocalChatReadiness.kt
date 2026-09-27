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
