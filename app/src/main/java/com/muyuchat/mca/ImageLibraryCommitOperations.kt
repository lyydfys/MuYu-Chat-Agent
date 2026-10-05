package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The named owner of the image-library commit transaction (P5). Moved verbatim from
 * MainViewModel.enqueueImageGeneration; the serialization invariant (persistence sequence bump
 * before the persistence mutex), the atomic chat commit with its durable alignment, the commit
 * journal handshake and the cancelled-publish rollback are unchanged. The only rewrites are
 * _uiState->uiState and port-qualified collaborator calls.
 *
 * P5 step 6 added [upsertImportedImageUnderMutationLock], the attach/import counterpart: the row
 * upsert and the caller's composer publish run inside one image-library mutation mutex hold; the
 * caller-side rollback (local-copy delete / CancellationException / fail) stays in attachFile.
 */
internal interface ImageLibraryCommitPort {
    // R3/S25: 契约收敛——端口只暴露只读 StateFlow 与具名写入方法。
    val uiState: StateFlow<MainUiState>
    fun updateUiState(transform: (MainUiState) -> MainUiState)
    val imageLibraryMutationMutex: Mutex
    val chatSessionPersistenceMutex: Mutex
    val chatSessionPersistenceSequence: AtomicLong
    val chatSessionStore: ChatSessionStore
    var durableChatSessions: List<ChatSessionRecord>
    val activeImageGenerationJobId: String?
    val chatImageCommitJournal: ChatImageCommitJournal

    fun buildChatImageCommitSnapshot(job: ImageGenerationJobRecord, images: List<ImageAssetRecord>): ChatImageCommitSnapshot?

    fun chatImageCommitJournalJobId(jobId: String, imageId: String): String

    fun updateImageJob(
        jobs: List<ImageGenerationJobRecord>,
        jobId: String,
        status: ImageGenerationStatusRecord,
        message: String,
        imageAssetId: String? = null,
        preview: PublishedLocalImagePreview? = null,
        imageAssetIds: List<String> = emptyList()
    ): List<ImageGenerationJobRecord>
}

internal suspend fun commitImageLibraryTransaction(
    port: ImageLibraryCommitPort,
    jobId: String,
    generatedImages: List<ImageAssetRecord>
): Throwable? {
    val uiState = port.uiState
    val imageLibraryMutationMutex = port.imageLibraryMutationMutex
    val chatSessionPersistenceMutex = port.chatSessionPersistenceMutex
    val chatSessionPersistenceSequence = port.chatSessionPersistenceSequence
    val chatSessionStore = port.chatSessionStore
    val activeImageGenerationJobId = port.activeImageGenerationJobId
    val chatImageCommitJournal = port.chatImageCommitJournal
    return runCatching {

        imageLibraryMutationMutex.withLock {
            currentCoroutineContext().ensureActive()
            // Serialize this multi-table commit with every full chat snapshot write.
            // A stale full snapshot must never land after the image/request transaction.
            chatSessionPersistenceSequence.incrementAndGet()
            chatSessionPersistenceMutex.withLock {
                try {
                    val admittedJob = uiState.value.imageJobs.firstOrNull { it.id == jobId }
                    if (activeImageGenerationJobId != jobId ||
                        admittedJob?.status != ImageGenerationStatusRecord.GENERATING
                    ) {
                        throw CancellationException(
                            "Image generation lost ownership before library commit."
                        )
                    }
                    val chatSnapshot = port.buildChatImageCommitSnapshot(admittedJob, generatedImages)
                    val journalRecords = if (chatSnapshot != null) {
                        val chatSessionId = requireNotNull(admittedJob.spec?.chatSessionId)
                        val requestId = requireNotNull(admittedJob.spec?.chatMessageId)
                        generatedImages.map { image ->
                            val journalJobId = port.chatImageCommitJournalJobId(jobId, image.id)
                            chatImageCommitJournal.stage(
                                ChatImageCommitJournal.Record(
                                    jobId = journalJobId,
                                    chatSessionId = chatSessionId,
                                    requestId = requestId,
                                    imageAssetMetadataJson = ChatImageCommitJournal
                                        .serializeImageAssetRecord(image)
                                )
                            )
                        }
                    } else emptyList()
                    val atomicallySaved = chatSnapshot?.let { snapshot ->
                        chatSessionStore.saveGeneratedImageResult(snapshot.sessions, generatedImages)
                    } == true
                    if (atomicallySaved) {
                        port.durableChatSessions = requireNotNull(chatSnapshot).sessions
                    }
                    if (!atomicallySaved) {
                        // Keep non-chat image jobs and deleted-chat results in the gallery.
                        chatSessionStore.upsertImages(generatedImages)
                    }
                    journalRecords.forEach { record ->
                        runCatching {
                            chatImageCommitJournal.removeAfterDurableCommit(
                                record.jobId,
                                record.requestId,
                                durableCommitConfirmed = true
                            )
                        }
                    }
                    port.updateUiState { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        if (activeImageGenerationJobId != jobId ||
                            job?.status != ImageGenerationStatusRecord.GENERATING
                        ) {
                            state
                        } else {
                            val committedImages = (
                                generatedImages + state.images.filterNot { existing ->
                                    generatedImages.any { generated ->
                                        generated.id == existing.id
                                    }
                                }
                            ).sortedImagesForLibrary()
                            state.copy(
                                images = committedImages,
                                chatSessions = if (atomicallySaved) {
                                    requireNotNull(chatSnapshot).sessions
                                } else {
                                    state.chatSessions
                                },
                                messages = if (atomicallySaved &&
                                    state.activeChatSessionId == admittedJob.spec?.chatSessionId
                                ) {
                                    requireNotNull(chatSnapshot).activeMessages ?: state.messages
                                } else {
                                    state.messages
                                },
                                imageJobs = port.updateImageJob(state.imageJobs, 
                                    jobId = jobId,
                                    status = ImageGenerationStatusRecord.DONE,
                                    message = if (generatedImages.size == 1) {
                                        "已保存到图片库"
                                    } else {
                                        "已保存 ${generatedImages.size} 张图片"
                                    },
                                    imageAssetId = generatedImages.first().id,
                                    imageAssetIds = generatedImages.map(ImageAssetRecord::id)
                                ),
                                statusMessage = if (generatedImages.size == 1) {
                                    "已生成图片并保存到图片库：${generatedImages.first().name}"
                                } else {
                                    "已生成 ${generatedImages.size} 张图片并保存到图片库"
                                }
                            )
                        }
                    }
                    val published = uiState.value.imageJobs.firstOrNull {
                        it.id == jobId
                    }?.let { job ->
                        val generatedIds = generatedImages.map(ImageAssetRecord::id).toSet()
                        job.status == ImageGenerationStatusRecord.DONE &&
                            job.imageAssetId == generatedImages.first().id &&
                            job.imageAssetIds.toSet() == generatedIds
                    } == true
                    if (!published) {
                        val generatedIds = generatedImages.map(ImageAssetRecord::id)
                        val rolledBack = runCatching {
                            chatSessionStore.deleteImages(generatedIds)
                        }.isSuccess
                        if (rolledBack) {
                            throw CancellationException(
                                "Image generation was cancelled during library commit."
                            )
                        }
                        // If rollback fails, Room owns these files. Publish that durable state
                        // so memory and the database cannot silently diverge.
                        port.updateUiState { state ->
                            val job = state.imageJobs.firstOrNull { it.id == jobId }
                            if (job == null) {
                                state
                            } else {
                                state.copy(
                                    images = (
                                        generatedImages + state.images.filterNot { existing ->
                                            generatedImages.any { generated ->
                                                generated.id == existing.id
                                            }
                                        }
                                    ).sortedImagesForLibrary(),
                                    imageJobs = port.updateImageJob(state.imageJobs, 
                                        jobId = jobId,
                                        status = ImageGenerationStatusRecord.DONE,
                                        message = if (generatedImages.size == 1) {
                                            "已保存到图片库"
                                        } else {
                                            "已保存 ${generatedImages.size} 张图片"
                                        },
                                        imageAssetId = generatedImages.first().id,
                                        imageAssetIds = generatedImages.map(ImageAssetRecord::id)
                                    ),
                                    statusMessage = if (generatedImages.size == 1) {
                                        "已生成图片并保存到图片库：${generatedImages.first().name}"
                                    } else {
                                        "已生成 ${generatedImages.size} 张图片并保存到图片库"
                                    }
                                )
                            }
                        }
                    }
                } finally {
                    // Invalidate snapshots captured before the in-memory state below
                    // became the committed Room state. Later snapshots see the result.
                    chatSessionPersistenceSequence.incrementAndGet()
                }
            }
        }
    }.exceptionOrNull()
}

/**
 * The imported-attachment commit (P5 step 6): the single chatSessionStore image write of the
 * attach/import flow. The upsert and the caller's publish run inside one image-library mutation
 * mutex hold so no concurrent mutation can interleave between the row write and the composer
 * publish. The rollback (local-copy delete / CancellationException rethrow / fail) stays with
 * the caller, unchanged.
 */
internal suspend fun upsertImportedImageUnderMutationLock(
    port: ImageLibraryCommitPort,
    image: ImageAssetRecord,
    publish: () -> List<FileAssetRecord>?
): List<FileAssetRecord>? =
    port.imageLibraryMutationMutex.withLock {
        currentCoroutineContext().ensureActive()
        port.chatSessionStore.upsertImages(listOf(image))
        publish()
    }
