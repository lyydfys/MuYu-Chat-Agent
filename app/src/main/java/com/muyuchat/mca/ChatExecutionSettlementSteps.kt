package com.muyuchat.mca

import com.muyuchat.feature.chat.failureSummaryLine

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.presentation.ChatGenerationUiPublisher

internal fun ChatGenerationExecutionPort.recordCitationAuditFor(
    citationAudit: WebSearchCitationAudit?,
    webSearchTurn: WebSearchTurnOutcome,
    generationRunId: Long
) {
                    if (citationAudit != null) {
                        recordWebSearchCitationAudit(
                            webSearchTurn.diagnostic?.id,
                            citationAudit,
                            generationRunId
                        )
                    }
}

internal fun ChatGenerationExecutionPort.publishSummaryActivationFor(
    summaryCandidate: ContextSummaryRecord?,
    admittedRequestMessages: List<ChatMessage>,
    summaryScope: String,
    generationRunId: Long,
    initialState: MainUiState,
    frozenSession: ChatSessionRecord,
    frozenAssistantId: String,
    frozenAssistantSnapshot: AssistantConversationSnapshot,
    generationUiPublisher: ChatGenerationUiPublisher
) {
                    if (summaryCandidate != null && chatRuns.matches(generationRunId) &&
                        admittedRequestMessages.any { it.id == "summary-${summaryCandidate.id}" }
                    ) {
                        val activated = generationUiPublisher.publishContextSummaryActivation(
                            candidate = summaryCandidate,
                            scope = summaryScope,
                            expectedSessionId = initialState.activeChatSessionId,
                            frozenSessionId = frozenSession.id,
                            frozenAssistantId = frozenAssistantId,
                            frozenAssistantSnapshot = frozenAssistantSnapshot
                        )
                        if (activated) {
                            localConversationContextNeedsInvalidation = true
                            localConversationContextInvalidationSequence.incrementAndGet()
                        }
                    }
}

internal fun ChatGenerationExecutionPort.recordCompletedTurnMemoryFor(
    toolCalls: List<ChatToolCall>,
    characterMemoryEnabled: Boolean,
    assistantSnapshot: AssistantConversationSnapshot?,
    initialState: MainUiState,
    requestMessages: List<ChatMessage>,
    effectiveChatBackend: ChatBackend,
    requestParams: GenerationParams,
    chatConfigForToolTurn: CloudApiConfig?,
    generationRunId: Long
) {
                    val conversationPersistJob = persistChatSessions(generationRunId = generationRunId)
                    if (toolCalls.isEmpty() && characterMemoryEnabled && assistantSnapshot != null) {
                        val completedState = uiState.value
                        val expectedReplyId = initialState.messages.lastOrNull {
                            it.role == Role.ASSISTANT
                        }?.id
                        val reply = completedState.messages.lastOrNull {
                            it.role == Role.ASSISTANT && it.id == expectedReplyId
                        }
                        val user = requestMessages.lastOrNull { it.role == Role.USER }
                        val sessionId = initialState.activeChatSessionId
                        if (reply != null && user != null && sessionId != null &&
                            completedState.activeChatSessionId == sessionId
                        ) {
                            recordCompletedCharacterMemoryTurn(
                                assistantId = assistantSnapshot.assistantId,
                                sessionId = sessionId,
                                assistantSnapshot = assistantSnapshot,
                                user = user,
                                assistant = reply,
                                conversationPersistJob = conversationPersistJob,
                                backend = effectiveChatBackend,
                                params = requestParams,
                                cloudConfig = chatConfigForToolTurn,
                                localIdentity = activeRuntimeIdentity?.identityHash
                            )
                        }
                    }
}

internal fun ChatGenerationExecutionPort.appendErrorOutcomeFor(
    event: GenerateEvent.Error,
    generationRunId: Long
) {
                    if (isConfigurationActionError(event)) {
                        removePendingAssistantPlaceholder(generationRunId)
                    } else {
                        // The body carries a short, internals-free reason; the full diagnostic
                        // (stage/runtime/model/memory) stays in the status message and its
                        // "错误详情" entry, and history/export therefore no longer show raw logs as
                        // if the assistant had answered with them.
                        appendAssistant(
                            "\n${failureSummaryLine(event.message)}",
                            generationRunId = generationRunId
                        )
                    }
}
