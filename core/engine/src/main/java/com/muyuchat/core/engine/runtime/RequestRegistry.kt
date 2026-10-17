package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RequestStateMachine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable, read-only view of a registered request. Callers that only need to observe a request
 * must not hold mutable registry state; they never can: this type exposes no mutator.
 */
data class RequestSnapshot(
    val identity: RequestIdentity,
    val state: RequestState,
    val terminal: Boolean,
    val terminalError: ErrorEnvelope?,
    val registeredAtEpoch: Long,
    val registeredAtGeneration: Long
)

/**
 * Read-only projection of a request state machine. Observers may read the state; only the owning
 * [RequestRegistry] may drive transitions, so no external caller can bypass the registry.
 */
interface RequestStateView {
    val currentState: RequestState
    val isTerminal: Boolean
    val currentIdentity: RequestIdentity
    val duplicateTerminalCount: Long
}

/**
 * Registry holding active requests and enforcing the Exactly-Once Terminal invariant.
 *
 * ## Ownership
 *
 * The request state machine, the terminal flag and the terminal error live in a private entry and
 * are only ever mutated by this registry, under that entry's monitor. [getEntry] and [register]
 * return a read-only [RequestEntry] view, so a caller that only has a requestId (or even a live
 * entry) cannot move a request into a terminal state behind the registry's back.
 *
 * ## Identity-checked settlement
 *
 * Every asynchronous mutation ([transitionState], [markTerminal], [settleAndRemove],
 * [evictTerminal]) requires the full [RequestIdentity] of the attempt that owns the request and
 * compares it before settling. A delayed callback from a retired attempt therefore cannot settle,
 * cancel or remove a replacement attempt that reused the same requestId.
 */
class RequestRegistry {

    /** Mutable record. Never leaves this class except as a read-only [RequestEntry] view. */
    internal class Entry(
        val identity: RequestIdentity,
        val machine: RequestStateMachine = RequestStateMachine(identity),
        val terminalReached: AtomicBoolean = AtomicBoolean(false),
        val registeredAtEpoch: Long = identity.runtimeEpoch,
        val registeredAtGeneration: Long = identity.sessionGeneration,
        @Volatile var terminalError: ErrorEnvelope? = null,
        val creationTime: Long = System.currentTimeMillis()
    )

    /**
     * Read-only view handed to callers. Property names are kept from the previous mutable entry so
     * existing observers keep compiling, but the state machine is exposed as [RequestStateView]
     * (no transition/abort/requestCancel reachable) and the terminal flag is not an [AtomicBoolean].
     */
    class RequestEntry internal constructor(private val entry: Entry) {
        val identity: RequestIdentity get() = entry.identity
        val state: RequestState get() = entry.machine.currentState
        val terminal: Boolean get() = entry.terminalReached.get()
        val terminalError: ErrorEnvelope? get() = entry.terminalError
        val registeredAtEpoch: Long get() = entry.registeredAtEpoch
        val registeredAtGeneration: Long get() = entry.registeredAtGeneration
        val creationTime: Long get() = entry.creationTime

        private val machineView: RequestStateView = object : RequestStateView {
            override val currentState: RequestState get() = entry.machine.currentState
            override val isTerminal: Boolean get() = entry.machine.isTerminal
            override val currentIdentity: RequestIdentity get() = entry.machine.currentIdentity
            override val duplicateTerminalCount: Long get() = entry.machine.duplicateTerminalCount
        }

        val stateMachine: RequestStateView get() = machineView

        /** Consistent copy taken under the same monitor that settles the entry. */
        fun snapshot(): RequestSnapshot = snapshotOfEntry(entry)
    }

    /** Result of the terminal settlement that actually happened. */
    data class TerminalSettlement(
        val identity: RequestIdentity,
        val state: RequestState,
        val error: ErrorEnvelope?
    )

    private val activeRequests = ConcurrentHashMap<String, Entry>()

    /**
     * Registers a new request with its verified identity.
     * Throws IllegalStateException if requestId is already active.
     */
    fun register(identity: RequestIdentity): RequestEntry {
        val entry = Entry(identity = identity)
        val existing = activeRequests.putIfAbsent(identity.requestId, entry)
        check(existing == null) {
            "Request ${identity.requestId} is already registered and active."
        }
        return RequestEntry(entry)
    }

    /**
     * Read-only view of an entry if present. The returned view cannot mutate registry state.
     */
    fun getEntry(requestId: String): RequestEntry? = activeRequests[requestId]?.let(::RequestEntry)

    /**
     * Transitions the exact attempt [identity] to a non-terminal state.
     */
    fun transitionState(identity: RequestIdentity, targetState: RequestState): Boolean {
        if (targetState.isTerminal) return false
        val entry = ownedEntry(identity) ?: return false
        synchronized(entry) {
            if (entry.terminalReached.get()) return false
            return entry.machine.transitionTo(targetState)
        }
    }

    /**
     * Exactly-once terminal transition for the exact attempt [identity].
     * Returns true only for the call that settled the terminal state.
     */
    fun markTerminal(
        identity: RequestIdentity,
        terminalState: RequestState,
        error: ErrorEnvelope? = null
    ): Boolean = settle(identity, terminalState, error) != null

    /**
     * Exactly-once terminal settlement, returning what was settled (or null when the attempt is
     * unknown, was replaced, or had already reached a terminal state).
     */
    fun settle(
        identity: RequestIdentity,
        terminalState: RequestState,
        error: ErrorEnvelope? = null
    ): TerminalSettlement? {
        require(terminalState.isTerminal) {
            "State $terminalState is not a valid terminal state."
        }
        val entry = ownedEntry(identity) ?: return null
        synchronized(entry) {
            if (entry.terminalReached.get()) return null
            if (!entry.machine.transitionTo(terminalState)) return null

            entry.terminalError = error
            entry.terminalReached.set(true)
            return TerminalSettlement(entry.identity, terminalState, error)
        }
    }

    /**
     * Consistent read of the settled fields, taken under the entry monitor that terminal settlement
     * also uses, so a reader can never observe a terminal state with a stale flag or a missing error.
     */
    fun snapshot(requestId: String): RequestSnapshot? = activeRequests[requestId]?.let(::snapshotOf)

    /** Identity-checked snapshot: null when [identity] no longer owns that requestId. */
    fun snapshot(identity: RequestIdentity): RequestSnapshot? =
        ownedEntry(identity)?.let(::snapshotOf)

    /**
     * Checks if a request is currently active and non-terminal.
     */
    fun isActive(requestId: String): Boolean {
        val entry = activeRequests[requestId] ?: return false
        return synchronized(entry) { !entry.terminalReached.get() }
    }

    /**
     * Checks if a request has reached terminal status.
     */
    fun isTerminal(requestId: String): Boolean {
        val entry = activeRequests[requestId] ?: return false
        return synchronized(entry) { entry.terminalReached.get() }
    }

    /**
     * Removes a request that already reached a terminal state.
     *
     * An active request is deliberately refused here: removing it without settling would leave
     * running work unaccounted for. Owners that must abandon an active request call
     * [settleAndRemove], which settles the terminal state and removes it in one step.
     */
    fun evictTerminal(identity: RequestIdentity): RequestEntry? {
        val entry = ownedEntry(identity) ?: return null
        synchronized(entry) {
            if (!entry.terminalReached.get()) return null
        }
        return if (activeRequests.remove(identity.requestId, entry)) RequestEntry(entry) else null
    }

    /**
     * Explicit settlement + removal for the exact attempt [identity]. The request is settled
     * (default [RequestState.Aborted]) and only then removed, so "no longer registered" never means
     * "removed while still running".
     */
    fun settleAndRemove(
        identity: RequestIdentity,
        terminalState: RequestState = RequestState.Aborted,
        error: ErrorEnvelope? = null
    ): RequestEntry? {
        require(terminalState.isTerminal) {
            "State $terminalState is not a valid terminal state."
        }
        val entry = ownedEntry(identity) ?: return null
        synchronized(entry) {
            if (!entry.terminalReached.get() && entry.machine.transitionTo(terminalState)) {
                entry.terminalError = error
                entry.terminalReached.set(true)
            }
        }
        return if (activeRequests.remove(identity.requestId, entry)) RequestEntry(entry) else null
    }

    /**
     * Number of currently registered requests.
     */
    fun count(): Int = activeRequests.size

    /**
     * Drains all requests, marking any active non-terminal requests as Aborted.
     *
     * Administrative entry point: module-scoped on purpose. It settles by design (never removes),
     * and the runtime supervisor is its only production caller.
     */
    internal fun drainAll(reason: String = "Registry drained"): List<RequestEntry> {
        val snapshot = activeRequests.values.toList()
        for (entry in snapshot) {
            abortIfActive(entry)
        }
        return snapshot.map(::RequestEntry)
    }

    /**
     * Drains exactly the requests registered under an epoch older than [runtimeEpoch].
     *
     * A plain drain-everything would also abort a request that was registered for the new epoch
     * while the epoch advance was still running, which is a live request, not a stale one.
     */
    internal fun drainEpochsBefore(runtimeEpoch: Long, reason: String = "Registry drained"): List<RequestEntry> {
        val drained = mutableListOf<RequestEntry>()
        for (entry in activeRequests.values) {
            if (entry.registeredAtEpoch >= runtimeEpoch) continue
            if (abortIfActive(entry)) drained.add(RequestEntry(entry))
        }
        return drained
    }

    /**
     * Evicts every live entry after the registry has been drained, returning how many were removed.
     *
     * Shutdown-only administrative step: [drainAll] settles what is still active but deliberately
     * leaves the entries in place, so a shut-down runtime would keep reporting a non-zero active
     * request count. Dropping the live index afterwards is what makes "0 active request after
     * shutdown" observable; the terminal outcomes of the normal (non-shutdown) path are retained by
     * the callback router, which is not involved here.
     */
    internal fun clearAll(): Int {
        val removed = activeRequests.size
        activeRequests.clear()
        return removed
    }

    // ── same-module compatibility shims ─────────────────────────────────────────────────────
    // These exist for synchronous same-module callers (and the engine's own tests) that registered
    // the request in the same call stack. They are NOT part of the request-owner contract and are
    // not visible outside this module: asynchronous callers must pass the full RequestIdentity so a
    // retired attempt cannot settle its replacement. The two production facades use the
    // identity-taking overloads exclusively.

    internal fun transitionState(requestId: String, targetState: RequestState): Boolean =
        activeRequests[requestId]?.let { transitionState(it.identity, targetState) } ?: false

    internal fun markTerminal(
        requestId: String,
        terminalState: RequestState,
        error: ErrorEnvelope? = null
    ): Boolean = activeRequests[requestId]?.let { markTerminal(it.identity, terminalState, error) } ?: false

    private fun snapshotOf(entry: Entry): RequestSnapshot = snapshotOfEntry(entry)

    /** The entry only when [identity] is still the exact registered attempt for that requestId. */
    private fun ownedEntry(identity: RequestIdentity): Entry? {
        val entry = activeRequests[identity.requestId] ?: return null
        return if (entry.identity.sameAttemptAs(identity)) entry else null
    }

    /** Returns true when this call performed the abort transition for [entry]. */
    private fun abortIfActive(entry: Entry): Boolean {
        synchronized(entry) {
            if (entry.terminalReached.get()) return false
            if (!entry.machine.transitionTo(RequestState.Aborted)) return false
            entry.terminalReached.set(true)
            return true
        }
    }
}

/** Identity fields that define one execution attempt of a logical request. */
internal fun RequestIdentity.sameAttemptAs(other: RequestIdentity): Boolean =
    requestId == other.requestId &&
        attemptId == other.attemptId &&
        sessionGeneration == other.sessionGeneration &&
        runtimeEpoch == other.runtimeEpoch &&
        workerIncarnation == other.workerIncarnation

/**
 * Consistent read of the settled fields, taken under the entry monitor that terminal settlement also
 * uses, so a reader can never observe a terminal state with a stale flag or a missing error.
 */
private fun snapshotOfEntry(entry: RequestRegistry.Entry): RequestSnapshot = synchronized(entry) {
    RequestSnapshot(
        identity = entry.identity,
        state = entry.machine.currentState,
        terminal = entry.terminalReached.get(),
        terminalError = entry.terminalError,
        registeredAtEpoch = entry.registeredAtEpoch,
        registeredAtGeneration = entry.registeredAtGeneration
    )
}
