package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery of a trial that outlived its owner process: only a `/proc`-attributed PID may be
 * signalled, and only a settled PID plus a clean survivor scan may be reported as released.
 */
class DshTrialRecoveryTest {

    private class Harness(
        val payloadPath: String,
        val alive: MutableSet<Int>,
        val commandLines: MutableMap<Int, String>,
        var signalAccepted: Boolean = true,
        var scanResult: List<Int>? = emptyList(),
        /** When set, a delivered signal does not actually end the process (a stuck child). */
        var ignoresSignals: Boolean = false
    ) {
        val signals = mutableListOf<Pair<Int, Int>>()
    }

    private fun recovery(harness: Harness, dispatcher: kotlinx.coroutines.CoroutineDispatcher, clock: () -> Long) =
        DshTrialRecovery(
            ioDispatcher = dispatcher,
            isAlive = { it in harness.alive },
            commandLine = { harness.commandLines[it] },
            signal = { pid, signal ->
                harness.signals += pid to signal
                if (!harness.signalAccepted) {
                    false
                } else {
                    if (!harness.ignoresSignals) harness.alive.remove(pid)
                    true
                }
            },
            scan = { harness.scanResult },
            needles = listOf(harness.payloadPath),
            pollMillis = 5,
            stopGraceMillis = 50,
            stopKillMillis = 50,
            clock = clock,
            log = {}
        )

    private fun record(pid: Long) = DshTrialJournalRecord(
        phase = "RUNNING",
        candidateFingerprint = "",
        candidateRoot = "",
        homeRoot = "",
        workspaceRoot = "",
        tmpRoot = "",
        pid = pid,
        generation = 1L
    )

    @Test
    fun aSettledAttributedProcessIsReleased() = runTest {
        val harness = Harness(
            payloadPath = "/data/app/lib/libdshnode.so",
            alive = mutableSetOf(101),
            commandLines = mutableMapOf(101 to "/data/app/lib/libdshnode.so web --port 0")
        )
        val verdict = recovery(harness, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            .release(record(101L))

        assertTrue(verdict is DshTrialStopPolicy.Verdict.Stopped)
        assertEquals(listOf(101 to TRIAL_SIGTERM), harness.signals)
        assertFalse(101 in harness.alive)
    }

    @Test
    fun anUnattributedProcessIsNeverSignalled() = runTest {
        val harness = Harness(
            payloadPath = "/data/app/lib/libdshnode.so",
            alive = mutableSetOf(202),
            commandLines = mutableMapOf(202 to "/system/bin/unrelated")
        )
        val verdict = recovery(harness, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            .release(record(202L))

        assertTrue(verdict is DshTrialStopPolicy.Verdict.Unverified)
        assertTrue(harness.signals.isEmpty())
        assertTrue(202 in harness.alive)
    }

    @Test
    fun aStuckProcessIsEscalatedToSigkillAndThenReportedAsSurvived() = runTest {
        val harness = Harness(
            payloadPath = "/data/app/lib/libdshnode.so",
            alive = mutableSetOf(303),
            commandLines = mutableMapOf(303 to "/data/app/lib/libdshnode.so web --port 0"),
            scanResult = listOf(303),
            ignoresSignals = true
        )
        val verdict = recovery(harness, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            .release(record(303L))

        assertEquals(listOf(303 to TRIAL_SIGTERM, 303 to TRIAL_SIGKILL), harness.signals)
        assertTrue(verdict is DshTrialStopPolicy.Verdict.Survived)
        assertEquals(listOf(303), (verdict as DshTrialStopPolicy.Verdict.Survived).pids)
    }

    @Test
    fun anUnavailableScanIsUnverifiedEvenWhenTheProcessIsGone() = runTest {
        val harness = Harness(
            payloadPath = "/data/app/lib/libdshnode.so",
            alive = mutableSetOf(404),
            commandLines = mutableMapOf(404 to "/data/app/lib/libdshnode.so --version"),
            scanResult = null
        )
        val verdict = recovery(harness, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            .release(record(404L))

        assertTrue(verdict is DshTrialStopPolicy.Verdict.Unverified)
    }

    @Test
    fun aRefusedSignalLeavesTheProcessAliveAndIsNeverStopped() = runTest {
        val harness = Harness(
            payloadPath = "/data/app/lib/libdshnode.so",
            alive = mutableSetOf(505),
            commandLines = mutableMapOf(505 to "/data/app/lib/libdshnode.so web"),
            signalAccepted = false
        )
        val verdict = recovery(harness, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            .release(record(505L))

        // Both escalation steps were attempted; the process is still alive, so it is not released.
        assertEquals(listOf(505 to TRIAL_SIGTERM, 505 to TRIAL_SIGKILL), harness.signals)
        assertTrue(verdict !is DshTrialStopPolicy.Verdict.Stopped)
        assertTrue(505 in harness.alive)
    }

    @Test
    fun aJournaledPidWithoutAProcessIsProvedGoneByTheScan() = runTest {
        val harness = Harness(
            payloadPath = "/data/app/lib/libdshnode.so",
            alive = mutableSetOf(),
            commandLines = mutableMapOf(),
            scanResult = emptyList()
        )
        val verdict = recovery(harness, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            .release(record(606L))

        assertTrue(verdict is DshTrialStopPolicy.Verdict.Stopped)
        assertTrue(harness.signals.isEmpty())
    }

    @Test
    fun theStopPolicyNeverRoundsDoubtDownToStopped() {
        assertTrue(DshTrialStopPolicy.verdict(null, null) is DshTrialStopPolicy.Verdict.Unverified)
        assertTrue(
            DshTrialStopPolicy.verdict(null, listOf(9)) is DshTrialStopPolicy.Verdict.Survived
        )
        assertTrue(
            DshTrialStopPolicy.verdict(null, emptyList()) is DshTrialStopPolicy.Verdict.Stopped
        )
        val fenceRequired = com.muyuchat.core.dsh.runtime.process.DshStopOutcome(
            stopped = true,
            escalated = false,
            fenceRequired = true,
            detail = "fence",
            trackedPids = listOf(4),
            survivingPids = emptyList()
        )
        assertEquals(
            listOf(4),
            (DshTrialStopPolicy.verdict(fenceRequired, emptyList()) as DshTrialStopPolicy.Verdict.Survived).pids
        )
    }

    @Test
    fun theJournalIsReadOnlyAndFailsClosedWhenUnreadable() {
        val root = DshTrialTestFiles.newRoot("dsh-trial-journal")
        val journal = DshTrialJournal(File(root, "trial/active.json"))
        assertEquals(null, journal.read())

        DshTrialTestFiles.writeJournal(
            root,
            phase = "RUNNING",
            pid = 12L,
            generation = 2L,
            candidateRoot = File(root, "closure/incoming/candidate-20261021-01")
        )
        val record = journal.read()!!
        assertEquals("RUNNING", record.phase)
        assertEquals(12L, record.pid)
        assertTrue(record.mayHoldProcess)

        DshTrialTestFiles.writeJournal(
            root,
            phase = "STOPPED",
            pid = 0L,
            generation = 2L,
            candidateRoot = File(root, "closure/incoming/candidate-20261021-01")
        )
        assertFalse(journal.read()!!.mayHoldProcess)

        File(root, "trial/active.json").writeText("{ not json")
        val unreadable = journal.read()!!
        assertTrue(unreadable.unreadable)
        assertTrue(unreadable.mayHoldProcess)
    }
}
