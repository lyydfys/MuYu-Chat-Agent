package com.muyuchat.mca

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal const val QNN_INPUT_TENSOR_PREPROCESS =
    "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1"
internal const val QNN_INPUT_TENSOR_DTYPE = "float32-le"
internal const val QNN_INPUT_TENSOR_LAYOUT = "NCHW"
internal const val QNN_INPUT_TENSOR_RANGE = "NEGATIVE_ONE_TO_ONE"

internal const val SDXL_INPUT_TENSOR_PREPROCESS = QNN_INPUT_TENSOR_PREPROCESS
internal const val SDXL_INPUT_TENSOR_DTYPE = QNN_INPUT_TENSOR_DTYPE
internal const val SDXL_INPUT_TENSOR_LAYOUT = QNN_INPUT_TENSOR_LAYOUT
internal const val SDXL_INPUT_TENSOR_RANGE = QNN_INPUT_TENSOR_RANGE

internal const val QNN_SHARED_ARTIFACT_DIRECTORY = "local_image_outputs"
internal const val QNN_SHARED_ARTIFACT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L

private val QNN_SHARED_REQUEST_TOKEN_PATTERN = Regex(
    "^qnn-htp-[0-9]{1,19}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)

private val QNN_SHARED_ARTIFACT_SUFFIXES = setOf(
    ".png",
    ".png.part",
    ".sdxl-conditioning.f32",
    ".sdxl-conditioning.f32.part",
    ".qnn-clip-conditioning.bin",
    ".qnn-clip-conditioning.bin.part",
    ".qnn-clip-token-ids.i32",
    ".qnn-clip-token-ids.i32.part",
    ".sd15-embeddings.f32",
    ".sd15-embeddings.f32.part",
    ".latent.f32",
    ".latent.f32.part",
    ".latent.json",
    ".latent.json.part",
    ".input-rgb-nchw.f32",
    ".input-rgb-nchw.f32.part",
    ".encoder-latent.f32",
    ".encoder-latent.f32.part",
    ".encoder-latent.json",
    ".encoder-latent.json.part",
    ".qnn-stage.json",
    ".qnn-stage.json.tmp",
    ".encoder-stage.json",
    ".encoder-stage.json.tmp",
    ".unet-stage.json",
    ".unet-stage.json.tmp",
    ".vae-stage.json",
    ".vae-stage.json.tmp"
)

internal data class QnnPreparedInputTensor(
    val sourcePath: String,
    val sourceSha256: String,
    val sourceBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val exifOrientation: Int,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val tensorPath: String,
    val tensorSha256: String,
    val tensorBytes: Long,
    val tensorWidth: Int,
    val tensorHeight: Int
) {
    val tensorShape: List<Int> = listOf(1, 3, tensorHeight, tensorWidth)

    fun putNativeParams(target: JSONObject): JSONObject = target
        .put("inputImagePath", sourcePath)
        .put("inputImageSha256", sourceSha256)
        .put("inputImageSizeBytes", sourceBytes)
        .put("inputImageSourceWidth", sourceWidth)
        .put("inputImageSourceHeight", sourceHeight)
        .put("inputImageExifOrientation", exifOrientation)
        .put("inputImageOrientedWidth", orientedWidth)
        .put("inputImageOrientedHeight", orientedHeight)
        .put("inputImageTensorPath", tensorPath)
        .put("inputImageTensorSha256", tensorSha256)
        .put("inputImageTensorBytes", tensorBytes)
        .put("inputImageTensorShape", JSONArray(tensorShape))
        .put("inputImageTensorDtype", QNN_INPUT_TENSOR_DTYPE)
        .put("inputImageTensorLayout", QNN_INPUT_TENSOR_LAYOUT)
        .put("inputImageTensorRange", QNN_INPUT_TENSOR_RANGE)
        .put("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
}

internal typealias SdxlPreparedInputTensor = QnnPreparedInputTensor

/** Produces an exact bounded RGB tensor for a supported QNN VAE encoder input. */
internal object QnnInputImageArtifact {
    /**
     * Removes only direct, expired artifacts created by the shared-QNN request naming contract.
     *
     * The output directory is shared with other runtimes, so cleanup is deliberately bounded by
     * the canonical app cache root, the exact QNN request token, a closed suffix set, and a strict
     * age threshold. Request-local `finally` cleanup remains the normal lifecycle path.
     */
    fun cleanupStaleSharedArtifacts(
        cacheRoot: File,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (nowMs <= QNN_SHARED_ARTIFACT_MAX_AGE_MS) return 0
        val canonicalCacheRoot = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return 0
        val configuredRoot = File(canonicalCacheRoot, QNN_SHARED_ARTIFACT_DIRECTORY)
        if (!configuredRoot.isDirectory || Files.isSymbolicLink(configuredRoot.toPath())) return 0
        val artifactRoot = runCatching { configuredRoot.canonicalFile }.getOrNull() ?: return 0
        if (artifactRoot.parentFile != canonicalCacheRoot || !artifactRoot.isDirectory) return 0

        val staleBeforeMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS
        return artifactRoot.listFiles().orEmpty().count { candidate ->
            if (!candidate.isFile || Files.isSymbolicLink(candidate.toPath()) ||
                !isSharedQnnArtifactName(candidate.name)
            ) {
                return@count false
            }
            val lastModifiedMs = candidate.lastModified()
            if (lastModifiedMs <= 0L || lastModifiedMs >= staleBeforeMs) {
                return@count false
            }
            val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull()
                ?: return@count false
            if (canonicalCandidate.parentFile != artifactRoot) {
                return@count false
            }
            runCatching { candidate.delete() }.getOrDefault(false)
        }
    }

    fun prepare(
        input: LocalImagePreparedInput,
        tensorFile: File,
        targetWidth: Int,
        targetHeight: Int,
        isCancelled: () -> Boolean = { false }
    ): QnnPreparedInputTensor {
        requireSupportedDimensions(targetWidth, targetHeight)
        val source = File(input.path).canonicalFile
        require(source.isFile && source.canRead()) { "Prepared QNN input image is not readable." }
        require(source.length() == input.sizeBytes && source.length() in 1..LocalImagePreparedInput.MAX_INPUT_BYTES) {
            "Prepared QNN input image size changed before preprocessing."
        }
        require(sdxlArtifactSha256(source) == input.sha256) {
            "Prepared QNN input image digest changed before preprocessing."
        }
        throwIfQnnInputCancelled(isCancelled)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth == input.width && bounds.outHeight == input.height) {
            "Prepared QNN input image dimensions changed before preprocessing."
        }
        val sampleSize = decodeSampleSizeForQnnInput(
            width = bounds.outWidth,
            height = bounds.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        val decoded = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        ) ?: error("Unable to decode the prepared QNN input image.")
        var owned: Bitmap? = decoded
        try {
            throwIfQnnInputCancelled(isCancelled)
            val exifOrientation = readExifOrientation(source)
            require(exifOrientation == input.exifOrientation) {
                "Prepared QNN input image EXIF orientation changed before preprocessing."
            }
            val oriented = orientQnnInput(decoded, exifOrientation)
            owned = oriented
            throwIfQnnInputCancelled(isCancelled)
            val tensorBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                val sourceCrop = centerCropRect(
                    sourceWidth = oriented.width,
                    sourceHeight = oriented.height,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
                Canvas(tensorBitmap).drawBitmap(
                    oriented,
                    sourceCrop,
                    RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        isDither = false
                    }
                )
                throwIfQnnInputCancelled(isCancelled)
                val tensor = writeRgbNchwTensorAtomic(
                    bitmap = tensorBitmap,
                    target = tensorFile,
                    isCancelled = isCancelled
                )
                return QnnPreparedInputTensor(
                    sourcePath = source.path,
                    sourceSha256 = input.sha256,
                    sourceBytes = input.sizeBytes,
                    sourceWidth = input.width,
                    sourceHeight = input.height,
                    exifOrientation = exifOrientation,
                    orientedWidth = input.orientedWidth,
                    orientedHeight = input.orientedHeight,
                    tensorPath = tensor.file.canonicalPath,
                    tensorSha256 = tensor.sha256,
                    tensorBytes = tensor.bytes,
                    tensorWidth = targetWidth,
                    tensorHeight = targetHeight
                )
            } finally {
                tensorBitmap.recycle()
            }
        } finally {
            owned?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    fun requireSupportedDimensions(targetWidth: Int, targetHeight: Int) {
        require(
            targetWidth in QNN_SHARED_ULTRAFIX_MIN_SIDE..QNN_SHARED_ULTRAFIX_MAX_SIDE &&
                targetHeight in QNN_SHARED_ULTRAFIX_MIN_SIDE..QNN_SHARED_ULTRAFIX_MAX_SIDE &&
                targetWidth % QNN_SHARED_ULTRAFIX_DIMENSION_MULTIPLE == 0 &&
                targetHeight % QNN_SHARED_ULTRAFIX_DIMENSION_MULTIPLE == 0
        ) {
            "QNN prepared RGB tensors must be 512-2048 per side and aligned to 64 pixels."
        }
    }

    private fun writeRgbNchwTensorAtomic(
        bitmap: Bitmap,
        target: File,
        isCancelled: () -> Boolean
    ): WrittenTensor {
        target.parentFile?.mkdirs()
        require(target.parentFile?.isDirectory == true) { "Unable to create the QNN tensor directory." }
        val temporary = File(target.parentFile, target.name + ".part")
        runCatching { temporary.delete() }
        val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileOutputStream(temporary).use { output ->
                fun flushBuffer() {
                    val count = buffer.position()
                    if (count == 0) return
                    output.write(buffer.array(), 0, count)
                    digest.update(buffer.array(), 0, count)
                    buffer.clear()
                }
                for (channel in 0..2) {
                    for (index in pixels.indices) {
                        if (index % 16_384 == 0) throwIfQnnInputCancelled(isCancelled)
                        val shift = 16 - channel * 8
                        val component = (pixels[index] ushr shift) and 0xff
                        if (buffer.remaining() < Float.SIZE_BYTES) flushBuffer()
                        buffer.putFloat(component / 127.5f - 1.0f)
                    }
                }
                flushBuffer()
                output.fd.sync()
            }
            val expectedBytes = Math.multiplyExact(
                Math.multiplyExact(bitmap.width.toLong(), bitmap.height.toLong()),
                3L * Float.SIZE_BYTES
            )
            require(temporary.length() == expectedBytes) {
                "QNN input tensor byte size does not match its declared shape."
            }
            throwIfQnnInputCancelled(isCancelled)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return WrittenTensor(
                file = target,
                sha256 = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                },
                bytes = expectedBytes
            )
        } catch (error: Throwable) {
            runCatching { temporary.delete() }
            runCatching { target.delete() }
            throw error
        }
    }
}

private fun isSharedQnnArtifactName(name: String): Boolean =
    QNN_SHARED_ARTIFACT_SUFFIXES.any { suffix ->
        name.endsWith(suffix) &&
            QNN_SHARED_REQUEST_TOKEN_PATTERN.matches(name.removeSuffix(suffix))
    }

/** Source-compatible SDXL wrapper; the isolated split-worker graph remains fixed at 1024x1024. */
internal object SdxlInputImageArtifact {
    fun prepare(
        input: LocalImagePreparedInput,
        tensorFile: File,
        targetWidth: Int,
        targetHeight: Int,
        isCancelled: () -> Boolean = { false }
    ): SdxlPreparedInputTensor {
        requireSupportedDimensions(targetWidth, targetHeight)
        return QnnInputImageArtifact.prepare(
            input = input,
            tensorFile = tensorFile,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            isCancelled = isCancelled
        )
    }

    fun requireSupportedDimensions(targetWidth: Int, targetHeight: Int) {
        require(targetWidth == 1024 && targetHeight == 1024) {
            "The installed SDXL VAE encoder requires an exact 1024x1024 RGB input."
        }
    }
}

internal fun resolveQnnImg2ImgSchedule(
    steps: Int,
    fullTimetableCount: Int,
    strength: Double
): QnnImg2ImgSchedule {
    require(steps > 0 && fullTimetableCount == steps) {
        "QNN img2img requires one scheduler timetable entry per requested step."
    }
    require(strength.isFinite() && strength >= 0.0 && strength <= 1.0) {
        "QNN img2img strength must be finite and in [0, 1]."
    }
    // Local Dream v2.8.0 parses denoise strength as float and derives the
    // first visited timetable entry from the retained-image fraction. Keep
    // those float32/truncation semantics exactly: algebraically replacing
    // this with floor(steps * strength) is observably different at values
    // such as 30 * 0.6f (19 visited entries here, not 18).
    val strengthFloat = strength.toFloat()
    val beginIndex = (steps.toFloat() * (1.0f - strengthFloat))
        .toInt()
        .coerceIn(0, steps - 1)
    val effectiveSteps = steps - beginIndex
    return QnnImg2ImgSchedule(
        strength = strength,
        effectiveSteps = effectiveSteps,
        beginIndex = beginIndex,
        fullTimetableCount = fullTimetableCount
    )
}

internal data class QnnImg2ImgSchedule(
    val strength: Double,
    val effectiveSteps: Int,
    val beginIndex: Int,
    val fullTimetableCount: Int
)

internal typealias SdxlImg2ImgSchedule = QnnImg2ImgSchedule

internal fun resolveSdxlImg2ImgSchedule(
    steps: Int,
    fullTimetableCount: Int,
    strength: Double
): SdxlImg2ImgSchedule = resolveQnnImg2ImgSchedule(
    steps = steps,
    fullTimetableCount = fullTimetableCount,
    strength = strength
)

internal fun ImageExecutionProfileResolution.withQnnProductSchedule(
    options: LocalImageGenerationOptions
): ImageExecutionProfileResolution {
    if (profile.runtime != LocalImageRuntime.QNN_HTP ||
        profile.graph.workerStrategy !in setOf(
            ImageWorkerStrategy.SPLIT_UNET_VAE,
            ImageWorkerStrategy.SHARED_UNET_VAE,
            ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        ) ||
        options.taskMode != LocalImageTaskMode.IMG2IMG
    ) {
        return this
    }
    require(profile.graph.vaeEncoder != null) {
        "Resolved QNN img2img requires a concrete VAE encoder graph."
    }
    val resolved = layers.resolved
    val schedule = resolveQnnImg2ImgSchedule(
        steps = resolved.steps,
        fullTimetableCount = resolved.timetableCount,
        strength = options.strength ?: 1.0
    )
    val branches = if (resolved.useCfg) 2 else 1
    return copy(
        layers = layers.copy(
            resolved = resolved.copy(
                timetableCount = schedule.effectiveSteps,
                unetExecutionCount = Math.multiplyExact(schedule.effectiveSteps, branches)
            )
        )
    )
}

internal fun ImageExecutionProfileResolution.withSdxlProductSchedule(
    options: LocalImageGenerationOptions
): ImageExecutionProfileResolution {
    if (profile.runtime != LocalImageRuntime.QNN_HTP ||
        profile.graph.workerStrategy != ImageWorkerStrategy.SPLIT_UNET_VAE ||
        options.taskMode != LocalImageTaskMode.IMG2IMG
    ) {
        return this
    }
    return withQnnProductSchedule(options)
}

private data class WrittenTensor(val file: File, val sha256: String, val bytes: Long)

private fun decodeSampleSizeForQnnInput(
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

private fun centerCropRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Rect {
    val sourceAspect = sourceWidth.toLong() * targetHeight.toLong()
    val targetAspect = sourceHeight.toLong() * targetWidth.toLong()
    return if (sourceAspect > targetAspect) {
        val cropWidth = (sourceHeight.toLong() * targetWidth / targetHeight).toInt().coerceAtMost(sourceWidth)
        val left = (sourceWidth - cropWidth) / 2
        Rect(left, 0, left + cropWidth, sourceHeight)
    } else {
        val cropHeight = (sourceWidth.toLong() * targetHeight / targetWidth).toInt().coerceAtMost(sourceHeight)
        val top = (sourceHeight - cropHeight) / 2
        Rect(0, top, sourceWidth, top + cropHeight)
    }
}

private fun orientQnnInput(source: Bitmap, orientation: Int): Bitmap {
    val (rotation, flipAfterRotation) = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
        ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
        ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
        ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
        ExifInterface.ORIENTATION_TRANSVERSE -> -90 to true
        ExifInterface.ORIENTATION_ROTATE_270 -> -90 to false
        else -> 0 to false
    }
    if (rotation == 0 && !flipAfterRotation) return source
    val matrix = Matrix().apply {
        setRotate(rotation.toFloat())
        if (flipAfterRotation) postScale(-1f, 1f)
    }
    val transformed = Bitmap.createBitmap(
        source,
        0,
        0,
        source.width,
        source.height,
        matrix,
        true
    )
    if (transformed !== source) source.recycle()
    return transformed
}

private fun readExifOrientation(file: File): Int = try {
    ExifInterface(file.path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    ).takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 }
        ?: ExifInterface.ORIENTATION_NORMAL
} catch (_: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun throwIfQnnInputCancelled(isCancelled: () -> Boolean) {
    check(!isCancelled()) { "QNN input preprocessing was cancelled." }
}
