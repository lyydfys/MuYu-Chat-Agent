package com.muyuchat.mca.agent.extensions.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end MCP handshake against a loopback HTTP/1.1 server.
 *
 * This drives the real [McpClient] + [OkHttpMcpTransport] through initialize ->
 * notifications/initialized -> tools/list -> tools/call. It intentionally uses a tiny
 * `java.net` server because Android unit tests do not expose com.sun.net.httpserver.
 */
class McpHandshakeLoopbackTest {

    private var socket: ServerSocket? = null
    @Volatile
    private var requiredToken: String? = null
    @Volatile
    private var expireNextList = false
    @Volatile
    private var expireNextCall = false
    @Volatile
    private var sseNextList = false
    @Volatile
    private var paginateNextList = false
    @Volatile
    private var delayNextResponseMillis = 0L
    private val requestCount = AtomicInteger(0)
    private val callCount = AtomicInteger(0)

    @After
    fun tearDown() {
        socket?.close()
    }

    private fun startServer(): String {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        socket = server
        thread(isDaemon = true, name = "mcp-handshake") {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    thread(isDaemon = true) { handle(client) }
                } catch (_: Exception) {
                    return@thread
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/mcp"
    }

    private fun handle(client: java.net.Socket) {
        client.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
            var contentLength = 0
            var authorization: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authorization = line.substringAfter(':').trim()
                }
            }
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = reader.read(body, read, contentLength - read)
                if (count <= 0) break
                read += count
            }
            val request = String(body, 0, read)
            requestCount.incrementAndGet()
            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(request)?.groupValues?.get(1)
            delayNextResponseMillis.takeIf { it > 0 }?.let { delay ->
                delayNextResponseMillis = 0L
                Thread.sleep(delay)
            }
            val output = connection.getOutputStream()
            fun write(status: String, contentType: String, payload: String, session: String? = null) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: ").append(contentType).append("\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Connection: close\r\n")
                    session?.let { append("Mcp-Session-Id: ").append(it).append("\r\n") }
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                output.write(head)
                output.write(bytes)
                output.flush()
            }
            requiredToken?.let { token ->
                if (authorization != "Bearer $token") {
                    write("401 Unauthorized", "application/json", """{"error":"unauthorized"}""")
                    return
                }
            }
            when {
                request.contains("\"method\":\"initialize\"") -> write(
                    "200 OK",
                    "application/json",
                    """{"jsonrpc":"2.0","id":${id ?: "1"},"result":{"protocolVersion":"2025-06-18",""" +
                        """"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"loopback","version":"1"}}}""",
                    session = "sess-1"
                )
                request.contains("notifications/initialized") -> write("202 Accepted", "application/json", "")
                request.contains("\"method\":\"tools/list\"") && expireNextList -> {
                    expireNextList = false
                    write("404 Not Found", "application/json", """{"error":"session_expired"}""")
                }
                request.contains("\"method\":\"tools/list\"") && paginateNextList -> {
                    val pageTwo = request.contains("\"cursor\":\"p2\"")
                    val tools = if (pageTwo) {
                        """{"name":"echo2","description":"Second page","inputSchema":{"type":"object"}}"""
                    } else {
                        """{"name":"echo","description":"Echo text","inputSchema":{"type":"object",""" +
                            """"properties":{"text":{"type":"string"}},"required":["text"]}}"""
                    }
                    val next = if (pageTwo) "" else ""","nextCursor":"p2""""
                    write(
                        "200 OK",
                        "application/json",
                        """{"jsonrpc":"2.0","id":${id ?: "2"},"result":{"tools":[$tools]$next}}"""
                    )
                }
                request.contains("\"method\":\"tools/list\"") && sseNextList -> {
                    sseNextList = false
                    write(
                        "200 OK",
                        "text/event-stream",
                        "event: message\ndata: " +
                            """{"jsonrpc":"2.0","id":${id ?: "2"},"result":{"tools":[{"name":"echo",""" +
                            """"description":"SSE","inputSchema":{"type":"object"}}]}}""" + "\n\n"
                    )
                }
                request.contains("\"method\":\"tools/list\"") -> write(
                    "200 OK",
                    "application/json",
                    """{"jsonrpc":"2.0","id":${id ?: "2"},"result":{"tools":[{"name":"echo",""" +
                        """"description":"Echo text","inputSchema":{"type":"object",""" +
                        """"properties":{"text":{"type":"string"}},"required":["text"]}}]}}"""
                )
                request.contains("\"method\":\"tools/call\"") && expireNextCall -> {
                    expireNextCall = false
                    callCount.incrementAndGet()
                    write("404 Not Found", "application/json", """{"error":"session_expired"}""")
                }
                request.contains("\"method\":\"tools/call\"") -> {
                    callCount.incrementAndGet()
                    write(
                        "200 OK",
                        "application/json",
                        """{"jsonrpc":"2.0","id":${id ?: "3"},"result":{"content":[""" +
                            """{"type":"text","text":"hello"}],"isError":false}}"""
                    )
                }
                else -> write("404 Not Found", "application/json", """{"error":"unknown"}""")
            }
        }
    }

    @Test
    fun initializeListAndCallSucceedAgainstARealServer() = runBlocking {
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback",
                displayName = "Loopback",
                endpoint = endpoint,
                allowLocalServer = true
            ),
            transport = transport
        )
        try {
            val connected = client.connect()
            assertTrue("connect failed: $connected", connected is McpResult.Ok)
            val session = (connected as McpResult.Ok).value
            assertEquals("sess-1", session.sessionId)
            assertTrue(session.toolsCapability)
            assertEquals(1, client.currentTools().size)
            assertEquals("echo", client.currentTools().single().remoteName)

            val call = client.callTool("echo", """{"text":"hi"}""")
            assertTrue("call failed: $call", call is McpToolCallOutcome.Success)
            assertEquals(listOf("hello"), (call as McpToolCallOutcome.Success).textBlocks)
        } finally {
            client.disconnect()
            transport.close()
        }
    }
    @Test
    fun bearerCredentialIsResolvedAndSentToTheServer() = runBlocking {
        requiredToken = "token-123"
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback-auth",
                displayName = "Loopback auth",
                endpoint = endpoint,
                allowLocalServer = true,
                auth = McpAuth.SecretReference("ref-1")
            ),
            transport = transport,
            credentials = McpCredentialProvider { ref -> if (ref == "ref-1") "token-123" else null }
        )
        try {
            val connected = client.connect()
            assertTrue("connect failed: $connected", connected is McpResult.Ok)
            assertTrue(requestCount.get() >= 2)
        } finally {
            client.disconnect()
            transport.close()
        }
    }

    @Test
    fun aRevokedCredentialNeverReachesTheWire() = runBlocking {
        requiredToken = "token-123"
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback-revoked",
                displayName = "Loopback revoked",
                endpoint = endpoint,
                allowLocalServer = true,
                auth = McpAuth.SecretReference("ref-1")
            ),
            transport = transport,
            credentials = McpCredentialProvider { null }
        )
        try {
            val connected = client.connect()
            assertTrue(connected is McpResult.Failed)
            assertEquals("mcp_credential_revoked", (connected as McpResult.Failed).failure.code)
            assertEquals(0, requestCount.get())
        } finally {
            transport.close()
        }
    }

    @Test
    fun aForgottenSessionReconnectsOnceWithoutReplayingTheCall() = runBlocking {
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback-expiry",
                displayName = "Loopback expiry",
                endpoint = endpoint,
                allowLocalServer = true
            ),
            transport = transport
        )
        try {
            assertTrue(client.connect() is McpResult.Ok)
            expireNextList = true
            val refreshed = client.refreshTools()
            assertTrue("refresh failed: $refreshed", refreshed is McpResult.Ok)
            assertEquals(1L, client.reconnectCount)

            expireNextCall = true
            val failed = client.callTool("echo", """{"text":"hi"}""")
            assertTrue("unexpected call outcome: $failed", failed is McpToolCallOutcome.Failed)
            assertTrue((failed as McpToolCallOutcome.Failed).failure is McpFailure.SessionExpired)
            // The side-effecting call was attempted exactly once; the reconnect does not replay it.
            assertEquals(1, callCount.get())

            val retried = client.callTool("echo", """{"text":"hi"}""")
            assertTrue("retry failed: $retried", retried is McpToolCallOutcome.Success)
            assertEquals(2, callCount.get())
        } finally {
            client.disconnect()
            transport.close()
        }
    }

    @Test
    fun toolsListPaginationIsFollowed() = runBlocking {
        paginateNextList = true
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback-pages",
                displayName = "Loopback pages",
                endpoint = endpoint,
                allowLocalServer = true
            ),
            transport = transport
        )
        try {
            val connected = client.connect()
            assertTrue("connect failed: $connected", connected is McpResult.Ok)
            assertEquals(listOf("echo", "echo2"), client.currentTools().map { it.remoteName })
        } finally {
            client.disconnect()
            transport.close()
        }
    }

    @Test
    fun toolsListCanArriveAsAStreamedEvent() = runBlocking {
        sseNextList = true
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback-sse",
                displayName = "Loopback SSE",
                endpoint = endpoint,
                allowLocalServer = true
            ),
            transport = transport
        )
        try {
            val connected = client.connect()
            assertTrue("connect failed: $connected", connected is McpResult.Ok)
            assertEquals(listOf("echo"), client.currentTools().map { it.remoteName })
        } finally {
            client.disconnect()
            transport.close()
        }
    }

    @Test
    fun aSlowServerBecomesATypedTimeout() = runBlocking {
        delayNextResponseMillis = 1_500L
        val endpoint = startServer()
        val transport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "loopback-slow",
                displayName = "Loopback slow",
                endpoint = endpoint,
                allowLocalServer = true
            ),
            transport = transport,
            requestTimeoutMillis = 300L
        )
        try {
            val started = System.nanoTime()
            val connected = client.connect()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("unexpected: $connected", connected is McpResult.Failed)
            assertTrue(
                "expected timeout, got ${(connected as McpResult.Failed).failure}",
                connected.failure is McpFailure.Timeout
            )
            // The typed failure is the contract; the sleep is dominated by the loopback server's
            // artificial delay, so this only guards against an unbounded hang.
            assertTrue("connect blocked for ${elapsedMillis}ms", elapsedMillis < 5_000)
        } finally {
            transport.close()
        }
    }

}
