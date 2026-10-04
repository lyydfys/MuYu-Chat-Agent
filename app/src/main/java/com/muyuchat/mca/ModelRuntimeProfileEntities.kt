package com.muyuchat.mca

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muyuchat.core.engine.ModelExecutionProfile

internal enum class PersistedProfileRecordState { STAGED, COMMITTED, REJECTED }
internal enum class PersistedProfileVerificationLevel { SAFE, COMPATIBLE, DEVICE_VERIFIED }
internal enum class PersistedTuningJobState {
    QUEUED, RUNNING, PAUSED, CANCELING, VALIDATING, SUCCEEDED, FAILED, RECOVERING
}
internal enum class TuningJournalState {
    STAGED, APPLYING, VALIDATING, COMMITTED, REJECTED, RECOVERING, ROLLED_BACK, FAILED
}

@Entity(tableName = "runtime_installation_scope")
internal data class RuntimeInstallationScopeEntity(
    @PrimaryKey val scopeKey: String = INSTALLATION_SCOPE_KEY,
    val installationScopeId: String,
    val createdAt: Long
) {
    companion object {
        const val INSTALLATION_SCOPE_KEY = "device_installation"
    }
}

@Entity(
    tableName = "runtime_identity",
    indices = [Index("modelId"), Index("runtime"), Index("updatedAt")]
)
internal data class RuntimeIdentityEntity(
    @PrimaryKey val identityKey: String,
    val modelId: String,
    val runtime: String,
    val artifactFingerprint: String,
    val deviceCapabilityFingerprint: String,
    val installationScopeId: String,
    val runtimeFingerprint: String,
    val fieldPolicyFingerprint: String,
    val ruleFingerprint: String,
    val evaluatorFingerprint: String,
    val schemaVersion: Int,
    /** Canonical, complete identity snapshot used to reject partial/stale reconstruction. */
    val identityJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Allocates a revision above both the currently committed profile and every retained history row.
 *
 * Rejected/superseded candidates remain in Room for crash and rollback audit, so using only
 * `active.revision + 1` can reuse an already occupied `(identityKey, revision)` pair.  Keep the
 * allocation rule pure so every UI/load path can share it and regression tests do not need a
 * device database.
 */
internal fun nextExecutionProfileRevision(
    committedRevision: Long,
    persistedRevisions: Iterable<Long>
): Long {
    require(committedRevision >= 0L) { "Committed profile revision must be non-negative." }
    val latest = persistedRevisions.fold(committedRevision) { current, revision ->
        require(revision >= 0L) { "Persisted profile revision must be non-negative." }
        maxOf(current, revision)
    }
    check(latest < Long.MAX_VALUE) { "Execution profile revision space is exhausted." }
    return latest + 1L
}

@Entity(
    tableName = "execution_profile",
    indices = [
        Index("identityKey"),
        Index(value = ["identityKey", "revision"], unique = true),
        Index("recordState"),
        Index("updatedAt")
    ]
)
internal data class ExecutionProfileEntity(
    @PrimaryKey val profileId: String,
    val identityKey: String,
    val revision: Long,
    val parentCommittedProfileId: String?,
    val recordState: String,
    val verificationLevel: String,
    val desiredProfileSignature: String,
    val resolvedLoadSignature: String,
    val activeLoadedSignature: String,
    val committedExecutionSignature: String,
    val runtimeOverrideSignature: String,
    val effectiveExecutionSignature: String,
    val resolvedLoadJson: String,
    val committedExecutionJson: String,
    val quarantinedOverridesJson: String,
    val sourceSummaryJson: String,
    /** Canonical execution profile snapshot. Resource paths are binding references, never raw paths. */
    val profileJson: String,
    val failureStage: String?,
    val failureCode: String?,
    val failureSummary: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Installation-local indirection for SAF URIs and native file paths.
 *
 * This table lives in the same database that is excluded from cloud backup/device transfer. Exported
 * profile evidence contains only [bindingId] and [resourceFingerprint], never [resourceValue].
 */
@Entity(
    tableName = "runtime_resource_binding",
    indices = [
        Index("identityKey"),
        Index("profileId"),
        Index(value = ["profileId", "field"], unique = true)
    ]
)
internal data class RuntimeResourceBindingEntity(
    @PrimaryKey val bindingId: String,
    val identityKey: String,
    val profileId: String,
    val field: String,
    val installationScopeId: String,
    val resourceFingerprint: String,
    val resourceValue: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "profile_pointers")
internal data class ProfilePointersEntity(
    @PrimaryKey val identityKey: String,
    val activeProfileId: String?,
    val pendingProfileId: String?,
    val lastKnownGoodProfileId: String?,
    val rollbackTargetProfileId: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "tuning_job",
    indices = [Index("identityKey"), Index("state"), Index("updatedAt")]
)
internal data class TuningJobEntity(
    @PrimaryKey val jobId: String,
    val identityKey: String,
    val state: String,
    val candidateProfileId: String?,
    val phase: String,
    val autoApplyLoadChanges: Boolean,
    val cancellationRequested: Boolean,
    val failureCode: String?,
    val failureSummary: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastHeartbeatAt: Long
)

@Entity(
    tableName = "tuning_journal",
    indices = [Index("identityKey"), Index("jobId"), Index("state"), Index("updatedAt")]
)
internal data class TuningJournalEntity(
    @PrimaryKey val transactionId: String,
    val identityKey: String,
    val jobId: String?,
    val pendingProfileId: String,
    val rollbackTargetProfileId: String?,
    val resolvedLoadSignature: String,
    val state: String,
    val stage: String,
    val recoveryAttempts: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "candidate_measurement",
    indices = [Index("profileId"), Index("jobId"), Index("createdAt")]
)
internal data class CandidateMeasurementEntity(
    @PrimaryKey val measurementId: String,
    val profileId: String,
    val jobId: String?,
    val correctnessPassed: Boolean,
    val safetyPassed: Boolean,
    val effectiveSignatureMatched: Boolean,
    val accepted: Boolean,
    val metricsJson: String,
    val failureCode: String?,
    val createdAt: Long
)

internal data class RuntimeRecoveryPlan(
    val transactionId: String,
    val identityKey: String,
    val rejectedProfileId: String,
    val rollbackProfileId: String?,
    val recoveryAttempt: Int
)

internal data class PersistedExecutionProfileSnapshot(
    val profile: ExecutionProfileEntity,
    val resourceBindings: List<RuntimeResourceBindingEntity>
)

internal data class RecoveryAttemptDecision(
    val canRecover: Boolean,
    val nextAttempts: Int,
    val terminalStage: String
)

/** Transaction-consistent read model used by MainViewModel and the Local API control plane. */
internal data class CurrentRuntimeProfileState(
    val identity: RuntimeIdentityEntity,
    val pointers: ProfilePointersEntity?,
    val activeProfile: ExecutionProfileEntity?,
    val activeExecutionProfile: ModelExecutionProfile?,
    val activeJob: TuningJobEntity?
)

/** Persisted, per-model summary used by the redacted Local API model catalog. */
internal data class PersistedModelRuntimeSummary(
    val modelId: String,
    val identityKey: String,
    val pointers: ProfilePointersEntity?,
    val activeProfile: ExecutionProfileEntity?,
    val pendingProfile: ExecutionProfileEntity?,
    val activeJob: TuningJobEntity?
)

/** The only unfinished transaction authorized to operate on an identity's pending profile. */
internal data class PendingRuntimeProfileTransaction(
    val pointers: ProfilePointersEntity,
    val pendingProfile: ExecutionProfileEntity,
    val pendingExecutionProfile: ModelExecutionProfile,
    val journal: TuningJournalEntity
)

