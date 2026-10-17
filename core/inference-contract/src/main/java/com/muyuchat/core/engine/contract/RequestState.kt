package com.muyuchat.core.engine.contract

/**
 * Lifecycle states of an MCA execution request.
 *
 * Enforces strict transitions across Kotlin owners, JNI bridge, and Native runtimes:
 *
 * Created
 *   -> Accepted
 *   -> Preparing
 *   -> Running
 *   -> CancelRequested
 *   -> DrainingCallbacks
 *   -> Succeeded | Failed | Cancelled | TimedOut | Aborted
 */
enum class RequestState {
    /**
     * Request has been instantiated locally but not yet accepted by the owning engine.
     */
    Created,

    /**
     * Request has been validated and queued/accepted by the engine owner.
     */
    Accepted,

    /**
     * Engine is allocating buffers, loading weights/prefix caches, or compiling graphs.
     */
    Preparing,

    /**
     * Engine is actively executing inference tokens or diffusion steps.
     */
    Running,

    /**
     * Cancellation was requested by the user or client.
     * Native execution has not yet stopped; input producers are paused.
     */
    CancelRequested,

    /**
     * Native execution is halting and in-flight buffered callbacks are being drained.
     */
    DrainingCallbacks,

    /**
     * Terminal state: Request completed successfully with full output.
     */
    Succeeded,

    /**
     * Terminal state: Request terminated due to an error.
     */
    Failed,

    /**
     * Terminal state: Request was cancelled and all native execution/callbacks cleanly drained.
     */
    Cancelled,

    /**
     * Terminal state: Request reached deadline and halted within safety thresholds.
     */
    TimedOut,

    /**
     * Terminal state: Execution was forcibly aborted (e.g. native unhealthy, unrecoverable timeout).
     */
    Aborted;

    /**
     * True if this state is an irreversible terminal state.
     */
    val isTerminal: Boolean
        get() = when (this) {
            Succeeded, Failed, Cancelled, TimedOut, Aborted -> true
            Created, Accepted, Preparing, Running, CancelRequested, DrainingCallbacks -> false
        }

    /**
     * True if the request is in an active non-terminal state.
     */
    val isActive: Boolean
        get() = !isTerminal

    /**
     * True if cancellation can be requested from this state.
     */
    val isCancellable: Boolean
        get() = when (this) {
            Created, Accepted, Preparing, Running -> true
            CancelRequested, DrainingCallbacks, Succeeded, Failed, Cancelled, TimedOut, Aborted -> false
        }

    /**
     * True if in a cancellation progression (requested, draining, or completed).
     */
    val isCancellingOrCancelled: Boolean
        get() = when (this) {
            CancelRequested, DrainingCallbacks, Cancelled -> true
            Created, Accepted, Preparing, Running, Succeeded, Failed, TimedOut, Aborted -> false
        }

    /**
     * Checks if transitioning from this state to [target] is valid according to the contract.
     */
    fun canTransitionTo(target: RequestState): Boolean {
        if (this == target) return false
        if (this.isTerminal) return false // Terminal states can never transition further

        return when (this) {
            Created -> target in setOf(Accepted, Cancelled, Aborted)
            Accepted -> target in setOf(Preparing, CancelRequested, Failed, Aborted)
            Preparing -> target in setOf(Running, CancelRequested, Failed, Aborted, TimedOut)
            Running -> target in setOf(CancelRequested, DrainingCallbacks, Succeeded, Failed, TimedOut, Aborted)
            CancelRequested -> target in setOf(DrainingCallbacks, Cancelled, Aborted, TimedOut)
            DrainingCallbacks -> target in setOf(Cancelled, Succeeded, Failed, Aborted, TimedOut)
            Succeeded, Failed, Cancelled, TimedOut, Aborted -> false
        }
    }
}
