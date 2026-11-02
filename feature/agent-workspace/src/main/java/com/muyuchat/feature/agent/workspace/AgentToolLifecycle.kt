package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import java.util.Locale

/**
 * Pure mapping of one tool-lifecycle event to the facts the page is allowed to show.
 *
 * P0 writes bounded summaries for `tool.proposed`, `tool.approval_requested`,
 * `tool.approval_resolved`, `tool.started` and `tool.result`. The UI reads only those summaries and
 * the frozen event identity; it never guesses a source from a display name and never renders raw
 * arguments, provider text or credentials.
 */

enum class AgentToolLifecycleState(val label: String) {
    PROPOSED("已准备"),
    WAITING_APPROVAL("等待授权"),
    APPROVED("已授权"),
    RUNNING("运行中"),
    SUCCEEDED("成功"),
    FAILED("失败"),
    DENIED("已拒绝"),
    CANCELLED("已取消"),
    NOT_EXECUTED("未执行"),
    EXPIRED("已过期"),
    UNKNOWN("状态未知");

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == DENIED || this == CANCELLED ||
            this == NOT_EXECUTED || this == EXPIRED || this == UNKNOWN

    internal val rank: Int
        get() = when (this) {
            PROPOSED -> 0
            WAITING_APPROVAL -> 1
            APPROVED -> 2
            RUNNING -> 3
            else -> 4
        }

    /** Higher wins when one invocation confusingly reports two terminal outcomes. */
    internal val terminalPriority: Int
        get() = when (this) {
            SUCCEEDED -> 1
            FAILED -> 2
            CANCELLED -> 3
            NOT_EXECUTED -> 4
            EXPIRED -> 5
            DENIED -> 6
            else -> 0
        }
}

enum class AgentToolSourceKind { BUILT_IN, SKILL, PACKAGE, MCP, UNKNOWN }

/** Bounded, already-safe source label. [label] is the only string the UI renders. */
data class AgentToolSourceUi(
    val kind: AgentToolSourceKind,
    val label: String
) {
    companion object {
        fun unknown(): AgentToolSourceUi = AgentToolSourceUi(AgentToolSourceKind.UNKNOWN, "来源未知")
    }
}

private const val MAX_SOURCE_LABEL_CHARS = 80
private const val MAX_TOOL_KEY_CHARS = 160
private const val MAX_SOURCE_SEGMENT_CHARS = 64
private const val MAX_SUMMARY_CHARS = 800

private val TOOL_KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.:@/-"
private val SOURCE_SEGMENT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-/"

private val LEGACY_TOOL_PREFIX = Regex(
    "^\\s*(" +
        "(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*" +
        "|mcp:[A-Za-z0-9][A-Za-z0-9-]{1,63}:[A-Za-z0-9][A-Za-z0-9._-]{0,127}" +
        "|pkg\\.[A-Za-z0-9_.-]+" +
        "|skill:[A-Za-z0-9_.-]+" +
        ")\\s*[:：]"
)

private val SECRET_ASSIGNMENT = Regex(
    "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|authorization|" +
        "auth[_-]?token|cookie|credential|private[_-]?key|access[_-]?key|" +
        "args|arguments|input|parameter|parameters)\\b" +
        "\\s*[:=]\\s*[^\\s,;]+"
)
private val BEARER_TOKEN = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{6,}")
private val OPENAI_STYLE_KEY = Regex("\\bsk-[A-Za-z0-9_-]{8,}")
private val AWS_STYLE_KEY = Regex("\\bAKIA[0-9A-Z]{16}\\b")

internal data class AgentToolLifecycleEvent(
    val toolKey: String?,
    val source: AgentToolSourceUi,
    val state: AgentToolLifecycleState,
    val summary: String?,
    val invocationId: String?,
    val argumentsOmitted: Boolean,
    val malformedSummary: Boolean
)

internal fun parseToolLifecycleEvent(event: AgentEvent): AgentToolLifecycleEvent {
    val document = parseAgentSummaryDocument(event.summary)
    val explicitTool = document.first("tool")
    val toolKey = sanitizeToolKey(explicitTool) ?: legacyToolKey(document.rawText)
    val explicitSource = document.first("source")
    val source = resolveToolSource(explicitSource, toolKey)

    val resultField = document.first("result")
    val stateToken = document.first("state")
        ?: resultField?.takeIf { parseStateToken(it) != null }
    val summaryField = document.first("summary")
        ?: resultField?.takeIf { parseStateToken(it) == null }
        ?: document.first("action")
    val legacyText = if (document.fields.keys.none { it in TOOL_SUMMARY_KEYS }) {
        document.rawText
    } else {
        null
    }
    val summary = when {
        summaryField != null -> boundedSafeText(
            stripToolPrefix(summaryField, toolKey),
            MAX_SUMMARY_CHARS
        )
        document.malformedJson -> null
        legacyText != null -> boundedSafeText(
            stripToolPrefix(legacyText, toolKey),
            MAX_SUMMARY_CHARS
        )
        else -> null
    }
    return AgentToolLifecycleEvent(
        toolKey = toolKey,
        source = source,
        state = lifecycleState(event.type, stateToken, event.summary),
        summary = summary,
        invocationId = document.first("invocation"),
        argumentsOmitted = document.has("args") && summary == null,
        malformedSummary = document.malformedJson
    )
}

private val TOOL_SUMMARY_KEYS = setOf(
    "tool", "source", "state", "summary", "result", "action", "read", "write", "network",
    "invocation", "principal", "approval", "expiry", "path", "old", "new", "args"
)

private val FAILED_LIFECYCLE_STATES = setOf(
    AgentToolLifecycleState.FAILED,
    AgentToolLifecycleState.DENIED,
    AgentToolLifecycleState.CANCELLED,
    AgentToolLifecycleState.NOT_EXECUTED,
    AgentToolLifecycleState.EXPIRED
)

internal fun lifecycleState(
    eventType: String,
    stateToken: String?,
    rawSummary: String?
): AgentToolLifecycleState {
    parseStateToken(stateToken)?.let { return it }
    val inferred = inferStateFromText(rawSummary)
    return when (eventType) {
        AgentEventTypes.TOOL_PROPOSED ->
            inferred?.takeIf { it in FAILED_LIFECYCLE_STATES } ?: AgentToolLifecycleState.PROPOSED
        AgentEventTypes.TOOL_APPROVAL_REQUESTED ->
            inferred?.takeIf {
                it == AgentToolLifecycleState.EXPIRED ||
                    it == AgentToolLifecycleState.DENIED ||
                    it == AgentToolLifecycleState.CANCELLED
            } ?: AgentToolLifecycleState.WAITING_APPROVAL
        AgentEventTypes.TOOL_STARTED ->
            inferred?.takeIf { it in FAILED_LIFECYCLE_STATES } ?: AgentToolLifecycleState.RUNNING
        AgentEventTypes.TOOL_APPROVAL_RESOLVED ->
            inferred ?: AgentToolLifecycleState.UNKNOWN
        AgentEventTypes.TOOL_RESULT ->
            inferred ?: AgentToolLifecycleState.SUCCEEDED
        else -> inferred ?: AgentToolLifecycleState.UNKNOWN
    }
}

internal fun parseStateToken(raw: String?): AgentToolLifecycleState? {
    val token = raw?.trim()?.lowercase(Locale.ROOT)
        ?.replace('-', '_')
        ?.replace(' ', '_')
        ?.trim(':', '=', '"', '\'')
        ?: return null
    return when (token) {
        "proposed", "prepared", "validated", "ready" -> AgentToolLifecycleState.PROPOSED
        "waiting_approval", "pending_approval", "needs_approval", "awaiting_approval",
        "approval_requested", "pending", "ask" -> AgentToolLifecycleState.WAITING_APPROVAL
        "approved", "allowed", "allow_once", "authorized" -> AgentToolLifecycleState.APPROVED
        "running", "started", "executing", "in_progress", "start" -> AgentToolLifecycleState.RUNNING
        "succeeded", "success", "ok", "completed", "complete", "done", "finished" ->
            AgentToolLifecycleState.SUCCEEDED
        "failed", "failure", "error", "errored" -> AgentToolLifecycleState.FAILED
        "denied", "deny", "rejected", "refused", "permission_denied" ->
            AgentToolLifecycleState.DENIED
        "cancelled", "canceled", "cancel", "aborted", "stopped", "stopping" ->
            AgentToolLifecycleState.CANCELLED
        "not_executed", "notexecuted", "skipped" -> AgentToolLifecycleState.NOT_EXECUTED
        "expired", "timeout", "timed_out", "timedout" -> AgentToolLifecycleState.EXPIRED
        "unknown", "indeterminate" -> AgentToolLifecycleState.UNKNOWN
        else -> null
    }
}

private fun inferStateFromText(raw: String?): AgentToolLifecycleState? {
    val text = raw?.lowercase(Locale.ROOT) ?: return null
    if (text.isBlank()) return null
    fun has(vararg markers: String): Boolean = markers.any { text.contains(it) }
    return when {
        has("denied", "deny", "rejected", "refused", "permission denied", "拒绝", "未授权", "not allowed") ->
            AgentToolLifecycleState.DENIED
        has("cancelled", "canceled", "aborted", "取消", "已停止", "终止") ->
            AgentToolLifecycleState.CANCELLED
        has("expired", "timed out", "timeout", "过期", "超时") ->
            AgentToolLifecycleState.EXPIRED
        has("not executed", "not_executed", "notexecuted", "未执行", "未运行") ->
            AgentToolLifecycleState.NOT_EXECUTED
        has(
            "failed", "failure", "error", "offline", "unavailable", "not_found",
            "tool_not_found", "missing_tool", "失败", "错误", "异常", "断线", "不可用", "缺少"
        ) -> AgentToolLifecycleState.FAILED
        has("succeeded", "success", "completed", "done", "finished", "成功", "完成", "已写入", "已读取") ->
            AgentToolLifecycleState.SUCCEEDED
        has("approved", "allowed", "allow_once", "已允许", "已授权", "允许一次") ->
            AgentToolLifecycleState.APPROVED
        has("waiting", "pending", "待授权", "等待授权") ->
            AgentToolLifecycleState.WAITING_APPROVAL
        else -> null
    }
}

internal fun sanitizeToolKey(raw: String?): String? {
    val trimmed = raw?.trim()?.trim('"', '\'', '`') ?: return null
    if (trimmed.isEmpty()) return null
    val cleaned = buildString(trimmed.length) {
        trimmed.forEach { char ->
            if (char in TOOL_KEY_CHARS) append(char)
        }
    }
    if (cleaned.length < 2 || cleaned.none { it.isLetter() }) return null
    return cleaned.take(MAX_TOOL_KEY_CHARS)
}

internal fun legacyToolKey(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val match = LEGACY_TOOL_PREFIX.find(text) ?: return null
    return sanitizeToolKey(match.groupValues[1])
}

internal fun resolveToolSource(rawSource: String?, toolKey: String?): AgentToolSourceUi {
    val token = rawSource?.trim()?.take(MAX_TOOL_KEY_CHARS).orEmpty()
    if (token.isNotEmpty()) {
        val lower = token.lowercase(Locale.ROOT)
        return when {
            lower in BUILT_IN_TOKENS || lower.startsWith("builtin:") ||
                lower.startsWith("builtin/") || lower.startsWith("built_in:") ->
                AgentToolSourceUi(AgentToolSourceKind.BUILT_IN, "内置工具")
            lower == "skill" -> skillSourceFromToolKey(toolKey)
            lower.startsWith("skill:") || lower.startsWith("skill/") ->
                skillSource(token.substringAfter(':').substringAfter('/'))
            lower == "package" || lower == "pkg" -> packageSourceFromToolKey(toolKey)
            lower.startsWith("package:") || lower.startsWith("pkg:") ||
                lower.startsWith("package/") ->
                packageSource(token.substringAfter(':').substringAfter('/'))
            lower == "mcp" -> mcpSourceFromToolKey(toolKey)
            lower.startsWith("mcp:") -> mcpSource(token.substringAfter(':'))
            else -> AgentToolSourceUi.unknown()
        }
    }
    val key = toolKey?.lowercase(Locale.ROOT).orEmpty()
    return when {
        key.startsWith("mcp:") -> mcpSourceFromToolKey(toolKey)
        key.startsWith("pkg.") -> packageSourceFromToolKey(toolKey)
        key.startsWith("skill:") || key.startsWith("skill.") ->
            skillSourceFromToolKey(toolKey)
        key.startsWith("workspace.") || key.startsWith("artifact.") ->
            AgentToolSourceUi(AgentToolSourceKind.BUILT_IN, "内置工具")
        else -> AgentToolSourceUi.unknown()
    }
}

private fun skillSourceFromToolKey(toolKey: String?): AgentToolSourceUi {
    val key = toolKey.orEmpty()
    val body = when {
        key.startsWith("skill:") -> key.removePrefix("skill:")
        key.startsWith("skill.") -> key.removePrefix("skill.")
        else -> ""
    }
    return skillSource(body.substringBefore(':').substringBefore('.'))
}

private fun packageSourceFromToolKey(toolKey: String?): AgentToolSourceUi {
    val key = toolKey.orEmpty()
    if (!key.startsWith("pkg.")) return packageSource("")
    val body = key.removePrefix("pkg.")
    return packageSource(body.substringBeforeLast('.', body))
}

private fun mcpSourceFromToolKey(toolKey: String?): AgentToolSourceUi {
    val key = toolKey.orEmpty()
    return if (key.startsWith("mcp:")) mcpSource(key.substringAfter(':')) else mcpSource("")
}

private val BUILT_IN_TOKENS = setOf("builtin", "built_in", "built-in", "core", "internal")

private fun skillSource(rawId: String): AgentToolSourceUi {
    val id = sourceSegment(rawId)
    val label = if (id == null) "Skill" else "Skill · $id"
    return AgentToolSourceUi(AgentToolSourceKind.SKILL, boundedLabel(label))
}

private fun packageSource(rawBody: String): AgentToolSourceUi {
    val body = rawBody.trim()
    if (body.isEmpty()) return AgentToolSourceUi(AgentToolSourceKind.PACKAGE, "工具包")
    val id = sourceSegment(body.substringBefore('@'))
    val version = sourceSegment(body.substringAfter('@', ""))
    val label = when {
        id != null && version != null -> "工具包 · $id @ $version"
        id != null -> "工具包 · $id"
        else -> "工具包"
    }
    return AgentToolSourceUi(AgentToolSourceKind.PACKAGE, boundedLabel(label))
}

private fun mcpSource(rawBody: String): AgentToolSourceUi {
    val body = rawBody.trim()
    if (body.isEmpty()) return AgentToolSourceUi(AgentToolSourceKind.MCP, "MCP")
    val server = sourceSegment(body.substringBefore(':').substringBefore('/'))
    val label = if (server == null) "MCP" else "MCP · $server"
    return AgentToolSourceUi(AgentToolSourceKind.MCP, boundedLabel(label))
}

private fun sourceSegment(raw: String): String? {
    val cleaned = buildString(raw.length) {
        raw.trim().forEach { char ->
            if (char in SOURCE_SEGMENT_CHARS) append(char)
        }
    }.trim('.', '-', '_', '/')
    if (cleaned.isEmpty()) return null
    return cleaned.take(MAX_SOURCE_SEGMENT_CHARS)
}

private fun boundedLabel(label: String): String =
    if (label.length <= MAX_SOURCE_LABEL_CHARS) label else label.take(MAX_SOURCE_LABEL_CHARS - 1) + "…"

internal fun stripToolPrefix(raw: String, toolKey: String?): String {
    val value = raw.trim()
    if (value.isEmpty()) return value
    if (toolKey != null && value.startsWith(toolKey, ignoreCase = true)) {
        val rest = value.substring(toolKey.length).trimStart(':', '：', '-', '·', ' ', '\n')
        if (rest.isNotEmpty()) return rest
    }
    val match = LEGACY_TOOL_PREFIX.find(value) ?: return value
    if (match.range.first != 0) return value
    return value.substring(match.range.last + 1).trimStart(':', '：', '-', '·', ' ', '\n')
}

internal fun scrubSensitiveText(raw: String): String {
    var value = SECRET_ASSIGNMENT.replace(raw) { match ->
        "${match.groupValues[1]}=***"
    }
    value = BEARER_TOKEN.replace(value, "Bearer ***")
    value = OPENAI_STYLE_KEY.replace(value, "sk-***")
    value = AWS_STYLE_KEY.replace(value, "AKIA***")
    return value
}

internal fun boundedSafeText(raw: String?, maxChars: Int): String? {
    val value = scrubSensitiveText(raw?.trim().orEmpty()).trim()
    if (value.isEmpty()) return null
    return if (value.length <= maxChars) {
        value
    } else {
        value.take(maxChars).trimEnd() + "…（内容已截断）"
    }
}
