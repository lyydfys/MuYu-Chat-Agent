package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes only one tombstoned task's private files.
 *
 * The workspace root, sibling task roots and the parent task directory are never deleted. Entries are
 * walked without following symbolic links; a symlink is removed as a link, so an escape target is
 * never touched. Failures are best-effort and reported to the caller for the retry store.
 */
class AgentTaskFileCleaner(
    private val agentRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun deleteTaskFiles(workspaceId: String, taskId: String): Boolean = withContext(ioDispatcher) {
        deleteInternal(workspaceId, taskId)
    }

    private fun deleteInternal(workspaceId: String, taskId: String): Boolean {
        val taskRoot = try {
            AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        } catch (_: Exception) {
            return false
        }
        if (!taskRoot.exists()) return true
        val canonicalRoot = try {
            taskRoot.canonicalFile
        } catch (_: IOException) {
            return false
        }
        val workspacesRoot = File(agentRoot.canonicalFile, "workspaces")
        if (!canonicalRoot.toPath().startsWith(workspacesRoot.canonicalFile.toPath())) return false
        var success = true
        listOf("inputs", "artifacts", "payloads", "staging").forEach { name ->
            val directory = File(canonicalRoot, name)
            if (!deleteTree(directory)) success = false
        }
        // Remove the task directory only when empty; never remove workspace/tasks parents.
        val leftovers = canonicalRoot.listFiles().orEmpty()
        if (leftovers.isEmpty()) {
            if (!canonicalRoot.delete()) success = false
        } else {
            success = false
        }
        return success
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (Files.isSymbolicLink(file.toPath())) {
            return file.delete()
        }
        if (file.isDirectory) {
            var success = true
            file.listFiles()?.forEach { child ->
                if (!deleteTree(child)) success = false
            }
            return if (success) file.delete() else false
        }
        return file.delete()
    }
}
