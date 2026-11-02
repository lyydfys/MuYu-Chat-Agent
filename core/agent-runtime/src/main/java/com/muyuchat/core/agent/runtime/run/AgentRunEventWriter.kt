package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand

/**
 * One writer for run audit events.
 *
 * Model loops and the workflow graph strategy both append events through this seam, so a tool node
 * produces the same `tool.proposed/approval_requested/started/result` facts regardless of which
 * execution strategy proposed it. It writes only events; invocation state remains in the ledger.
 */
interface AgentRunEventSink {
    suspend fun append(
        run: AgentRun,
        type: String,
        summary: String?,
        causationId: String?
    ): AgentRun

    suspend fun toolProposed(
        run: AgentRun,
        descriptor: AgentToolDescriptor,
        normalizedArgumentsJson: String
    ): AgentRun = append(
        run,
        AgentEventTypes.TOOL_PROPOSED,
        AgentToolLifecycle.proposedSummary(
            EVENT_CODEC,
            descriptor.toolKey,
            descriptor.sideEffect,
            normalizedArgumentsJson
        ),
        null
    )

    suspend fun approvalRequested(
        run: AgentRun,
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        normalizedArgumentsJson: String
    ): AgentRun = append(
        run,
        AgentEventTypes.TOOL_APPROVAL_REQUESTED,
        AgentToolLifecycle.approvalRequestedSummary(
            EVENT_CODEC,
            command,
            descriptor,
            normalizedArgumentsJson
        ),
        null
    )

    suspend fun toolResult(
        run: AgentRun,
        toolKey: String,
        invocationId: String,
        result: AgentToolResult
    ): AgentRun = append(
        run,
        AgentEventTypes.TOOL_RESULT,
        AgentToolLifecycle.resultSummary(EVENT_CODEC, toolKey, result),
        invocationId
    )

}

private val EVENT_CODEC =
    com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec()

class AgentRunEventWriter(
    private val journal: AgentJournal,
    private val clock: AgentMonotonicClock,
    private val ids: AgentIdGenerator
) : AgentRunEventSink {

    override suspend fun append(
        run: AgentRun,
        type: String,
        summary: String?,
        causationId: String?
    ): AgentRun {
        val event = AgentEvent(
            eventId = ids.nextId("event"),
            runId = run.runId,
            sequence = run.lastSequence + 1L,
            type = type,
            createdAtMillis = clock.nowMillis(),
            causationId = causationId,
            summary = summary
        )
        val committed = journal.commit(
            AppendEventsTransition(
                commandId = ids.nextId("cmd"),
                runIdValue = run.runId,
                events = listOf(event),
                expectedRevision = run.revision
            )
        )
        return run.copy(
            lastSequence = committed.eventSequences.lastOrNull() ?: event.sequence,
            revision = committed.runRevision ?: (run.revision + 1L)
        )
    }
}
