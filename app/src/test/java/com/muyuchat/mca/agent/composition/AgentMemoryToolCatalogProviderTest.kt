package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import com.muyuchat.mca.agent.memory.AgentMemoryTestFixtures
import com.muyuchat.mca.agent.memory.InMemoryAgentMemoryPersistence
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Run-scoped catalog rule for `memory.search`.
 *
 * Eligible = the task already has a persisted, non-revoked frozen selection AND the run policy is
 * LOCAL_ONLY AND the policy's frozen `memorySpaceIds` still equals that selection. In every other
 * case the base catalog is returned by identity, so a network run can never see a memory tool and
 * a stale/rewritten policy can only disable memory, never widen it.
 */
class AgentMemoryToolCatalogProviderTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val store = AgentMemoryTestFixtures.store(
        persistence,
        AgentMemoryTestFixtures.sequentialIds()
    )
    private val selections = AgentMemorySelectionService(
        persistence,
        clock = { AgentMemoryTestFixtures.NOW }
    )
    private lateinit var baseCatalog: AgentToolCatalog

    @Before
    fun setUp() {
        baseCatalog = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
    }

    @Test
    fun `installs memory search only for a matching local-only selection and preserves four built-ins`() = runBlocking {
        persistence.seedSpace("space-a")
        selections.freezeForTask(AgentTestFixtures.TASK_ID, listOf("space-a"))

        val catalog = provider().catalogFor(run(memorySpaceIds = setOf("space-a")), AgentTestFixtures.task())

        assertEquals(
            setOf(
                "artifact.write_text",
                "memory.get",
                "memory.search",
                "workspace.list",
                "workspace.read_text",
                "workspace.search_text"
            ),
            catalog.snapshot().map { it.toolKey }.toSet()
        )
        assertNotNull(catalog.find("memory.search"))
        baseCatalog.snapshot().forEach { descriptor ->
            assertSame(
                "built-in ${descriptor.toolKey} must keep its exact executor",
                baseCatalog.find(descriptor.toolKey),
                catalog.find(descriptor.toolKey)
            )
        }
    }

    @Test
    fun `network-enabled run never receives memory even with a frozen selection`() = runBlocking {
        persistence.seedSpace("space-a")
        selections.freezeForTask(AgentTestFixtures.TASK_ID, listOf("space-a"))

        val catalog = provider().catalogFor(
            run(
                memorySpaceIds = setOf("space-a"),
                networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
                offlineForced = false
            ),
            AgentTestFixtures.task()
        )

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("memory.search"))
    }

    @Test
    fun `local-only run without a frozen task selection stays on the base catalog`() = runBlocking {
        persistence.seedSpace("space-a")

        val catalog = provider().catalogFor(run(memorySpaceIds = setOf("space-a")), AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("memory.search"))
    }

    @Test
    fun `local-only run whose policy dropped the frozen selection stays on the base catalog`() = runBlocking {
        persistence.seedSpace("space-a")
        selections.freezeForTask(AgentTestFixtures.TASK_ID, listOf("space-a"))

        val catalog = provider().catalogFor(run(memorySpaceIds = emptySet()), AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("memory.search"))
    }

    @Test
    fun `a rewritten policy can never widen the frozen selection`() = runBlocking {
        persistence.seedSpace("space-a")
        persistence.seedSpace("space-b")
        selections.freezeForTask(AgentTestFixtures.TASK_ID, listOf("space-a"))

        val catalog = provider().catalogFor(
            run(memorySpaceIds = setOf("space-a", "space-b")),
            AgentTestFixtures.task()
        )

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("memory.search"))
    }

    @Test
    fun `a run for another task cannot read this task's selection`() = runBlocking {
        persistence.seedSpace("space-a")
        selections.freezeForTask(AgentTestFixtures.TASK_ID, listOf("space-a"))

        val otherTaskRun = run(memorySpaceIds = setOf("space-a")).copy(taskId = "task-other")
        val catalog = provider().catalogFor(otherTaskRun, AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("memory.search"))
    }

    @Test
    fun `a revoked task selection removes memory search`() = runBlocking {
        persistence.seedSpace("space-a")
        selections.freezeForTask(AgentTestFixtures.TASK_ID, listOf("space-a"))
        selections.revokeForTask(AgentTestFixtures.TASK_ID)

        val catalog = provider().catalogFor(run(memorySpaceIds = setOf("space-a")), AgentTestFixtures.task())

        assertSame(baseCatalog, catalog)
        assertNull(catalog.find("memory.search"))
    }

    private fun provider(): AgentMemoryToolCatalogProvider = AgentMemoryToolCatalogProvider(
        // N04: the base catalog is resolved per run, not captured at construction.
        baseCatalogFor = { _, _ -> baseCatalog },
        store = store,
        selections = selections
    )

    private fun run(
        memorySpaceIds: Set<String>,
        networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
        offlineForced: Boolean = true
    ) = AgentTestFixtures.run().copy(
        policy = AgentTestFixtures.policy().copy(
            networkPolicy = networkPolicy,
            memorySpaceIds = memorySpaceIds,
            offlineForced = offlineForced
        )
    )
}
