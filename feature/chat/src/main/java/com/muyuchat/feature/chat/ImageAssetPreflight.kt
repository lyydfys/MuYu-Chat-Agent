package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * 生图输入图片的提交前体检。
 *
 * 失败链路（用户可见 `image_assets/... open failed: ENOENT`）的真实原因是：用户选中/历史恢复的
 * 输入图在真正读取前就已经被删除或权限失效，任务却照常入队，最终以一条原生异常收尾；重试再次读取
 * 同一个失效引用，于是永远失败。这里把「引用是否存在、可读、非空、未超限、格式可解码、尺寸有效」
 * 做成一次纯函数判定，并给出可操作的中文原因；调用方在入队/重试之前执行它，而不是等 worker 打开
 * 文件时才失败。
 *
 * 纯函数部分不依赖 Android：文件系统/ContentProvider 由 [ImageAssetProbe] 适配进来，因此 JVM 测试
 * 可以覆盖每个分支；[preflightGenerationImageReference] 只是把 File / ContentResolver 探针接上去。
 */

/** 体检结论。除 [OK] 外都带一个明确的用户可行动原因。 */
internal enum class ImageAssetPreflightReason {
    OK,
    MISSING,
    NOT_READABLE,
    EMPTY,
    TOO_LARGE,
    UNSUPPORTED_FORMAT,
    INVALID_DIMENSIONS,
}

/** 体检上限：与应用输入暂存合同的 32 MiB 对齐；边长上限防止把超大图整图解码。 */
internal data class ImageAssetPreflightLimits(
    val maxBytes: Long = 32L * 1024L * 1024L,
    val maxSide: Int = 8_192,
) {
    init {
        require(maxBytes > 0L) { "Image preflight maxBytes must be positive." }
        require(maxSide > 0) { "Image preflight maxSide must be positive." }
    }
}

/** 纯探针结果；[dimensionsKnown] 为 false 时跳过尺寸判断（例如 data URL 未解码）。 */
internal data class ImageAssetProbe(
    val exists: Boolean,
    val canRead: Boolean,
    val sizeBytes: Long,
    val header: ByteArray = ByteArray(0),
    val width: Int = 0,
    val height: Int = 0,
    val dimensionsKnown: Boolean = true,
)

/** 输入引用在 UI 上的角色，用于把原因写进对应字段。 */
internal enum class ImageInputRole(val label: String, val fieldLabel: String) {
    INPUT("原图", "输入图片"),
    MASK("蒙版", "蒙版图片"),
    CONTROL("控制图", "控制图片"),
}

internal fun imageAssetPreflightReasonMessage(
    reason: ImageAssetPreflightReason,
    role: ImageInputRole = ImageInputRole.INPUT,
): String? = when (reason) {
    ImageAssetPreflightReason.OK -> null
    ImageAssetPreflightReason.MISSING -> "${role.fieldLabel}已丢失，请重新选择图片。"
    ImageAssetPreflightReason.NOT_READABLE -> "${role.fieldLabel}无法读取，请重新选择图片。"
    ImageAssetPreflightReason.EMPTY -> "${role.fieldLabel}是空文件，请重新选择图片。"
    ImageAssetPreflightReason.TOO_LARGE -> "${role.fieldLabel}超过 32 MiB，请换一张较小的图片。"
    ImageAssetPreflightReason.UNSUPPORTED_FORMAT ->
        "${role.fieldLabel}格式不受支持，请选择 JPG、PNG 或 WebP 图片。"
    ImageAssetPreflightReason.INVALID_DIMENSIONS ->
        "${role.fieldLabel}尺寸无效或超出支持范围，请重新选择图片。"
}

/** 体检核心：顺序固定，先回答“有没有文件”，再回答“能不能读/是否可用”。 */
internal fun classifyImageAssetProbe(
    probe: ImageAssetProbe,
    limits: ImageAssetPreflightLimits = ImageAssetPreflightLimits(),
): ImageAssetPreflightReason {
    if (!probe.exists) return ImageAssetPreflightReason.MISSING
    if (!probe.canRead) return ImageAssetPreflightReason.NOT_READABLE
    if (probe.sizeBytes <= 0L) return ImageAssetPreflightReason.EMPTY
    if (probe.sizeBytes > limits.maxBytes) return ImageAssetPreflightReason.TOO_LARGE
    if (detectImageHeaderFormat(probe.header) == null) {
        return ImageAssetPreflightReason.UNSUPPORTED_FORMAT
    }
    if (probe.dimensionsKnown) {
        if (probe.width <= 0 || probe.height <= 0) return ImageAssetPreflightReason.INVALID_DIMENSIONS
        if (probe.width > limits.maxSide || probe.height > limits.maxSide) {
            return ImageAssetPreflightReason.INVALID_DIMENSIONS
        }
    }
    return ImageAssetPreflightReason.OK
}

internal enum class ImageAssetFormat { JPEG, PNG, WEBP, GIF, HEIF }

/** 只看文件头，不解码像素。未知格式返回 null。 */
internal fun detectImageHeaderFormat(header: ByteArray): ImageAssetFormat? {
    fun at(index: Int): Int = header.getOrNull(index)?.toInt()?.and(0xff) ?: return -1
    fun ascii(start: Int, length: Int): String? {
        if (header.size < start + length) return null
        return buildString(length) {
            for (offset in start until start + length) {
                append((header[offset].toInt() and 0xff).toChar())
            }
        }
    }
    if (header.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) {
        return ImageAssetFormat.JPEG
    }
    if (header.size >= 8 && at(0) == 0x89 && ascii(1, 3) == "PNG" &&
        at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A
    ) {
        return ImageAssetFormat.PNG
    }
    if (header.size >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") {
        return ImageAssetFormat.WEBP
    }
    if (header.size >= 6 && (ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a")) {
        return ImageAssetFormat.GIF
    }
    if (header.size >= 12 && ascii(4, 4) == "ftyp") {
        val brand = ascii(8, 4)?.lowercase(Locale.US)
        if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) {
            return ImageAssetFormat.HEIF
        }
    }
    return null
}

/** data URL 的体检：mime 决定格式，base64 长度决定估算字节数；像素尺寸未知。 */
internal fun classifyImageDataUrlReference(
    reference: String,
    limits: ImageAssetPreflightLimits = ImageAssetPreflightLimits(),
): ImageAssetPreflightReason {
    val match = DATA_IMAGE_REFERENCE.matchEntire(reference.trim())
        ?: return ImageAssetPreflightReason.UNSUPPORTED_FORMAT
    val mime = match.groupValues[1].lowercase(Locale.US)
    val supportedMime = mime in setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/heic",
        "image/heif",
        "image/avif",
    )
    if (!supportedMime) return ImageAssetPreflightReason.UNSUPPORTED_FORMAT
    val encoded = match.groupValues[2]
    if (encoded.isBlank()) return ImageAssetPreflightReason.EMPTY
    val estimatedBytes = encoded.length.toLong() * 3L / 4L
    if (estimatedBytes > limits.maxBytes) return ImageAssetPreflightReason.TOO_LARGE
    return ImageAssetPreflightReason.OK
}

/**
 * Android 侧探针：把 File / content:// / data:image 引用转成 [ImageAssetProbe] 后交给纯函数。
 *
 * 只做文件头与尺寸探测（`inJustDecodeBounds`），不解码整图；content:// 流即使无法打开也只会返回
 * NOT_READABLE，不会把 provider 的原生异常写到用户提示里。
 */
internal fun preflightGenerationImageReference(
    context: Context,
    reference: String?,
    limits: ImageAssetPreflightLimits = ImageAssetPreflightLimits(),
): ImageAssetPreflightReason {
    val value = reference?.trim().orEmpty()
    if (value.isEmpty()) return ImageAssetPreflightReason.OK
    if (DATA_IMAGE_REFERENCE.matches(value)) return classifyImageDataUrlReference(value, limits)
    val uri = runCatching { Uri.parse(value) }.getOrNull()
        ?: return ImageAssetPreflightReason.UNSUPPORTED_FORMAT
    val appContext = context.applicationContext
    return when (uri.scheme?.lowercase(Locale.US)) {
        "content" -> classifyImageAssetProbe(
            probe = probeContentImage(appContext, uri, limits),
            limits = limits,
        )
        "file" -> classifyImageAssetProbe(
            probe = probeFileImage(File(uri.path.orEmpty())),
            limits = limits,
        )
        null, "" -> {
            val file = File(value)
            if (!file.isAbsolute) ImageAssetPreflightReason.UNSUPPORTED_FORMAT
            else classifyImageAssetProbe(probeFileImage(file), limits)
        }
        else -> ImageAssetPreflightReason.UNSUPPORTED_FORMAT
    }
}

private fun probeFileImage(file: File): ImageAssetProbe {
    val exists = file.exists()
    val canRead = exists && file.isFile && file.canRead()
    if (!canRead) {
        return ImageAssetProbe(
            exists = exists,
            canRead = false,
            sizeBytes = 0L,
            dimensionsKnown = false,
        )
    }
    val header = runCatching {
        file.inputStream().use { stream -> readHeaderBytes(stream) }
    }.getOrDefault(ByteArray(0))
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
    return ImageAssetProbe(
        exists = true,
        canRead = true,
        sizeBytes = file.length(),
        header = header,
        width = bounds.outWidth,
        height = bounds.outHeight,
        dimensionsKnown = bounds.outWidth > 0 && bounds.outHeight > 0,
    )
}

private fun probeContentImage(
    context: Context,
    uri: Uri,
    limits: ImageAssetPreflightLimits,
): ImageAssetProbe {
    val resolver = context.contentResolver
    val header = runCatching {
        resolver.openInputStream(uri)?.use { stream -> readHeaderBytes(stream) }
    }.getOrNull()
    if (header == null) {
        return ImageAssetProbe(
            exists = true,
            canRead = false,
            sizeBytes = 0L,
            dimensionsKnown = false,
        )
    }
    val declaredSize = queryContentSize(context, uri)
        ?: runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length }
        }.getOrNull()
        ?.takeIf { it > 0L }
    val sizeBytes = declaredSize ?: runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            countStreamBytes(stream, stopAfter = limits.maxBytes + 1L)
        }
    }.getOrNull() ?: 0L
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
    }
    return ImageAssetProbe(
        exists = true,
        canRead = true,
        sizeBytes = sizeBytes,
        header = header,
        width = bounds.outWidth,
        height = bounds.outHeight,
        dimensionsKnown = bounds.outWidth > 0 && bounds.outHeight > 0,
    )
}

private fun queryContentSize(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
}.getOrNull()

private fun readHeaderBytes(stream: InputStream): ByteArray {
    val buffer = ByteArray(HEADER_BYTES)
    var read = 0
    while (read < buffer.size) {
        val count = stream.read(buffer, read, buffer.size - read)
        if (count <= 0) break
        read += count
    }
    return buffer.copyOf(read)
}

/** Counts bytes until [stopAfter]; a larger file is reported as at least that size (TOO_LARGE). */
private fun countStreamBytes(stream: InputStream, stopAfter: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (total < stopAfter) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read.toLong()
    }
    return total
}

private const val HEADER_BYTES = 16
private val DATA_IMAGE_REFERENCE = Regex(
    pattern = "^data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]*)$",
    option = RegexOption.IGNORE_CASE,
)
