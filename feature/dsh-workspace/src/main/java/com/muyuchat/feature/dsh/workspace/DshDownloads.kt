package com.muyuchat.feature.dsh.workspace

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/** Everything the WebView's DownloadListener hands to the host. */
data class DshDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    /** -1 when the server did not send Content-Length. */
    val contentLengthBytes: Long = -1L
)

/** Why a page download was refused before any Android component fetched it. */
enum class DshDownloadRejection {
    MALFORMED_URL,
    UNSUPPORTED_SCHEME,
    CREDENTIALS_IN_URL,
    URL_TOO_LONG,
    DATA_URL_TOO_LARGE,
    DATA_URL_MALFORMED
}

/** A classified, still-unexecuted download. */
sealed interface DshDownloadInspection {
    /** Fetch through DownloadManager; headers were already sanitized by the policy. */
    data class Http(
        val url: String,
        val userAgent: String?,
        val mimeType: String?,
        val suggestedFileName: String
    ) : DshDownloadInspection

    /** `data:` URL already decoded into bounded memory; the host must write it through SAF. */
    data class Data(
        val bytes: ByteArray,
        val mimeType: String,
        val suggestedFileName: String
    ) : DshDownloadInspection

    data class Rejected(val reason: DshDownloadRejection) : DshDownloadInspection
}

/**
 * Pure download policy.
 *
 * The DSH page is served from loopback and may legitimately trigger downloads for artifacts. Only
 * `http`/`https` (handled by DownloadManager) and bounded `data:` URLs (handled through the Storage
 * Access Framework) are allowed. `blob:` URLs cannot be fetched by DownloadManager and reading them
 * would require a JavaScript bridge, which this security-first container deliberately does not add;
 * they are rejected with an explicit reason. All other schemes are refused.
 */
object DshDownloadPolicy {

    const val MAX_HTTP_URL_LENGTH = 8192
    const val MAX_DATA_URL_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_MIME = "application/octet-stream"
    private const val FALLBACK_NAME = "dsh-download"

    private val FILENAME_STAR = Regex("(?i)filename\\*\\s*=\\s*([^;]+)")
    private val FILENAME_PLAIN = Regex("(?i)filename\\s*=\\s*\"?([^\";]+)\"?")
    private val INVALID_FILE_CHARS = Regex("[<>:\"/\\\\|?*]")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    fun classify(request: DshDownloadRequest): DshDownloadInspection {
        val url = request.url.trim()
        if (url.isEmpty() || url.length > MAX_HTTP_URL_LENGTH) {
            return DshDownloadInspection.Rejected(
                if (url.isEmpty()) DshDownloadRejection.MALFORMED_URL else DshDownloadRejection.URL_TOO_LONG
            )
        }
        if (url.startsWith("data:", ignoreCase = true)) {
            return when (val parsed = parseDataUrl(url, MAX_DATA_URL_BYTES)) {
                is DataUrlResult.Parsed -> DshDownloadInspection.Data(
                    bytes = parsed.bytes,
                    mimeType = parsed.mimeType,
                    suggestedFileName = withExtension(FALLBACK_NAME, parsed.mimeType)
                )
                is DataUrlResult.Rejected -> DshDownloadInspection.Rejected(parsed.reason)
            }
        }
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return DshDownloadInspection.Rejected(DshDownloadRejection.MALFORMED_URL)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return DshDownloadInspection.Rejected(DshDownloadRejection.UNSUPPORTED_SCHEME)
        }
        if (uri.host.isNullOrBlank()) {
            return DshDownloadInspection.Rejected(DshDownloadRejection.MALFORMED_URL)
        }
        if (uri.rawUserInfo != null) {
            return DshDownloadInspection.Rejected(DshDownloadRejection.CREDENTIALS_IN_URL)
        }
        val mime = request.mimeType?.takeIf { it.isNotBlank() }
        return DshDownloadInspection.Http(
            url = url,
            userAgent = safeHeaderValue(request.userAgent),
            mimeType = mime,
            suggestedFileName = suggestedFileName(request.contentDisposition, url, mime)
        )
    }

    /** Derives a safe local name from Content-Disposition, then the URL, then the MIME type. */
    fun suggestedFileName(contentDisposition: String?, url: String?, mimeType: String?): String {
        val fromDisposition = contentDisposition?.let(::fileNameFromContentDisposition)
        if (!fromDisposition.isNullOrBlank()) {
            return sanitizeFileName(fromDisposition, extensionForMime(mimeType))
        }
        val fromUrl = runCatching { URI(url.orEmpty()) }
            .getOrNull()
            ?.rawPath
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { decodeComponent(it) }
        if (!fromUrl.isNullOrBlank()) {
            return sanitizeFileName(fromUrl, extensionForMime(mimeType))
        }
        return withExtension(FALLBACK_NAME, mimeType)
    }

    fun sanitizeFileName(raw: String?, fallbackExtension: String = ".bin"): String {
        val base = raw.orEmpty()
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .let { CONTROL_CHARS.replace(it, "") }
            .let { INVALID_FILE_CHARS.replace(it, "") }
            .trim()
            .trim('.')
        val collapsed = base.replace(Regex("\\s+"), " ").take(120).trim()
        val name = collapsed.ifBlank { FALLBACK_NAME }
        val dotted = name.contains('.')
        if (dotted) return name
        val extension = fallbackExtension.takeIf { it.startsWith(".") && it.length in 2..10 } ?: ".bin"
        return name + extension
    }

    /** Adds the MIME extension unless the name already carries one. */
    fun withExtension(name: String, mimeType: String?): String =
        sanitizeFileName(name, extensionForMime(mimeType))

    fun extensionForMime(mimeType: String?): String {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (mime) {
            "text/plain" -> ".txt"
            "text/markdown", "text/x-markdown" -> ".md"
            "text/csv" -> ".csv"
            "text/html" -> ".html"
            "application/json", "application/problem+json" -> ".json"
            "application/xml", "text/xml" -> ".xml"
            "application/zip" -> ".zip"
            "application/gzip", "application/x-gzip" -> ".gz"
            "application/pdf" -> ".pdf"
            "application/octet-stream" -> ".bin"
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "image/svg+xml" -> ".svg"
            "audio/mpeg" -> ".mp3"
            "video/mp4" -> ".mp4"
            else -> ".bin"
        }
    }

    /** User-facing explanation for a refused download; never contains the URL/token. */
    fun messageFor(reason: DshDownloadRejection): String = when (reason) {
        DshDownloadRejection.MALFORMED_URL -> "下载地址无效，已取消。"
        DshDownloadRejection.UNSUPPORTED_SCHEME ->
            "该下载类型（如网页内生成的 blob 链接）暂不受支持，已取消。"
        DshDownloadRejection.CREDENTIALS_IN_URL -> "下载地址包含凭据信息，已拒绝。"
        DshDownloadRejection.URL_TOO_LONG -> "下载地址过长，已拒绝。"
        DshDownloadRejection.DATA_URL_TOO_LARGE -> "内嵌下载内容超过 2 MB 限制，已取消。"
        DshDownloadRejection.DATA_URL_MALFORMED -> "内嵌下载内容无法解析，已取消。"
    }

    /** Truncates at the first CR/LF and bounds the value so it cannot inject a second header. */
    fun safeHeaderValue(value: String?): String? = value
        ?.substringBefore('\r')
        ?.substringBefore('\n')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(1024)

    // -- data: URL ---------------------------------------------------------

    sealed interface DataUrlResult {
        data class Parsed(val bytes: ByteArray, val mimeType: String) : DataUrlResult
        data class Rejected(val reason: DshDownloadRejection) : DataUrlResult
    }

    fun parseDataUrl(url: String, maxBytes: Int = MAX_DATA_URL_BYTES): DataUrlResult {
        if (!url.startsWith("data:", ignoreCase = true)) {
            return DataUrlResult.Rejected(DshDownloadRejection.DATA_URL_MALFORMED)
        }
        val comma = url.indexOf(',')
        if (comma < 0) return DataUrlResult.Rejected(DshDownloadRejection.DATA_URL_MALFORMED)
        val meta = url.substring("data:".length, comma)
        val payload = url.substring(comma + 1)
        // Cheap upper bound before allocating/decoding: base64 is at most 4/3 of source bytes.
        if (payload.length > maxBytes * 2 + 64) {
            return DataUrlResult.Rejected(DshDownloadRejection.DATA_URL_TOO_LARGE)
        }
        val metaParts = meta.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val isBase64 = metaParts.any { it.equals("base64", ignoreCase = true) }
        val declaredMime = metaParts.firstOrNull { it.contains('/') && !it.contains('=') }
            ?.substringBefore(';')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) }
            ?: DEFAULT_MIME
        val bytes = if (isBase64) {
            runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
                ?: return DataUrlResult.Rejected(DshDownloadRejection.DATA_URL_MALFORMED)
        } else {
            runCatching {
                // '+' is a literal plus in a data URL, not a space: protect it before URL decoding.
                URLDecoder.decode(payload.replace("+", "%2B"), Charsets.UTF_8.name())
                    .toByteArray(Charsets.UTF_8)
            }.getOrNull() ?: return DataUrlResult.Rejected(DshDownloadRejection.DATA_URL_MALFORMED)
        }
        if (bytes.size > maxBytes) {
            return DataUrlResult.Rejected(DshDownloadRejection.DATA_URL_TOO_LARGE)
        }
        return DataUrlResult.Parsed(bytes, declaredMime)
    }

    // -- internals ---------------------------------------------------------

    private fun fileNameFromContentDisposition(value: String): String? {
        val star = FILENAME_STAR.find(value)?.groupValues?.getOrNull(1)
        if (star != null) {
            val decoded = decodeRfc5987(star)
            if (!decoded.isNullOrBlank()) return decoded
        }
        return FILENAME_PLAIN.find(value)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
    }

    /** RFC 5987 `charset'lang'percent-encoded`; tolerant of a missing charset/lang prefix. */
    private fun decodeRfc5987(raw: String): String? {
        val trimmed = raw.trim().trim('"')
        val delimiter = trimmed.indexOf("''")
        if (delimiter < 0) return decodeComponent(trimmed)
        val charset = trimmed.substring(0, delimiter).ifBlank { "UTF-8" }
        val encoded = trimmed.substring(delimiter + 2)
        return runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()
    }

    private fun decodeComponent(raw: String): String? =
        runCatching { URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrNull()
}
