package com.muyuchat.mca

import com.muyuchat.core.engine.Role

/** One completed exchange awaiting incorporation into an assistant's long-term memory. */
data class AssistantMemoryTurnRecord(
    val id: String,
    val assistantId: String,
    val sessionId: String,
    val userText: String,
    val assistantText: String,
    val createdAt: Long
) {
    companion object {
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_PENDING_FETCH = 100
    }
}

internal fun List<ChatSessionRecord>.containsCompletedMemoryTurn(turn: AssistantMemoryTurnRecord): Boolean =
    any { session ->
        session.id == turn.sessionId &&
            !session.mixedAssistantHistory &&
            session.assistantId == turn.assistantId &&
            session.assistantSnapshot?.assistantId == turn.assistantId &&
            session.messages.any { message -> message.id == turn.id && message.role == Role.ASSISTANT }
    }
