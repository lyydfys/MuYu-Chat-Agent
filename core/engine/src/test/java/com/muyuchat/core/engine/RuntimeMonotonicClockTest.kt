package com.muyuchat.core.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMonotonicClockTest {
    @Test
    fun systemClockWorksWithoutAnAndroidClockStub() {
        val before = SystemRuntimeMonotonicClock.nowMs()
        val after = SystemRuntimeMonotonicClock.nowMs()
        assertTrue(after >= before)
    }

    @Test
    fun negativeMonotonicOriginIsValid() {
        val clock = MutableClock(elapsedMs = -1_200L)
        val timing = GenerationElapsedTiming(clock)
        timing.start()
        clock.elapsedMs = -800L
        timing.markFirstToken()
        clock.elapsedMs = -300L
        timing.complete()

        val snapshot = timing.snapshot()
        assertTrue(timing.hasFirstToken())
        assertTrue(snapshot.completed)
        assertEquals(400L, snapshot.ttftMs)
        assertEquals(500L, snapshot.decodeMs)
        assertEquals(900L, snapshot.elapsedMs)
        assertEquals(4.0, snapshot.decodeTokensPerSecond(2), 0.0)
    }

    @Test
    fun zeroStartAndFirstTokenAreValidObservations() {
        val clock = MutableClock()
        val timing = GenerationElapsedTiming(clock)
        timing.start()
        timing.markFirstToken()
        clock.elapsedMs = 500L
        timing.markFirstToken()
        clock.elapsedMs = 1_200L
        timing.complete()

        val snapshot = timing.snapshot()
        assertTrue(timing.hasFirstToken())
        assertTrue(snapshot.completed)
        assertEquals(0L, snapshot.ttftMs)
        assertEquals(1_200L, snapshot.decodeMs)
        assertEquals(1_200L, snapshot.elapsedMs)
        assertEquals(5.0, snapshot.decodeTokensPerSecond(6), 0.0)
        assertEquals(5.0, snapshot.endToEndTokensPerSecond(6), 0.0)
    }

    @Test
    fun unstartedTimingDoesNotPublishElapsedOrThroughput() {
        val timing = GenerationElapsedTiming(MutableClock())
        timing.markFirstToken()
        timing.complete()

        val snapshot = timing.snapshot()
        assertFalse(timing.hasFirstToken())
        assertFalse(snapshot.completed)
        assertNull(snapshot.elapsedMs)
        assertEquals(0L, snapshot.ttftMs)
        assertEquals(0L, snapshot.decodeMs)
        assertEquals(0.0, snapshot.endToEndTokensPerSecond(5), 0.0)
    }

    @Test
    fun liveThroughputAndCompletedThroughputUseTheSameElapsedOrigin() {
        val clock = MutableClock(elapsedMs = 10L)
        val timing = GenerationElapsedTiming(clock)
        timing.start()
        clock.elapsedMs = 60L
        timing.markFirstToken()
        clock.elapsedMs = 260L

        val live = timing.snapshot()
        assertEquals(50L, live.ttftMs)
        assertEquals(0L, live.decodeMs)
        assertEquals(80.0, live.endToEndTokensPerSecond(20), 0.0)
        assertEquals(0.0, live.endToEndTokensPerSecond(20, requireCompleted = true), 0.0)

        clock.elapsedMs = 310L
        timing.complete()
        clock.elapsedMs = 20_000L
        timing.complete()
        val completed = timing.snapshot()
        assertEquals(300L, completed.elapsedMs)
        assertEquals(250L, completed.decodeMs)
        assertEquals(20_000.0 / 300L, completed.endToEndTokensPerSecond(20), 0.0)
    }

    @Test
    fun rescueClearsPreviousAttemptCompletionAndRetainsRequestElapsedTime() {
        val clock = MutableClock(elapsedMs = 100L)
        val timing = GenerationElapsedTiming(clock)
        timing.start()
        clock.elapsedMs = 200L
        timing.markFirstToken()
        clock.elapsedMs = 300L
        timing.complete()

        clock.elapsedMs = 400L
        timing.resetDecodeAttempt()
        val waitingForRescue = timing.snapshot()
        assertFalse(timing.hasFirstToken())
        assertFalse(waitingForRescue.completed)
        assertEquals(300L, waitingForRescue.elapsedMs)
        assertEquals(0L, waitingForRescue.decodeMs)

        clock.elapsedMs = 650L
        timing.markFirstToken()
        clock.elapsedMs = 1_000L
        timing.complete()
        val rescued = timing.snapshot()
        assertEquals(550L, rescued.ttftMs)
        assertEquals(350L, rescued.decodeMs)
        assertEquals(900L, rescued.elapsedMs)

        clock.elapsedMs = 2_000L
        timing.start()
        assertFalse(timing.hasFirstToken())
        assertFalse(timing.snapshot().completed)
        assertEquals(0L, timing.snapshot().elapsedMs)
    }

    @Test
    fun liteRtProductionStatsPreserveWallTimestampAcrossClockCorrections() {
        val clock = MutableClock(wallMs = 1_700_000_000_000L)
        val runner = LiteRtLmChatRunner(clock, wallClockMs = { clock.wallMs })
        val timing = generationTimingOf(runner)
        timing.start()
        timing.markFirstToken()
        setRunnerField(runner, "completionTokens", 4)
        clock.elapsedMs = 800L
        timing.complete()

        for (wallMs in listOf(1_699_989_200_000L, 1_700_021_600_000L)) {
            clock.wallMs = wallMs
            clock.elapsedMs += 50_000L
            val stats = JSONObject(runner.getRuntimeStatsJson())
            assertEquals(wallMs, stats.getLong("statsAt"))
            assertEquals(0L, stats.getLong("ttftMs"))
            assertEquals(800L, stats.getLong("decodeMs"))
            assertEquals(5.0, stats.getDouble("decodeTps"), 0.0)
            assertEquals(5.0, stats.getDouble("e2eTps"), 0.0)
        }
    }

    @Test
    fun genieXProductionStatsAndPrefillRecognizeAFirstTokenAtZero() {
        val clock = MutableClock()
        val runner = GenieXChatRunner(
            runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
            requestedRuntimeId = "llama_cpp",
            defaultComputeUnit = "cpu",
            defaultBackendDevices = "fixture",
            clock = clock
        )
        val timing = generationTimingOf(runner)
        timing.start()
        timing.markFirstToken()
        setRunnerField(runner, "completionTokens", 2)
        setRunnerField(runner, "prefillTotalTokens", 10)
        clock.elapsedMs = 1_000L
        timing.complete()

        assertEquals(10, runner.prefillProgress()?.completedTokens)
        val stats = JSONObject(runner.getRuntimeStatsJson())
        assertEquals(0L, stats.getLong("ttftMs"))
        assertEquals(1_000L, stats.getLong("decodeMs"))
        assertEquals(2.0, stats.getDouble("decodeTps"), 0.0)
        assertEquals(2.0, stats.getDouble("e2eTps"), 0.0)
    }

    private class MutableClock(
        var elapsedMs: Long = 0L,
        var wallMs: Long = 0L
    ) : RuntimeMonotonicClock {
        override fun nowMs(): Long = elapsedMs
    }

    // Drive the real timing owner and stats adapter without starting an Android SDK/native engine.
    private fun generationTimingOf(runner: Any): GenerationElapsedTiming =
        runner.javaClass.getDeclaredField("generationTiming").apply {
            isAccessible = true
        }.get(runner) as GenerationElapsedTiming

    private fun setRunnerField(runner: Any, name: String, value: Any) {
        runner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(runner, value)
    }
}
