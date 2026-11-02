package com.muyuchat.mca.agent.extensions.mcp

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private data class ToolDef(val name: String, val schemaJson: String)

private const val SESSION_ID = "sess-drift"
private const val ECHO_SCHEMA_A =
    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}"
private const val ECHO_SCHEMA_B =
    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"additionalProperties\":false}"
private const val SUM_SCHEMA =
    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}"

/**
 * X09: schema-drift / catalog-change callback contract for [AgentMcpRegistry].
 *
 * The fake transport serves `initialize` -> `tools/list` and allows the tool catalog to change
 * between calls (add/remove/schema edit), so no network is involved.
 */
class AgentMcpCatalogChangeTest {
    private lateinit var filesDir: File
    private lateinit var store: AgentMcpServerStore
    private lateinit var transport: CatalogTransport
    private lateinit var registry: AgentMcpRegistry
    private val events = ArrayList<AgentMcpCatalogChange>()

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mcp-catalog-change-test").toFile()
        store = AgentMcpServerStore(filesDir)
        transport = CatalogTransport()
        events.clear()
        store.add(
            AgentMcpServerRecord(
                serverId = "alpha",
                displayName = "Server alpha",
                endpoint = "https://example.com/mcp",
                createdAtMillis = 1_000L
            )
        )
        registry = AgentMcpRegistry(
            store = store,
            egressGuard = AgentNetworkEgressGuard { AgentMcpEgressDecision.Allowed },
            clientFactory = AgentMcpClientFactory { config ->
                McpClient(config, transport, requestTimeoutMillis = 5_000L)
            },
            catalogChangeListener = AgentMcpCatalogChangeListener { change -> events += change }
        )
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `initial connect and unchanged refresh emit no catalog change`() = runBlocking {
        val connected = registry.connect("alpha") as AgentMcpServerStatus.Connected
        assertTrue(events.isEmpty())

        val refreshed = registry.refreshTools("alpha") as AgentMcpServerStatus.Connected

        assertEquals(connected.generation, refreshed.generation)
        assertTrue(events.isEmpty())
        assertEquals(1, registry.snapshot().adapters.size)
    }

    @Test
    fun `added tool emits one callback and bumps the generation`() = runBlocking {
        val connected = registry.connect("alpha") as AgentMcpServerStatus.Connected
        transport.tools = listOf(ToolDef("echo", ECHO_SCHEMA_A), ToolDef("sum", SUM_SCHEMA))

        val refreshed = registry.refreshTools("alpha") as AgentMcpServerStatus.Connected

        val change = events.single()
        assertEquals("alpha", change.serverId)
        assertEquals(refreshed.generation, change.generation)
        assertTrue(refreshed.generation > connected.generation)
        assertEquals(listOf("mcp:alpha:sum"), change.addedKeys)
        assertTrue(change.removedKeys.isEmpty())
        assertTrue(change.changedSchemaKeys.isEmpty())
        assertEquals(listOf("mcp:alpha:sum"), change.affectedKeys)
        assertEquals(2, registry.snapshot().adapters.size)

        registry.refreshTools("alpha")
        assertEquals(1, events.size)
    }

    @Test
    fun `removed tool emits once with the removed key`() = runBlocking {
        registry.connect("alpha")
        transport.tools = emptyList()

        registry.refreshTools("alpha")

        val change = events.single()
        assertEquals(listOf("mcp:alpha:echo"), change.removedKeys)
        assertTrue(change.addedKeys.isEmpty())
        assertTrue(change.changedSchemaKeys.isEmpty())
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `schema hash change emits changedSchemaKeys and replaces the adapter`() = runBlocking {
        registry.connect("alpha")
        val before = registry.snapshot().adapters.single().descriptor.schemaHash
        transport.tools = listOf(ToolDef("echo", ECHO_SCHEMA_B))

        registry.refreshTools("alpha")

        val change = events.single()
        assertEquals(listOf("mcp:alpha:echo"), change.changedSchemaKeys)
        assertTrue(change.addedKeys.isEmpty())
        assertTrue(change.removedKeys.isEmpty())
        val after = registry.snapshot().adapters.single().descriptor.schemaHash
        assertTrue(after != before)
        assertEquals(listOf("mcp:alpha:echo"), change.affectedKeys)
    }

    @Test
    fun `disconnect and remove never emit a catalog change`() = runBlocking {
        registry.connect("alpha")
        registry.disconnect("alpha")
        val afterDisconnect = registry.refreshTools("alpha")
        assertTrue(afterDisconnect is AgentMcpServerStatus.Disconnected)
        assertTrue(events.isEmpty())

        registry.connect("alpha")
        registry.remove("alpha")
        registry.refreshTools("alpha")
        assertTrue(events.isEmpty())
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `stale refresh completion after disconnect emits nothing`() = runBlocking {
        registry.connect("alpha")
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        transport.tools = listOf(ToolDef("sum", SUM_SCHEMA))
        transport.toolsStarted = started
        transport.toolsGate = gate

        val job = launch(Dispatchers.Default) { registry.refreshTools("alpha") }
        started.await()
        registry.disconnect("alpha")
        gate.complete(Unit)
        job.join()

        assertTrue(events.isEmpty())
        assertTrue(registry.snapshot().adapters.isEmpty())
        assertTrue(registry.snapshot().servers.single() is AgentMcpServerStatus.Disconnected)
    }

    @Test
    fun `refresh failure drops tools and emits no catalog change`() = runBlocking {
        registry.connect("alpha")
        transport.failToolsList = true

        val status = registry.refreshTools("alpha")

        assertTrue(status is AgentMcpServerStatus.Failed)
        assertTrue(events.isEmpty())
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `a throwing listener does not break refresh`() = runBlocking {
        registry.connect("alpha")
        registry.setCatalogChangeListener {
            throw IllegalStateException("consumer fault")
        }
        transport.tools = listOf(ToolDef("echo", ECHO_SCHEMA_A), ToolDef("sum", SUM_SCHEMA))

        val status = registry.refreshTools("alpha")

        assertTrue(status is AgentMcpServerStatus.Connected)
        assertEquals(2, registry.snapshot().adapters.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `reconnect with a drifted schema emits exactly one change`() = runBlocking {
        val first = registry.connect("alpha") as AgentMcpServerStatus.Connected
        transport.tools = listOf(ToolDef("echo", ECHO_SCHEMA_B))

        val second = registry.connect("alpha") as AgentMcpServerStatus.Connected

        val change = events.single()
        assertEquals(listOf("mcp:alpha:echo"), change.changedSchemaKeys)
        assertEquals(second.generation, change.generation)
        assertTrue(second.generation > first.generation)
        assertEquals(1, events.size)
    }

    @Test
    fun `refreshConnectedTools refreshes only connected servers`() = runBlocking {
        store.add(
            AgentMcpServerRecord(
                serverId = "beta",
                displayName = "Server beta",
                endpoint = "https://example.com/mcp",
                enabled = false,
                createdAtMillis = 2_000L
            )
        )
        registry.connect("alpha")
        transport.tools = listOf(ToolDef("echo", ECHO_SCHEMA_A), ToolDef("sum", SUM_SCHEMA))

        registry.refreshConnectedTools()

        val change = events.single()
        assertEquals("alpha", change.serverId)
        assertEquals(listOf("mcp:alpha:sum"), change.addedKeys)
    }
}

private class CatalogTransport : McpHttpTransport {
    private val codec = McpJsonCodec()
    var tools: List<ToolDef> = listOf(ToolDef("echo", ECHO_SCHEMA_A))
    var toolsStarted: CompletableDeferred<Unit>? = null
    var toolsGate: CompletableDeferred<Unit>? = null
    var failToolsList: Boolean = false
    val toolsListCalls = AtomicInteger(0)

    override suspend fun post(post: McpHttpPost): McpHttpResult {
        val message = (codec.parse(post.bodyJson) as McpJsonParseResult.Parsed).value.asObj()!!
        val method = message.string("method").orEmpty()
        val id = message.numberOrNull("id")?.literal?.toLongOrNull()
        return when (method) {
            McpProtocol.METHOD_INITIALIZE -> McpHttpResult.Json(
                200,
                """{"jsonrpc":"2.0","id":${id ?: 0},"result":{"protocolVersion":"2025-06-18",""" +
                    """"capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"fixture","version":"1"}}}""",
                SESSION_ID
            )
            McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202, SESSION_ID)
            McpProtocol.METHOD_TOOLS_LIST -> {
                toolsListCalls.incrementAndGet()
                if (failToolsList) {
                    return McpHttpResult.Failed(McpFailure.Protocol("mcp_http_500"), 500)
                }
                toolsStarted?.complete(Unit)
                toolsGate?.await()
                McpHttpResult.Json(200, toolsResult(id), SESSION_ID)
            }
            else -> McpHttpResult.Failed(McpFailure.Protocol("mcp_unexpected_method"))
        }
    }

    override suspend fun deleteSession(post: McpHttpPost): Boolean = true

    override fun close() = Unit

    private fun toolsResult(id: Long?): String {
        val items = tools.joinToString(",") { tool ->
            """{"name":"${tool.name}","description":"tool ${tool.name}","inputSchema":${tool.schemaJson}}"""
        }
        return """{"jsonrpc":"2.0","id":${id ?: 0},"result":{"tools":[$items]}}"""
    }
}
