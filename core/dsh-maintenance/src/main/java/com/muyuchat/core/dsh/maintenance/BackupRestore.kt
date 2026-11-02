package com.muyuchat.core.dsh.maintenance

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** What a restore should produce. Built by the console/SAF flow, never by DSH itself. */
class DshRestoreRequest(
    val archive: File,
    /** Real home being (re)populated; may be a brand-new directory. */
    val homeTarget: File,
    /** Workspace target; required only when the archive contains `workspace/` entries. */
    val workspaceTarget: File? = null,
    /** Common ancestor of every target; both targets must live below it. */
    val targetRoot: File,
    val requester: DshMaintenanceRequester,
    val quiescence: DshQuiescenceEvidence,
    /** Default false: an existing different file is a visible conflict, never overwritten silently. */
    val overwriteExisting: Boolean = false,
    val admissionWaitMillis: Long = 0L
)

sealed interface DshBackupRestoreResult {
    data class Restored(
        val transactionId: String,
        val manifest: DshBackupManifest,
        val restoredEntries: Int,
        val skippedIdenticalEntries: Int,
        val restorePoint: File?
    ) : DshBackupRestoreResult

    data class Rejected(val failure: DshMaintenanceFailure) : DshBackupRestoreResult

    data class RolledBack(
        val transactionId: String,
        val failure: DshMaintenanceFailure
    ) : DshBackupRestoreResult

    data class RecoveryRequired(
        val transactionId: String,
        val failure: DshMaintenanceFailure
    ) : DshBackupRestoreResult
}

/**
 * Transactional restore (D22b).
 *
 * Order: validate the archive (hash/version/zip-slip/zip-bomb) → extract into a marked staging
 * directory while re-verifying every hash → detect conflicts → save a restore point of exactly
 * the originals that will be replaced → hand the steps to the single maintenance engine →
 * official-reader health probe → commit. A failure at any point leaves the target untouched or
 * restores it from the transaction quarantine; user data is never deleted.
 */
class DshBackupRestorer(
    private val engine: DshMaintenanceEngine,
    private val validator: DshBackupValidator,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun restore(request: DshRestoreRequest, cipher: DshBackupCipherPort? = null): DshBackupRestoreResult {
        if (!request.homeTarget.isAbsolute || !request.targetRoot.isAbsolute) {
            return DshBackupRestoreResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.INTERNAL, "restore targets must be absolute")
            )
        }
        if (DshMaintenanceIo.relativeWithin(request.targetRoot, request.homeTarget) == null) {
            return DshBackupRestoreResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.PATH_ESCAPE, "home target is outside the restore root")
            )
        }
        request.workspaceTarget?.let {
            if (DshMaintenanceIo.relativeWithin(request.targetRoot, it) == null) {
                return DshBackupRestoreResult.Rejected(
                    DshMaintenanceFailure(DshMaintenanceError.PATH_ESCAPE, "workspace target is outside the restore root")
                )
            }
        }
        val workRoot = try {
            engine.newWorkDirectory("restore")
        } catch (io: IOException) {
            return DshBackupRestoreResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the restore staging directory")
            )
        }
        val staging = File(workRoot, "staging")
        val validationDir = File(workRoot, "validate")
        if (!staging.mkdirs() && !staging.isDirectory) {
            return DshBackupRestoreResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create restore staging")
            )
        }
        var keepForRescue = false
        try {
            val validation = validator.validate(request.archive, validationDir)
            if (validation is DshBackupValidation.Invalid) {
                return DshBackupRestoreResult.Rejected(validation.failure)
            }
            validation as DshBackupValidation.Valid
            val manifest = validation.manifest
            if (!request.homeTarget.isDirectory && !request.homeTarget.mkdirs() && !request.homeTarget.isDirectory) {
                return DshBackupRestoreResult.Rejected(
                    DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the restore home")
                )
            }
            val plan = mapEntries(manifest, request, staging)
            if (plan.failure != null) return DshBackupRestoreResult.Rejected(plan.failure)
            val extractFailure = extract(validation.plainArchive, manifest, staging)
            if (extractFailure != null) return DshBackupRestoreResult.Rejected(extractFailure)

            val existingIdentical = ArrayList<String>()
            val conflicts = ArrayList<File>()
            var conflictBytes = 0L
            for (target in plan.targets) {
                if (!target.file.exists()) continue
                if (target.file.isFile && DshMaintenanceIo.sha256(target.file) == target.entry.sha256) {
                    existingIdentical.add(target.entry.path)
                    continue
                }
                if (!request.overwriteExisting) {
                    return DshBackupRestoreResult.Rejected(
                        DshMaintenanceFailure(
                            DshMaintenanceError.RESTORE_CONFLICT,
                            "the restore target already holds different data; confirm overwrite to replace it",
                            detail = DshMaintenanceIo.relativeWithin(request.targetRoot, target.file)?.take(120)
                        )
                    )
                }
                conflicts.add(target.file)
                conflictBytes += DshMaintenanceIo.totalTreeBytes(target.file)
            }
            var restorePoint: File? = null
            if (conflicts.isNotEmpty()) {
                restorePoint = try {
                    saveRestorePoint(engine, request, conflicts, manifest)
                } catch (io: IOException) {
                    return DshBackupRestoreResult.Rejected(
                        DshMaintenanceFailure(
                            DshMaintenanceError.IO_FAILURE,
                            "could not save the restore point; nothing was changed",
                            detail = io.message?.take(200)
                        )
                    )
                }
            }
            val steps = plan.targets
                .filterNot { it.entry.path in existingIdentical }
                .map { target ->
                    DshMaintenanceStep(
                        action = DshStepAction.INSTALL_FILE,
                        target = target.file,
                        staging = File(staging, target.entry.path),
                        expectedSha256 = target.entry.sha256
                    )
                }
            val requiredFree = manifest.totalBytes + conflictBytes + MIN_HEADROOM_BYTES
            val outcome = engine.execute(
                DshMaintenanceRequest(
                    kind = DshMaintenanceKind.RESTORE,
                    requester = request.requester,
                    quiescence = request.quiescence,
                    targetRoot = request.targetRoot,
                    steps = steps,
                    restorePoint = restorePoint,
                    healthCheckRequired = true,
                    admissionWaitMillis = request.admissionWaitMillis,
                    requiredFreeBytes = requiredFree,
                    detail = "entries=${manifest.entries.size};overwrite=${request.overwriteExisting}"
                )
            )
            return when (outcome) {
                is DshMaintenanceOutcome.Committed -> DshBackupRestoreResult.Restored(
                    outcome.transactionId,
                    manifest,
                    steps.size,
                    existingIdentical.size,
                    restorePoint
                )
                is DshMaintenanceOutcome.RolledBack -> DshBackupRestoreResult.RolledBack(
                    outcome.transactionId,
                    outcome.failure
                )
                is DshMaintenanceOutcome.Rejected -> DshBackupRestoreResult.Rejected(outcome.failure)
                is DshMaintenanceOutcome.RecoveryRequired -> {
                    keepForRescue = true
                    DshBackupRestoreResult.RecoveryRequired(outcome.transactionId, outcome.failure)
                }
            }
        } finally {
            // Validation staging is module-owned and never user data. A transaction that entered
            // RECOVERY_REQUIRED keeps its staging for the rescue surface.
            runCatching { DshMaintenanceIo.deleteOwnedTree(validationDir) }
            if (!keepForRescue && workRoot.isDirectory &&
                File(workRoot, DshMaintenanceIo.STAGING_MARKER).isFile
            ) {
                runCatching { DshMaintenanceIo.deleteOwnedTree(workRoot) }
            }
        }
    }

    private data class PlannedTarget(
        val entry: DshBackupEntry,
        val file: File
    )

    private data class EntryPlan(
        val targets: List<PlannedTarget>,
        val failure: DshMaintenanceFailure? = null
    )

    private fun mapEntries(
        manifest: DshBackupManifest,
        request: DshRestoreRequest,
        staging: File
    ): EntryPlan {
        val targets = ArrayList<PlannedTarget>(manifest.entries.size)
        for (entry in manifest.entries) {
            val relative = when {
                entry.path.startsWith("$HOME_PREFIX/") -> entry.path.removePrefix("$HOME_PREFIX/")
                entry.path.startsWith("$WORKSPACE_PREFIX/") -> entry.path.removePrefix("$WORKSPACE_PREFIX/")
                else -> return EntryPlan(
                    emptyList(),
                    DshMaintenanceFailure(
                        DshMaintenanceError.ARCHIVE_INVALID,
                        "archive contains a root this restore does not understand"
                    )
                )
            }
            if (!DshMaintenanceIo.isSafeRelativePath(relative)) {
                return EntryPlan(
                    emptyList(),
                    DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_MALICIOUS, "archive entry escapes its root")
                )
            }
            val target = when {
                entry.path.startsWith("$HOME_PREFIX/") -> File(request.homeTarget, relative)
                else -> {
                    val workspace = request.workspaceTarget ?: return EntryPlan(
                        emptyList(),
                        DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "archive contains workspace data but no workspace target was supplied"
                        )
                    )
                    File(workspace, relative)
                }
            }
            if (DshMaintenanceIo.relativeWithin(request.targetRoot, target) == null) {
                return EntryPlan(
                    emptyList(),
                    DshMaintenanceFailure(DshMaintenanceError.PATH_ESCAPE, "restore target escapes the restore root")
                )
            }
            if (File(staging, entry.path).let { DshMaintenanceIo.relativeWithin(staging, it) == null }) {
                return EntryPlan(
                    emptyList(),
                    DshMaintenanceFailure(DshMaintenanceError.PATH_ESCAPE, "staging path escapes the staging root")
                )
            }
            targets.add(PlannedTarget(entry, target))
        }
        return EntryPlan(targets)
    }

    private fun extract(plainArchive: File, manifest: DshBackupManifest, staging: File): DshMaintenanceFailure? {
        val declared = manifest.entries.associateBy { it.path }
        val seen = HashSet<String>()
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(plainArchive))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_ENTRY_NAME) {
                        entry = zip.nextEntry
                        continue
                    }
                    val normalized = DshMaintenanceIo.normalizeArchiveEntry(entry.name, directory = false)
                        ?: return DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_MALICIOUS,
                            "archive entry path escapes the staging root"
                        )
                    val expected = declared[normalized] ?: return DshMaintenanceFailure(
                        DshMaintenanceError.ARCHIVE_INVALID,
                        "archive contains an undeclared entry"
                    )
                    if (!seen.add(normalized)) {
                        return DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "archive repeats an entry")
                    }
                    val out = DshMaintenanceIo.resolveWithin(staging, normalized) ?: return DshMaintenanceFailure(
                        DshMaintenanceError.ARCHIVE_MALICIOUS,
                        "archive entry resolves outside the staging root"
                    )
                    out.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                            return DshMaintenanceFailure(
                                DshMaintenanceError.IO_FAILURE,
                                "cannot create the staging directory"
                            )
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            written += read
                            if (written > expected.bytes) {
                                return DshMaintenanceFailure(
                                    DshMaintenanceError.ARCHIVE_INVALID,
                                    "archive entry is larger than its manifest declares"
                                )
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                    if (written != expected.bytes || digest.digest().toHexString() != expected.sha256) {
                        return DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "extracted entry does not match its manifest hash/size"
                        )
                    }
                    entry = zip.nextEntry
                }
            }
            if (seen.size != declared.size) {
                DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "archive is missing declared entries")
            } else {
                null
            }
        } catch (io: IOException) {
            DshMaintenanceFailure(
                DshMaintenanceError.ARCHIVE_INVALID,
                "archive could not be extracted",
                detail = io.message?.take(200)
            )
        }
    }

    private fun saveRestorePoint(
        engine: DshMaintenanceEngine,
        request: DshRestoreRequest,
        conflicts: List<File>,
        manifest: DshBackupManifest
    ): File {
        val point = engine.createRestorePointDirectory("restore")
        for (conflict in conflicts) {
            val relative = DshMaintenanceIo.relativeWithin(request.targetRoot, conflict)
                ?: throw IOException("conflict target escapes the restore root")
            DshMaintenanceIo.copyTreeDurably(conflict, File(point, relative))
        }
        val note = org.json.JSONObject().apply {
            put("archiveCreatedAt", manifest.createdAtMillis)
            put("entries", manifest.entries.size)
            put("conflicts", conflicts.size)
            put("createdAt", clock())
        }.toString()
        DshMaintenanceIo.writeDurably(File(point, "restore-point.json"), note)
        return point
    }

    private companion object {
        const val HOME_PREFIX = "home"
        const val WORKSPACE_PREFIX = "workspace"
        const val COPY_BUFFER = 64 * 1024
        const val MIN_HEADROOM_BYTES = 16L * 1024L * 1024L
    }
}

private fun ByteArray.toHexString(): String {
    val chars = "0123456789abcdef".toCharArray()
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        out[i * 2] = chars[value ushr 4]
        out[i * 2 + 1] = chars[value and 0x0f]
    }
    return String(out)
}

sealed interface DshBackupCreateResult {
    data class Created(
        val archive: File,
        val manifest: DshBackupManifest,
        val archiveBytes: Long,
        val archiveSha256: String,
        val transactionId: String
    ) : DshBackupCreateResult

    data class Rejected(val failure: DshMaintenanceFailure) : DshBackupCreateResult
}

/**
 * Facade that runs backup creation and restore through the same maintenance lease. The archive
 * is streamed inside the lease (`body`) so a concurrent maintenance command cannot change the
 * inventory while it is being written.
 */
class DshBackupService(
    private val engine: DshMaintenanceEngine,
    private val planner: DshBackupPlanner = DshBackupPlanner(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun createBackup(
        sources: List<DshBackupSource>,
        output: File,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        closureId: String? = null,
        encryption: DshBackupEncryption = DshBackupEncryption.NONE,
        cipher: DshBackupCipherPort? = null,
        admissionWaitMillis: Long = 0L
    ): DshBackupCreateResult {
        val selection = planner.plan(sources)
        selection.failure?.let { return DshBackupCreateResult.Rejected(it) }
        var created: DshBackupWriteResult.Created? = null
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.BACKUP,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = emptyList(),
                healthCheckRequired = false,
                admissionWaitMillis = admissionWaitMillis,
                detail = "entries=${selection.entries.size};bytes=${selection.totalBytes}",
                body = {
                    val result = DshBackupWriter(clock, planner.limits, cipher).write(
                        selection = selection,
                        output = output,
                        closureId = closureId,
                        encryption = encryption,
                        note = "consistent backup under the maintenance lease"
                    )
                    when (result) {
                        is DshBackupWriteResult.Created -> {
                            created = result
                            null
                        }
                        is DshBackupWriteResult.Failed -> result.failure
                    }
                }
            )
        )
        val result = created
        return when {
            result != null && outcome is DshMaintenanceOutcome.Committed -> DshBackupCreateResult.Created(
                result.archive,
                result.manifest,
                result.archiveBytes,
                result.archiveSha256,
                outcome.transactionId
            )
            outcome is DshMaintenanceOutcome.Rejected -> DshBackupCreateResult.Rejected(outcome.failure)
            outcome is DshMaintenanceOutcome.RolledBack -> DshBackupCreateResult.Rejected(outcome.failure)
            outcome is DshMaintenanceOutcome.RecoveryRequired -> DshBackupCreateResult.Rejected(outcome.failure)
            else -> DshBackupCreateResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.INTERNAL, "backup transaction did not commit")
            )
        }
    }

    fun restore(
        request: DshRestoreRequest,
        cipher: DshBackupCipherPort? = null
    ): DshBackupRestoreResult =
        DshBackupRestorer(engine, DshBackupValidator(cipher = cipher), clock).restore(request, cipher)
}
