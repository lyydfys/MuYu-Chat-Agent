package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteLimits
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Typed parse rejection. [message] is prompt-free and safe to return to the caller. */
class DshCapabilityHttpParseException(
    val statusCode: Int,
    val errorCode: DshCapabilityErrorCode,
    override val message: String
) : IOException(message)

/**
 * One HTTP/1.1 request parsed from the loopback socket.
 *
 * Header names are lower-case; [header] is case-insensitive. The body is only the declared
 * `Content-Length` bytes (chunked transfer is rejected), so a truncated or oversized request can
 * never reach a handler.
 */
class ParsedDshCapabilityRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: String,
    headers: Map<String, String>
) {
    val headers: Map<String, String> = headers.toMap()

    fun header(name: String): String? = this.headers[name.lowercase()]
}

/**
 * Bounded HTTP/1.1 request parser with no external dependency.
 *
 * It enforces the contract limits before any route runs: header block <= 64 KiB, path <=
 * [DshCapabilityRouteLimits.MAX_PATH_CHARS], query <= [DshCapabilityRouteLimits.MAX_QUERY_CHARS],
 * at most [DshCapabilityRouteLimits.MAX_QUERY_PAIRS] distinct pairs, each side <=
 * [DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS], and body <=
 * [DshCapabilityRouteLimits.MAX_BODY_BYTES]. `Transfer-Encoding` other than `identity` is refused
 * because a chunked body has no bounded size at parse time.
 */
object DshCapabilityHttpParser {
    const val MAX_HEADER_BYTES = 64 * 1024

    private const val MAX_HEADER_LINES = 64
    private const val MAX_METHOD_CHARS = 16
    private const val MAX_REQUEST_TARGET_CHARS =
        DshCapabilityRouteLimits.MAX_PATH_CHARS + 1 + DshCapabilityRouteLimits.MAX_QUERY_CHARS
    private const val READ_BUFFER_BYTES = 16 * 1024

    /**
     * Reads exactly one request. The caller owns [input] and the socket; this function performs no
     * blocking beyond the request itself and never buffers more than the declared limits.
     */
    fun parse(input: InputStream): ParsedDshCapabilityRequest {
        val headerBytes = readHeaderBlock(input)
        val headerText = String(headerBytes, Charsets.ISO_8859_1).trimEnd('\r', '\n')
        val lines = headerText.split(Regex("\r?\n"))
        if (lines.size > MAX_HEADER_LINES) {
            throw reject(431, DshCapabilityErrorCode.TOO_LARGE, "request has too many header lines")
        }
        val requestLine = lines.firstOrNull().orEmpty()
        val requestParts = requestLine.split(' ')
        if (requestParts.size != 3) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "malformed request line")
        }
        val method = requestParts[0]
        if (method.isEmpty() || method.length > MAX_METHOD_CHARS || !isToken(method)) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "malformed request method")
        }
        val version = requestParts[2]
        if (!version.startsWith("HTTP/1.")) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "unsupported HTTP version")
        }
        val target = requestParts[1]
        if (target.length > MAX_REQUEST_TARGET_CHARS) {
            throw reject(414, DshCapabilityErrorCode.TOO_LARGE, "request target is too long")
        }
        val pathAndQuery = splitTarget(target)
        val headers = parseHeaders(lines.drop(1))
        val contentLength = parseContentLength(headers)
        val body = readBody(input, contentLength)
        return ParsedDshCapabilityRequest(
            method = method.uppercase(),
            path = pathAndQuery.first,
            query = parseQuery(pathAndQuery.second),
            body = String(body, Charsets.UTF_8),
            headers = headers
        )
    }

    private fun readHeaderBlock(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream(1024)
        var previous = -1
        var secondPrevious = -1
        var thirdPrevious = -1
        var separatorFound = false
        while (!separatorFound) {
            val next = input.read()
            if (next < 0) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "request headers are incomplete")
            }
            buffer.write(next)
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw reject(431, DshCapabilityErrorCode.TOO_LARGE, "request headers are too large")
            }
            separatorFound = when {
                next != '\n'.code -> false
                previous == '\n'.code -> true
                previous == '\r'.code && secondPrevious == '\n'.code && thirdPrevious == '\r'.code -> true
                else -> false
            }
            thirdPrevious = secondPrevious
            secondPrevious = previous
            previous = next
        }
        return buffer.toByteArray()
    }

    private fun splitTarget(target: String): Pair<String, String> {
        if (target.contains('#')) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "request target must not carry a fragment")
        }
        val queryIndex = target.indexOf('?')
        val rawPath = if (queryIndex < 0) target else target.substring(0, queryIndex)
        val rawQuery = if (queryIndex < 0) "" else target.substring(queryIndex + 1)
        if (!rawPath.startsWith('/')) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "request path must be absolute")
        }
        if (rawPath.length > DshCapabilityRouteLimits.MAX_PATH_CHARS) {
            throw reject(414, DshCapabilityErrorCode.TOO_LARGE, "request path is too long")
        }
        if (rawQuery.length > DshCapabilityRouteLimits.MAX_QUERY_CHARS) {
            throw reject(414, DshCapabilityErrorCode.TOO_LARGE, "request query is too long")
        }
        return rawPath to rawQuery
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>(lines.size)
        lines.forEach { line ->
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "malformed request header")
            }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (!isToken(name) || value.any(::isControlCharacter)) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "malformed request header")
            }
            val key = name.lowercase()
            if (headers.put(key, value) != null) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "duplicate request header")
            }
        }
        return headers
    }

    private fun parseContentLength(headers: Map<String, String>): Int {
        val transferEncoding = headers["transfer-encoding"]
        if (transferEncoding != null && !transferEncoding.equals("identity", ignoreCase = true)) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "chunked request bodies are unsupported")
        }
        val raw = headers["content-length"] ?: return 0
        val parsed = raw.toLongOrNull()
            ?: throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "content length is not an integer")
        if (parsed < 0L) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "content length is negative")
        }
        if (parsed > DshCapabilityRouteLimits.MAX_BODY_BYTES.toLong()) {
            throw reject(413, DshCapabilityErrorCode.TOO_LARGE, "request body is too large")
        }
        return parsed.toInt()
    }

    private fun readBody(input: InputStream, contentLength: Int): ByteArray {
        if (contentLength == 0) return ByteArray(0)
        val body = ByteArrayOutputStream(contentLength.coerceAtMost(READ_BUFFER_BYTES))
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var remaining = contentLength
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "request body is incomplete")
            }
            body.write(buffer, 0, read)
            remaining -= read
        }
        return body.toByteArray()
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        val pairs = rawQuery.split('&').filter { it.isNotEmpty() }
        if (pairs.size > DshCapabilityRouteLimits.MAX_QUERY_PAIRS) {
            throw reject(413, DshCapabilityErrorCode.TOO_LARGE, "request has too many query pairs")
        }
        val query = LinkedHashMap<String, String>(pairs.size)
        pairs.forEach { pair ->
            val separator = pair.indexOf('=')
            val rawKey = if (separator < 0) pair else pair.substring(0, separator)
            val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
            if (rawKey.isEmpty()) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "query pair has an empty key")
            }
            if (rawKey.length > DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS ||
                rawValue.length > DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS
            ) {
                throw reject(413, DshCapabilityErrorCode.TOO_LARGE, "query value is too long")
            }
            val key = percentDecode(rawKey)
            val value = percentDecode(rawValue)
            if (key.length > DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS ||
                value.length > DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS
            ) {
                throw reject(413, DshCapabilityErrorCode.TOO_LARGE, "query value is too long")
            }
            if (query.put(key, value) != null) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "duplicate query key")
            }
        }
        return query
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) {
            if (value.any(::isControlCharacter)) {
                throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "query value carries a control character")
            }
            return value
        }
        val decoded = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                if (index + 2 >= value.length) {
                    throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "malformed percent escape")
                }
                val high = hexValue(value[index + 1])
                val low = hexValue(value[index + 2])
                if (high < 0 || low < 0) {
                    throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "malformed percent escape")
                }
                decoded.write((high shl 4) or low)
                index += 3
            } else {
                decoded.write(character.toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        val text = String(decoded.toByteArray(), Charsets.UTF_8)
        if (text.any(::isControlCharacter)) {
            throw reject(400, DshCapabilityErrorCode.INVALID_REQUEST, "query value carries a control character")
        }
        return text
    }

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> -1
    }

    private fun isToken(value: String): Boolean =
        value.isNotEmpty() && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                it == '!' || it == '#' || it == '$' || it == '%' || it == '&' ||
                it == '\'' || it == '*' || it == '+' || it == '-' || it == '.' ||
                it == '^' || it == '_' || it == '`' || it == '|' || it == '~'
        }

    private fun isControlCharacter(character: Char): Boolean =
        character.code < 0x20 || character.code in 0x7F..0x9F

    private fun reject(
        statusCode: Int,
        errorCode: DshCapabilityErrorCode,
        message: String
    ): DshCapabilityHttpParseException = DshCapabilityHttpParseException(statusCode, errorCode, message)
}
