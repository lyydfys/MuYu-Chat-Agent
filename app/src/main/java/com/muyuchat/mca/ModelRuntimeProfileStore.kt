package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.ModelExecutionProfile
import java.util.UUID

/**
 * Transactional persistence for model/runtime/device-specific execution state.
 * It never stores prompts, API keys, absolute model paths, or hardware IDs.
 */
internal class ModelRuntimeProfileStore(context: Context) {
    internal val database = ModelRuntimeProfileDatabase.get(context)
    internal val dao = database.dao()

    suspend fun installationScopeId(now: Long = System.currentTimeMillis()): String =
        installationScopeIdImpl(now)

    suspend fun upsertIdentity(identity: RuntimeIdentityEntity) {
        upsertIdentityImpl(identity)
    }

    suspend fun currentRuntimeState(identityKey: String): CurrentRuntimeProfileState? =
        currentRuntimeStateImpl(identityKey)

    /**
     * Returns one installation-local runtime summary for each requested model.
     *
     * The query deliberately never returns profile JSON, resource bindings, absolute paths, or
     * device identifiers. Callers may expose only the small state vocabulary represented here.
     */
    suspend fun modelRuntimeSummaries(modelIds: Collection<String>): List<PersistedModelRuntimeSummary> =
        modelRuntimeSummariesImpl(modelIds)

    suspend fun activeTuningJob(identityKey: String): TuningJobEntity? = activeTuningJobImpl(identityKey)

    suspend fun tuningJob(jobId: String): TuningJobEntity? = tuningJobImpl(jobId)

    suspend fun pendingTransaction(identityKey: String): PendingRuntimeProfileTransaction? =
        pendingTransactionImpl(identityKey)

    suspend fun createTuningJob(
        identityKey: String,
        autoApplyLoadChanges: Boolean,
        jobId: String = UUID.randomUUID().toString(),
        phase: String = "QUEUED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = createTuningJobImpl(identityKey, autoApplyLoadChanges, jobId, phase, now)

    suspend fun transitionTuningJob(
        jobId: String,
        state: PersistedTuningJobState,
        phase: String,
        failureCode: String? = null,
        failureSummary: String? = null,
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = transitionTuningJobImpl(jobId, state, phase, failureCode, failureSummary, now)

    suspend fun heartbeatTuningJob(
        jobId: String,
        phase: String? = null,
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = heartbeatTuningJobImpl(jobId, phase, now)

    suspend fun pauseTuningJob(
        jobId: String,
        phase: String = "PAUSED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = pauseTuningJobImpl(jobId, phase, now)

    suspend fun resumeTuningJob(
        jobId: String,
        phase: String = "RUNNING",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = resumeTuningJobImpl(jobId, phase, now)

    suspend fun requestTuningJobCancellation(
        jobId: String,
        phase: String = "CANCEL_REQUESTED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = requestTuningJobCancellationImpl(jobId, phase, now)

    suspend fun stageCandidate(
        profile: ExecutionProfileEntity,
        transactionId: String,
        rollbackTargetProfileId: String?,
        job: TuningJobEntity? = null,
        resourceBindings: List<RuntimeResourceBindingEntity> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = stageCandidateImpl(profile, transactionId, rollbackTargetProfileId, job, resourceBindings, now)

    suspend fun stageCandidate(
        snapshot: PersistedExecutionProfileSnapshot,
        transactionId: String,
        rollbackTargetProfileId: String?,
        job: TuningJobEntity? = null,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = stageCandidateImpl2(snapshot, transactionId, rollbackTargetProfileId, job, now)

    suspend fun updateJournalStage(
        transactionId: String,
        state: TuningJournalState,
        stage: String,
        now: Long = System.currentTimeMillis()
    ) = updateJournalStageImpl(transactionId, state, stage, now)

    suspend fun commitCandidate(
        transactionId: String,
        verificationLevel: PersistedProfileVerificationLevel,
        activeLoadedSignature: String,
        effectiveExecutionSignature: String,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = commitCandidateImpl(transactionId, verificationLevel, activeLoadedSignature, effectiveExecutionSignature, now)

    /**
     * Closes a disposable probe journal without promoting its temporary profile.
     *
     * Probe profiles deliberately remain terminal audit records. The active/LKG pointers never
     * move: the main process restores the exact committed profile before calling this method.
     */
    suspend fun completeIsolatedProbe(
        transactionId: String,
        passed: Boolean,
        summary: String,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = completeIsolatedProbeImpl(transactionId, passed, summary, now)

    suspend fun rejectCandidate(
        transactionId: String,
        failureStage: String,
        failureCode: String,
        failureSummary: String,
        now: Long = System.currentTimeMillis()
    ): RuntimeRecoveryPlan = rejectCandidateImpl(transactionId, failureStage, failureCode, failureSummary, now)

    suspend fun recoverInterruptedTransactions(
        now: Long = System.currentTimeMillis()
    ): List<RuntimeRecoveryPlan> = recoverInterruptedTransactionsImpl(now)

    suspend fun completeRecovery(
        transactionId: String,
        restoredProfileId: String?,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = completeRecoveryImpl(transactionId, restoredProfileId, now)

    suspend fun recordMeasurement(measurement: CandidateMeasurementEntity, keepCount: Int = 256) {
        recordMeasurementImpl(measurement, keepCount)
    }

    suspend fun pruneRejectedProfiles(
        olderThan: Long = System.currentTimeMillis() - REJECTED_PROFILE_RETENTION_MS
    ) {
        pruneRejectedProfilesImpl(olderThan)
    }

    suspend fun pruneTerminalHistory(
        olderThan: Long = System.currentTimeMillis() - TERMINAL_HISTORY_RETENTION_MS,
        keepCount: Int = 512
    ) {
        pruneTerminalHistoryImpl(olderThan, keepCount)
    }

    suspend fun pointers(identityKey: String): ProfilePointersEntity? = pointersImpl(identityKey)

    suspend fun profile(profileId: String): ExecutionProfileEntity? = profileImpl(profileId)

    suspend fun profiles(identityKey: String): List<ExecutionProfileEntity> = profilesImpl(identityKey)

    suspend fun reconstructedProfile(profileId: String): ModelExecutionProfile? = reconstructedProfileImpl(profileId)

}

private const val REJECTED_PROFILE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L

private const val TERMINAL_HISTORY_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
