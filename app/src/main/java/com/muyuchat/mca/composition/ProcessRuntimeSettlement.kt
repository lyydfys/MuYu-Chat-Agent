package com.muyuchat.mca.composition

/**
 * Decides when the shared process root may be settled.
 *
 * A worker-only process has no Activity, but a one-time wake and the periodic sweep can overlap.
 * The first worker to finish must therefore not close a root another worker is still using. The
 * counter and the close decision share one lock, so a worker either starts before the check (the
 * root stays) or after the close (it obtains a fresh root).
 */
internal class ProcessRuntimeSettlement(
    private val hasLiveActivities: () -> Boolean,
    private val closeRoot: () -> Unit,
    /**
     * The Local API foreground service is a user-enabled process surface. A scheduled wake that
     * finishes in a worker-only process must not tear it down; the service keeps its own process
     * alive and the root settles later when no Activity, worker or service remains.
     */
    private val hasLiveProcessService: () -> Boolean = { false }
) {
    private var backgroundWorkers = 0

    @Synchronized
    fun beginBackgroundWork() {
        backgroundWorkers += 1
    }

    @Synchronized
    fun finishBackgroundWork() {
        if (backgroundWorkers == 0) return
        backgroundWorkers -= 1
        settleIfIdleLocked()
    }

    @Synchronized
    fun settleIfIdle() {
        settleIfIdleLocked()
    }

    /** Explicit process exit (or a test hook): settles even while Activities/workers are active. */
    @Synchronized
    fun forceClose() {
        runCatching(closeRoot)
    }

    private fun settleIfIdleLocked() {
        if (backgroundWorkers == 0 && !hasLiveActivities() && !hasLiveProcessService()) {
            runCatching(closeRoot)
        }
    }
}
