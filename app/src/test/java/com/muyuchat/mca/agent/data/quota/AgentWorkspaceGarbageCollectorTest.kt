package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentWorkspaceGarbageCollectorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var agentRoot: File
    private val now = 10_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        agentRoot = temporary.newFolder("agent")
    }

    @Test
    fun `only old staging files are removed`() = runBlocking {
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, "workspace-1", "task-1", create = true)
        val oldStaging = File(taskRoot, "staging/old.part").apply { writeText("old") }
        oldStaging.setLastModified(now - 40 * day)
        val newStaging = File(taskRoot, "staging/new.part").apply { writeText("new") }
        newStaging.setLastModified(now - 1 * day)
        val artifact = File(taskRoot, "artifacts/report.md").apply { writeText("artifact") }
        artifact.setLastModified(now - 40 * day)
        val payload = File(taskRoot, "payloads/p.bin").apply { writeText("payload") }
        payload.setLastModified(now - 40 * day)

        val collector = AgentWorkspaceGarbageCollector(
            agentRoot = agentRoot,
            clock = AgentClock { now },
            maxAgeMillis = 30 * day,
            maxTasks = 10,
            maxFilesPerTask = 100
        )
        val result = collector.collectOrphanStaging()
        assertEquals(1, result.deletedStagingFiles)
        assertEquals("old".length.toLong(), result.deletedBytes)
        assertFalse("old staging removed", oldStaging.exists())
        assertTrue("fresh staging kept", newStaging.isFile)
        assertTrue("committed artifacts never GC'd", artifact.isFile)
        assertTrue("payloads never GC'd", payload.isFile)
        assertEquals(0, result.failedDeletions)
    }

    @Test
    fun `task scan is bounded`() = runBlocking {
        for (index in 1..3) {
            val root = AgentWorkspacePaths.taskRoot(agentRoot, "workspace-1", "task-$index", create = true)
            File(root, "staging/old.part").apply {
                writeText("x")
                setLastModified(now - 40 * day)
            }
        }
        val collector = AgentWorkspaceGarbageCollector(
            agentRoot = agentRoot,
            clock = AgentClock { now },
            maxAgeMillis = 30 * day,
            maxTasks = 2,
            maxFilesPerTask = 10
        )
        val result = collector.collectOrphanStaging()
        assertTrue(result.bounded)
        assertTrue(result.scannedTasks <= 2)
        assertTrue(result.deletedStagingFiles <= 2)
    }
}
