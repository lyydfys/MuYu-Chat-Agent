package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpClientFactory
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpRunGrantBinder
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerMutation
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerRecord
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerStore
import com.muyuchat.mca.agent.extensions.mcp.McpClient
import com.muyuchat.mca.agent.extensions.mcp.McpFailure
import com.muyuchat.mca.agent.extensions.mcp.McpHttpPost
import com.muyuchat.mca.agent.extensions.mcp.McpHttpResult
import com.muyuchat.mca.agent.extensions.mcp.McpHttpTransport
import com.muyuchat.mca.agent.extensions.mcp.McpJsonCodec
import com.muyuchat.mca.agent.extensions.mcp.McpJsonParseResult
import com.muyuchat.mca.agent.extensions.mcp.McpProtocol
import com.muyuchat.mca.agent.extensions.mcp.asObj
import com.muyuchat.mca.agent.extensions.mcp.numberOrNull
import com.muyuchat.mca.agent.extensions.mcp.string
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressGrantOutcome
import com.muyuchat.mca.agent.security.AgentEgressGrantStore
import com.muyuchat.mca.agent.security.AgentEgressTransport
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0 regression coverage for the MCP egress grant contract:
 * - a standing `allowAgentNetwork` consent can never mint a grant, and no explicit grant means no
 *   client/socket at all;
 * - an already-created run-scoped grant is the only way to connect;
 * - `release` revokes the observed run grants, so a resume cannot reuse them;
 * - LOCAL_ONLY never opens a socket even when a local grant exists.
 */
class RunScopedAgentToolCatalogProviderTest {
    private lateinit var filesDir: File
    private lateinit var store: AgentMcpServerStore
    private lateinit var grantStore: FakeEgressGrantStore
    private lateinit var baseCatalog: AgentToolCatalog

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("run-scoped-catalog-test").toFile()
        store = AgentMcpServerStore(filesDir)
        grantStore = FakeEgressGrantStore()
        baseCatalog = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `no explicit run grant returns base catalog without any client or socket`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        val run = networkRun()
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, grantStore.createCalls.get())
        assertTrue(grantStore.createdGrants.isEmpty())
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())
    }

    @Test
    fun `standing allowAgentNetwork consent can never mint a run grant`() = runBlocking {
        addServer("alpha", "https://example.com/mcp", allowAgentNetwork = true)
        addServer("beta", "https://example.com/mcp2", allowAgentNetwork = true)
        val fixture = fixture()

        fixture.catalogFor(networkRun(), AgentTestFixtures.task())
        fixture.catalogFor(networkRun(runId = "run-2"), AgentTestFixtures.task())

        assertEquals(0, grantStore.createCalls.get())
        assertTrue(grantStore.createdGrants.isEmpty())
        assertEquals(0, fixture.factoryCalls.get())
    }

    @Test
    fun `LOCAL_ONLY run stays on base catalog and opens no socket even with a local grant`() = runBlocking {
        val run = AgentTestFixtures.run()
        grantStore.active = listOf(
            explicitGrant(
                run,
                transport = AgentEgressTransport.LOCAL_SERVER,
                origin = "http://127.0.0.1:9"
            )
        )
        addServer("local", "http://127.0.0.1:9/mcp", allowLocal = true)
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, grantStore.createCalls.get())
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())

        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `explicit run grant connects and release revokes it before a resume`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run))
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertNotNull(catalog.find("mcp:alpha:echo"))
        assertNotNull(catalog.find("workspace.list"))
        assertEquals(0, grantStore.createCalls.get())
        assertTrue(grantStore.createdGrants.isEmpty())
        assertEquals(1, fixture.factoryCalls.get())

        fixture.release(run)

        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
        assertTrue(grantStore.active.isEmpty())
        assertEquals(1, fixture.transport.deleteSessionCalls.get())

        // The same run id cannot reuse a completed execution's grant on resume.
        val postsAfterRelease = fixture.transport.posts.get()
        val resumed = fixture.catalogFor(run, AgentTestFixtures.task())
        assertSame(baseCatalog, resumed)
        assertEquals(1, fixture.factoryCalls.get())
        assertEquals(postsAfterRelease, fixture.transport.posts.get())

        fixture.release(run)
        assertEquals(1, grantStore.revokedGrantIds.size)
    }

    @Test
    fun `release closes the run session and is idempotent`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run))
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()
        fixture.catalogFor(run, AgentTestFixtures.task())

        fixture.release(run)

        assertEquals(1, fixture.transport.deleteSessionCalls.get())
        fixture.release(run)
        assertEquals(1, fixture.transport.deleteSessionCalls.get())
        assertEquals(1, grantStore.revokedGrantIds.size)
    }

    @Test
    fun `catalogFor caches the per-run catalog without creating a grant`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run))
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()

        val first = fixture.catalogFor(run, AgentTestFixtures.task())
        val second = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(first, second)
        assertEquals(0, grantStore.createCalls.get())
        assertEquals(1, grantStore.listCalls.get())
        assertEquals(1, fixture.factoryCalls.get())
        fixture.release(run)
    }

    @Test
    fun `standing consent off still blocks an otherwise granted server`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run))
        addServer("alpha", "https://example.com/mcp", allowAgentNetwork = false)
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())

        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `grant store failure fails closed without a client or socket`() = runBlocking {
        grantStore.listFailure = IllegalStateException("grant store unavailable")
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()

        val catalog = fixture.catalogFor(networkRun(), AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, grantStore.createCalls.get())
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())
    }

    @Test
    fun `grant for another run cannot authorize this run`() = runBlocking {
        val otherRun = networkRun(runId = "run-other")
        grantStore.active = listOf(explicitGrant(otherRun, grantId = "grant-other-run"))
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()
        val run = networkRun()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())

        fixture.release(run)
        assertTrue(grantStore.revokedGrantIds.isEmpty())
    }

    @Test
    fun `stale policy-version grant fails closed and is revoked on release`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(
            explicitGrant(run, policyVersion = run.policy.policyVersion - 1)
        )
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())

        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `expired grant fails closed and is revoked on release`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run, expiresAtMillis = 9_000L))
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())

        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `grant that does not cover PRIVATE cannot connect MCP tools`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(
            explicitGrant(run, dataClasses = setOf(AgentDataClass.PUBLIC))
        )
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, fixture.factoryCalls.get())
        assertEquals(0, fixture.transport.posts.get())

        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `empty mcp adapters fall back to the base catalog and release revokes`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run))
        addServer("alpha", "https://example.com/mcp")
        val fixture = fixture(transport = RecordingTransport(tools = emptyList()))

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(1, fixture.factoryCalls.get())
        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `assembly conflict falls back to base and closes the registry`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(explicitGrant(run))
        addServer("alpha", "https://example.com/mcp")
        val builtInExecutor = baseCatalog.find("workspace.list")!!
        val duplicateBuiltIn = object : AgentToolCatalog {
            override fun find(toolKey: String) =
                if (toolKey == "workspace.list") builtInExecutor else null

            override fun snapshot() =
                listOf(builtInExecutor.descriptor, builtInExecutor.descriptor)
        }
        val fixture = fixture(base = duplicateBuiltIn)

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertSame(duplicateBuiltIn, catalog)
        assertEquals(1, fixture.transport.deleteSessionCalls.get())
        fixture.release(run)
        assertEquals(listOf(RUN_GRANT_ID), grantStore.revokedGrantIds)
    }

    @Test
    fun `mislabelled local server is skipped while a real local server is used`() = runBlocking {
        val run = networkRun()
        grantStore.active = listOf(
            explicitGrant(
                run,
                grantId = "grant-bad",
                transport = AgentEgressTransport.REMOTE_MCP,
                origin = "https://example.com"
            ),
            explicitGrant(
                run,
                grantId = "grant-local",
                transport = AgentEgressTransport.LOCAL_SERVER,
                origin = "http://127.0.0.1:9"
            )
        )
        addServer("bad", "https://example.com/mcp", allowLocal = true)
        addServer("local", "http://127.0.0.1:9/mcp", allowLocal = true)
        val fixture = fixture()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        assertNull(catalog.find("mcp:bad:echo"))
        assertNotNull(catalog.find("mcp:local:echo"))
        assertEquals(0, grantStore.createCalls.get())
        assertEquals(1, fixture.factoryCalls.get())

        fixture.release(run)
        assertEquals(
            setOf("grant-bad", "grant-local"),
            grantStore.revokedGrantIds.toSet()
        )
    }

    @Test
    fun `user authorization binds at run start and projects the tool boundary`() = runBlocking {
        addServer("alpha", "https://example.com/mcp", allowAgentNetwork = false)
        assertTrue(store.authorizeForAgent("alpha") is AgentMcpServerMutation.Applied)
        val fixture = fixture(bindAuthorizations = true)
        val run = networkRun()

        val catalog = fixture.catalogFor(run, AgentTestFixtures.task())

        val executor = catalog.find("mcp:alpha:echo")
        assertNotNull(executor)
        assertEquals(1, grantStore.createdGrants.size)
        val grant = grantStore.createdGrants.single()
        assertEquals(run.runId, grant.runId)
        assertEquals(AgentEgressTransport.REMOTE_MCP, grant.transport)
        assertEquals("https://example.com", grant.origin)
        assertEquals(setOf(AgentDataClass.PRIVATE), grant.allowedDataClasses)
        // Schema/effects/timeout are projected from the frozen MCP descriptor, not annotations.
        assertEquals(
            com.muyuchat.core.agent.contract.AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            executor!!.descriptor.sideEffect
        )
        assertEquals(AgentDataClass.PRIVATE, executor.descriptor.dataClass)
        assertEquals(McpProtocol.DEFAULT_DEADLINE_MILLIS, executor.descriptor.timeoutMillis)
        assertTrue(executor.descriptor.schemaHash.isNotBlank())

        fixture.release(run)
        assertEquals(listOf(grant.grantId), grantStore.revokedGrantIds)
    }

    @Test
    fun `revoked server authorization stops the next run from binding`() = runBlocking {
        addServer("alpha", "https://example.com/mcp", allowAgentNetwork = false)
        store.authorizeForAgent("alpha")
        val fixture = fixture(bindAuthorizations = true)
        val first = networkRun(runId = "run-1")

        assertNotNull(fixture.catalogFor(first, AgentTestFixtures.task()).find("mcp:alpha:echo"))
        fixture.release(first)
        store.revokeAgentAuthorization("alpha")

        val second = networkRun(runId = "run-2")
        val catalog = fixture.catalogFor(second, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(1, grantStore.createdGrants.size)
        assertEquals(1, fixture.factoryCalls.get())
        fixture.release(second)
    }

    @Test
    fun `binder without an authorization never mints a grant`() = runBlocking {
        addServer("alpha", "https://example.com/mcp", allowAgentNetwork = true)
        val fixture = fixture(bindAuthorizations = true)

        val catalog = fixture.catalogFor(networkRun(), AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertEquals(0, grantStore.createdGrants.size)
        assertEquals(0, fixture.factoryCalls.get())
    }

    private fun addServer(
        serverId: String,
        endpoint: String,
        allowLocal: Boolean = false,
        enabled: Boolean = true,
        allowAgentNetwork: Boolean = true
    ) {
        val result = store.add(
            AgentMcpServerRecord(
                serverId = serverId,
                displayName = "Server $serverId",
                endpoint = endpoint,
                enabled = enabled,
                allowLocal = allowLocal,
                createdAtMillis = 1_000L,
                allowAgentNetwork = allowAgentNetwork
            )
        )
        assertTrue("server config must be accepted: $result", result is AgentMcpServerMutation.Applied)
    }

    private fun networkRun(
        policyVersion: Long = 3L,
        runId: String = AgentTestFixtures.RUN_ID
    ): AgentRun = AgentTestFixtures.run(runId = runId).copy(
        policy = AgentTestFixtures.policy(version = policyVersion).copy(
            networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
            offlineForced = false
        )
    )

    private fun explicitGrant(
        run: AgentRun,
        grantId: String = RUN_GRANT_ID,
        transport: AgentEgressTransport = AgentEgressTransport.REMOTE_MCP,
        origin: String = "https://example.com",
        dataClasses: Set<AgentDataClass> = setOf(AgentDataClass.PRIVATE),
        policyVersion: Long = run.policy.policyVersion,
        expiresAtMillis: Long? = null
    ): AgentEgressGrant = AgentEgressGrant(
        grantId = grantId,
        runId = run.runId,
        taskId = run.taskId,
        transport = transport,
        origin = origin,
        allowedDataClasses = dataClasses,
        allowedSourceIds = emptySet(),
        policyVersion = policyVersion,
        expiresAtMillis = expiresAtMillis
    )

    private fun fixture(
        base: AgentToolCatalog = baseCatalog,
        transport: RecordingTransport = RecordingTransport(),
        bindAuthorizations: Boolean = false
    ): ProviderFixture {
        val factoryCalls = AtomicInteger(0)
        val provider = RunScopedAgentToolCatalogProvider(
            delegate = object : AgentRunToolCatalogProvider {
                override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog = base
            },
            serverStore = store,
            grantStore = grantStore,
            grantBinder = if (bindAuthorizations) {
                AgentMcpRunGrantBinder(store, grantStore)
            } else {
                null
            },
            clientFactory = AgentMcpClientFactory { config ->
                factoryCalls.incrementAndGet()
                McpClient(config, transport, requestTimeoutMillis = 5_000L)
            },
            nowMillis = { 10_000L }
        )
        return ProviderFixture(provider, transport, factoryCalls)
    }

    private class ProviderFixture(
        val provider: RunScopedAgentToolCatalogProvider,
        val transport: RecordingTransport,
        val factoryCalls: AtomicInteger
    ) {
        suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog =
            provider.catalogFor(run, task)

        suspend fun release(run: AgentRun) = provider.release(run)
    }

    private class FakeEgressGrantStore : AgentEgressGrantStore {
        val createdGrants = ArrayList<AgentEgressGrant>()
        val createCalls = AtomicInteger(0)
        val revokedGrantIds = ArrayList<String>()
        val listCalls = AtomicInteger(0)
        var active: List<AgentEgressGrant> = emptyList()
        var listFailure: Throwable? = null

        override suspend fun create(grant: AgentEgressGrant): AgentEgressGrantOutcome {
            createCalls.incrementAndGet()
            createdGrants += grant
            active = active + grant
            return AgentEgressGrantOutcome.Created(grant)
        }

        override suspend fun listActiveForTask(
            taskId: String,
            nowMillis: Long,
            limit: Int
        ): List<AgentEgressGrant> {
            listCalls.incrementAndGet()
            listFailure?.let { throw it }
            return active.filter { !it.revoked }
        }

        override suspend fun revoke(grantId: String, nowMillis: Long, reason: String): Boolean {
            revokedGrantIds += grantId
            if (active.none { it.grantId == grantId }) return false
            active = active.filterNot { it.grantId == grantId }
            return true
        }

        override suspend fun revokeTaskScope(taskId: String, nowMillis: Long, reason: String): Int = 0
    }

    private class RecordingTransport(
        private val codec: McpJsonCodec = McpJsonCodec(),
        private val sessionId: String = SESSION_ID,
        private val toolSchema: String = TOOL_SCHEMA,
        var tools: List<ToolDef> = listOf(ToolDef("echo"))
    ) : McpHttpTransport {
        val posts = AtomicInteger(0)
        val deleteSessionCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)

        override suspend fun post(post: McpHttpPost): McpHttpResult {
            posts.incrementAndGet()
            val message = (codec.parse(post.bodyJson) as McpJsonParseResult.Parsed).value.asObj()!!
            val method = message.string("method").orEmpty()
            val id = message.numberOrNull("id")?.literal?.toLongOrNull()
            return when (method) {
                McpProtocol.METHOD_INITIALIZE -> McpHttpResult.Json(
                    statusCode = 200,
                    body = INITIALIZE_RESULT.replace("__ID__", (id ?: 0).toString()),
                    sessionId = sessionId
                )
                McpProtocol.METHOD_INITIALIZED -> McpHttpResult.Accepted(202, sessionId)
                McpProtocol.METHOD_TOOLS_LIST -> McpHttpResult.Json(
                    statusCode = 200,
                    body = toolsResult(id),
                    sessionId = sessionId
                )
                else -> McpHttpResult.Failed(McpFailure.Protocol("mcp_unexpected_method"))
            }
        }

        override suspend fun deleteSession(post: McpHttpPost): Boolean {
            deleteSessionCalls.incrementAndGet()
            return true
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }

        private fun toolsResult(id: Long?): String {
            val items = tools.joinToString(",") { tool ->
                """{"name":"${tool.name}","description":"tool ${tool.name}","inputSchema":$toolSchema}"""
            }
            return """{"jsonrpc":"2.0","id":${id ?: 0},"result":{"tools":[$items]}}"""
        }
    }

    private companion object {
        const val RUN_GRANT_ID = "grant-explicit-run"
    }
}

private data class ToolDef(val name: String)

private const val SESSION_ID = "sess-run-scoped"

private const val TOOL_SCHEMA =
    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}," +
        "\"required\":[\"text\"],\"additionalProperties\":false}"

private const val INITIALIZE_RESULT =
    "{\"jsonrpc\":\"2.0\",\"id\":__ID__,\"result\":{\"protocolVersion\":\"2025-06-18\"," +
        "\"capabilities\":{\"tools\":{\"listChanged\":false}}," +
        "\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}"
