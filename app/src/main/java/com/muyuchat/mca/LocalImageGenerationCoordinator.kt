package com.muyuchat.mca

/**
 * Serializes product image requests across the UI and authenticated Local API.
 *
 * A lease is bound to one request and one epoch. A stale completion therefore
 * cannot release a newer request, and contention is reported before a worker
 * bind or native load is attempted.
 */
internal class LocalImageGenerationCoordinator {
    // The generated copy() must not expose the internal primary constructor to callers outside the
    // module: lease identity (not value equality) is what releases a request, and a publicly
    // constructible value-equal lease is exactly the shape that invariant must reject.
    // ConsistentCopyVisibility keeps copy() at the constructor's own visibility instead of
    // widening it, while remaining binary compatible with the class's existing internal surface.
    @ConsistentCopyVisibility
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
