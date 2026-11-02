package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationBasis
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationPort
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationRequest
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationResult
import com.muyuchat.core.agent.runtime.run.AgentToolDenialResult
import com.muyuchat.core.agent.runtime.run.AuthorizedToolCommand
import com.muyuchat.mca.agent.security.AgentApprovalBinding
import com.muyuchat.mca.agent.security.AgentApprovalResolveOutcome
import com.muyuchat.mca.agent.security.AgentExecutionAuthorization
import com.muyuchat.mca.agent.security.AgentPermissionCandidate
import com.muyuchat.mca.agent.security.AgentPermissionPolicy
import com.muyuchat.mca.agent.security.AgentResolvedToolScope
import com.muyuchat.mca.agent.security.AgentWorkspaceReadGrant

/**
 * One-shot authorization adapter for the run owner.
 *
 * It reconstructs the exact frozen candidate from durable records only — the recorded invocation
 * row plus the content-addressed frozen command/descriptor/scope payload — and revalidates it through
 * [com.muyuchat.mca.agent.security.AgentPermissionBroker.authorize], the single
 * `PENDING -> CONSUMED` consume point. A missing/corrupt payload, a run-epoch/policy mismatch,
 * a changed binding or a stale catalog descriptor all fail closed; no executor can be reached.
 */
class BrokerAgentToolAuthorization(
    private val invocations: AgentToolInvocationLedger,
    private val broker: com.muyuchat.mca.agent.security.AgentPermissionBroker
) : AgentToolAuthorizationPort {

    override suspend fun authorize(
        request: AgentToolAuthorizationRequest
    ): AgentToolAuthorizationResult {
        val invocation = invocations.readInvocation(request.invocationId)
            ?: return refuse(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen tool invocation is not recorded."
            )
        if (request.run.state != com.muyuchat.core.agent.contract.AgentRunState.WAITING_APPROVAL) {
            return refuse(
                request,
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The run is not waiting for approval."
            )
        }
        if (invocation.runId != request.run.runId) {
            return refuse(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The invocation belongs to a different run."
            )
        }
        if (invocation.status != ToolInvocationStatus.WAITING_APPROVAL) {
            return refuse(
                request,
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The invocation is not waiting for approval."
            )
        }
        val frozen = invocations.readFrozenInvocation(request.invocationId)
            ?: return refuse(
                request,
                AgentErrorCodes.STORAGE_CORRUPTION,
                "The frozen tool command material is missing or corrupt."
            )
        if (frozen.command.runId != request.run.runId) {
            return refuse(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen command belongs to a different run."
            )
        }
        val expectedTaskRoot = "${request.task.workspaceId}/tasks/${request.task.taskId}"
        if (frozen.scope.taskRoot != expectedTaskRoot) {
            return refuse(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen resource scope belongs to a different task."
            )
        }
        val scope = try {
            AgentResolvedToolScope(
                taskRoot = frozen.scope.taskRoot,
                logicalPaths = frozen.scope.logicalPaths,
                expectedOldHash = frozen.scope.expectedOldHash,
                networkDestinations = frozen.scope.networkDestinations,
                // Persisted typed-scope document (N02c); null for legacy workspace invocations.
                typedPreconditionJson = frozen.scope.typedPreconditionJson
            )
        } catch (_: IllegalArgumentException) {
            return refuse(
                request,
                AgentErrorCodes.STORAGE_CORRUPTION,
                "The frozen resource scope is not canonical."
            )
        }
        val candidate = try {
            AgentPermissionCandidate(
                command = frozen.command,
                descriptor = frozen.descriptor,
                scope = scope
            )
        } catch (_: IllegalArgumentException) {
            return refuse(
                request,
                AgentErrorCodes.STORAGE_CORRUPTION,
                "The frozen invocation failed candidate verification."
            )
        }
        val policy = AgentPermissionPolicy(
            runEpoch = request.run.epoch,
            policyVersion = request.run.policy.policyVersion,
            networkPolicy = request.run.policy.networkPolicy
        )
        val grant = AgentWorkspaceReadGrant.v1(request.task)
        return when (
            val authorization = broker.authorize(
                candidate = candidate,
                approvalId = request.approvalId,
                policy = policy,
                grant = grant,
                nowMillis = request.nowMillis
            )
        ) {
            is AgentExecutionAuthorization.Authorized -> AgentToolAuthorizationResult.Authorized(
                AuthorizedToolCommand(
                    command = authorization.command,
                    approvalId = authorization.approvalId,
                    basis = AgentToolAuthorizationBasis.APPROVED_ONCE
                )
            )
            is AgentExecutionAuthorization.Denied -> {
                broker.invalidateApproval(request.approvalId, request.nowMillis)
                AgentToolAuthorizationResult.NotExecuted(
                    errorCode = authorization.error.code,
                    safeMessage = authorization.error.safeMessage,
                    toolStatus = authorization.toolStatus
                )
            }
        }
    }

    override suspend fun deny(request: AgentToolAuthorizationRequest): AgentToolDenialResult {
        val invocation = invocations.readInvocation(request.invocationId)
            ?: return refuseDenial(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen tool invocation is not recorded."
            )
        if (request.run.state != com.muyuchat.core.agent.contract.AgentRunState.WAITING_APPROVAL) {
            return refuseDenial(
                request,
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The run is not waiting for approval."
            )
        }
        if (invocation.runId != request.run.runId) {
            return refuseDenial(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The invocation belongs to a different run."
            )
        }
        if (invocation.status != ToolInvocationStatus.WAITING_APPROVAL) {
            return refuseDenial(
                request,
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The invocation is not waiting for approval."
            )
        }
        val binding = try {
            AgentApprovalBinding(
                invocationId = invocation.invocationId,
                argumentsHash = invocation.argumentsHash,
                toolSchemaHash = invocation.schemaHash,
                resourceFingerprint = invocation.resourceFingerprint,
                runEpoch = request.run.epoch,
                policyVersion = request.run.policy.policyVersion
            )
        } catch (_: IllegalArgumentException) {
            return refuseDenial(
                request,
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The approval binding is not reconstructible."
            )
        }
        return when (val outcome = broker.denyPending(request.approvalId, binding, request.nowMillis)) {
            is AgentApprovalResolveOutcome.Resolved ->
                if (outcome.approval.status ==
                    com.muyuchat.core.agent.contract.AgentApprovalStatus.DENIED
                ) {
                    AgentToolDenialResult.Denied("The user denied this tool invocation.")
                } else {
                    AgentToolDenialResult.NotExecuted(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "The approval could not be recorded as denied."
                    )
                }
            is AgentApprovalResolveOutcome.AlreadyResolved ->
                if (outcome.approval.status ==
                    com.muyuchat.core.agent.contract.AgentApprovalStatus.DENIED
                ) {
                    AgentToolDenialResult.Denied("The user denied this tool invocation.")
                } else {
                    AgentToolDenialResult.NotExecuted(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "The approval was already consumed."
                    )
                }
            is AgentApprovalResolveOutcome.Expired -> AgentToolDenialResult.NotExecuted(
                AgentErrorCodes.PERMISSION_EXPIRED,
                "The approval expired before it was resolved."
            )
            is AgentApprovalResolveOutcome.BindingMismatch -> {
                broker.invalidateApproval(request.approvalId, request.nowMillis)
                AgentToolDenialResult.NotExecuted(
                    AgentErrorCodes.PERMISSION_ARGUMENTS_CHANGED,
                    "The approval is not bound to the frozen invocation."
                )
            }
            AgentApprovalResolveOutcome.NotFound -> AgentToolDenialResult.NotExecuted(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "The approval was not found."
            )
            is AgentApprovalResolveOutcome.Rejected -> AgentToolDenialResult.NotExecuted(
                outcome.error.code,
                outcome.error.safeMessage
            )
        }
    }

    /**
     * Fail-closed refusal that also burns the still-PENDING approval, so a corrupt/stale request can
     * never leave a reusable prompt behind. Invalidating an already-resolved approval is a no-op.
     */
    private suspend fun refuseDenial(
        request: AgentToolAuthorizationRequest,
        code: String,
        message: String
    ): AgentToolDenialResult {
        broker.invalidateApproval(request.approvalId, request.nowMillis)
        return AgentToolDenialResult.NotExecuted(code, message)
    }

    private suspend fun refuse(
        request: AgentToolAuthorizationRequest,
        code: String,
        message: String
    ): AgentToolAuthorizationResult {
        broker.invalidateApproval(request.approvalId, request.nowMillis)
        return notExecuted(code, message)
    }

    private fun notExecuted(
        code: String,
        message: String,
        status: AgentToolResultStatus = AgentToolResultStatus.NOT_EXECUTED
    ): AgentToolAuthorizationResult = AgentToolAuthorizationResult.NotExecuted(
        errorCode = code,
        safeMessage = message,
        toolStatus = status
    )
}
