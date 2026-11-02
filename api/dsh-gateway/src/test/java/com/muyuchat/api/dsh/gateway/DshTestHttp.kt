package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset

class DshTestHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String
) {
    fun json(): org.json.JSONObject = org.json.JSONObject(body)
}

/** Raw-socket HTTP client used by the loopback integration tests. */
internal object DshTestHttp {

    fun port(endpoint: DshGatewayEndpoint): Int = URI(endpoint.baseUrl).port

    fun request(
        endpoint: DshGatewayEndpoint,
        method: String,
        path: String,
        body: String? = null,
        token: String? = endpoint.bearerToken,
        extraHeaders: Map<String, String> = emptyMap(),
        hostHeader: String? = null,
        contentLengthOverride: Long? = null
    ): DshTestHttpResponse {
        val socket = Socket("127.0.0.1", port(endpoint))
        socket.soTimeout = 15_000
        try {
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${hostHeader ?: "127.0.0.1:${port(endpoint)}"}\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (bodyBytes != null || method == "POST") append("Content-Type: application/json\r\n")
                append("Content-Length: ${contentLengthOverride ?: bodyBytes?.size?.toLong() ?: 0L}\r\n")
                extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Connection: close\r\n\r\n")
            }
            val output = socket.getOutputStream()
            output.write(headers.toByteArray(Charsets.ISO_8859_1))
            if (bodyBytes != null && contentLengthOverride == null) output.write(bodyBytes)
            output.flush()
            return readResponse(socket.getInputStream())
        } finally {
            runCatching { socket.close() }
        }
    }

    fun openSse(
        endpoint: DshGatewayEndpoint,
        body: String,
        token: String? = endpoint.bearerToken
    ): DshTestSse {
        val socket = Socket("127.0.0.1", port(endpoint))
        socket.soTimeout = 15_000
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("POST /v1/chat/completions HTTP/1.1\r\n")
            append("Host: 127.0.0.1:${port(endpoint)}\r\n")
            if (token != null) append("Authorization: Bearer $token\r\n")
            append("Content-Type: application/json\r\n")
            append("Accept: text/event-stream\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
        val reader = DshTestLineReader(socket.getInputStream())
        val statusLine = reader.readIsoLine()
            ?: error("The gateway closed the SSE connection before sending a status line.")
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: error("Malformed SSE status line: $statusLine")
        val responseHeaders = reader.readHeaders()
        check(status == 200) {
            val bodyText = runCatching { reader.readBytes(responseHeaders["content-length"]?.toInt() ?: 0) }
            "Expected SSE 200, got $status: ${String(bodyText.getOrDefault(ByteArray(0)), Charsets.UTF_8)}"
        }
        return DshTestSse(socket, reader)
    }

    private fun readResponse(input: InputStream): DshTestHttpResponse {
        val reader = DshTestLineReader(input)
        val statusLine = reader.readIsoLine() ?: error("No HTTP status line.")
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: error("Malformed HTTP status line: $statusLine")
        val headers = reader.readHeaders()
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = String(reader.readBytes(contentLength), Charsets.UTF_8)
        return DshTestHttpResponse(status, headers, body)
    }
}

/** One SSE response stream; reads `data:` events one at a time. */
internal class DshTestSse(
    private val socket: Socket,
    private val reader: DshTestLineReader
) : Closeable {

    fun readEvent(): String? {
        val data = StringBuilder()
        var sawData = false
        while (true) {
            val line = reader.readUtf8Line() ?: return if (sawData) data.toString() else null
            when {
                line.isEmpty() -> if (sawData) return data.toString()
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    sawData = true
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
    }

    fun readUntilDone(maxEvents: Int = 2_000): List<String> {
        val events = ArrayList<String>()
        while (events.size < maxEvents) {
            val event = readEvent() ?: break
            events += event
            if (event == "[DONE]") break
        }
        return events
    }

    /** Reads events until [predicate] matches the latest event or the stream ends. */
    fun readUntil(maxEvents: Int = 500, predicate: (String) -> Boolean): List<String> {
        val events = ArrayList<String>()
        while (events.size < maxEvents) {
            val event = readEvent() ?: break
            events += event
            if (predicate(event)) break
        }
        return events
    }

    fun closeSocket() {
        runCatching { socket.close() }
    }

    override fun close() {
        closeSocket()
    }
}

internal class DshTestLineReader(private val input: InputStream) {

    fun readIsoLine(): String? = readLine(Charsets.ISO_8859_1)

    fun readUtf8Line(): String? = readLine(Charsets.UTF_8)

    fun readHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readIsoLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) bytes else bytes.copyOf(offset)
    }

    private fun readLine(charset: Charset): String? {
        val buffer = ByteArrayOutputStream()
        var first = true
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (first) return null
                break
            }
            first = false
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return String(buffer.toByteArray(), charset)
    }
}
