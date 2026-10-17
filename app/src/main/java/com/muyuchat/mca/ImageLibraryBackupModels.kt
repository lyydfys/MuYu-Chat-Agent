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

internal class ImageLibraryBackupFormatException(message: String) : IOException(message)

internal data class ImageLibraryBackupExportResult(
    val exported: Int,
    val skipped: Int
)

internal data class ImageLibraryBackupImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val missingModelIds: Set<String>
)

internal data class ImageLibraryBackupManifestItem(
    val id: String,
    val entryPath: String,
    val extension: String,
    val sha256: String,
    val byteSize: Long,
    val name: String,
    val source: String,
    val prompt: String,
    val createdAt: Long,
    val width: Int,
    val height: Int,
    val generationMetadataJson: String,
    val generationModelId: String?,
    val favorite: Boolean
)

internal data class ParsedImageLibraryBackupManifest(
    val items: List<ImageLibraryBackupManifestItem>,
    val invalidItems: Int,
    val declaredEntryPaths: Set<String> = items.mapTo(linkedSetOf()) { it.entryPath }
)

internal object ImageLibraryBackupLimits {
    const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    const val MAX_BACKUP_ITEMS = 512
    const val MAX_IMAGE_BYTES = 96L * 1024L * 1024L
    const val MAX_TOTAL_IMAGE_BYTES = 1024L * 1024L * 1024L
    const val MAX_IMAGE_SIDE = 4_096
    const val MAX_IMAGE_PIXELS = 16_777_216L
    const val MAX_PROMPT_CHARS = 16_384
    const val MAX_COMPRESSED_ARCHIVE_BYTES = MAX_TOTAL_IMAGE_BYTES + 16L * 1024L * 1024L
    const val MAX_COMPRESSION_RATIO = 200L
    const val MIN_RATIO_CHECK_BYTES = 4L * 1024L * 1024L
    const val MIN_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
}


internal fun ImageAssetRecord.toBackupManifestJson(
    entryPath: String,
    byteSize: Long,
    sha256: String,
    width: Int,
    height: Int
): JSONObject = JSONObject()
    .put("id", id)
    .put("entryPath", entryPath)
    .put("sha256", sha256)
    .put("byteSize", byteSize)
    .put("name", name.ifBlank { "图片" }.backupBounded("name", MAX_NAME_CHARS))
    .put("source", source.ifBlank { "restored" }.backupBounded("source", MAX_SOURCE_CHARS))
    .put("prompt", prompt.backupBounded("prompt", MAX_PROMPT_CHARS))
    .put("createdAt", createdAt)
    .put("width", width)
    .put("height", height)
    .put("favorite", favorite)
    .apply {
        ImageGenerationHistoryMetadata.fromJsonOrNull(generationMetadataJson)
            ?.toPortableBackupJsonString()
            ?.let(::JSONObject)
            ?.let { put("generationMetadata", it) }
    }

internal fun validateArchiveEntryName(name: String) {
    val safe = name.isNotBlank() &&
        name.length <= MAX_ENTRY_PATH_CHARS &&
        !name.startsWith('/') &&
        !name.startsWith('\\') &&
        '\\' !in name &&
        ':' !in name &&
        name.split('/').none { it == "." || it == ".." }
    if (!safe) throw ImageLibraryBackupFormatException("备份包含不安全路径")
}

private fun String.backupBounded(field: String, maxChars: Int): String {
    if (length > maxChars) throw IOException("$field 超过备份长度限制")
    return this
}
