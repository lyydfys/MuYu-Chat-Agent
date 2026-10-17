package com.muyuchat.mca

import androidx.room.withTransaction
import java.util.UUID

internal suspend fun ModelRuntimeProfileStore.activeTuningJobImpl(identityKey: String): TuningJobEntity? = database.withTransaction {
        require(identityKey.isNotBlank())
        val identity = dao.identity(identityKey) ?: return@withTransaction null
        validateLocalIdentity(identity)
        activeTuningJobLocked(identityKey)
    }

internal suspend fun ModelRuntimeProfileStore.tuningJobImpl(jobId: String): TuningJobEntity? = database.withTransaction {
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

internal suspend fun ModelRuntimeProfileStore.createTuningJobImpl(
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

internal suspend fun ModelRuntimeProfileStore.transitionTuningJobImpl(
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

internal suspend fun ModelRuntimeProfileStore.heartbeatTuningJobImpl(
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

internal suspend fun ModelRuntimeProfileStore.pauseTuningJobImpl(
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

internal suspend fun ModelRuntimeProfileStore.resumeTuningJobImpl(
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

internal suspend fun ModelRuntimeProfileStore.requestTuningJobCancellationImpl(
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

internal suspend fun ModelRuntimeProfileStore.recordMeasurementImpl(measurement: CandidateMeasurementEntity, keepCount: Int = 256) {
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

internal suspend fun ModelRuntimeProfileStore.pruneRejectedProfilesImpl(
        olderThan: Long
    ) {
        database.withTransaction {
            dao.pruneRejectedProfiles(olderThan)
            dao.pruneOrphanMeasurements()
            dao.pruneOrphanResourceBindings()
        }
    }

internal suspend fun ModelRuntimeProfileStore.pruneTerminalHistoryImpl(
        olderThan: Long,
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

internal suspend fun ModelRuntimeProfileStore.activeTuningJobLocked(identityKey: String): TuningJobEntity? {
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

private suspend fun ModelRuntimeProfileStore.requireTuningJobLocked(jobId: String): TuningJobEntity {
        require(jobId.isNotBlank())
        val job = requireNotNull(dao.job(jobId)) { "Unknown tuning job." }
        validateTuningJob(job)
        val identity = requireNotNull(dao.identity(job.identityKey)) {
            "Tuning job runtime identity is missing."
        }
        validateLocalIdentity(identity)
        return job
    }

private suspend fun ModelRuntimeProfileStore.updateTuningJobLocked(
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

private suspend fun ModelRuntimeProfileStore.persistTuningJob(job: TuningJobEntity): TuningJobEntity {
        validateTuningJob(job)
        dao.upsertJob(job)
        return job
    }

internal fun ModelRuntimeProfileStore.validateTuningJob(job: TuningJobEntity) {
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

private fun ModelRuntimeProfileStore.monotonicJobTime(job: TuningJobEntity, now: Long): Long = maxOf(
        now,
        job.createdAt,
        job.updatedAt,
        job.lastHeartbeatAt
    )

internal fun ModelRuntimeProfileStore.persistedTuningJobState(value: String): PersistedTuningJobState =
        runCatching { PersistedTuningJobState.valueOf(value) }
            .getOrElse { throw IllegalStateException("Unknown persisted tuning job state: $value") }

internal val ACTIVE_JOB_STATE_NAMES = setOf(
    PersistedTuningJobState.QUEUED,
    PersistedTuningJobState.RUNNING,
    PersistedTuningJobState.PAUSED,
    PersistedTuningJobState.CANCELING,
    PersistedTuningJobState.VALIDATING,
    PersistedTuningJobState.RECOVERING
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
