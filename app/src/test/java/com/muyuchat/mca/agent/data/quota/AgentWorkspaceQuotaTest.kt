package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentWorkspaceQuotaTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var agentRoot: File
    private val workspaceId = "workspace-1"
    private val taskId = "task-1"

    @Before
    fun setUp() {
        agentRoot = temporary.newFolder("agent")
        AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)
        AgentWorkspacePaths.taskRoot(agentRoot, "workspace-2", "task-2", create = true)
    }

    @Test
    fun `soft task and total boundaries are exact`() {
        writeBytes(workspaceId, taskId, 90L)
        val quota = AgentWorkspaceQuota(
            agentRoot = agentRoot,
            limits = AgentWorkspaceQuotaLimits(
                taskSoftBytes = 100L,
                totalSoftBytes = 200L,
                diskSafetyBytes = 0L
            ),
            diskFreeBytes = { 1_000L }
        )
        assertEquals(AgentQuotaDecision.Allowed, quota.evaluate(workspaceId, taskId, 10L))
        val taskExceeded = quota.evaluate(workspaceId, taskId, 11L)
        assertTrue(taskExceeded is AgentQuotaDecision.SoftLimitExceeded)
        assertEquals(AgentQuotaScope.TASK, (taskExceeded as AgentQuotaDecision.SoftLimitExceeded).scope)
        assertEquals(10L, quota.remainingTaskBytes(workspaceId, taskId))

        writeBytes("workspace-2", "task-2", 190L)
        // Task has 90/100 but total is 280/200 -> TOTAL wins before the disk check.
        val totalExceeded = quota.evaluate(workspaceId, taskId, 1L)
        assertTrue(totalExceeded is AgentQuotaDecision.SoftLimitExceeded)
        assertEquals(AgentQuotaScope.TOTAL, (totalExceeded as AgentQuotaDecision.SoftLimitExceeded).scope)
    }

    @Test
    fun `low disk returns a typed denial and never deletes data`() {
        writeBytes(workspaceId, taskId, 10L)
        val marker = File(AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId), "payloads/seed.bin")
        assertTrue(marker.isFile)
        val quota = AgentWorkspaceQuota(
            agentRoot = agentRoot,
            limits = AgentWorkspaceQuotaLimits(
                taskSoftBytes = 1_000L,
                totalSoftBytes = 1_000L,
                diskSafetyBytes = 10L
            ),
            diskFreeBytes = { 5L }
        )
        val decision = quota.evaluate(workspaceId, taskId, 1L)
        assertTrue(decision is AgentQuotaDecision.DiskSpaceInsufficient)
        assertEquals(5L, (decision as AgentQuotaDecision.DiskSpaceInsufficient).usableBytes)
        assertTrue("quota checks must not delete existing data", marker.isFile)
        assertEquals(10L, marker.length())
    }

    @Test
    fun `unknown zero disk reading is treated as non-blocking`() {
        val quota = AgentWorkspaceQuota(
            agentRoot = agentRoot,
            limits = AgentWorkspaceQuotaLimits(diskSafetyBytes = 100L),
            diskFreeBytes = { 0L }
        )
        assertEquals(AgentQuotaDecision.Allowed, quota.evaluateTotal(1L))
    }

    @Test
    fun `quota accounts across tasks for the total threshold`() {
        writeBytes(workspaceId, taskId, 40L)
        writeBytes("workspace-2", "task-2", 60L)
        val quota = AgentWorkspaceQuota(
            agentRoot = agentRoot,
            limits = AgentWorkspaceQuotaLimits(
                taskSoftBytes = 1_000L,
                totalSoftBytes = 100L,
                diskSafetyBytes = 0L
            ),
            diskFreeBytes = { 1_000L }
        )
        assertEquals(AgentQuotaDecision.Allowed, quota.evaluateTotal(0L))
        val exceeded = quota.evaluateTotal(1L)
        assertTrue(exceeded is AgentQuotaDecision.SoftLimitExceeded)
        assertEquals(AgentQuotaScope.TOTAL, (exceeded as AgentQuotaDecision.SoftLimitExceeded).scope)
        assertEquals(100L, exceeded.usedBytes)
    }

    private fun writeBytes(workspaceId: String, taskId: String, length: Long) {
        val root = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)
        val file = File(root, "payloads/seed.bin")
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }
}
