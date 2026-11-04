package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.McaInferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.ChatImageActionKind
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import kotlinx.coroutines.Deferred
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.mca.presentation.ChatContextSnapshot
import com.muyuchat.mca.presentation.chatContextSnapshot
import com.muyuchat.mca.presentation.lifecycleAfterGeneration

internal class ChatLocalApiProviderPortAdapter(
    override val engine: McaInferenceService,
    override val chatRuns: ChatRunOwnership,
    private val store: UiStateWriter<GenerationLifecycleFields>,
    private val cancelActiveLocalApiImageGenerationAction: (String) -> Boolean,
    private val localApiPayloadPortProvider: () -> LocalApiPayloadPort,
    override val chatExecutionFacade: com.muyuchat.mca.facade.ChatExecutionFacade,
) : ChatLocalApiProviderPort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override fun updateUiState(transform: (GenerationLifecycleFields) -> GenerationLifecycleFields) {
        store.update(transform)
    }

    override fun mergeExecutionProfile(
        generation: GenerationParams,
        profile: ModelExecutionProfile
    ): GenerationParams = mergedExecutionProfile(generation, profile)

    override fun cancelActiveLocalApiImageGeneration(reason: String): Boolean =
        cancelActiveLocalApiImageGenerationAction(reason)

    override fun loadedModelJson(): String =
        localApiLoadedModelJson(localApiPayloadPortProvider())
}

internal class ChatGenerationExecutionPortAdapter(
    override val application: Application,
    private val store: UiStateWriter<GenerationExecutionFields>,
    override val engine: McaInferenceService,
    override val chatRuns: ChatRunOwnership,
    override val chatSessionStore: ChatSessionStore,
    override val chatContextComposer: ChatContextComposer,
    override val cloudChatProvider: OpenAiCompatibleChatProvider,
    override val webSearchProvider: WebSearchProvider,
    override val manualContextCompressionRequests: ContextCompressionRequestStore,
    private val runtimeAdmission: RuntimeAdmissionState,
    private val visionAttachmentPort: VisionAttachmentPort,
    private val getActiveWebSearchTaskIdRef: () -> String?,
    private val setActiveWebSearchTaskIdRef: (String?) -> Unit,
    private val getForegroundRecoveryJobRef: () -> Job?,
    private val persistChatSessionsRef: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    private val appendAssistantRef: (String, String, Long, Boolean, Long?) -> Unit,
    private val flushPendingAssistantOutputRef: (Long?) -> Boolean,
    private val removePendingAssistantPlaceholderRef: (Long?) -> Unit,
    private val ensureVisibleAssistantReplyAfterCompletionRef: (Long?) -> Unit,
    private val applyWebSearchAnswerGuardsToLastAssistantRef: (Long?) -> WebSearchCitationAudit?,
    private val recordWebSearchCitationAuditRef: (String?, WebSearchCitationAudit, Long?) -> Unit,
    private val attachWebSearchEvidenceToPendingAssistantRef: (List<ChatSourceReference>, ChatWebSearchTrace?, Long?) -> Unit,
    private val attachContextAssemblyTraceRef: (String?, Long, String?, String?) -> Unit,
    private val promptContextUsageForRef: (ChatRuntimeContextPlan, ContextWindowAdmission, GenerationParams) -> PromptContextUsage,
    private val appendWebSearchDiagnosticRef: (WebSearchDiagnosticRecord) -> List<WebSearchDiagnosticRecord>,
    private val recordCompletedCharacterMemoryTurnRef: (String, String, AssistantConversationSnapshot, ChatMessage, ChatMessage, Job?, ChatBackend, GenerationParams, CloudApiConfig?, String?) -> Unit,
    private val summarizeContextForGenerationRef: suspend (ChatRequest, ContextCompressionSettings, ContextCompressionTrigger, MainUiState, ChatBackend, String?, Long, Boolean) -> ContextCompressionResult,
    private val ensureLocalConversationContextInvalidatedRef: suspend () -> Boolean,
    private val loadContextCompressionThresholdRef: () -> ContextCompressionThreshold,
    private val handleAssistantImageToolCallsRef: suspend (List<ChatToolCall>, List<ChatMessage>, GenerationParams, String?, String?, String?, AssistantImageModelBinding?, CloudApiConfig?) -> Boolean,
    private val finishAssistantImageContinuationRef: (String, Boolean, String) -> Unit,
    private val recordPrefixCacheTelemetryRef: (RuntimeStats) -> Unit,
) : ChatGenerationExecutionPort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override val generationUiWriter: UiStateWriter<GenerationExecutionFields>
        get() = store

    override val localConversationContextInvalidationSequence: AtomicLong
        get() = runtimeAdmission.localConversationContextInvalidationSequence

    override var activeWebSearchDeferred: Deferred<WebSearchResult>?
        get() = runtimeAdmission.activeWebSearchDeferred
        set(value) {
            runtimeAdmission.activeWebSearchDeferred = value
        }

    override var activeWebSearchTaskId: String?
        get() = getActiveWebSearchTaskIdRef()
        set(value) {
            setActiveWebSearchTaskIdRef(value)
        }

    override var localConversationContextNeedsInvalidation: Boolean
        get() = runtimeAdmission.localConversationContextNeedsInvalidation
        set(value) {
            runtimeAdmission.localConversationContextNeedsInvalidation = value
        }

    override val foregroundRecoveryJob: Job?
        get() = getForegroundRecoveryJobRef()

    override val activeRuntimeIdentity: ModelRuntimeIdentity?
        get() = runtimeAdmission.activeRuntimeIdentity

    override fun persistChatSessions(
        sessions: List<ChatSessionRecord>?,
        knowledgeBinding: Pair<String, Set<String>>?,
        generationRunId: Long?
    ): Job? = persistChatSessionsRef(sessions, knowledgeBinding, generationRunId)

    override fun appendAssistant(
        delta: String,
        reasoningDelta: String,
        reasoningDurationMs: Long,
        forcePublish: Boolean,
        generationRunId: Long?
    ) {
        appendAssistantRef(delta, reasoningDelta, reasoningDurationMs, forcePublish, generationRunId)
    }

    override fun flushPendingAssistantOutput(generationRunId: Long?): Boolean =
        flushPendingAssistantOutputRef(generationRunId)

    override fun removePendingAssistantPlaceholder(generationRunId: Long?) {
        removePendingAssistantPlaceholderRef(generationRunId)
    }

    override fun ensureVisibleAssistantReplyAfterCompletion(generationRunId: Long?) {
        ensureVisibleAssistantReplyAfterCompletionRef(generationRunId)
    }

    override fun applyWebSearchAnswerGuardsToLastAssistant(generationRunId: Long?): WebSearchCitationAudit? =
        applyWebSearchAnswerGuardsToLastAssistantRef(generationRunId)

    override fun recordWebSearchCitationAudit(
        recordId: String?,
        audit: WebSearchCitationAudit,
        generationRunId: Long?
    ) {
        recordWebSearchCitationAuditRef(recordId, audit, generationRunId)
    }

    override fun attachWebSearchEvidenceToPendingAssistant(
        sources: List<ChatSourceReference>,
        trace: ChatWebSearchTrace?,
        generationRunId: Long?
    ) {
        attachWebSearchEvidenceToPendingAssistantRef(sources, trace, generationRunId)
    }

    override fun attachContextAssemblyTrace(
        traceJson: String?,
        generationRunId: Long,
        sessionId: String?,
        assistantMessageId: String?
    ) {
        attachContextAssemblyTraceRef(traceJson, generationRunId, sessionId, assistantMessageId)
    }

    override fun promptContextUsageFor(
        plan: ChatRuntimeContextPlan,
        admission: ContextWindowAdmission,
        params: GenerationParams
    ): PromptContextUsage = promptContextUsageForRef(plan, admission, params)

    override fun appendWebSearchDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> =
        appendWebSearchDiagnosticRef(record)

    override fun recordCompletedCharacterMemoryTurn(
        assistantId: String,
        sessionId: String,
        assistantSnapshot: AssistantConversationSnapshot,
        user: ChatMessage,
        assistant: ChatMessage,
        conversationPersistJob: Job?,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) {
        recordCompletedCharacterMemoryTurnRef(
            assistantId, sessionId, assistantSnapshot, user, assistant, conversationPersistJob, backend, params, cloudConfig, localIdentity
        )
    }

    override suspend fun summarizeContextForGeneration(
        request: ChatRequest,
        settings: ContextCompressionSettings,
        trigger: ContextCompressionTrigger,
        initialState: MainUiState,
        effectiveBackend: ChatBackend,
        cloudModelIdOverride: String?,
        generationRunId: Long,
        useModel: Boolean
    ): ContextCompressionResult = summarizeContextForGenerationRef(
        request, settings, trigger, initialState, effectiveBackend, cloudModelIdOverride, generationRunId, useModel
    )

    override suspend fun ensureLocalConversationContextInvalidated(): Boolean =
        ensureLocalConversationContextInvalidatedRef()

    override fun loadContextCompressionThreshold(): ContextCompressionThreshold =
        loadContextCompressionThresholdRef()

    override suspend fun handleAssistantImageToolCalls(
        calls: List<ChatToolCall>,
        originalRequestMessages: List<ChatMessage>,
        paramsBeforeAssistant: GenerationParams,
        chatSessionId: String?,
        cloudModelId: String?,
        chatModelIdentity: String?,
        imageModelBinding: AssistantImageModelBinding?,
        chatConfig: CloudApiConfig?
    ): Boolean = handleAssistantImageToolCallsRef(
        calls, originalRequestMessages, paramsBeforeAssistant, chatSessionId, cloudModelId, chatModelIdentity, imageModelBinding, chatConfig
    )

    override fun finishAssistantImageContinuation(
        requestId: String,
        completed: Boolean,
        failureMessage: String
    ) {
        finishAssistantImageContinuationRef(requestId, completed, failureMessage)
    }

    override fun recordPrefixCacheTelemetry(stats: RuntimeStats) {
        recordPrefixCacheTelemetryRef(stats)
    }

    override fun chatImageToolChatModelIdentity(
        modelId: String?,
        config: CloudApiConfig?
    ): String? = com.muyuchat.mca.chatImageToolChatModelIdentity(modelId, config)

    override suspend fun localVisionRunnerAvailable(): Boolean =
        isLocalVisionRunnerAvailable(visionAttachmentPort)

    override fun mergeExecutionProfile(
        generation: GenerationParams,
        profile: ModelExecutionProfile
    ): GenerationParams = mergedExecutionProfile(generation, profile)

    override fun persistGenerationParams(params: GenerationParams) {
        GenerationPreferences.persistGenerationParams(application, params)
    }

    override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
        state.canWriteToActiveAssistantSession()

    override fun selectedAssistant(state: MainUiState): AssistantRecord? =
        state.selectedAssistant()

    override fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig? =
        state.selectedChatCloudConfig()

    override fun currentChatModelId(state: MainUiState): String? =
        state.currentChatModelId()

    override fun chatImageModelBinding(state: MainUiState): AssistantImageModelBinding? =
        state.chatImageModelBinding()

    override fun localVisionUnavailableMessage(state: MainUiState): String =
        localVisionUnavailableReason(state, runCatching { engine.nativeStatsJson() }.getOrNull())

    override fun chatContextSnapshot(
        state: MainUiState,
        assistantId: String,
        fileContextEnabled: Boolean
    ): ChatContextSnapshot = state.chatContextSnapshot(assistantId, fileContextEnabled)

    override fun isConfigurationActionError(event: GenerateEvent.Error): Boolean =
        event.code in setOf(
            "model_reload_required",
            "model_reload_required_authorized",
            "execution_override_forbidden",
            "model_behavior_override_forbidden",
            "active_profile_drift",
            "model_mismatch"
        )

    override suspend fun withInlineImageDataForCloud(messages: List<ChatMessage>): List<ChatMessage> =
        messages.preparedForCloudVision(visionAttachmentPort)

    override suspend fun withLocalImageFilesForVision(
        messages: List<ChatMessage>,
        createdFiles: MutableList<File>
    ): List<ChatMessage> = messages.preparedForLocalVision(visionAttachmentPort, createdFiles)
}

internal class ChatImagePromptBridgePortAdapter(
    private val store: UiStateWriter<ChatPromptDraftFields>,
    private val chatContextComposer: ChatContextComposer,
    private val cloudChatProvider: OpenAiCompatibleChatProvider,
    private val engine: McaInferenceService,
    private val currentBridgeGenerationRef: () -> Long,
    private val incrementBridgeGenerationRef: () -> Long,
    private val cancelBridgeJobRef: () -> Unit,
    private val setBridgeJobRef: (Job) -> Unit,
    private val bridgeScopeRef: () -> CoroutineScope,
    private val setPendingChatImageActionRef: (String, ChatImageActionKind, String, Int?, () -> Boolean) -> Unit,
    private val setPendingImageTranslationRef: (PendingImageTranslation) -> Unit,
    private val showStatusRef: (String?) -> Unit,
    private val chatImageOptionsForCurrentSelectionRef: (MainUiState) -> LocalImageGenerationOptions,
    private val offlinePromptTranslationServiceProvider: () -> OfflinePromptTranslationService,
    private val verifiedOfflineTranslationBundleRef: suspend () -> VerifiedOfflinePromptTranslationBundle?,
    private val sendChatImageRequestRef: (String, String, LocalImageGenerationOptions?, PromptEnvelope?, String?) -> Unit,
    private val negativePromptSupportedRef: (MainUiState) -> Boolean,
    private val chatExecutionFacade: com.muyuchat.mca.facade.ChatExecutionFacade,
) : ChatImagePromptBridgePort {

    override fun currentState(): MainUiState = store.state.value

    override fun updateUiState(transform: (ChatPromptDraftFields) -> ChatPromptDraftFields) {
        store.update(transform)
    }

    override fun currentBridgeGeneration(): Long = currentBridgeGenerationRef()

    override fun incrementBridgeGeneration(): Long = incrementBridgeGenerationRef()

    override fun cancelBridgeJob() {
        cancelBridgeJobRef()
    }

    override fun setBridgeJob(job: Job) {
        setBridgeJobRef(job)
    }

    override fun bridgeScope(): CoroutineScope = bridgeScopeRef()

    override fun setPendingChatImageAction(
        id: String,
        kind: ChatImageActionKind,
        visibleUserText: String,
        requestedOutputCount: Int?,
        stillOwned: () -> Boolean
    ) {
        setPendingChatImageActionRef(id, kind, visibleUserText, requestedOutputCount, stillOwned)
    }

    override fun setPendingImageTranslation(translation: PendingImageTranslation) {
        setPendingImageTranslationRef(translation)
    }

    override fun showStatus(message: String?) {
        showStatusRef(message)
    }

    override fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
        chatImageOptionsForCurrentSelectionRef(state)

    override fun composeChatContext(
        messages: List<ChatMessage>,
        params: GenerationParams,
        assistantId: String,
        chatSessionId: String?,
        knowledgeBaseIds: Set<String>,
        fileContextEnabled: Boolean
    ): ChatRuntimeContextPlan = chatContextComposer.compose(
        messages = messages,
        params = params,
        assistantId = assistantId,
        chatSessionId = chatSessionId,
        knowledgeBaseIds = knowledgeBaseIds,
        fileContextEnabled = fileContextEnabled
    )

    override fun streamChatCloud(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent> =
        cloudChatProvider.streamChat(config, request)

    override fun streamChatLocal(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext
    ): Flow<GenerateEvent> = chatExecutionFacade.streamChat(request, executionContext)

    override suspend fun translateOffline(request: OfflinePromptTranslationRequest): OfflinePromptTranslationResolution =
        offlinePromptTranslationServiceProvider().translate(verifiedOfflineTranslationBundleRef(), request)

    override fun sendChatImageRequest(
        visibleUserText: String,
        prompt: String,
        optionsOverride: LocalImageGenerationOptions?,
        promptEnvelope: PromptEnvelope?,
        contextTraceJson: String?
    ) {
        sendChatImageRequestRef(
            visibleUserText,
            prompt,
            optionsOverride,
            promptEnvelope,
            contextTraceJson
        )
    }

    override fun negativePromptSupported(state: MainUiState): Boolean =
        negativePromptSupportedRef(state)

    override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
        state.canWriteToActiveAssistantSession()

    override fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig? =
        state.selectedChatCloudConfig()

    override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig? =
        state.selectedImageCloudConfig()

    override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord? =
        state.selectedLocalImageModel()

    override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot? =
        state.activeAssistantSnapshot()

    override fun selectedAssistant(state: MainUiState): AssistantRecord? =
        state.selectedAssistant()
}

internal class ChatStopGenerationPortAdapter(
    private val store: UiStateWriter<GenerationStopFields>,
    private val engine: McaInferenceService,
    private val chatRuns: ChatRunOwnership,
    private val stopScopeRef: () -> CoroutineScope,
    private val rejectWhileConversationMutationInProgressRef: () -> Boolean,
    private val stopGenerationInProgressRef: AtomicBoolean,
    private val currentReplyMetricsRef: () -> ReplyGenerationMetrics?,
    private val cancelGenerationJobRef: () -> UiGenerationCancellation,
    private val flushPendingAssistantOutputRef: () -> Unit,
    private val persistConversationMutationRef: (List<ChatSessionRecord>, ConversationMutationRollbackState) -> Unit,
    private val chatExecutionFacade: com.muyuchat.mca.facade.ChatExecutionFacade,
) : ChatStopGenerationPort {

    override fun currentState(): MainUiState = store.state.value

    override fun updateUiState(transform: (GenerationStopFields) -> GenerationStopFields): GenerationStopFields =
        store.updateAndGet(transform)

    override fun stopScope(): CoroutineScope = stopScopeRef()

    override fun rejectWhileConversationMutationInProgress(): Boolean =
        rejectWhileConversationMutationInProgressRef()

    override fun tryEnterStop(): Boolean =
        stopGenerationInProgressRef.compareAndSet(false, true)

    override fun exitStop() {
        stopGenerationInProgressRef.set(false)
    }

    override fun currentReplyMetrics(): ReplyGenerationMetrics? =
        currentReplyMetricsRef()

    override fun isRunCurrent(runId: Long?): Boolean = chatRuns.isCurrent(runId)

    override fun cancelCurrentGeneration(): UiGenerationCancellation =
        cancelGenerationJobRef()

    override fun frozenLocalAttempt(cancellation: UiGenerationCancellation): ChatAttemptIdentity? =
        // The identity was registered before admission and captured under the ownership lock with
        // the cancellation. Only that exact request may be stopped; null (cloud, pending, idle)
        // means this stop must not touch the engine at all.
        cancellation.localAttempt

    override suspend fun requestRuntimeStop(attempt: ChatAttemptIdentity) {
        // P03: exact attempt only. The frozen request id is never re-derived from the engine's
        // current token, and there is deliberately no unconditional fallback stop: a late chat
        // stop for an already-finished attempt is a no-op, and an Agent/API/image request that
        // owns the engine now is never a target.
        chatExecutionFacade.stopGenerationIfActive(attempt.requestId)
    }

    override fun runtimeLifecycle(): AgentEngineLifecycle =
        engine.stats.value.lifecycleAfterGeneration()

    override fun rollbackState(): ConversationMutationRollbackState =
        store.state.value.conversationMutationRollbackState()

    override fun flushPendingAssistantOutput() {
        flushPendingAssistantOutputRef()
    }

    override fun persistConversationMutation(
        sessions: List<ChatSessionRecord>,
        rollback: ConversationMutationRollbackState
    ) {
        persistConversationMutationRef(sessions, rollback)
    }
}
