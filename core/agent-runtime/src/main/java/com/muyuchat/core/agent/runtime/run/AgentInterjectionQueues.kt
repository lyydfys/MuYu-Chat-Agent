package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of [AgentInterjectionQueue]s, one per running run.
 *
 * Why a registry instead of a constructor parameter: the text is typed on the UI thread and reaches
 * the owner, while the consumer is the loop coroutine inside `AgentPromptedLoopCoordinator`. Threading
 * one queue instance through every construction site (9 of them today, several of them frames and
 * tests) would widen the runtime's wiring for a purely transient side channel. The registry keeps the
 * production singleton in one place while tests can pass their own instance explicitly.
 *
 * Ownership: the queue exists only while a run can still consume it. [clear] is called when the run
 * reaches a terminal state, so a later run with a recycled id never inherits stale text.
 */
class AgentInterjectionQueues {

    private val queues = ConcurrentHashMap<String, AgentInterjectionQueue>()

    /** The queue for [runId], created on first use. */
    fun queueFor(runId: String): AgentInterjectionQueue =
        queues.computeIfAbsent(runId) { AgentInterjectionQueue() }

    /**
     * Buffers one interjection for [runId].
     *
     * @return true when it was accepted without evicting an older entry (see
     *   [AgentInterjectionQueue.enqueue]).
     */
    fun enqueue(runId: String, text: String, atMillis: Long): Boolean =
        queueFor(runId).enqueue(text, atMillis)

    /** Takes everything pending for [runId]; empty when the run has no queue. */
    fun drain(runId: String): List<String> = queues[runId]?.drainAtStepBoundary().orEmpty()

    /**
     * Takes everything pending for [runId] as transcript entries stamped with [atMillis].
     *
     * The loop body needs entries, not words: doing the conversion here keeps the coordinator at its
     * line budget and keeps the "one batch, one timestamp" rule in a single place, so every entry of
     * the same step boundary reports the same arrival instant.
     */
    fun drainAsEntries(runId: String, atMillis: Long): List<AgentTranscriptEntry.UserInterjection> =
        drain(runId).map { text ->
            AgentTranscriptEntry.UserInterjection(text = text, receivedAtMillis = atMillis)
        }

    /** Read-only snapshot for a "N 条插话待送达" hint. */
    fun pending(runId: String): List<String> = queues[runId]?.pending().orEmpty()

    /** Drops the queue of a finished run. */
    fun clear(runId: String) {
        queues.remove(runId)
    }

    /** Test/diagnostic view of the live queue count. */
    fun trackedRunCount(): Int = queues.size

    companion object {
        /** Production instance: one per process, shared by the owner and the loop. */
        val global: AgentInterjectionQueues = AgentInterjectionQueues()
    }
}
