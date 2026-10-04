package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The image-asset deletion owner (P5). deleteImageAssets moved here verbatim; the deletion-lease
 * helpers became top-level internals shared with the still-in-ViewModel clearImageLibrary, taking
 * the asset directory explicitly. The lease staging / rollback / commit ordering and the upsale
 * lifecycle-lock protocol are unchanged.
 */
internal interface ImageAssetDeletionPort {
    val uiState: MutableStateFlow<MainUiState>
    val scope: CoroutineScope
    val imageLibraryMutationMutex: Mutex
    val imageUpscaleOwner: ImageUpscaleOwner
    val chatSessionStore: ChatSessionStore
    val latestImageFavoriteMutations: MutableMap<String, Pair<Long, Boolean>>
    val imageAssetDirectory: File

    suspend fun awaitImageLibraryStartupReconciliation()

    fun showStatus(message: String?)
}

internal data class ImageAssetDeletionStaging(
    val leases: List<ImageAssetDeletionLease>?,
    val rollbackFailures: Int = 0
)

internal fun stageImageAssetDeletionLeases(
images: List<ImageAssetRecord>,
retainedImages: List<ImageAssetRecord>,
imageAssetDirectory: File
): ImageAssetDeletionStaging {
    val retainedPaths = retainedImages.mapNotNullTo(mutableSetOf()) { image ->
        image.ownedLocalImageFileOrNull(imageAssetDirectory)?.path
    }
    val leases = ArrayList<ImageAssetDeletionLease>(images.size)
    for (image in images) {
        val ownedPath = image.ownedLocalImageFileOrNull(imageAssetDirectory)?.path
        val lease = if (ownedPath != null && ownedPath in retainedPaths) {
            ImageAssetDeletionLease(original = null, staged = null)
        } else {
            image.stageLocalCopyDeletion(imageAssetDirectory)
        }
        if (lease == null) {
            val rollbackFailures = leases.asReversed().count { staged -> !staged.rollback() }
            return ImageAssetDeletionStaging(leases = null, rollbackFailures = rollbackFailures)
        }
        leases += lease
    }
    return ImageAssetDeletionStaging(leases = leases)
}

internal fun rollbackImageAssetDeletionLeases(
    leases: List<ImageAssetDeletionLease>
): Int = leases.asReversed().count { lease -> !lease.rollback() }

internal fun commitImageAssetDeletionLeases(
    leases: List<ImageAssetDeletionLease>
): Int = leases.count { lease -> !lease.commit() }

internal fun deleteImageAssetsOperation(
    port: ImageAssetDeletionPort,
    imageIds: List<String>
) {
    val uiState = port.uiState
    val imageLibraryMutationMutex = port.imageLibraryMutationMutex
    val imageUpscaleOwner = port.imageUpscaleOwner
    val chatSessionStore = port.chatSessionStore
    val latestImageFavoriteMutations = port.latestImageFavoriteMutations
    val imageAssetDirectory = port.imageAssetDirectory
    val requestedIds = imageIds.asSequence()
        .map { it.trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .toSet()
    if (requestedIds.isEmpty()) return
    port.scope.launch(Dispatchers.IO) {
        port.awaitImageLibraryStartupReconciliation()
        val (removed, cleanupFailures) = imageLibraryMutationMutex.withLock {
            imageUpscaleOwner.withUpscaleLifecycleLock { _ ->
                val activeSourceId = imageUpscaleOwner.activeSourceImageId()
                if (activeSourceId != null && activeSourceId in requestedIds) {
                    uiState.update {
                        it.copy(statusMessage = "源图片正被放大任务使用，任务结束后才能删除。")
                    }
                    return@withUpscaleLifecycleLock emptyList<ImageAssetRecord>() to 0
                }
                val candidates = uiState.value.images.filter { it.id in requestedIds }
                if (candidates.isEmpty()) {
                    port.showStatus("未找到要删除的图片")
                    return@withUpscaleLifecycleLock emptyList<ImageAssetRecord>() to 0
                }
                val staging = stageImageAssetDeletionLeases(
                    images = candidates,
                    retainedImages = uiState.value.images.filterNot { image ->
                        image.id in requestedIds
                    },
                    imageAssetDirectory = imageAssetDirectory
                )
                val leases = staging.leases
                if (leases == null) {
                    uiState.update {
                        it.copy(
                            statusMessage = buildString {
                                append("图片删除失败：无法安全暂存本地文件")
                                if (staging.rollbackFailures > 0) {
                                    append("；")
                                    append(staging.rollbackFailures)
                                    append(" 个文件等待启动恢复")
                                }
                            }
                        )
                    }
                    return@withUpscaleLifecycleLock emptyList<ImageAssetRecord>() to 0
                }
                val removedIds = candidates.map(ImageAssetRecord::id)
                val error = runCatching { chatSessionStore.deleteImages(removedIds) }.exceptionOrNull()
                if (error != null) {
                    val rollbackFailures = rollbackImageAssetDeletionLeases(leases)
                    uiState.update {
                        it.copy(
                            statusMessage = buildString {
                                append("图片删除失败：")
                                append(error.message ?: "数据库提交失败")
                                if (rollbackFailures > 0) {
                                    append("；")
                                    append(rollbackFailures)
                                    append(" 个文件等待启动恢复")
                                }
                            }
                        )
                    }
                    return@withUpscaleLifecycleLock emptyList<ImageAssetRecord>() to 0
                }
                removedIds.forEach(latestImageFavoriteMutations::remove)
                uiState.update { state ->
                    state.copy(images = state.images.filterNot { it.id in removedIds })
                }
                candidates to commitImageAssetDeletionLeases(leases)
            }
        }
        if (removed.isEmpty()) return@launch
        uiState.update { state ->
            val successMessage = if (removed.size == 1) {
                "已从图片库移除：${removed.single().name}"
            } else {
                "已从图片库移除 ${removed.size} 张图片"
            }
            state.copy(
                statusMessage = if (cleanupFailures == 0) {
                    successMessage
                } else {
                    "$successMessage；$cleanupFailures 个本地文件未能清理"
                }
            )
        }
    }
}
