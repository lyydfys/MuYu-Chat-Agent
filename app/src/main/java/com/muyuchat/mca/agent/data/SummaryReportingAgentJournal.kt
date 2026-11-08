package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.mca.agent.projection.AgentTurnSummarySink

/**
 * Journal decorator that reports the one moment an Agent run finishes successfully.
 *
 * Mount point: `AgentRunOwner.appendTerminalEventIfNeeded` writes the single `RUN_TERMINAL` event
 * for a settled run, and it does so through this journal. Watching the *committed* `RUN_TERMINAL`
 * append for a `SUCCEEDED` run is therefore exact — no polling, no second query, and no callback
 * from inside the runtime (which would have needed an app dependency in a core module).
 *
 * Ordering guarantees, all inherited from doing this after the delegate returns:
 *  - a rejected/failed commit never reports a completion;
 *  - the sink runs after the terminal event is durable, so it can read it back;
 *  - a sink exception is swallowed: chat bookkeeping must never turn a committed Agent transition
 *    into a failed command (same rule as [ObservingAgentJournal]).
 *
 * Idempotency is the sink's job (`AgentTurnSummaryWriter` keys on `runId`), because a recovery path
 * may legitimately append a terminal event for a run whose previous attempt was interrupted.
 */
class SummaryReportingAgentJournal(
    private val delegate: AgentJournal,
    private val onRunSucceeded: suspend (runId: String) -> Unit
) : AgentJournal by delegate {

    override suspend fun commit(command: AgentTransition): AgentCommitResult {
        val result = delegate.commit(command)
        val succeededRunId = when (command) {
            is AppendEventsTransition -> command.events
                .firstOrNull { event ->
                    event.type == AgentEventTypes.RUN_TERMINAL &&
                        event.runId == command.runId
                }
                ?.runId

            // A paused run is settled-but-continuable, not terminal: the user pressed stop and the
            // chat needs the wrap-up line so "say continue" is a visible affordance. Every other
            // state (FAILED / CANCELLED / SUPERSEDED / INTERRUPTED) stays out -- an aborted attempt's
            // transcript ends in an error, and reporting it as a conclusion would be a lie.
            is UpdateRunTransition ->
                command.run.runId.takeIf {
                    command.run.state == com.muyuchat.core.agent.contract.AgentRunState.SUCCEEDED ||
                        command.run.state == com.muyuchat.core.agent.contract.AgentRunState.PAUSED
                }

            is CreateRunTransition -> null
            else -> null
        }
        if (succeededRunId != null) {
            // Suspending inside runCatching would need a suspend-aware variant; the try/catch keeps
            // the same "a bookkeeping failure must never fail the commit" guarantee.
            try {
                onRunSucceeded(succeededRunId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Chat bookkeeping must not turn a committed Agent transition into a failed command.
            }
        }
        return result
    }
}

/**
 * Mount helper: wraps [journal] so a successful run's terminal event reaches [sink].
 *
 * Composed *under* [ObservingAgentJournal] (the foreground-service guard), so a completion is
 * reported exactly once per committed terminal append regardless of which decorator is outermost.
 * [NoAgentTurnSummarySink] keeps the wiring honest while no chat session binding exists; it is a
 * no-op, not a fake implementation.
 */
fun AgentJournal.reportingAgentTurnSummaries(
    sink: AgentTurnSummarySink = com.muyuchat.mca.agent.projection.NoAgentTurnSummarySink,
    /**
     * Resolves the conversation a finished run belongs to, or null when it has none.
     *
     * A run only knows its task id, and the durable task -> conversation mapping lives in the app's
     * binding store, so the lookup is injected rather than reached for. The default answers null,
     * which is also the correct answer for scheduled, intent-triggered and workflow frames: those
     * were never started from a conversation and their summaries must not be attached to one.
     */
    resolveChatSession: suspend (runId: String) -> String? = { null }
): AgentJournal = SummaryReportingAgentJournal(this) { runId ->
    // A throwing resolver must not turn a committed Agent transition into a failed command, so the
    // lookup degrades to "no conversation" instead of propagating.
    val chatSessionId = try {
        resolveChatSession(runId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
    sink.onAgentTurnCompleted(runId, chatSessionId = chatSessionId)
}
