package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.mca.ChatImageRequestPort
import com.muyuchat.mca.ImageAssetCreationPort
import com.muyuchat.mca.ImageAssetCreationPortAdapter
import com.muyuchat.mca.ImageAssetDeletionPort
import com.muyuchat.mca.ImageAssetDeletionPortAdapter
import com.muyuchat.mca.ImageLibraryCommitPort
import com.muyuchat.mca.ImageLibraryCommitPortAdapter
import com.muyuchat.mca.ImageUpscaleOwner
import com.muyuchat.mca.ImageUpscalePort
import com.muyuchat.mca.ImageUpscalePortAdapter
import com.muyuchat.mca.LocalImageUpscalerPort
import com.muyuchat.mca.LocalImageUpscalerPortAdapter
import com.muyuchat.mca.CreateLocalGeneratedImageAssetPort
import com.muyuchat.mca.CreateLocalGeneratedImageAssetPortAdapter
import com.muyuchat.mca.LocalGeneratedImageWorkerGatewayAdapter
import com.muyuchat.mca.ImagePromptTranslationPort
import com.muyuchat.mca.ImagePromptTranslationPortAdapter
import com.muyuchat.mca.ImagePromptTranslationPreviewPort
import com.muyuchat.mca.ImagePromptTranslationPreviewPortAdapter
import com.muyuchat.mca.AssistantImageToolTurnPort
import com.muyuchat.mca.AssistantImageToolTurnPortAdapter
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.mca.ChatImagePromptBridge
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.ImageGenerationJobSpec
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ImageGenerationHostAdapter
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope

internal fun McaAppContainer.createImageAssetCreationPortImpl(
        store: UiStateStore,
        application: Application,
        imageGenerationOwner: ImageGenerationOwner
    ): ImageAssetCreationPort =
        ImageAssetCreationPortAdapter(
            store = store,
            application = application,
            admission = runtimeAdmission,
            imageGenerationOwner = imageGenerationOwner,
            assetWriteMutex = imageAssetWriteMutex,
            assetDirectory = imageAssetDirectory,
            localImageModelStoreRef = localImageModelStore
        )

internal fun McaAppContainer.createImageUpscalePortImpl(
        store: UiStateStore,
        application: Application,
        assetCreationPort: ImageAssetCreationPort,
        startupReconciliation: ImageLibraryStartupReconciliation
    ): ImageUpscalePort =
        ImageUpscalePortAdapter(
            store = store,
            application = application,
            coordinator = localImageGenerationCoordinator,
            observationLock = localImageGenerationCoordinatorObservationLock,
            assetCreationPort = assetCreationPort,
            assetWriteMutex = imageAssetWriteMutex,
            startupReconciliation = startupReconciliation,
            workerClientRef = localImageWorkerClient,
            chatSessionStoreRef = chatSessionStore,
            upscalerStoreRef = localImageUpscalerStore,
            assetDirectory = imageAssetDirectory,
            mutationMutex = imageLibraryMutationMutex
        )

    /** R3/C3-S16：图片库提交/删除端口工厂。 */
internal fun McaAppContainer.createImageLibraryCommitPort(
        store: UiStateStore,
        requestPort: ChatImageRequestPort
    ): ImageLibraryCommitPort =
        ImageLibraryCommitPortAdapter(
            store = store,
            persistenceState = chatSessionPersistenceState,
            chatSessionStoreRef = chatSessionStore,
            journalProvider = { chatImageCommitJournal },
            admissionRef = runtimeAdmission,
            requestPortRef = requestPort,
            imageLibraryMutationMutexRef = imageLibraryMutationMutex
        )

internal fun McaAppContainer.createImageAssetDeletionPortImpl(
        store: UiStateStore,
        scope: CoroutineScope,
        imageUpscaleOwner: ImageUpscaleOwner,
        startupReconciliation: ImageLibraryStartupReconciliation
    ): ImageAssetDeletionPort =
        ImageAssetDeletionPortAdapter(
            store = store,
            scopeRef = scope,
            admissionRef = runtimeAdmission,
            imageUpscaleOwnerRef = imageUpscaleOwner,
            chatSessionStoreRef = chatSessionStore,
            mutationMutex = imageLibraryMutationMutex,
            assetDirectory = imageAssetDirectory,
            startupReconciliation = startupReconciliation
        )

internal fun McaAppContainer.createImagePromptTranslationPortImpl(
        store: UiStateStore,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPort =
        ImagePromptTranslationPortAdapter(
            store = store,
            admission = runtimeAdmission,
            downloader = offlineTranslationDownloader,
            serviceProvider = serviceProvider
        )

    /** R3-S30：本地生图资产生成端口工厂（worker 代际经 holder；网关包真实客户端）。 */
internal fun McaAppContainer.createCreateLocalGeneratedImageAssetPort(
        store: UiStateStore,
        application: Application
    ): CreateLocalGeneratedImageAssetPort =
        CreateLocalGeneratedImageAssetPortAdapter(
            store = store,
            admission = runtimeAdmission,
            gateway = LocalGeneratedImageWorkerGatewayAdapter(localImageWorkerClient),
            localImageModelStoreRef = localImageModelStore,
            readinessGate = readinessRefreshGate,
            assetWriteMutex = imageAssetWriteMutex,
            assetDirectory = imageAssetDirectory,
            application = application
        )

internal fun McaAppContainer.createImageGenerationOwnerImpl(
        store: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService,
        imageLibraryStartupReconciliationProvider: () -> ImageLibraryStartupReconciliation,
        imageLibraryCommitPortProvider: () -> ImageLibraryCommitPort,
        chatImageRequestPortProvider: () -> ChatImageRequestPort,
        imagePromptTranslationPortProvider: () -> ImagePromptTranslationPort,
        completeAssistantImageToolTurnForJob: (String) -> Unit
    ): ImageGenerationOwner = ImageGenerationOwner(
        host = ImageGenerationHostAdapter(
            store = store,
            scopeRef = scope,
            applicationRef = application,
            runtimeAdmission = runtimeAdmission,
            coordinator = localImageGenerationCoordinator,
            coordinatorObservationLock = localImageGenerationCoordinatorObservationLock,
            localImageLoraLifecycleLockRef = localImageLoraLifecycleLock,
            localImageTextualInversionLifecycleLockRef = localImageTextualInversionLifecycleLock,
            localImageLoraStoreRef = localImageLoraStore,
            localImageWorkerClientRef = localImageWorkerClient,
            imageAssetDirectoryRef = imageAssetDirectory,
            imageAssetWriteMutex = imageAssetWriteMutex,
            cloudImageProviderRef = cloudImageProvider,
            chatImageCommitJournalRef = chatImageCommitJournal,
            runtimeProfileStoreRef = runtimeProfileStore,
            engineRef = engine,
            createLocalGeneratedImageAssetPort = createCreateLocalGeneratedImageAssetPort(store, application),
            imageLibraryStartupReconciliationProvider = imageLibraryStartupReconciliationProvider,
            imageLibraryCommitPortProvider = imageLibraryCommitPortProvider,
            chatImageRequestPortProvider = chatImageRequestPortProvider,
            imagePromptTranslationPortProvider = imagePromptTranslationPortProvider,
            completeAssistantImageToolTurnForJobRef = completeAssistantImageToolTurnForJob
        )
    )

internal fun McaAppContainer.createLocalImageUpscalerPortImpl(
        store: UiStateStore,
        imageUpscaleOwnerProvider: () -> ImageUpscaleOwner
    ): LocalImageUpscalerPort =
        LocalImageUpscalerPortAdapter(
            store = store,
            upscalerStoreRef = localImageUpscalerStore,
            preferences = imageUpscalerPreferences,
            imageUpscaleOwnerProvider = imageUpscaleOwnerProvider
        )

internal fun McaAppContainer.createImagePromptTranslationPreviewPortImpl(
        store: UiStateStore,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPreviewPort =
        ImagePromptTranslationPreviewPortAdapter(
            store = store,
            admission = runtimeAdmission,
            downloader = offlineTranslationDownloader,
            serviceProvider = serviceProvider
        )

internal fun McaAppContainer.createAssistantImageToolTurnPortImpl(
        store: UiStateStore,
        chatImageNegativePromptSupported: (MainUiState) -> Boolean,
        prepareAssistantImagePrompt: suspend (
            prompt: String,
            chatConfig: CloudApiConfig?,
            params: GenerationParams,
            conversationContext: List<ChatMessage>
        ) -> ChatImagePromptBridge.Result,
        prepareAssistantImageNegativePrompt: suspend (
            prompt: String,
            chatConfig: CloudApiConfig?,
            params: GenerationParams,
            conversationContext: List<ChatMessage>,
            preserveAuthoredNegative: Boolean
        ) -> ChatImagePromptBridge.Result,
        setPendingAssistantToolFailure: (String) -> Unit,
        buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
        attachAssistantImageRequestToTimeline: (String, ChatGeneratedImageRequest) -> Unit,
        approveChatImageRequest: (String) -> Unit
    ): AssistantImageToolTurnPort =
        AssistantImageToolTurnPortAdapter(
            store = store,
            admission = runtimeAdmission,
            authorizationStore = chatImageToolAuthorizationStore,
            chatImageNegativePromptSupportedRef = chatImageNegativePromptSupported,
            prepareAssistantImagePromptRef = prepareAssistantImagePrompt,
            prepareAssistantImageNegativePromptRef = prepareAssistantImageNegativePrompt,
            setPendingAssistantToolFailureRef = setPendingAssistantToolFailure,
            buildChatImageRetrySnapshotRef = buildChatImageRetrySnapshot,
            attachAssistantImageRequestToTimelineRef = attachAssistantImageRequestToTimeline,
            approveChatImageRequestRef = approveChatImageRequest
        )
