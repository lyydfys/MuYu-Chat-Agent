package com.muyuchat.core.engine

import android.graphics.BitmapFactory
import android.os.Build
import com.muyuchat.core.engine.LocalVisionInputPreparer.RemoteImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

private const val MAX_IMAGE_BYTES = MAX_LOCAL_VISION_IMAGE_BYTES

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

internal fun String.sha256(): String = toByteArray(Charsets.UTF_8).sha256()

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

internal fun ChatImageAttachment.withNativeReadableImageFile(
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

internal fun ChatImageAttachment.inlineMimeTypeOr(fallback: String): String {
        if (!dataBase64.startsWith("data:", ignoreCase = true)) return fallback
        return dataBase64
            .substring(5)
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
            .takeIf { it.startsWith("image/") }
            ?: fallback
    }

internal fun ChatImageAttachment.withExistingFile(file: File): ChatImageAttachment {
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

internal fun ChatImageAttachment.withDownloadedImageFile(
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

internal fun writeImageFile(
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
internal fun validateNativeReadableImage(file: File, declaredMimeType: String = "") {
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

internal fun validateDeclaredImageFormat(declaredMimeType: String, detectedFormat: String) {
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

internal fun imageExtension(mimeType: String, sourceName: String = ""): String {
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

internal fun downloadRemoteImage(url: String): RemoteImage {
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

internal fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
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

internal fun File.sha256(): String {
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
