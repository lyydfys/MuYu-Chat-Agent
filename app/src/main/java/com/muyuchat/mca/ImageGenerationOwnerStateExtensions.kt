package com.muyuchat.mca

// R3/C2: 以下 4 个扩展原为 MainViewModel 类成员，随 enqueue 体移至顶层 internal 函数（同包 VM 与 owner 共用），体逐字等价。
internal fun MainUiState.selectedLocalImageModel(): LocalImageModelRecord? =
    localImageModels.firstOrNull { it.id == selectedLocalImageModelId && it.configured }


internal fun MainUiState.selectedImageCloudConfig(): CloudApiConfig? =
    cloudModels
        .firstOrNull { it.id == selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }
        ?.toImageConfig()


internal fun List<ImageGenerationJobRecord>.updateImageJob(
    jobId: String,
    status: ImageGenerationStatusRecord,
    message: String,
    imageAssetId: String? = null,
    preview: PublishedLocalImagePreview? = null,
    imageAssetIds: List<String> = emptyList()
): List<ImageGenerationJobRecord> =
    map { job ->
        if (job.id == jobId) {
            job.withCommittedImageJobUpdate(
                status = status,
                message = message,
                imageAssetId = imageAssetId ?: job.imageAssetId,
                preview = preview,
                imageAssetIds = imageAssetIds
            )
        } else {
            job
        }
    }

internal fun LocalImageProgress.toImageGenerationMessage(): String {
    val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val progress = if (steps > 0) "第 ${step.coerceIn(0, steps)}/$steps 步" else "准备中"
    val size = if (width > 0 && height > 0) " · ${width}x$height" else ""
    val threadText = if (threads > 0) " · ${threads} 线程" else ""
    val batchPrefix = message.substringBefore(" · ")
        .takeIf { prefix -> Regex("^第 [1-8]/[1-8] 张$").matches(prefix) }
    val detailMessage = batchPrefix
        ?.let { prefix -> message.removePrefix("$prefix · ") }
        ?: message
    val status = when {
        cancelRequested || phase == "cancelling" -> "正在停止本地生图 · ${elapsedSeconds}s"
        phase == "loading" -> "正在加载本地图像引擎 · ${elapsedSeconds}s$threadText"
        phase == "conditioning" -> "正在编码提示词 · ${elapsedSeconds}s$threadText"
        phase == "encoding" -> "正在准备初始图像 · ${elapsedSeconds}s$size$threadText"
        phase == "decoding" -> "正在解码图片 · ${elapsedSeconds}s$size"
        phase == "preparing" -> "正在准备采样 · ${elapsedSeconds}s$size$threadText"
        phase == "sampling" && step <= 0 -> {
            if (elapsedSeconds >= 60L) {
                "首步计算中 · ${elapsedSeconds}s$size$threadText"
            } else {
                "即将开始采样 · ${elapsedSeconds}s$size$threadText"
            }
        }
        phase == "sampling" -> "本地生图 $progress · ${elapsedSeconds}s$size$threadText"
        phase == "writing" -> "正在写入图片 · ${elapsedSeconds}s"
        phase == "completed" -> "本地生图完成 · ${elapsedSeconds}s"
        phase == "failed" -> detailMessage.ifBlank { "本地生图失败" }
        else -> "正在准备本地生图 · ${elapsedSeconds}s$threadText"
    }
    return appendLocalImagePreviewDegradationMessage(
        message = batchPrefix?.let { "$it · $status" } ?: status,
        previewFailureCode = previewFailureCode
    )
}
