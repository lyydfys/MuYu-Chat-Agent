package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Top-level Process Runtime Host supervisor.
 * Unique owner responsible for process initialization, monotonic runtime epoch increments,
 * graceful shutdown, and native health evaluation.
 *
 * ## Admission is part of the lifecycle protocol
 *
 * Request admission ([admitRequest]), epoch advance ([advanceEpoch]) and [shutdownProcess] all run
 * under one lifecycle lock. A dispatch therefore cannot slip in between the shutdown decision and
 * the drain, and it cannot register itself under an epoch that an advance is concurrently
 * superseding. The supervisor is also the single owner that assigns the attempt identity, so two
 * dispatches for the same requestId never share one execution identity.
 */
class RuntimeSupervisor(initialEpoch: Long = 1L) : RuntimePort {

    private val epochCounter = AtomicLong(initialEpoch)
    private val isRunning = AtomicBoolean(true)
    private val backends = ConcurrentHashMap<String, BackendPort>()

    /** Monotonic attempt identity source; the admission owner, so no unbounded per-id table. */
    private val attemptSequence = AtomicLong(0L)

    /** Serializes backend admission against shutdown so a backend can never be admitted unseen. */
    private val backendLock = Any()

    /** Serializes request admission, epoch advance and shutdown. */
    private val lifecycleLock = Any()

    val requestRegistry = RequestRegistry()
    val cancellationController = RequestCancellationController()
    val callbackRouter = CallbackRouter()

    override val currentEpoch: RuntimeEpoch
        get() = RuntimeEpoch(epochCounter.get())

    override val isHealthy: Boolean
        get() = isRunning.get()

    /**
     * Advances the process runtime epoch.
     * All requests, sessions, or late callbacks tied to older epochs become immediately stale and
     * isolated. Only work registered under a superseded epoch is drained: a request that already
     * belongs to the new epoch must survive the epoch change that ran concurrently with it.
     */
    override fun advanceEpoch(): RuntimeEpoch = synchronized(lifecycleLock) {
        val next = epochCounter.incrementAndGet()
        requestRegistry.drainEpochsBefore(next, "Runtime epoch advanced to $next")
        cancellationController.cancelAllActiveBefore(next, "Runtime epoch advanced to $next")
        RuntimeEpoch(next)
    }

    /**
     * Atomically admits a request: running check, epoch read, attempt identity assignment, live
     * registry reservation, cancellation registration, optional callback-route subscription and the
     * `Accepted` transition happen in **one** lifecycle transaction.
     *
     * The reservations belong to the same critical section on purpose. When a caller registered the
     * cancel handle and the callback route *after* admission returned, a shutdown could interleave in
     * between: it would close admission, cancel/drain/clear everything, and then the dispatch would
     * still subscribe a route and publish a cancel handle onto a shut-down runtime -- a residual
     * route with no owner. Registration now either happens before the shutdown decision (and is
     * therefore cancelled, drained and cleared by it) or is rejected because the runtime is closed.
     *
     * Returns null when the runtime is shutting down or already shut down, in which case nothing is
     * left behind. Throws [IllegalStateException] when the requestId is already active (the caller
     * must then decide whether it is an idempotent replay or a conflict); a failure at any later
     * reservation step rolls back every reservation this call already made.
     */
    fun admitRequest(
        requestId: String,
        consumer: EventConsumer? = null
    ): RequestRegistry.RequestEntry? = synchronized(lifecycleLock) {
        if (!isRunning.get()) return@synchronized null
        val identity = RequestIdentity(
            requestId = requestId,
            attemptId = attemptSequence.incrementAndGet(),
            runtimeEpoch = epochCounter.get()
        )
        val entry = requestRegistry.register(identity)
        var cancelRegistered = false
        var routeSubscribed = false
        try {
            cancellationController.registerRequest(identity)
            cancelRegistered = true
            if (consumer != null) {
                callbackRouter.subscribe(identity, consumer)
                routeSubscribed = true
            }
            if (!requestRegistry.transitionState(entry.identity, RequestState.Accepted)) {
                throw IllegalStateException(
                    "Request ${identity.requestId} could not be moved to ${RequestState.Accepted}."
                )
            }
        } catch (failure: Throwable) {
            // Never leave a half-admitted request behind: compare-remove only this attempt's own
            // reservations, then propagate the failure so the caller cannot mistake it for success.
            if (routeSubscribed) callbackRouter.unsubscribe(identity)
            if (cancelRegistered) cancellationController.unregisterRequest(identity)
            requestRegistry.settleAndRemove(identity, RequestState.Aborted)
            throw failure
        }
        entry
    }

    /**
     * Exactly-once terminal settlement of [identity] followed by the release of every per-request
     * resource that belongs to that attempt.
     *
     * Order matters and mirrors the terminal settlement contract: settle the attempt (compare
     * attempt, CAS terminal, keep the bounded outcome), then unregister the cancel handle, then
     * archive the bounded outcome, then retire the callback route, then evict the live registry
     * entry. Delivery has already happened through [CallbackRouter.routeEvent]; this step only
     * clears ownership, so a terminated request cannot linger as a live entry, a stale route or a
     * pending cancel handle, and a late cleanup of a retired attempt cannot touch its successor
     * (every step is identity-checked).
     *
     * Returns true only for the call that performed the terminal transition.
     */
    fun settleRequest(
        identity: RequestIdentity,
        terminalState: RequestState,
        error: ErrorEnvelope? = null
    ): Boolean {
        require(terminalState.isTerminal) {
            "State $terminalState is not a valid terminal state."
        }
        val settled = requestRegistry.settle(identity, terminalState, error) != null
        releaseRequestResources(identity, terminalState)
        return settled
    }

    /**
     * Releases the per-request resources of an attempt that already reached a terminal state without
     * settling it again (idempotent cleanup of an aborted or superseded attempt).
     *
     * [finalState] publishes the bounded outcome record when the attempt still owns a route; pass
     * null when the outcome was already delivered as a terminal event.
     */
    fun releaseRequestResources(identity: RequestIdentity, finalState: RequestState? = null): Boolean {
        cancellationController.unregisterRequest(identity)
        if (finalState != null) callbackRouter.recordOutcome(identity, finalState)
        callbackRouter.unsubscribe(identity)
        return requestRegistry.evictTerminal(identity) != null
    }

    override fun getBackend(kind: String): BackendPort? {
        return backends[kind]
    }

    /**
     * Admits a backend owner. The running check and the registration are one atomic step, so a
     * backend can never be registered after shutdown and left unclosed; a rejected backend is
     * released here instead of being leaked to its caller. Replacing an existing owner releases the
     * replaced backend, which is otherwise unreachable and would never be closed.
     */
    override fun registerBackend(kind: String, backend: BackendPort) {
        val replaced: BackendPort?
        synchronized(backendLock) {
            if (!isRunning.get()) {
                closeBackend(backend)
                throw IllegalStateException("RuntimeSupervisor is shutdown.")
            }
            replaced = backends.put(kind, backend)
        }
        replaced?.let { previous -> closeBackend(previous) }
    }

    /**
     * Produces a runtime health diagnostic report.
     */
    fun evaluateHealth(): RuntimeHealthReport {
        return RuntimeHealthReport(
            epoch = currentEpoch,
            isHealthy = isRunning.get(),
            activeRequestCount = requestRegistry.count(),
            totalSessions = backends.size,
            details = mapOf(
                "activeBackends" to backends.keys.joinToString(","),
                "droppedStaleEvents" to callbackRouter.droppedStaleCount().toString(),
                "callbackFailures" to callbackRouter.callbackFailureCount().toString()
            )
        )
    }

    /**
     * Executes process-level graceful shutdown.
     *
     * The shutdown decision is taken under the lifecycle lock, so a concurrent admission either
     * completes before it (and is then drained) or is rejected afterwards -- never left as an
     * `Accepted` request that nothing will execute.
     *
     * Every registered backend close is attempted even when an earlier one fails: a single broken
     * owner must not keep the remaining native resources alive. The first failure is rethrown after
     * the whole set has been released, so shutdown is never a silent success.
     */
    override fun shutdownProcess() {
        synchronized(lifecycleLock) {
            if (!isRunning.compareAndSet(true, false)) return
        }

        // 1. Cancel active requests, settle every still-active attempt, drop the callback routes and
        //    terminate archives, then drop the per-request bookkeeping itself. The last step is what
        //    makes "0 active lease / 0 stale route / 0 live request" observable after shutdown; the
        //    bounded outcomes of the normal path are retained by the router, which is cleared here
        //    because a shut-down runtime has no consumer left to query them.
        cancellationController.cancelAllActive("Process runtime shutdown")
        requestRegistry.drainAll("Process runtime shutdown")
        callbackRouter.clear()
        requestRegistry.clearAll()
        cancellationController.clearAll()

        // 2. Shut down every registered backend, attempting all closes.
        val registered: List<BackendPort>
        synchronized(backendLock) {
            registered = backends.values.toList()
            backends.clear()
        }
        var firstFailure: Throwable? = null
        for (backend in registered) {
            try {
                backend.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { failure ->
            throw IllegalStateException(
                "RuntimeSupervisor shutdown could not close every backend.",
                failure
            )
        }
    }

    override fun close() {
        shutdownProcess()
    }

    private fun closeBackend(backend: BackendPort) {
        try {
            backend.close()
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "RuntimeSupervisor could not release backend '${backend.backendKind}'.",
                failure
            )
        }
    }
}
