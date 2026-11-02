package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradePhase
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateLookup
import com.muyuchat.core.dsh.maintenance.DshHostHealth
import com.muyuchat.core.dsh.maintenance.DshHostLifecyclePort
import com.muyuchat.core.dsh.maintenance.DshHostState
import com.muyuchat.core.dsh.maintenance.DshHostStatus
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionOutcome
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionPort
import com.muyuchat.core.dsh.maintenance.DshMaintenanceLease
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOwner
import com.muyuchat.core.dsh.maintenance.DshMaintenanceProbeResult
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRecoveryProbe
import com.muyuchat.core.dsh.maintenance.DshTrialActiveRecord
import com.muyuchat.core.dsh.maintenance.DshTrialPhase
import com.muyuchat.core.dsh.maintenance.DshUpgradeCandidate
import com.muyuchat.core.dsh.maintenance.DshUpgradePhase
import com.muyuchat.core.dsh.maintenance.DshUpgradeState
import com.muyuchat.core.dsh.maintenance.DshUpgradeStateStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D24 console behaviour of the closure upgrade around the isolated trial.
 *
 * These tests use the real [DshMaintenanceOwner] over a temporary data root (the owner reads its
 * durable upgrade state from that root), a stopped runtime projection and an injected candidate
 * lookup. They pin the three layers that must agree:
 *
 * - advertisement (which actions the port offers),
 * - projection (the coarse upgrade/trial phase + candidate token the console renders),
 * - execution gate (a stale console can never start a second upgrade over a pending decision).
 *
 * No trial process, engine transaction or backup is started by these cases; the assertions about
 * pending states are exactly the refusals that run before any effect.
 */
class DshMaintenanceUpgradeConsoleTest {

    @get:Rule
    val temporary: TemporaryFolder = TemporaryFolder()

    @Test
    fun `run is advertised only with a verified candidate and no pending upgrade`() = runTest {
        val root = temporary.newFolder("idle")
        val candidate = verifiedCandidate(root)

        val idle = adapter(root, owner(root, candidate)).snapshot()
        assertTrue(DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in idle.availableOperations)
        assertFalse(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in idle.availableOperations)
        assertEquals(DshMaintenanceUpgradePhase.IDLE, idle.upgrade.phase)
        assertEquals("candidate-1", idle.upgrade.candidateVersion)

        val noCandidate = adapter(root, owner(root)).snapshot()
        assertFalse(
            "no verified candidate means the run action must not be offered",
            DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in noCandidate.availableOperations
        )

        val committedRoot = temporary.newFolder("committed")
        writeUpgradeState(committedRoot, DshUpgradePhase.COMMITTED)
        val committed = adapter(
            committedRoot,
            owner(committedRoot, verifiedCandidate(committedRoot))
        ).snapshot()
        assertTrue(
            "a committed closure must not make the upgrade capability permanent",
            DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in committed.availableOperations
        )
        assertEquals(DshMaintenanceUpgradePhase.COMMITTED, committed.upgrade.phase)

        val recoveryRoot = temporary.newFolder("recovery")
        writeUpgradeState(recoveryRoot, DshUpgradePhase.RECOVERY_REQUIRED)
        val recovery = adapter(
            recoveryRoot,
            owner(recoveryRoot, verifiedCandidate(recoveryRoot))
        ).snapshot()
        assertFalse(
            "a recovery-required upgrade must not be overwritten by a new run",
            DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in recovery.availableOperations
        )
        assertEquals(DshMaintenanceUpgradePhase.RECOVERY_REQUIRED, recovery.upgrade.phase)
    }

    @Test
    fun `resume is advertised only for a pending trial or health step`() = runTest {
        val root = temporary.newFolder("resume")
        val owner = owner(root, verifiedCandidate(root))

        writeUpgradeState(root, DshUpgradePhase.TRIAL_RUNNING)
        val trial = adapter(root, owner).snapshot()
        assertTrue(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in trial.availableOperations)
        assertFalse(DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in trial.availableOperations)
        assertEquals(DshMaintenanceUpgradePhase.TRIAL_RUNNING, trial.upgrade.phase)
        assertEquals("candidate-1", trial.upgrade.candidateVersion)

        writeUpgradeState(root, DshUpgradePhase.HEALTH_PENDING)
        val health = adapter(root, owner).snapshot()
        assertTrue(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in health.availableOperations)
        assertEquals(DshMaintenanceUpgradePhase.HEALTH_PENDING, health.upgrade.phase)

        // Every pre-selector phase the contract folds into TRIAL_RUNNING stays resumable.
        listOf(
            DshUpgradePhase.VERIFIED,
            DshUpgradePhase.TRIAL_PASSED,
            DshUpgradePhase.APPLYING
        ).forEach { phase ->
            writeUpgradeState(root, phase)
            val snapshot = adapter(root, owner).snapshot()
            assertEquals(DshMaintenanceUpgradePhase.TRIAL_RUNNING, snapshot.upgrade.phase)
            assertTrue(
                "$phase must stay resumable",
                DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in snapshot.availableOperations
            )
        }

        writeUpgradeState(root, DshUpgradePhase.ROLLED_BACK)
        val rolledBack = adapter(root, owner).snapshot()
        assertFalse(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in rolledBack.availableOperations)
        assertEquals(DshMaintenanceUpgradePhase.ROLLED_BACK, rolledBack.upgrade.phase)
    }

    @Test
    fun `abandon is advertised exactly for a recovery-required upgrade`() = runTest {
        val root = temporary.newFolder("abandon")
        val owner = owner(root, verifiedCandidate(root))

        writeUpgradeState(root, DshUpgradePhase.RECOVERY_REQUIRED)
        val recovery = adapter(root, owner).snapshot()
        assertTrue(
            "a recovery-required upgrade is otherwise a permanent boot block",
            DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE in recovery.availableOperations
        )
        assertFalse(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in recovery.availableOperations)
        assertFalse(DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in recovery.availableOperations)

        writeUpgradeState(root, DshUpgradePhase.TRIAL_RUNNING)
        val trial = adapter(root, owner).snapshot()
        assertFalse(DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE in trial.availableOperations)
    }

    @Test
    fun `a live trial journal is surfaced even without an upgrade state`() = runTest {
        val root = temporary.newFolder("journal")
        val owner = owner(root, verifiedCandidate(root))

        val running = adapter(
            root,
            owner,
            trial = trialRecord(DshTrialPhase.RUNNING, candidateId = "candidate-9")
        ).snapshot()
        assertEquals(DshMaintenanceUpgradePhase.TRIAL_RUNNING, running.upgrade.phase)
        assertEquals("candidate-9", running.upgrade.candidateVersion)
        // The trial journal alone never advertises run, and it cannot advertise resume without an
        // upgrade state that actually owns the decision.
        assertFalse(DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in running.availableOperations)
        assertFalse(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in running.availableOperations)

        val unreleased = adapter(
            root,
            owner,
            trial = trialRecord(DshTrialPhase.RECOVERY_REQUIRED, candidateId = "candidate-9")
        ).snapshot()
        assertEquals(DshMaintenanceUpgradePhase.RECOVERY_REQUIRED, unreleased.upgrade.phase)
        assertFalse(DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE in unreleased.availableOperations)

        // A terminal journal never upgrades an idle upgrade state: a failed launch is simply idle,
        // and the candidate token comes from the staged closure, not from the dead trial attempt.
        val failed = adapter(
            root,
            owner,
            trial = trialRecord(DshTrialPhase.FAILED, candidateId = "candidate-9")
        ).snapshot()
        assertEquals(DshMaintenanceUpgradePhase.IDLE, failed.upgrade.phase)
        assertEquals("candidate-1", failed.upgrade.candidateVersion)
    }

    @Test
    fun `a stale console cannot start a second upgrade over a pending decision`() = runTest {
        val root = temporary.newFolder("pending")
        val owner = owner(root, verifiedCandidate(root))
        writeUpgradeState(root, DshUpgradePhase.TRIAL_RUNNING)
        val console = adapter(root, owner)

        val refused = console.execute(
            DshMaintenanceConsoleIntent.RunClosureUpgrade(confirmed = true)
        )

        assertTrue(refused is DshMaintenanceConsoleResult.Refused)
        assertEquals(
            DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS,
            (refused as DshMaintenanceConsoleResult.Refused).reason
        )
        // The refusal happens before the backup/trial transaction: no effect was created.
        val backupsDir = File(root, "maintenance/backups")
        assertFalse(backupsDir.isDirectory && (backupsDir.listFiles()?.isNotEmpty() == true))
    }

    @Test
    fun `a committed upgrade does not refuse the next staged candidate`() = runTest {
        val root = temporary.newFolder("next")
        val owner = owner(root, verifiedCandidate(root))
        writeUpgradeState(root, DshUpgradePhase.COMMITTED)

        val result = adapter(root, owner).execute(
            DshMaintenanceConsoleIntent.RunClosureUpgrade(confirmed = true)
        )

        // Anything after the phase gate comes from this fixture's non-executable candidate or its
        // empty backup sources; "already committed" itself must never be the refusal again.
        assertFalse(
            result is DshMaintenanceConsoleResult.Refused &&
                result.reason == DshMaintenanceConsoleRefusal.NOT_ELIGIBLE
        )
    }

    @Test
    fun `a recovery-required upgrade is a typed failure not a new attempt`() = runTest {
        val root = temporary.newFolder("stuck")
        val owner = owner(root, verifiedCandidate(root))
        writeUpgradeState(root, DshUpgradePhase.RECOVERY_REQUIRED)

        val result = adapter(root, owner).execute(
            DshMaintenanceConsoleIntent.RunClosureUpgrade(confirmed = true)
        )

        assertTrue(result is DshMaintenanceConsoleResult.Failed)
        val failed = result as DshMaintenanceConsoleResult.Failed
        assertEquals("RECOVERY_REQUIRED", failed.code)
        assertFalse(failed.retryable)
    }

    @Test
    fun `resume without a pending upgrade is refused and run without a candidate is refused`() = runTest {
        val root = temporary.newFolder("idle")
        val console = adapter(root, owner(root))

        val resume = console.execute(DshMaintenanceConsoleIntent.ResumeClosureUpgrade)
        assertTrue(resume is DshMaintenanceConsoleResult.Refused)
        assertEquals(
            DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE,
            (resume as DshMaintenanceConsoleResult.Refused).reason
        )

        val run = console.execute(DshMaintenanceConsoleIntent.RunClosureUpgrade(confirmed = true))
        assertTrue(run is DshMaintenanceConsoleResult.Refused)
        assertEquals(
            DshMaintenanceConsoleRefusal.NOT_ELIGIBLE,
            (run as DshMaintenanceConsoleResult.Refused).reason
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun adapter(
        root: File,
        owner: DshMaintenanceOwner,
        trial: DshTrialActiveRecord? = null
    ): DshMaintenanceConsoleAdapter = DshMaintenanceConsoleAdapter(
        dataRoot = root,
        runtime = StoppedRuntime(),
        maintenance = { owner },
        clock = { 1_000L },
        trialJournal = { trial }
    )

    private fun owner(
        root: File,
        candidate: DshClosureCandidateLookup = DshClosureCandidateLookup.None
    ): DshMaintenanceOwner = DshMaintenanceOwner(
        dataRoot = root,
        admission = DshMaintenanceAdmissionPort {
            DshMaintenanceAdmissionOutcome.Granted(
                object : DshMaintenanceLease {
                    override val leaseId: String = "lease-1"
                    override val owner: String = "console-test"
                    override fun release() = Unit
                }
            )
        },
        probe = DshMaintenanceRecoveryProbe { _, _, _ -> DshMaintenanceProbeResult.HEALTHY },
        host = NoTrialHost(),
        supportedAbis = setOf("arm64-v8a"),
        candidateLookup = { candidate }
    )

    private fun writeUpgradeState(root: File, phase: DshUpgradePhase) {
        DshUpgradeStateStore(File(root, "maintenance/upgrade.json")).write(
            DshUpgradeState(
                phase = phase,
                candidateId = "candidate-1",
                candidateFingerprint = "a".repeat(64),
                updatedAtMillis = 1_000L
            )
        )
    }

    private fun verifiedCandidate(root: File): DshClosureCandidateLookup =
        DshClosureCandidateLookup.Available(
            DshUpgradeCandidate(
                candidateId = "candidate-1",
                stagedRoot = File(root, "closure/incoming/candidate-1"),
                artifacts = emptyMap(),
                targetAbi = "arm64-v8a",
                dataSchemaVersion = 5,
                minReadableDataSchema = 4,
                migratesData = false,
                nativeLibraryReplaced = false,
                prePackagedSlotVerified = true,
                abiCompatibleWithPrevious = true
            )
        )

    private fun trialRecord(phase: DshTrialPhase, candidateId: String): DshTrialActiveRecord =
        DshTrialActiveRecord(
            phase = phase,
            candidateId = candidateId,
            candidateFingerprint = "a".repeat(64),
            candidateRoot = "",
            homeRoot = "",
            workspaceRoot = "",
            tmpRoot = "",
            pid = 42L,
            startedAtMillis = 900L,
            updatedAtMillis = 950L
        )

    private class StoppedRuntime : DshRuntimeClient {
        private val state = MutableStateFlow(DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED))
        override val snapshot: StateFlow<DshRuntimeSnapshot> = state

        override suspend fun ensureStarted(): DshRuntimeSnapshot = state.value
        override suspend fun restart(): DshRuntimeSnapshot = state.value
        override suspend fun stop() = Unit
        override suspend fun webEntry(): DshWebEntry? = null
    }

    /** The production half only; the trial half is owned by the DshClosureTrialHost wrapper. */
    private class NoTrialHost : DshHostLifecyclePort {
        override fun status(): DshHostStatus = DshHostStatus(DshHostState.STOPPED)
        override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus =
            DshHostStatus(DshHostState.FAILED, detail = "no trial generation")

        override fun probeTrialHealth(): DshHostHealth = DshHostHealth(detail = "no trial generation")

        override fun stopTrial(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun startProduction(activeRoot: File, signature: String): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun probeReadOnlyHealth(): DshHostHealth = DshHostHealth()
    }
}
