package com.muyuchat.core.engine

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

internal object LocalVisionInputPreparer {
    private const val MAX_REMOTE_IMAGE_BYTES = 20L * 1024L * 1024L

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
        diagnosticSink: (String, JSONObject) -> Unit = LocalChatRunnerDebug::emit
    ): ChatRequest =
        request.copy(
            messages = request.messages.mapIndexed { messageIndex, message ->
                if (message.imageAttachments.isEmpty()) {
                    message
                } else {
                    message.copy(
                        imageAttachments = message.imageAttachments.mapIndexed { attachmentIndex, attachment ->
                            val sourceType = attachment.sourceType()
                            try {
                                val prepared = attachment.withNativeReadableImageFile(
                                    cacheDir = cacheDir,
                                    nowMillis = nowMillis,
                                    idSuffix = idSuffix,
                                    remoteImageFetcher = remoteImageFetcher
                                )
                                emitPreparedDiagnostic(
                                    original = attachment,
                                    prepared = prepared,
                                    sourceType = sourceType,
                                    messageIndex = messageIndex,
                                    attachmentIndex = attachmentIndex,
                                    sink = diagnosticSink
                                )
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
                    )
                }
            }
        )

    private fun emitPreparedDiagnostic(
        original: ChatImageAttachment,
        prepared: ChatImageAttachment,
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
                .put("preprocessing", "passthrough")
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
                return ImageProbe("jpeg", readUShortBigEndian(offset + 3), readUShortBigEndian(offset + 5))
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

        val bytes = Base64.getMimeDecoder().decode(plainBase64())
        require(bytes.isNotEmpty()) { "Inline image is empty: ${name.ifBlank { "api-image" }}" }
        val file = writeImageFile(cacheDir, bytes, mimeType, nowMillis, idSuffix)
        return copy(
            uriString = file.absolutePath,
            dataBase64 = "",
            sizeBytes = file.length()
        )
    }

    private fun ChatImageAttachment.withExistingFile(file: File): ChatImageAttachment {
        require(file.isFile && file.canRead()) {
            "Image file is not readable: ${file.path.ifBlank { name.ifBlank { "api-image" } }}"
        }
        return copy(
            uriString = file.absolutePath,
            dataBase64 = "",
            sizeBytes = if (sizeBytes > 0L) sizeBytes else file.length()
        )
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
        file.writeBytes(bytes)
        return file
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
            require(contentLength <= 0L || contentLength <= MAX_REMOTE_IMAGE_BYTES) {
                "Remote image is too large: ${contentLength / 1024L / 1024L} MB. Max supported size is 20 MB."
            }
            connection.inputStream.use { input ->
                RemoteImage(
                    bytes = input.readBytesLimited(MAX_REMOTE_IMAGE_BYTES),
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
            require(total <= maxBytes) { "Remote image exceeded 20 MB. Download stopped." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val JPEG_START_OF_FRAME_MARKERS = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
        0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf
    )
}
