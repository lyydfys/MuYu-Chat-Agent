package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Small collaborator that owns the MCP registry surface for [AgentWorkspaceViewModel].
 *
 * It wraps [AgentMcpCoordinator] with the page's Saveable-flag policy and the intents the Route
 * maps, keeping the view model's file budget intact. No network client is reachable from here.
 */
internal class AgentMcpVmBinding(
    scope: CoroutineScope,
    port: AgentMcpAdminPort,
    private val savedStateHandle: SavedStateHandle
) {
    private val coordinator = AgentMcpCoordinator(scope, port)
    val state: StateFlow<AgentMcpUiState> = coordinator.state

    init {
        if (savedStateHandle.get<Boolean>(KEY_MCP_OPEN) == true) {
            coordinator.open()
        }
    }

    val isVisible: Boolean get() = coordinator.state.value.visible

    fun open() {
        savedStateHandle[KEY_MCP_OPEN] = true
        coordinator.open()
    }

    fun close() {
        savedStateHandle[KEY_MCP_OPEN] = false
        coordinator.close()
    }

    fun refresh() = coordinator.refresh()

    fun openAddDialog() = coordinator.openAddDialog()

    fun updateAddDisplayName(value: String) = coordinator.updateAddDisplayName(value)

    fun updateAddEndpoint(value: String) = coordinator.updateAddEndpoint(value)

    fun updateAddAllowLocal(value: Boolean) = coordinator.updateAddAllowLocal(value)

    fun submitAdd() = coordinator.submitAdd()

    fun dismissAddDialog() = coordinator.dismissAddDialog()

    fun setEnabled(serverId: String, enabled: Boolean) =
        coordinator.requestSetEnabled(serverId, enabled)

    fun setAgentNetworkAllowed(serverId: String, allowed: Boolean) =
        coordinator.requestSetAgentNetworkAllowed(serverId, allowed)

    fun requestRemove(serverId: String) = coordinator.requestRemove(serverId)

    fun confirmRemove() = coordinator.confirmRemove()

    fun cancelRemove() = coordinator.cancelRemove()

    fun dismissStatus() = coordinator.dismissStatus()

    companion object {
        private const val KEY_MCP_OPEN = "agent.workspace.mcpOpen"
    }
}
