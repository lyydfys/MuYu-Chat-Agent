package com.muyuchat.mca.agent.extensions.skills

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAgentSkillStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun manifest(
        id: String = "demo.skill",
        version: Long = 1L,
        enabled: Boolean = false
    ): AgentSkillManifest = AgentSkillManifest(
        id = id,
        name = "Demo Skill",
        description = "A deterministic test skill.",
        packageVersion = "1.2.3",
        source = AgentSkillSource("content://skill/demo", "tree:" + "a".repeat(64)),
        sha256 = "b".repeat(64),
        entryPath = "SKILL.md",
        enabled = enabled,
        declaredToolPatterns = listOf("workspace.*", "mcp:files:*"),
        resourcePaths = listOf("docs/readme.md"),
        version = version,
        snapshotId = "snap-$id-$version",
        installedAtMillis = 1_700_000_000_000L,
        metadata = mapOf(
            "x-harness" to AgentSkillMetadataValue.Text("other"),
            "x-tags" to AgentSkillMetadataValue.ListValue(listOf("alpha", "beta"))
        )
    )

    @Test
    fun roundTripPreservesEveryManifestField() {
        val root = temporary.newFolder("skills")
        val created = FileAgentSkillStore(root).create(manifest(enabled = true))
        val reopened = FileAgentSkillStore(root)
        val loaded = reopened.find(created.id)!!
        assertEquals(created, loaded)
        assertEquals(listOf("workspace.*", "mcp:files:*"), loaded.declaredToolPatterns)
        assertEquals(
            AgentSkillMetadataValue.ListValue(listOf("alpha", "beta")),
            loaded.metadata["x-tags"]
        )
    }

    @Test
    fun createRejectsDuplicateId() {
        val store = FileAgentSkillStore(temporary.newFolder("skills"))
        store.create(manifest())
        val failure = runCatching { store.create(manifest()) }.exceptionOrNull()
        assertEquals(
            AgentSkillErrorCode.VERSION_CONFLICT,
            (failure as AgentSkillException).error.code
        )
    }

    @Test
    fun updateRequiresTheExpectedVersion() {
        val store = FileAgentSkillStore(temporary.newFolder("skills"))
        val current = store.create(manifest())
        val stale = runCatching { store.update(manifest(version = 2L), expectedVersion = 99L) }
        assertEquals(
            AgentSkillErrorCode.VERSION_CONFLICT,
            (stale.exceptionOrNull() as AgentSkillException).error.code
        )
        val updated = store.update(manifest(version = 2L, enabled = true), current.version)
        assertEquals(2L, updated.version)
        assertTrue(FileAgentSkillStore(temporary.root.resolve("skills")).find(updated.id)!!.enabled)
    }

    @Test
    fun setEnabledAndDeleteSurviveReopen() {
        val root = temporary.newFolder("skills")
        val store = FileAgentSkillStore(root)
        store.create(manifest())
        store.setEnabled("demo.skill", true)
        assertTrue(FileAgentSkillStore(root).find("demo.skill")!!.enabled)
        val removed = FileAgentSkillStore(root).delete("demo.skill")
        assertEquals("demo.skill", removed.id)
        assertTrue(FileAgentSkillStore(root).list().isEmpty())
    }

    @Test
    fun corruptStoreFailsClosedInsteadOfDroppingSkills() {
        val root = temporary.newFolder("skills")
        File(root, "manifests.json").writeText("{not-json", Charsets.UTF_8)
        val failure = runCatching { FileAgentSkillStore(root) }.exceptionOrNull()
        assertEquals(
            AgentSkillErrorCode.STORE_FAILURE,
            (failure as AgentSkillException).error.code
        )
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFilesBehind() {
        val root = temporary.newFolder("skills")
        val store = FileAgentSkillStore(root)
        store.create(manifest())
        store.setEnabled("demo.skill", true)
        val leftovers = root.listFiles().orEmpty().filter { it.name.contains(".tmp") }
        assertTrue(leftovers.isEmpty())
        assertFalse(File(root, "manifests.json").readText(Charsets.UTF_8).isBlank())
    }

    @Test
    fun listIsDeterministicallySortedAndSeparateRootsDoNotShareState() {
        val firstRoot = temporary.newFolder("first")
        val secondRoot = temporary.newFolder("second")
        val first = FileAgentSkillStore(firstRoot)
        first.create(manifest(id = "b.skill"))
        first.create(manifest(id = "a.skill"))
        assertEquals(listOf("a.skill", "b.skill"), first.list().map { it.id })
        assertTrue(FileAgentSkillStore(secondRoot).list().isEmpty())
    }

    @Test
    fun storeFileStaysInsideItsRoot() {
        val root = temporary.newFolder("skills")
        val store = FileAgentSkillStore(root)
        store.create(manifest())
        assertTrue(File(root, "manifests.json").isFile)
        assertTrue(root.listFiles()!!.none { it.name.contains("/") || it.name.contains("..") })
    }

    @Test
    fun missingStoreStartsEmptyAndCreatesTheFileOnFirstWrite() {
        val root = File(temporary.root, "new-skills")
        val store = FileAgentSkillStore(root)
        assertTrue(store.list().isEmpty())
        assertFalse(File(root, "manifests.json").exists())
        store.create(manifest())
        assertTrue(File(root, "manifests.json").isFile)
        assertEquals(1, root.listFiles()!!.size)
    }
}
