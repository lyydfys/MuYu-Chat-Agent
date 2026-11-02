package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspacePackageCoordinatorTest {

    private class FakePackageActions(
        var exportResult: Result<AgentExportOutcome> = Result.success(AgentExportOutcome.Saved),
        var downloadResult: Result<AgentExportOutcome> = Result.success(AgentExportOutcome.Saved),
        var importResult: Result<AgentImportOutcome> = Result.success(
            AgentImportOutcome(importedTaskId = "imported-1", displayTitle = "导入的摘要")
        ),
        var deleteResult: Result<AgentDeleteOutcome> = Result.success(
            AgentDeleteOutcome(deletedTaskId = "task-1")
        )
    ) : AgentPackageActions {
        var importGate: CompletableDeferred<Result<AgentImportOutcome>>? = null
        val exportCalls = mutableListOf<Pair<String, AgentDocumentRef>>()
        val downloadCalls = mutableListOf<String>()
        val importCalls = mutableListOf<AgentDocumentRef>()
        val deleteCalls = mutableListOf<String>()

        override suspend fun export(
            taskId: String,
            destination: AgentDocumentRef
        ): Result<AgentExportOutcome> {
            exportCalls += taskId to destination
            return exportResult
        }

        override suspend fun exportToDownloads(taskId: String): Result<AgentExportOutcome> {
            downloadCalls += taskId
            return downloadResult
        }

        override suspend fun importPackage(source: AgentDocumentRef): Result<AgentImportOutcome> {
            importCalls += source
            return importGate?.await() ?: importResult
        }

        override suspend fun deleteTask(taskId: String): Result<AgentDeleteOutcome> {
            deleteCalls += taskId
            return deleteResult
        }
    }

    private class FakeDocumentLauncher : AgentDocumentLauncher {
        var lastSuggestedName: String? = null
        var createCount = 0
        var openCount = 0
        private var createCallback: ((AgentDocumentRef?) -> Unit)? = null
        private var openCallback: ((AgentDocumentRef?) -> Unit)? = null

        override fun createDocument(
            suggestedFileName: String,
            onResult: (AgentDocumentRef?) -> Unit
        ) {
            createCount++
            lastSuggestedName = suggestedFileName
            createCallback = onResult
        }

        override fun openDocument(onResult: (AgentDocumentRef?) -> Unit) {
            openCount++
            openCallback = onResult
        }

        fun pickCreate(reference: AgentDocumentRef?) {
            createCallback?.invoke(reference)
        }

        fun pickOpen(reference: AgentDocumentRef?) {
            openCallback?.invoke(reference)
        }
    }

    private class Harness(
        val actions: FakePackageActions = FakePackageActions(),
        val launcher: FakeDocumentLauncher = FakeDocumentLauncher()
    ) {
        val notices = mutableListOf<String>()
        val taskRefreshes = mutableListOf<Unit>()
        val deletedTasks = mutableListOf<String>()
        private val scope = CoroutineScope(Dispatchers.Unconfined)

        val coordinator = AgentWorkspacePackageCoordinator(
            scope = scope,
            actions = actions,
            documents = launcher,
            findTask = { taskId ->
                if (taskId == "task-1") {
                    testTask(taskId = "task-1", goal = "整理 notes.md 并写入 summary.md")
                } else {
                    null
                }
            },
            onNotice = { notices += it },
            onTasksChanged = { taskRefreshes += Unit },
            onTaskDeleted = { deletedTasks += it }
        )
    }

    @Test
    fun exportRoutesThePickedDestinationToTheActionsPort() {
        val harness = Harness()
        harness.coordinator.requestExport("task-1")
        assertEquals("agent-task-1.mca-task.zip", harness.launcher.lastSuggestedName)
        assertTrue(harness.actions.exportCalls.isEmpty())

        val destination = AgentDocumentRef("content://docs/export.zip")
        harness.launcher.pickCreate(destination)

        assertEquals(listOf("task-1" to destination), harness.actions.exportCalls)
        assertEquals(listOf("任务包已导出"), harness.notices)
        assertNull(harness.coordinator.state.value.operation)
        assertFalse(harness.coordinator.state.value.busy)
    }

    @Test
    fun exportToDownloadsSavesWithoutOpeningThePicker() {
        val harness = Harness()
        harness.coordinator.requestExportToDownloads("task-1")

        assertEquals(listOf("task-1"), harness.actions.downloadCalls)
        assertEquals(0, harness.launcher.createCount)
        assertEquals(listOf("任务包已保存到下载目录"), harness.notices)
        assertFalse(harness.coordinator.state.value.busy)
    }

    @Test
    fun canceledPickerReportsCancellationWithoutCallingActions() {
        val harness = Harness()
        harness.coordinator.requestExport("task-1")
        harness.launcher.pickCreate(null)

        assertTrue(harness.actions.exportCalls.isEmpty())
        assertEquals(listOf("已取消导出"), harness.notices)
        assertNull(harness.coordinator.state.value.operation)
    }

    @Test
    fun typedExportFailureSurfacesSafeMessageAndCanBeDismissed() {
        val harness = Harness(
            actions = FakePackageActions(
                exportResult = Result.failure(
                    AgentPackageOperationException("STORAGE_NO_SPACE", "空间不足，无法写入任务包。")
                )
            )
        )
        harness.coordinator.requestExport("task-1")
        harness.launcher.pickCreate(AgentDocumentRef("content://docs/export.zip"))

        val operation = harness.coordinator.state.value.operation
        assertEquals(AgentWorkspaceStatus.PACKAGE_FAILED, operation?.status)
        assertEquals("空间不足，无法写入任务包。", operation?.message)
        assertFalse(harness.coordinator.state.value.busy)

        harness.coordinator.dismissStatus()
        assertNull(harness.coordinator.state.value.operation)
    }

    @Test
    fun importShowsValidatingThenRefreshesAndReportsTitle() {
        val harness = Harness()
        val gate = CompletableDeferred<Result<AgentImportOutcome>>()
        harness.actions.importGate = gate

        harness.coordinator.requestImport()
        assertEquals(1, harness.launcher.openCount)
        val source = AgentDocumentRef("content://docs/import.zip")
        harness.launcher.pickOpen(source)

        assertEquals(listOf(source), harness.actions.importCalls)
        assertEquals(
            AgentWorkspaceStatus.PACKAGE_IMPORTING,
            harness.coordinator.state.value.operation?.status
        )
        assertTrue(harness.coordinator.state.value.busy)

        gate.complete(
            Result.success(AgentImportOutcome(importedTaskId = "imported-1", displayTitle = "导入的摘要"))
        )
        assertNull(harness.coordinator.state.value.operation)
        assertEquals(1, harness.taskRefreshes.size)
        assertTrue(harness.notices.single().contains("导入的摘要"))
    }

    @Test
    fun busyOperationRejectsASecondPackageRequest() {
        val harness = Harness()
        harness.actions.importGate = CompletableDeferred()

        harness.coordinator.requestImport()
        harness.launcher.pickOpen(AgentDocumentRef("content://docs/import.zip"))
        assertTrue(harness.coordinator.state.value.busy)

        harness.coordinator.requestImport()
        harness.coordinator.requestExport("task-1")
        harness.coordinator.requestDelete("task-1")

        assertEquals(1, harness.launcher.openCount)
        assertEquals(0, harness.launcher.createCount)
        assertNull(harness.coordinator.state.value.deleteConfirmation)
    }

    @Test
    fun deleteRequiresConfirmationThenCallsServiceAndRefreshes() {
        val harness = Harness()
        harness.coordinator.requestDelete("task-1")

        val confirmation = harness.coordinator.state.value.deleteConfirmation
        assertEquals("task-1", confirmation?.taskId)
        assertTrue(requireNotNull(confirmation).goal.isNotBlank())
        assertTrue(harness.actions.deleteCalls.isEmpty())

        harness.coordinator.confirmDelete()

        assertEquals(listOf("task-1"), harness.actions.deleteCalls)
        assertEquals(listOf("task-1"), harness.deletedTasks)
        assertEquals(1, harness.taskRefreshes.size)
        assertEquals(listOf("任务已删除"), harness.notices)
        assertNull(harness.coordinator.state.value.deleteConfirmation)
        assertNull(harness.coordinator.state.value.operation)
    }

    @Test
    fun deleteConfirmationCanBeCancelledWithoutCallingService() {
        val harness = Harness()
        harness.coordinator.requestDelete("task-1")
        harness.coordinator.cancelDelete()

        assertNull(harness.coordinator.state.value.deleteConfirmation)
        assertTrue(harness.actions.deleteCalls.isEmpty())
    }

    @Test
    fun deleteFailureSurfacesPackageFailure() {
        val harness = Harness(
            actions = FakePackageActions(
                deleteResult = Result.failure(
                    AgentPackageOperationException("RESOURCE_BUSY", "运行尚未停止，稍后再试。")
                )
            )
        )
        harness.coordinator.requestDelete("task-1")
        harness.coordinator.confirmDelete()

        assertEquals(
            AgentWorkspaceStatus.PACKAGE_FAILED,
            harness.coordinator.state.value.operation?.status
        )
        assertEquals("运行尚未停止，稍后再试。", harness.coordinator.state.value.operation?.message)
        assertTrue(harness.deletedTasks.isEmpty())
    }

    @Test
    fun missingTaskDoesNotLaunchThePickerOrShowConfirmation() {
        val harness = Harness()
        harness.coordinator.requestExport("missing-task")
        harness.coordinator.requestDelete("missing-task")

        assertEquals(0, harness.launcher.createCount)
        assertNull(harness.coordinator.state.value.deleteConfirmation)
        assertEquals(2, harness.notices.size)
        assertTrue(harness.notices.all { it.contains("找不到") })
    }
}
