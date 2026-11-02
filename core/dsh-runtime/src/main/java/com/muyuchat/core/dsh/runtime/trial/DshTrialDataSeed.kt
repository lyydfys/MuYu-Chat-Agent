package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * The data half of an isolated closure trial (D24).
 *
 * The trial cannot swap the payload tree in this build (the candidate payload/Node replacement is
 * not implemented), so what a trial actually proves is the *data* half: the verified installed
 * kernel boots against a private copy of the real committed session store and can still read it.
 * This object therefore copies **only** `home/sessions/<slug>/<session>/session*.jsonl.zstd` from
 * the production home into the trial home, before the trial host starts:
 *
 * - read-only on the source: the real home is never written, renamed or opened for append;
 * - credentials, attachments, workspace files and plugin-manager state are **never** copied, so a
 *   trial can neither authenticate nor replay a turn;
 * - every target path is contained in the trial home and a symbolic link is never followed;
 * - a per-file atomic copy (`<name>.mca-trial-copy` + rename) means a crash cannot leave a torn
 *   session log that the health proof would then reject;
 * - the copy is bounded (files and bytes). A file that does not fit the remaining budget is skipped
 *   whole, never truncated, and the result says how many were skipped.
 *
 * The previous trial's session copies are removed first (bounded to the trial home), so a repeated
 * trial always migrates the current production data instead of stacking stale copies.
 */
internal object DshTrialDataSeed {

    private const val SESSIONS_DIR = "sessions"
    private const val COPY_TEMP_SUFFIX = ".mca-trial-copy"
    private val SESSION_FILE_NAME = Regex("^session(\\.[A-Za-z0-9]+)?\\.jsonl\\.zstd$")

    const val DEFAULT_MAX_FILES = 128
    const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L

    data class Result(
        /** Committed session logs the production home offered; 0 means a new-user profile. */
        val sourceLogs: Int = 0,
        val copiedFiles: Int,
        val copiedBytes: Long,
        val skipped: Int,
        val detail: String
    ) {
        companion object {
            val NONE = Result(0, 0, 0L, 0, "data-half: no committed session log to copy")
        }
    }

    fun seed(
        productionHome: File,
        trialHome: File,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxBytes: Long = DEFAULT_MAX_BYTES
    ): Result {
        val target = File(trialHome, SESSIONS_DIR)
        if (!DshTrialRoots.isWithin(trialHome, target)) return Result.NONE
        // Reset our own previous copy: the trial proves the *current* data, never stale leftovers.
        deleteTree(target, allowedRoot = trialHome)
        val source = File(productionHome, SESSIONS_DIR)
        // A new user has no session to migrate. The trial must still prove that the official session
        // store can be initialized, so the isolated home gets a real (empty) store. This is a
        // positive observation, not a fabricated session: no log file is created.
        fun initializeEmptyStore(): Result {
            val created = runCatching { target.mkdirs() || target.isDirectory }.getOrDefault(false)
            return Result(
                sourceLogs = 0,
                copiedFiles = 0,
                copiedBytes = 0L,
                skipped = 0,
                detail = if (created) {
                    "data-half: new-user profile; isolated session store initialized empty"
                } else {
                    "data-half: the real home holds no committed session log and the isolated store could not be created"
                }
            )
        }
        if (!source.isDirectory) return initializeEmptyStore()
        val logs = runCatching { collect(sources = source) }.getOrElse {
            return Result(0, 0, 0L, 0, "data-half: the real session store cannot be listed")
        }
        if (logs.isEmpty()) return initializeEmptyStore()
        var copiedFiles = 0
        var copiedBytes = 0L
        var skipped = 0
        for (log in logs) {
            val size = log.length()
            if (size <= 0L || copiedFiles >= maxFiles || copiedBytes + size > maxBytes) {
                skipped += 1
                continue
            }
            val destination = File(target, relativeTo(source, log))
            if (!DshTrialRoots.isWithin(trialHome, destination)) {
                skipped += 1
                continue
            }
            if (!copyAtomically(log, destination)) {
                skipped += 1
                continue
            }
            copiedFiles += 1
            copiedBytes += size
        }
        val detail = buildString {
            append("data-half: copied ")
            append(copiedFiles)
            append(" session log(s) (")
            append(copiedBytes / 1024L)
            append(" KiB)")
            if (skipped > 0) {
                append(", ")
                append(skipped)
                append(" skipped by the copy budget")
            }
            append("; credentials and attachments are not copied")
        }
        return Result(logs.size, copiedFiles, copiedBytes, skipped, detail)
    }

    /** Session logs below `sessions/<slug>/<session>/`, newest first, symlinks never followed. */
    private fun collect(sources: File): List<File> {
        val slugs = sources.listFiles { file -> file.isDirectory } ?: return emptyList()
        val found = ArrayList<File>(8)
        for (slug in slugs.sortedBy { it.name }) {
            if (Files.isSymbolicLink(slug.toPath())) continue
            val sessions = slug.listFiles { file -> file.isDirectory } ?: continue
            for (session in sessions.sortedBy { it.name }) {
                if (Files.isSymbolicLink(session.toPath())) continue
                val logs = session.listFiles { file ->
                    file.isFile && !Files.isSymbolicLink(file.toPath()) && SESSION_FILE_NAME.matches(file.name)
                } ?: continue
                found.addAll(logs)
            }
        }
        return found.sortedByDescending { it.lastModified() }
    }

    private fun relativeTo(root: File, file: File): String =
        file.absoluteFile.path.removePrefix(root.absoluteFile.path).trimStart(File.separatorChar)

    private fun copyAtomically(source: File, destination: File): Boolean = runCatching {
        val parent = destination.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        val temp = File(parent, destination.name + COPY_TEMP_SUFFIX)
        FileInputStream(source).use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
                output.fd.sync()
            }
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    /** Deletes only a tree that is strictly inside [allowedRoot]; a symlink is unlinked, not followed. */
    private fun deleteTree(target: File, allowedRoot: File) {
        if (!target.exists()) return
        if (!DshTrialRoots.isWithin(allowedRoot, target) || target.absoluteFile == allowedRoot.absoluteFile) return
        if (Files.isSymbolicLink(target.toPath())) {
            target.delete()
            return
        }
        target.listFiles()?.forEach { child ->
            if (Files.isSymbolicLink(child.toPath()) || child.isFile) {
                child.delete()
            } else {
                deleteTree(child, allowedRoot)
            }
        }
        target.delete()
    }
}
