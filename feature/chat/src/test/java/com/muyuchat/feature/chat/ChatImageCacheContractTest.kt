package com.muyuchat.feature.chat

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageCacheContractTest {
    @Test
    fun `cache budget is heap bounded and capped`() {
        assertEquals(32 * 1024 * 1024, chatImageDecodedCacheBudgetBytes(4L * 1024 * 1024 * 1024))
        assertEquals(16 * 1024 * 1024, chatImageDecodedCacheBudgetBytes(256L * 1024 * 1024))
        assertEquals(4 * 1024 * 1024, chatImageDecodedCacheBudgetBytes(64L * 1024 * 1024))
    }

    @Test
    fun `decode key separates source revision caller revision target and configuration`() {
        val base = ChatImageDecodeKey.create(
            resourceId = "content://images/1",
            revisionKey = 7L,
            sourceRevision = "size=10|modified=20",
            maxDimensionPx = 384
        )

        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(revisionToken = chatImageRevisionToken(8L)))
        assertNotEquals(base, base.copy(sourceRevision = "size=11|modified=21"))
        assertNotEquals(base, base.copy(maxDimensionPx = 512))
        assertNotEquals(base, base.copy(decodeConfig = "different-decoder"))
    }

    @Test
    fun `LRU evicts by accounted bytes using access order`() {
        val cache = ByteSizedLruCache<String, SizedValue>(
            maxBytes = 8,
            sizeOf = { it.bytes }
        )
        cache.put("a", SizedValue("a", 4))
        cache.put("b", SizedValue("b", 4))

        assertEquals("a", cache.get("a")?.id)
        cache.put("c", SizedValue("c", 4))

        assertEquals("a", cache.get("a")?.id)
        assertNull(cache.get("b"))
        assertEquals("c", cache.get("c")?.id)
        assertEquals(8L, cache.sizeBytes)
    }

    @Test
    fun `oversized successful value is not cached and does not evict useful entries`() {
        val cache = ByteSizedLruCache<String, SizedValue>(
            maxBytes = 8,
            sizeOf = { it.bytes }
        )
        cache.put("small", SizedValue("small", 4))
        cache.put("oversized", SizedValue("oversized", 9))

        assertEquals("small", cache.get("small")?.id)
        assertNull(cache.get("oversized"))
        assertEquals(4L, cache.sizeBytes)
    }

    @Test
    fun `same key in flight is deduplicated across concurrent consumers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val coordinator = ChatImageDecodeCoordinator<String, String>(
            cache = ByteSizedLruCache(1024) { 4 },
            maxConcurrentDecodes = 2,
            scope = scope,
            decode = {
                attempts.incrementAndGet()
                started.complete(Unit)
                release.await()
                "decoded"
            }
        )

        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.load("same-key")
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.load("same-key")
            }
            release.complete(Unit)

            assertEquals("decoded", first.await())
            assertEquals("decoded", second.await())
            assertEquals(1, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `decode concurrency never exceeds the configured bound`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val twoStarted = CompletableDeferred<Unit>()
        val releaseFirstWave = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val coordinator = ChatImageDecodeCoordinator<Int, Int>(
            cache = ByteSizedLruCache(1024) { 4 },
            maxConcurrentDecodes = 2,
            scope = scope,
            decode = { key ->
                val now = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    if (now == 2) twoStarted.complete(Unit)
                    releaseFirstWave.await()
                    key
                } finally {
                    active.decrementAndGet()
                }
            }
        )

        try {
            val jobs = (1..8).map { key ->
                async(start = CoroutineStart.UNDISPATCHED) { coordinator.load(key) }
            }
            twoStarted.await()
            assertEquals(2, maximumActive.get())
            releaseFirstWave.complete(Unit)

            assertEquals((1..8).toSet(), jobs.awaitAll().toSet())
            assertEquals(2, maximumActive.get())
            assertTrue(maximumActive.get() <= 2)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `one cancelled subscriber does not cancel another subscriber`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val coordinator = ChatImageDecodeCoordinator<String, String>(
            cache = ByteSizedLruCache(1024) { 4 },
            maxConcurrentDecodes = 1,
            scope = scope,
            decode = {
                attempts.incrementAndGet()
                started.complete(Unit)
                release.await()
                "shared"
            }
        )

        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.load("shared-key")
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.load("shared-key")
            }
            first.cancelAndJoin()
            release.complete(Unit)

            assertEquals("shared", second.await())
            assertEquals(1, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `last subscriber cancellation discards result and does not poison a later request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val firstResult = AtomicReference<String?>()
        val coordinator = ChatImageDecodeCoordinator<String, String>(
            cache = ByteSizedLruCache(1024) { 4 },
            maxConcurrentDecodes = 1,
            scope = scope,
            decode = {
                if (attempts.incrementAndGet() == 1) {
                    started.complete(Unit)
                    release.await()
                    "abandoned"
                } else {
                    "fresh"
                }
            }
        )

        try {
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                firstResult.set(coordinator.load("retry-key"))
            }
            started.await()
            first.cancelAndJoin()
            release.complete(Unit)

            assertEquals("fresh", coordinator.load("retry-key"))
            assertEquals(2, attempts.get())
            assertNull(firstResult.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `request slots bound distinct queued decodes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val coordinator = ChatImageDecodeCoordinator<Int, Int>(
            cache = ByteSizedLruCache(1024) { 4 },
            maxConcurrentDecodes = 1,
            scope = scope,
            decode = { key ->
                attempts.incrementAndGet()
                started.complete(Unit)
                release.await()
                key
            },
            maxPendingRequests = 1
        )

        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.load(1) }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.load(2) }
            assertEquals(1, attempts.get())

            release.complete(Unit)
            assertEquals(1, first.await())
            assertEquals(2, second.await())
            assertEquals(2, attempts.get())
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun `failed decode is retried instead of becoming a permanent cache entry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        val coordinator = ChatImageDecodeCoordinator<String, String>(
            cache = ByteSizedLruCache(1024) { 4 },
            maxConcurrentDecodes = 1,
            scope = scope,
            decode = {
                if (attempts.incrementAndGet() == 1) null else "recovered"
            }
        )

        try {
            assertNull(coordinator.load("failure-key"))
            assertEquals("recovered", coordinator.load("failure-key"))
            assertEquals(2, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    private data class SizedValue(val id: String, val bytes: Long)
}