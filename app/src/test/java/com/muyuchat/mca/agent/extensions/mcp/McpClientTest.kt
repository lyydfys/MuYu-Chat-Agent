package com.muyuchat.mca.agent.extensions.mcp

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private lateinit var server: McpTestHttpServer
    private lateinit var client: McpClient
    private val codec = McpJsonCodec()
    private val requestCounter = AtomicInteger(0)
    private val lastHeaders = HashMap<String, String>()
    private var handler: (McpTestRequest) -> McpTestResponse = { request -> defaultHandler(request) }

    @Before
    fun setUp() {
        requestCounter.set(0)
        server = McpTestHttpServer { request -> handler(request) }
        client = McpClient(
            config = McpServerConfig(
                serverId = "test-server",
                displayName = "Test",
                endpoint = server.url,
                allowLocalServer = true
            ),
            transport = OkHttpMcpTransport(),
            requestTimeoutMillis = 5_000
        )
    }

    @After
    fun tearDown() {
        runBlocking { client.disconnect() }
        server.stop()
    }

    private fun defaultHandler(request: McpTestRequest): McpTestResponse {
        requestCounter.incrementAndGet()
        request.headers.forEach { (name, value) ->
            if (name == McpProtocol.HEADER_SESSION_ID.lowercase() ||
                name == McpProtocol.HEADER_PROTOCOL_VERSION.lowercase()
            ) {
                lastHeaders[name] = value
            }
        }
        val json = (codec.parse(request.body) as McpJsonParseResult.Parsed).value.asObj()!!
        val method = json.string("method").orEmpty()
        val id = json.numberOrNull("id")?.literal?.toLongOrNull()
        return when (method) {
            McpProtocol.METHOD_INITIALIZE -> jsonResponse(
                resultJson(
                    id,
                    """{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},""" +
                        """"serverInfo":{"name":"fixture","version":"1"}}"""
                ),
                sessionId = "sess-1"
            )
            McpProtocol.METHOD_INITIALIZED -> McpTestResponse(status = 202)
            McpProtocol.METHOD_TOOLS_LIST -> jsonResponse(
                resultJson(
                    id,
                    """{"tools":[{"name":"echo","description":"Echo text","inputSchema":""" +
                        """{"type":"object","properties":{"text":{"type":"string"}},""" +
                        """"required":["text"],"additionalProperties":false}}]}"""
                )
            )
            McpProtocol.METHOD_TOOLS_CALL -> jsonResponse(
                resultJson(id, """{"content":[{"type":"text","text":"hello"}]}""")
            )
            else -> jsonResponse(errorJson(id, McpProtocol.JSONRPC_METHOD_NOT_FOUND, "Method not found"))
        }
    }

    private fun resultJson(id: Long?, result: String): String =
        """{"jsonrpc":"2.0","id":${id ?: 0},"result":$result}"""

    private fun errorJson(id: Long?, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${id ?: 0},"error":{"code":$code,"message":"$message"}}"""

    private fun jsonResponse(body: String, sessionId: String? = null): McpTestResponse =
        McpTestResponse(
            status = 200,
            headers = buildMap {
                put("Content-Type", "application/json")
                sessionId?.let { put(McpProtocol.HEADER_SESSION_ID, it) }
            },
            body = body
        )

    @Test
    fun handshakeDiscoversToolsAndCallsThem() = runBlocking {
        val connected = client.connect()
        assertTrue(connected is McpResult.Ok)
        val session = (connected as McpResult.Ok).value
        assertEquals("sess-1", session.sessionId)
        assertEquals(McpProtocol.VERSION_2025_06_18, session.negotiatedVersion)
        assertEquals(1, client.currentTools().size)

        val outcome = client.callTool("echo", """{"text":"hi"}""")
        assertTrue(outcome is McpToolCallOutcome.Success)
        assertEquals(listOf("hello"), (outcome as McpToolCallOutcome.Success).textBlocks)
        assertTrue(requestCounter.get() >= 4)
    }

    @Test
    fun sessionAndProtocolHeadersAreSentOnSubsequentRequests() = runBlocking {
        client.connect()
        assertEquals("sess-1", lastHeaders[McpProtocol.HEADER_SESSION_ID.lowercase()])
        assertEquals(
            McpProtocol.VERSION_2025_06_18,
            lastHeaders[McpProtocol.HEADER_PROTOCOL_VERSION.lowercase()]
        )
    }

    @Test
    fun sseResponseIsParsedAsOneMessage() = runBlocking {
        client.connect()
        handler = { request ->
            val json = (codec.parse(request.body) as McpJsonParseResult.Parsed).value.asObj()!!
            val id = json.numberOrNull("id")?.literal?.toLongOrNull()
            if (json.string("method") == McpProtocol.METHOD_TOOLS_CALL) {
                val event = "data: " +
                    resultJson(id, """{"content":[{"type":"text","text":"from-sse"}]}""") + "\n\n"
                McpTestResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "text/event-stream"),
                    body = event
                )
            } else {
                jsonResponse(resultJson(id, """{"tools":[]}"""))
            }
        }
        val outcome = client.callTool("echo", """{"text":"hi"}""")
        assertTrue(outcome is McpToolCallOutcome.Success)
        assertEquals(listOf("from-sse"), (outcome as McpToolCallOutcome.Success).textBlocks)
    }

    @Test
    fun notFoundAfterSessionIsReportedAsSessionExpiredAndNeverReplayed() = runBlocking {
        client.connect()
        handler = { McpTestResponse(status = 404) }
        val refreshed = client.refreshTools()
        assertTrue(refreshed is McpResult.Failed)
        assertTrue((refreshed as McpResult.Failed).failure is McpFailure.SessionExpired)
        assertNull(client.currentSession())
    }

    @Test
    fun serverRequestIsRejectedAndDoesNotBecomeAToolResult() = runBlocking {
        client.connect()
        val rejectionCount = AtomicInteger(0)
        handler = { request ->
            val json = (codec.parse(request.body) as McpJsonParseResult.Parsed).value.asObj()!!
            if (json.string("method") == null) {
                rejectionCount.incrementAndGet()
                McpTestResponse(status = 200)
            } else {
                jsonResponse(
                    """{"jsonrpc":"2.0","id":99,"method":"sampling/createMessage","params":{}}"""
                )
            }
        }
        val outcome = client.callTool("echo", """{"text":"hi"}""")
        assertTrue(outcome is McpToolCallOutcome.Failed)
        assertTrue((outcome as McpToolCallOutcome.Failed).failure is McpFailure.UnsupportedCapability)
        assertTrue(rejectionCount.get() >= 1)
    }

    @Test
    fun insecureRemoteEndpointIsRejectedBeforeConnecting() = runBlocking {
        val remote = McpClient(
            config = McpServerConfig("remote", "Remote", "http://example.com/mcp"),
            transport = OkHttpMcpTransport()
        )
        val result = remote.connect()
        assertTrue(result is McpResult.Failed)
        assertTrue((result as McpResult.Failed).failure is McpFailure.Endpoint)
    }
}
