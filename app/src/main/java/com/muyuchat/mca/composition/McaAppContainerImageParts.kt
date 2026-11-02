package com.muyuchat.mca.composition

import com.muyuchat.mca.UiStateReadPort
import com.muyuchat.mca.ImageUpscalerCatalogFields
import com.muyuchat.mca.ImageTranslationFields
import com.muyuchat.mca.ImageAssetDeletionFields
import com.muyuchat.mca.ImageUpscaleFields
import com.muyuchat.mca.ImageAssetCreationFields
import com.muyuchat.mca.UiStateWriter
import com.muyuchat.mca.OfflineTranslationFields
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
        store: UiStateWriter<ImageAssetCreationFields>,
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
        store: UiStateWriter<ImageUpscaleFields>,
        application: Application,
        assetCreationPort: ImageAssetCreationPort,
        startupReconciliation: ImageLibraryStartupReconciliation,
        setDeferGenerationImageGrantRelease: (Boolean) -> Unit
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
            mutationMutex = imageLibraryMutationMutex,
            setDeferGenerationImageGrantRelease = setDeferGenerationImageGrantRelease
        )

    /** R3/C3-S16：图片库提交/删除端口工厂。 */
internal fun McaAppContainer.createImageLibraryCommitPort(
        store: UiStateStore,
        requestPort: ChatImageRequestPort
    ): ImageLibraryCommitPort =
        ImageLibraryCommitPortAdapter(
            store = store.imageLibraryCommitWriter(),
            persistenceState = chatSessionPersistenceState,
            chatSessionStoreRef = chatSessionStore,
            journalProvider = { chatImageCommitJournal },
            admissionRef = runtimeAdmission,
            requestPortRef = requestPort,
            imageLibraryMutationMutexRef = imageLibraryMutationMutex
        )

internal fun McaAppContainer.createImageAssetDeletionPortImpl(
        store: UiStateWriter<ImageAssetDeletionFields>,
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
        store: UiStateWriter<ImageTranslationFields>,
        offlineTranslationStore: UiStateWriter<OfflineTranslationFields>,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPort =
        ImagePromptTranslationPortAdapter(
            store = store,
            offlineTranslationStore = offlineTranslationStore,
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
            store = store.imageModelSelectionWriter(),
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
            store = store.imageGenerationWriter(),
            runtimeStore = store.runtimeProfileWriter(),
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
        store: UiStateWriter<ImageUpscalerCatalogFields>,
        imageUpscaleOwnerProvider: () -> ImageUpscaleOwner
    ): LocalImageUpscalerPort =
        LocalImageUpscalerPortAdapter(
            store = store,
            upscalerStoreRef = localImageUpscalerStore,
            preferences = imageUpscalerPreferences,
            imageUpscaleOwnerProvider = imageUpscaleOwnerProvider
        )

internal fun McaAppContainer.createImagePromptTranslationPreviewPortImpl(
        store: UiStateWriter<ImageTranslationFields>,
        offlineTranslationStore: UiStateWriter<OfflineTranslationFields>,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPreviewPort =
        ImagePromptTranslationPreviewPortAdapter(
            store = store,
            offlineTranslationStore = offlineTranslationStore,
            admission = runtimeAdmission,
            downloader = offlineTranslationDownloader,
            serviceProvider = serviceProvider
        )

internal fun McaAppContainer.createAssistantImageToolTurnPortImpl(
        store: UiStateReadPort,
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
