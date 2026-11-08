package com.muyuchat.mca

import com.muyuchat.feature.settings.AgentApprovalPresetUi
import com.muyuchat.mca.agent.security.AgentToolApprovalPolicyStore

/**
 * Settings-page intent for the Agent approval tier.
 *
 * Kept as a delegate file because `MainViewModel.kt` is at its 1000-line ceiling. The write is
 * synchronous and immediately visible to the next tool decision: the store caches the encoded
 * document it just wrote, and the permission hook reads [AgentToolApprovalPolicyStore.current] on
 * every call, so no state broadcast is needed for the change to take effect.
 */
internal fun MainViewModel.setAgentApprovalPreset(preset: AgentApprovalPresetUi) {
    val store = AgentToolApprovalPolicyStore(getApplication())
    if (AgentApprovalPolicyPresets.selectedFor(store) == preset &&
        AgentApprovalPolicyPresets.isPreset(store.current())
    ) {
        // Already exactly this preset: skip the write so an accidental re-tap cannot rewrite a
        // document the user hand-edited into a preset shape in the meantime.
        return
    }
    val applied = AgentApprovalPolicyPresets.apply(store, preset)
    showStatus("Agent 工具审批已切换为「${preset.title}」 · ${AgentApprovalPolicyPresets.modeLabel(applied.defaultMode)}")
}

/** Current tier for the settings card; read on each composition of the experiments page. */
internal fun MainViewModel.agentApprovalPreset(): AgentApprovalPresetUi =
    AgentApprovalPolicyPresets.selectedFor(AgentToolApprovalPolicyStore(getApplication()))
