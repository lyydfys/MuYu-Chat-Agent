package com.muyuchat.core.engine

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

internal enum class LocalVisionInputFailureCode(val wireCode: String) {
    IMAGE_TOO_LARGE("local_vision_image_too_large"),
    IMAGE_RESOLUTION_TOO_LARGE("local_vision_image_resolution_too_large"),
    INVALID_IMAGE_GEOMETRY("local_vision_image_invalid_geometry"),
    IMAGE_FORMAT_MISMATCH("local_vision_image_format_mismatch"),
    IMAGE_FORMAT_UNSUPPORTED("local_vision_image_format_unsupported"),
    IMAGE_DECODE_FAILED("local_vision_image_decode_failed"),
    TOO_MANY_IMAGES("local_vision_too_many_images")
}

internal class LocalVisionInputException(
    val failureCode: LocalVisionInputFailureCode,
    message: String
) : IllegalArgumentException(message)

internal object LocalVisionInputPreparer {
    private const val MAX_REQUEST_IMAGES = 8
    private const val MAX_REQUEST_IMAGE_BYTES = 40L * 1024L * 1024L
    private const val MAX_REQUEST_IMAGE_PIXELS = MAX_LOCAL_VISION_IMAGE_PIXELS

    data class RemoteImage(
        val bytes: ByteArray,
        val mimeType: String
    )

    fun prepare(
        request: ChatRequest,
        cacheDir: File,
        nowMillis: () -> Long = { System.currentTimeMillis() },
        idSuffix: () -> String = { UUID.randomUUID().toString().take(8) },
        remoteImageFetcher: (String) -> RemoteImage = ::downloadRemoteImage,
        diagnosticSink: (String, JSONObject) -> Unit = LocalChatRunnerDebug::emit,
        /** Reports completed image preparations and the total work item count. */
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): ChatRequest {
        if (request.messages.sumOf { it.imageAttachments.size } > MAX_REQUEST_IMAGES) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.TOO_MANY_IMAGES,
                "A local vision request supports at most $MAX_REQUEST_IMAGES images."
            )
        }
        val createdFiles = mutableListOf<File>()
        val sourceAttachments = mutableListOf<ChatImageAttachment>()
        val totalAttachments = request.messages.sumOf { message ->
            message.imageAttachments.deduplicateVisionAttachments().size
        }
        var completedAttachments = 0
        if (totalAttachments > 0) onProgress?.invoke(0, totalAttachments)
        return try {
            val prepared = request.copy(
                messages = request.messages.mapIndexed { messageIndex, message ->
                    if (message.imageAttachments.isEmpty()) {
                        message
                    } else {
                        val preparedAttachments = message.imageAttachments
                            .deduplicateVisionAttachments()
                            .mapIndexed { attachmentIndex, attachment ->
                                val sourceType = attachment.sourceType()
                                try {
                                    val staged = attachment.withNativeReadableImageFile(
                                        cacheDir = cacheDir,
                                        nowMillis = nowMillis,
                                        idSuffix = idSuffix,
                                        remoteImageFetcher = remoteImageFetcher
                                    )
                                    if (sourceType == "inline" || sourceType == "http") {
                                        createdFiles += File(staged.uriString)
                                    }
                                    sourceAttachments += staged
                                    validateAttachmentBudget(sourceAttachments)
                                    val oriented = normalizeJpegExifOrientation(
                                        staged, cacheDir, nowMillis, idSuffix
                                    )
                                    if (oriented.uriString != staged.uriString) {
                                        // Keep intermediate EXIF-normalized files on the same
                                        // request cleanup path when a second downsample pass follows.
                                        createdFiles += File(oriented.uriString)
                                    }
                                    val prepared = downsampleForNativeVision(
                                        oriented, cacheDir, nowMillis, idSuffix
                                    )
                                    if (prepared.uriString != staged.uriString) {
                                        createdFiles += File(prepared.uriString)
                                    }
                                    emitPreparedDiagnostic(
                                        original = attachment,
                                        prepared = prepared,
                                        preprocessing = if (prepared.uriString != staged.uriString) {
                                            "native_vision_staged"
                                        } else {
                                            "passthrough"
                                        },
                                        sourceType = sourceType,
                                        messageIndex = messageIndex,
                                        attachmentIndex = attachmentIndex,
                                        sink = diagnosticSink
                                    )
                                    completedAttachments += 1
                                    onProgress?.invoke(completedAttachments, totalAttachments)
                                    prepared
                                } catch (error: Throwable) {
                                    emitFailureDiagnostic(
                                        attachment = attachment,
                                        sourceType = sourceType,
                                        messageIndex = messageIndex,
                                        attachmentIndex = attachmentIndex,
                                        error = error,
                                        sink = diagnosticSink
                                    )
                                    throw error
                                }
                            }
                        // Different client representations of one image (content URI, file URI,
                        // data URL, or a copied temporary file) become the same native-readable
                        // file only after preparation. Deduplicate a second time on the materialized
                        // bytes so a single user image cannot be sent twice to a multimodal runner.
                        message.copy(imageAttachments = preparedAttachments.deduplicatePreparedVisionAttachments())
                    }
                }
            )
            validateRequestBudget(prepared)
            val retained = prepared.messages.flatMap { it.imageAttachments }
                .mapTo(hashSetOf()) { File(it.uriString).absolutePath }
            createdFiles.filterNot { it.absolutePath in retained }.forEach { it.delete() }
            prepared
        } catch (error: Throwable) {
            createdFiles.forEach { it.delete() }
            throw error
        }
    }

    /** Call after the native request has stopped consuming prepared images. */
    fun releasePreparedInputs(original: ChatRequest, prepared: ChatRequest, cacheDir: File) {
        val root = File(cacheDir, "engine_vision_inputs").canonicalFile
        val originalPaths = original.messages.flatMap { it.imageAttachments }
            .mapNotNull { attachment ->
                attachment.uriString.takeIf(String::isNotBlank)
                    ?.let { source ->
                        runCatching {
                            val file = if (source.startsWith("file:", ignoreCase = true)) {
                                source.fileFromUri()
                            } else {
                                File(source)
                            }
                            file.canonicalPath
                        }.getOrNull()
                    }
            }.toSet()
        prepared.messages.flatMap { it.imageAttachments }.forEach { attachment ->
            val candidate = runCatching { File(attachment.uriString).canonicalFile }.getOrNull()
                ?: return@forEach
            if (candidate.parentFile == root && candidate.name.startsWith("vision-") &&
                candidate.path !in originalPaths
            ) {
                candidate.delete()
            }
        }
    }

    private fun validateRequestBudget(request: ChatRequest) {
        validateAttachmentBudget(request.messages.flatMap { it.imageAttachments })
    }

    private fun validateAttachmentBudget(attachments: List<ChatImageAttachment>) {
        if (attachments.size > MAX_REQUEST_IMAGES) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.TOO_MANY_IMAGES,
                "A local vision request supports at most $MAX_REQUEST_IMAGES images."
            )
        }
        var totalBytes = 0L
        var totalPixels = 0L
        attachments.forEach { attachment ->
            val file = File(attachment.uriString)
            val size = file.length()
            if (size < 0L || size > MAX_REQUEST_IMAGE_BYTES - totalBytes) {
                throw LocalVisionInputException(
                    LocalVisionInputFailureCode.IMAGE_TOO_LARGE,
                    "The combined image data exceeds the 40 MB local vision request limit."
                )
            }
            totalBytes += size
            val bounds = readAndroidImageBounds(file) ?: readHeaderImageBounds(file)
            val pixels = bounds.width.toLong() * bounds.height.toLong()
            if (pixels <= 0L || pixels > MAX_REQUEST_IMAGE_PIXELS - totalPixels) {
                throw LocalVisionInputException(
                    LocalVisionInputFailureCode.IMAGE_RESOLUTION_TOO_LARGE,
                    "The combined image resolution exceeds the local vision request budget."
                )
            }
            totalPixels += pixels
        }
    }

    private fun List<ChatImageAttachment>.deduplicatePreparedVisionAttachments(): List<ChatImageAttachment> {
        if (size < 2) return this
        val seen = LinkedHashSet<String>(size)
        return filter { attachment ->
            val file = attachment.uriString.trim().let(::File)
            val key = if (!attachment.hasInlineData && file.isFile) {
                runCatching { "bytes:${file.length()}:${file.sha256()}" }
                    .getOrElse { attachment.visionDeduplicationKey() }
            } else {
                attachment.visionDeduplicationKey()
            }
            seen.add(key)
        }
    }

}

internal fun String.fileFromUri(): File =
        runCatching { File(URI(this)) }.getOrElse {
            File(removePrefix("file://").removePrefix("file:"))
        }
