package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentTuningJobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R3-S23：模型装载/卸载/删除纵切片自 MainViewModel 迁出（体逐字；依赖参数化/容器对象）。
 *
 * `restoreLoadedRuntimeSnapshotFor` 为装载恢复投影；`unloadModelFor`/`deleteModelFor` 为
 * 用户动作（launch 由 scope 参数承载，`cancelGeneration` 以函数参数注入——VM 传 `{ cancelGenerationJob() }`）。
 */
internal suspend fun restoreLoadedRuntimeSnapshotFor(
    store: UiStateWriter<RuntimeProfileFields>,
    admission: RuntimeAdmissionState,
    engine: McaInferenceService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    modelStore: ModelStoreRepository,
    tuningOwner: TuningOwner,
    readinessGate: ManagedRuntimeReadinessRefreshGate,
    snapshot: LoadedRuntimeSnapshot,
    statusMessage: String
): Boolean {
    if (!tuningOwner.restoreExactRuntimeProfile(snapshot.model, snapshot.profile)) return false
    val activeProfile = engine.activeExecutionProfile() ?: snapshot.profile
    val profileState = runtimeProfileStore.currentRuntimeState(activeProfile.runtimeIdentity.identityHash)
    admission.activeRuntimeIdentity = activeProfile.runtimeIdentity
    admission.activeVisionProjectorStamp = loadedVisionProjectorStamp(snapshot.model, activeProfile.runtimeIdentity)
    admission.activeModelForRuntimeProfile = snapshot.model
    admission.activeAdaptiveRecommendation = snapshot.adaptiveRecommendation
    admission.pendingAdaptiveRecommendation = null
    admission.pendingProfileTransactionId = null
    admission.activeProfileTransactionId = null
    val nativeStats = engine.nativeStatsJsonOrEmpty()
    val recentLogs = engine.recentEngineLogsOrEmpty()
    readinessGate.invalidate()
    val restoredModels = modelStore.listModels()
    val restoredStats = engine.stats.value
    store.update { state ->
        state.copy(
            models = restoredModels,
            loadedModelId = snapshot.model.id,
            loadedModelName = snapshot.model.displayName,
            selectedChatBackend = snapshot.uiState.selectedChatBackend,
            busy = false,
            isGenerating = false,
            params = mergedExecutionProfile(snapshot.uiState.params, activeProfile),
            stats = restoredStats,
            autoTuningInProgress = false,
            rollbackParams = null,
            profileId = activeProfile.profileId,
            revision = activeProfile.revision,
            profileRecordState = if (profileState?.activeProfile != null) {
                AgentProfileRecordState.COMMITTED
            } else {
                AgentProfileRecordState.NONE
            },
            verification = profileState?.activeProfile?.verificationLevel
                ?.let(::agentVerification)
                ?: snapshot.uiState.verification,
            engineLifecycle = AgentEngineLifecycle.READY,
            tuningJobState = AgentTuningJobState.IDLE,
            reloadRequired = false,
            pendingProfile = null,
            rollbackProfile = snapshot.uiState.rollbackProfile,
            tuningEtaSeconds = null,
            tuningPhase = null,
            tuningCandidateProgress = AgentCandidateProgress(),
            nativeStatsJson = nativeStats,
            logs = recentLogs,
            statusMessage = statusMessage
        )
    }
    return true
}

internal fun unloadModelFor(
    scope: CoroutineScope,
    store: UiStateWriter<RuntimeProfileFields>,
    admission: RuntimeAdmissionState,
    engine: McaInferenceService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    model: ModelManifest,
    cancelGeneration: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        if (store.current.loadedModelId != model.id) return@launch
        admission.directParameterStageJob?.cancel()
        cancelGeneration()
        val releasedIdentity = admission.activeRuntimeIdentity
        store.update {
            it.copy(
                busy = true,
                engineLifecycle = AgentEngineLifecycle.STOPPING,
                statusMessage = "正在卸载 ${model.displayName}…"
            )
        }
        val nativeRelease = runCatching {
            engine.stopGeneration()
            engine.unloadModel()
        }
        if (nativeRelease.isFailure &&
            !engine.nativeRuntimeReleaseObservedNow()
        ) {
            val error = nativeRelease.exceptionOrNull()
            val message = "卸载失败：${error?.message ?: "未知错误"}"
            val stats = engine.stats.value
            store.update {
                it.afterRuntimeGenerationTerminated(
                    stats = stats,
                    statusMessage = message
                ).copy(busy = false)
            }
            return@launch
        }
        // Native ownership is gone. Clear every active projection before
        // transaction cleanup, which may independently fail.
        clearNativeRuntimeSessionStateFor(
            store, admission, engine,
            lifecycle = AgentEngineLifecycle.UNLOADED,
            statusMessage = "已卸载：${model.displayName}。模型文件和自定义参数已保留。"
        )
        val cleanupError = runCatching {
            clearPendingRuntimeTransactionForLifecycleFor(
                runtimeProfileStore, admission,
                reason = "USER_UNLOAD",
                identity = releasedIdentity
            )
        }.exceptionOrNull()
        if (cleanupError != null || nativeRelease.isFailure) {
            store.update {
                it.copy(
                    statusMessage = buildString {
                        append("已卸载：${model.displayName}。模型文件和自定义参数已保留")
                        nativeRelease.exceptionOrNull()?.let { error ->
                            append("；原生销毁返回异常但已确认 handle 释放：${error.message}")
                        }
                        cleanupError?.let { error ->
                            append("；待应用参数清理失败，将在下次加载时恢复：${error.message}")
                        }
                    }
                )
            }
        }
    }
}

internal fun deleteModelFor(
    scope: CoroutineScope,
    store: UiStateWriter<RuntimeProfileFields>,
    admission: RuntimeAdmissionState,
    engine: McaInferenceService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    modelStore: ModelStoreRepository,
    readinessGate: ManagedRuntimeReadinessRefreshGate,
    application: Application,
    model: ModelManifest,
    cancelGeneration: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        admission.directParameterStageJob?.cancel()
        val wasLoaded = store.current.loadedModelId == model.id
        var nativeReleased = false
        var lifecycleWarning: String? = null
        val releasedIdentity = admission.activeRuntimeIdentity
        store.update { it.copy(busy = true, statusMessage = "正在删除 ${model.displayName}…") }
        runCatching {
            if (wasLoaded) {
                cancelGeneration()
                engine.stopGeneration()
                val release = runCatching { engine.unloadModel() }
                if (release.isFailure &&
                    !engine.nativeRuntimeReleaseObservedNow()
                ) {
                    throw requireNotNull(release.exceptionOrNull())
                }
                nativeReleased = true
                // Native ownership is gone now. Clear the public runtime
                // projection before pending/file cleanup, either of which
                // may fail independently.
                clearNativeRuntimeSessionStateFor(
                    store, admission, engine,
                    lifecycle = AgentEngineLifecycle.UNLOADED,
                    statusMessage = "已卸载 ${model.displayName}，正在删除模型文件…",
                    busy = true
                )
                val cleanupError = runCatching {
                    clearPendingRuntimeTransactionForLifecycleFor(
                        runtimeProfileStore, admission,
                        reason = "USER_DELETE",
                        identity = releasedIdentity
                    )
                }.exceptionOrNull()
                lifecycleWarning = buildList {
                    release.exceptionOrNull()?.let { error ->
                        add("原生销毁返回异常但已确认 handle 释放：${error.message}")
                    }
                    cleanupError?.let { error ->
                        add("待应用参数清理失败，将在下次加载时恢复：${error.message}")
                    }
                }.joinToString("；").takeIf { it.isNotBlank() }
            }
            check(modelStore.deleteModel(model.id)) { "模型文件或记录未能删除" }
        }.onSuccess {
            val remainingModels = modelStore.listModels()
            val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(application, remainingModels)
            readinessGate.invalidate()
            store.update {
                it.copy(
                    models = remainingModels,
                    qairtVerifiedLocalModelIds = qairtVerifiedIds,
                    qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                        models = remainingModels,
                        verifiedLocalModelIds = qairtVerifiedIds,
                        recommendations = it.recommendedRemoteModels
                    ),
                    loadedModelId = it.loadedModelId?.takeUnless { id -> id == model.id },
                    loadedModelName = if (wasLoaded) null else it.loadedModelName,
                    busy = false,
                    isGenerating = if (wasLoaded) false else it.isGenerating,
                    engineLifecycle = if (wasLoaded) AgentEngineLifecycle.UNLOADED else it.engineLifecycle,
                    reloadRequired = if (wasLoaded) false else it.reloadRequired,
                    pendingProfile = if (wasLoaded) null else it.pendingProfile,
                    statusMessage = buildString {
                        append("已删除：${model.displayName}")
                        lifecycleWarning?.let { append("；").append(it) }
                    }
                )
            }
        }.onFailure { error ->
            val currentModels = modelStore.listModels()
            val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(application, currentModels)
            val failureMessage = if (nativeReleased) {
                buildString {
                    append("删除失败，但模型已安全卸载：")
                    append(error.message ?: "请检查文件权限或存储状态")
                    lifecycleWarning?.let { append("；").append(it) }
                }
            } else {
                "删除失败：${error.message ?: "请检查文件权限或存储状态"}"
            }
            val stats = engine.stats.value
            readinessGate.invalidate()
            store.update { state ->
                val releasedGeneration = if (wasLoaded && !nativeReleased) {
                    state.afterRuntimeGenerationTerminated(
                        stats = stats,
                        statusMessage = failureMessage
                    )
                } else {
                    state
                }
                releasedGeneration.copy(
                    models = currentModels,
                    qairtVerifiedLocalModelIds = qairtVerifiedIds,
                    qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                        models = currentModels,
                        verifiedLocalModelIds = qairtVerifiedIds,
                        recommendations = releasedGeneration.recommendedRemoteModels
                    ),
                    busy = false,
                    statusMessage = failureMessage
                )
            }
        }
    }
}
