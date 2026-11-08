package com.muyuchat.mca.agent.projection

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role

/**
 * Turns a *paused* Agent run into the one-line wrap-up the chat shows after the user presses stop.
 *
 * ## Why this exists separately from [AgentTurnSummaryWriter]
 *
 * That writer is SUCCEEDED-only on purpose: a failed or cancelled run's transcript ends in an error,
 * so replaying its last text as a "conclusion" would teach the model that an aborted attempt was the
 * answer. A **paused** run differs in kind — it did not fail, it stopped because the user said stop,
 * and the contract names exactly one continuable state: `AgentRunState.PAUSED` (the continue planner
 * refuses every other state). The two writers answer two different questions:
 *  - succeeded → *what did it conclude?* → a summary that may re-enter later prompts
 *  - paused → *where did it stop, and can I say continue?* → a handoff that must **not** re-enter
 *    the prompt as a conclusion
 *
 * ## Why it reads events instead of a transcript
 *
 * The runtime's `AgentTranscriptEntry` list is in-memory: it exists while the loop runs and is not
 * persisted. By the time this sink is called the run is already settled, so a durable reader can
 * only see the journal's `AgentEvent`s. [buildHandoff] is therefore not reused directly (it takes a
 * transcript); the same three facts are derived from the event stream, which is what the journal
 * actually keeps. [AgentStopHandoffSummary]'s shape and budgets are honoured so the chat line stays
 * one short line.
 *
 * ## Why the handoff never enters the model context
 *
 * Its `AgentTurnTrace.kind` is `"step"`, not `AgentTurnTrace.SUMMARY`, and `finalSummary` is blank.
 * The projection (`ChatContextComposer.agentContextProjection()`) keeps only `summary` traces with a
 * non-blank `finalSummary` and drops every other agent-tagged message, so this bubble is visible to
 * the user but invisible to the next prompt. Deliberate: "执行了 3 步 / 调用 2 次工具" is bookkeeping
 * about an interrupted attempt; feeding it forward would bias the resumed turn toward believing the
 * work was done.
 */
internal object AgentStopHandoffWriter {

    /** Wire value for "an agent turn that is visible but is not a conclusion". */
    const val HANDOFF_KIND: String = "step"

    /** Same user-facing budget as the core handoff renderer; the bubble is never a report. */
    private const val MAX_RENDERED_CHARS = 200

    /** Per-fact cap so one huge tool summary cannot crowd out the other facts. */
    private const val FACT_MAX_CHARS = 120

    /** Rendered when a fact has no value. Matches `AGENT_STOP_HANDOFF_NONE`. */
    private const val NONE = "无"

    /**
     * Returns the handoff message for a paused [run], or `null` when nothing may be written.
     *
     * Rejects, in order, each for its own reason:
     *  - blank session — a message with nowhere to live is an orphan;
     *  - any state other than [AgentRunState.PAUSED] — only a user stop is continuable. Offering
     *    "say continue" on a CANCELLED run would be a lie: the planner refuses it;
     *  - no visible activity — a paused run with neither a model reply nor a tool result has nothing
     *    honest to say, so it stays silent rather than emitting a filler line.
     */
    fun messageFor(
        run: AgentRun,
        events: List<AgentEvent>,
        chatSessionId: String?,
        createdAtMillis: Long = System.currentTimeMillis()
    ): ChatMessage? {
        if (chatSessionId.isNullOrBlank()) return null
        if (run.state != AgentRunState.PAUSED) return null

        // Only this run's events describe this run.
        val mine = events.filter { it.runId == run.runId }
        val replies = mine.count { it.type == AgentEventTypes.MODEL_ATTEMPT_SETTLED }
        val toolCalls = mine.count { it.type == AgentEventTypes.TOOL_RESULT }
        if (replies == 0 && toolCalls == 0) return null

        val sawWhat = mine.asReversed()
            .firstOrNull { it.type == AgentEventTypes.TOOL_RESULT && !it.summary.isNullOrBlank() }
            ?.summary
            ?.let(::bounded)
            ?: NONE
        // What is still owed: the last text the model produced is the best available statement of
        // intent, because nothing after it settled the step.
        val leftToDo = mine.asReversed()
            .firstOrNull { it.type == AgentEventTypes.MODEL_ATTEMPT_SETTLED && !it.summary.isNullOrBlank() }
            ?.summary
            ?.let(::bounded)
            ?: NONE

        val rendered = buildString {
            append("已停止：执行了 ")
            append(replies)
            append(" 步 / 调用 ")
            append(toolCalls)
            append(" 次工具。")
            append("看到 ")
            append(sawWhat)
            append("；还差 ")
            append(leftToDo)
            append("。说「继续」我可以接着做。")
        }.take(MAX_RENDERED_CHARS)

        return ChatMessage(
            role = Role.ASSISTANT,
            content = rendered,
            createdAt = createdAtMillis,
            agentTrace = AgentTurnTrace(
                turnId = run.runId,
                kind = HANDOFF_KIND,
                // Deliberately blank: this turn may not enter a later prompt. The text lives in
                // `content` for the user only.
                finalSummary = ""
            )
        )
    }

    private fun bounded(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= FACT_MAX_CHARS) trimmed else trimmed.take(FACT_MAX_CHARS) + "…"
    }
}
