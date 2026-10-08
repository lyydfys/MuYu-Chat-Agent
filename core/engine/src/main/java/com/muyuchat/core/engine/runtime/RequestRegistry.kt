package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RequestStateMachine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registry holding active requests and enforcing Exactly-Once Terminal invariant.
 * Once a request enters a terminal state (Succeeded, Failed, Cancelled, Aborted),
 * no further terminal transitions or state mutations are allowed.
 */
class RequestRegistry {

    private val activeRequests = ConcurrentHashMap<String, RequestEntry>()

    data class RequestEntry(
        val identity: RequestIdentity,
        val stateMachine: RequestStateMachine = RequestStateMachine(identity),
        val terminalReached: AtomicBoolean = AtomicBoolean(false),
        val registeredAtEpoch: Long = identity.runtimeEpoch,
        val registeredAtGeneration: Long = identity.sessionGeneration,
        @Volatile var terminalError: ErrorEnvelope? = null,
        val creationTime: Long = System.currentTimeMillis()
    )

    /**
     * Registers a new request with its verified identity.
     * Throws IllegalStateException if requestId is already active.
     */
    fun register(identity: RequestIdentity): RequestEntry {
        val entry = RequestEntry(identity = identity)
        val existing = activeRequests.putIfAbsent(identity.requestId, entry)
        check(existing == null) {
            "Request ${identity.requestId} is already registered and active."
        }
        return entry
    }

    /**
     * Attempts to transition an active request to a non-terminal state.
     */
    fun transitionState(requestId: String, targetState: RequestState): Boolean {
        val entry = activeRequests[requestId] ?: return false
        if (targetState.isTerminal) return false
        synchronized(entry) {
            if (entry.terminalReached.get()) return false
            return entry.stateMachine.transitionTo(targetState)
        }
    }

    /**
     * Exactly-once terminal transition.
     * Guaranteed: Only the very first caller succeeds in recording a terminal state.
     * All subsequent calls return false and leave the original terminal state unmodified.
     */
    fun markTerminal(
        requestId: String,
        terminalState: RequestState,
        error: ErrorEnvelope? = null
    ): Boolean {
        require(terminalState.isTerminal) {
            "State $terminalState is not a valid terminal state."
        }
        val entry = activeRequests[requestId] ?: return false

        synchronized(entry) {
            if (entry.terminalReached.get()) return false
            if (!entry.stateMachine.transitionTo(terminalState)) return false

            entry.terminalError = error
            entry.terminalReached.set(true)
            return true
        }
    }

    /**
     * Retrieves an entry if present.
     */
    fun getEntry(requestId: String): RequestEntry? = activeRequests[requestId]

    /**
     * Checks if a request is currently active and non-terminal.
     */
    fun isActive(requestId: String): Boolean {
        val entry = activeRequests[requestId] ?: return false
        return !entry.terminalReached.get()
    }

    /**
     * Checks if a request has reached terminal status.
     */
    fun isTerminal(requestId: String): Boolean {
        val entry = activeRequests[requestId] ?: return false
        return entry.terminalReached.get()
    }

    /**
     * Removes and evicts a terminated request from active tracking.
     */
    fun evict(requestId: String): RequestEntry? {
        return activeRequests.remove(requestId)
    }

    /**
     * Number of currently registered requests.
     */
    fun count(): Int = activeRequests.size

    /**
     * Drains all requests, marking any active non-terminal requests as Aborted.
     */
    fun drainAll(reason: String = "Registry drained"): List<RequestEntry> {
        val snapshot = activeRequests.values.toList()
        for (entry in snapshot) {
            synchronized(entry) {
                if (!entry.terminalReached.get() &&
                    entry.stateMachine.transitionTo(RequestState.Aborted)) {
                    entry.terminalReached.set(true)
                }
            }
        }
        return snapshot
    }
}
