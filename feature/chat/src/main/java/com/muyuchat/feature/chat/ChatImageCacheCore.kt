package com.muyuchat.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val CHAT_IMAGE_DECODE_CONFIG = "bitmapfactory:default:argb8888:v1"
internal const val CHAT_IMAGE_CACHE_MAX_BYTES = 32L * 1024L * 1024L
internal const val CHAT_IMAGE_CACHE_HEAP_DIVISOR = 16L

/**
 * The decoded UI cache is an evaluation budget, not an admission rule. Keep it below both a
 * small fraction of the app heap and the explicit ceiling; very small processes simply get a
 * smaller cache.
 */
internal fun chatImageDecodedCacheBudgetBytes(maxHeapBytes: Long): Int {
    require(maxHeapBytes > 0L) { "maxHeapBytes must be positive" }
    return (maxHeapBytes / CHAT_IMAGE_CACHE_HEAP_DIVISOR)
        .coerceAtMost(CHAT_IMAGE_CACHE_MAX_BYTES)
        .coerceAtLeast(1L)
        .toInt()
}

internal fun chatImageRevisionToken(revision: Any?): String {
    if (revision == null) return "none"
    val type = revision.javaClass.name
    val value = runCatching { revision.toString() }
        .getOrElse { "unprintable@${System.identityHashCode(revision)}" }
    return "$type:$value"
}

internal data class ChatImageDecodeKey(
    val resourceId: String,
    val revisionToken: String,
    val sourceRevision: String?,
    val maxDimensionPx: Int?,
    val decodeConfig: String = CHAT_IMAGE_DECODE_CONFIG
) {
    companion object {
        fun create(
            resourceId: String,
            revisionKey: Any?,
            sourceRevision: String?,
            maxDimensionPx: Int?
        ): ChatImageDecodeKey = ChatImageDecodeKey(
            resourceId = resourceId,
            revisionToken = chatImageRevisionToken(revisionKey),
            sourceRevision = sourceRevision,
            maxDimensionPx = maxDimensionPx?.coerceAtLeast(1)
        )
    }
}

/**
 * Thread-safe access-order LRU. Values are removed without any recycling callback because an
 * evicted bitmap may still be referenced by Compose, Canvas or a GPU upload.
 */
internal class ByteSizedLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long
) {
    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var accountedBytes = 0L

    val sizeBytes: Long
        @Synchronized get() = accountedBytes

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries.remove(key)?.let { previous ->
            accountedBytes -= sizeOf(previous).coerceAtLeast(1L)
        }
        val valueBytes = sizeOf(value).coerceAtLeast(1L)
        if (valueBytes > maxBytes) return

        entries[key] = value
        accountedBytes += valueBytes
        val iterator = entries.entries.iterator()
        while (accountedBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            accountedBytes -= sizeOf(eldest.value).coerceAtLeast(1L)
            iterator.remove()
        }
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)?.also { removed ->
        accountedBytes -= sizeOf(removed).coerceAtLeast(1L)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        accountedBytes = 0L
    }
}

/**
 * Deduplicates one decode per key, limits blocking decode work, and only publishes a result while
 * at least one consumer is still subscribed. Failed results are never cached.
 */
internal class ChatImageDecodeCoordinator<K : Any, V : Any>(
    private val cache: ByteSizedLruCache<K, V>,
    maxConcurrentDecodes: Int,
    private val scope: CoroutineScope,
    private val decode: suspend (K) -> V?,
    maxPendingRequests: Int = maxConcurrentDecodes * 16
) {
    init {
        require(maxConcurrentDecodes > 0) { "maxConcurrentDecodes must be positive" }
        require(maxPendingRequests >= maxConcurrentDecodes) {
            "maxPendingRequests must cover the decode concurrency"
        }
    }

    private class InFlight<V : Any> {
        val result = CompletableDeferred<V?>()
        var subscribers = 1
        var abandoned = false
        var completed = false
        var job: Job? = null
    }

    private val lock = Any()
    private val decodePermits = Semaphore(maxConcurrentDecodes)
    private val requestSlots = Semaphore(maxPendingRequests)
    private val inFlight = mutableMapOf<K, InFlight<V>>()

    suspend fun load(key: K): V? {
        cache.get(key)?.let { return it }
        return requestSlots.withPermit { loadWithSlot(key) }
    }

    private suspend fun loadWithSlot(key: K): V? {
        var selected: InFlight<V>? = null
        var startJob = false
        synchronized(lock) {
            cache.get(key)?.let { return it }
            val current = inFlight[key]
            if (current != null && !current.abandoned && !current.completed) {
                current.subscribers += 1
                selected = current
            } else {
                val created = InFlight<V>()
                inFlight[key] = created
                created.job = scope.launch(start = CoroutineStart.LAZY) {
                    runDecode(key, created)
                }
                selected = created
                startJob = true
            }
        }

        val slot = checkNotNull(selected)
        if (startJob) slot.job?.start()
        return try {
            slot.result.await()
        } finally {
            releaseSubscriber(key, slot)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private suspend fun runDecode(key: K, slot: InFlight<V>) {
        val value = try {
            decodePermits.withPermit { decode(key) }.also {
                currentCoroutineContext().ensureActive()
            }
        } catch (cancelled: CancellationException) {
            finish(key, slot, null)
            throw cancelled
        } catch (_: Throwable) {
            finish(key, slot, null)
            return
        }
        finish(key, slot, value)
    }

    private fun finish(key: K, slot: InFlight<V>, value: V?) {
        var completeResult: CompletableDeferred<V?>? = null
        var cancelResult: CompletableDeferred<V?>? = null
        synchronized(lock) {
            if (slot.completed) return
            slot.completed = true
            if (inFlight[key] === slot) inFlight.remove(key)
            if (!slot.abandoned && slot.subscribers > 0) {
                if (value != null) cache.put(key, value)
                completeResult = slot.result
            } else {
                cancelResult = slot.result
            }
        }
        if (completeResult != null) {
            completeResult.complete(value)
        } else {
            cancelResult?.cancel()
        }
    }

    private fun releaseSubscriber(key: K, slot: InFlight<V>) {
        var cancelJob: Job? = null
        synchronized(lock) {
            if (slot.subscribers > 0) slot.subscribers -= 1
            if (slot.subscribers == 0 && !slot.completed) {
                slot.abandoned = true
                if (inFlight[key] === slot) inFlight.remove(key)
                cancelJob = slot.job
            }
        }
        cancelJob?.cancel()
    }
}