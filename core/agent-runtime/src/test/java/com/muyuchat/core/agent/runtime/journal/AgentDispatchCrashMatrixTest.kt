package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash matrix for the approval -> dispatch -> execution -> result boundaries (N03 / N19 / TOOL-26).
 *
 * Each case kills the JVM at one boundary, then restarts a fresh dispatcher over the same durable
 * state. Assertions are about facts, not intentions: the external side-effect count, the durable
 * invocation status and the outbox terminal status. "Unknown" never becomes a success.
 */
class AgentDispatchCrashMatrixTest {

    @Test
    fun crashAfterApprovalBeforeAnyDispatchRunsTheEffectOnceAfterRestart() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()

        assertEquals(
            "the approved-but-undispatched row is durable before the crash",
            AgentDispatchOutboxStatus.PENDING,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )

        val executor = RecordingDispatchExecutor(harness.journal)
        val pass = harness.dispatcher(executor, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, pass.dispatched)
        assertEquals(1, executor.effects.size)
        assertEquals(
            AgentDispatchOutboxStatus.SETTLED,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )
    }

    @Test
    fun crashAfterClaimBeforeDispatchIntentReclaimsTheLeaseAndRunsOnce() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        harness.store.claim(entry.idempotencyKey, "crashed-dispatcher", harness.nowMillis, 1_000L)
        harness.nowMillis += 2_000L

        val executor = RecordingDispatchExecutor(harness.journal)
        val pass = harness.dispatcher(executor, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, pass.dispatched)
        assertEquals(1, executor.effects.size)
        assertEquals(
            AgentDispatchOutboxStatus.SETTLED,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )
    }

    @Test
    fun crashAfterDispatchIntentBeforeExecutionIsFencedWithoutSideEffects() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val claimed = harness.store.claim(
            entry.idempotencyKey,
            "crashed-dispatcher",
            harness.nowMillis,
            1_000L
        ) as AgentDispatchClaimResult.Granted
        harness.store.markDispatching(
            idempotencyKey = entry.idempotencyKey,
            expectedRevision = claimed.entry.revision,
            leaseOwner = "crashed-dispatcher",
            nowMillis = harness.nowMillis
        )
        harness.nowMillis += 2_000L

        val recovered = harness.reconciler().reconcile()
        val executor = RecordingDispatchExecutor(harness.journal)
        val pass = harness.dispatcher(executor, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals("a recorded dispatch intent must never be resent", 0, pass.dispatched)
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
    fun crashDuringExecutionIsFencedAndTheLedgerIsNotRewrittenAsSuccess() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val crashed = RecordingDispatchExecutor(
            harness.journal,
            crash = DispatchCrashPoint.DURING_EXECUTION
        )

        val death = runCatching { harness.dispatcher(crashed).dispatchOnce() }
        assertTrue("the simulated process death must escape the pass", death.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals(0, crashed.effects.size)
        assertEquals(
            AgentDispatchOutboxStatus.DISPATCHING,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )
        assertEquals(
            ToolInvocationStatus.RUNNING,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )

        harness.nowMillis += 2_000L
        val restarted = RecordingDispatchExecutor(harness.journal)
        val recovered = harness.reconciler().reconcile()
        val pass = harness.dispatcher(restarted, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals("an in-flight execution is never resent", 0, pass.dispatched)
        assertEquals(0, restarted.effects.size)
        val invocation = requireNotNull(harness.journal.readInvocation(entry.invocationId))
        assertEquals(ToolInvocationStatus.RUNNING, invocation.status)
        assertNotEquals(ToolInvocationStatus.SUCCEEDED, invocation.status)
    }

    @Test
    fun crashBeforeResultCommitKeepsTheEffectCountAtOneAndRequiresReview() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val crashed = RecordingDispatchExecutor(
            harness.journal,
            crash = DispatchCrashPoint.BEFORE_RESULT_COMMIT
        )

        val death = runCatching { harness.dispatcher(crashed).dispatchOnce() }
        assertTrue(death.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals("the external side effect happened", 1, crashed.effects.size)
        assertEquals(
            ToolInvocationStatus.RUNNING,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )

        harness.nowMillis += 2_000L
        val restarted = RecordingDispatchExecutor(harness.journal)
        val recovered = harness.reconciler().reconcile()
        val pass = harness.dispatcher(restarted, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals(0, pass.dispatched)
        assertEquals(
            "the restart must not perform a second effect",
            1,
            crashed.effects.size + restarted.effects.size
        )
        assertEquals(0, restarted.effects.size)
        assertEquals(
            "the uncommitted result must not be presented as success",
            ToolInvocationStatus.RUNNING,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )
        assertEquals(
            AgentDispatchOutboxStatus.NEEDS_REVIEW,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )
    }

    @Test
    fun crashAfterResultCommitReusesTheTerminalFactWithoutExecutingAgain() = runTest {
        val harness = AgentDispatchHarness()
        harness.seedRun()
        val entry = harness.approveInvocation()
        val crashed = RecordingDispatchExecutor(
            harness.journal,
            crash = DispatchCrashPoint.AFTER_RESULT_COMMIT
        )

        val death = runCatching { harness.dispatcher(crashed).dispatchOnce() }
        assertTrue(death.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals(1, crashed.effects.size)
        assertEquals(
            ToolInvocationStatus.SUCCEEDED,
            requireNotNull(harness.journal.readInvocation(entry.invocationId)).status
        )
        assertEquals(
            "the outbox lags behind the durable result",
            AgentDispatchOutboxStatus.DISPATCHING,
            requireNotNull(harness.store.read(entry.idempotencyKey)).status
        )

        harness.nowMillis += 2_000L
        val restarted = RecordingDispatchExecutor(harness.journal)
        val recovered = harness.reconciler().reconcile()
        val pass = harness.dispatcher(restarted, dispatcherId = "restarted").dispatchOnce()

        assertEquals(1, recovered.settled)
        assertEquals(0, pass.dispatched)
        assertEquals(
            "the committed result is reused, not produced twice",
            1,
            crashed.effects.size + restarted.effects.size
        )
        assertEquals(0, restarted.effects.size)
        assertNotNull(harness.journal.readInvocation(entry.invocationId))
        val outbox = requireNotNull(harness.store.read(entry.idempotencyKey))
        assertEquals(AgentDispatchOutboxStatus.SETTLED, outbox.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, outbox.settledInvocationStatus)
    }
}
