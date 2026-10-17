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

internal fun validateImageLibraryBackupCompressionBudget(
    compressedBytes: Long,
    uncompressedBytes: Long
) {
    if (compressedBytes < 0L || uncompressedBytes < 0L) {
        throw ImageLibraryBackupFormatException("备份压缩大小无效")
    }
    if (compressedBytes > ImageLibraryBackupLimits.MAX_COMPRESSED_ARCHIVE_BYTES) {
        throw ImageLibraryBackupFormatException("备份压缩文件超过大小限制")
    }
    if (uncompressedBytes >= ImageLibraryBackupLimits.MIN_RATIO_CHECK_BYTES) {
        val ratioLimit = compressedBytes * ImageLibraryBackupLimits.MAX_COMPRESSION_RATIO
        if (compressedBytes == 0L || uncompressedBytes > ratioLimit) {
            throw ImageLibraryBackupFormatException("备份压缩比超过安全限制")
        }
    }
}

internal fun requiredImageLibraryImportSpaceBytes(expectedImageBytes: Long): Long {
    require(expectedImageBytes in 0L..ImageLibraryBackupLimits.MAX_TOTAL_IMAGE_BYTES) {
        "Expected image bytes are outside the backup import contract."
    }
    val reserve = maxOf(
        ImageLibraryBackupLimits.MIN_FREE_SPACE_RESERVE_BYTES,
        expectedImageBytes / 10L
    )
    return safeAddBackupBytes(expectedImageBytes, reserve)
}

internal fun validateImageLibraryImportSpaceBudget(expectedImageBytes: Long, usableBytes: Long) {
    val requiredBytes = requiredImageLibraryImportSpaceBytes(expectedImageBytes)
    if (usableBytes < requiredBytes) {
        throw IOException("设备可用存储空间不足，无法安全恢复图片备份")
    }
}

internal fun unreferencedRestoredImageFiles(
    root: File,
    referencedCanonicalPaths: Set<String>
): List<File> {
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
    return canonicalRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter { file -> file.isFile && RESTORED_IMAGE_FILE_REGEX.matches(file.name) }
        .mapNotNull { file -> runCatching { file.canonicalFile }.getOrNull() }
        .filter { file ->
            file.parentFile == canonicalRoot && file.path !in referencedCanonicalPaths
        }
        .toList()
}

private val RESTORED_IMAGE_FILE_REGEX = Regex(
    "restored-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
        "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?:-[0-9a-fA-F]{8})?\\." +
        "(?:png|jpg|jpeg|webp|heic|heif)"
)
