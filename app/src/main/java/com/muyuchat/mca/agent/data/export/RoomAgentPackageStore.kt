package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.mca.agent.data.AgentArtifactEntity
import com.muyuchat.mca.agent.data.AgentArtifactLayout
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentEntityMappers
import com.muyuchat.mca.agent.data.AgentFileIo
import com.muyuchat.mca.agent.data.AgentJournalDaos
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.data.AgentWorkspaceEntity
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedImportedTask(
    val taskId: String,
    val workspaceId: String,
    val artifactIds: List<String>
)

/**
 * Persists one verified [AgentImportedTaskPackage] as an archived task.
 *
 * Physical artifact copies happen BEFORE the Room transaction; the transaction then inserts the
 * workspace, task and committed artifact rows atomically. If the DB transaction fails, the newly
 * copied physical versions and the import staging directory are removed. No run, grant, event,
 * command, question or approval row is created, and the insert makes no network/tool call.
 *
 * Note: `agent_artifact_commits.invocationId` is NOT NULL with an invocation FK in schema v28, so an
 * imported artifact (which has no invocation) cannot own a commit-journal row without a migration.
 * This store therefore writes COMMITTED artifact rows with `commitId = null`; see the K14 report for
 * the optional migration decision.
 */
class RoomAgentPackageStore(
    private val daos: AgentJournalDaos,
    private val transactions: AgentTransactionRunner,
    private val agentRoot: File,
    private val importStagingRoot: File,
    private val clock: AgentClock = AgentClock.system(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val limits: AgentTaskPackageLimits = AgentTaskPackageLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun persistImported(
        importedPackage: AgentImportedTaskPackage
    ): AgentPackageResult<SavedImportedTask> = withContext(ioDispatcher) {
        val installed = ArrayList<File>()
        try {
            requireValidPackage(importedPackage)
            if (daos.task.find(importedPackage.taskId) != null ||
                daos.workspace.find(importedPackage.workspaceId) != null
            ) {
                return@withContext failure(
                    AgentPackageErrorCode.PERSISTENCE_FAILED,
                    "imported task or workspace id already exists"
                )
            }
            val taskRoot = AgentWorkspacePaths.taskRoot(
                agentRoot,
                importedPackage.workspaceId,
                importedPackage.taskId,
                create = true
            )
            val artifactsRoot = File(taskRoot, AgentArtifactLayout.ARTIFACTS)
            if (Files.isSymbolicLink(artifactsRoot.toPath())) {
                throw AgentPackageException(
                    AgentPackageErrorCode.PERSISTENCE_FAILED,
                    "artifact directory is a symbolic link"
                )
            }
            val now = clock.nowMillis()
            val records = ArrayList<AgentArtifactEntity>(importedPackage.artifacts.size)
            val normalizedPaths = HashSet<String>()
            importedPackage.artifacts.forEach { artifact ->
                val safePath = AgentPackagePathGuard.validateArtifactPath(artifact.relativePath, limits)
                    ?: return@withContext failure(
                        AgentPackageErrorCode.UNSAFE_PATH,
                        "imported artifact path is unsafe"
                    )
                if (!normalizedPaths.add(AgentPackagePathGuard.normalizeForComparison(safePath))) {
                    return@withContext failure(
                        AgentPackageErrorCode.DUPLICATE_ENTRY,
                        "imported artifact paths collide after normalization"
                    )
                }
                if (artifact.mediaType.isBlank() || artifact.mediaType.length > limits.maxMediaTypeChars) {
                    return@withContext failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact media type is invalid")
                }
                val staged = artifact.stagedFile
                if (!staged.isFile || Files.isSymbolicLink(staged.toPath())) {
                    return@withContext failure(
                        AgentPackageErrorCode.PERSISTENCE_FAILED,
                        "staged artifact is missing"
                    )
                }
                if (staged.length() != artifact.sizeBytes || AgentFileIo.sha256File(staged) != artifact.sha256) {
                    return@withContext failure(
                        AgentPackageErrorCode.HASH_MISMATCH,
                        "staged artifact no longer matches its verified hash"
                    )
                }
                val artifactId = requireSafe(idGenerator(), "artifactId")
                val commitId = requireSafe(idGenerator(), "commitId")
                val storageRef = AgentArtifactLayout.physicalStorageRef(artifactId, commitId)
                val target = File(taskRoot, storageRef)
                ensureVersionDirectory(target.parentFile)
                val temp = File(target.parentFile, ".import-${commitId}.part")
                AgentFileIo.deleteQuietly(temp)
                val copied = AgentFileIo.copyAndHash(
                    input = staged.inputStream(),
                    target = temp,
                    maxBytes = limits.maxEntryBytes
                )
                if (copied.sizeBytes != artifact.sizeBytes || copied.sha256 != artifact.sha256) {
                    AgentFileIo.deleteQuietly(temp)
                    return@withContext failure(
                        AgentPackageErrorCode.HASH_MISMATCH,
                        "artifact changed while it was copied into the task"
                    )
                }
                AgentFileIo.atomicMove(temp, target)
                AgentFileIo.fsyncDirectory(requireNotNull(target.parentFile) { "artifact target has no parent" })
                installed += target
                AgentFileIo.deleteQuietly(staged)
                records += AgentArtifactEntity(
                    artifactId = artifactId,
                    taskId = importedPackage.taskId,
                    invocationId = null,
                    relativePath = safePath,
                    mediaType = artifact.mediaType,
                    sizeBytes = artifact.sizeBytes,
                    sha256 = artifact.sha256,
                    commitState = AgentArtifactCommitState.COMMITTED.name,
                    storageRef = storageRef,
                    version = 1L,
                    isLatest = 1,
                    latestKey = safePath,
                    commitId = null,
                    createdAt = now
                )
            }
            val task = AgentTask(
                taskId = importedPackage.taskId,
                workspaceId = importedPackage.workspaceId,
                goal = SYNTHETIC_ARCHIVED_GOAL,
                lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED,
                createdAtMillis = importedPackage.createdAtMillis,
                updatedAtMillis = importedPackage.updatedAtMillis,
                displayStatus = importedPackage.displayStatus,
                lastRunId = null,
                revision = 0L
            )
            transactions.inTransaction {
                if (daos.workspace.find(importedPackage.workspaceId) != null) {
                    throw AgentPackageException(
                        AgentPackageErrorCode.PERSISTENCE_FAILED,
                        "imported workspace id already exists"
                    )
                }
                if (daos.task.find(importedPackage.taskId) != null) {
                    throw AgentPackageException(
                        AgentPackageErrorCode.PERSISTENCE_FAILED,
                        "imported task id already exists"
                    )
                }
                daos.workspace.insert(
                    AgentWorkspaceEntity(
                        workspaceId = importedPackage.workspaceId,
                        displayName = importedPackage.displayTitle,
                        storageRef = importedPackage.workspaceId,
                        createdAt = importedPackage.createdAtMillis,
                        updatedAt = importedPackage.updatedAtMillis
                    )
                )
                daos.task.insert(
                    AgentEntityMappers.toEntity(
                        task = task,
                        displayTitle = importedPackage.displayTitle,
                        modelBindingJson = null,
                        policyJson = null,
                        budgetJson = null
                    )
                )
                records.forEach { record -> daos.artifact.insert(record) }
            }
            deleteStagingDirectory(importedPackage.stagingRoot)
            AgentPackageResult.Success(
                SavedImportedTask(
                    taskId = importedPackage.taskId,
                    workspaceId = importedPackage.workspaceId,
                    artifactIds = records.map { it.artifactId }
                )
            )
        } catch (error: AgentPackageException) {
            installed.forEach(AgentFileIo::deleteQuietly)
            deleteStagingDirectory(importedPackage.stagingRoot)
            failure(error.code, error.message ?: "cannot persist the imported task")
        } catch (_: IOException) {
            installed.forEach(AgentFileIo::deleteQuietly)
            deleteStagingDirectory(importedPackage.stagingRoot)
            failure(AgentPackageErrorCode.IO_FAILURE, "cannot persist the imported task")
        } catch (_: Exception) {
            installed.forEach(AgentFileIo::deleteQuietly)
            deleteStagingDirectory(importedPackage.stagingRoot)
            failure(AgentPackageErrorCode.PERSISTENCE_FAILED, "database rejected the imported task")
        }
    }

    private fun requireValidPackage(importedPackage: AgentImportedTaskPackage) {
        requireSafe(importedPackage.taskId, "taskId")
        requireSafe(importedPackage.workspaceId, "workspaceId")
        if (importedPackage.lifecycle != AgentTaskLifecycle.IMPORTED_ARCHIVED) {
            throw AgentPackageException(
                AgentPackageErrorCode.PERSISTENCE_FAILED,
                "only archived imports can be persisted"
            )
        }
        if (importedPackage.displayTitle.isBlank() || importedPackage.displayTitle.codePointCount(0, importedPackage.displayTitle.length) >
            limits.maxTitleChars
        ) {
            throw AgentPackageException(AgentPackageErrorCode.MANIFEST_INVALID, "display title is invalid")
        }
        if (importedPackage.artifacts.size > limits.maxArtifacts) {
            throw AgentPackageException(AgentPackageErrorCode.TOO_MANY_ENTRIES, "too many imported artifacts")
        }
        if (importedPackage.createdAtMillis < 0L || importedPackage.updatedAtMillis < importedPackage.createdAtMillis) {
            throw AgentPackageException(AgentPackageErrorCode.MANIFEST_INVALID, "import timestamps are invalid")
        }
        val staging = importedPackage.stagingRoot.canonicalFile
        val root = importStagingRoot.canonicalFile
        if (!staging.toPath().startsWith(root.toPath()) || staging == root) {
            throw AgentPackageException(
                AgentPackageErrorCode.PERSISTENCE_FAILED,
                "import staging directory is outside the configured root"
            )
        }
    }

    private fun ensureVersionDirectory(directory: File?) {
        requireNotNull(directory) { "artifact version directory is missing" }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create artifact version directory")
        }
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            throw IOException("artifact version directory is not regular")
        }
    }

    private fun deleteStagingDirectory(directory: File) {
        runCatching {
            val root = importStagingRoot.canonicalFile
            val staging = directory.canonicalFile
            if (!staging.toPath().startsWith(root.toPath()) || staging == root) return
            deleteTree(staging)
        }
    }

    private fun deleteTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }

    private fun requireSafe(value: String, field: String): String {
        try {
            return AgentWorkspacePaths.requireSafeId(value, field)
        } catch (error: Exception) {
            throw AgentPackageException(AgentPackageErrorCode.PERSISTENCE_FAILED, "$field is invalid")
        }
    }

    private fun failure(code: AgentPackageErrorCode, message: String): AgentPackageResult.Failure =
        AgentPackageResult.Failure(code, message)

    companion object {
        /** Synthetic non-executable goal: the manifest deliberately contains no user goal text. */
        const val SYNTHETIC_ARCHIVED_GOAL: String = "Imported archived task package"
    }
}
