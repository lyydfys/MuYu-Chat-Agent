package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Permission decision engine for K07 (architecture 8).
 *
 * Intended flow for K08/K03:
 * 1. K08/K03 freeze the call with [AgentFrozenCommandFactory] and build an
 *    [AgentPermissionCandidate] from the same current descriptor.
 * 2. K03 calls [decide]: ALLOW_FOR_TASK (read-only workspace within the task grant or a policy
 *    ALLOW), an approval to show, or a typed denial. Writes/external effects/network require a
 *    fresh approval unless the user's [ToolApprovalPolicy] says otherwise; FORBID is denied
 *    without creating a prompt.
 * 3. The user click reaches the run owner as `ResolveAgentApproval`. On ALLOW_ONCE the owner calls
 *    [authorize] (the one-shot consume); on DENY it calls [denyPending]. [resolveApproval] is the
 *    idempotent two-step helper used by tests/other callers and never grants execution by itself.
 * 4. [authorize] is the only value that may produce
 *    [AgentExecutionAuthorization.Authorized]; it is produced by one atomic one-shot
 *    `PENDING -> CONSUMED` CAS. Expired/invalidated/replayed approvals map to NOT_EXECUTED, user
 *    denials map to DENIED.
 *
 * **Wiring status (P0 closure):** the runtime now persists a `PROPOSED` invocation before freeze,
 * records the frozen descriptor/scope, and the owner resume reconstructs the candidate from that
 * durable material and calls [authorize] before any executor. [authorize] is the single
 * `PENDING -> CONSUMED` consume point; the owner never passes a stored command to the loop. If the
 * invocation ledger or this broker is not wired, the resume is refused (fail closed), never
 * blindly approved.
 *
 * Approval TTL is 5 minutes from creation and is additionally bounded by the frozen command's own
 * expiry. Any schema/tool, policy-version, run-epoch, offline-policy or revoked-grant change
 * invalidates the pending approval; the caller must then refreeze and ask again.
 *
 * Permission is derived only from typed host facts. There is deliberately no parameter that accepts
 * model text, MCP annotations, Skill allowed-tools or tool-declared hints.
 */
class AgentPermissionBroker(
    private val approvalStore: AgentApprovalStorePort,
    private val approvalIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val principal: String = LOCAL_PRINCIPAL
) {
    init {
        AgentContract.requireNotBlank(principal, "principal")
    }

    private val denialLoop = DenialLoopGuard()

    suspend fun decide(
        candidate: AgentPermissionCandidate,
        policy: AgentPermissionPolicy,
        grant: AgentWorkspaceReadGrant?,
        nowMillis: Long,
        approvalPolicy: ToolApprovalPolicy? = null
    ): AgentPermissionDecision {
        revalidate(candidate, policy, grant, nowMillis)?.let { return denied(it) }
        if (approvalPolicy != null) {
            // User policy is evaluated before the workspace-read shortcut so a FORBID exception can
            // also stop a read the task grant would otherwise cover. Null keeps the historical
            // behavior (no auto-allow), which is what every test/harness without a policy sees.
            when (approvalPolicy.decide(candidate.descriptor.toolKey, candidate.descriptor.sideEffect)) {
                ToolApprovalMode.FORBID -> return denied(AgentSecurityErrors.permissionForbidden())
                ToolApprovalMode.ALLOW ->
                    return AgentPermissionDecision.AllowForTask(
                        candidate.command,
                        AgentPermissionBasis.POLICY_ALLOW
                    )
                ToolApprovalMode.ASK -> Unit
            }
        }
        if (candidate.descriptor.isWorkspaceRead() && grant != null && grant.covers(candidate.scope)) {
            return AgentPermissionDecision.AllowForTask(candidate.command, AgentPermissionBasis.WORKSPACE_READ_GRANT)
        }
        return insertOrReplay(candidate, nowMillis)
    }

    /** Idempotent approval creation. A replay of the same binding returns the same pending record. */
    private suspend fun insertOrReplay(
        candidate: AgentPermissionCandidate,
        nowMillis: Long
    ): AgentPermissionDecision {
        val draft = draftApproval(candidate, nowMillis)
        return when (val outcome = approvalStore.insertPending(draft)) {
            is AgentApprovalInsertResult.Inserted ->
                AgentPermissionDecision.NeedsApproval(candidate.command, candidate.descriptor, outcome.approval)
            is AgentApprovalInsertResult.AlreadyPending -> {
                val binding = AgentApprovalBinding.of(candidate.command)
                when {
                    !binding.matches(outcome.approval) -> denied(
                        AgentSecurityErrors.internalInvariant(
                            "The invocation already has a pending approval for different frozen arguments."
                        )
                    )
                    outcome.approval.isExpiredAt(nowMillis) -> {
                        // The frozen command can outlive its 5-minute approval; replace the stale row.
                        approvalStore.invalidate(outcome.approval.approvalId, nowMillis)
                        when (val retry = approvalStore.insertPending(draftApproval(candidate, nowMillis))) {
                            is AgentApprovalInsertResult.Inserted ->
                                AgentPermissionDecision.NeedsApproval(candidate.command, candidate.descriptor, retry.approval)
                            is AgentApprovalInsertResult.AlreadyPending -> denied(AgentSecurityErrors.permissionExpired())
                            is AgentApprovalInsertResult.Rejected -> denied(retry.error)
                        }
                    }
                    outcome.approval.status != AgentApprovalStatus.PENDING -> denied(
                        AgentSecurityErrors.internalInvariant(
                            "The invocation already has a resolved approval; a new call needs a new invocation."
                        )
                    )
                    else ->
                        AgentPermissionDecision.NeedsApproval(candidate.command, candidate.descriptor, outcome.approval)
                }
            }
            is AgentApprovalInsertResult.Rejected -> denied(outcome.error)
        }
    }

    suspend fun resolveApproval(
        candidate: AgentPermissionCandidate,
        approvalId: String,
        decision: AgentApprovalDecision,
        policy: AgentPermissionPolicy,
        grant: AgentWorkspaceReadGrant?,
        nowMillis: Long
    ): AgentApprovalResolutionResult {
        if (!AgentFrozenIntegrity.matches(candidate.command, candidate.descriptor, candidate.scope)) {
            return AgentApprovalResolutionResult.NotExecuted(
                AgentSecurityErrors.internalInvariant("The frozen command failed integrity verification.")
            )
        }
        val binding = AgentApprovalBinding.of(candidate.command)
        if (decision == AgentApprovalDecision.DENY) {
            return when (val outcome = approvalStore.resolve(approvalId, binding, decision, nowMillis)) {
                is AgentApprovalResolveOutcome.Resolved -> {
                    val streak = denialLoop.record(candidate.command.runId, proposalKey(candidate))
                    AgentApprovalResolutionResult.Denied(
                        approval = outcome.approval,
                        pauseRunWith = if (streak >= DENIED_LOOP_THRESHOLD) {
                            AgentPauseReason.PERMISSION_DENIED_LOOP
                        } else {
                            null
                        }
                    )
                }
                is AgentApprovalResolveOutcome.AlreadyResolved ->
                    if (outcome.approval.status == AgentApprovalStatus.DENIED) {
                        AgentApprovalResolutionResult.Denied(outcome.approval, pauseRunWith = null, replay = true)
                    } else {
                        AgentApprovalResolutionResult.NotExecuted(AgentSecurityErrors.permissionExpired())
                    }
                is AgentApprovalResolveOutcome.Expired -> AgentApprovalResolutionResult.NotExecuted(
                    AgentSecurityErrors.permissionExpired()
                )
                is AgentApprovalResolveOutcome.BindingMismatch -> AgentApprovalResolutionResult.NotExecuted(
                    AgentSecurityErrors.argumentsChanged()
                )
                AgentApprovalResolveOutcome.NotFound -> AgentApprovalResolutionResult.NotExecuted(
                    AgentSecurityErrors.permissionExpired()
                )
                is AgentApprovalResolveOutcome.Rejected -> AgentApprovalResolutionResult.NotExecuted(outcome.error)
            }
        }

        revalidate(candidate, policy, grant, nowMillis)?.let { error ->
            approvalStore.invalidate(approvalId, nowMillis)
            return AgentApprovalResolutionResult.NotExecuted(error)
        }
        return when (val outcome = approvalStore.resolve(approvalId, binding, AgentApprovalDecision.ALLOW_ONCE, nowMillis)) {
            is AgentApprovalResolveOutcome.Resolved -> {
                denialLoop.reset(candidate.command.runId)
                AgentApprovalResolutionResult.Approved(outcome.approval)
            }
            is AgentApprovalResolveOutcome.AlreadyResolved -> when (outcome.approval.status) {
                AgentApprovalStatus.APPROVED, AgentApprovalStatus.CONSUMED -> {
                    denialLoop.reset(candidate.command.runId)
                    AgentApprovalResolutionResult.Approved(outcome.approval, replay = true)
                }
                AgentApprovalStatus.DENIED -> AgentApprovalResolutionResult.Denied(
                    outcome.approval,
                    pauseRunWith = null,
                    replay = true
                )
                else -> AgentApprovalResolutionResult.NotExecuted(AgentSecurityErrors.permissionExpired())
            }
            is AgentApprovalResolveOutcome.Expired -> AgentApprovalResolutionResult.NotExecuted(
                AgentSecurityErrors.permissionExpired()
            )
            is AgentApprovalResolveOutcome.BindingMismatch -> AgentApprovalResolutionResult.NotExecuted(
                AgentSecurityErrors.argumentsChanged()
            )
            AgentApprovalResolveOutcome.NotFound -> AgentApprovalResolutionResult.NotExecuted(
                AgentSecurityErrors.permissionExpired()
            )
            is AgentApprovalResolveOutcome.Rejected -> AgentApprovalResolutionResult.NotExecuted(outcome.error)
        }
    }

    /**
     * Explicit invalidation hook. K03 calls this when the frozen catalog snapshot detects a tool
     * version/schema change or when the owner withdraws a pending prompt; authorization would fail
     * anyway, but invalidating immediately removes the pending approval from Room/UI.
     */
    suspend fun invalidateApproval(approvalId: String, nowMillis: Long): Boolean =
        approvalStore.invalidate(approvalId, nowMillis)

    /** Applies a user DENY to a still-PENDING approval; repeated denials are idempotent. */
    suspend fun denyPending(
        approvalId: String,
        binding: AgentApprovalBinding,
        nowMillis: Long
    ): AgentApprovalResolveOutcome = approvalStore.resolve(
        approvalId = approvalId,
        binding = binding,
        decision = AgentApprovalDecision.DENY,
        nowMillis = nowMillis
    )

    /**
     * One-shot execution gate.
     *
     * It revalidates the frozen candidate against the current policy/epoch/expiry and then performs
     * the atomic `PENDING -> CONSUMED` CAS ([AgentApprovalStorePort.consumePending]). Only
     * [AgentExecutionAuthorization.Authorized] may reach an executor. A binding mismatch burns the
     * pending approval so a stale prompt cannot be retried against a changed command.
     */
    suspend fun authorize(
        candidate: AgentPermissionCandidate,
        approvalId: String,
        policy: AgentPermissionPolicy,
        grant: AgentWorkspaceReadGrant?,
        nowMillis: Long
    ): AgentExecutionAuthorization {
        revalidate(candidate, policy, grant, nowMillis)?.let { error ->
            approvalStore.invalidate(approvalId, nowMillis)
            return AgentExecutionAuthorization.Denied(error, AgentToolResultStatus.NOT_EXECUTED)
        }
        return when (
            val outcome = approvalStore.consumePending(
                approvalId = approvalId,
                binding = AgentApprovalBinding.of(candidate.command),
                runEpoch = policy.runEpoch,
                policyVersion = policy.policyVersion,
                nowMillis = nowMillis
            )
        ) {
            is AgentApprovalConsumeOutcome.Consumed -> {
                denialLoop.reset(candidate.command.runId)
                AgentExecutionAuthorization.Authorized(
                    command = candidate.command,
                    approvalId = approvalId,
                    basis = AgentPermissionBasis.APPROVED_ONCE
                )
            }
            is AgentApprovalConsumeOutcome.AlreadyConsumed -> AgentExecutionAuthorization.Denied(
                AgentSecurityErrors.permissionExpired(),
                AgentToolResultStatus.NOT_EXECUTED
            )
            is AgentApprovalConsumeOutcome.Expired -> AgentExecutionAuthorization.Denied(
                AgentSecurityErrors.permissionExpired(),
                AgentToolResultStatus.NOT_EXECUTED
            )
            is AgentApprovalConsumeOutcome.NotApproved -> when (outcome.approval.status) {
                AgentApprovalStatus.DENIED -> AgentExecutionAuthorization.Denied(
                    AgentSecurityErrors.permissionDenied(),
                    AgentToolResultStatus.DENIED
                )
                AgentApprovalStatus.INVALIDATED -> AgentExecutionAuthorization.Denied(
                    AgentSecurityErrors.scopeRevoked("The approval was invalidated before execution."),
                    AgentToolResultStatus.NOT_EXECUTED
                )
                else -> AgentExecutionAuthorization.Denied(
                    AgentSecurityErrors.permissionExpired(),
                    AgentToolResultStatus.NOT_EXECUTED
                )
            }
            is AgentApprovalConsumeOutcome.BindingMismatch -> {
                approvalStore.invalidate(approvalId, nowMillis)
                AgentExecutionAuthorization.Denied(
                    AgentSecurityErrors.argumentsChanged(),
                    AgentToolResultStatus.NOT_EXECUTED
                )
            }
            AgentApprovalConsumeOutcome.NotFound -> AgentExecutionAuthorization.Denied(
                AgentSecurityErrors.permissionExpired(),
                AgentToolResultStatus.NOT_EXECUTED
            )
            is AgentApprovalConsumeOutcome.Rejected -> AgentExecutionAuthorization.Denied(
                outcome.error,
                AgentToolResultStatus.NOT_EXECUTED
            )
        }
    }

    private fun revalidate(
        candidate: AgentPermissionCandidate,
        policy: AgentPermissionPolicy,
        grant: AgentWorkspaceReadGrant?,
        nowMillis: Long
    ): AgentSecurityError? {
        val command = candidate.command
        if (!AgentFrozenIntegrity.matches(command, candidate.descriptor, candidate.scope)) {
            return AgentSecurityErrors.internalInvariant("The frozen command failed integrity verification.")
        }
        if (command.policyVersion != policy.policyVersion) {
            return AgentSecurityErrors.scopeRevoked("The run policy version changed after this command was frozen.")
        }
        if (command.runEpoch != policy.runEpoch) {
            return AgentSecurityErrors.scopeRevoked("The run epoch changed after this command was frozen.")
        }
        if (candidate.descriptor.sideEffect == AgentToolSideEffect.NETWORK &&
            policy.networkPolicy == AgentNetworkPolicy.LOCAL_ONLY
        ) {
            return AgentSecurityErrors.policyOffline()
        }
        if (grant?.revoked == true) {
            return AgentSecurityErrors.scopeRevoked("The workspace read grant was revoked.")
        }
        if (command.expiresAtMillis <= nowMillis) {
            return AgentSecurityErrors.permissionExpired()
        }
        return null
    }

    private fun draftApproval(candidate: AgentPermissionCandidate, nowMillis: Long): AgentApproval {
        val ttlExpiry = if (nowMillis > Long.MAX_VALUE - AgentContract.APPROVAL_TTL_MILLIS) {
            Long.MAX_VALUE
        } else {
            nowMillis + AgentContract.APPROVAL_TTL_MILLIS
        }
        val expiresAtMillis = minOf(candidate.command.expiresAtMillis, ttlExpiry)
        return AgentApproval(
            approvalId = approvalIdFactory(),
            invocationId = candidate.command.invocationId,
            principal = principal,
            argumentsHash = candidate.command.argumentsHash,
            toolSchemaHash = candidate.command.schemaHash,
            resourceFingerprint = candidate.command.resourceFingerprint,
            policyVersion = candidate.command.policyVersion,
            runEpoch = candidate.command.runEpoch,
            expiresAtMillis = expiresAtMillis,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = nowMillis
        )
    }

    private fun denied(
        error: AgentSecurityError,
        toolStatus: AgentToolResultStatus = AgentToolResultStatus.NOT_EXECUTED
    ): AgentPermissionDecision = AgentPermissionDecision.Denied(error, toolStatus)

    private fun proposalKey(candidate: AgentPermissionCandidate): String =
        "${candidate.command.runId}|${candidate.command.toolKey}|" +
            "${candidate.command.argumentsHash}|${candidate.command.resourceFingerprint}"

    /** Counts consecutive *identical* denied proposals per run; resets on approval/execution. */
    private class DenialLoopGuard {
        private val states = ConcurrentHashMap<String, State>()

        fun record(runId: String, key: String): Int {
            val updated = states.compute(runId) { _, previous ->
                if (previous != null && previous.key == key) State(key, previous.count + 1) else State(key, 1)
            }
            return updated?.count ?: 1
        }

        fun reset(runId: String) {
            states.remove(runId)
        }

        private data class State(val key: String, val count: Int)
    }

    companion object {
        const val LOCAL_PRINCIPAL = "local-user"
        const val DENIED_LOOP_THRESHOLD = 2
    }
}
