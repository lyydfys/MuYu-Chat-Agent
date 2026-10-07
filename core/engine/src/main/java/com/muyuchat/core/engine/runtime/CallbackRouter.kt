package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Routes asynchronous callback events from native and worker pipelines to subscribers.
 * Invariants:
 * 1. Strict stale detection: Events with runtimeEpoch < currentEpoch or sessionGeneration < currentGeneration are dropped.
 * 2. Native sequence ordering: Events must arrive with strictly non-decreasing sequence numbers per request.
 * 3. Terminal exactly-once: Once a terminal event is routed, no further events for that attempt are routed.
 */
class CallbackRouter {

    private val subscribers = ConcurrentHashMap<String, EventConsumer>()
    private val lastSeenSequence = ConcurrentHashMap<String, AtomicLong>()
    private val terminalDispatched = ConcurrentHashMap<String, Boolean>()
    private val droppedStaleEventsCount = AtomicLong(0)

    /**
     * Registers a listener for events of a specific request ID.
     */
    fun subscribe(requestId: String, consumer: EventConsumer) {
        subscribers[requestId] = consumer
        lastSeenSequence.putIfAbsent(requestId, AtomicLong(-1L))
    }

    /**
     * Unregisters a listener for a request ID.
     */
    fun unsubscribe(requestId: String) {
        subscribers.remove(requestId)
        lastSeenSequence.remove(requestId)
        terminalDispatched.remove(requestId)
    }

    /**
     * Routes an incoming callback event.
     * Returns true if event was valid and dispatched, false if dropped as stale, out-of-order, or post-terminal.
     */
    fun routeEvent(
        event: CallbackEvent,
        currentRuntimeEpoch: Long,
        currentSessionGeneration: Long
    ): Boolean {
        val identity = event.identity
        val reqId = identity.requestId

        // 1. Stale runtime epoch or session generation verification
        if (identity.runtimeEpoch < currentRuntimeEpoch) {
            droppedStaleEventsCount.incrementAndGet()
            return false
        }
        if (identity.sessionGeneration < currentSessionGeneration) {
            droppedStaleEventsCount.incrementAndGet()
            return false
        }

        // 2. Post-terminal check
        if (terminalDispatched[reqId] == true) {
            droppedStaleEventsCount.incrementAndGet()
            return false
        }

        // 3. Sequence monotonicity check
        val seqTracker = lastSeenSequence[reqId]
        if (seqTracker != null) {
            val lastSeq = seqTracker.get()
            if (event.sequence <= lastSeq) {
                // Out of order or duplicate sequence dropped
                droppedStaleEventsCount.incrementAndGet()
                return false
            }
            seqTracker.set(event.sequence)
        }

        // 4. Mark terminal dispatch
        if (event.isTerminal) {
            terminalDispatched[reqId] = true
        }

        // 5. Dispatch to consumer
        val consumer = subscribers[reqId] ?: return false
        consumer.onEvent(event)
        return true
    }

    /**
     * Returns the total count of stale or out-of-order dropped events.
     */
    fun droppedStaleCount(): Long = droppedStaleEventsCount.get()

    /**
     * Clears all subscriptions.
     */
    fun clear() {
        subscribers.clear()
        lastSeenSequence.clear()
        terminalDispatched.clear()
    }
}
