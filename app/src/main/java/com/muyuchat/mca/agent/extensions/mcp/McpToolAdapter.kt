package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentSchemaValidation
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.security.MessageDigest

/** Stable names for MCP tools. Remote names are never used directly as executor keys by the model. */
object McpToolIdentity {
    const val NAMESPACE: String = "mcp"

    fun namespacedKey(serverId: String, remoteName: String): String = "mcp:$serverId:$remoteName"

    /** Model-visible ASCII provider name: `mcp_` + first 32 lowercase hex of SHA-256. */
    fun providerName(serverId: String, remoteName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("$serverId\u0000$remoteName".toByteArray(Charsets.UTF_8))
        return "mcp_" + hash.take(16).joinToString("") { "%02x".format(it) }
    }

    fun isSafeRemoteName(remoteName: String): Boolean =
        remoteName.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))
}

/** One MCP tool accepted into the immutable run catalog, or a typed reason it stayed disabled. */
sealed interface McpToolRegistration {
    data class Accepted(val executor: McpToolExecutor) : McpToolRegistration

    data class Disabled(
        val serverId: String,
        val remoteName: String,
        val reasonCode: String
    ) : McpToolRegistration
}

data class McpToolRegistrationResult(
    val registrations: List<McpToolRegistration>,
    val collisions: List<String>
) {
    val accepted: List<McpToolExecutor>
        get() = registrations.filterIsInstance<McpToolRegistration.Accepted>().map { it.executor }

    val disabled: List<McpToolRegistration.Disabled>
        get() = registrations.filterIsInstance<McpToolRegistration.Disabled>()
}

/**
 * Converts discovered remote tools into contract executors for one connected client/server.
 *
 * Registration is atomic per server: an unsupported schema disables that single tool with a typed
 * reason (other tools remain usable), while duplicate namespaced keys are reported as collisions
 * and never registered. MCP annotations are display hints only and never lower the side-effect
 * class: every remote tool is EXTERNAL_SIDE_EFFECT/ASK in V1.
 */
class McpToolCatalogFactory(
    private val client: McpToolCaller,
    private val validator: AgentToolSchemaValidator,
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) {
    fun build(
        serverId: String,
        remoteTools: List<McpRemoteTool>,
        serverOrigin: String? = null
    ): McpToolRegistrationResult {
        val registrations = ArrayList<McpToolRegistration>(remoteTools.size)
        val collisions = ArrayList<String>()
        val seen = HashSet<String>()
        for (tool in remoteTools) {
            if (!McpToolIdentity.isSafeRemoteName(tool.remoteName)) {
                registrations += McpToolRegistration.Disabled(serverId, tool.remoteName, "mcp_remote_name_unsafe")
                continue
            }
            val key = McpToolIdentity.namespacedKey(serverId, tool.remoteName)
            if (!seen.add(key)) {
                collisions += key
                continue
            }
            val canonical = validator.validateToolSchema(tool.inputSchemaJson)
            if (canonical !is AgentSchemaValidation.Supported) {
                registrations += McpToolRegistration.Disabled(
                    serverId,
                    tool.remoteName,
                    (canonical as AgentSchemaValidation.Unsupported).errorCode
                )
                continue
            }
            val descriptor = AgentToolDescriptor(
                toolKey = key,
                version = "mcp-${tool.schemaHash.take(12)}",
                displayName = tool.displayName,
                description = buildString {
                    append(tool.description)
                    if (tool.readOnlyHint == true) append(" (server hint: read-only)")
                    if (tool.destructiveHint == true) append(" (server hint: destructive)")
                }.take(4 * 1024),
                schemaJson = canonical.canonicalSchemaJson,
                schemaHash = canonical.schemaHash,
                sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
                dataClass = AgentDataClass.PRIVATE,
                timeoutMillis = McpProtocol.DEFAULT_DEADLINE_MILLIS
            )
            registrations += McpToolRegistration.Accepted(
                McpToolExecutor(descriptor, client, tool.remoteName, codec, serverOrigin, validator)
            )
        }
        return McpToolRegistrationResult(registrations, collisions)
    }
}

/**
 * Executor for one remote MCP tool. It receives only the frozen command, re-checks the tool key and
 * argument bytes, and maps every transport/protocol outcome to a paired [AgentToolResult].
 */
class McpToolExecutor(
    override val descriptor: AgentToolDescriptor,
    private val client: McpToolCaller,
    private val remoteName: String,
    private val codec: StrictJsonCodec,
    /** Canonical server origin; the approved Network scope is bound to exactly this origin. */
    private val serverOrigin: String? = null,
    private val validator: com.muyuchat.core.agent.runtime.AgentToolSchemaValidator =
        com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator()
) : AgentToolExecutor, com.muyuchat.core.agent.runtime.run.AgentTypedScopeSource {

    override fun scopePrecondition(): com.muyuchat.core.agent.contract.AgentScopePrecondition? {
        val origin = serverOrigin ?: return null
        return com.muyuchat.core.agent.contract.AgentScopePrecondition(
            scopes = listOf(
                com.muyuchat.core.agent.contract.AgentResourceScope.Network(
                    origins = listOf(origin),
                    methods = setOf("POST"),
                    dataClass = AgentDataClass.PRIVATE
                )
            ),
            effects = setOf(
                com.muyuchat.core.agent.contract.AgentEffectKind.NETWORK_READ,
                com.muyuchat.core.agent.contract.AgentEffectKind.NETWORK_WRITE
            )
        )
    }

    companion object {
        /** Stable code: the live tool schema no longer matches the approved descriptor. */
        const val MCP_SCHEMA_DRIFT: String = "MCP_SCHEMA_DRIFT"
    }

    override suspend fun execute(
        command: FrozenToolCommand,
        context: AgentToolContext
    ): AgentToolResult {
        if (command.toolKey != descriptor.toolKey) {
            return failure(
                AgentErrorCodes.TOOL_INPUT_INVALID,
                "The frozen command does not belong to this tool.",
                status = AgentToolResultStatus.NOT_EXECUTED
            )
        }
        // Schema drift is a *visible* refusal, not a silent replay against a changed server tool.
        // The descriptor hash is what the user approved; a live catalog that no longer advertises the
        // same hash invalidates that approval, so the call is not sent and the model is told why.
        // The descriptor hash is the canonical schema hash; canonicalize the live schema too so a
        // formatting-only difference is not reported as drift.
        val liveSchemaHash = client.currentSchemaJson(remoteName)?.let { live ->
            (validator.validateToolSchema(live) as? com.muyuchat.core.agent.runtime.AgentSchemaValidation.Supported)
                ?.schemaHash
        } ?: client.currentSchemaHash(remoteName)
        if (liveSchemaHash != null && liveSchemaHash != descriptor.schemaHash) {
            return failure(
                MCP_SCHEMA_DRIFT,
                "The MCP server changed this tool's input schema after it was approved; " +
                    "the call was not sent and a new approval is required.",
                status = AgentToolResultStatus.NOT_EXECUTED
            )
        }
        val argumentsJson = command.normalizedArgumentsJson
        if (codec.parse(argumentsJson) !is AgentJsonParseResult.Parsed) {
            return failure(AgentErrorCodes.TOOL_INPUT_INVALID, "The frozen arguments are not valid JSON.")
        }
        return when (val outcome = client.callTool(remoteName, argumentsJson)) {
            is McpToolCallOutcome.Success -> AgentToolResult(
                status = AgentToolResultStatus.SUCCEEDED,
                summary = outcome.textBlocks.joinToString("\n").take(8 * 1024)
                    .ifBlank { "The MCP tool completed without a text result." },
                dataJson = buildDataJson(outcome),
                truncated = outcome.textBlocks.any { it.contains("[truncated]") },
                possibleSideEffects = true
            )
            is McpToolCallOutcome.ToolError -> failure(
                AgentErrorCodes.TOOL_FAILED,
                "The MCP tool reported an error.",
                possibleSideEffects = true
            )
            is McpToolCallOutcome.Failed -> mapFailure(outcome.failure)
        }
    }

    private fun buildDataJson(outcome: McpToolCallOutcome.Success): String {
        val builder = StringBuilder("{\"content\":[")
        outcome.textBlocks.forEachIndexed { index, text ->
            if (index > 0) builder.append(',')
            builder.append(quote(text))
        }
        builder.append(']')
        outcome.structuredContentJson?.let { builder.append(",\"structuredContent\":").append(it) }
        if (outcome.unsupportedBlockTypes.isNotEmpty()) {
            builder.append(",\"unsupportedBlockTypes\":[")
            outcome.unsupportedBlockTypes.forEachIndexed { index, type ->
                if (index > 0) builder.append(',')
                builder.append(quote(type))
            }
            builder.append(']')
        }
        builder.append('}')
        return builder.toString()
    }

    private fun mapFailure(failure: McpFailure): AgentToolResult = when (failure) {
        is McpFailure.Timeout -> failure(
            AgentErrorCodes.TOOL_TIMEOUT,
            "The MCP tool did not answer before the deadline; its side effects may be unknown.",
            possibleSideEffects = true,
            status = AgentToolResultStatus.UNKNOWN
        )
        is McpFailure.Cancelled -> failure(
            AgentErrorCodes.TOOL_FAILED,
            "The MCP call was cancelled; remote execution may not have stopped.",
            possibleSideEffects = true,
            status = AgentToolResultStatus.CANCELLED
        )
        is McpFailure.SizeLimit -> failure(
            AgentErrorCodes.TOOL_FAILED,
            "The MCP response exceeded the bounded result size.",
            possibleSideEffects = true,
            status = AgentToolResultStatus.UNKNOWN
        )
        is McpFailure.SessionExpired, is McpFailure.Protocol -> failure(
            AgentErrorCodes.TOOL_FAILED,
            "The MCP server is not reachable or its protocol response was invalid.",
            possibleSideEffects = true,
            status = AgentToolResultStatus.UNKNOWN
        )
        is McpFailure.Authentication, is McpFailure.Authorization, is McpFailure.Endpoint,
        is McpFailure.UnsupportedCapability -> failure(
            AgentErrorCodes.TOOL_FAILED,
            "The MCP server rejected the connection or does not support this capability.",
            status = AgentToolResultStatus.NOT_EXECUTED
        )
    }

    private fun failure(
        errorCode: String,
        safeMessage: String,
        status: AgentToolResultStatus = AgentToolResultStatus.FAILED,
        possibleSideEffects: Boolean = false
    ): AgentToolResult = AgentToolResult(
        status = status,
        summary = safeMessage,
        errorCode = errorCode,
        possibleSideEffects = possibleSideEffects
    )

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 16)
        builder.append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
