package com.muyuchat.core.engine

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.File

internal fun normalizeJpegExifOrientation(
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
internal fun downsampleForNativeVision(
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

internal const val MAX_VALIDATION_DECODE_PIXELS = 4L * 1024L * 1024L

private const val MAX_NATIVE_VISION_PIXELS = 4L * 1024L * 1024L
