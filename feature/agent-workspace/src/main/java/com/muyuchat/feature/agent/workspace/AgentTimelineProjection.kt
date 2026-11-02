package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import java.util.Locale

/**
 * Timeline projection over the sanitized event stream.
 *
 * Only event summaries are used. No model reasoning or raw provider text is part of the event
 * contract, and this projection never renders streaming deltas. All five tool lifecycle events for
 * one invocation collapse into one row whose state moves proposed -> waiting/approved -> running ->
 * success/denied/cancelled/expired. Repeated identical terminal activity still collapses into one
 * row with a repeat count. Parameters/errors are available through the bounded expanded details,
 * with argument fields deliberately omitted.
 */

private val HIDDEN_EVENT_TYPES = setOf(
    AgentEventTypes.TASK_CREATED,
    AgentEventTypes.RUN_QUEUED,
    AgentEventTypes.RUN_STATE_CHANGED
)

private val TOOL_EVENT_TYPES = setOf(
    AgentEventTypes.TOOL_PROPOSED,
    AgentEventTypes.TOOL_APPROVAL_REQUESTED,
    AgentEventTypes.TOOL_APPROVAL_RESOLVED,
    AgentEventTypes.TOOL_STARTED,
    AgentEventTypes.TOOL_RESULT
)

private val FAILURE_MARKERS = listOf(
    "failed", "denied", "unknown", "error:", "失败", "拒绝", "错误", "未知"
)

private const val MAX_DETAIL_LINES = 10
private const val MAX_DETAIL_CHARS = 800
private const val MAX_PREVIEW_CHARS = 160
private const val MAX_FINAL_ANSWER_CHARS = 8_192
private const val HINT_SCAN_EVENTS = 40

internal enum class AgentTimelineHint { NONE, MCP_OFFLINE, SKILL_MISSING_TOOL }

/**
 * Forward-compatible status hint derived from a bounded safe summary. V1 has no dedicated
 * MCP/skill event type; when K12/K13 land a typed event, only this function changes.
 */
internal fun detectTimelineHint(events: List<AgentEvent>): AgentTimelineHint {
    val recent = events.takeLast(HINT_SCAN_EVENTS).asReversed()
    for (event in recent) {
        if (event.type !in TOOL_EVENT_TYPES) continue
        val summary = event.summary?.uppercase(Locale.ROOT) ?: continue
        if (summary.contains("MCP_OFFLINE")) return AgentTimelineHint.MCP_OFFLINE
        if (summary.contains("SKILL_MISSING_TOOL") ||
            (summary.contains("SKILL") && summary.contains("TOOL_NOT_FOUND"))
        ) {
            return AgentTimelineHint.SKILL_MISSING_TOOL
        }
    }
    return AgentTimelineHint.NONE
}

/** Final answer text is the safe summary of the latest terminal event, bounded for display. */
internal fun extractFinalAnswer(events: List<AgentEvent>): String? {
    val terminal = events.asReversed().firstOrNull {
        it.type == AgentEventTypes.RUN_TERMINAL && !it.summary.isNullOrBlank()
    } ?: return null
    val text = terminal.summary?.trim().orEmpty()
    if (text.isEmpty()) return null
    return if (text.length <= MAX_FINAL_ANSWER_CHARS) text else text.take(MAX_FINAL_ANSWER_CHARS) + "\n…（内容已截断）"
}

internal fun projectTimeline(
    events: List<AgentEvent>,
    expandedRowKeys: Set<String>
): List<AgentTimelineRow> {
    val visible = events.filter { it.type !in HIDDEN_EVENT_TYPES }.sortedBy { it.sequence }
    if (visible.isEmpty()) return emptyList()

    val activities = mutableListOf<TimelineActivity>()
    for (event in visible) {
        val current = activities.lastOrNull()
        if (current != null && current.canAbsorb(event)) {
            current.absorb(event)
        } else {
            activities += TimelineActivity(event)
        }
    }

    val rows = mutableListOf<TimelineRowDraft>()
    for (activity in activities) {
        val row = activity.toDraft()
        val previous = rows.lastOrNull()
        if (previous != null && previous.canMerge(row)) {
            rows[rows.lastIndex] = previous.merge(row)
        } else {
            rows += row
        }
    }
    return rows.map { it.toUi(expandedRowKeys) }
}

/**
 * One contiguous activity. Tool events only absorb a later phase of the same invocation; a second
 * terminal result without a shared invocation id becomes its own row and is then collapsed by the
 * repeat-count merge, never shown as a second lifecycle terminal.
 */
private class TimelineActivity(first: AgentEvent) {
    val events = mutableListOf(first)
    private val toolActivity = first.type in TOOL_EVENT_TYPES
    private var toolKey: String? = null
    private var invocationKey: String? = null
    private var state: AgentToolLifecycleState = AgentToolLifecycleState.UNKNOWN
    private var source: AgentToolSourceUi = AgentToolSourceUi.unknown()

    init {
        if (toolActivity) {
            val parsed = parseToolLifecycleEvent(first)
            toolKey = parsed.toolKey
            invocationKey = eventInvocationKey(first, parsed)
            state = parsed.state
            source = parsed.source
        }
    }

    fun canAbsorb(next: AgentEvent): Boolean {
        if (!toolActivity || next.type !in TOOL_EVENT_TYPES) return false
        val parsed = parseToolLifecycleEvent(next)
        val nextInvocation = eventInvocationKey(next, parsed)
        val sameInvocation = invocationKey != null && nextInvocation == invocationKey
        val sameTool = toolKey != null && parsed.toolKey != null && toolKey == parsed.toolKey
        val sameUnknownText = toolKey == null && parsed.toolKey == null &&
            events.last().summary == next.summary
        if (!sameInvocation && !sameTool && !sameUnknownText) return false

        val nextState = parsed.state
        if (nextState.rank > state.rank) return true
        if (nextState.terminal && state.terminal) return sameInvocation
        return sameInvocation
    }

    fun absorb(next: AgentEvent) {
        val parsed = parseToolLifecycleEvent(next)
        val nextState = parsed.state
        if (nextState.rank > state.rank) {
            state = nextState
        } else if (nextState.terminal && state.terminal &&
            nextState.terminalPriority > state.terminalPriority
        ) {
            state = nextState
        }
        if (toolKey == null) toolKey = parsed.toolKey
        if (invocationKey == null) invocationKey = eventInvocationKey(next, parsed)
        if (parsed.source.kind != AgentToolSourceKind.UNKNOWN) source = parsed.source
        events += next
    }

    fun toDraft(): TimelineRowDraft {
        val first = events.first()
        val parsedEvents = if (toolActivity) events.map(::parseToolLifecycleEvent) else emptyList()
        val toolSource = source
        val summary = parsedEvents.asReversed()
            .firstNotNullOfOrNull { it.summary }
            ?.let(::previewText)
            ?: if (!toolActivity) previewText(first.summary) else null
        val kind = when {
            !toolActivity -> timelineKind(first.type)
            events.any {
                it.type == AgentEventTypes.TOOL_STARTED || it.type == AgentEventTypes.TOOL_RESULT
            } -> AgentTimelineKind.TOOL
            first.type == AgentEventTypes.TOOL_APPROVAL_REQUESTED ||
                first.type == AgentEventTypes.TOOL_APPROVAL_RESOLVED -> AgentTimelineKind.APPROVAL
            else -> AgentTimelineKind.TOOL
        }
        return TimelineRowDraft(
            key = first.eventId,
            kind = kind,
            tone = if (toolActivity) toolStateTone(state) else eventTone(first.type, first.summary),
            title = if (toolActivity) {
                toolKey ?: toolFallbackTitle(first.type)
            } else {
                activityTitle(first.type)
            },
            summary = summary,
            timeText = formatClockTime(first.createdAtMillis),
            eventIds = events.map { it.eventId },
            details = buildDetails(events, toolActivity),
            repeatCount = 1,
            toolKey = toolKey,
            toolState = if (toolActivity) state else null,
            source = if (toolActivity) toolSource else null
        )
    }
}

private fun eventInvocationKey(event: AgentEvent, parsed: AgentToolLifecycleEvent): String? =
    event.causationId?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.invocationId

private fun buildDetails(events: List<AgentEvent>, toolActivity: Boolean): List<String> {
    val lines = events.map { event ->
        val label = eventTypeLabel(event.type)
        val safeSummary = if (toolActivity && event.type in TOOL_EVENT_TYPES) {
            val parsed = parseToolLifecycleEvent(event)
            when {
                parsed.summary != null -> parsed.summary
                parsed.argumentsOmitted -> "参数已省略（不展示原始参数）"
                parsed.malformedSummary -> "摘要不可解析（原始内容未展示）"
                else -> null
            }
        } else {
            boundedText(event.summary, MAX_DETAIL_CHARS)
        }
        val prefix = "#${event.sequence} · ${formatClockTime(event.createdAtMillis)} · $label"
        if (safeSummary == null) prefix else "$prefix\n$safeSummary"
    }
    if (lines.size <= MAX_DETAIL_LINES) return lines
    return lines.take(MAX_DETAIL_LINES) + "…（还有 ${lines.size - MAX_DETAIL_LINES} 条活动未展开）"
}

private fun timelineKind(type: String): AgentTimelineKind = when {
    type == AgentEventTypes.TOOL_APPROVAL_REQUESTED || type == AgentEventTypes.TOOL_APPROVAL_RESOLVED ->
        AgentTimelineKind.APPROVAL
    type in TOOL_EVENT_TYPES -> AgentTimelineKind.TOOL
    type == AgentEventTypes.MODEL_ATTEMPT_STARTED || type == AgentEventTypes.MODEL_ATTEMPT_SETTLED ->
        AgentTimelineKind.MODEL
    type == AgentEventTypes.QUESTION_ASKED || type == AgentEventTypes.QUESTION_ANSWERED ->
        AgentTimelineKind.QUESTION
    type == AgentEventTypes.ARTIFACT_COMMITTED -> AgentTimelineKind.ARTIFACT
    type == AgentEventTypes.BUDGET_PAUSED -> AgentTimelineKind.BUDGET
    type == AgentEventTypes.PROTOCOL_ERROR -> AgentTimelineKind.ERROR
    type == AgentEventTypes.RUN_INTERRUPTED || type == AgentEventTypes.RUN_SUPERSEDED ||
        type == AgentEventTypes.RUN_TERMINAL -> AgentTimelineKind.RUN
    else -> AgentTimelineKind.OTHER
}

private fun toolFallbackTitle(type: String): String = when (type) {
    AgentEventTypes.TOOL_PROPOSED -> "准备工具调用"
    AgentEventTypes.TOOL_APPROVAL_REQUESTED -> "请求工具授权"
    AgentEventTypes.TOOL_APPROVAL_RESOLVED -> "授权已处理"
    AgentEventTypes.TOOL_STARTED -> "工具调用"
    AgentEventTypes.TOOL_RESULT -> "工具结果"
    else -> "工具活动"
}

private fun activityTitle(type: String): String = when (type) {
    AgentEventTypes.MODEL_ATTEMPT_STARTED -> "模型生成"
    AgentEventTypes.MODEL_ATTEMPT_SETTLED -> "模型回复完成"
    AgentEventTypes.PROTOCOL_ERROR -> "协议错误"
    AgentEventTypes.QUESTION_ASKED -> "向你提问"
    AgentEventTypes.QUESTION_ANSWERED -> "问题已回答"
    AgentEventTypes.ARTIFACT_COMMITTED -> "产物已保存"
    AgentEventTypes.BUDGET_PAUSED -> "运行达到上限"
    AgentEventTypes.RUN_INTERRUPTED -> "运行中断"
    AgentEventTypes.RUN_SUPERSEDED -> "运行被继续运行替代"
    AgentEventTypes.RUN_TERMINAL -> "运行结束"
    else -> "运行活动"
}

private fun previewText(rawSummary: String?): String? {
    val firstLine = rawSummary?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?: return null
    if (firstLine.isEmpty()) return null
    return if (firstLine.length <= MAX_PREVIEW_CHARS) firstLine else firstLine.take(MAX_PREVIEW_CHARS) + "…"
}

private fun toolStateTone(state: AgentToolLifecycleState): AgentStatusTone = when (state) {
    AgentToolLifecycleState.PROPOSED,
    AgentToolLifecycleState.APPROVED,
    AgentToolLifecycleState.UNKNOWN -> AgentStatusTone.NEUTRAL
    AgentToolLifecycleState.WAITING_APPROVAL,
    AgentToolLifecycleState.CANCELLED,
    AgentToolLifecycleState.NOT_EXECUTED,
    AgentToolLifecycleState.EXPIRED -> AgentStatusTone.WARNING
    AgentToolLifecycleState.RUNNING -> AgentStatusTone.ACTIVE
    AgentToolLifecycleState.SUCCEEDED -> AgentStatusTone.SUCCESS
    AgentToolLifecycleState.FAILED,
    AgentToolLifecycleState.DENIED -> AgentStatusTone.ERROR
}

private fun eventTone(type: String, summary: String?): AgentStatusTone {
    val normalized = summary?.lowercase(Locale.ROOT).orEmpty()
    val failure = FAILURE_MARKERS.any { normalized.contains(it) }
    return when {
        type == AgentEventTypes.PROTOCOL_ERROR -> AgentStatusTone.ERROR
        type == AgentEventTypes.RUN_INTERRUPTED || type == AgentEventTypes.BUDGET_PAUSED ->
            AgentStatusTone.WARNING
        type == AgentEventTypes.ARTIFACT_COMMITTED -> AgentStatusTone.SUCCESS
        type == AgentEventTypes.MODEL_ATTEMPT_STARTED || type == AgentEventTypes.QUESTION_ASKED ->
            AgentStatusTone.ACTIVE
        type == AgentEventTypes.RUN_TERMINAL && failure -> AgentStatusTone.ERROR
        else -> AgentStatusTone.NEUTRAL
    }
}

private fun toneRank(tone: AgentStatusTone): Int = when (tone) {
    AgentStatusTone.NEUTRAL -> 0
    AgentStatusTone.SUCCESS -> 1
    AgentStatusTone.ACTIVE -> 2
    AgentStatusTone.WARNING -> 3
    AgentStatusTone.ERROR -> 4
}

private fun eventTypeLabel(type: String): String = when (type) {
    AgentEventTypes.MODEL_ATTEMPT_STARTED -> "模型开始"
    AgentEventTypes.MODEL_ATTEMPT_SETTLED -> "模型结束"
    AgentEventTypes.PROTOCOL_ERROR -> "协议错误"
    AgentEventTypes.TOOL_PROPOSED -> "工具提案"
    AgentEventTypes.TOOL_APPROVAL_REQUESTED -> "申请授权"
    AgentEventTypes.TOOL_APPROVAL_RESOLVED -> "授权结果"
    AgentEventTypes.TOOL_STARTED -> "工具开始"
    AgentEventTypes.TOOL_RESULT -> "工具结果"
    AgentEventTypes.QUESTION_ASKED -> "模型提问"
    AgentEventTypes.QUESTION_ANSWERED -> "用户回答"
    AgentEventTypes.ARTIFACT_COMMITTED -> "产物提交"
    AgentEventTypes.BUDGET_PAUSED -> "预算暂停"
    AgentEventTypes.RUN_INTERRUPTED -> "运行中断"
    AgentEventTypes.RUN_SUPERSEDED -> "运行替代"
    AgentEventTypes.RUN_TERMINAL -> "运行终态"
    else -> type
}

private data class TimelineRowDraft(
    val key: String,
    val kind: AgentTimelineKind,
    val tone: AgentStatusTone,
    val title: String,
    val summary: String?,
    val timeText: String,
    val eventIds: List<String>,
    val details: List<String>,
    val repeatCount: Int,
    val toolKey: String?,
    val toolState: AgentToolLifecycleState?,
    val source: AgentToolSourceUi?
) {
    fun canMerge(next: TimelineRowDraft): Boolean {
        if (kind != next.kind || title != next.title) return false
        if (kind == AgentTimelineKind.TOOL || kind == AgentTimelineKind.APPROVAL) {
            if (toolState != next.toolState) return false
            if (toolKey != next.toolKey) return false
            if (source?.kind != next.source?.kind) return false
            if (source?.label != next.source?.label) return false
            // Without a stable tool key, only an exactly repeated summary may collapse; otherwise
            // two different unknown tools would be misrepresented as one repeated activity.
            if (toolKey == null && summary != next.summary) return false
        }
        return true
    }

    fun merge(next: TimelineRowDraft): TimelineRowDraft = copy(
        tone = if (toneRank(next.tone) > toneRank(tone)) next.tone else tone,
        summary = summary ?: next.summary,
        eventIds = eventIds + next.eventIds,
        details = (details + next.details).take(MAX_DETAIL_LINES + 1),
        repeatCount = repeatCount + next.repeatCount
    )

    fun toUi(expandedRowKeys: Set<String>): AgentTimelineRow = AgentTimelineRow(
        key = key,
        kind = kind,
        tone = tone,
        title = title,
        summary = summary,
        timeText = timeText,
        eventIds = eventIds,
        details = details,
        repeatCount = repeatCount,
        expandable = eventIds.size > 1 || details.size > 1 || (summary?.length ?: 0) > 80,
        expanded = key in expandedRowKeys,
        toolKey = toolKey,
        toolState = toolState,
        source = source
    )
}
