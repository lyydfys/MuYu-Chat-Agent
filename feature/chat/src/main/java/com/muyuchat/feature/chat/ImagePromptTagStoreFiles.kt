package com.muyuchat.feature.chat

import android.content.ContentResolver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal fun isSafeImagePromptTagContentUri(rawUri: String): Boolean = try {
    val uri = URI(rawUri)
    !uri.isOpaque &&
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) &&
        !uri.authority.isNullOrBlank() &&
        uri.path.orEmpty().split('/').none { segment ->
            segment == "." || segment == ".." || '\\' in segment
        }
} catch (_: Exception) {
    false
}

internal fun imagePromptTagStoreFile(
    root: File,
    kind: ImagePromptTagDictionaryKind
): File = requireNotNull(safeImagePromptTagStoreChild(root, kind.fileName))

internal fun safeImagePromptTagStoreChild(root: File, fileName: String): File? {
    if (fileName !in ImagePromptTagDictionaryKind.entries.map { it.fileName }) return null
    return try {
        val canonicalRoot = root.canonicalFile
        File(canonicalRoot, fileName).canonicalFile.takeIf { it.parentFile == canonicalRoot }
    } catch (_: Exception) {
        null
    }
}

internal fun copyImagePromptTagStreamBounded(
    input: InputStream,
    output: OutputStream,
    maximumBytes: Long,
    checkCancelled: () -> Unit = {}
): Long {
    require(maximumBytes > 0L) { "Copy limit must be positive." }
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var copied = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (copied > maximumBytes - count) {
            throw ImagePromptTagStoreValidationException(ImagePromptTagStoreIssue.FILE_TOO_LARGE)
        }
        output.write(buffer, 0, count)
        copied += count
    }
    return copied
}

internal fun replaceImagePromptTagSnapshot(
    staged: File,
    target: File,
    moveFile: (source: File, destination: File, atomic: Boolean) -> Unit = ::moveImagePromptTagFile,
    copyAndSync: (source: File, destination: File) -> Unit = ::copyAndSyncImagePromptTagFile,
    syncDirectory: (File) -> Unit = ::syncImagePromptTagDirectory
) {
    val stagedCanonical = staged.canonicalFile
    val targetCanonical = target.canonicalFile
    val parent = requireNotNull(targetCanonical.parentFile)
    require(stagedCanonical.parentFile == parent && parent.isDirectory) {
        "Dictionary replacement must stay inside one directory."
    }
    require(stagedCanonical.isFile && stagedCanonical != targetCanonical) {
        "Dictionary staging file is invalid."
    }
    require(!targetCanonical.exists() || targetCanonical.isFile) {
        "Dictionary target must be a regular file."
    }

    val hadPrevious = targetCanonical.isFile
    val backup = if (hadPrevious) {
        File(parent, ".${targetCanonical.name}.${UUID.randomUUID()}.rollback.tmp")
    } else {
        null
    }
    var backupReady = false
    var publishStarted = false
    var committed = false
    var rolledBack = false
    try {
        if (backup != null) {
            copyAndSync(targetCanonical, backup)
            backupReady = true
        }
        publishStarted = true
        moveWithAtomicFallback(stagedCanonical, targetCanonical, moveFile)
        check(!stagedCanonical.exists() && targetCanonical.isFile) {
            "Dictionary move did not publish its target."
        }
        syncDirectory(parent)
        committed = true
    } catch (failure: Throwable) {
        if (backupReady && publishStarted && backup != null) {
            try {
                if (targetCanonical.exists() && !targetCanonical.delete()) {
                    throw IOException("Unable to remove failed dictionary publication.")
                }
                moveWithAtomicFallback(backup, targetCanonical, moveFile)
                syncDirectory(parent)
                rolledBack = true
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
        } else if (!hadPrevious && publishStarted) {
            try {
                if (targetCanonical.exists() && !targetCanonical.delete()) {
                    throw IOException("Unable to remove failed dictionary publication.")
                }
                syncDirectory(parent)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    } finally {
        stagedCanonical.delete()
        if (backup != null && (committed || rolledBack || !backupReady)) {
            if (backup.delete() && committed) {
                try {
                    syncDirectory(parent)
                } catch (_: Exception) {
                    // Publication is already durable; stale directory metadata is non-fatal.
                }
            }
        }
    }
}

private fun moveWithAtomicFallback(
    source: File,
    target: File,
    moveFile: (source: File, destination: File, atomic: Boolean) -> Unit
) {
    try {
        moveFile(source, target, true)
    } catch (_: AtomicMoveNotSupportedException) {
        moveFile(source, target, false)
    }
}

private fun moveImagePromptTagFile(source: File, destination: File, atomic: Boolean) {
    val options = if (atomic) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        arrayOf(StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(source.toPath(), destination.toPath(), *options)
}

private fun copyAndSyncImagePromptTagFile(source: File, destination: File) {
    FileInputStream(source).use { input ->
        FileOutputStream(destination).use { output ->
            input.copyTo(output, COPY_BUFFER_BYTES)
            output.flush()
            output.fd.sync()
        }
    }
}

internal fun syncImagePromptTagDirectory(directory: File) {
    val canonical = directory.canonicalFile
    require(canonical.isDirectory) { "Dictionary parent must be a directory." }
    val descriptor = try {
        Os.open(canonical.path, OsConstants.O_RDONLY, 0)
    } catch (error: ErrnoException) {
        throw IOException("Unable to open dictionary directory for sync.", error)
    }
    try {
        Os.fsync(descriptor)
    } catch (error: ErrnoException) {
        throw IOException("Unable to sync dictionary directory.", error)
    } finally {
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            // Preserve the primary sync outcome.
        }
    }
}

internal const val COPY_BUFFER_BYTES: Int = 32 * 1024
