package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.composition.MainRuntimeAssembly
import com.muyuchat.mca.presentation.OwnerBackedLocalApiImageGenerationPort
import com.muyuchat.mca.presentation.generateLocalApiImageOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ChatImageWorkflowCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: UiStateStore,
    private val container: com.muyuchat.mca.composition.McaAppContainer,
    private val imageAssetWriteMutex: Mutex,
    private val imageLibraryMutationMutex: Mutex,
    private val imageLibraryBackup: ImageLibraryBackup,
    private val chatSessionStore: ChatSessionStore,
    private val imageGenerationOwner: ImageGenerationOwner,
    private val imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
    private val chatImageRetryPort: ChatImageRetryPort,
    private val showStatus: (String?) -> Unit,
    private val offlinePromptTranslationService: () -> OfflinePromptTranslationService,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val engine: McaInferenceService,
    private val selectCloudImageModel: (String) -> Unit
) {
    /**
     * Compatibility constructor for the existing composition root. Local API ownership callbacks
     * are intentionally ignored: the port now enters [ImageGenerationOwner] directly.
     */
    @Suppress("UNUSED_PARAMETER")
    internal constructor(
        application: Application,
        scope: CoroutineScope,
        store: UiStateStore,
        container: com.muyuchat.mca.composition.McaAppContainer,
        imageAssetWriteMutex: Mutex,
        imageLibraryMutationMutex: Mutex,
        imageLibraryBackup: ImageLibraryBackup,
        chatSessionStore: ChatSessionStore,
        imageGenerationOwner: ImageGenerationOwner,
        imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
        chatImageRetryPort: ChatImageRetryPort,
        showStatus: (String?) -> Unit,
        tryAcquireObservedImageGenerationLease: (String) -> LocalImageGenerationCoordinator.Lease?,
        releaseObservedImageGenerationLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
        offlinePromptTranslationService: () -> OfflinePromptTranslationService,
        runtimeProfileStore: ModelRuntimeProfileStore,
        engine: McaInferenceService,
        selectCloudImageModel: (String) -> Unit,
        registerLocalApiImageGenerationOwnership: (LocalApiImageGenerationOwnership) -> Unit,
        unregisterLocalApiImageGenerationOwnership: (LocalApiImageGenerationOwnership) -> Boolean,
        cancelOwnedLocalApiImageWorker: (LocalApiImageGenerationOwnership) -> Boolean
    ) : this(
        application = application,
        scope = scope,
        store = store,
        container = container,
        imageAssetWriteMutex = imageAssetWriteMutex,
        imageLibraryMutationMutex = imageLibraryMutationMutex,
        imageLibraryBackup = imageLibraryBackup,
        chatSessionStore = chatSessionStore,
        imageGenerationOwner = imageGenerationOwner,
        imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
        chatImageRetryPort = chatImageRetryPort,
        showStatus = showStatus,
        offlinePromptTranslationService = offlinePromptTranslationService,
        runtimeProfileStore = runtimeProfileStore,
        engine = engine,
        selectCloudImageModel = selectCloudImageModel
    )
    val imageAssetCreationPort: ImageAssetCreationPort =
        container.createImageAssetCreationPort(store, application, imageGenerationOwner)

    val imageUpscalePort: ImageUpscalePort =
        container.createImageUpscalePort(
            store, application, imageAssetCreationPort, imageLibraryStartupReconciliation
        )

    val imageUpscaleOwner = ImageUpscaleOwner(imageUpscalePort)

    val imagePromptTranslationPort: ImagePromptTranslationPort =
        container.createImagePromptTranslationPort(
            store,
            offlinePromptTranslationService
        )

    val imageAssetDeletionPort: ImageAssetDeletionPort =
        container.createImageAssetDeletionPort(
            store, scope, imageUpscaleOwner, imageLibraryStartupReconciliation
        )

    val imageLibraryBackupCoordinator = ImageLibraryBackupCoordinator()

    fun generateImageAsset(
        prompt: String,
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ): Boolean = imageGenerationOwner.submit(
        prompt = prompt,
        inputDraft = inputDraft,
        options = options,
        jobSnapshot = null
    )

    private val _uiState get() = store.state

    fun retryImageGeneration(jobId: String) {
        val sourceJob = store.current.imageJobs.firstOrNull { it.id == jobId }
        val originalSnapshot = sourceJob?.spec
        if (sourceJob == null || originalSnapshot == null) {
            showStatus("原图片任务参数已不可用，无法按原任务重试。")
            return
        }
        if (!sourceJob.status.terminal) {
            showStatus("当前图片任务仍在运行，无需重复提交。")
            return
        }
        // A retry is a new admission. Re-resolve the current model record and discard prompt
        // execution evidence captured by the failed/previous worker; the profile, tokenizer and
        // language gate must be evaluated again before a new worker is bound.
        val currentModel = originalSnapshot.localModelSnapshot?.let { previous ->
            _uiState.value.localImageModels.firstOrNull { it.matchesCatalogId(previous.id) } ?: previous
        }
        val retrySnapshot = originalSnapshot.copy(
            localModelSnapshot = currentModel,
            promptExecution = null
        )
        val jobSnapshot = retrySnapshot
        val snapshot = jobSnapshot
        imageGenerationOwner.submit(
            prompt = snapshot.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot
        )
    }

    fun upscaleImageAsset(imageId: String, targetScale: Int) {
        imageUpscaleOwner.upscaleImageAsset(imageId, targetScale, scope)
    }

    fun cancelImageUpscale() {
        imageUpscaleOwner.cancelImageUpscale(scope)
    }

    fun recreateImageAsset(imageId: String) {
        recreateAsset(port = imageAssetCreationPort, imageId = imageId)
    }

    fun selectLocalImageModel(modelId: String) {
        applyLocalImageModelSelection(port = imageAssetCreationPort, modelId = modelId, scope = scope)
    }

    suspend fun createUpscaledImageAsset(
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord = imageAssetWriteMutex.withLock {
        createUpscaledAsset(port = imageAssetCreationPort, spec = spec, result = result, requestId = requestId)
    }

    fun cancelImageGeneration() = cancelImageGenerationForJob(expectedJobId = null)

    fun cancelImageGenerationForJob(expectedJobId: String?) =
        cancelImageGenerationForJobFor(
            scope,
            store,
            container.runtimeAdmission,
            container.localImageWorkerClient,
            container.runtimeAdmission.activeLocalImageWorkerRequestId,
            expectedJobId
        )

    fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? =
        publishLocalImagePreviewTo(
            store,
            container.runtimeAdmission,
            application,
            imageAssetWriteMutex,
            jobId,
            progress
        )

    fun deleteImageAsset(imageId: String) = deleteImageAssets(listOf(imageId))
    fun deleteImageAssets(imageIds: List<String>) = deleteImageAssetsOperation(imageAssetDeletionPort, imageIds)
    fun setImageAssetFavorite(imageId: String, favorite: Boolean) = setImageAssetFavoriteOperation(imageAssetDeletionPort, imageId, favorite)
    fun clearImageLibrary() = clearImageLibraryOperation(imageAssetDeletionPort)

    fun exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) =
        imageLibraryBackupCoordinator.exportBackup(
            store, scope, application, imageLibraryBackup, imageLibraryMutationMutex,
            { imageLibraryStartupReconciliation.await() }, { container.runtimeAdmission.imageLibraryBackupJob },
            { container.runtimeAdmission.imageLibraryBackupJob = it },
            showStatus, uriString, favoritesOnly
        )

    fun importImageLibraryBackup(uriString: String) =
        imageLibraryBackupCoordinator.importBackup(
            store, scope, application, imageLibraryBackup, chatSessionStore,
            imageAssetWriteMutex, imageLibraryMutationMutex, { imageLibraryStartupReconciliation.await() },
            { container.runtimeAdmission.imageLibraryBackupJob }, { container.runtimeAdmission.imageLibraryBackupJob = it },
            showStatus, uriString
        )

    fun cancelImageLibraryBackup() =
        imageLibraryBackupCoordinator.cancelBackup { container.runtimeAdmission.imageLibraryBackupJob }

    fun selectImageGenerationModel(choiceId: String) {
        when {
            choiceId.startsWith(MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX) -> {
                selectLocalImageModel(choiceId.removePrefix(MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX))
            }
            choiceId.startsWith(MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX) -> {
                val id = choiceId.removePrefix(MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX)
                selectCloudImageModel(id)
            }
        }
    }

    fun deleteCloudModel(modelId: String) {
        deleteCloudModelRecord(port = chatImageRetryPort, modelId = modelId)
    }

    fun confirmChatImageTranslationDraft(id: String, positive: String, negative: String?) {
        applyChatImageTranslationDraft(port = chatImageRetryPort, id = id, positive = positive, negative = negative)
    }

    fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec? =
        chatImageRetrySnapshot(port = chatImageRetryPort, request = request, chatSessionId = chatSessionId)

    fun releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock: () -> Unit): Boolean =
        synchronized(container.localImageGenerationCoordinatorObservationLock) {
            if (container.localImageGenerationCoordinator.activeRequestId() != null) {
                false
            } else {
                releaseBlock()
                true
            }
        }

    fun updateGenerationImageGrantReleaseDefer(defer: Boolean) =
        updateGenerationImageGrantReleaseDeferInto(store, defer)

    val localApiImageGenerationPort: OwnerBackedLocalApiImageGenerationPort =
        LocalApiImageGenerationOwnerPortAdapter(
            store = store,
            imageGenerationOwner = imageGenerationOwner,
            localImageLoraLifecycleLock = container.localImageLoraLifecycleLock,
            localImageTextualInversionLifecycleLock = container.localImageTextualInversionLifecycleLock,
            localImageWorkerClient = LocalApiImageWorkerGatewayAdapter { container.localImageWorkerClient },
            localImageLoraStore = LocalApiImageLoraGatewayAdapter { container.localImageLoraStore },
            resolveCurrentLocalImageLorasRef1 = { resolveCurrentLocalImageLorasFor(it, store.current.localImageLoras) },
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
                prepareLocalImageRuntimeMemoryFor(
                    store, container.runtimeAdmission, runtimeProfileStore, engine
                )
            },
            executionWithQnnVerificationEvidenceRef = { model, result, options, requestId, attemptId, entryPoint ->
                executionWithQnnVerificationEvidenceFor(
                    application, model, result, options, requestId, attemptId, entryPoint
                )
            },
        )

    suspend fun generateLocalApiImage(requestId: String, body: String): String =
        generateLocalApiImageOperations(
            requestId = requestId,
            body = body,
            port = localApiImageGenerationPort
        )
}

/**
 * MainViewModel-facing factory overload. The compatibility callbacks required by the old
 * composition signature are not ownership paths anymore: Local API image execution enters the
 * injected [ImageGenerationOwner] through its owner-backed port.
 */
internal fun MainRuntimeAssembly.createChatImageWorkflowCoordinator(
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
    selectCloudImageModel: (String) -> Unit
): ChatImageWorkflowCoordinator = createChatImageWorkflowCoordinator(
    store = store,
    imageAssetWriteMutex = imageAssetWriteMutex,
    imageLibraryMutationMutex = imageLibraryMutationMutex,
    imageLibraryBackup = imageLibraryBackup,
    chatSessionStore = chatSessionStore,
    imageGenerationOwner = imageGenerationOwner,
    imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
    chatImageRetryPort = chatImageRetryPort,
    showStatus = showStatus,
    tryAcquireObservedImageGenerationLease = { null },
    releaseObservedImageGenerationLease = { false },
    offlinePromptTranslationService = offlinePromptTranslationService,
    runtimeProfileStore = runtimeProfileStore,
    selectCloudImageModel = selectCloudImageModel,
    registerLocalApiImageGenerationOwnership = {},
    unregisterLocalApiImageGenerationOwnership = { false },
    cancelOwnedLocalApiImageWorker = { false }
)
