package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.RuntimeMonotonicClock
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import com.muyuchat.core.engine.contract.RequestIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Controller managing cooperative cancellation, drain periods, and timeouts for native requests.
 * Invariant: Request cancellation is strictly idempotent; canceling an already cancelled or terminal request is a safe no-op.
 *
 * Every tracked request carries its full [RequestIdentity], so cleanup is conditional: a delayed
 * cleanup of an older attempt can never remove the record of a replacement attempt that reused the
 * same requestId. The cancellation flag and its reason are published as one immutable record, and
 * deadlines are measured on the monotonic clock so a wall-clock adjustment cannot change timeouts.
 */
class RequestCancellationController(
    private val monotonicClock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock
) {

    private val cancelMap = ConcurrentHashMap<String, CancelHandle>()

    /**
     * One registered request. All cancellation fields are published together through [state], so a
     * reader can never observe "requested" with a stale or unrelated reason.
     */
    class CancelHandle internal constructor(
        val identity: RequestIdentity,
        registeredAtMonotonicMs: Long
    ) {
        internal data class State(
            val requested: Boolean,
            val reason: String,
            val registeredAtMonotonicMs: Long
        )

        private val state = AtomicReference(State(false, "", registeredAtMonotonicMs))

        internal fun snapshot(): State = state.get()

        internal fun markRequested(reason: String): Boolean {
            while (true) {
                val current = state.get()
                if (current.requested) return false
                if (state.compareAndSet(current, current.copy(requested = true, reason = reason))) {
                    return true
                }
            }
        }
    }

    /** Immutable observation of a cancellation decision: the flag and its reason never disagree. */
    data class CancelRecord(
        val identity: RequestIdentity,
        val requested: Boolean,
        val reason: String
    )

    /**
     * Registers a request for cancellation tracking. A duplicate registration for a requestId that
     * is still tracked is rejected instead of silently replacing a pending cancel record.
     */
    fun registerRequest(identity: RequestIdentity): CancelHandle {
        val handle = CancelHandle(identity, monotonicClock.nowMs())
        val existing = cancelMap.putIfAbsent(identity.requestId, handle)
        check(existing == null) {
            "Request ${identity.requestId} already has a cancellation record."
        }
        return handle
    }

    /**
     * Requests cooperative cancellation for the currently tracked attempt of a request ID.
     *
     * This is the client-facing "cancel by id" entry: it always targets whichever attempt currently
     * holds that requestId. Attempt-owned actions (stream failure, disconnect cleanup, timeouts)
     * must use [requestCancellation] with the full identity instead, so a late action of a retired
     * attempt cannot cancel its replacement.
     */
    fun requestCancellation(requestId: String, reason: String = "User requested cancellation"): Boolean {
        val handle = cancelMap[requestId] ?: return false
        return handle.markRequested(reason)
    }

    /**
     * Attempt-checked cancellation: only the exact attempt [identity] can transition its own cancel
     * record. A retired attempt whose requestId was reused cannot cancel the replacement.
     */
    fun requestCancellation(identity: RequestIdentity, reason: String = "User requested cancellation"): Boolean {
        val handle = cancelMap[identity.requestId] ?: return false
        if (!handle.identity.sameAttemptAs(identity)) return false
        return handle.markRequested(reason)
    }

    /**
     * Attempt-checked cancellation query: true only when the exact attempt is the tracked one and
     * has cancellation requested.
     */
    fun isCancellationRequested(identity: RequestIdentity): Boolean {
        val handle = cancelMap[identity.requestId] ?: return false
        if (!handle.identity.sameAttemptAs(identity)) return false
        return handle.snapshot().requested
    }

    /**
     * Checks if cancellation has been requested for a given request.
     */
    fun isCancellationRequested(requestId: String): Boolean {
        return cancelMap[requestId]?.snapshot()?.requested == true
    }

    /**
     * Retrieves the cancellation reason if cancelled.
     */
    fun getCancellationReason(requestId: String): String? {
        val state = cancelMap[requestId]?.snapshot() ?: return null
        return if (state.requested) state.reason else null
    }

    /**
     * Immutable view of the cancellation decision, so a reader can never observe "requested" with
     * an unrelated or empty reason.
     */
    fun cancelRecord(requestId: String): CancelRecord? {
        val handle = cancelMap[requestId] ?: return null
        val state = handle.snapshot()
        return CancelRecord(handle.identity, state.requested, state.reason)
    }

    /**
     * Evaluates if a request has exceeded its maximum execution deadline.
     *
     * Elapsed time comes from the monotonic clock captured at registration: wall-clock changes
     * (NTP steps, manual time changes, timezone jumps) cannot extend or shorten the timeout.
     */
    fun isTimedOut(requestId: String, timeoutMillis: Long): Boolean {
        if (timeoutMillis <= 0L) return false
        val state = cancelMap[requestId]?.snapshot() ?: return false
        val elapsed = monotonicClock.nowMs() - state.registeredAtMonotonicMs
        return elapsed > timeoutMillis
    }

    /**
     * Unregisters and cleans up tracking for a terminated request by id. Administrative form:
     * request-owner cleanup must use the identity-checked overload.
     */
    fun unregisterRequest(requestId: String) {
        cancelMap.remove(requestId)
    }

    /**
     * Identity-checked cleanup: a delayed cleanup of an older attempt must not drop the cancel
     * record that belongs to a replacement attempt with the same requestId.
     */
    fun unregisterRequest(identity: RequestIdentity) {
        val handle = cancelMap[identity.requestId] ?: return
        if (handle.identity.sameAttemptAs(identity)) {
            cancelMap.remove(identity.requestId, handle)
        }
    }

    /**
     * Cancels all currently tracked active requests (e.g. during process runtime shutdown).
     */
    fun cancelAllActive(reason: String = "Process runtime shutting down"): Int {
        var count = 0
        for ((_, handle) in cancelMap) {
            if (handle.markRequested(reason)) count++
        }
        return count
    }

    /**
     * Cancels only the requests superseded by [runtimeEpoch]. An epoch advance must not cancel the
     * cancellation record of a request that was admitted for the new epoch concurrently with it.
     */
    fun cancelAllActiveBefore(runtimeEpoch: Long, reason: String = "Process runtime shutting down"): Int {
        var count = 0
        for ((_, handle) in cancelMap) {
            if (handle.identity.runtimeEpoch < runtimeEpoch && handle.markRequested(reason)) count++
        }
        return count
    }

    /**
     * Returns the count of currently tracked requests.
     */
    fun activeCount(): Int = cancelMap.size

    /**
     * Drops every tracking record after a shutdown cancelled them, returning how many were removed.
     *
     * [cancelAllActive] only marks the handles; a shut-down runtime must not keep reporting pending
     * cancellation records for work that no longer exists. Only [RuntimeSupervisor] calls this, and
     * only after admission has been closed, so no live request can lose its handle here.
     */
    internal fun clearAll(): Int {
        val removed = cancelMap.size
        cancelMap.clear()
        return removed
    }
}

