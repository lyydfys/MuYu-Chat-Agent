package com.muyuchat.mca.agent.data.export

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentJournalDaos
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import com.muyuchat.mca.agent.data.RoomAgentTransactionRunner
import com.muyuchat.mca.agent.data.quota.AgentCancelOutcome
import com.muyuchat.mca.agent.data.quota.AgentRunCancelRequester
import com.muyuchat.mca.agent.data.quota.AgentTaskDeletionResult
import com.muyuchat.mca.agent.data.quota.RoomAgentPackageMaintenance
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Room evidence for K14 import persistence and deletion.
 *
 * NOT_RUN in the K14 data session (no device). It covers the transaction/DAO portions that cannot be
 * exercised with pure JVM fakes: archived import persistence, no run/grant rows, verified physical
 * artifact placement, staging cleanup, TOMBSTONED ordering and child-row deletion.
 */
@RunWith(AndroidJUnit4::class)
class AgentRoomPackagePersistenceTest {
    private lateinit var database: McaRoomDatabase
    private lateinit var daos: AgentJournalDaos
    private lateinit var transactions: RoomAgentTransactionRunner
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, McaRoomDatabase::class.java).build()
        transactions = RoomAgentTransactionRunner(database)
        daos = AgentJournalDaos(
            task = database.agentTaskDao(),
            workspace = database.agentWorkspaceDao(),
            run = database.agentRunDao(),
            step = database.agentStepDao(),
            attempt = database.agentModelAttemptDao(),
            invocation = database.agentInvocationDao(),
            approval = database.agentApprovalDao(),
            event = database.agentEventDao(),
            artifact = database.agentArtifactDao(),
            artifactCommit = database.agentArtifactCommitDao(),
            question = database.agentQuestionDao(),
            command = database.agentCommandDao()
        )
        cacheRoot = File(context.cacheDir, "k14-package-test-${System.nanoTime()}")
        assertTrue(cacheRoot.mkdirs())
    }

    @After
    fun tearDown() {
        database.close()
        cacheRoot.deleteRecursively()
    }

    @Test
    fun persistImportedWritesArchivedTaskAndCommittedArtifactsWithoutRunsOrGrants() = runBlocking {
        val stagingRoot = File(cacheRoot, "staging")
        val packageDir = File(stagingRoot, "import-fixture")
        val staged = File(packageDir, "artifacts/a.txt")
        staged.parentFile?.mkdirs()
        staged.writeText("hello")
        val packageData = importedPackage(packageDir, staged, "hello".toByteArray())
        val store = packageStore(stagingRoot)
        val result = store.persistImported(packageData)
        assertTrue(result is AgentPackageResult.Success)
        val saved = (result as AgentPackageResult.Success).value

        val taskEntity = database.agentTaskDao().find(saved.taskId)
        assertTrue(taskEntity != null)
        assertEquals(AgentTaskLifecycle.IMPORTED_ARCHIVED.name, taskEntity!!.lifecycle)
        assertEquals("Imported title", taskEntity.displayTitle)
        assertTrue(!packageDir.exists())

        val artifacts = database.agentArtifactDao().listLatestCommitted(saved.taskId, 10)
        assertEquals(1, artifacts.size)
        assertEquals(AgentArtifactCommitState.COMMITTED.name, artifacts.single().commitState)
        // commitId is null for imports; the physical ref is the stored storageRef.
        val storedPhysical = File(
            AgentWorkspacePaths.taskRoot(cacheRoot, saved.workspaceId, saved.taskId),
            artifacts.single().storageRef
        )
        assertTrue(storedPhysical.isFile)
        assertEquals("hello", storedPhysical.readText())
        val nonTerminal = AgentRunState.entries.filterNot { it.isTerminal }.map { it.name }
        assertTrue(database.agentRunDao().listByTaskAndStates(saved.taskId, nonTerminal, 10).isEmpty())
        assertTrue(database.agentGrantDao().listActiveForTask(saved.taskId, Long.MAX_VALUE, 10).isEmpty())
    }

    @Test
    fun deletionTombstonesThenRemovesChildrenAndTaskFiles() = runBlocking {
        val stagingRoot = File(cacheRoot, "staging-delete")
        val packageDir = File(stagingRoot, "import-fixture-delete")
        val staged = File(packageDir, "artifacts/a.txt")
        staged.parentFile?.mkdirs()
        staged.writeText("delete me")
        val packageData = importedPackage(packageDir, staged, "delete me".toByteArray())
        val saved = (packageStore(stagingRoot).persistImported(packageData)
            as AgentPackageResult.Success).value
        val maintenance = RoomAgentPackageMaintenance(
            daos = daos,
            transactions = transactions,
            agentRoot = cacheRoot,
            cancelRequester = object : AgentRunCancelRequester {
                override suspend fun requestCancel(run: AgentRun): AgentCancelOutcome =
                    AgentCancelOutcome.Missing
            },
            grantDao = database.agentGrantDao(),
            clock = AgentClock { 2_000L }
        )
        val result = maintenance.deleteTask(saved.taskId)
        assertTrue(result is AgentTaskDeletionResult.Deleted)
        assertEquals(
            AgentTaskLifecycle.TOMBSTONED.name,
            database.agentTaskDao().find(saved.taskId)!!.lifecycle
        )
        assertTrue(database.agentArtifactDao().listLatestCommitted(saved.taskId, 10).isEmpty())
        assertFalse(AgentWorkspacePaths.taskRoot(cacheRoot, saved.workspaceId, saved.taskId).exists())
    }

    private fun packageStore(stagingRoot: File): RoomAgentPackageStore =
        RoomAgentPackageStore(
            daos = daos,
            transactions = transactions,
            agentRoot = cacheRoot,
            importStagingRoot = stagingRoot,
            clock = AgentClock { 1_000L }
        )

    private fun importedPackage(
        packageDir: File,
        staged: File,
        bytes: ByteArray
    ): AgentImportedTaskPackage = AgentImportedTaskPackage(
        taskId = "imported-task-${System.nanoTime()}",
        workspaceId = "imported-workspace-${System.nanoTime()}",
        lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED,
        displayTitle = "Imported title",
        displayStatus = AgentDisplayStatus.SUCCEEDED,
        createdAtMillis = 100L,
        updatedAtMillis = 200L,
        artifacts = listOf(
            AgentImportedArtifact(
                relativePath = "artifacts/a.txt",
                mediaType = "text/plain",
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                stagedFile = staged
            )
        ),
        stagingRoot = packageDir,
        archiveSha256 = sha256(ByteArray(0)),
        archiveBytes = 1L,
        unknownManifestJson = null
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
