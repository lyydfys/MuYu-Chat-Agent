package com.muyuchat.core.engine.contract

/**
 * Immutable identifier and epoch tracking for an MCA execution request.
 *
 * Spans UI, Facade, JNI boundary, Native engine, and isolated workers.
 * Used as the primary key for state transitions, callback routing, journal logging,
 * and stale event rejection.
 *
 * ## Attempt identity
 *
 * [attemptId] distinguishes execution attempts of the same logical request. It is **not** a
 * decorative counter: settlement, cancellation, eviction and callback routing all compare the full
 * identity, so two dispatches that share a requestId must never share an attempt.
 *
 * The runtime admission owner (`RuntimeSupervisor.admitRequest`) assigns a monotonic [attemptId] to
 * every admitted request, and callers must use the identity that admission returned rather than
 * rebuilding one. A worker that owns its own attempt progression may pass an explicit non-zero
 * attempt ([nextAttempt]); [UNASSIGNED_ATTEMPT_ID] means "not yet assigned by admission".
 */
data class RequestIdentity(
    val requestId: String,
    val attemptId: Long = UNASSIGNED_ATTEMPT_ID,
    val sessionGeneration: Long = 0L,
    val runtimeEpoch: Long = 0L,
    val workerIncarnation: Long = 0L,
    val nativeSequence: Long = 0L,
    val traceId: String = ""
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(attemptId >= 0L) { "attemptId must be >= 0: $attemptId" }
        require(sessionGeneration >= 0L) { "sessionGeneration must be >= 0: $sessionGeneration" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be >= 0: $runtimeEpoch" }
        require(workerIncarnation >= 0L) { "workerIncarnation must be >= 0: $workerIncarnation" }
        require(nativeSequence >= 0L) { "nativeSequence must be >= 0: $nativeSequence" }
    }

    /**
     * Produces a new identity representing a subsequent execution attempt of the same logical request.
     */
    fun nextAttempt(): RequestIdentity = copy(
        attemptId = attemptId + 1L,
        nativeSequence = 0L
    )

    /**
     * Increments the native sequence counter for ordering events within this attempt.
     */
    fun nextSequence(): RequestIdentity = copy(
        nativeSequence = nativeSequence + 1L
    )

    /**
     * Binds this identity to a specific session generation.
     */
    fun withGeneration(generation: Long): RequestIdentity {
        require(generation >= 0L) { "generation must be >= 0: $generation" }
        return copy(sessionGeneration = generation)
    }

    /**
     * Binds this identity to a specific runtime epoch.
     */
    fun withEpoch(epoch: Long): RequestIdentity {
        require(epoch >= 0L) { "epoch must be >= 0: $epoch" }
        return copy(runtimeEpoch = epoch)
    }

    /**
     * Binds this identity to a specific worker incarnation.
     */
    fun withWorker(worker: Long): RequestIdentity {
        require(worker >= 0L) { "worker must be >= 0: $worker" }
        return copy(workerIncarnation = worker)
    }

    /**
     * Binds a distributed trace ID to this request identity.
     */
    fun withTraceId(trace: String): RequestIdentity = copy(
        traceId = trace.trim()
    )

    /**
     * Returns true if this identity represents an older runtime epoch, session generation,
     * or worker incarnation than the currently active environment values.
     */
    fun isStaleComparedTo(
        currentEpoch: Long,
        currentGeneration: Long,
        currentWorker: Long
    ): Boolean {
        if (runtimeEpoch < currentEpoch) return true
        if (sessionGeneration < currentGeneration) return true
        if (workerIncarnation < currentWorker) return true
        return false
    }

    /**
     * Diagnostic representation without leaking internal secrets.
     */
    fun toDiagnosticString(): String =
        "RequestIdentity[id=$requestId, att=$attemptId, gen=$sessionGeneration, ep=$runtimeEpoch, wkr=$workerIncarnation, seq=$nativeSequence, trc=${if (traceId.isEmpty()) "none" else traceId}]"

    companion object {
        /** Attempt value meaning "not yet assigned by the runtime admission owner". */
        const val UNASSIGNED_ATTEMPT_ID: Long = 0L

        /**
         * Creates a fresh request identity with initial generation and epoch counters.
         */
        fun create(
            requestId: String,
            traceId: String = "",
            runtimeEpoch: Long = 0L,
            sessionGeneration: Long = 0L,
            workerIncarnation: Long = 0L
        ): RequestIdentity = RequestIdentity(
            requestId = requestId,
            attemptId = 0L,
            sessionGeneration = sessionGeneration,
            runtimeEpoch = runtimeEpoch,
            workerIncarnation = workerIncarnation,
            nativeSequence = 0L,
            traceId = traceId
        )
    }
}
