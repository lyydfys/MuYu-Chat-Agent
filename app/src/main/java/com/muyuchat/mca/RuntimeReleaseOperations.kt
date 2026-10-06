package com.muyuchat.mca

import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.presentation.afterNativeRuntimeReleased
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * R3/C3-S19：运行时释放三方法自 MainViewModel 迁出（体逐字；字段依赖已全部 holder 化）。
 *
 * `clearNativeRuntimeSessionStateFor` 与 `clearPendingRuntimeTransactionForLifecycleFor` 的调用点较多
 * （生命周期族），VM 保留逐字签名薄委托；`prepareLocalImageRuntimeMemoryFor` 供 ImageGenerationHost 链。
 */
internal fun clearNativeRuntimeSessionStateFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    engine: McaInferenceService,
    lifecycle: AgentEngineLifecycle,
    statusMessage: String,
    busy: Boolean = false
) {
    admission.directParameterStageGeneration.incrementAndGet()
    admission.directParameterStageJob?.cancel()
    admission.activeRuntimeIdentity = null
    admission.activeVisionProjectorStamp = null
    admission.activeModelForRuntimeProfile = null
    admission.activeAdaptiveRecommendation = null
    admission.pendingAdaptiveRecommendation = null
    admission.pendingProfileTransactionId = null
    admission.activeProfileTransactionId = null
    val nativeStats = engine.nativeStatsJsonOrEmpty()
    val recentLogs = engine.recentEngineLogsOrEmpty()
    store.update { state ->
        state.afterNativeRuntimeReleased(
            lifecycle = lifecycle,
            statusMessage = statusMessage,
            stats = engine.stats.value,
            busy = busy
        ).copy(
            nativeStatsJson = nativeStats,
            logs = recentLogs
        )
    }
}

internal suspend fun clearPendingRuntimeTransactionForLifecycleFor(
    runtimeProfileStore: ModelRuntimeProfileStore,
    admission: RuntimeAdmissionState,
    reason: String,
    identity: ModelRuntimeIdentity?
) {
    identity ?: return
    val pending = runtimeProfileStore.pendingTransaction(identity.identityHash) ?: return
    val recovery = runtimeProfileStore.rejectCandidate(
        transactionId = pending.journal.transactionId,
        failureStage = reason,
        failureCode = "LIFECYCLE_CLOSED",
        failureSummary = "模型生命周期结束，未应用候选已安全撤销。"
    )
    runtimeProfileStore.completeRecovery(
        transactionId = pending.journal.transactionId,
        restoredProfileId = recovery.rollbackProfileId
    )
    if (admission.pendingProfileTransactionId == pending.journal.transactionId) {
        admission.pendingProfileTransactionId = null
    }
}

internal suspend fun prepareLocalImageRuntimeMemoryFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    runtimeProfileStore: ModelRuntimeProfileStore,
    engine: McaInferenceService
): EngineLifecycleLease {
    // Wait for a genuine in-flight chat/load to finish; never stop another request here.
    val lease = engine.acquireExclusiveLifecycleLease(stopActiveGeneration = false)
    try {
        val hadLoadedModel = engine.stats.value.loaded || store.current.loadedModelId != null
        val releasedIdentity = admission.activeRuntimeIdentity
        engine.unloadModel(lease = lease)
        if (hadLoadedModel) {
            clearNativeRuntimeSessionStateFor(
                store = store,
                admission = admission,
                engine = engine,
                lifecycle = AgentEngineLifecycle.UNLOADED,
                statusMessage = "已释放聊天模型内存，正在准备本地生图；聊天模型可在生图结束后重新加载。"
            )
            clearPendingRuntimeTransactionForLifecycleFor(
                runtimeProfileStore = runtimeProfileStore,
                admission = admission,
                reason = "LOCAL_IMAGE_MEMORY_RECLAIM",
                identity = releasedIdentity
            )
        }
        return lease
    } catch (error: Throwable) {
        withContext(NonCancellable) { lease.release() }
        throw error
    }
}
