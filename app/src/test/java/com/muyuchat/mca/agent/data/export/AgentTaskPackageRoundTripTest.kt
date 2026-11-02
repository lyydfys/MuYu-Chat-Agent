package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.runtime.journal.AgentArtifactReader
import com.muyuchat.core.agent.runtime.journal.AgentTaskReader
import com.muyuchat.mca.agent.data.AgentArtifactStore
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.data.FakeArtifactLedger
import com.muyuchat.core.agent.contract.AgentFileResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentTaskPackageRoundTripTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var agentRoot: File
    private lateinit var exportStaging: File
    private lateinit var importStaging: File
    private lateinit var ledger: FakeArtifactLedger
    private lateinit var artifactStore: AgentArtifactStore
    private val clock = AgentTestFixtures.MutableClock(1_000L)
    private val ids = AgentTestFixtures.SequentialIds()

    private val workspaceId = AgentTestFixtures.WORKSPACE_ID
    private val taskId = AgentTestFixtures.TASK_ID

    @Before
    fun setUp() {
        agentRoot = temporary.newFolder("agent")
        exportStaging = temporary.newFolder("export-staging")
        importStaging = temporary.newFolder("import-staging")
        ledger = FakeArtifactLedger()
        artifactStore = AgentArtifactStore(
            agentRoot = agentRoot,
            ledger = ledger,
            clock = clock,
            idGenerator = ids
        )
    }

    @Test
    fun `export then import preserves paths sizes hashes and content`() = runBlocking {
        writeArtifact("artifacts/a.txt", "alpha")
        writeArtifact("artifacts/nested/b.md", "# beta 世界")
        val task = AgentTestFixtures.task(taskId = taskId, workspaceId = workspaceId)
        val artifacts = committedArtifacts()
        val packageBytes = export(task, artifacts)
        val imported = importPackage(packageBytes)

        assertEquals(AgentTaskLifecycle.IMPORTED_ARCHIVED, imported.lifecycle)
        assertNotEquals(task.taskId, imported.taskId)
        assertNotEquals(task.workspaceId, imported.workspaceId)
        assertEquals("Safe title", imported.displayTitle)
        assertEquals(task.displayStatus, imported.displayStatus)
        assertEquals(
            listOf("artifacts/a.txt", "artifacts/nested/b.md"),
            imported.artifacts.map { it.relativePath }
        )
        val byPath = imported.artifacts.associateBy { it.relativePath }
        assertEquals("alpha", byPath.getValue("artifacts/a.txt").stagedFile.readText())
        assertEquals("# beta 世界", byPath.getValue("artifacts/nested/b.md").stagedFile.readText())
        artifacts.forEach { artifact ->
            val importedArtifact = byPath.getValue(artifact.relativePath)
            assertEquals(artifact.sizeBytes, importedArtifact.sizeBytes)
            assertEquals(artifact.sha256, importedArtifact.sha256)
            assertTrue(importedArtifact.stagedFile.isFile)
        }
        assertTrue(imported.stagingRoot.isDirectory)
        assertTrue(imported.stagingRoot.canonicalPath.startsWith(importStaging.canonicalPath))
    }

    @Test
    fun `manifest is deterministic sorted and contains no goal grants or credentials`() = runBlocking {
        writeArtifact("artifacts/z.txt", "z")
        writeArtifact("artifacts/a.txt", "a")
        val task = AgentTestFixtures.task(taskId = taskId, workspaceId = workspaceId)
            .copy(goal = "TOP_SECRET_GOAL must never be exported")
        val artifacts = committedArtifacts()
        val first = export(task, artifacts)
        val second = export(task, artifacts)
        assertArrayEquals("package bytes must be deterministic for a fixed clock", first, second)

        val entryNames = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(first)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryNames[entry.name] = zip.readBytes()
            }
        }
        assertEquals(
            listOf("manifest.json", "artifacts/a.txt", "artifacts/z.txt"),
            entryNames.keys.toList()
        )
        val manifest = JSONObject(String(entryNames.getValue("manifest.json"), Charsets.UTF_8))
        assertEquals("mca.agent.task-package.v1", manifest.getString("format"))
        assertEquals(1, manifest.getInt("version"))
        assertEquals("Safe title", manifest.getJSONObject("task").getString("displayTitle"))
        assertFalse(manifest.has("goal"))
        assertFalse(manifest.has("grants"))
        assertFalse(manifest.has("policy"))
        assertFalse(manifest.has("credentials"))
        val artifactsJson = manifest.getJSONArray("artifacts")
        assertEquals("artifacts/a.txt", artifactsJson.getJSONObject(0).getString("relativePath"))
        assertEquals("artifacts/z.txt", artifactsJson.getJSONObject(1).getString("relativePath"))
        val raw = String(first, Charsets.ISO_8859_1) + String(entryNames.getValue("manifest.json"), Charsets.UTF_8)
        assertFalse("goal text must never appear in the package", raw.contains("TOP_SECRET_GOAL"))
    }

    @Test
    fun `non committed artifacts are excluded and a missing committed file fails`() = runBlocking {
        writeArtifact("artifacts/kept.txt", "kept")
        val task = AgentTestFixtures.task(taskId = taskId, workspaceId = workspaceId)
        val ghost = AgentTestFixtures.artifact(
            artifactId = "artifact-ghost",
            taskId = taskId,
            invocationId = null,
            relativePath = "artifacts/ghost.txt",
            commitState = AgentArtifactCommitState.PREPARING
        )
        val packageBytes = export(task, committedArtifacts() + ghost)
        val imported = importPackage(packageBytes)
        assertEquals(listOf("artifacts/kept.txt"), imported.artifacts.map { it.relativePath })

        val committed = committedArtifacts().single()
        val physical = File(artifactStore.taskRoot(workspaceId, taskId), ledger.artifacts.getValue(committed.artifactId).storageRef)
        assertTrue(physical.delete())
        val failure = exportFailure(task, committedArtifacts())
        assertEquals(AgentPackageErrorCode.ARTIFACT_FILE_MISSING, failure.code)
    }

    @Test
    fun `tombstoned tasks cannot be exported`() = runBlocking {
        val task = AgentTestFixtures.task(
            taskId = taskId,
            workspaceId = workspaceId,
            lifecycle = AgentTaskLifecycle.TOMBSTONED
        )
        val failure = exportFailure(task, emptyList())
        assertEquals(AgentPackageErrorCode.TASK_TOMBSTONED, failure.code)
    }

    @Test
    fun `export rejects artifact paths that collide after normalization`() = runBlocking {
        writeArtifact("artifacts/A.txt", "upper")
        writeArtifact("artifacts/a.txt", "lower")
        val task = AgentTestFixtures.task(taskId = taskId, workspaceId = workspaceId)
        val failure = exportFailure(task, committedArtifacts())
        assertEquals(AgentPackageErrorCode.DUPLICATE_ENTRY, failure.code)
    }

    @Test
    fun `empty artifact list round trips`() = runBlocking {
        val task = AgentTestFixtures.task(taskId = taskId, workspaceId = workspaceId)
        val imported = importPackage(export(task, emptyList()))
        assertTrue(imported.artifacts.isEmpty())
        assertEquals(AgentTaskLifecycle.IMPORTED_ARCHIVED, imported.lifecycle)
    }

    // --- helpers -------------------------------------------------------------------------
    private suspend fun writeArtifact(path: String, content: String) {
        val result = artifactStore.writeArtifact(
            workspaceId = workspaceId,
            taskId = taskId,
            invocationId = AgentTestFixtures.INVOCATION_ID,
            logicalPath = path,
            content = content,
            expectedOldHash = null,
            policyVersion = 1L
        )
        when (result) {
            is AgentFileResult.Ok -> Unit
            is AgentFileResult.Failure -> error("artifact write failed: ${result.errorCode}")
        }
    }

    private fun committedArtifacts(): List<AgentArtifact> = ledger.artifacts.values
        .filter { it.artifact.commitState == AgentArtifactCommitState.COMMITTED }
        .map { it.artifact }

    private suspend fun export(task: AgentTask, artifacts: List<AgentArtifact>): ByteArray {
        val output = ByteArrayOutputStream()
        val exporter = exporter(task, artifacts)
        val result = exporter.export(task.taskId, output)
        when (result) {
            is AgentPackageResult.Success -> Unit
            is AgentPackageResult.Failure -> error("export failed: ${result.code} ${result.safeMessage}")
        }
        return output.toByteArray()
    }

    private suspend fun exportFailure(
        task: AgentTask,
        artifacts: List<AgentArtifact>
    ): AgentPackageResult.Failure {
        val output = ByteArrayOutputStream()
        val result = exporter(task, artifacts).export(task.taskId, output)
        return result as? AgentPackageResult.Failure ?: error("expected export failure")
    }

    private fun exporter(task: AgentTask, artifacts: List<AgentArtifact>): AgentTaskPackageExporter =
        AgentTaskPackageExporter(
            taskReader = object : AgentTaskReader {
                override suspend fun readTask(taskId: String): AgentTask? = task
            },
            artifactReader = object : AgentArtifactReader {
                override suspend fun readArtifacts(taskId: String): List<AgentArtifact> = artifacts
            },
            artifactStore = artifactStore,
            stagingRoot = exportStaging,
            displayTitleReader = AgentTaskDisplayTitleReader { "Safe title" },
            clock = clock,
            limits = AgentTaskPackageLimits()
        )

    private suspend fun importPackage(packageBytes: ByteArray): AgentImportedTaskPackage {
        val importer = AgentTaskPackageImporter(
            stagingRoot = importStaging,
            idGenerator = AgentTestFixtures.SequentialIds(100),
            limits = AgentTaskPackageLimits()
        )
        val result = importer.import(ByteArrayInputStream(packageBytes))
        return when (result) {
            is AgentPackageResult.Success -> result.value
            is AgentPackageResult.Failure -> error("import failed: ${result.code} ${result.safeMessage}")
        }
    }
}
