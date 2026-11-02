package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressGrantOutcome
import com.muyuchat.mca.agent.security.AgentEgressGrantStore
import com.muyuchat.mca.agent.security.AgentEgressTransport
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Run-start binding matrix for durable MCP server authorizations.
 *
 * These tests never open a socket: the binder only derives/persists run-scoped egress grants through
 * a fake [AgentEgressGrantStore]. They do not prove a real MCP server or transport works.
 */
class AgentMcpRunGrantBinderTest {
    private lateinit var filesDir: File
    private lateinit var store: AgentMcpServerStore
    private lateinit var grants: RecordingGrantStore
    private lateinit var binder: AgentMcpRunGrantBinder

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mcp-grant-binder-test").toFile()
        store = AgentMcpServerStore(filesDir, clockMillis = { 1_000L })
        grants = RecordingGrantStore()
        binder = AgentMcpRunGrantBinder(store, grants)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `user authorized server binds one explicit run grant`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        assertTrue(store.authorizeForAgent("alpha") is AgentMcpServerMutation.Applied)
        val run = networkRun()

        val report = binder.bind(run, AgentTestFixtures.task())

        assertEquals(listOf("alpha"), report.boundServerIds)
        assertTrue(report.failures.isEmpty())
        val grant = grants.created.single()
        assertEquals(run.runId, grant.runId)
        assertEquals(AgentEgressTransport.REMOTE_MCP, grant.transport)
        assertEquals("https://example.com", grant.origin)
        assertEquals(setOf(AgentDataClass.PRIVATE), grant.allowedDataClasses)
        assertTrue(grant.allowedSourceIds.isEmpty())
        assertEquals(run.policy.policyVersion, grant.policyVersion)
        assertEquals(AgentMcpRunGrantBinder.grantIdFor(run.runId, "alpha"), grant.grantId)
    }

    @Test
    fun `repeated binding for the same run and server reuses one deterministic id`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        val run = networkRun()

        binder.bind(run, AgentTestFixtures.task())
        binder.bind(run, AgentTestFixtures.task())

        assertEquals(2, grants.created.size)
        assertEquals(1, grants.created.map { it.grantId }.distinct().size)
    }

    @Test
    fun `revoked authorization binds nothing and does not call the grant store`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        store.revokeAgentAuthorization("alpha")

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertEquals(emptyList<String>(), report.boundServerIds)
        assertEquals("MCP_GRANT_NO_AUTHORIZED_SERVER", report.skippedReasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `standing consent without a durable authorization binds nothing`() = runBlocking {
        // Legacy/fixture shape: consent true but no authorization snapshot — fail closed.
        addServer("alpha", "https://example.com/mcp", allowAgentNetwork = true)

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertEquals(emptyList<String>(), report.boundServerIds)
        assertEquals("MCP_GRANT_NO_AUTHORIZED_SERVER", report.skippedReasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `disabled server is not bound even when it was authorized`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        store.setEnabled("alpha", false)

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertTrue(report.boundServerIds.isEmpty())
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `deleted server leaves no binding`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        store.remove("alpha")

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertEquals("MCP_GRANT_NO_AUTHORIZED_SERVER", report.skippedReasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `endpoint drift after authorization makes the record corrupt and binds nothing`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        // Simulate a hand-edited/server-rotated endpoint while the old authorization stays behind.
        val rewritten = store.path.readText(Charsets.UTF_8)
            .replace("https://example.com/mcp", "https://evil.example.net/mcp")
        store.path.writeText(rewritten, Charsets.UTF_8)
        assertTrue(store.load() is AgentMcpServerLoad.Corrupt)

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertEquals("MCP_STORE_INVALID_RECORD", report.skippedReasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `local-only run never binds even for an authorized local server`() = runBlocking {
        addServer("local", "http://127.0.0.1:9/mcp", allowLocal = true)
        store.authorizeForAgent("local")
        val localRun = AgentTestFixtures.run().copy(
            policy = AgentTestFixtures.policy(version = 3L).copy(
                networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
                offlineForced = true
            )
        )

        val report = binder.bind(localRun, AgentTestFixtures.task())

        assertEquals("MCP_GRANT_POLICY_OFFLINE", report.skippedReasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `task mismatch binds nothing`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        val run = networkRun()
        val otherTask = AgentTestFixtures.task(taskId = "task-other")

        val report = binder.bind(run, otherTask)

        assertEquals("MCP_GRANT_TASK_MISMATCH", report.skippedReasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `one rejected grant is typed and does not stop another server`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        addServer("beta", "https://beta.example.com/mcp")
        store.authorizeForAgent("alpha")
        store.authorizeForAgent("beta")
        grants.rejectNextWith = "EGRESS_DENIED"

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertEquals(listOf("beta"), report.boundServerIds)
        assertEquals(listOf("alpha"), report.failures.map { it.serverId })
        // The store returns the existing security layer's typed code (detail = "EGRESS_DENIED"),
        // not the raw detail string.
        assertEquals("POLICY_EGRESS_DENIED", report.failures.single().reasonCode)
        assertEquals(1, grants.created.size)
    }

    @Test
    fun `grant store outage is typed and never throws`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")
        store.authorizeForAgent("alpha")
        grants.failWith = IllegalStateException("storage unavailable")

        val report = binder.bind(networkRun(), AgentTestFixtures.task())

        assertTrue(report.boundServerIds.isEmpty())
        assertEquals("MCP_GRANT_PERSIST_FAILED", report.failures.single().reasonCode)
        assertTrue(grants.created.isEmpty())
    }

    @Test
    fun `authorization scope without PRIVATE is rejected by the store`() = runBlocking {
        addServer("alpha", "https://example.com/mcp")

        val outcome = store.authorizeForAgent("alpha", dataClasses = setOf(AgentDataClass.PUBLIC))

        assertTrue(outcome is AgentMcpServerMutation.Rejected)
        assertEquals(
            "MCP_AUTHORIZATION_SCOPE_INVALID",
            (outcome as AgentMcpServerMutation.Rejected).code
        )
        val loaded = store.load() as AgentMcpServerLoad.Loaded
        assertEquals(null, loaded.servers.single().agentAuthorization)
    }

    private fun addServer(
        serverId: String,
        endpoint: String,
        allowLocal: Boolean = false,
        allowAgentNetwork: Boolean = false
    ) {
        val result = store.add(
            AgentMcpServerRecord(
                serverId = serverId,
                displayName = "Server $serverId",
                endpoint = endpoint,
                enabled = true,
                allowLocal = allowLocal,
                createdAtMillis = 1_000L,
                allowAgentNetwork = allowAgentNetwork
            )
        )
        assertTrue("server config must be accepted: $result", result is AgentMcpServerMutation.Applied)
    }

    private fun networkRun(runId: String = AgentTestFixtures.RUN_ID): AgentRun =
        AgentTestFixtures.run(runId = runId).copy(
            policy = AgentTestFixtures.policy(version = 3L).copy(
                networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
                offlineForced = false
            )
        )

    private class RecordingGrantStore : AgentEgressGrantStore {
        val created = ArrayList<AgentEgressGrant>()
        var rejectNextWith: String? = null
        var failWith: Throwable? = null

        override suspend fun create(grant: AgentEgressGrant): AgentEgressGrantOutcome {
            failWith?.let { throw it }
            rejectNextWith?.let { code ->
                rejectNextWith = null
                return AgentEgressGrantOutcome.Rejected(AgentSecurityErrors.egressDenied(code))
            }
            created += grant
            return AgentEgressGrantOutcome.Created(grant)
        }

        override suspend fun listActiveForTask(
            taskId: String,
            nowMillis: Long,
            limit: Int
        ): List<AgentEgressGrant> = created.filter { it.taskId == null || it.taskId == taskId }

        override suspend fun revoke(grantId: String, nowMillis: Long, reason: String): Boolean =
            created.removeAll { it.grantId == grantId }

        override suspend fun revokeTaskScope(taskId: String, nowMillis: Long, reason: String): Int = 0
    }
}
