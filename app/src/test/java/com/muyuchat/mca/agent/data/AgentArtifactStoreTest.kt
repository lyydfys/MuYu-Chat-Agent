package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentArtifactStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workspaceId = "workspace-1"
    private val taskId = "task-1"
    private val clock = AgentTestFixtures.MutableClock()
    private val ids = AgentTestFixtures.SequentialIds()

    @Test
    fun `artifact write stages renames and commits an immutable version`() = runBlocking {
        val ledger = FakeArtifactLedger()
        val store = newStore(temporary.root, ledger)
        val content = "# report\nhello"
        val result = store.writeArtifact(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/report.md", content, null, 1L
        ).requireValue()
        assertEquals("artifacts/report.md", result.logicalPath)
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toLong(), result.sizeBytes)
        val commit = ledger.commits.values.single()
        assertEquals(AgentArtifactCommitPhase.COMMITTED, commit.phase)
        val stored = ledger.artifacts.values.single()
        assertTrue(stored.isLatest)
        assertEquals(1L, stored.version)
        val physical = File(store.taskRoot(workspaceId, taskId), stored.storageRef)
        assertTrue("physical immutable version must exist", physical.isFile)
        assertEquals(content, physical.readText())
        assertFalse(File(store.taskRoot(workspaceId, taskId), commit.stagingRef).exists())
    }

    @Test
    fun `overwrite requires the previous hash and keeps the old version`() = runBlocking {
        val ledger = FakeArtifactLedger()
        val store = newStore(temporary.root, ledger)
        val first = store.writeArtifact(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/report.md", "v1", null, 1L
        ).requireValue()
        val second = store.writeArtifact(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/report.md", "v2", first.sha256, 1L
        ).requireValue()
        assertEquals(2, ledger.artifacts.size)
        assertEquals(2L, ledger.latest(taskId, "artifacts/report.md")!!.version)
        assertEquals("v2", File(store.taskRoot(workspaceId, taskId), ledger.latest(taskId, "artifacts/report.md")!!.storageRef).readText())
        val v1 = ledger.artifacts.values.single { it.version == 1L }
        assertEquals("v1", File(store.taskRoot(workspaceId, taskId), v1.storageRef).readText())
        assertFalse(v1.isLatest)
        assertEquals(2, ledger.commits.values.count { it.phase == AgentArtifactCommitPhase.COMMITTED })
        assertNotNull(second.artifactId)
    }

    @Test
    fun `create-only preconditions and validation fail before any staging write`() = runBlocking {
        val ledger = FakeArtifactLedger()
        val store = newStore(temporary.root, ledger)
        store.writeArtifact(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/report.md", "v1", null, 1L
        ).requireValue()
        assertEquals(
            AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED,
            store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/report.md", "v2", null, 1L
            ).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED,
            store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/report.md", "v2", "b".repeat(64), 1L
            ).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "inputs/report.md", "v2", null, 1L
            ).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/.versions/x.blob", "v2", null, 1L
            ).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/big.txt", "x".repeat(AgentDataLimits.MAX_ARTIFACT_WRITE_BYTES + 1), null, 1L
            ).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/report.md", "v2", "not-a-hash", 1L
            ).requireFailureCode()
        )
        assertEquals(1, ledger.commits.size)
    }

    @Test
    fun `race between precheck and db commit returns precondition changed and keeps the renamed file`() =
        runBlocking {
            val ledger = FakeArtifactLedger()
            val store = newStore(temporary.root, ledger)
            val first = store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/report.md", "v1", null, 1L
            ).requireValue()
            ledger.mutateLatestBeforeCommit = {
                ledger.mutateLatestForTest(taskId, "artifacts/report.md") { current ->
                    current.copy(artifact = current.artifact.copy(sha256 = "b".repeat(64)))
                }
            }
            val failure = store.writeArtifact(
                workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                "artifacts/report.md", "v2", first.sha256, 1L
            ).requireFailure()
            assertEquals(AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED, failure.errorCode)
            assertTrue(failure.possibleSideEffects)
            val failedCommit = ledger.commits.values.single { it.phase == AgentArtifactCommitPhase.FAILED }
            val failedArtifact = ledger.artifacts[failedCommit.artifactId]!!
            val physical = File(
                store.taskRoot(workspaceId, taskId),
                AgentArtifactLayout.physicalStorageRef(failedCommit.artifactId, failedCommit.commitId)
            )
            assertTrue("renamed physical version must not be deleted on conflict", physical.isFile)
            assertEquals("v2", physical.readText())
            assertNull(ledger.latest(taskId, "artifacts/report.md")!!.takeIf {
                it.artifact.artifactId == failedArtifact.artifact.artifactId
            })
        }

    @Test
    fun `every crash point is reconciled by hash without rewriting the tool result`() {
        val expected = mapOf(
            AgentArtifactCrashPoint.BEFORE_STAGING_WRITE to AgentArtifactRecoveryStatus.FAILED,
            AgentArtifactCrashPoint.AFTER_STAGING_WRITE to AgentArtifactRecoveryStatus.RECOVERED,
            AgentArtifactCrashPoint.BEFORE_RENAME to AgentArtifactRecoveryStatus.RECOVERED,
            AgentArtifactCrashPoint.AFTER_RENAME to AgentArtifactRecoveryStatus.RECOVERED,
            AgentArtifactCrashPoint.BEFORE_DB_COMMIT to AgentArtifactRecoveryStatus.RECOVERED,
            AgentArtifactCrashPoint.AFTER_DB_COMMIT to AgentArtifactRecoveryStatus.COMMITTED
        )
        expected.forEach { (point, status) ->
            val caseRoot = temporary.newFolder("crash-${point.name.lowercase()}")
            val ledger = FakeArtifactLedger()
            val ids = AgentTestFixtures.SequentialIds()
            val crashing = AgentArtifactStore(
                agentRoot = caseRoot,
                ledger = ledger,
                clock = clock,
                idGenerator = ids,
                crashInjector = AgentArtifactCrashInjector { crashPoint ->
                    if (crashPoint == point) throw AgentArtifactCrashException(crashPoint)
                }
            )
            runBlocking {
                try {
                    crashing.writeArtifact(
                        workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                        "artifacts/report.md", "content-$point", null, 1L
                    )
                    fail("expected simulated crash at $point")
                } catch (error: AgentArtifactCrashException) {
                    assertEquals(point, error.point)
                }
                val commit = ledger.commits.values.single()
                val recovery = AgentArtifactStore(
                    agentRoot = caseRoot,
                    ledger = ledger,
                    clock = clock,
                    idGenerator = ids,
                    crashInjector = AgentArtifactCrashInjector.NONE
                ).reconcile(workspaceId, taskId, commit.commitId)
                assertEquals("crash point $point", status, recovery.status)
                // A second reconciliation must not create a second artifact version.
                val again = AgentArtifactStore(
                    agentRoot = caseRoot,
                    ledger = ledger,
                    clock = clock,
                    idGenerator = ids
                ).reconcile(workspaceId, taskId, commit.commitId)
                assertTrue(
                    "second reconcile must stay settled at $point, was ${again.status}",
                    again.status == AgentArtifactRecoveryStatus.COMMITTED ||
                        again.status == AgentArtifactRecoveryStatus.FAILED
                )
                assertEquals(1, ledger.artifacts.size)
                if (status != AgentArtifactRecoveryStatus.FAILED) {
                    assertEquals(1L, ledger.artifacts.values.single().version)
                }
            }
        }
    }

    @Test
    fun `reconcileTask settles only the requested task and never touches other task roots`() =
        runBlocking {
            val ledger = FakeArtifactLedger()
            val store = newStore(temporary.root, ledger)
            val crashing = AgentArtifactStore(
                agentRoot = temporary.root,
                ledger = ledger,
                clock = clock,
                idGenerator = AgentTestFixtures.SequentialIds(),
                crashInjector = AgentArtifactCrashInjector { throw AgentArtifactCrashException(it) }
            )
            try {
                crashing.writeArtifact(
                    workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
                    "artifacts/report.md", "crash", null, 1L
                )
            } catch (_: AgentArtifactCrashException) {
                // expected at the first checkpoint
            }
            val otherRoot = store.ensureTaskRoots("workspace-2", "task-2")
            val sentinel = File(otherRoot, "inputs/sentinel.txt").apply { writeText("keep me") }
            val results = store.reconcileTask(workspaceId, taskId, 10)
            assertEquals(1, results.size)
            assertTrue(sentinel.isFile)
            assertEquals("keep me", sentinel.readText())
        }

    @Test
    fun `soft task quota rejects the write with STORAGE_NO_SPACE`() = runBlocking {
        val ledger = FakeArtifactLedger()
        val store = newStore(temporary.root, ledger)
        val taskRoot = store.taskRoot(workspaceId, taskId)
        val sparse = File(taskRoot, "payloads/sparse.bin")
        sparse.parentFile?.mkdirs()
        RandomAccessFile(sparse, "rw").use { it.setLength(AgentDataLimits.TASK_SOFT_QUOTA_BYTES) }
        val failure = store.writeArtifact(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/report.md", "small", null, 1L
        ).requireFailure()
        assertEquals(AgentDataErrorCodes.STORAGE_NO_SPACE, failure.errorCode)
        assertTrue(ledger.commits.isEmpty())
    }

    @Test
    fun `workspace store is the contract file port and serves reads and writes`() = runBlocking {
        val ledger = FakeArtifactLedger()
        val store = AgentWorkspaceStore(
            agentRoot = temporary.root,
            ledger = ledger,
            clock = clock,
            idGenerator = ids
        )
        val port: AgentWorkspaceFilePort = store
        val write = port.writeArtifact(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/report.md", "hello", null, 1L
        ).requireValue()
        assertEquals("artifacts/report.md", write.logicalPath)
        val read = port.readText(workspaceId, taskId, "artifacts/report.md", 0L, 16).requireValue()
        assertEquals("hello", read.content)
        assertEquals(write.sha256, read.sha256)
        val list = port.list(workspaceId, taskId, "artifacts", 10, null).requireValue()
        assertEquals(listOf("artifacts/report.md"), list.entries.map { it.logicalPath })
        val search = port.searchText(workspaceId, taskId, "hello", "artifacts", 10).requireValue()
        assertEquals(listOf("artifacts/report.md"), search.matches.map { it.logicalPath })
        val recovered = store.reconcile(workspaceId, taskId, ledger.commits.keys.single())
        assertEquals(AgentArtifactRecoveryStatus.COMMITTED, recovered.status)
    }

    private fun newStore(root: File, ledger: FakeArtifactLedger): AgentArtifactStore =
        AgentArtifactStore(
            agentRoot = root,
            ledger = ledger,
            clock = clock,
            idGenerator = ids,
            crashInjector = AgentArtifactCrashInjector.NONE
        )

    private fun <T> AgentFileResult<T>.requireValue(): T = when (this) {
        is AgentFileResult.Ok -> value
        is AgentFileResult.Failure -> error("unexpected failure $errorCode: $safeMessage")
    }

    private fun <T> AgentFileResult<T>.requireFailureCode(): String =
        requireFailure().errorCode

    private fun <T> AgentFileResult<T>.requireFailure(): AgentFileResult.Failure = when (this) {
        is AgentFileResult.Failure -> this
        is AgentFileResult.Ok -> error("expected failure but got $value")
    }
    @Test
    fun `binary artifacts commit byte-exact with hash and size`() = runBlocking {
        val ledger = FakeArtifactLedger()
        val store = newStore(temporary.root, ledger)
        val bytes = ByteArray(256) { it.toByte() }
        val result = store.writeArtifactBytes(
            workspaceId, taskId, AgentTestFixtures.INVOCATION_ID,
            "artifacts/blob.bin", bytes, null, 1L
        ).requireValue()

        assertEquals(bytes.size.toLong(), result.sizeBytes)
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, result.sha256)
        val stored = ledger.artifacts.values.single()
        val physical = File(store.taskRoot(workspaceId, taskId), stored.storageRef)
        assertArrayEquals(bytes, physical.readBytes())
    }

}
