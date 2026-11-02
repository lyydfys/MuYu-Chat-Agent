package com.muyuchat.mca.agent.extensions.skills

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillSnapshotRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sourceFile(path: String, content: ByteArray): AgentSkillSourceFile =
        AgentSkillSourceFile(path, content.size.toLong()) { ByteArrayInputStream(content) }

    @Test
    fun `writes a read-only private snapshot and refuses overwrite`() {
        val root = temp.newFolder("skills")
        val repository = FileAgentSkillSnapshotRepository(root)
        val files = listOf(
            sourceFile("SKILL.md", AgentSkillFixtures.validMarkdown()),
            sourceFile("docs/a.md", "resource".toByteArray())
        )
        val stored = repository.create("demo-skill", 1L, files)

        assertEquals("demo-skill-v1", stored.snapshotId)
        assertTrue(stored.sha256.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(listOf("SKILL.md", "docs/a.md"), stored.files.map { it.relativePath })
        assertTrue(repository.exists(stored.snapshotId))
        val storedMarkdown = File(root, "${stored.snapshotId}/SKILL.md")
        assertTrue(storedMarkdown.isFile)
        assertFalse("private snapshot files must be read-only", storedMarkdown.canWrite())
        assertEquals("resource", File(root, "${stored.snapshotId}/docs/a.md").readText())

        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.VERSION_CONFLICT) {
            repository.create("demo-skill", 1L, files)
        }
        repository.delete(stored.snapshotId)
        assertFalse(repository.exists(stored.snapshotId))
    }

    @Test
    fun `rejects unsafe source paths and invalid snapshot ids`() {
        val repository = FileAgentSkillSnapshotRepository(temp.newFolder("unsafe"))
        assertThrows(IllegalArgumentException::class.java) {
            AgentSkillSourceFile("../escape.md", 1L) { ByteArrayInputStream(byteArrayOf(0)) }
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.STORE_FAILURE) {
            repository.delete("../escape")
        }
    }
}
