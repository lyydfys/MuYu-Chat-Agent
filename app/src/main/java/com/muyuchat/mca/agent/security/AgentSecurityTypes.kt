package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRetryability

/**
 * Typed, log-safe security denial produced by this package.
 *
 * It is intentionally smaller than the contract [com.muyuchat.core.agent.contract.AgentError]:
 * the run owner attaches the run/attempt identity and correlation id when it projects this into
 * the journal, event stream or UI. [safeMessage] is a fixed schema/policy sentence and never
 * contains model arguments, file names, file contents, credentials, provider bodies or exception
 * text (architecture 11.4).
 */
data class AgentSecurityError(
    val code: String,
    val category: AgentErrorCategory,
    val retryability: AgentRetryability,
    val safeMessage: String
) {
    init {
        require(code.matches(Regex("^[A-Z][A-Z0-9_]{1,63}$"))) { "security error code is not UPPER_SNAKE_CASE" }
        require(safeMessage.isNotBlank()) { "security error safeMessage must not be blank" }
        require(safeMessage.length <= 256) { "security error safeMessage must stay short" }
        require(safeMessage.none { it == '\n' || it == '\r' }) { "security error safeMessage must be single-line" }
    }

    /** Redacted on purpose: a security error is safe to log, but the code is the only useful token. */
    override fun toString(): String = "AgentSecurityError($code)"
}

/** Single place that defines the stable, content-free denial codes used by K07. */
internal object AgentSecurityErrors {
    fun invalidArguments(detail: String): AgentSecurityError =
        error(AgentErrorCodes.TOOL_INPUT_INVALID, AgentErrorCategory.TOOL, AgentRetryability.SAFE_RETRY, detail)

    fun toolNotRegistered(): AgentSecurityError =
        error(
            AgentErrorCodes.TOOL_NOT_FOUND,
            AgentErrorCategory.TOOL,
            AgentRetryability.NEVER,
            "Tool is not registered in this run's frozen catalog."
        )

    fun policyOffline(): AgentSecurityError =
        error(
            AgentErrorCodes.POLICY_OFFLINE,
            AgentErrorCategory.POLICY,
            AgentRetryability.AFTER_USER_ACTION,
            "This run is LOCAL_ONLY; cloud and remote network access are disabled."
        )

    fun egressDenied(detail: String): AgentSecurityError =
        error(AgentErrorCodes.POLICY_EGRESS_DENIED, AgentErrorCategory.POLICY, AgentRetryability.AFTER_USER_ACTION, detail)

    fun permissionDenied(): AgentSecurityError =
        error(
            AgentErrorCodes.PERMISSION_DENIED,
            AgentErrorCategory.PERMISSION,
            AgentRetryability.AFTER_USER_ACTION,
            "The user denied this tool invocation."
        )

    /**
     * The persisted approval policy forbids this tool outright
     * ([ToolApprovalMode.FORBID]). No approval is offered, so retrying only makes sense after the
     * user changes the policy.
     */
    fun permissionForbidden(): AgentSecurityError =
        error(
            AgentErrorCodes.PERMISSION_DENIED,
            AgentErrorCategory.PERMISSION,
            AgentRetryability.NEVER,
            "This tool is forbidden by the current approval policy."
        )

    fun permissionExpired(): AgentSecurityError =
        error(
            AgentErrorCodes.PERMISSION_EXPIRED,
            AgentErrorCategory.PERMISSION,
            AgentRetryability.AFTER_USER_ACTION,
            "The one-shot approval is no longer valid; request a new approval."
        )

    fun argumentsChanged(): AgentSecurityError =
        error(
            AgentErrorCodes.PERMISSION_ARGUMENTS_CHANGED,
            AgentErrorCategory.PERMISSION,
            AgentRetryability.AFTER_USER_ACTION,
            "The frozen command no longer matches the approved arguments or precondition."
        )

    fun scopeRevoked(detail: String): AgentSecurityError =
        error(AgentErrorCodes.PERMISSION_SCOPE_REVOKED, AgentErrorCategory.PERMISSION, AgentRetryability.AFTER_USER_ACTION, detail)

    fun deniedLoop(): AgentSecurityError =
        error(
            AgentErrorCodes.PERMISSION_DENIED_LOOP,
            AgentErrorCategory.PERMISSION,
            AgentRetryability.AFTER_USER_ACTION,
            "The same proposal was denied twice; the run pauses instead of prompting again."
        )

    fun internalInvariant(detail: String): AgentSecurityError =
        error(AgentErrorCodes.INTERNAL_INVARIANT, AgentErrorCategory.INTERNAL, AgentRetryability.NEVER, detail)

    private fun error(
        code: String,
        category: AgentErrorCategory,
        retryability: AgentRetryability,
        safeMessage: String
    ) = AgentSecurityError(code, category, retryability, safeMessage)
}
