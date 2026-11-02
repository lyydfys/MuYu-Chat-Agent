package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeModeTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-safemode")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private class Fixture(root: File, clock: TestSupport.FakeClock) {
        val root: File = root
        val home = File(root, "home")
        val history = DshStartHistoryStore(File(root, "maintenance/start-history.json"))
        val state = DshSafeModeStateStore(File(root, "maintenance/safe-mode.json"))
        val admission = TestSupport.FakeAdmission()
        val engine = DshMaintenanceEngine(root, admission, TestSupport.healthyProbe(), clock = clock)
        val controller = DshSafeModeController(engine, home, history, state, clock = clock)

        fun seedUserLayers() {
            TestSupport.write(File(home, "user-patches/dsh-console/theme.css"), "patched")
            TestSupport.write(File(home, "plugins-user/tool/index.js"), "plugin")
            TestSupport.write(File(home, "sessions/main/session-a/session.v4.jsonl.zstd"), "session")
            TestSupport.write(File(home, ".credentials.yaml"), "token: fake")
        }
    }

    @Test
    fun decisionMatrixCoversNormalSafeModeAndRecovery() {
        val clock = TestSupport.FakeClock()
        val history = DshStartHistory()
        val policy = DshSafeModePolicy()
        assertEquals(DshSafeModeDecision.NORMAL, policy.decide(history, clock(), false))
        val twoFailures = history.copy(consecutiveFailures = 2)
        assertEquals(DshSafeModeDecision.NORMAL, policy.decide(twoFailures, clock(), false))
        val threeFailures = history.copy(consecutiveFailures = 3)
        assertEquals(DshSafeModeDecision.SAFE_MODE, policy.decide(threeFailures, clock(), false))
        val burst = history.copy(
            events = listOf(
                DshStartEvent(clock() - 1_000, DshStartOutcome.CRASHED),
                DshStartEvent(clock() - 2_000, DshStartOutcome.FAILED)
            )
        )
        assertEquals(DshSafeModeDecision.SAFE_MODE, policy.decide(burst, clock(), false))
        val recovered = history.copy(
            events = listOf(
                DshStartEvent(clock() - 1_000, DshStartOutcome.CRASHED),
                DshStartEvent(clock(), DshStartOutcome.READY)
            )
        )
        assertEquals(DshSafeModeDecision.NORMAL, policy.decide(recovered, clock(), false))
        assertEquals(DshSafeModeDecision.SAFE_MODE, policy.decide(history, clock(), true))
        val exhausted = history.copy(safeModeAttempts = 2)
        assertEquals(DshSafeModeDecision.RECOVERY_REQUIRED, policy.decide(exhausted, clock(), true))
        assertTrue(policy.canAutoRepair(history))
        assertFalse(policy.canAutoRepair(history.copy(autoRepairAttempts = 2)))
    }

    @Test
    fun enterStashesOnlyUserLayersAndExitRestoresThem() {
        val clock = TestSupport.FakeClock()
        val fixture = Fixture(newRoot(), clock)
        fixture.seedUserLayers()
        val entered = fixture.controller.enter(TestSupport.requester(), TestSupport.stopped())
        assertTrue(entered is DshSafeModeResult.Active)
        assertEquals(DshSafeModePhase.ACTIVE, fixture.state.load().phase)
        assertFalse(File(fixture.home, "user-patches").exists())
        assertFalse(File(fixture.home, "plugins-user").exists())
        // Data that is never a safe-mode target stays exactly where it is.
        assertTrue(File(fixture.home, "sessions/main/session-a/session.v4.jsonl.zstd").isFile)
        assertTrue(File(fixture.home, ".credentials.yaml").isFile)
        assertEquals(DshSafeModeDecision.SAFE_MODE, fixture.controller.decide(clock(), false))
        val exited = fixture.controller.exit(TestSupport.requester("op-exit"), TestSupport.stopped())
        assertTrue(exited is DshSafeModeResult.Inactive)
        assertEquals("patched", File(fixture.home, "user-patches/dsh-console/theme.css").readText())
        assertEquals("plugin", File(fixture.home, "plugins-user/tool/index.js").readText())
        assertTrue(File(fixture.home, "sessions/main/session-a/session.v4.jsonl.zstd").isFile)
    }

    @Test
    fun reseedsAnInterruptedEnterAndExit() {
        val clock = TestSupport.FakeClock()
        val root = newRoot()
        val fixture = Fixture(root, clock)
        fixture.seedUserLayers()
        val stash = File(root, "maintenance/safe-mode/manual")
        stash.mkdirs()
        // Model a crash after the first item moved and its state save: resume must finish the rest.
        val movedSource = File(fixture.home, "user-patches")
        val movedTarget = File(stash, "0")
        DshMaintenanceIo.moveDurably(movedSource, movedTarget)
        fixture.state.save(
            DshSafeModeState(
                phase = DshSafeModePhase.ENTERING,
                enteredAtMillis = clock(),
                stashDir = stash.absolutePath,
                items = listOf(
                    DshSafeModeItem("user-patches", moved = true),
                    DshSafeModeItem("plugins-user", moved = false)
                ),
                attempts = 1
            )
        )
        val resumed = fixture.controller.resume(TestSupport.requester("resume"), TestSupport.stopped())
        assertTrue(resumed is DshSafeModeResult.Active)
        assertFalse(File(fixture.home, "plugins-user").exists())
        assertTrue(File(stash, "1").isDirectory)
        // Now model an interrupted exit and resume it.
        fixture.state.save(
            fixture.state.load().copy(phase = DshSafeModePhase.EXITING)
        )
        val exited = fixture.controller.resume(TestSupport.requester("resume-exit"), TestSupport.stopped())
        assertTrue(exited is DshSafeModeResult.Inactive)
        assertTrue(File(fixture.home, "user-patches/dsh-console/theme.css").isFile)
        assertTrue(File(fixture.home, "plugins-user/tool/index.js").isFile)
    }

    @Test
    fun enterWithoutCandidateLayersStillActivatesSafely() {
        val clock = TestSupport.FakeClock()
        val fixture = Fixture(newRoot(), clock)
        TestSupport.write(File(fixture.home, "sessions/main/session-a/session.v4.jsonl.zstd"), "session")
        val entered = fixture.controller.enter(TestSupport.requester(), TestSupport.stopped())
        assertTrue(entered is DshSafeModeResult.Active)
        assertTrue((entered as DshSafeModeResult.Active).state.items.isEmpty())
        assertTrue(File(fixture.home, "sessions/main/session-a/session.v4.jsonl.zstd").isFile)
        assertEquals(DshSafeModePhase.ACTIVE, fixture.state.load().phase)
    }

    @Test
    fun crashDuringEnterLeavesResumableState() {
        val clock = TestSupport.FakeClock()
        val root = newRoot()
        val fixture = Fixture(root, clock)
        fixture.seedUserLayers()
        val crashingEngine = DshMaintenanceEngine(
            root,
            TestSupport.FakeAdmission(),
            TestSupport.healthyProbe(),
            crashHook = TestSupport.CrashAt(DshMaintenanceBoundary.PREPARED)
        )
        val crashingController = DshSafeModeController(
            crashingEngine,
            fixture.home,
            fixture.history,
            fixture.state,
            clock = clock
        )
        runCatching { crashingController.enter(TestSupport.requester(), TestSupport.stopped()) }
        assertEquals(DshSafeModePhase.ENTERING, fixture.state.load().phase)
        // Nothing had moved yet; a fresh controller finishes entering.
        val resumed = fixture.controller.resume(TestSupport.requester("resume"), TestSupport.stopped())
        assertTrue(resumed is DshSafeModeResult.Active)
        assertEquals(DshSafeModePhase.ACTIVE, fixture.state.load().phase)
    }
}
