package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxDispatcher
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchStartupReconciler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N03/N19 crash matrix over the real file outbox.
 *
 * Every restart builds a *new* [FileAgentDispatchOutboxStore] over the same directory and a new
 * dispatcher, so no in-memory state can mask a lost or duplicated dispatch. Covers the five required
 * boundaries plus the claim-without-intent boundary and the receipt-with-result gap.
 */
class FileAgentDispatchCrashRecoveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var nowMillis: Long = 1_000L
    private val ledger = TestDispatchLedger()

    private fun store() = FileAgentDispatchOutboxStore(
        rootDirectory = folder.root,
        clock = { nowMillis }
    )

    private fun dispatcher(
        store: FileAgentDispatchOutboxStore,
        executor: TestDispatchExecutor,
        dispatcherId: String = "restarted"
    ) = AgentDispatchOutboxDispatcher(
        store = store,
        ledger = ledger,
        executor = executor,
        dispatcherId = dispatcherId,
        clock = { nowMillis },
        leaseMillis = 1_000L,
        approvalGraceMillis = 0L
    )

    private fun reconciler(store: FileAgentDispatchOutboxStore) = AgentDispatchStartupReconciler(
        store = store,
        ledger = ledger,
        clock = { nowMillis },
        approvalGraceMillis = 0L
    )

    @Test
    fun crashAfterApprovalBeforeDispatchDispatchesOnceAfterRestart() = runBlocking {
        val entry = ledger.seedApproved()
        store().enqueue(entry)

        val executor = TestDispatchExecutor(ledger)
        val report = dispatcher(store(), executor).dispatchOnce()

        assertEquals(1, report.dispatched)
        assertEquals(1, executor.effects.size)
        assertEquals(
            AgentDispatchOutboxStatus.SETTLED,
            store().read(entry.idempotencyKey)!!.status
        )
        assertEquals(ToolInvocationStatus.SUCCEEDED, ledger.statusOf(entry.invocationId))
    }

    @Test
    fun crashAfterClaimBeforeDispatchIntentRunsTheEffectOnceAfterLeaseExpiry() = runBlocking {
        val entry = ledger.seedApproved()
        store().enqueue(entry)
        store().claim(entry.idempotencyKey, "crashed", nowMillis, 1_000L)
        nowMillis += 2_000L

        val executor = TestDispatchExecutor(ledger)
        val report = dispatcher(store(), executor).dispatchOnce()

        assertEquals(1, report.dispatched)
        assertEquals(1, executor.effects.size)
    }

    @Test
    fun crashAfterDispatchIntentBeforeExecutionIsFencedOverTheFileStore() = runBlocking {
        val entry = ledger.seedApproved()
        val first = store()
        first.enqueue(entry)
        val claimed = first.claim(entry.idempotencyKey, "crashed", nowMillis, 1_000L)
            as AgentDispatchClaimResult.Granted
        first.markDispatching(entry.idempotencyKey, claimed.entry.revision, "crashed", nowMillis)
        nowMillis += 2_000L

        val recovered = reconciler(store()).reconcile()
        val executor = TestDispatchExecutor(ledger)
        val report = dispatcher(store(), executor).dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals(0, report.dispatched)
        assertEquals(0, executor.dispatchCalls)
        assertEquals(ToolInvocationStatus.APPROVED, ledger.statusOf(entry.invocationId))
        assertEquals(
            AgentDispatchOutboxStatus.NEEDS_REVIEW,
            store().read(entry.idempotencyKey)!!.status
        )
    }

    @Test
    fun crashDuringExecutionIsFencedAndNeverReplayed() = runBlocking {
        val entry = ledger.seedApproved()
        store().enqueue(entry)
        val crashed = TestDispatchExecutor(ledger, crash = TestCrashPoint.DURING_EXECUTION)

        val death = runCatching { dispatcher(store(), crashed, "crashed").dispatchOnce() }
        assertTrue(death.exceptionOrNull() is TestProcessDeath)
        nowMillis += 2_000L

        val restarted = TestDispatchExecutor(ledger)
        val recovered = reconciler(store()).reconcile()
        val report = dispatcher(store(), restarted).dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals(0, report.dispatched)
        assertEquals(0, crashed.effects.size + restarted.effects.size)
        assertEquals(ToolInvocationStatus.RUNNING, ledger.statusOf(entry.invocationId))
        assertEquals(
            AgentDispatchOutboxStatus.NEEDS_REVIEW,
            store().read(entry.idempotencyKey)!!.status
        )
    }

    @Test
    fun crashBeforeResultCommitKeepsOneEffectAndRequiresReview() = runBlocking {
        val entry = ledger.seedApproved()
        store().enqueue(entry)
        val crashed = TestDispatchExecutor(ledger, crash = TestCrashPoint.BEFORE_RESULT_COMMIT)

        val death = runCatching { dispatcher(store(), crashed, "crashed").dispatchOnce() }
        assertTrue(death.exceptionOrNull() is TestProcessDeath)
        assertEquals(1, crashed.effects.size)
        nowMillis += 2_000L

        val restarted = TestDispatchExecutor(ledger)
        val recovered = reconciler(store()).reconcile()
        val report = dispatcher(store(), restarted).dispatchOnce()

        assertEquals(1, recovered.needsReview)
        assertEquals(0, report.dispatched)
        assertEquals(1, crashed.effects.size + restarted.effects.size)
        assertEquals(ToolInvocationStatus.RUNNING, ledger.statusOf(entry.invocationId))
        assertEquals(
            AgentDispatchOutboxStatus.NEEDS_REVIEW,
            store().read(entry.idempotencyKey)!!.status
        )
    }

    @Test
    fun crashAfterResultCommitReusesTheDurableResultWithoutASecondEffect() = runBlocking {
        val entry = ledger.seedApproved()
        store().enqueue(entry)
        val crashed = TestDispatchExecutor(ledger, crash = TestCrashPoint.AFTER_RESULT_COMMIT)

        val death = runCatching { dispatcher(store(), crashed, "crashed").dispatchOnce() }
        assertTrue(death.exceptionOrNull() is TestProcessDeath)
        assertEquals(1, crashed.effects.size)
        assertEquals(ToolInvocationStatus.SUCCEEDED, ledger.statusOf(entry.invocationId))
        assertEquals(
            AgentDispatchOutboxStatus.DISPATCHING,
            store().read(entry.idempotencyKey)!!.status
        )
        nowMillis += 2_000L

        val restarted = TestDispatchExecutor(ledger)
        val recovered = reconciler(store()).reconcile()
        val report = dispatcher(store(), restarted).dispatchOnce()

        assertEquals(1, recovered.settled)
        assertEquals(0, report.dispatched)
        assertEquals(1, crashed.effects.size + restarted.effects.size)
        val outbox = store().read(entry.idempotencyKey)!!
        assertEquals(AgentDispatchOutboxStatus.SETTLED, outbox.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, outbox.settledInvocationStatus)
    }
}
