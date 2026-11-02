package com.muyuchat.mca.composition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCloseSequenceTest {

    @Test
    fun `every step runs once in declaration order`() {
        val events = ArrayList<String>()
        val sequence = ProcessCloseSequence(
            "local-api" to { events += "local-api" },
            "dsh" to { events += "dsh" },
            "agent" to { events += "agent" },
            "scope" to { events += "scope" }
        )

        assertTrue(sequence.close().isEmpty())
        assertTrue(sequence.close().isEmpty())

        assertEquals(listOf("local-api", "dsh", "agent", "scope"), events)
    }

    @Test
    fun `a failing step does not skip the remaining resources`() {
        val events = ArrayList<String>()
        val failure = IllegalStateException("dsh close failed")
        val sequence = ProcessCloseSequence(
            "dsh" to { throw failure },
            "agent" to { events += "agent" },
            "scope" to { events += "scope" }
        )

        val failures = sequence.close()

        assertEquals(listOf("agent", "scope"), events)
        assertEquals(1, failures.size)
        assertEquals("dsh", failures.single().step)
        assertEquals(failure, failures.single().error)
    }

    @Test
    fun `concurrent settlement runs the steps once`() {
        val runs = AtomicInteger(0)
        val sequence = ProcessCloseSequence(
            "agent" to { runs.incrementAndGet() }
        )
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                pool.execute {
                    start.await()
                    sequence.close()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("all settlers finish", done.await(5, TimeUnit.SECONDS))
            assertEquals("the close action runs exactly once", 1, runs.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
