package com.muyuchat.mca

/**
 * Serializes product image requests across the UI and authenticated Local API.
 *
 * A lease is bound to one request and one epoch. A stale completion therefore
 * cannot release a newer request, and contention is reported before a worker
 * bind or native load is attempted.
 */
internal class LocalImageGenerationCoordinator {
    internal data class Lease internal constructor(
        val requestId: String,
        internal val epoch: Long
    )

    private val lock = Any()
    private var nextEpoch = 0L
    private var active: Lease? = null

    fun tryAcquire(requestId: String): Lease? {
        require(requestId.isNotBlank()) { "Image generation requestId must not be blank." }
        return synchronized(lock) {
            if (active != null) return@synchronized null
            check(nextEpoch < Long.MAX_VALUE) { "Image generation lease epoch exhausted." }
            Lease(requestId = requestId, epoch = ++nextEpoch).also { active = it }
        }
    }

    fun release(lease: Lease): Boolean = synchronized(lock) {
        if (active !== lease) return@synchronized false
        active = null
        true
    }

    fun activeRequestId(): String? = synchronized(lock) { active?.requestId }
}
