package com.muyuchat.core.agent.contract

/**
 * Immutable, fully-resolved tool command produced before any approval is shown.
 *
 * Executors receive only this object: they never re-read the model's argument string, and defaults
 * or path normalization can no longer change after approval (architecture 8.2).
 */
data class FrozenToolCommand(
    val invocationId: String,
    val runId: String,
    val stepId: String,
    val callOrdinal: Int,
    val toolKey: String,
    val toolVersion: String,
    val schemaHash: String,
    val normalizedArgumentsJson: String,
    val argumentsHash: String,
    val resourceFingerprint: String,
    val runEpoch: Long,
    val policyVersion: Long,
    val expiresAtMillis: Long,
    val networkDestinations: List<String> = emptyList(),
    val resourcePreconditionsJson: String = "{}",
    /**
     * Provider call id when the model round was native (N05c). It is frozen with the command so a
     * resumed approval can still pair its result as the provider's `function_call_output`.
     */
    val providerCallId: String? = null,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(stepId, "stepId")
        require(callOrdinal >= 0) { "callOrdinal must not be negative" }
        AgentContract.requireNotBlank(toolKey, "toolKey")
        AgentContract.requireNotBlank(toolVersion, "toolVersion")
        AgentContract.requireNotBlank(schemaHash, "schemaHash")
        AgentContract.requireNotBlank(argumentsHash, "argumentsHash")
        providerCallId?.let { AgentContract.requireNotBlank(it, "providerCallId") }
        require(runEpoch > 0L) { "runEpoch must be positive" }
        require(policyVersion > 0L) { "policyVersion must be positive" }
    }
}

enum class ToolInvocationStatus {
    PROPOSED,
    VALIDATED,
    WAITING_APPROVAL,
    APPROVED,
    DENIED,
    EXPIRED,
    NOT_EXECUTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == DENIED || this == EXPIRED || this == NOT_EXECUTED || this == SUCCEEDED ||
            this == FAILED || this == CANCELLED || this == UNKNOWN
}

data class ToolInvocation(
    val invocationId: String,
    val runId: String,
    val stepId: String,
    val callOrdinal: Int,
    val toolKey: String,
    val toolVersion: String,
    val schemaHash: String,
    val frozenArgumentsRef: String?,
    val argumentsHash: String,
    val resourceFingerprint: String,
    val status: ToolInvocationStatus,
    val idempotencyKey: String,
    val resultRef: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(stepId, "stepId")
        require(callOrdinal >= 0) { "callOrdinal must not be negative" }
        AgentContract.requireNotBlank(toolKey, "toolKey")
        AgentContract.requireNotBlank(idempotencyKey, "idempotencyKey")
    }
}

enum class AgentApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    CONSUMED,
    INVALIDATED
}

enum class AgentApprovalDecision { ALLOW_ONCE, DENY }

/**
 * One-shot approval bound to the exact frozen invocation, schema, resource and run epoch.
 * It contains no credentials and cannot be reused for another invocation.
 */
data class AgentApproval(
    val approvalId: String,
    val invocationId: String,
    val principal: String,
    val argumentsHash: String,
    val toolSchemaHash: String,
    val resourceFingerprint: String,
    val policyVersion: Long,
    val runEpoch: Long,
    val expiresAtMillis: Long,
    val status: AgentApprovalStatus,
    val createdAtMillis: Long,
    val consumedAtMillis: Long? = null,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(approvalId, "approvalId")
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireNotBlank(principal, "principal")
        AgentContract.requireNotBlank(argumentsHash, "argumentsHash")
        require(expiresAtMillis >= createdAtMillis) { "approval expiry must not precede creation" }
    }
}

data class ResolveAgentApproval(
    val commandId: String,
    val runId: String,
    val expectedRunRevision: Long,
    val approvalId: String,
    val decision: AgentApprovalDecision
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(approvalId, "approvalId")
        require(expectedRunRevision >= 0L) { "expectedRunRevision must not be negative" }
    }
}

enum class AgentArtifactCommitState { PREPARING, COMMITTED, FAILED, UNKNOWN }

data class AgentArtifact(
    val artifactId: String,
    val taskId: String,
    val invocationId: String?,
    val relativePath: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val commitState: AgentArtifactCommitState,
    val createdAtMillis: Long,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(artifactId, "artifactId")
        AgentContract.requireId(taskId, "taskId")
        AgentContract.requireNotBlank(relativePath, "relativePath")
        require(!relativePath.startsWith("/") && !relativePath.contains("..")) {
            "artifact path must be a normalized task-relative path"
        }
        require(sizeBytes >= 0L) { "sizeBytes must not be negative" }
        require(sha256.isBlank() || sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "sha256 must be lowercase hex"
        }
    }
}

enum class AgentArtifactCommitPhase {
    PREPARING,
    STAGED,
    RENAMED,
    COMMITTED,
    FAILED,
    UNKNOWN
}

/** Durable cross-resource commit journal for one artifact write (architecture 10.3). */
data class AgentArtifactCommit(
    val commitId: String,
    val invocationId: String,
    val artifactId: String,
    val stagingRef: String,
    val targetLogicalPath: String,
    val expectedOldHash: String?,
    val newHash: String,
    val newSizeBytes: Long,
    val phase: AgentArtifactCommitPhase,
    val updatedAtMillis: Long,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(commitId, "commitId")
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(artifactId, "artifactId")
        AgentContract.requireNotBlank(targetLogicalPath, "targetLogicalPath")
        require(newSizeBytes >= 0L) { "newSizeBytes must not be negative" }
    }
}
