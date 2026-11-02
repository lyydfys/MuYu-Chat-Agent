package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.FrozenToolCommand

/**
 * Exact one-shot approval binding (architecture 7.1 / 8.2). A different invocation, arguments hash,
 * schema, resource precondition, run epoch or policy version can never consume the same approval.
 */
data class AgentApprovalBinding(
    val invocationId: String,
    val argumentsHash: String,
    val toolSchemaHash: String,
    val resourceFingerprint: String,
    val runEpoch: Long,
    val policyVersion: Long
) {
    init {
        require(invocationId.isNotBlank()) { "invocationId must not be blank" }
        require(argumentsHash.isNotBlank()) { "argumentsHash must not be blank" }
        require(toolSchemaHash.isNotBlank()) { "toolSchemaHash must not be blank" }
        require(resourceFingerprint.isNotBlank()) { "resourceFingerprint must not be blank" }
        require(runEpoch > 0L) { "runEpoch must be positive" }
        require(policyVersion > 0L) { "policyVersion must be positive" }
    }

    fun matches(approval: AgentApproval): Boolean =
        approval.invocationId == invocationId &&
            approval.argumentsHash == argumentsHash &&
            approval.toolSchemaHash == toolSchemaHash &&
            approval.resourceFingerprint == resourceFingerprint &&
            approval.runEpoch == runEpoch &&
            approval.policyVersion == policyVersion

    companion object {
        fun of(command: FrozenToolCommand): AgentApprovalBinding = AgentApprovalBinding(
            invocationId = command.invocationId,
            argumentsHash = command.argumentsHash,
            toolSchemaHash = command.schemaHash,
            resourceFingerprint = command.resourceFingerprint,
            runEpoch = command.runEpoch,
            policyVersion = command.policyVersion
        )
    }
}

sealed interface AgentApprovalInsertResult {
    data class Inserted(val approval: AgentApproval) : AgentApprovalInsertResult

    /** At most one valid pending approval exists per invocation; replaying creation is safe. */
    data class AlreadyPending(val approval: AgentApproval) : AgentApprovalInsertResult

    data class Rejected(val error: AgentSecurityError) : AgentApprovalInsertResult
}

sealed interface AgentApprovalResolveOutcome {
    /** CAS PENDING -> APPROVED or DENIED succeeded; [approval] carries the new status. */
    data class Resolved(val approval: AgentApproval) : AgentApprovalResolveOutcome

    /** A repeated click/command; no state changed, never executes twice. */
    data class AlreadyResolved(val approval: AgentApproval) : AgentApprovalResolveOutcome

    data class Expired(val approval: AgentApproval) : AgentApprovalResolveOutcome

    data class BindingMismatch(val approval: AgentApproval) : AgentApprovalResolveOutcome

    data object NotFound : AgentApprovalResolveOutcome

    data class Rejected(val error: AgentSecurityError) : AgentApprovalResolveOutcome
}

sealed interface AgentApprovalConsumeOutcome {
    data class Consumed(val approval: AgentApproval, val consumedAtMillis: Long) : AgentApprovalConsumeOutcome

    data class AlreadyConsumed(val approval: AgentApproval) : AgentApprovalConsumeOutcome

    data class Expired(val approval: AgentApproval) : AgentApprovalConsumeOutcome

    data class NotApproved(val approval: AgentApproval) : AgentApprovalConsumeOutcome

    data class BindingMismatch(val approval: AgentApproval) : AgentApprovalConsumeOutcome

    data object NotFound : AgentApprovalConsumeOutcome

    data class Rejected(val error: AgentSecurityError) : AgentApprovalConsumeOutcome
}

/**
 * Narrow persistence port for K07 approvals. The Room/journal adapter is provided by K05/Lead.
 *
 * Contract for the adapter (must be a single atomic transaction or single conditional UPDATE):
 * - `insertPending` keeps at most one valid pending approval per invocation;
 * - `resolve` CAS: PENDING -> APPROVED/DENIED only; an expired PENDING first becomes EXPIRED;
 * - `consumePending` is the *security-critical execution gate*: one conditional statement that
 *   accepts **only** status PENDING, compares the full [AgentApprovalBinding], the run epoch, the
 *   policy version and the expiry inside the same statement, and returns
 *   [AgentApprovalConsumeOutcome.Consumed] exactly once for concurrent callers;
 * - `consume` CAS: APPROVED -> CONSUMED only (legacy two-step flow; not used by the owner resume);
 * - every method re-reads status/expiry inside the atomic operation, never before it.
 *
 * The broker never holds a lock while awaiting these calls.
 */
interface AgentApprovalStorePort {
    suspend fun insertPending(approval: AgentApproval): AgentApprovalInsertResult

    suspend fun resolve(
        approvalId: String,
        binding: AgentApprovalBinding,
        decision: AgentApprovalDecision,
        nowMillis: Long
    ): AgentApprovalResolveOutcome

    /**
     * One-shot execution gate. Exactly one concurrent caller observes
     * [AgentApprovalConsumeOutcome.Consumed]; every other caller observes
     * `AlreadyConsumed`/`NotApproved`/`Expired`/`BindingMismatch`. A PENDING approval that does not
     * fully match the binding, epoch, policy version or the current time is never consumed.
     */
    suspend fun consumePending(
        approvalId: String,
        binding: AgentApprovalBinding,
        runEpoch: Long,
        policyVersion: Long,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome

    suspend fun consume(
        approvalId: String,
        binding: AgentApprovalBinding,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome

    suspend fun invalidate(approvalId: String, nowMillis: Long): Boolean

    suspend fun read(approvalId: String): AgentApproval?

    /**
     * Fails every PENDING approval of one run closed. Used when the tool/Skill/MCP policy snapshot
     * changes: a stale approval must never execute against a newer catalog. Default is a no-op so
     * narrow test fakes remain source-compatible; the Room adapter implements it atomically.
     */
    suspend fun invalidatePendingForRun(runId: String): Int = 0
}

/** Contract helper: a pending approval is still usable only before its absolute expiry. */
internal fun AgentApproval.isExpiredAt(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
