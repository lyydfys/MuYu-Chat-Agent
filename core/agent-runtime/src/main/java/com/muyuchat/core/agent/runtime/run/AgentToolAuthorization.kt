package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand

/** Why an exact frozen command may be handed to an executor. */
enum class AgentToolAuthorizationBasis {
    /** Read-only workspace call inside the task grant; no one-shot approval was needed. */
    WORKSPACE_READ_GRANT,

    /** Fresh `ALLOW_ONCE` approval atomically consumed exactly once. */
    APPROVED_ONCE
}

/**
 * The only value an executor may be given. It is produced either by the trusted freeze/decide seam
 * (workspace-read grant basis) or by the owner's one-shot [AgentToolAuthorizationPort]; it is never
 * produced from a stored command that merely exists in memory.
 */
data class AuthorizedToolCommand(
    val command: FrozenToolCommand,
    val approvalId: String?,
    val basis: AgentToolAuthorizationBasis
) {
    init {
        approvalId?.let { AgentContract.requireId(it, "approvalId") }
        if (basis == AgentToolAuthorizationBasis.APPROVED_ONCE) {
            AgentContract.requireNotBlank(approvalId ?: "", "approvalId")
        }
    }

    override fun toString(): String =
        "AuthorizedToolCommand(toolKey=${command.toolKey}, invocationId=${command.invocationId}, " +
            "basis=$basis)"
}

data class AgentToolAuthorizationRequest(
    val run: AgentRun,
    val task: AgentTask,
    val invocationId: String,
    val approvalId: String,
    val nowMillis: Long
) {
    init {
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(approvalId, "approvalId")
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
    }
}

sealed interface AgentToolAuthorizationResult {
    data class Authorized(val authorization: AuthorizedToolCommand) : AgentToolAuthorizationResult

    /** No executor call is allowed; [toolStatus] is the paired observation status. */
    data class NotExecuted(
        val errorCode: String,
        val safeMessage: String,
        val toolStatus: AgentToolResultStatus = AgentToolResultStatus.NOT_EXECUTED
    ) : AgentToolAuthorizationResult
}

sealed interface AgentToolDenialResult {
    data class Denied(val safeMessage: String) : AgentToolDenialResult

    data class NotExecuted(
        val errorCode: String,
        val safeMessage: String
    ) : AgentToolDenialResult
}

/**
 * Owner-facing one-shot authorization seam (K07 adapter in app composition).
 *
 * `authorize` must reconstruct the exact frozen candidate from durable invocation/material state,
 * revalidate policy/epoch/expiry/binding, and atomically consume the `PENDING` approval. Only its
 * [AgentToolAuthorizationResult.Authorized] may reach an executor.
 */
interface AgentToolAuthorizationPort {
    suspend fun authorize(request: AgentToolAuthorizationRequest): AgentToolAuthorizationResult

    /** Applies the user's DENY to a still-`PENDING` approval without authorizing execution. */
    suspend fun deny(request: AgentToolAuthorizationRequest): AgentToolDenialResult
}

/**
 * Fail-closed default: an owner with no authorization seam never authorizes or denies execution.
 * Production composition must inject the real broker-backed adapter.
 */
object NoAgentToolAuthorization : AgentToolAuthorizationPort {
    override suspend fun authorize(
        request: AgentToolAuthorizationRequest
    ): AgentToolAuthorizationResult = AgentToolAuthorizationResult.NotExecuted(
        AgentErrorCodes.INTERNAL_INVARIANT,
        "The tool authorization seam is not configured."
    )

    override suspend fun deny(
        request: AgentToolAuthorizationRequest
    ): AgentToolDenialResult = AgentToolDenialResult.NotExecuted(
        AgentErrorCodes.INTERNAL_INVARIANT,
        "The tool authorization seam is not configured."
    )
}
