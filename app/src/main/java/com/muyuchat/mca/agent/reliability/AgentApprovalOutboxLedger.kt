package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchEnqueueResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import kotlinx.coroutines.CancellationException

/**
 * Approval-to-outbox adapter for N03.
 *
 * The owner calls [markApproved] inside its approval transaction (`prepareApprovalResume`). This
 * decorator forwards every ledger operation unchanged and, after a successful approval, writes the
 * durable dispatch row. In production the store is the Room-backed
 * [com.muyuchat.mca.agent.data.RoomAgentDispatchOutboxStore], so the approval CAS, the invocation
 * APPROVED state and the outbox row commit in the same Room transaction and a rollback leaves no
 * row. A test/harness that injects the legacy file store keeps the old caveat: the row is written
 * inside the transaction block but is not co-transactional with Room, so the dispatcher revalidates
 * the authoritative invocation row and the startup reconciler expires a row whose approval rolled
 * back.
 *
 * The other write path closes the asynchronous receipt: after the execution pipeline durably marks
 * the invocation terminal, the matching outbox row is settled from that fact. A crash between the
 * two writes leaves a `DISPATCHING` row, which the startup reconciler settles from the ledger.
 * Settlement is best effort here because the invocation ledger is authoritative; a store failure
 * must not roll back a completed tool call.
 */
class AgentApprovalOutboxLedger(
    private val delegate: AgentToolInvocationLedger,
    private val outbox: AgentDispatchOutboxStore
) : AgentToolInvocationLedger {

    override suspend fun markApproved(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate {
        val updated = delegate.markApproved(invocationId, atMillis)
        if (updated !is AgentToolInvocationUpdate.Updated) return updated
        val invocation = delegate.readInvocation(invocationId)
            ?: throw AgentJournalRejectedException(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "The approved invocation '$invocationId' disappeared before its dispatch row was written."
            )
        if (invocation.status != ToolInvocationStatus.APPROVED) {
            throw AgentJournalRejectedException(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The invocation '$invocationId' is not durably APPROVED; no dispatch row was written."
            )
        }
        val entry = AgentDispatchOutboxEntry.approved(invocation, atMillis)
        when (val enqueued = outbox.enqueue(entry)) {
            is AgentDispatchEnqueueResult.Recorded -> Unit
            is AgentDispatchEnqueueResult.Rejected -> throw AgentJournalRejectedException(
                enqueued.errorCode,
                enqueued.safeMessage
            )
        }
        return updated
    }

    override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord =
        delegate.recordProposed(proposal)

    override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord =
        delegate.recordFrozen(frozen)

    override suspend fun markWaitingApproval(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = delegate.markWaitingApproval(invocationId, atMillis)

    override suspend fun markRunning(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = delegate.markRunning(invocationId, atMillis)

    override suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate {
        val updated = delegate.markTerminal(
            invocationId = invocationId,
            status = status,
            possibleSideEffects = possibleSideEffects,
            resultRef = resultRef,
            atMillis = atMillis
        )
        if (updated !is AgentToolInvocationUpdate.Updated) return updated
        settleOutboxFromTerminal(invocationId, status, atMillis)
        return updated
    }

    override suspend fun readInvocation(invocationId: String): ToolInvocation? =
        delegate.readInvocation(invocationId)

    override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? =
        delegate.readFrozenInvocation(invocationId)

    /**
     * Closes the durable dispatch row from the authoritative terminal invocation fact.
     *
     * `UNKNOWN` maps to `NEEDS_REVIEW` instead of `SETTLED`: a possible side effect must never be
     * rendered as a settled/successful dispatch. A CAS loss or store failure is left to the startup
     * reconciler; it must not break the tool result that is already durable.
     */
    private suspend fun settleOutboxFromTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        atMillis: Long
    ) {
        try {
            val key = AgentDispatchOutboxEntry.keyFor(invocationId)
            val entry = outbox.read(key) ?: return
            if (entry.status.isTerminal) return
            val needsReview = status == ToolInvocationStatus.UNKNOWN
            val result = outbox.settle(
                idempotencyKey = key,
                expectedRevision = entry.revision,
                status = if (needsReview) {
                    AgentDispatchOutboxStatus.NEEDS_REVIEW
                } else {
                    AgentDispatchOutboxStatus.SETTLED
                },
                invocationStatus = if (needsReview) null else status,
                errorCode = if (needsReview) "invocation_unknown" else null,
                nowMillis = atMillis
            )
            if (result !is AgentDispatchUpdateResult.Updated) {
                // Another writer already settled it (startup reconciler / duplicate receipt).
                return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort: the terminal invocation row stays authoritative and the startup
            // reconciler will align the outbox on the next fence.
        }
    }
}
