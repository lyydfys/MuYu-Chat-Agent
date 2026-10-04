package com.muyuchat.mca

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The image-library mutation owner (P5). deleteImageAssets moved here verbatim in P5 step 4 with
 * the deletion-lease helpers (top-level internals, asset directory explicit). P5 step 5 moved
 * setImageAssetFavorite and clearImageLibrary here as well, so every chatSessionStore image write
 * in this family lives in one file; the port gained imageFavoriteMutationSequence. Lease staging /
 * rollback / commit ordering, the favourite latest-wins sequence guard and the upscale
 * lifecycle-lock protocol are unchanged.
 */
internal interface ImageAssetDeletionPort {
    val uiState: MutableStateFlow<MainUiState>
    val scope: CoroutineScope
    val imageLibraryMutationMutex: Mutex
    val imageUpscaleOwner: ImageUpscaleOwner
    val chatSessionStore: ChatSessionStore
    val latestImageFavoriteMutations: MutableMap<String, Pair<Long, Boolean>>
    val imageFavoriteMutationSequence: AtomicLong
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

internal fun setImageAssetFavoriteOperation(
    port: ImageAssetDeletionPort,
    imageId: String,
    favorite: Boolean
) {
    val uiState = port.uiState
    val imageLibraryMutationMutex = port.imageLibraryMutationMutex
    val latestImageFavoriteMutations = port.latestImageFavoriteMutations
    val imageFavoriteMutationSequence = port.imageFavoriteMutationSequence
    val chatSessionStore = port.chatSessionStore
    val image = uiState.value.images.firstOrNull { it.id == imageId }
    if (image == null) {
        port.showStatus("未找到要收藏的图片")
        return
    }
    val mutation = imageFavoriteMutationSequence.incrementAndGet() to favorite
    latestImageFavoriteMutations[imageId] = mutation
    port.scope.launch(Dispatchers.IO) {
        imageLibraryMutationMutex.withLock {
            if (latestImageFavoriteMutations[imageId] != mutation) return@withLock
            val updateResult = runCatching {
                chatSessionStore.setImageFavorite(imageId, favorite)
            }
            if (latestImageFavoriteMutations[imageId] != mutation) return@withLock
            updateResult
                .onSuccess { updateCount ->
                    if (updateCount == 1) {
                        uiState.update { state ->
                            state.copy(
                                images = state.images.map { current ->
                                    if (current.id == imageId) current.copy(favorite = favorite) else current
                                },
                                statusMessage = if (favorite) {
                                    "已收藏：${image.name}"
                                } else {
                                    "已取消收藏：${image.name}"
                                }
                            )
                        }
                    } else {
                        uiState.update {
                            it.copy(statusMessage = "收藏状态更新失败：图片记录已不存在")
                        }
                    }
                }
                .onFailure { error ->
                    uiState.update {
                        it.copy(statusMessage = "收藏状态更新失败：${error.message ?: "数据库提交失败"}")
                    }
                }
            latestImageFavoriteMutations.remove(imageId, mutation)
        }
    }
}

internal fun clearImageLibraryOperation(
    port: ImageAssetDeletionPort
) {
    val uiState = port.uiState
    val imageLibraryMutationMutex = port.imageLibraryMutationMutex
    val imageUpscaleOwner = port.imageUpscaleOwner
    val chatSessionStore = port.chatSessionStore
    val latestImageFavoriteMutations = port.latestImageFavoriteMutations
    val imageAssetDirectory = port.imageAssetDirectory
    if (uiState.value.images.isEmpty()) {
        port.showStatus("图片库已为空")
        return
    }
    port.scope.launch(Dispatchers.IO) {
        port.awaitImageLibraryStartupReconciliation()
        val (removed, cleanupFailures) = imageLibraryMutationMutex.withLock {
            imageUpscaleOwner.withUpscaleLifecycleLock { _ ->
                if (imageUpscaleOwner.activeSourceImageId() != null) {
                    uiState.update {
                        it.copy(statusMessage = "源图片正被放大任务使用，任务结束后才能清空图片库。")
                    }
                    return@withUpscaleLifecycleLock emptyList<ImageAssetRecord>() to 0
                }
                val currentImages = uiState.value.images
                if (currentImages.isEmpty()) {
                    port.showStatus("图片库已为空")
                    return@withUpscaleLifecycleLock emptyList<ImageAssetRecord>() to 0
                }
                val staging = stageImageAssetDeletionLeases(
                    images = currentImages,
                    retainedImages = emptyList(),
                    imageAssetDirectory = imageAssetDirectory
                )
                val leases = staging.leases
                if (leases == null) {
                    uiState.update {
                        it.copy(
                            statusMessage = buildString {
                                append("图片库清空失败：无法安全暂存本地文件")
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
                val error = runCatching { chatSessionStore.clearImages() }.exceptionOrNull()
                if (error != null) {
                    val rollbackFailures = rollbackImageAssetDeletionLeases(leases)
                    uiState.update {
                        it.copy(
                            statusMessage = buildString {
                                append("图片库清空失败：")
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
                latestImageFavoriteMutations.clear()
                uiState.update {
                    it.copy(
                        images = emptyList(),
                        imageJobs = it.imageJobs.filter { job -> !job.status.terminal }
                    )
                }
                currentImages to commitImageAssetDeletionLeases(leases)
            }
        }
        if (removed.isEmpty()) return@launch
        uiState.update {
            it.copy(
                statusMessage = buildString {
                    append("已清空图片库：")
                    append(removed.size)
                    append(" 张图片")
                    if (cleanupFailures > 0) {
                        append("；")
                        append(cleanupFailures)
                        append(" 个本地文件未能清理")
                    }
                }
            )
        }
    }
}
