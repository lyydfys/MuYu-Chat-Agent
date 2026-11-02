package com.muyuchat.mca.agent.extensions.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** One request as the fixture server parsed it. */
data class McpTestRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
)

data class McpTestResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
)

/**
 * Minimal loopback HTTP/1.1 fixture server for the MCP client tests.
 *
 * Android unit tests do not get `com.sun.net.httpserver`, so this uses only `java.net` from the
 * base module. It is deliberately single-threaded and closes each connection; the client under test
 * is the only user and the tests assert its behavior, not HTTP server concurrency.
 */
class McpTestHttpServer(
    private val handler: (McpTestRequest) -> McpTestResponse
) {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val thread = Thread({ acceptLoop() }, "mcp-test-http").apply {
        isDaemon = true
        start()
    }

    val url: String = "http://127.0.0.1:${socket.localPort}/mcp"

    fun stop() {
        running.set(false)
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                return
            }
            client.use { serve(it) }
        }
    }

    private fun serve(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = readBody(input, length)
            val response = handler(
                McpTestRequest(
                    method = parts[0],
                    path = parts[1],
                    headers = headers,
                    body = body
                )
            )
            writeResponse(client.getOutputStream(), response)
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (current == '\n'.code) {
                if (previous == '\r'.code) {
                    val bytes = out.toByteArray()
                    return String(bytes, 0, bytes.size - 1, StandardCharsets.UTF_8)
                }
                return out.toString("UTF-8")
            }
            out.write(current)
            previous = current
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return String(bytes, 0, offset, StandardCharsets.UTF_8)
    }

    private fun writeResponse(output: OutputStream, response: McpTestResponse) {
        val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val headerText = buildString {
            append("HTTP/1.1 ").append(response.status).append(" Fixture\r\n")
            response.headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headerText.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }
}
