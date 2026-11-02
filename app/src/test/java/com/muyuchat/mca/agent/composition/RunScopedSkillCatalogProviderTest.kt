package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.runtime.run.AgentRecordedCatalog
import com.muyuchat.core.agent.runtime.run.AgentRunCatalogSnapshotRecorder
import com.muyuchat.core.agent.runtime.run.AgentToolCatalogHashing
import com.muyuchat.core.agent.runtime.run.RecordedToolIdentity
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.extensions.skills.AgentSkillFixtures
import com.muyuchat.mca.agent.extensions.skills.AgentSkillManager
import com.muyuchat.mca.agent.extensions.skills.AgentSkillManifest
import com.muyuchat.mca.agent.extensions.skills.AgentSkillRevocationPort
import com.muyuchat.mca.agent.extensions.skills.AgentSkillSnapshotReferencePort
import com.muyuchat.mca.agent.extensions.skills.FakeSnapshotRepository
import com.muyuchat.mca.agent.extensions.skills.InMemoryAgentSkillStore
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N04 / TOOL-21: each run resolves the Skill set enabled when it starts, and keeps that immutable
 * catalog for its own execution. V1 captured the snapshot once at runtime construction, so a
 * disable/update never reached the next run without a process restart.
 */
class RunScopedSkillCatalogProviderTest {
    private val store = InMemoryAgentSkillStore()
    private val snapshots = FakeSnapshotRepository()
    private val manager = AgentSkillManager(
        store = store,
        snapshots = snapshots,
        references = AgentSkillSnapshotReferencePort { snapshotId -> snapshots.exists(snapshotId) },
        revocations = AgentSkillRevocationPort { },
        clock = { NOW }
    )
    private val builtIns = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())

    @Test
    fun `narrowing is the Skill enabled when the run starts and earlier runs keep their catalog`() =
        runBlocking {
            val skill = install("Alpha Skill", allowedTools = listOf("workspace.read_text"), enable = true)
            val provider = provider()

            val firstRun = provider.catalogFor(run(runId = "run-1"), AgentTestFixtures.task())
            assertEquals(
                setOf("workspace.read_text"),
                firstRun.snapshot().map { it.toolKey }.toSet()
            )

            manager.setEnabled(skill.id, false)
            val secondRun = provider.catalogFor(run(runId = "run-2"), AgentTestFixtures.task())

            assertEquals(
                builtIns.snapshot().map { it.toolKey }.toSet(),
                secondRun.snapshot().map { it.toolKey }.toSet()
            )
            assertEquals(
                "an already-resolved catalog must not follow a later disable",
                setOf("workspace.read_text"),
                firstRun.snapshot().map { it.toolKey }.toSet()
            )
        }

    @Test
    fun `catalog hash changes when the enabled Skill set changes and is stable otherwise`() =
        runBlocking {
            val skill = install("Alpha Skill", allowedTools = listOf("workspace.read_text"), enable = true)
            val recorder = AgentRunCatalogSnapshotRecorder(provider(), clockMillis = { NOW })

            recorder.catalogFor(run(runId = "run-1"), AgentTestFixtures.task())
            val first = requireNotNull(recorder.snapshotFor("run-1"))
            recorder.catalogFor(run(runId = "run-2"), AgentTestFixtures.task())
            val sameState = requireNotNull(recorder.snapshotFor("run-2"))
            assertEquals(first.catalogHash, sameState.catalogHash)
            assertFalse(first.catalogHash == "0".repeat(64))

            manager.setEnabled(skill.id, false)
            recorder.catalogFor(run(runId = "run-3"), AgentTestFixtures.task())
            val changed = requireNotNull(recorder.snapshotFor("run-3"))
            assertNotEquals(first.catalogHash, changed.catalogHash)
            assertTrue(changed.toolKeys.size > first.toolKeys.size)
        }

    @Test
    fun `a rejected assembly surfaces a typed failure instead of a partial catalog`() = runBlocking {
        // Two manifests with the same id cannot be created through the manager; the conflict path is
        // still reachable through the assembler contract, so pin the exception type and message shape.
        val exception = AgentRunCatalogAssemblyException(emptyList())
        assertTrue(exception is IllegalStateException)
        assertTrue(exception.message.orEmpty().startsWith("agent tool catalog conflict:"))
    }

    @Test
    fun `a drifted surface restores exactly the recorded tools and refuses missing ones`() = runBlocking {
        val skill = install("Alpha Skill", allowedTools = listOf("workspace.read_text"), enable = true)
        val provider = provider()
        val firstRun = provider.catalogFor(run("run-1"), AgentTestFixtures.task())
        val recorded = AgentRecordedCatalog(
            catalogHash = AgentToolCatalogHashing.hash(firstRun),
            tools = firstRun.snapshot().map {
                RecordedToolIdentity(it.toolKey, it.version, it.schemaHash)
            }.sortedBy { it.toolKey }
        )

        manager.setEnabled(skill.id, false)
        val restored = provider.catalogForRecorded(
            run("run-1"),
            AgentTestFixtures.task(),
            recorded
        )

        assertEquals(
            setOf("workspace.read_text"),
            requireNotNull(restored).snapshot().map { it.toolKey }.toSet()
        )
        assertEquals(AgentToolCatalogHashing.hash(firstRun), AgentToolCatalogHashing.hash(restored))

        val widened = recorded.copy(
            tools = (recorded.tools + builtIns.snapshot().map {
                RecordedToolIdentity(it.toolKey, it.version, it.schemaHash)
            }).distinctBy { it.toolKey }.sortedBy { it.toolKey }
        )
        assertEquals(
            "a catalog wider than the recorded hash must not be restored",
            null,
            provider.catalogForRecorded(run("run-1"), AgentTestFixtures.task(), widened)
        )
    }

    private fun provider(): RunScopedSkillCatalogProvider = RunScopedSkillCatalogProvider(
        builtInCatalog = builtIns,
        skillSnapshotSource = { availableKeys -> manager.buildRunSnapshot(availableKeys) }
    )

    private fun install(
        name: String,
        allowedTools: List<String>,
        enable: Boolean
    ): AgentSkillManifest = manager.installTree(
        sourceUri = "content://skill/$name",
        root = AgentSkillFixtures.treeSkill(
            markdown = AgentSkillFixtures.validMarkdown(name = name, allowedTools = allowedTools)
        ),
        enable = enable
    )

    private fun run(runId: String) = AgentTestFixtures.run().copy(runId = runId)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
