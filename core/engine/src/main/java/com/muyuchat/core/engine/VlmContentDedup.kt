package com.muyuchat.core.engine

import com.geniex.sdk.bean.VlmContent
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
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

/** Deduplicates only image entries while preserving all text/content ordering. */
fun List<VlmContent>.deduplicateVisionContents(): List<VlmContent> {
    if (size < 2) return this
    val seenImages = LinkedHashSet<String>()
    return filter { content ->
        if (!content.type.equals("image", ignoreCase = true)) return@filter true
        val path = content.text?.trim().orEmpty()
        path.isBlank() || seenImages.add(imageContentDeduplicationKey(path))
    }
}

/** Normalize direct VLM image inputs so equivalent file/data/url spellings dedupe. */
private fun imageContentDeduplicationKey(raw: String): String {
    val value = raw.trim()
    if (value.startsWith("data:", ignoreCase = true)) {
        val comma = value.indexOf(',')
        if (comma > 0) {
            val metadata = value.substring(5, comma)
            val payload = value.substring(comma + 1)
            val bytes = runCatching {
                if (metadata.contains(";base64", ignoreCase = true)) {
                    Base64.getDecoder().decode(payload.replace(Regex("\\s+"), ""))
                } else {
                    decodeDataUrlPayload(payload)
                }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return "bytes:${sha256(bytes)}"
        }
    }
    if (value.startsWith("file:", ignoreCase = true)) {
        runCatching { File(URI(value)).canonicalFile }
            .getOrNull()
            ?.let { return localFileDeduplicationKey(it) }
    }
    if (File(value).isAbsolute) {
        runCatching { File(value).canonicalFile }
            .getOrNull()
            ?.let { return localFileDeduplicationKey(it) }
    }
    if (value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)) {
        // Scheme/host are case-insensitive, but path and query are not. Lowercasing
        // the whole URL can collapse distinct image resources or signed URLs and
        // silently drop one of the user's images.
        val normalized = runCatching { normalizedRemoteImageUrl(URI(value)) }
            .getOrDefault(value)
        return "url:$normalized"
    }
    return "path:$value"
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

/** Percent-decodes a non-base64 data URL without treating '+' as a space. */
private fun decodeDataUrlPayload(payload: String): ByteArray {
    val output = java.io.ByteArrayOutputStream(payload.length)
    var index = 0
    while (index < payload.length) {
        val char = payload[index]
        if (char == '%') {
            require(index + 2 < payload.length) { "incomplete percent escape" }
            val high = payload[index + 1].digitToIntOrNull(16) ?: error("invalid percent escape")
            val low = payload[index + 2].digitToIntOrNull(16) ?: error("invalid percent escape")
            output.write((high shl 4) or low)
            index += 3
        } else {
            val codePoint = payload.codePointAt(index)
            output.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
    }
    return output.toByteArray()
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }