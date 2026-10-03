package com.muyuchat.mca

import android.app.Application
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive


/**
 * The owner seam for the image-upscale execution.
 *
 * The four pieces of ownership -- the job, the lifecycle lock and the two active-identity fields --
 * live in [ImageUpscaleOwner], **not** here: this interface is what the owner needs from the app, and
 * it deliberately contains no lock, no job and no scope. The coroutine scope is a parameter on the
 * owner's entry points because a scope belongs to the caller.
 */
internal interface ImageUpscalePort {

    val context: Application

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val imageAssetDirectory: File

    val localImageWorkerClient: LocalImageWorkerClient

    val chatSessionStore: ChatSessionStore

    val localImageUpscalerStore: LocalImageUpscalerStore

    suspend fun <T> withImageLibraryMutationLock(block: suspend () -> T): T

    fun showStatus(message: String?)

    suspend fun awaitImageLibraryStartupReconciliation()

    suspend fun createUpscaledImageAsset(
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord

    fun tryAcquireObservedImageGenerationLease(
        requestId: String
    ): LocalImageGenerationCoordinator.Lease?

    fun releaseObservedImageGenerationLease(
        lease: LocalImageGenerationCoordinator.Lease
    ): Boolean
}

/**
 * Owns the single image-upscale execution: its job, its lifecycle lock and the identity of the
 * upscale that is currently running.
 *
 * **This is the point of the class.** The ViewModel used to hold the lock and the job directly, so any
 * code with a `MainViewModel` reference could read or nest them. Here they are private, and the only
 * ways in are:
 *
 *  * [upscaleImageAsset] / [cancelImageUpscale] -- the two user-visible actions;
 *  * [withUpscaleLifecycleLock] -- runs a block while holding the lock and reports whether a job is
 *    running, which is what the "is this upscaler in use?" check needs;
 *  * [activeSourceImageId] / [hasActiveUpscale] -- plain reads of the `@Volatile` fields, matching
 *    exactly what the previous code did (the readiness refresh never took the lock to ask whether an
 *    upscale was active, and it still does not).
 */
/** Tile edge for the ESRGAN worker; moved here with its only consumer. */
internal const val IMAGE_UPSCALE_TILE_SIZE = 128

internal class ImageUpscaleOwner(private val port: ImageUpscalePort) {

    private val localImageUpscaleLifecycleLock = Any()

    private var imageUpscaleJob: Job? = null

    @Volatile
    private var activeImageUpscaleJobId: String? = null

    @Volatile
    private var activeImageUpscaleSourceImageId: String? = null

    /** Runs [block] under the lifecycle lock, reporting whether an upscale job is running. */
    fun <T> withUpscaleLifecycleLock(block: (upscaleRunning: Boolean) -> T): T =
        synchronized(localImageUpscaleLifecycleLock) { block(activeImageUpscaleJobId != null) }

    /** The source image of the running upscale. Read this while holding [withUpscaleLifecycleLock]. */
    fun activeSourceImageId(): String? = activeImageUpscaleSourceImageId

    /** Whether an upscale is running. A plain volatile read, exactly as before. */
    fun hasActiveUpscale(): Boolean = activeImageUpscaleJobId != null


private fun LocalImageProgress.toImageUpscaleMessage(): String {
        val elapsedSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        val size = if (width > 0 && height > 0) " · ${width}x$height" else ""
        return when {
            cancelRequested || phase == "cancelling" -> "正在停止图片放大 · ${elapsedSeconds}s"
            phase == "loading" || phase == "loading_upscaler" ->
                "正在加载 ESRGAN 放大模型 · ${elapsedSeconds}s"
            phase == "upscaling" || phase == "sampling" ->
                "正在分块放大图片 · ${elapsedSeconds}s$size"
            phase == "writing" -> "正在写入放大结果 · ${elapsedSeconds}s$size"
            phase == "completed" -> "图片放大完成 · ${elapsedSeconds}s$size"
            phase == "failed" -> message.ifBlank { "图片放大失败" }
            else -> "正在准备图片放大 · ${elapsedSeconds}s$size"
        }
    }

    fun upscaleImageAsset(imageId: String, targetScale: Int, scope: CoroutineScope) {        if (targetScale !in setOf(2, 3, 4)) {
            port.showStatus("图片放大仅支持 2x、3x 或 4x。")
            return
        }
        val requestId = "ui-upscale-${UUID.randomUUID()}"
        val lease = port.tryAcquireObservedImageGenerationLease(requestId)
        if (lease == null) {
            port.updateUiState {
                it.copy(statusMessage = "已有 UI 或 Local API 图片任务正在运行，请等待完成或先停止当前任务")
            }
            return
        }
        val spec = synchronized(localImageUpscaleLifecycleLock) {
            val state = port.uiState()
            val source = state.images.firstOrNull { it.id == imageId }
            val selectedUpscalerId = state.selectedLocalImageUpscalerId
            val upscaler = port.localImageUpscalerStore.load()
                .firstOrNull { it.id == selectedUpscalerId }
            if (source == null || upscaler == null) {
                check(port.releaseObservedImageGenerationLease(lease)) {
                    "Upscale lease was replaced during request admission."
                }
                port.updateUiState {
                    it.copy(
                        localImageUpscalers = port.localImageUpscalerStore.load(),
                        selectedLocalImageUpscalerId = upscaler?.id,
                        localImageUpscalerMessage = if (source == null) {
                            "源图片已不存在。"
                        } else {
                            "请先导入并选择一个 ESRGAN 放大模型。"
                        }
                    )
                }
                return
            }
            ImageUpscaleJobSpec(
                sourceImageSnapshot = source,
                upscalerSnapshot = upscaler.toPrepared(),
                targetScale = targetScale,
                tileSize = IMAGE_UPSCALE_TILE_SIZE,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 5)
            ).also { snapshot ->
                activeImageUpscaleJobId = requestId
                activeImageUpscaleSourceImageId = source.id
                port.updateUiState {
                    it.copy(
                        localImageUpscalers = port.localImageUpscalerStore.load(),
                        activeLocalImageUpscalerId = upscaler.id,
                        imageUpscaleJob = ImageUpscaleJobRecord(
                            id = requestId,
                            spec = snapshot,
                            status = ImageUpscaleStatusRecord.QUEUED,
                            message = "图片放大任务已排队"
                        ),
                        localImageUpscalerMessage = "图片放大任务已排队"
                    )
                }
            }
        }
        val foregroundLease = McaGenerationForegroundService.acquire(
            port.context,
            McaGenerationForegroundService.KIND_IMAGE
        )
        val executionJob = scope.launch(Dispatchers.IO) {
            var unpublishedImage: ImageAssetRecord? = null
            try {
                port.awaitImageLibraryStartupReconciliation()
                port.updateUiState { state ->
                    val job = state.imageUpscaleJob
                    if (job?.id == requestId) {
                        state.copy(
                            imageUpscaleJob = job.copy(
                                status = ImageUpscaleStatusRecord.RUNNING,
                                message = "正在运行本地 ESRGAN 放大"
                            )
                        )
                    } else {
                        state
                    }
                }
                val result = port.localImageWorkerClient.upscale(
                    inputImageReference = spec.sourceImageSnapshot.uriString,
                    upscaler = spec.upscalerSnapshot,
                    targetScale = spec.targetScale,
                    tileSize = spec.tileSize,
                    threads = spec.threads,
                    requestId = requestId,
                    onProgress = { progress ->
                        port.updateUiState { state ->
                            val job = state.imageUpscaleJob
                            if (job?.id == requestId && !job.status.terminal) {
                                state.copy(
                                    imageUpscaleJob = job.copy(
                                        message = progress.toImageUpscaleMessage()
                                    )
                                )
                            } else {
                                state
                            }
                        }
                    }
                )
                currentCoroutineContext().ensureActive()
                if (port.uiState().imageUpscaleJob?.status == ImageUpscaleStatusRecord.CANCEL_REQUESTED) {
                    throw CancellationException("Image upscale was cancelled before publication.")
                }
                val generated = port.createUpscaledImageAsset(spec, result, requestId)
                unpublishedImage = generated
                val commitError = runCatching {
                    port.withImageLibraryMutationLock {
                        currentCoroutineContext().ensureActive()
                        val admittedJob = port.uiState().imageUpscaleJob
                        if (activeImageUpscaleJobId != requestId ||
                            admittedJob == null ||
                            admittedJob.id != requestId ||
                            admittedJob.status == ImageUpscaleStatusRecord.CANCEL_REQUESTED ||
                            admittedJob.status.terminal
                        ) {
                            throw CancellationException(
                                "Image upscale lost ownership before library commit."
                            )
                        }
                        port.chatSessionStore.upsertImages(listOf(generated))
                        port.updateUiState { state ->
                            val job = state.imageUpscaleJob
                            if (activeImageUpscaleJobId != requestId ||
                                job == null ||
                                job.id != requestId ||
                                job.status == ImageUpscaleStatusRecord.CANCEL_REQUESTED ||
                                job.status.terminal
                            ) {
                                state
                            } else {
                                state.copy(
                                    images = (
                                        listOf(generated) + state.images.filterNot {
                                            it.id == generated.id
                                        }
                                    ).sortedImagesForLibrary(),
                                    imageUpscaleJob = job.copy(
                                        status = ImageUpscaleStatusRecord.DONE,
                                        resultImageAssetId = generated.id,
                                        message = "放大结果已保存到图片库"
                                    ),
                                    localImageUpscalerMessage = "放大结果已保存：${generated.name}",
                                    statusMessage = "图片放大完成并已保存到图片库"
                                )
                            }
                        }
                        val published = port.uiState().imageUpscaleJob?.let { job ->
                            job.id == requestId &&
                                job.status == ImageUpscaleStatusRecord.DONE &&
                                job.resultImageAssetId == generated.id
                        } == true
                        if (!published) {
                            val rolledBack = runCatching {
                                port.chatSessionStore.deleteImages(listOf(generated.id))
                            }.isSuccess
                            if (rolledBack) {
                                throw CancellationException(
                                    "Image upscale was cancelled during library commit."
                                )
                            }
                            // The database owns the file now. Reflect that durable state instead of
                            // deleting the bytes and leaving a hidden row with a broken URI.
                            port.updateUiState { state ->
                                val job = state.imageUpscaleJob
                                if (job?.id != requestId) {
                                    state
                                } else {
                                    state.copy(
                                        images = (
                                            listOf(generated) + state.images.filterNot {
                                                it.id == generated.id
                                            }
                                        ).sortedImagesForLibrary(),
                                        imageUpscaleJob = job.copy(
                                            status = ImageUpscaleStatusRecord.DONE,
                                            resultImageAssetId = generated.id,
                                            message = "放大结果已保存到图片库"
                                        ),
                                        localImageUpscalerMessage =
                                            "放大结果已保存：${generated.name}",
                                        statusMessage = "图片放大完成并已保存到图片库"
                                    )
                                }
                            }
                        }
                    }
                }.exceptionOrNull()
                if (commitError != null) {
                    generated.deleteLocalCopy(port.imageAssetDirectory)
                    unpublishedImage = null
                    throw commitError
                }
                unpublishedImage = null
            } catch (error: Throwable) {
                unpublishedImage?.deleteLocalCopy(port.imageAssetDirectory)
                unpublishedImage = null
                val cancelled = error is CancellationException ||
                    error is LocalImageWorkerCancelledException
                val message = if (cancelled) {
                    "已取消图片放大"
                } else {
                    error.message ?: "图片放大失败"
                }
                port.updateUiState { state ->
                    val job = state.imageUpscaleJob
                    if (job?.id != requestId) return@updateUiState state
                    state.copy(
                        imageUpscaleJob = job.copy(
                            status = if (cancelled) {
                                ImageUpscaleStatusRecord.CANCELLED
                            } else {
                                ImageUpscaleStatusRecord.FAILED
                            },
                            message = message
                        ),
                        localImageUpscalerMessage = message,
                        statusMessage = if (cancelled) message else "图片放大失败：$message"
                    )
                }
            }
        }
        imageUpscaleJob = executionJob
        executionJob.invokeOnCompletion { completion ->
            try {
                synchronized(localImageUpscaleLifecycleLock) {
                    val ownsActiveUpscale = activeImageUpscaleJobId == requestId
                    if (ownsActiveUpscale) {
                        activeImageUpscaleJobId = null
                        activeImageUpscaleSourceImageId = null
                    }
                    val released = port.releaseObservedImageGenerationLease(lease)
                    port.updateUiState { state ->
                        val job = state.imageUpscaleJob
                        val terminalJob = if (completion is CancellationException &&
                            job?.id == requestId && !job.status.terminal
                        ) {
                            job.copy(
                                status = ImageUpscaleStatusRecord.CANCELLED,
                                message = "已取消图片放大"
                            )
                        } else {
                            job
                        }
                        state.copy(
                            activeLocalImageUpscalerId = if (ownsActiveUpscale) {
                                null
                            } else {
                                state.activeLocalImageUpscalerId
                            },
                            imageUpscaleJob = terminalJob,
                            localImageUpscalerMessage = if (terminalJob?.status ==
                                ImageUpscaleStatusRecord.CANCELLED
                            ) {
                                "已取消图片放大"
                            } else {
                                state.localImageUpscalerMessage
                            },
                            statusMessage = if (!released) {
                                "图片放大任务已结束，但 coordinator lease 状态不一致。"
                            } else {
                                state.statusMessage
                            }
                        )
                    }
                }
                if (imageUpscaleJob === executionJob) imageUpscaleJob = null
            } finally {
                McaGenerationForegroundService.release(port.context, foregroundLease)
            }
        }
    }

    fun cancelImageUpscale(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val requestId = activeImageUpscaleJobId
            if (requestId == null) {
                port.updateUiState { it.copy(localImageUpscalerMessage = "当前没有正在运行的图片放大任务。") }
                return@launch
            }
            port.updateUiState { state ->
                val job = state.imageUpscaleJob
                if (job?.id == requestId && !job.status.terminal) {
                    state.copy(
                        imageUpscaleJob = job.copy(
                            status = ImageUpscaleStatusRecord.CANCEL_REQUESTED,
                            message = "正在停止图片放大，等待 worker 释放本次执行"
                        ),
                        localImageUpscalerMessage = "正在停止图片放大…"
                    )
                } else {
                    state
                }
            }
            val nativeCancelRequested = port.localImageWorkerClient.cancel(requestId)
            if (!nativeCancelRequested) {
                imageUpscaleJob
                    ?.takeIf { activeImageUpscaleJobId == requestId }
                    ?.cancel(CancellationException("Image upscale cancelled by user."))
            }
        }
    }
}
