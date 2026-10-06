package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentTuningJobState
import kotlinx.coroutines.withTimeout

internal suspend fun timedLoadModelFor(
    engine: McaInferenceService,
    activeRuntimeIdentity: ModelRuntimeIdentity?,
    modelStore: ModelStoreRepository,
    context: Context,
    device: DeviceProfile,
    installationScopeId: String,
    model: ModelManifest,
    runtime: LocalChatRuntime,
    loadParams: LoadParams,
    currentQairtBundleSha256: (requested: ModelManifest, persistedModels: List<ModelManifest>) -> String?,
    currentQairtVerifiedLocalModelIds: (context: Context, persistedModels: List<ModelManifest>) -> Set<String>
): Long {
    // Stability smoke reloads must use the same identity/profile as the
    // formal model-page load. Falling back to McaInferenceService's legacy
    // identity builder here would key the reload by mtime/length and make
    // a tuning candidate appear to belong to a different model.
    val activeProfile = engine.activeExecutionProfile()
        ?.takeIf {
            it.runtimeIdentity.modelId == model.id &&
                it.runtimeIdentity.runtime == runtime
        }
    val activeIdentity = activeRuntimeIdentity
        ?.takeIf {
            it.modelId == model.id && it.runtime == runtime
        }
    val identity = activeIdentity
        ?: activeProfile?.runtimeIdentity
        ?: RuntimeIdentityFactory.create(
            context = context,
            model = model,
            runtime = runtime,
            device = device,
            installationScopeId = installationScopeId,
            qairtAdmissionPassed = model.runtime == ChatModelRuntime.GENIEX_QAIRT &&
                model.id in currentQairtVerifiedLocalModelIds(context, modelStore.listModels())
        )
    val profile = activeProfile
        ?.takeIf { it.runtimeIdentity.identityHash == identity.identityHash }
    val started = android.os.SystemClock.elapsedRealtime()
    engine.loadModel(
        modelPath = model.path,
        runtime = runtime,
        params = loadParams,
        qairtBundleSha256 = currentQairtBundleSha256(
            model,
            modelStore.listModels()
        ),
        runtimeIdentity = identity,
        executionProfile = profile
    ).getOrThrow()
    return (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
}

internal suspend fun runLocalGenerationSmokeFor(
    engine: McaInferenceService,
    params: GenerationParams
): LocalGenerationSmokeResult {
    val smokeParams = params.copy(
        nPredict = 32,
        reasoningMode = ReasoningMode.OFF,
        hideReasoning = true,
        systemPrompt = "你是 MCA 稳定性自检助手。请只输出简短中文结果。"
    )
    val text = StringBuilder()
    var finalStats = RuntimeStats()
    var errorMessage: String? = null
    withTimeout(60_000L) {
        engine.streamChat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "用一句中文回答：稳定性自检完成。")),
                params = smokeParams
            )
        ).collect { event ->
            when (event) {
                is GenerateEvent.Phase -> finalStats = event.stats
                is GenerateEvent.Persist -> Unit
                is GenerateEvent.Chunk -> {
                    text.append(event.text)
                    finalStats = event.stats
                }
                is GenerateEvent.Done -> finalStats = event.stats
                is GenerateEvent.Error -> errorMessage = event.message
            }
        }
    }
    errorMessage?.let { error(it) }
    if (text.isBlank()) error("短流式生成没有产生可见正文")
    return LocalGenerationSmokeResult(
        visibleChars = text.length,
        completionTokens = finalStats.completionTokens,
        decodeTps = finalStats.decodeTps
    )
}

internal suspend fun recoverInterruptedRuntimeProfilesFor(
    runtimeProfileStore: ModelRuntimeProfileStore,
    pendingRuntimeRecoveries: MutableMap<String, RuntimeRecoveryPlan>,
    uiStateStore: UiStateStore
) {
    runCatching {
        val plans = runtimeProfileStore.recoverInterruptedTransactions()
        synchronized(pendingRuntimeRecoveries) {
            pendingRuntimeRecoveries.clear()
            plans.forEach { plan -> pendingRuntimeRecoveries[plan.identityKey] = plan }
        }
        runtimeProfileStore.pruneRejectedProfiles()
        runtimeProfileStore.pruneTerminalHistory()
        if (plans.isNotEmpty()) {
            uiStateStore.update { state ->
                state.copy(
                    tuningJobState = AgentTuningJobState.RECOVERING,
                    engineLifecycle = if (state.loadedModelId == null) {
                        AgentEngineLifecycle.UNLOADED
                    } else {
                        state.engineLifecycle
                    },
                    tuningPhase = "等待对应模型加载后恢复 LKG",
                    statusMessage = "检测到 ${plans.size} 个中断的调参事务；加载对应模型时将最多回滚一次。"
                )
            }
        }
    }.onFailure { error ->
        uiStateStore.update { state ->
            state.copy(
                tuningJobState = AgentTuningJobState.FAILED,
                tuningPhase = "启动恢复检查失败",
                statusMessage = "运行配置恢复检查失败：${error.message ?: "未知错误"}"
            )
        }
    }
}
