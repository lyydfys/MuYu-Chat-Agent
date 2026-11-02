package com.muyuchat.core.dsh.maintenance

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Session recycle bin (D21).
 *
 * Trash is a same-volume transaction rename out of `sessions/` into `sessions-deleted/` (the
 * kernel does not scan that tree), with an `.origin` record of the original slug and directory
 * name. The session id and every internal association are preserved because the directory is
 * moved, never rewritten. Permanent deletion exists only for a recycle-bin entry and only with
 * an explicit confirmation flag. Restore is idempotent and a same-name destination is a visible
 * conflict instead of an overwrite.
 */
data class DshTrashEntry(
    val entryName: String,
    val slug: String,
    val dirName: String,
    val removedAtMillis: Long,
    val bytes: Long,
    val directory: File
)

sealed interface DshSessionRecycleResult {
    data class Trashed(val entry: DshTrashEntry, val transactionId: String) : DshSessionRecycleResult
    data class Restored(val destination: File, val transactionId: String) : DshSessionRecycleResult
    data class AlreadyRestored(val destination: File) : DshSessionRecycleResult
    data class Purged(val bytes: Long) : DshSessionRecycleResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshSessionRecycleResult
    data class RolledBack(val transactionId: String, val failure: DshMaintenanceFailure) : DshSessionRecycleResult
    data class RecoveryRequired(val transactionId: String, val failure: DshMaintenanceFailure) : DshSessionRecycleResult
}

class DshSessionRecycler(
    private val engine: DshMaintenanceEngine,
    private val layout: DshHomeLayout,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun listTrash(): List<DshTrashEntry> {
        val entries = ArrayList<DshTrashEntry>()
        val dirs = layout.trashDir.listFiles() ?: return emptyList()
        for (directory in dirs) {
            if (!directory.isDirectory) continue
            val origin = readOrigin(directory)
            val fallback = parseEntryName(directory.name)
            entries.add(
                DshTrashEntry(
                    entryName = directory.name,
                    slug = origin?.optString("slug")?.takeIf { it.isNotEmpty() } ?: fallback?.first ?: "",
                    dirName = origin?.optString("dirName")?.takeIf { it.isNotEmpty() } ?: fallback?.second ?: "",
                    removedAtMillis = origin?.optString("at")?.let(::parseMillis) ?: directory.lastModified(),
                    bytes = DshMaintenanceIo.totalTreeBytes(directory),
                    directory = directory
                )
            )
        }
        return entries.sortedByDescending { it.removedAtMillis }
    }

    fun trash(
        session: DshSessionRef,
        running: Boolean,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        admissionWaitMillis: Long = 0L
    ): DshSessionRecycleResult {
        if (running) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.SESSION_RUNNING,
                    "the session is running; settle it or refuse maintenance first"
                )
            )
        }
        if (!session.directory.isDirectory) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SESSION_NOT_FOUND, "session directory is absent")
            )
        }
        if (DshMaintenanceIo.relativeWithin(layout.sessionsDir, session.directory) == null) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.PATH_ESCAPE, "session directory escapes the sessions root")
            )
        }
        val stamp = STAMP_FORMAT.format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()))
        var entryName = "$stamp-${session.dirName}"
        var suffix = 1
        while (File(layout.trashDir, entryName).exists()) {
            entryName = "$stamp-${session.dirName}-${suffix++}"
        }
        val destination = File(layout.trashDir, entryName)
        if (!layout.trashDir.isDirectory && !layout.trashDir.mkdirs() && !layout.trashDir.isDirectory) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the recycle-bin directory")
            )
        }
        val originText = JSONObject().apply {
            put("slug", session.slug)
            put("dirName", session.dirName)
            put("at", Instant.ofEpochMilli(clock()).toString())
            put("sessionId", session.sessionId)
        }.toString()
        val originStaging = try {
            val work = engine.newWorkDirectory("session-trash")
            File(work, "origin.json").also { DshMaintenanceIo.writeDurably(it, originText) }
        } catch (io: Exception) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot stage the recycle-bin origin record")
            )
        }
        val originTarget = File(destination, ".origin")
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SESSION_RECYCLE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = layout.homeDir,
                steps = listOf(
                    DshMaintenanceStep(
                        action = DshStepAction.RELOCATE,
                        target = session.directory,
                        destination = destination
                    ),
                    DshMaintenanceStep(
                        action = DshStepAction.INSTALL_FILE,
                        target = originTarget,
                        staging = originStaging,
                        expectedSha256 = DshMaintenanceIo.sha256(originStaging)
                    )
                ),
                healthCheckRequired = true,
                admissionWaitMillis = admissionWaitMillis,
                detail = "trash=${session.sessionId}"
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshSessionRecycleResult.Trashed(
                DshTrashEntry(
                    entryName = entryName,
                    slug = session.slug,
                    dirName = session.dirName,
                    removedAtMillis = clock(),
                    bytes = DshMaintenanceIo.totalTreeBytes(destination),
                    directory = destination
                ),
                outcome.transactionId
            )

            is DshMaintenanceOutcome.RolledBack -> DshSessionRecycleResult.RolledBack(outcome.transactionId, outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshSessionRecycleResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshSessionRecycleResult.RecoveryRequired(
                outcome.transactionId,
                outcome.failure
            )
        }
    }

    fun restore(
        entry: DshTrashEntry,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        admissionWaitMillis: Long = 0L
    ): DshSessionRecycleResult {
        val destination = destinationFor(entry)
            ?: return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.RECOVERY_REQUIRED,
                    "recycle-bin entry has no safe original location"
                )
            )
        if (!entry.directory.isDirectory) {
            return if (destination.exists()) {
                DshSessionRecycleResult.AlreadyRestored(destination)
            } else {
                DshSessionRecycleResult.Rejected(
                    DshMaintenanceFailure(DshMaintenanceError.SESSION_NOT_FOUND, "recycle-bin entry is absent")
                )
            }
        }
        if (destination.exists()) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.RECYCLE_CONFLICT,
                    "a session with the same id already exists at the original location",
                    detail = DshMaintenanceIo.relativeWithin(layout.sessionsDir, destination)?.take(120)
                )
            )
        }
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SESSION_RECYCLE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = layout.homeDir,
                steps = listOf(
                    DshMaintenanceStep(
                        action = DshStepAction.RELOCATE,
                        target = entry.directory,
                        destination = destination
                    )
                ),
                healthCheckRequired = true,
                admissionWaitMillis = admissionWaitMillis,
                detail = "restore=${entry.entryName}",
                body = {
                    // Recycle-bin metadata is module-owned; the session content is untouched.
                    File(destination, ".origin").delete()
                    null
                }
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshSessionRecycleResult.Restored(destination, outcome.transactionId)
            is DshMaintenanceOutcome.RolledBack -> DshSessionRecycleResult.RolledBack(outcome.transactionId, outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshSessionRecycleResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshSessionRecycleResult.RecoveryRequired(
                outcome.transactionId,
                outcome.failure
            )
        }
    }

    /**
     * Permanent deletion, only for a recycle-bin entry and only with [permanentConfirmed]. The
     * recycle-bin entry is the only thing deleted; `sessions/` is never touched.
     */
    fun purge(
        entry: DshTrashEntry,
        permanentConfirmed: Boolean,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshSessionRecycleResult {
        if (!permanentConfirmed) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.PURGE_CONFIRMATION_REQUIRED,
                    "permanent deletion requires an explicit confirmation"
                )
            )
        }
        if (!entry.directory.isDirectory) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SESSION_NOT_FOUND, "recycle-bin entry is absent")
            )
        }
        if (DshMaintenanceIo.relativeWithin(layout.trashDir, entry.directory) == null) {
            return DshSessionRecycleResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.PATH_ESCAPE, "only recycle-bin entries can be purged")
            )
        }
        val bytes = DshMaintenanceIo.totalTreeBytes(entry.directory)
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SESSION_RECYCLE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = layout.homeDir,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "purge=${entry.entryName}",
                body = {
                    if (DshMaintenanceIo.deleteOwnedTree(entry.directory)) {
                        null
                    } else {
                        DshMaintenanceFailure(
                            DshMaintenanceError.IO_FAILURE,
                            "could not delete the recycle-bin entry"
                        )
                    }
                }
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshSessionRecycleResult.Purged(bytes)
            is DshMaintenanceOutcome.RolledBack -> DshSessionRecycleResult.RolledBack(outcome.transactionId, outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshSessionRecycleResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshSessionRecycleResult.RecoveryRequired(
                outcome.transactionId,
                outcome.failure
            )
        }
    }

    private fun destinationFor(entry: DshTrashEntry): File? {
        if (!isSingleSegment(entry.slug) || !isSingleSegment(entry.dirName)) return null
        val destination = File(File(layout.sessionsDir, entry.slug), entry.dirName)
        return if (DshMaintenanceIo.relativeWithin(layout.sessionsDir, destination) == null) null else destination
    }

    private fun isSingleSegment(value: String): Boolean =
        value.isNotEmpty() && value.length <= 128 && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' || it.code < 0x20 }

    private fun readOrigin(directory: File): JSONObject? {
        val file = File(directory, ".origin")
        if (!file.isFile) return null
        return runCatching { JSONObject(DshMaintenanceIo.readText(file)) }.getOrNull()
    }

    private fun parseEntryName(name: String): Pair<String, String>? {
        val match = ENTRY_NAME.matchEntire(name) ?: return null
        // Without the `.origin` record the original slug is unknown; the reference restores such
        // an entry under a dedicated slug instead of guessing. Same rule here.
        return RESTORED_SLUG to match.groupValues[1]
    }

    private fun parseMillis(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private companion object {
        val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd-HHmmss")
        val ENTRY_NAME = Regex("^\\d{6}-\\d{6}-(.+)$")
        const val RESTORED_SLUG = "--restored--"
    }
}
