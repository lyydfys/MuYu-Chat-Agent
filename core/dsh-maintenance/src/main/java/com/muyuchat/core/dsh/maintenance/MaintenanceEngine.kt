package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.io.IOException
import java.util.UUID

/** Raised by a [DshMaintenanceCrashHook] to simulate process death at a durable boundary. */
class DshSimulatedCrash(message: String = "simulated maintenance crash") : RuntimeException(message)

/**
 * One physical maintenance step. The engine never interprets the content: callers stage the new
 * bytes/tree, prove the hash and declare the exact target.
 *
 * [DshStepAction.INSTALL_FILE] / [DshStepAction.INSTALL_TREE] replace the target with [staging]
 * after moving the current target into the transaction quarantine. [DshStepAction.MOVE_ASIDE]
 * only quarantines the target (session recycle, safe-mode stash, purge).
 */
class DshMaintenanceStep(
    val action: DshStepAction,
    val target: File,
    val staging: File? = null,
    val expectedSha256: String? = null,
    /** For MOVE_ASIDE: a missing target is a successful no-op instead of a validation error. */
    val optionalTarget: Boolean = false,
    /** For RELOCATE: the exact new location (session recycle bin). Must not exist yet. */
    val destination: File? = null
)

/** Everything one maintenance transaction needs. Built by the responsibility-specific policy. */
class DshMaintenanceRequest(
    val kind: DshMaintenanceKind,
    val requester: DshMaintenanceRequester,
    val quiescence: DshQuiescenceEvidence,
    val targetRoot: File,
    val steps: List<DshMaintenanceStep> = emptyList(),
    /** Pre-transaction snapshot that must survive; its presence is journaled before APPLYING. */
    val restorePoint: File? = null,
    val healthCheckRequired: Boolean = true,
    val admissionWaitMillis: Long = 0L,
    val requiredFreeBytes: Long = 0L,
    val detail: String = "",
    /**
     * Work that must run inside the lease but produces its own durable output (for example
     * streaming a backup archive). Returning a failure aborts the transaction without claiming
     * success; a null return means the body completed and its output is authoritative.
     */
    val body: (() -> DshMaintenanceFailure?)? = null
)

/**
 * The single maintenance transaction engine (D22a).
 *
 * Contract:
 *
 * 1. the caller must be able to prove quiescence; the engine refuses without it and never treats
 *    "UI looks idle" as proof;
 * 2. one lease from [DshMaintenanceAdmissionPort] wraps the whole transaction; every phase is
 *    forced to the journal before it is reported;
 * 3. the original file is moved into a transaction quarantine before the new one is installed, so
 *    rollback always restores the original and a failed install never deletes user data;
 * 4. `APPLYING` crashes roll back from the journal; `HEALTH_PENDING` re-runs the official-reader
 *    probe and either finishes the commit or rolls back; anything that cannot prove either state
 *    becomes `RECOVERY_REQUIRED` and stops the automatic loop;
 * 5. `recoverOnStart()` must run before the managed host starts (single startup thread); it never
 *    starts Node and never touches a directory that does not carry this module's staging marker.
 *
 * Crash-safe restart is proven by the JVM tests injecting [DshMaintenanceCrashHook] at every
 * boundary and constructing a fresh engine over the same temp directory.
 */
class DshMaintenanceEngine(
    dataRoot: File,
    private val admission: DshMaintenanceAdmissionPort,
    private val probe: DshMaintenanceRecoveryProbe = DshMaintenanceRecoveryProbe.ALWAYS_UNHEALTHY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val crashHook: DshMaintenanceCrashHook = DshMaintenanceCrashHook.NONE,
    private val transactionIdSource: () -> String = {
        UUID.randomUUID().toString().replace("-", "").take(24)
    },
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS
) {
    private val dataRoot: File = dataRoot.canonicalFile
    private val journal = DshMaintenanceJournalStore(this.dataRoot)

    init {
        require(dataRoot.isAbsolute) { "dsh: maintenance data root must be absolute" }
    }

    /** The app-private DSH data root (`files/dsh`), exposed for responsibility-specific staging. */
    val dataRootDirectory: File get() = dataRoot

    /** The journal directory, exposed for wiring/evidence. */
    val journalDirectory: File get() = journal.journalDir

    fun execute(request: DshMaintenanceRequest): DshMaintenanceOutcome {
        val acquired = admission.acquire(
            DshMaintenanceAdmissionRequest(request.requester, request.kind, request.admissionWaitMillis)
        )
        val lease = when (acquired) {
            is DshMaintenanceAdmissionOutcome.Granted -> acquired.lease
            is DshMaintenanceAdmissionOutcome.Busy -> return DshMaintenanceOutcome.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.ADMISSION_BUSY,
                    "another maintenance owner holds the lease",
                    retryable = true,
                    detail = acquired.holders.joinToString(",").take(200)
                )
            )
            is DshMaintenanceAdmissionOutcome.Rejected -> return DshMaintenanceOutcome.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.ADMISSION_REJECTED, acquired.reason)
            )
        }
        try {
            if (!request.quiescence.satisfiesMaintenance) {
                return DshMaintenanceOutcome.Rejected(
                    DshMaintenanceFailure(
                        DshMaintenanceError.QUIESCENCE_UNPROVEN,
                        "managed host is not proven stopped and the online barrier did not cover every mutator",
                        retryable = true,
                        detail = request.quiescence.detail.take(200)
                    )
                )
            }
            if (!request.targetRoot.isAbsolute) {
                return DshMaintenanceOutcome.Rejected(
                    DshMaintenanceFailure(DshMaintenanceError.INTERNAL, "target root must be absolute")
                )
            }
            val validation = validate(request)
            if (validation != null) return DshMaintenanceOutcome.Rejected(validation)
            if (request.requiredFreeBytes > 0L &&
                DshMaintenanceIo.usableBytes(request.targetRoot) < request.requiredFreeBytes
            ) {
                return DshMaintenanceOutcome.Rejected(
                    DshMaintenanceFailure(
                        DshMaintenanceError.SPACE_INSUFFICIENT,
                        "not enough free space for the operation and its restore point",
                        retryable = true,
                        detail = "required=${
                            request.requiredFreeBytes
                        } available=${DshMaintenanceIo.usableBytes(request.targetRoot)}"
                    )
                )
            }
            return runTransaction(request)
        } catch (crash: DshSimulatedCrash) {
            // Simulated process death: no rollback, no lease bookkeeping. The next engine
            // instance must recover from the journal exactly as a real process restart would.
            throw crash
        } finally {
            runCatching { lease.release() }
        }
    }

    /**
     * Recovers every non-terminal journal entry. Must run on the single app startup thread
     * before the managed host is started; it never starts Node and never guesses a writer.
     */
    fun recoverOnStart(): List<DshMaintenanceRecoveryResult> =
        journal.pending().mapNotNull { state -> recoverOne(state) }

    /**
     * Creates a unique, marked work directory below `dataRoot/maintenance/work`. Only directories
     * carrying [DshMaintenanceIo.STAGING_MARKER] are ever cleaned by [cleanupAged].
     */
    fun newWorkDirectory(label: String): File {
        val safe = label.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifEmpty { "work" }
        val directory = File(journal.workRoot, "$safe-${transactionIdSource()}")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create the maintenance work directory")
        }
        DshMaintenanceIo.writeDurably(File(directory, DshMaintenanceIo.STAGING_MARKER), safe)
        return directory
    }

    /** Latest states for the console projection (read-only). */
    fun recentStates(limit: Int = 10): List<DshJournalState> = journal.allOrEmpty().take(limit)

    /**
     * Creates a unique restore-point directory below `dataRoot/maintenance/restore-points`.
     * Responsibility-specific policies copy exactly the originals they are about to replace and
     * pass the directory as [DshMaintenanceRequest.restorePoint].
     */
    fun createRestorePointDirectory(label: String): File {
        val safe = label.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifEmpty { "point" }
        val directory = File(journal.restorePointRoot, "${clock()}-$safe")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create the restore-point directory")
        }
        return directory
    }

    /** Bounded cleanup of module-owned staging/quarantine and old journals. Never touches user data. */
    fun cleanupAged(nowMillis: Long = clock()): Int {
        var removed = 0
        val workDirs = journal.workRoot.listFiles() ?: emptyArray()
        val terminal = journal.allOrEmpty().associateBy { it.transactionId }
        for (directory in workDirs) {
            if (!directory.isDirectory) continue
            val marker = File(directory, DshMaintenanceIo.STAGING_MARKER)
            if (!marker.isFile) continue
            val state = terminal[directory.name]
            val referenceMillis = state?.atMillis ?: marker.lastModified()
            if (state != null && !state.phase.isTerminal) continue
            if (nowMillis - referenceMillis > retentionMillis) {
                if (DshMaintenanceIo.deleteOwnedTree(directory)) removed++
            }
        }
        val journalCutoff = nowMillis - JOURNAL_RETENTION_MILLIS
        for (state in terminal.values) {
            if (state.phase.isTerminal && state.atMillis < journalCutoff && journal.deleteJournal(state.transactionId)) {
                removed++
            }
        }
        return removed
    }

    // ---------------------------------------------------------------- transaction body

    private fun runTransaction(request: DshMaintenanceRequest): DshMaintenanceOutcome {
        val transactionId = transactionIdSource()
        val workDir = File(journal.workRoot, transactionId)
        val quarantineDir = File(workDir, "quarantine")
        if (!quarantineDir.isDirectory && !quarantineDir.mkdirs() && !quarantineDir.isDirectory) {
            return DshMaintenanceOutcome.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create transaction work directory")
            )
        }
        DshMaintenanceIo.writeDurably(File(workDir, DshMaintenanceIo.STAGING_MARKER), transactionId)
        val targetRoot = request.targetRoot.canonicalFile
        val records = request.steps.mapIndexed { index, step ->
            DshJournalStepRecord(
                index = index,
                action = step.action,
                targetPath = step.target.absolutePath,
                stagingPath = step.staging?.absolutePath,
                expectedSha256 = step.expectedSha256,
                backupPath = if (step.action == DshStepAction.RELOCATE) {
                    ""
                } else {
                    File(quarantineDir, "$index-${step.target.name}").absolutePath
                },
                hadTarget = step.target.exists(),
                applied = false,
                destinationPath = step.destination?.absolutePath
            )
        }
        var state = DshJournalState(
            seq = 1,
            transactionId = transactionId,
            kind = request.kind,
            phase = DshMaintenancePhase.PREPARED,
            atMillis = clock(),
            owner = request.requester.owner,
            operationId = request.requester.operationId,
            admissionProof = "owner=${request.requester.owner};${request.quiescence.detail}".take(300),
            targetRoot = targetRoot.absolutePath,
            restorePoint = request.restorePoint?.absolutePath,
            steps = records,
            detail = request.detail.take(300)
        )
        journal.append(state)
        crashHook.onBoundary(DshMaintenanceBoundary(DshMaintenanceBoundary.PREPARED, transactionId))

        state = state.copy(seq = state.seq + 1, phase = DshMaintenancePhase.QUIESCED, atMillis = clock())
        journal.append(state)
        if (request.restorePoint != null) {
            state = state.copy(seq = state.seq + 1, phase = DshMaintenancePhase.SNAPSHOT_READY, atMillis = clock())
            journal.append(state)
            crashHook.onBoundary(DshMaintenanceBoundary(DshMaintenanceBoundary.SNAPSHOT_READY, transactionId))
        }
        state = state.copy(seq = state.seq + 1, phase = DshMaintenancePhase.APPLYING, atMillis = clock())
        journal.append(state)
        crashHook.onBoundary(DshMaintenanceBoundary(DshMaintenanceBoundary.APPLYING, transactionId))

        try {
            for (record in records) {
                applyStep(record, quarantineDir)
                state = state.copy(
                    seq = state.seq + 1,
                    atMillis = clock(),
                    steps = state.steps.map { if (it.index == record.index) it.copy(applied = true) else it }
                )
                journal.append(state)
                crashHook.onBoundary(
                    DshMaintenanceBoundary(DshMaintenanceBoundary.STEP_APPLIED, transactionId, record.index)
                )
            }
            if (request.healthCheckRequired) {
                state = state.copy(seq = state.seq + 1, phase = DshMaintenancePhase.HEALTH_PENDING, atMillis = clock())
                journal.append(state)
                crashHook.onBoundary(DshMaintenanceBoundary(DshMaintenanceBoundary.HEALTH_PENDING, transactionId))
                val health = probe.verify(request.kind, targetRoot, transactionId)
                if (!health.healthy) {
                    return rollbackAfterFailure(
                        state,
                        DshMaintenanceFailure(
                            DshMaintenanceError.HEALTH_FAILED,
                            "official reader/host did not accept the committed data",
                            detail = health.detail.take(200)
                        ),
                        quarantineDir
                    )
                }
            }
            if (request.body != null) {
                val bodyFailure = request.body.invoke()
                if (bodyFailure != null) {
                    if (records.isEmpty()) {
                        journal.append(
                            state.copy(
                                seq = state.seq + 1,
                                phase = DshMaintenancePhase.ABORTED,
                                atMillis = clock(),
                                detail = "body failed: ${bodyFailure.code.name}".take(300)
                            )
                        )
                        return DshMaintenanceOutcome.Rejected(bodyFailure, transactionId)
                    }
                    return rollbackAfterFailure(state, bodyFailure, quarantineDir)
                }
            }
            state = state.copy(seq = state.seq + 1, phase = DshMaintenancePhase.COMMITTED, atMillis = clock())
            journal.append(state)
            crashHook.onBoundary(DshMaintenanceBoundary(DshMaintenanceBoundary.COMMITTED, transactionId))
            return DshMaintenanceOutcome.Committed(transactionId, journal.journalFile(transactionId).path, clock())
        } catch (crash: DshSimulatedCrash) {
            throw crash
        } catch (failure: IOException) {
            return rollbackAfterFailure(
                state,
                DshMaintenanceFailure(
                    DshMaintenanceError.IO_FAILURE,
                    "maintenance step failed; original data restored",
                    detail = failure.message?.take(200)
                ),
                quarantineDir
            )
        } catch (failure: Exception) {
            return rollbackAfterFailure(
                state,
                DshMaintenanceFailure(
                    DshMaintenanceError.INTERNAL,
                    "maintenance step failed unexpectedly; original data restored",
                    detail = failure.javaClass.simpleName.take(80)
                ),
                quarantineDir
            )
        }
    }

    private fun applyStep(record: DshJournalStepRecord, quarantineDir: File) {
        val target = File(record.targetPath)
        if (record.action == DshStepAction.RELOCATE) {
            val destination = record.destinationPath?.let(::File)
                ?: throw IOException("relocate step has no destination")
            if (destination.exists()) throw IOException("relocate destination already exists")
            DshMaintenanceIo.moveDurably(target, destination)
            DshMaintenanceIo.forceDir(destination.parentFile)
            DshMaintenanceIo.forceDir(target.parentFile)
            return
        }
        val staged = record.stagingPath?.let(::File)
        if (record.action != DshStepAction.MOVE_ASIDE) {
            if (staged == null || !staged.exists()) throw IOException("staged content is missing")
            if (record.expectedSha256 != null && staged.isFile) {
                val actual = DshMaintenanceIo.sha256(staged)
                if (!actual.equals(record.expectedSha256, ignoreCase = true)) {
                    throw IOException("staged content hash mismatch")
                }
            }
        }
        if (target.exists()) {
            DshMaintenanceIo.moveDurably(target, File(record.backupPath))
        } else if (record.action == DshStepAction.MOVE_ASIDE && !record.hadTarget) {
            // Nothing to quarantine; callers that need the target to exist validate it beforehand.
        }
        when (record.action) {
            DshStepAction.INSTALL_FILE, DshStepAction.INSTALL_TREE -> {
                DshMaintenanceIo.moveDurably(staged!!, target)
            }
            DshStepAction.MOVE_ASIDE -> Unit
            DshStepAction.RELOCATE -> Unit
        }
        DshMaintenanceIo.forceDir(target.parentFile)
        DshMaintenanceIo.forceDir(quarantineDir)
    }

    private fun rollbackAfterFailure(
        state: DshJournalState,
        failure: DshMaintenanceFailure,
        quarantineDir: File
    ): DshMaintenanceOutcome {
        val targetRoot = File(state.targetRoot)
        val restored = rollbackSteps(state.steps, targetRoot, quarantineDir, state.transactionId)
        return if (restored) {
            val terminal = state.copy(
                seq = state.seq + 1,
                phase = DshMaintenancePhase.ROLLED_BACK,
                atMillis = clock(),
                detail = "${failure.code.name}: ${failure.message}".take(300)
            )
            journal.append(terminal)
            crashHook.onBoundary(DshMaintenanceBoundary(DshMaintenanceBoundary.ROLLED_BACK, state.transactionId))
            DshMaintenanceOutcome.RolledBack(state.transactionId, failure, journal.journalFile(state.transactionId).path)
        } else {
            val required = state.copy(
                seq = state.seq + 1,
                phase = DshMaintenancePhase.RECOVERY_REQUIRED,
                atMillis = clock(),
                detail = "rollback incomplete after ${failure.code.name}".take(300)
            )
            journal.append(required)
            crashHook.onBoundary(
                DshMaintenanceBoundary(DshMaintenanceBoundary.RECOVERY_REQUIRED, state.transactionId)
            )
            DshMaintenanceOutcome.RecoveryRequired(
                state.transactionId,
                failure.copy(
                    code = DshMaintenanceError.RECOVERY_REQUIRED,
                    message = "automatic rollback could not restore every item; data preserved for rescue"
                ),
                journal.journalFile(state.transactionId).path
            )
        }
    }

    // ---------------------------------------------------------------- recovery

    private fun recoverOne(state: DshJournalState): DshMaintenanceRecoveryResult? {
        val targetRoot = File(state.targetRoot)
        val quarantineDir = File(File(journal.workRoot, state.transactionId), "quarantine")
        return when (state.phase) {
            DshMaintenancePhase.PREPARED,
            DshMaintenancePhase.QUIESCED,
            DshMaintenancePhase.SNAPSHOT_READY -> {
                journal.append(
                    state.copy(
                        seq = state.seq + 1,
                        phase = DshMaintenancePhase.ABORTED,
                        atMillis = clock(),
                        detail = "recovered: aborted before apply"
                    )
                )
                DshMaintenanceRecoveryResult.Aborted(
                    state.transactionId,
                    state.kind,
                    "no data had been applied"
                )
            }

            DshMaintenancePhase.APPLYING -> {
                if (rollbackSteps(state.steps, targetRoot, quarantineDir, state.transactionId)) {
                    journal.append(
                        state.copy(
                            seq = state.seq + 1,
                            phase = DshMaintenancePhase.ROLLED_BACK,
                            atMillis = clock(),
                            detail = "recovered: rolled back interrupted apply"
                        )
                    )
                    DshMaintenanceRecoveryResult.RolledBack(
                        state.transactionId,
                        state.kind,
                        "original data restored"
                    )
                } else {
                    requiresAttention(state, "interrupted apply could not be rolled back")
                }
            }

            DshMaintenancePhase.HEALTH_PENDING -> {
                val health = probe.verify(state.kind, targetRoot, state.transactionId)
                if (health.healthy) {
                    journal.append(
                        state.copy(
                            seq = state.seq + 1,
                            phase = DshMaintenancePhase.COMMITTED,
                            atMillis = clock(),
                            detail = "recovered: health confirmed after restart"
                        )
                    )
                    DshMaintenanceRecoveryResult.Completed(
                        state.transactionId,
                        state.kind,
                        "health probe passed on restart"
                    )
                } else if (rollbackSteps(state.steps, targetRoot, quarantineDir, state.transactionId)) {
                    journal.append(
                        state.copy(
                            seq = state.seq + 1,
                            phase = DshMaintenancePhase.ROLLED_BACK,
                            atMillis = clock(),
                            detail = "recovered: health failed, original restored"
                        )
                    )
                    DshMaintenanceRecoveryResult.RolledBack(
                        state.transactionId,
                        state.kind,
                        "health probe failed; original data restored"
                    )
                } else {
                    requiresAttention(state, "health failed and rollback was incomplete")
                }
            }

            DshMaintenancePhase.RECOVERY_REQUIRED -> requiresAttention(
                state,
                state.detail ?: "transaction requires the rescue surface"
            )

            DshMaintenancePhase.COMMITTED,
            DshMaintenancePhase.ABORTED,
            DshMaintenancePhase.ROLLED_BACK -> null
        }
    }

    private fun requiresAttention(state: DshJournalState, detail: String): DshMaintenanceRecoveryResult {
        if (state.phase != DshMaintenancePhase.RECOVERY_REQUIRED) {
            journal.append(
                state.copy(
                    seq = state.seq + 1,
                    phase = DshMaintenancePhase.RECOVERY_REQUIRED,
                    atMillis = clock(),
                    detail = detail.take(300)
                )
            )
        }
        return DshMaintenanceRecoveryResult.RequiresAttention(
            state.transactionId,
            state.kind,
            DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "maintenance transaction requires the rescue surface",
                detail = detail.take(200)
            )
        )
    }

    /**
     * Restores the pre-transaction state for every step, in reverse order. It only reads the
     * journal plus the quarantine: a step whose original was never moved is untouched, a step
     * whose original is in quarantine is restored, and content created by the failed install is
     * moved into the quarantine (`undo-*`) instead of being deleted.
     */
    private fun rollbackSteps(
        steps: List<DshJournalStepRecord>,
        targetRoot: File,
        quarantineDir: File,
        transactionId: String
    ): Boolean {
        var allRestored = true
        for (record in steps.sortedByDescending { it.index }) {
            val target = File(record.targetPath)
            if (DshMaintenanceIo.relativeWithin(targetRoot, target) == null) {
                allRestored = false
                continue
            }
            try {
                if (record.action == DshStepAction.RELOCATE) {
                    val destination = record.destinationPath?.let(::File)
                    if (destination == null) {
                        allRestored = false
                        continue
                    }
                    if (destination.exists()) {
                        if (target.exists()) {
                            DshMaintenanceIo.moveDurably(
                                target,
                                File(quarantineDir, "undo-${record.index}-${target.name}")
                            )
                        }
                        DshMaintenanceIo.moveDurably(destination, target)
                    }
                    continue
                }
                val backup = File(record.backupPath)
                if (record.hadTarget) {
                    if (backup.exists()) {
                        if (target.exists()) {
                            val undo = File(quarantineDir, "undo-${record.index}-${target.name}")
                            DshMaintenanceIo.moveDurably(target, undo)
                        }
                        DshMaintenanceIo.moveDurably(backup, target)
                    }
                } else if (target.exists()) {
                    val undo = File(quarantineDir, "undo-${record.index}-${target.name}")
                    DshMaintenanceIo.moveDurably(target, undo)
                }
            } catch (_: IOException) {
                allRestored = false
            }
        }
        if (!allRestored) {
            // Best effort: keep the quarantine for the rescue surface, never delete evidence.
            runCatching { DshMaintenanceIo.forceDir(quarantineDir) }
        }
        return allRestored
    }

    // ---------------------------------------------------------------- validation

    private fun validate(request: DshMaintenanceRequest): DshMaintenanceFailure? {
        val targetRoot = request.targetRoot
        val seen = HashSet<String>()
        for ((index, step) in request.steps.withIndex()) {
            val targetRelative = DshMaintenanceIo.relativeWithin(targetRoot, step.target)
                ?: return DshMaintenanceFailure(
                    DshMaintenanceError.PATH_ESCAPE,
                    "step $index target is outside the declared root"
                )
            if (targetRelative.isEmpty()) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.PATH_ESCAPE,
                    "step $index target is the root itself"
                )
            }
            if (!seen.add(targetRelative)) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.INTERNAL,
                    "step $index repeats target $targetRelative"
                )
            }
            if (step.action == DshStepAction.MOVE_ASIDE) {
                if (!step.target.exists() && !step.optionalTarget) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.STAGING_MISSING,
                        "step $index target does not exist"
                    )
                }
                continue
            }
            if (step.action == DshStepAction.RELOCATE) {
                if (!step.target.exists()) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.STAGING_MISSING,
                        "step $index relocate target does not exist"
                    )
                }
                val destination = step.destination
                    ?: return DshMaintenanceFailure(
                        DshMaintenanceError.INTERNAL,
                        "step $index relocate has no destination"
                    )
                if (!destination.isAbsolute) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.PATH_ESCAPE,
                        "step $index relocate destination must be absolute"
                    )
                }
                val insideManagedRoot = DshMaintenanceIo.relativeWithin(dataRoot, destination) != null ||
                    DshMaintenanceIo.relativeWithin(targetRoot, destination) != null
                if (!insideManagedRoot) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.PATH_ESCAPE,
                        "step $index relocate destination is outside the managed roots"
                    )
                }
                if (destination.exists()) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.RESTORE_CONFLICT,
                        "step $index relocate destination already exists"
                    )
                }
                val destinationKey = "dest:" + (
                    DshMaintenanceIo.relativeWithin(dataRoot, destination)
                        ?: DshMaintenanceIo.relativeWithin(targetRoot, destination)
                        ?: destination.absolutePath
                    )
                if (!seen.add(destinationKey) ||
                    DshMaintenanceIo.relativeWithin(destination, step.target) != null
                ) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.INTERNAL,
                        "step $index relocate destination overlaps another step"
                    )
                }
                continue
            }
            val staging = step.staging
                ?: return DshMaintenanceFailure(
                    DshMaintenanceError.STAGING_MISSING,
                    "step $index has no staging path"
                )
            if (DshMaintenanceIo.relativeWithin(dataRoot, staging) == null) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.PATH_ESCAPE,
                    "step $index staging is outside the managed data root"
                )
            }
            if (!staging.exists()) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.STAGING_MISSING,
                    "step $index staged content is missing"
                )
            }
            when (step.action) {
                DshStepAction.INSTALL_FILE -> {
                    if (!staging.isFile) {
                        return DshMaintenanceFailure(
                            DshMaintenanceError.STAGING_MISSING,
                            "step $index expects a staged file"
                        )
                    }
                    if (step.expectedSha256 != null && !step.expectedSha256.equals(
                            DshMaintenanceIo.sha256(staging),
                            ignoreCase = true
                        )
                    ) {
                        return DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "step $index staged hash does not match the declared hash"
                        )
                    }
                }
                DshStepAction.INSTALL_TREE -> {
                    if (!staging.isDirectory) {
                        return DshMaintenanceFailure(
                            DshMaintenanceError.STAGING_MISSING,
                            "step $index expects a staged directory"
                        )
                    }
                }
                DshStepAction.MOVE_ASIDE -> Unit
                DshStepAction.RELOCATE -> Unit
            }
            if (DshMaintenanceIo.relativeWithin(staging, step.target) != null ||
                DshMaintenanceIo.relativeWithin(step.target, staging) != null
            ) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.INTERNAL,
                    "step $index staging and target overlap"
                )
            }
        }
        return null
    }

    private companion object {
        /** How long a committed/rolled-back quarantine and its journal are kept for rescue. */
        const val DEFAULT_RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
        const val JOURNAL_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
