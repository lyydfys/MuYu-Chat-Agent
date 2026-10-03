package com.muyuchat.feature.modelhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地」模型卡的"已加载"判定。
 *
 * **真机回归背景**（设备 `f9d98aa3`，Round 268–269 故障注入）：在生成过程中杀掉 `:local_chat`
 * 隔离工作进程后，`loadedModelId` 被**有意保留**为恢复目标，而 `stats.loaded` 变为 false，顶栏正确
 * 显示 ``需要重载 · 点击模型重试``。但「本地」页原先只比较 id，于是**同一屏上**出现
 *
 * ```
 * 顶栏：   需要重载 · 点击模型重试
 * 模型卡： 已加载
 * 页面内： 无「加载」按钮      ← 重新加载入口以同一标记为条件，因此永不出现
 * ```
 *
 * ⇒ 用户被引导去"点击模型重试"，但**应用内没有任何可达的恢复动作**。
 * 这组用例锁定修复：卡片只有在 **id 匹配且引擎仍持有该模型** 时才可声称已加载。
 */
class LocalChatModelCardLoadedTest {

    private fun state(
        loadedModelId: String? = "model-a",
        localChatEngineReady: Boolean = true
    ) = ModelHubUiState(
        loadedModelId = loadedModelId,
        localChatEngineReady = localChatEngineReady
    )

    @Test
    fun aLiveEngineWithMatchingIdShowsLoaded() {
        assertTrue(localChatModelCardIsLoaded(state(), "model-a"))
    }

    @Test
    fun aDeadWorkerStopsTheCardClaimingLoadedWhileTheIdIsRetained() {
        val dead = state(loadedModelId = "model-a", localChatEngineReady = false)

        // 身份保留（供恢复）……
        assertTrue(dead.loadedModelId == "model-a")
        // ……但卡片不得再声称"已加载"，否则重新加载入口不可达
        assertFalse(localChatModelCardIsLoaded(dead, "model-a"))
    }

    @Test
    fun anotherModelsIdIsNeverReportedAsLoaded() {
        assertFalse(localChatModelCardIsLoaded(state(), "model-b"))
    }

    @Test
    fun noLoadedIdentityIsNotLoaded() {
        assertFalse(localChatModelCardIsLoaded(state(loadedModelId = null), "model-a"))
    }

    @Test
    fun theDefaultKeepsLegacyBehaviourForUnwiredCallers() {
        // 预览/测试未接线时应保持旧行为（默认 true），避免影响无关调用方
        val legacy = ModelHubUiState(loadedModelId = "model-a")

        assertTrue(legacy.localChatEngineReady)
        assertTrue(localChatModelCardIsLoaded(legacy, "model-a"))
    }
}
