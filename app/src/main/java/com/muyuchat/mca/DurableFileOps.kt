package com.muyuchat.mca

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

internal fun interface ParentDirectorySyncer {
    fun sync(directory: File)
}

internal object AndroidParentDirectorySyncer : ParentDirectorySyncer {
    override fun sync(directory: File) {
        val canonical = directory.canonicalFile
        require(canonical.isDirectory) { "Published file parent must be a directory." }
        val descriptor = try {
            Os.open(
                canonical.path,
                OsConstants.O_RDONLY,
                0
            )
        } catch (error: ErrnoException) {
            throw IOException("Unable to open published file parent directory for fsync.", error)
        }
        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw IOException("Unable to fsync published file parent directory.", error)
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }
}

internal fun durableMoveWithinParent(
    source: File,
    target: File,
    move: (source: File, target: File) -> Unit,
    parentDirectorySyncer: ParentDirectorySyncer = AndroidParentDirectorySyncer
) {
    val sourceParent = requireNotNull(source.canonicalFile.parentFile)
    val targetParent = requireNotNull(target.canonicalFile.parentFile)
    require(sourceParent == targetParent && targetParent.isDirectory) {
        "Durable move must remain within one existing parent directory."
    }
    move(source, target)
    check(!source.exists() && target.isFile) { "Durable move did not publish its target." }
    parentDirectorySyncer.sync(targetParent)
}
