package com.muyuchat.mca.runtime

import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheck
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckProbe
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckRepair
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckRow
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckStatus

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the watchdog loop and the mapping from real runtime facts to the console's self-check rows.
 *
 * The loop is driven on virtual time with an injected probe/restart, so "three failures restart once,
 * two restarts then back off" is asserted as behaviour rather than as a comment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McaRuntimeWatchdogTest {

    @Test
    fun `a healthy runtime is never restarted`() = runTest {
        var restarts = 0
        val watchdog = McaRuntimeWatchdog(
            probe = { true },
            restart = { restarts++ },
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 10)

        assertEquals(0, restarts)
        assertTrue(watchdog.isRunning())
        watchdog.stop()
        assertFalse(watchdog.isRunning())
    }

    @Test
    fun `three consecutive failures restart once through the injected entry`() = runTest {
        var restarts = 0
        val watchdog = McaRuntimeWatchdog(
            probe = { false },
            restart = { restarts++ },
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)

        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 2)
        assertEquals("two failures are only observed", 0, restarts)

        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS)
        assertEquals(1, restarts)

        watchdog.stop()
    }

    @Test
    fun `the loop backs off after two restarts in the window`() = runTest {
        var restarts = 0
        val logs = mutableListOf<String>()
        val watchdog = McaRuntimeWatchdog(
            probe = { false },
            restart = { restarts++ },
            log = { logs += it },
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)

        // Enough virtual time for two restart cycles, all inside the 5-minute throttle window, so the
        // third streak must back off instead of restarting again.
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 12)

        assertEquals(WatchdogPolicy.MAX_RESTARTS_PER_WINDOW, restarts)
        assertTrue(logs.any { it.contains("backoff") })
        watchdog.stop()
    }

    @Test
    fun `a recovered runtime resets the failure streak`() = runTest {
        var healthy = false
        var restarts = 0
        val watchdog = McaRuntimeWatchdog(
            probe = { healthy },
            restart = { restarts++ },
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 2)
        healthy = true
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 2)

        assertEquals(0, restarts)
        watchdog.stop()
    }

    @Test
    fun `starting twice does not double the probes`() = runTest {
        var probes = 0
        val watchdog = McaRuntimeWatchdog(
            probe = { probes++; true },
            restart = {},
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)
        watchdog.start(this)
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS)

        assertEquals("one loop, so one probe per interval", 1, probes)
        watchdog.stop()
    }

    @Test
    fun `stopping cancels the loop`() = runTest {
        var probes = 0
        val watchdog = McaRuntimeWatchdog(
            probe = { probes++; false },
            restart = {},
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS)
        val before = probes
        watchdog.stop()
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 5)

        assertEquals(before, probes)
    }

    @Test
    fun `a failing restart entry is logged and does not kill the loop`() = runTest {
        var attempts = 0
        val logs = mutableListOf<String>()
        val watchdog = McaRuntimeWatchdog(
            probe = { false },
            restart = { attempts++; error("owner busy") },
            log = { logs += it },
            now = { testScheduler.currentTime }
        )
        watchdog.start(this)
        advanceTimeBy(WatchdogPolicy.PROBE_INTERVAL_MS * 12)

        assertTrue("the loop must keep trying after a rejected restart", attempts >= 2)
        assertTrue(logs.any { it.contains("restart failed") })
        watchdog.stop()
    }

    @Test
    fun `only stable phases are probed`() {
        assertTrue(McaRuntimeWatchdog.shouldProbe(snapshot(DshRuntimePhase.READY)))
        assertTrue(McaRuntimeWatchdog.shouldProbe(snapshot(DshRuntimePhase.FAILED)))
        assertTrue(McaRuntimeWatchdog.shouldProbe(snapshot(DshRuntimePhase.STOPPED)))
        assertFalse("a transitional phase must not be interrupted", McaRuntimeWatchdog.shouldProbe(snapshot(DshRuntimePhase.STARTING)))
        assertFalse(McaRuntimeWatchdog.shouldProbe(snapshot(DshRuntimePhase.STOPPING)))
        assertFalse("no snapshot means no host yet", McaRuntimeWatchdog.shouldProbe(null))
    }

    @Test
    fun `self-check rows are built from the real snapshot and port probe`() {
        val ready = DshRuntimeSnapshot(
            phase = DshRuntimePhase.READY,
            origin = "http://127.0.0.1:43210",
            changedAtMillis = 1L
        )
        val source = McaRuntimeSelfCheckSource(
            snapshot = { ready },
            portProbe = { false },
            gatewayReachable = { true },
            terminalPtyReady = { true },
            configDirectoryReady = { true }
        )

        val rows = RuntimeSelfCheck.rows(source.probe())

        assertEquals(5, rows.size)
        assertEquals(RuntimeSelfCheckStatus.READY, rows[0].status)
        assertEquals(RuntimeSelfCheckStatus.FAILED, rows[1].status)
        assertTrue(rows[1].detail.contains("43210"))
        assertEquals(RuntimeSelfCheckRepair.USE_FALLBACK_PORT, rows[1].repair)
        assertEquals(1, RuntimeSelfCheck.repairableRows(rows).size)
    }

    @Test
    fun `a stopped host offers the restart button, an unprobed host shows unknown`() {
        val stopped = McaRuntimeSelfCheckSource(snapshot = { snapshot(DshRuntimePhase.STOPPED) })
        val stoppedRows = RuntimeSelfCheck.rows(stopped.probe())
        assertEquals(RuntimeSelfCheckStatus.FAILED, stoppedRows[0].status)
        assertEquals(RuntimeSelfCheckRepair.RESTART_RUNTIME, stoppedRows[0].repair)

        val unprobed = McaRuntimeSelfCheckSource()
        val unprobedRows = RuntimeSelfCheck.rows(unprobed.probe())
        assertTrue(unprobedRows.all { it.status == RuntimeSelfCheckStatus.UNKNOWN })
        assertTrue(RuntimeSelfCheck.repairableRows(unprobedRows).isEmpty())
        assertNull(unprobed.probe().webPort)
        assertNull(unprobed.probe().gatewayHealthy)
    }

    @Test
    fun `the configured port is used while the host has no origin`() {
        val source = McaRuntimeSelfCheckSource(
            snapshot = { snapshot(DshRuntimePhase.STARTING) },
            configuredPort = { 15_000 },
            portProbe = { true }
        )

        val probe = source.probe()

        assertNull("STARTING is neither running nor failed", probe.processRunning)
        assertEquals(15_000, probe.webPort)
        assertEquals(true, probe.webPortAvailable)
    }

    private fun snapshot(phase: DshRuntimePhase) =
        DshRuntimeSnapshot(phase = phase, origin = null, changedAtMillis = 1L)
}
