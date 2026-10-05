package com.muyuchat.mca

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalImageWorkerProcessRetirementGate {
    private val retiring = AtomicBoolean(false)

    val isRetiring: Boolean
        get() = retiring.get()

    fun beginRetirement(): Boolean = retiring.compareAndSet(false, true)
}

internal fun localImageWorkerErrorCode(error: Throwable): String {
    val raw = when (error) {
        is LocalImageProductContractException -> error.code
        is LocalImageWorkerRemoteException -> error.code
        is ImageNativeExecutionContractException -> error.code
        is ImageProfileResolutionException -> error.localImagePublicErrorCode()
        else -> "generation_failed"
    }
    return raw.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_.-]+"), "_")
        .trim('_')
        .take(80)
        .ifBlank { "generation_failed" }
}

/**
 * Serializes result, cancellation, and normal-error terminal publication for a worker request.
 * Result publication wins once the output file is complete; cancellation or error wins only while
 * native work is still in progress.
 */
internal class LocalImageWorkerPublicationGate {
    private enum class State {
        RUNNING,
        CANCELLATION_REQUESTED,
        RESULT_PUBLICATION_STARTED,
        ERROR_PUBLICATION_STARTED
    }

    @Volatile
    private var state: State = State.RUNNING

    private var cancellationTerminalPublicationStarted: Boolean = false
    private val resultPublicationTerminalAttemptFinished = CountDownLatch(1)

    val cancelRequested: Boolean
        get() = state == State.CANCELLATION_REQUESTED

    val resultPublicationStarted: Boolean
        get() = state == State.RESULT_PUBLICATION_STARTED

    fun tryBeginResultPublication(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.RESULT_PUBLICATION_STARTED
        true
    }

    fun markResultPublicationTerminalAttemptFinished() {
        resultPublicationTerminalAttemptFinished.countDown()
    }

    fun awaitResultPublicationTerminalAttempt(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L) { "Result publication exit timeout must be non-negative." }
        return resultPublicationTerminalAttemptFinished.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun tryRequestCancellation(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.CANCELLATION_REQUESTED
        true
    }

    fun tryBeginCancellationTerminalPublication(): Boolean = synchronized(this) {
        if (state != State.CANCELLATION_REQUESTED || cancellationTerminalPublicationStarted) {
            return@synchronized false
        }
        cancellationTerminalPublicationStarted = true
        true
    }

    fun tryBeginErrorPublication(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.ERROR_PUBLICATION_STARTED
        true
    }
}
