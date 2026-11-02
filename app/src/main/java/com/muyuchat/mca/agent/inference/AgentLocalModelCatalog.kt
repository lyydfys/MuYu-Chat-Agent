package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.toLocalChatRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Read-only projection of the installed local MCA model catalog plus the engine's real load state.
 *
 * Two independent production sources are combined here:
 *  * installed chat models: `MainUiState.models` (published by the app from `ModelStoreRepository`);
 *  * actually loaded model: `McaInferenceService.activeExecutionProfile()` **and**
 *    `McaInferenceService.stats.value.loaded`.
 *
 * The committed execution profile is deliberately not treated as proof of a loaded model:
 * `ParameterCoordinator.markUnloaded()` keeps the last committed profile, so it survives explicit
 * unloads, failed reloads and worker loss. Only [AgentEngineExecutionSnapshot.loaded] (engine
 * `stats.loaded`) may mark a model as loaded; after a process restart the profile is null and every
 * installed model is reported as [AgentModelLoadState.UNLOADED].
 *
 * The loaded entry always carries the engine identity hash the local Agent model adapter verifies
 * in `verifyBinding`. An unloaded entry never carries an engine identity: it exposes a stable
 * `installed:` catalog fingerprint instead, so nothing can present it as an already-loaded binding.
 * The contract [AgentModelLoadState] is the single explicit loaded/unloaded signal; the projection
 * layer (picker badge, "去聊天页加载" action, run gating) consumes it directly.
 *
 * Local MCA text models speak [AgentProtocolMode.PROMPTED_V1] (architecture 6.1); this projection
 * never reports a local model as `NONE` or `NATIVE`.
 */
internal data class AgentLocalModelEntry(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val runtime: String,
    val protocolMode: AgentProtocolMode,
    val assetFingerprint: String,
    val availableOffline: Boolean,
    val loadState: AgentModelLoadState,
    val installed: Boolean
) {
    val isLoaded: Boolean get() = loadState == AgentModelLoadState.LOADED
}

/**
 * One coherent read of the engine: the committed profile and the real native-loaded flag.
 * [loaded] must come from `McaInferenceService.stats`, never from profile presence.
 */
internal data class AgentEngineExecutionSnapshot(
    val activeProfile: ModelExecutionProfile?,
    val loaded: Boolean
)

/** Narrow read surface so the catalog and its adapter stay JVM-testable without an engine. */
internal interface AgentLocalModelCatalogReads {
    fun observeSnapshot(): Flow<AgentEngineExecutionSnapshot>
}

/**
 * Production reads over the single engine. [McaInferenceService.stats] is the engine-owned loaded
 * state flow; the committed profile is read at each emission so the two values stay coherent.
 */
internal class McaInferenceServiceCatalogReads(
    private val engine: McaInferenceService
) : AgentLocalModelCatalogReads {
    override fun observeSnapshot(): Flow<AgentEngineExecutionSnapshot> =
        engine.stats
            .map { stats ->
                AgentEngineExecutionSnapshot(
                    activeProfile = engine.activeExecutionProfile(),
                    loaded = stats.loaded
                )
            }
            .distinctUntilChanged()
}

internal const val AGENT_LOCAL_PROVIDER_ID: String = "local"
internal const val AGENT_INSTALLED_FINGERPRINT_PREFIX: String = "installed:"

/**
 * Builds the local catalog: the genuinely loaded model first, then every other installed model as
 * visible-but-unloaded. Never claims a loaded state when the engine has no active profile.
 */
internal fun buildAgentLocalModelEntries(
    installedModels: List<ModelManifest>,
    snapshot: AgentEngineExecutionSnapshot,
    loadedDisplayName: String? = null
): List<AgentLocalModelEntry> {
    val loadedProfile = snapshot.loadedProfileOrNull()
    val loadedEntry = loadedProfile?.let { profile ->
        val identity = profile.runtimeIdentity
        val modelId = profile.modelId.trim()
        if (modelId.isEmpty() || identity.identityHash.isBlank()) return@let null
        val installed = installedModels.firstOrNull { manifest -> manifest.matchesLoadedModel(profile) }
        AgentLocalModelEntry(
            providerId = AGENT_LOCAL_PROVIDER_ID,
            modelId = modelId,
            displayName = installed?.displayName.normalizedOrNull()
                ?: loadedDisplayName.normalizedOrNull()
                ?: modelId,
            runtime = identity.runtime.backendId,
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            assetFingerprint = identity.identityHash,
            availableOffline = true,
            loadState = AgentModelLoadState.LOADED,
            installed = installed != null
        )
    }
    val unloadedEntries = installedModels.asSequence()
        .filter { manifest ->
            manifest.id.isNotBlank() &&
                (loadedProfile == null || !manifest.matchesLoadedModel(loadedProfile))
        }
        .distinctBy { manifest -> manifest.id }
        .map { manifest ->
            AgentLocalModelEntry(
                providerId = AGENT_LOCAL_PROVIDER_ID,
                modelId = manifest.id,
                displayName = manifest.displayName.normalizedOrNull()
                    ?: manifest.fileName.normalizedOrNull()
                    ?: manifest.id,
                runtime = manifest.runtime.toLocalChatRuntime().backendId,
                protocolMode = AgentProtocolMode.PROMPTED_V1,
                // Deliberately not the engine identity: the entry is not a loaded binding, and the
                // fingerprint must stay stable across restarts before the model is loaded. It uses
                // only persisted manifest fields so the UI projection never touches the filesystem.
                assetFingerprint = manifest.installedFingerprint(),
                // A local model never needs the network; "offline capability" is not "loaded now".
                // The explicit loadState is what stops the picker from offering it as runnable.
                availableOffline = true,
                loadState = AgentModelLoadState.UNLOADED,
                installed = true
            )
        }
        .toList()
    return listOfNotNull(loadedEntry) + unloadedEntries
}

/** The loaded profile only when the engine itself reports a native-loaded model. */
internal fun AgentEngineExecutionSnapshot.loadedProfileOrNull(): ModelExecutionProfile? =
    activeProfile?.takeIf { loaded }

/**
 * True when the installed record is the model the engine currently has resident. The app load path
 * writes `model.id` into the runtime identity, so the id normally matches; the artifact digest is a
 * conservative secondary key for legacy/renamed records so one resident model never appears twice
 * (once loaded, once as an installed-but-unloaded duplicate).
 */
private fun ModelManifest.matchesLoadedModel(profile: ModelExecutionProfile): Boolean {
    if (id.isNotBlank() && id == profile.modelId.trim()) return true
    val artifactFingerprint = profile.runtimeIdentity.artifactFingerprint.trim()
    return artifactFingerprint.isNotEmpty() &&
        sha256.isNotBlank() &&
        sha256.equals(artifactFingerprint, ignoreCase = true)
}

/** Stable, filesystem-free identity for an installed model that is not loaded right now. */
private fun ModelManifest.installedFingerprint(): String =
    AGENT_INSTALLED_FINGERPRINT_PREFIX + id + ":" + sha256.trim().lowercase()

private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
