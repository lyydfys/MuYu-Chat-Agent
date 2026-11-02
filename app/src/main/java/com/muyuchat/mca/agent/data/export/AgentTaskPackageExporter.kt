package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.runtime.journal.AgentArtifactReader
import com.muyuchat.core.agent.runtime.journal.AgentTaskReader
import com.muyuchat.mca.agent.data.AgentArtifactStore
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a versioned, display-safe task package to a caller-provided OutputStream (SAF
 * CreateDocument opens that stream).
 *
 * Guarantees:
 *  * only COMMITTED artifacts with a verified physical immutable version are included;
 *  * each artifact is streamed into a private staging ZIP while being hashed and compared with the
 *    artifact row, and the completed ZIP is re-read and re-hashed before a single byte reaches the
 *    caller's stream (copy-on-success);
 *  * the manifest contains display metadata and artifact paths/hashes/sizes only — no goal text,
 *    grants, policy, credentials, events or opaque provider state;
 *  * deterministic entry ordering (manifest first, artifacts sorted by path, fixed ZIP timestamp).
 */
class AgentTaskPackageExporter(
    private val taskReader: AgentTaskReader,
    private val artifactReader: AgentArtifactReader,
    private val artifactStore: AgentArtifactStore,
    private val stagingRoot: File,
    private val displayTitleReader: AgentTaskDisplayTitleReader? = null,
    private val clock: AgentClock = AgentClock.system(),
    private val limits: AgentTaskPackageLimits = AgentTaskPackageLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun export(
        taskId: String,
        output: OutputStream
    ): AgentPackageResult<AgentTaskExportSummary> = withContext(ioDispatcher) {
        var staged: File? = null
        try {
            AgentWorkspacePaths.requireSafeId(taskId, "taskId")
            val task = taskReader.readTask(taskId)
                ?: return@withContext failure(AgentPackageErrorCode.NOT_FOUND, "task was not found")
            if (task.lifecycle == AgentTaskLifecycle.TOMBSTONED) {
                return@withContext failure(AgentPackageErrorCode.TASK_TOMBSTONED, "task was deleted")
            }
            val selected = when (val result = selectArtifacts(task)) {
                is AgentPackageResult.Success -> result.value
                is AgentPackageResult.Failure -> return@withContext result
            }
            val title = displayTitleReader?.displayTitle(taskId)
                ?.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= limits.maxTitleChars }
                ?: "Agent task"
            val manifest = AgentTaskPackageManifest(
                exportedAtMillis = clock.nowMillis(),
                task = task.toDisplaySummary(title),
                artifacts = selected.map { it.entry }.sortedBy { it.relativePath }
            )
            val manifestBytes = try {
                AgentTaskPackageManifestCodec.encode(manifest, limits)
            } catch (error: IllegalArgumentException) {
                return@withContext failure(AgentPackageErrorCode.MANIFEST_INVALID, error.message ?: "manifest invalid")
            }
            val stageDirectory = requireStagingRoot()
            val stageFile = File(stageDirectory, "export-${UUID.randomUUID()}.zip.part")
            staged = stageFile
            AgentFileIoBridge.deleteIfExists(stageFile)
            val write = AgentTaskPackageZip.writeArchive(
                target = stageFile,
                manifestBytes = manifestBytes,
                artifacts = selected.map { AgentPackageZipSource(it.entry, it.physicalFile) },
                limits = limits
            )
            if (write is AgentPackageResult.Failure) {
                return@withContext write
            }
            val verified = AgentTaskPackageZip.verifyStaged(stageFile, manifest, limits)
            if (verified is AgentPackageResult.Failure) {
                return@withContext verified
            }
            val packageBytes = stageFile.length()
            val packageSha = AgentFileIoBridge.sha256(stageFile)
            copyStagedToCaller(stageFile, output)
            AgentPackageResult.Success(
                AgentTaskExportSummary(
                    taskId = taskId,
                    artifactCount = selected.size,
                    packageBytes = packageBytes,
                    packageSha256 = packageSha,
                    exportedAtMillis = manifest.exportedAtMillis
                )
            )
        } catch (_: IOException) {
            failure(AgentPackageErrorCode.IO_FAILURE, "cannot write the task package")
        } catch (_: IllegalArgumentException) {
            failure(AgentPackageErrorCode.IO_FAILURE, "invalid export arguments")
        } finally {
            AgentFileIoBridge.deleteIfExists(staged)
        }
    }

    private data class SelectedArtifact(
        val entry: AgentPackageArtifactEntry,
        val physicalFile: File
    )

    private suspend fun selectArtifacts(task: AgentTask): AgentPackageResult<List<SelectedArtifact>> {
        val taskRoot = artifactStore.taskRoot(task.workspaceId, task.taskId)
        val committed = artifactReader.readArtifacts(task.taskId)
            .filter { it.commitState == AgentArtifactCommitState.COMMITTED }
            .sortedBy { it.relativePath }
        val paths = linkedSetOf<String>()
        val normalizedPaths = HashSet<String>()
        val candidates = linkedMapOf<String, MutableList<com.muyuchat.mca.agent.data.AgentStoredArtifact>>()
        for (artifact in committed) {
            val safePath = AgentPackagePathGuard.validateArtifactPath(artifact.relativePath, limits)
                ?: return failure(AgentPackageErrorCode.UNSAFE_PATH, "artifact path is unsafe")
            if (!normalizedPaths.add(AgentPackagePathGuard.normalizeForComparison(safePath))) {
                return failure(
                    AgentPackageErrorCode.DUPLICATE_ENTRY,
                    "artifact paths collide after normalization"
                )
            }
            if (artifact.mediaType.isBlank() || artifact.mediaType.length > limits.maxMediaTypeChars) {
                return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact media type is invalid")
            }
            paths += safePath
            val stored = artifactStore.findCommitted(task.workspaceId, task.taskId, artifact.artifactId)
                ?: continue
            if (stored.artifact.commitState != AgentArtifactCommitState.COMMITTED) continue
            candidates.getOrPut(safePath) { ArrayList() } += stored
        }
        val selected = ArrayList<SelectedArtifact>(paths.size)
        for (path in paths.sorted()) {
            val latest = candidates[path]?.firstOrNull { it.isLatest }
                ?: return failure(
                    AgentPackageErrorCode.ARTIFACT_FILE_MISSING,
                    "committed latest artifact is missing or corrupt"
                )
            val physical = File(taskRoot, latest.storageRef)
            val canonicalRoot = taskRoot.canonicalFile
            if (java.nio.file.Files.isSymbolicLink(physical.toPath()) ||
                !physical.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) ||
                !physical.isFile
            ) {
                return failure(AgentPackageErrorCode.ARTIFACT_FILE_MISSING, "artifact file is missing")
            }
            if (physical.length() != latest.artifact.sizeBytes) {
                return failure(AgentPackageErrorCode.SIZE_MISMATCH, "artifact file size changed")
            }
            selected += SelectedArtifact(
                entry = AgentPackageArtifactEntry(
                    relativePath = path,
                    mediaType = latest.artifact.mediaType,
                    sizeBytes = latest.artifact.sizeBytes,
                    sha256 = latest.artifact.sha256
                ),
                physicalFile = physical
            )
        }
        return AgentPackageResult.Success(selected)
    }

    private fun requireStagingRoot(): File {
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw IOException("cannot create export staging directory")
        }
        if (!stagingRoot.isDirectory || java.nio.file.Files.isSymbolicLink(stagingRoot.toPath())) {
            throw IOException("export staging root is not a regular directory")
        }
        return stagingRoot.canonicalFile
    }

    private fun copyStagedToCaller(staged: File, output: OutputStream) {
        val buffered = BufferedOutputStream(output, 64 * 1024)
        FileInputStream(staged).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                buffered.write(buffer, 0, read)
            }
        }
        buffered.flush()
    }

    private fun failure(code: AgentPackageErrorCode, message: String): AgentPackageResult.Failure =
        AgentPackageResult.Failure(code, message)
}

/** Small bridge so the export package does not depend on the internal app file-I/O helpers' shape. */
internal object AgentFileIoBridge {
    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun deleteIfExists(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
    }
}
