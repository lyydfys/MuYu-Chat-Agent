package com.muyuchat.mca

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.QuarantinedOverride
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transactional persistence for model/runtime/device-specific execution state.
 * It never stores prompts, API keys, absolute model paths, or hardware IDs.
 */
internal class ModelRuntimeProfileStore(context: Context) {
    private val database = ModelRuntimeProfileDatabase.get(context)
    private val dao = database.dao()

    suspend fun installationScopeId(now: Long = System.currentTimeMillis()): String =
        database.withTransaction {
            installationScopeIdLocked(now)
        }

    suspend fun upsertIdentity(identity: RuntimeIdentityEntity) {
        require(identity.identityKey.isNotBlank() && identity.modelId.isNotBlank())
        require(identity.artifactFingerprint.isNotBlank())
        require(identity.deviceCapabilityFingerprint.isNotBlank())
        require(identity.installationScopeId.isNotBlank())
        val reconstructed = identity.toModelRuntimeIdentity()
        require(reconstructed.identityHash == identity.identityKey)
        database.withTransaction {
            val localScope = installationScopeIdLocked(identity.updatedAt)
            require(identity.installationScopeId == localScope) {
                "Runtime identity belongs to another app installation."
            }
            val existing = dao.identity(identity.identityKey)
            if (existing == null) {
                dao.insertIdentity(identity)
            } else {
                val updated = identity.copy(createdAt = existing.createdAt)
                require(existing.copy(updatedAt = updated.updatedAt) == updated) {
                    "Runtime identity is immutable; use a new identityKey after any fingerprint change."
                }
                check(dao.updateIdentity(updated) == 1) { "Runtime identity update was lost." }
            }
        }
    }

    suspend fun currentRuntimeState(identityKey: String): CurrentRuntimeProfileState? =
        database.withTransaction {
            require(identityKey.isNotBlank())
            val identity = dao.identity(identityKey) ?: return@withTransaction null
            val runtimeIdentity = validateLocalIdentity(identity)
            val pointers = dao.pointers(identityKey)
            if (pointers != null) validatePointers(pointers, identityKey)
            val activeProfile = pointers?.activeProfileId?.let { profileId ->
                requireNotNull(dao.profile(profileId)) { "Active profile pointer is dangling." }.also { profile ->
                    validatePersistedProfile(profile)
                    require(profile.identityKey == identityKey) { "Active profile belongs to another identity." }
                    require(profile.recordState == PersistedProfileRecordState.COMMITTED.name) {
                        "Active profile is not committed."
                    }
                }
            }
            CurrentRuntimeProfileState(
                identity = identity,
                pointers = pointers,
                activeProfile = activeProfile,
                activeExecutionProfile = activeProfile?.toModelExecutionProfile(
                    runtimeIdentity,
                    dao.resourceBindings(activeProfile.profileId)
                ),
                activeJob = activeTuningJobLocked(identityKey)
            )
        }

    /**
     * Returns one installation-local runtime summary for each requested model.
     *
     * The query deliberately never returns profile JSON, resource bindings, absolute paths, or
     * device identifiers. Callers may expose only the small state vocabulary represented here.
     */
    suspend fun modelRuntimeSummaries(modelIds: Collection<String>): List<PersistedModelRuntimeSummary> =
        database.withTransaction {
            val summaries = mutableListOf<PersistedModelRuntimeSummary>()
            val distinctModelIds = modelIds.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            for (modelId in distinctModelIds) {
                val identity = dao.latestIdentityForModel(modelId) ?: continue
                validateLocalIdentity(identity)
                val pointers = dao.pointers(identity.identityKey)?.also { value ->
                    validatePointers(value, identity.identityKey)
                }
                val activeProfile = pointers?.activeProfileId?.let { profileId ->
                    requireNotNull(dao.profile(profileId)).also(::validatePersistedProfile)
                }
                val pendingProfile = pointers?.pendingProfileId?.let { profileId ->
                    requireNotNull(dao.profile(profileId)).also(::validatePersistedProfile)
                }
                summaries += PersistedModelRuntimeSummary(
                    modelId = modelId,
                    identityKey = identity.identityKey,
                    pointers = pointers,
                    activeProfile = activeProfile,
                    pendingProfile = pendingProfile,
                    activeJob = activeTuningJobLocked(identity.identityKey)
                )
            }
            summaries
        }

    suspend fun activeTuningJob(identityKey: String): TuningJobEntity? = database.withTransaction {
        require(identityKey.isNotBlank())
        val identity = dao.identity(identityKey) ?: return@withTransaction null
        validateLocalIdentity(identity)
        activeTuningJobLocked(identityKey)
    }

    suspend fun tuningJob(jobId: String): TuningJobEntity? = database.withTransaction {
        require(jobId.isNotBlank())
        val job = dao.job(jobId)
        if (job != null) {
            validateTuningJob(job)
            val identity = requireNotNull(dao.identity(job.identityKey)) {
                "Tuning job runtime identity is missing."
            }
            validateLocalIdentity(identity)
        }
        job
    }

    suspend fun pendingTransaction(identityKey: String): PendingRuntimeProfileTransaction? =
        database.withTransaction {
            require(identityKey.isNotBlank())
            val identity = requireNotNull(dao.identity(identityKey)) { "Runtime identity is missing." }
            val runtimeIdentity = validateLocalIdentity(identity)
            val pointers = dao.pointers(identityKey) ?: return@withTransaction null
            validatePointers(pointers, identityKey)
            val pendingProfileId = pointers.pendingProfileId ?: return@withTransaction null
            val pendingProfile = requireNotNull(dao.profile(pendingProfileId)) {
                "Pending profile pointer is dangling."
            }
            validatePersistedProfile(pendingProfile)
            require(pendingProfile.identityKey == identityKey) { "Pending profile belongs to another identity." }
            require(pendingProfile.recordState == PersistedProfileRecordState.STAGED.name) {
                "Pending profile is not staged."
            }
            val journals = dao.journalsInStates(identityKey, RECOVERABLE_JOURNAL_STATE_NAMES)
                .filter { it.pendingProfileId == pendingProfileId }
            require(journals.size == 1) {
                "Pending profile must have exactly one unfinished tuning transaction."
            }
            val journal = journals.single()
            require(journal.resolvedLoadSignature == pendingProfile.resolvedLoadSignature) {
                "Pending profile signature does not match its tuning transaction."
            }
            journal.jobId?.let { jobId ->
                val job = requireNotNull(dao.job(jobId)) { "Pending transaction tuning job is missing." }
                validateTuningJob(job)
                require(job.identityKey == identityKey) { "Pending transaction job belongs to another identity." }
                require(job.state in ACTIVE_JOB_STATE_NAMES) {
                    "Pending transaction points at a terminal tuning job."
                }
                require(job.candidateProfileId == pendingProfileId) {
                    "Pending transaction job points at another candidate."
                }
            }
            PendingRuntimeProfileTransaction(
                pointers = pointers,
                pendingProfile = pendingProfile,
                pendingExecutionProfile = pendingProfile.toModelExecutionProfile(
                    runtimeIdentity,
                    dao.resourceBindings(pendingProfile.profileId)
                ),
                journal = journal
            )
        }

    suspend fun createTuningJob(
        identityKey: String,
        autoApplyLoadChanges: Boolean,
        jobId: String = UUID.randomUUID().toString(),
        phase: String = "QUEUED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        require(identityKey.isNotBlank() && jobId.isNotBlank())
        val identity = requireNotNull(dao.identity(identityKey)) {
            "Runtime identity must be persisted before creating a tuning job."
        }
        validateLocalIdentity(identity)
        dao.job(jobId)?.let { existing ->
            validateTuningJob(existing)
            require(existing.identityKey == identityKey) { "Tuning job id belongs to another identity." }
            require(existing.autoApplyLoadChanges == autoApplyLoadChanges) {
                "Tuning job id was reused with different auto-apply authorization."
            }
            return@withTransaction existing
        }
        require(activeTuningJobLocked(identityKey) == null) {
            "A tuning job is already active for this runtime identity."
        }
        val created = TuningJobEntity(
            jobId = jobId,
            identityKey = identityKey,
            state = PersistedTuningJobState.QUEUED.name,
            candidateProfileId = null,
            phase = RuntimeProfilePersistencePolicy.sanitizeStage(phase),
            autoApplyLoadChanges = autoApplyLoadChanges,
            cancellationRequested = false,
            failureCode = null,
            failureSummary = null,
            createdAt = now,
            updatedAt = now,
            lastHeartbeatAt = now
        )
        validateTuningJob(created)
        dao.upsertJob(created)
        created
    }

    suspend fun transitionTuningJob(
        jobId: String,
        state: PersistedTuningJobState,
        phase: String,
        failureCode: String? = null,
        failureSummary: String? = null,
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        updateTuningJobLocked(
            current = current,
            targetState = state,
            phase = phase,
            failureCode = failureCode,
            failureSummary = failureSummary,
            now = now
        )
    }

    suspend fun heartbeatTuningJob(
        jobId: String,
        phase: String? = null,
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val state = persistedTuningJobState(current.state)
        if (state in TERMINAL_JOB_STATES) return@withTransaction current
        require(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                from = state,
                to = state,
                cancellationRequested = current.cancellationRequested
            )
        ) { "A canceled tuning job cannot continue reporting active work." }
        persistTuningJob(
            current.copy(
                phase = RuntimeProfilePersistencePolicy.sanitizeStage(phase ?: current.phase),
                updatedAt = monotonicJobTime(current, now),
                lastHeartbeatAt = monotonicJobTime(current, now)
            )
        )
    }

    suspend fun pauseTuningJob(
        jobId: String,
        phase: String = "PAUSED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val from = persistedTuningJobState(current.state)
        val target = requireNotNull(RuntimeProfilePersistencePolicy.pauseTuningJobTarget(from)) {
            "Tuning job cannot be paused from ${from.name}."
        }
        updateTuningJobLocked(current, target, phase, now = now)
    }

    suspend fun resumeTuningJob(
        jobId: String,
        phase: String = "RUNNING",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val from = persistedTuningJobState(current.state)
        val target = requireNotNull(RuntimeProfilePersistencePolicy.resumeTuningJobTarget(from)) {
            "Tuning job cannot be resumed from ${from.name}."
        }
        updateTuningJobLocked(current, target, phase, now = now)
    }

    suspend fun requestTuningJobCancellation(
        jobId: String,
        phase: String = "CANCEL_REQUESTED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val from = persistedTuningJobState(current.state)
        val target = RuntimeProfilePersistencePolicy.cancellationTuningJobTarget(from)
        if (target == from && from in NON_INTERRUPTIBLE_OR_TERMINAL_JOB_STATES) {
            return@withTransaction current
        }
        updateTuningJobLocked(
            current = current,
            targetState = target,
            phase = phase,
            cancellationRequested = true,
            now = now
        )
    }

    suspend fun stageCandidate(
        profile: ExecutionProfileEntity,
        transactionId: String,
        rollbackTargetProfileId: String?,
        job: TuningJobEntity? = null,
        resourceBindings: List<RuntimeResourceBindingEntity> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = database.withTransaction {
        require(profile.recordState == PersistedProfileRecordState.STAGED.name)
        require(profile.profileId.isNotBlank() && profile.identityKey.isNotBlank())
        require(profile.resolvedLoadSignature.isNotBlank())
        require(transactionId.isNotBlank())
        require(profile.runtimeOverrideSignature == "NONE") { "Request-scoped runtime overrides must not be persisted." }
        validatePersistedProfile(profile)
        val identityEntity = requireNotNull(dao.identity(profile.identityKey)) {
            "Runtime identity must be persisted before a profile."
        }
        val identity = identityEntity.toModelRuntimeIdentity()
        require(identity.installationScopeId == installationScopeIdLocked(now)) {
            "Runtime profile belongs to another app installation."
        }
        profile.toModelExecutionProfile(identity, resourceBindings)

        dao.journal(transactionId)?.let { existingJournal ->
            val existingProfile = requireNotNull(dao.profile(existingJournal.pendingProfileId)) {
                "Idempotent transaction lost its pending profile."
            }
            val existingPointers = requireNotNull(dao.pointers(existingJournal.identityKey)) {
                "Idempotent transaction lost its profile pointers."
            }
            require(existingJournal.state == TuningJournalState.STAGED.name)
            require(existingJournal.identityKey == profile.identityKey)
            require(existingJournal.pendingProfileId == profile.profileId)
            require(existingJournal.resolvedLoadSignature == profile.resolvedLoadSignature)
            require(
                existingProfile == profile.copy(
                    parentCommittedProfileId = existingProfile.parentCommittedProfileId,
                    createdAt = existingProfile.createdAt,
                    updatedAt = existingProfile.updatedAt
                ) && existingPointers.pendingProfileId == profile.profileId
            ) {
                "Transaction id was reused with a different candidate."
            }
            val storedBindings = dao.resourceBindings(profile.profileId)
            val replayBindings = resourceBindings.sortedBy { it.field }
            require(
                storedBindings.size == replayBindings.size && storedBindings.zip(replayBindings).all { (stored, replay) ->
                    stored == replay.copy(createdAt = stored.createdAt, updatedAt = stored.updatedAt)
                }
            ) {
                "Transaction id was reused with different resource bindings."
            }
            return@withTransaction existingPointers
        }
        require(dao.profile(profile.profileId) == null) { "Profile id already exists." }
        require(
            dao.journalsInStates(profile.identityKey, RECOVERABLE_JOURNAL_STATE_NAMES).isEmpty()
        ) { "A previous profile transaction still requires recovery." }

        val current = dao.pointers(profile.identityKey) ?: ProfilePointersEntity(
            identityKey = profile.identityKey,
            activeProfileId = null,
            pendingProfileId = null,
            lastKnownGoodProfileId = null,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        require(current.pendingProfileId == null || current.pendingProfileId == profile.profileId) {
            "A different pending profile already exists for this runtime identity."
        }
        val selectedRollback = selectCommittedPointerTarget(
            identityKey = profile.identityKey,
            explicitProfileId = rollbackTargetProfileId,
            current = current
        )
        val parentProfileId = profile.parentCommittedProfileId ?: selectedRollback?.profileId
        if (parentProfileId != null) {
            val parent = requireNotNull(dao.profile(parentProfileId)) { "Parent committed profile is missing." }
            require(parent.identityKey == profile.identityKey)
            require(parent.recordState == PersistedProfileRecordState.COMMITTED.name)
        }
        if (job != null) {
            val sanitizedInputJob = job.copy(
                phase = RuntimeProfilePersistencePolicy.sanitizeStage(job.phase),
                failureCode = job.failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode),
                failureSummary = job.failureSummary?.let(RuntimeProfilePersistencePolicy::sanitizeFailureSummary)
            )
            val persistedJob = dao.job(job.jobId)
            val jobForWrite = persistedJob ?: sanitizedInputJob
            validateTuningJob(jobForWrite)
            require(jobForWrite.identityKey == profile.identityKey) {
                "Tuning job belongs to another runtime identity."
            }
            require(jobForWrite.candidateProfileId == null || jobForWrite.candidateProfileId == profile.profileId)
            require(jobForWrite.state in ACTIVE_JOB_STATE_NAMES) { "Cannot stage a candidate for a terminal tuning job." }
            val jobState = persistedTuningJobState(jobForWrite.state)
            require(
                !jobForWrite.cancellationRequested &&
                    jobState != PersistedTuningJobState.CANCELING &&
                    jobState != PersistedTuningJobState.RECOVERING
            ) {
                "Cannot stage a candidate for a canceled tuning job."
            }
            val otherJobs = dao.jobsInStates(profile.identityKey, ACTIVE_JOB_STATE_NAMES)
                .filterNot { it.jobId == jobForWrite.jobId }
            require(otherJobs.isEmpty()) { "A tuning job is already active for this runtime identity." }
            dao.upsertJob(
                jobForWrite.copy(
                    candidateProfileId = profile.profileId,
                    phase = RuntimeProfilePersistencePolicy.sanitizeStage(jobForWrite.phase),
                    failureCode = jobForWrite.failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode),
                    failureSummary = jobForWrite.failureSummary?.let(RuntimeProfilePersistencePolicy::sanitizeFailureSummary),
                    updatedAt = maxOf(now, jobForWrite.createdAt, jobForWrite.updatedAt, jobForWrite.lastHeartbeatAt),
                    lastHeartbeatAt = maxOf(now, jobForWrite.createdAt, jobForWrite.updatedAt, jobForWrite.lastHeartbeatAt)
                )
            )
        }
        dao.insertProfile(
            profile.copy(
                parentCommittedProfileId = parentProfileId,
                updatedAt = now
            )
        )
        if (resourceBindings.isNotEmpty()) {
            validateResourceBindings(profile, identity, resourceBindings)
            dao.insertResourceBindings(resourceBindings.sortedBy { it.field })
        }
        val pointers = current.copy(
            pendingProfileId = profile.profileId,
            rollbackTargetProfileId = selectedRollback?.profileId,
            updatedAt = now
        )
        dao.upsertPointers(pointers)
        dao.insertJournal(
            TuningJournalEntity(
                transactionId = transactionId,
                identityKey = profile.identityKey,
                jobId = job?.jobId,
                pendingProfileId = profile.profileId,
                rollbackTargetProfileId = pointers.rollbackTargetProfileId,
                resolvedLoadSignature = profile.resolvedLoadSignature,
                state = TuningJournalState.STAGED.name,
                stage = "STAGED",
                recoveryAttempts = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        pointers
    }

    suspend fun stageCandidate(
        snapshot: PersistedExecutionProfileSnapshot,
        transactionId: String,
        rollbackTargetProfileId: String?,
        job: TuningJobEntity? = null,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = stageCandidate(
        profile = snapshot.profile,
        transactionId = transactionId,
        rollbackTargetProfileId = rollbackTargetProfileId,
        job = job,
        resourceBindings = snapshot.resourceBindings,
        now = now
    )

    suspend fun updateJournalStage(
        transactionId: String,
        state: TuningJournalState,
        stage: String,
        now: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        val currentState = persistedJournalState(journal.state)
        require(RuntimeProfilePersistencePolicy.canUpdateJournalStage(currentState, state)) {
            "Illegal tuning journal transition: ${journal.state} -> ${state.name}."
        }
        check(
            dao.updateJournal(
                journal.copy(
                    state = state.name,
                    stage = RuntimeProfilePersistencePolicy.sanitizeStage(stage),
                    updatedAt = now
                )
            ) == 1
        ) { "Tuning journal update was lost." }
    }

    suspend fun commitCandidate(
        transactionId: String,
        verificationLevel: PersistedProfileVerificationLevel,
        activeLoadedSignature: String,
        effectiveExecutionSignature: String,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(journal.state == TuningJournalState.VALIDATING.name) {
            "A candidate can be committed only after validation."
        }
        val profile = requireNotNull(dao.profile(journal.pendingProfileId)) { "Pending profile is missing." }
        val pointers = requireNotNull(dao.pointers(journal.identityKey)) { "Profile pointers are missing." }
        require(profile.identityKey == journal.identityKey) { "Pending profile belongs to another identity." }
        require(pointers.pendingProfileId == profile.profileId) { "Pending profile pointer does not match the transaction." }
        require(profile.recordState == PersistedProfileRecordState.STAGED.name) { "Only a staged profile can be committed." }
        require(profile.resolvedLoadSignature == journal.resolvedLoadSignature) {
            "Resolved load signature changed after authorization."
        }
        require(activeLoadedSignature.isNotBlank() && effectiveExecutionSignature.isNotBlank())

        check(
            dao.updateProfile(
                profile.copy(
                recordState = PersistedProfileRecordState.COMMITTED.name,
                verificationLevel = verificationLevel.name,
                activeLoadedSignature = activeLoadedSignature,
                effectiveExecutionSignature = effectiveExecutionSignature,
                failureStage = null,
                failureCode = null,
                failureSummary = null,
                    updatedAt = now
                )
            )
            == 1
        ) { "Pending profile commit was lost." }
        val committedPointers = pointers.copy(
            activeProfileId = profile.profileId,
            pendingProfileId = null,
            lastKnownGoodProfileId = profile.profileId,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        dao.upsertPointers(committedPointers)
        check(
            dao.updateJournal(
                journal.copy(
                state = TuningJournalState.COMMITTED.name,
                stage = "COMMITTED",
                    updatedAt = now
                )
            )
            == 1
        ) { "Tuning journal commit was lost." }
        journal.jobId?.let { jobId ->
            val job = requireNotNull(dao.job(jobId)) { "Tuning job is missing for the candidate transaction." }
            validateTuningJob(job)
            require(persistedTuningJobState(job.state) == PersistedTuningJobState.VALIDATING) {
                "A tuning job can be committed only from VALIDATING."
            }
            require(!job.cancellationRequested) { "A canceled tuning job cannot be committed." }
            require(job.candidateProfileId == profile.profileId) {
                "Tuning job candidate does not match the transaction."
            }
            dao.upsertJob(
                job.copy(
                    state = PersistedTuningJobState.SUCCEEDED.name,
                    phase = "COMPLETED",
                    candidateProfileId = profile.profileId,
                    updatedAt = maxOf(now, job.createdAt, job.updatedAt, job.lastHeartbeatAt),
                    lastHeartbeatAt = maxOf(now, job.createdAt, job.updatedAt, job.lastHeartbeatAt)
                )
            )
        }
        committedPointers
    }

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
    ): ProfilePointersEntity = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(RuntimeProfilePersistencePolicy.canCompleteIsolatedProbe(journal)) {
            "Only an unfinished jobless probe transaction can be completed here."
        }
        val profile = requireNotNull(dao.profile(journal.pendingProfileId)) {
            "Pending probe profile is missing."
        }
        require(profile.identityKey == journal.identityKey)
        require(profile.recordState == PersistedProfileRecordState.STAGED.name)
        val pointers = requireNotNull(dao.pointers(journal.identityKey)) {
            "Probe profile pointers are missing."
        }
        require(pointers.pendingProfileId == profile.profileId) {
            "Pending probe pointer does not match the journal."
        }
        check(
            dao.updateProfile(
                profile.copy(
                    recordState = PersistedProfileRecordState.REJECTED.name,
                    failureStage = "ISOLATED_PROBE",
                    failureCode = if (passed) "PROBE_EVIDENCE_RETAINED" else "PROBE_REJECTED",
                    failureSummary = RuntimeProfilePersistencePolicy.sanitizeFailureSummary(summary),
                    updatedAt = now
                )
            ) == 1
        ) { "Probe profile terminal update was lost." }
        val completed = pointers.copy(
            pendingProfileId = null,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        dao.upsertPointers(completed)
        check(
            dao.updateJournal(
                journal.copy(
                    state = TuningJournalState.REJECTED.name,
                    stage = if (passed) "PROBE_PASSED" else "PROBE_REJECTED",
                    updatedAt = now
                )
            ) == 1
        ) { "Probe journal terminal update was lost." }
        completed
    }

    suspend fun rejectCandidate(
        transactionId: String,
        failureStage: String,
        failureCode: String,
        failureSummary: String,
        now: Long = System.currentTimeMillis()
    ): RuntimeRecoveryPlan = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(journal.state in REJECTABLE_JOURNAL_STATE_NAMES) {
            "Only an unfinished candidate transaction can be rejected."
        }
        val profile = requireNotNull(dao.profile(journal.pendingProfileId)) { "Pending profile is missing." }
        require(profile.identityKey == journal.identityKey)
        require(profile.recordState == PersistedProfileRecordState.STAGED.name) {
            "Only a staged candidate can be rejected."
        }
        val pointers = dao.pointers(journal.identityKey) ?: ProfilePointersEntity(
            identityKey = journal.identityKey,
            activeProfileId = null,
            pendingProfileId = profile.profileId,
            lastKnownGoodProfileId = null,
            rollbackTargetProfileId = journal.rollbackTargetProfileId,
            updatedAt = now
        )
        require(pointers.pendingProfileId == profile.profileId) { "Pending profile pointer does not match the transaction." }
        val sanitizedStage = RuntimeProfilePersistencePolicy.sanitizeStage(failureStage)
        val sanitizedCode = RuntimeProfilePersistencePolicy.sanitizeFailureCode(failureCode)
        val sanitizedSummary = RuntimeProfilePersistencePolicy.sanitizeFailureSummary(failureSummary)
        check(
            dao.updateProfile(
                profile.copy(
                recordState = PersistedProfileRecordState.REJECTED.name,
                    failureStage = sanitizedStage,
                    failureCode = sanitizedCode,
                    failureSummary = sanitizedSummary,
                    updatedAt = now
                )
            )
            == 1
        ) { "Rejected profile update was lost." }
        val rollback = resolveRecoveryTarget(journal, profile, pointers)
        val recovery = RuntimeProfilePersistencePolicy.recoveryDecision(
            journal.recoveryAttempts,
            rollback != null
        )
        dao.upsertPointers(
            pointers.copy(
                pendingProfileId = null,
                rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                updatedAt = now
            )
        )
        check(
            dao.updateJournal(
                journal.copy(
                    rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                    state = if (recovery.canRecover) {
                        TuningJournalState.RECOVERING.name
                    } else {
                        TuningJournalState.FAILED.name
                    },
                    stage = recovery.terminalStage,
                    recoveryAttempts = recovery.nextAttempts,
                    updatedAt = now
                )
            )
            == 1
        ) { "Tuning journal rejection was lost." }
        journal.jobId?.let { jobId ->
            dao.job(jobId)?.let { job ->
                dao.upsertJob(
                    job.copy(
                        state = if (recovery.canRecover) PersistedTuningJobState.RECOVERING.name else PersistedTuningJobState.FAILED.name,
                        phase = if (recovery.canRecover) "RECOVERING" else "FAILED",
                        failureCode = sanitizedCode,
                        failureSummary = sanitizedSummary,
                        updatedAt = now,
                        lastHeartbeatAt = now
                    )
                )
            }
        }
        RuntimeRecoveryPlan(
            transactionId,
            journal.identityKey,
            profile.profileId,
            rollback?.profileId?.takeIf { recovery.canRecover },
            recovery.nextAttempts
        )
    }

    suspend fun recoverInterruptedTransactions(
        now: Long = System.currentTimeMillis()
    ): List<RuntimeRecoveryPlan> = database.withTransaction {
        val recoverable = dao.journalsInStates(RECOVERABLE_JOURNAL_STATE_NAMES)
        val selectedTransactionIds = mutableSetOf<String>()
        recoverable.groupBy { it.identityKey }.forEach { (identityKey, journals) ->
            val pendingProfileId = dao.pointers(identityKey)?.pendingProfileId
            val selected = journals.firstOrNull { it.pendingProfileId == pendingProfileId }
                ?: journals.maxWith(compareBy<TuningJournalEntity> { it.updatedAt }.thenBy { it.transactionId })
            selectedTransactionIds += selected.transactionId
        }

        recoverable.filterNot { it.transactionId in selectedTransactionIds }.forEach { journal ->
            dao.profile(journal.pendingProfileId)
                ?.takeIf { it.recordState == PersistedProfileRecordState.STAGED.name }
                ?.let { profile ->
                    check(
                        dao.updateProfile(
                            profile.copy(
                                recordState = PersistedProfileRecordState.REJECTED.name,
                                failureStage = "PROCESS_RECOVERY",
                                failureCode = "SUPERSEDED_TRANSACTION",
                                failureSummary = "同一模型存在更新的未完成事务；旧事务已隔离。",
                                updatedAt = now
                            )
                        ) == 1
                    )
                }
            check(
                dao.updateJournal(
                    journal.copy(
                        state = TuningJournalState.FAILED.name,
                        stage = "SUPERSEDED_RECOVERY",
                        updatedAt = now
                    )
                ) == 1
            )
        }

        recoverable.filter { it.transactionId in selectedTransactionIds }.mapNotNull { journal ->
            val profile = dao.profile(journal.pendingProfileId)
            val pointers = dao.pointers(journal.identityKey) ?: ProfilePointersEntity(
                identityKey = journal.identityKey,
                activeProfileId = null,
                pendingProfileId = journal.pendingProfileId,
                lastKnownGoodProfileId = null,
                rollbackTargetProfileId = journal.rollbackTargetProfileId,
                updatedAt = now
            )
            if (profile?.recordState == PersistedProfileRecordState.COMMITTED.name) {
                check(
                    dao.updateJournal(
                        journal.copy(
                            state = TuningJournalState.FAILED.name,
                            stage = "INCONSISTENT_COMMITTED_PROFILE",
                            updatedAt = now
                        )
                    ) == 1
                )
                return@mapNotNull null
            }
            if (profile?.recordState == PersistedProfileRecordState.STAGED.name) {
                check(
                    dao.updateProfile(
                        profile.copy(
                        recordState = PersistedProfileRecordState.REJECTED.name,
                        failureStage = "PROCESS_RECOVERY",
                        failureCode = "INTERRUPTED_TRANSACTION",
                        failureSummary = "调优事务在进程退出前未完成。",
                            updatedAt = now
                        )
                    )
                    == 1
                ) { "Interrupted profile update was lost." }
            }
            val rollback = resolveRecoveryTarget(journal, profile, pointers)
            val recovery = RuntimeProfilePersistencePolicy.recoveryDecision(
                journal.recoveryAttempts,
                rollback != null
            )
            dao.upsertPointers(
                pointers.copy(
                    pendingProfileId = null,
                    rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                    updatedAt = now
                )
            )
            check(
                dao.updateJournal(
                    journal.copy(
                        rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                        state = if (recovery.canRecover) {
                            TuningJournalState.RECOVERING.name
                        } else {
                            TuningJournalState.FAILED.name
                        },
                        stage = if (recovery.canRecover) "PROCESS_RECOVERY" else recovery.terminalStage,
                        recoveryAttempts = recovery.nextAttempts,
                        updatedAt = now
                    )
                )
                == 1
            ) { "Interrupted tuning journal update was lost." }
            journal.jobId?.let { jobId ->
                dao.job(jobId)?.let { job ->
                    dao.upsertJob(
                        job.copy(
                            state = if (recovery.canRecover) PersistedTuningJobState.RECOVERING.name else PersistedTuningJobState.FAILED.name,
                            phase = if (recovery.canRecover) "PROCESS_RECOVERY" else recovery.terminalStage,
                            failureCode = "INTERRUPTED_TRANSACTION",
                            failureSummary = "调优事务在进程退出前未完成。",
                            updatedAt = now,
                            lastHeartbeatAt = now
                        )
                    )
                }
            }
            if (!recovery.canRecover) return@mapNotNull null
            RuntimeRecoveryPlan(
                transactionId = journal.transactionId,
                identityKey = journal.identityKey,
                rejectedProfileId = journal.pendingProfileId,
                rollbackProfileId = requireNotNull(rollback).profileId,
                recoveryAttempt = recovery.nextAttempts
            )
        }
    }

    suspend fun completeRecovery(
        transactionId: String,
        restoredProfileId: String?,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(journal.state == TuningJournalState.RECOVERING.name) {
            "Only a recovering transaction can be completed."
        }
        require(journal.recoveryAttempts == RuntimeProfilePersistencePolicy.MAX_RECOVERY_ATTEMPTS) {
            "Recovery was not authorized exactly once."
        }
        val pointers = requireNotNull(dao.pointers(journal.identityKey)) { "Profile pointers are missing." }
        if (restoredProfileId != null) {
            val restored = requireNotNull(dao.profile(restoredProfileId)) { "Rollback profile is missing." }
            require(restored.recordState == PersistedProfileRecordState.COMMITTED.name) { "Rollback profile is not committed." }
            require(restored.identityKey == journal.identityKey) { "Rollback profile belongs to another identity." }
            require(restoredProfileId == journal.rollbackTargetProfileId) { "Rollback profile was not authorized by the journal." }
            require(restoredProfileId == pointers.rollbackTargetProfileId) { "Rollback profile pointer changed during recovery." }
        }
        val completed = pointers.copy(
            activeProfileId = restoredProfileId ?: pointers.activeProfileId,
            pendingProfileId = null,
            lastKnownGoodProfileId = restoredProfileId ?: pointers.lastKnownGoodProfileId,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        dao.upsertPointers(completed)
        check(
            dao.updateJournal(
                journal.copy(
                    state = if (restoredProfileId == null) TuningJournalState.FAILED.name else TuningJournalState.ROLLED_BACK.name,
                    stage = if (restoredProfileId == null) "ROLLBACK_FAILED" else "ROLLED_BACK",
                    updatedAt = now
                )
            )
            == 1
        ) { "Recovery completion was lost." }
        journal.jobId?.let { jobId ->
            dao.job(jobId)?.let { job ->
                dao.upsertJob(
                    job.copy(
                        state = PersistedTuningJobState.FAILED.name,
                        phase = if (restoredProfileId == null) "ROLLBACK_FAILED" else "ROLLED_BACK",
                        updatedAt = now,
                        lastHeartbeatAt = now
                    )
                )
            }
        }
        completed
    }

    suspend fun recordMeasurement(measurement: CandidateMeasurementEntity, keepCount: Int = 256) {
        database.withTransaction {
            require(measurement.measurementId.isNotBlank())
            require(dao.profile(measurement.profileId) != null) { "Measurement profile is missing." }
            val sanitized = measurement.copy(
                metricsJson = RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(measurement.metricsJson),
                failureCode = measurement.failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode)
            )
            dao.insertMeasurement(sanitized)
            dao.pruneMeasurements(measurement.profileId, keepCount.coerceIn(32, 2048))
        }
    }

    suspend fun pruneRejectedProfiles(
        olderThan: Long = System.currentTimeMillis() - REJECTED_PROFILE_RETENTION_MS
    ) {
        database.withTransaction {
            dao.pruneRejectedProfiles(olderThan)
            dao.pruneOrphanMeasurements()
            dao.pruneOrphanResourceBindings()
        }
    }

    suspend fun pruneTerminalHistory(
        olderThan: Long = System.currentTimeMillis() - TERMINAL_HISTORY_RETENTION_MS,
        keepCount: Int = 512
    ) {
        database.withTransaction {
            val boundedCount = keepCount.coerceIn(64, 4096)
            dao.pruneTerminalJournalsByAge(TERMINAL_JOURNAL_STATE_NAMES, olderThan)
            dao.pruneTerminalJournalsByCount(TERMINAL_JOURNAL_STATE_NAMES, boundedCount)
            dao.pruneTerminalJobsByAge(TERMINAL_JOB_STATE_NAMES, olderThan)
            dao.pruneTerminalJobsByCount(TERMINAL_JOB_STATE_NAMES, boundedCount)
        }
    }

    suspend fun pointers(identityKey: String): ProfilePointersEntity? = dao.pointers(identityKey)
    suspend fun profile(profileId: String): ExecutionProfileEntity? = dao.profile(profileId)
    suspend fun profiles(identityKey: String): List<ExecutionProfileEntity> = dao.profiles(identityKey)

    suspend fun reconstructedProfile(profileId: String): ModelExecutionProfile? = database.withTransaction {
        val entity = dao.profile(profileId) ?: return@withTransaction null
        val identityEntity = requireNotNull(dao.identity(entity.identityKey)) { "Runtime identity is missing." }
        val identity = identityEntity.toModelRuntimeIdentity()
        require(identity.installationScopeId == installationScopeIdLocked(System.currentTimeMillis())) {
            "Persisted profile belongs to another app installation."
        }
        entity.toModelExecutionProfile(identity, dao.resourceBindings(profileId))
    }

    private suspend fun activeTuningJobLocked(identityKey: String): TuningJobEntity? {
        val jobs = dao.jobsInStates(identityKey, ACTIVE_JOB_STATE_NAMES)
        jobs.forEach { job ->
            validateTuningJob(job)
            job.candidateProfileId?.let { profileId ->
                val candidate = requireNotNull(dao.profile(profileId)) {
                    "Active tuning job candidate profile is missing."
                }
                require(candidate.identityKey == identityKey) {
                    "Active tuning job candidate belongs to another identity."
                }
            }
        }
        require(jobs.size <= 1) { "Multiple active tuning jobs exist for this runtime identity." }
        return jobs.singleOrNull()
    }

    private suspend fun requireTuningJobLocked(jobId: String): TuningJobEntity {
        require(jobId.isNotBlank())
        val job = requireNotNull(dao.job(jobId)) { "Unknown tuning job." }
        validateTuningJob(job)
        val identity = requireNotNull(dao.identity(job.identityKey)) {
            "Tuning job runtime identity is missing."
        }
        validateLocalIdentity(identity)
        return job
    }

    private suspend fun updateTuningJobLocked(
        current: TuningJobEntity,
        targetState: PersistedTuningJobState,
        phase: String,
        failureCode: String? = null,
        failureSummary: String? = null,
        cancellationRequested: Boolean = current.cancellationRequested ||
            targetState == PersistedTuningJobState.CANCELING,
        now: Long
    ): TuningJobEntity {
        val from = persistedTuningJobState(current.state)
        if (from == targetState && from in TERMINAL_JOB_STATES) return current
        require(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                from = from,
                to = targetState,
                cancellationRequested = current.cancellationRequested
            )
        ) { "Illegal tuning job transition: ${from.name} -> ${targetState.name}." }
        require(!current.cancellationRequested || cancellationRequested) {
            "A tuning job cancellation request is monotonic."
        }
        if (targetState.name in ACTIVE_JOB_STATE_NAMES) {
            val otherJobs = dao.jobsInStates(current.identityKey, ACTIVE_JOB_STATE_NAMES)
                .filterNot { it.jobId == current.jobId }
            require(otherJobs.isEmpty()) { "Another tuning job is active for this runtime identity." }
        }
        if (targetState == PersistedTuningJobState.SUCCEEDED && from != PersistedTuningJobState.SUCCEEDED) {
            val candidateProfileId = requireNotNull(current.candidateProfileId) {
                "A tuning job cannot succeed without a candidate profile."
            }
            val candidate = requireNotNull(dao.profile(candidateProfileId)) {
                "Successful tuning job candidate is missing."
            }
            val pointers = requireNotNull(dao.pointers(current.identityKey)) {
                "Successful tuning job profile pointers are missing."
            }
            require(candidate.recordState == PersistedProfileRecordState.COMMITTED.name) {
                "A tuning job cannot succeed before its candidate is committed."
            }
            require(pointers.activeProfileId == candidateProfileId && pointers.pendingProfileId == null) {
                "A tuning job cannot succeed before its candidate becomes active."
            }
        }
        val retainFailure = targetState == PersistedTuningJobState.FAILED ||
            targetState == PersistedTuningJobState.RECOVERING
        val timestamp = monotonicJobTime(current, now)
        return persistTuningJob(
            current.copy(
                state = targetState.name,
                phase = RuntimeProfilePersistencePolicy.sanitizeStage(phase),
                cancellationRequested = cancellationRequested,
                failureCode = if (retainFailure) {
                    failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode) ?: current.failureCode
                } else {
                    null
                },
                failureSummary = if (retainFailure) {
                    failureSummary?.let(RuntimeProfilePersistencePolicy::sanitizeFailureSummary) ?:
                        current.failureSummary
                } else {
                    null
                },
                updatedAt = timestamp,
                lastHeartbeatAt = timestamp
            )
        )
    }

    private suspend fun persistTuningJob(job: TuningJobEntity): TuningJobEntity {
        validateTuningJob(job)
        dao.upsertJob(job)
        return job
    }

    private fun validateTuningJob(job: TuningJobEntity) {
        require(job.jobId.isNotBlank() && job.identityKey.isNotBlank())
        val state = persistedTuningJobState(job.state)
        require(job.phase == RuntimeProfilePersistencePolicy.sanitizeStage(job.phase)) {
            "Tuning job phase must be canonical."
        }
        require(
            job.failureCode == null ||
                job.failureCode == RuntimeProfilePersistencePolicy.sanitizeFailureCode(job.failureCode)
        ) { "Tuning job failure code must be canonical." }
        require(
            job.failureSummary == null ||
                job.failureSummary == RuntimeProfilePersistencePolicy.sanitizeFailureSummary(job.failureSummary)
        ) { "Tuning job failure summary must be redacted." }
        require(job.updatedAt >= job.createdAt && job.lastHeartbeatAt >= job.createdAt) {
            "Tuning job timestamps are not monotonic."
        }
        require(state != PersistedTuningJobState.CANCELING || job.cancellationRequested) {
            "A canceling tuning job must retain its cancellation request."
        }
    }

    private suspend fun validateLocalIdentity(identity: RuntimeIdentityEntity): ModelRuntimeIdentity {
        val runtimeIdentity = identity.toModelRuntimeIdentity()
        val localScope = requireNotNull(
            dao.installationScope(RuntimeInstallationScopeEntity.INSTALLATION_SCOPE_KEY)
        ) { "Runtime installation scope is missing." }
        require(identity.installationScopeId == localScope.installationScopeId) {
            "Runtime identity belongs to another app installation."
        }
        return runtimeIdentity
    }

    private suspend fun validatePointers(pointers: ProfilePointersEntity, identityKey: String) {
        require(pointers.identityKey == identityKey) { "Profile pointers belong to another identity." }
        require(
            pointers.pendingProfileId == null || pointers.pendingProfileId !in listOfNotNull(
                pointers.activeProfileId,
                pointers.lastKnownGoodProfileId,
                pointers.rollbackTargetProfileId
            )
        ) { "Pending profile pointer must be distinct from all committed-profile pointers." }
        listOfNotNull(
            pointers.activeProfileId,
            pointers.pendingProfileId,
            pointers.lastKnownGoodProfileId,
            pointers.rollbackTargetProfileId
        ).distinct().forEach { profileId ->
            val profile = requireNotNull(dao.profile(profileId)) { "Profile pointer is dangling." }
            require(profile.identityKey == identityKey) { "Profile pointer belongs to another identity." }
            val expectedState = when (profileId) {
                pointers.pendingProfileId -> PersistedProfileRecordState.STAGED.name
                else -> PersistedProfileRecordState.COMMITTED.name
            }
            require(profile.recordState == expectedState) {
                "Profile pointer references a ${profile.recordState} profile where $expectedState is required."
            }
        }
    }

    private fun monotonicJobTime(job: TuningJobEntity, now: Long): Long = maxOf(
        now,
        job.createdAt,
        job.updatedAt,
        job.lastHeartbeatAt
    )

    private suspend fun resolveRecoveryTarget(
        journal: TuningJournalEntity,
        rejected: ExecutionProfileEntity?,
        pointers: ProfilePointersEntity
    ): ExecutionProfileEntity? {
        val preferred = listOfNotNull(
            journal.rollbackTargetProfileId,
            pointers.rollbackTargetProfileId,
            pointers.activeProfileId,
            pointers.lastKnownGoodProfileId,
            rejected?.parentCommittedProfileId
        ).distinct()
        preferred.forEach { profileId ->
            committedRecoveryProfile(profileId, journal.identityKey, rejected?.profileId)?.let { return it }
        }

        var parentId = rejected?.parentCommittedProfileId
        var depth = 0
        while (parentId != null && depth < MAX_PARENT_DEPTH) {
            val parent = dao.profile(parentId) ?: break
            if (parent.identityKey != journal.identityKey) break
            if (parent.recordState == PersistedProfileRecordState.COMMITTED.name) return parent
            parentId = parent.parentCommittedProfileId
            depth += 1
        }
        return null
    }

    private suspend fun committedRecoveryProfile(
        profileId: String,
        identityKey: String,
        rejectedProfileId: String?
    ): ExecutionProfileEntity? = dao.profile(profileId)?.takeIf { candidate ->
        candidate.profileId != rejectedProfileId &&
            candidate.identityKey == identityKey &&
            candidate.recordState == PersistedProfileRecordState.COMMITTED.name
    }

    private suspend fun installationScopeIdLocked(now: Long): String =
        dao.installationScope(RuntimeInstallationScopeEntity.INSTALLATION_SCOPE_KEY)
            ?.installationScopeId
            ?: UUID.randomUUID().toString().also { generated ->
                dao.insertInstallationScope(
                    RuntimeInstallationScopeEntity(
                        installationScopeId = generated,
                        createdAt = now
                    )
                )
            }

    private suspend fun selectCommittedPointerTarget(
        identityKey: String,
        explicitProfileId: String?,
        current: ProfilePointersEntity
    ): ExecutionProfileEntity? {
        val profileId = explicitProfileId ?: current.activeProfileId ?: current.lastKnownGoodProfileId ?: return null
        return requireNotNull(dao.profile(profileId)) { "Rollback profile is missing." }.also { target ->
            require(target.identityKey == identityKey) { "Rollback profile belongs to another identity." }
            require(target.recordState == PersistedProfileRecordState.COMMITTED.name) {
                "Rollback profile must be committed."
            }
        }
    }

    private fun validatePersistedProfile(profile: ExecutionProfileEntity) {
        require(profile.revision > 0)
        PersistedProfileRecordState.valueOf(profile.recordState)
        PersistedProfileVerificationLevel.valueOf(profile.verificationLevel)
        require(profile.desiredProfileSignature.isNotBlank())
        require(profile.committedExecutionSignature.isNotBlank())
        RuntimeProfilePersistencePolicy.requireSafeProfileJson(profile.profileJson)
        RuntimeProfilePersistencePolicy.requireSafeProfileJson(profile.resolvedLoadJson)
        RuntimeProfilePersistencePolicy.requireSafeProfileJson(profile.committedExecutionJson)
        require(
            profile.quarantinedOverridesJson ==
                RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(profile.quarantinedOverridesJson)
        ) { "Quarantined override evidence must be canonical and redacted." }
        require(
            profile.sourceSummaryJson == RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(profile.sourceSummaryJson)
        ) { "Profile source evidence must be canonical and redacted." }
    }

    private fun validateResourceBindings(
        profile: ExecutionProfileEntity,
        identity: ModelRuntimeIdentity,
        bindings: List<RuntimeResourceBindingEntity>
    ) {
        require(bindings.map { it.bindingId }.distinct().size == bindings.size)
        require(bindings.map { it.field }.distinct().size == bindings.size)
        bindings.forEach { binding ->
            require(binding.identityKey == profile.identityKey && binding.identityKey == identity.identityHash)
            require(binding.profileId == profile.profileId)
            require(binding.installationScopeId == identity.installationScopeId)
            require(binding.resourceValue.length <= MAX_RESOURCE_VALUE_CHARS)
            require(binding.resourceFingerprint == persistenceSha256(binding.resourceValue))
        }
    }

    private fun persistedJournalState(value: String): TuningJournalState =
        runCatching { TuningJournalState.valueOf(value) }
            .getOrElse { throw IllegalStateException("Unknown persisted journal state: $value") }

    private fun persistedTuningJobState(value: String): PersistedTuningJobState =
        runCatching { PersistedTuningJobState.valueOf(value) }
            .getOrElse { throw IllegalStateException("Unknown persisted tuning job state: $value") }

    private companion object {
        private const val MAX_PARENT_DEPTH = 16
        private const val REJECTED_PROFILE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        private const val TERMINAL_HISTORY_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
        private val ACTIVE_JOB_STATE_NAMES = setOf(
            PersistedTuningJobState.QUEUED,
            PersistedTuningJobState.RUNNING,
            PersistedTuningJobState.PAUSED,
            PersistedTuningJobState.CANCELING,
            PersistedTuningJobState.VALIDATING,
            PersistedTuningJobState.RECOVERING
        ).map { it.name }
        private val REJECTABLE_JOURNAL_STATE_NAMES = setOf(
            TuningJournalState.STAGED,
            TuningJournalState.APPLYING,
            TuningJournalState.VALIDATING
        ).map { it.name }
        private val RECOVERABLE_JOURNAL_STATE_NAMES = setOf(
            TuningJournalState.STAGED,
            TuningJournalState.APPLYING,
            TuningJournalState.VALIDATING,
            TuningJournalState.RECOVERING
        ).map { it.name }
        private val TERMINAL_JOURNAL_STATE_NAMES = setOf(
            TuningJournalState.COMMITTED,
            TuningJournalState.REJECTED,
            TuningJournalState.ROLLED_BACK,
            TuningJournalState.FAILED
        ).map { it.name }
        private val TERMINAL_JOB_STATE_NAMES = setOf(
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED
        ).map { it.name }
        private val TERMINAL_JOB_STATES = setOf(
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED
        )
        private val NON_INTERRUPTIBLE_OR_TERMINAL_JOB_STATES = setOf(
            PersistedTuningJobState.RECOVERING,
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED
        )
    }
}

