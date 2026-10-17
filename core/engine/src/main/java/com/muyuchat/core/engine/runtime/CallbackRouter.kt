package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.ErrorPayload
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.TerminalPayload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Reliable record of a terminal event for one request attempt, independent of consumer delivery.
 * [delivered] distinguishes "the terminal was handed to the consumer" from "the terminal was
 * recorded but delivery failed", which is information a caller needs when it queries the terminal
 * state of a request whose stream broke.
 */
data class TerminalDeliveryRecord(
    val identity: RequestIdentity,
    val finalState: RequestState,
    val delivered: Boolean,
    val failure: String? = null
)

/**
 * Routes callbacks only to the exact subscribed request attempt.
 *
 * ## Ordering
 *
 * Acceptance and ordering are decided together: under the route's state monitor the router checks
 * the attempt identity and the monotonic sequence and *enqueues* the event. The first thread that
 * finds the route not draining becomes its single drainer and delivers from that queue in FIFO
 * order without holding the state monitor. A re-entrant event (the consumer routing another event
 * for the same request) is therefore appended behind everything already accepted and cannot overtake
 * it, and a concurrent producer cannot win a separate delivery lock out of order.
 *
 * ## Terminal records
 *
 * Terminal outcomes are stored per *attempt*, never per requestId, and are only updated by a
 * compare-update for the attempt that produced them. A late completion of a retired attempt
 * therefore cannot rewrite the record of the attempt that replaced it. The record table is bounded
 * (FIFO archive) instead of relying on a global clear, and [subscribe] scopes old-attempt records
 * away only for the requestId it is replacing.
 */
class CallbackRouter {

    private class RouteState(val identity: RequestIdentity, val consumer: EventConsumer) {
        /** Guards acceptance, ordering and the queue; never held while the consumer runs. */
        val stateLock = Any()
        val accepted = ArrayDeque<CallbackEvent>()
        var lastAcceptedSequence: Long = -1L
        var terminalAccepted: Boolean = false
        var draining: Boolean = false
        var acceptedCount: Long = 0L

        /**
         * Router generation in which this route's pending terminal record was published, or `-1`
         * when no record was published for it (the router was cleared first, or the terminal was
         * never accepted). Guarded by [recordLock]; read back by the completion so an in-flight
         * terminal can tell "my record was evicted by the bounded archive" from "the whole router
         * was cleared".
         */
        var publishedGeneration: Long = -1L
    }

    /** Attempt-scoped terminal record key. */
    private class TerminalKey(val identity: RequestIdentity) {
        override fun equals(other: Any?): Boolean =
            other is TerminalKey && identity.sameAttemptAs(other.identity)

        override fun hashCode(): Int {
            var result = identity.requestId.hashCode()
            result = 31 * result + identity.attemptId.hashCode()
            result = 31 * result + identity.sessionGeneration.hashCode()
            result = 31 * result + identity.runtimeEpoch.hashCode()
            result = 31 * result + identity.workerIncarnation.hashCode()
            return result
        }

        companion object {
            fun of(identity: RequestIdentity): TerminalKey = TerminalKey(identity)
        }
    }

    private val routes = ConcurrentHashMap<String, RouteState>()
    private val terminalRecords = ConcurrentHashMap<TerminalKey, TerminalDeliveryRecord>()
    private val terminalArchive = ConcurrentLinkedQueue<TerminalKey>()
    private val routedAttempt = ConcurrentHashMap<String, RequestIdentity>()
    private val droppedStaleEventsCount = AtomicLong(0)
    private val callbackFailuresCount = AtomicLong(0)

    /**
     * Guards terminal-record bookkeeping and the [clear] generation stamp.
     *
     * Publishing a record, completing one and clearing the table are three steps of one protocol:
     * a terminal accepted before [clear] must not be archived after it. Serializing them on this
     * monitor (never held while a consumer runs) makes "before clear" and "after clear" decidable,
     * which a plain concurrent map clear cannot express.
     */
    private val recordLock = Any()
    private var routerGeneration: Long = 0L

    /**
     * Subscribes [consumer] to the exact attempt [identity]. Records of superseded attempts for the
     * same requestId are scoped away so a reader of this requestId cannot observe them.
     */
    fun subscribe(identity: RequestIdentity, consumer: EventConsumer) {
        synchronized(recordLock) {
            routes[identity.requestId] = RouteState(identity, consumer)
            routedAttempt[identity.requestId] = identity
            pruneSupersededTerminalRecords(identity)
        }
    }

    /**
     * Identity-checked unsubscribe: only the attempt that owns the route can remove it, so a delayed
     * cleanup of a retired attempt cannot tear down its replacement's subscription.
     *
     * The requestId index entry is dropped together with the route **unless** a retained terminal
     * record still answers id-keyed terminal queries for that attempt; that keeps the index bounded
     * by `terminalRecords + live routes` without losing the documented id-query behaviour.
     */
    fun unsubscribe(identity: RequestIdentity) {
        synchronized(recordLock) {
            val route = routes[identity.requestId] ?: return
            if (route.identity.sameAttemptAs(identity)) {
                routes.remove(identity.requestId, route)
                if (terminalRecords[TerminalKey.of(identity)] == null) {
                    routedAttempt.remove(identity.requestId, identity)
                }
            }
        }
    }

    /**
     * Routes one event to the exact subscribed attempt.
     *
     * Returns true when the event was accepted into this route's delivery order. The event is
     * delivered by the route's single drainer (the accepting thread when the route is idle) before
     * this call returns for an idle route; a consumer failure is counted and recorded separately
     * rather than reported as a drop.
     */
    fun routeEvent(
        event: CallbackEvent,
        currentRuntimeEpoch: Long,
        currentSessionGeneration: Long
    ): Boolean {
        val eventIdentity = event.identity
        val route = routes[eventIdentity.requestId] ?: return dropStale()
        if (eventIdentity.runtimeEpoch < currentRuntimeEpoch ||
            eventIdentity.sessionGeneration < currentSessionGeneration) {
            return dropStale()
        }

        var drainer = false
        synchronized(route.stateLock) {
            if (routes[eventIdentity.requestId] !== route) return dropStale()
            if (!route.identity.sameAttemptAs(eventIdentity)) return dropStale()
            if (route.terminalAccepted) return dropStale()
            if (event.sequence <= route.lastAcceptedSequence) return dropStale()

            route.lastAcceptedSequence = event.sequence
            if (event.isTerminal) {
                route.terminalAccepted = true
                // The terminal record must exist *before* the event becomes visible to a drainer,
                // otherwise a drainer can deliver it first and the completion finds no record to
                // update (leaving delivered=false forever). Publishing a record is pure bookkeeping:
                // no user callback runs under this monitor.
                publishPendingTerminal(route, event)
            }
            route.accepted.addLast(event)
            route.acceptedCount++
            if (!route.draining) {
                route.draining = true
                drainer = true
            }
        }

        if (drainer) drain(route)
        return true
    }

    /** Single-drainer loop: FIFO delivery, never holding the state monitor while the consumer runs. */
    private fun drain(route: RouteState) {
        while (true) {
            val next = synchronized(route.stateLock) {
                val head = route.accepted.removeFirstOrNull()
                if (head == null) {
                    route.draining = false
                    return
                }
                head
            }
            deliver(route, next)
        }
    }

    private fun deliver(route: RouteState, event: CallbackEvent) {
        val failure = try {
            route.consumer.onEvent(event)
            null
        } catch (error: Exception) {
            callbackFailuresCount.incrementAndGet()
            error
        }
        if (event.isTerminal) completeTerminalRecord(route, event, failure)
    }

    /** Records the terminal before delivery so a throwing consumer cannot lose it. */
    private fun publishPendingTerminal(route: RouteState, event: CallbackEvent) {
        val key = TerminalKey.of(event.identity)
        synchronized(recordLock) {
            if (routes[event.identity.requestId] !== route) {
                // The router was cleared (shutdown) between acceptance and publication. The accepted
                // delivery still completes, but nothing may be archived for a cleared router.
                route.publishedGeneration = -1L
                return
            }
            route.publishedGeneration = routerGeneration
            terminalRecords.put(
                key,
                TerminalDeliveryRecord(
                    identity = event.identity,
                    finalState = terminalStateOf(event),
                    delivered = false
                )
            )
            terminalArchive.add(key)
            trimTerminalArchive(exempt = key)
        }
    }

    /**
     * Idempotent completion: only the attempt that owns the key may update its own record, a record
     * that already reported a delivery is never downgraded back to "pending", and a record that was
     * trimmed while the event was in flight is re-inserted as completed rather than silently lost --
     * **unless** the whole table was cleared meanwhile, in which case the in-flight completion must
     * not resurrect an outcome of a shut-down router.
     */
    private fun completeTerminalRecord(route: RouteState, event: CallbackEvent, failure: Exception?) {
        val key = TerminalKey.of(event.identity)
        val completed = TerminalDeliveryRecord(
            identity = event.identity,
            finalState = terminalStateOf(event),
            delivered = failure == null,
            failure = failure?.let { it.message ?: it::class.java.name }
        )
        synchronized(recordLock) {
            val existing = terminalRecords[key]
            if (existing == null) {
                if (route.publishedGeneration != routerGeneration) return
                terminalRecords.put(key, completed)
                terminalArchive.add(key)
                trimTerminalArchive(exempt = key)
                return
            }
            if (!existing.identity.sameAttemptAs(event.identity)) return
            if (existing.delivered) return
            terminalRecords.put(key, completed)
        }
    }

    /**
     * Publishes the bounded outcome record of an attempt that already reached a terminal state
     * without a routed terminal event (a broken consumer channel settles through the supervisor).
     *
     * Returns the record now in the table, or null when the attempt is not the router's current
     * attempt for that requestId -- which is also what makes this refuse to archive anything after
     * [clear]: a cleared router has no route for the attempt, so a late settlement cannot re-insert
     * an outcome into a shut-down router.
     */
    fun recordOutcome(
        identity: RequestIdentity,
        finalState: RequestState,
        failure: String? = null
    ): TerminalDeliveryRecord? {
        val key = TerminalKey.of(identity)
        synchronized(recordLock) {
            terminalRecords[key]?.let { existing ->
                if (existing.identity.sameAttemptAs(identity)) return existing
            }
            if (routes[identity.requestId]?.identity?.sameAttemptAs(identity) != true) return null
            val record = TerminalDeliveryRecord(
                identity = identity,
                finalState = finalState,
                delivered = false,
                failure = failure
            )
            terminalRecords.put(key, record)
            terminalArchive.add(key)
            trimTerminalArchive(exempt = key)
            return record
        }
    }

    fun droppedStaleCount(): Long = droppedStaleEventsCount.get()

    fun callbackFailureCount(): Long = callbackFailuresCount.get()

    /** Terminal record of the exact attempt [identity], or null when that attempt has none. */
    fun terminalRecord(identity: RequestIdentity): TerminalDeliveryRecord? {
        val record = terminalRecords[TerminalKey.of(identity)] ?: return null
        return if (record.identity.sameAttemptAs(identity)) record else null
    }

    /**
     * Terminal record for the attempt currently routed for [requestId], or null. An older attempt's
     * record is never returned here, so a late completion cannot make this read regress.
     */
    fun terminalRecord(requestId: String): TerminalDeliveryRecord? {
        val routed = routedAttempt[requestId] ?: return null
        return terminalRecord(routed)
    }

    /** Number of attempt-scoped terminal records currently retained. */
    fun terminalRecordCount(): Int = terminalRecords.size

    /**
     * Clears every route and every retained outcome.
     *
     * The generation is stamped **before** the tables are cleared and under the same monitor that
     * publishes and completes a record, so a terminal that was accepted before this call is either
     * already removed here or sees the new generation and refuses to archive itself afterwards.
     * Without that stamp an in-flight completion would re-insert its record right after `clear()`
     * returned, i.e. a shut-down router would keep resurrecting terminal state.
     */
    fun clear() {
        synchronized(recordLock) {
            routerGeneration++
            routes.clear()
            terminalRecords.clear()
            terminalArchive.clear()
            routedAttempt.clear()
        }
    }

    private fun dropStale(): Boolean {
        droppedStaleEventsCount.incrementAndGet()
        return false
    }

    private fun pruneSupersededTerminalRecords(identity: RequestIdentity) {
        val stale = terminalRecords.keys.filter { key ->
            key.identity.requestId == identity.requestId && !key.matches(identity)
        }
        for (key in stale) {
            terminalRecords.remove(key)
            terminalArchive.remove(key)
            routedAttempt.remove(key.identity.requestId, key.identity)
        }
    }

    /**
     * Bounded retention: the oldest records are dropped instead of relying on a global clear, and the
     * requestId→attempt index entry is evicted together with its record (compare-remove, so a newer
     * attempt that reused the id is never dropped by an older cleanup).
     */
    private fun trimTerminalArchive(exempt: TerminalKey) {
        while (terminalArchive.size > MAX_TERMINAL_RECORDS) {
            val oldest = terminalArchive.poll() ?: return
            if (oldest == exempt) continue
            if (terminalRecords.remove(oldest) != null) {
                routedAttempt.remove(oldest.identity.requestId, oldest.identity)
            }
        }
    }

    private fun TerminalKey.matches(identity: RequestIdentity): Boolean = equals(TerminalKey.of(identity))

    private fun terminalStateOf(event: CallbackEvent): RequestState {
        return when (val payload = event.payload) {
            is TerminalPayload -> payload.finalState
            is ErrorPayload -> RequestState.Failed
            else -> RequestState.Failed
        }
    }

    private companion object {
        const val MAX_TERMINAL_RECORDS = 128
    }
}
