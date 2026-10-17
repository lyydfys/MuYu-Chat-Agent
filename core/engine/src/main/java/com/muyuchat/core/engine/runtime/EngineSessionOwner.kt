package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.SessionGeneration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owner managing engine session lifecycles, session generations, KV/prefix cache isolation,
 * and single-concurrency execution leases per session.
 *
 * The execution lease is an opaque token held in an [AtomicReference], not a thread-affine
 * `ReentrantLock`: a lease may be acquired on one thread and released on another, and the lock is
 * never held for the duration of the async request. Acquisition and release are single CAS
 * operations, so neither path can block a caller or corrupt a later lease.
 */
class EngineSessionOwner(
    override val sessionId: String,
    initialGeneration: Long = 1L
) : SessionPort {

    private val generationCounter = AtomicLong(initialGeneration)
    private val generationLock = Any()

    /**
     * The single live lease token, or null when the session is idle. Holding the token *is* the
     * lease; releasing compares the token back out, which is what makes a delayed close of an old
     * lease unable to release its successor.
     */
    private val activeLease = AtomicReference<LeaseToken?>(null)

    /** Prefix cache entries stamped with the generation that produced them (see [CacheEntry]). */
    private val cachedPrefixEntries = ConcurrentHashMap<String, CacheEntry>()
    @Volatile private var isClosed = false

    /**
     * Serializes session close against lease acquisition.
     *
     * The closed flag alone was not enough: `tryAcquireExecutionLease` read it once before the CAS
     * and once after, so a `close()` landing between the second read and the return handed the caller
     * a lease on an already closed session -- a lease that the session would never take back.
     * Making the closed check and the token CAS one critical section removes that window. The lock is
     * never held while a lease is in use, so a long-running request cannot block close.
     */
    private val lifecycleLock = Any()

    override val currentGeneration: SessionGeneration
        get() = SessionGeneration(generationCounter.get())

    override val isLeaseActive: Boolean
        get() = activeLease.get() != null

    /**
     * Attempts to acquire an exclusive execution lease for a request within this session.
     * Invariant: Only one request may actively execute in a session at any given moment, and a
     * closed session never hands out a lease.
     */
    override fun tryAcquireExecutionLease(identity: RequestIdentity): ExecutionLease? {
        val token: LeaseToken
        synchronized(lifecycleLock) {
            if (isClosed) return null
            token = LeaseToken(identity.withGeneration(generationCounter.get()))
            if (!activeLease.compareAndSet(null, token)) return null
        }
        return SessionExecutionLease(token)
    }

    /**
     * Lease whose release is idempotent and thread-agnostic: only the token owner can clear the
     * token, and a repeat close is a no-op rather than a release of somebody else's lease.
     */
    private inner class SessionExecutionLease(private val token: LeaseToken) : ExecutionLease {
        private val cancelled = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        override val identity: RequestIdentity = token.identity
        override val isCancelled: Boolean get() = cancelled.get()

        override fun requestCancel(reason: String) {
            cancelled.set(true)
        }

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            activeLease.compareAndSet(token, null)
        }
    }

    private class LeaseToken(val identity: RequestIdentity)

    /**
     * Generation-stamped prefix cache entry. Storing the generation with the payload lets reads
     * reject entries that belong to a superseded generation even before the map is cleared.
     */
    private data class CacheEntry(val generation: Long, val payload: ByteArray)

    /**
     * Increments the session generation, effectively isolating and invalidating all prior KV cache entries.
     *
     * This deliberately does not wait for an active execution lease: generation advance is session
     * bookkeeping, and blocking it on a long-running request would stall every unrelated caller.
     */
    override fun advanceGeneration(): SessionGeneration = synchronized(generationLock) {
        val next = generationCounter.incrementAndGet()
        cachedPrefixEntries.clear()
        SessionGeneration(next)
    }

    /**
     * Stores a prefix cache entry for the current session generation.
     *
     * A write coming from a lease that belongs to a superseded generation is **refused**: an old
     * execution must not be able to backfill the cache after `advanceGeneration` invalidated it. A
     * write without an active lease (administrative/loading path) is stamped with the current
     * generation. Returns true when the entry was stored.
     */
    fun storePrefixCache(key: String, payload: ByteArray): Boolean {
        if (isClosed) return false
        val generation = generationCounter.get()
        val lease = activeLease.get()
        if (lease != null && lease.identity.sessionGeneration != generation) return false
        cachedPrefixEntries[key] = CacheEntry(generation, payload)
        return true
    }

    /**
     * Retrieves a prefix cache entry of the **current** generation. An entry stored by a superseded
     * generation is never visible, even if the clear and this read interleaved.
     */
    fun getPrefixCache(key: String): ByteArray? {
        if (isClosed) return null
        val entry = cachedPrefixEntries[key] ?: return null
        return if (entry.generation == generationCounter.get()) entry.payload else null
    }

    /**
     * Checks if input prompt fits within the configured context window budget.
     */
    fun fitsInContextWindow(estimatedTokens: Int, maxContextBudget: Int): Boolean {
        if (maxContextBudget <= 0) return true
        return estimatedTokens <= maxContextBudget
    }

    /**
     * Evicts all cached state in this session.
     */
    override fun releaseAllResources() {
        cachedPrefixEntries.clear()
    }

    /**
     * Closes the session. Idempotent, and mutually exclusive with lease acquisition: once this
     * returns, no lease can be handed out. An execution lease that was already granted is
     * deliberately **not** force-released here -- the in-flight holder keeps its token until it
     * releases it, so resources of a running request are never freed ahead of its own drain.
     */
    override fun close() {
        synchronized(lifecycleLock) {
            if (isClosed) return
            isClosed = true
        }
        releaseAllResources()
    }
}
