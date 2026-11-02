package com.muyuchat.mca.agent.composition

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs startup reconciliation once per process root, whichever entry reaches it first.
 *
 * The UI, a worker-only cold start and a future Local API entry share one Agent runtime. Only the
 * first caller reconciles; the others wait for it and then continue without repeating the scan.
 * A failed attempt is not marked complete, so the next entry retries instead of running on records
 * that were never settled.
 */
internal class StartupReconcileGate {
    private val mutex = Mutex()
    private var completed = false

    suspend fun runOnce(reconcile: suspend () -> Unit) {
        mutex.withLock {
            if (completed) return
            reconcile()
            completed = true
        }
    }
}
