package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentWriteResult
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Fault-injection seams for the staging → rename → DB commit protocol. */
enum class AgentArtifactCrashPoint {
    BEFORE_STAGING_WRITE,
    AFTER_STAGING_WRITE,
    BEFORE_RENAME,
    AFTER_RENAME,
    BEFORE_DB_COMMIT,
    AFTER_DB_COMMIT
}

fun interface AgentArtifactCrashInjector {
    fun checkpoint(point: AgentArtifactCrashPoint)

    companion object {
        val NONE: AgentArtifactCrashInjector = AgentArtifactCrashInjector { }
    }
}

/** Simulated process death in tests; the store deliberately does not clean up after this. */
class AgentArtifactCrashException(val point: AgentArtifactCrashPoint) :
    IllegalStateException("simulated Agent artifact crash at $point")

enum class AgentArtifactRecoveryStatus {
    COMMITTED,
    RECOVERED,
    PRECONDITION_CHANGED,
    FAILED,
    UNKNOWN,
    NOT_FOUND
}

data class AgentArtifactRecoveryResult(
    val commitId: String,
    val status: AgentArtifactRecoveryStatus,
    val artifactId: String? = null,
    val sha256: String? = null,
    val message: String? = null
)

/**
 * Writes immutable artifact versions through the cross-resource protocol (architecture 10.3):
 *
 * 1. DB: artifact PREPARING + commit PREPARING (`expectedOldHash` frozen);
 * 2. staging file written, flushed, fsynced and hashed;
 * 3. same-volume atomic rename to `artifacts/.versions/<artifactId>_<commitId>.blob`;
 * 4. DB transaction: re-check `expectedOldHash`, commit the artifact version, latest mapping,
 *    invocation SUCCEEDED, event sequence and result ref.
 *
 * The logical latest mapping is separate from the physical `storageRef`; old versions are never
 * deleted or overwritten. [reconcile] settles any phase by hashing the surviving file, never by
 * re-executing the tool.
 */
class AgentArtifactStore(
    private val agentRoot: File,
    private val ledger: AgentArtifactLedger,
    private val clock: AgentClock = AgentClock.system(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val crashInjector: AgentArtifactCrashInjector = AgentArtifactCrashInjector.NONE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun writeArtifact(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: String,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult> =
        writeArtifactBytes(
            workspaceId = workspaceId,
            taskId = taskId,
            invocationId = invocationId,
            logicalPath = logicalPath,
            content = content.toByteArray(Charsets.UTF_8),
            expectedOldHash = expectedOldHash,
            policyVersion = policyVersion
        )

    /** Byte-exact variant for binary artifacts (images, archives); hashes and commits stay identical. */
    suspend fun writeArtifactBytes(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: ByteArray,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult> = withContext(ioDispatcher) {
        var begunCommitId: String? = null
        try {
            val normalizedPath = AgentWorkspacePaths.fileLogicalPath(logicalPath).also { path ->
                if (!path.startsWith("${AgentWorkspacePaths.ARTIFACTS}/")) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.INPUT_INVALID,
                        "artifact writes must target artifacts/"
                    )
                }
                if (AgentArtifactLayout.isInternalArtifactsPath(path)) {
                    throw AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, "internal artifact path")
                }
            }
            if (expectedOldHash != null && !expectedOldHash.matches(Regex("^[0-9a-f]{64}$"))) {
                throw AgentStorageException(
                    AgentDataErrorCodes.INPUT_INVALID,
                    "expectedOldHash must be a lowercase SHA-256 or null"
                )
            }
            if (policyVersion <= 0L) {
                throw AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, "policyVersion must be positive")
            }
            AgentWorkspacePaths.requireSafeId(invocationId, "invocationId")
            val bytes = content
            if (bytes.size > AgentDataLimits.MAX_ARTIFACT_WRITE_BYTES) {
                throw AgentStorageException(
                    AgentDataErrorCodes.INPUT_INVALID,
                    "artifact content exceeds ${AgentDataLimits.MAX_ARTIFACT_WRITE_BYTES} bytes"
                )
            }
            val sha256 = AgentCommandFingerprint.sha256Hex(bytes)
            val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)
            val latest = ledger.latestCommitted(taskId, normalizedPath)
            requireExpectedHash(latest, expectedOldHash)
            ensureQuota(workspaceId, taskId, bytes.size.toLong())
            val artifactId = idGenerator()
            val commitId = idGenerator()
            AgentWorkspacePaths.requireSafeId(artifactId, "artifactId")
            AgentWorkspacePaths.requireSafeId(commitId, "commitId")
            val version = ledger.nextVersion(taskId, normalizedPath)
            val physicalRef = AgentArtifactLayout.physicalStorageRef(artifactId, commitId)
            val now = clock.nowMillis()
            val stored = AgentStoredArtifact(
                artifact = AgentArtifact(
                    artifactId = artifactId,
                    taskId = taskId,
                    invocationId = invocationId,
                    relativePath = normalizedPath,
                    mediaType = inferMediaType(normalizedPath),
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256,
                    commitState = AgentArtifactCommitState.PREPARING,
                    createdAtMillis = now
                ),
                storageRef = physicalRef,
                version = version,
                isLatest = false,
                commitId = commitId
            )
            val commit = AgentArtifactCommit(
                commitId = commitId,
                invocationId = invocationId,
                artifactId = artifactId,
                stagingRef = "staging/$commitId.part",
                targetLogicalPath = normalizedPath,
                expectedOldHash = expectedOldHash,
                newHash = sha256,
                newSizeBytes = bytes.size.toLong(),
                phase = AgentArtifactCommitPhase.PREPARING,
                updatedAtMillis = now
            )
            if (!ledger.begin(commit, stored)) {
                throw AgentStorageException(
                    AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                    "artifact commit id already exists"
                )
            }
            begunCommitId = commitId
            val staging = File(taskRoot, commit.stagingRef)
            val physical = File(taskRoot, physicalRef)
            ensureInside(taskRoot, physical)
            ensureInside(taskRoot, staging)
            crashInjector.checkpoint(AgentArtifactCrashPoint.BEFORE_STAGING_WRITE)
            val physicalMatches = fileMatches(physical, sha256, bytes.size.toLong())
            if (!physicalMatches) {
                val stagingMatches = fileMatches(staging, sha256, bytes.size.toLong())
                if (!stagingMatches) {
                    if (staging.exists()) AgentFileIo.deleteQuietly(staging)
                    val writtenHash = AgentFileIo.writeBytes(staging, bytes)
                    if (writtenHash != sha256) {
                        throw AgentStorageException(
                            AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                            "staged artifact hash mismatch"
                        )
                    }
                }
                crashInjector.checkpoint(AgentArtifactCrashPoint.AFTER_STAGING_WRITE)
                advanceOrVerify(commitId, AgentArtifactCommitPhase.STAGED, now)
                crashInjector.checkpoint(AgentArtifactCrashPoint.BEFORE_RENAME)
                val versions = physical.parentFile
                    ?: throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                        "immutable version directory is missing"
                    )
                if (!versions.isDirectory && !versions.mkdirs()) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_NO_SPACE,
                        "cannot create immutable version directory"
                    )
                }
                if (Files.isSymbolicLink(versions.toPath())) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                        "immutable version directory is a symbolic link"
                    )
                }
                AgentFileIo.atomicMove(staging, physical)
                AgentFileIo.fsyncDirectory(versions)
                crashInjector.checkpoint(AgentArtifactCrashPoint.AFTER_RENAME)
            }
            advanceOrVerify(commitId, AgentArtifactCommitPhase.RENAMED, now)
            crashInjector.checkpoint(AgentArtifactCrashPoint.BEFORE_DB_COMMIT)
            val decision = ledger.commitArtifact(
                commitId = commitId,
                storedArtifact = stored,
                committedAtMillis = now,
                invocationResultRef = resultRefJson(
                    artifactId = artifactId,
                    logicalPath = normalizedPath,
                    sha256 = sha256,
                    sizeBytes = bytes.size.toLong(),
                    version = version
                ),
                event = AgentArtifactCommitEvent(
                    eventId = idGenerator(),
                    type = AgentEventTypes.ARTIFACT_COMMITTED,
                    summary = "artifact committed: $normalizedPath",
                    createdAtMillis = now
                )
            )
            when (decision) {
                is AgentArtifactCommitDecision.Committed -> {
                    crashInjector.checkpoint(AgentArtifactCrashPoint.AFTER_DB_COMMIT)
                    AgentFileIo.deleteQuietly(staging)
                    begunCommitId = null
                    AgentFileResult.Ok(
                        AgentWriteResult(
                            artifactId = artifactId,
                            logicalPath = normalizedPath,
                            sizeBytes = decision.artifact.artifact.sizeBytes,
                            sha256 = decision.artifact.artifact.sha256
                        )
                    )
                }
                is AgentArtifactCommitDecision.PreconditionChanged ->
                    AgentFileResult.Failure(
                        AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED,
                        decision.message,
                        possibleSideEffects = true
                    )
                is AgentArtifactCommitDecision.Conflict ->
                    AgentFileResult.Failure(
                        AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                        decision.message,
                        possibleSideEffects = true
                    )
            }
        } catch (crash: AgentArtifactCrashException) {
            throw crash
        } catch (error: AgentStorageException) {
            begunCommitId?.let { runCatching { ledger.markFailed(it, clock.nowMillis()) } }
            AgentFileResult.Failure(
                error.code,
                error.message ?: "artifact write failed",
                error.possibleSideEffects
            )
        } catch (error: AgentJournalException) {
            begunCommitId?.let { runCatching { ledger.markFailed(it, clock.nowMillis()) } }
            AgentFileResult.Failure(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                error.message ?: "artifact write failed",
                possibleSideEffects = true
            )
        } catch (error: IOException) {
            begunCommitId?.let { runCatching { ledger.markFailed(it, clock.nowMillis()) } }
            AgentFileResult.Failure(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                "artifact file operation failed: ${error.javaClass.simpleName}",
                possibleSideEffects = true
            )
        }
    }

    /** Settles one commit from its journal phase and surviving file hashes. */
    suspend fun reconcile(
        workspaceId: String,
        taskId: String,
        commitId: String
    ): AgentArtifactRecoveryResult = withContext(ioDispatcher) {
        AgentWorkspacePaths.requireSafeId(commitId, "commitId")
        val commit = ledger.findCommit(commitId)
            ?: return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.NOT_FOUND
            )
        val stored = ledger.findArtifact(commit.artifactId)
            ?: return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.UNKNOWN,
                message = "artifact row is missing"
            )
        if (stored.artifact.taskId != taskId) {
            return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.UNKNOWN,
                artifactId = stored.artifact.artifactId,
                message = "commit belongs to another task"
            )
        }
        if (commit.targetLogicalPath != stored.artifact.relativePath) {
            return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.UNKNOWN,
                artifactId = stored.artifact.artifactId,
                message = "commit logical path does not match the artifact"
            )
        }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        val physical = File(taskRoot, stored.storageRef)
        val staging = File(taskRoot, commit.stagingRef)
        val hash = commit.newHash
        val size = commit.newSizeBytes
        when (commit.phase) {
            AgentArtifactCommitPhase.COMMITTED -> return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = if (fileMatches(physical, hash, size)) {
                    AgentArtifactRecoveryStatus.COMMITTED
                } else {
                    AgentArtifactRecoveryStatus.UNKNOWN
                },
                artifactId = commit.artifactId,
                sha256 = hash,
                message = if (fileMatches(physical, hash, size)) null else "committed file is missing or corrupt"
            )
            AgentArtifactCommitPhase.FAILED -> return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.FAILED,
                artifactId = commit.artifactId,
                sha256 = hash
            )
            AgentArtifactCommitPhase.UNKNOWN -> return@withContext AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.UNKNOWN,
                artifactId = commit.artifactId,
                sha256 = hash
            )
            AgentArtifactCommitPhase.PREPARING,
            AgentArtifactCommitPhase.STAGED,
            AgentArtifactCommitPhase.RENAMED -> Unit
        }
        try {
            ensureInside(taskRoot, physical)
            ensureInside(taskRoot, staging)
            val physicalMatches = fileMatches(physical, hash, size)
            val stagingMatches = !physicalMatches && fileMatches(staging, hash, size)
            if (!physicalMatches && !stagingMatches) {
                ledger.markFailed(commitId, clock.nowMillis())
                return@withContext AgentArtifactRecoveryResult(
                    commitId = commitId,
                    status = AgentArtifactRecoveryStatus.FAILED,
                    artifactId = commit.artifactId,
                    sha256 = hash,
                    message = "neither the staged nor the renamed file exists with the recorded hash"
                )
            }
            val now = clock.nowMillis()
            if (stagingMatches) {
                advanceOrVerifyBestEffort(commitId, AgentArtifactCommitPhase.STAGED, now)
                val versions = physical.parentFile
                    ?: throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                        "immutable version directory is missing"
                    )
                if (!versions.isDirectory && !versions.mkdirs()) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_NO_SPACE,
                        "cannot create immutable version directory"
                    )
                }
                AgentFileIo.atomicMove(staging, physical)
                AgentFileIo.fsyncDirectory(versions)
            }
            advanceOrVerifyBestEffort(commitId, AgentArtifactCommitPhase.RENAMED, now)
            val decision = ledger.commitArtifact(
                commitId = commitId,
                storedArtifact = stored,
                committedAtMillis = now,
                invocationResultRef = resultRefJson(
                    artifactId = commit.artifactId,
                    logicalPath = commit.targetLogicalPath,
                    sha256 = hash,
                    sizeBytes = size,
                    version = stored.version
                ),
                event = AgentArtifactCommitEvent(
                    eventId = idGenerator(),
                    type = AgentEventTypes.ARTIFACT_COMMITTED,
                    summary = "artifact recovered: ${commit.targetLogicalPath}",
                    createdAtMillis = now
                )
            )
            when (decision) {
                is AgentArtifactCommitDecision.Committed -> AgentArtifactRecoveryResult(
                    commitId = commitId,
                    status = AgentArtifactRecoveryStatus.RECOVERED,
                    artifactId = decision.artifact.artifact.artifactId,
                    sha256 = decision.artifact.artifact.sha256
                )
                is AgentArtifactCommitDecision.PreconditionChanged -> AgentArtifactRecoveryResult(
                    commitId = commitId,
                    status = AgentArtifactRecoveryStatus.PRECONDITION_CHANGED,
                    artifactId = commit.artifactId,
                    sha256 = hash,
                    message = decision.message
                )
                is AgentArtifactCommitDecision.Conflict -> AgentArtifactRecoveryResult(
                    commitId = commitId,
                    status = AgentArtifactRecoveryStatus.UNKNOWN,
                    artifactId = commit.artifactId,
                    sha256 = hash,
                    message = decision.message
                )
            }
        } catch (error: AgentStorageException) {
            AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.UNKNOWN,
                artifactId = commit.artifactId,
                sha256 = hash,
                message = error.message
            )
        } catch (error: AgentJournalException) {
            AgentArtifactRecoveryResult(
                commitId = commitId,
                status = AgentArtifactRecoveryStatus.UNKNOWN,
                artifactId = commit.artifactId,
                sha256 = hash,
                message = error.message
            )
        }
    }

    /** Bounded reconciliation for one task; never walks or deletes another task root. */
    suspend fun reconcileTask(
        workspaceId: String,
        taskId: String,
        limit: Int = AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT
    ): List<AgentArtifactRecoveryResult> = withContext(ioDispatcher) {
        ledger.unsettledCommits(taskId, limit).map { commit ->
            reconcile(workspaceId, taskId, commit.commitId)
        }
    }

    suspend fun findCommitted(
        workspaceId: String,
        taskId: String,
        artifactId: String
    ): AgentStoredArtifact? = withContext(ioDispatcher) {
        AgentWorkspacePaths.requireSafeId(artifactId, "artifactId")
        val stored = ledger.findArtifact(artifactId) ?: return@withContext null
        if (stored.artifact.taskId != taskId ||
            stored.artifact.commitState != AgentArtifactCommitState.COMMITTED
        ) {
            return@withContext null
        }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        val physical = File(taskRoot, stored.storageRef)
        if (!fileMatches(physical, stored.artifact.sha256, stored.artifact.sizeBytes)) {
            return@withContext null
        }
        stored
    }

    /** Task root resolver for composition/tests; the caller never builds absolute paths itself. */
    fun taskRoot(workspaceId: String, taskId: String): File =
        AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)

    /** Creates the four roots; used before the first staged write in tests/composition. */
    fun ensureTaskRoots(workspaceId: String, taskId: String): File =
        AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)

    private suspend fun advanceOrVerify(
        commitId: String,
        target: AgentArtifactCommitPhase,
        now: Long
    ) {
        val advanced = ledger.advance(commitId, target, now)
        if (advanced) return
        val current = ledger.findCommit(commitId)?.phase
        val acceptable = when (target) {
            AgentArtifactCommitPhase.STAGED ->
                current == AgentArtifactCommitPhase.STAGED || current == AgentArtifactCommitPhase.RENAMED
            AgentArtifactCommitPhase.RENAMED ->
                current == AgentArtifactCommitPhase.RENAMED
            else -> false
        }
        if (!acceptable) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                "artifact commit phase is $current, expected $target"
            )
        }
    }

    private suspend fun advanceOrVerifyBestEffort(
        commitId: String,
        target: AgentArtifactCommitPhase,
        now: Long
    ) {
        val current = ledger.findCommit(commitId)?.phase ?: return
        val advanceFrom = when {
            current == AgentArtifactCommitPhase.PREPARING -> setOf(AgentArtifactCommitPhase.PREPARING)
            current == AgentArtifactCommitPhase.STAGED && target == AgentArtifactCommitPhase.RENAMED ->
                setOf(AgentArtifactCommitPhase.STAGED)
            else -> return
        }
        ledger.advance(commitId, target, now, advanceFrom)
    }

    private suspend fun requireExpectedHash(
        latest: AgentStoredArtifact?,
        expectedOldHash: String?
    ) {
        if (expectedOldHash == null) {
            if (latest != null) {
                throw AgentStorageException(
                    AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED,
                    "logical path already exists; expectedOldHash=null means create-only"
                )
            }
        } else if (latest == null) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED,
                "logical path does not exist; expectedOldHash requires an existing artifact"
            )
        } else if (latest.artifact.sha256 != expectedOldHash) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_PRECONDITION_CHANGED,
                "logical path content changed since the command was frozen"
            )
        }
    }

    private fun ensureQuota(workspaceId: String, taskId: String, newBytes: Long) {
        val taskUsage = AgentStorageUsage.taskBytes(agentRoot, workspaceId, taskId)
        if (taskUsage + newBytes > AgentDataLimits.TASK_SOFT_QUOTA_BYTES) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_NO_SPACE,
                "task workspace exceeds the 256 MiB soft quota"
            )
        }
        val total = AgentStorageUsage.totalBytes(agentRoot)
        if (total + newBytes > AgentDataLimits.TOTAL_SOFT_QUOTA_BYTES) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_NO_SPACE,
                "Agent storage exceeds the 1 GiB soft quota"
            )
        }
        val usable = agentRoot.usableSpace
        if (usable in 1..(newBytes + AgentDataLimits.DISK_FREE_SAFETY_MARGIN_BYTES)) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_NO_SPACE, "device storage is low")
        }
    }

    private fun fileMatches(file: File, sha256: String, sizeBytes: Long): Boolean {
        if (!file.isFile || file.length() != sizeBytes) return false
        return AgentFileIo.sha256File(file) == sha256
    }

    private fun ensureInside(taskRoot: File, file: File) {
        if (!file.canonicalFile.toPath().startsWith(taskRoot.canonicalFile.toPath())) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                "artifact path escapes the task root"
            )
        }
    }

    private fun inferMediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        else -> "text/plain"
    }

    private fun resultRefJson(
        artifactId: String,
        logicalPath: String,
        sha256: String,
        sizeBytes: Long,
        version: Long
    ): String = JSONObject()
        .put("schemaVersion", 1)
        .put("artifactId", artifactId)
        .put("logicalPath", logicalPath)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("version", version)
        .toString()
}
