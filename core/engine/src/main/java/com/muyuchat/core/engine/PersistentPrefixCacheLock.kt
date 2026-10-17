package com.muyuchat.core.engine

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

    /**
     * Acquires the process-wide store mutex without waiting. Cache work is
     * optional, so contention must immediately fall back to an ordinary
     * prefill instead of delaying inference behind another process.
     */
internal fun PersistentPrefixCacheStore.acquireStoreLock(root: File): Closeable? {
        val lockFile = File(root, LOCK_FILE_NAME)
        if (!isDirectChild(root, lockFile) || existsAsSymbolicLink(lockFile)) return null

        var channelToClose: FileChannel? = null
        var lockToRelease: FileLock? = null
        return try {
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            )
            channelToClose = channel
            if (!isDirectChild(root, lockFile) || !isRegularFileWithoutLinks(lockFile)) return null

            val fileLock = channel.tryLock() ?: return null
            lockToRelease = fileLock
            if (!isDirectChild(root, lockFile) || !isRegularFileWithoutLinks(lockFile)) return null

            HeldStoreLock(channel, fileLock).also {
                channelToClose = null
                lockToRelease = null
            }
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            lockToRelease?.let { lock -> runCatching { lock.release() } }
            channelToClose?.let { channel -> runCatching { channel.close() } }
        }
    }

internal fun PersistentPrefixCacheStore.safeNow(): Long = runCatching { clock() }.getOrDefault(0L).coerceAtLeast(0L)

private class HeldStoreLock(
    private val channel: FileChannel,
    private val fileLock: FileLock
) : Closeable {
    override fun close() {
        try {
            if (fileLock.isValid) fileLock.release()
        } catch (_: Exception) {
            // Cache cleanup is best effort; the channel close below also
            // releases this process's lock if an explicit release failed.
        } finally {
            runCatching { channel.close() }
        }
    }
}

private const val LOCK_FILE_NAME = ".prefix-cache.lock"
