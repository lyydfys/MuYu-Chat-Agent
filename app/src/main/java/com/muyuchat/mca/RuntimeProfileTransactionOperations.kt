package com.muyuchat.mca

import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PendingProfileDisposition
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import com.muyuchat.core.tuning.ProfileVerificationLevel
import java.util.UUID


/**
 * What applying or rolling back a runtime profile transaction needs from its owner.
 *
 * Eleven members. Five are the machinery the transaction drives -- the persisted journal, the engine
 * session it takes an exclusive lifecycle lease from, the canary runner, the sequence scorer and the
 * measurement builder -- and one is the state it publishes while doing so. The rest are the owner's
 * own helpers: the merge that folds a committed profile into the live parameters, the exact-profile
 * restore used by the failure path, the status sink, and the two properties the transaction flips.
 *
 * `activeModelForRuntimeProfile` and `pendingProfileTransactionId` are read AND written, so they stay
 * ports rather than being passed by value: the original cleared the pending id in place, and a
 * snapshot would leave the ViewModel holding a stale one after the transaction settled.
 */
internal interface RuntimeProfileTransactionPort {

    /** The live UI state. Published atomically through [updateUiState]. */
    fun uiState(): MainUiState

    /** The single atomic publish, delegating to `MutableStateFlow.update` so the CAS is kept. */
    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** The persisted runtime profile journal. */
    val runtimeProfileStore: ModelRuntimeProfileStore

    /** The engine session the transaction takes an exclusive lifecycle lease from. */
    val engine: McaInferenceService

    /** The model a failed apply or rollback restores back to. */
    val activeModelForRuntimeProfile: ModelManifest?

    /**
     * The pending transaction id, cleared once the transaction settles. A property because the
     * original cleared it in place and other ViewModel paths read it.
     */
    var pendingProfileTransactionId: String?

    /** Builds a durable measurement record out of a canary verdict. */
    fun candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity

    /** Scores a canary verdict and reports whether the profile is eligible to commit. */
    fun tuningCandidateScore(result: CandidateCanaryResult): CandidateScore

    /** Folds a committed execution profile into the live generation parameters. */
    fun mergeExecutionProfile(
        generation: GenerationParams,
        profile: ModelExecutionProfile
    ): GenerationParams

    /** Restores an exact persisted profile onto the running engine; false when it does not fit. */
    suspend fun restoreExactRuntimeProfile(
        model: ModelManifest,
        profile: ModelExecutionProfile
    ): Boolean

    /** The user-visible status sink. */
    fun showStatus(message: String)

    /**
     * Wraps a persisted execution profile as a tuning candidate. A private extension of the
     * ViewModel, so it stays on the owner side.
     */
    fun asTuningExecutionProfile(
        profile: ModelExecutionProfile,
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile

    /** Runs the canary evaluation for a staged profile. */
    suspend fun runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: CanaryEvaluationParams,
        authorization: LoadAuthorization,
        disposition: PendingProfileDisposition,
        lifecycleLease: EngineLifecycleLease?
    ): CandidateCanaryResult
}

/**
 * Applies a pending runtime profile transaction: stages it on the engine, canaries it under an
 * exclusive lifecycle lease, commits it, and republishes the live parameters. On any failure it
 * rejects the candidate, restores the exact previous profile and reports the failure -- the same
 * ordering, messages and lease handling as before the move.
 *
 * Moved verbatim out of `MainViewModel` (110 lines). Only the owner interactions became [port] calls.
 */
internal suspend fun applyPendingRuntimeTransaction(
    port: RuntimeProfileTransactionPort,
    pending: PendingRuntimeProfileTransaction
) {
        val model = port.activeModelForRuntimeProfile
            ?: error("当前没有可用于 pending profile 的已加载模型")
        var lease: com.muyuchat.core.engine.EngineLifecycleLease? = null
        val transactionId = pending.journal.transactionId
        try {
            port.updateUiState {
                it.copy(
                    busy = true,
                    engineLifecycle = AgentEngineLifecycle.RELOADING,
                    tuningJobState = AgentTuningJobState.VALIDATING,
                    tuningPhase = "应用 pending profile",
                    statusMessage = "正在重新加载并验证 pending profile…"
                )
            }
            port.runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.VALIDATING, "USER_APPLY")
            pending.journal.jobId?.let { jobId ->
                val persistedJob = port.runtimeProfileStore.tuningJob(jobId)
                    ?: error("pending profile 对应的调优任务不存在")
                if (persistedJob.state == PersistedTuningJobState.PAUSED.name) {
                    port.runtimeProfileStore.resumeTuningJob(jobId, "USER_APPLY_RESUMED")
                }
                port.runtimeProfileStore.transitionTuningJob(
                    jobId,
                    PersistedTuningJobState.VALIDATING,
                    "USER_APPLY"
                )
            }
            val candidate = port.asTuningExecutionProfile(
                pending.pendingExecutionProfile,
                kind = ExecutionProfileKind.BALANCED,
                verificationLevel = ProfileVerificationLevel.UNVERIFIED,
                reason = "用户应用已验证的 pending profile"
            )
            lease = port.engine.acquireExclusiveLifecycleLease()
            val authorization = port.engine.stagePendingExecutionProfile(
                transactionId = transactionId,
                profile = pending.pendingExecutionProfile,
                rollbackTargetProfileId = pending.journal.rollbackTargetProfileId
            )
            val result = port.runCandidateCanary(
                candidate = candidate,
                canaryParams = com.muyuchat.core.tuning.CanaryEvaluationParams(),
                authorization = authorization,
                disposition = com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                lifecycleLease = lease
            )
            port.runtimeProfileStore.recordMeasurement(
                port.candidateMeasurement(
                    profileId = pending.pendingExecutionProfile.profileId,
                    jobId = pending.journal.jobId,
                    phase = "USER_APPLY_CANARY",
                    result = result
                )
            )
            val applyScore = port.tuningCandidateScore(result)
            if (!applyScore.eligible) error(applyScore.reason ?: result.detail)
            port.engine.commitDeferredPendingExecutionProfile(authorization, lease ?: error("pending apply lease 已释放"))
            val signatures = port.engine.parameterSignatureSnapshot() ?: error("pending profile 缺少签名快照")
            port.runtimeProfileStore.commitCandidate(
                transactionId = transactionId,
                verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                activeLoadedSignature = signatures.active?.digest ?: signatures.resolved.digest,
                effectiveExecutionSignature = signatures.effective?.digest ?: signatures.committed.digest
            )
            port.pendingProfileTransactionId = null
            port.updateUiState { state ->
                state.copy(
                    params = port.mergeExecutionProfile(state.params, pending.pendingExecutionProfile),
                    profileId = pending.pendingExecutionProfile.profileId,
                    revision = pending.pendingExecutionProfile.revision,
                    profileRecordState = AgentProfileRecordState.COMMITTED,
                    verification = AgentProfileVerification.COMPATIBLE,
                    pendingProfile = null,
                    reloadRequired = false,
                    engineLifecycle = AgentEngineLifecycle.READY,
                    tuningJobState = AgentTuningJobState.SUCCEEDED,
                    tuningPhase = "pending 已提交",
                    busy = false,
                    statusMessage = "pending profile 已通过正确性与签名核对并提交。"
                )
            }
        } catch (error: Throwable) {
            lease?.release()
            lease = null
            val recovery = runCatching {
                port.runtimeProfileStore.rejectCandidate(
                    transactionId = transactionId,
                    failureStage = "USER_APPLY",
                    failureCode = "PENDING_APPLY_FAILED",
                    failureSummary = error.message ?: "pending apply failed"
                )
            }.getOrNull()
            val restoredId = recovery?.rollbackProfileId?.let { rollbackId ->
                val rollback = port.runtimeProfileStore.reconstructedProfile(rollbackId)
                if (rollback != null && port.restoreExactRuntimeProfile(model, rollback)) rollbackId else null
            }
            if (recovery != null) runCatching { port.runtimeProfileStore.completeRecovery(transactionId, restoredId) }
            if (port.pendingProfileTransactionId == transactionId) port.pendingProfileTransactionId = null
            port.updateUiState {
                it.copy(
                    busy = false,
                    engineLifecycle = if (restoredId != null) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR,
                    tuningJobState = AgentTuningJobState.FAILED,
                    tuningPhase = "pending 应用失败",
                    pendingProfile = null,
                    statusMessage = "pending profile 应用失败：${error.message ?: "未知错误"}"
                )
            }
        } finally {
            runCatching { lease?.release() }
        }
}

/**
 * Rolls the committed runtime profile back to its last known good parent: builds a fresh rollback
 * candidate, stages and canaries it under an exclusive lease, commits it, and on failure restores the
 * exact previous profile. Same ordering, wording and lease handling as before the move.
 *
 * Moved verbatim out of `MainViewModel` (122 lines). Only the owner interactions became [port] calls.
 */
internal suspend fun rollbackCommittedRuntimeProfile(
    port: RuntimeProfileTransactionPort,
    identity: ModelRuntimeIdentity,
    model: ModelManifest
) {
        val current = port.runtimeProfileStore.currentRuntimeState(identity.identityHash)
            ?: error("当前运行配置未持久化")
        val activeEntity = current.activeProfile ?: error("当前没有 active profile")
        val parentId = activeEntity.parentCommittedProfileId
            ?: current.pointers?.lastKnownGoodProfileId?.takeIf { it != activeEntity.profileId }
        if (parentId == null) {
            port.showStatus("当前 profile 没有更早的稳定版本可回退。")
            return
        }
        val parentEntity = port.runtimeProfileStore.profile(parentId) ?: error("回退目标不存在")
        val target = port.runtimeProfileStore.reconstructedProfile(parentId) ?: error("回退目标无法重建")
        val rollbackCandidate = target.copy(
            profileId = "rollback-${UUID.randomUUID().toString().replace("-", "").take(20)}",
            revision = nextExecutionProfileRevision(
                committedRevision = activeEntity.revision,
                persistedRevisions = port.runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
            ),
            resolvedAt = System.currentTimeMillis()
        )
        val transactionId = "rollback-${UUID.randomUUID()}"
        var lease: com.muyuchat.core.engine.EngineLifecycleLease? = null
        try {
            port.updateUiState {
                it.copy(
                    busy = true,
                    engineLifecycle = AgentEngineLifecycle.ROLLING_BACK,
                    tuningJobState = AgentTuningJobState.RECOVERING,
                    tuningPhase = "回退到上一稳定 profile",
                    statusMessage = "正在回退并重新验证…"
                )
            }
            port.runtimeProfileStore.stageCandidate(
                snapshot = rollbackCandidate.toPersistedExecutionProfileSnapshot(
                    parentCommittedProfileId = parentEntity.parentCommittedProfileId,
                    verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                    sourceSummaryJson = JSONObject().put("source", "user_rollback").put("target", parentId).toString()
                ),
                transactionId = transactionId,
                rollbackTargetProfileId = activeEntity.profileId
            )
            port.runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.APPLYING, "ROLLBACK_APPLYING")
            lease = port.engine.acquireExclusiveLifecycleLease()
            val authorization = port.engine.stagePendingExecutionProfile(
                transactionId = transactionId,
                profile = rollbackCandidate,
                rollbackTargetProfileId = activeEntity.profileId
            )
            port.runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.VALIDATING, "ROLLBACK_VALIDATING")
            val wrapper = port.asTuningExecutionProfile(
                rollbackCandidate,
                ExecutionProfileKind.BALANCED,
                ProfileVerificationLevel.UNVERIFIED,
                "用户回退"
            )
            val result = port.runCandidateCanary(
                wrapper,
                com.muyuchat.core.tuning.CanaryEvaluationParams(),
                authorization,
                com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                lease
            )
            port.runtimeProfileStore.recordMeasurement(
                port.candidateMeasurement(
                    profileId = rollbackCandidate.profileId,
                    jobId = null,
                    phase = "ROLLBACK_CANARY",
                    result = result
                )
            )
            val rollbackScore = port.tuningCandidateScore(result)
            if (!rollbackScore.eligible) error(rollbackScore.reason ?: result.detail)
            port.engine.commitDeferredPendingExecutionProfile(authorization, lease ?: error("rollback lease 已释放"))
            val signatures = port.engine.parameterSignatureSnapshot() ?: error("回退缺少签名快照")
            port.runtimeProfileStore.commitCandidate(
                transactionId,
                PersistedProfileVerificationLevel.COMPATIBLE,
                signatures.active?.digest ?: signatures.resolved.digest,
                signatures.effective?.digest ?: signatures.committed.digest
            )
            port.updateUiState { state ->
                state.copy(
                    params = port.mergeExecutionProfile(state.params, rollbackCandidate),
                    profileId = rollbackCandidate.profileId,
                    revision = rollbackCandidate.revision,
                    profileRecordState = AgentProfileRecordState.COMMITTED,
                    verification = AgentProfileVerification.COMPATIBLE,
                    engineLifecycle = AgentEngineLifecycle.READY,
                    tuningJobState = AgentTuningJobState.SUCCEEDED,
                    tuningPhase = "回退完成",
                    busy = false,
                    rollbackProfile = parentEntity.parentCommittedProfileId?.let {
                        AgentRollbackProfile(it, null, "可继续回退", true)
                    },
                    statusMessage = "已回退到上一稳定配置并完成正确性验证。"
                )
            }
        } catch (error: Throwable) {
            lease?.release()
            lease = null
            val recovery = runCatching {
                port.runtimeProfileStore.rejectCandidate(
                    transactionId,
                    "ROLLBACK",
                    "ROLLBACK_FAILED",
                    error.message ?: "rollback failed"
                )
            }.getOrNull()
            val restoredId = recovery?.rollbackProfileId?.let { rollbackId ->
                val previous = port.runtimeProfileStore.reconstructedProfile(rollbackId)
                if (previous != null && port.restoreExactRuntimeProfile(model, previous)) rollbackId else null
            }
            if (recovery != null) runCatching { port.runtimeProfileStore.completeRecovery(transactionId, restoredId) }
            port.updateUiState {
                it.copy(
                    busy = false,
                    engineLifecycle = if (restoredId != null) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR,
                    tuningJobState = AgentTuningJobState.FAILED,
                    statusMessage = "回退失败：${error.message ?: "未知错误"}"
                )
            }
        } finally {
            runCatching { lease?.release() }
        }
}
