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

internal fun parseImageLibraryBackupManifest(json: JSONObject): ParsedImageLibraryBackupManifest {
    if (json.optString("format") != FORMAT) {
        throw ImageLibraryBackupFormatException("无法识别的图片库备份格式")
    }
    val version = json.requiredBackupLong("version")
    if (version !in 1..VERSION.toLong()) {
        throw ImageLibraryBackupFormatException("备份版本不受支持")
    }
    val itemsJson = json.optJSONArray("items")
        ?: throw ImageLibraryBackupFormatException("备份清单缺少 items")
    if (itemsJson.length() > MAX_BACKUP_ITEMS) {
        throw ImageLibraryBackupFormatException("备份条目数量超过限制")
    }
    val items = mutableListOf<ImageLibraryBackupManifestItem>()
    val declaredEntryPaths = linkedSetOf<String>()
    var invalidItems = 0
    for (index in 0 until itemsJson.length()) {
        val raw = itemsJson.optJSONObject(index)
        (raw?.opt("entryPath") as? String)
            ?.takeIf { path ->
                path.length <= MAX_ENTRY_PATH_CHARS &&
                    IMAGE_ENTRY_REGEX.matches(path) &&
                    runCatching { validateArchiveEntryName(path) }.isSuccess
            }
            ?.let(declaredEntryPaths::add)
        val item = runCatching {
            val itemJson = requireNotNull(raw)
            val id = itemJson.requiredBackupString("id", MAX_ID_CHARS)
            require(BACKUP_ID_REGEX.matches(id))
            val entryPath = itemJson.requiredBackupString("entryPath", MAX_ENTRY_PATH_CHARS)
            val match = IMAGE_ENTRY_REGEX.matchEntire(entryPath) ?: error("invalid entry path")
            require(match.groupValues[1] == id)
            val extension = match.groupValues[2]
            val sha256 = itemJson.requiredBackupString("sha256", 64).lowercase()
            require(SHA256_REGEX.matches(sha256))
            val byteSize = itemJson.requiredBackupLong("byteSize")
            require(byteSize in 1..MAX_IMAGE_BYTES)
            val name = itemJson.requiredBackupString("name", MAX_NAME_CHARS)
            val source = itemJson.requiredBackupString("source", MAX_SOURCE_CHARS)
            val prompt = itemJson.requiredBackupString("prompt", MAX_PROMPT_CHARS, allowEmpty = true)
            val createdAt = itemJson.requiredBackupLong("createdAt")
            require(createdAt > 0L)
            val width = itemJson.requiredBackupInt("width")
            val height = itemJson.requiredBackupInt("height")
            require(width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE)
            require(width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS)
            val favorite = itemJson.requiredBackupBoolean("favorite")
            val metadata = itemJson.optJSONObject("generationMetadata")
                ?.toString()
                ?.let(ImageGenerationHistoryMetadata::fromJsonOrNull)
            ImageLibraryBackupManifestItem(
                id = id,
                entryPath = entryPath,
                extension = extension,
                sha256 = sha256,
                byteSize = byteSize,
                name = name,
                source = source,
                prompt = prompt,
                createdAt = createdAt,
                width = width,
                height = height,
                generationMetadataJson = metadata?.toPortableBackupJsonString().orEmpty(),
                generationModelId = metadata?.modelId,
                favorite = favorite
            )
        }.getOrNull()
        if (item == null) invalidItems++ else items += item
    }
    return ParsedImageLibraryBackupManifest(
        items = items,
        invalidItems = invalidItems,
        declaredEntryPaths = declaredEntryPaths
    )
}

private fun JSONObject.requiredBackupString(
    name: String,
    maxChars: Int,
    allowEmpty: Boolean = false
): String {
    if (!has(name) || isNull(name) || get(name) !is String) error("$name must be a string")
    val value = getString(name)
    require(value.length <= maxChars)
    if (!allowEmpty) require(value.isNotBlank())
    require('\u0000' !in value)
    return value
}

private fun JSONObject.requiredBackupLong(name: String): Long {
    if (!has(name) || isNull(name) || get(name) !is Number) error("$name must be an integer")
    val value = get(name) as Number
    val doubleValue = value.toDouble()
    require(doubleValue.isFinite() && doubleValue % 1.0 == 0.0)
    val longValue = value.toLong()
    require(longValue.toDouble() == doubleValue)
    return longValue
}

private fun JSONObject.requiredBackupInt(name: String): Int {
    val value = requiredBackupLong(name)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return value.toInt()
}

private fun JSONObject.requiredBackupBoolean(name: String): Boolean {
    if (!has(name) || isNull(name) || get(name) !is Boolean) error("$name must be a boolean")
    return getBoolean(name)
}


internal fun safeAddBackupBytes(current: Long, next: Long): Long {
    if (next < 0L || current > Long.MAX_VALUE - next) {
        throw ImageLibraryBackupFormatException("备份大小字段无效")
    }
    return current + next
}

private const val MAX_ID_CHARS = 36
private val BACKUP_ID_REGEX =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
