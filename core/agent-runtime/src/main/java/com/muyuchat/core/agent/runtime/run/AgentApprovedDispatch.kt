package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * The exact in-memory material authorized by one approval decision (N03).
 *
 * [resume] carries the one-shot [AgentResumeToolDecision.Authorized] produced inside the approval
 * transaction. It exists only in process memory: after a restart it is gone, and the stored frozen
 * command must never be promoted into a new authorization.
 */
data class AgentApprovedDispatch(
    val invocationId: String,
    val runId: String,
    val runEpoch: Long,
    val resume: AgentLoopResume
) {
    init {
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(runId, "runId")
        require(runEpoch > 0L) { "runEpoch must be positive" }
        require(resume.toolDecision is AgentResumeToolDecision.Authorized) {
            "an approved dispatch must carry an Authorized tool decision"
        }
    }

    val authorization: AuthorizedToolCommand
        get() = (resume.toolDecision as AgentResumeToolDecision.Authorized).authorization
}

/** Outcome of offering an approved resume to the outbox-driven consumer. */
sealed interface AgentApprovedDispatchOffer {
    /** A live consumer accepted the hand-off; it will call the owner exactly once. */
    data object HandedOff : AgentApprovedDispatchOffer

    /** No consumer is active; the owner must fall back to its legacy in-process launch. */
    data class NotAccepted(val errorCode: String, val safeMessage: String) : AgentApprovedDispatchOffer
}

/**
 * Offer seam between the owner (approval completion) and the outbox background loop.
 *
 * Implementations must only return [AgentApprovedDispatchOffer.HandedOff] when a live consumer has
 * durably taken responsibility for the dispatch (published hand-off + accepted wake). A `false`
 * hand-off must leave the in-memory authorization either claimable by the owner fallback or unused,
 * never duplicated.
 */
fun interface AgentApprovedDispatchSink {
    suspend fun offer(dispatch: AgentApprovedDispatch): AgentApprovedDispatchOffer
}

/**
 * Single-consumer hand-off registry. `claim` removes the entry, so duplicate outbox wakes or
 * duplicate dispatcher passes can never launch the same approval twice.
 */
interface AgentApprovedDispatchHandoff {
    fun publish(dispatch: AgentApprovedDispatch)

    fun claim(invocationId: String): AgentApprovedDispatch?

    fun discard(invocationId: String): Boolean
}

class InMemoryAgentApprovedDispatchHandoff : AgentApprovedDispatchHandoff {
    private val pending = ConcurrentHashMap<String, AgentApprovedDispatch>()

    override fun publish(dispatch: AgentApprovedDispatch) {
        // The first published resume wins: a second approval decision for the same invocation is a
        // protocol violation and must not replace the authorized material.
        pending.putIfAbsent(dispatch.invocationId, dispatch)
    }

    override fun claim(invocationId: String): AgentApprovedDispatch? = pending.remove(invocationId)

    override fun discard(invocationId: String): Boolean = pending.remove(invocationId) != null
}

/**
 * Serialized outbox entry point on the owner.
 *
 * It consumes the exact in-memory authorization and starts the *existing* run loop; it never
 * consumes an approval, never builds an executor and never reconstructs authorization from storage.
 * Any mismatch returns a typed [AgentDispatchExecution.Refused] with zero side effects.
 */
internal suspend fun AgentRunOwner.dispatchApprovedLocked(
    entry: AgentDispatchOutboxEntry
): AgentDispatchExecution {
    val handoff = dispatchHandoff
        ?: return refused(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "No approved-dispatch hand-off is configured."
        )
    val dispatch = handoff.claim(entry.invocationId)
        ?: return refused(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "No in-memory authorization is available for this invocation."
        )
    val command = dispatch.authorization.command
    if (command.invocationId != entry.invocationId ||
        command.runId != entry.runId ||
        command.stepId != entry.stepId ||
        command.callOrdinal != entry.callOrdinal ||
        command.toolKey != entry.toolKey ||
        command.toolVersion != entry.toolVersion
    ) {
        return refused(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "The outbox row does not match the authorized command."
        )
    }
    val snapshot = journal.readRun(entry.runId)
        ?: return refused(AgentErrorCodes.COMMAND_NOT_FOUND, "The run no longer exists.")
    val run = snapshot.run
    if (run.state != AgentRunState.RUNNING) {
        return refused(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            "The run is not RUNNING; the approval resume cannot start."
        )
    }
    if (run.epoch != dispatch.runEpoch) {
        return refused(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "The approval belongs to a stale run epoch."
        )
    }
    val slot = active
    if (slot != null && slot.runId == run.runId) {
        return refused(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "The run already owns the execution slot; the approval resume cannot start twice."
        )
    }
    // S2: a parked run released the admission slot when it entered WAITING_APPROVAL, so the slot is
    // either free or owned by a newer run. The exact authorized resume is scheduled through the
    // single admission path; promotion re-checks the epoch/state before any execution, so the
    // resumed run can never execute beside another run.
    pendingResumes[run.runId] = PendingOwnerResume(
        runId = run.runId,
        epoch = dispatch.runEpoch,
        resume = dispatch.resume,
        kind = PendingResumeKind.APPROVAL,
        invocationId = entry.invocationId
    )
    schedule(run)
    return AgentDispatchExecution.HandedOff
}

private fun refused(errorCode: String, safeMessage: String): AgentDispatchExecution =
    AgentDispatchExecution.Refused(errorCode, safeMessage)
