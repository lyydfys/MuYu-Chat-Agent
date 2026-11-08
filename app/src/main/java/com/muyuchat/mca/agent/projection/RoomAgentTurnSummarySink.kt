package com.muyuchat.mca.agent.projection

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.mca.ChatMessageEntity
import com.muyuchat.mca.ChatSessionDao

/**
 * Writes a finished Agent run's summary into the conversation it was started from.
 *
 * The chat thread and the agent loop are one conversation, so the agent's conclusion has to land
 * where the conversation is. This is the production half of [ChatAgentTurnSummarySink]: the sink
 * decides *what* message to build, this decides *where* it goes and makes that durable.
 *
 * ## Why a single-message insert instead of [com.muyuchat.mca.ChatSessionStore.save]
 *
 * `save` replaces the whole session snapshot, so using it here would race the chat UI: any message
 * the user typed between the store's read and its write would be dropped. The DAO's
 * `insertMessages` appends by `(sessionId, position)`, which is the same shape the chat path already
 * uses, so a summary either lands completely or not at all and no concurrent turn is disturbed.
 *
 * ## Idempotency
 *
 * [AgentTurnSummaryWriter] keys on `runId`, and this class re-checks the terminal message before
 * inserting: a recovery path may legitimately report the same run twice, and a duplicate summary
 * bubble in the transcript would be a visible bug.
 */
class RoomAgentTurnSummarySink(
    private val journal: AgentJournal,
    private val dao: ChatSessionDao,
    /** Highest `position` currently used by the session, or -1 when it is empty. */
    private val lastPositionOf: suspend (sessionId: String) -> Int,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AgentTurnSummarySink {

    override suspend fun onAgentTurnCompleted(runId: String, chatSessionId: String?) {
        if (chatSessionId.isNullOrBlank()) return

        val snapshot = journal.readRun(runId) ?: return
        val run = snapshot.run
        val events = readAllEvents(runId)
        val message = AgentTurnSummaryWriter.messageFor(
            run = run,
            events = events,
            chatSessionId = chatSessionId,
            createdAtMillis = nowMillis()
        ) ?: return

        // A run reported twice must not produce two bubbles.
        if (alreadyPresent(chatSessionId, run.runId)) return

        val position = lastPositionOf(chatSessionId) + 1
        dao.insertMessages(listOf(message.toEntity(chatSessionId, position)))
    }

    /**
     * Reads the run's whole event stream.
     *
     * The summary keys on the terminal event's bounded text, and the terminal event can be anywhere in
     * the stream, so this pages until exhaustion rather than assuming it fits in one page.
     */
    private suspend fun readAllEvents(runId: String): List<AgentEvent> {
        val collected = ArrayList<AgentEvent>()
        var after = 0L
        while (true) {
            val page: AgentEventPage = journal.readEvents(runId, afterSequence = after, limit = PAGE_SIZE)
            if (page.events.isEmpty()) return collected
            collected += page.events
            val next = page.nextSequence ?: return collected
            if (next <= after) return collected
            after = next
        }
    }

    /** True when this session already holds the summary bubble for [runId]. */
    private suspend fun alreadyPresent(sessionId: String, runId: String): Boolean =
        lastSummarizedRunId(sessionId) == runId

    /**
     * Marker carried in the entity's `reasoningContent` column.
     *
     * Reusing an existing column keeps this out of the Room schema: a migration for one internal
     * marker would be a worse trade than a field the chat renderer already ignores for agent turns.
     */
    private suspend fun lastSummarizedRunId(sessionId: String): String? =
        dao.messages(sessionId).lastOrNull { it.role == AGENT_ROLE }
            ?.reasoningContent
            ?.takeIf { it.startsWith(RUN_MARKER_PREFIX) }
            ?.removePrefix(RUN_MARKER_PREFIX)

    companion object {
        private const val PAGE_SIZE = 200
        private const val AGENT_ROLE = "assistant"
        private const val RUN_MARKER_PREFIX = "agent-run:"
        internal const val RUN_MARKER = RUN_MARKER_PREFIX
    }
}

/** Maps the projection's [ChatMessage] onto the persisted row shape. */
internal fun ChatMessage.toEntity(sessionId: String, position: Int): ChatMessageEntity =
    ChatMessageEntity(
        sessionId = sessionId,
        position = position,
        role = role.name.lowercase(),
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        reasoningContent = agentTrace?.let { "agent-run:${it.turnId}" }.orEmpty()
    )

/** Convenience for callers that only have the run and want the marker value. */
internal fun AgentRun.summaryMarker(): String = "agent-run:$runId"
