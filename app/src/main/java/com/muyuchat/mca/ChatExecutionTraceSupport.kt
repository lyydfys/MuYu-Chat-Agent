package com.muyuchat.mca

import com.muyuchat.core.engine.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ChatGenerationExecutionPort.attachTurnContextAssemblyTraceFor(
    runtimeContextPlan: ChatRuntimeContextPlan,
    generationRunId: Long,
    initialState: MainUiState
) {
    attachContextAssemblyTrace(
        withContext(Dispatchers.IO) { runtimeContextPlan.trace?.toJsonString() },
        generationRunId,
        initialState.activeChatSessionId,
        initialState.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
    )
}

internal fun attachContextAssemblyTrace(
    uiStateStore: UiStateStore,
    chatRuns: ChatRunOwnership,
    traceJson: String?,
    generationRunId: Long,
    sessionId: String?,
    assistantMessageId: String?
) {
    if (traceJson == null || sessionId == null || assistantMessageId == null ||
        !chatRuns.isCurrent(generationRunId)
    ) return
    uiStateStore.update { state ->
        if (!chatRuns.isCurrent(generationRunId) ||
            state.activeChatSessionId != sessionId
        ) return@update state
        val index = state.messages.indexOfLast {
            it.id == assistantMessageId && it.role == Role.ASSISTANT
        }
        val sessionIndex = state.chatSessions.indexOfFirst { it.id == sessionId }
        if (index < 0 || sessionIndex < 0 ||
            state.chatSessions[sessionIndex].messages.none { it.id == assistantMessageId }
        ) return@update state
        val updated = state.messages.mapIndexed { position, message ->
            if (position == index) message.copy(contextAssemblyTraceJson = traceJson) else message
        }
        state.copy(
            messages = updated,
            chatSessions = state.chatSessions.mapIndexed { position, session ->
                if (position != sessionIndex) session else session.copy(messages = updated)
            }
        )
    }
}
