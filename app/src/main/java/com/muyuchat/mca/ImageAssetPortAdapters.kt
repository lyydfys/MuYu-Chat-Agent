package com.muyuchat.mca

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** R3/C3-S15：资产生成端口具名化（owner 真实对象注入；注释中的 requestedJobId 丢弃为现存行为）。 */
internal class ImageAssetCreationPortAdapter(
    private val store: UiStateWriter<ImageAssetCreationFields>,
    private val application: Application,
    private val admission: RuntimeAdmissionState,
    private val imageGenerationOwner: ImageGenerationOwner,
    private val assetWriteMutex: Mutex,
    private val assetDirectory: File,
    private val localImageModelStoreRef: LocalImageModelStore
) : ImageAssetCreationPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (ImageAssetCreationFields) -> ImageAssetCreationFields) {
        store.update(transform)
    }

    override val context: Application
        get() = application

    override suspend fun <T> withImageAssetWriteLock(block: suspend () -> T): T =
        assetWriteMutex.withLock { block() }

    override val imageAssetDirectory: File
        get() = assetDirectory

    override val localImageModelStore: LocalImageModelStore
        get() = localImageModelStoreRef

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override fun invalidatePendingChatImagePreparation() {
        invalidatePendingChatImagePreparationFor(admission) {
            store.update { it.copy(
                pendingChatImageTranslationDraft = null,
                pendingChatImageActionDraft = null
            ) }
        }
    }

    override fun canReadGenerationHistoryInput(reference: String): Boolean =
        canReadGenerationHistoryInputFor(application, reference)

    override fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean =
        rejectImageModelSwitchWhileGenerationIsActiveFor(store, admission)

    override fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?,
        requestedJobId: String?,
        requestedChatSessionId: String?,
        requestedChatMessageId: String?
    ): Boolean = imageGenerationOwner.submit(
        prompt = prompt,
        inputDraft = inputDraft,
        options = options,
        jobSnapshot = jobSnapshot,
        requestedChatSessionId = requestedChatSessionId,
        requestedChatMessageId = requestedChatMessageId
    )

    override fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora> = resolveCurrentLocalImageLorasFor(selections, records)

    override fun activeImageGenerationJobId(): String? =
        admission.activeImageGenerationJobId

    override fun activeImageGenerationModelId(): String? =
        admission.activeImageGenerationModelId
}

/** R3/C3-S15：图像放大端口具名化（对账 owner 经 S13 注入；createUpscaledAsset 走资产生成端口）。 */
internal class ImageUpscalePortAdapter(
    private val store: UiStateWriter<ImageUpscaleFields>,
    private val application: Application,
    private val coordinator: LocalImageGenerationCoordinator,
    private val observationLock: Any,
    private val assetCreationPort: ImageAssetCreationPort,
    private val assetWriteMutex: Mutex,
    private val startupReconciliation: ImageLibraryStartupReconciliation,
    private val workerClientRef: LocalImageWorkerClient,
    private val chatSessionStoreRef: ChatSessionStore,
    private val upscalerStoreRef: LocalImageUpscalerStore,
    private val assetDirectory: File,
    private val mutationMutex: Mutex,
    private val setDeferGenerationImageGrantRelease: (Boolean) -> Unit
) : ImageUpscalePort {
    override val context: Application
        get() = application

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (ImageUpscaleFields) -> ImageUpscaleFields) {
        store.update(transform)
    }

    override val imageAssetDirectory: File
        get() = assetDirectory

    override val localImageWorkerClient: LocalImageWorkerClient
        get() = workerClientRef

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override val localImageUpscalerStore: LocalImageUpscalerStore
        get() = upscalerStoreRef

    override suspend fun <T> withImageLibraryMutationLock(block: suspend () -> T): T =
        mutationMutex.withLock { block() }

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override suspend fun awaitImageLibraryStartupReconciliation() {
        startupReconciliation.await()
    }

    override suspend fun createUpscaledImageAsset(
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord = assetWriteMutex.withLock {
        createUpscaledAsset(port = assetCreationPort, spec = spec, result = result, requestId = requestId)
    }

    override fun tryAcquireObservedImageGenerationLease(
        requestId: String
    ): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(
            setDeferGenerationImageGrantRelease, coordinator, observationLock, requestId
        )

    override fun releaseObservedImageGenerationLease(
        lease: LocalImageGenerationCoordinator.Lease
    ): Boolean =
        releaseObservedImageGenerationLeaseFor(
            setDeferGenerationImageGrantRelease, coordinator, observationLock, lease
        )
}

/** R3/C3-S16：图片库提交端口具名化（journal 以 provider 传入，保持原 lazy 时机）。 */
internal class ImageLibraryCommitPortAdapter(
    private val store: UiStateWriter<ImageLibraryCommitFields>,
    private val persistenceState: ChatSessionPersistenceState,
    private val chatSessionStoreRef: ChatSessionStore,
    private val journalProvider: () -> ChatImageCommitJournal,
    private val admissionRef: RuntimeAdmissionState,
    private val requestPortRef: ChatImageRequestPort,
    private val imageLibraryMutationMutexRef: Mutex
) : ImageLibraryCommitPort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override fun updateUiState(transform: (ImageLibraryCommitFields) -> ImageLibraryCommitFields) {
        store.update(transform)
    }

    override val imageLibraryMutationMutex: Mutex
        get() = imageLibraryMutationMutexRef

    override val chatSessionPersistenceMutex: Mutex
        get() = persistenceState.persistenceMutex

    override val chatSessionPersistenceSequence: AtomicLong
        get() = persistenceState.persistenceSequence

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override var durableChatSessions: List<ChatSessionRecord>
        get() = persistenceState.durableChatSessions
        set(value) {
            persistenceState.durableChatSessions = value
        }

    override val activeImageGenerationJobId: String?
        get() = admissionRef.activeImageGenerationJobId

    override val chatImageCommitJournal: ChatImageCommitJournal
        get() = journalProvider()

    override fun buildChatImageCommitSnapshot(
        job: ImageGenerationJobRecord,
        images: List<ImageAssetRecord>
    ): ChatImageCommitSnapshot? = chatImageCommitSnapshot(requestPortRef, job, images)

    override fun chatImageCommitJournalJobId(jobId: String, imageId: String): String =
        com.muyuchat.mca.chatImageCommitJournalJobId(jobId, imageId)

    override fun updateImageJob(
        jobs: List<ImageGenerationJobRecord>,
        jobId: String,
        status: ImageGenerationStatusRecord,
        message: String,
        imageAssetId: String?,
        preview: PublishedLocalImagePreview?,
        imageAssetIds: List<String>
    ): List<ImageGenerationJobRecord> = jobs.updateImageJob(
        jobId = jobId,
        status = status,
        message = message,
        imageAssetId = imageAssetId,
        preview = preview,
        imageAssetIds = imageAssetIds
    )
}

/** R3/C3-S16：图片删除端口具名化（放大 owner 与 S13 对账 owner 均真实对象注入）。 */
internal class ImageAssetDeletionPortAdapter(
    private val store: UiStateWriter<ImageAssetDeletionFields>,
    private val scopeRef: CoroutineScope,
    private val admissionRef: RuntimeAdmissionState,
    private val imageUpscaleOwnerRef: ImageUpscaleOwner,
    private val chatSessionStoreRef: ChatSessionStore,
    private val mutationMutex: Mutex,
    private val assetDirectory: File,
    private val startupReconciliation: ImageLibraryStartupReconciliation
) : ImageAssetDeletionPort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override fun updateUiState(transform: (ImageAssetDeletionFields) -> ImageAssetDeletionFields) {
        store.update(transform)
    }

    override val scope: CoroutineScope
        get() = scopeRef

    override val imageLibraryMutationMutex: Mutex
        get() = mutationMutex

    override val imageUpscaleOwner: ImageUpscaleOwner
        get() = imageUpscaleOwnerRef

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override val latestImageFavoriteMutations: MutableMap<String, Pair<Long, Boolean>>
        get() = admissionRef.latestImageFavoriteMutations

    override val imageFavoriteMutationSequence: AtomicLong
        get() = admissionRef.imageFavoriteMutationSequence

    override val imageAssetDirectory: File
        get() = assetDirectory

    override suspend fun awaitImageLibraryStartupReconciliation() {
        startupReconciliation.await()
    }

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }
}
