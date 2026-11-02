package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.SavedStateHandle
import com.muyuchat.core.agent.contract.AgentModelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One collaborator that owns the tool-package, memory and workflow surfaces for the view model.
 *
 * It keeps the view model small, restores the open surface from [SavedStateHandle] and exposes the
 * exact intents the Route maps. Each coordinator remains the single owner of its own operations and
 * outcomes; no port call, list state or selection lives in the view model.
 */
internal class AgentSettingsVmBinding(
    scope: CoroutineScope,
    toolPackages: AgentToolPackageAdminPort,
    documents: AgentDocumentLauncher,
    memory: AgentMemoryAdminPort,
    workflows: AgentWorkflowAdminPort,
    workflowLaunch: AgentWorkflowLaunchPort = AgentWorkflowLaunchPort.Unavailable,
    workflowSchedule: AgentWorkflowSchedulePort = AgentWorkflowSchedulePort.Unavailable,
    modelProvider: () -> AgentModelBinding? = { null },
    private val savedStateHandle: SavedStateHandle
) {
    private val toolPackagesCoordinator = AgentToolPackagesCoordinator(
        scope = scope,
        port = toolPackages,
        documents = documents
    )
    private val memoryCoordinator = AgentMemoryCoordinator(scope, memory)
    private val workflowCoordinator = AgentWorkflowCoordinator(scope, workflows)
    private val workflowLaunchCoordinator = AgentWorkflowLaunchCoordinator(
        scope = scope,
        port = workflowLaunch,
        modelProvider = modelProvider
    )
    private val workflowScheduleCoordinator = AgentWorkflowScheduleCoordinator(
        scope = scope,
        port = workflowSchedule
    )

    init {
        if (savedStateHandle.get<Boolean>(KEY_TOOL_PACKAGES_OPEN) == true) {
            toolPackagesCoordinator.open()
        }
        if (savedStateHandle.get<Boolean>(KEY_MEMORY_OPEN) == true) {
            memoryCoordinator.open()
        }
        if (savedStateHandle.get<Boolean>(KEY_WORKFLOWS_OPEN) == true) {
            workflowCoordinator.open()
        }
    }

    val state: StateFlow<AgentSettingsUiState> = combine(
        toolPackagesCoordinator.state,
        memoryCoordinator.state,
        workflowCoordinator.state,
        workflowLaunchCoordinator.state,
        workflowScheduleCoordinator.state
    ) { toolPackageState, memoryState, workflowState, workflowLaunchState, workflowScheduleState ->
        AgentSettingsUiState(
            toolPackages = toolPackageState,
            memory = memoryState,
            workflows = workflowState,
            workflowLaunch = workflowLaunchState,
            workflowSchedule = workflowScheduleState
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AgentSettingsUiState(
            toolPackages = toolPackagesCoordinator.state.value,
            memory = memoryCoordinator.state.value,
            workflows = workflowCoordinator.state.value,
            workflowLaunch = workflowLaunchCoordinator.state.value,
            workflowSchedule = workflowScheduleCoordinator.state.value
        )
    )

    fun openToolPackages() {
        savedStateHandle[KEY_TOOL_PACKAGES_OPEN] = true
        toolPackagesCoordinator.open()
    }

    fun closeToolPackages() {
        savedStateHandle[KEY_TOOL_PACKAGES_OPEN] = false
        toolPackagesCoordinator.close()
    }

    fun refreshToolPackages() = toolPackagesCoordinator.refresh()

    fun previewToolPackage() = toolPackagesCoordinator.requestPreview()

    fun importToolPackage() = toolPackagesCoordinator.requestImport()

    fun dismissToolPackageStatus() = toolPackagesCoordinator.dismissStatus()

    // R6 executable (format v2) lifecycle intents.
    fun installExecutableToolPackage() = toolPackagesCoordinator.installExecutablePackage()

    fun requestExecutableToolPackageAction(
        packageId: String,
        action: AgentExecutablePackageAction
    ) = toolPackagesCoordinator.requestExecutableAction(packageId, action)

    fun confirmExecutableToolPackageAction() = toolPackagesCoordinator.confirmExecutableAction()

    fun cancelExecutableToolPackageAction() = toolPackagesCoordinator.cancelExecutableAction()

    fun openMemory() {
        savedStateHandle[KEY_MEMORY_OPEN] = true
        memoryCoordinator.open()
    }

    fun closeMemory() {
        savedStateHandle[KEY_MEMORY_OPEN] = false
        memoryCoordinator.close()
    }

    fun refreshMemory() = memoryCoordinator.refresh()

    fun selectMemorySpace(spaceId: String) = memoryCoordinator.selectSpace(spaceId)

    fun dismissMemoryStatus() = memoryCoordinator.dismissStatus()

    fun openWorkflows() {
        savedStateHandle[KEY_WORKFLOWS_OPEN] = true
        workflowCoordinator.open()
    }

    fun closeWorkflows() {
        savedStateHandle[KEY_WORKFLOWS_OPEN] = false
        workflowCoordinator.close()
        workflowLaunchCoordinator.clear()
        workflowScheduleCoordinator.close()
    }

    fun refreshWorkflows() {
        workflowCoordinator.refresh()
        workflowLaunchCoordinator.refreshPreview()
    }

    fun openWorkflowCreate() = workflowCoordinator.openCreateDialog()

    fun updateWorkflowName(value: String) = workflowCoordinator.updateName(value)

    fun updateWorkflowGoal(value: String) = workflowCoordinator.updateGoal(value)

    fun submitWorkflowCreate() = workflowCoordinator.submitCreate()

    fun dismissWorkflowCreate() = workflowCoordinator.dismissCreateDialog()

    fun dismissWorkflowStatus() {
        workflowCoordinator.dismissStatus()
        workflowLaunchCoordinator.dismissStatus()
    }

    fun selectWorkflow(recipeId: String) = workflowLaunchCoordinator.select(recipeId)

    fun updateWorkflowInput(inputId: String, value: String) =
        workflowLaunchCoordinator.updateInput(inputId, value)

    fun createWorkflowTask() = workflowLaunchCoordinator.createTask()

    fun openWorkflowSchedule(recipeId: String, recipeName: String) =
        workflowScheduleCoordinator.open(recipeId, recipeName)

    fun closeWorkflowSchedule() = workflowScheduleCoordinator.close()

    fun retryWorkflowSchedule() = workflowScheduleCoordinator.retry()

    fun updateWorkflowScheduleKind(kind: AgentWorkflowScheduleKind) =
        workflowScheduleCoordinator.updateKind(kind)

    fun updateWorkflowScheduleMinutes(value: String) =
        workflowScheduleCoordinator.updateMinutes(value)

    fun updateWorkflowScheduleTime(value: String) =
        workflowScheduleCoordinator.updateTime(value)

    fun toggleWorkflowScheduleWeekday(day: AgentWorkflowWeekday) =
        workflowScheduleCoordinator.toggleWeekday(day)

    fun updateWorkflowScheduleEnabled(enabled: Boolean) =
        workflowScheduleCoordinator.updateEnabled(enabled)

    fun submitWorkflowSchedule() = workflowScheduleCoordinator.save()

    fun deleteWorkflowSchedule() = workflowScheduleCoordinator.remove()

    /**
     * Closes the schedule dialog when it is the top-most surface. Returns true when the back press
     * was consumed; the dialog is modal, so it is checked before the list surfaces.
     */
    fun closeVisibleSchedule(): Boolean {
        if (!workflowScheduleCoordinator.state.value.visible) return false
        workflowScheduleCoordinator.close()
        return true
    }

    /** Returns true when a settings surface was open and has been closed (back consumed). */
    fun closeVisibleSurface(): Boolean {
        if (toolPackagesCoordinator.state.value.visible) {
            closeToolPackages()
            return true
        }
        if (memoryCoordinator.state.value.visible) {
            closeMemory()
            return true
        }
        if (workflowCoordinator.state.value.visible) {
            closeWorkflows()
            return true
        }
        return false
    }

    companion object {
        private const val KEY_TOOL_PACKAGES_OPEN = "agent.workspace.toolPackagesOpen"
        private const val KEY_MEMORY_OPEN = "agent.workspace.memoryOpen"
        private const val KEY_WORKFLOWS_OPEN = "agent.workspace.workflowsOpen"
    }
}
