package com.muyuchat.core.agent.runtime.run

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step-watchdog rules: a step is overdue only while it is still open and the elapsed time really
 * passed. The cases below pin the three ways this could silently mislead the owner — a stale report
 * after a finish, a retry that resets the clock, and a clock that moves backwards.
 */
class AgentStepWatchdogTest {

    private val clock = ManualClock(1_000_000L)

    private fun watchdog(timeout: Long = 180_000L) = AgentStepWatchdog(timeout, clock::now)

    @Test
    fun `a step that finishes inside its budget is never reported`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(0)
        clock.advance(179_999L)

        assertTrue("just under the limit is not overdue", watchdog.timedOutSteps().isEmpty())
        assertFalse(watchdog.isCurrentStepOverdue())

        watchdog.onStepFinished(0)
        clock.advance(1_000_000L)
        assertTrue("a finished step is forgotten", watchdog.timedOutSteps().isEmpty())
    }

    @Test
    fun `a step that exceeds the budget is reported`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(3)
        clock.advance(180_000L)

        assertEquals(
            "the boundary itself counts as timed out",
            listOf(3),
            watchdog.timedOutSteps()
        )
        assertTrue(watchdog.isCurrentStepOverdue())
    }

    @Test
    fun `finishing a timed out step stops the report`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(7)
        clock.advance(200_000L)
        assertEquals(listOf(7), watchdog.timedOutSteps())

        watchdog.onStepFinished(7)

        assertTrue(watchdog.timedOutSteps().isEmpty())
        assertFalse(watchdog.isCurrentStepOverdue())
        assertEquals(0, watchdog.activeStepCount())
    }

    @Test
    fun `a repeated start keeps the original start time`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(2)
        clock.advance(150_000L)
        // A retry of the same index must not buy another full budget.
        watchdog.onStepStarted(2)
        clock.advance(30_000L)

        assertEquals(
            "the retry did not reset the elapsed budget",
            listOf(2),
            watchdog.timedOutSteps()
        )
    }

    @Test
    fun `concurrent steps are reported independently and only when overdue`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(0)
        clock.advance(100_000L)
        watchdog.onStepStarted(1)
        clock.advance(90_000L)

        assertEquals("only step 0 passed its own budget", listOf(0), watchdog.timedOutSteps())
        assertFalse("the newest open step is still inside its budget", watchdog.isCurrentStepOverdue())

        // Step 1 needs its own full 180 s from its own start, not from step 0's.
        clock.advance(89_999L)
        assertEquals("step 1 is still one tick short of its own budget", listOf(0), watchdog.timedOutSteps())

        clock.advance(1L)
        assertEquals(listOf(0, 1), watchdog.timedOutSteps())
        assertTrue(watchdog.isCurrentStepOverdue())

        watchdog.onStepFinished(0)
        assertEquals("finishing the older step leaves the newer one", listOf(1), watchdog.timedOutSteps())
    }

    @Test
    fun `a batch of steps finishing out of order reports only the stragglers`() {
        val watchdog = watchdog()
        (0..3).forEach { index ->
            watchdog.onStepStarted(index)
            clock.advance(10_000L)
        }
        clock.advance(180_000L)

        watchdog.onStepFinished(0)
        watchdog.onStepFinished(2)

        assertEquals(listOf(1, 3), watchdog.timedOutSteps())
        assertEquals(2, watchdog.activeStepCount())
    }

    @Test
    fun `a clock that moves backwards never fabricates a timeout`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(0)
        clock.advance(100_000L)
        clock.rewind(50_000L)

        assertTrue("a backward jump is not an expiry", watchdog.timedOutSteps().isEmpty())
        assertFalse(watchdog.isCurrentStepOverdue())

        // Once the clock has genuinely advanced past the limit again, the step is reported.
        clock.advance(130_000L)
        assertEquals(listOf(0), watchdog.timedOutSteps())
    }

    @Test
    fun `finishing an unknown or already finished step is harmless`() {
        val watchdog = watchdog()
        watchdog.onStepFinished(42)
        watchdog.onStepStarted(0)
        watchdog.onStepFinished(0)
        watchdog.onStepFinished(0)

        assertTrue(watchdog.timedOutSteps().isEmpty())
        assertEquals(0, watchdog.activeStepCount())
    }

    @Test
    fun `isCurrentStepOverdue follows the highest open index`() {
        val watchdog = watchdog()
        watchdog.onStepStarted(0)
        clock.advance(180_000L)
        assertTrue("step 0 alone is current and overdue", watchdog.isCurrentStepOverdue())

        watchdog.onStepStarted(1)
        assertFalse("a fresh step 1 resets the current-step view", watchdog.isCurrentStepOverdue())

        watchdog.onStepFinished(1)
        assertTrue("with step 1 closed, the overdue step 0 is current again", watchdog.isCurrentStepOverdue())
    }

    @Test
    fun `a non positive timeout is rejected`() {
        listOf(0L, -1L).forEach { timeout ->
            val failure = runCatching { AgentStepWatchdog(timeout) }.exceptionOrNull()
            assertTrue("timeout $timeout must be rejected", failure is IllegalArgumentException)
        }
        assertTrue(
            "the production default stays three minutes",
            AgentStepWatchdog.DEFAULT_STEP_TIMEOUT_MILLIS == 180_000L
        )
    }

    @Test
    fun `starts and finishes from many threads leave a consistent state`() {
        val watchdog = AgentStepWatchdog(stepTimeoutMillis = 60_000L, clock = clock::now)
        val threads = 8
        val perThread = 40
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { thread ->
                pool.submit {
                    startGate.await()
                    repeat(perThread) { iteration ->
                        val index = thread * perThread + iteration
                        watchdog.onStepStarted(index)
                        if (index % 2 == 0) watchdog.onStepFinished(index)
                    }
                }
            }
            startGate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        clock.advance(60_000L)
        val overdue = watchdog.timedOutSteps()
        assertEquals("every odd index is still open", threads * perThread / 2, overdue.size)
        assertTrue("an even index finished and must not be reported", overdue.none { it % 2 == 0 })
        assertEquals(overdue.sorted(), overdue)
    }

    /** Deterministic clock that may also be rewound, unlike the shared manual clock. */
    private class ManualClock(startMillis: Long) {
        private val current = AtomicLong(startMillis)

        fun now(): Long = current.get()

        fun advance(deltaMillis: Long) {
            require(deltaMillis >= 0L) { "advance must not be negative" }
            current.addAndGet(deltaMillis)
        }

        fun rewind(deltaMillis: Long) {
            require(deltaMillis >= 0L) { "rewind must not be negative" }
            current.addAndGet(-deltaMillis)
        }
    }
}
