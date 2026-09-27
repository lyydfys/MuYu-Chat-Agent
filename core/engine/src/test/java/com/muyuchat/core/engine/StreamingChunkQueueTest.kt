package com.muyuchat.core.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class StreamingChunkQueueTest {
    @Test fun cancellationReleasesProducerWaitingOnFullQueue() {
        val queue = StreamingChunkQueue(capacity = 1)
        queue.put("first")
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val producer = thread {
            started.countDown()
            queue.put("second")
            stopped.countDown()
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        queue.cancel()
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        assertNull(queue.next())
        producer.join(1_000)
    }

    @Test fun terminalFailureDoesNotNeedQueueCapacity() {
        val queue = StreamingChunkQueue(capacity = 1)
        queue.put(" \n")
        queue.finish("native_decode_failed")
        assertEquals(" \n", queue.next())
        try {
            queue.next()
            fail("Expected terminal failure after the existing delta")
        } catch (expected: IllegalStateException) {
            assertEquals("native_decode_failed", expected.message)
        }
    }

    @Test fun lateCallbackCannotWriteIntoReplacementQueue() {
        val old = StreamingChunkQueue(capacity = 1)
        val current = StreamingChunkQueue(capacity = 1)
        old.cancel()
        old.put("late")
        current.put("current")
        current.finish()
        assertEquals("current", current.next())
        assertNull(current.next())
    }
}
