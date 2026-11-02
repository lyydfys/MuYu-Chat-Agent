package com.muyuchat.core.dsh.platform

/**
 * Bounded rate and concurrency admission for the App-local capability server.
 *
 * The policy is deliberately small and deterministic: a sliding request window plus a fixed
 * in-flight ceiling. A rejected call places the surface in a short cooldown so an abusive caller
 * cannot tight-loop on the loopback socket. A duplicate replay never reaches this policy because
 * the ledger answers first.
 *
 * One instance belongs to one server generation; the owner creates a fresh instance when it
 * rotates generation, so an old generation cannot consume the new budget.
 */
class DshCapabilityThrottle(
    private val maxRequestsPerWindow: Int = 120,
    private val windowMillis: Long = 60_000L,
    private val maxConcurrent: Int = 4,
    private val cooldownMillis: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(maxRequestsPerWindow >= 1) { "request window budget must be positive" }
        require(windowMillis >= 1L) { "request window must be positive" }
        require(maxConcurrent >= 1) { "concurrency limit must be positive" }
        require(cooldownMillis >= 0L) { "cooldown must be non-negative" }
    }

    private val lock = Any()
    private val requestTimes = ArrayDeque<Long>()
    private val activeLeases = LinkedHashSet<Long>()
    private var nextLeaseId = 1L
    private var cooldownUntilMillis = 0L

    /** Tries to admit one request. [DshCapabilityThrottleDecision.Admitted] owns a lease. */
    fun tryAcquire(): DshCapabilityThrottleDecision = synchronized(lock) {
        val now = clock()
        purgeExpired(now)
        if (cooldownUntilMillis > now) {
            return DshCapabilityThrottleDecision.RateLimited(cooldownUntilMillis - now)
        }
        if (activeLeases.size >= maxConcurrent) {
            cooldownUntilMillis = maxOf(cooldownUntilMillis, now + cooldownMillis)
            return DshCapabilityThrottleDecision.ConcurrencyLimited
        }
        if (requestTimes.size >= maxRequestsPerWindow) {
            val oldest = requestTimes.first()
            val windowRetryMillis = (oldest + windowMillis - now).coerceAtLeast(1L)
            cooldownUntilMillis = maxOf(cooldownUntilMillis, now + cooldownMillis)
            return DshCapabilityThrottleDecision.RateLimited(
                maxOf(windowRetryMillis, cooldownMillis)
            )
        }
        requestTimes.addLast(now)
        val leaseId = nextLeaseId++
        activeLeases.add(leaseId)
        DshCapabilityThrottleDecision.Admitted(leaseId)
    }

    /** Releases one admitted lease. Unknown ids are ignored, so a retry is harmless. */
    fun release(leaseId: Long): Boolean = synchronized(lock) {
        activeLeases.remove(leaseId)
    }

    /** In-flight requests right now (projection only). */
    fun activeLeaseCount(): Int = synchronized(lock) { activeLeases.size }

    /** Requests recorded inside the live window (projection only). */
    fun windowRequestCount(): Int = synchronized(lock) {
        purgeExpired(clock())
        requestTimes.size
    }

    private fun purgeExpired(nowMillis: Long) {
        val cutoff = nowMillis - windowMillis
        while (requestTimes.isNotEmpty() && requestTimes.first() <= cutoff) {
            requestTimes.removeFirst()
        }
    }
}

/** Outcome of [DshCapabilityThrottle.tryAcquire]. */
sealed interface DshCapabilityThrottleDecision {
    data class Admitted(val leaseId: Long) : DshCapabilityThrottleDecision

    /** The caller must back off for [retryAfterMillis]; nothing was admitted. */
    data class RateLimited(val retryAfterMillis: Long) : DshCapabilityThrottleDecision

    /** The in-flight ceiling was reached; nothing was admitted. */
    data object ConcurrencyLimited : DshCapabilityThrottleDecision
}
