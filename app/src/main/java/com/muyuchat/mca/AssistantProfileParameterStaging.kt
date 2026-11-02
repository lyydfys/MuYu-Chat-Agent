package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal fun stageDirectParameterProfileFor(
    store: UiStateWriter<AssistantProfileFields>,
    scope: CoroutineScope,
    engine: McaInferenceService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    runtimeUserOverrideFields: Set<String>,
    directParameterStageGeneration: AtomicLong,
    getActiveDirectParameterStageJob: () -> Job?,
    setActiveDirectParameterStageJob: (Job?) -> Unit,
    directParameterStageMutex: Mutex,
    adaptiveTuningJobIsActive: () -> Boolean,
    getActiveTuningJobId: () -> String?,
    getPendingProfileTransactionId: () -> String?,
    setPendingProfileTransactionId: (String?) -> Unit,
    params: GenerationParams
) {
    val identity = activeRuntimeIdentity() ?: return
    val active = engine.activeExecutionProfile() ?: return
    if (store.state.value.loadedModelId == null || active.runtimeIdentity.identityHash != identity.identityHash) return
    val stageGeneration = directParameterStageGeneration.incrementAndGet()
    getActiveDirectParameterStageJob()?.cancel()
    val job = scope.launch(Dispatchers.IO) {
        delay(120L)
        directParameterStageMutex.withLock {
            withContext(NonCancellable) stage@{
                if (stageGeneration != directParameterStageGeneration.get()) return@stage
                if (adaptiveTuningJobIsActive() || getActiveTuningJobId() != null) {
                    store.update {
                        it.copy(statusMessage = "当前调优任务尚未结束；完成或取消后再应用自定义运行参数。")
                    }
                    return@stage
                }
                val latestParams = store.state.value.params
                if (latestParams != params) return@stage
                runCatching {
                    val nextRevision = nextExecutionProfileRevision(
                        committedRevision = active.revision,
                        persistedRevisions = runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
                    )
                    val requested = engine.resolveExecutionProfile(
                        identity = identity,
                        requestedParamsJson = latestParams.toJson(),
                        profileId = "manual-${UUID.randomUUID().toString().replace("-", "").take(20)}",
                        revision = nextRevision
                    ).profile
                    val candidate = mergeUserRequestedIntoProfile(
                        base = active,
                        requested = requested,
                        authoritativeFields = runtimeUserOverrideFields
                    )
                    if (candidate.resolvedLoadSignature.digest == active.resolvedLoadSignature.digest &&
                        candidate.committedExecutionSignature.digest == active.committedExecutionSignature.digest
                    ) {
                        setPendingProfileTransactionId(null)
                        store.update {
                            it.copy(
                                reloadRequired = false,
                                pendingProfile = null,
                                statusMessage = "自定义参数与当前生效配置一致。"
                            )
                        }
                        return@runCatching
                    }

                    runtimeProfileStore.pendingTransaction(identity.identityHash)?.let { existing ->
                        if (existing.journal.transactionId != getPendingProfileTransactionId()) {
                            error("已有调优候选等待处理，请先应用、回滚或取消该候选。")
                        }
                        val recovery = runtimeProfileStore.rejectCandidate(
                            transactionId = existing.journal.transactionId,
                            failureStage = "USER_EDIT_REPLACED",
                            failureCode = "SUPERSEDED",
                            failureSummary = "用户继续修改运行参数，旧候选已被新候选替换。"
                        )
                        runtimeProfileStore.completeRecovery(
                            transactionId = existing.journal.transactionId,
                            restoredProfileId = recovery.rollbackProfileId
                        )
                    }

                    val transactionId = "manual-${UUID.randomUUID()}"
                    runtimeProfileStore.stageCandidate(
                        snapshot = candidate.toPersistedExecutionProfileSnapshot(
                            parentCommittedProfileId = active.profileId,
                            verificationLevel = PersistedProfileVerificationLevel.SAFE,
                            sourceSummaryJson = JSONObject()
                                .put("kind", "user_parameter_edit")
                                .put("authoritativeFields", JSONArray(runtimeUserOverrideFields.sorted()))
                                .toString()
                        ),
                        transactionId = transactionId,
                        rollbackTargetProfileId = active.profileId
                    )
                    if (stageGeneration != directParameterStageGeneration.get() || store.state.value.params != params) {
                        val recovery = runtimeProfileStore.rejectCandidate(
                            transactionId = transactionId,
                            failureStage = "USER_EDIT_SUPERSEDED",
                            failureCode = "SUPERSEDED",
                            failureSummary = "候选建立期间用户再次修改参数，失效候选已撤销。"
                        )
                        runtimeProfileStore.completeRecovery(transactionId, recovery.rollbackProfileId)
                        return@runCatching
                    }
                    setPendingProfileTransactionId(transactionId)
                    val currentCtx = active.resolvedLoadBoundValues.toJsonObject().optInt("n_ctx", store.state.value.stats.nCtx)
                    val pendingCtx = candidate.resolvedLoadBoundValues.toJsonObject().optInt("n_ctx", latestParams.nCtx)
                    store.update {
                        it.copy(
                            reloadRequired = true,
                            pendingProfile = AgentPendingProfile(
                                profileId = candidate.profileId,
                                revision = candidate.revision,
                                summary = "当前 n_ctx=$currentCtx · 待应用 n_ctx=$pendingCtx",
                                readyToApply = true
                            ),
                            tuningJobState = AgentTuningJobState.PAUSED,
                            tuningPhase = "自定义参数等待应用",
                            statusMessage = "上下文已修改为 $pendingCtx，需要重新加载后生效；当前聊天仍使用 $currentCtx。"
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    store.update {
                        it.copy(
                            pendingProfile = null,
                            reloadRequired = true,
                            statusMessage = "自定义参数候选建立失败：${error.message ?: "未知错误"}"
                        )
                    }
                }
            }
        }
    }
    setActiveDirectParameterStageJob(job)
}
