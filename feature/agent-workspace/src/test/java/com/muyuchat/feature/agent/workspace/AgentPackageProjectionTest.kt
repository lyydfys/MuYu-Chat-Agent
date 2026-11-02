package com.muyuchat.feature.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPackageProjectionTest {

    @Test
    fun emptyPackageStateIsIdleAndNotBusy() {
        val state = AgentPackageUiState()
        assertNull(state.operation)
        assertNull(state.deleteConfirmation)
        assertFalse(state.busy)
        assertFalse(state.failed)
    }

    @Test
    fun busyTracksOnlyInFlightPackageStatuses() {
        val inFlight = listOf(
            AgentWorkspaceStatus.PACKAGE_EXPORTING,
            AgentWorkspaceStatus.PACKAGE_IMPORTING,
            AgentWorkspaceStatus.PACKAGE_DELETING
        )
        inFlight.forEach { status ->
            val state = AgentPackageUiState(
                operation = AgentPackageOperationUi(
                    kind = AgentPackageOperationKind.EXPORT,
                    status = status
                )
            )
            assertTrue("$status must be busy", state.busy)
            assertFalse(state.failed)
        }

        val failed = AgentPackageUiState(
            operation = AgentPackageOperationUi(
                kind = AgentPackageOperationKind.IMPORT,
                status = AgentWorkspaceStatus.PACKAGE_FAILED,
                message = "校验失败"
            )
        )
        assertFalse(failed.busy)
        assertTrue(failed.failed)
    }

    @Test
    fun explicitPackageStatusesHaveLabelsAndTones() {
        val required = setOf(
            AgentWorkspaceStatus.PACKAGE_EXPORTING,
            AgentWorkspaceStatus.PACKAGE_IMPORTING,
            AgentWorkspaceStatus.PACKAGE_DELETING,
            AgentWorkspaceStatus.PACKAGE_FAILED
        )
        assertTrue(AgentWorkspaceStatus.entries.containsAll(required))
        required.forEach { status ->
            assertTrue("label for $status", statusLabel(status).isNotBlank())
        }
        assertEquals(AgentStatusTone.ACTIVE, statusTone(AgentWorkspaceStatus.PACKAGE_EXPORTING))
        assertEquals(AgentStatusTone.ACTIVE, statusTone(AgentWorkspaceStatus.PACKAGE_IMPORTING))
        assertEquals(AgentStatusTone.ACTIVE, statusTone(AgentWorkspaceStatus.PACKAGE_DELETING))
        assertEquals(AgentStatusTone.ERROR, statusTone(AgentWorkspaceStatus.PACKAGE_FAILED))
    }

    @Test
    fun projectionCarriesPackageOperationAndDeleteConfirmation() {
        val task = testTask(taskId = "task-1")
        val operation = AgentPackageOperationUi(
            kind = AgentPackageOperationKind.DELETE,
            status = AgentWorkspaceStatus.PACKAGE_DELETING,
            taskId = "task-1"
        )
        val confirmation = AgentDeleteConfirmationUi(
            taskId = "task-1",
            goal = "整理 notes.md 的要点并写入 summary.md"
        )
        val state = projectWorkspaceState(
            projectionInput(
                tasks = listOf(task),
                packageState = AgentPackageUiState(
                    operation = operation,
                    deleteConfirmation = confirmation
                )
            )
        )
        assertEquals(operation, state.packageState.operation)
        assertEquals(confirmation, state.packageState.deleteConfirmation)
        assertTrue(state.packageState.busy)
    }

    @Test
    fun existingProjectionDoesNotRequirePackageState() {
        val state = projectWorkspaceState(projectionInput(tasks = listOf(testTask())))
        assertEquals(AgentPackageUiState(), state.packageState)
        assertFalse(state.packageState.busy)
    }
}
