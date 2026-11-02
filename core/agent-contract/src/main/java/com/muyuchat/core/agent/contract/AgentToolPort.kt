package com.muyuchat.core.agent.contract

/** Declared capability, not a user grant. Hints never lower the side-effect level. */
enum class AgentToolSideEffect {
    READ_ONLY,
    WORKSPACE_WRITE,
    EXTERNAL_SIDE_EFFECT,
    NETWORK
}

enum class AgentDataClass { PUBLIC, PRIVATE, LOCAL_ONLY, SECRET }

data class AgentToolDescriptor(
    val toolKey: String,
    val version: String,
    val displayName: String,
    val description: String,
    val schemaJson: String,
    val schemaHash: String,
    val sideEffect: AgentToolSideEffect,
    val dataClass: AgentDataClass,
    val timeoutMillis: Long = 30_000L
) {
    init {
        // Built-in tools use dotted lowercase keys. MCP tools use the frozen namespace
        // `mcp:<serverId>:<remoteName>`; both are validated here so an arbitrary remote name can
        // never become a path, protocol method or untyped lookup key.
        val builtIn = toolKey.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))
        val mcp = toolKey.matches(Regex("^mcp:[a-z0-9][a-z0-9-]{1,63}:[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))
        require(builtIn || mcp) {
            "tool key must be a dotted lowercase identifier or mcp:<serverId>:<name>: '$toolKey'"
        }
        AgentContract.requireNotBlank(version, "tool version")
        AgentContract.requireNotBlank(schemaHash, "tool schema hash")
        require(timeoutMillis > 0L) { "tool timeout must be positive" }
    }
}

/** Execution context handed to an app executor. It holds logical scope, never absolute paths. */
data class AgentToolContext(
    val taskId: String,
    val workspaceId: String,
    val runId: String,
    val stepIndex: Int,
    val runEpoch: Long,
    val policyVersion: Long,
    val workspaceReadGranted: Boolean
)

enum class AgentToolResultStatus {
    SUCCEEDED,
    FAILED,
    DENIED,
    NOT_EXECUTED,
    CANCELLED,
    UNKNOWN
}

data class AgentToolResult(
    val status: AgentToolResultStatus,
    val summary: String,
    val dataJson: String? = null,
    val artifactIds: List<String> = emptyList(),
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val possibleSideEffects: Boolean = false,
    val fullResultRef: String? = null
) {
    init {
        AgentContract.requireNotBlank(summary, "tool result summary")
        require(artifactIds.size <= 20) { "tool result references at most 20 artifacts" }
    }
}

/** The single execution surface for built-in and (later) remote tools. */
interface AgentToolExecutor {
    val descriptor: AgentToolDescriptor

    suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult
}

/**
 * Immutable snapshot of the tools available for one run. The registry is rebuilt off the run
 * hot path; an extension update never mutates a live run's tool set.
 */
interface AgentToolCatalog {
    fun find(toolKey: String): AgentToolExecutor?

    fun snapshot(): List<AgentToolDescriptor>
}
