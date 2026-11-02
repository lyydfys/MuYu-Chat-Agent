package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentClock
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AgentWorkspaceGcResult(
    val scannedTasks: Int,
    val deletedStagingFiles: Int,
    val deletedBytes: Long,
    val failedDeletions: Int,
    val bounded: Boolean
)

/**
 * Conservative staging GC (architecture 10.5).
 *
 * Only files under a task's `staging/` directory older than [maxAgeMillis] are removed. Inputs,
 * artifacts, payloads, manifest/retry records and task/workspace directories are never touched, so
 * committed data and audit state survive. Each task and the total scanned count are bounded.
 */
class AgentWorkspaceGarbageCollector(
    private val agentRoot: File,
    private val clock: AgentClock = AgentClock.system(),
    private val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000,
    private val maxTasks: Int = 200,
    private val maxFilesPerTask: Int = 500,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun collectOrphanStaging(): AgentWorkspaceGcResult = withContext(ioDispatcher) {
        val cutoff = clock.nowMillis() - maxAgeMillis
        var scannedTasks = 0
        var deletedFiles = 0
        var deletedBytes = 0L
        var failed = 0
        var bounded = false
        val workspaces = File(agentRoot.canonicalFile, "workspaces")
        val workspaceDirectories = workspaces.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (workspace in workspaceDirectories.sortedBy { it.name }) {
            val tasks = File(workspace, "tasks").listFiles()?.filter { it.isDirectory }.orEmpty()
            for (task in tasks.sortedBy { it.name }) {
                if (scannedTasks >= maxTasks) {
                    bounded = true
                    break
                }
                scannedTasks += 1
                val staging = File(task, "staging")
                if (!staging.isDirectory || Files.isSymbolicLink(staging.toPath())) continue
                val files = staging.listFiles()?.filter { it.isFile }.orEmpty()
                    .sortedBy { it.name }
                    .take(maxFilesPerTask)
                if ((staging.listFiles()?.size ?: 0) > files.size) bounded = true
                for (file in files) {
                    val modified = lastModifiedNoFollow(file) ?: continue
                    if (modified > cutoff) continue
                    val length = runCatching { file.length() }.getOrDefault(0L)
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        deletedFiles += 1
                        deletedBytes += length
                    } else {
                        failed += 1
                    }
                }
            }
            if (scannedTasks >= maxTasks) {
                bounded = true
                break
            }
        }
        AgentWorkspaceGcResult(
            scannedTasks = scannedTasks,
            deletedStagingFiles = deletedFiles,
            deletedBytes = deletedBytes,
            failedDeletions = failed,
            bounded = bounded
        )
    }

    private fun lastModifiedNoFollow(file: File): Long? = try {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        ).lastModifiedTime().toMillis()
    } catch (_: Exception) {
        null
    }
}
