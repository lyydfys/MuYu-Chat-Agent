package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.isPreExecution
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Bounded, sanitized tool-lifecycle event summaries.
 *
 * The approval projection and the timeline parse `tool`, `action`, `read`, `write`, `network`,
 * `args`, `path`, `old`, `new` and `source`. Only bounded host facts are written: no raw tool result
 * bodies, no content-like argument values, and never more than the 4 KiB event-summary limit.
 */
internal object AgentToolLifecycle {

    const val MAX_SUMMARY_BYTES: Int = 4 * 1024
    const val TOOL_SCHEMA_STALE: String = "TOOL_SCHEMA_STALE"

    private const val MAX_ARGUMENTS_CHARS = 1_200
    private const val MAX_ARGUMENT_VALUE_CHARS = 160
    private const val MAX_ARGUMENT_FIELDS = 8
    private const val MAX_TARGETS = 8
    private const val MAX_TARGET_CHARS = 240
    private val REDACTED_ARGUMENT_KEYS = setOf("content", "text", "body", "bytes", "base64")

    fun proposedSummary(
        codec: StrictJsonCodec,
        toolKey: String,
        sideEffect: AgentToolSideEffect,
        normalizedArgumentsJson: String
    ): String = encode(
        codec,
        listOf(
            "tool" to AgentJsonValue.Str(bounded(toolKey, 200)),
            "source" to AgentJsonValue.Str(sourceOf(toolKey)),
            "action" to AgentJsonValue.Str(sideEffect.name),
            "args" to AgentJsonValue.Str(argumentsSummary(codec, normalizedArgumentsJson))
        )
    )

    fun approvalRequestedSummary(
        codec: StrictJsonCodec,
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        normalizedArgumentsJson: String
    ): String {
        val precondition = parsePreconditions(codec, command.resourcePreconditionsJson)
        val paths = precondition.logicalPaths
        val readOnly = descriptor.sideEffect == AgentToolSideEffect.READ_ONLY
        val fields = ArrayList<Pair<String, AgentJsonValue>>()
        fields += "tool" to AgentJsonValue.Str(bounded(command.toolKey, 200))
        fields += "source" to AgentJsonValue.Str(sourceOf(command.toolKey))
        fields += "action" to AgentJsonValue.Str(descriptor.sideEffect.name)
        if (readOnly) {
            fields += "read" to AgentJsonValue.Arr(paths.map(AgentJsonValue::Str))
        } else {
            fields += "write" to AgentJsonValue.Arr(paths.map(AgentJsonValue::Str))
            paths.firstOrNull()?.let { fields += "path" to AgentJsonValue.Str(bounded(it, MAX_TARGET_CHARS)) }
        }
        if (command.networkDestinations.isNotEmpty()) {
            fields += "network" to AgentJsonValue.Arr(
                command.networkDestinations.take(MAX_TARGETS).map { AgentJsonValue.Str(bounded(it, MAX_TARGET_CHARS)) }
            )
        }
        precondition.expectedOldHash?.let { fields += "old" to AgentJsonValue.Str(it) }
        val deviceScopes = deviceScopeSummaries(codec, command.resourcePreconditionsJson)
        if (deviceScopes.isNotEmpty()) {
            fields += "scope" to AgentJsonValue.Str(bounded(deviceScopes.joinToString("；"), MAX_TARGET_CHARS))
        }
        fields += "args" to AgentJsonValue.Str(argumentsSummary(codec, normalizedArgumentsJson))
        return encode(codec, fields)
    }

    /**
     * Human-readable Device scope summaries from the typed precondition.
     *
     * Only `userVisibleSummary` is copied; operation/targets stay in the frozen record. Other scope
     * kinds are ignored here because the approval already shows their concrete targets.
     */
    private fun deviceScopeSummaries(codec: StrictJsonCodec, preconditionJson: String): List<String> {
        val parsed = codec.parse(preconditionJson)
        if (parsed !is AgentJsonParseResult.Parsed) return emptyList()
        val root = parsed.value as? AgentJsonValue.Obj ?: return emptyList()
        val scopes = (root.fields.toMap()["scopes"] as? AgentJsonValue.Arr)?.items.orEmpty()
        return scopes.mapNotNull { scope ->
            val fields = (scope as? AgentJsonValue.Obj)?.fields?.toMap() ?: return@mapNotNull null
            if ((fields["kind"] as? AgentJsonValue.Str)?.value != "device") return@mapNotNull null
            (fields["userVisibleSummary"] as? AgentJsonValue.Str)?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { bounded(it, MAX_TARGET_CHARS) }
        }.distinct().take(4)
    }

    fun approvalResolvedSummary(
        codec: StrictJsonCodec,
        toolKey: String,
        approvalId: String,
        decision: String
    ): String = encode(
        codec,
        listOf(
            "tool" to AgentJsonValue.Str(bounded(toolKey, 200)),
            "approval" to AgentJsonValue.Str(bounded(approvalId, 128)),
            "decision" to AgentJsonValue.Str(decision)
        )
    )

    fun startedSummary(codec: StrictJsonCodec, command: FrozenToolCommand): String = encode(
        codec,
        listOf(
            "tool" to AgentJsonValue.Str(bounded(command.toolKey, 200)),
            "invocation" to AgentJsonValue.Str(bounded(command.invocationId, 128)),
            "source" to AgentJsonValue.Str(sourceOf(command.toolKey))
        )
    )

    fun resultSummary(
        codec: StrictJsonCodec,
        toolKey: String,
        result: AgentToolResult
    ): String {
        val fields = ArrayList<Pair<String, AgentJsonValue>>()
        fields += "tool" to AgentJsonValue.Str(bounded(toolKey, 200))
        fields += "status" to AgentJsonValue.Str(result.status.name)
        result.errorCode?.let { fields += "errorCode" to AgentJsonValue.Str(bounded(it, 64)) }
        if (result.possibleSideEffects) {
            fields += "possibleSideEffects" to AgentJsonValue.Bool(true)
        }
        if (result.artifactIds.isNotEmpty()) {
            fields += "artifactCount" to AgentJsonValue.Num(result.artifactIds.size.toString())
        }
        return encode(codec, fields)
    }

    fun notExecutedSummary(
        codec: StrictJsonCodec,
        toolKey: String,
        status: AgentToolResultStatus,
        errorCode: String?
    ): String {
        val fields = ArrayList<Pair<String, AgentJsonValue>>()
        fields += "tool" to AgentJsonValue.Str(bounded(toolKey, 200))
        fields += "status" to AgentJsonValue.Str(status.name)
        errorCode?.let { fields += "errorCode" to AgentJsonValue.Str(bounded(it, 64)) }
        return encode(codec, fields)
    }

    /** `builtin` for code-owned tools, `mcp` for frozen remote namespaces, `tool` otherwise. */
    fun sourceOf(toolKey: String): String = when {
        toolKey.startsWith("mcp:") -> "mcp"
        toolKey.startsWith("workspace.") || toolKey.startsWith("artifact.") -> "builtin"
        else -> "tool"
    }

    private fun encode(codec: StrictJsonCodec, fields: List<Pair<String, AgentJsonValue>>): String {
        val text = codec.write(AgentJsonValues.canonical(AgentJsonValue.Obj(fields)))
        if (text.toByteArray(Charsets.UTF_8).size <= MAX_SUMMARY_BYTES) return text
        // Never emit a truncated JSON document that a parser could misread as incomplete facts.
        val tool = (fields.firstOrNull { it.first == "tool" }?.second as? AgentJsonValue.Str)?.value.orEmpty()
        val status = (fields.firstOrNull { it.first == "status" }?.second as? AgentJsonValue.Str)?.value.orEmpty()
        return "tool=" + bounded(tool, 200) + if (status.isNotEmpty()) " status=$status" else ""
    }

    private fun argumentsSummary(codec: StrictJsonCodec, json: String): String {
        val parsed = codec.parse(json)
        if (parsed !is AgentJsonParseResult.Parsed) return ""
        val obj = parsed.value as? AgentJsonValue.Obj ?: return ""
        val parts = ArrayList<String>()
        for ((key, value) in obj.fields.sortedBy { it.first }) {
            if (parts.size >= MAX_ARGUMENT_FIELDS) break
            val rendered = if (key.lowercase() in REDACTED_ARGUMENT_KEYS) {
                "\"<redacted ${value.approxSize()} chars>\""
            } else {
                renderScalar(value)
            }
            parts += bounded(key, 64) + "=" + rendered
            if (parts.joinToString("; ").length >= MAX_ARGUMENTS_CHARS) break
        }
        val summary = parts.joinToString("; ")
        return if (summary.length <= MAX_ARGUMENTS_CHARS) {
            summary
        } else {
            summary.take(MAX_ARGUMENTS_CHARS - 1) + "…"
        }
    }

    private fun renderScalar(value: AgentJsonValue): String = when (value) {
        is AgentJsonValue.Str -> "\"" + bounded(value.value, MAX_ARGUMENT_VALUE_CHARS) + "\""
        is AgentJsonValue.Num -> value.literal
        is AgentJsonValue.Bool -> value.value.toString()
        is AgentJsonValue.Arr -> "[${value.items.size} items]"
        is AgentJsonValue.Obj -> "{" + value.fields.size + " fields}"
        AgentJsonValue.Null -> "null"
    }

    private fun AgentJsonValue.approxSize(): Int = when (this) {
        is AgentJsonValue.Str -> value.length
        is AgentJsonValue.Arr -> items.size
        is AgentJsonValue.Obj -> fields.size
        is AgentJsonValue.Num -> literal.length
        is AgentJsonValue.Bool -> 1
        AgentJsonValue.Null -> 0
    }

    private fun bounded(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max - 1) + "…"

    private data class Preconditions(
        val logicalPaths: List<String>,
        val expectedOldHash: String?
    )

    private fun parsePreconditions(codec: StrictJsonCodec, json: String): Preconditions {
        val parsed = codec.parse(json)
        if (parsed !is AgentJsonParseResult.Parsed) return Preconditions(emptyList(), null)
        val obj = parsed.value as? AgentJsonValue.Obj ?: return Preconditions(emptyList(), null)
        val fields = obj.fields.toMap()
        val paths = (fields["logicalPaths"] as? AgentJsonValue.Arr)?.items.orEmpty()
            .mapNotNull { (it as? AgentJsonValue.Str)?.value }
            .distinct()
            .take(MAX_TARGETS)
            .map { bounded(it, MAX_TARGET_CHARS) }
        val oldHash = (fields["expectedOldHash"] as? AgentJsonValue.Str)?.value
        return Preconditions(paths, oldHash)
    }
}

/** Final outcome of one authorized tool execution: the observation and the run after its events. */
data class AgentToolExecutionOutcome(
    val run: AgentRun,
    val result: AgentToolResult
)

internal fun ToolInvocation.matchesFrozenCommand(command: FrozenToolCommand): Boolean =
    invocationId == command.invocationId &&
        runId == command.runId &&
        stepId == command.stepId &&
        callOrdinal == command.callOrdinal &&
        toolKey == command.toolKey &&
        toolVersion == command.toolVersion &&
        schemaHash == command.schemaHash &&
        argumentsHash == command.argumentsHash &&
        resourceFingerprint == command.resourceFingerprint

internal fun AgentToolResultStatus.toInvocationStatus(): ToolInvocationStatus = when (this) {
    AgentToolResultStatus.SUCCEEDED -> ToolInvocationStatus.SUCCEEDED
    AgentToolResultStatus.FAILED -> ToolInvocationStatus.FAILED
    AgentToolResultStatus.DENIED -> ToolInvocationStatus.DENIED
    AgentToolResultStatus.NOT_EXECUTED -> ToolInvocationStatus.NOT_EXECUTED
    AgentToolResultStatus.CANCELLED -> ToolInvocationStatus.CANCELLED
    AgentToolResultStatus.UNKNOWN -> ToolInvocationStatus.UNKNOWN
}

internal fun ToolInvocationStatus.isPreExecution(): Boolean =
    this == ToolInvocationStatus.PROPOSED ||
        this == ToolInvocationStatus.VALIDATED ||
        this == ToolInvocationStatus.WAITING_APPROVAL ||
        this == ToolInvocationStatus.APPROVED
