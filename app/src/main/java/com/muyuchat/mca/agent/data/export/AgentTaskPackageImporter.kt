package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.mca.agent.data.AgentStorageException
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import com.muyuchat.mca.agent.data.quota.AgentQuotaDecision
import com.muyuchat.mca.agent.data.quota.AgentWorkspaceQuota
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validates a `mca.agent.task-package.v1` ZIP from a caller-provided stream (SAF OpenDocument) and
 * stages verified artifacts under a private import directory.
 *
 * The importer never touches Room, never restores grants/policy/credentials, never creates a run and
 * never auto-starts anything. It returns a typed [AgentImportedTaskPackage] whose caller persists it
 * as lifecycle IMPORTED_ARCHIVED under the generated ids. On any validation failure the staging
 * directory and archive are deleted, so a half package is never reported as imported.
 */
class AgentTaskPackageImporter(
    private val stagingRoot: File,
    private val quota: AgentWorkspaceQuota? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val limits: AgentTaskPackageLimits = AgentTaskPackageLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun import(input: InputStream): AgentPackageResult<AgentImportedTaskPackage> = withContext(ioDispatcher) {
        var archive: File? = null
        var stagingDirectory: File? = null
        try {
            val root = requireStagingRoot()
            val taskId = AgentWorkspacePaths.requireSafeId(idGenerator(), "taskId")
            val workspaceId = AgentWorkspacePaths.requireSafeId(idGenerator(), "workspaceId")
            archive = File(root, "import-${UUID.randomUUID()}.zip.part")
            val copied = copyArchive(input, archive)
            val entries = when (val result = AgentTaskPackageZip.scanArchive(archive, limits)) {
                is AgentPackageResult.Success -> result.value
                is AgentPackageResult.Failure -> return@withContext result
            }
            val manifestBytes = when (val result = AgentTaskPackageZip.readManifest(archive, entries, limits)) {
                is AgentPackageResult.Success -> result.value
                is AgentPackageResult.Failure -> return@withContext result
            }
            val manifest = when (val result = AgentTaskPackageManifestCodec.decode(manifestBytes, limits)) {
                is AgentPackageResult.Success -> result.value
                is AgentPackageResult.Failure -> return@withContext result
            }
            val artifactEntries = entries.filter { it.name != AgentTaskPackageFormat.MANIFEST_ENTRY }
            if (artifactEntries.size != manifest.artifacts.size) {
                return@withContext failure(
                    AgentPackageErrorCode.MANIFEST_PATH_MISMATCH,
                    "manifest artifact count does not match the archive"
                )
            }
            val entriesByName = artifactEntries.associateBy { it.name }
            manifest.artifacts.forEach { artifact ->
                val entry = entriesByName[artifact.relativePath]
                    ?: return@withContext failure(
                        AgentPackageErrorCode.MANIFEST_PATH_MISMATCH,
                        "archive entry is missing for a manifest artifact"
                    )
                if (entry.uncompressedSize != artifact.sizeBytes) {
                    return@withContext failure(
                        AgentPackageErrorCode.SIZE_MISMATCH,
                        "manifest artifact size does not match the archive"
                    )
                }
            }
            val totalExpanded = artifactEntries.sumOf { it.uncompressedSize }
            quota?.let { workspaceQuota ->
                when (val decision = workspaceQuota.evaluate(workspaceId, taskId, totalExpanded)) {
                    AgentQuotaDecision.Allowed -> Unit
                    is AgentQuotaDecision.SoftLimitExceeded -> return@withContext failure(
                        AgentPackageErrorCode.QUOTA_EXCEEDED,
                        "import would exceed the ${decision.scope.name.lowercase()} quota"
                    )
                    is AgentQuotaDecision.DiskSpaceInsufficient -> return@withContext failure(
                        AgentPackageErrorCode.DISK_SPACE_INSUFFICIENT,
                        "not enough free space for the imported package"
                    )
                }
            }
            stagingDirectory = File(root, "import-${UUID.randomUUID()}")
            if (!stagingDirectory.mkdirs()) {
                return@withContext failure(AgentPackageErrorCode.IO_FAILURE, "cannot create import staging directory")
            }
            val artifacts = when (
                val result = AgentTaskPackageZip.extractArtifacts(
                    archive = archive,
                    manifest = manifest,
                    stagingDirectory = stagingDirectory,
                    limits = limits
                )
            ) {
                is AgentPackageResult.Success -> result.value
                is AgentPackageResult.Failure -> return@withContext result
            }
            val packageResult = AgentImportedTaskPackage(
                taskId = taskId,
                workspaceId = workspaceId,
                lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED,
                displayTitle = manifest.task.displayTitle,
                displayStatus = manifest.task.displayStatus,
                createdAtMillis = manifest.task.createdAtMillis,
                updatedAtMillis = manifest.task.updatedAtMillis,
                artifacts = artifacts,
                stagingRoot = stagingDirectory,
                archiveSha256 = copied.sha256,
                archiveBytes = copied.sizeBytes,
                unknownManifestJson = manifest.unknownFieldsJson
            )
            stagingDirectory = null // ownership transfers to the returned package
            AgentPackageResult.Success(packageResult)
        } catch (error: AgentPackageException) {
            failure(error.code, error.message ?: "task package rejected")
        } catch (error: AgentStorageException) {
            failure(AgentPackageErrorCode.INVALID_ARCHIVE, error.message ?: "invalid import arguments")
        } catch (_: IOException) {
            failure(AgentPackageErrorCode.IO_FAILURE, "cannot import the task package")
        } catch (_: IllegalArgumentException) {
            failure(AgentPackageErrorCode.INVALID_ARCHIVE, "invalid import arguments")
        } finally {
            AgentFileIoBridge.deleteIfExists(archive)
            stagingDirectory?.let { deleteRecursively(it) }
        }
    }

    private data class CopiedArchive(val sizeBytes: Long, val sha256: String)

    private fun copyArchive(input: InputStream, archive: File): CopiedArchive {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(archive, false).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limits.maxArchiveBytes) {
                    throw AgentPackageException(
                        AgentPackageErrorCode.PACKAGE_TOO_LARGE,
                        "archive exceeds the size limit"
                    )
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
            output.flush()
            runCatching { output.fd.sync() }
        }
        return CopiedArchive(
            total,
            digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        )
    }

    private fun requireStagingRoot(): File {
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw IOException("cannot create import staging root")
        }
        if (!stagingRoot.isDirectory || java.nio.file.Files.isSymbolicLink(stagingRoot.toPath())) {
            throw IOException("import staging root is not a regular directory")
        }
        return stagingRoot.canonicalFile
    }

    private fun deleteRecursively(file: File) {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            AgentFileIoBridge.deleteIfExists(file)
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        AgentFileIoBridge.deleteIfExists(file)
    }

    private fun failure(code: AgentPackageErrorCode, message: String): AgentPackageResult.Failure =
        AgentPackageResult.Failure(code, message)
}
