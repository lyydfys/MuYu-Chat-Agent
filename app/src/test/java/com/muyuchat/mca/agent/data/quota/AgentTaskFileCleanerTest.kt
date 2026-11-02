package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentTaskFileCleanerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var agentRoot: File
    private lateinit var cleaner: AgentTaskFileCleaner

    @Before
    fun setUp() {
        agentRoot = temporary.newFolder("agent")
        cleaner = AgentTaskFileCleaner(agentRoot)
    }

    @Test
    fun `deleting one task never touches a sibling task root`() = runBlocking {
        val first = AgentWorkspacePaths.taskRoot(agentRoot, "workspace-1", "task-1", create = true)
        val second = AgentWorkspacePaths.taskRoot(agentRoot, "workspace-1", "task-2", create = true)
        File(first, "inputs/a.txt").writeText("a")
        File(first, "artifacts/report.md").writeText("report")
        File(first, "payloads/p.bin").writeText("payload")
        File(first, "staging/tmp.part").writeText("tmp")
        val sentinel = File(second, "inputs/keep.txt").apply { writeText("keep") }

        assertTrue(cleaner.deleteTaskFiles("workspace-1", "task-1"))
        assertFalse(first.exists())
        assertTrue(sentinel.isFile)
        assertTrue(second.isDirectory)
        assertTrue(File(agentRoot, "workspaces/workspace-1/tasks").isDirectory)
    }

    @Test
    fun `symbolic links are deleted as links without following the target`() = runBlocking {
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, "workspace-1", "task-1", create = true)
        val outside = temporary.newFolder("outside")
        val outsideFile = File(outside, "outside.txt").apply { writeText("outside") }
        val link = File(taskRoot, "inputs/link.txt")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outsideFile.toPath())
            true
        } catch (_: Exception) {
            false
        }
        Assume.assumeTrue("symbolic links are not supported on this platform", created)
        assertTrue(cleaner.deleteTaskFiles("workspace-1", "task-1"))
        assertFalse(taskRoot.exists())
        assertTrue(outsideFile.isFile)
        assertTrue(outsideFile.readText() == "outside")
    }

    @Test
    fun `missing task root is already clean`() = runBlocking {
        assertTrue(cleaner.deleteTaskFiles("workspace-1", "missing-task"))
    }
}
