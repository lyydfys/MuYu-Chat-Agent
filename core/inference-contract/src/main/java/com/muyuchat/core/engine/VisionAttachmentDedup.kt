package com.muyuchat.core.engine

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * Removes accidental duplicate image parts before they cross a native vision
 * boundary.  Android clients can describe the same image as a content/file
 * URI, a file:// URI, or an inline data URL; comparing the raw attachment
 * object is therefore not sufficient.  The first occurrence wins so the
 * user's ordering is preserved.
 */
/** Shared attachment identity contract for UI, cloud, and native vision paths. */
fun List<ChatImageAttachment>.deduplicateVisionAttachments(): List<ChatImageAttachment> {
    if (size < 2) return this
    val seen = LinkedHashSet<String>(size)
    return filter { attachment -> seen.add(attachment.visionDeduplicationKey()) }
}

/**
 * Cheap display-only duplicate removal for Compose. Unlike
 * [deduplicateVisionAttachments], this never opens a file or hashes image
 * bytes, so it is safe to call while composing a message bubble. Persisted
 * history and inference requests still use content-based deduplication on
 * their IO/native preparation paths.
 */
fun List<ChatImageAttachment>.deduplicateVisionAttachmentsForDisplay(): List<ChatImageAttachment> {
    if (size < 2) return this
    val seen = LinkedHashSet<String>(size)
    return filter { attachment -> seen.add(attachment.visionDisplayDeduplicationKey()) }
}

private fun ChatImageAttachment.visionDisplayDeduplicationKey(): String {
    val source = uriString.trim()
    if (source.isNotEmpty()) {
        val uri = runCatching { URI(source) }.getOrNull()
        if (uri?.scheme.equals("file", ignoreCase = true)) {
            // URI.path is decoded without touching the filesystem. This makes
            // file:///data/x.png and /data/x.png share a stable display key.
            val path = uri?.path.orEmpty().replace('\\', '/')
            return "file:${path.trimEnd('/')}"
        }
        if (source.startsWith('/')) return "file:${source.replace('\\', '/').trimEnd('/')}"
        if (uri?.scheme != null) {
            val scheme = uri.scheme.lowercase(java.util.Locale.ROOT)
            val host = uri.host?.lowercase(java.util.Locale.ROOT)
            if (host != null) {
                return buildString {
                    append(scheme).append("://").append(host)
                    if (uri.port >= 0) append(':').append(uri.port)
                    append(uri.rawPath.orEmpty())
                    uri.rawQuery?.let { append('?').append(it) }
                }
            }
        }
        return "uri:$source"
    }
    // Inline payloads are uncommon in persisted UI state. Exact string
    // identity avoids decoding or reading any backing file on the UI thread.
    if (hasInlineData) return "inline:${dataBase64.trim()}"
    return "empty:${name.trim()}"
}

fun ChatImageAttachment.visionDeduplicationKey(): String {
    if (hasInlineData) {
        // Oversized inline payloads are rejected before they are decoded by
        // the image pipeline. Keep their raw spelling as the key here so the
        // early dedup pass does not allocate another full decoded byte array.
        if (!inlineVisionImageWithinLimit(dataBase64)) return dataBase64
        // Hash decoded bytes rather than the spelling of the base64 payload.
        // MIME/base64 wrapping and line breaks must not create a second visual
        // slot for the same image.
        val normalized = plainBase64().replace(Regex("\\s+"), "")
        val decoded = runCatching { Base64.getMimeDecoder().decode(normalized) }.getOrNull()
        return if (decoded != null && decoded.isNotEmpty()) {
            // Keep the namespace identical to readable local files and direct
            // VLM content.  A picker may represent one image once as a file
            // URI and once as inline base64; different prefixes would let both
            // parts reach the provider as two images.
            "bytes:" + sha256(decoded)
        } else {
            "inline:" + sha256(normalized)
        }
    }
    val source = uriString.trim()
    if (source.isBlank()) return "empty:${name.trim()}"
    val canonical = when {
        source.startsWith("file:", ignoreCase = true) ->
            runCatching { localFileDeduplicationKey(File(URI(source)).canonicalFile) }
                .getOrElse { "file-path:" + source.removePrefix("file://").removePrefix("file:") }
        File(source).isAbsolute -> runCatching { localFileDeduplicationKey(File(source).canonicalFile) }
            .getOrDefault("file-path:$source")
        source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) ->
            runCatching { normalizedRemoteImageUrl(URI(source)) }.getOrDefault(source)
        else -> source
    }
    return if (canonical.startsWith("bytes:") || canonical.startsWith("file-path:")) {
        canonical.trim()
    } else {
        "uri:" + canonical.trim()
    }
}

/** Uses content identity for readable local files and path identity otherwise. */
private fun localFileDeduplicationKey(file: File): String {
    val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
    if (!canonical.isFile || !canonical.canRead()) return "file-path:${canonical.path}"
    val digest = runCatching {
        val algorithm = MessageDigest.getInstance("SHA-256")
        canonical.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                algorithm.update(buffer, 0, read)
            }
        }
        algorithm.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()
    return digest?.let { "bytes:$it" } ?: "file-path:${canonical.path}"
}

private fun normalizedRemoteImageUrl(uri: URI): String {
    val scheme = uri.scheme ?: return uri.toString().substringBefore('#')
    val host = uri.host ?: return uri.toString().substringBefore('#')
    val normalizedHost = host.lowercase(java.util.Locale.ROOT).let { value ->
        when {
            value.startsWith("[") -> value
            ':' in value -> "[$value]"
            else -> value
        }
    }
    return buildString {
        append(scheme.lowercase(java.util.Locale.ROOT)).append("://")
        uri.rawUserInfo?.let { append(it).append('@') }
        append(normalizedHost)
        if (uri.port >= 0) append(':').append(uri.port)
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
    }
}

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }