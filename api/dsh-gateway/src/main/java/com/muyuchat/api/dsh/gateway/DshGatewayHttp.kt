package com.muyuchat.api.dsh.gateway

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/** One parsed HTTP request whose body has been fully read and length-checked. */
internal class DshGatewayHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * A request rejected before or during HTTP parsing. Always mapped to an OpenAI-shaped JSON error
 * before the socket is closed.
 */
internal class DshGatewayHttpRejection(
    val status: Int,
    val type: String,
    val code: String,
    override val message: String,
    val param: String? = null
) : IOException(message)

/**
 * Minimal HTTP/1.1 server transport over a plain [Socket].
 *
 * Android has no `com.sun.net.httpserver`, and the public Local API transport is not reused here
 * because it carries unrelated public-API behavior (CORS, permissive parsing, route set). This
 * transport is deliberately strict for one trusted loopback caller:
 * - bounded request line and headers, bounded body, no chunked request bodies;
 * - duplicate headers, a wrong `Host` and any browser `Origin` are rejected (DNS-rebinding and
 *   accidental browser access defense points in addition to the bearer token);
 * - every response closes the connection, so no keep-alive request smuggling is possible.
 */
internal object DshGatewayHttp {

    /** Reads and validates one request. Throws [DshGatewayHttpRejection] with an HTTP mapping. */
    fun read(socket: Socket, limits: DshGatewayLimits): DshGatewayHttpRequest {
        val input = socket.getInputStream()
        val headerBytes = readHeaderBlock(input, limits.maxHeaderBytes)
        val headerText = String(headerBytes, Charsets.ISO_8859_1).trimEnd('\r', '\n')
        val lines = headerText.split(Regex("\r?\n"))
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        val requestParts = requestLine.split(Regex(" +")).filter { it.isNotEmpty() }
        if (requestParts.size < 3 || !requestParts[2].startsWith("HTTP/")) {
            throw DshGatewayHttpRejection(
                400, "invalid_request_error", "invalid_request_line",
                "The HTTP request line is malformed."
            )
        }
        val method = requestParts[0].uppercase()
        val path = requestParts[1].substringBefore('?').ifEmpty { "/" }
        if (!path.startsWith("/")) {
            throw DshGatewayHttpRejection(
                400, "invalid_request_error", "invalid_request_line",
                "The HTTP request target must be an absolute path."
            )
        }

        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw DshGatewayHttpRejection(
                    400, "invalid_request_error", "invalid_header",
                    "An HTTP header line is malformed."
                )
            }
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty()) {
                throw DshGatewayHttpRejection(
                    400, "invalid_request_error", "invalid_header",
                    "An HTTP header name is empty."
                )
            }
            if (headers.containsKey(name)) {
                throw DshGatewayHttpRejection(
                    400, "invalid_request_error", "duplicate_header",
                    "Duplicate HTTP header: $name."
                )
            }
            headers[name] = value
        }

        validateHost(headers)
        if (headers.containsKey("origin")) {
            throw DshGatewayHttpRejection(
                403, "invalid_request_error", "origin_not_allowed",
                "Browser-originated requests are not accepted by the internal model gateway."
            )
        }
        val transferEncoding = headers["transfer-encoding"]
        if (transferEncoding != null &&
            transferEncoding.isNotBlank() &&
            !transferEncoding.equals("identity", ignoreCase = true)
        ) {
            throw DshGatewayHttpRejection(
                400, "invalid_request_error", "unsupported_transfer_encoding",
                "Chunked request bodies are not supported."
            )
        }

        val contentLength = headers["content-length"]?.let { raw ->
            val parsed = raw.toLongOrNull()
            if (parsed == null || parsed < 0L) {
                throw DshGatewayHttpRejection(
                    400, "invalid_request_error", "invalid_content_length",
                    "Content-Length must be a non-negative integer."
                )
            }
            if (parsed > limits.maxRequestBytes.toLong()) {
                throw DshGatewayHttpRejection(
                    413, "invalid_request_error", "request_too_large",
                    "Request body exceeds ${limits.maxRequestBytes} bytes."
                )
            }
            parsed.toInt()
        } ?: 0

        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            if (read < 0) {
                throw DshGatewayHttpRejection(
                    400, "invalid_request_error", "incomplete_request_body",
                    "The request body ended before Content-Length bytes were received."
                )
            }
            offset += read
        }
        return DshGatewayHttpRequest(
            method = method,
            path = path,
            headers = headers,
            body = if (contentLength == 0) "" else String(bodyBytes, Charsets.UTF_8)
        )
    }

    private fun readHeaderBlock(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream(1024)
        var last = -1
        var last2 = -1
        var last3 = -1
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) throw EOFException("The HTTP peer closed before sending a request.")
                throw DshGatewayHttpRejection(
                    400, "invalid_request_error", "incomplete_http_request",
                    "The HTTP peer closed before the request headers were complete."
                )
            }
            buffer.write(value)
            if (buffer.size() > limit) {
                throw DshGatewayHttpRejection(
                    431, "invalid_request_error", "http_header_too_large",
                    "HTTP headers exceed $limit bytes."
                )
            }
            val lfLf = value == '\n'.code && last == '\n'.code
            val crlfCrlf = value == '\n'.code && last == '\r'.code &&
                last2 == '\n'.code && last3 == '\r'.code
            if (lfLf || crlfCrlf) break
            last3 = last2
            last2 = last
            last = value
        }
        return buffer.toByteArray()
    }

    /**
     * Only accepts the literal loopback authority the gateway itself publishes. This keeps a
     * hostile DNS name resolving to 127.0.0.1 from reaching the routes even if a token leaked.
     */
    private fun validateHost(headers: Map<String, String>) {
        val host = headers["host"]
            ?: throw DshGatewayHttpRejection(
                400, "invalid_request_error", "missing_host",
                "The HTTP Host header is required."
            )
        val hostName = when {
            host.startsWith("[") -> host.substringBefore(']').removePrefix("[")
            else -> host.substringBefore(':')
        }.trim().lowercase()
        if (hostName != "127.0.0.1" && hostName != "localhost") {
            throw DshGatewayHttpRejection(
                403, "invalid_request_error", "invalid_host",
                "The internal model gateway only accepts loopback requests."
            )
        }
    }

    fun writeJson(
        socket: Socket,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Connection" to "close"
        )
        headers.putAll(extraHeaders)
        val output = socket.getOutputStream()
        output.write(statusLine(status))
        headers.forEach { (name, value) ->
            val safeName = name.filter { it.isLetterOrDigit() || it == '-' }
            val safeValue = value.replace("\r", "").replace("\n", "")
            if (safeName.isNotEmpty()) output.write("$safeName: $safeValue\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    fun writeError(
        socket: Socket,
        status: Int,
        type: String,
        code: String,
        message: String,
        param: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeJson(socket, status, errorJson(type, code, message, param), extraHeaders)
    }

    fun errorJson(type: String, code: String, message: String, param: String? = null): String {
        val error = org.json.JSONObject()
            .put("message", message)
            .put("type", type)
            .put("code", code)
        if (param != null) error.put("param", param)
        return org.json.JSONObject().put("error", error).toString()
    }

    fun statusLine(status: Int): ByteArray {
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            499 -> "Client Closed Request"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        return "HTTP/1.1 $status $reason\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
