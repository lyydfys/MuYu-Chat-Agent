package com.muyuchat.mca

import androidx.room.withTransaction

internal suspend fun ModelRuntimeProfileStore.completeIsolatedProbeImpl(
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

internal suspend fun ModelRuntimeProfileStore.rejectCandidateImpl(
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

internal suspend fun ModelRuntimeProfileStore.recoverInterruptedTransactionsImpl(
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

internal suspend fun ModelRuntimeProfileStore.completeRecoveryImpl(
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

private suspend fun ModelRuntimeProfileStore.resolveRecoveryTarget(
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

private suspend fun ModelRuntimeProfileStore.committedRecoveryProfile(
        profileId: String,
        identityKey: String,
        rejectedProfileId: String?
    ): ExecutionProfileEntity? = dao.profile(profileId)?.takeIf { candidate ->
        candidate.profileId != rejectedProfileId &&
            candidate.identityKey == identityKey &&
            candidate.recordState == PersistedProfileRecordState.COMMITTED.name
    }

private const val MAX_PARENT_DEPTH = 16

private val REJECTABLE_JOURNAL_STATE_NAMES = setOf(
    TuningJournalState.STAGED,
    TuningJournalState.APPLYING,
    TuningJournalState.VALIDATING
).map { it.name }

internal val RECOVERABLE_JOURNAL_STATE_NAMES = setOf(
    TuningJournalState.STAGED,
    TuningJournalState.APPLYING,
    TuningJournalState.VALIDATING,
    TuningJournalState.RECOVERING
).map { it.name }
