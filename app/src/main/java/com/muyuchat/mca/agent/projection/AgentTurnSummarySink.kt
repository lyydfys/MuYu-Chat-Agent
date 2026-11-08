package com.muyuchat.mca.agent.projection

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.engine.ChatMessage

/**
 * Seam between a finished Agent run and the chat timeline.
 *
 * The runtime reports *that* a run reached a terminal state; this sink decides whether a chat
 * message should be produced and where it goes. It deliberately takes ids instead of an `AgentRun`
 * so the runtime never has to hand a whole aggregate across the boundary, and so the chat side can
 * be implemented without the agent package leaking into one another.
 *
 * ## Status: mounted and wired
 *
 * The runtime mount point exists (`AgentRunOwner.appendTerminalEventIfNeeded`, the single
 * `RUN_TERMINAL` write) and `AgentHostAssembly` passes `RoomAgentTurnSummarySink`. The blocker
 * that used to prevent this - an Agent task having no chat session binding - is gone: the app
 * records a durable task -> conversation mapping (`FileAgentTaskChatBindingStore`) when the user
 * starts a task from a conversation, so `chatSessionId` resolves for a real run.
 *
 * Two terminal states report here, for two different reasons: SUCCEEDED (its final answer may
 * re-enter later prompts as the one carried-over summary) and PAUSED (the user pressed stop, so
 * the chat gets a wrap-up line that offers "say continue" but never enters the prompt).
 * FAILED / CANCELLED / SUPERSEDED / INTERRUPTED deliberately write nothing.
 */
fun interface AgentTurnSummarySink {
    /**
     * Called once per terminal Agent run.
     *
     * @param runId the run that just became terminal.
     * @param chatSessionId the conversation the summary belongs to, or `null` when the run has no
     *   conversation. A null id is a normal value, not an error: the sink must then write nothing.
     */
    suspend fun onAgentTurnCompleted(runId: String, chatSessionId: String?)
}

/**
 * Production default: records nothing.
 *
 * Deliberately not a silent stub that pretends to work — it performs no I/O, keeps no state and
 * answers nothing, so the "no session binding" state is visible in one place instead of being
 * smeared across the runtime as an unused parameter.
 */
object NoAgentTurnSummarySink : AgentTurnSummarySink {
    override suspend fun onAgentTurnCompleted(runId: String, chatSessionId: String?) = Unit
}

/**
 * The sink body that will be used once a chat session binding exists.
 *
 * Kept pure so it can be unit-tested on the JVM: the run and its events are injected, and the
 * finished message is handed to [deliver] instead of a ViewModel. Every rejection rule lives in
 * [AgentTurnSummaryWriter] (SUCCEEDED only, bounded terminal text, blank session rejected), so this
 * adapter adds exactly one thing: it resolves the run, calls the writer, and drops nothing else.
 *
 * @param loadRun reads the settled run; `null` means the run is gone and nothing may be written.
 * @param loadEvents reads that run's events; the writer only looks at `RUN_TERMINAL`.
 * @param deliver receives the produced message — this is the line that will call the chat state
 *   owner once the binding exists.
 */
internal class ChatAgentTurnSummarySink(
    private val loadRun: suspend (String) -> AgentRun?,
    private val loadEvents: suspend (String) -> List<AgentEvent>,
    private val deliver: suspend (ChatMessage) -> Unit
) : AgentTurnSummarySink {

    override suspend fun onAgentTurnCompleted(runId: String, chatSessionId: String?) {
        if (chatSessionId.isNullOrBlank()) return
        val run = loadRun(runId) ?: return
        val message = AgentTurnSummaryWriter.messageFor(
            run = run,
            events = loadEvents(runId),
            chatSessionId = chatSessionId
        ) ?: return
        deliver(message)
    }
}
