package com.muyuchat.core.dsh.runtime.payload

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Result of trying to take the cross-process payload-install lock.
 *
 * There is deliberately no "unlocked fallback": when the OS lock cannot be acquired the caller
 * must fail the install closed with the concrete [Busy]/[Unavailable] detail (the detail keeps
 * the original exception class/message so errno-like causes survive into diagnostics).
 */
internal sealed interface DshPayloadLockOutcome {
    data class Acquired(val lock: DshPayloadLock) : DshPayloadLockOutcome
    data class Busy(val detail: String) : DshPayloadLockOutcome
    data class Unavailable(val detail: String) : DshPayloadLockOutcome
}

/**
 * Real OS-level exclusive lock over [DshRuntimeConfig.payloadInstallLockFile] using
 * [FileChannel.tryLock] (fcntl on Android/Bionic). Guarantees:
 *
 * - one holder across processes/JVMs; every other caller waits (bounded) and then fails closed;
 * - the kernel drops the lock when the holder closes the channel, dies or is killed, so no
 *   stale "pseudo lock" can survive a crash (the lock file itself may stay on disk);
 * - no symlink following: the lock file is opened with [LinkOption.NOFOLLOW_LINKS].
 */
internal class DshPayloadLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : Closeable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 600_000L
        const val DEFAULT_POLL_MILLIS: Long = 50L

        fun acquire(
            file: File,
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
            pollMillis: Long = DEFAULT_POLL_MILLIS
        ): DshPayloadLockOutcome {
            val timeout = timeoutMillis.coerceAtLeast(1L)
            val poll = pollMillis.coerceAtLeast(10L)
            file.parentFile?.mkdirs()
            val started = System.nanoTime()
            while (true) {
                when (val attempt = tryAcquireOnce(file)) {
                    null -> Unit
                    else -> return attempt
                }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
                if (elapsedMillis >= timeout) {
                    return DshPayloadLockOutcome.Busy(
                        "lock busy after ${timeout}ms: ${file.absolutePath}"
                    )
                }
                try {
                    Thread.sleep(poll)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return DshPayloadLockOutcome.Unavailable(
                        "interrupted while waiting for lock ${file.absolutePath}: " + describe(t)
                    )
                }
            }
        }

        /** null means "held elsewhere, retry"; a non-null outcome is final for this attempt. */
        private fun tryAcquireOnce(file: File): DshPayloadLockOutcome? {
            val channel = try {
                FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                )
            } catch (t: Throwable) {
                return DshPayloadLockOutcome.Unavailable(
                    "cannot open lock ${file.absolutePath}: " + describe(t)
                )
            }
            val lock = try {
                channel.tryLock()
            } catch (t: OverlappingFileLockException) {
                runCatching { channel.close() }
                return DshPayloadLockOutcome.Busy(
                    "lock already held by this process: ${file.absolutePath}"
                )
            } catch (t: Throwable) {
                runCatching { channel.close() }
                // Some platforms surface a held lock as an IOException instead of tryLock()==null;
                // that remains retryable contention, never an "unsupported/no-op" fallback.
                if (isLockContention(t)) return null
                return DshPayloadLockOutcome.Unavailable(
                    "cannot lock ${file.absolutePath}: " + describe(t)
                )
            }
            if (lock == null) {
                runCatching { channel.close() }
                return null
            }
            return DshPayloadLockOutcome.Acquired(DshPayloadLock(channel, lock))
        }

        private fun isLockContention(t: Throwable): Boolean {
            val message = (t.message ?: "").lowercase()
            return "locked" in message ||
                "lock violation" in message ||
                "used by another process" in message ||
                "resource temporarily unavailable" in message ||
                "errno=11" in message ||
                "eagain" in message
        }

        private fun describe(t: Throwable): String {
            val reason = (t as? java.nio.file.FileSystemException)?.reason?.takeIf { it.isNotBlank() }
            return buildString {
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                if (reason != null && t.message?.contains(reason) != true) append(" (").append(reason).append(')')
            }
        }
    }
}
