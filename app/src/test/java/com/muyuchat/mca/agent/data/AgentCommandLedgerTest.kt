package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.runtime.journal.AgentCommandLedger
import com.muyuchat.core.agent.runtime.journal.AgentCommandRecord
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentCommandLedgerTest {
    @Test
    fun `record then find returns the durable command result`() {
        val ledger = newLedger()
        val record = AgentCommandRecord(
            commandId = "command-1",
            contentHash = "a".repeat(64),
            appliedRevision = 3L,
            taskRevision = 5L,
            runId = "run-1"
        )
        runBlocking {
            assertNull(ledger.findCommand("command-1"))
            ledger.recordCommand(record)
            assertEquals(record, ledger.findCommand("command-1"))
        }
    }

    @Test
    fun `recording identical content is idempotent even if revisions differ`() {
        val ledger = newLedger()
        runBlocking {
            ledger.recordCommand(
                AgentCommandRecord(
                    commandId = "command-1",
                    contentHash = "b".repeat(64),
                    appliedRevision = 1L,
                    taskRevision = 1L,
                    runId = null
                )
            )
            ledger.recordCommand(
                AgentCommandRecord(
                    commandId = "command-1",
                    contentHash = "b".repeat(64),
                    appliedRevision = 9L,
                    taskRevision = 9L,
                    runId = "run-9"
                )
            )
            val stored = ledger.findCommand("command-1")!!
            assertEquals(1L, stored.appliedRevision)
            assertEquals(1L, stored.taskRevision)
        }
    }

    @Test
    fun `recording different content is an idempotency conflict`() {
        val ledger = newLedger()
        runBlocking {
            ledger.recordCommand(
                AgentCommandRecord("command-1", "c".repeat(64), appliedRevision = 1L)
            )
            try {
                ledger.recordCommand(
                    AgentCommandRecord("command-1", "d".repeat(64), appliedRevision = 2L)
                )
                fail("expected idempotency conflict")
            } catch (error: AgentJournalRejectedException) {
                assertEquals(AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT, error.errorCode)
            }
        }
    }

    private fun newLedger(): RoomAgentCommandLedger {
        val dao = FakeCommandResultDao()
        return RoomAgentCommandLedger(
            dao = dao,
            transactions = object : AgentTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            clock = AgentTestFixtures.MutableClock()
        )
    }

    private class FakeCommandResultDao : AgentCommandResultDao {
        private val rows = linkedMapOf<String, AgentCommandResultEntity>()
        private val mutex = Mutex()

        override suspend fun find(commandId: String): AgentCommandResultEntity? =
            mutex.withLock { rows[commandId] }

        override suspend fun insert(record: AgentCommandResultEntity): Long = mutex.withLock {
            check(!rows.containsKey(record.commandId)) { "duplicate command result" }
            rows[record.commandId] = record
            return@withLock 1L
        }
    }

    @Test
    fun `run spec codec prefers the task frozen context and falls back to the latest run`() {
        val binding = AgentRecordJson.encodeBinding(AgentTestFixtures.binding())
        val policy = AgentRecordJson.encodePolicy(AgentTestFixtures.policy())
        val budget = AgentRecordJson.encodeBudget(AgentTestFixtures.budget())
        val withContext = AgentEntityMappers.toEntity(
            task = AgentTestFixtures.task(),
            displayTitle = "title",
            modelBindingJson = binding,
            policyJson = policy,
            budgetJson = budget
        )
        val spec = AgentRunSpecCodec.decode(withContext, null)
        assertEquals(AgentTestFixtures.binding(), spec!!.modelBinding)
        assertEquals(AgentTestFixtures.policy(), spec.policy)

        val withoutContext = AgentEntityMappers.toEntity(
            task = AgentTestFixtures.task(),
            displayTitle = "title",
            modelBindingJson = null,
            policyJson = null,
            budgetJson = null
        )
        assertNull(AgentRunSpecCodec.decode(withoutContext, null))
        val runEntity = AgentEntityMappers.toEntity(AgentTestFixtures.run())
        val fromRun = AgentRunSpecCodec.decode(withoutContext, runEntity)
        assertEquals(AgentTestFixtures.budget(), fromRun!!.budget)
    }

    @Test
    fun `ledger rejects malformed commands`() {
        val ledger = newLedger()
        runBlocking {
            try {
                ledger.recordCommand(
                    AgentCommandRecord("command-1", "not-a-hash", appliedRevision = 1L)
                )
                fail("expected hash validation")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message!!.contains("contentHash"))
            }
            try {
                ledger.recordCommand(
                    AgentCommandRecord("bad id!", "a".repeat(64), appliedRevision = 1L)
                )
                fail("expected id validation")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message!!.contains("commandId"))
            }
        }
    }
}
