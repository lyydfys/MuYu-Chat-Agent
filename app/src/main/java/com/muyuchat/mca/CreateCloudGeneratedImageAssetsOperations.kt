package com.muyuchat.mca

import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// R3-S28：云端生成图资产生成体自 MainViewModel 迁出（体逐字；provider/mutex/dir 参数化，零替换）。

internal suspend fun createCloudGeneratedImageAssetsFor(
    cloudImageProvider: CloudImageProvider,
    imageAssetWriteMutex: Mutex,
    imageAssetDirectory: File,
    request: CloudImageRequest,
    config: CloudApiConfig,
    chatSessionId: String? = null,
    generationMetadata: ImageGenerationHistoryMetadata
): List<ImageAssetRecord> {
        val result = cloudImageProvider.generateBatch(config, request)
        currentCoroutineContext().ensureActive()
        return imageAssetWriteMutex.withLock {
            val writtenFiles = mutableListOf<File>()
            try {
                result.outputs.mapIndexed { index, output ->
                    currentCoroutineContext().ensureActive()
                    val extension = imageExtensionForMime(output.mimeType)
                    val outputFile = writeImageAssetBytesAtomically(
                        directory = imageAssetDirectory,
                        fileName = "cloud-image-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-$index.$extension",
                        bytes = output.bytes
                    )
                    writtenFiles += outputFile
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        "Cloud image $index could not be decoded after publication."
                    }
                    ImageAssetRecord(
                        id = UUID.randomUUID().toString(),
                        name = "Cloud Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}-$index.$extension",
                        uriString = Uri.fromFile(outputFile).toString(),
                        source = "generated:${config.imageApiFormat.label}",
                        prompt = output.revisedPrompt.ifBlank { request.positivePrompt },
                        sizeBytes = outputFile.length(),
                        width = bounds.outWidth,
                        height = bounds.outHeight,
                        generationMetadataJson = generationMetadata.toJsonString(),
                        chatSessionId = chatSessionId
                    )
                }
            } catch (error: Throwable) {
                writtenFiles.forEach { file -> runCatching { file.delete() } }
                throw error
            }
        }
    }
