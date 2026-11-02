package com.muyuchat.mca

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Cross-process gate over the ONE physical native media execution domain (proposal
 * mca-native-video-workflow-v1 V1-00). Every real native media execution in this app — local
 * image generate (sd-native and MNN alike) and upscale today, the video pipeline from V1-02b on —
 * runs inside [withLease]. Product request identity (coordinators/owners) and the chat engine
 * lifecycle lease are separate, higher layers; this gate is the bottom physical mutex and must
 * never be acquired while holding those in reverse order (see the lock-order contract in the
 * proposal §7.4).
 *
 * Semantics are unchanged from the former LocalImageExecutionGate; only the name now states the
 * shared domain. The lock FILE name stays `native_image_execution.lock` on purpose: an updated
 * main process must keep excluding an older worker process (and vice versa) during upgrades, so
 * the domain identity is the file, not this class name.
 *
 * Two levels: a fair in-process lease (one holder per process) and an OS file lock (one holder
 * across processes). Waiting is cancellable and cooperative; a cancelled wait never holds either
 * level. The OS releases the file lock if a holder process dies.
 */
internal object NativeMediaExecutionGate {
    private const val LOCK_FILE_NAME = "native_image_execution.lock"
    private const val RETRY_DELAY_MS = 100L

    private val processStateLock = ReentrantLock(true)
    private var processLeaseHeld = false

    suspend fun <T> withLease(
        context: Context,
        isCancelled: () -> Boolean = { false },
        onWaiting: () -> Unit = {},
        block: suspend () -> T
    ): T = withLease(File(context.filesDir, LOCK_FILE_NAME), isCancelled, onWaiting, block)

    /**
     * Core entry, directory-based so pure-JVM tests can drive the real lease/file-lock protocol
     * without an Android Context. The lock file name is fixed by the shared domain contract.
     */
    suspend fun <T> withLease(
        lockFile: File,
        isCancelled: () -> Boolean = { false },
        onWaiting: () -> Unit = {},
        block: suspend () -> T
    ): T {
        var processLeaseAcquired = false
        var channel: FileChannel? = null
        var fileLock: FileLock? = null
        try {
            while (!processLeaseAcquired) {
                currentCoroutineContext().ensureActive()
                throwIfCancelled(isCancelled)
                processLeaseAcquired = processStateLock.withLock {
                    if (processLeaseHeld) {
                        false
                    } else {
                        processLeaseHeld = true
                        true
                    }
                }
                if (!processLeaseAcquired) {
                    onWaiting()
                    delay(RETRY_DELAY_MS)
                }
            }

            channel = FileOutputStream(lockFile.apply { parentFile?.mkdirs() }, true).channel
            while (fileLock == null) {
                currentCoroutineContext().ensureActive()
                throwIfCancelled(isCancelled)
                fileLock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (fileLock == null) {
                    onWaiting()
                    delay(RETRY_DELAY_MS)
                }
            }
            currentCoroutineContext().ensureActive()
            throwIfCancelled(isCancelled)
            return block()
        } finally {
            runCatching { fileLock?.release() }
            runCatching { channel?.close() }
            if (processLeaseAcquired) {
                processStateLock.withLock { processLeaseHeld = false }
            }
        }
    }

    private fun throwIfCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
    }
}

internal class NativeMediaExecutionLeaseCancelledException :
    IllegalStateException("A native media execution was cancelled while waiting for the shared execution lease.")
