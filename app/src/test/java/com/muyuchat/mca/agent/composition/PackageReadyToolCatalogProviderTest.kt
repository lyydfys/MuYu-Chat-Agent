package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.runtime.run.AgentRecordedCatalog
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priority 5: the run catalog chain must not resolve before the executable-package surface has
 * committed, and the barrier must not change what the wrapped provider does.
 */
class PackageReadyToolCatalogProviderTest {

    private class RecordingProvider(
        private val catalog: AgentToolCatalog
    ) : AgentRunToolCatalogProvider {
        val calls = mutableListOf<String>()
        var released = 0

        override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
            calls += "catalogFor:${run.runId}"
            return catalog
        }

        override suspend fun catalogForRecorded(
            run: AgentRun,
            task: AgentTask,
            recorded: AgentRecordedCatalog
        ): AgentToolCatalog {
            calls += "catalogForRecorded:${run.runId}"
            return catalog
        }

        override suspend fun release(run: AgentRun) {
            released += 1
        }
    }

    private fun catalog(): AgentToolCatalog =
        builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())

    @Test
    fun `resolution waits for the package surface before delegating`() = runBlocking {
        val provider = RecordingProvider(catalog())
        var ready = false
        val gated = PackageReadyToolCatalogProvider(provider) { ready = true }

        gated.catalogFor(AgentTestFixtures.run(), AgentTestFixtures.task())

        assertTrue(ready)
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `the recorded-catalog path uses the same barrier`() = runBlocking {
        val provider = RecordingProvider(catalog())
        var ready = 0
        val gated = PackageReadyToolCatalogProvider(provider) { ready += 1 }

        gated.catalogForRecorded(
            AgentTestFixtures.run(),
            AgentTestFixtures.task(),
            AgentRecordedCatalog("a".repeat(64), emptyList())
        )

        assertEquals(1, ready)
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `release still reaches the wrapped provider`() = runBlocking {
        val provider = RecordingProvider(catalog())
        val gated = PackageReadyToolCatalogProvider(provider) { }

        gated.release(AgentTestFixtures.run())

        assertEquals(1, provider.released)
    }

    @Test
    fun `a provider without a recorded reconstruction still returns null`() = runBlocking {
        // A SAM provider keeps the interface default: no recorded reconstruction.
        val provider = AgentRunToolCatalogProvider { _, _ -> catalog() }
        val gated = PackageReadyToolCatalogProvider(provider) { }

        assertNull(
            gated.catalogForRecorded(
                AgentTestFixtures.run(),
                AgentTestFixtures.task(),
                AgentRecordedCatalog("a".repeat(64), emptyList())
            )
        )
    }
}
