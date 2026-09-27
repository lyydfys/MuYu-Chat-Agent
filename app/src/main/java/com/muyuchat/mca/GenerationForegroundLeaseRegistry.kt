package com.muyuchat.mca

/** Opaque identity: a completed request can release only its own process-protection lease. */
internal class GenerationForegroundLease internal constructor()

/**
 * Serializes ownership and the Android start/stop dispatch together. Separating a last-owner
 * check from stopService would let a concurrent acquire start a replacement, then lose it to
 * the old stop. The callbacks are bounded service commands, never model or network operations.
 */
internal class GenerationForegroundLeaseRegistry {
    internal data class Snapshot(val kind: String, val ownerCount: Int)

    private val lock = Any()
    private val owners = linkedMapOf<GenerationForegroundLease, String>()

    fun acquire(kind: String, start: () -> Unit): GenerationForegroundLease = synchronized(lock) {
        val lease = GenerationForegroundLease()
        owners[lease] = kind
        try {
            start()
        } catch (failure: Throwable) {
            owners.remove(lease)
            throw failure
        }
        lease
    }

    fun release(lease: GenerationForegroundLease, stop: () -> Unit): Boolean = synchronized(lock) {
        if (owners.remove(lease) == null) return@synchronized false
        if (owners.isEmpty()) stop()
        true
    }

    /** A delayed onStartCommand must promote current work, not an already-released Intent. */
    fun <T> withCurrentTask(block: (Snapshot?) -> T): T = synchronized(lock) {
        val snapshot = owners.values.lastOrNull()?.let { Snapshot(it, owners.size) }
        block(snapshot)
    }
}
