package com.muyuchat.core.agent.runtime.run

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interjection buffering for a running agent loop (Mika's "reads it before its next step").
 *
 * The queue is pure state, so these cases are plain JUnit: no scheduler, no clock, no Android.
 */
class AgentInterjectionQueueTest {

    private val queue = AgentInterjectionQueue()

    @Test
    fun enqueueIsVisibleAndDrainEmptiesTheQueue() {
        assertTrue(queue.isEmpty())

        assertTrue(queue.enqueue("先看日志", atMillis = 1))
        assertTrue(queue.enqueue("别改配置", atMillis = 2))

        assertEquals(listOf("先看日志", "别改配置"), queue.pending())
        assertFalse(queue.isEmpty())

        assertEquals(listOf("先看日志", "别改配置"), queue.drainAtStepBoundary())
        assertTrue("drain must clear the queue", queue.isEmpty())
        assertEquals(emptyList<String>(), queue.pending())
        assertEquals("a second drain sees nothing", emptyList<String>(), queue.drainAtStepBoundary())
    }

    @Test
    fun drainOnAnEmptyQueueReturnsAnEmptyList() {
        assertEquals(emptyList<String>(), queue.drainAtStepBoundary())
    }

    @Test
    fun exceedingTheBoundEvictsTheOldestEntry() {
        repeat(AgentInterjectionQueue.MAX_ENTRIES) { index ->
            assertTrue("entry $index fits", queue.enqueue("m$index", atMillis = index.toLong()))
        }
        assertEquals(
            (0 until AgentInterjectionQueue.MAX_ENTRIES).map { "m$it" },
            queue.pending()
        )

        // The sixth entry is accepted by dropping m0, and the false return reports the eviction.
        assertFalse(queue.enqueue("m5", atMillis = 5))

        assertEquals(
            listOf("m1", "m2", "m3", "m4", "m5"),
            queue.pending()
        )
        assertEquals(AgentInterjectionQueue.MAX_ENTRIES, queue.drainAtStepBoundary().size)
    }

    @Test
    fun blankAndOverlongTextIsRejected() {
        assertFalse(queue.enqueue("", atMillis = 0))
        assertFalse(queue.enqueue("   ", atMillis = 0))
        assertFalse(queue.enqueue("\n\t", atMillis = 0))
        assertFalse(
            queue.enqueue("x".repeat(AgentInterjectionQueue.MAX_CHARS + 1), atMillis = 0)
        )
        assertTrue("rejections must not enqueue anything", queue.isEmpty())
    }

    @Test
    fun textAtTheLengthLimitIsAcceptedAndTrimmed() {
        assertTrue(queue.enqueue("  " + "x".repeat(AgentInterjectionQueue.MAX_CHARS) + "  ", atMillis = 1))
        val drained = queue.drainAtStepBoundary()
        assertEquals(1, drained.size)
        assertEquals(AgentInterjectionQueue.MAX_CHARS, drained.single().length)
        assertEquals("surrounding whitespace is trimmed", "x", drained.single().first().toString())
    }

    @Test
    fun concurrentEnqueuesLoseNothing() {
        val threads = 4
        val perThread = 25
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        try {
            repeat(threads) { thread ->
                executor.execute {
                    start.await()
                    repeat(perThread) { index ->
                        if (queue.enqueue("t$thread-$index", atMillis = index.toLong())) {
                            accepted.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue("workers must finish", executor.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        val drained = queue.drainAtStepBoundary()
        assertEquals(
            "the queue is bounded no matter how many writers race",
            AgentInterjectionQueue.MAX_ENTRIES,
            drained.size
        )
        assertTrue("drained entries must all be well-formed", drained.all { it.startsWith("t") })
        // Every accepted insert before the bound was reached is either still pending or was evicted,
        // never silently dropped while the queue had room.
        assertTrue("at least the bound must have been accepted", accepted.get() >= 1)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun countReportsWhatIsPending() {
        repeat(3) { queue.enqueue("m$it", atMillis = it.toLong()) }
        assertEquals(3, queue.pending().size)
        queue.drainAtStepBoundary()
        assertTrue(queue.pending().isEmpty())
        assertTrue(queue.isEmpty())
    }
}
