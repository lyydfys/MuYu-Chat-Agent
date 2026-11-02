package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatcher recovery semantics after process death (N03 / N19 / TOOL-26 / WF-16).
 *
 * Every case starts from durable state only: a fresh dispatcher (a new instance) must decide from
 * the authoritative invocation row, never from an in-memory approval callback.
 */
class AgentDispatchRecoveryTest {

    @Test
    fun anApprovedEntryDispatchesOnceAndDuplicatePassesDoNotRunAgain() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val executor = RecordingDispatchExecutor(harness.journal)

        val first = harness.dispatcher(executor).dispatchOnce()

        assertEquals(1, first.dispatched)
        assertEquals(1, first.settled)
        assertEquals(1, executor.effects.size)
        assertEquals(1, executor.runningGateWins)
        val settled = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.SETTLED, settled.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, settled.settledInvocationStatus)

        val second = harness.dispatcher(executor).dispatchOnce()
        assertEquals("a completed row must not be dispatched again", 0, second.dispatched)
        assertEquals(1, executor.effects.size)
        assertEquals(1, executor.runningGateWins)
    }

    @Test
    fun aRunningInvocationIsFencedInsteadOfBeingResentOrCalledSuccessful() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dead-dispatcher",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "dead-dispatcher",
            nowMillis = harness.nowMillis
        )
        assertTrue(
            harness.journal.markRunning(entry.invocationId, harness.nowMillis + 1L) is
                AgentToolInvocationUpdate.Updated
        )
        harness.nowMillis += 2_000L
        val executor = RecordingDispatchExecutor(harness.journal)

        val recovered = harness.reconciler().reconcile()

        assertEquals("an unknown receipt must never be dispatched again", 1, recovered.needsReview)
        assertEquals(0, executor.effects.size)
        val invocation = requireNotNull(harness.journal.readInvocation(entry.invocationId))
        assertEquals("the ledger fact must not be rewritten as success", ToolInvocationStatus.RUNNING, invocation.status)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertNull(outbox.settledInvocationStatus)
    }

    @Test
    fun aDispatchedEntryWithoutReceiptIsFencedNotRedispatched() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dead-dispatcher",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "dead-dispatcher",
            nowMillis = harness.nowMillis
        )
        harness.nowMillis += 2_000L
        val executor = RecordingDispatchExecutor(harness.journal)

        val recovered = harness.reconciler().reconcile()

        assertEquals(1, recovered.needsReview)
        assertEquals(0, executor.dispatchCalls)
        assertEquals(
            ToolInvocationStatus.APPROVED,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("dispatch_receipt_unknown", outbox.errorCode)
    }

    @Test
    fun thePeriodicPassNeverTouchesADispatchingRowThatMayStillBeRunning() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "live-dispatcher",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "live-dispatcher",
            nowMillis = harness.nowMillis
        )
        harness.nowMillis += 5_000L
        val executor = RecordingDispatchExecutor(harness.journal)

        val pass = harness.dispatcher(executor).dispatchOnce()

        assertEquals("a long-running tool must not be reaped by a periodic pass", 0, pass.dispatched)
        assertEquals(0, pass.needsReview)
        assertEquals(0, executor.dispatchCalls)
        assertEquals(
            AgentDispatchOutboxStatus.DISPATCHING,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )
    }

    @Test
    fun aTerminalInvocationSettlesTheOutboxWithoutExecuting() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        harness.journal.markRunning(entry.invocationId, harness.nowMillis + 1L)
        harness.journal.markTerminal(
            invocationId = entry.invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = "result-1",
            atMillis = harness.nowMillis + 2L
        )
        val executor = RecordingDispatchExecutor(harness.journal)

        val pass = harness.dispatcher(executor).dispatchOnce()

        assertEquals(0, pass.dispatched)
        assertEquals(1, pass.settled)
        assertEquals(0, executor.dispatchCalls)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.SETTLED, outbox.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, outbox.settledInvocationStatus)
    }

    @Test
    fun anOutboxRowWithoutACommittedApprovalExpiresInsteadOfDispatching() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val pending = harness.uncommittedApprovalEntry()
        val executor = RecordingDispatchExecutor(harness.journal)

        val withinGrace = harness.dispatcher(executor, approvalGraceMillis = 5_000L).dispatchOnce()
        assertEquals("an in-flight approval commit is deferred, not dispatched", 1, withinGrace.deferred)
        assertEquals(0, executor.dispatchCalls)

        harness.nowMillis += 6_000L
        val pass = harness.dispatcher(executor, approvalGraceMillis = 5_000L).dispatchOnce()
        assertEquals(0, pass.dispatched)
        assertEquals(1, pass.expired)
        assertEquals(0, executor.dispatchCalls)
        assertEquals(
            AgentDispatchOutboxStatus.EXPIRED,
            requireNotNull(harness.store.read(pending.idempotencyKey)).status
        )
    }

    @Test
    fun anExpiredClaimIsReclaimedAndExecutedExactlyOnce() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        harness.store.claim(entry.idempotencyKey, "dead-dispatcher", harness.nowMillis, 1_000L)
        harness.nowMillis += 2_000L
        val executor = RecordingDispatchExecutor(harness.journal)

        val pass = harness.dispatcher(executor).dispatchOnce()

        assertEquals(1, pass.dispatched)
        assertEquals(1, executor.effects.size)
        assertEquals(1, executor.runningGateWins)
    }

    @Test
    fun aMissingInvocationIsFencedWithReviewNotSuccess() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val pending = harness.danglingEntry()
        harness.store.enqueue(pending)
        val executor = RecordingDispatchExecutor(harness.journal)

        val pass = harness.dispatcher(executor).dispatchOnce()

        assertEquals(0, pass.dispatched)
        assertEquals(1, pass.needsReview)
        assertEquals(0, executor.dispatchCalls)
        val outbox = requireNotNull(harness.store.read(pending.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("invocation_missing", outbox.errorCode)
    }

    @Test
    fun anIdentityMismatchFailsWithoutDispatching() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation(enqueue = false)
        val mismatched = entry.copy(toolKey = "workspace.write_text")
        harness.store.enqueue(mismatched)
        val executor = RecordingDispatchExecutor(harness.journal)

        val pass = harness.dispatcher(executor).dispatchOnce()

        assertEquals(0, pass.dispatched)
        assertEquals(1, pass.failed)
        assertEquals(0, executor.dispatchCalls)
        assertEquals(
            AgentDispatchOutboxStatus.FAILED,
            requireNotNull(harness.store.read(mismatched.idempotencyKey)).status
        )
    }

    @Test
    fun concurrentDispatchersProduceOneExecution() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        harness.approveInvocation()
        val executor = RecordingDispatchExecutor(harness.journal)

        val reports = coroutineScope {
            val first = async { harness.dispatcher(executor, dispatcherId = "dispatcher-a").dispatchOnce() }
            val second = async {
                delay(1L)
                harness.dispatcher(executor, dispatcherId = "dispatcher-b").dispatchOnce()
            }
            listOf(first.await(), second.await())
        }

        assertEquals("exactly one dispatcher may hand the row to the executor", 1, executor.dispatchCalls)
        assertEquals(1, executor.effects.size)
        assertEquals(1, reports.sumOf { it.dispatched })
    }
}
