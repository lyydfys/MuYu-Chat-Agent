package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import kotlin.math.ceil

/**
 * Approval presentation projection.
 *
 * The frozen [AgentApproval] record carries the binding hashes and the bound principal but not the
 * tool action or its targets. The safe summary of the paired `tool.approval_requested` (or a
 * matching `tool.proposed`) event is therefore the only contract-level source for the human-readable
 * request. P0 writes a flat JSON object with the keys `tool`, `action`, `read`, `write`, `network`,
 * `args`, `source`, `path`, `oldHash`, `newHash` and `principal`; `key=value` lines are also accepted
 * for compatibility. A request event bound to a different invocation is never used: showing another
 * call's targets would be a fabricated approval.
 */

internal const val MAX_APPROVAL_ARGUMENT_CHARS = 2_000
private const val MAX_APPROVAL_TARGETS = 8
private const val MAX_APPROVAL_TARGET_CHARS = 240
private const val MAX_APPROVAL_PRINCIPAL_CHARS = 120
private const val MAX_APPROVAL_SUMMARY_CHARS = 4_096

internal data class ParsedApprovalSummary(
    val tool: String? = null,
    val action: String? = null,
    val readTargets: List<String> = emptyList(),
    val writeTargets: List<String> = emptyList(),
    val networkTargets: List<String> = emptyList(),
    val scopeSummary: String? = null,
    val argumentsSummary: String? = null,
    val path: String? = null,
    val oldHash: String? = null,
    val newHash: String? = null,
    val source: String? = null,
    val principal: String? = null,
    val state: String? = null,
    val summaryText: String? = null,
    val invocationId: String? = null,
    val approvalId: String? = null,
    val expiresAt: String? = null
)

internal fun approvalStatusLabel(status: AgentApprovalStatus): String = when (status) {
    AgentApprovalStatus.PENDING -> "待授权"
    AgentApprovalStatus.APPROVED -> "已允许"
    AgentApprovalStatus.DENIED -> "已拒绝"
    AgentApprovalStatus.EXPIRED -> "已过期"
    AgentApprovalStatus.CONSUMED -> "已使用"
    AgentApprovalStatus.INVALIDATED -> "已失效"
}

internal fun projectApprovalUi(
    approval: AgentApproval,
    events: List<AgentEvent>,
    nowMillis: Long
): AgentApprovalUi {
    val requestEvent = findBoundApprovalEvent(approval, events)
    val summary = parseApprovalSummary(requestEvent?.summary)
    val remainingMillis = approval.expiresAtMillis - nowMillis
    val expired = remainingMillis <= 0L
    val remainingMinutes = ceil(remainingMillis.coerceAtLeast(0L) / 60_000.0).toInt()
    val expiresText = if (expired) {
        "已于 ${formatClockTime(approval.expiresAtMillis)} 过期"
    } else {
        "有效期至 ${formatClockTime(approval.expiresAtMillis)}" +
            "（剩余约 ${remainingMinutes.coerceAtLeast(1)} 分钟）"
    }

    val readTargets = boundedTargets(summary.readTargets)
    val writeTargets = boundedTargets(summary.writeTargets)
    val networkTargets = boundedTargets(summary.networkTargets)
    val scopeSummary = summary.scopeSummary?.let { boundedSafeText(it, MAX_APPROVAL_TARGET_CHARS) }
    val toolLabel = summary.tool ?: legacyToolKey(requestEvent?.summary)
    val actionLabel = summary.action
    val toolAction = listOfNotNull(toolLabel, actionLabel)
        .distinct()
        .joinToString(" · ")
        .ifBlank { "工具调用" }
    val rawArguments = summary.argumentsSummary
    val argumentsSummary = rawArguments?.let {
        boundedSafeText(it, MAX_APPROVAL_ARGUMENT_CHARS)
    }
    val overwrite = if (summary.oldHash != null || summary.newHash != null) {
        AgentOverwriteUi(
            path = summary.path ?: writeTargets.firstOrNull(),
            beforeHash = summary.oldHash?.takeIf { it.isNotBlank() },
            afterHash = summary.newHash?.takeIf { it.isNotBlank() }
        )
    } else {
        null
    }
    val source = resolveToolSource(summary.source, toolLabel)
    val pending = approval.status == AgentApprovalStatus.PENDING
    val statusLabel = if (pending && expired) {
        "已过期"
    } else {
        approvalStatusLabel(approval.status)
    }
    val principal = boundedSafeText(approval.principal, MAX_APPROVAL_PRINCIPAL_CHARS)

    return AgentApprovalUi(
        approvalId = approval.approvalId,
        invocationId = approval.invocationId,
        statusLabel = statusLabel,
        toolAction = toolAction,
        readTargets = readTargets,
        writeTargets = writeTargets,
        networkTargets = networkTargets,
        scopeSummary = scopeSummary,
        argumentsSummary = argumentsSummary,
        argumentsTruncated = (rawArguments?.length ?: 0) > MAX_APPROVAL_ARGUMENT_CHARS,
        overwrite = overwrite,
        expiresText = expiresText,
        expired = expired,
        argsHashShort = formatShortHash(approval.argumentsHash),
        schemaHashShort = formatShortHash(approval.toolSchemaHash),
        resourceFingerprintShort = formatShortHash(approval.resourceFingerprint),
        policyVersion = approval.policyVersion,
        runEpoch = approval.runEpoch,
        hasStructuredDetails = toolLabel != null || actionLabel != null ||
            readTargets.isNotEmpty() || writeTargets.isNotEmpty() ||
            networkTargets.isNotEmpty() || scopeSummary != null || argumentsSummary != null || overwrite != null,
        pending = pending,
        toolLabel = toolLabel,
        actionLabel = actionLabel,
        source = source,
        principal = principal,
        approvalIdShort = formatShortHash(approval.approvalId),
        invocationIdShort = formatShortHash(approval.invocationId),
        actionable = pending && !expired
    )
}

/**
 * Strict binding: only an event for this approval/invocation may supply display details. There is
 * deliberately no "latest approval request" fallback, because that would show another call's tool
 * name and targets under this approval.
 */
private fun findBoundApprovalEvent(approval: AgentApproval, events: List<AgentEvent>): AgentEvent? {
    val candidates = events.asReversed().filter {
        it.type == AgentEventTypes.TOOL_APPROVAL_REQUESTED || it.type == AgentEventTypes.TOOL_PROPOSED
    }
    for (event in candidates) {
        if (event.causationId == approval.invocationId || event.commandId == approval.invocationId) {
            return event
        }
        val document = parseAgentSummaryDocument(event.summary)
        if (document.first("invocation") == approval.invocationId) return event
        if (document.first("approval") == approval.approvalId) return event
    }
    return null
}

internal fun parseApprovalSummary(raw: String?): ParsedApprovalSummary {
    val document = parseAgentSummaryDocument(raw)
    if (document.fields.isEmpty()) {
        return ParsedApprovalSummary(
            argumentsSummary = boundedSafeText(document.rawText, MAX_APPROVAL_SUMMARY_CHARS)
        )
    }
    return mapApprovalFields(document.fields)
}

private fun mapApprovalFields(fields: Map<String, List<String>>): ParsedApprovalSummary {
    var tool: String? = null
    var action: String? = null
    var scopeSummary: String? = null
    val readTargets = mutableListOf<String>()
    val writeTargets = mutableListOf<String>()
    val networkTargets = mutableListOf<String>()
    val extras = mutableListOf<String>()
    var arguments: String? = null
    var path: String? = null
    var oldHash: String? = null
    var newHash: String? = null
    var source: String? = null
    var principal: String? = null
    var state: String? = null
    var summaryText: String? = null
    var invocationId: String? = null
    var approvalId: String? = null
    var expiresAt: String? = null

    for ((key, values) in fields) {
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) continue
        when (key) {
            "tool" -> tool = cleaned.joinToString(" ")
            "action" -> action = cleaned.joinToString(" ")
            "read" -> readTargets += splitSummaryTargets(cleaned)
            "write" -> writeTargets += splitSummaryTargets(cleaned)
            "network" -> networkTargets += splitSummaryTargets(cleaned)
            "scope" -> scopeSummary = cleaned.joinToString(" ")
            "args" -> arguments = cleaned.joinToString("\n")
            "path" -> path = cleaned.joinToString(" ")
            "old" -> oldHash = cleaned.firstOrNull()
            "new" -> newHash = cleaned.firstOrNull()
            "source" -> source = cleaned.joinToString(" ")
            "principal" -> principal = cleaned.joinToString(" ")
            "state" -> state = cleaned.firstOrNull()
            "summary", "result" -> summaryText = cleaned.joinToString("\n")
            "invocation" -> invocationId = cleaned.firstOrNull()
            "approval" -> approvalId = cleaned.firstOrNull()
            "expiry" -> expiresAt = cleaned.firstOrNull()
            else -> extras += "$key: ${cleaned.joinToString(", ")}"
        }
    }

    return ParsedApprovalSummary(
        tool = tool?.takeIf { it.isNotBlank() },
        action = action?.takeIf { it.isNotBlank() },
        readTargets = readTargets,
        writeTargets = writeTargets,
        networkTargets = networkTargets,
        scopeSummary = scopeSummary?.takeIf { it.isNotBlank() },
        argumentsSummary = arguments ?: extras.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        path = path,
        oldHash = oldHash,
        newHash = newHash,
        source = source?.takeIf { it.isNotBlank() },
        principal = principal?.takeIf { it.isNotBlank() },
        state = state?.takeIf { it.isNotBlank() },
        summaryText = summaryText?.takeIf { it.isNotBlank() },
        invocationId = invocationId?.takeIf { it.isNotBlank() },
        approvalId = approvalId?.takeIf { it.isNotBlank() },
        expiresAt = expiresAt?.takeIf { it.isNotBlank() }
    )
}

private fun boundedTargets(values: List<String>): List<String> =
    values.map { scrubSensitiveText(it.trim()) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_APPROVAL_TARGETS)
        .map {
            if (it.length <= MAX_APPROVAL_TARGET_CHARS) {
                it
            } else {
                it.take(MAX_APPROVAL_TARGET_CHARS) + "…"
            }
        }
