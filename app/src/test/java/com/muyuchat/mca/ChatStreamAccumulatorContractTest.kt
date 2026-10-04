package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour contract for [ChatStreamAccumulator] -- the closure that owns the streamed assistant
 * output.
 *
 * This pins the rules that used to be spread through `MainViewModel` and could only be checked by
 * reading it: when a delta is published, when it is deferred, and -- the part that matters most --
 * when a drained batch must be **thrown away** rather than appended to a message that belongs to a
 * newer request.
 *
 * The clock is injected, so the publish-interval rule is checked without waiting on wall time. The
 * deferred-publish case is the one place that uses real time, because the accumulator schedules on a
 * real coroutine scope: it uses a 75 ms interval and waits well past it.
 */
class ChatStreamAccumulatorContractTest {

    private class Commits {
        val batches = mutableListOf<ChatStreamOutputBatch>()
        var lastPredicateResult: Boolean? = null
        val calls = AtomicInteger()

        fun record(batch: ChatStreamOutputBatch, isStillCurrent: () -> Boolean) {
            calls.incrementAndGet()
            batches += batch
            lastPredicateResult = isStillCurrent()
        }

        val content: String get() = batches.joinToString("") { it.content }
        val reasoning: String get() = batches.joinToString("") { it.reasoning }
    }

    private class Fixture(
        var runId: Long = 1L,
        var sessionId: String? = "session-1",
        var hasAssistant: Boolean = true
    ) {
        val commits = Commits()

        /**
         * Starts at 0 so the fixture is "just constructed": `lastPublishedAtMs` is 0 too, and the
         * interval rule is `now - lastPublishedAtMs >= CHAT_STREAM_PUBLISH_INTERVAL_MS`.
         *
         * With a realistic (large) clock the very first append satisfies that rule and publishes
         * immediately -- which is the real behaviour and is pinned by its own test below. Starting
         * the fixture at 0 keeps the *other* tests measuring the buffering rule rather than that
         * first-publish rule.
         */
        var clockMs = 0L
        val accumulator = ChatStreamAccumulator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            currentRunId = { runId },
            activeSessionId = { sessionId },
            hasAssistantMessage = { hasAssistant },
            commit = commits::record,
            now = { clockMs }
        )
    }

    @Test
    fun `the first delta publishes immediately when the clock is already running`() {
        // The real ViewModel starts with lastPublishedAtMs = 0 and a monotonic clock far from 0, so
        // the interval rule fires on the very first append: the first token reaches the message at
        // once instead of waiting for the 75 ms timer.
        val f = Fixture()
        f.clockMs = 5_000L

        f.accumulator.append("first token")

        assertEquals(1, f.commits.calls.get())
        assertEquals("first token", f.commits.content)
    }

    @Test
    fun `a small delta is buffered and published only when forced`() {
        val f = Fixture()

        f.accumulator.append("hello")

        assertEquals("nothing is committed below the threshold", 0, f.commits.calls.get())

        f.accumulator.append(" world", forcePublish = true)

        assertEquals(1, f.commits.calls.get())
        assertEquals("hello world", f.commits.content)
    }

    @Test
    fun `the buffer publishes once it reaches the character threshold`() {
        val f = Fixture()

        f.accumulator.append("x".repeat(CHAT_STREAM_PUBLISH_CHARS - 1))
        assertEquals("one short of the threshold must not publish", 0, f.commits.calls.get())

        f.accumulator.append("y")

        assertEquals(1, f.commits.calls.get())
        assertEquals(CHAT_STREAM_PUBLISH_CHARS, f.commits.content.length)
    }

    @Test
    fun `the interval alone publishes a buffer that is below the character threshold`() {
        val f = Fixture()

        f.accumulator.append("small")
        assertEquals(0, f.commits.calls.get())

        f.clockMs += CHAT_STREAM_PUBLISH_INTERVAL_MS

        f.accumulator.append("small")

        assertEquals(1, f.commits.calls.get())
        assertEquals("smallsmall", f.commits.content)
    }

    @Test
    fun `a delta from a superseded run is ignored`() {
        val f = Fixture(runId = 2L)

        f.accumulator.append("from the old run", forcePublish = true, generationRunId = 1L)

        assertEquals("a stale run must not reach the message", 0, f.commits.calls.get())
    }

    @Test
    fun `a newer run discards whatever the older run had buffered`() {
        val f = Fixture(runId = 1L)
        // The buffer only tracks a generation once it is told about one, so the older run has to
        // name itself here -- that is exactly what the real caller passes.
        f.accumulator.append("old text", generationRunId = 1L)

        f.runId = 2L
        f.accumulator.append("new text", forcePublish = true, generationRunId = 2L)

        assertEquals(1, f.commits.calls.get())
        assertEquals("the older buffer is dropped, not merged", "new text", f.commits.content)
    }

    @Test
    fun `flush refuses to commit when there is no assistant message to append to`() {
        val f = Fixture(hasAssistant = false)
        f.accumulator.append("text")

        val flushed = f.accumulator.flush()

        assertFalse("flush must report that it published nothing", flushed)
        assertEquals(0, f.commits.calls.get())
    }

    @Test
    fun `flush refuses to commit a batch drained for a superseded run`() {
        val f = Fixture(runId = 1L)
        f.accumulator.append("text")

        f.runId = 2L

        assertFalse(f.accumulator.flush(generationRunId = 1L))
        assertEquals(0, f.commits.calls.get())
    }

    @Test
    fun `discard drops the buffer so a later flush commits nothing`() {
        val f = Fixture()
        f.accumulator.append("text")

        f.accumulator.discard()

        assertFalse("an empty buffer has nothing to publish", f.accumulator.flush())
        assertEquals(0, f.commits.calls.get())
    }

    @Test
    fun `discard for a superseded run leaves the current buffer alone`() {
        val f = Fixture(runId = 1L)
        f.accumulator.append("keep me")

        f.accumulator.discard(generationRunId = 99L)

        assertTrue(f.accumulator.flush())
        assertEquals("keep me", f.commits.content)
    }

    @Test
    fun `the reasoning duration keeps its high water mark across appends`() {
        val f = Fixture()

        f.accumulator.append("a", reasoningDelta = "think", reasoningDurationMs = 120L)
        f.accumulator.append("b", reasoningDelta = "", reasoningDurationMs = 40L, forcePublish = true)

        assertEquals(1, f.commits.calls.get())
        assertEquals("think", f.commits.reasoning)
        assertEquals(
            "the reported duration is the maximum seen, not the last one",
            120L,
            f.commits.batches.single().reasoningDurationMs
        )
    }

    @Test
    fun `the commit predicate reports the run as still current while it is`() {
        val f = Fixture(runId = 7L)

        f.accumulator.append("text", forcePublish = true, generationRunId = 7L)

        assertEquals(true, f.commits.lastPredicateResult)
    }

    @Test
    fun `the deferred publication still delivers after the interval`() {
        val f = Fixture()

        f.accumulator.append("deferred")
        assertEquals("the deferred path must not publish inline", 0, f.commits.calls.get())

        // The accumulator schedules on a real scope with a 75 ms interval; wait well past it.
        val deadline = System.currentTimeMillis() + 2_000L
        while (f.commits.calls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }

        assertEquals(1, f.commits.calls.get())
        assertEquals("deferred", f.commits.content)
    }
}
