package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-level coordinator for task-package export/import/delete.
 *
 * It owns the SAF hand-off and the one-at-a-time operation state, but performs no data work: the
 * [AgentPackageActions] port owns package bytes, validation, stop-then-delete and cleanup. Keeping
 * this out of [AgentWorkspaceViewModel] preserves the page state holder's file budget and keeps
 * the composables free of business logic.
 */
internal class AgentWorkspacePackageCoordinator(
    private val scope: CoroutineScope,
    private val actions: AgentPackageActions,
    private val documents: AgentDocumentLauncher,
    private val findTask: (String) -> AgentTask?,
    private val onNotice: (String) -> Unit,
    private val onTasksChanged: () -> Unit,
    private val onTaskDeleted: (String) -> Unit
) {
    private val mutableState = MutableStateFlow(AgentPackageUiState())
    val state: StateFlow<AgentPackageUiState> = mutableState.asStateFlow()

    fun requestExport(taskId: String) {
        if (mutableState.value.busy) return
        if (findTask(taskId) == null) {
            onNotice("找不到要导出的任务。")
            return
        }
        documents.createDocument(exportPackageFileName(taskId)) { target ->
            if (mutableState.value.busy) return@createDocument
            if (target == null) {
                onNotice("已取消导出")
                return@createDocument
            }
            start(AgentPackageOperationKind.EXPORT, taskId) { exportTo(taskId, target) }
        }
    }

    fun requestExportToDownloads(taskId: String) {
        if (mutableState.value.busy) return
        if (findTask(taskId) == null) {
            onNotice("找不到要导出的任务。")
            return
        }
        start(AgentPackageOperationKind.EXPORT, taskId) { exportToDownloads(taskId) }
    }

    fun requestImport() {
        if (mutableState.value.busy) return
        documents.openDocument { source ->
            if (mutableState.value.busy) return@openDocument
            if (source == null) {
                onNotice("已取消导入")
                return@openDocument
            }
            start(AgentPackageOperationKind.IMPORT, taskId = null) { importFrom(source) }
        }
    }

    fun requestDelete(taskId: String) {
        if (mutableState.value.busy) return
        val task = findTask(taskId)
        if (task == null) {
            onNotice("找不到要删除的任务。")
            return
        }
        mutableState.update {
            it.copy(deleteConfirmation = AgentDeleteConfirmationUi(task.taskId, task.goal))
        }
    }

    fun confirmDelete() {
        val confirmation = mutableState.value.deleteConfirmation ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(deleteConfirmation = null) }
        start(AgentPackageOperationKind.DELETE, confirmation.taskId) {
            deleteConfirmed(confirmation.taskId)
        }
    }

    fun cancelDelete() {
        mutableState.update { it.copy(deleteConfirmation = null) }
    }

    fun dismissStatus() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(operation = null) }
    }

    // Suspend entry points kept internal so JVM tests can drive every outcome deterministically.
    internal suspend fun exportTo(taskId: String, destination: AgentDocumentRef) {
        actions.export(taskId, destination).fold(
            onSuccess = { outcome ->
                clearOperation()
                onNotice(
                    when (outcome) {
                        AgentExportOutcome.Saved -> "任务包已导出"
                        AgentExportOutcome.Canceled -> "已取消导出"
                    }
                )
            },
            onFailure = { error ->
                fail(AgentPackageOperationKind.EXPORT, taskId, error)
            }
        )
    }

    internal suspend fun exportToDownloads(taskId: String) {
        actions.exportToDownloads(taskId).fold(
            onSuccess = {
                clearOperation()
                onNotice("任务包已保存到下载目录")
            },
            onFailure = { error -> fail(AgentPackageOperationKind.EXPORT, taskId, error) }
        )
    }

    internal suspend fun importFrom(source: AgentDocumentRef) {
        actions.importPackage(source).fold(
            onSuccess = { outcome ->
                clearOperation()
                onTasksChanged()
                val title = outcome.displayTitle?.takeIf { it.isNotBlank() }
                onNotice(
                    if (title == null) {
                        "任务包已导入（已归档）"
                    } else {
                        "任务包已导入：$title（已归档）"
                    }
                )
            },
            onFailure = { error ->
                fail(AgentPackageOperationKind.IMPORT, taskId = null, error)
            }
        )
    }

    internal suspend fun deleteConfirmed(taskId: String) {
        actions.deleteTask(taskId).fold(
            onSuccess = {
                clearOperation()
                onTaskDeleted(it.deletedTaskId)
                onTasksChanged()
                onNotice("任务已删除")
            },
            onFailure = { error ->
                fail(AgentPackageOperationKind.DELETE, taskId, error)
            }
        )
    }

    private fun start(
        kind: AgentPackageOperationKind,
        taskId: String?,
        block: suspend () -> Unit
    ) {
        val inFlightStatus = when (kind) {
            AgentPackageOperationKind.EXPORT -> AgentWorkspaceStatus.PACKAGE_EXPORTING
            AgentPackageOperationKind.IMPORT -> AgentWorkspaceStatus.PACKAGE_IMPORTING
            AgentPackageOperationKind.DELETE -> AgentWorkspaceStatus.PACKAGE_DELETING
        }
        mutableState.update {
            it.copy(
                operation = AgentPackageOperationUi(kind, inFlightStatus, taskId),
                deleteConfirmation = null
            )
        }
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                fail(kind, taskId, error)
            }
        }
    }

    private fun fail(kind: AgentPackageOperationKind, taskId: String?, error: Throwable) {
        val safe = agentPackageErrorUi(error)
        mutableState.update {
            it.copy(
                operation = AgentPackageOperationUi(
                    kind = kind,
                    status = AgentWorkspaceStatus.PACKAGE_FAILED,
                    taskId = taskId,
                    message = safe.message
                ),
                deleteConfirmation = null
            )
        }
    }

    private fun clearOperation() {
        mutableState.update { it.copy(operation = null) }
    }
}
