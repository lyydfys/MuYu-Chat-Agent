package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchEnqueueResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentDispatchOutboxStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * N03 approval-to-outbox adapter: the dispatch row is written only after the durable approval and
 * shares the owner's approval transaction. A failed write must fail the approval command instead of
 * silently losing the dispatch.
 */
class AgentApprovalOutboxLedgerTest {

    private val ledger = TestDispatchLedger()
    private val store = InMemoryAgentDispatchOutboxStore()

    private fun decorated() = AgentApprovalOutboxLedger(ledger, store)

    @Test
    fun markApprovedWritesExactlyOnePendingDispatchRow() = runBlocking {
        val invocationId = ledger.seedWaiting()

        val updated = decorated().markApproved(invocationId, 2_000L)

        assertTrue(updated is AgentToolInvocationUpdate.Updated)
        assertEquals(ToolInvocationStatus.APPROVED, ledger.statusOf(invocationId))
        val entry = store.read(AgentDispatchOutboxEntry.keyFor(invocationId))!!
        assertEquals(invocationId, entry.invocationId)
        assertEquals(2_000L, entry.approvedAtMillis)
        assertEquals(AgentDispatchOutboxStatus.PENDING, entry.status)

        // A replayed approval (same command retried) must not create a second dispatch row.
        assertTrue(decorated().markApproved(invocationId, 3_000L) is AgentToolInvocationUpdate.Updated)
        assertEquals(1, store.listActive(10).size)
    }

    @Test
    fun aRejectedApprovalNeverWritesADispatchRow() = runBlocking {
        val invocationId = ledger.seedWaiting()
        ledger.markTerminal(
            invocationId = invocationId,
            status = ToolInvocationStatus.DENIED,
            possibleSideEffects = false,
            resultRef = null,
            atMillis = 2_000L
        )

        val update = decorated().markApproved(invocationId, 3_000L)

        assertTrue(update is AgentToolInvocationUpdate.Rejected)
        assertEquals(0, store.listActive(10).size)
    }

    @Test
    fun anUnwritableOutboxFailsTheApprovalCommand() = runBlocking {
        val invocationId = ledger.seedWaiting()
        val rejecting = object : AgentDispatchOutboxStore {
            override suspend fun enqueue(entry: AgentDispatchOutboxEntry): AgentDispatchEnqueueResult =
                AgentDispatchEnqueueResult.Rejected(
                    AgentErrorCodes.STORAGE_CORRUPTION,
                    "the outbox is unreadable"
                )

            override suspend fun read(idempotencyKey: String): AgentDispatchOutboxEntry? = null
            override suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntry> = emptyList()
            override suspend fun claim(
                idempotencyKey: String,
                leaseOwner: String,
                nowMillis: Long,
                leaseMillis: Long
            ): AgentDispatchClaimResult = AgentDispatchClaimResult.NotFound

            override suspend fun markDispatching(
                idempotencyKey: String,
                expectedRevision: Long,
                leaseOwner: String,
                nowMillis: Long
            ): AgentDispatchUpdateResult = AgentDispatchUpdateResult.Rejected(
                AgentErrorCodes.STORAGE_CORRUPTION,
                "closed"
            )

            override suspend fun release(
                idempotencyKey: String,
                expectedRevision: Long,
                leaseOwner: String,
                nowMillis: Long
            ): AgentDispatchUpdateResult = AgentDispatchUpdateResult.Rejected(
                AgentErrorCodes.STORAGE_CORRUPTION,
                "closed"
            )

            override suspend fun settle(
                idempotencyKey: String,
                expectedRevision: Long,
                status: AgentDispatchOutboxStatus,
                invocationStatus: ToolInvocationStatus?,
                errorCode: String?,
                nowMillis: Long
            ): AgentDispatchUpdateResult = AgentDispatchUpdateResult.Rejected(
                AgentErrorCodes.STORAGE_CORRUPTION,
                "closed"
            )
        }

        try {
            AgentApprovalOutboxLedger(ledger, rejecting).markApproved(invocationId, 2_000L)
            fail("a failed outbox write must surface as a typed approval rejection")
        } catch (expected: AgentJournalRejectedException) {
            assertEquals(AgentErrorCodes.STORAGE_CORRUPTION, expected.errorCode)
        }
    }

    @Test
    fun allOtherLedgerOperationsPassThrough() = runBlocking {
        val invocationId = ledger.seedApproved().invocationId
        val passthrough = decorated()

        assertEquals(ToolInvocationStatus.APPROVED, passthrough.readInvocation(invocationId)?.status)
        val gate = passthrough.markRunning(invocationId, 2_000L)
        assertTrue(gate is AgentToolInvocationUpdate.Updated)
        assertEquals(ToolInvocationStatus.RUNNING, ledger.statusOf(invocationId))
    }

    @Test
    fun markTerminalSettlesTheMatchingOutboxRowFromTheDurableFact() = runBlocking {
        val invocationId = ledger.seedWaiting()
        val decorated = decorated()
        decorated.markApproved(invocationId, 2_000L)
        decorated.markRunning(invocationId, 3_000L)

        decorated.markTerminal(
            invocationId = invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = "result-1",
            atMillis = 4_000L
        )

        val outbox = store.read(AgentDispatchOutboxEntry.keyFor(invocationId))!!
        assertEquals(AgentDispatchOutboxStatus.SETTLED, outbox.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, outbox.settledInvocationStatus)
        assertEquals(0, store.listActive(10).size)
    }

    @Test
    fun markTerminalUnknownIsFencedInsteadOfBeingSettledAsSuccess() = runBlocking {
        val invocationId = ledger.seedWaiting()
        val decorated = decorated()
        decorated.markApproved(invocationId, 2_000L)
        decorated.markRunning(invocationId, 3_000L)

        decorated.markTerminal(
            invocationId = invocationId,
            status = ToolInvocationStatus.UNKNOWN,
            possibleSideEffects = true,
            resultRef = null,
            atMillis = 4_000L
        )

        val outbox = store.read(AgentDispatchOutboxEntry.keyFor(invocationId))!!
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("invocation_unknown", outbox.errorCode)
        assertEquals(null, outbox.settledInvocationStatus)
    }

    @Test
    fun markTerminalWithoutADispatchRowDoesNotCreateOne() = runBlocking {
        val invocationId = ledger.seedApproved().invocationId

        decorated().markTerminal(
            invocationId = invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = null,
            atMillis = 2_000L
        )

        assertEquals(0, store.listActive(10).size)
        assertTrue(store.read(AgentDispatchOutboxEntry.keyFor(invocationId)) == null)
    }
}
