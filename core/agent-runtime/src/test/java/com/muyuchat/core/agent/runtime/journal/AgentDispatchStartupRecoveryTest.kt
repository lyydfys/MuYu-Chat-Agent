package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N03/N19 startup fence: after a process restart the outbox is aligned with the authoritative
 * invocation/run facts and nothing is dispatched from a lost in-memory authorization.
 */
class AgentDispatchStartupRecoveryTest {

    @Test
    fun anApprovedRowIsFencedBecauseItsInMemoryAuthorizationIsGone() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val executor = RecordingDispatchExecutor(harness.journal)

        val report = harness.reconciler().reconcile()

        assertEquals(1, report.needsReview)
        assertEquals(0, executor.dispatchCalls)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("authorization_not_recoverable", outbox.errorCode)
        assertEquals(
            "the reconciler must not rewrite the invocation row",
            ToolInvocationStatus.APPROVED,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )
        val pass = harness.dispatcher(executor).dispatchOnce()
        assertEquals("a fenced row is terminal and cannot be dispatched", 0, pass.dispatched)
    }

    @Test
    fun aClaimedRowWithoutADispatchIntentIsAlsoFenced() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        harness.store.claim(entry.idempotencyKey, "dead", harness.nowMillis, 1_000L)

        val report = harness.reconciler().reconcile()

        assertEquals(1, report.needsReview)
        assertEquals(
            "authorization_not_recoverable",
            requireNotNull(harness.store.read(entry.idempotencyKey)).errorCode
        )
    }

    @Test
    fun aDispatchingRowWithARunningInvocationStaysUnknown() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dead",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        harness.store.markDispatching(entry.idempotencyKey, claimed.entry.revision, "dead", harness.nowMillis)
        harness.journal.markRunning(entry.invocationId, harness.nowMillis + 1L)

        val report = harness.reconciler().reconcile()

        assertEquals(1, report.needsReview)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("execution_result_unknown", outbox.errorCode)
        assertEquals(
            ToolInvocationStatus.RUNNING,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )
    }

    @Test
    fun aDispatchingRowWithATerminalInvocationSettlesFromTheLedger() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "dead",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        harness.store.markDispatching(entry.idempotencyKey, claimed.entry.revision, "dead", harness.nowMillis)
        harness.journal.markRunning(entry.invocationId, harness.nowMillis + 1L)
        harness.journal.markTerminal(
            invocationId = entry.invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = "result-1",
            atMillis = harness.nowMillis + 2L
        )

        val report = harness.reconciler().reconcile()

        assertEquals(1, report.settled)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.SETTLED, outbox.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, outbox.settledInvocationStatus)
    }

    @Test
    fun aTerminalUnknownIsFencedInsteadOfSettled() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        harness.journal.markRunning(entry.invocationId, harness.nowMillis + 1L)
        harness.journal.markTerminal(
            invocationId = entry.invocationId,
            status = ToolInvocationStatus.UNKNOWN,
            possibleSideEffects = true,
            resultRef = null,
            atMillis = harness.nowMillis + 2L
        )

        val report = harness.reconciler().reconcile()

        assertEquals(1, report.needsReview)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("invocation_unknown", outbox.errorCode)
        assertEquals(null, outbox.settledInvocationStatus)
    }

    @Test
    fun anUncommittedApprovalIsDeferredInsideTheGraceWindowThenExpired() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.uncommittedApprovalEntry()

        val deferred = harness.reconciler(approvalGraceMillis = 5_000L).reconcile()
        assertEquals(1, deferred.deferred)
        assertEquals(AgentDispatchOutboxStatus.PENDING, requireNotNull(harness.store.read(entry.idempotencyKey)).status)

        harness.nowMillis += 6_000L
        val expired = harness.reconciler(approvalGraceMillis = 5_000L).reconcile()
        assertEquals(1, expired.expired)
        assertEquals(
            AgentDispatchOutboxStatus.EXPIRED,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )
    }

    @Test
    fun reconciliationIsIdempotentAndNeverDispatches() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val first = harness.reconciler().reconcile()
        val second = harness.reconciler().reconcile()

        assertEquals(1, first.needsReview)
        assertEquals("a terminal row is no longer active", 0, second.examined)
        assertEquals(0, second.needsReview)
        assertEquals(0, second.settled + second.expired + second.failed)
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
    }

    @Test
    fun aMissingInvocationIsFencedAsReview() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val dangling = harness.danglingEntry()
        harness.store.enqueue(dangling)

        val report = harness.reconciler().reconcile()

        assertEquals(1, report.needsReview)
        val outbox = requireNotNull(harness.store.read(dangling.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.NEEDS_REVIEW, outbox.status)
        assertEquals("invocation_missing", outbox.errorCode)
        assertTrue(report.examined == 1)
    }
}
