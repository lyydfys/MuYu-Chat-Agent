package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult
import com.muyuchat.mca.agent.data.AgentDispatchOutboxDao
import com.muyuchat.mca.agent.data.AgentDispatchOutboxEntity
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.data.RoomAgentDispatchOutboxStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyAgentDispatchOutboxImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aLegacyPendingRowIsImportedOnceAndStaysDurable() = runTest {
        val legacy = FileAgentDispatchOutboxStore(folder.newFolder("legacy"), clock = { 1_000L })
        val entry = entry("inv-legacy")
        legacy.enqueue(entry)
        val dao = FakeOutboxDao()
        val room = roomStore(dao)
        val importer = LegacyAgentDispatchOutboxImporter(legacy, room)

        assertEquals(1, importer.import())
        val imported = requireNotNull(room.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.PENDING, imported.status)
        assertEquals(0L, imported.revision)
        assertEquals("inv-legacy", imported.invocationId)

        assertEquals("a second import must be a no-op", 0, importer.import())
        assertEquals("the legacy document is retained as evidence", 1, legacy.listActive(10).size)
    }

    @Test
    fun aLegacyDispatchingRowKeepsItsStatusAndLeaseForTheReconciler() = runTest {
        val legacy = FileAgentDispatchOutboxStore(folder.newFolder("legacy"), clock = { 1_000L })
        val entry = entry("inv-legacy")
        legacy.enqueue(entry)
        val claimed = legacy.claim(entry.idempotencyKey, "dispatcher-a", 1_000L, 100L)
        val lease = (claimed as AgentDispatchClaimResult.Granted).entry
        assertTrue(
            legacy.markDispatching(entry.idempotencyKey, lease.revision, "dispatcher-a", 1_010L)
                is AgentDispatchUpdateResult.Updated
        )
        val dao = FakeOutboxDao()
        val room = roomStore(dao)

        assertEquals(1, LegacyAgentDispatchOutboxImporter(legacy, room).import())

        val imported = requireNotNull(room.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.DISPATCHING, imported.status)
        assertEquals("dispatcher-a", imported.leaseOwner)
        assertEquals(lease.revision + 1L, imported.revision)
        assertTrue(
            "a DISPATCHING import must not be claimable",
            room.claim(entry.idempotencyKey, "dispatcher-b", 1_020L, 100L)
                is AgentDispatchClaimResult.NotAvailable
        )
    }

    @Test
    fun aCorruptLegacyDocumentBlocksNothingAndImportsNothing() = runTest {
        val root = folder.newFolder("legacy")
        val file = File(root, "dispatch-outbox/outbox.json").apply {
            parentFile.mkdirs()
            writeText("{ not json", Charsets.UTF_8)
        }
        val legacy = FileAgentDispatchOutboxStore(root, clock = { 1_000L })
        val dao = FakeOutboxDao()
        val room = roomStore(dao)

        assertEquals(0, LegacyAgentDispatchOutboxImporter(legacy, room).import())
        assertTrue(dao.rows.isEmpty())
        assertTrue("the corrupt legacy evidence is not overwritten", file.isFile)
    }

    private fun roomStore(dao: FakeOutboxDao): RoomAgentDispatchOutboxStore =
        RoomAgentDispatchOutboxStore(
            dao = dao,
            transactions = object : AgentTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            clock = { 1_000L }
        )

    private fun entry(invocationId: String): AgentDispatchOutboxEntry =
        AgentDispatchOutboxEntry(
            idempotencyKey = AgentDispatchOutboxEntry.keyFor(invocationId),
            invocationId = invocationId,
            runId = "run-1",
            stepId = "step-1",
            callOrdinal = 0,
            toolKey = "artifact.write_text",
            toolVersion = "1",
            approvedAtMillis = 1_000L
        )

    private class FakeOutboxDao : AgentDispatchOutboxDao {
        val rows = LinkedHashMap<String, AgentDispatchOutboxEntity>()

        override suspend fun find(idempotencyKey: String): AgentDispatchOutboxEntity? =
            rows[idempotencyKey]

        override suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntity> =
            rows.values
                .filter { it.status !in TERMINAL }
                .sortedWith(compareBy({ it.updatedAtMillis }, { it.idempotencyKey }))
                .take(limit)

        override suspend fun insert(entity: AgentDispatchOutboxEntity): Long {
            check(!rows.containsKey(entity.idempotencyKey))
            rows[entity.idempotencyKey] = entity
            return 1L
        }

        override suspend fun updateCas(
            idempotencyKey: String,
            expectedRevision: Long,
            status: String,
            leaseOwner: String?,
            leaseExpiresAtMillis: Long,
            dispatchAttempts: Int,
            settledInvocationStatus: String?,
            errorCode: String?,
            updatedAtMillis: Long,
            newRevision: Long
        ): Int {
            val current = rows[idempotencyKey] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows[idempotencyKey] = current.copy(
                status = status,
                leaseOwner = leaseOwner,
                leaseExpiresAtMillis = leaseExpiresAtMillis,
                dispatchAttempts = dispatchAttempts,
                settledInvocationStatus = settledInvocationStatus,
                errorCode = errorCode,
                updatedAtMillis = updatedAtMillis,
                revision = newRevision
            )
            return 1
        }

        override suspend fun pruneTerminal(cutoffMillis: Long): Int = 0

        private companion object {
            val TERMINAL = setOf("SETTLED", "EXPIRED", "FAILED", "NEEDS_REVIEW", "BYPASSED")
        }
    }
}
