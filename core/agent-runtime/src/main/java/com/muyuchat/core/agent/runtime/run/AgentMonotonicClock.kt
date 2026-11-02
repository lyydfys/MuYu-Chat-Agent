package com.muyuchat.core.agent.runtime.run

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic millisecond clock for every budget, wait, timeout and TTL decision.
 *
 * Wall-clock dates are never used for durations, so user or NTP clock changes cannot extend a
 * budget or approval lifetime.
 */
fun interface AgentMonotonicClock {
    fun nowMillis(): Long
}

/** Production monotonic clock based on [System.nanoTime]. */
object AgentSystemMonotonicClock : AgentMonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}

/** Deterministic clock for unit tests; advancing is explicit and never wall-clock dependent. */
class ManualAgentMonotonicClock(startMillis: Long = 1_000_000L) : AgentMonotonicClock {
    private val current = AtomicLong(startMillis)

    override fun nowMillis(): Long = current.get()

    fun advanceMillis(deltaMillis: Long) {
        require(deltaMillis >= 0L) { "manual clock cannot move backwards" }
        current.addAndGet(deltaMillis)
    }
}
