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
    private val onRunSucceeded: (runId: String) -> Unit
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

            is UpdateRunTransition ->
                command.run.runId.takeIf {
                    command.run.state == com.muyuchat.core.agent.contract.AgentRunState.SUCCEEDED
                }

            is CreateRunTransition -> null
            else -> null
        }
        if (succeededRunId != null) {
            runCatching { onRunSucceeded(succeededRunId) }
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
    sink: AgentTurnSummarySink = com.muyuchat.mca.agent.projection.NoAgentTurnSummarySink
): AgentJournal = SummaryReportingAgentJournal(this) { runId ->
    // TODO(agent-chat-bridge): resolve the run's chatSessionId once a task -> conversation binding
    // exists. Today no contract type carries it (CreateAgentTask/AgentTask/AgentRun/AgentRunPolicy
    // all lack the field), so the id is always null and the sink writes nothing.
    sink.onAgentTurnCompleted(runId, chatSessionId = null)
}
