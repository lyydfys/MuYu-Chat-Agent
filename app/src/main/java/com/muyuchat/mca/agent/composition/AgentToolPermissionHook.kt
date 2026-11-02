package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandDecision
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandHook
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandRequest
import com.muyuchat.mca.agent.security.AgentFreezeRequest
import com.muyuchat.mca.agent.security.AgentFreezeResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import com.muyuchat.mca.agent.security.AgentPermissionBroker
import com.muyuchat.mca.agent.security.AgentPermissionCandidate
import com.muyuchat.mca.agent.security.AgentPermissionDecision
import com.muyuchat.mca.agent.security.AgentPermissionPolicy
import com.muyuchat.mca.agent.security.AgentWorkspaceReadGrant
import kotlinx.coroutines.CancellationException

/**
 * K07 seam for the K03 loop: record frozen material -> freeze -> persist the frozen identity ->
 * permission decision. It never executes a tool and never derives permission from model text, MCP
 * annotations or Skill declarations.
 *
 * Ordering is security-relevant: the coordinator has already persisted a `PROPOSED` invocation, and
 * this hook records the frozen command/descriptor/scope as `VALIDATED` *before* the broker is asked
 * to create a PENDING approval. A missing ledger therefore yields a typed `Failed` decision, never a
 * dangling approval. The hook does not consume approvals; the owner handles the user click through
 * the one-shot authorization seam.
 */
class AgentToolPermissionHook(
    private val factory: AgentFrozenCommandFactory,
    private val broker: AgentPermissionBroker,
    private val invocations: AgentToolInvocationLedger,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    fun hook(): AgentFrozenCommandHook = AgentFrozenCommandHook { request ->
        freezeAndDecide(request)
    }

    private suspend fun freezeAndDecide(request: AgentFrozenCommandRequest): AgentFrozenCommandDecision {
        val now = clockMillis()
        val grant = AgentWorkspaceReadGrant.v1(request.task)
        // N02c: a tool that cannot express its resource identity as a workspace path must never be
        // frozen with the legacy pseudo-path scope. Fail closed instead of approving a fake scope.
        val typedScope = request.typedScope ?: AgentTypedResourceScopes.forRequest(request)
        if (typedScope == null && AgentTypedResourceScopes.requiresTypedScope(request.descriptor.toolKey)) {
            return AgentFrozenCommandDecision.Denied(
                errorCode = AgentErrorCodes.RESOURCE_SCOPE_OUTDATED,
                safeMessage = "The tool needs a frozen typed resource scope; re-create the task selection and approve again."
            )
        }
        val frozen = when (
            val freeze = factory.freeze(
                AgentFreezeRequest(
                    descriptor = request.descriptor,
                    rawArgumentsJson = request.normalizedArgumentsJson,
                    invocationId = request.invocationId,
                    runId = request.run.runId,
                    stepId = request.stepId,
                    callOrdinal = request.callOrdinal,
                    workspaceId = request.task.workspaceId,
                    taskId = request.task.taskId,
                    runEpoch = request.run.epoch,
                    policyVersion = request.run.policy.policyVersion,
                    nowMillis = now,
                    typedScope = typedScope,
                    providerCallId = request.providerCallId
                )
            )
        ) {
            is AgentFreezeResult.Frozen -> freeze
            is AgentFreezeResult.Rejected -> return AgentFrozenCommandDecision.Denied(
                errorCode = freeze.error.code,
                safeMessage = freeze.error.safeMessage
            )
        }
        val recorded = try {
            invocations.recordFrozen(
                AgentFrozenInvocation(
                    command = frozen.command,
                    descriptor = request.descriptor,
                    scope = AgentToolScope(
                        taskRoot = frozen.scope.taskRoot,
                        logicalPaths = frozen.scope.logicalPaths,
                        expectedOldHash = frozen.scope.expectedOldHash,
                        networkDestinations = frozen.scope.networkDestinations,
                        // Typed-scope tools (memory/device) have no workspace paths; dropping this
                        // field made AgentToolScope reject a valid frozen scope and fail the call
                        // as INTERNAL_UNEXPECTED before approval.
                        typedPreconditionJson = frozen.scope.typedPreconditionJson
                    ),
                    recordedAtMillis = now
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.INTERNAL_UNEXPECTED,
                "The runtime could not record the frozen tool command."
            )
        }
        if (recorded !is AgentToolInvocationRecord.Recorded) {
            val rejected = recorded as AgentToolInvocationRecord.Rejected
            return AgentFrozenCommandDecision.Failed(
                errorCode = rejected.errorCode,
                safeMessage = rejected.safeMessage
            )
        }
        val candidate = AgentPermissionCandidate(
            command = frozen.command,
            descriptor = request.descriptor,
            scope = frozen.scope
        )
        val policy = AgentPermissionPolicy(
            runEpoch = request.run.epoch,
            policyVersion = request.run.policy.policyVersion,
            networkPolicy = request.run.policy.networkPolicy
        )
        return when (val decision = broker.decide(candidate, policy, grant, now)) {
            is AgentPermissionDecision.AllowForTask ->
                AgentFrozenCommandDecision.ReadyToExecute(decision.command)
            is AgentPermissionDecision.NeedsApproval ->
                AgentFrozenCommandDecision.WaitingApproval(decision.command, decision.approval)
            is AgentPermissionDecision.Denied -> AgentFrozenCommandDecision.Denied(
                errorCode = decision.error.code,
                safeMessage = decision.error.safeMessage,
                possibleSideEffects = decision.toolStatus ==
                    com.muyuchat.core.agent.contract.AgentToolResultStatus.UNKNOWN
            )
        }
    }
}
