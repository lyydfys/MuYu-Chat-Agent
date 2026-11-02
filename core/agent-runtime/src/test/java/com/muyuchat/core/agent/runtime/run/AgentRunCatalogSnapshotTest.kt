package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.testing.RecordingToolExecutor
import com.muyuchat.core.agent.runtime.testing.customDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N04: a run must execute with one immutable, fingerprinted catalog snapshot, and the snapshot record
 * must disappear with the run's scoped resources.
 */
class AgentRunCatalogSnapshotTest {

    private fun executor(toolKey: String, versionSuffix: String = ""): RecordingToolExecutor {
        val descriptor = customDescriptor(
            toolKey = toolKey,
            schemaJson = "{}",
            schemaHash = "a".repeat(64).dropLast(versionSuffix.length) + versionSuffix
        )
        return RecordingToolExecutor(descriptor)
    }

    private fun catalog(vararg executors: RecordingToolExecutor): AgentToolCatalog =
        FakeToolCatalog(executors.associateBy { it.descriptor.toolKey })

    @Test
    fun catalogHashIsOrderIndependentAndSensitiveToDescriptorIdentity() {
        val readA = executor("workspace.read_text")
        val list = executor("workspace.list")
        val write = executor("artifact.write_text")

        val first = AgentToolCatalogHashing.hash(catalog(readA, list, write))
        val second = AgentToolCatalogHashing.hash(catalog(write, readA, list))

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))

        val changedSchema = RecordingToolExecutor(
            customDescriptor("workspace.read_text", "{}", "b".repeat(64))
        )
        val changed = AgentToolCatalogHashing.hash(catalog(changedSchema, list, write))
        assertTrue(first != changed)
    }

    @Test
    fun recorderRecordsTheExecutedSurfaceAndReleasesIt() = runBlocking {
        val run = AgentRuntimeFixtures.run()
        val task = AgentRuntimeFixtures.task()
        val delegate = FakeRunCatalogProvider(
            catalog(readB(), readA())
        )
        val recorder = AgentRunCatalogSnapshotRecorder(delegate, clockMillis = { 1_000L })

        val catalog = recorder.catalogFor(run, task)
        val snapshot = recorder.snapshotFor(run.runId)

        assertNotNull(snapshot)
        assertEquals(run.runId, snapshot!!.runId)
        assertEquals(AgentToolCatalogHashing.hash(catalog), snapshot.catalogHash)
        assertEquals(listOf("workspace.list", "workspace.read_text"), snapshot.toolKeys)
        assertEquals(1_000L, snapshot.recordedAtMillis)

        recorder.release(run)

        assertNull(recorder.snapshotFor(run.runId))
        assertTrue("delegate release must be forwarded", delegate.released)
    }

    @Test
    fun aFailingProviderLeavesNoRecordedSnapshot() = runBlocking {
        val delegate = FakeRunCatalogProvider(
            catalog(readA()),
            failure = IllegalStateException("catalog unavailable")
        )
        val recorder = AgentRunCatalogSnapshotRecorder(delegate)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { recorder.catalogFor(AgentRuntimeFixtures.run(), AgentRuntimeFixtures.task()) }
        }
        assertNull(recorder.snapshotFor(AgentRuntimeFixtures.RUN_ID))
    }

    @Test
    fun differentRunsKeepIndependentSnapshots() = runBlocking {
        val recorder = AgentRunCatalogSnapshotRecorder(
            FakeRunCatalogProvider(catalog(readA())),
            clockMillis = { 5L }
        )
        val runA = AgentRuntimeFixtures.run(runId = "run-a")
        val runB = AgentRuntimeFixtures.run(runId = "run-b")
        val task = AgentRuntimeFixtures.task()

        recorder.catalogFor(runA, task)
        recorder.catalogFor(runB.copy(taskId = task.taskId), task)
        recorder.release(runA)

        assertNull(recorder.snapshotFor(runA.runId))
        assertNotNull(recorder.snapshotFor(runB.runId))
    }

    private fun readA(): RecordingToolExecutor = executor("workspace.read_text")
    private fun readB(): RecordingToolExecutor = executor("workspace.list")

    private class FakeRunCatalogProvider(
        private val catalog: AgentToolCatalog,
        private val failure: Throwable? = null
    ) : AgentRunToolCatalogProvider {
        var released = false

        override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
            failure?.let { throw it }
            return catalog
        }

        override suspend fun release(run: AgentRun) {
            released = true
        }
    }
}
