package com.muyuchat.api.local

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket

    internal fun IOException.isPeerDisconnect(): Boolean {
        val text = message.orEmpty().lowercase()
        return text.isBlank() ||
            "broken pipe" in text ||
            "connection reset" in text ||
            "connection abort" in text ||
            "connection closed" in text ||
            "socket closed" in text ||
            "not connected" in text ||
            "eof" in text
    }

    internal fun McaLoopbackServer.writeJson(
        socket: Socket,
        body: String,
        status: String = "200 OK",
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeText(socket, body, status, "application/json; charset=utf-8", extraHeaders)
    }

    internal fun McaLoopbackServer.writeError(
        socket: Socket,
        status: String,
        code: String,
        message: String,
        detailsJson: String = "{}",
        retryAfterMs: Long = 0L
    ) {
        val retryHeaders = if (retryAfterMs > 0L) {
            mapOf(
                "Retry-After" to ((retryAfterMs + 999L) / 1000L).coerceAtLeast(1L).toString(),
                "X-Retry-After-Ms" to retryAfterMs.toString()
            )
        } else {
            emptyMap()
        }
        writeJson(
            socket,
            errorJson(
                code,
                LocalApiRuntime.publicMessage(message),
                LocalApiRuntime.publicJson(detailsJson)
            ).toString(),
            status,
            retryHeaders
        )
    }

    internal fun errorJson(code: String, message: String, detailsJson: String = "{}"): JSONObject =
        OpenAiApiCompat.errorJson(code, message, detailsJson)

    internal fun McaLoopbackServer.writeNoContent(socket: Socket) {
        val output = socket.getOutputStream()
        synchronized(clientSocketLock) { responseStartedSockets.add(socket) }
        output.write("HTTP/1.1 204 No Content\r\n".toByteArray())
        output.write(corsHeaders().toByteArray())
        output.write("Content-Length: 0\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    internal fun McaLoopbackServer.isAuthorized(headers: Map<String, String>): Boolean {
        if (apiKey.isBlank()) return true
        val authorization = headers["authorization"].orEmpty()
        val bearer = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substringAfter(' ').trim()
        } else {
            authorization.trim()
        }
        val headerKey = headers["x-api-key"]?.trim()
        return bearer == apiKey || headerKey == apiKey
    }

    internal fun McaLoopbackServer.writeText(
        socket: Socket,
        body: String,
        status: String = "200 OK",
        contentType: String = "text/plain; charset=utf-8",
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        synchronized(clientSocketLock) { responseStartedSockets.add(socket) }
        output.write("HTTP/1.1 $status\r\n".toByteArray())
        output.write("Content-Type: $contentType\r\n".toByteArray())
        output.write(corsHeaders().toByteArray())
        extraHeaders.forEach { (name, value) ->
            val safeName = name.filter { it.isLetterOrDigit() || it == '-' }
            val safeValue = value.replace("\r", "").replace("\n", "")
            if (safeName.isNotBlank()) {
                output.write("$safeName: $safeValue\r\n".toByteArray())
            }
        }
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    internal fun corsHeaders(): String = buildString {
        append(OpenAiApiCompat.corsHeaders())
    }

    internal fun McaLoopbackServer.readHttpRequest(socket: Socket): HttpRequest {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        var headerEnd = -1
        while (headerEnd < 0) {
            val read = input.read()
            if (read < 0) break
            buffer.write(read)
            headerEnd = findHeaderEnd(buffer.toByteArray())
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw HttpRequestRejected(
                    "431 Request Header Fields Too Large",
                    "http_header_too_large",
                    "HTTP header exceeds $MAX_HEADER_BYTES bytes."
                )
            }
        }

        val raw = buffer.toByteArray()
        val safeHeaderEnd = headerEnd.takeIf { it >= 0 } ?: raw.size
        val bodyStart = when {
            raw.copyOfRange(0, safeHeaderEnd).endsWithBytes(CRLFCRLF) -> safeHeaderEnd
            raw.copyOfRange(0, safeHeaderEnd).endsWithBytes(LFLF) -> safeHeaderEnd
            else -> safeHeaderEnd
        }
        val headerText = String(raw.copyOfRange(0, bodyStart), Charsets.ISO_8859_1)
            .trimEnd('\r', '\n')
        val headerLines = headerText.split(Regex("\r?\n"))
        val requestLine = headerLines.firstOrNull().orEmpty()
        val headers = mutableMapOf<String, String>()
        headerLines.drop(1).forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
        }
        val requestParts = requestLine.split(" ")
        val requestMethod = requestParts.getOrNull(0).orEmpty().uppercase()
        val requestPath = requestParts.getOrNull(1).orEmpty().substringBefore("?")
        if (requestMethod.isNotEmpty() && requestPath.isNotEmpty() &&
            !isPublicRoute(requestMethod, requestPath) && !isAuthorized(headers)
        ) {
            throw HttpRequestRejected(
                "401 Unauthorized",
                "unauthorized",
                "API Key 不正确或缺失。"
            )
        }
        val transferEncoding = headers["transfer-encoding"].orEmpty().trim()
        if (transferEncoding.isNotEmpty() && !transferEncoding.equals("identity", ignoreCase = true)) {
            throw HttpRequestRejected(
                "400 Bad Request",
                "unsupported_transfer_encoding",
                "Chunked request bodies are not supported."
            )
        }
        val contentLength = headers["content-length"]?.let { rawLength ->
            val parsed = rawLength.toLongOrNull()
                ?: throw HttpRequestRejected(
                    "400 Bad Request",
                    "invalid_content_length",
                    "Content-Length must be a non-negative integer."
                )
            if (parsed < 0L) {
                throw HttpRequestRejected(
                    "400 Bad Request",
                    "invalid_content_length",
                    "Content-Length must be a non-negative integer."
                )
            }
            val requestBodyLimit = requestBodyLimitFor(requestMethod, requestPath)
            if (parsed > requestBodyLimit.toLong()) {
                throw HttpRequestRejected(
                    "413 Payload Too Large",
                    "request_body_too_large",
                    "Request body exceeds $requestBodyLimit bytes."
                )
            }
            parsed.toInt()
        } ?: 0
        val alreadyRead = raw.copyOfRange(bodyStart, raw.size)
        val copied = alreadyRead.size.coerceAtMost(contentLength)
        val bodyBuffer = ByteArrayOutputStream(
            contentLength.coerceAtMost(REQUEST_BODY_INITIAL_CAPACITY)
        )
        if (copied > 0) {
            bodyBuffer.write(alreadyRead, 0, copied)
        }
        var offset = copied
        val readBuffer = ByteArray(REQUEST_BODY_READ_BUFFER_BYTES)
        while (offset < contentLength) {
            val read = input.read(readBuffer, 0, minOf(readBuffer.size, contentLength - offset))
            if (read <= 0) break
            bodyBuffer.write(readBuffer, 0, read)
            offset += read
        }
        if (offset != contentLength) {
            throw HttpRequestRejected(
                "400 Bad Request",
                "incomplete_request_body",
                "Request body ended before Content-Length bytes were received."
            )
        }
        return HttpRequest(
            requestLine = requestLine,
            headers = headers,
            body = String(bodyBuffer.toByteArray(), Charsets.UTF_8)
        )
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        val crlf = bytes.indexOfSequence(CRLFCRLF)
        if (crlf >= 0) return crlf + CRLFCRLF.size
        val lf = bytes.indexOfSequence(LFLF)
        return if (lf >= 0) lf + LFLF.size else -1
    }

    private fun ByteArray.indexOfSequence(target: ByteArray): Int {
        if (target.isEmpty() || size < target.size) return -1
        for (index in 0..(size - target.size)) {
            var matched = true
            for (targetIndex in target.indices) {
                if (this[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    private fun ByteArray.endsWithBytes(target: ByteArray): Boolean {
        if (size < target.size) return false
        for (index in target.indices) {
            if (this[size - target.size + index] != target[index]) return false
        }
        return true
    }

    internal data class HttpRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    internal class HttpRequestRejected(
        val status: String,
        val code: String,
        message: String
    ) : IllegalArgumentException(message)

        private val CRLFCRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val LFLF = byteArrayOf('\n'.code.toByte(), '\n'.code.toByte())
        private const val MAX_HEADER_BYTES = 64 * 1024

        private const val REQUEST_BODY_INITIAL_CAPACITY = 16 * 1024
        private const val REQUEST_BODY_READ_BUFFER_BYTES = 16 * 1024

        private const val TAG = "McaLoopbackServer"

        internal fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        internal fun logDebug(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        internal fun logWarning(message: String, error: Throwable) {
            runCatching { Log.w(TAG, message, error) }
        }
