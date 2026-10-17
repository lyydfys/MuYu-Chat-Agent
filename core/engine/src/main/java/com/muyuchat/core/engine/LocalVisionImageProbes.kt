package com.muyuchat.core.engine

import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class ImageInspection(
        val sha256: String,
        val format: String,
        val byteCount: Long,
        val width: Int,
        val height: Int
    )

internal fun inspectFile(file: File): ImageInspection {
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

internal data class ImageProbe(val format: String = "unknown", val width: Int = 0, val height: Int = 0)

internal fun probeImage(bytes: ByteArray): ImageProbe = when {
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

internal fun ImageProbe.sanitized(): ImageProbe = copy(
        width = width.takeIf { it > 0 } ?: 0,
        height = height.takeIf { it > 0 } ?: 0
    )

internal fun ByteArray.isPng(): Boolean = size >= 24 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4e.toByte() &&
        this[3] == 0x47.toByte() && this[4] == 0x0d.toByte() && this[5] == 0x0a.toByte() &&
        this[6] == 0x1a.toByte() && this[7] == 0x0a.toByte()

internal fun ByteArray.isGif(): Boolean = size >= 10 &&
        String(this, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")

internal fun ByteArray.isBmp(): Boolean = size >= 26 && this[0] == 'B'.code.toByte() && this[1] == 'M'.code.toByte()

internal fun ByteArray.isWebp(): Boolean = size >= 30 &&
        String(this, 0, 4, Charsets.US_ASCII) == "RIFF" && String(this, 8, 4, Charsets.US_ASCII) == "WEBP"

internal fun ByteArray.isJpeg(): Boolean = size >= 4 && this[0] == 0xff.toByte() && this[1] == 0xd8.toByte()

internal fun ByteArray.probeJpeg(): ImageProbe {
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

internal fun ByteArray.probeWebp(): ImageProbe {
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

internal fun ByteArray.readUShortBigEndian(offset: Int): Int =
        if (offset + 1 < size) ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff) else 0

internal fun ByteArray.readUShortLittleEndian(offset: Int): Int =
        if (offset + 1 < size) (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8) else 0

internal fun ByteArray.readIntBigEndian(offset: Int): Int =
        if (offset + 3 < size) (0..3).fold(0) { value, index -> (value shl 8) or (this[offset + index].toInt() and 0xff) } else 0

internal fun ByteArray.readIntLittleEndian(offset: Int): Int =
        if (offset + 3 < size) (0..3).fold(0) { value, index -> value or ((this[offset + index].toInt() and 0xff) shl (index * 8)) } else 0

internal fun ByteArray.readUInt24LittleEndian(offset: Int): Int =
        if (offset + 2 < size) (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) else 0

internal fun readAndroidImageBounds(file: File): ImageProbe? = runCatching {
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

internal fun readHeaderImageBounds(file: File): ImageProbe {
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

private val JPEG_START_OF_FRAME_MARKERS = setOf(
    0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
    0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf
)
