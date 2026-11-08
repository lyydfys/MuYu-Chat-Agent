package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode

/**
 * Picks the agent binding for a turn started from a conversation, using that conversation's model.
 *
 * ## Why reuse [AgentModelChoice] instead of rebuilding a binding from chat state
 *
 * The agent picker's own catalog (`AgentModelCatalogAdapter`) is already the single authority on what a
 * given local engine or cloud record can do: it decides `PROMPTED_V1` vs `NATIVE` from the API format,
 * the tool opt-in and the real-provider evidence, and it computes the provider id, runtime id and asset
 * fingerprint the runtime later verifies. Re-deriving any of that here would create a second set of
 * rules that silently disagrees with the picker — an agent turn that the picker would have offered as
 * prompted could claim native, or a fingerprint mismatch could fail a run late with a confusing error.
 *
 * So the resolution is: read the same catalog, then convert the chosen entry. Two sources feed the
 * choice list — the loaded local engine and configured cloud chat models — which is exactly the
 * "local and cloud both work" requirement.
 */
internal object ChatAgentModelBinding {

    /**
     * The binding for an agent turn, or null when no usable model is available.
     *
     * Selection rule, in order:
     *  1. an explicit cloud selection, when it names a configured chat model — a deliberate choice wins
     *     over a background-loaded local engine;
     *  2. the loaded local engine;
     *  3. otherwise null, so the caller can tell the user to pick a model instead of failing inside a
     *     run with a fabricated binding.
     *
     * An entry that is installed but not loaded is skipped: its fingerprint deliberately does not carry
     * an engine identity, and an agent run needs a模型 that can actually execute.
     */
    fun resolve(
        choices: List<AgentModelChoice>,
        selectedCloudChatModelId: String?
    ): AgentModelBinding? {
        val loaded = choices.filter { it.loadState == AgentModelLoadState.LOADED }
        val cloud = selectedCloudChatModelId
            ?.let { id -> loaded.firstOrNull { it.providerId == CLOUD_PROVIDER_PREFIX + id } }
        val local = loaded.firstOrNull { it.providerId == AGENT_LOCAL_PROVIDER_ID }
        return (cloud ?: local)?.toBinding()
    }

    /**
     * Converts a catalog entry into the frozen binding shape.
     *
     * [AgentProtocolMode.NONE] is rejected: a model the agent cannot speak any protocol to would create
     * a task that can never take a step.
     */
    private fun AgentModelChoice.toBinding(): AgentModelBinding? {
        if (protocolMode == AgentProtocolMode.NONE) return null
        if (modelId.isBlank() || assetFingerprint.isBlank()) return null
        return AgentModelBinding(
            providerId = providerId,
            modelId = modelId,
            runtime = runtime,
            assetFingerprint = assetFingerprint,
            protocolMode = protocolMode,
            modelDisplayName = displayName.ifBlank { modelId }
        )
    }

    /** Matches the picker's prefix so a cloud selection resolves to the same entry the user saw. */
    private const val CLOUD_PROVIDER_PREFIX = "cloud:"
}
