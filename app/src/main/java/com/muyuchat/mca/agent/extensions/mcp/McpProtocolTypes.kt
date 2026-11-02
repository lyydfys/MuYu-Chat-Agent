package com.muyuchat.mca.agent.extensions.mcp

/**
 * MCP V1 protocol constants and bounded wire limits.
 *
 * The negotiated protocol version is recorded per session; 2025-06-18 is a deliberate pin
 * (see evidence/5e912ff9/dependency-verification.md), not "latest". Unknown versions are rejected
 * rather than silently treated as compatible.
 */
object McpProtocol {
    const val VERSION_2025_06_18: String = "2025-06-18"

    /** Only this exact negotiated version is supported by V1. */
    val SUPPORTED_VERSIONS: Set<String> = setOf(VERSION_2025_06_18)

    // Transport bounds (architecture 12.2 / package limits).
    const val MAX_MESSAGE_BYTES: Int = 256 * 1024
    const val MAX_TOOL_SCHEMA_BYTES: Int = 64 * 1024
    const val MAX_TOOL_LIST_BYTES: Int = 1024 * 1024
    const val MAX_TOOL_LIST_PAGES: Int = 10
    const val MAX_TOOLS: Int = 1_000
    const val MAX_RESULT_TEXT_BYTES_TO_MODEL: Int = 16 * 1024
    const val MAX_JSON_DEPTH: Int = 32
    const val MAX_NAME_LENGTH: Int = 256
    const val DEFAULT_DEADLINE_MILLIS: Long = 30_000L

    const val HEADER_SESSION_ID: String = "Mcp-Session-Id"
    const val HEADER_PROTOCOL_VERSION: String = "MCP-Protocol-Version"
    const val CONTENT_TYPE_JSON: String = "application/json"
    const val ACCEPT_BOTH: String = "application/json, text/event-stream"

    const val METHOD_INITIALIZE: String = "initialize"
    const val METHOD_INITIALIZED: String = "notifications/initialized"
    const val METHOD_TOOLS_LIST: String = "tools/list"
    const val METHOD_TOOLS_CALL: String = "tools/call"
    const val METHOD_CANCELLED: String = "notifications/cancelled"
    const val METHOD_PING: String = "ping"

    const val JSONRPC_VERSION: String = "2.0"
    const val JSONRPC_METHOD_NOT_FOUND: Int = -32601
    const val JSONRPC_INVALID_PARAMS: Int = -32602
}

/** Authentication mode. The credential itself never lives in the config or manifest. */
sealed interface McpAuth {
    data object None : McpAuth

    /** Opaque reference into the app's Keystore-backed credential store. */
    data class SecretReference(val credentialRefId: String) : McpAuth {
        init {
            require(credentialRefId.isNotBlank()) { "credentialRefId must not be blank" }
        }
    }
}

/**
 * User-authored server configuration. [serverId] is a stable local identity; the model-visible
 * namespace is derived from it. The endpoint must be a plain HTTPS origin for REMOTE servers.
 */
data class McpServerConfig(
    val serverId: String,
    val displayName: String,
    val endpoint: String,
    val auth: McpAuth = McpAuth.None,
    val enabled: Boolean = true,
    val allowLocalServer: Boolean = false
) {
    init {
        require(serverId.matches(Regex("^[a-z0-9][a-z0-9-]{1,63}$"))) {
            "serverId must be a stable lowercase id"
        }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
    }
}

/** Session established by `initialize`; never shared across origins or server ids. */
data class McpSession(
    val serverId: String,
    val endpointOrigin: String,
    val sessionId: String?,
    val negotiatedVersion: String,
    val toolsCapability: Boolean,
    val listChanged: Boolean
)

/** One tool as advertised by `tools/list`, with the exact schema snapshot hash. */
data class McpRemoteTool(
    val serverId: String,
    val remoteName: String,
    val displayName: String,
    val description: String,
    val inputSchemaJson: String,
    val schemaHash: String,
    val readOnlyHint: Boolean?,
    val destructiveHint: Boolean?
)

enum class McpConnectionState { DISCONNECTED, CONNECTING, READY, DEGRADED }

/** Typed transport/protocol failure. No provider body text is exposed to the UI. */
sealed interface McpFailure {
    val code: String

    data class Authentication(override val code: String) : McpFailure
    data class Authorization(override val code: String) : McpFailure
    data class Endpoint(override val code: String, val originChanged: Boolean = false) : McpFailure
    data class SessionExpired(override val code: String) : McpFailure
    data class Timeout(override val code: String) : McpFailure
    data class SizeLimit(override val code: String) : McpFailure
    data class Protocol(override val code: String) : McpFailure
    data class UnsupportedCapability(override val code: String) : McpFailure
    data class Cancelled(override val code: String) : McpFailure
}

sealed interface McpToolCallOutcome {
    data class Success(
        val textBlocks: List<String>,
        val structuredContentJson: String?,
        val unsupportedBlockTypes: List<String>
    ) : McpToolCallOutcome

    /** JSON-RPC error or `isError=true`; both remain paired to the invocation. */
    data class ToolError(
        val message: String,
        val rpcCode: Int? = null,
        val isProtocolError: Boolean = false
    ) : McpToolCallOutcome

    data class Failed(val failure: McpFailure) : McpToolCallOutcome
}

/** Narrow call surface so the executor can be tested without a real transport. */
interface McpToolCaller {
    suspend fun callTool(remoteName: String, argumentsJson: String): McpToolCallOutcome

    /**
     * Schema hash the server currently advertises for [remoteName], or `null` when this caller
     * cannot observe it (a fake/older caller). The executor uses it to detect schema drift between
     * the approved descriptor and the live catalog without replaying the call.
     */
    fun currentSchemaHash(remoteName: String): String? = null

    /** Raw schema advertised for [remoteName]; canonicalized by the executor for drift checks. */
    fun currentSchemaJson(remoteName: String): String? = null
}
