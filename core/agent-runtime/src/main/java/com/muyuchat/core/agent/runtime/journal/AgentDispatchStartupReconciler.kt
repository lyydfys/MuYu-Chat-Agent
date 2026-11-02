package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus

/**
 * Bounded startup reconciliation for the dispatch outbox (N03 / N19 / TOOL-26).
 *
 * It runs at the startup fence, before new runs/approvals are admitted, and only *aligns durable
 * facts*: it never dispatches, never calls an executor and never rewrites an invocation row.
 *
 * Why `APPROVED` becomes `NEEDS_REVIEW` here: the one-shot authorization is produced by the approval
 * command and handed over in process memory. After a process restart that hand-off is gone, so an
 * `APPROVED` invocation has no recoverable authorization. Executing it from the stored frozen command
 * would mint a second authorization and is forbidden. The matching `AgentStartupRecovery` settles the
 * run as `NEEDS_REVIEW` for the same reason.
 *
 * A `DISPATCHING` row has a recorded dispatch intent; without a terminal ledger fact the receipt is
 * unknown, so it is fenced. The periodic [AgentDispatchOutboxDispatcher] deliberately ignores
 * `DISPATCHING` rows, which lets a long-running tool finish without being mistaken for a lost dispatch.
 */
class AgentDispatchStartupReconciler(
    private val store: AgentDispatchOutboxStore,
    private val ledger: AgentToolInvocationLedger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val approvalGraceMillis: Long = DEFAULT_APPROVAL_GRACE_MILLIS
) {
    init {
        require(approvalGraceMillis >= 0L) { "approvalGraceMillis must not be negative" }
    }

    suspend fun reconcile(maxEntries: Int = DEFAULT_MAX_ENTRIES): AgentDispatchReconcileReport {
        require(maxEntries > 0) { "maxEntries must be positive" }
        val now = clock()
        val active = store.listActive(maxEntries)
        val tally = Tally()
        for (entry in active) {
            if (entry.status.isTerminal) continue
            tally.examined += 1
            when (entry.status) {
                AgentDispatchOutboxStatus.PENDING -> reconcileUndispatched(entry, now, tally)
                AgentDispatchOutboxStatus.CLAIMED -> reconcileUndispatched(entry, now, tally)
                AgentDispatchOutboxStatus.DISPATCHING -> reconcileDispatched(entry, tally)
                else -> tally.skipped += 1
            }
        }
        return tally.toReport()
    }

    private suspend fun reconcileUndispatched(
        entry: AgentDispatchOutboxEntry,
        nowMillis: Long,
        tally: Tally
    ) {
        val invocation = ledger.readInvocation(entry.invocationId)
        when {
            invocation == null -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_missing",
                tally
            )

            !sameIdentity(invocation, entry) -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.FAILED,
                null,
                "entry_identity_mismatch",
                tally
            )

            invocation.status.isTerminal -> settleTerminal(entry.idempotencyKey, invocation, tally)

            invocation.status == ToolInvocationStatus.RUNNING -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_running",
                tally
            )

            invocation.status == ToolInvocationStatus.APPROVED -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "authorization_not_recoverable",
                tally
            )

            nowMillis - entry.approvedAtMillis <= approvalGraceMillis -> tally.deferred += 1

            else -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.EXPIRED,
                null,
                "approval_not_committed",
                tally
            )
        }
    }

    private suspend fun reconcileDispatched(entry: AgentDispatchOutboxEntry, tally: Tally) {
        val invocation = ledger.readInvocation(entry.invocationId)
        when {
            invocation == null -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_missing",
                tally
            )

            !sameIdentity(invocation, entry) -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "entry_identity_mismatch",
                tally
            )

            invocation.status.isTerminal -> settleTerminal(entry.idempotencyKey, invocation, tally)

            invocation.status == ToolInvocationStatus.RUNNING -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "execution_result_unknown",
                tally
            )

            else -> settle(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "dispatch_receipt_unknown",
                tally
            )
        }
    }

    private suspend fun settleTerminal(
        idempotencyKey: String,
        invocation: ToolInvocation,
        tally: Tally
    ) {
        if (invocation.status == ToolInvocationStatus.UNKNOWN) {
            settle(
                idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_unknown",
                tally
            )
        } else {
            settle(
                idempotencyKey,
                AgentDispatchOutboxStatus.SETTLED,
                invocation.status,
                null,
                tally
            )
        }
    }

    private fun sameIdentity(invocation: ToolInvocation, entry: AgentDispatchOutboxEntry): Boolean =
        invocation.invocationId == entry.invocationId &&
            invocation.runId == entry.runId &&
            invocation.stepId == entry.stepId &&
            invocation.callOrdinal == entry.callOrdinal &&
            invocation.toolKey == entry.toolKey &&
            invocation.toolVersion == entry.toolVersion

    private suspend fun settle(
        idempotencyKey: String,
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        tally: Tally
    ) {
        val current = store.read(idempotencyKey) ?: return
        if (current.status.isTerminal) return
        val result = store.settle(
            idempotencyKey = idempotencyKey,
            expectedRevision = current.revision,
            status = status,
            invocationStatus = invocationStatus,
            errorCode = errorCode,
            nowMillis = clock()
        )
        if (result !is AgentDispatchUpdateResult.Updated) return
        when (status) {
            AgentDispatchOutboxStatus.SETTLED -> tally.settled += 1
            AgentDispatchOutboxStatus.EXPIRED -> tally.expired += 1
            AgentDispatchOutboxStatus.FAILED -> tally.failed += 1
            AgentDispatchOutboxStatus.NEEDS_REVIEW -> tally.needsReview += 1
            else -> Unit
        }
    }

    private class Tally {
        var examined: Int = 0
        var settled: Int = 0
        var needsReview: Int = 0
        var expired: Int = 0
        var failed: Int = 0
        var deferred: Int = 0
        var skipped: Int = 0

        fun toReport(): AgentDispatchReconcileReport = AgentDispatchReconcileReport(
            examined = examined,
            settled = settled,
            needsReview = needsReview,
            expired = expired,
            failed = failed,
            deferred = deferred,
            skipped = skipped
        )
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 100
        const val DEFAULT_APPROVAL_GRACE_MILLIS: Long = 30_000L
    }
}

/** Counters for one startup reconciliation pass; every count is a durable terminal transition. */
data class AgentDispatchReconcileReport(
    val examined: Int,
    val settled: Int,
    val needsReview: Int,
    val expired: Int,
    val failed: Int,
    val deferred: Int,
    val skipped: Int
)
