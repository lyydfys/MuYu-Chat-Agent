package com.muyuchat.mca.composition

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

/**
 * Constructs the image workflow's ports in their established order. Business orchestration receives
 * these specific capabilities instead of retaining the application container. Provider callbacks
 * stay deferred, including translation, worker gateways and Local API runtime preparation.
 */
internal fun McaAppContainer.assembleChatImageWorkflowCoordinator(
    scope: CoroutineScope,
    store: UiStateStore,
    imageAssetWriteMutex: Mutex,
    imageLibraryMutationMutex: Mutex,
    imageLibraryBackup: ImageLibraryBackup,
    chatSessionStore: ChatSessionStore,
    imageGenerationOwner: ImageGenerationOwner,
    imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
    chatImageRetryPort: ChatImageRetryPort,
    showStatus: (String?) -> Unit,
    offlinePromptTranslationService: () -> OfflinePromptTranslationService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    engine: McaInferenceService,
    selectCloudImageModel: (String) -> Unit,
): ChatImageWorkflowCoordinator {
    val generationStore = store.imageGenerationWriter()
    val runtimeStore = store.runtimeProfileWriter()
    val imageAssetCreationPort = createImageAssetCreationPort(
        store.imageAssetCreationWriter(), application, imageGenerationOwner,
    )
    val imageUpscalePort = createImageUpscalePort(
        store.imageUpscaleWriter(), application, imageAssetCreationPort,
        imageLibraryStartupReconciliation,
        setDeferGenerationImageGrantRelease = {
            updateGenerationImageGrantReleaseDeferInto(generationStore, it)
        },
    )
    val imageUpscaleOwner = ImageUpscaleOwner(imageUpscalePort)
    val imagePromptTranslationPort = createImagePromptTranslationPort(
        store.imageTranslationWriter(), store.offlineTranslationWriter(),
        offlinePromptTranslationService,
    )
    val imageAssetDeletionPort = createImageAssetDeletionPort(
        store.imageAssetDeletionWriter(), scope, imageUpscaleOwner,
        imageLibraryStartupReconciliation,
    )
    val imageLibraryBackupCoordinator = ImageLibraryBackupCoordinator()
    val localApiImageGenerationPort = LocalApiImageGenerationOwnerPortAdapter(
        store = store.localApiImageWriter(),
        imageGenerationOwner = imageGenerationOwner,
        localImageLoraLifecycleLock = localImageLoraLifecycleLock,
        localImageTextualInversionLifecycleLock = localImageTextualInversionLifecycleLock,
        localImageWorkerClient = LocalApiImageWorkerGatewayAdapter { localImageWorkerClient },
        localImageLoraStore = LocalApiImageLoraGatewayAdapter { localImageLoraStore },
        resolveCurrentLocalImageLorasRef1 = {
            resolveCurrentLocalImageLorasFor(it, store.current.localImageLoras)
        },
        resolveCurrentLocalImageLorasRef2 = { selections, records ->
            resolveCurrentLocalImageLorasFor(selections, records)
        },
        resolveCurrentLocalImageTextualInversionIdsRef = {
            resolveCurrentLocalImageTextualInversionIdsFor(it, store.current.localImageTextualInversions)
        },
        prepareLocalImagePromptExecutionRef = { model, prompt, options, captured ->
            prepareLocalImagePromptExecution(imagePromptTranslationPort, model, prompt, options, captured)
        },
        prepareLocalImageRuntimeMemoryRef = {
            prepareLocalImageRuntimeMemoryFor(runtimeStore, runtimeAdmission, runtimeProfileStore, engine)
        },
        executionWithQnnVerificationEvidenceRef = { model, result, options, requestId, attemptId, entryPoint ->
            executionWithQnnVerificationEvidenceFor(
                application, model, result, options, requestId, attemptId, entryPoint,
            )
        },
    )
    return ChatImageWorkflowCoordinator(
        application = application,
        scope = scope,
        store = store,
        jobStore = store.imageJobCancellationWriter(),
        backupStore = store.imageLibraryBackupWriter(),
        generationStore = generationStore,
        admission = runtimeAdmission,
        localImageWorkerClient = localImageWorkerClient,
        localImageGenerationCoordinator = localImageGenerationCoordinator,
        localImageGenerationCoordinatorObservationLock = localImageGenerationCoordinatorObservationLock,
        imageAssetWriteMutex = imageAssetWriteMutex,
        imageLibraryMutationMutex = imageLibraryMutationMutex,
        imageLibraryBackup = imageLibraryBackup,
        chatSessionStore = chatSessionStore,
        imageGenerationOwner = imageGenerationOwner,
        imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
        chatImageRetryPort = chatImageRetryPort,
        imageAssetCreationPort = imageAssetCreationPort,
        imageUpscalePort = imageUpscalePort,
        imageUpscaleOwner = imageUpscaleOwner,
        imagePromptTranslationPort = imagePromptTranslationPort,
        imageAssetDeletionPort = imageAssetDeletionPort,
        imageLibraryBackupCoordinator = imageLibraryBackupCoordinator,
        localApiImageGenerationPort = localApiImageGenerationPort,
        showStatus = showStatus,
        selectCloudImageModel = selectCloudImageModel,
    )
}
