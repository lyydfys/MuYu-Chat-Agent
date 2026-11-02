package com.muyuchat.mca.composition

import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.runtime.run.AgentRunStopSettlement
import com.muyuchat.core.agent.runtime.run.AgentStopSettlementReceipt
import com.muyuchat.mca.agent.inference.AgentLeaseRelease
import com.muyuchat.mca.agent.inference.AgentLeaseState
import com.muyuchat.mca.agent.inference.AgentModelLeaseRetention
import com.muyuchat.mca.agent.inference.AgentStopProof
import com.muyuchat.mca.agent.inference.FakeLeaseHandle
import com.muyuchat.mca.agent.inference.attempt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C01 / P01 / P02: the run settlement source is derived only from the real Agent model lease
 * retention. A non-released lease is never reported as Released, and a later engine proof that
 * releases the fence is reported to the run owner exactly once.
 */
class AgentLeaseRunStopSettlementSourceTest {

    private class ReportedSettlements {
        val receipts = CopyOnWriteArrayList<AgentStopSettlementReceipt>()
        val first = CompletableDeferred<AgentStopSettlementReceipt>()

        suspend fun report(receipt: AgentStopSettlementReceipt) {
            receipts += receipt
            first.complete(receipt)
        }
    }

    private fun source(
        retention: AgentModelLeaseRetention,
        scope: CoroutineScope,
        reported: ReportedSettlements,
        stoppingRunEpoch: (String) -> Long? = { 1L }
    ): AgentLeaseRunStopSettlementSource =
        AgentLeaseRunStopSettlementSource(
            retention = retention,
            scope = scope,
            lookupStoppingRunEpoch = { runId -> stoppingRunEpoch(runId) },
            reportLateSettlement = reported::report
        )

    private fun acquiredAttempt(
        retention: AgentModelLeaseRetention,
        ref: AgentModelAttemptRef = attempt(),
        lease: FakeLeaseHandle = FakeLeaseHandle()
    ): FakeLeaseHandle {
        retention.register(ref, requestId = "agent:${ref.runId}:0:${ref.ordinal}", lease = lease)
        return lease
    }

    private fun confirmed(evidence: String = "native idle (test)"): AgentStopProof =
        AgentStopProof.Confirmed(evidence = evidence, waitedMillis = 10L)

    @Test
    fun noRetainedLeaseIsReleasedBecauseThereIsNoResourceFence() = runBlocking {
        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(AgentModelLeaseRetention(), scope, reported)
                .settlementFor("run-1", 1L)

            assertTrue(receipt.settlement is AgentRunStopSettlement.Released)
            assertEquals("run-1", receipt.runId)
            assertEquals(1L, receipt.runEpoch)
            assertNull(receipt.attempt)
            assertTrue((receipt.settlement as AgentRunStopSettlement.Released)
                .evidence.orEmpty().contains("no retained Agent model lease"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun everyReleasedLeaseIsReleased() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        retention.markStopRequested(ref)
        val released = retention.releaseAfterProof(ref, confirmed())
        assertTrue(released is AgentLeaseRelease.Released)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(retention, scope, ReportedSettlements()).settlementFor("run-1", 4L)

            assertTrue(receipt.settlement is AgentRunStopSettlement.Released)
            assertTrue((receipt.settlement as AgentRunStopSettlement.Released)
                .evidence.orEmpty().contains("are RELEASED"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anAcquiredButNotDispatchedLeaseIsNeverReportedAsReleased() = runBlocking {
        val retention = AgentModelLeaseRetention()
        acquiredAttempt(retention)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(retention, scope, ReportedSettlements()).settlementFor("run-1", 1L)

            val settlement = receipt.settlement as AgentRunStopSettlement.StoppingUnknown
            assertTrue(settlement.detail.contains(AgentLeaseState.ACQUIRED.name))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDispatchedLeaseIsNeverReportedAsReleased() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(retention, scope, ReportedSettlements()).settlementFor("run-1", 1L)

            val settlement = receipt.settlement as AgentRunStopSettlement.StoppingUnknown
            assertTrue(settlement.detail.contains(AgentLeaseState.DISPATCHED.name))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aStoppingLeaseIsNeverReportedAsReleased() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        retention.markStopRequested(ref)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(retention, scope, ReportedSettlements()).settlementFor("run-1", 1L)

            val settlement = receipt.settlement as AgentRunStopSettlement.StoppingUnknown
            assertTrue(settlement.detail.contains(AgentLeaseState.STOPPING.name))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFenceHeldLeaseIsNeverReportedAsReleased() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        val blocked = retention.releaseAfterProof(
            ref,
            AgentStopProof.TimedOut(
                waitedMillis = 200L,
                detail = "native generation is still active",
                activity = "ACTIVE"
            )
        )
        assertTrue(blocked is AgentLeaseRelease.Blocked)
        assertEquals(AgentLeaseState.FENCE_HELD, retention.snapshot(ref)?.state)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(retention, scope, ReportedSettlements()).settlementFor("run-1", 1L)

            val settlement = receipt.settlement as AgentRunStopSettlement.StoppingUnknown
            assertTrue(settlement.detail.contains(AgentLeaseState.FENCE_HELD.name))
            assertTrue(settlement.detail.contains("still active"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLeaseRetainedForAnotherRunDoesNotBlockThisRun() = runBlocking {
        val retention = AgentModelLeaseRetention()
        acquiredAttempt(retention, attempt(runId = "run-2"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val receipt = source(retention, scope, ReportedSettlements()).settlementFor("run-1", 1L)

            assertTrue(receipt.settlement is AgentRunStopSettlement.Released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLateReleasedProofIsReportedWithTheQueriedRunIdentity() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        retention.markStopRequested(ref)

        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported)
            val first = source.settlementFor("run-1", 5L)
            assertTrue(first.settlement is AgentRunStopSettlement.StoppingUnknown)
            assertTrue(reported.receipts.isEmpty())

            val release = retention.releaseAfterProof(ref, confirmed("native idle after late exit"))
            assertTrue(release is AgentLeaseRelease.Released)
            source.onLeaseSettled(ref, release, stoppedExecution = true)

            val receipt = withTimeout(2_000L) { reported.first.await() }
            assertTrue(receipt.settlement is AgentRunStopSettlement.Released)
            assertEquals("run-1", receipt.runId)
            assertEquals(5L, receipt.runEpoch)
            assertEquals(ref, receipt.attempt)
            assertEquals(1, reported.receipts.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anOrdinarySuccessfulReleaseIsNotReportedAsAStopSettlement() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        val release = retention.releaseAfterProof(ref, confirmed())
        assertTrue(release is AgentLeaseRelease.Released)

        val reported = ReportedSettlements()
        val lookups = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported, stoppingRunEpoch = { lookups.incrementAndGet(); 1L })
            source.onLeaseSettled(ref, release, stoppedExecution = false)

            delay(200L)
            assertTrue(reported.receipts.isEmpty())
            assertEquals(0, lookups.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aStopDrivenReleaseWithoutAQueryUsesThePersistedStoppingEpoch() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        val release = retention.releaseAfterProof(ref, confirmed())
        assertTrue(release is AgentLeaseRelease.Released)

        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported, stoppingRunEpoch = { runId ->
                if (runId == "run-1") 9L else null
            })
            // No settlement query happened: the owner's join timeout path. The journal epoch binds
            // the late receipt to the exact run.
            source.onLeaseSettled(ref, release, stoppedExecution = true)

            val receipt = withTimeout(2_000L) { reported.first.await() }
            assertTrue(receipt.settlement is AgentRunStopSettlement.Released)
            assertEquals("run-1", receipt.runId)
            assertEquals(9L, receipt.runEpoch)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLateReleaseForAnUnknownRunEpochIsNotReported() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        val release = retention.releaseAfterProof(ref, confirmed())
        assertTrue(release is AgentLeaseRelease.Released)

        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported, stoppingRunEpoch = { null })
            source.onLeaseSettled(ref, release, stoppedExecution = true)

            delay(200L)
            assertTrue(reported.receipts.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aForeignAttemptIdentityWithTheSameAttemptIdIsNotReported() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        retention.markStopRequested(ref)

        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported)
            assertTrue(source.settlementFor("run-1", 3L).settlement is AgentRunStopSettlement.StoppingUnknown)

            val release = retention.releaseAfterProof(ref, confirmed())
            source.onLeaseSettled(ref.copy(runId = "run-2"), release, stoppedExecution = true)

            delay(200L)
            assertTrue(reported.receipts.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLateReleaseNeverSettlesTheRunWhileANewerSameRunLeaseIsStillHeld() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val first = attempt(ordinal = 1)
        acquiredAttempt(retention, first)
        retention.markDispatchStarted(first)
        retention.markStopRequested(first)

        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported)
            assertTrue(source.settlementFor("run-1", 1L).settlement is AgentRunStopSettlement.StoppingUnknown)

            val firstRelease = retention.releaseAfterProof(first, confirmed())
            assertTrue(firstRelease is AgentLeaseRelease.Released)

            // A later execution of the same run acquires the lease before the first release is
            // reported. Its proof, not the older one, must settle the stop.
            val second = attempt(ordinal = 2)
            acquiredAttempt(retention, second)
            retention.markDispatchStarted(second)
            retention.markStopRequested(second)
            assertTrue(source.settlementFor("run-1", 1L).settlement is AgentRunStopSettlement.StoppingUnknown)

            source.onLeaseSettled(first, firstRelease, stoppedExecution = true)
            delay(200L)
            assertTrue(reported.receipts.isEmpty())

            val secondRelease = retention.releaseAfterProof(second, confirmed())
            assertTrue(secondRelease is AgentLeaseRelease.Released)
            source.onLeaseSettled(second, secondRelease, stoppedExecution = true)

            val receipt = withTimeout(2_000L) { reported.first.await() }
            assertEquals(second, receipt.attempt)
            assertTrue(receipt.settlement is AgentRunStopSettlement.Released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSecondSettlementQueryProvesReleasedAndPrunesThePendingIdentity() = runBlocking {
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        acquiredAttempt(retention, ref)
        retention.markDispatchStarted(ref)
        retention.markStopRequested(ref)

        val reported = ReportedSettlements()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = source(retention, scope, reported)
            assertTrue(source.settlementFor("run-1", 2L).settlement is AgentRunStopSettlement.StoppingUnknown)

            val release = retention.releaseAfterProof(ref, confirmed())
            assertTrue(release is AgentLeaseRelease.Released)

            val second = source.settlementFor("run-1", 2L)
            assertTrue(second.settlement is AgentRunStopSettlement.Released)

            // The release was already accounted for by the second query; an ordinary (non-stop)
            // notification for it must not enqueue a second receipt.
            source.onLeaseSettled(ref, release, stoppedExecution = false)
            delay(200L)
            assertTrue(reported.receipts.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
