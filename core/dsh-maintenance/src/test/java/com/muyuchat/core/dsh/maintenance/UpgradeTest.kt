package com.muyuchat.core.dsh.maintenance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpgradeTest {

    private val roots = ArrayList<File>()

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-upgrade")
        roots.add(root)
        return root
    }

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private class Fixture(val root: File) {
        val host = TestSupport.FakeHost()
        val admission = TestSupport.FakeAdmission()
        var engine = DshMaintenanceEngine(root, admission, TestSupport.healthyProbe())
        val selectorStore = DshClosureSelectorStore(File(root, "closure/selector.json"))
        var manager = DshClosureUpgradeManager(engine, host, setOf("arm64-v8a"))

        fun seedActive(fingerprint: String = "prev-fingerprint", schema: Int = 1) {
            selectorStore.write(
                DshClosureSelector(fingerprint, "/previous/closure", schema, committedAtMillis = 10L)
            )
        }

        fun rebuildEngine(crash: DshMaintenanceCrashHook = DshMaintenanceCrashHook.NONE) {
            engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe(), crashHook = crash)
            manager = DshClosureUpgradeManager(engine, host, setOf("arm64-v8a"))
        }
    }

    private fun candidate(
        root: File,
        id: String = "cand-1",
        schema: Int = 1,
        minSchema: Int = 1,
        migrates: Boolean = false,
        nativeReplaced: Boolean = false,
        slotVerified: Boolean = false,
        abiCompatible: Boolean = false,
        abi: String = "arm64-v8a",
        omit: String? = null
    ): DshUpgradeCandidate {
        val staged = File(root, "candidates/$id")
        val artifacts = LinkedHashMap<String, File>()
        for (name in DSH_CLOSURE_COMPONENTS) {
            if (name == omit) continue
            val artifact = File(staged, "$name.bin")
            TestSupport.write(artifact, "artifact-$name-$id")
            artifacts[name] = artifact
        }
        val payloadRoot = File(staged, DshUpgradeCandidate.PAYLOAD_DIR_NAME)
        val kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        val kernel = File(payloadRoot, kernelEntry)
        TestSupport.write(kernel, "kernel-$id")
        return DshUpgradeCandidate(
            candidateId = id,
            stagedRoot = staged,
            artifacts = artifacts,
            targetAbi = abi,
            dataSchemaVersion = schema,
            minReadableDataSchema = minSchema,
            migratesData = migrates,
            nativeLibraryReplaced = nativeReplaced,
            prePackagedSlotVerified = slotVerified,
            abiCompatibleWithPrevious = abiCompatible,
            payloadRoot = payloadRoot,
            payloadKernelEntry = kernelEntry,
            payloadCriticalFiles = mapOf(kernelEntry to DshMaintenanceIo.sha256(kernel)),
            payloadArchiveSha256 = "a".repeat(64),
            payloadFileCount = 1L
        )
    }

    @Test
    fun happyPathCommitsOneSerialClosure() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val candidate = candidate(fixture.root)
        val result = fixture.manager.run(candidate, TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.Committed)
        val selector = fixture.manager.selector()!!
        assertEquals(candidate.identity().fingerprint, selector.fingerprint)
        assertEquals("prev-fingerprint", selector.previousFingerprint)
        assertEquals(1, fixture.host.trialStarts)
        assertEquals(1, fixture.host.productionStarts)
        assertEquals(listOf("startTrial", "stopTrial", "startProduction"), fixture.host.calls)
        assertEquals(DshUpgradePhase.COMMITTED, fixture.manager.state().phase)
    }

    @Test
    fun anIdentityOnlyCandidateIsRefusedBeforeAnyTrialOrProductionStart() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val identityOnly = candidate(fixture.root).copy(
            payloadRoot = null,
            payloadKernelEntry = "",
            payloadCriticalFiles = emptyMap()
        )

        val result = fixture.manager.run(identityOnly, TestSupport.requester(), TestSupport.stopped())

        assertTrue(result is DshUpgradeResult.Rejected)
        assertEquals(0, fixture.host.trialStarts)
        assertEquals(0, fixture.host.productionStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
    }

    @Test
    fun aCandidatePayloadTamperedAfterStagingIsRefusedAtThePointOfUse() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val candidate = candidate(fixture.root)
        TestSupport.write(
            File(candidate.payloadRoot!!, candidate.payloadKernelEntry),
            "tampered-after-staging"
        )

        val result = fixture.manager.run(candidate, TestSupport.requester(), TestSupport.stopped())

        assertTrue(result is DshUpgradeResult.Rejected)
        assertEquals(0, fixture.host.trialStarts)
        assertEquals(0, fixture.host.productionStarts)
    }

    @Test
    fun theCommittedSelectorPersistsInstalledAndLastKnownGoodIdentity() {
        val root = newRoot()
        val engine = DshMaintenanceEngine(root, TestSupport.FakeAdmission(), TestSupport.healthyProbe())
        val manager = DshClosureUpgradeManager(
            engine,
            TestSupport.FakeHost(),
            setOf("arm64-v8a"),
            installedIdentity = { DshInstalledPayloadIdentity("dsh-9.9", "b".repeat(64)) }
        )
        DshClosureSelectorStore(File(root, "closure/selector.json"))
            .write(DshClosureSelector("prev-fingerprint", "/previous/closure", 1, committedAtMillis = 10L))
        val candidate = candidate(root)

        val result = manager.run(candidate, TestSupport.requester(), TestSupport.stopped())

        assertTrue(result is DshUpgradeResult.Committed)
        val selector = manager.selector()!!
        assertEquals(candidate.identity().fingerprint, selector.fingerprint)
        assertEquals(candidate.identity().fingerprint, selector.lastGoodFingerprint)
        assertEquals("prev-fingerprint", selector.previousFingerprint)
        assertEquals("dsh-9.9", selector.installedPayloadVersion)
        assertEquals("b".repeat(64), selector.installedArchiveSha256)
    }

    @Test
    fun trialStartFailureNeverStartsProduction() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.startState = DshHostState.FAILED
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.TrialFailed)
        assertEquals(0, fixture.host.productionStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
    }

    @Test
    fun aNewUserTrialPassesOnAnInitializedEmptySessionStore() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.trialHealth = DshHostHealth(
            hostReady = true,
            homeReadable = true,
            sessionReaderReady = false,
            sessionStoreInitialized = true,
            newUserProfile = true
        )
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.Committed)
        assertEquals(1, fixture.host.productionStarts)
    }

    @Test
    fun aTrialWithMigratedSessionsStillRequiresAVerifiedContainer() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.trialHealth = DshHostHealth(
            hostReady = true,
            homeReadable = true,
            sessionReaderReady = false,
            sessionStoreInitialized = true,
            newUserProfile = false
        )
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.TrialFailed)
        assertEquals(0, fixture.host.productionStarts)
    }

    @Test
    fun trialHealthFailureNeverStartsProduction() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.trialHealth = DshHostHealth(hostReady = false)
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.TrialFailed)
        assertEquals(0, fixture.host.productionStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
    }

    @Test
    fun trialThatCannotStopBlocksProduction() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.trialStopRefuses = true
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.RecoveryRequired)
        assertEquals(0, fixture.host.productionStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.RECOVERY_REQUIRED, fixture.manager.state().phase)
    }

    @Test
    fun hostThatWillNotStopIsRejectedBeforeAnyTrial() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.state = DshHostState.RUNNING
        fixture.host.stopRefuses = true
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertEquals(
            DshMaintenanceError.HOST_STILL_RUNNING,
            (result as DshUpgradeResult.Rejected).failure.code
        )
        assertEquals(0, fixture.host.trialStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
    }

    @Test
    fun dataSchemaDowngradeIsRejectedAndMixedClosureIsRejected() {
        val fixture = Fixture(newRoot())
        fixture.seedActive(schema = 5)
        val downgrade = fixture.manager.run(
            candidate(fixture.root, schema = 3),
            TestSupport.requester(),
            TestSupport.stopped()
        )
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (downgrade as DshUpgradeResult.Rejected).failure.code
        )
        assertEquals(0, fixture.host.trialStarts)
        val incomplete = fixture.manager.run(
            candidate(fixture.root, id = "cand-incomplete", omit = "node"),
            TestSupport.requester(),
            TestSupport.stopped()
        )
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (incomplete as DshUpgradeResult.Rejected).failure.code
        )
        val wrongAbi = fixture.manager.run(
            candidate(fixture.root, id = "cand-abi", abi = "x86_64"),
            TestSupport.requester(),
            TestSupport.stopped()
        )
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (wrongAbi as DshUpgradeResult.Rejected).failure.code
        )
        assertEquals(0, fixture.host.trialStarts)
    }

    @Test
    fun productionHealthFailureStopsTheCandidateAndRestoresThePreviousClosure() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        // The candidate starts but is unhealthy; the restored previous closure is healthy.
        fixture.host.productionHealthPerStart = listOf(
            DshHostHealth(hostReady = true, homeReadable = false),
            DshHostHealth(
                hostReady = true,
                homeReadable = true,
                sessionReaderReady = true,
                providerConfigured = true,
                authReady = true
            )
        )
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.RolledBack)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
        // The unhealthy generation was stopped and the previous closure served again.
        assertEquals(2, fixture.host.productionStarts)
        assertTrue(fixture.host.calls.contains("stop"))
        assertEquals(DshHostState.RUNNING, fixture.host.state)
    }

    @Test
    fun aFirstCommitThatFailsHealthClearsTheSelectorAndRestoresInstalledPayload() {
        val fixture = Fixture(newRoot())
        fixture.host.productionHealthPerStart = listOf(
            DshHostHealth(hostReady = true, homeReadable = false),
            DshHostHealth(
                hostReady = true,
                homeReadable = true,
                sessionReaderReady = true,
                providerConfigured = true,
                authReady = true
            )
        )
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.RolledBack)
        assertNull(fixture.manager.selector())
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
        assertEquals(2, fixture.host.productionStarts)
        assertEquals(DshHostState.RUNNING, fixture.host.state)
    }

    @Test
    fun aPreviousClosureThatDoesNotServeHealthyAfterRollbackBecomesRecoveryRequired() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.productionHealth = DshHostHealth(hostReady = true, homeReadable = false)
        val result = fixture.manager.run(candidate(fixture.root), TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.RecoveryRequired)
        // The selector was reverted, but the serving state is unproven, so it is not "rolled back".
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.RECOVERY_REQUIRED, fixture.manager.state().phase)
        assertEquals(2, fixture.host.productionStarts)
    }

    @Test
    fun unverifiableBinaryRollbackBecomesRecoveryRequired() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.host.productionHealth = DshHostHealth(hostReady = false)
        val candidate = candidate(fixture.root, nativeReplaced = true, slotVerified = false, abiCompatible = false)
        val result = fixture.manager.run(candidate, TestSupport.requester(), TestSupport.stopped())
        assertTrue(result is DshUpgradeResult.RecoveryRequired)
        assertEquals(candidate.identity().fingerprint, fixture.manager.selector()!!.fingerprint)
        assertNotEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.RECOVERY_REQUIRED, fixture.manager.state().phase)
    }

    @Test
    fun aFailedTrialLeavesProductionDataAndTheBackupUntouched() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val session = File(fixture.root, "home/sessions/session-1.jsonl.zstd").apply {
            TestSupport.write(this, "real-user-session")
        }
        val workspaceFile = File(fixture.root, "workspace/notes.txt").apply {
            TestSupport.write(this, "real-workspace-note")
        }
        val before = DshMaintenanceIo.sha256(session) to DshMaintenanceIo.sha256(workspaceFile)
        fixture.host.trialHealth = DshHostHealth(hostReady = true, homeReadable = false)
        var backups = 0

        val result = fixture.manager.run(
            candidate(fixture.root),
            TestSupport.requester(),
            TestSupport.stopped()
        ) {
            backups += 1
            null
        }

        assertTrue(result is DshUpgradeResult.TrialFailed)
        assertEquals(0, backups)
        assertEquals(0, fixture.host.productionStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
        assertEquals(before.first, DshMaintenanceIo.sha256(session))
        assertEquals(before.second, DshMaintenanceIo.sha256(workspaceFile))
    }

    @Test
    fun aCandidateWhoseStagingTreeDisappearedIsRejectedBeforeAnyHostWork() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val candidate = candidate(fixture.root)
        TestSupport.deleteTree(candidate.stagedRoot)

        val result = fixture.manager.run(candidate, TestSupport.requester(), TestSupport.stopped())

        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (result as DshUpgradeResult.Rejected).failure.code
        )
        assertTrue(fixture.host.calls.isEmpty())
        assertEquals(0, fixture.host.trialStarts)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
    }

    @Test
    fun crashBeforeSelectorSwitchKeepsThePreviousClosure() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val candidate = candidate(fixture.root)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.manager.run(
                candidate,
                TestSupport.requester(),
                TestSupport.stopped()
            ) { throw DshSimulatedCrash("backup interrupted") }
        }
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.TRIAL_PASSED, fixture.manager.state().phase)
        fixture.rebuildEngine()
        val resumed = fixture.manager.resume(TestSupport.requester("resume"), TestSupport.stopped())
        assertTrue(resumed is DshUpgradeResult.NoChange)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
    }

    @Test
    fun crashAfterCommitKeepsTheNewSelectorAndResumeDoesNotRollBack() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        fixture.rebuildEngine(TestSupport.CrashAt(DshMaintenanceBoundary.COMMITTED))
        val candidate = candidate(fixture.root)
        assertThrows(DshSimulatedCrash::class.java) {
            fixture.manager.run(candidate, TestSupport.requester(), TestSupport.stopped())
        }
        assertEquals(DshUpgradePhase.COMMITTED, fixture.manager.state().phase)
        assertEquals(candidate.identity().fingerprint, fixture.manager.selector()!!.fingerprint)
        fixture.rebuildEngine()
        val resumed = fixture.manager.resume(TestSupport.requester("resume"), TestSupport.stopped())
        assertTrue(resumed is DshUpgradeResult.NoChange)
        assertEquals(candidate.identity().fingerprint, fixture.manager.selector()!!.fingerprint)
        assertEquals(1, fixture.host.trialStarts)
    }

    /** The durable state of a crash after the selector switch, before health was confirmed. */
    private fun Fixture.seedHealthPending(candidate: DshUpgradeCandidate): String {
        seedActive()
        val identity = candidate.identity()
        selectorStore.write(
            DshClosureSelector(
                fingerprint = identity.fingerprint,
                root = candidate.stagedRoot.absolutePath,
                dataSchemaVersion = candidate.dataSchemaVersion,
                committedAtMillis = 20L,
                previousFingerprint = "prev-fingerprint",
                previousRoot = "/previous/closure",
                lastGoodFingerprint = identity.fingerprint,
                lastGoodRoot = candidate.stagedRoot.absolutePath
            )
        )
        DshUpgradeStateStore(File(root, "maintenance/upgrade.json")).write(
            DshUpgradeState(
                phase = DshUpgradePhase.HEALTH_PENDING,
                candidateId = candidate.candidateId,
                candidateFingerprint = identity.fingerprint,
                candidateRoot = candidate.stagedRoot.absolutePath,
                previousFingerprint = "prev-fingerprint",
                previousRoot = "/previous/closure",
                previousDataSchemaVersion = 1,
                updatedAtMillis = 20L
            )
        )
        rebuildEngine()
        return identity.fingerprint
    }

    @Test
    fun resumeDuringStartupNeverStartsTheHostItGuards() {
        val fixture = Fixture(newRoot())
        val candidate = candidate(fixture.root)
        fixture.seedHealthPending(candidate)

        val resumed = fixture.manager.resume(TestSupport.requester("resume", owner = "startup"), TestSupport.stopped())

        assertTrue(resumed is DshUpgradeResult.NoChange)
        assertEquals(DshUpgradePhase.HEALTH_PENDING, fixture.manager.state().phase)
        assertEquals(candidate.identity().fingerprint, fixture.manager.selector()!!.fingerprint)
        // The sweep runs inside the runtime's own pre-start admission; a start here would re-enter it.
        assertEquals(0, fixture.host.productionStarts)
        assertTrue(fixture.host.calls.none { it == "startProduction" })
    }

    @Test
    fun postStartConfirmationCommitsAHealthyCommittedSelector() {
        val fixture = Fixture(newRoot())
        val candidate = candidate(fixture.root)
        val fingerprint = fixture.seedHealthPending(candidate)
        // The runtime is now serving the selector the interrupted apply committed.
        fixture.host.state = DshHostState.RUNNING

        val confirmed = fixture.manager.confirmAfterStart(
            TestSupport.requester("post-start", owner = "post-start"),
            TestSupport.stopped()
        )

        assertTrue(confirmed is DshUpgradeResult.Committed)
        assertEquals(DshUpgradePhase.COMMITTED, fixture.manager.state().phase)
        assertEquals(fingerprint, fixture.manager.selector()!!.fingerprint)
        assertEquals(0, fixture.host.productionStarts)
    }

    @Test
    fun postStartConfirmationRollsBackAnUnhealthyCommittedSelector() {
        val fixture = Fixture(newRoot())
        val candidate = candidate(fixture.root)
        fixture.seedHealthPending(candidate)
        fixture.host.state = DshHostState.RUNNING
        // The serving candidate is unhealthy; the restored previous closure is healthy.
        fixture.host.productionHealth = DshHostHealth(hostReady = true, homeReadable = false)
        fixture.host.productionHealthPerStart = listOf(
            DshHostHealth(
                hostReady = true,
                homeReadable = true,
                sessionReaderReady = true,
                providerConfigured = true,
                authReady = true
            )
        )

        val confirmed = fixture.manager.confirmAfterStart(
            TestSupport.requester("post-start", owner = "post-start"),
            TestSupport.stopped()
        )

        assertTrue(confirmed is DshUpgradeResult.RolledBack)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        // Candidate served, was stopped, and the previous closure was started and proved healthy.
        assertTrue("stop" in fixture.host.calls)
        assertEquals(1, fixture.host.productionStarts)
        assertEquals(DshHostState.RUNNING, fixture.host.state)
    }

    @Test
    fun resumeWithAnInterruptedTrialRollsBackOnlyAfterTheTrialIsConfirmedStopped() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val candidate = candidate(fixture.root)
        val identity = candidate.identity()
        DshUpgradeStateStore(File(fixture.root, "maintenance/upgrade.json")).write(
            DshUpgradeState(
                phase = DshUpgradePhase.TRIAL_RUNNING,
                candidateId = candidate.candidateId,
                candidateFingerprint = identity.fingerprint,
                candidateRoot = candidate.stagedRoot.absolutePath,
                previousFingerprint = "prev-fingerprint",
                previousRoot = "/previous/closure",
                previousDataSchemaVersion = 1,
                updatedAtMillis = 20L
            )
        )
        fixture.host.state = DshHostState.RUNNING
        fixture.rebuildEngine()

        val resumed = fixture.manager.resume(TestSupport.requester("resume"), TestSupport.stopped())

        assertTrue(resumed is DshUpgradeResult.NoChange)
        assertTrue("stopTrial" in fixture.host.calls)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshUpgradePhase.ROLLED_BACK, fixture.manager.state().phase)
        assertEquals(DshHostState.STOPPED, fixture.host.state)
    }

    @Test
    fun resumeWithATrialThatWillNotStopBecomesRecoveryRequired() {
        val fixture = Fixture(newRoot())
        fixture.seedActive()
        val candidate = candidate(fixture.root)
        val identity = candidate.identity()
        DshUpgradeStateStore(File(fixture.root, "maintenance/upgrade.json")).write(
            DshUpgradeState(
                phase = DshUpgradePhase.TRIAL_RUNNING,
                candidateId = candidate.candidateId,
                candidateFingerprint = identity.fingerprint,
                candidateRoot = candidate.stagedRoot.absolutePath,
                previousFingerprint = "prev-fingerprint",
                previousRoot = "/previous/closure",
                previousDataSchemaVersion = 1,
                updatedAtMillis = 20L
            )
        )
        fixture.host.state = DshHostState.RUNNING
        fixture.host.trialStopRefuses = true
        fixture.rebuildEngine()

        val resumed = fixture.manager.resume(TestSupport.requester("resume"), TestSupport.stopped())

        assertTrue(resumed is DshUpgradeResult.RecoveryRequired)
        assertEquals(DshUpgradePhase.RECOVERY_REQUIRED, fixture.manager.state().phase)
        assertEquals("prev-fingerprint", fixture.manager.selector()!!.fingerprint)
        assertEquals(DshHostState.RUNNING, fixture.host.state)
    }
}
