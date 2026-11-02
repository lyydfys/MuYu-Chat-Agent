package com.muyuchat.mca.agent.extensions.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real OkHttp transport against a loopback HTTP/1.1 socket server (JVM only).
 *
 * Android unit tests cannot use com.sun.net.httpserver, so this minimal one-shot server exercises
 * the actual wire path: URL policy, headers, JSON vs SSE parsing, 401 mapping and redirect refusal.
 * The MCP handshake itself remains covered by the fake-transport client tests.
 */
class McpHttpTransportLoopbackTest {

    private var socket: ServerSocket? = null

    @After
    fun tearDown() {
        socket?.close()
    }

    private data class Reply(
        val status: Int,
        val contentType: String,
        val body: String,
        val sessionId: String? = null,
        val location: String? = null
    )

    private fun start(reply: Reply): String {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        socket = server
        thread(isDaemon = true, name = "mcp-loopback") {
            try {
                server.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    repeat(contentLength) { reader.read() }
                    val bytes = reply.body.toByteArray(Charsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 ").append(reply.status).append(' ').append(reason(reply.status)).append("\r\n")
                        append("Content-Type: ").append(reply.contentType).append("\r\n")
                        append("Content-Length: ").append(bytes.size).append("\r\n")
                        append("Connection: close\r\n")
                        reply.sessionId?.let { append("Mcp-Session-Id: ").append(it).append("\r\n") }
                        reply.location?.let { append("Location: ").append(it).append("\r\n") }
                        append("\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().write(head)
                    client.getOutputStream().write(bytes)
                    client.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // Test teardown closes the socket; no assertion depends on this thread.
            }
        }
        return "http://127.0.0.1:${server.localPort}/mcp"
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        302 -> "Found"
        401 -> "Unauthorized"
        else -> "Status"
    }

    private fun post(url: String, allowCleartext: Boolean = true) = McpHttpPost(
        url = url,
        bodyJson = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
        sessionId = null,
        protocolVersion = "2025-06-18",
        bearerToken = null,
        allowCleartext = allowCleartext
    )

    @Test
    fun jsonResponseAndSessionHeaderAreParsed() = runBlocking {
        val url = start(
            Reply(
                status = 200,
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""",
                sessionId = "session-1"
            )
        )
        val transport = OkHttpMcpTransport()
        try {
            val result = transport.post(post(url))
            assertTrue("unexpected: $result", result is McpHttpResult.Json)
            val json = result as McpHttpResult.Json
            assertEquals(200, json.statusCode)
            assertEquals("session-1", json.sessionId)
            assertTrue(json.body.contains("\"tools\""))
        } finally {
            transport.close()
        }
    }

    @Test
    fun eventStreamResponseIsParsed() = runBlocking {
        val url = start(
            Reply(
                status = 200,
                contentType = "text/event-stream",
                body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n"
            )
        )
        val transport = OkHttpMcpTransport()
        try {
            val result = transport.post(post(url))
            assertTrue("unexpected: $result", result is McpHttpResult.EventStream)
            val events = (result as McpHttpResult.EventStream).events
            assertEquals(1, events.size)
            assertTrue(events.single().contains("jsonrpc"))
        } finally {
            transport.close()
        }
    }

    @Test
    fun unauthorizedResponseIsAuthenticationFailure() = runBlocking {
        val url = start(Reply(401, "application/json", "{}"))
        val transport = OkHttpMcpTransport()
        try {
            val result = transport.post(post(url))
            assertTrue(result is McpHttpResult.Failed)
            assertTrue((result as McpHttpResult.Failed).failure is McpFailure.Authentication)
        } finally {
            transport.close()
        }
    }

    @Test
    fun redirectIsRefusedAsAnOriginChange() = runBlocking {
        val url = start(
            Reply(
                status = 302,
                contentType = "text/plain",
                body = "",
                location = "https://example.invalid/mcp"
            )
        )
        val transport = OkHttpMcpTransport()
        try {
            val result = transport.post(post(url))
            assertTrue(result is McpHttpResult.Failed)
            val failure = (result as McpHttpResult.Failed).failure
            assertTrue("unexpected: $failure", failure is McpFailure.Endpoint)
            assertTrue((failure as McpFailure.Endpoint).originChanged)
        } finally {
            transport.close()
        }
    }

    @Test
    fun cleartextIsRefusedWithoutAnExplicitLocalGrant() = runBlocking {
        val url = start(Reply(200, "application/json", "{}"))
        val transport = OkHttpMcpTransport()
        try {
            val result = transport.post(post(url, allowCleartext = false))
            assertTrue(result is McpHttpResult.Failed)
            assertTrue((result as McpHttpResult.Failed).failure is McpFailure.Endpoint)
        } finally {
            transport.close()
        }
    }
}
