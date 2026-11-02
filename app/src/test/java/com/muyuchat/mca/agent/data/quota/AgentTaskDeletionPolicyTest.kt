package com.muyuchat.mca.agent.data.quota

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.mca.agent.data.AgentTestFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentTaskDeletionPolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var agentRoot: File
    private lateinit var retryStore: FileAgentFileCleanupRetryStore
    private val clock = AgentTestFixtures.MutableClock(10_000L)
    private val taskId = AgentTestFixtures.TASK_ID

    @Before
    fun setUp() {
        agentRoot = temporary.newFolder("agent")
        retryStore = FileAgentFileCleanupRetryStore(agentRoot)
    }

    @Test
    fun `active run is cancelled and tombstoned only after it becomes terminal`() = runBlocking {
        val port = FakePort(task(), AgentTestFixtures.run(state = AgentRunState.RUNNING))
        port.onCancel = { port.run = null }
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.Deleted)
        assertEquals(
            listOf("findTask", "findRun", "cancel", "findRun", "tombstone", "records", "files"),
            port.calls
        )
        assertEquals(AgentTaskLifecycle.TOMBSTONED, port.task!!.lifecycle)
    }

    @Test
    fun `stopping run blocks tombstone until stop is confirmed`() = runBlocking {
        val port = FakePort(task(), AgentTestFixtures.run(state = AgentRunState.RUNNING))
        port.onCancel = { port.run = port.run?.copy(state = AgentRunState.STOPPING) }
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.AwaitingStop)
        assertFalse(port.calls.contains("tombstone"))
        assertFalse(port.calls.contains("records"))
        assertFalse(port.calls.contains("files"))
        assertEquals(AgentTaskLifecycle.ACTIVE, port.task!!.lifecycle)
    }

    @Test
    fun `cancel accepted but run still running returns cancellation requested`() = runBlocking {
        val port = FakePort(task(), AgentTestFixtures.run(state = AgentRunState.RUNNING))
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.CancellationRequested)
        assertFalse(port.calls.contains("tombstone"))
    }

    @Test
    fun `rejected cancellation never tombstones`() = runBlocking {
        val port = FakePort(task(), AgentTestFixtures.run(state = AgentRunState.RUNNING))
        port.cancelOutcome = AgentCancelOutcome.Rejected("RESOURCE_BUSY")
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.Rejected)
        assertEquals(AgentDeletionErrorCode.CANCEL_REJECTED, (result as AgentTaskDeletionResult.Rejected).code)
        assertFalse(port.calls.contains("tombstone"))
    }

    @Test
    fun `paused run holds no execution slot and does not block deletion`() = runBlocking {
        val port = FakePort(task(), AgentTestFixtures.run(state = AgentRunState.PAUSED))
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.Deleted)
        assertFalse(port.calls.contains("cancel"))
        assertTrue(port.calls.contains("tombstone"))
    }

    @Test
    fun `already tombstoned task completes cleanup idempotently`() = runBlocking {
        val port = FakePort(task().copy(lifecycle = AgentTaskLifecycle.TOMBSTONED), null)
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.Deleted)
        assertEquals(listOf("findTask", "records", "files"), port.calls)
        assertFalse(port.calls.contains("cancel"))
        assertFalse(port.calls.contains("tombstone"))
    }

    @Test
    fun `file cleanup failure writes a retry record and a later retry clears it`() = runBlocking {
        val port = FakePort(task(), null)
        port.filesDeleted = false
        val first = policy(port).deleteTask(taskId)
        assertTrue(first is AgentTaskDeletionResult.Deleted)
        val deferred = (first as AgentTaskDeletionResult.Deleted).fileCleanup
        assertTrue(deferred is AgentFileCleanupResult.Deferred)
        val record = (deferred as AgentFileCleanupResult.Deferred).record
        assertEquals(1, record.attempts)
        assertEquals(taskId, record.taskId)
        assertEquals(1, retryStore.pending(10).size)

        clock.advance(61_000L)
        port.filesDeleted = true
        val retried = policy(port).retryPendingCleanup(10)
        assertEquals(1, retried.size)
        assertTrue(retried.single().cleared)
        assertTrue(retryStore.pending(10).isEmpty())
    }

    @Test
    fun `failed record deletion defers file cleanup instead of deleting referenced files`() = runBlocking {
        val port = FakePort(task(), null)
        port.recordsDeleted = false
        val result = policy(port).deleteTask(taskId)
        assertTrue(result is AgentTaskDeletionResult.Deleted)
        val deleted = result as AgentTaskDeletionResult.Deleted
        assertFalse(deleted.recordsDeleted)
        assertEquals(AgentFileCleanupResult.NotNeeded, deleted.fileCleanup)
        assertFalse(port.calls.contains("files"))
    }

    private fun task(): AgentTask = AgentTestFixtures.task(taskId = taskId, workspaceId = AgentTestFixtures.WORKSPACE_ID)

    private fun policy(port: FakePort): AgentTaskDeletionPolicy =
        AgentTaskDeletionPolicy(port = port, retryStore = retryStore, clock = clock)

    private class FakePort(
        var task: AgentTask?,
        var run: AgentRun?
    ) : AgentTaskDeletionPort {
        val calls = mutableListOf<String>()
        var cancelOutcome: AgentCancelOutcome = AgentCancelOutcome.Accepted
        var onCancel: (() -> Unit)? = null
        var recordsDeleted = true
        var filesDeleted = true

        override suspend fun findTask(taskId: String): AgentTask? {
            calls += "findTask"
            return task
        }

        override suspend fun findLatestNonTerminalRun(taskId: String): AgentRun? {
            calls += "findRun"
            return run
        }

        override suspend fun requestCancel(run: AgentRun): AgentCancelOutcome {
            calls += "cancel"
            onCancel?.invoke()
            return cancelOutcome
        }

        override suspend fun markTombstoned(taskId: String, expectedRevision: Long): Boolean {
            calls += "tombstone"
            val current = task ?: return false
            if (current.revision != expectedRevision) return false
            task = current.copy(
                lifecycle = AgentTaskLifecycle.TOMBSTONED,
                revision = expectedRevision + 1
            )
            return true
        }

        override suspend fun deleteRelatedRecords(taskId: String): Boolean {
            calls += "records"
            return recordsDeleted
        }

        override suspend fun deleteTaskFiles(workspaceId: String, taskId: String): Boolean {
            calls += "files"
            return filesDeleted
        }
    }
}
