package com.muyuchat.mca.agent.mcptest

import com.muyuchat.mca.agent.extensions.mcp.McpClient
import com.muyuchat.mca.agent.extensions.mcp.McpHttpTransport
import com.muyuchat.mca.agent.extensions.mcp.McpResult
import com.muyuchat.mca.agent.extensions.mcp.McpServerConfig
import com.muyuchat.mca.agent.extensions.mcp.McpToolCallOutcome
import com.muyuchat.mca.agent.extensions.mcp.OkHttpMcpTransport
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
 * Device-only MCP client/transport proof against a loopback server running inside the test process.
 *
 * This exercises Android's real OkHttp stack, cleartext local-server policy, JSON-RPC handshake,
 * session header, tools/list and tools/call without any external server or adb reverse.
 */
class McpDeviceLoopbackTest {

    private var socket: ServerSocket? = null

    @After
    fun tearDown() {
        socket?.close()
    }

    private fun startServer(): String {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        socket = server
        thread(isDaemon = true, name = "mcp-device-loopback") {
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
            var length = 0
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(chars, read, length - read)
                if (n <= 0) break
                read += n
            }
            val request = String(chars, 0, read)
            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(request)?.groupValues?.get(1)
            val output = connection.getOutputStream()
            fun write(status: String, payload: String) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val head = ("HTTP/1.1 " + status + "\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + bytes.size + "\r\n" +
                    "Mcp-Session-Id: sess-device\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                output.write(head)
                output.write(bytes)
                output.flush()
            }
            when {
                request.contains("\"method\":\"initialize\"") -> write(
                    "200 OK",
                    """{"jsonrpc":"2.0","id":${id ?: "1"},"result":{"protocolVersion":"2025-06-18",""" +
                        """"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"device","version":"1"}}}"""
                )
                request.contains("notifications/initialized") -> {
                    val head = "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    output.write(head.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                request.contains("\"method\":\"tools/list\"") -> write(
                    "200 OK",
                    """{"jsonrpc":"2.0","id":${id ?: "2"},"result":{"tools":[{"name":"echo",""" +
                        """"description":"Echo","inputSchema":{"type":"object",""" +
                        """"properties":{"text":{"type":"string"}},"required":["text"],""" +
                        """"additionalProperties":false}}]}}"""
                )
                request.contains("\"method\":\"tools/call\"") -> write(
                    "200 OK",
                    """{"jsonrpc":"2.0","id":${id ?: "3"},"result":{"content":[""" +
                        """{"type":"text","text":"device-hello"}],"isError":false}}"""
                )
                else -> write("404 Not Found", """{"error":"unknown"}""")
            }
        }
    }

    @Test
    fun handshakeAndToolCallRunOnDevice() = runBlocking {
        val endpoint = startServer()
        val transport: McpHttpTransport = OkHttpMcpTransport()
        val client = McpClient(
            config = McpServerConfig(
                serverId = "device-loopback",
                displayName = "Device loopback",
                endpoint = endpoint,
                allowLocalServer = true
            ),
            transport = transport
        )
        try {
            val connected = client.connect()
            assertTrue("connect failed: $connected", connected is McpResult.Ok)
            assertEquals("sess-device", (connected as McpResult.Ok).value.sessionId)
            assertEquals(listOf("echo"), client.currentTools().map { it.remoteName })

            val call = client.callTool("echo", """{"text":"hi"}""")
            assertTrue("call failed: $call", call is McpToolCallOutcome.Success)
            assertEquals(listOf("device-hello"), (call as McpToolCallOutcome.Success).textBlocks)
        } finally {
            client.disconnect()
            transport.close()
        }
    }
}
