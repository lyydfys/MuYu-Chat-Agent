package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.runtime.run.AgentRunCatalogSnapshotRecorder
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
import com.muyuchat.mca.agent.extensions.skills.AgentSkillSnapshot
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressGrantOutcome
import com.muyuchat.mca.agent.security.AgentEgressGrantStore
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Assembly-level contract for the production catalog chain
 * `skill -> MCP -> (memory) -> snapshot recorder`, using fake dependencies only.
 *
 * It proves the ordering/merge behavior this task owns. It does not compile or instantiate
 * `MainRuntimeAssembly`, and it does not prove any real MCP server, socket, credential store or
 * memory selection; those stay NOT_RUN.
 */
class AgentMcpProductionChainTest {
    private lateinit var filesDir: File
    private lateinit var serverStore: AgentMcpServerStore
    private lateinit var grantStore: RecordingGrantStore

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mcp-production-chain-test").toFile()
        serverStore = AgentMcpServerStore(filesDir, clockMillis = { 1_000L })
        grantStore = RecordingGrantStore()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `skill base is preserved and an authorized MCP server is merged before recording`() = runBlocking {
        val builtIns = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
        val skillProvider = RunScopedSkillCatalogProvider(
            builtInCatalog = builtIns,
            skillSnapshotSource = { availableKeys ->
                AgentSkillSnapshot(
                    snapshotId = "a".repeat(64),
                    skills = emptyList(),
                    effectiveToolKeys = availableKeys,
                    createdAtMillis = 1_000L,
                    registryToolKeyCount = availableKeys.size
                )
            }
        )
        val transport = RecordingTransport(tools = listOf("echo"))
        val mcpProvider = RunScopedAgentToolCatalogProvider(
            delegate = skillProvider,
            serverStore = serverStore,
            grantStore = grantStore,
            grantBinder = AgentMcpRunGrantBinder(serverStore, grantStore),
            clientFactory = AgentMcpClientFactory { config ->
                McpClient(config, transport, requestTimeoutMillis = 5_000L)
            },
            nowMillis = { 10_000L }
        )
        val recorder = AgentRunCatalogSnapshotRecorder(mcpProvider, clockMillis = { 20_000L })
        addServer("alpha")
        assertTrue(serverStore.authorizeForAgent("alpha") is AgentMcpServerMutation.Applied)
        val run = AgentTestFixtures.run().copy(
            policy = AgentTestFixtures.policy(version = 3L).copy(
                networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
                offlineForced = false
            )
        )

        val catalog = recorder.catalogFor(run, AgentTestFixtures.task())

        // Base (built-ins) survives untouched; MCP adds only its namespaced tool.
        assertNotNull(catalog.find("workspace.list"))
        assertNotNull(catalog.find("mcp:alpha:echo"))
        assertEquals(1, grantStore.created.size)
        val snapshot = requireNotNull(recorder.snapshotFor(run.runId))
        assertTrue("mcp:alpha:echo" in snapshot.toolKeys)
        assertEquals(catalog.snapshot().map { it.toolKey }.toSet(), snapshot.toolKeys.toSet())

        recorder.release(run)

        assertEquals(1, grantStore.revoked.size)
        assertEquals(1, transport.deleteSessionCalls.get())
        assertEquals(null, recorder.snapshotFor(run.runId))
    }

    @Test
    fun `without a user authorization the chain returns the unchanged skill catalog`() = runBlocking {
        val builtIns = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
        val skillProvider = RunScopedSkillCatalogProvider(
            builtInCatalog = builtIns,
            skillSnapshotSource = { availableKeys ->
                AgentSkillSnapshot(
                    snapshotId = "b".repeat(64),
                    skills = emptyList(),
                    effectiveToolKeys = availableKeys,
                    createdAtMillis = 1_000L,
                    registryToolKeyCount = availableKeys.size
                )
            }
        )
        val factoryCalls = AtomicInteger(0)
        val mcpProvider = RunScopedAgentToolCatalogProvider(
            delegate = skillProvider,
            serverStore = serverStore,
            grantStore = grantStore,
            grantBinder = AgentMcpRunGrantBinder(serverStore, grantStore),
            clientFactory = AgentMcpClientFactory { config ->
                factoryCalls.incrementAndGet()
                McpClient(config, RecordingTransport(), requestTimeoutMillis = 5_000L)
            },
            nowMillis = { 10_000L }
        )
        addServer("alpha")
        val run = AgentTestFixtures.run().copy(
            policy = AgentTestFixtures.policy(version = 3L).copy(
                networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
                offlineForced = false
            )
        )

        val catalog = mcpProvider.catalogFor(run, AgentTestFixtures.task())
        val expected = skillProvider.catalogFor(run, AgentTestFixtures.task())

        assertEquals(
            expected.snapshot().map { it.toolKey },
            catalog.snapshot().map { it.toolKey }
        )
        assertEquals(null, catalog.find("mcp:alpha:echo"))
        assertEquals(0, factoryCalls.get())
        assertTrue(grantStore.created.isEmpty())
    }

    private fun addServer(serverId: String) {
        val result = serverStore.add(
            AgentMcpServerRecord(
                serverId = serverId,
                displayName = "Server $serverId",
                endpoint = "https://example.com/mcp",
                enabled = true,
                createdAtMillis = 1_000L
            )
        )
        assertTrue("server config must be accepted: $result", result is AgentMcpServerMutation.Applied)
    }

    private class RecordingGrantStore : AgentEgressGrantStore {
        val created = ArrayList<AgentEgressGrant>()
        val revoked = ArrayList<String>()

        override suspend fun create(grant: AgentEgressGrant): AgentEgressGrantOutcome {
            created += grant
            return AgentEgressGrantOutcome.Created(grant)
        }

        override suspend fun listActiveForTask(
            taskId: String,
            nowMillis: Long,
            limit: Int
        ): List<AgentEgressGrant> = created.filter { it.taskId == null || it.taskId == taskId }

        override suspend fun revoke(grantId: String, nowMillis: Long, reason: String): Boolean {
            revoked += grantId
            created.removeAll { it.grantId == grantId }
            return true
        }

        override suspend fun revokeTaskScope(taskId: String, nowMillis: Long, reason: String): Int = 0
    }

    private class RecordingTransport(
        private val codec: McpJsonCodec = McpJsonCodec(),
        private val sessionId: String = "sess-chain",
        var tools: List<String> = listOf("echo")
    ) : McpHttpTransport {
        val deleteSessionCalls = AtomicInteger(0)

        override suspend fun post(post: McpHttpPost): McpHttpResult {
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

        override fun close() = Unit

        private fun toolsResult(id: Long?): String {
            val items = tools.joinToString(",") { name ->
                """{"name":"$name","description":"tool $name","inputSchema":$TOOL_SCHEMA}"""
            }
            return """{"jsonrpc":"2.0","id":${id ?: 0},"result":{"tools":[$items]}}"""
        }
    }

    private companion object {
        const val TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}," +
                "\"required\":[\"text\"],\"additionalProperties\":false}"

        const val INITIALIZE_RESULT =
            "{\"jsonrpc\":\"2.0\",\"id\":__ID__,\"result\":{\"protocolVersion\":\"2025-06-18\"," +
                "\"capabilities\":{\"tools\":{\"listChanged\":false}}," +
                "\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}"
    }
}
