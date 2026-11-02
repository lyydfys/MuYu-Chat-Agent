package com.muyuchat.mca.composition

import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.mca.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal data class MainViewModelPorts(
    val chatSessionPersistencePort: ChatSessionPersistencePort,
    val chatImageRequestPort: ChatImageRequestPort,
    val sendChatImageRequestPort: SendChatImageRequestPort,
    val chatImageRetryPort: ChatImageRetryPort,
    val imageLibraryCommitPort: ImageLibraryCommitPort,
    val localApiAuxProviderPort: LocalApiAuxProviderPort,
    val chatLocalApiProviderPort: ChatLocalApiProviderPort,
    val chatImagePromptBridgePort: ChatImagePromptBridgePort,
    val chatStopGenerationPort: ChatStopGenerationPort,
)

internal object MainViewModelPortFactory {
    fun createPorts(
        container: McaAppContainer,
        uiStateStore: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService,
        imageGenerationOwner: ImageGenerationOwner,
        chatImageCommitJournalProvider: () -> ChatImageCommitJournal,
        localApiPayloadPortProvider: () -> LocalApiPayloadPort,
        benchmarkRunner: BenchmarkRunner,
        buildRecommendationAction: (UserPreference) -> AgentRecommendation,
        generateLocalApiImageAction: suspend (String, String) -> String,
        generateLocalApiVideoAction: suspend (String, String) -> String,
        stopLocalApiVideoAction: suspend (String) -> Boolean,
        cancelActiveLocalApiImageGenerationAction: (String) -> Boolean,
        chatContextComposer: ChatContextComposer,
        showStatus: (String?) -> Unit,
        chatImageOptionsForCurrentSelection: (MainUiState) -> LocalImageGenerationOptions,
        offlinePromptTranslationServiceProvider: () -> OfflinePromptTranslationService,
        verifiedOfflineTranslationBundleRef: suspend () -> VerifiedOfflinePromptTranslationBundle?,
        chatImageNegativePromptSupported: (MainUiState) -> Boolean,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        stopGenerationInProgress: AtomicBoolean,
        currentReplyMetricsRef: () -> ReplyGenerationMetrics?,
        cancelGenerationJobRef: () -> UiGenerationCancellation,
        flushPendingAssistantOutputRef: () -> Unit,
        persistConversationMutationRef: (List<ChatSessionRecord>, ConversationMutationRollbackState) -> Unit,
    ): MainViewModelPorts {
        val chatSessionPersistencePort =
            container.createChatSessionPersistencePort(uiStateStore, scope, engine)

        val chatImageRequestPort: ChatImageRequestPort =
            container.createChatImageRequestPort(uiStateStore, chatSessionPersistencePort)

        val sendChatImageRequestPort: SendChatImageRequestPort =
            container.createSendChatImageRequestPort(
                uiStateStore, chatSessionPersistencePort, chatImageRequestPort, imageGenerationOwner
            )

        val chatImageRetryPort: ChatImageRetryPort =
            container.createChatImageRetryPort(
                uiStateStore, sendChatImageRequestPort, chatImageCommitJournalProvider
            )

        val imageLibraryCommitPort: ImageLibraryCommitPort =
            container.createImageLibraryCommitPort(uiStateStore, chatImageRequestPort)

        val localApiAuxProviderPort: LocalApiAuxProviderPort =
            container.createLocalApiAuxProviderPort(
                store = uiStateStore,
                engine = engine,
                localApiPayloadPortProvider = localApiPayloadPortProvider,
                benchmarkRunner = benchmarkRunner,
                buildRecommendationAction = buildRecommendationAction,
                generateLocalApiImageAction = generateLocalApiImageAction,
                generateLocalApiVideoAction = generateLocalApiVideoAction,
                stopLocalApiVideoAction = stopLocalApiVideoAction,
            )

        val chatLocalApiProviderPort: ChatLocalApiProviderPort =
            container.createChatLocalApiProviderPort(
                engine = engine,
                store = uiStateStore,
                cancelActiveLocalApiImageGenerationAction = cancelActiveLocalApiImageGenerationAction,
                localApiPayloadPortProvider = localApiPayloadPortProvider,
            )

        val chatImagePromptBridgePort: ChatImagePromptBridgePort =
            container.createChatImagePromptBridgePort(
                store = uiStateStore,
                chatContextComposer = chatContextComposer,
                engine = engine,
                currentBridgeGenerationRef = { container.runtimeAdmission.chatImagePromptBridgeGeneration.get() },
                incrementBridgeGenerationRef = { container.runtimeAdmission.chatImagePromptBridgeGeneration.incrementAndGet() },
                cancelBridgeJobRef = { container.runtimeAdmission.chatImagePromptBridgeJob?.cancel() },
                setBridgeJobRef = { container.runtimeAdmission.chatImagePromptBridgeJob = it },
                bridgeScopeRef = { scope },
                setPendingChatImageActionRef = { id, kind, visibleUserText, requestedOutputCount, stillOwned ->
                    container.runtimeAdmission.pendingChatImageAction = PendingChatImageAction(
                        id = id,
                        kind = kind,
                        visibleUserText = visibleUserText,
                        requestedOutputCount = requestedOutputCount,
                        stillOwned = stillOwned
                    )
                },
                setPendingImageTranslationRef = { container.runtimeAdmission.pendingImageTranslation = it },
                showStatusRef = { showStatus(it) },
                chatImageOptionsForCurrentSelectionRef = { chatImageOptionsForCurrentSelection(it) },
                offlinePromptTranslationServiceProvider = offlinePromptTranslationServiceProvider,
                verifiedOfflineTranslationBundleRef = verifiedOfflineTranslationBundleRef,
                sendChatImageRequestRef = { visibleUserText, prompt, optionsOverride, promptEnvelope, contextTraceJson ->
                    SendChatImageRequestOperations.sendChatImageRequest(
                        port = sendChatImageRequestPort,
                        visibleUserText = visibleUserText,
                        prompt = prompt,
                        optionsOverride = optionsOverride,
                        promptEnvelope = promptEnvelope,
                        contextTraceJson = contextTraceJson
                    )
                },
                negativePromptSupportedRef = { chatImageNegativePromptSupported(it) },
            )

        val chatStopGenerationPort: ChatStopGenerationPort =
            container.createChatStopGenerationPort(
                store = uiStateStore,
                engine = engine,
                stopScopeRef = { scope },
                rejectWhileConversationMutationInProgressRef = { rejectWhileConversationMutationInProgress() },
                stopGenerationInProgressRef = stopGenerationInProgress,
                currentReplyMetricsRef = currentReplyMetricsRef,
                cancelGenerationJobRef = cancelGenerationJobRef,
                flushPendingAssistantOutputRef = flushPendingAssistantOutputRef,
                persistConversationMutationRef = persistConversationMutationRef,
            )

        return MainViewModelPorts(
            chatSessionPersistencePort = chatSessionPersistencePort,
            chatImageRequestPort = chatImageRequestPort,
            sendChatImageRequestPort = sendChatImageRequestPort,
            chatImageRetryPort = chatImageRetryPort,
            imageLibraryCommitPort = imageLibraryCommitPort,
            localApiAuxProviderPort = localApiAuxProviderPort,
            chatLocalApiProviderPort = chatLocalApiProviderPort,
            chatImagePromptBridgePort = chatImagePromptBridgePort,
            chatStopGenerationPort = chatStopGenerationPort,
        )
    }
}
