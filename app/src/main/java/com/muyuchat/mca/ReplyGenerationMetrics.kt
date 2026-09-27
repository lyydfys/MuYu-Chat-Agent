package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.toChatGenerationMetrics

/** Monotonic elapsed time includes request preparation and waiting for the first token. */
internal class ReplyGenerationMetrics(
    val runId: Long,
    private val startedAtNanos: Long = System.nanoTime()
) {
    private var latest: RuntimeStats? = null
    private var finished: ChatGenerationMetrics? = null

    fun record(stats: RuntimeStats) { latest = stats }

    fun finish(nowNanos: Long = System.nanoTime()): ChatGenerationMetrics? {
        finished?.let { return it }
        return latest?.toChatGenerationMetrics(((nowNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L))
            ?.also { finished = it }
    }
}

internal fun MainUiState.withReplyGenerationMetrics(metrics: ChatGenerationMetrics?): MainUiState {
    if (metrics == null || messages.lastOrNull()?.role != Role.ASSISTANT) return this
    val updated = messages.dropLast(1) + messages.last().copy(generationMetrics = metrics)
    return copy(
        messages = updated,
        chatSessions = chatSessions.map { session ->
            if (session.id == activeChatSessionId) session.copy(messages = updated) else session
        }
    )
}
