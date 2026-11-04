package com.muyuchat.mca.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the three reliability decisions the DSH console depends on: which port to bind, which
 * refresh rate to request, and when the watchdog may restart the host.
 *
 * Pure JVM: the port probe, the display-mode list and the clock are all injected.
 */
class McaRuntimeReliabilityTest {

    // ---- port selection ----

    @Test
    fun `a free preferred port is used as is`() {
        val choice = RuntimePortPlanner.choose(14_000) { true }

        val chosen = choice as RuntimePortChoice.Chosen
        assertEquals(14_000, chosen.port)
        assertEquals(1, chosen.attempts)
        assertFalse(chosen.fellBack)
        assertNull("no fallback notice when the preferred port worked", chosen.fallbackNotice)
    }

    @Test
    fun `an occupied port falls back to the next free one and says so`() {
        val occupied = setOf(14_000, 14_001)

        val chosen = RuntimePortPlanner.choose(14_000) { it !in occupied } as RuntimePortChoice.Chosen

        assertEquals(14_002, chosen.port)
        assertEquals(3, chosen.attempts)
        assertTrue(chosen.fellBack)
        assertEquals("原端口 14000 被占用，已回退到 14002", chosen.fallbackNotice)
    }

    @Test
    fun `ten occupied candidates are refused instead of scanning forever`() {
        val choice = RuntimePortPlanner.choose(20_000) { false }

        val rejected = choice as RuntimePortChoice.Rejected
        assertTrue(rejected.reason.contains("20000"))
        assertTrue(rejected.reason.contains("10"))
        assertEquals(RuntimePortPlanner.MAX_CANDIDATES, RuntimePortPlanner.candidates(20_000).size)
    }

    @Test
    fun `reserved or out of range ports are refused rather than clamped`() {
        listOf(80, 1023, 0, -1, 65_536, 70_000).forEach { port ->
            val rejected = RuntimePortPlanner.choose(port) { true }
            assertTrue("port=$port", rejected is RuntimePortChoice.Rejected)
            assertTrue((rejected as RuntimePortChoice.Rejected).reason.contains("1024–65535"))
        }
        assertTrue(RuntimePortPlanner.isValidPort(1024))
        assertTrue(RuntimePortPlanner.isValidPort(65_535))
        assertFalse(RuntimePortPlanner.isValidPort(1023))
        assertFalse(RuntimePortPlanner.isValidPort(65_536))
        assertTrue(RuntimePortPlanner.candidates(80).isEmpty())
    }

    @Test
    fun `candidates stop at the maximum port`() {
        val candidates = RuntimePortPlanner.candidates(65_530)

        assertEquals(listOf(65_530, 65_531, 65_532, 65_533, 65_534, 65_535), candidates)
    }

    @Test
    fun `the probe is asked in order and stops at the first free port`() {
        val asked = mutableListOf<Int>()

        RuntimePortPlanner.choose(30_000) { port ->
            asked += port
            port == 30_002
        }

        assertEquals(listOf(30_000, 30_001, 30_002), asked)
    }

    // ---- frame rate ----

    @Test
    fun `following the system keeps the system mode`() {
        val modes = modes(60 to 1, 120 to 2)

        assertEquals(
            FrameRateChoice.FollowSystem(2),
            FrameRatePlanner.choose(modes, FrameRatePreference.FOLLOW_SYSTEM, systemModeId = 2)
        )
        assertEquals(
            "an unknown system mode still means 'system decides'",
            FrameRateChoice.FollowSystem(null),
            FrameRatePlanner.choose(modes, FrameRatePreference.FOLLOW_SYSTEM)
        )
    }

    @Test
    fun `a supported ceiling requests the matching mode`() {
        val chosen = FrameRatePlanner.choose(modes(60 to 1, 120 to 2), FrameRatePreference.HZ_120)

        assertEquals(FrameRateChoice.Request(modeId = 2, hz = 120f, degradedFromHz = null), chosen)
    }

    @Test
    fun `a 120 request on a 90 Hz panel degrades to 90 and reports it`() {
        val chosen = FrameRatePlanner.choose(modes(60 to 1, 90 to 2), FrameRatePreference.HZ_120)

        assertEquals(FrameRateChoice.Request(modeId = 2, hz = 90f, degradedFromHz = 120), chosen)
    }

    @Test
    fun `a 90 request never silently runs a 120 Hz panel at 120`() {
        val chosen = FrameRatePlanner.choose(modes(120 to 9), FrameRatePreference.HZ_90)

        // No mode is at or below the ceiling, so the app must not request one above it; the platform
        // keeps deciding and the UI reports that the ceiling is unsupported.
        assertEquals(FrameRateChoice.FollowSystem(modeId = null, unsupportedCeilingHz = 90), chosen)
    }

    @Test
    fun `a panel without usable modes is unavailable`() {
        assertEquals(FrameRateChoice.Unavailable, FrameRatePlanner.choose(emptyList(), FrameRatePreference.HZ_120))
        val invalid = listOf(
            DisplayModeOption(1, Float.NaN),
            DisplayModeOption(2, 0f),
            DisplayModeOption(3, -60f)
        )
        assertEquals(FrameRateChoice.Unavailable, FrameRatePlanner.choose(invalid, FrameRatePreference.HZ_60))
    }

    @Test
    fun `an unknown stored preference falls back to following the system`() {
        assertEquals(FrameRatePreference.FOLLOW_SYSTEM, FrameRatePreference.fromWireName(null))
        assertEquals(FrameRatePreference.FOLLOW_SYSTEM, FrameRatePreference.fromWireName("144"))
        assertEquals(FrameRatePreference.HZ_120, FrameRatePreference.fromWireName("hz_120"))
        assertNull(FrameRatePreference.FOLLOW_SYSTEM.targetHz)
    }

    // ---- watchdog ----

    @Test
    fun `a healthy probe resets the failure streak`() {
        val decision = WatchdogPolicy.evaluate(
            ok = true,
            state = WatchdogState(failedStreak = 2, restartsInWindow = 0, windowStartedAtMillis = 1_000L),
            nowMillis = 20_000L
        )

        assertEquals(WatchdogAction.KEEP, decision.action)
        assertEquals(0, decision.state.failedStreak)
        assertNull(decision.logLine)
    }

    @Test
    fun `fewer than three failures are only observed`() {
        var state = WatchdogPolicy.initialState(nowMillis = 0L)
        repeat(2) { index ->
            val decision = WatchdogPolicy.evaluate(ok = false, state = state, nowMillis = index * 15_000L)
            assertEquals(WatchdogAction.KEEP, decision.action)
            state = decision.state
        }

        assertEquals(2, state.failedStreak)
        assertEquals(0, state.restartsInWindow)
    }

    @Test
    fun `three consecutive failures trigger one controlled restart`() {
        val first = WatchdogPolicy.evaluate(ok = false, state = WatchdogPolicy.initialState(0L), nowMillis = 0L)
        val second = WatchdogPolicy.evaluate(ok = false, state = first.state, nowMillis = 15_000L)
        val third = WatchdogPolicy.evaluate(ok = false, state = second.state, nowMillis = 30_000L)

        assertEquals(WatchdogAction.KEEP, first.action)
        assertEquals(WatchdogAction.KEEP, second.action)
        assertEquals(WatchdogAction.RESTART, third.action)
        assertEquals(1, third.state.restartsInWindow)
        assertEquals("a restart starts a fresh streak", 0, third.state.failedStreak)
        assertTrue(third.logLine.orEmpty().contains("restart #1"))
    }

    @Test
    fun `the watchdog throttles to two restarts per five minutes`() {
        val windowStart = 1_000L
        var state = WatchdogState(failedStreak = 2, restartsInWindow = 2, windowStartedAtMillis = windowStart)

        val backoff = WatchdogPolicy.evaluate(ok = false, state = state, nowMillis = windowStart + 60_000L)

        assertEquals(WatchdogAction.BACKOFF, backoff.action)
        assertTrue(backoff.reason.contains("5 分钟"))
        assertTrue(backoff.logLine.orEmpty().contains("backoff"))
        state = backoff.state
        assertEquals("a throttled watchdog never grows the restart counter", 2, state.restartsInWindow)
    }

    @Test
    fun `after the window expires a restart is allowed again`() {
        val state = WatchdogState(failedStreak = 2, restartsInWindow = 2, windowStartedAtMillis = 1_000L)

        val decision = WatchdogPolicy.evaluate(
            ok = false,
            state = state,
            nowMillis = 1_000L + WatchdogPolicy.RESTART_WINDOW_MS + 1
        )

        assertEquals(WatchdogAction.RESTART, decision.action)
        assertEquals(1, decision.state.restartsInWindow)
        assertEquals(1_000L + WatchdogPolicy.RESTART_WINDOW_MS + 1, decision.state.windowStartedAtMillis)
    }

    @Test
    fun `recovery keeps the restart counter until the window expires`() {
        val state = WatchdogState(failedStreak = 1, restartsInWindow = 1, windowStartedAtMillis = 0L)

        val recovered = WatchdogPolicy.evaluate(ok = true, state = state, nowMillis = 10_000L)

        assertEquals(1, recovered.state.restartsInWindow)
        assertEquals(10_000L, recovered.state.windowStartedAtMillis)
        assertEquals("one probe still needs three fresh failures", 0, recovered.state.failedStreak)
    }

    @Test
    fun `the probe cadence matches the documented fifteen seconds`() {
        assertEquals(15_000L, WatchdogPolicy.PROBE_INTERVAL_MS)
        assertEquals(3, WatchdogPolicy.FAILURE_THRESHOLD)
        assertEquals(2, WatchdogPolicy.MAX_RESTARTS_PER_WINDOW)
    }

    private fun modes(vararg pairs: Pair<Int, Int>): List<DisplayModeOption> =
        pairs.map { (hz, modeId) -> DisplayModeOption(modeId = modeId, refreshRateHz = hz.toFloat()) }
}
