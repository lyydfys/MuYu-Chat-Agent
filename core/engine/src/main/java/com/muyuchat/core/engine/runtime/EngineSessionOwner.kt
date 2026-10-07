package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.SessionGeneration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owner managing engine session lifecycles, session generations, KV/prefix cache isolation,
 * and single-concurrency execution leases per session.
 */
class EngineSessionOwner(
    override val sessionId: String,
    initialGeneration: Long = 1L
) : SessionPort {

    private val generationCounter = AtomicLong(initialGeneration)
    private val executionLock = ReentrantLock()
    private val activeLeaseRef = AtomicBoolean(false)
    private val cachedPrefixEntries = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var isClosed = false

    override val currentGeneration: SessionGeneration
        get() = SessionGeneration(generationCounter.get())

    override val isLeaseActive: Boolean
        get() = activeLeaseRef.get()

    /**
     * Attempts to acquire an exclusive execution lease for a request within this session.
     * Invariant: Only one request may actively execute in a session at any given moment.
     */
    override fun tryAcquireExecutionLease(identity: RequestIdentity): ExecutionLease? {
        if (isClosed) return null

        if (!executionLock.tryLock()) {
            return null
        }

        if (!activeLeaseRef.compareAndSet(false, true)) {
            executionLock.unlock()
            return null
        }

        return object : ExecutionLease {
            private val cancelled = AtomicBoolean(false)
            override val identity: RequestIdentity = identity.withGeneration(generationCounter.get())
            override val isCancelled: Boolean get() = cancelled.get()

            override fun requestCancel(reason: String) {
                cancelled.set(true)
            }

            override fun close() {
                try {
                    activeLeaseRef.set(false)
                } finally {
                    if (executionLock.isHeldByCurrentThread) {
                        executionLock.unlock()
                    }
                }
            }
        }
    }

    /**
     * Increments the session generation, effectively isolating and invalidating all prior KV cache entries.
     */
    override fun advanceGeneration(): SessionGeneration {
        executionLock.withLock {
            val next = generationCounter.incrementAndGet()
            cachedPrefixEntries.clear()
            return SessionGeneration(next)
        }
    }

    /**
     * Stores a prefix cache entry bounded to the current session generation.
     */
    fun storePrefixCache(key: String, payload: ByteArray) {
        if (isClosed) return
        cachedPrefixEntries[key] = payload
    }

    /**
     * Retrieves a prefix cache entry.
     */
    fun getPrefixCache(key: String): ByteArray? {
        if (isClosed) return null
        return cachedPrefixEntries[key]
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
        executionLock.withLock {
            cachedPrefixEntries.clear()
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        releaseAllResources()
    }
}
