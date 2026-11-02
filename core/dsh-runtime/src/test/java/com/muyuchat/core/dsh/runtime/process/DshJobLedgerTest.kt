package com.muyuchat.core.dsh.runtime.process

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The job ledger is the only durable trace of a process that outlived its Node host. These tests
 * cover the guarantees recovery relies on: one bounded file per generation, idempotent sightings,
 * folded status updates and a read model that never invents a resolved record.
 */
class DshJobLedgerTest {

    private fun newDir(): File = Files.createTempDirectory("dsh-job-ledger").toFile()

    private fun observe(
        ledger: DshJobLedger,
        generation: Long = 1L,
        pid: Int = 61,
        startTime: Long = 7_000L,
        hash: String = "a".repeat(64),
        command: String? = "sh -c sleep 300",
        nowMs: Long = 1_000L
    ): Boolean = ledger.observe(
        taskId = DshJobIdentity.taskId(generation, pid, startTime),
        generation = generation,
        hostPid = 50,
        pid = pid,
        startTime = startTime,
        cmdlineHash = hash,
        command = command,
        nowMs = nowMs
    )

    @Test
    fun oneFilePerGenerationIsTheRotationUnit() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)

        assertTrue(observe(ledger, generation = 12L, pid = 61, startTime = 100L))
        assertTrue(observe(ledger, generation = 13L, pid = 62, startTime = 200L))

        assertEquals(setOf("jobs-12.jsonl", "jobs-13.jsonl"), dir.list()!!.toSet())
        assertEquals(setOf(12L, 13L), ledger.records().map { it.generation }.toSet())
    }

    @Test
    fun repeatedObservationIsIdempotentAndKeepsTheFirstSighting() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)

        assertTrue(observe(ledger, nowMs = 1_000L))
        assertTrue(observe(ledger, nowMs = 2_000L))
        assertEquals(
            "an unchanged sighting inside the refresh window must not append",
            1,
            File(dir, "jobs-1.jsonl").readLines().size
        )

        assertTrue(observe(ledger, nowMs = 31_000L))
        assertEquals(2, File(dir, "jobs-1.jsonl").readLines().size)
        val record = ledger.records().single()
        assertEquals(1_000L, record.firstSeenMs)
        assertEquals(31_000L, record.lastSeenMs)
        assertEquals(DshJobStatus.OBSERVED, record.status)
    }

    @Test
    fun aStatusChangeIsAppendedAndFoldedByTaskId() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)
        observe(ledger)
        val taskId = DshJobIdentity.taskId(1L, 61, 7_000L)

        assertTrue(ledger.mark(taskId, DshJobStatus.REAPED, 5_000L))
        assertTrue("a repeated status is a no-op", ledger.mark(taskId, DshJobStatus.REAPED, 6_000L))
        assertFalse(ledger.mark("mca-job-1-999-1", DshJobStatus.REAPED, 6_000L))

        val reopened = DshJobLedger(dir)
        val record = reopened.records().single()
        assertEquals(DshJobStatus.REAPED, record.status)
        assertEquals(1_000L, record.firstSeenMs)
        assertEquals(5_000L, record.lastSeenMs)
    }

    @Test
    fun orphansExposeOnlyUnresolvedProcessesAndStayBounded() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)
        observe(ledger, pid = 61, startTime = 100L)
        observe(ledger, pid = 62, startTime = 200L)
        observe(ledger, pid = 63, startTime = 300L, nowMs = 2_000L)
        ledger.mark(DshJobIdentity.taskId(1L, 62, 200L), DshJobStatus.SKIPPED, 3_000L)

        val orphans = ledger.orphans(limit = 10)
        assertEquals(2, orphans.size)
        assertEquals(
            listOf(
                DshJobIdentity.taskId(1L, 63, 300L),
                DshJobIdentity.taskId(1L, 61, 100L)
            ),
            orphans.map { it.taskId }
        )
        assertEquals(1, ledger.orphans(limit = 1).size)
        assertEquals("sh -c sleep 300", orphans.first().command)
    }

    @Test
    fun aCompactedFileStillHoldsEveryLatestRecord() {
        val dir = newDir()
        val ledger = DshJobLedger(dir, maxLinesPerFile = 3)
        for (offset in 0..3) {
            assertTrue(observe(ledger, pid = 70 + offset, startTime = 1_000L + offset))
        }

        val lines = File(dir, "jobs-1.jsonl").readLines()
        assertTrue("compaction bounds the file to one line per task", lines.size <= 4)
        assertEquals(4, DshJobLedger(dir).records().size)
    }

    @Test
    fun resolvedGenerationFilesBeyondTheBoundArePruned() {
        val dir = newDir()
        val ledger = DshJobLedger(dir, maxGenerationFiles = 2)
        observe(ledger, generation = 1L, pid = 61, startTime = 100L)
        observe(ledger, generation = 2L, pid = 62, startTime = 200L)
        observe(ledger, generation = 3L, pid = 63, startTime = 300L)
        ledger.mark(DshJobIdentity.taskId(1L, 61, 100L), DshJobStatus.REAPED, 4_000L)
        ledger.mark(DshJobIdentity.taskId(2L, 62, 200L), DshJobStatus.REAPED, 4_000L)

        val reopened = DshJobLedger(dir, maxGenerationFiles = 2)
        assertEquals(3, reopened.records().size)
        assertFalse("the oldest resolved generation file is pruned", File(dir, "jobs-1.jsonl").exists())
        assertTrue(File(dir, "jobs-2.jsonl").exists())
        assertTrue(File(dir, "jobs-3.jsonl").exists())
    }

    @Test
    fun malformedLinesAreIgnoredAndNeverBecomeRecords() {
        val dir = newDir()
        val ledger = DshJobLedger(dir)
        observe(ledger)
        File(dir, "jobs-1.jsonl").appendText("{ not json\n")

        val reopened = DshJobLedger(dir)
        assertEquals(1, reopened.records().size)
        assertEquals(DshJobStatus.OBSERVED, reopened.records().single().status)
    }
}
