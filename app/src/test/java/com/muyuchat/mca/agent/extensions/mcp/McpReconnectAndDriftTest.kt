package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP reconnect / session-invalidation / credential-revocation / schema-drift behavior.
 *
 * Every case uses an in-memory fake transport: no socket, no TLS, no public MCP server. This proves
 * the client state machine and the executor contract, not real-server interoperability.
 */
class McpReconnectAndDriftTest {
    private val codec = McpJsonCodec()

    private class ScriptedTransport : McpHttpTransport {
        val posts = mutableListOf<McpHttpPost>()
        var handler: (McpHttpPost) -> McpHttpResult = { _ -> McpHttpResult.Failed(McpFailure.Protocol("no_handler")) }

        override suspend fun post(post: McpHttpPost): McpHttpResult {
            posts += post
            return handler(post)
        }

        override suspend fun deleteSession(post: McpHttpPost): Boolean = true

        override fun close() = Unit
    }

    private fun method(post: McpHttpPost): String? {
        val parsed = codec.parse(post.bodyJson) as? McpJsonParseResult.Parsed ?: return null
        return (parsed.value as? McpJson.Obj)?.string("method")
    }

    private fun requestId(post: McpHttpPost): Long? {
        val parsed = codec.parse(post.bodyJson) as? McpJsonParseResult.Parsed ?: return null
        return (parsed.value as? McpJson.Obj)?.numberOrNull("id")?.literal?.toLongOrNull()
    }

    private fun json(body: String, sessionId: String? = null): McpHttpResult =
        McpHttpResult.Json(
            statusCode = 200,
            body = body,
            sessionId = sessionId
        )

    private fun initializeReply(post: McpHttpPost, sessionId: String): McpHttpResult =
        json(
            """{"jsonrpc":"2.0","id":${requestId(post) ?: 0},"result":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"fixture","version":"1"}}}""",
            sessionId
        )

    private fun toolsReply(post: McpHttpPost, schema: String = ECHO_SCHEMA): McpHttpResult =
        json(
            """{"jsonrpc":"2.0","id":${requestId(post) ?: 0},"result":{"tools":[{"name":"echo",""" +
                """"description":"Echo","inputSchema":$schema}]}}"""
        )

    private fun callReply(post: McpHttpPost, text: String = "hello"): McpHttpResult =
        json(
            """{"jsonrpc":"2.0","id":${requestId(post) ?: 0},"result":{"content":[{"type":"text","text":"$text"}]}}"""
        )

    @Test
    fun `session expiry on refresh reconnects exactly once and recovers the tool list`() = runBlocking {
        val transport = ScriptedTransport()
        val sessions = AtomicInteger(0)
        var expireNextList = false
        transport.handler = { post ->
            when (method(post)) {
                McpProtocol.METHOD_INITIALIZE -> {
                    sessions.incrementAndGet()
                    initializeReply(post, "session-${sessions.get()}")
                }
                McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202)
                McpProtocol.METHOD_TOOLS_LIST ->
                    if (expireNextList) {
                        expireNextList = false
                        McpHttpResult.Failed(McpFailure.SessionExpired("mcp_not_found"), 404)
                    } else {
                        toolsReply(post)
                    }
                else -> McpHttpResult.Failed(McpFailure.Protocol("unexpected_method"))
            }
        }
        val client = McpClient(
            config = McpServerConfig("srv", "Server", "https://example.com/mcp"),
            transport = transport,
            requestTimeoutMillis = 2_000
        )

        assertTrue(client.connect() is McpResult.Ok)
        assertEquals("session-1", client.currentSession()?.sessionId)

        expireNextList = true
        val refreshed = client.refreshTools()

        assertTrue("refresh should recover after one reconnect: $refreshed", refreshed is McpResult.Ok)
        assertEquals(1, (refreshed as McpResult.Ok).value.size)
        assertEquals(1L, client.reconnectCount)
        assertEquals("mcp_session_expired", client.lastReconnectReason)
        assertEquals(2, sessions.get())
        assertEquals("session-2", client.currentSession()?.sessionId)
        assertEquals(1, client.currentTools().size)
    }

    @Test
    fun `failed reconnect keeps the original typed failure`() = runBlocking {
        val transport = ScriptedTransport()
        var initialized = false
        transport.handler = { post ->
            when (method(post)) {
                McpProtocol.METHOD_INITIALIZE ->
                    if (initialized) McpHttpResult.Failed(McpFailure.Protocol("mcp_transport_io"))
                    else {
                        initialized = true
                        initializeReply(post, "session-1")
                    }
                McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202)
                McpProtocol.METHOD_TOOLS_LIST -> toolsReply(post)
                else -> McpHttpResult.Failed(McpFailure.Protocol("unexpected_method"))
            }
        }
        val client = McpClient(
            config = McpServerConfig("srv", "Server", "https://example.com/mcp"),
            transport = transport,
            requestTimeoutMillis = 2_000
        )
        client.connect()

        transport.handler = { post ->
            if (method(post) == McpProtocol.METHOD_TOOLS_LIST) {
                McpHttpResult.Failed(McpFailure.SessionExpired("mcp_not_found"), 404)
            } else {
                McpHttpResult.Failed(McpFailure.Protocol("mcp_transport_io"))
            }
        }
        val result = client.refreshTools()

        assertTrue(result is McpResult.Failed)
        assertTrue((result as McpResult.Failed).failure is McpFailure.SessionExpired)
        assertEquals(1L, client.reconnectCount)
        assertNull(client.currentSession())
    }

    @Test
    fun `a side-effecting call is not replayed after session expiry`() = runBlocking {
        val transport = ScriptedTransport()
        val sessions = AtomicInteger(0)
        val callPosts = AtomicInteger(0)
        var expireCalls = true
        transport.handler = { post ->
            when (method(post)) {
                McpProtocol.METHOD_INITIALIZE -> {
                    sessions.incrementAndGet()
                    initializeReply(post, "session-${sessions.get()}")
                }
                McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202)
                McpProtocol.METHOD_TOOLS_LIST -> toolsReply(post)
                McpProtocol.METHOD_TOOLS_CALL -> {
                    callPosts.incrementAndGet()
                    if (expireCalls) {
                        expireCalls = false
                        McpHttpResult.Failed(McpFailure.SessionExpired("mcp_not_found"), 404)
                    } else {
                        callReply(post)
                    }
                }
                else -> McpHttpResult.Failed(McpFailure.Protocol("unexpected_method"))
            }
        }
        val client = McpClient(
            config = McpServerConfig("srv", "Server", "https://example.com/mcp"),
            transport = transport,
            requestTimeoutMillis = 2_000
        )
        client.connect()
        transport.posts.clear()

        val outcome = client.callTool("echo", """{"text":"hi"}""")

        assertTrue(outcome is McpToolCallOutcome.Failed)
        assertTrue((outcome as McpToolCallOutcome.Failed).failure is McpFailure.SessionExpired)
        assertEquals("the expired call must be sent exactly once", 1, callPosts.get())
        assertEquals(1L, client.reconnectCount)
        assertEquals("mcp_session_expired_call", client.lastReconnectReason)
        // The reconnect prepared a fresh session for the next call, without replaying this one.
        assertNotNull(client.currentSession())
        assertEquals(2, sessions.get())
        assertEquals("session-2", client.currentSession()?.sessionId)
    }

    @Test
    fun `a revoked credential is refused before any request is built`() = runBlocking {
        val transport = ScriptedTransport()
        transport.handler = { post -> initializeReply(post, "session-1") }
        val client = McpClient(
            config = McpServerConfig(
                "srv",
                "Server",
                "https://example.com/mcp",
                auth = McpAuth.SecretReference("cred-1")
            ),
            transport = transport,
            credentials = McpCredentialProvider { null },
            requestTimeoutMillis = 2_000
        )

        val result = client.connect()

        assertTrue(result is McpResult.Failed)
        val failure = (result as McpResult.Failed).failure
        assertTrue(failure is McpFailure.Authentication)
        assertEquals("mcp_credential_revoked", failure.code)
        assertTrue("a revoked credential must not send anything", transport.posts.isEmpty())
    }

    @Test
    fun `revocation after connect blocks the next call`() = runBlocking {
        val transport = ScriptedTransport()
        transport.handler = { post ->
            when (method(post)) {
                McpProtocol.METHOD_INITIALIZE -> initializeReply(post, "session-1")
                McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202)
                McpProtocol.METHOD_TOOLS_LIST -> toolsReply(post)
                McpProtocol.METHOD_TOOLS_CALL -> callReply(post)
                else -> McpHttpResult.Failed(McpFailure.Protocol("unexpected_method"))
            }
        }
        var token: String? = "token-1"
        val client = McpClient(
            config = McpServerConfig(
                "srv",
                "Server",
                "https://example.com/mcp",
                auth = McpAuth.SecretReference("cred-1")
            ),
            transport = transport,
            credentials = McpCredentialProvider { token },
            requestTimeoutMillis = 2_000
        )
        client.connect()
        transport.posts.clear()

        token = null
        val outcome = client.callTool("echo", """{"text":"hi"}""")

        assertTrue(outcome is McpToolCallOutcome.Failed)
        assertTrue((outcome as McpToolCallOutcome.Failed).failure is McpFailure.Authentication)
        assertTrue(transport.posts.isEmpty())
    }

    private class DriftingCaller(var liveHash: String?) : McpToolCaller {
        var calls = 0

        override suspend fun callTool(remoteName: String, argumentsJson: String): McpToolCallOutcome {
            calls += 1
            return McpToolCallOutcome.Success(listOf("ok"), null, emptyList())
        }

        override fun currentSchemaHash(remoteName: String): String? = liveHash
    }

    private fun remoteTool(schemaHash: String, schema: String = ECHO_SCHEMA): McpRemoteTool = McpRemoteTool(
        serverId = "srv",
        remoteName = "echo",
        displayName = "Echo",
        description = "Echo text",
        inputSchemaJson = schema,
        schemaHash = schemaHash,
        readOnlyHint = null,
        destructiveHint = null
    )

    private fun commandFor(toolKey: String, version: String, schemaHash: String): FrozenToolCommand =
        FrozenToolCommand(
            invocationId = "inv-1",
            runId = "run-1",
            stepId = "step-1",
            callOrdinal = 0,
            toolKey = toolKey,
            toolVersion = version,
            schemaHash = schemaHash,
            normalizedArgumentsJson = """{"text":"hi"}""",
            argumentsHash = "arguments-hash",
            resourceFingerprint = "resource-fingerprint",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )

    @Test
    fun `schema drift is visible and the approved call is not dispatched`() = runBlocking {
        val approvedHash = com.muyuchat.mca.agent.tools.AgentToolJson.sha256Hex(ECHO_SCHEMA)
        val caller = DriftingCaller(approvedHash)
        val registration = McpToolCatalogFactory(caller, SupportedToolSchemaValidator())
            .build("srv", listOf(remoteTool(approvedHash)))
        val executor = registration.accepted.single()
        val command = commandFor(
            executor.descriptor.toolKey,
            executor.descriptor.version,
            executor.descriptor.schemaHash
        )
        val context = AgentToolContext(
            taskId = "task-1",
            workspaceId = "workspace-1",
            runId = "run-1",
            stepIndex = 0,
            runEpoch = 1L,
            policyVersion = 1L,
            workspaceReadGranted = true
        )

        assertEquals(AgentToolSideEffect.EXTERNAL_SIDE_EFFECT, executor.descriptor.sideEffect)
        val first = executor.execute(command, context)
        assertEquals(AgentToolResultStatus.SUCCEEDED, first.status)
        assertEquals(1, caller.calls)

        caller.liveHash = "b".repeat(64)
        val drifted = executor.execute(command, context)

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, drifted.status)
        assertEquals(McpToolExecutor.MCP_SCHEMA_DRIFT, drifted.errorCode)
        assertEquals("a drifted call must not reach the server", 1, caller.calls)

        caller.liveHash = null
        val unknownCatalog = executor.execute(command, context)
        assertEquals(AgentToolResultStatus.SUCCEEDED, unknownCatalog.status)
        assertEquals(2, caller.calls)
    }

    private companion object {
        const val ECHO_SCHEMA =
            """{"additionalProperties":false,"properties":{"text":{"type":"string"}},""" +
                """"required":["text"],"type":"object"}"""
    }
}
