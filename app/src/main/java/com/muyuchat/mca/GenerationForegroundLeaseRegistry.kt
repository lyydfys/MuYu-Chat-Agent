package com.muyuchat.mca

import com.muyuchat.mca.agent.platform.AgentStopAction

/** Opaque identity: a completed request can release only its own process-protection lease. */
internal class GenerationForegroundLease internal constructor()

/**
 * Serializes ownership and the Android start/stop dispatch together. Separating a last-owner
 * check from stopService would let a concurrent acquire start a replacement, then lose it to
 * the old stop. The callbacks are bounded service commands, never model or network operations.
 */
internal class GenerationForegroundLeaseRegistry {
    internal data class Snapshot(
        val kind: String,
        val ownerCount: Int,
        val agentStopAction: AgentStopAction? = null
    )

    private data class LeaseInfo(val kind: String, val agentStopAction: AgentStopAction?)

    private val lock = Any()
    private val owners = linkedMapOf<GenerationForegroundLease, LeaseInfo>()

    fun acquire(
        kind: String,
        agentStopAction: AgentStopAction? = null,
        start: () -> Unit
    ): GenerationForegroundLease = synchronized(lock) {
        val lease = GenerationForegroundLease()
        owners[lease] = LeaseInfo(kind, agentStopAction)
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
        val snapshot = owners.values.lastOrNull()?.let {
            Snapshot(it.kind, owners.size, it.agentStopAction)
        }
        block(snapshot)
    }
}
