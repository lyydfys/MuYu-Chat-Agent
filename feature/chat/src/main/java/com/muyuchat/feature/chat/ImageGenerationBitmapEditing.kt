package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val GENERATION_IMAGE_INPUT_DIRECTORY = "image_generation_inputs"
internal const val GENERATION_IMAGE_INPUT_PROVIDER_PATH = "generation_inputs"
internal const val GENERATION_IMAGE_INPUT_GRACE_MILLIS = 24L * 60L * 60L * 1_000L

private const val MAX_EDITABLE_IMAGE_PIXELS = 16_000_000L
private const val MAX_EDITABLE_IMAGE_EDGE = 8_192
internal const val MAX_EDITABLE_ENCODED_IMAGE_BYTES = 32L * 1_024L * 1_024L
private val OWNED_INPUT_FILE_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png$")
private val OWNED_INPUT_TEMPORARY_FILE_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png\\.tmp$")

internal data class DecodedGenerationImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sampleSize: Int
)

internal data class GenerationImageDimensions(
    val width: Int,
    val height: Int
)

internal data class GenerationInpaintOwnedUris(
    val inputUri: String,
    val maskUri: String
)

internal data class GenerationOwnedInputFileSnapshot(
    val name: String,
    val isFile: Boolean,
    val lastModifiedMillis: Long
)

internal data class ExifOrientationTransform(
    val rotationDegrees: Int,
    val flipHorizontalAfterRotation: Boolean
) {
    init {
        require(rotationDegrees in setOf(-90, 0, 90, 180)) {
            "Unsupported EXIF rotation."
        }
    }

    fun outputWidth(sourceWidth: Int, sourceHeight: Int): Int =
        if (rotationDegrees == 90 || rotationDegrees == -90) sourceHeight else sourceWidth

    fun outputHeight(sourceWidth: Int, sourceHeight: Int): Int =
        if (rotationDegrees == 90 || rotationDegrees == -90) sourceWidth else sourceHeight

    fun mapSourcePixel(
        x: Int,
        y: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0 && x in 0 until sourceWidth && y in 0 until sourceHeight) {
            "Source pixel must fit the image."
        }
        val rotated = when (rotationDegrees) {
            90 -> sourceHeight - 1 - y to x
            -90 -> y to sourceWidth - 1 - x
            180 -> sourceWidth - 1 - x to sourceHeight - 1 - y
            else -> x to y
        }
        return if (flipHorizontalAfterRotation) {
            outputWidth(sourceWidth, sourceHeight) - 1 - rotated.first to rotated.second
        } else {
            rotated
        }
    }
}

/**
 * Android Bitmap/Canvas adapter for the immutable editing core. Inputs are normalized to an
 * orientation=1, bounded ARGB bitmap before editing so a hostile or very large document cannot
 * make the editor retain an unbounded full-resolution allocation.
 */
internal object ImageGenerationBitmapEditing {
    fun probeDimensionsBounded(
        context: Context,
        rawUri: String,
        checkCancelled: () -> Unit = {}
    ): GenerationImageDimensions {
        val uri = requireContentUri(rawUri)
        val appContext = context.applicationContext
        val snapshotDirectory = File(appContext.cacheDir, "image_generation_editor")
        snapshotDirectory.mkdirs()
        check(snapshotDirectory.isDirectory) { "无法创建图片编辑临时目录。" }
        val snapshot = File.createTempFile("probe-", ".image", snapshotDirectory)
        try {
            checkCancelled()
            val copiedBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(snapshot).use { output ->
                    val copied = copyGenerationImageSnapshot(
                        input = input,
                        output = output,
                        checkCancelled = checkCancelled
                    )
                    output.flush()
                    output.fd.sync()
                    copied
                }
            } ?: error("无法读取图片。")
            require(isGenerationImageEncodedFileSizeAllowed(copiedBytes)) {
                "图片文件必须为 1 字节到 32 MiB。"
            }

            checkCancelled()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(snapshot.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片尺寸。" }
            val orientation = try {
                ExifInterface(snapshot.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            checkCancelled()
            val transform = exifOrientationTransform(orientation)
            return GenerationImageDimensions(
                width = transform.outputWidth(bounds.outWidth, bounds.outHeight),
                height = transform.outputHeight(bounds.outWidth, bounds.outHeight)
            )
        } finally {
            snapshot.delete()
        }
    }

    fun decodeBounded(
        context: Context,
        rawUri: String,
        checkCancelled: () -> Unit = {}
    ): DecodedGenerationImage {
        val uri = requireContentUri(rawUri)
        val appContext = context.applicationContext
        val snapshotDirectory = File(appContext.cacheDir, "image_generation_editor")
        snapshotDirectory.mkdirs()
        check(snapshotDirectory.isDirectory) { "无法创建图片编辑临时目录。" }
        val snapshot = File.createTempFile("source-", ".image", snapshotDirectory)
        var ownedBitmap: Bitmap? = null
        try {
            checkCancelled()
            val copiedBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(snapshot).use { output ->
                    val copied = copyGenerationImageSnapshot(
                        input = input,
                        output = output,
                        checkCancelled = checkCancelled
                    )
                    output.flush()
                    output.fd.sync()
                    copied
                }
            } ?: error("无法读取图片。")
            require(isGenerationImageEncodedFileSizeAllowed(copiedBytes)) {
                "图片文件必须为 1 字节到 32 MiB。"
            }

            checkCancelled()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(snapshot.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片尺寸。" }
            val sampleSize = boundedSampleSize(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            checkCancelled()
            val decoded = BitmapFactory.decodeFile(snapshot.absolutePath, options)
                ?: error("无法解码图片。")
            ownedBitmap = decoded
            checkCancelled()
            val orientation = try {
                ExifInterface(snapshot.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val oriented = orient(decoded, orientation)
            ownedBitmap = oriented
            checkCancelled()
            check(
                oriented.width <= MAX_EDITABLE_IMAGE_EDGE &&
                    oriented.height <= MAX_EDITABLE_IMAGE_EDGE &&
                    oriented.width.toLong() * oriented.height.toLong() <= MAX_EDITABLE_IMAGE_PIXELS
            ) { "图片超过编辑器内存上限。" }
            return DecodedGenerationImage(
                bitmap = oriented,
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                sampleSize = sampleSize
            ).also { ownedBitmap = null }
        } finally {
            ownedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            snapshot.delete()
        }
    }

    fun crop(
        bitmap: Bitmap,
        crop: NormalizedImageRect,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val pixels = crop.toExactTargetAspectPixelRectOrNull(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
            ?: error("裁剪区域太小。")
        return Bitmap.createBitmap(
            bitmap,
            pixels.left,
            pixels.top,
            pixels.width,
            pixels.height
        )
    }

    fun renderMask(
        imageWidth: Int,
        imageHeight: Int,
        state: ImageMaskEditingState
    ): Bitmap {
        require(imageWidth > 0 && imageHeight > 0) { "蒙版尺寸必须为正数。" }
        require(
            imageWidth <= MAX_EDITABLE_IMAGE_EDGE &&
                imageHeight <= MAX_EDITABLE_IMAGE_EDGE &&
                imageWidth.toLong() * imageHeight.toLong() <= MAX_EDITABLE_IMAGE_PIXELS
        ) { "蒙版超过编辑器内存上限。" }
        val mask = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        canvas.drawColor(grayscaleColor(state.baseGrayscaleValue))
        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        state.strokes.forEach { stroke ->
            paint.color = grayscaleColor(stroke.mode.grayscaleValue)
            paint.strokeWidth = (stroke.radiusInPixels(imageWidth, imageHeight) * 2.0).toFloat()
            val points = stroke.points
            if (points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(
                    (points.first().x * imageWidth).toFloat(),
                    (points.first().y * imageHeight).toFloat(),
                    stroke.radiusInPixels(imageWidth, imageHeight).toFloat(),
                    paint
                )
                paint.style = Paint.Style.STROKE
            } else {
                points.zipWithNext().forEach { (from, to) ->
                    canvas.drawLine(
                        (from.x * imageWidth).toFloat(),
                        (from.y * imageHeight).toFloat(),
                        (to.x * imageWidth).toFloat(),
                        (to.y * imageHeight).toFloat(),
                        paint
                    )
                }
            }
        }
        return mask
    }

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val transform = exifOrientationTransform(orientation)
        if (transform.rotationDegrees == 0 && !transform.flipHorizontalAfterRotation) return source
        val matrix = Matrix().apply {
            setRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontalAfterRotation) postScale(-1f, 1f)
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
}

/** Stores only UUID-named PNGs under filesDir/image_generation_inputs. */
internal class GenerationImageOwnedInputStore(context: Context) {
    private val appContext = context.applicationContext
    private val authority = appContext.packageName + ".fileprovider"
    private val root = File(appContext.filesDir, GENERATION_IMAGE_INPUT_DIRECTORY)

    fun writeBitmap(bitmap: Bitmap): String {
        require(bitmap.width > 0 && bitmap.height > 0) { "图片尺寸必须为正数。" }
        root.mkdirs()
        check(root.isDirectory) { "无法创建图片编辑目录。" }
        val finalFile = File(root, UUID.randomUUID().toString() + ".png")
        val temporaryFile = File(root, finalFile.name + ".tmp")
        var completed = false
        try {
            FileOutputStream(temporaryFile).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法编码 PNG 图片。"
                }
                output.flush()
                output.fd.sync()
            }
            require(isGenerationImageEncodedFileSizeAllowed(temporaryFile.length())) {
                "PNG 图片必须为 1 字节到 32 MiB。"
            }
            try {
                Files.move(
                    temporaryFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            val uri = FileProvider.getUriForFile(appContext, authority, finalFile).toString()
            completed = true
            return uri
        } finally {
            if (!completed) {
                temporaryFile.delete()
                finalFile.delete()
            }
        }
    }

    fun writeInpaintPair(input: Bitmap, mask: Bitmap): GenerationInpaintOwnedUris {
        require(input.width == mask.width && input.height == mask.height) {
            "蒙版必须与原图尺寸一致。"
        }
        val inputUri = writeBitmap(input)
        var completed = false
        try {
            val pair = GenerationInpaintOwnedUris(inputUri = inputUri, maskUri = writeBitmap(mask))
            completed = true
            return pair
        } finally {
            if (!completed) deleteOwnedUri(inputUri)
        }
    }

    fun readableOwnedUriOrNull(raw: String?): String? {
        val file = fileForOwnedUriOrNull(appContext, raw) ?: return null
        return raw?.trim()?.takeIf { file.isFile && file.canRead() }
    }

    fun deleteOwnedUri(raw: String): Boolean =
        fileForOwnedUriOrNull(appContext, raw)?.delete() == true

    fun pruneUnreferenced(
        referencedUris: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
        graceMillis: Long = GENERATION_IMAGE_INPUT_GRACE_MILLIS
    ): Int {
        if (!root.isDirectory) return 0
        val referencedNames = referencedUris.mapNotNullTo(mutableSetOf()) { raw ->
            ownedGenerationImageFileNameOrNull(raw, authority)
        }
        val candidates = root.listFiles().orEmpty()
        val deleteNames = generationOwnedInputFileNamesToDelete(
            files = candidates.map { candidate ->
                GenerationOwnedInputFileSnapshot(
                    name = candidate.name,
                    isFile = candidate.isFile,
                    lastModifiedMillis = candidate.lastModified()
                )
            },
            referencedNames = referencedNames,
            nowMillis = nowMillis,
            graceMillis = graceMillis
        )
        return candidates.count { candidate -> candidate.name in deleteNames && candidate.delete() }
    }
}

internal fun ownedGenerationImageFileNameOrNull(
    raw: String?,
    expectedAuthority: String
): String? {
    val uri = raw?.trim()?.takeIf(String::isNotEmpty)?.let {
        try {
            Uri.parse(it)
        } catch (_: Exception) {
            null
        }
    }
        ?: return null
    return ownedGenerationImageFileNameOrNull(
        scheme = uri.scheme,
        authority = uri.authority,
        pathSegments = uri.pathSegments,
        expectedAuthority = expectedAuthority
    )
}

internal fun ownedGenerationImageFileNameOrNull(
    scheme: String?,
    authority: String?,
    pathSegments: List<String>,
    expectedAuthority: String
): String? {
    if (!scheme.equals("content", ignoreCase = true) || authority != expectedAuthority) return null
    if (pathSegments.size != 2 || pathSegments[0] != GENERATION_IMAGE_INPUT_PROVIDER_PATH) return null
    return pathSegments[1].takeIf(OWNED_INPUT_FILE_PATTERN::matches)
}

internal fun isGenerationOwnedInputUri(context: Context, raw: String?): Boolean =
    ownedGenerationImageFileNameOrNull(
        raw = raw,
        expectedAuthority = context.applicationContext.packageName + ".fileprovider"
    ) != null

internal fun isGenerationImageEncodedFileSizeAllowed(sizeBytes: Long): Boolean =
    sizeBytes in 1L..MAX_EDITABLE_ENCODED_IMAGE_BYTES

internal fun copyGenerationImageSnapshot(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_EDITABLE_ENCODED_IMAGE_BYTES,
    checkCancelled: () -> Unit = {}
): Long {
    require(maxBytes > 0L) { "Snapshot byte limit must be positive." }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        checkCancelled()
        val maximumRead = if (copied == maxBytes) {
            1
        } else {
            minOf(buffer.size.toLong(), maxBytes - copied).toInt()
        }
        val read = input.read(buffer, 0, maximumRead)
        checkCancelled()
        if (read < 0) return copied
        if (read == 0) {
            val singleByte = input.read()
            checkCancelled()
            if (singleByte < 0) return copied
            if (copied == maxBytes) {
                throw IllegalArgumentException("图片文件超过 32 MiB 编辑上限。")
            }
            output.write(singleByte)
            copied += 1L
            continue
        }
        if (copied == maxBytes || read.toLong() > maxBytes - copied) {
            throw IllegalArgumentException("图片文件超过 32 MiB 编辑上限。")
        }
        output.write(buffer, 0, read)
        copied += read
    }
}

internal fun exifOrientationTransform(orientation: Int): ExifOrientationTransform =
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifOrientationTransform(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifOrientationTransform(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifOrientationTransform(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifOrientationTransform(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifOrientationTransform(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifOrientationTransform(-90, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifOrientationTransform(-90, false)
        else -> ExifOrientationTransform(0, false)
    }

internal fun generationOwnedInputFileNamesToDelete(
    files: List<GenerationOwnedInputFileSnapshot>,
    referencedNames: Set<String>,
    nowMillis: Long,
    graceMillis: Long = GENERATION_IMAGE_INPUT_GRACE_MILLIS
): Set<String> {
    val cutoff = nowMillis - graceMillis.coerceAtLeast(0L)
    return files.mapNotNullTo(mutableSetOf()) { file ->
        file.name.takeIf {
            file.isFile &&
                (OWNED_INPUT_FILE_PATTERN.matches(file.name) ||
                    OWNED_INPUT_TEMPORARY_FILE_PATTERN.matches(file.name)) &&
                file.name !in referencedNames &&
                file.lastModifiedMillis <= cutoff
        }
    }
}

private fun fileForOwnedUriOrNull(context: Context, raw: String?): File? {
    val name = ownedGenerationImageFileNameOrNull(
        raw = raw,
        expectedAuthority = context.packageName + ".fileprovider"
    ) ?: return null
    val root = File(context.filesDir, GENERATION_IMAGE_INPUT_DIRECTORY)
    val candidate = File(root, name)
    val rootPath = try {
        root.canonicalFile.toPath()
    } catch (_: Exception) {
        return null
    }
    val candidatePath = try {
        candidate.canonicalFile.toPath()
    } catch (_: Exception) {
        return null
    }
    return candidate.takeIf { candidatePath.parent == rootPath }
}

private fun boundedSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (
        ((width + sample - 1L) / sample) > MAX_EDITABLE_IMAGE_EDGE ||
        ((height + sample - 1L) / sample) > MAX_EDITABLE_IMAGE_EDGE ||
        ((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) >
            MAX_EDITABLE_IMAGE_PIXELS
    ) {
        check(sample <= (1 shl 29)) { "图片尺寸无法安全缩放。" }
        sample *= 2
    }
    return sample
}

private fun requireContentUri(raw: String): Uri {
    val uri = try {
        Uri.parse(raw.trim())
    } catch (_: Exception) {
        null
    } ?: throw IllegalArgumentException("图片 URI 无效。")
    require(uri.scheme.equals("content", ignoreCase = true)) { "图片必须使用 content URI。" }
    return uri
}

private fun grayscaleColor(value: Int): Int {
    require(value in 0..255) { "灰度值必须位于 0..255。" }
    return Color.rgb(value, value, value)
}
