package com.muyuchat.mca.agent.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillManagerTest {
    private val store = InMemoryAgentSkillStore()
    private val snapshots = FakeSnapshotRepository()
    private val revocations = mutableListOf<AgentSkillRevocation>()
    private val referenced = mutableSetOf<String>()

    private fun manager(referenceCheck: (String) -> Boolean = { it in referenced }): AgentSkillManager =
        AgentSkillManager(
            store = store,
            snapshots = snapshots,
            references = AgentSkillSnapshotReferencePort { snapshotId -> referenceCheck(snapshotId) },
            revocations = AgentSkillRevocationPort { revocations += it },
            clock = { 1000L }
        )

    private fun install(
        manager: AgentSkillManager,
        name: String,
        allowedTools: List<String> = emptyList(),
        enable: Boolean = false,
        description: String = "A deterministic test skill.",
        extraChildren: List<AgentSkillTreeDocument> = emptyList()
    ): AgentSkillManifest = manager.installTree(
        sourceUri = "content://skill/$name",
        root = AgentSkillFixtures.treeSkill(
            markdown = AgentSkillFixtures.validMarkdown(
                name = name,
                description = description,
                allowedTools = allowedTools
            ),
            extraRootChildren = extraChildren
        ),
        enable = enable
    )

    @Test
    fun `install persists manifest and immutable snapshot metadata`() {
        val manifest = install(manager(), "Alpha Skill", allowedTools = listOf("workspace.read"))
        assertEquals("Alpha Skill", manifest.name)
        assertEquals(1L, manifest.version)
        assertEquals(1, manifest.schemaVersion)
        assertFalse(manifest.enabled)
        assertEquals(listOf("workspace.read"), manifest.declaredToolPatterns)
        assertEquals(AgentSkillManifest.ENTRY_PATH, manifest.entryPath)
        assertEquals("tree:${manifest.sha256}", manifest.source.fingerprint)
        assertNotNull(store.find(manifest.id))
        assertEquals(manifest, store.find(manifest.id))
        assertTrue(snapshots.exists(manifest.snapshotId))
    }

    @Test
    fun `v1 allows at most four enabled skills and rejects the fifth`() {
        val manager = manager()
        repeat(4) { index -> install(manager, "Skill $index", enable = true) }
        assertEquals(4, manager.list().count { it.enabled })
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.TOO_MANY_ENABLED_SKILLS) {
            install(manager, "Skill 4", enable = true)
        }
        val fifth = install(manager, "Skill 5")
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.TOO_MANY_ENABLED_SKILLS) {
            manager.setEnabled(fifth.id, true)
        }
        assertFalse(manager.list().first { it.id == fifth.id }.enabled)
    }

    @Test
    fun `update creates a new version and leaves the live run snapshot unchanged`() {
        val manager = manager()
        val first = install(manager, "Versioned Skill", allowedTools = listOf("workspace.*"), enable = true)
        val available = setOf("workspace.read", "workspace.write", "artifact.write")
        val snapshot = manager.buildRunSnapshot(available)
        assertEquals(1, snapshot.skills.size)
        assertEquals(1L, snapshot.skills.single().version)
        assertEquals(setOf("workspace.read", "workspace.write"), snapshot.effectiveToolKeys)

        val updated = manager.updateTree(
            skillId = first.id,
            sourceUri = "content://skill/updated",
            root = AgentSkillFixtures.treeSkill(
                markdown = AgentSkillFixtures.validMarkdown(
                    name = "Versioned Skill",
                    description = "Updated description.",
                    allowedTools = listOf("workspace.read")
                ),
                extraRootChildren = listOf(
                    AgentSkillFixtures.treeDirectory(
                        "docs",
                        listOf(AgentSkillFixtures.treeFile("new.md", "new"))
                    )
                )
            )
        )
        assertEquals(first.id, updated.id)
        assertEquals(2L, updated.version)
        assertTrue(updated.enabled)
        assertEquals(listOf("docs/new.md"), updated.resourcePaths)
        assertTrue(snapshots.exists(first.snapshotId))
        assertTrue(snapshots.exists(updated.snapshotId))
        assertFalse(updated.sha256 == first.sha256)

        // Old snapshot remains exactly as it was; it is not repointed at v2.
        assertEquals(1L, snapshot.skills.single().version)
        assertEquals(first.sha256, snapshot.skills.single().sha256)
        assertEquals(setOf("workspace.read", "workspace.write"), snapshot.effectiveToolKeys)

        val second = manager.buildRunSnapshot(available)
        assertEquals(2L, second.skills.single().version)
        assertEquals(updated.sha256, second.skills.single().sha256)
        assertEquals(setOf("workspace.read"), second.effectiveToolKeys)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.skills as MutableList<AgentSkillSnapshotSkill>).add(second.skills.single())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.effectiveToolKeys as MutableSet<String>).add("artifact.write")
        }
    }

    @Test
    fun `disable revokes pending approvals and falls back to the registry tool set`() {
        val manager = manager()
        val manifest = install(manager, "Revoked Skill", allowedTools = listOf("workspace.*"), enable = true)
        val available = setOf("workspace.read", "artifact.write")

        val disabled = manager.setEnabled(manifest.id, false)

        assertFalse(disabled.enabled)
        assertEquals(1, revocations.size)
        assertEquals(AgentSkillRevocationReason.DISABLED, revocations.single().reason)
        assertEquals(manifest.id, revocations.single().skillId)
        assertEquals(1L, revocations.single().version)
        assertEquals(listOf("workspace.*"), revocations.single().declaredToolPatterns)
        val snapshot = manager.buildRunSnapshot(available)
        assertTrue(snapshot.skills.isEmpty())
        assertEquals(available, snapshot.effectiveToolKeys)

        // Idempotent disable does not emit a second revocation.
        manager.setEnabled(manifest.id, false)
        assertEquals(1, revocations.size)
    }

    @Test
    fun `uninstall retains referenced snapshots and deletes unreferenced ones`() {
        val referencingManager = manager()
        val referencedManifest = install(referencingManager, "Referenced Skill", enable = true)
        referenced += referencedManifest.snapshotId
        val retained = referencingManager.uninstall(referencedManifest.id)
        assertTrue(retained.snapshotRetained)
        assertTrue(snapshots.exists(referencedManifest.snapshotId))
        assertTrue(snapshots.deleted.isEmpty())
        assertNull(store.find(referencedManifest.id))
        assertEquals(AgentSkillRevocationReason.UNINSTALLED, revocations.single().reason)

        val plainManager = manager()
        val unreferenced = install(plainManager, "Unreferenced Skill", enable = true)
        val removed = plainManager.uninstall(unreferenced.id)
        assertFalse(removed.snapshotRetained)
        assertEquals(listOf(unreferenced.snapshotId), snapshots.deleted)
        assertFalse(snapshots.exists(unreferenced.snapshotId))
    }

    @Test
    fun `run snapshot enforces max four enabled skills and intersects declarations`() {
        val manager = manager()
        install(manager, "A", allowedTools = listOf("workspace.*", "artifact.*"), enable = true)
        install(manager, "B", allowedTools = listOf("workspace.read"), enable = true)
        install(manager, "C", enable = true)
        val snapshot = manager.buildRunSnapshot(
            setOf("workspace.read", "workspace.write", "artifact.write", "network.search")
        )
        assertEquals(setOf("workspace.read"), snapshot.effectiveToolKeys)
        assertTrue(snapshot.allowsTool("workspace.read"))
        assertFalse(snapshot.allowsTool("workspace.write"))

        // Corrupt/legacy store bypassing the manager still cannot build a >4 snapshot.
        repeat(2) { index ->
            val manifest = install(manager, "Extra $index")
            store.setEnabled(manifest.id, true)
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.TOO_MANY_ENABLED_SKILLS) {
            manager.buildRunSnapshot(emptySet())
        }
    }

    @Test
    fun `store rejects stale updates and missing skills with typed errors`() {
        val manager = manager()
        val manifest = install(manager, "Conflict Skill")
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.VERSION_CONFLICT) {
            store.update(manifest.copy(version = 99L), expectedVersion = 50L)
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.SKILL_NOT_FOUND) {
            manager.setEnabled("missing-skill", true)
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.SKILL_NOT_FOUND) {
            store.delete("missing-skill")
        }
    }

    @Test
    fun `script documents are never opened during install or preview`() {
        val script = AgentSkillFixtures.treeFile("run.py", "print('SHOULD_NOT_RUN')", id = "py-script")
        val manager = manager()
        val root = AgentSkillFixtures.treeSkill(
            extraRootChildren = listOf(
                AgentSkillFixtures.treeDirectory("scripts", listOf(script), id = "scripts")
            )
        )
        val preview = manager.previewTree("content://skill/scripts", root)
        assertEquals(listOf("scripts/run.py"), preview.previewOnlyScripts)
        assertEquals(0, script.openCount)
        val manifest = manager.installTree("content://skill/scripts", root)
        assertEquals(0, script.openCount)
        assertTrue(manifest.resourcePaths.isEmpty())
    }
}
