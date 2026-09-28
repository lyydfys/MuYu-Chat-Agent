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
    private const val MAX_IMAGE_BYTES = MAX_LOCAL_VISION_IMAGE_BYTES
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

    private fun emitPreparedDiagnostic(
        original: ChatImageAttachment,
        prepared: ChatImageAttachment,
        preprocessing: String,
        sourceType: String,
        messageIndex: Int,
        attachmentIndex: Int,
        sink: (String, JSONObject) -> Unit
    ) {
        runCatching {
            val nativeFile = File(prepared.uriString)
            val details = baseDiagnostic(
                attachment = original,
                sourceType = sourceType,
                messageIndex = messageIndex,
                attachmentIndex = attachmentIndex
            )
                .put("status", "prepared")
                .put("declaredFormat", original.sourceDeclaredFormat(prepared, sourceType))
                .put("requestedFormat", original.normalizedMimeType())
                .put("preprocessing", preprocessing)
                .put("nativeReadablePath", nativeFile.name)
                .put("nativeReadablePathKind", "file_name")
                .put("nativeReadableBytes", nativeFile.length())
                .put("inputBytes", nativeFile.length())
            runCatching {
                inspectFile(nativeFile)
            }.onSuccess { inspection ->
                details
                    .put("inputSha256", inspection.sha256)
                    .put("detectedFormat", inspection.format)
                    .put("inputBytes", inspection.byteCount)
                    .put("originalWidth", inspection.width)
                    .put("originalHeight", inspection.height)
                    .put("dimensionsDetected", inspection.width > 0 && inspection.height > 0)
                    .put("inspectionStatus", "complete")
            }.onFailure { error ->
                details
                    .put("inputSha256", "unavailable")
                    .put("detectedFormat", "unknown")
                    .put("originalWidth", 0)
                    .put("originalHeight", 0)
                    .put("dimensionsDetected", false)
                    .put("inspectionStatus", "failed")
                    .put("inspectionErrorType", error::class.java.simpleName)
            }
            sink("local_vision_input_prepared", details)
        }
    }

    private fun emitFailureDiagnostic(
        attachment: ChatImageAttachment,
        sourceType: String,
        messageIndex: Int,
        attachmentIndex: Int,
        error: Throwable,
        sink: (String, JSONObject) -> Unit
    ) {
        runCatching {
            val details = baseDiagnostic(
                attachment = attachment,
                sourceType = sourceType,
                messageIndex = messageIndex,
                attachmentIndex = attachmentIndex
            )
                .put("status", "failed")
                .put("preprocessing", "not_started")
                .put("inputSha256", "unavailable")
                .put("detectedFormat", "unknown")
                .put("inputBytes", attachment.sizeBytes.coerceAtLeast(0L))
                .put("originalWidth", attachment.width.coerceAtLeast(0))
                .put("originalHeight", attachment.height.coerceAtLeast(0))
                .put("dimensionsDetected", false)
                .put("nativeReadablePath", "unavailable")
                .put("nativeReadablePathKind", "unavailable")
                .put("nativeReadableBytes", 0L)
                .put("inspectionStatus", "not_available")
                .put("errorType", error::class.java.simpleName)
                .put("failureCode", (error as? LocalVisionInputException)?.failureCode?.wireCode.orEmpty())
            sink("local_vision_input_prepare_failed", details)
        }
    }

    private fun baseDiagnostic(
        attachment: ChatImageAttachment,
        sourceType: String,
        messageIndex: Int,
        attachmentIndex: Int
    ): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("messageIndex", messageIndex)
        .put("attachmentIndex", attachmentIndex)
        .put("sourceType", sourceType)
        .put("declaredFormat", attachment.normalizedMimeType())
        .put("attachmentName", attachment.name.safeFileName())
        .put("sourceReferenceSha256", attachment.sourceReference().sha256())

    private fun ChatImageAttachment.sourceDeclaredFormat(
        prepared: ChatImageAttachment,
        sourceType: String
    ): String = when (sourceType) {
        "inline" -> dataBase64
            .takeIf { it.startsWith("data:", ignoreCase = true) }
            ?.substring(5)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: normalizedMimeType()
        "http" -> prepared.normalizedMimeType()
        else -> normalizedMimeType()
    }

    private fun ChatImageAttachment.normalizedMimeType(): String =
        mimeType.trim().lowercase(Locale.US).ifBlank { "unknown" }

    private fun ChatImageAttachment.sourceType(): String {
        if (hasInlineData) return "inline"
        val source = uriString.trim()
        return when {
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> "http"
            source.startsWith("content://", ignoreCase = true) -> "content"
            else -> "file"
        }
    }

    private fun ChatImageAttachment.sourceReference(): String =
        when {
            hasInlineData -> "inline:${name.safeFileName()}:${dataBase64.length}"
            else -> uriString.trim()
        }

    private fun String.safeFileName(): String =
        replace('\\', '/').substringAfterLast('/').take(160)

    private data class ImageInspection(
        val sha256: String,
        val format: String,
        val byteCount: Long,
        val width: Int,
        val height: Int
    )

    private fun inspectFile(file: File): ImageInspection {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(64 * 1024)
        var headerBytes = 0
        var totalBytes = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                totalBytes += read
                if (headerBytes < header.size) {
                    val copied = minOf(read, header.size - headerBytes)
                    buffer.copyInto(header, headerBytes, 0, copied)
                    headerBytes += copied
                }
            }
        }
        val probe = probeImage(header.copyOf(headerBytes))
        return ImageInspection(digest.digest().toHex(), probe.format, totalBytes, probe.width, probe.height)
    }

    private data class ImageProbe(val format: String = "unknown", val width: Int = 0, val height: Int = 0)

    private fun probeImage(bytes: ByteArray): ImageProbe = when {
        bytes.isPng() -> ImageProbe("png", bytes.readIntBigEndian(16), bytes.readIntBigEndian(20))
        bytes.isGif() -> ImageProbe("gif", bytes.readUShortLittleEndian(6), bytes.readUShortLittleEndian(8))
        bytes.isBmp() -> ImageProbe(
            "bmp",
            bytes.readIntLittleEndian(18),
            kotlin.math.abs(bytes.readIntLittleEndian(22))
        )
        bytes.isWebp() -> bytes.probeWebp()
        bytes.isJpeg() -> bytes.probeJpeg()
        else -> ImageProbe()
    }.sanitized()

    private fun ImageProbe.sanitized(): ImageProbe = copy(
        width = width.takeIf { it > 0 } ?: 0,
        height = height.takeIf { it > 0 } ?: 0
    )

    private fun ByteArray.isPng(): Boolean = size >= 24 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4e.toByte() &&
        this[3] == 0x47.toByte() && this[4] == 0x0d.toByte() && this[5] == 0x0a.toByte() &&
        this[6] == 0x1a.toByte() && this[7] == 0x0a.toByte()

    private fun ByteArray.isGif(): Boolean = size >= 10 &&
        String(this, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")

    private fun ByteArray.isBmp(): Boolean = size >= 26 && this[0] == 'B'.code.toByte() && this[1] == 'M'.code.toByte()

    private fun ByteArray.isWebp(): Boolean = size >= 30 &&
        String(this, 0, 4, Charsets.US_ASCII) == "RIFF" && String(this, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun ByteArray.isJpeg(): Boolean = size >= 4 && this[0] == 0xff.toByte() && this[1] == 0xd8.toByte()

    private fun ByteArray.probeJpeg(): ImageProbe {
        var offset = 2
        while (offset + 3 < size) {
            while (offset < size && this[offset] != 0xff.toByte()) offset++
            while (offset < size && this[offset] == 0xff.toByte()) offset++
            if (offset >= size) break
            val marker = this[offset].toInt() and 0xff
            offset++
            if (marker == 0xd8 || marker == 0xd9 || marker in 0xd0..0xd7) continue
            if (offset + 1 >= size) break
            val segmentLength = readUShortBigEndian(offset)
            if (segmentLength < 2 || offset + segmentLength > size) break
            if (marker in JPEG_START_OF_FRAME_MARKERS && segmentLength >= 7) {
                return ImageProbe("jpeg", readUShortBigEndian(offset + 5), readUShortBigEndian(offset + 3))
            }
            offset += segmentLength
        }
        return ImageProbe("jpeg")
    }

    private fun ByteArray.probeWebp(): ImageProbe {
        val chunk = String(this, 12, 4, Charsets.US_ASCII)
        return when (chunk) {
            "VP8X" -> ImageProbe("webp", 1 + readUInt24LittleEndian(24), 1 + readUInt24LittleEndian(27))
            "VP8L" -> if (size >= 25 && this[20] == 0x2f.toByte()) {
                val bits = (this[21].toInt() and 0xff) or
                    ((this[22].toInt() and 0xff) shl 8) or
                    ((this[23].toInt() and 0xff) shl 16) or
                    ((this[24].toInt() and 0xff) shl 24)
                ImageProbe("webp", (bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
            } else {
                ImageProbe("webp")
            }
            "VP8 " -> if (size >= 30 && this[23] == 0x9d.toByte() && this[24] == 0x01.toByte() && this[25] == 0x2a.toByte()) {
                ImageProbe("webp", readUShortLittleEndian(26) and 0x3fff, readUShortLittleEndian(28) and 0x3fff)
            } else {
                ImageProbe("webp")
            }
            else -> ImageProbe("webp")
        }
    }

    private fun ByteArray.readUShortBigEndian(offset: Int): Int =
        if (offset + 1 < size) ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff) else 0

    private fun ByteArray.readUShortLittleEndian(offset: Int): Int =
        if (offset + 1 < size) (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8) else 0

    private fun ByteArray.readIntBigEndian(offset: Int): Int =
        if (offset + 3 < size) (0..3).fold(0) { value, index -> (value shl 8) or (this[offset + index].toInt() and 0xff) } else 0

    private fun ByteArray.readIntLittleEndian(offset: Int): Int =
        if (offset + 3 < size) (0..3).fold(0) { value, index -> value or ((this[offset + index].toInt() and 0xff) shl (index * 8)) } else 0

    private fun ByteArray.readUInt24LittleEndian(offset: Int): Int =
        if (offset + 2 < size) (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) else 0

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun String.sha256(): String = toByteArray(Charsets.UTF_8).sha256()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private fun ChatImageAttachment.withNativeReadableImageFile(
        cacheDir: File,
        nowMillis: () -> Long,
        idSuffix: () -> String,
        remoteImageFetcher: (String) -> RemoteImage
    ): ChatImageAttachment {
        val url = uriString.trim()
        if (!hasInlineData && url.isBlank()) {
            error("Image URI is empty: ${name.ifBlank { "api-image" }}")
        }
        if (!hasInlineData) {
            return when {
                url.startsWith("file:", ignoreCase = true) -> withExistingFile(url.fileFromUri())
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                    withDownloadedImageFile(url, cacheDir, nowMillis, idSuffix, remoteImageFetcher)
                url.startsWith("content://", ignoreCase = true) ->
                    error("Local API cannot read content:// images directly. Use data:image base64, file://, or a reachable http(s) image URL.")
                else -> withExistingFile(File(url))
            }
        }

        val encodedImage = plainBase64()
        if (!inlineVisionImageWithinLimit(encodedImage)) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_TOO_LARGE,
                "Inline image exceeds the 20 MB local vision limit. Resize or compress the image and retry."
            )
        }
        val bytes = Base64.getMimeDecoder().decode(encodedImage)
        require(bytes.isNotEmpty()) { "Inline image is empty: ${name.ifBlank { "api-image" }}" }
        val inlineMimeType = inlineMimeTypeOr(mimeType)
        val file = writeImageFile(cacheDir, bytes, inlineMimeType, nowMillis, idSuffix)
        return copy(
            uriString = file.absolutePath,
            mimeType = inlineMimeType,
            dataBase64 = "",
            sizeBytes = file.length()
        )
    }

    private fun ChatImageAttachment.inlineMimeTypeOr(fallback: String): String {
        if (!dataBase64.startsWith("data:", ignoreCase = true)) return fallback
        return dataBase64
            .substring(5)
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
            .takeIf { it.startsWith("image/") }
            ?: fallback
    }

    private fun ChatImageAttachment.withExistingFile(file: File): ChatImageAttachment {
        require(file.isFile && file.canRead()) {
            "Image file is not readable: ${file.path.ifBlank { name.ifBlank { "api-image" } }}"
        }
        validateNativeReadableImage(file, mimeType)
        return copy(
            uriString = file.absolutePath,
            dataBase64 = "",
            sizeBytes = if (sizeBytes > 0L) sizeBytes else file.length()
        )
    }

    private fun normalizeJpegExifOrientation(
        attachment: ChatImageAttachment,
        cacheDir: File,
        nowMillis: () -> Long,
        idSuffix: () -> String
    ): ChatImageAttachment {
        if (Build.VERSION.SDK_INT <= 0) return attachment
        val source = File(attachment.uriString)
        if (readHeaderImageBounds(source).format != "jpeg") return attachment
        val orientation = try {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (error: Exception) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_FORMAT_UNSUPPORTED,
                "JPEG EXIF metadata cannot be read: ${error.message ?: error::class.java.simpleName}"
            )
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) return attachment

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                else -> throw LocalVisionInputException(
                    LocalVisionInputFailureCode.IMAGE_FORMAT_UNSUPPORTED,
                    "Unsupported JPEG EXIF orientation: $orientation"
                )
            }
        }
        val bounds = readAndroidImageBounds(source) ?: throw LocalVisionInputException(
            LocalVisionInputFailureCode.IMAGE_DECODE_FAILED,
            "JPEG dimensions cannot be decoded for EXIF normalization."
        )
        var sampleSize = 1
        val pixels = bounds.width.toLong() * bounds.height.toLong()
        while (pixels / (sampleSize.toLong() * sampleSize.toLong()) > MAX_VALIDATION_DECODE_PIXELS) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: throw LocalVisionInputException(
            LocalVisionInputFailureCode.IMAGE_DECODE_FAILED,
            "JPEG cannot be decoded for EXIF normalization."
        )
        val outputDir = File(cacheDir, "engine_vision_inputs").apply { mkdirs() }
        val output = File(outputDir, "vision-exif-${nowMillis()}-${idSuffix()}.jpg")
        var outputCreated = false
        try {
            val corrected = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            try {
                check(output.createNewFile()) { "Prepared image file already exists; retry this request." }
                outputCreated = true
                output.outputStream().buffered().use { stream ->
                    check(corrected.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)) {
                        "JPEG EXIF normalization failed."
                    }
                }
                validateNativeReadableImage(output, "image/jpeg")
                return attachment.copy(
                    uriString = output.absolutePath,
                    mimeType = "image/jpeg",
                    width = corrected.width,
                    height = corrected.height,
                    sizeBytes = output.length()
                )
            } finally {
                if (corrected !== bitmap) corrected.recycle()
            }
        } catch (error: Throwable) {
            if (outputCreated) output.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Camera-resolution pixels create a large temporary tensor in mtmd without improving the
     * projector result. Keep the original file for history, but give native vision a bounded
     * RGB JPEG when the source exceeds the 4MP staging budget.
     */
    private fun downsampleForNativeVision(
        attachment: ChatImageAttachment,
        cacheDir: File,
        nowMillis: () -> Long,
        idSuffix: () -> String
    ): ChatImageAttachment {
        val source = File(attachment.uriString)
        // Keep JVM/unit-test and older codec paths compatible: if Android cannot expose decoded
        // bounds, the existing header validation remains authoritative and native can consume the
        // original file.
        val bounds = readAndroidImageBounds(source) ?: return attachment
        val pixels = bounds.width.toLong() * bounds.height.toLong()
        if (bounds.width <= 0 || bounds.height <= 0 || pixels <= MAX_NATIVE_VISION_PIXELS) return attachment

        var sampleSize = 1
        while (pixels / (sampleSize.toLong() * sampleSize.toLong()) > MAX_NATIVE_VISION_PIXELS) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: throw LocalVisionInputException(
            LocalVisionInputFailureCode.IMAGE_DECODE_FAILED,
            "Image cannot be decoded for native vision staging."
        )
        val outputDir = File(cacheDir, "engine_vision_inputs").apply { mkdirs() }
        val output = File(outputDir, "vision-sampled-${nowMillis()}-${idSuffix()}.jpg")
        var outputCreated = false
        try {
            check(output.createNewFile()) { "Prepared image file already exists; retry this request." }
            outputCreated = true
            output.outputStream().buffered().use { stream ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, stream)) {
                    "Native vision image staging failed."
                }
            }
            validateNativeReadableImage(output, "image/jpeg")
            return attachment.copy(
                uriString = output.absolutePath,
                mimeType = "image/jpeg",
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = output.length()
            )
        } catch (error: Throwable) {
            if (outputCreated) output.delete()
            throw error
        } finally {
            bitmap.recycle()
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

    private fun ChatImageAttachment.withDownloadedImageFile(
        url: String,
        cacheDir: File,
        nowMillis: () -> Long,
        idSuffix: () -> String,
        remoteImageFetcher: (String) -> RemoteImage
    ): ChatImageAttachment {
        val remote = remoteImageFetcher(url)
        require(remote.bytes.isNotEmpty()) { "Remote image is empty: $url" }
        if (remote.bytes.size.toLong() > MAX_IMAGE_BYTES) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_TOO_LARGE,
                "Remote image exceeds the 20 MB local vision limit. Resize or compress the image and retry."
            )
        }
        val file = writeImageFile(
            cacheDir = cacheDir,
            bytes = remote.bytes,
            mimeType = remote.mimeType.ifBlank { mimeType },
            nowMillis = nowMillis,
            idSuffix = idSuffix,
            sourceName = url
        )
        return copy(
            uriString = file.absolutePath,
            mimeType = remote.mimeType.ifBlank { mimeType },
            dataBase64 = "",
            sizeBytes = file.length()
        )
    }

    private fun writeImageFile(
        cacheDir: File,
        bytes: ByteArray,
        mimeType: String,
        nowMillis: () -> Long,
        idSuffix: () -> String,
        sourceName: String = ""
    ): File {
        val dir = File(cacheDir, "engine_vision_inputs").apply { mkdirs() }
        val file = File(dir, "vision-${nowMillis()}-${idSuffix()}.${imageExtension(mimeType, sourceName)}")
        check(file.createNewFile()) { "Prepared image file already exists; retry this request." }
        return try {
            file.writeBytes(bytes)
            validateNativeReadableImage(file, mimeType)
            file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /**
     * Validate every local path before handing it to a native VLM decoder.
     * BitmapFactory is called with inJustDecodeBounds, so Android reads image
     * headers without allocating a full-resolution bitmap. Reject unknown
     * signatures and geometry before a sampled decode verifies that the file
     * can actually be read by Android's image codec.
     */
    private fun validateNativeReadableImage(file: File, declaredMimeType: String = "") {
        val bytes = file.length()
        if (bytes > MAX_IMAGE_BYTES) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_TOO_LARGE,
                "Image file exceeds the 20 MB local vision limit. Resize or compress the image and retry."
            )
        }
        if (bytes <= 0L) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.INVALID_IMAGE_GEOMETRY,
                "Image file is empty. Choose a readable image and retry."
            )
        }

        val header = readHeaderImageBounds(file)
        if (header.format == "unknown") {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_FORMAT_UNSUPPORTED,
                "Image signature is not a supported local vision format. Re-export as PNG, JPEG, WebP, GIF, or BMP."
            )
        }
        val bounds = readAndroidImageBounds(file) ?: header
        validateDeclaredImageFormat(declaredMimeType, header.format)
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.INVALID_IMAGE_GEOMETRY,
                "Image dimensions are invalid. Re-export the image and retry."
            )
        }
        val pixels = bounds.width.toLong() * bounds.height.toLong()
        if (bounds.width > MAX_LOCAL_VISION_IMAGE_SIDE ||
            bounds.height > MAX_LOCAL_VISION_IMAGE_SIDE ||
            pixels > MAX_LOCAL_VISION_IMAGE_PIXELS
        ) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_RESOLUTION_TOO_LARGE,
                "Image resolution is too large for safe local recognition (${bounds.width}×${bounds.height}). " +
                    "Resize the image to at most 16,384 pixels per side and 32 megapixels, then retry."
            )
        }
        val androidCodecAvailable = runCatching { Build.VERSION.SDK_INT > 0 }.getOrDefault(false)
        if (androidCodecAvailable) {
            var sampleSize = 1
            while (pixels / (sampleSize.toLong() * sampleSize.toLong()) > MAX_VALIDATION_DECODE_PIXELS) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
                ?: throw LocalVisionInputException(
                    LocalVisionInputFailureCode.IMAGE_DECODE_FAILED,
                    "Image header is readable, but the image data cannot be decoded. Re-export the image and retry."
                )
            decoded.recycle()
        }
    }

    private fun validateDeclaredImageFormat(declaredMimeType: String, detectedFormat: String) {
        val expected = declaredMimeType
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
            .removePrefix("image/")
            .let { if (it == "jpg") "jpeg" else it }
        val actual = detectedFormat
            .trim()
            .lowercase(Locale.US)
            .let { if (it == "jpg") "jpeg" else it }
        if (expected.isBlank() || expected == "unknown" || actual.isBlank() || actual == "unknown") return
        if (expected !in setOf("jpeg", "png", "webp", "gif", "bmp")) return
        if (actual != expected) {
            throw LocalVisionInputException(
                LocalVisionInputFailureCode.IMAGE_FORMAT_MISMATCH,
                "Image MIME type ($declaredMimeType) does not match the detected $actual format. " +
                    "Use a matching MIME type or re-export the image and retry."
            )
        }
    }

    private fun readAndroidImageBounds(file: File): ImageProbe? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
        val format = options.outMimeType
            ?.substringAfter('/', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it in setOf("jpeg", "jpg", "png", "webp", "gif", "bmp") }
            ?: "unknown"
        ImageProbe(format, options.outWidth, options.outHeight)
    }.getOrNull()

    private fun readHeaderImageBounds(file: File): ImageProbe {
        val header = ByteArray(64 * 1024)
        val count = file.inputStream().buffered().use { input ->
            var total = 0
            while (total < header.size) {
                val read = input.read(header, total, header.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        return probeImage(header.copyOf(count))
    }

    private fun imageExtension(mimeType: String, sourceName: String = ""): String {
        val normalizedMime = mimeType.lowercase(Locale.US)
        val sourceExtension = sourceName.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            "png" in normalizedMime -> "png"
            "webp" in normalizedMime -> "webp"
            "gif" in normalizedMime -> "gif"
            "bmp" in normalizedMime -> "bmp"
            sourceExtension in setOf("png", "webp", "gif", "bmp", "jpg", "jpeg") ->
                if (sourceExtension == "jpeg") "jpg" else sourceExtension
            else -> "jpg"
        }
    }

    private fun String.fileFromUri(): File =
        runCatching { File(URI(this)) }.getOrElse {
            File(removePrefix("file://").removePrefix("file:"))
        }

    private fun downloadRemoteImage(url: String): RemoteImage {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
        }
        return try {
            val status = connection.responseCode
            require(status in 200..299) { "Remote image download failed with HTTP $status: $url" }
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_IMAGE_BYTES) {
                throw LocalVisionInputException(
                    LocalVisionInputFailureCode.IMAGE_TOO_LARGE,
                    "Remote image is too large: ${contentLength / 1024L / 1024L} MB. Max supported size is 20 MB."
                )
            }
            connection.inputStream.use { input ->
                RemoteImage(
                    bytes = input.readBytesLimited(MAX_IMAGE_BYTES),
                    mimeType = connection.contentType?.substringBefore(';')?.trim().orEmpty()
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw LocalVisionInputException(
                    LocalVisionInputFailureCode.IMAGE_TOO_LARGE,
                    "Remote image exceeded the 20 MB local vision limit. Download stopped."
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private val JPEG_START_OF_FRAME_MARKERS = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
        0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf
    )

    private const val MAX_VALIDATION_DECODE_PIXELS = 4L * 1024L * 1024L
    private const val MAX_NATIVE_VISION_PIXELS = 4L * 1024L * 1024L
}
