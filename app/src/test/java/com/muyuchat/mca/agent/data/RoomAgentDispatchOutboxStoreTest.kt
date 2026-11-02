package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchEnqueueResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAgentDispatchOutboxStoreTest {

    private val runner = DepthTrackingRunner()
    private val dao = FakeOutboxDao(onInsert = { runner.depthAtInsert = runner.depth })
    private val store = RoomAgentDispatchOutboxStore(
        dao = dao,
        transactions = runner,
        clock = { 1_000L }
    )

    @Test
    fun enqueueIsIdempotentAndRejectsAnotherInvocation() = runTest {
        val entry = entry("inv-1")
        assertTrue(store.enqueue(entry) is AgentDispatchEnqueueResult.Recorded)
        val again = store.enqueue(entry)
        assertTrue(again is AgentDispatchEnqueueResult.Recorded)
        assertEquals(0L, (again as AgentDispatchEnqueueResult.Recorded).entry.revision)

        val conflict = store.enqueue(entry.copy(invocationId = "inv-2"))
        assertTrue(conflict is AgentDispatchEnqueueResult.Rejected)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun claimGrantsOneLiveLeaseAndCanReclaimAfterExpiry() = runTest {
        val entry = entry("inv-1")
        store.enqueue(entry)

        val first = store.claim(entry.idempotencyKey, "dispatcher-a", nowMillis = 1_000L, leaseMillis = 100L)
        assertTrue(first is AgentDispatchClaimResult.Granted)
        assertEquals(1, (first as AgentDispatchClaimResult.Granted).entry.dispatchAttempts)

        val live = store.claim(entry.idempotencyKey, "dispatcher-b", 1_050L, 100L)
        assertTrue(live is AgentDispatchClaimResult.NotAvailable)

        val expired = store.claim(entry.idempotencyKey, "dispatcher-b", 1_200L, 100L)
        assertTrue(expired is AgentDispatchClaimResult.Granted)
        assertEquals(2, (expired as AgentDispatchClaimResult.Granted).entry.dispatchAttempts)
    }

    @Test
    fun dispatchingIsNeverClaimableAndRejectsStaleRevision() = runTest {
        val entry = entry("inv-1")
        store.enqueue(entry)
        val claimed = store.claim(entry.idempotencyKey, "dispatcher-a", 1_000L, 100L)
        val claim = (claimed as AgentDispatchClaimResult.Granted).entry

        val stale = store.markDispatching(entry.idempotencyKey, claim.revision + 99, "dispatcher-a", 1_010L)
        assertTrue(stale is AgentDispatchUpdateResult.Rejected)

        val dispatching = store.markDispatching(entry.idempotencyKey, claim.revision, "dispatcher-a", 1_010L)
        assertTrue(dispatching is AgentDispatchUpdateResult.Updated)
        val second = store.claim(entry.idempotencyKey, "dispatcher-b", 1_020L, 100L)
        assertTrue(second is AgentDispatchClaimResult.NotAvailable)
    }

    @Test
    fun terminalSettlementIsFinalAndPrunedFromActiveList() = runTest {
        val entry = entry("inv-1")
        store.enqueue(entry)
        val claimed = (store.claim(entry.idempotencyKey, "dispatcher-a", 1_000L, 100L)
            as AgentDispatchClaimResult.Granted).entry

        val settled = store.settle(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.revision,
            status = AgentDispatchOutboxStatus.SETTLED,
            invocationStatus = ToolInvocationStatus.SUCCEEDED,
            errorCode = null,
            nowMillis = 1_020L
        )
        assertTrue(settled is AgentDispatchUpdateResult.Updated)
        val settledEntry = (settled as AgentDispatchUpdateResult.Updated).entry
        assertEquals(ToolInvocationStatus.SUCCEEDED, settledEntry.settledInvocationStatus)

        val again = store.settle(
            entry.idempotencyKey,
            settledEntry.revision,
            AgentDispatchOutboxStatus.EXPIRED,
            null,
            "WF_TEST",
            1_030L
        )
        assertTrue(again is AgentDispatchUpdateResult.Rejected)
        assertTrue(store.listActive(10).isEmpty())
    }

    @Test
    fun enqueueJoinsTheCallersTransactionBoundary() = runTest {
        val entry = entry("inv-1")
        runner.inTransaction { store.enqueue(entry) }
        assertEquals(
            "the store must open (and join) a transaction, not write outside the caller's",
            2,
            runner.depthAtInsert
        )
        assertEquals(0, runner.depth)
    }

    @Test
    fun importLegacyPreservesStatusAndDoesNotOverwrite() = runTest {
        val legacy = entry("inv-legacy").copy(
            status = AgentDispatchOutboxStatus.DISPATCHING,
            leaseOwner = "legacy-dispatcher",
            leaseExpiresAtMillis = 2_000L,
            dispatchAttempts = 2,
            revision = 5L
        )
        assertTrue(store.importLegacy(legacy))
        assertTrue("a second import must not overwrite", !store.importLegacy(legacy))
        val loaded = store.read(legacy.idempotencyKey)!!
        assertEquals(AgentDispatchOutboxStatus.DISPATCHING, loaded.status)
        assertEquals(5L, loaded.revision)
        assertEquals("legacy-dispatcher", loaded.leaseOwner)
    }

    @Test
    fun anEnqueueInARolledBackTransactionLeavesNoRow() = runTest {
        val rollbackDao = FakeOutboxDao()
        val rollbackRunner = object : AgentTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                val snapshot = LinkedHashMap(rollbackDao.rows)
                return try {
                    block()
                } catch (failure: Throwable) {
                    rollbackDao.rows.clear()
                    rollbackDao.rows.putAll(snapshot)
                    throw failure
                }
            }
        }
        val rollbackStore = RoomAgentDispatchOutboxStore(
            dao = rollbackDao,
            transactions = rollbackRunner,
            clock = { 1_000L }
        )
        val entry = entry("inv-rollback")
        var thrown = false
        try {
            rollbackRunner.inTransaction {
                rollbackStore.enqueue(entry)
                throw IllegalStateException("the approval rolled back")
            }
        } catch (_: IllegalStateException) {
            thrown = true
        }

        assertTrue(thrown)
        assertTrue("the outbox row must roll back with the approval", rollbackDao.rows.isEmpty())
        assertTrue(rollbackStore.read(entry.idempotencyKey) == null)
    }

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

    private class DepthTrackingRunner : AgentTransactionRunner {
        var depth: Int = 0
        var depthAtInsert: Int = -1

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            depth += 1
            return try {
                block()
            } finally {
                depth -= 1
            }
        }
    }

    private class FakeOutboxDao(
        private val onInsert: () -> Unit = {}
    ) : AgentDispatchOutboxDao {
        val rows = LinkedHashMap<String, AgentDispatchOutboxEntity>()

        override suspend fun find(idempotencyKey: String): AgentDispatchOutboxEntity? =
            rows[idempotencyKey]

        override suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntity> =
            rows.values
                .filter { it.status !in TERMINAL }
                .sortedWith(compareBy({ it.updatedAtMillis }, { it.idempotencyKey }))
                .take(limit)

        override suspend fun insert(entity: AgentDispatchOutboxEntity): Long {
            check(!rows.containsKey(entity.idempotencyKey)) { "duplicate outbox key" }
            onInsert()
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

        override suspend fun pruneTerminal(cutoffMillis: Long): Int {
            val before = rows.size
            rows.entries.removeIf { (_, row) ->
                row.status in TERMINAL && row.updatedAtMillis < cutoffMillis
            }
            return before - rows.size
        }

        private companion object {
            val TERMINAL = setOf("SETTLED", "EXPIRED", "FAILED", "NEEDS_REVIEW", "BYPASSED")
        }
    }
}
