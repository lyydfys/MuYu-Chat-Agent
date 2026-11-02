package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecutor
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxDispatcher
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchStartupReconciler
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P1 evidence for the documented N03 boundary: the dispatch outbox row is a **file** write performed
 * inside the Room approval transaction but *not* co-transactional with it
 * (`AgentApprovalOutboxLedger` KDoc). If Room rolls back after the file write, the outbox keeps a
 * PENDING row while the authoritative invocation is still `WAITING_APPROVAL`.
 *
 * This test uses the real [FileAgentDispatchOutboxStore] (durable file, restarted between steps) and
 * a rollback-capable ledger double: [AgentApprovalOutboxLedger.markApproved] writes the row, then the
 * ledger is reverted to the pre-commit state to model the rollback. The startup reconciler must
 * revalidate the authoritative row, defer inside the grace window, then expire the stale entry with
 * **zero executor effects**; the periodic dispatcher must never claim the expired row.
 *
 * Remaining gap (not weakened here): a real instrumented Room rollback around this path is still
 * required to prove the Room half; this suite proves the production File-store recovery half.
 */
class AgentApprovalRollbackOutboxRecoveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var nowMillis: Long = 1_000L
    private val ledger = RollbackableApprovalLedger()

    @Test
    fun fileOutboxRowFromRolledBackApprovalIsExpiredWithZeroEffects() = runBlocking {
        val outbox = store()
        val approvalOutbox = AgentApprovalOutboxLedger(ledger, outbox)

        val approved = approvalOutbox.markApproved(INVOCATION_ID, nowMillis)
        assertTrue("the approval CAS must be recorded before the row is written", approved is AgentToolInvocationUpdate.Updated)
        assertEquals("pre-rollback authoritative status", ToolInvocationStatus.APPROVED, ledger.status())
        val pending = requireNotNull(store().read(OUTBOX_KEY)) { "the file outbox row must exist" }
        assertEquals("file row after markApproved", AgentDispatchOutboxStatus.PENDING, pending.status)

        // Room transaction rolls back: the invocation returns to WAITING_APPROVAL, the file row stays.
        ledger.rollbackApproval()
        assertEquals("authoritative status after rollback", ToolInvocationStatus.WAITING_APPROVAL, ledger.status())

        val deferred = reconciler(store(), approvalGraceMillis = 5_000L).reconcile()
        assertEquals("inside the grace window the row is deferred", 1, deferred.deferred)
        assertEquals("deferred pass must not dispatch", 0, deferred.settled + deferred.expired + deferred.needsReview)
        assertEquals(
            "row stays PENDING while the grace window is open",
            AgentDispatchOutboxStatus.PENDING,
            requireNotNull(store().read(OUTBOX_KEY)).status
        )
        assertEquals(
            "the reconciler must not rewrite the invocation row",
            ToolInvocationStatus.WAITING_APPROVAL,
            ledger.status()
        )

        nowMillis += 6_000L
        val expired = reconciler(store(), approvalGraceMillis = 5_000L).reconcile()
        assertEquals("outside the grace window the stale row is expired", 1, expired.expired)
        val stale = requireNotNull(store().read(OUTBOX_KEY))
        assertEquals("expired row status", AgentDispatchOutboxStatus.EXPIRED, stale.status)
        assertEquals("expired row error code", "approval_not_committed", stale.errorCode)
        assertNull("an expired row must not claim a settled invocation status", stale.settledInvocationStatus)
        assertEquals(
            "the authoritative row is still untouched",
            ToolInvocationStatus.WAITING_APPROVAL,
            ledger.status()
        )

        val executor = CountingExecutor()
        val pass = dispatcher(store(), executor).dispatchOnce()
        assertEquals("an expired row must not be dispatched", 0, pass.dispatched)
        assertEquals("the executor must never be invoked", 0, executor.calls)
        assertEquals(
            "the store restart must still read the expired row",
            AgentDispatchOutboxStatus.EXPIRED,
            requireNotNull(store().read(OUTBOX_KEY)).status
        )
    }

    private fun store(): FileAgentDispatchOutboxStore =
        FileAgentDispatchOutboxStore(rootDirectory = folder.root, clock = { nowMillis })

    private fun reconciler(
        store: FileAgentDispatchOutboxStore,
        approvalGraceMillis: Long
    ) = AgentDispatchStartupReconciler(
        store = store,
        ledger = ledger,
        clock = { nowMillis },
        approvalGraceMillis = approvalGraceMillis
    )

    private fun dispatcher(
        store: FileAgentDispatchOutboxStore,
        executor: AgentDispatchExecutor
    ) = AgentDispatchOutboxDispatcher(
        store = store,
        ledger = ledger,
        executor = executor,
        dispatcherId = "restarted",
        clock = { nowMillis },
        leaseMillis = 1_000L,
        approvalGraceMillis = 0L
    )

    private class CountingExecutor : AgentDispatchExecutor {
        var calls: Int = 0
            private set

        override suspend fun dispatch(entry: AgentDispatchOutboxEntry): AgentDispatchExecution {
            calls += 1
            return AgentDispatchExecution.Settled(ToolInvocationStatus.SUCCEEDED)
        }
    }

    /**
     * Minimal ledger whose `markApproved` mirrors the pre-commit Room row and whose
     * [rollbackApproval] mirrors the transaction rollback after the file row was written.
     */
    private class RollbackableApprovalLedger : AgentToolInvocationLedger {
        private var status: ToolInvocationStatus = ToolInvocationStatus.WAITING_APPROVAL

        fun status(): ToolInvocationStatus = status

        fun rollbackApproval() {
            status = ToolInvocationStatus.WAITING_APPROVAL
        }

        override suspend fun readInvocation(invocationId: String): ToolInvocation =
            invocation(invocationId, status)

        override suspend fun markApproved(
            invocationId: String,
            atMillis: Long
        ): AgentToolInvocationUpdate {
            if (status != ToolInvocationStatus.VALIDATED && status != ToolInvocationStatus.WAITING_APPROVAL) {
                return AgentToolInvocationUpdate.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "the invocation cannot be approved from $status"
                )
            }
            status = ToolInvocationStatus.APPROVED
            return AgentToolInvocationUpdate.Updated
        }

        override suspend fun markRunning(
            invocationId: String,
            atMillis: Long
        ): AgentToolInvocationUpdate = AgentToolInvocationUpdate.Rejected(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "the rolled-back approval must never reach the execution gate"
        )

        override suspend fun markTerminal(
            invocationId: String,
            status: ToolInvocationStatus,
            possibleSideEffects: Boolean,
            resultRef: String?,
            atMillis: Long
        ): AgentToolInvocationUpdate = AgentToolInvocationUpdate.Rejected(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "the rolled-back approval must never produce a tool result"
        )

        override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord =
            AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "the fixture starts from a validated invocation"
            )

        override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord =
            AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "the fixture starts from a validated invocation"
            )

        override suspend fun markWaitingApproval(
            invocationId: String,
            atMillis: Long
        ): AgentToolInvocationUpdate = AgentToolInvocationUpdate.Rejected(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "the fixture starts from a validated invocation"
        )

        override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? = null

        private fun invocation(invocationId: String, status: ToolInvocationStatus): ToolInvocation =
            ToolInvocation(
                invocationId = invocationId,
                runId = "run-1",
                stepId = "step-1",
                callOrdinal = 0,
                toolKey = "workspace.read_text",
                toolVersion = "1.0.0",
                schemaHash = "schema-hash",
                frozenArgumentsRef = "memory:frozen:$invocationId",
                argumentsHash = "a".repeat(64),
                resourceFingerprint = "resource-fingerprint",
                status = status,
                idempotencyKey = OUTBOX_KEY,
                resultRef = null,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L
            )
    }

    private companion object {
        const val INVOCATION_ID = "invocation-1"

        /** The production key is derived from the invocation id, never from another id field. */
        val OUTBOX_KEY: String = AgentDispatchOutboxEntry.keyFor(INVOCATION_ID)
    }
}
