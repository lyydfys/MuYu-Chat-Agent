package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Routes callbacks only to the exact subscribed request attempt. */
class CallbackRouter {

    private data class RouteState(
        val identity: RequestIdentity,
        val consumer: EventConsumer,
        var lastSequence: Long = -1L,
        var terminalDispatched: Boolean = false
    )

    private val routes = ConcurrentHashMap<String, RouteState>()
    private val droppedStaleEventsCount = AtomicLong(0)
    private val callbackFailuresCount = AtomicLong(0)

    fun subscribe(identity: RequestIdentity, consumer: EventConsumer) {
        routes[identity.requestId] = RouteState(identity, consumer)
    }

    fun unsubscribe(requestId: String) {
        routes.remove(requestId)
    }

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

        synchronized(route) {
            if (routes[eventIdentity.requestId] !== route || !route.identity.matchesAttempt(eventIdentity)) {
                return dropStale()
            }
            if (route.terminalDispatched) return dropStale()
            if (event.sequence <= route.lastSequence) return dropStale()

            route.lastSequence = event.sequence
            if (event.isTerminal) route.terminalDispatched = true

            return try {
                route.consumer.onEvent(event)
                true
            } catch (_: Exception) {
                callbackFailuresCount.incrementAndGet()
                false
            }
        }
    }

    fun droppedStaleCount(): Long = droppedStaleEventsCount.get()

    fun callbackFailureCount(): Long = callbackFailuresCount.get()

    fun clear() {
        routes.clear()
    }

    private fun dropStale(): Boolean {
        droppedStaleEventsCount.incrementAndGet()
        return false
    }

    private fun RequestIdentity.matchesAttempt(other: RequestIdentity): Boolean =
        requestId == other.requestId &&
            attemptId == other.attemptId &&
            sessionGeneration == other.sessionGeneration &&
            runtimeEpoch == other.runtimeEpoch &&
            workerIncarnation == other.workerIncarnation
}
