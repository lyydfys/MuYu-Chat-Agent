package com.muyuchat.feature.agent.workspace

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STATIC source contract for the three Agent settings list surfaces.
 *
 * These guards read production sources as text. They prove no fake enable/schedule control exists
 * and that every drawer entry maps to a real settings intent. They do not prove runtime behaviour,
 * rendering, Compose semantics or port wiring outside this module; those remain NOT_RUN without a
 * build/device execution.
 */
class AgentSettingsSourceContractTest {

    @Test
    fun `tool packages expose read-only preview and keep catalog exposure gated`() {
        val contracts = source(TOOL_PACKAGE_CONTRACTS)
        assertTrue(contracts.contains("APPROVAL_CLOSURE_COMPLETE: Boolean = true"))
        assertTrue(contracts.contains("PACKAGE_CATALOG_EXPOSURE_ENABLED: Boolean = false"))
        assertTrue(contracts.contains("PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY"))
        assertTrue(contracts.contains("suspend fun preview(source: AgentDocumentRef)"))
        // Preview exists, but no enable/import/activation capability can be faked.
        assertFalse(contracts.contains("val enabled"))
        assertFalse(contracts.contains("fun setEnabled"))
        assertFalse(contracts.contains("fun import"))
        assertTrue(contracts.contains("val Unavailable: AgentToolPackageAdminPort"))

        val screen = source(TOOL_PACKAGE_SCREEN)
        assertTrue(screen.contains("message = state.gateMessage"))
        assertTrue(screen.contains("enabled = state.previewEnabled"))
        assertTrue(screen.contains("enabled = state.importEnabled"))
        assertFalse(screen.contains("Switch("))
        assertFalse(screen.contains("onCheckedChange"))
        assertFalse(screen.contains("已启用"))
    }

    @Test
    fun `memory page lists real spaces and selection only`() {
        val screen = source(MEMORY_SCREEN)
        assertTrue(screen.contains("state.spaces"))
        assertTrue(screen.contains("state.totalItemCount"))
        assertTrue(screen.contains("state.selectedSpace"))
        assertTrue(screen.contains("onSelectMemorySpace"))
        assertFalse(screen.contains("Switch("))
        // No chat/knowledge/character memory is read by this page.
        assertFalse(screen.contains("MemoryEntity"))
        assertFalse(screen.contains("KnowledgeBase"))
        assertFalse(screen.contains("agent_memory_items.content"))
    }

    @Test
    fun `workflow page stays manual and schedule is a separate gated entry`() {
        // The manual contract file itself still has no trigger, schedule or next-run concept.
        val contracts = source(WORKFLOW_CONTRACTS)
        assertFalse(contracts.contains("val trigger"))
        assertFalse(contracts.contains("val schedule"))
        assertFalse(contracts.contains("val nextRun"))
        assertFalse(contracts.contains("fun schedule"))
        assertTrue(contracts.contains("suspend fun create(name: String, goalTemplate: String)"))

        // The manual page keeps create/open-run and cannot install or run anything by itself.
        val screen = source(WORKFLOW_SCREEN)
        assertTrue(screen.contains("onOpenWorkflowCreate"))
        assertTrue(screen.contains("onSubmitWorkflowCreate"))
        assertTrue(screen.contains("onOpenWorkflowSchedule"))
        // The schedule surface is typed state supplied by the route, not a page-owned engine.
        assertTrue(screen.contains("schedule: AgentWorkflowScheduleUiState"))
        assertTrue(screen.contains("WorkflowScheduleDialog(state = schedule, actions = actions)"))
        assertFalse(screen.contains("Switch("))
        assertFalse(screen.contains("WorkManager"))
        assertFalse(screen.contains("PeriodicWorkRequest"))
        assertFalse(screen.contains("AlarmManager"))
        assertFalse(screen.contains("setExact"))
        assertFalse(screen.contains("立即运行"))
        // The page never talks to the schedule port or starts a scheduled run directly.
        assertFalse(screen.contains("AgentWorkflowSchedulePort"))
        assertFalse(screen.contains("runManual"))
        assertFalse(screen.contains("nextRun"))

        // The separate schedule contract is narrow: list/save/remove only.
        val schedule = source(WORKFLOW_SCHEDULE_CONTRACTS)
        assertTrue(schedule.contains("interface AgentWorkflowSchedulePort"))
        assertTrue(schedule.contains("suspend fun list(recipeId: String)"))
        assertTrue(schedule.contains("suspend fun save(command: AgentWorkflowScheduleCommandUi)"))
        assertTrue(schedule.contains("suspend fun remove(recipeId: String)"))
        assertTrue(schedule.contains("val Unavailable: AgentWorkflowSchedulePort"))
        // A saved row must report whether the host actually armed the platform wake.
        assertTrue(schedule.contains("val wakeArmed: Boolean"))
        // A plan is pinned to the recipe version it was created for.
        assertTrue(schedule.contains("val definitionVersion: Long"))
        // No page-side run/trigger/next-run shortcut exists in this contract.
        assertFalse(schedule.contains("fun run"))
        assertFalse(schedule.contains("fun trigger"))
        assertFalse(schedule.contains("nextRunAt"))
        assertFalse(schedule.contains("System.currentTimeMillis"))

        // The dialog is the only schedule authoring surface and stays platform-agnostic.
        val dialog = source(WORKFLOW_SCHEDULE_DIALOG)
        assertTrue(dialog.contains("onSubmitWorkflowSchedule"))
        assertTrue(dialog.contains("onDeleteWorkflowSchedule"))
        assertTrue(dialog.contains("可能延迟"))
        assertFalse(dialog.contains("Switch("))
        assertFalse(dialog.contains("WorkManager"))
        assertFalse(dialog.contains("AlarmManager"))
        assertFalse(dialog.contains("setExact"))
        assertFalse(dialog.contains("立即运行"))

        // The coordinator asks the port and never installs a wake or starts a run.
        val coordinator = source(WORKFLOW_SCHEDULE_COORDINATOR)
        assertTrue(coordinator.contains("port.save(scheduleCommandFrom(recipeId, draft))"))
        assertTrue(coordinator.contains("port.remove(recipeId)"))
        assertTrue(coordinator.contains("scheduleDraftValidationError(draft)"))
        assertFalse(coordinator.contains("WorkManager"))
        assertFalse(coordinator.contains("AlarmManager"))
        assertFalse(coordinator.contains("setExact"))
    }

    @Test
    fun `drawer exposes all three settings entries and keeps existing rows`() {
        val drawer = source(DRAWER)
        assertTrue(drawer.contains("\"工具包\""))
        assertTrue(drawer.contains("\"记忆空间\""))
        assertTrue(drawer.contains("\"工作流\""))
        assertTrue(drawer.contains("actions.settings.onOpenToolPackages"))
        assertTrue(drawer.contains("actions.settings.onOpenMemory"))
        assertTrue(drawer.contains("actions.settings.onOpenWorkflows"))
        // Existing entries are preserved.
        assertTrue(drawer.contains("\"Skills\""))
        assertTrue(drawer.contains("\"MCP 服务器\""))
        assertTrue(drawer.contains("\"自动化任务\""))
        assertTrue(drawer.contains("onOpenSkills"))
        assertTrue(drawer.contains("onOpenMcp"))
    }

    @Test
    fun `route wires real settings callbacks and defaults every port to unavailable`() {
        val route = source(ROUTE)
        assertTrue(route.contains("toolPackageAdmin: AgentToolPackageAdminPort"))
        assertTrue(route.contains("memoryAdmin: AgentMemoryAdminPort"))
        assertTrue(route.contains("workflowAdmin: AgentWorkflowAdminPort"))
        assertTrue(route.contains("workflowSchedule: AgentWorkflowSchedulePort"))
        assertTrue(route.contains("AgentToolPackageAdminPort.Unavailable"))
        assertTrue(route.contains("AgentMemoryAdminPort.Unavailable"))
        assertTrue(route.contains("AgentWorkflowAdminPort.Unavailable"))
        assertTrue(route.contains("AgentWorkflowSchedulePort.Unavailable"))
        assertTrue(route.contains("viewModel.settingsBinding::openToolPackages"))
        assertTrue(route.contains("viewModel.settingsBinding::openMemory"))
        assertTrue(route.contains("viewModel.settingsBinding::openWorkflows"))
        assertTrue(route.contains("viewModel.settingsBinding::submitWorkflowCreate"))
        assertTrue(route.contains("viewModel.settingsBinding::openWorkflowSchedule"))
        assertTrue(route.contains("viewModel.settingsBinding::submitWorkflowSchedule"))
        assertTrue(route.contains("viewModel.settingsBinding::deleteWorkflowSchedule"))
        // Back closes the schedule dialog before leaving the page.
        assertTrue(route.contains("viewModel.settingsBinding.closeVisibleSchedule()"))

        val content = source(CONTENT)
        assertTrue(content.contains("AgentWorkspaceSurface.TOOL_PACKAGES"))
        assertTrue(content.contains("AgentWorkspaceSurface.MEMORY"))
        assertTrue(content.contains("AgentWorkspaceSurface.WORKFLOWS"))
        assertTrue(content.contains("schedule = presentation.settings.workflowSchedule"))
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val BASE = "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace"
        const val TOOL_PACKAGE_CONTRACTS = "$BASE/AgentToolPackageContracts.kt"
        const val WORKFLOW_CONTRACTS = "$BASE/AgentWorkflowContracts.kt"
        const val WORKFLOW_SCHEDULE_CONTRACTS = "$BASE/AgentWorkflowScheduleContracts.kt"
        const val WORKFLOW_SCHEDULE_COORDINATOR = "$BASE/AgentWorkflowScheduleCoordinator.kt"
        const val ROUTE = "$BASE/AgentWorkspaceRoute.kt"
        const val DRAWER = "$BASE/ui/AgentHomeDrawer.kt"
        const val CONTENT = "$BASE/ui/AgentWorkspaceContent.kt"
        const val TOOL_PACKAGE_SCREEN = "$BASE/ui/AgentToolPackagesScreen.kt"
        const val MEMORY_SCREEN = "$BASE/ui/AgentMemoryScreen.kt"
        const val WORKFLOW_SCREEN = "$BASE/ui/AgentWorkflowsScreen.kt"
        const val WORKFLOW_SCHEDULE_DIALOG = "$BASE/ui/AgentWorkflowScheduleDialog.kt"
    }
}
