package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatch-outbox store contract (N03/N19).
 *
 * These are the invariants both the in-memory and file adapters must satisfy: idempotent enqueue,
 * exclusive leases, CAS-protected dispatch intent, one terminal settlement and a strict canonical
 * document encoding. The file adapter re-runs the same contract in the app module.
 */
class AgentDispatchOutboxTest {

    @Test
    fun enqueueIsIdempotentOnTheInvocationKeyAndRejectsAnotherInvocation() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()

        val replay = harness.store.enqueue(entry) as AgentDispatchEnqueueResult.Recorded

        assertEquals(entry.idempotencyKey, replay.entry.idempotencyKey)
        assertEquals(AgentDispatchOutboxStatus.PENDING, replay.entry.status)
        assertEquals("a replay must not create a second row", 1, harness.store.listActive(10).size)

        val conflict = harness.store.enqueue(entry.copy(invocationId = "invocation-other"))
        assertTrue("a foreign invocation must not reuse the key", conflict is AgentDispatchEnqueueResult.Rejected)
    }

    @Test
    fun claimIsExclusiveAndTheLeaseAllowsExactlyOneReclaim() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()

        val first = harness.store.claim(entry.idempotencyKey, "dispatcher-1", harness.nowMillis, 1_000L)
        val granted = first as AgentDispatchClaimResult.Granted
        assertEquals(AgentDispatchOutboxStatus.CLAIMED, granted.entry.status)
        assertEquals("dispatcher-1", granted.entry.leaseOwner)
        assertEquals(1, granted.entry.dispatchAttempts)
        assertEquals(
            AgentDispatchClaimResult.NotAvailable,
            harness.store.claim(entry.idempotencyKey, "dispatcher-2", harness.nowMillis, 1_000L)
        )

        harness.nowMillis += 1_001L
        val reclaimed = harness.store.claim(
            entry.idempotencyKey,
            "dispatcher-2",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        assertEquals("dispatcher-2", reclaimed.entry.leaseOwner)
        assertEquals(2, reclaimed.entry.dispatchAttempts)
    }

    @Test
    fun dispatchIntentIsNotClaimableAndTerminalSettlementIsFinal() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dispatcher-1",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted

        val dispatching = harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "dispatcher-1",
            nowMillis = harness.nowMillis
        ) as AgentDispatchUpdateResult.Updated
        assertEquals(AgentDispatchOutboxStatus.DISPATCHING, dispatching.entry.status)
        assertEquals(
            "a recorded dispatch intent must never be claimed again",
            AgentDispatchClaimResult.NotAvailable,
            harness.store.claim(entry.idempotencyKey, "dispatcher-2", harness.nowMillis + 10_000L, 1_000L)
        )

        val settled = harness.store.settle(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = dispatching.entry.revision,
            status = AgentDispatchOutboxStatus.SETTLED,
            invocationStatus = ToolInvocationStatus.SUCCEEDED,
            errorCode = null,
            nowMillis = harness.nowMillis + 1L
        ) as AgentDispatchUpdateResult.Updated
        assertEquals(AgentDispatchOutboxStatus.SETTLED, settled.entry.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, settled.entry.settledInvocationStatus)
        assertNull(settled.entry.leaseOwner)
        assertEquals(0L, settled.entry.leaseExpiresAtMillis)

        val second = harness.store.settle(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = settled.entry.revision,
            status = AgentDispatchOutboxStatus.FAILED,
            invocationStatus = null,
            errorCode = "late",
            nowMillis = harness.nowMillis + 2L
        )
        assertTrue("a terminal row must not be re-opened", second is AgentDispatchUpdateResult.Rejected)
        assertEquals(
            "a replayed enqueue returns the terminal row instead of dispatching again",
            AgentDispatchOutboxStatus.SETTLED,
            (harness.store.enqueue(entry) as AgentDispatchEnqueueResult.Recorded).entry.status
        )
    }

    @Test
    fun releaseRequiresTheLeaseOwnerAndReturnsTheRowToPending() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dispatcher-1",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted

        val stale = harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision + 5L,
            leaseOwner = "dispatcher-1",
            nowMillis = harness.nowMillis
        )
        assertTrue("a stale revision must lose the CAS", stale is AgentDispatchUpdateResult.Rejected)

        val foreign = harness.store.release(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "dispatcher-2",
            nowMillis = harness.nowMillis
        )
        assertTrue("only the lease owner may release", foreign is AgentDispatchUpdateResult.Rejected)

        val released = harness.store.release(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "dispatcher-1",
            nowMillis = harness.nowMillis + 1L
        ) as AgentDispatchUpdateResult.Updated
        assertEquals(AgentDispatchOutboxStatus.PENDING, released.entry.status)
        assertNull(released.entry.leaseOwner)
    }

    @Test
    fun canonicalJsonRoundTripsAndRejectsCorruption() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dispatcher-1",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        val dispatching = harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "dispatcher-1",
            nowMillis = harness.nowMillis
        ) as AgentDispatchUpdateResult.Updated
        val settled = harness.store.settle(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = dispatching.entry.revision,
            status = AgentDispatchOutboxStatus.NEEDS_REVIEW,
            invocationStatus = null,
            errorCode = "dispatch_receipt_unknown",
            nowMillis = harness.nowMillis + 1L
        ) as AgentDispatchUpdateResult.Updated
        val pending = harness.danglingEntry(invocationId = "invocation-dangling")

        val text = AgentDispatchOutboxJson.encode(listOf(settled.entry, pending))
        val decoded = AgentDispatchOutboxJson.decode(text)
        assertNotNull("a canonical document must round-trip", decoded)
        assertEquals(2, decoded!!.size)
        assertEquals(
            AgentDispatchOutboxStatus.NEEDS_REVIEW,
            decoded.first { it.invocationId == entry.invocationId }.status
        )

        assertNull(AgentDispatchOutboxJson.decode("not json"))
        assertNull(AgentDispatchOutboxJson.decode(text.replace("NEEDS_REVIEW", "TOTALLY_DONE")))
        assertNull(
            "duplicate idempotency keys are corruption",
            AgentDispatchOutboxJson.decode(AgentDispatchOutboxJson.encode(listOf(pending, pending)))
        )
    }
}
