package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.tools.AgentHttpGetOutcome
import com.muyuchat.mca.agent.tools.AgentHttpGetRequest
import com.muyuchat.mca.agent.tools.AgentHttpTextClient
import com.muyuchat.mca.agent.tools.ExtendedToolTestSupport
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Run-scoped catalog rule for `http.get`.
 *
 * The tool is never part of the default surface: the frozen policy must be NETWORK_ENABLED, not
 * offline-forced, and list `http.get` in `enabledToolKeys`. The installed executor is bound to the
 * exact run id; another run's context is denied before the transport is touched.
 */
class AgentHttpToolCatalogProviderTest {

    private class RecordingClient : AgentHttpTextClient {
        val requests = mutableListOf<AgentHttpGetRequest>()
        override suspend fun get(request: AgentHttpGetRequest): AgentHttpGetOutcome {
            requests += request
            return AgentHttpGetOutcome.Response(
                statusCode = 200,
                contentType = "text/plain",
                bodyBytes = 2,
                text = "ok",
                truncated = false
            )
        }
    }

    private val client = RecordingClient()
    private lateinit var baseCatalog: AgentToolCatalog

    @Before
    fun setUp() {
        baseCatalog = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
    }

    private fun provider() = AgentHttpToolCatalogProvider(
        baseCatalogFor = { _, _ -> baseCatalog },
        client = client
    )

    private fun run(
        networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
        offlineForced: Boolean = false,
        enabledToolKeys: Set<String>? = setOf("http.get")
    ) = AgentTestFixtures.run().copy(
        policy = AgentTestFixtures.policy().copy(
            networkPolicy = networkPolicy,
            offlineForced = offlineForced,
            enabledToolKeys = enabledToolKeys
        )
    )

    @Test
    fun `an explicitly opted-in network run receives http get and keeps every base executor`() = runBlocking {
        val catalog = provider().catalogFor(run(), AgentTestFixtures.task())

        assertNotNull(catalog.find("http.get"))
        baseCatalog.snapshot().forEach { descriptor ->
            assertSame(
                "base ${descriptor.toolKey} must keep its exact executor",
                baseCatalog.find(descriptor.toolKey),
                catalog.find(descriptor.toolKey)
            )
        }
    }

    @Test
    fun `a null or default tool set never enables http get`() = runBlocking {
        val catalog = provider().catalogFor(run(enabledToolKeys = null), AgentTestFixtures.task())
        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("http.get"))

        val defaultKeys = provider().catalogFor(
            run(enabledToolKeys = setOf("workspace.read_text", "artifact.write_text")),
            AgentTestFixtures.task()
        )
        assertSame(baseCatalog, defaultKeys)
        assertNull(defaultKeys.find("http.get"))
    }

    @Test
    fun `local-only or offline-forced policies never enable http get`() = runBlocking {
        val localOnly = provider().catalogFor(
            run(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY, offlineForced = true),
            AgentTestFixtures.task()
        )
        assertSame(baseCatalog, localOnly)
        assertNull(localOnly.find("http.get"))

        val offlineDespiteNetwork = provider().catalogFor(
            run(networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED, offlineForced = true),
            AgentTestFixtures.task()
        )
        assertSame(baseCatalog, offlineDespiteNetwork)
        assertNull(offlineDespiteNetwork.find("http.get"))
    }

    @Test
    fun `a run for another task cannot install the tool over this task`() = runBlocking {
        val otherTaskRun = run().copy(taskId = "task-other")
        val catalog = provider().catalogFor(otherTaskRun, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("http.get"))
    }

    @Test
    fun `the installed executor is bound to the authorizing run`() = runBlocking {
        val catalog = provider().catalogFor(run(), AgentTestFixtures.task())
        val executor = requireNotNull(catalog.find("http.get"))
        val command = ExtendedToolTestSupport.command(
            executor.descriptor,
            """{"url":"https://example.com/notes.txt"}"""
        )

        val foreign = executor.execute(
            command,
            ExtendedToolTestSupport.context().copy(runId = "run-other")
        )
        assertEquals(AgentToolResultStatus.DENIED, foreign.status)
        assertEquals("POLICY_EGRESS_DENIED", foreign.errorCode)
        assertTrue("a foreign run must not open the transport", client.requests.isEmpty())

        val own = executor.execute(
            command,
            ExtendedToolTestSupport.context().copy(runId = AgentTestFixtures.RUN_ID)
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, own.status)
        assertEquals("https://example.com", client.requests.single().origin)
    }
}
