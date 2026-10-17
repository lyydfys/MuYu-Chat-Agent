package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.localContextWindowAdmission
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.presentation.ChatContextOperations
import com.muyuchat.mca.presentation.ChatGenerationUiPublisher

internal suspend fun ChatGenerationExecutionPort.summarizeTurnContextFor(
    manualCompression: Boolean,
    compressionThreshold: ContextCompressionThreshold,
    preflightCompression: ContextCompressionResult?,
    requestMessages: List<ChatMessage>,
    requestParams: GenerationParams,
    characterMemoryContextForTurn: String,
    initialState: MainUiState,
    effectiveChatBackend: ChatBackend,
    chatModelIdOverride: String?,
    generationRunId: Long,
    allowSummaryCandidate: Boolean
): ContextCompressionResult {
    val compression = summarizeContextForGeneration(
        request = ChatRequest(
            messages = requestMessages,
            params = requestParams,
            runtimeSystemContext = preflightCompression?.request?.runtimeSystemContext
                ?.takeIf(String::isNotBlank) ?: characterMemoryContextForTurn,
            protectedMessageIds = requestMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id }
        ),
        settings = ContextCompressionSettings(
            threshold = compressionThreshold,
            keepRecentMessages = 8
        ),
        trigger = if (manualCompression || preflightCompression?.didCompress == true) {
            ContextCompressionTrigger.MANUAL
        } else {
            ContextCompressionTrigger.AUTOMATIC
        },
        initialState = initialState,
        effectiveBackend = effectiveChatBackend,
        cloudModelIdOverride = chatModelIdOverride,
        generationRunId = generationRunId,
        useModel = allowSummaryCandidate
    )
    return compression
}

internal fun ChatGenerationExecutionPort.selectTurnSummaryFor(
    requestMessages: List<ChatMessage>,
    requestParams: GenerationParams,
    assistantSnapshot: AssistantConversationSnapshot?,
    summaryRecords: List<ContextSummaryRecord>,
    compression: ContextCompressionResult,
    allowSummaryCandidate: Boolean,
    generationUiPublisher: ChatGenerationUiPublisher
): ChatTurnSummarySelection {
    val summaryScope = requestParams.systemPrompt + "\n" +
        assistantSnapshot?.toJsonString().orEmpty()
    val preparedSummary = if (allowSummaryCandidate) {
        contextSummaryCandidate(
            originals = requestMessages, result = compression,
            previous = summaryRecords, scope = summaryScope
        )
    } else null
    val reusableSummary = summaryRecords.lastOrNull { it.appliesTo(requestMessages, summaryScope) }
    val summaryCandidate = preparedSummary?.takeUnless {
        it.sourceFingerprint == reusableSummary?.sourceFingerprint &&
            it.scopeFingerprint == reusableSummary?.scopeFingerprint && it.text == reusableSummary?.text
    }
    val effectiveRequestMessages = summaryCandidate?.projectCandidate(requestMessages, summaryScope)
        ?: reusableSummary?.project(requestMessages, summaryScope)
        ?: requestMessages
    if (effectiveRequestMessages !== requestMessages) {
        generationUiPublisher.publishContextCompressedStatus(compression.compressedMessageCount)
    }
    return ChatTurnSummarySelection(summaryScope, summaryCandidate, effectiveRequestMessages)
}

internal fun ChatGenerationExecutionPort.publishTurnContextStatusFor(
    initialState: MainUiState,
    assistantSnapshot: AssistantConversationSnapshot?,
    runtimeContextPlan: ChatRuntimeContextPlan,
    generationUiPublisher: ChatGenerationUiPublisher
) {
    val contextSnapshot = chatContextSnapshot(initialState,
        assistantId = assistantSnapshot?.assistantId ?: initialState.selectedAssistantId,
        // Same fallback chain the composer call above uses, resolved at the same moment.
        fileContextEnabled = assistantSnapshot?.fileContextEnabled
            ?: selectedAssistant(initialState)?.fileContextEnabled
            ?: true
    )
    val contextStatus = ChatContextOperations.turnContextStatus(contextSnapshot, runtimeContextPlan)
    if (contextStatus != null) {
        generationUiPublisher.publishContextStatusMessage(contextStatus)
    }
}

internal fun ChatGenerationExecutionPort.admitTurnContextFor(
    effectiveRequestMessages: List<ChatMessage>,
    requestParams: GenerationParams,
    requestMessages: List<ChatMessage>,
    runtimeSystemContextForTurn: String
): ContextWindowAdmission {
    val contextAdmission = localContextWindowAdmission(
        ChatRequest(
            messages = effectiveRequestMessages,
            params = requestParams,
            protectedMessageIds = requestMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id },
            runtimeSystemContext = runtimeSystemContextForTurn
        )
    )
    return contextAdmission
}

internal fun ChatGenerationExecutionPort.publishPromptContextUsageFor(
    runtimeContextPlan: ChatRuntimeContextPlan,
    contextAdmission: ContextWindowAdmission,
    requestParams: GenerationParams,
    generationUiPublisher: ChatGenerationUiPublisher
) {
    generationUiPublisher.publishPromptContextUsage(
        promptContextUsageFor(
            plan = runtimeContextPlan,
            admission = contextAdmission,
            params = requestParams
        )
    )
}

internal fun ChatGenerationExecutionPort.frozenContextRevisionFor(
    frozenSessionId: String,
    summaryRecords: List<ContextSummaryRecord>,
    summaryCandidate: ContextSummaryRecord?
): String {
    val frozenContextRevision = com.muyuchat.core.engine.PrefixCacheKey.sha256Utf8(
        buildString {
            append(frozenSessionId).append('\n')
            summaryRecords.forEach { append(it.toString()).append('\n') }
            summaryCandidate?.let { append(it.toString()) }
        }
    )
    return frozenContextRevision
}

internal data class ChatTurnSummarySelection(
    val scope: String,
    val candidate: ContextSummaryRecord?,
    val effectiveMessages: List<ChatMessage>
)
