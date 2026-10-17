package com.muyuchat.mca

import androidx.room.withTransaction
import com.muyuchat.core.engine.ModelRuntimeIdentity

internal suspend fun ModelRuntimeProfileStore.pendingTransactionImpl(identityKey: String): PendingRuntimeProfileTransaction? =
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

internal suspend fun ModelRuntimeProfileStore.stageCandidateImpl(
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

internal suspend fun ModelRuntimeProfileStore.stageCandidateImpl2(
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

internal suspend fun ModelRuntimeProfileStore.updateJournalStageImpl(
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

internal suspend fun ModelRuntimeProfileStore.commitCandidateImpl(
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

private suspend fun ModelRuntimeProfileStore.selectCommittedPointerTarget(
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

private fun ModelRuntimeProfileStore.validateResourceBindings(
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

private fun ModelRuntimeProfileStore.persistedJournalState(value: String): TuningJournalState =
        runCatching { TuningJournalState.valueOf(value) }
            .getOrElse { throw IllegalStateException("Unknown persisted journal state: $value") }
