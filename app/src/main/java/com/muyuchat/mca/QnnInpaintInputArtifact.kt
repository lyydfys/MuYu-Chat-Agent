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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal const val QNN_INPAINT_MASK_PREPROCESS =
    "source_aligned_center_crop_linear_grayscale_area_latent_nchw_v2"
internal const val QNN_INPAINT_FULL_MASK_PREPROCESS =
    "source_aligned_center_crop_linear_grayscale_full_nchw_v1"
internal const val QNN_INPAINT_MASKED_RGB_PREPROCESS =
    "source_aligned_grayscale_masked_rgb_nchw_negative_one_to_one_v2"
internal const val QNN_INPAINT_MASK_CONVENTION = "white_repaint_black_preserve"
internal const val QNN_INPAINT_ARTIFACT_VERSION = 2

private val QNN_INPAINT_REQUEST_TOKEN = Regex(
    "^qnn-htp-[0-9]{1,19}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
private val QNN_INPAINT_ARTIFACT_SUFFIXES = setOf(
    ".inpaint-mask-latent.f32",
    ".inpaint-mask-latent.f32.part",
    ".inpaint-mask-full.f32",
    ".inpaint-mask-full.f32.part",
    ".inpaint-masked-rgb-nchw.f32",
    ".inpaint-masked-rgb-nchw.f32.part",
)

internal data class QnnPreparedInpaintInput(
    val source: QnnPreparedInputTensor,
    val topology: QnnInpaintMaskTopology,
    val maskSourcePath: String,
    val maskSourceSha256: String,
    val maskSourceBytes: Long,
    val maskSourceWidth: Int,
    val maskSourceHeight: Int,
    val maskExifOrientation: Int,
    val maskOrientedWidth: Int,
    val maskOrientedHeight: Int,
    val maskTensorPath: String,
    val maskTensorSha256: String,
    val maskTensorBytes: Long,
    val fullMaskTensorPath: String,
    val fullMaskTensorSha256: String,
    val fullMaskTensorBytes: Long,
    val maskedInputTensorPath: String?,
    val maskedInputTensorSha256: String?,
    val maskedInputTensorBytes: Long,
    val targetWidth: Int,
    val targetHeight: Int,
    val repaintPixelCount: Long,
    val latentRepaintPixelCount: Long,
) {
    val maskTensorShape: List<Int> = listOf(1, 1, targetHeight / 8, targetWidth / 8)
    val fullMaskTensorShape: List<Int> = listOf(1, 1, targetHeight, targetWidth)
    val maskedInputTensorShape: List<Int> = listOf(1, 3, targetHeight, targetWidth)

    fun putNativeParams(target: JSONObject): JSONObject {
        source.putNativeParams(target)
        target
            .put("inpaintArtifactVersion", QNN_INPAINT_ARTIFACT_VERSION)
            .put("inpaintRequestedTopology", topology.wireName)
            .put("inpaintMaskConvention", QNN_INPAINT_MASK_CONVENTION)
            .put("maskImagePath", maskSourcePath)
            .put("maskImageSha256", maskSourceSha256)
            .put("maskImageSizeBytes", maskSourceBytes)
            .put("maskImageSourceWidth", maskSourceWidth)
            .put("maskImageSourceHeight", maskSourceHeight)
            .put("maskImageExifOrientation", maskExifOrientation)
            .put("maskImageOrientedWidth", maskOrientedWidth)
            .put("maskImageOrientedHeight", maskOrientedHeight)
            .put("maskImageTensorPath", maskTensorPath)
            .put("maskImageTensorSha256", maskTensorSha256)
            .put("maskImageTensorBytes", maskTensorBytes)
            .put("maskImageTensorShape", JSONArray(maskTensorShape))
            .put("maskImageTensorDtype", "float32-le")
            .put("maskImageTensorLayout", "NCHW")
            .put("maskImageTensorRange", "ZERO_TO_ONE")
            .put("maskImageTensorPreprocess", QNN_INPAINT_MASK_PREPROCESS)
            .put("maskImageFullTensorPath", fullMaskTensorPath)
            .put("maskImageFullTensorSha256", fullMaskTensorSha256)
            .put("maskImageFullTensorBytes", fullMaskTensorBytes)
            .put("maskImageFullTensorShape", JSONArray(fullMaskTensorShape))
            .put("maskImageFullTensorDtype", "float32-le")
            .put("maskImageFullTensorLayout", "NCHW")
            .put("maskImageFullTensorRange", "ZERO_TO_ONE")
            .put("maskImageFullTensorPreprocess", QNN_INPAINT_FULL_MASK_PREPROCESS)
            .put("maskImageRepaintPixelCount", repaintPixelCount)
            .put("maskImageLatentRepaintPixelCount", latentRepaintPixelCount)
        if (topology.requiresMaskedImageLatent) {
            target
                .put("maskedInputImageTensorPath", requireNotNull(maskedInputTensorPath))
                .put("maskedInputImageTensorSha256", requireNotNull(maskedInputTensorSha256))
                .put("maskedInputImageTensorBytes", maskedInputTensorBytes)
                .put("maskedInputImageTensorShape", JSONArray(maskedInputTensorShape))
                .put("maskedInputImageTensorDtype", "float32-le")
                .put("maskedInputImageTensorLayout", "NCHW")
                .put("maskedInputImageTensorRange", "NEGATIVE_ONE_TO_ONE")
                .put("maskedInputImageTensorPreprocess", QNN_INPAINT_MASKED_RGB_PREPROCESS)
        }
        return target
    }

    fun cleanup() {
        listOfNotNull(maskTensorPath, fullMaskTensorPath, maskedInputTensorPath).forEach { path ->
            runCatching { File(path).delete() }
            runCatching { File("$path.part").delete() }
        }
    }
}

/** Creates deterministic mask artifacts; native owns the source encode and any topology-required masked encode. */
internal object QnnInpaintInputArtifact {
    fun prepare(
        input: LocalImagePreparedInput,
        mask: LocalImagePreparedInput,
        sourceTensorFile: File,
        maskTensorFile: File,
        fullMaskTensorFile: File,
        maskedInputTensorFile: File,
        topology: QnnInpaintTopologyInspection,
        targetWidth: Int,
        targetHeight: Int,
        isCancelled: () -> Boolean = { false },
    ): QnnPreparedInpaintInput {
        QnnInputImageArtifact.requireSupportedDimensions(targetWidth, targetHeight)
        require(targetWidth % 8 == 0 && targetHeight % 8 == 0) {
            "QNN inpaint dimensions must be divisible by eight."
        }
        require(topology.supported &&
            topology.width == targetWidth / 8 && topology.height == targetHeight / 8
        ) {
            "QNN inpaint graph topology does not match the requested latent dimensions."
        }
        requireDistinctSiblingTargets(
            sourceTensorFile,
            maskTensorFile,
            fullMaskTensorFile,
            maskedInputTensorFile,
        )
        if (!topology.requiresMaskedImageLatent) {
            runCatching { maskedInputTensorFile.delete() }
            runCatching { File(maskedInputTensorFile.parentFile, maskedInputTensorFile.name + ".part").delete() }
        }
        require(
            mask.orientedWidth == input.orientedWidth &&
                mask.orientedHeight == input.orientedHeight
        ) {
            "QNN inpaint source and mask must have identical prepared oriented dimensions."
        }
        val sourceTensor = QnnInputImageArtifact.prepare(
            input = input,
            tensorFile = sourceTensorFile,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            isCancelled = isCancelled,
        )
        try {
            throwIfCancelled(isCancelled)
            val maskSource = validatePreparedSource(mask, "mask")
            val maskOrientation = readOrientation(maskSource)
            require(maskOrientation == mask.exifOrientation) {
                "Prepared QNN mask EXIF orientation changed before preprocessing."
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(maskSource.path, bounds)
            require(bounds.outWidth == mask.width && bounds.outHeight == mask.height) {
                "Prepared QNN mask dimensions changed before preprocessing."
            }
            val sampleSize = decodeSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
            )
            val decoded = BitmapFactory.decodeFile(
                maskSource.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ) ?: error("Unable to decode the prepared QNN inpaint mask.")
            var ownedMask: Bitmap? = decoded
            try {
                val orientedMask = orient(decoded, maskOrientation)
                ownedMask = orientedMask
                throwIfCancelled(isCancelled)
                val targetMask = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                try {
                    val crop = centerCropRect(
                        sourceWidth = orientedMask.width,
                        sourceHeight = orientedMask.height,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                    )
                    Canvas(targetMask).drawBitmap(
                        orientedMask,
                        crop,
                        RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            isDither = false
                        },
                    )
                    val maskPixels = IntArray(Math.multiplyExact(targetWidth, targetHeight))
                    targetMask.getPixels(maskPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
                    val fullResolutionMask = FloatArray(maskPixels.size)
                    var repaintPixelCount = 0L
                    maskPixels.forEachIndexed { index, pixel ->
                        val value = qnnInpaintGrayscaleMaskValue(pixel)
                        fullResolutionMask[index] = value
                        if (value > 0.0f) repaintPixelCount++
                    }
                    throwIfCancelled(isCancelled)
                    val latentMask = downsampleQnnInpaintMaskArea(
                        fullResolutionMask = fullResolutionMask,
                        width = targetWidth,
                        height = targetHeight,
                    )
                    val maskTensor = writeFloatTensorAtomic(
                        values = latentMask,
                        target = maskTensorFile,
                        isCancelled = isCancelled,
                    )
                    val fullMaskTensor = writeFloatTensorAtomic(
                        values = fullResolutionMask,
                        target = fullMaskTensorFile,
                        isCancelled = isCancelled,
                    )
                    val maskedInputTensor = if (topology.requiresMaskedImageLatent) {
                        writeMaskedRgbTensorAtomic(
                            sourceTensor = File(sourceTensor.tensorPath),
                            expectedSourceSha256 = sourceTensor.tensorSha256,
                            fullResolutionMask = fullResolutionMask,
                            target = maskedInputTensorFile,
                            isCancelled = isCancelled,
                        )
                    } else {
                        null
                    }
                    throwIfCancelled(isCancelled)
                    return QnnPreparedInpaintInput(
                        source = sourceTensor,
                        topology = topology.topology,
                        maskSourcePath = maskSource.path,
                        maskSourceSha256 = mask.sha256,
                        maskSourceBytes = mask.sizeBytes,
                        maskSourceWidth = mask.width,
                        maskSourceHeight = mask.height,
                        maskExifOrientation = maskOrientation,
                        maskOrientedWidth = mask.orientedWidth,
                        maskOrientedHeight = mask.orientedHeight,
                        maskTensorPath = maskTensor.file.canonicalPath,
                        maskTensorSha256 = maskTensor.sha256,
                        maskTensorBytes = maskTensor.bytes,
                        fullMaskTensorPath = fullMaskTensor.file.canonicalPath,
                        fullMaskTensorSha256 = fullMaskTensor.sha256,
                        fullMaskTensorBytes = fullMaskTensor.bytes,
                        maskedInputTensorPath = maskedInputTensor?.file?.canonicalPath,
                        maskedInputTensorSha256 = maskedInputTensor?.sha256,
                        maskedInputTensorBytes = maskedInputTensor?.bytes ?: 0L,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        repaintPixelCount = repaintPixelCount,
                        latentRepaintPixelCount = latentMask.count { it > 0.0f }.toLong(),
                    )
                } finally {
                    targetMask.recycle()
                }
            } finally {
                ownedMask?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        } catch (error: Throwable) {
            listOf(
                sourceTensorFile,
                maskTensorFile,
                fullMaskTensorFile,
                maskedInputTensorFile,
            ).forEach { target ->
                runCatching { target.delete() }
                runCatching { File(target.parentFile, target.name + ".part").delete() }
            }
            throw error
        }
    }

    fun cleanupStaleArtifacts(
        cacheRoot: File,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        if (nowMs <= QNN_SHARED_ARTIFACT_MAX_AGE_MS) return 0
        val canonicalCacheRoot = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return 0
        val configuredRoot = File(canonicalCacheRoot, QNN_SHARED_ARTIFACT_DIRECTORY)
        if (!configuredRoot.isDirectory || Files.isSymbolicLink(configuredRoot.toPath())) return 0
        val artifactRoot = runCatching { configuredRoot.canonicalFile }.getOrNull() ?: return 0
        if (artifactRoot.parentFile != canonicalCacheRoot) return 0
        val staleBeforeMs = nowMs - QNN_SHARED_ARTIFACT_MAX_AGE_MS
        return artifactRoot.listFiles().orEmpty().count { candidate ->
            val suffix = QNN_INPAINT_ARTIFACT_SUFFIXES.firstOrNull(candidate.name::endsWith)
                ?: return@count false
            if (!candidate.isFile || Files.isSymbolicLink(candidate.toPath()) ||
                !QNN_INPAINT_REQUEST_TOKEN.matches(candidate.name.removeSuffix(suffix)) ||
                candidate.lastModified() <= 0L || candidate.lastModified() >= staleBeforeMs
            ) {
                return@count false
            }
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@count false
            canonical.parentFile == artifactRoot && runCatching { candidate.delete() }.getOrDefault(false)
        }
    }
}

internal fun qnnInpaintGrayscaleMaskValue(argb: Int): Float {
    val alpha = (argb ushr 24) and 0xff
    val red = (argb ushr 16) and 0xff
    val green = (argb ushr 8) and 0xff
    val blue = argb and 0xff
    return (red + green + blue).toFloat() * alpha.toFloat() / (3.0f * 255.0f * 255.0f)
}

internal fun downsampleQnnInpaintMaskArea(
    fullResolutionMask: FloatArray,
    width: Int,
    height: Int,
): FloatArray {
    require(width > 0 && height > 0 && width % 8 == 0 && height % 8 == 0)
    require(fullResolutionMask.size == Math.multiplyExact(width, height))
    require(fullResolutionMask.all { it.isFinite() && it in 0.0f..1.0f })
    val latentWidth = width / 8
    val latentHeight = height / 8
    return FloatArray(Math.multiplyExact(latentWidth, latentHeight)) { index ->
        val latentY = index / latentWidth
        val latentX = index % latentWidth
        val sourceX = latentX * 8
        val sourceY = latentY * 8
        var sum = 0.0
        for (offsetY in 0 until 8) {
            val row = (sourceY + offsetY) * width + sourceX
            for (offsetX in 0 until 8) {
                sum += fullResolutionMask[row + offsetX].toDouble()
            }
        }
        (sum / 64.0).toFloat()
    }
}

internal fun qnnInpaintLaplacianLevelCount(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    val minimum = minOf(width, height)
    val floorLog2 = Int.SIZE_BITS - 1 - Integer.numberOfLeadingZeros(minimum)
    var levels = maxOf(floorLog2 - 3, 2)
    while (levels > 0 && (minimum shr levels) < 4) levels--
    return maxOf(levels, 1)
}

private data class WrittenInpaintTensor(val file: File, val sha256: String, val bytes: Long)

private fun writeFloatTensorAtomic(
    values: FloatArray,
    target: File,
    isCancelled: () -> Boolean,
): WrittenInpaintTensor = writeAtomicTensor(
    target = target,
    expectedBytes = Math.multiplyExact(values.size.toLong(), Float.SIZE_BYTES.toLong()),
    isCancelled = isCancelled,
) { output, digest ->
    val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    fun flush() {
        val count = buffer.position()
        if (count == 0) return
        output.write(buffer.array(), 0, count)
        digest.update(buffer.array(), 0, count)
        buffer.clear()
    }
    values.forEachIndexed { index, value ->
        if (index % 16_384 == 0) throwIfCancelled(isCancelled)
        require(value.isFinite() && value in 0.0f..1.0f) {
            "QNN inpaint mask must remain finite and normalized."
        }
        if (buffer.remaining() < Float.SIZE_BYTES) flush()
        buffer.putFloat(value)
    }
    flush()
}

private fun writeMaskedRgbTensorAtomic(
    sourceTensor: File,
    expectedSourceSha256: String,
    fullResolutionMask: FloatArray,
    target: File,
    isCancelled: () -> Boolean,
): WrittenInpaintTensor {
    require(fullResolutionMask.all { it.isFinite() && it in 0.0f..1.0f })
    val expectedElements = Math.multiplyExact(fullResolutionMask.size.toLong(), 3L)
    val expectedBytes = Math.multiplyExact(expectedElements, Float.SIZE_BYTES.toLong())
    require(sourceTensor.isFile && sourceTensor.length() == expectedBytes) {
        "QNN source RGB tensor changed before masked-image preprocessing."
    }
    return writeAtomicTensor(target, expectedBytes, isCancelled) { output, outputDigest ->
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        FileInputStream(sourceTensor).use { input ->
            val inputBytes = ByteArray(64 * 1024)
            var elementOffset = 0L
            while (elementOffset < expectedElements) {
                throwIfCancelled(isCancelled)
                val chunkElements = minOf(inputBytes.size / Float.SIZE_BYTES, (expectedElements - elementOffset).toInt())
                val chunkBytes = chunkElements * Float.SIZE_BYTES
                readFully(input, inputBytes, chunkBytes)
                sourceDigest.update(inputBytes, 0, chunkBytes)
                val sourceBuffer = ByteBuffer.wrap(inputBytes, 0, chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                val outputBuffer = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                repeat(chunkElements) { localIndex ->
                    val sourceValue = sourceBuffer.float
                    require(sourceValue.isFinite() && sourceValue in -1.000001f..1.000001f) {
                        "QNN source RGB tensor contains an invalid value."
                    }
                    val pixelIndex = ((elementOffset + localIndex) % fullResolutionMask.size).toInt()
                    outputBuffer.putFloat(sourceValue * (1.0f - fullResolutionMask[pixelIndex]))
                }
                output.write(outputBuffer.array(), 0, chunkBytes)
                outputDigest.update(outputBuffer.array(), 0, chunkBytes)
                elementOffset += chunkElements
            }
            require(input.read() == -1) { "QNN source RGB tensor grew during preprocessing." }
        }
        require(sourceDigest.digest().toHex() == expectedSourceSha256) {
            "QNN source RGB tensor digest changed during masked-image preprocessing."
        }
    }
}

private fun writeAtomicTensor(
    target: File,
    expectedBytes: Long,
    isCancelled: () -> Boolean,
    write: (FileOutputStream, MessageDigest) -> Unit,
): WrittenInpaintTensor {
    target.parentFile?.mkdirs()
    require(target.parentFile?.isDirectory == true) { "Unable to create QNN inpaint tensor directory." }
    val temporary = File(target.parentFile, target.name + ".part")
    runCatching { temporary.delete() }
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        FileOutputStream(temporary).use { output ->
            write(output, digest)
            output.fd.sync()
        }
        require(temporary.length() == expectedBytes) {
            "QNN inpaint tensor byte size does not match its declared shape."
        }
        throwIfCancelled(isCancelled)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return WrittenInpaintTensor(target, digest.digest().toHex(), expectedBytes)
    } catch (error: Throwable) {
        runCatching { temporary.delete() }
        runCatching { target.delete() }
        throw error
    }
}

private fun validatePreparedSource(input: LocalImagePreparedInput, role: String): File {
    val source = File(input.path).canonicalFile
    require(source.isFile && source.canRead()) { "Prepared QNN $role image is not readable." }
    require(source.length() == input.sizeBytes && source.length() in 1..LocalImagePreparedInput.MAX_INPUT_BYTES) {
        "Prepared QNN $role image size changed before preprocessing."
    }
    require(sdxlArtifactSha256(source) == input.sha256) {
        "Prepared QNN $role image digest changed before preprocessing."
    }
    return source
}

private fun requireDistinctSiblingTargets(vararg targets: File) {
    val canonicalTargets = targets.map { it.canonicalFile }
    require(canonicalTargets.map { it.path }.distinct().size == canonicalTargets.size) {
        "QNN inpaint artifacts must use distinct files."
    }
    val parent = canonicalTargets.first().parentFile
    require(parent != null && canonicalTargets.all { it.parentFile == parent }) {
        "QNN inpaint artifacts must share one request-private directory."
    }
    require(canonicalTargets.none { Files.isSymbolicLink(it.toPath()) }) {
        "QNN inpaint artifact paths must not be symbolic links."
    }
}

private fun decodeSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

private fun centerCropRect(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Rect {
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

private fun orient(source: Bitmap, orientation: Int): Bitmap {
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
    val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (transformed !== source) source.recycle()
    return transformed
}

private fun readOrientation(file: File): Int = try {
    ExifInterface(file.path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    ).takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 }
        ?: ExifInterface.ORIENTATION_NORMAL
} catch (_: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun readFully(input: FileInputStream, bytes: ByteArray, count: Int) {
    var offset = 0
    while (offset < count) {
        val read = input.read(bytes, offset, count - offset)
        require(read > 0) { "QNN source RGB tensor ended before its declared size." }
        offset += read
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun throwIfCancelled(isCancelled: () -> Boolean) {
    check(!isCancelled()) { "QNN inpaint preprocessing was cancelled." }
}
