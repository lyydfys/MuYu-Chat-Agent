package com.muyuchat.mca

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * R3/C3-S17：本地生图 watchdog 与预览三方法自 MainViewModel 迁出（体逐字；仅依赖参数化）。
 *
 * 三个常量随迁顶层化（原 `MainViewModel` companion 私有常量，只被本文件与 `startAfterConstruction`
 * 的预览目录清理引用；后者经同包顶层解析，零改动）。
 */
internal const val LOCAL_IMAGE_GENERATION_WATCHDOG_MS = 8 * 60 * 1000L

internal const val LOCAL_IMAGE_UI_PREVIEW_DIRECTORY = "local_image_ui_previews"

internal const val MAX_LOCAL_IMAGE_PREVIEW_BYTES = 16L * 1024L * 1024L

internal fun startLocalImageGenerationWatchdogFor(store: UiStateStore, scope: CoroutineScope, jobId: String) {
    scope.launch(Dispatchers.IO) {
        delay(LOCAL_IMAGE_GENERATION_WATCHDOG_MS)
        store.update { state ->
            val job = state.imageJobs.firstOrNull { it.id == jobId }
            if (job?.status == ImageGenerationStatusRecord.QUEUED || job?.status == ImageGenerationStatusRecord.GENERATING) {
                val message = "本地生图耗时超过 8 分钟，native 后端仍在运行。可点击停止，当前图执行结束后会退出。"
                state.copy(
                    imageJobs = state.imageJobs.updateImageJob(jobId, ImageGenerationStatusRecord.GENERATING, message),
                    statusMessage = message
                )
            } else {
                state
            }
        }
    }
}

internal fun canPublishLocalImagePreviewFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    jobId: String
): Boolean =
    admission.activeImageGenerationJobId == jobId &&
        store.current.imageJobs.firstOrNull { job -> job.id == jobId }?.status ==
        ImageGenerationStatusRecord.GENERATING

internal fun publishLocalImagePreviewTo(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    application: Application,
    imageAssetWriteMutex: Mutex,
    jobId: String,
    progress: LocalImageProgress
): PublishedLocalImagePreview? = runCatching {
    if (!canPublishLocalImagePreviewFor(store, admission, jobId)) return@runCatching null
    if (progress.previewRevision <= 0L || progress.previewPath.isBlank()) return@runCatching null
    require(progress.previewMimeType == "image/png") {
        "Native image preview must be a PNG."
    }
    require(progress.previewMode in setOf("projection", "tae", "vae")) {
        "Native image preview reported an unknown mode."
    }
    require(
        progress.previewStep > 0 && progress.previewWidth > 0 &&
            progress.previewHeight > 0 && progress.previewFrameCount > 0 &&
            !progress.previewNoisy
    ) { "Native image preview metadata is incomplete." }

    val app = application
    val cacheRoot = app.cacheDir.canonicalFile
    val source = validatedLocalImagePreviewSource(
        cacheRoot = cacheRoot,
        previewPath = progress.previewPath,
        coordinatedRevision = progress.previewRevision
    )
    require(
        source.isFile && source.length() in 1..MAX_LOCAL_IMAGE_PREVIEW_BYTES
    ) { "Native image preview path is not a bounded app-cache file." }
    val bytes = source.readBytes()
    require(bytes.size.toLong() == source.length()) {
        "Native image preview changed while it was being copied."
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(
        bounds.outWidth == progress.previewWidth && bounds.outHeight == progress.previewHeight
    ) { "Native image preview dimensions do not match the decoded PNG." }

    // Copying and decoding can take long enough for a cancellation to win. Do not create a
    // new UI-cache artifact once the job has left the only state that may show previews.
    if (!canPublishLocalImagePreviewFor(store, admission, jobId)) return@runCatching null

    val root = File(cacheRoot, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY).canonicalFile
    require(root.isDirectory || root.mkdirs()) { "Unable to create the image preview directory." }
    val requestToken = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    val requestDir = File(root, requestToken).canonicalFile
    require(requestDir.parentFile == root && (requestDir.isDirectory || requestDir.mkdirs())) {
        "Unable to create the request preview directory."
    }
    val fileName = "preview-${progress.previewRevision}.png"
    var createdForThisPublication = false
    val published = File(requestDir, fileName).takeIf(File::isFile)
        ?: run {
            if (!imageAssetWriteMutex.tryLock()) return@runCatching null
            try {
                if (!canPublishLocalImagePreviewFor(store, admission, jobId)) return@runCatching null
                val created = writeImageAssetBytesAtomically(requestDir, fileName, bytes)
                createdForThisPublication = true
                created
            } finally {
                imageAssetWriteMutex.unlock()
            }
        }
    if (!canPublishLocalImagePreviewFor(store, admission, jobId)) {
        if (createdForThisPublication) {
            published.delete()
        }
        return@runCatching null
    }
    PublishedLocalImagePreview(
        uriString = Uri.fromFile(published).toString(),
        mode = progress.previewMode,
        step = progress.previewStep,
        revision = progress.previewRevision,
        width = progress.previewWidth,
        height = progress.previewHeight
    )
}.getOrNull()

internal fun cleanupLocalImagePreviewsFor(application: Application, jobId: String) {
    runCatching {
        val cacheRoot = application.cacheDir.canonicalFile
        val root = File(cacheRoot, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY).canonicalFile
        val requestToken = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val requestDir = File(root, requestToken).canonicalFile
        if (requestDir.parentFile == root && requestDir.exists()) {
            requestDir.deleteRecursively()
        }
    }
}
