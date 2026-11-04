package com.muyuchat.mca.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What happened to the target file. The original is only ever replaced by a verified payload. */
internal enum class McaBackupRestoreOutcome {
    /** The verified payload is now the target file. */
    RESTORED,

    /** The payload did not survive [McaBackupStaging.restoreAtomically]'s validator. */
    VALIDATION_FAILED,

    /** The swap itself failed; the previous file was put back. */
    PROMOTION_FAILED,

    /** Writing the staging copy failed; nothing was touched. */
    STAGING_FAILED
}

internal data class McaBackupRestoreResult(
    val outcome: McaBackupRestoreOutcome,
    val message: String,
    /** True when the previous target content is still (or again) in place. */
    val keptOriginal: Boolean
) {
    val succeeded: Boolean get() = outcome == McaBackupRestoreOutcome.RESTORED
}

/**
 * Small, explicit file transaction for backup artifacts.
 *
 * Order matters and is the whole point: a payload is written to a sibling staging file first, the
 * caller validates *that* file, and only then is the live file replaced. If validation or the swap
 * fails the previous file is restored (or never moved), so a bad or truncated backup can never
 * destroy the current one — the rule the settings page promises to the user.
 *
 * Everything here is plain `java.io.File`; it holds no Android type, which is what makes the
 * failure branches testable on a temp directory.
 */
internal object McaBackupStaging {

    private const val STAGING_NAME = ".mca-restore.staging"
    private const val PREVIOUS_NAME = ".mca-restore.previous"

    /**
     * Writes [payload] to [workDir], validates the staged copy with [validate], then replaces
     * [target] atomically (rename), keeping the previous target until the swap succeeded.
     */
    fun restoreAtomically(
        target: File,
        payload: ByteArray,
        workDir: File,
        validate: (File) -> Boolean,
        rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) }
    ): McaBackupRestoreResult {
        val staging = File(workDir, STAGING_NAME)
        val previous = File(workDir, PREVIOUS_NAME)
        runCatching {
            if (!workDir.isDirectory && !workDir.mkdirs()) {
                error("无法创建临时目录")
            }
            staging.delete()
            previous.delete()
            FileOutputStream(staging).use { output ->
                output.write(payload)
                output.flush()
            }
        }.onFailure { error ->
            staging.delete()
            return McaBackupRestoreResult(
                outcome = McaBackupRestoreOutcome.STAGING_FAILED,
                message = "写入临时文件失败：${error.message ?: error.javaClass.simpleName}",
                keptOriginal = target.exists()
            )
        }

        val accepted = runCatching { validate(staging) }.getOrDefault(false)
        if (!accepted) {
            staging.delete()
            return McaBackupRestoreResult(
                outcome = McaBackupRestoreOutcome.VALIDATION_FAILED,
                message = "备份未通过校验，已保留原文件",
                keptOriginal = true
            )
        }

        val hadTarget = target.exists()
        // The target's parent may not exist on a first export/restore into a fresh sub-directory.
        target.parentFile?.mkdirs()
        if (hadTarget) {
            if (!rename(target, previous)) {
                staging.delete()
                return McaBackupRestoreResult(
                    outcome = McaBackupRestoreOutcome.PROMOTION_FAILED,
                    message = "无法移走原文件，已保留原文件",
                    keptOriginal = true
                )
            }
        }
        if (!rename(staging, target)) {
            // Put the previous content back and report honestly; a half-swapped file is worse than
            // an old one.
            runCatching { target.delete() }
            val restored = if (hadTarget) runCatching { rename(previous, target) }.getOrDefault(false) else true
            staging.delete()
            return McaBackupRestoreResult(
                outcome = McaBackupRestoreOutcome.PROMOTION_FAILED,
                message = if (restored) "替换失败，已还原原文件" else "替换失败，且原文件未能还原",
                keptOriginal = restored
            )
        }
        previous.delete()
        return McaBackupRestoreResult(
            outcome = McaBackupRestoreOutcome.RESTORED,
            message = "已写入已校验的备份",
            keptOriginal = false
        )
    }

    /** Writes a payload to [file] through a staging sibling, keeping the old file on failure. */
    fun publishVerified(
        file: File,
        payload: ByteArray,
        validate: (File) -> Boolean
    ): McaBackupRestoreResult = restoreAtomically(
        target = file,
        payload = payload,
        workDir = requireNotNull(file.parentFile) { "target must have a parent directory" },
        validate = validate
    )
}

/** Reads a backup document with the format's size ceiling; null when it is too large to read. */
internal fun File.readBackupText(maxBytes: Int = McaBackupFormat.MAX_TEXT_BYTES): String? {
    if (!isFile) return null
    if (length() > maxBytes.toLong()) return null
    return try {
        readText(Charsets.UTF_8)
    } catch (error: IOException) {
        null
    }
}
