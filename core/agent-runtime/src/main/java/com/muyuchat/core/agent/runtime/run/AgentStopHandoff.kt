package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry

/**
 * Handoff summary produced when the user stops a run.
 *
 * Mirrors the "press stop → short wrap-up, then say *continue*" behaviour: the three structured facts
 * are what a resumed turn needs, and [rendered] is the single Chinese line shown in the chat.
 */
data class AgentStopHandoffSummary(
    val didWhat: String,
    val sawWhat: String,
    val leftToDo: String,
    val rendered: String
)

/** User-facing rendering budget; the bubble is one short line, never a report. */
const val AGENT_STOP_HANDOFF_MAX_RENDERED_CHARS: Int = 200

/** Per-field budget so one huge tool result cannot crowd out the other two facts. */
private const val FIELD_MAX_CHARS: Int = 120

/** Value of [AgentStopHandoffSummary.sawWhat]/[AgentStopHandoffSummary.leftToDo] when there is none. */
const val AGENT_STOP_HANDOFF_NONE: String = "无"

/**
 * Builds a stop-handoff summary from the authoritative transcript.
 *
 * Pure: no clock, no I/O, and neither [run] nor [transcript] is mutated; identical inputs always
 * produce an identical summary. An empty transcript (stopped before the first step) is a valid input
 * and yields an explicit "0 steps" handoff instead of throwing or inventing work.
 */
fun buildHandoff(
    run: AgentRun,
    transcript: List<AgentTranscriptEntry>
): AgentStopHandoffSummary {
    val steps = transcript.count { it is AgentTranscriptEntry.AssistantEnvelope }
    val toolCalls = transcript.count { it is AgentTranscriptEntry.ToolResult }
    val lastToolResult = transcript.lastOrNull { it is AgentTranscriptEntry.ToolResult }
        as? AgentTranscriptEntry.ToolResult

    val didWhat = "执行了 $steps 步 / 调用 $toolCalls 次工具"
    val sawWhat = lastToolResult?.let { summarize(it.result) }?.takeIf { it.isNotEmpty() }
        ?: AGENT_STOP_HANDOFF_NONE
    // Only an assistant envelope can be a not-yet-finished intention; anything after it means the
    // run stopped on a settled step.
    val leftToDo = (transcript.lastOrNull() as? AgentTranscriptEntry.AssistantEnvelope)
        ?.let { summarizeEnvelope(it.rawEnvelope) }
        ?.takeIf { it.isNotEmpty() }
        ?: AGENT_STOP_HANDOFF_NONE

    return AgentStopHandoffSummary(
        didWhat = didWhat,
        sawWhat = sawWhat,
        leftToDo = leftToDo,
        rendered = render(run, didWhat, sawWhat, leftToDo)
    )
}

/** One Chinese sentence for the user, hard-capped at [AGENT_STOP_HANDOFF_MAX_RENDERED_CHARS]. */
private fun render(
    run: AgentRun,
    didWhat: String,
    sawWhat: String,
    leftToDo: String
): String {
    val nextStep = if (leftToDo == AGENT_STOP_HANDOFF_NONE) {
        "下次说「继续」可以接着跑。"
    } else {
        "下次说「继续」会从这里接着做。"
    }
    val text = "已停止（run ${run.runId}）：本轮$didWhat；看到：$sawWhat；待办：$leftToDo。$nextStep"
    return if (text.length <= AGENT_STOP_HANDOFF_MAX_RENDERED_CHARS) {
        text
    } else {
        text.take(AGENT_STOP_HANDOFF_MAX_RENDERED_CHARS - 1) + "…"
    }
}

/**
 * The assistant envelope is protocol text (usually JSON carrying a tool call). Prefer a
 * human-readable field when one is present, otherwise fall back to the compact raw text.
 */
private fun summarizeEnvelope(rawEnvelope: String): String {
    val trimmed = rawEnvelope.trim()
    if (trimmed.isEmpty()) return ""
    val preferred = PREFERRED_TEXT_KEYS
        .mapNotNull { key -> extractQuoted(trimmed, key) }
        .firstOrNull { it.isNotBlank() }
    return truncate(preferred ?: trimmed.replace(WHITESPACE, " "), FIELD_MAX_CHARS)
}

/** `"key": "value"` extraction that tolerates escaped quotes; a heuristic, never a parser. */
private fun extractQuoted(source: String, key: String): String? {
    val marker = "\"$key\""
    var index = source.indexOf(marker)
    while (index >= 0) {
        val colon = source.indexOf(':', index + marker.length)
        if (colon >= 0) {
            val start = source.indexOf('"', colon + 1)
            if (start >= 0 && source.substring(colon + 1, start).isBlank()) {
                val value = StringBuilder()
                var position = start + 1
                while (position < source.length) {
                    val char = source[position]
                    if (char == '\\' && position + 1 < source.length) {
                        value.append(source[position + 1])
                        position += 2
                        continue
                    }
                    if (char == '"') return value.toString()
                    value.append(char)
                    position++
                }
            }
        }
        index = source.indexOf(marker, index + 1)
    }
    return null
}

/** Compact, bounded preview of one tool observation. Never the full payload. */
internal fun summarize(value: AgentJsonValue): String =
    truncate(renderValue(value, depth = 0), FIELD_MAX_CHARS)

private fun renderValue(value: AgentJsonValue, depth: Int): String = when (value) {
    is AgentJsonValue.Str -> value.value
    is AgentJsonValue.Num -> value.literal
    is AgentJsonValue.Bool -> if (value.value) "true" else "false"
    AgentJsonValue.Null -> "null"
    is AgentJsonValue.Arr -> when {
        value.items.isEmpty() -> "[]"
        depth >= MAX_RENDER_DEPTH -> "[${value.items.size} 项]"
        else -> value.items.joinToString(prefix = "[", postfix = "]", separator = ", ") {
            renderValue(it, depth + 1)
        }
    }

    is AgentJsonValue.Obj -> {
        val preferred = PREFERRED_TEXT_KEYS.firstNotNullOfOrNull { key ->
            (value.fields.firstOrNull { it.first == key }?.second as? AgentJsonValue.Str)
                ?.takeIf { it.value.isNotBlank() }
        }
        when {
            preferred != null -> preferred.value
            depth >= MAX_RENDER_DEPTH -> "{${value.fields.size} 个字段}"
            else -> value.fields.joinToString(prefix = "{", postfix = "}", separator = ", ") { field ->
                "${field.first}=${renderValue(field.second, depth + 1)}"
            }
        }
    }
}

private fun truncate(text: String, maxChars: Int): String {
    val normalized = text.replace(WHITESPACE, " ").trim()
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 1) + "…"
}

private val WHITESPACE = Regex("\\s+")

private val PREFERRED_TEXT_KEYS: List<String> =
    listOf("summary", "message", "text", "output", "result", "error", "next", "intent")

private const val MAX_RENDER_DEPTH: Int = 2
