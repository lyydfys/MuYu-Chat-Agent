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

internal fun ChatGenerationExecutionPort.applyTavernAssistantOutputFor(
    initialState: MainUiState,
    generationRunId: Long
) {
    if (!chatRuns.matches(generationRunId)) return
    val state = uiState.value
    val index = state.messages.indexOfLast { it.role == Role.ASSISTANT }
    if (index < 0) return
    val original = state.messages[index].content
    if (original.isBlank() || original == EMPTY_ASSISTANT_COMPLETION_MESSAGE) return
    val transformed = TavernPromptPipeline.transformAssistantOutput(initialState, original)
    if (transformed == original) return
    val occurredAt = System.currentTimeMillis()
    generationUiWriter.update { fields ->
        if (!chatRuns.matches(generationRunId)) return@update fields
        val currentIndex = fields.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (currentIndex < 0) return@update fields
        val updated = fields.messages.toMutableList().also { messages ->
            messages[currentIndex] = messages[currentIndex].copy(
                content = transformed,
                // The stored content changed, so any previously measured token count is stale.
                tokenCount = null
            )
        }
        val sessionId = fields.snapshot.activeChatSessionId
        val sessions = if (sessionId == null) {
            fields.chatSessions
        } else {
            fields.chatSessions.upsertSessionFor(
                state = fields.snapshot,
                sessionId = sessionId,
                messages = updated,
                assistantId = fields.snapshot.selectedAssistantId,
                modelMode = fields.snapshot.selectedChatBackend.bindingValue(),
                modelId = fields.snapshot.currentChatModelId(),
                occurredAt = occurredAt
            )
        }
        fields.copy(messages = updated, chatSessions = sessions)
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
                    // Apply the preset's output contract before the turn is persisted or recorded
                    // into memory: macro expansion first, then regex_scripts(placement=2). The
                    // streaming preview stays raw until this settlement point.
                    applyTavernAssistantOutputFor(initialState, generationRunId)
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
