package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.Role

/** Message and durable-session metrics stay paired in one typed CAS commit. */
internal fun GenerationExecutionFields.withReplyGenerationMetrics(
    metrics: ChatGenerationMetrics?
): GenerationExecutionFields {
    if (metrics == null || messages.lastOrNull()?.role != Role.ASSISTANT) return this
    val updated = messages.dropLast(1) + messages.last().copy(generationMetrics = metrics)
    return copy(
        messages = updated,
        chatSessions = chatSessions.map { session ->
            if (session.id == snapshot.activeChatSessionId) session.copy(messages = updated) else session
        }
    )
}

internal fun GenerationStopFields.withReplyGenerationMetrics(
    metrics: ChatGenerationMetrics?
): GenerationStopFields {
    if (metrics == null || messages.lastOrNull()?.role != Role.ASSISTANT) return this
    val updated = messages.dropLast(1) + messages.last().copy(generationMetrics = metrics)
    return copy(
        messages = updated,
        chatSessions = chatSessions.map { session ->
            if (session.id == snapshot.activeChatSessionId) session.copy(messages = updated) else session
        }
    )
}
