package com.muyuchat.core.dsh.runtime.process

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery policy: a prior-generation job may be signalled only while its generation token, its
 * kernel start time and its command-line hash all still match. Any mismatch is a skip, never a
 * kill by PID.
 */
class DshJobRecoveryTest {

    private class Fixture(generation: Long = 13L) {
        val ledger = DshJobLedger(Files.createTempDirectory("dsh-job-recovery").toFile())
        val processes = FakeJobProcesses()
        val job = processes.add(
            pid = 7164,
            ppid = 1,
            startTime = 9_000L,
            cmdline = "sh -c sleep 300; echo done",
            generationToken = generation.toString()
        )
        val taskId = DshJobIdentity.taskId(generation, job.pid, job.startTime)

        init {
            ledger.observe(
                taskId = taskId,
                generation = generation,
                hostPid = 5_620,
                pid = job.pid,
                startTime = job.startTime,
                cmdlineHash = DshJobIdentity.cmdlineHash(processes.cmdline(job.pid)!!),
                command = "sh -c sleep 300; echo done",
                nowMs = 1_000L
            )
        }
    }

    private fun recovery(
        fixture: Fixture,
        source: DshJobProcessSource = fixture.processes,
        clock: () -> Long
    ): DshJobRecovery = DshJobRecovery(
        source = source,
        ledger = fixture.ledger,
        graceMillis = 2_000L,
        killMillis = 1_000L,
        pollMillis = 100L,
        clock = clock,
        log = {}
    )

    @Test
    fun aFullyMatchingOrphanIsReaped() = runTest {
        val fixture = Fixture()

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(fixture.taskId), result.reaped)
        assertTrue(result.skipped.isEmpty())
        assertFalse(fixture.processes.isAlive(7164))
        assertEquals(listOf(DshProcessTree.SIGTERM), fixture.processes.signalsFor(7164))
        assertEquals(DshJobStatus.REAPED, fixture.ledger.records().single().status)
    }

    @Test
    fun aReusedPidWithAForeignStartTimeIsNeverSignalled() = runTest {
        val fixture = Fixture()
        // Same PID, different kernel start time: the PID was recycled by an unrelated process.
        fixture.job.startTime = 9_500L

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(fixture.taskId), result.skipped)
        assertTrue(result.reaped.isEmpty())
        assertTrue("a startTime mismatch must never signal", fixture.processes.signals.isEmpty())
        assertTrue(fixture.processes.isAlive(7164))
        assertEquals(DshJobStatus.SKIPPED, fixture.ledger.records().single().status)
    }

    @Test
    fun aProcessWithoutTheGenerationTokenIsSkipped() = runTest {
        val fixture = Fixture()
        fixture.job.generationToken = "12"

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(fixture.taskId), result.skipped)
        assertTrue(fixture.processes.signals.isEmpty())
        assertEquals(DshJobStatus.SKIPPED, fixture.ledger.records().single().status)
    }

    @Test
    fun aProcessWhoseCommandLineChangedIsSkipped() = runTest {
        val fixture = Fixture()
        fixture.job.cmdline = "sh -c something-else"

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(fixture.taskId), result.skipped)
        assertTrue(fixture.processes.signals.isEmpty())
    }

    @Test
    fun aProcessThatIsAlreadyGoneIsSkippedWithoutSignal() = runTest {
        val fixture = Fixture()
        fixture.job.alive = false

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(fixture.taskId), result.skipped)
        assertTrue(fixture.processes.signals.isEmpty())
    }

    @Test
    fun aSigtermIgnoringOrphanIsEscalatedToSigkill() = runTest {
        val fixture = Fixture()
        fixture.job.ignoresSigterm = true

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(fixture.taskId), result.reaped)
        val signals = fixture.processes.signalsFor(7164)
        assertEquals(DshProcessTree.SIGTERM, signals.first())
        assertTrue("a SIGTERM-ignoring orphan must be escalated", signals.contains(DshProcessTree.SIGKILL))
        assertFalse(fixture.processes.isAlive(7164))
    }

    @Test
    fun anUnsettledOrphanStaysObserved() = runTest {
        val fixture = Fixture()
        fixture.job.ignoresSigterm = true
        fixture.job.ignoresSigkill = true

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(listOf(7164), result.survived)
        assertTrue(result.reaped.isEmpty())
        assertTrue(fixture.processes.isAlive(7164))
        assertEquals(DshJobStatus.OBSERVED, fixture.ledger.records().single().status)
    }

    @Test
    fun theCurrentGenerationIsNeverExamined() = runTest {
        val fixture = Fixture(generation = 14L)

        val result = recovery(fixture) { testScheduler.currentTime }.recover(currentGeneration = 14L)

        assertEquals(0, result.examined)
        assertTrue(fixture.processes.signals.isEmpty())
        assertEquals(DshJobStatus.OBSERVED, fixture.ledger.records().single().status)
    }

    @Test
    fun aPidRecycledDuringTheGraceWindowIsNeverKilled() = runTest {
        val fixture = Fixture()
        fixture.job.ignoresSigterm = true
        val recycling = RecycleOnSigterm(fixture.processes, fixture.job.pid)

        val result = recovery(fixture, recycling) { testScheduler.currentTime }
            .recover(currentGeneration = 14L)

        assertEquals(listOf(7164), result.survived)
        assertTrue(result.reaped.isEmpty())
        // The original job got SIGTERM; the recycled PID must never receive SIGKILL.
        assertEquals(listOf(DshProcessTree.SIGTERM), fixture.processes.signalsFor(7164))
        assertTrue("the unrelated recycled process stays untouched", fixture.processes.isAlive(7164))
        assertEquals(DshJobStatus.OBSERVED, fixture.ledger.records().single().status)
    }

    /**
     * Simulates a job that exits during the SIGTERM grace window and whose PID is recycled by an
     * unrelated process before the escalation step. The replacement has a different start time
     * and no generation token.
     */
    private class RecycleOnSigterm(
        private val delegate: FakeJobProcesses,
        private val recycledPid: Int
    ) : DshJobProcessSource by delegate {
        private var recycled = false

        override fun signal(pid: Int, signal: Int): Boolean {
            val delivered = delegate.signal(pid, signal)
            if (!recycled && pid == recycledPid && signal == DshProcessTree.SIGTERM) {
                recycled = true
                delegate.process(recycledPid)?.alive = false
                delegate.add(
                    pid = recycledPid,
                    ppid = 1,
                    startTime = 99_000L,
                    cmdline = "/system/bin/unrelated",
                    generationToken = null
                )
            }
            return delivered
        }

        override fun signalTree(rootPid: Int, signal: Int): Int {
            var delivered = 0
            for (pid in delegate.descendantsOf(rootPid).asReversed()) {
                if (signal(pid, signal)) delivered++
            }
            if (signal(rootPid, signal)) delivered++
            return delivered
        }
    }
}
