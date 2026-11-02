package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Small collaborator that owns the Skills surface for [AgentWorkspaceViewModel].
 *
 * It wraps [AgentSkillsCoordinator] with the page's Saveable-flag policy and exposes the exact
 * intents the Route maps. Keeping this out of the view model preserves its file budget while the
 * coordinator remains the single owner of operation ordering and outcomes.
 */
internal class AgentSkillsVmBinding(
    scope: CoroutineScope,
    port: AgentSkillAdminPort,
    documents: AgentDocumentLauncher,
    private val savedStateHandle: SavedStateHandle
) {
    private val coordinator = AgentSkillsCoordinator(scope, port, documents)
    val state: StateFlow<AgentSkillsUiState> = coordinator.state

    init {
        if (savedStateHandle.get<Boolean>(KEY_SKILLS_OPEN) == true) {
            coordinator.open()
        }
    }

    val isVisible: Boolean get() = coordinator.state.value.visible

    fun open() {
        savedStateHandle[KEY_SKILLS_OPEN] = true
        coordinator.open()
    }

    fun close() {
        savedStateHandle[KEY_SKILLS_OPEN] = false
        coordinator.close()
    }

    fun refresh() = coordinator.refresh()

    fun importZip() = coordinator.requestImportZip()

    fun importTree() = coordinator.requestImportTree()

    fun setEnabled(skillId: String, enabled: Boolean) =
        coordinator.requestSetEnabled(skillId, enabled)

    fun requestUninstall(skillId: String) = coordinator.requestUninstall(skillId)

    fun confirmUninstall() = coordinator.confirmUninstall()

    fun cancelUninstall() = coordinator.cancelUninstall()

    fun dismissStatus() = coordinator.dismissStatus()

    companion object {
        private const val KEY_SKILLS_OPEN = "agent.workspace.skillsOpen"
    }
}
