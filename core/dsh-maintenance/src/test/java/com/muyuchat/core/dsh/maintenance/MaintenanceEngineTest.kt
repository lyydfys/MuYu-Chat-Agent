package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MaintenanceEngineTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("maintenance-engine")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private class Fixture(val root: File) {
        val home: File = File(root, "home")
        val staging: File = File(root, "staging")
        val original: File = File(home, "state.json")
        val staged: File = File(staging, "state.json")
        val admission = TestSupport.FakeAdmission()

        fun engine(
            probe: DshMaintenanceRecoveryProbe = TestSupport.healthyProbe(),
            crash: DshMaintenanceCrashHook = DshMaintenanceCrashHook.NONE,
            clock: () -> Long = TestSupport.FakeClock()
        ) = DshMaintenanceEngine(root, admission, probe, clock, crash)

        fun prepare(old: String = "old", new: String = "new"): Fixture {
            TestSupport.write(original, old)
            TestSupport.write(staged, new)
            return this
        }

        fun request(
            health: Boolean = true,
            restorePoint: File? = null,
            steps: List<DshMaintenanceStep> = listOf(
                DshMaintenanceStep(
                    action = DshStepAction.INSTALL_FILE,
                    target = original,
                    staging = staged,
                    expectedSha256 = DshMaintenanceIo.sha256(staged)
                )
            ),
            evidence: DshQuiescenceEvidence = TestSupport.stopped()
        ) = DshMaintenanceRequest(
            kind = DshMaintenanceKind.SESSION_REPAIR,
            requester = TestSupport.requester(),
            quiescence = evidence,
            targetRoot = home,
            steps = steps,
            restorePoint = restorePoint,
            healthCheckRequired = health
        )
    }

    @Test
    fun installsFileAndTouchesQuarantine() {
        val fixture = Fixture(newRoot()).prepare()
        val result = fixture.engine().execute(
            fixture.request(restorePoint = File(fixture.root, "restore-point"))
        )
        assertTrue(result is DshMaintenanceOutcome.Committed)
        assertEquals("new", fixture.original.readText())
        // The original is preserved in the transaction quarantine, not deleted.
        val quarantine = File(fixture.root, "maintenance/work")
        val quarantined = quarantine.walkTopDown().filter { it.isFile && it.name.endsWith("state.json") }
            .map { it.readText() }.toList()
        assertTrue(quarantined.contains("old"))
        assertEquals(1, fixture.admission.grants)
        assertEquals(1, fixture.admission.releases)
    }

    @Test
    fun rollsBackWhenHealthProbeRejectsData() {
        val fixture = Fixture(newRoot()).prepare()
        val result = fixture.engine(probe = TestSupport.unhealthyProbe()).execute(fixture.request())
        assertTrue(result is DshMaintenanceOutcome.RolledBack)
        assertEquals("old", fixture.original.readText())
        val outcome = result as DshMaintenanceOutcome.RolledBack
        assertEquals(DshMaintenanceError.HEALTH_FAILED, outcome.failure.code)
    }

    @Test
    fun rejectsUnprovenQuiescence() {
        val fixture = Fixture(newRoot()).prepare()
        val unproven = DshQuiescenceEvidence(
            hostGenerationStopped = false,
            onlineBarrierCovered = false,
            allMutatorsDrained = false
        )
        val result = fixture.engine().execute(fixture.request(evidence = unproven))
        assertTrue(result is DshMaintenanceOutcome.Rejected)
        assertEquals(DshMaintenanceError.QUIESCENCE_UNPROVEN, (result as DshMaintenanceOutcome.Rejected).failure.code)
        assertEquals("old", fixture.original.readText())
        assertEquals(1, fixture.admission.releases)
    }

    @Test
    fun rejectsBusyAdmission() {
        val fixture = Fixture(newRoot()).prepare()
        fixture.admission.busy = true
        val result = fixture.engine().execute(fixture.request())
        assertTrue(result is DshMaintenanceOutcome.Rejected)
        assertEquals(DshMaintenanceError.ADMISSION_BUSY, (result as DshMaintenanceOutcome.Rejected).failure.code)
        assertEquals("old", fixture.original.readText())
    }

    @Test
    fun rejectsPathEscapeAndMissingStaging() {
        val fixture = Fixture(newRoot()).prepare()
        val escaping = DshMaintenanceStep(
            action = DshStepAction.INSTALL_FILE,
            target = File(fixture.root, "outside.json"),
            staging = fixture.staged
        )
        val escapeResult = fixture.engine().execute(fixture.request(steps = listOf(escaping)))
        assertEquals(
            DshMaintenanceError.PATH_ESCAPE,
            (escapeResult as DshMaintenanceOutcome.Rejected).failure.code
        )
        fixture.staged.delete()
        val missingStep = DshMaintenanceStep(
            action = DshStepAction.INSTALL_FILE,
            target = fixture.original,
            staging = File(fixture.staging, "missing.json")
        )
        val missingResult = fixture.engine().execute(fixture.request(steps = listOf(missingStep)))
        assertEquals(
            DshMaintenanceError.STAGING_MISSING,
            (missingResult as DshMaintenanceOutcome.Rejected).failure.code
        )
        assertEquals("old", fixture.original.readText())
    }

    @Test
    fun recoversAfterCrashBeforeApply() {
        val fixture = Fixture(newRoot()).prepare()
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.PREPARED)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(fixture.request())
        }
        assertTrue(crash.fired)
        assertEquals("old", fixture.original.readText())
        val results = fixture.engine().recoverOnStart()
        assertEquals(1, results.size)
        assertTrue(results.first() is DshMaintenanceRecoveryResult.Aborted)
        assertEquals("old", fixture.original.readText())
        assertTrue(fixture.engine().recentStates().first().phase == DshMaintenancePhase.ABORTED)
    }

    @Test
    fun recoversAfterCrashMidApplyAndRestoresOriginal() {
        val fixture = Fixture(newRoot()).prepare()
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.STEP_APPLIED, stepIndex = 0)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(fixture.request())
        }
        assertEquals("new", fixture.original.readText())
        val results = fixture.engine().recoverOnStart()
        assertEquals(1, results.size)
        assertTrue(results.first() is DshMaintenanceRecoveryResult.RolledBack)
        assertEquals("old", fixture.original.readText())
    }

    @Test
    fun recoversHealthPendingAndCommitsWhenReaderAcceptsData() {
        val fixture = Fixture(newRoot()).prepare()
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.HEALTH_PENDING)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(fixture.request())
        }
        assertEquals("new", fixture.original.readText())
        val results = fixture.engine(probe = TestSupport.healthyProbe()).recoverOnStart()
        assertEquals(1, results.size)
        assertTrue(results.first() is DshMaintenanceRecoveryResult.Completed)
        assertEquals("new", fixture.original.readText())
    }

    @Test
    fun recoversHealthPendingAndRollsBackWhenReaderRejectsData() {
        val fixture = Fixture(newRoot()).prepare()
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.HEALTH_PENDING)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(fixture.request())
        }
        val results = fixture.engine(probe = TestSupport.unhealthyProbe()).recoverOnStart()
        assertEquals(1, results.size)
        assertTrue(results.first() is DshMaintenanceRecoveryResult.RolledBack)
        assertEquals("old", fixture.original.readText())
    }

    @Test
    fun crashAfterCommitKeepsNewFileAndRecoversNothing() {
        val fixture = Fixture(newRoot()).prepare()
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.COMMITTED)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(fixture.request())
        }
        assertEquals("new", fixture.original.readText())
        assertTrue(fixture.engine().recoverOnStart().isEmpty())
        assertTrue(fixture.engine().recentStates().first().phase == DshMaintenancePhase.COMMITTED)
    }

    @Test
    fun relocationRollsBackToTheOriginalLocation() {
        val root = newRoot()
        val home = File(root, "home")
        val sessionDir = File(home, "sessions/main/session-a")
        TestSupport.write(File(sessionDir, "session.v4.jsonl.zstd"), "session")
        val trash = File(home, "sessions-deleted/240101-120000-session-a")
        val fixture = Fixture(root)
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.STEP_APPLIED, stepIndex = 0)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(
                DshMaintenanceRequest(
                    kind = DshMaintenanceKind.SESSION_RECYCLE,
                    requester = TestSupport.requester(),
                    quiescence = TestSupport.stopped(),
                    targetRoot = home,
                    steps = listOf(
                        DshMaintenanceStep(
                            action = DshStepAction.RELOCATE,
                            target = sessionDir,
                            destination = trash
                        )
                    ),
                    healthCheckRequired = true
                )
            )
        }
        assertTrue(trash.isDirectory)
        assertFalse(sessionDir.exists())
        fixture.engine().recoverOnStart()
        assertTrue(sessionDir.isDirectory)
        assertFalse(trash.exists())
        assertEquals("session", File(sessionDir, "session.v4.jsonl.zstd").readText())
    }

    @Test
    fun rejectsWhenTheFreeSpaceBudgetCannotBeMet() {
        val fixture = Fixture(newRoot()).prepare()
        val request = DshMaintenanceRequest(
            kind = DshMaintenanceKind.RESTORE,
            requester = TestSupport.requester(),
            quiescence = TestSupport.stopped(),
            targetRoot = fixture.home,
            steps = listOf(
                DshMaintenanceStep(
                    action = DshStepAction.INSTALL_FILE,
                    target = fixture.original,
                    staging = fixture.staged
                )
            ),
            healthCheckRequired = false,
            requiredFreeBytes = Long.MAX_VALUE
        )
        val result = fixture.engine().execute(request)
        assertEquals(
            DshMaintenanceError.SPACE_INSUFFICIENT,
            (result as DshMaintenanceOutcome.Rejected).failure.code
        )
        assertEquals("old", fixture.original.readText())
    }

    @Test
    fun recoversSnapshotReadyCrashAsAborted() {
        val fixture = Fixture(newRoot()).prepare()
        val restorePoint = File(fixture.root, "restore-point")
        restorePoint.mkdirs()
        val crash = TestSupport.CrashAt(DshMaintenanceBoundary.SNAPSHOT_READY)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.engine(crash = crash).execute(fixture.request(restorePoint = restorePoint))
        }
        assertTrue(restorePoint.isDirectory)
        val results = fixture.engine().recoverOnStart()
        assertEquals(1, results.size)
        assertTrue(results.first() is DshMaintenanceRecoveryResult.Aborted)
        assertEquals("old", fixture.original.readText())
    }

    @Test
    fun journalContainsNoSecretAndRecordsEveryPhase() {
        val fixture = Fixture(newRoot()).prepare()
        val result = fixture.engine().execute(fixture.request())
        assertTrue(result is DshMaintenanceOutcome.Committed)
        val journal = File((result as DshMaintenanceOutcome.Committed).journalPath).readText()
        assertTrue(journal.contains("COMMITTED"))
        assertTrue(journal.contains("APPLYING"))
        assertTrue(journal.contains("QUIESCED"))
        assertFalse(journal.contains("secret", ignoreCase = true))
        assertNull(fixture.engine().recoverOnStart().firstOrNull())
    }
}
