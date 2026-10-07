package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RequestIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller managing cooperative cancellation, drain periods, and timeouts for native requests.
 * Invariant: Request cancellation is strictly idempotent; canceling an already cancelled or terminal request is a safe no-op.
 */
class RequestCancellationController {

    private val cancelMap = ConcurrentHashMap<String, CancelHandle>()

    data class CancelHandle(
        val identity: RequestIdentity,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val requestedTimestamp: Long = System.currentTimeMillis(),
        @Volatile var cancelReason: String = ""
    )

    /**
     * Registers a request for cancellation tracking.
     */
    fun registerRequest(identity: RequestIdentity): CancelHandle {
        val handle = CancelHandle(identity = identity)
        cancelMap[identity.requestId] = handle
        return handle
    }

    /**
     * Requests cooperative cancellation for a specific request ID.
     * Returns true if this call transitioned the request into cancellation state, false if already requested or absent.
     */
    fun requestCancellation(requestId: String, reason: String = "User requested cancellation"): Boolean {
        val handle = cancelMap[requestId] ?: return false
        if (handle.cancelRequested.compareAndSet(false, true)) {
            handle.cancelReason = reason
            return true
        }
        return false
    }

    /**
     * Checks if cancellation has been requested for a given request.
     */
    fun isCancellationRequested(requestId: String): Boolean {
        return cancelMap[requestId]?.cancelRequested?.get() == true
    }

    /**
     * Retrieves the cancellation reason if cancelled.
     */
    fun getCancellationReason(requestId: String): String? {
        val handle = cancelMap[requestId] ?: return null
        return if (handle.cancelRequested.get()) handle.cancelReason else null
    }

    /**
     * Evaluates if a request has exceeded its maximum execution deadline.
     */
    fun isTimedOut(requestId: String, timeoutMillis: Long): Boolean {
        if (timeoutMillis <= 0L) return false
        val handle = cancelMap[requestId] ?: return false
        val elapsed = System.currentTimeMillis() - handle.requestedTimestamp
        return elapsed > timeoutMillis
    }

    /**
     * Unregisters and cleans up tracking for a terminated request.
     */
    fun unregisterRequest(requestId: String) {
        cancelMap.remove(requestId)
    }

    /**
     * Cancels all currently tracked active requests (e.g. during process runtime shutdown).
     */
    fun cancelAllActive(reason: String = "Process runtime shutting down"): Int {
        var count = 0
        for ((_, handle) in cancelMap) {
            if (handle.cancelRequested.compareAndSet(false, true)) {
                handle.cancelReason = reason
                count++
            }
        }
        return count
    }

    /**
     * Returns the count of currently tracked requests.
     */
    fun activeCount(): Int = cancelMap.size
}
