package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup sweep must never read "unreadable journal" as "no transactions": that would start a
 * host on top of an unproven transaction state. Display/cleanup paths keep the tolerant read.
 */
class JournalStrictnessTest {

    @Test
    fun `an unreadable journal directory raises instead of reading as empty`() {
        val threw = runCatching {
            readJournalStates(
                listFiles = { null },
                directoryExists = { true },
                readOne = { null }
            )
        }.exceptionOrNull()
        assertTrue("expected an IOException, got $threw", threw is IOException)
    }

    @Test
    fun `an absent journal directory is genuinely empty`() {
        assertEquals(
            emptyList<DshJournalState>(),
            readJournalStates(listFiles = { null }, directoryExists = { false }, readOne = { null })
        )
    }

    @Test
    fun `states are read newest first`() {
        val older = DshJournalState(
            transactionId = "a", seq = 1, kind = DshMaintenanceKind.BACKUP,
            phase = DshMaintenancePhase.COMMITTED, atMillis = 10L, owner = "t", operationId = "o",
            admissionProof = "p", targetRoot = "/t", restorePoint = null, steps = emptyList(), detail = null
        )
        val newer = older.copy(transactionId = "b", atMillis = 20L)
        val states = readJournalStates(
            listFiles = { arrayOf(File("a.jsonl"), File("b.jsonl")) },
            directoryExists = { true },
            readOne = { file -> if (file.name == "a.jsonl") older else newer }
        )
        assertEquals(listOf("b", "a"), states.map { it.transactionId })
    }
}
