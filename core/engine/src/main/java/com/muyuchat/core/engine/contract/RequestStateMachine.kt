package com.muyuchat.core.engine.contract

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Result of handling a callback event in the state machine.
 */
sealed interface EventProcessResult {
    data class Accepted(val state: RequestState, val isTerminal: Boolean) : EventProcessResult
    data class StaleRejected(val reason: String, val eventSeq: Long) : EventProcessResult
    data class DuplicateTerminalIgnored(val existingTerminalState: RequestState) : EventProcessResult
    data class InvalidTransition(val currentState: RequestState, val attemptedState: RequestState) : EventProcessResult
}

/**
 * Thread-safe state machine governing the execution lifecycle of a single request.
 *
 * Enforces five critical invariants:
 * 1. Terminal exactly-once: An irreversible terminal state can only be entered once.
 *    Any subsequent terminal event is logged as diagnostic and ignored.
 * 2. Stale callback rejection: Events carrying older epoch, generation, worker, or non-monotonic sequence are rejected.
 * 3. Cancel drain invariant: CancelRequested pauses inputs and waits for DrainingCallbacks before Cancelled terminal.
 * 4. Timeout abort invariant: Timed-out requests that fail to drain gracefully transition to Aborted.
 * 5. Monotonic sequence ordering: Sequence numbers within an attempt must strictly increase.
 */
class RequestStateMachine(
    val initialIdentity: RequestIdentity
) {
    private val stateRef = AtomicReference(RequestState.Created)
    private val identityRef = AtomicReference(initialIdentity)
    private val lastSeenSequence = AtomicLong(-1L)
    private val terminalCallbackCount = AtomicLong(0L)

    val currentState: RequestState
        get() = stateRef.get()

    val currentIdentity: RequestIdentity
        get() = identityRef.get()

    val isTerminal: Boolean
        get() = stateRef.get().isTerminal

    val duplicateTerminalCount: Long
        get() = (terminalCallbackCount.get() - 1L).coerceAtLeast(0L)

    /**
     * Attempts a state transition directly if valid.
     */
    fun transitionTo(target: RequestState): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current == target) return true
            if (!current.canTransitionTo(target)) return false
            if (stateRef.compareAndSet(current, target)) {
                return true
            }
        }
    }

    /**
     * Initiates cancellation.
     * Transitions from Created/Accepted/Preparing/Running to CancelRequested.
     */
    fun requestCancel(): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current.isTerminal) return false
            if (current == RequestState.CancelRequested || current == RequestState.DrainingCallbacks) {
                return true // already cancelling
            }
            if (!current.isCancellable) return false

            if (current == RequestState.Created) {
                // If not yet started, we can immediately cancel cleanly
                if (stateRef.compareAndSet(current, RequestState.Cancelled)) {
                    terminalCallbackCount.incrementAndGet()
                    return true
                }
            } else {
                if (stateRef.compareAndSet(current, RequestState.CancelRequested)) {
                    return true
                }
            }
        }
    }

    /**
     * Signals that native execution has acknowledged cancel and is draining queued callbacks.
     */
    fun beginDraining(): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current != RequestState.CancelRequested && current != RequestState.Running) return false
            if (stateRef.compareAndSet(current, RequestState.DrainingCallbacks)) {
                return true
            }
        }
    }

    /**
     * Finalizes cancellation after all callbacks are drained.
     */
    fun completeCancel(): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current != RequestState.DrainingCallbacks && current != RequestState.CancelRequested) return false
            if (stateRef.compareAndSet(current, RequestState.Cancelled)) {
                terminalCallbackCount.incrementAndGet()
                return true
            }
        }
    }

    /**
     * Aborts execution immediately due to timeout or unrecoverable error.
     */
    fun abort(): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current.isTerminal) return false
            if (stateRef.compareAndSet(current, RequestState.Aborted)) {
                terminalCallbackCount.incrementAndGet()
                return true
            }
        }
    }

    /**
     * Times out execution.
     */
    fun timeout(): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current.isTerminal) return false
            if (stateRef.compareAndSet(current, RequestState.TimedOut)) {
                terminalCallbackCount.incrementAndGet()
                return true
            }
        }
    }

    /**
     * Evaluates and processes an incoming callback event against the state machine.
     */
    @Synchronized
    fun processEvent(
        event: CallbackEvent,
        activeEpoch: Long,
        activeGeneration: Long,
        activeWorker: Long
    ): EventProcessResult {
        // 1. Reject callbacks from another request attempt before checking its environment.
        val currentIdentity = identityRef.get()
        if (!currentIdentity.matchesAttempt(event.identity)) {
            return EventProcessResult.StaleRejected(
                reason = "Callback identity does not match the active request attempt",
                eventSeq = event.sequence
            )
        }

        // 2. Check stale epoch/generation/worker
        if (event.isStaleFor(activeEpoch, activeGeneration, activeWorker)) {
            return EventProcessResult.StaleRejected(
                reason = "Stale environment: event[ep=${event.identity.runtimeEpoch}, gen=${event.identity.sessionGeneration}, wkr=${event.identity.workerIncarnation}] vs active[ep=$activeEpoch, gen=$activeGeneration, wkr=$activeWorker]",
                eventSeq = event.sequence
            )
        }

        // 3. Check monotonic sequence within this attempt
        val lastSeq = lastSeenSequence.get()
        if (event.sequence <= lastSeq) {
            return EventProcessResult.StaleRejected(
                reason = "Non-monotonic sequence: eventSeq=${event.sequence} <= lastSeen=$lastSeq",
                eventSeq = event.sequence
            )
        }

        // 4. Handle terminal events
        if (event.isTerminal) {
            val current = stateRef.get()
            if (current.isTerminal) {
                // Invariant 1: Terminal exactly-once. Repeated terminal is ignored.
                terminalCallbackCount.incrementAndGet()
                return EventProcessResult.DuplicateTerminalIgnored(current)
            }

            val targetState = when (event.payload) {
                is TerminalPayload -> (event.payload as TerminalPayload).finalState
                is ErrorPayload -> RequestState.Failed
                else -> RequestState.Succeeded
            }

            if (!transitionTo(targetState)) {
                return EventProcessResult.InvalidTransition(current, targetState)
            }

            lastSeenSequence.set(event.sequence)
            terminalCallbackCount.incrementAndGet()
            return EventProcessResult.Accepted(stateRef.get(), isTerminal = true)
        }

        // 5. Non-terminal event on an already terminal state is dropped as stale
        if (stateRef.get().isTerminal) {
            return EventProcessResult.StaleRejected(
                reason = "Received non-terminal event after terminal state: ${stateRef.get()}",
                eventSeq = event.sequence
            )
        }

        // 6. Update sequence
        lastSeenSequence.set(event.sequence)
        return EventProcessResult.Accepted(stateRef.get(), isTerminal = false)
    }

    private fun RequestIdentity.matchesAttempt(other: RequestIdentity): Boolean =
        requestId == other.requestId &&
            attemptId == other.attemptId &&
            sessionGeneration == other.sessionGeneration &&
            runtimeEpoch == other.runtimeEpoch &&
            workerIncarnation == other.workerIncarnation
}
