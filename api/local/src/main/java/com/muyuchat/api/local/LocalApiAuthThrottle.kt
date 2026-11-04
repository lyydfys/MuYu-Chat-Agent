package com.muyuchat.api.local

/**
 * Failure-based throttle for the LAN-facing Local API.
 *
 * The API listens on `0.0.0.0` once the user enables it, and its key is a long-lived secret shown in
 * the app. Without a throttle, a device on the same Wi-Fi can guess keys as fast as the network
 * allows, and every wrong guess costs a full request handler. This counts failures per client
 * address and refuses further attempts for a cool-down window.
 *
 * Design notes:
 * - Pure and clock-injected so the policy can be tested without sleeping.
 * - Success clears the counter, so a user who mistypes once is never locked out for long.
 * - `WINDOW` resets the counter when the previous failure is old enough; a slow attacker therefore
 *   still gets at most [MAX_FAILURES] attempts per window, but a legitimate client recovers quickly.
 * - Keyed by address, not by the presented key: guessing many keys from one address must trip it.
 */
internal class LocalApiAuthThrottle(
    private val maxFailures: Int = MAX_FAILURES,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val blockMillis: Long = BLOCK_MILLIS
) {
    private data class Entry(
        var failures: Int = 0,
        var firstFailureAt: Long = 0L,
        var blockedUntil: Long = 0L
    )

    private val entries = HashMap<String, Entry>()

    /** True when [client] must be refused without checking the presented key. */
    fun isBlocked(client: String, nowMillis: Long): Boolean {
        val entry = entries[client] ?: return false
        if (entry.blockedUntil == 0L) return false
        if (nowMillis < entry.blockedUntil) return true
        // Cool-down elapsed: start a fresh window instead of keeping the old failures forever.
        entries.remove(client)
        return false
    }

    /** Remaining cool-down in seconds for the Retry-After header; 0 when not blocked. */
    fun retryAfterSeconds(client: String, nowMillis: Long): Long {
        val entry = entries[client] ?: return 0L
        if (entry.blockedUntil == 0L || nowMillis >= entry.blockedUntil) return 0L
        return ((entry.blockedUntil - nowMillis) + 999L) / 1000L
    }

    /** Records one failed authentication; returns true when this failure tripped the block. */
    fun recordFailure(client: String, nowMillis: Long): Boolean {
        val entry = entries.getOrPut(client) { Entry(firstFailureAt = nowMillis) }
        if (nowMillis - entry.firstFailureAt > windowMillis) {
            entry.failures = 0
            entry.firstFailureAt = nowMillis
        }
        entry.failures += 1
        if (entry.failures < maxFailures) return false
        entry.blockedUntil = nowMillis + blockMillis
        return true
    }

    /** A successful authentication clears the client's history. */
    fun recordSuccess(client: String) {
        entries.remove(client)
    }

    companion object {
        /** Failures allowed inside one window before the client is blocked. */
        const val MAX_FAILURES: Int = 5

        /** How long a failure streak stays relevant. */
        const val WINDOW_MILLIS: Long = 60_000L

        /** Cool-down after tripping the throttle. */
        const val BLOCK_MILLIS: Long = 30_000L
    }
}
