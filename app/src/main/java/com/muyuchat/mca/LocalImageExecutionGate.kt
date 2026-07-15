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

internal object LocalImageExecutionGate {
    private const val LOCK_FILE_NAME = "native_image_execution.lock"
    private const val RETRY_DELAY_MS = 100L

    private val processStateLock = ReentrantLock(true)
    private var processLeaseHeld = false

    suspend fun <T> withLease(
        context: Context,
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

            val lockFile = File(context.filesDir, LOCK_FILE_NAME).apply {
                parentFile?.mkdirs()
            }
            channel = FileOutputStream(lockFile, true).channel
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
        if (isCancelled()) throw LocalImageExecutionLeaseCancelledException()
    }
}

internal class LocalImageExecutionLeaseCancelledException :
    IllegalStateException("Local image generation was cancelled while waiting for native execution.")
