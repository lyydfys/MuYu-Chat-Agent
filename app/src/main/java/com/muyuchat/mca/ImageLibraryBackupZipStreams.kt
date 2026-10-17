package com.muyuchat.mca

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ImageLibraryBackupZipInput(input: InputStream) : Closeable {
    private val compressedInput = BoundedCompressedBackupInputStream(
        input = input,
        maxBytes = ImageLibraryBackupLimits.MAX_COMPRESSED_ARCHIVE_BYTES
    )
    private val zip = ZipInputStream(BufferedInputStream(compressedInput))
    private var uncompressedBytes = 0L

    fun nextEntry(): ZipEntry? = zip.nextEntry.also {
        validateImageLibraryBackupCompressionBudget(
            compressedBytes = compressedInput.bytesRead,
            uncompressedBytes = uncompressedBytes
        )
    }

    fun read(buffer: ByteArray): Int {
        val read = zip.read(buffer)
        if (read > 0) {
            uncompressedBytes = safeAddBackupBytes(uncompressedBytes, read.toLong())
            validateImageLibraryBackupCompressionBudget(
                compressedBytes = compressedInput.bytesRead,
                uncompressedBytes = uncompressedBytes
            )
        }
        return read
    }

    fun closeEntry() = zip.closeEntry()

    override fun close() = zip.close()
}

private class BoundedCompressedBackupInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) recordRead(1L)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remainingWithSentinel = maxBytes - bytesRead + 1L
        if (remainingWithSentinel <= 0L) {
            throw ImageLibraryBackupFormatException("备份压缩文件超过大小限制")
        }
        val boundedLength = minOf(length.toLong(), remainingWithSentinel).toInt()
        val read = super.read(buffer, offset, boundedLength)
        if (read > 0) recordRead(read.toLong())
        return read
    }

    private fun recordRead(count: Long) {
        bytesRead = safeAddBackupBytes(bytesRead, count)
        if (bytesRead > maxBytes) {
            throw ImageLibraryBackupFormatException("备份压缩文件超过大小限制")
        }
    }
}

internal suspend fun readBounded(input: ImageLibraryBackupZipInput, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw ImageLibraryBackupFormatException("备份清单过大")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal suspend fun discardBounded(input: ImageLibraryBackupZipInput, maxBytes: Long): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        total = safeAddBackupBytes(total, read.toLong())
        if (total > maxBytes) {
            throw ImageLibraryBackupFormatException("备份图片超过单文件大小限制")
        }
    }
    return total
}
