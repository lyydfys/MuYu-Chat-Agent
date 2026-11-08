package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.FrozenToolCommand

/**
 * One frozen tool call plus the trusted descriptor and resolved scope used to decide it.
 *
 * The constructor enforces that the command was frozen with exactly this descriptor. There is no
 * field for model-supplied permission claims, MCP annotations, Skill allowed-tools or tool-declared
 * hints: those can never raise the decision, by construction.
 */
data class AgentPermissionCandidate(
    val command: FrozenToolCommand,
    val descriptor: AgentToolDescriptor,
    val scope: AgentResolvedToolScope
) {
    init {
        require(command.toolKey == descriptor.toolKey) { "frozen command toolKey does not match descriptor" }
        require(command.toolVersion == descriptor.version) { "frozen command tool version does not match descriptor" }
        require(command.schemaHash == descriptor.schemaHash) { "frozen command schema hash does not match descriptor" }
        require(scope.typedPreconditionJson != null || scope.logicalPaths.isNotEmpty()) {
            "a permission candidate must name a typed scope or at least one resource path"
        }
    }

    /** Redacted: the frozen command's arguments may contain private content. */
    override fun toString(): String =
        "AgentPermissionCandidate(toolKey=${command.toolKey}, invocationId=${command.invocationId}, " +
            "argumentsHash=${command.argumentsHash}, resourceFingerprint=${command.resourceFingerprint})"
}

/**
 * Task-creation read grant. It can only narrow the task root; it is never a whole-files-dir grant.
 * [revoked] must reflect current persistence when the broker is consulted; a revoked grant
 * invalidates pending approvals and removes ALLOW_FOR_TASK.
 */
data class AgentWorkspaceReadGrant(
    val workspaceId: String,
    val taskId: String,
    val taskRoot: String,
    val scopePrefixes: List<String>,
    val revoked: Boolean = false
) {
    init {
        AgentContract.requireId(workspaceId, "workspaceId")
        AgentContract.requireId(taskId, "taskId")
        require(taskRoot == "$workspaceId/tasks/$taskId") { "workspace grant task root does not match ids" }
        require(scopePrefixes.isNotEmpty()) { "a workspace read grant needs at least one scope prefix" }
        require(scopePrefixes == scopePrefixes.distinct()) { "workspace grant prefixes must be unique" }
        require(
            scopePrefixes.all {
                it.isNotBlank() && !it.startsWith("/") && !it.contains('\\') && !it.contains("..")
            }
        ) { "workspace grant prefixes must be logical relative paths" }
    }

    /** A workspace read grant can never cover a typed (non-workspace) resource scope. */
    fun covers(scope: AgentResolvedToolScope): Boolean =
        !revoked && scope.typedPreconditionJson == null && taskRoot == scope.taskRoot &&
            scope.logicalPaths.all { path ->
                scopePrefixes.any { prefix -> AgentLogicalPaths.covers(prefix, path) }
            }

    companion object {
        /** V1 grants the private task `inputs/` read scope at task creation. */
        const val V1_READ_SCOPE_PREFIX: String = "inputs"

        /**
         * Rebuilds the task-creation read grant from the persisted task identity only; model text
         * and extension metadata never participate.
         */
        fun v1(task: com.muyuchat.core.agent.contract.AgentTask): AgentWorkspaceReadGrant =
            AgentWorkspaceReadGrant(
                workspaceId = task.workspaceId,
                taskId = task.taskId,
                taskRoot = "${task.workspaceId}/tasks/${task.taskId}",
                scopePrefixes = listOf(V1_READ_SCOPE_PREFIX),
                revoked = false
            )
    }
}

/** Trusted, current run facts. K03 loads these immediately before deciding/authorizing. */
data class AgentPermissionPolicy(
    val runEpoch: Long,
    val policyVersion: Long,
    val networkPolicy: AgentNetworkPolicy
) {
    init {
        require(runEpoch > 0L) { "runEpoch must be positive" }
        require(policyVersion > 0L) { "policyVersion must be positive" }
    }
}

enum class AgentPermissionBasis {
    /** Read-only workspace access inside the grant created when the task was created. */
    WORKSPACE_READ_GRANT,

    /** Fresh user ALLOW_ONCE, atomically consumed at execution time. */
    APPROVED_ONCE,

    /**
     * The user's persisted approval policy says this tool may run without a prompt
     * ([ToolApprovalMode.ALLOW]). No approval row exists, so nothing is consumed.
     */
    POLICY_ALLOW
}

sealed interface AgentPermissionDecision {
    data class AllowForTask(
        val command: FrozenToolCommand,
        val basis: AgentPermissionBasis
    ) : AgentPermissionDecision {
        override fun toString(): String = "AllowForTask(toolKey=${command.toolKey}, basis=$basis)"
    }

    /** Approval was created (or an identical pending one was replayed); the run may wait. */
    data class NeedsApproval(
        val command: FrozenToolCommand,
        val descriptor: AgentToolDescriptor,
        val approval: AgentApproval
    ) : AgentPermissionDecision {
        override fun toString(): String =
            "NeedsApproval(toolKey=${command.toolKey}, approvalId=${approval.approvalId}, " +
                "argumentsHash=${command.argumentsHash})"
    }

    data class Denied(
        val error: AgentSecurityError,
        val toolStatus: AgentToolResultStatus,
        val pauseRunWith: AgentPauseReason? = null
    ) : AgentPermissionDecision
}

/** Result of applying the user's allow/deny click to a pending approval. */
sealed interface AgentApprovalResolutionResult {
    data class Approved(val approval: AgentApproval, val replay: Boolean = false) : AgentApprovalResolutionResult

    data class Denied(
        val approval: AgentApproval,
        val pauseRunWith: AgentPauseReason?,
        val replay: Boolean = false
    ) : AgentApprovalResolutionResult

    /** No tool execution may happen; map to a paired NOT_EXECUTED tool result. */
    data class NotExecuted(val error: AgentSecurityError) : AgentApprovalResolutionResult
}

/** Result of the pre-executor CAS check. Only [Authorized] may reach an executor (architecture 7.4). */
sealed interface AgentExecutionAuthorization {
    data class Authorized(
        val command: FrozenToolCommand,
        val approvalId: String?,
        val basis: AgentPermissionBasis
    ) : AgentExecutionAuthorization {
        override fun toString(): String =
            "Authorized(toolKey=${command.toolKey}, approvalId=$approvalId, basis=$basis)"
    }

    data class Denied(
        val error: AgentSecurityError,
        val toolStatus: AgentToolResultStatus
    ) : AgentExecutionAuthorization
}

internal fun AgentToolDescriptor.isWorkspaceRead(): Boolean =
    sideEffect == AgentToolSideEffect.READ_ONLY && toolKey.startsWith("workspace.")
