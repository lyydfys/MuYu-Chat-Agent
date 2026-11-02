package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClosureUpgradeAbandonTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-upgrade-abandon")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private class Fixture(val root: File) {
        val host = TestSupport.FakeHost()
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val selectorStore = DshClosureSelectorStore(File(root, "closure/selector.json"))
        val stateStore = DshUpgradeStateStore(File(root, "maintenance/upgrade.json"))
        val manager = DshClosureUpgradeManager(engine, host, setOf("arm64-v8a"))

        fun seedActive() {
            selectorStore.write(
                DshClosureSelector("prev-fingerprint", "/previous/closure", 1, committedAtMillis = 10L)
            )
        }

        fun seedRecoveryRequired() {
            stateStore.write(
                DshUpgradeState(
                    phase = DshUpgradePhase.RECOVERY_REQUIRED,
                    candidateId = "cand-9",
                    candidateFingerprint = "cand-fingerprint",
                    candidateRoot = "$root/candidates/cand-9",
                    previousFingerprint = "prev-fingerprint",
                    previousRoot = "/previous/closure",
                    previousDataSchemaVersion = 1,
                    updatedAtMillis = 20L,
                    detail = "resume-trial-unstopped"
                )
            )
        }
    }

    @Test
    fun `abandon outside recovery changes nothing`() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.stateStore.write(DshUpgradeState(phase = DshUpgradePhase.ROLLED_BACK, detail = "done"))

        val result = fixture.manager.abandon(TestSupport.requester(), TestSupport.stopped())

        assertTrue(result is DshUpgradeResult.NoChange)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.stateStore.read().phase)
        assertEquals("done", fixture.stateStore.read().detail)
    }

    @Test
    fun `abandon releases the trial and records a rollback without touching the selector`() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.seedRecoveryRequired()

        val result = fixture.manager.abandon(TestSupport.requester(), TestSupport.stopped())

        assertTrue("expected a typed rollback, got $result", result is DshUpgradeResult.RolledBack)
        val state = fixture.stateStore.read()
        assertEquals(DshUpgradePhase.ROLLED_BACK, state.phase)
        assertEquals("operator-abandoned", state.detail)
        assertEquals("the selector must keep serving the previous closure", "prev-fingerprint", fixture.selectorStore.read()?.fingerprint)
        assertTrue("the trial release must be attempted", fixture.host.calls.contains("stopTrial"))
    }

    @Test
    fun `abandon refuses while the interrupted trial cannot be proven stopped`() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.seedRecoveryRequired()
        fixture.host.state = DshHostState.RUNNING
        fixture.host.trialStopRefuses = true

        val result = fixture.manager.abandon(TestSupport.requester(), TestSupport.stopped())

        assertTrue("expected recovery to stay required, got $result", result is DshUpgradeResult.RecoveryRequired)
        assertEquals(DshUpgradePhase.RECOVERY_REQUIRED, fixture.stateStore.read().phase)
        assertEquals("prev-fingerprint", fixture.selectorStore.read()?.fingerprint)
    }
}
