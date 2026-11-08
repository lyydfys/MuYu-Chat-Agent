package com.muyuchat.mca.agent.projection

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role

/**
 * Turns one finished Agent run into the single chat message that may enter later prompts.
 *
 * This is the missing *producer* for [AgentTurnTrace]: the projection side already exists
 * (`ChatContextComposer.agentContextProjection()` keeps only a `summary` trace with a non-blank
 * `finalSummary` and drops every other agent-tagged message) but until now nothing created one.
 *
 * Contract, deliberately narrow:
 *  - **Success only.** FAILED / CANCELLED / SUPERSEDED / INTERRUPTED runs return `null`. A failed
 *    run's last visible text is a partial answer or an error hint, not a conclusion; replaying it
 *    later would teach the model that an aborted attempt was the answer.
 *  - **Only the authoritative terminal text.** The summary is read from the newest non-blank
 *    `run.terminal` event summary of *this* run — the value the run owner persisted from
 *    `AgentLoopResult.Completed.finalText` through `boundedTerminalSummary`.
 *  - **Empty is not a message.** Missing/blank summary returns `null` instead of writing an empty
 *    assistant bubble.
 *  - **Pure.** No state write, no coroutine, no I/O and no clock beyond the caller-supplied
 *    timestamp, so the decision is unit-testable without Room, Android or a running owner.
 *
 * Idempotency stays the caller's job and has one stable key: [AgentTurnTrace.turnId] is the run id,
 * so a replayed terminal transition is deduplicated by lookup instead of by text comparison.
 */
internal object AgentTurnSummaryWriter {

    /** Wire value reserved for "this agent turn may enter the model context". */
    const val SUMMARY_KIND = AgentTurnTrace.SUMMARY

    /**
     * Same display budget as the workspace timeline's final-answer reader
     * (`feature/agent-workspace`'s `extractFinalAnswer`). The run owner already bounds the stored
     * text to 1 200 characters, so this cap is a defence against an imported/legacy row, not the
     * normal path.
     */
    private const val MAX_SUMMARY_CHARS = 8_192

    /** Appended once, only when [MAX_SUMMARY_CHARS] actually cut the text. */
    private const val TRUNCATED_MARKER = "\n…（内容已截断）"

    /**
     * Returns the summary message for [run], or `null` when nothing may be written.
     *
     * [chatSessionId] must identify the conversation the summary belongs to: a message without a
     * session has nowhere to live, so a blank id is rejected rather than producing an orphan.
     * [events] are expected to belong to [run]; events of other runs are ignored.
     */
    fun messageFor(
        run: AgentRun,
        events: List<AgentEvent>,
        chatSessionId: String?,
        createdAtMillis: Long = System.currentTimeMillis()
    ): ChatMessage? {
        if (chatSessionId.isNullOrBlank()) return null
        // Only a successful run has a final answer worth carrying forward. A failed or
        // cancelled run is terminal too, but its transcript ends in an error, so writing its
        // last event as a "summary" would teach the model that a failed turn concluded
        // something. The guard is therefore SUCCEEDED-only, not "any terminal state".
        if (run.state != AgentRunState.SUCCEEDED) return null
        val summary = boundedSummary(run, events) ?: return null
        return ChatMessage(
            role = Role.ASSISTANT,
            content = summary,
            createdAt = createdAtMillis,
            agentTrace = AgentTurnTrace(
                turnId = run.runId,
                kind = SUMMARY_KIND,
                finalSummary = summary
            )
        )
    }

    /**
     * Final answer text of exactly this run, or `null` when it carries nothing readable.
     *
     * Mirrors the workspace timeline reader (newest non-blank `run.terminal` summary, trimmed, then
     * length-bounded). It is re-stated here instead of imported because architecture Rule 12 forbids
     * anything under the app agent package from importing `com.muyuchat.feature.agent.*`; the
     * contract module that would host a shared copy is deliberately untouched by this change.
     */
    fun boundedSummary(run: AgentRun, events: List<AgentEvent>): String? {
        val terminal = events.asReversed().firstOrNull { event ->
            event.runId == run.runId &&
                event.type == AgentEventTypes.RUN_TERMINAL &&
                !event.summary.isNullOrBlank()
        } ?: return null
        val text = terminal.summary?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_SUMMARY_CHARS) {
            text
        } else {
            text.take(MAX_SUMMARY_CHARS) + TRUNCATED_MARKER
        }
    }
}
