package com.muyuchat.core.engine

import com.muyuchat.core.engine.PersistentPrefixCacheStore.Companion.FORMAT_VERSION
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal data class StoredEntry(
    val key: PrefixCacheKey,
    val metadataFile: File,
    val metadataFileSizeBytes: Long,
    val stateFile: File,
    val stateSha256: String,
    val stateSizeBytes: Long,
    val createdAtEpochMs: Long,
    val lastAccessEpochMs: Long
) {
    val totalBytes: Long
        get() = saturatedAdd(metadataFileSizeBytes, stateSizeBytes)

    fun toPublicEntry(): PersistentPrefixCacheEntry = PersistentPrefixCacheEntry(
        key = key,
        stateFile = stateFile,
        stateSizeBytes = stateSizeBytes,
        stateSha256 = stateSha256,
        createdAtEpochMs = createdAtEpochMs,
        lastAccessEpochMs = lastAccessEpochMs
    )
}

internal data class CacheMetadata(
    val key: PrefixCacheKey,
    val stateFileName: String,
    val stateSha256: String,
    val stateSizeBytes: Long,
    val createdAtEpochMs: Long,
    val lastAccessEpochMs: Long
) {
    fun serialize(): String = buildString {
        append("format_version=").append(FORMAT_VERSION).append('\n')
        append("cache_key=").append(key.cacheId).append('\n')
        append("model_fingerprint=").append(key.modelFingerprint).append('\n')
        append("tokenizer_fingerprint=").append(key.tokenizerFingerprint).append('\n')
        append("template_fingerprint=").append(key.templateFingerprint).append('\n')
        append("system_prompt_fingerprint=").append(key.systemPromptFingerprint).append('\n')
        append("runtime_fingerprint=").append(key.runtimeFingerprint).append('\n')
        append("prefix_fingerprint=").append(key.prefixFingerprint).append('\n')
        append("state_file=").append(stateFileName).append('\n')
        append("state_sha256=").append(stateSha256).append('\n')
        append("state_size_bytes=").append(stateSizeBytes).append('\n')
        append("created_at_epoch_ms=").append(createdAtEpochMs).append('\n')
        append("last_access_epoch_ms=").append(lastAccessEpochMs).append('\n')
    }

    fun toStored(root: File): StoredEntry? {
        if (!PrefixCacheKey.isSha256Hex(stateSha256) || stateSizeBytes <= 0L ||
            createdAtEpochMs < 0L || lastAccessEpochMs < 0L ||
            stateFileName != stateFileName(key.cacheId, stateSha256)
        ) {
            return null
        }
        val metadataFile = safeMetadataFile(root, key.cacheId) ?: return null
        val stateFile = safeStateFile(root, stateFileName) ?: return null
        val metadataSize = regularSize(metadataFile)
        if (metadataSize <= 0L || metadataSize > MAX_METADATA_BYTES) return null
        return StoredEntry(
            key = key,
            metadataFile = metadataFile,
            metadataFileSizeBytes = metadataSize,
            stateFile = stateFile,
            stateSha256 = stateSha256,
            stateSizeBytes = stateSizeBytes,
            createdAtEpochMs = createdAtEpochMs,
            lastAccessEpochMs = lastAccessEpochMs
        )
    }

    companion object {
        fun parse(document: String): CacheMetadata? {
            if (document.isEmpty() || !document.endsWith('\n') || '\r' in document) return null
            val fields = LinkedHashMap<String, String>()
            document.dropLast(1).split('\n').forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0 || separator != line.lastIndexOf('=')) return null
                val field = line.substring(0, separator)
                val value = line.substring(separator + 1)
                if (field !in METADATA_FIELD_ORDER || fields.put(field, value) != null) return null
            }
            if (fields.keys.toList() != METADATA_FIELD_ORDER) return null
            if (fields["format_version"] != FORMAT_VERSION.toString()) return null

            val key = try {
                val cacheId = fields.getValue("cache_key")
                PrefixCacheKey(
                    modelFingerprint = fields.getValue("model_fingerprint"),
                    tokenizerFingerprint = fields.getValue("tokenizer_fingerprint"),
                    templateFingerprint = fields.getValue("template_fingerprint"),
                    systemPromptFingerprint = fields.getValue("system_prompt_fingerprint"),
                    runtimeFingerprint = fields.getValue("runtime_fingerprint"),
                    prefixFingerprint = fields.getValue("prefix_fingerprint")
                ).takeIf { it.cacheId == cacheId }
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null

            val stateSha256 = fields.getValue("state_sha256")
            val stateSize = parseNonNegativeLong(fields.getValue("state_size_bytes")) ?: return null
            val createdAt = parseNonNegativeLong(fields.getValue("created_at_epoch_ms")) ?: return null
            val lastAccess = parseNonNegativeLong(fields.getValue("last_access_epoch_ms")) ?: return null
            return CacheMetadata(
                key = key,
                stateFileName = fields.getValue("state_file"),
                stateSha256 = stateSha256,
                stateSizeBytes = stateSize,
                createdAtEpochMs = createdAt,
                lastAccessEpochMs = lastAccess
            )
        }
    }
}

internal const val IO_BUFFER_BYTES = 32 * 1024

internal const val MAX_METADATA_BYTES = 16L * 1024L

private val SHA256_FILE_COMPONENT = "[0-9a-f]{64}"

internal val METADATA_FILE_NAME = Regex("^$SHA256_FILE_COMPONENT\\.meta$")

internal val STATE_FILE_NAME = Regex("^$SHA256_FILE_COMPONENT\\.$SHA256_FILE_COMPONENT\\.state$")

internal val TEMP_FILE_NAME = Regex("^\\.$SHA256_FILE_COMPONENT-[A-Za-z0-9._-]+\\.tmp$")

private val NON_NEGATIVE_LONG = Regex("^(0|[1-9][0-9]*)$")

private val METADATA_FIELD_ORDER = listOf(
    "format_version",
    "cache_key",
    "model_fingerprint",
    "tokenizer_fingerprint",
    "template_fingerprint",
    "system_prompt_fingerprint",
    "runtime_fingerprint",
    "prefix_fingerprint",
    "state_file",
    "state_sha256",
    "state_size_bytes",
    "created_at_epoch_ms",
    "last_access_epoch_ms"
)

internal fun metadataFileName(cacheId: String): String = "$cacheId.meta"

internal fun stateFileName(cacheId: String, stateSha256: String): String =
    "$cacheId.$stateSha256.state"

private fun safeMetadataFile(root: File, cacheId: String): File? =
    safeChildStatic(root, metadataFileName(cacheId), METADATA_FILE_NAME)

private fun safeStateFile(root: File, name: String): File? =
    safeChildStatic(root, name, STATE_FILE_NAME)

private fun safeChildStatic(root: File, name: String, pattern: Regex): File? {
    if (!pattern.matches(name)) return null
    val child = File(root, name)
    return try {
        val directChild = child.toPath().toAbsolutePath().normalize().parent ==
            root.toPath().toAbsolutePath().normalize()
        if (!directChild || Files.isSymbolicLink(child.toPath())) null else child
    } catch (_: Exception) {
        null
    }
}

private fun regularSize(file: File): Long = try {
    if (!Files.isSymbolicLink(file.toPath()) &&
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    ) {
        Files.size(file.toPath())
    } else {
        -1L
    }
} catch (_: Exception) {
    -1L
}

private fun parseNonNegativeLong(value: String): Long? =
    value.takeIf { NON_NEGATIVE_LONG.matches(it) }?.toLongOrNull()

internal fun isManagedName(name: String): Boolean =
    METADATA_FILE_NAME.matches(name) || STATE_FILE_NAME.matches(name) || TEMP_FILE_NAME.matches(name)

internal fun ByteArray.toLowercaseHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowercaseHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
