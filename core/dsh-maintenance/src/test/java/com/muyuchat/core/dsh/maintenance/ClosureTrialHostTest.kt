package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trial-host arbitration, stop proof and recovery tests with fakes.
 *
 * The real launch is owned by the runtime owner's `:dsh` process; these tests prove that the app
 * side never reports a trial as healthy/stopped without a positive proof from that port, and that
 * production and trial can never be started in the wrong order.
 */
class ClosureTrialHostTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-trial-host")
        roots.add(root)
        return root
    }

    private class FakeProduction : DshHostLifecyclePort {
        var state = DshHostState.STOPPED
        val calls = ArrayList<String>()

        override fun status(): DshHostStatus = DshHostStatus(state)

        override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus {
            calls.add("production.stop")
            state = DshHostState.STOPPED
            return DshHostStatus(state)
        }

        override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus =
            DshHostStatus(DshHostState.FAILED, detail = "fake production port must not own trials")

        override fun probeTrialHealth(): DshHostHealth = DshHostHealth()

        override fun stopTrial(timeoutMillis: Long): DshHostStatus = DshHostStatus(DshHostState.STOPPED)

        override fun startProduction(activeRoot: File, signature: String): DshHostStatus {
            calls.add("production.start")
            state = DshHostState.RUNNING
            return DshHostStatus(state, generation = 9L)
        }

        override fun probeReadOnlyHealth(): DshHostHealth =
            DshHostHealth(true, true, true, true, true)
    }

    private class FakeTrial : DshTrialLaunchPort {
        var launchResult: DshTrialLaunchResult = DshTrialLaunchResult.Started(pid = 4242L, generation = 3L)
        var stopProof: DshTrialStopProof = DshTrialStopProof.Stopped
        var health: DshHostHealth = DshHostHealth(hostReady = true, homeReadable = true, sessionReaderReady = true)
        var running: Boolean? = false
        val calls = ArrayList<String>()
        var launches = 0
        var lastRequest: DshTrialLaunchRequest? = null

        override fun launch(request: DshTrialLaunchRequest): DshTrialLaunchResult {
            calls.add("trial.launch")
            lastRequest = request
            launches += 1
            if (launchResult is DshTrialLaunchResult.Started) running = true
            return launchResult
        }

        override fun probeHealth(): DshHostHealth {
            calls.add("trial.health")
            return health
        }

        override fun stopAndProve(timeoutMillis: Long): DshTrialStopProof {
            calls.add("trial.stop")
            if (stopProof is DshTrialStopProof.Stopped) running = false
            return stopProof
        }

        override fun status(): DshTrialRuntimeStatus {
            calls.add("trial.status")
            return DshTrialRuntimeStatus(running, pid = 4242L)
        }
    }

    private fun candidate(root: File, id: String = "candidate-1"): File =
        File(root, "closure/incoming/$id").apply {
            mkdirs()
            File(this, "payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            File(this, "candidate.json").writeText("""{"candidateId":"$id"}""", Charsets.UTF_8)
        }

    @Test
    fun trialIsRefusedWhileTheProductionHostRuns() {
        val root = newRoot()
        val production = FakeProduction().apply { state = DshHostState.RUNNING }
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)

        val status = host.startTrial(candidate(root), "a".repeat(64))

        assertEquals(DshHostState.FAILED, status.state)
        assertEquals(0, trial.launches)
        assertNull(host.activeTrial())
    }

    @Test
    fun aDeadCandidateProcessProvesTheInterruptedTrialStopped() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply {
            stopProof = DshTrialStopProof.Unverified("this process owns no trial generation")
        }
        val host = DshClosureTrialHost(
            production,
            { trial },
            root,
            candidateProcessAlive = { false }
        )

        assertEquals(
            DshHostState.RUNNING,
            host.startTrial(candidate(root), "a".repeat(64)).state
        )
        val stopped = host.stopTrial(5_000L)

        assertEquals(DshHostState.STOPPED, stopped.state)
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)
    }

    @Test
    fun aLiveCandidateProcessKeepsTheTrialInRecoveryRequired() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply {
            stopProof = DshTrialStopProof.Unverified("this process owns no trial generation")
        }
        val host = DshClosureTrialHost(
            production,
            { trial },
            root,
            candidateProcessAlive = { true }
        )

        assertEquals(
            DshHostState.RUNNING,
            host.startTrial(candidate(root), "a".repeat(64)).state
        )
        val refused = host.stopTrial(5_000L)

        assertEquals(DshHostState.FAILED, refused.state)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
    }

    @Test
    fun aStoppedJournalAndADeadCandidateProveTheOldTrialIsGone() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root, candidateProcessAlive = { false })
        host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.STOPPED, host.stopTrial(5_000L).state)

        // A restarted app never launched that generation, so the port reports "unknown"; the
        // journal plus a dead candidate root must still count as a positive stop proof.
        trial.running = null
        val again = host.stopTrial(5_000L)

        assertEquals(DshHostState.STOPPED, again.state)
        assertTrue(again.detail.contains("candidate root"))
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)
    }

    @Test
    fun aStoppedJournalWithALiveCandidateProcessStaysUnknown() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root, candidateProcessAlive = { true })
        host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.STOPPED, host.stopTrial(5_000L).state)

        trial.running = null
        val again = host.stopTrial(5_000L)

        // A live process on a stopped journal's candidate contradicts the journal: never stopped.
        assertEquals(DshHostState.UNKNOWN, again.state)
    }

    @Test
    fun aStoppedJournalWithADeadCandidateLeavesTheProductionStatusAnswerable() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root, candidateProcessAlive = { false })
        host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.STOPPED, host.stopTrial(5_000L).state)

        // A restarted app cannot see the trial generation it never launched; the journal plus a
        // dead candidate root must not mask a production host that really is serving.
        trial.running = null
        production.state = DshHostState.RUNNING

        assertEquals(DshHostState.RUNNING, host.status().state)
    }

    @Test
    fun aServingProductionAnswersEvenWhenTheCandidateRootLooksAlive() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root, candidateProcessAlive = { true })
        host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.STOPPED, host.stopTrial(5_000L).state)

        // After the selector switch the production host itself runs from the candidate root, so
        // the crash-proof scan is inconclusive; a serving production is the answer.
        trial.running = null
        production.state = DshHostState.RUNNING

        assertEquals(DshHostState.RUNNING, host.status().state)
    }

    @Test
    fun aStoppedJournalWithALiveCandidateAndNoServerKeepsTheStatusUnknown() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root, candidateProcessAlive = { true })
        host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.STOPPED, host.stopTrial(5_000L).state)

        trial.running = null

        assertEquals(DshHostState.UNKNOWN, host.status().state)
    }

    @Test
    fun trialAndProductionRunStrictlySerially() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)

        val started = host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.RUNNING, started.state)
        assertEquals(1, trial.launches)
        assertNotNull(trial.lastRequest)
        assertEquals(File(root, "trial/home"), trial.lastRequest!!.homeRoot)
        assertEquals(DshTrialPhase.RUNNING, host.activeTrial()?.phase)

        val health = host.probeTrialHealth()
        assertTrue(health.hostReady)
        assertTrue(health.homeReadable)
        assertTrue(health.sessionReaderReady)
        // Isolation is explicit: a trial never reports production auth/provider state.
        assertFalse(health.authReady)
        assertFalse(health.providerConfigured)

        val stopped = host.stopTrial(5_000L)
        assertEquals(DshHostState.STOPPED, stopped.state)
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)

        val productionStatus = host.startProduction(File(root, "closure/incoming/candidate-1"), "b".repeat(64))
        assertEquals(DshHostState.RUNNING, productionStatus.state)
        assertEquals(listOf("production.start"), production.calls)
        assertTrue("trial.stop" in trial.calls)
    }

    @Test
    fun aSurvivingTrialProcessBlocksProductionAndNeedsRecovery() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply { stopProof = DshTrialStopProof.Survived(listOf(4242)) }
        val host = DshClosureTrialHost(production, { trial }, root)
        host.startTrial(candidate(root), "a".repeat(64))

        val stopped = host.stopTrial(5_000L)
        assertEquals(DshHostState.FAILED, stopped.state)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)

        val productionStatus = host.startProduction(File(root, "closure/incoming/candidate-1"), "b".repeat(64))
        assertEquals(DshHostState.FAILED, productionStatus.state)
        assertTrue(production.calls.none { it == "production.start" })
        assertEquals(DshHostState.FAILED, host.status().state)
    }

    @Test
    fun anUnconnectedLauncherNeverClaimsATrial() {
        val root = newRoot()
        val production = FakeProduction()
        val host = DshClosureTrialHost(production, { null }, root)

        val started = host.startTrial(candidate(root), "a".repeat(64))
        assertEquals(DshHostState.FAILED, started.state)
        assertTrue(started.detail.contains("launcher"))
        assertEquals(DshHostState.STOPPED, host.status().state)
        // No journal entry was ever made "running", so stopping is a positive statement.
        val stopped = host.stopTrial(1_000L)
        assertEquals(DshHostState.STOPPED, stopped.state)
        assertTrue(host.probeTrialHealth().healthy.not())
    }

    @Test
    fun anUnverifiedStopFailsClosed() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply { stopProof = DshTrialStopProof.Unverified("orphan scan unavailable") }
        val host = DshClosureTrialHost(production, { trial }, root)
        host.startTrial(candidate(root), "a".repeat(64))

        val stopped = host.stopTrial(5_000L)
        assertEquals(DshHostState.FAILED, stopped.state)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
        assertFalse(host.status().state == DshHostState.STOPPED)
    }

    @Test
    fun startupRecoveryReleasesAnInterruptedTrialOnlyWithAStopProof() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)
        host.startTrial(candidate(root), "a".repeat(64))
        // Simulate a crash: the journal still says RUNNING and no stop was recorded.
        assertEquals(DshTrialPhase.RUNNING, host.activeTrial()?.phase)

        assertTrue(host.recoverInterruptedTrial() is DshTrialRecovery.Recovered)
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)
    }

    @Test
    fun anInvalidCandidateStagingTreeIsRefusedBeforeAnyLaunch() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)
        val empty = File(root, "closure/incoming/candidate-1").apply { mkdirs() }

        val missingManifest = host.startTrial(empty, "a".repeat(64))

        assertEquals(DshHostState.FAILED, missingManifest.state)
        assertEquals(0, trial.launches)
        assertNull(host.activeTrial())

        // A manifest whose id does not match its directory is not a candidate either.
        TestSupport.write(File(empty, "candidate.json"), """{"candidateId":"candidate-2"}""")
        val mismatched = host.startTrial(empty, "a".repeat(64))
        assertEquals(DshHostState.FAILED, mismatched.state)
        assertEquals(0, trial.launches)
        assertNull(host.activeTrial())
    }

    @Test
    fun aCandidateOutsideTheManagedClosureRootIsRefused() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)
        val elsewhere = File(root, "elsewhere/candidate-1").apply {
            mkdirs()
            TestSupport.write(File(this, "candidate.json"), """{"candidateId":"candidate-1"}""")
        }

        val status = host.startTrial(elsewhere, "a".repeat(64))

        assertEquals(DshHostState.FAILED, status.state)
        assertEquals(0, trial.launches)
        assertNull(host.activeTrial())
    }

    @Test
    fun productionIsRefusedWhileTheTrialReleaseIsUnproven() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply { stopProof = DshTrialStopProof.Unverified("orphan scan unavailable") }
        val host = DshClosureTrialHost(production, { trial }, root)
        host.startTrial(candidate(root), "a".repeat(64))

        val productionStatus = host.startProduction(File(root, "closure/incoming/candidate-1"), "b".repeat(64))

        assertEquals(DshHostState.FAILED, productionStatus.state)
        assertTrue(production.calls.isEmpty())
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
    }

    @Test
    fun anUnverifiedStopKeepsRecoveryRequiredAcrossRepeatedStopsAndBlocksRetrial() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply { stopProof = DshTrialStopProof.Unverified("scan unavailable") }
        val host = DshClosureTrialHost(production, { trial }, root)
        host.startTrial(candidate(root), "a".repeat(64))

        assertEquals(DshHostState.FAILED, host.stopTrial(1_000L).state)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
        // A second stop keeps failing closed rather than clearing the evidence.
        assertEquals(DshHostState.FAILED, host.stopTrial(1_000L).state)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)

        // A new trial cannot start over an unrecovered generation.
        val retrial = host.startTrial(candidate(root, id = "candidate-2"), "c".repeat(64))
        assertEquals(DshHostState.FAILED, retrial.state)
        assertEquals(1, trial.launches)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
    }

    @Test
    fun anInterruptedLaunchingJournalIsReleasedOnlyWithAStopProof() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)
        // Simulate a crash between journaling LAUNCHING and the launcher answering.
        DshTrialActiveStore(File(root, "trial/active.json")).write(
            DshTrialActiveRecord(
                phase = DshTrialPhase.LAUNCHING,
                candidateId = "candidate-1",
                candidateFingerprint = "a".repeat(64),
                candidateRoot = File(root, "closure/incoming/candidate-1").absolutePath,
                homeRoot = File(root, "trial/home").absolutePath,
                workspaceRoot = File(root, "trial/workspace").absolutePath,
                tmpRoot = File(root, "trial/tmp").absolutePath,
                startedAtMillis = 10L,
                updatedAtMillis = 20L
            )
        )

        val recovery = host.recoverInterruptedTrial()

        assertTrue(recovery is DshTrialRecovery.Recovered)
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)
        assertTrue("trial.stop" in trial.calls)
    }

    @Test
    fun anUnreadableTrialJournalFailsClosedAndIsNeverTreatedAsNoTrial() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial()
        val host = DshClosureTrialHost(production, { trial }, root)
        File(root, "trial").mkdirs()
        TestSupport.write(File(root, "trial/active.json"), "{this is not json")

        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
        assertTrue(host.recoverInterruptedTrial() is DshTrialRecovery.Recovered)
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)
        assertTrue("trial.stop" in trial.calls)
    }

    @Test
    fun startupRecoveryKeepsAttentionWhenTheTrialCannotBeReleased() {
        val root = newRoot()
        val production = FakeProduction()
        val trial = FakeTrial().apply { stopProof = DshTrialStopProof.Survived(listOf(77)) }
        val host = DshClosureTrialHost(production, { trial }, root)
        host.startTrial(candidate(root), "a".repeat(64))

        val recovery = host.recoverInterruptedTrial()

        assertTrue(recovery is DshTrialRecovery.RequiresAttention)
        assertEquals(DshTrialPhase.RECOVERY_REQUIRED, host.activeTrial()?.phase)
    }
}
