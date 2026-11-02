package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.core.agent.contract.AgentModelCatalogPort
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries

/**
 * Composition helper for [AgentWorkspaceViewModel].
 *
 * Kept outside the view model so the state holder stays within the file review budget and the
 * factory's three new settings ports are visible in one construction site. Every admin port
 * defaults to its explicit `Unavailable` fallback; no `Any?` lookup or real-instance cast exists.
 */
object AgentWorkspaceViewModelFactory {

    fun create(
        queries: AgentWorkspaceQueries,
        commands: AgentWorkspaceCommands,
        modelCatalog: AgentModelCatalogPort
    ): ViewModelProvider.Factory = create(
        queries = queries,
        commands = commands,
        modelCatalog = modelCatalog,
        packageActions = AgentPackageActions.Unavailable,
        documentLauncher = AgentDocumentLauncher.Unavailable,
        skillAdmin = AgentSkillAdminPort.Unavailable,
        mcpAdmin = AgentMcpAdminPort.Unavailable,
        toolPackageAdmin = AgentToolPackageAdminPort.Unavailable,
        memoryAdmin = AgentMemoryAdminPort.Unavailable,
        workflowAdmin = AgentWorkflowAdminPort.Unavailable,
        workflowLaunch = AgentWorkflowLaunchPort.Unavailable,
        workflowSchedule = AgentWorkflowSchedulePort.Unavailable
    )

    fun create(
        queries: AgentWorkspaceQueries,
        commands: AgentWorkspaceCommands,
        modelCatalog: AgentModelCatalogPort,
        packageActions: AgentPackageActions,
        documentLauncher: AgentDocumentLauncher,
        skillAdmin: AgentSkillAdminPort = AgentSkillAdminPort.Unavailable,
        mcpAdmin: AgentMcpAdminPort = AgentMcpAdminPort.Unavailable,
        toolPackageAdmin: AgentToolPackageAdminPort = AgentToolPackageAdminPort.Unavailable,
        memoryAdmin: AgentMemoryAdminPort = AgentMemoryAdminPort.Unavailable,
        workflowAdmin: AgentWorkflowAdminPort = AgentWorkflowAdminPort.Unavailable,
        workflowLaunch: AgentWorkflowLaunchPort = AgentWorkflowLaunchPort.Unavailable,
        workflowSchedule: AgentWorkflowSchedulePort = AgentWorkflowSchedulePort.Unavailable
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AgentWorkspaceViewModel(
                queries = queries,
                commands = commands,
                modelCatalog = modelCatalog,
                savedStateHandle = createSavedStateHandle(),
                packageActions = packageActions,
                documentLauncher = documentLauncher,
                skillAdmin = skillAdmin,
                mcpAdmin = mcpAdmin,
                toolPackageAdmin = toolPackageAdmin,
                memoryAdmin = memoryAdmin,
                workflowAdmin = workflowAdmin,
                workflowLaunch = workflowLaunch,
                workflowSchedule = workflowSchedule
            )
        }
    }
}
