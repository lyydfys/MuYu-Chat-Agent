package com.muyuchat.mca.composition

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.*
import kotlinx.coroutines.Job

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleChatExecutionPort(
    store: UiStateStore,
    container: McaAppContainer,
    engine: McaInferenceService,
    chatContextComposer: ChatContextComposer,
    visionAttachmentPort: VisionAttachmentPort,
    chatTerminalSettlementPort: ChatTerminalSettlementPort,
    webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator,
    getActiveWebSearchTaskId: () -> String?,
    setActiveWebSearchTaskId: (String?) -> Unit,
    getForegroundRecoveryJob: () -> Job?,
    persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    appendAssistant: (String, String, Long, Boolean, Long?) -> Unit,
    flushPendingAssistantOutput: (Long?) -> Boolean,
    ensureLocalConversationContextInvalidated: suspend () -> Boolean,
    assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
    assistantProfileCoordinator: AssistantProfileCoordinator,
    contextCompressionCoordinator: ContextCompressionCoordinator,
): ChatGenerationExecutionPort = container.createChatGenerationExecutionPort(
    store = store,
    engine = engine,
    chatContextComposer = chatContextComposer,
    visionAttachmentPort = visionAttachmentPort,
    getActiveWebSearchTaskIdRef = { getActiveWebSearchTaskId() },
    setActiveWebSearchTaskIdRef = { setActiveWebSearchTaskId(it) },
    getForegroundRecoveryJobRef = { getForegroundRecoveryJob() },
    persistChatSessionsRef = persistChatSessions,
    appendAssistantRef = appendAssistant,
    flushPendingAssistantOutputRef = flushPendingAssistantOutput,
    removePendingAssistantPlaceholderRef = { settlePendingAssistantPlaceholder(port = chatTerminalSettlementPort, generationRunId = it) },
    ensureVisibleAssistantReplyAfterCompletionRef = { settleVisibleAssistantReply(port = chatTerminalSettlementPort, generationRunId = it) },
    applyWebSearchAnswerGuardsToLastAssistantRef = webSearchWorkflowCoordinator::applyWebSearchAnswerGuardsToLastAssistant,
    recordWebSearchCitationAuditRef = webSearchWorkflowCoordinator::recordWebSearchCitationAudit,
    attachWebSearchEvidenceToPendingAssistantRef = webSearchWorkflowCoordinator::attachWebSearchEvidenceToPendingAssistant,
    attachContextAssemblyTraceRef = { traceJson, generationRunId, sessionId, assistantMessageId ->
        attachContextAssemblyTrace(
            uiStateStore = store.conversationTraceWriter(),
            chatRuns = container.chatRuns,
            traceJson = traceJson,
            generationRunId = generationRunId,
            sessionId = sessionId,
            assistantMessageId = assistantMessageId
        )
    },
    promptContextUsageForRef = contextCompressionCoordinator::promptContextUsageFor,
    appendWebSearchDiagnosticRef = webSearchWorkflowCoordinator::appendDiagnostic,
    recordCompletedCharacterMemoryTurnRef = assistantProfileCoordinator::recordCompletedCharacterMemoryTurn,
    summarizeContextForGenerationRef = contextCompressionCoordinator::summarizeContextForGeneration,
    ensureLocalConversationContextInvalidatedRef = ensureLocalConversationContextInvalidated,
    loadContextCompressionThresholdRef = contextCompressionCoordinator::loadContextCompressionThreshold,
    handleAssistantImageToolCallsRef = assistantImageContinuationCoordinator::handleAssistantImageToolCalls,
    finishAssistantImageContinuationRef = assistantImageContinuationCoordinator::finishAssistantImageContinuation,
)

