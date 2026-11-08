package com.muyuchat.core.agent.contract

enum class AgentErrorCategory {
    MODEL,
    RESOURCE,
    TOOL,
    PERMISSION,
    POLICY,
    BUDGET,
    STORAGE,
    INTERNAL
}

enum class AgentRetryability {
    NEVER,
    AFTER_USER_ACTION,
    SAFE_RETRY
}

/**
 * Typed failure surfaced to the owner/UI. It must never contain raw provider bodies, prompts or
 * secrets; [causeRef] is a redacted correlation reference, not an exception message dump.
 */
data class AgentError(
    val code: String,
    val category: AgentErrorCategory,
    val retryability: AgentRetryability,
    val safeMessage: String,
    val correlationId: String,
    val causeRef: String? = null,
    val possibleSideEffects: Boolean = false
) {
    init {
        AgentContract.requireNotBlank(code, "error code")
        require(code.matches(Regex("^[A-Z][A-Z0-9_]{1,63}$"))) { "error code must be UPPER_SNAKE_CASE" }
        AgentContract.requireNotBlank(safeMessage, "safeMessage")
        AgentContract.requireNotBlank(correlationId, "correlationId")
        require(safeMessage.toByteArray(Charsets.UTF_8).size <= 2 * 1024) {
            "safeMessage exceeds 2 KiB"
        }
    }
}

/** Stable error codes from architecture 7.5. Extra codes must keep the same families. */
object AgentErrorCodes {
    // MODEL_*
    const val MODEL_NOT_LOADED = "MODEL_NOT_LOADED"
    const val MODEL_BINDING_CHANGED = "MODEL_BINDING_CHANGED"
    const val MODEL_PROTOCOL_ERROR = "MODEL_PROTOCOL_ERROR"
    const val MODEL_TERMINAL_UNVERIFIED = "MODEL_TERMINAL_UNVERIFIED"
    const val MODEL_CONTEXT_LIMIT = "MODEL_CONTEXT_LIMIT"

    // RESOURCE_*
    const val RESOURCE_BUSY = "RESOURCE_BUSY"
    const val RESOURCE_STOP_TIMEOUT = "RESOURCE_STOP_TIMEOUT"
    const val RESOURCE_LEASE_LOST = "RESOURCE_LEASE_LOST"

    // TOOL_*
    const val TOOL_INPUT_INVALID = "TOOL_INPUT_INVALID"
    const val TOOL_TIMEOUT = "TOOL_TIMEOUT"
    const val TOOL_FAILED = "TOOL_FAILED"
    const val TOOL_RESULT_UNKNOWN = "TOOL_RESULT_UNKNOWN"
    const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"

    // PERMISSION_*
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val PERMISSION_EXPIRED = "PERMISSION_EXPIRED"
    const val PERMISSION_ARGUMENTS_CHANGED = "PERMISSION_ARGUMENTS_CHANGED"
    const val PERMISSION_SCOPE_REVOKED = "PERMISSION_SCOPE_REVOKED"
    const val PERMISSION_DENIED_LOOP = "PERMISSION_DENIED_LOOP"

    /**
     * The frozen command carries a legacy (V1) resource precondition where a typed scope is required
     * (N02c). The approval was bound to a fake/absent resource identity and must be requested again
     * against the typed scope instead of being reinterpreted.
     */
    const val RESOURCE_SCOPE_OUTDATED = "RESOURCE_SCOPE_OUTDATED"

    // POLICY_*
    const val POLICY_OFFLINE = "POLICY_OFFLINE"
    const val POLICY_EGRESS_DENIED = "POLICY_EGRESS_DENIED"
    const val POLICY_SCHEMA_UNSUPPORTED = "POLICY_SCHEMA_UNSUPPORTED"

    // BUDGET_*
    const val BUDGET_STEP_LIMIT = "BUDGET_STEP_LIMIT"

    /**
     * A single step exceeded the per-step watchdog limit.
     *
     * Distinct from [BUDGET_STEP_LIMIT] (the run used up its attempt allowance): this one means the loop
     * is stuck inside one step, which is a hang rather than a spend.
     */
    const val BUDGET_STEP_TIMEOUT = "BUDGET_STEP_TIMEOUT"
    const val BUDGET_TOKEN_LIMIT = "BUDGET_TOKEN_LIMIT"
    const val BUDGET_TIME_LIMIT = "BUDGET_TIME_LIMIT"
    const val BUDGET_DISK_LIMIT = "BUDGET_DISK_LIMIT"

    // STORAGE_*
    const val STORAGE_MIGRATION = "STORAGE_MIGRATION"
    const val STORAGE_NO_SPACE = "STORAGE_NO_SPACE"
    const val STORAGE_ARTIFACT_COMMIT = "STORAGE_ARTIFACT_COMMIT"
    const val STORAGE_CORRUPTION = "STORAGE_CORRUPTION"
    const val STORAGE_PRECONDITION_CHANGED = "STORAGE_PRECONDITION_CHANGED"

    // COMMAND_* (idempotency / revision)
    const val COMMAND_IDEMPOTENCY_CONFLICT = "COMMAND_IDEMPOTENCY_CONFLICT"
    const val COMMAND_REVISION_CONFLICT = "COMMAND_REVISION_CONFLICT"
    const val COMMAND_INVALID_STATE = "COMMAND_INVALID_STATE"
    const val COMMAND_NOT_FOUND = "COMMAND_NOT_FOUND"
    const val COMMAND_BUSY = "COMMAND_BUSY"

    /** The command ledger knows the command but no durable task anchor can be proven (N01). */
    const val TASK_ANCHOR_UNAVAILABLE = "TASK_ANCHOR_UNAVAILABLE"

    // INTERNAL_*
    const val INTERNAL_INVARIANT = "INTERNAL_INVARIANT"
    const val INTERNAL_UNEXPECTED = "INTERNAL_UNEXPECTED"
}

sealed interface AgentCommandResult {
    data class Accepted(
        val appliedRevision: Long,
        val runId: String? = null,
        val idempotentReplay: Boolean = false,
        /**
         * Present for create-like commands (N01). Old producers leave it null; consumers must treat
         * a null receipt on a create command as "anchor not available" and never guess a task.
         */
        val creationReceipt: AgentCreationReceipt? = null
    ) : AgentCommandResult

    data class Rejected(val error: AgentError) : AgentCommandResult
}
