package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentModelCatalogPort
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.agent.inference.AGENT_LOCAL_PROVIDER_ID
import com.muyuchat.mca.agent.inference.AgentEngineExecutionSnapshot
import com.muyuchat.mca.agent.inference.AgentLocalModelCatalogReads
import com.muyuchat.mca.agent.inference.AgentNativeToolEvidenceStore
import com.muyuchat.mca.agent.inference.AgentLocalModelEntry
import com.muyuchat.mca.agent.inference.McaInferenceServiceCatalogReads
import com.muyuchat.mca.agent.inference.buildAgentLocalModelEntries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Read-only model catalog for the Agent page.
 *
 * Sources (no second catalog, no engine mutation):
 *  * every installed local chat model from `MainUiState.models` (`ModelStoreRepository`), so the
 *    picker is never empty merely because nothing is loaded;
 *  * the engine's genuinely loaded profile (`activeExecutionProfile()` **and** `stats.loaded`), which
 *    is preferred and carries the real identity hash the Agent model adapter verifies;
 *  * optionally configured cloud chat models (kept hidden until the Agent-specific egress client is
 *    wired; see [includeCloudModels]).
 *
 * An installed-but-unloaded local model is visible with the contract [AgentModelLoadState.UNLOADED]
 * state and a stable non-engine fingerprint; it never carries an engine identity and must not be
 * offered as an already-loaded model. The projection layer renders the "未加载" badge and the
 * "去聊天页加载" action, and refuses to start a task until the engine really loads it. Loading stays
 * the Chat page's existing engine load path; this adapter never loads, leases or bypasses the engine.
 *
 * It never mutates chat selection, cloud config or the active backend, and never exposes
 * credentials: [AgentModelChoice.providerId] carries only stable identities and fingerprints.
 */
class AgentModelCatalogAdapter internal constructor(
    private val reads: AgentLocalModelCatalogReads,
    private val state: Flow<MainUiState>,
    private val includeCloudModels: Boolean,
    /**
     * Real-provider round evidence for exactly this provider+model. It gates NATIVE only for a
     * format without controlled verification; a controlled-verified format (Responses) is already
     * covered by [com.muyuchat.mca.agent.inference.AgentNativeToolCapabilities].
     */
    private val nativeToolEvidence: (providerId: String, modelId: String) -> Boolean = { _, _ -> false }
) : AgentModelCatalogPort {

    /**
     * Production wiring. Reads the single engine's committed profile + loaded stats.
     *
     * Cloud choices stay hidden until the Agent-specific egress client (K07 scope/grant transport)
     * is wired. Offering a cloud model whose requests would use the local adapter or the chat
     * client would be a correctness and privacy bug. Local-only is the safe V1 default.
     */
    constructor(
        engine: McaInferenceService,
        state: Flow<MainUiState>,
        includeCloudModels: Boolean = false,
        nativeToolEvidence: AgentNativeToolEvidenceStore? = null
    ) : this(
        reads = McaInferenceServiceCatalogReads(engine),
        state = state,
        includeCloudModels = includeCloudModels,
        nativeToolEvidence = { providerId, modelId ->
            nativeToolEvidence?.isVerified(providerId, modelId) == true
        }
    )

    override fun observeModelChoices(): Flow<List<AgentModelChoice>> =
        combine(state, reads.observeSnapshot()) { current, snapshot ->
            buildChoices(current, snapshot)
        }.distinctUntilChanged()

    private fun buildChoices(
        current: MainUiState,
        snapshot: AgentEngineExecutionSnapshot
    ): List<AgentModelChoice> = buildList {
        buildAgentLocalModelEntries(
            installedModels = current.models,
            snapshot = snapshot,
            loadedDisplayName = current.loadedModelName
        ).forEach { entry -> add(entry.toChoice()) }
        if (!includeCloudModels) return@buildList
        current.cloudModels
            .filter { it.kind == CloudModelKind.CHAT && it.configured }
            .forEach { record ->
                val providerId = CLOUD_PROVIDER_PREFIX + record.id
                val protocol = when {
                    // The prompted chat wire carries the envelope over `chat/completions` without
                    // provider tool declarations, so the picker may offer it as PROMPTED_V1.
                    record.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE ->
                        AgentProtocolMode.PROMPTED_V1
                    record.apiFormat != CloudApiFormat.OPENAI_RESPONSES -> AgentProtocolMode.NONE
                    // NATIVE requires the implemented wire for this format *and* the user's
                    // explicit supportsTools opt-in. A format verified only against a controlled
                    // provider server (see AgentNativeToolCapabilities) needs no per-model record;
                    // a format without that verification still needs real-provider evidence, so a
                    // picker can never advertise a protocol the runtime will not speak.
                    record.supportsTools &&
                        com.muyuchat.mca.agent.inference.AgentNativeToolCapabilities
                            .forFormat(record.apiFormat)
                            .let { it.supported && (it.controlledVerification || nativeToolEvidence(providerId, record.modelName)) } ->
                        AgentProtocolMode.NATIVE
                    else -> AgentProtocolMode.PROMPTED_V1
                }
                add(
                    AgentModelChoice(
                        providerId = providerId,
                        modelId = record.modelName,
                        displayName = record.displayName.takeIf { it.isNotBlank() }
                            ?: record.modelName.takeIf { it.isNotBlank() }
                            ?: record.apiFormat.label,
                        runtime = CLOUD_RUNTIME_PREFIX + record.apiFormat.name,
                        protocolMode = protocol,
                        assetFingerprint = "cloud:${record.id}:${record.updatedAt}",
                        availableOffline = false
                    )
                )
            }
    }

    private fun AgentLocalModelEntry.toChoice(): AgentModelChoice = AgentModelChoice(
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        runtime = runtime,
        protocolMode = protocolMode,
        assetFingerprint = assetFingerprint,
        availableOffline = availableOffline,
        // The only explicit loaded/unloaded signal. A committed profile without engine
        // stats.loaded is already normalized to UNLOADED by the catalog builder.
        loadState = loadState
    )

    companion object {
        const val LOCAL_PROVIDER_ID: String = AGENT_LOCAL_PROVIDER_ID
        const val CLOUD_PROVIDER_PREFIX: String = "cloud:"
        const val CLOUD_RUNTIME_PREFIX: String = "CLOUD_"
    }
}
