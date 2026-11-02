package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleLocalImageLibraryAndModelCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    container: McaAppContainer,
    imageUpscaleOwner: () -> ImageUpscaleOwner,
    showStatus: (String?) -> Unit,
    fail: (String) -> Unit,
    busy: (String) -> Unit,
    launchModelOperation: (String, suspend CoroutineScope.() -> Unit) -> Job,
    tryAcquireObservedImageGenerationLease: (String) -> LocalImageGenerationCoordinator.Lease?,
    releaseObservedImageGenerationLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
    persistFiles: (List<FileAssetRecord>) -> Unit,
): LocalImageLibraryAndModelCoordinator = LocalImageLibraryAndModelCoordinator(
    store = store.imageWorkspaceWriter(),
    attachmentStore = store.chatAttachmentWriter(),
    modelSelectionStore = store.imageModelSelectionWriter(),
    modelCatalogStore = store.modelCatalogWriter(),
    adapterStore = store.localImageAdapterWriter(),
    localImageAdapterPort = container.createLocalImageAdapterPort(store.localImageAdapterWriter()),
    localImageUpscalerPort = container.createLocalImageUpscalerPort(store.imageUpscalerCatalogWriter(), imageUpscaleOwner),
    scope = scope,
    application = application,
    chatSessionStore = container.chatSessionStore,
    localImageModelStore = container.localImageModelStore,
    localImageWorkerClient = container.localImageWorkerClient,
    localImageLoraStore = container.localImageLoraStore,
    managedRuntimeReadinessRefreshGate = container.readinessRefreshGate,
    launchModelOperation = { label, block ->
        launchModelOperation(label, block)
        Unit
    },
    tryAcquireObservedImageGenerationLease = tryAcquireObservedImageGenerationLease,
    releaseObservedImageGenerationLease = releaseObservedImageGenerationLease,
    showStatus = showStatus,
    busy = busy,
    fail = fail,
    persistFiles = persistFiles,
    activeImageGenerationModelId = { container.runtimeAdmission.activeImageGenerationModelId },
    activeLocalApiImageModelId = { container.runtimeAdmission.activeLocalApiImageModelId }
)

internal fun assembleAssistantImageContinuationCoordinator(
    store: UiStateStore,
    container: McaAppContainer,
    chatContextComposer: ChatContextComposer,
    chatImageRequestPort: () -> ChatImageRequestPort,
    imageGenerationOwner: () -> ImageGenerationOwner,
    showStatus: (String?) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
    chatImageNegativePromptSupported: (MainUiState) -> Boolean,
    cancelImageGenerationForJob: (String?) -> Unit,
    reserveUiGenerationStart: () -> UiGenerationReservation?,
    startGeneration: (List<ChatMessage>, UiGenerationReservation?, List<ChatToolExchange>, GenerationParams?, Boolean, String?, String?) -> Boolean,
): AssistantImageToolContinuationCoordinator = AssistantImageToolContinuationCoordinator(
    store = store.assistantImageContinuationWriter(),
    admission = container.runtimeAdmission,
    chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore,
    cloudChatProvider = container.cloudChatProvider,
    chatContextComposer = chatContextComposer,
    imageGenerationOwnerProvider = { imageGenerationOwner() },
    chatImageRequestPort = { chatImageRequestPort() },
    persistChatSessions = { persistChatSessions(it, null, null) },
    buildChatImageRetrySnapshot = { req, sId -> buildChatImageRetrySnapshot(req, sId) },
    chatImageNegativePromptSupported = { chatImageNegativePromptSupported(it) },
    cancelImageGenerationForJob = { cancelImageGenerationForJob(it) },
    reserveUiGenerationStart = reserveUiGenerationStart,
    cancelPendingReservation = { container.chatRuns.cancelPending(it) },
    showStatus = showStatus,
    startGeneration = { requestMessages, reservation, toolExchanges, paramsOverride, offerAssistantImageTool, chatModelIdOverride, continuationRequestId ->
        startGeneration(requestMessages, reservation, toolExchanges, paramsOverride, offerAssistantImageTool, chatModelIdOverride, continuationRequestId)
    }
)

internal fun assembleOfflinePromptTranslationAndChatImageCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    container: McaAppContainer,
    offlinePromptTranslationService: () -> OfflinePromptTranslationService,
    showStatus: (String?) -> Unit,
    sendChatImageRequestWithPromptBridge: (String, String, Int?, Boolean) -> Unit,
    sendPreparedMessage: (MainUiState, PreparedChatInput) -> Unit,
    prepareChatInput: (MainUiState) -> PreparedChatInput,
    assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
): OfflinePromptTranslationAndChatImageCoordinator {
    val offlineTranslationStore = store.offlineTranslationWriter()
    return OfflinePromptTranslationAndChatImageCoordinator(
        store = offlineTranslationStore,
        promptDraftStore = store.chatPromptDraftWriter(),
        admission = container.runtimeAdmission,
        scope = scope,
        imagePromptTranslationPreviewPort = container.createImagePromptTranslationPreviewPort(
            store = store.imageTranslationWriter(),
            offlineTranslationStore = offlineTranslationStore,
            serviceProvider = offlinePromptTranslationService
        ),
        installer = container.offlineTranslationInstaller,
        downloader = container.offlineTranslationDownloader,
        chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore,
        prepareChatInput = { prepareChatInput(it) },
        showStatus = showStatus,
        rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgressInto(store, container.runtimeAdmission) },
        hasUnresolvedAssistantImageToolTurn = assistantImageContinuationCoordinator::hasUnresolvedAssistantImageToolTurn,
        sendChatImageRequestWithPromptBridge = sendChatImageRequestWithPromptBridge,
        sendPreparedMessage = sendPreparedMessage
    )
}

