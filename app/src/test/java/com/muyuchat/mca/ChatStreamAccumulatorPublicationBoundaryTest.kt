package com.muyuchat.mca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supplementary boundary tests for [ChatStreamAccumulator].
 *
 * Complements `ChatStreamAccumulatorContractTest` (which covers the thresholds, the superseded-run
 * cases and the high-water mark). This file adds only the gaps around **what a pending publication
 * may still do after the buffer it belongs to has been drained, discarded or superseded** -- the
 * window where a late coroutine could otherwise append stale text into a newer message.
 *
 * The publish job runs in the caller's scope, so `delay(...)` below is what lets a scheduled
 * publication actually attempt to deliver. No `kotlinx-coroutines-test` and no new dependency: the
 * clock is injected exactly as the owner allows.
 */
class ChatStreamAccumulatorPublicationBoundaryTest {

    private class Harness(
        initialRunId: Long = 7L,
        initialSessionId: String? = "session-1",
        initialMessagePresent: Boolean = true
    ) {
        var runId: Long = initialRunId
        var sessionId: String? = initialSessionId
        var messagePresent: Boolean = initialMessagePresent
        var clockMs = 0L
        val commits = mutableListOf<ChatStreamOutputBatch>()

        fun accumulator(scope: CoroutineScope) = ChatStreamAccumulator(
            scope = scope,
            currentRunId = { this.runId },
            activeSessionId = { this.sessionId },
            hasAssistantMessage = { this.messagePresent },
            commit = { batch, _ -> commits += batch },
            now = { clockMs }
        )
    }

    // ------------------------------------------------- flush, then immediately append again

    @Test
    fun `appending after a flush publishes only the new delta`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)

        accumulator.append("first")
        assertTrue("the explicit flush drains the first delta", accumulator.flush(harness.runId))

        // The drain reset lastPublishedAtMs, so moving the clock past the interval publishes the
        // next append immediately instead of folding it into the previous batch.
        harness.clockMs += CHAT_STREAM_PUBLISH_INTERVAL_MS
        accumulator.append("second")

        assertEquals(2, harness.commits.size)
        assertEquals("first", harness.commits[0].content)
        assertEquals("second", harness.commits[1].content)
    }

    @Test
    fun `a flushed buffer is not published a second time by its scheduled job`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)

        accumulator.append("only-once")
        assertTrue(accumulator.flush(harness.runId))

        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)

        assertEquals("the pending publication must be cancelled by the flush",
            1, harness.commits.size)
        assertEquals("only-once", harness.commits[0].content)
    }

    // ------------------------------------------------- discard vs. an already scheduled job

    @Test
    fun `discard prevents an already scheduled publication from delivering`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)

        // Small delta at clock 0: below both thresholds, so a publication is scheduled instead.
        accumulator.append("must-not-land")
        accumulator.discard(harness.runId)

        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)

        assertTrue("a cancelled publication must never commit stale text",
            harness.commits.isEmpty())
    }

    @Test
    fun `discard clears the interval so the next append publishes at once`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)
        harness.clockMs = 5_000L

        accumulator.discard(harness.runId)
        accumulator.append("fresh")

        assertEquals("discard resets the publish clock, so the next delta is not deferred",
            1, harness.commits.size)
        assertEquals("fresh", harness.commits[0].content)
    }

    // ------------------------------------------------- generation change vs. the scheduled job

    @Test
    fun `a scheduled publication does not deliver after the generation changed`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)

        accumulator.append("run-7")
        harness.runId = 8L

        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)

        assertTrue("a publication scheduled for run 7 must not land in run 8",
            harness.commits.isEmpty())
    }

    // ------------------------------------------------- an append that carries no run id

    @Test
    fun `an append without a run id is not generation scoped`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)

        accumulator.append("unbound")
        harness.runId = 99L

        assertTrue("an unbound buffer is not dropped by a generation change",
            accumulator.flush())
        assertEquals("unbound", harness.commits[0].content)
    }

    @Test
    fun `an append without a run id schedules against the current run`() = runBlocking {
        val harness = Harness()
        val accumulator = harness.accumulator(this)

        accumulator.append("unbound")
        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)

        assertEquals("the deferred publication uses the current run id",
            1, harness.commits.size)
        assertEquals("unbound", harness.commits[0].content)
    }

    @Test
    fun `an append for a superseded run buffers nothing and schedules nothing`() = runBlocking {
        val harness = Harness(initialRunId = 8L)
        val accumulator = harness.accumulator(this)

        accumulator.append("stale", generationRunId = 7L)

        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)
        assertTrue("a stale delta must not be published", harness.commits.isEmpty())
        assertFalse("and it must not have been buffered for the current run either",
            accumulator.flush(8L))
    }
}
