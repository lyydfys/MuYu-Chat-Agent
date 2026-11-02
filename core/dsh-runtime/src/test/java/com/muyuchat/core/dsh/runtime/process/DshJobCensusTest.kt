package com.muyuchat.core.dsh.runtime.process

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census policy: only descendants of the READY host that carry this generation's token are
 * recorded, and the census itself never sends a signal.
 */
class DshJobCensusTest {

    private fun newDir(): File = Files.createTempDirectory("dsh-job-census").toFile()

    private fun processes(): FakeJobProcesses = FakeJobProcesses().apply {
        // The Node host itself is not a descendant of itself, so it must never be recorded.
        add(pid = 5_620, ppid = 1, startTime = 10L, cmdline = "node bin.js web", generationToken = "13")
    }

    private fun census(
        processes: FakeJobProcesses,
        ledger: DshJobLedger,
        generation: Long = 13L,
        hostPid: Int = 5_620
    ): DshJobCensus = DshJobCensus(
        source = processes,
        ledger = ledger,
        generation = generation,
        hostPid = hostPid,
        clock = { 1_000L },
        log = {}
    )

    @Test
    fun recordsOnlyDescendantsCarryingThisGenerationToken() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)
        val processes = processes()
        processes.add(7164, ppid = 5_620, startTime = 100L, cmdline = "sh -c sleep 300", generationToken = "13")
        processes.add(7165, ppid = 7164, startTime = 101L, cmdline = "sleep 300", generationToken = "13")
        processes.add(7166, ppid = 5_620, startTime = 102L, cmdline = "git status", generationToken = null)
        processes.add(7167, ppid = 5_620, startTime = 103L, cmdline = "old job", generationToken = "12")
        processes.add(8_000, ppid = 1, startTime = 104L, cmdline = "unrelated", generationToken = "13")

        census(processes, ledger).pollOnce()

        assertEquals(setOf(7164, 7165), ledger.records().map { it.pid }.toSet())
        assertEquals(DshJobStatus.OBSERVED, ledger.records().first().status)
        assertTrue("the census must never signal", processes.signals.isEmpty())
    }

    @Test
    fun aJobThatIsProvablyGoneStopsBeingObserved() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)
        val processes = processes()
        val job = processes.add(
            7164, ppid = 5_620, startTime = 100L, cmdline = "sh -c sleep 300", generationToken = "13"
        )
        val census = census(processes, ledger)
        census.pollOnce()
        assertEquals(1, ledger.records().count { it.status == DshJobStatus.OBSERVED })

        job.alive = false
        census.pollOnce()

        val record = ledger.records().single()
        assertEquals(DshJobStatus.EXITED, record.status)
        assertTrue(processes.signals.isEmpty())
    }

    @Test
    fun aLiveReparentedProcessIsNotMarkedExited() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)
        val processes = processes()
        val job = processes.add(
            7164, ppid = 5_620, startTime = 100L, cmdline = "sh -c sleep 300", generationToken = "13"
        )
        val census = census(processes, ledger)
        census.pollOnce()

        // The shell exits but its child is reparented and still alive: the census cannot see it
        // as a descendant any more, but it must not claim the process ended.
        job.ppid = 1
        census.pollOnce()

        assertEquals(DshJobStatus.OBSERVED, ledger.records().single().status)
        assertTrue(processes.signals.isEmpty())
    }
}
