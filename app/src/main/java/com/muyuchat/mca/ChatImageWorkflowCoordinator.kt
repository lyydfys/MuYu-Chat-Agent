package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.mca.presentation.OwnerBackedLocalApiImageGenerationPort
import com.muyuchat.mca.presentation.generateLocalApiImageOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Image intents and cancellation share the existing owners; composition supplies each port. */
internal class ChatImageWorkflowCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: UiStateReadPort,
    private val jobStore: UiStateWriter<ImageJobCancellationFields>,
    private val backupStore: UiStateWriter<ImageLibraryBackupFields>,
    private val generationStore: UiStateWriter<ImageGenerationFields>,
    private val admission: RuntimeAdmissionState,
    private val localImageWorkerClient: LocalImageWorkerClient,
    private val localImageGenerationCoordinator: LocalImageGenerationCoordinator,
    private val localImageGenerationCoordinatorObservationLock: Any,
    private val imageAssetWriteMutex: Mutex,
    private val imageLibraryMutationMutex: Mutex,
    private val imageLibraryBackup: ImageLibraryBackup,
    private val chatSessionStore: ChatSessionStore,
    private val imageGenerationOwner: ImageGenerationOwner,
    private val imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
    private val chatImageRetryPort: ChatImageRetryPort,
    val imageAssetCreationPort: ImageAssetCreationPort,
    val imageUpscalePort: ImageUpscalePort,
    val imageUpscaleOwner: ImageUpscaleOwner,
    val imagePromptTranslationPort: ImagePromptTranslationPort,
    val imageAssetDeletionPort: ImageAssetDeletionPort,
    val imageLibraryBackupCoordinator: ImageLibraryBackupCoordinator,
    val localApiImageGenerationPort: OwnerBackedLocalApiImageGenerationPort,
    private val showStatus: (String?) -> Unit,
    private val selectCloudImageModel: (String) -> Unit
) {
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
        // 重试前先确认原任务的输入图仍然可读：失败任务留下的失效引用不能再提交一次，
        // 否则只会重复同一条 ENOENT 失败。原因直接告诉用户去重新选图，不需要新开聊天。
        val missingInputs = missingGenerationImageInputRoles(
            references = localImageInputReferences(snapshot.inputDraft),
            isReadable = { reference -> generationImageReferenceReadable(application, reference) },
        )
        if (missingInputs.isNotEmpty()) {
            showStatus(generationImageInputMissingMessage(missingInputs))
            return
        }
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
            jobStore,
            admission,
            localImageWorkerClient,
            admission.activeLocalImageWorkerRequestId,
            expectedJobId
        )

    fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? =
        publishLocalImagePreviewTo(
            jobStore,
            admission,
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
            backupStore, scope, application, imageLibraryBackup, imageLibraryMutationMutex,
            { imageLibraryStartupReconciliation.await() }, { admission.imageLibraryBackupJob },
            { admission.imageLibraryBackupJob = it },
            showStatus, uriString, favoritesOnly
        )

    fun importImageLibraryBackup(uriString: String) =
        imageLibraryBackupCoordinator.importBackup(
            backupStore, scope, application, imageLibraryBackup, chatSessionStore,
            imageAssetWriteMutex, imageLibraryMutationMutex, { imageLibraryStartupReconciliation.await() },
            { admission.imageLibraryBackupJob }, { admission.imageLibraryBackupJob = it },
            showStatus, uriString
        )

    fun cancelImageLibraryBackup() =
        imageLibraryBackupCoordinator.cancelBackup { admission.imageLibraryBackupJob }

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
        synchronized(localImageGenerationCoordinatorObservationLock) {
            if (localImageGenerationCoordinator.activeRequestId() != null) {
                false
            } else {
                releaseBlock()
                true
            }
        }

    fun updateGenerationImageGrantReleaseDefer(defer: Boolean) =
        updateGenerationImageGrantReleaseDeferInto(generationStore, defer)

    suspend fun generateLocalApiImage(requestId: String, body: String): String =
        generateLocalApiImageOperations(
            requestId = requestId,
            body = body,
            port = localApiImageGenerationPort
        )
}
