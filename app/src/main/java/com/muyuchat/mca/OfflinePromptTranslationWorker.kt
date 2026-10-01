package com.muyuchat.mca

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val OFFLINE_PROMPT_TRANSLATION_CLEANUP_TIMEOUT_MS = 10_000L

/** A failed cleanup remains retryable as soon as the previous owner's actual release is known. */
internal class OfflinePromptTranslationWorkerSlot<T : Any>(private val isReleased: (T) -> Boolean) {
    private val current = AtomicReference<T?>(null)

    fun tryAcquire(worker: T): Boolean {
        while (true) {
            val previous = current.get()
            if (previous != null && !isReleased(previous)) return false
            if (current.compareAndSet(previous, worker)) return true
        }
    }

    fun release(worker: T) {
        if (isReleased(worker)) current.compareAndSet(worker, null)
    }
}

/** A Binder death callback is authoritative even if a stale proxy still reports itself alive. */
internal fun offlinePromptTranslationWorkerReleased(
    confirmedWorkerDeath: Boolean,
    binderAlive: Boolean?
): Boolean = confirmedWorkerDeath || binderAlive != true

/**
 * Tracks only the most recently accepted endpoint. The caller serializes access with its state
 * lock; a late death callback from a retired Binder must never release a replacement worker.
 */
internal class ConnectedWorkerDeathEvidence<T : Any> {
    var lastEndpoint: T? = null
        private set
    var confirmedDeath: Boolean = false
        private set

    fun connected(endpoint: T) {
        lastEndpoint = endpoint
        confirmedDeath = false
    }

    fun confirmDeath(endpoint: T) {
        if (lastEndpoint === endpoint) confirmedDeath = true
    }

    fun isReleased(isAlive: (T) -> Boolean): Boolean =
        offlinePromptTranslationWorkerReleased(confirmedDeath, lastEndpoint?.let(isAlive))
}

/** Cancels the image request when translation memory has not been confirmed released. */
internal class OfflinePromptTranslationCleanupException(cause: Throwable? = null) :
    IllegalStateException(
        "The offline translation worker has not confirmed releasing its model. Retry after it finishes.",
        cause
    )

/** Keeps cancellation from advancing to image loading while a native translation still owns RAM. */
internal suspend fun <T> runOfflinePromptTranslationWorker(
    cleanupTimeoutMs: Long = OFFLINE_PROMPT_TRANSLATION_CLEANUP_TIMEOUT_MS,
    requestStop: () -> Unit,
    release: () -> Unit,
    onReleased: () -> Unit,
    execute: (AtomicBoolean) -> T
): T {
    require(cleanupTimeoutMs > 0)
    val cancelled = AtomicBoolean(false)
    val stopStarted = AtomicBoolean(false)
    val released = CompletableDeferred<Result<Unit>>()
    val stopFinished = CompletableDeferred<Unit>()

    fun stopWorker() {
        cancelled.set(true)
        if (!stopStarted.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "mca-translation-stop") {
            try {
                val startedAt = System.nanoTime()
                val budgetNanos = TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMs)
                while (!released.isCompleted && System.nanoTime() - startedAt < budgetNanos) {
                    // Retry across initial binding and the transition to unload. Each call arms
                    // the existing watchdog for the current native operation in this worker.
                    runCatching(requestStop)
                    if (!released.isCompleted) Thread.sleep(100L)
                }
            } finally {
                stopFinished.complete(Unit)
            }
        }
    }

    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { stopWorker() }
            thread(isDaemon = true, name = "mca-hymt2-translation") {
                val outcome = runCatching { execute(cancelled) }
                // Release only after the blocking native call has returned. Never unload a
                // context concurrently with prefill/decode, even when its caller is cancelled.
                val cleanup = runCatching {
                    release()
                    onReleased()
                }
                released.complete(cleanup)
                if (continuation.isActive) {
                    continuation.resumeWith(outcome)
                }
            }
        }
    } finally {
        // suspendCancellableCoroutine otherwise returns immediately on timeout/user stop and
        // lets the image worker overlap the translator's still-resident model.
        val cleanup = withContext(NonCancellable) {
            withTimeoutOrNull(cleanupTimeoutMs) {
                val result = released.await()
                if (stopStarted.get()) stopFinished.await()
                result
            }
        }
        if (cleanup == null || cleanup.isFailure) {
            // Use a real failure so it overrides an enclosing withTimeout cancellation.
            // The translation service must propagate it and abort the image request.
            throw OfflinePromptTranslationCleanupException(cleanup?.exceptionOrNull())
        }
    }
}
