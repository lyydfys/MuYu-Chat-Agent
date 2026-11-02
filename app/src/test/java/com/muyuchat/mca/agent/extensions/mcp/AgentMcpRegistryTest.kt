package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.mca.agent.tools.AgentToolCatalogAssembler
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import com.muyuchat.mca.agent.extensions.skills.AgentSkillSnapshotSkill
import com.muyuchat.mca.agent.extensions.skills.AgentSkillToolPattern
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentMcpRegistryTest {
    private lateinit var filesDir: File
    private lateinit var store: AgentMcpServerStore
    private val codec = McpJsonCodec()

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mcp-registry-test").toFile()
        store = AgentMcpServerStore(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `egress denial never creates a client or transport`() = runBlocking {
        val created = AtomicInteger(0)
        val registry = AgentMcpRegistry(
            store = store,
            egressGuard = AgentNetworkEgressGuard {
                AgentMcpEgressDecision.Denied("EGRESS_DENIED", "MCP egress is not granted for this run.")
            },
            clientFactory = AgentMcpClientFactory {
                created.incrementAndGet()
                throw AssertionError("client factory must not be called when egress is denied")
            }
        )
        store.add(record("alpha", "https://example.com/mcp"))

        val status = registry.connect("alpha")

        assertTrue(status is AgentMcpServerStatus.Failed)
        assertEquals("EGRESS_DENIED", (status as AgentMcpServerStatus.Failed).code)
        assertEquals(0, created.get())
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `disabled server never reaches the client factory`() = runBlocking {
        val created = AtomicInteger(0)
        val registry = AgentMcpRegistry(
            store = store,
            egressGuard = allowAll,
            clientFactory = AgentMcpClientFactory {
                created.incrementAndGet()
                throw AssertionError("client factory must not be called for a disabled server")
            }
        )
        store.add(record("alpha", "https://example.com/mcp", enabled = false))

        assertTrue(registry.connect("alpha") is AgentMcpServerStatus.Disabled)
        val snapshot = registry.refreshAndConnectEnabled()
        assertTrue(snapshot.servers.single() is AgentMcpServerStatus.Disabled)
        assertEquals(0, created.get())
    }

    @Test
    fun `refresh connects an enabled server and exposes its namespaced adapters`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = registryWithTransport(handshakeTransport())

        val snapshot = registry.refreshAndConnectEnabled()

        val status = snapshot.servers.single()
        assertTrue(status is AgentMcpServerStatus.Connected)
        assertEquals(1, (status as AgentMcpServerStatus.Connected).toolCount)
        assertEquals(1, snapshot.adapters.size)
        assertEquals("mcp:alpha:echo", snapshot.adapters.single().descriptor.toolKey)
        assertEquals(setOf("alpha"), snapshot.connectedServerIds)
    }

    @Test
    fun `disconnect and remove drop tools and fence the generation`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = registryWithTransport(handshakeTransport())
        registry.refreshAndConnectEnabled()
        val connected = registry.snapshot().servers.single() as AgentMcpServerStatus.Connected

        val disconnected = registry.disconnect("alpha")
        assertTrue(disconnected is AgentMcpServerStatus.Disconnected)
        assertTrue(disconnected.generation > connected.generation)
        assertTrue(registry.snapshot().adapters.isEmpty())

        val removed = registry.remove("alpha")
        assertTrue(removed.servers.isEmpty())
        assertTrue(removed.adapters.isEmpty())
        assertTrue((store.load() as AgentMcpServerLoad.Loaded).servers.isEmpty())
    }

    @Test
    fun `stale connect completion cannot re-add tools after disconnect`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = handshakeTransport(gate = release, started = started)
        val registry = registryWithTransport(transport)

        val job = launch(Dispatchers.Default) { registry.connect("alpha") }
        started.await()
        val staleGeneration = registry.snapshot().servers.single().generation
        assertTrue(registry.snapshot().servers.single() is AgentMcpServerStatus.Connecting)

        registry.disconnect("alpha")
        release.complete(Unit)
        job.join()

        val status = registry.snapshot().servers.single()
        assertTrue(status is AgentMcpServerStatus.Disconnected)
        assertTrue(status.generation > staleGeneration)
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `connect timeout is typed and leaves no adapters`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = AgentMcpRegistry(
            store = store,
            egressGuard = allowAll,
            clientFactory = clientFactory(handshakeTransport(delayMillis = 5_000)),
            connectTimeoutMillis = 100
        )

        val status = registry.connect("alpha")

        assertTrue(status is AgentMcpServerStatus.Failed)
        assertEquals("MCP_CONNECT_TIMEOUT", (status as AgentMcpServerStatus.Failed).code)
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `initialize failure is typed and drops tools`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = registryWithTransport(
            handshakeTransport(initializeFailure = McpHttpResult.Failed(McpFailure.Protocol("mcp_http_500"), 500))
        )

        val status = registry.connect("alpha")

        assertTrue(status is AgentMcpServerStatus.Failed)
        assertEquals("mcp_http_500", (status as AgentMcpServerStatus.Failed).code)
        assertTrue(registry.snapshot().adapters.isEmpty())
    }

    @Test
    fun `corrupt registry reports the problem and connects nothing`() = runBlocking {
        store.path.parentFile.mkdirs()
        store.path.writeText("{ definitely not json", Charsets.UTF_8)
        val created = AtomicInteger(0)
        val registry = AgentMcpRegistry(
            store = store,
            egressGuard = allowAll,
            clientFactory = AgentMcpClientFactory {
                created.incrementAndGet()
                throw AssertionError("corrupt store must not connect")
            }
        )

        val snapshot = registry.refreshAndConnectEnabled()

        assertEquals("MCP_STORE_CORRUPT_JSON", snapshot.storeProblemCode)
        assertTrue(snapshot.servers.isEmpty())
        assertTrue(snapshot.adapters.isEmpty())
        assertEquals(0, created.get())
    }

    @Test
    fun `set enabled closes the client and can reconnect`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = registryWithTransport(handshakeTransport())
        registry.refreshAndConnectEnabled()
        val connectedGeneration = registry.snapshot().servers.single().generation

        val disabled = registry.setEnabled("alpha", false)
        assertTrue(disabled is AgentMcpServerStatus.Disabled)
        assertTrue(disabled.generation > connectedGeneration)
        assertTrue(registry.snapshot().adapters.isEmpty())

        val enabled = registry.setEnabled("alpha", true)
        assertTrue(enabled is AgentMcpServerStatus.Disconnected)
        val reconnected = registry.connect("alpha")
        assertTrue(reconnected is AgentMcpServerStatus.Connected)
        assertEquals(1, registry.snapshot().adapters.size)
    }

    @Test
    fun `loopback fixture server completes the real handshake`() = runBlocking {
        val server = McpTestHttpServer(fixtureHandler())
        try {
            store.add(record("local", server.url, allowLocal = true))
            val registry = registryWithTransport(OkHttpMcpTransport(), requestTimeoutMillis = 5_000L)

            val snapshot = registry.refreshAndConnectEnabled()

            val status = snapshot.servers.single()
            assertTrue(status is AgentMcpServerStatus.Connected)
            val connected = status as AgentMcpServerStatus.Connected
            assertTrue(connected.origin.startsWith("http://127.0.0.1:"))
            assertEquals(1, connected.toolCount)
            assertEquals("mcp:local:echo", snapshot.adapters.single().descriptor.toolKey)
            registry.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `registry adapters feed the catalog assembler and skills still narrow`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = registryWithTransport(handshakeTransport())
        registry.refreshAndConnectEnabled()
        val adapters = registry.snapshot().adapters
        val builtIns = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
        val assembler = AgentToolCatalogAssembler()

        val all = assembler.assemble(builtIns, emptyList(), adapters)
        assertTrue(all is AgentToolCatalogAssemblyResult.Assembled)
        val allAssembled = all as AgentToolCatalogAssemblyResult.Assembled
        assertNotNull(allAssembled.catalog.find("mcp:alpha:echo"))
        assertNotNull(allAssembled.catalog.find("workspace.list"))

        val narrowed = assembler.assemble(
            builtIns,
            listOf(skill("workspace-only", listOf("workspace.*"))),
            adapters
        ) as AgentToolCatalogAssemblyResult.Assembled
        assertNull(narrowed.catalog.find("mcp:alpha:echo"))
        assertTrue(narrowed.effectiveToolKeys.all { it.startsWith("workspace.") })
    }

    @Test
    fun `egress denied adapters are absent from the assembled catalog`() = runBlocking {
        store.add(record("alpha", "https://example.com/mcp"))
        val registry = AgentMcpRegistry(
            store = store,
            egressGuard = AgentNetworkEgressGuard {
                AgentMcpEgressDecision.Denied("POLICY_EGRESS_DENIED", "denied")
            },
            clientFactory = AgentMcpClientFactory {
                throw AssertionError("no client on denial")
            }
        )
        registry.refreshAndConnectEnabled()
        assertTrue(registry.snapshot().adapters.isEmpty())

        val builtIns = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
        val assembled = AgentToolCatalogAssembler()
            .assemble(builtIns, emptyList(), registry.snapshot().adapters)
            as AgentToolCatalogAssemblyResult.Assembled
        assertNull(assembled.catalog.find("mcp:alpha:echo"))
        assertEquals(builtIns.snapshot().map { it.toolKey }.toSet(), assembled.effectiveToolKeys)
    }

    private val allowAll = AgentNetworkEgressGuard { AgentMcpEgressDecision.Allowed }

    private fun registryWithTransport(
        transport: McpHttpTransport,
        requestTimeoutMillis: Long = 5_000L
    ): AgentMcpRegistry = AgentMcpRegistry(
        store = store,
        egressGuard = allowAll,
        clientFactory = clientFactory(transport, requestTimeoutMillis)
    )

    private fun clientFactory(
        transport: McpHttpTransport,
        requestTimeoutMillis: Long = 5_000L
    ): AgentMcpClientFactory = AgentMcpClientFactory { config ->
        McpClient(config, transport, requestTimeoutMillis = requestTimeoutMillis)
    }

    private fun record(
        serverId: String,
        endpoint: String,
        allowLocal: Boolean = false,
        enabled: Boolean = true
    ): AgentMcpServerRecord = AgentMcpServerRecord(
        serverId = serverId,
        displayName = "Server $serverId",
        endpoint = endpoint,
        enabled = enabled,
        allowLocal = allowLocal,
        createdAtMillis = 1_000L
    )

    private fun skill(id: String, patterns: List<String>): AgentSkillSnapshotSkill =
        AgentSkillSnapshotSkill(
            id = id,
            version = 1L,
            name = "Skill $id",
            sha256 = "a".repeat(64),
            packageVersion = "1",
            entryPath = "SKILL.md",
            declaredToolPatterns = patterns.map { AgentSkillToolPattern.parse(it) },
            sourceFingerprint = "tree:" + "b".repeat(64)
        )

    private fun handshakeTransport(
        gate: CompletableDeferred<Unit>? = null,
        started: CompletableDeferred<Unit>? = null,
        delayMillis: Long = 0L,
        initializeFailure: McpHttpResult.Failed? = null
    ): McpHttpTransport = object : McpHttpTransport {
        override suspend fun post(post: McpHttpPost): McpHttpResult {
            if (delayMillis > 0L) delay(delayMillis)
            val message = (codec.parse(post.bodyJson) as McpJsonParseResult.Parsed).value.asObj()!!
            val method = message.string("method").orEmpty()
            val id = message.numberOrNull("id")?.literal?.toLongOrNull()
            return when (method) {
                McpProtocol.METHOD_INITIALIZE -> {
                    started?.complete(Unit)
                    gate?.await()
                    initializeFailure ?: McpHttpResult.Json(200, initializeResult(id), SESSION_ID)
                }
                McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202, SESSION_ID)
                McpProtocol.METHOD_TOOLS_LIST -> McpHttpResult.Json(200, toolsResult(id), SESSION_ID)
                else -> McpHttpResult.Failed(McpFailure.Protocol("mcp_unexpected_method"))
            }
        }

        override suspend fun deleteSession(post: McpHttpPost): Boolean = true

        override fun close() = Unit
    }

    private fun fixtureHandler(): (McpTestRequest) -> McpTestResponse = { request ->
        val message = (codec.parse(request.body) as McpJsonParseResult.Parsed).value.asObj()!!
        val method = message.string("method").orEmpty()
        val id = message.numberOrNull("id")?.literal?.toLongOrNull()
        when (method) {
            McpProtocol.METHOD_INITIALIZE -> McpTestResponse(
                status = 200,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    McpProtocol.HEADER_SESSION_ID to SESSION_ID
                ),
                body = initializeResult(id)
            )
            McpProtocol.METHOD_INITIALIZED -> McpTestResponse(status = 202)
            McpProtocol.METHOD_TOOLS_LIST -> McpTestResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = toolsResult(id)
            )
            else -> McpTestResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"jsonrpc":"2.0","id":${id ?: 0},"error":{"code":-32601,"message":"nope"}}"""
            )
        }
    }

    private fun initializeResult(id: Long?): String =
        """{"jsonrpc":"2.0","id":${id ?: 0},"result":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"fixture","version":"1"}}}"""

    private fun toolsResult(id: Long?): String =
        """{"jsonrpc":"2.0","id":${id ?: 0},"result":{"tools":[{"name":"echo","description":"Echo",""" +
            """"inputSchema":{"type":"object","properties":{"text":{"type":"string"}},""" +
            """"required":["text"],"additionalProperties":false}}]}}"""

    private companion object {
        const val SESSION_ID = "sess-registry"
    }
}
