package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.agent.inference.AGENT_INSTALLED_FINGERPRINT_PREFIX
import com.muyuchat.mca.agent.inference.AgentEngineExecutionSnapshot
import com.muyuchat.mca.agent.inference.AgentLocalModelCatalogReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter-level tests for what the Agent picker actually receives.
 *
 * These pin the user-visible contract of this slice: installed-but-unloaded models are listed with
 * the contract `UNLOADED` state and no engine identity, the genuinely loaded model is first and
 * carries the engine identity, and a stale committed profile (unload/failed reload/worker loss)
 * never produces a fake loaded choice. Protocol projection stays accurate: local is always
 * PROMPTED_V1 and an unsupported cloud format stays NONE.
 */
class AgentModelCatalogAdapterTest {

    @Test
    fun installedButUnloadedModelsAreVisibleWithExplicitState() {
        val choices = observeChoices(
            state = MainUiState(
                models = listOf(
                    manifest(id = "m-a", displayName = "Model A"),
                    manifest(id = "m-b", displayName = "Model B")
                )
            ),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false)
        )

        assertEquals(listOf("m-a", "m-b"), choices.map { it.modelId })
        assertEquals(listOf("Model A", "Model B"), choices.map { it.displayName })
        choices.forEach { choice ->
            assertEquals(AgentModelLoadState.UNLOADED, choice.loadState)
            // A local model never needs the network; loadState is the honest non-runnability signal.
            assertTrue(choice.availableOffline)
            assertEquals(AgentProtocolMode.PROMPTED_V1, choice.protocolMode)
            assertTrue(choice.assetFingerprint.startsWith(AGENT_INSTALLED_FINGERPRINT_PREFIX))
            assertEquals(AgentModelCatalogAdapter.LOCAL_PROVIDER_ID, choice.providerId)
        }
    }

    @Test
    fun loadedModelIsPreferredFirstAndUsesTheEngineIdentity() {
        val identity = identity(modelId = "m-b")
        val choices = observeChoices(
            state = MainUiState(
                models = listOf(
                    manifest(id = "m-a", displayName = "Model A"),
                    manifest(id = "m-b", displayName = "Model B")
                ),
                loadedModelName = "Model B"
            ),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = profile(identity), loaded = true)
        )

        val loaded = choices.first()
        assertEquals("m-b", loaded.modelId)
        assertEquals("Model B", loaded.displayName)
        assertEquals(AgentModelLoadState.LOADED, loaded.loadState)
        assertTrue(loaded.availableOffline)
        assertEquals(identity.identityHash, loaded.assetFingerprint)
        assertEquals(LocalChatRuntime.LLAMA_CPP.backendId, loaded.runtime)
        assertEquals(AgentProtocolMode.PROMPTED_V1, loaded.protocolMode)
        assertEquals(listOf("m-b", "m-a"), choices.map { it.modelId })
        assertEquals(AgentModelLoadState.UNLOADED, choices[1].loadState)
        assertTrue(choices[1].assetFingerprint.startsWith(AGENT_INSTALLED_FINGERPRINT_PREFIX))
    }

    @Test
    fun committedProfileWithUnloadedStatsNeverProducesAFakeLoadedChoice() {
        val identity = identity(modelId = "m-a")
        val choices = observeChoices(
            state = MainUiState(models = listOf(manifest(id = "m-a", displayName = "Model A"))),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = profile(identity), loaded = false)
        )

        val only = choices.single()
        assertEquals("m-a", only.modelId)
        assertEquals("Model A", only.displayName)
        assertEquals(AgentModelLoadState.UNLOADED, only.loadState)
        assertNotEquals(identity.identityHash, only.assetFingerprint)
        assertTrue(only.assetFingerprint.startsWith(AGENT_INSTALLED_FINGERPRINT_PREFIX))
        assertEquals(AgentProtocolMode.PROMPTED_V1, only.protocolMode)
    }

    @Test
    fun noChoiceClaimsLoadedWhenTheEngineHasNoActiveProfile() {
        val choices = observeChoices(
            state = MainUiState(models = listOf(manifest(id = "m-a"), manifest(id = "m-b"))),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false)
        )

        assertTrue(choices.isNotEmpty())
        assertTrue(choices.none { it.loadState == AgentModelLoadState.LOADED })
        assertTrue(choices.all { it.assetFingerprint.startsWith(AGENT_INSTALLED_FINGERPRINT_PREFIX) })
    }

    @Test
    fun localChoicesNeverReportUnsupportedProtocol() {
        val choices = observeChoices(
            state = MainUiState(
                models = listOf(manifest(id = "m-a"), manifest(id = "m-b"))
            ),
            snapshot = AgentEngineExecutionSnapshot(
                activeProfile = profile(identity(modelId = "m-b")),
                loaded = true
            )
        )

        assertTrue(choices.isNotEmpty())
        assertTrue(choices.all { it.protocolMode != AgentProtocolMode.NONE })
        assertTrue(choices.all { it.protocolMode == AgentProtocolMode.PROMPTED_V1 })
    }

    @Test
    fun cloudProtocolProjectionKeepsOnlyUnwiredFormatsAtNone() {
        val choices = observeChoices(
            state = MainUiState(
                models = emptyList(),
                cloudModels = listOf(
                    cloudModel(id = "native", CloudApiFormat.OPENAI_RESPONSES, supportsTools = true),
                    cloudModel(id = "prompted", CloudApiFormat.OPENAI_RESPONSES, supportsTools = false),
                    cloudModel(id = "compatible", CloudApiFormat.OPENAI_COMPATIBLE, supportsTools = true),
                    cloudModel(id = "unsupported", CloudApiFormat.ANTHROPIC, supportsTools = true)
                )
            ),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false),
            includeCloudModels = true
        )

        val byId = choices.associateBy { it.providerId }
        assertEquals(
            "the controlled-verified Responses wire is offered as NATIVE when the user opts in",
            AgentProtocolMode.NATIVE,
            byId.getValue(AgentModelCatalogAdapter.CLOUD_PROVIDER_PREFIX + "native").protocolMode
        )
        assertEquals(
            AgentProtocolMode.PROMPTED_V1,
            byId.getValue(AgentModelCatalogAdapter.CLOUD_PROVIDER_PREFIX + "prompted").protocolMode
        )
        val compatible = byId.getValue(AgentModelCatalogAdapter.CLOUD_PROVIDER_PREFIX + "compatible")
        assertEquals(
            "a compatible record runs the prompted chat wire",
            AgentProtocolMode.PROMPTED_V1,
            compatible.protocolMode
        )
        assertEquals(AgentModelLoadState.LOADED, compatible.loadState)
        assertFalse(compatible.availableOffline)
        val unsupported = byId.getValue(AgentModelCatalogAdapter.CLOUD_PROVIDER_PREFIX + "unsupported")
        assertEquals(AgentProtocolMode.NONE, unsupported.protocolMode)
        // Cloud providers need no local engine load; only their protocol may make them unusable.
        assertEquals(AgentModelLoadState.LOADED, unsupported.loadState)
        assertFalse(unsupported.availableOffline)
    }

    @Test
    fun aControlledVerifiedWireProjectsNativeOnlyWhenTheUserOptsIn() {
        val optedIn = MainUiState(
            models = emptyList(),
            cloudModels = listOf(
                cloudModel(id = "native", CloudApiFormat.OPENAI_RESPONSES, supportsTools = true)
            )
        )
        val notOptedIn = MainUiState(
            models = emptyList(),
            cloudModels = listOf(
                cloudModel(id = "native", CloudApiFormat.OPENAI_RESPONSES, supportsTools = false)
            )
        )
        val snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false)

        // The Responses wire is verified against a controlled provider server, so the picker may
        // advertise the protocol the runtime will really speak — without a per-model record.
        val projected = observeChoices(optedIn, snapshot, includeCloudModels = true)
        assertEquals(AgentProtocolMode.NATIVE, projected.single().protocolMode)

        // supportsTools stays the explicit opt-in; no user toggle means the prompted envelope.
        val prompted = observeChoices(notOptedIn, snapshot, includeCloudModels = true)
        assertEquals(AgentProtocolMode.PROMPTED_V1, prompted.single().protocolMode)
    }

    @Test
    fun choicesFollowTheEngineLoadedStateWithoutAProcessRestart() = runBlocking {
        val state = MutableStateFlow(
            MainUiState(models = listOf(manifest(id = "m-a", displayName = "Model A")))
        )
        val snapshot = MutableStateFlow(AgentEngineExecutionSnapshot(activeProfile = null, loaded = false))
        val adapter = AgentModelCatalogAdapter(
            reads = FakeFlowReads(snapshot),
            state = state,
            includeCloudModels = false
        )
        val beforeLoad = adapter.observeModelChoices().first()

        val identity = identity(modelId = "m-a")
        snapshot.value = AgentEngineExecutionSnapshot(activeProfile = profile(identity), loaded = true)
        val afterLoad = adapter.observeModelChoices().first()

        assertEquals(AgentModelLoadState.UNLOADED, beforeLoad.single().loadState)
        assertEquals(AgentModelLoadState.LOADED, afterLoad.single().loadState)
        assertEquals(identity.identityHash, afterLoad.single().assetFingerprint)
    }

    private fun observeChoices(
        state: MainUiState,
        snapshot: AgentEngineExecutionSnapshot,
        includeCloudModels: Boolean = false,
        nativeVerified: Boolean = false
    ): List<AgentModelChoice> = runBlocking {
        AgentModelCatalogAdapter(
            reads = FakeReads(snapshot),
            state = flowOf(state),
            includeCloudModels = includeCloudModels,
            nativeToolEvidence = { _, _ -> nativeVerified }
        ).observeModelChoices().first()
    }

    private class FakeReads(
        private val snapshot: AgentEngineExecutionSnapshot
    ) : AgentLocalModelCatalogReads {
        override fun observeSnapshot(): Flow<AgentEngineExecutionSnapshot> = flowOf(snapshot)
    }

    private class FakeFlowReads(
        private val snapshots: Flow<AgentEngineExecutionSnapshot>
    ) : AgentLocalModelCatalogReads {
        override fun observeSnapshot(): Flow<AgentEngineExecutionSnapshot> = snapshots
    }

    private fun identity(
        modelId: String,
        runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP
    ): ModelRuntimeIdentity = ModelRuntimeIdentity(
        modelId = modelId,
        artifactFingerprint = "artifact-$modelId",
        runtime = runtime
    )

    private fun profile(identity: ModelRuntimeIdentity): ModelExecutionProfile = ModelExecutionProfile(
        modelId = identity.modelId,
        runtimeIdentity = identity,
        desiredLoadBoundValues = CanonicalParameterSet.EMPTY,
        resolvedLoadBoundValues = CanonicalParameterSet.EMPTY,
        hotExecutionValues = CanonicalParameterSet.EMPTY
    )

    private fun manifest(
        id: String,
        displayName: String = "Model $id",
        runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP
    ): ModelManifest = ModelManifest(
        id = id,
        displayName = displayName,
        path = "models/$id.gguf",
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = "$id.gguf",
        sizeBytes = 1_024L,
        sha256 = "b".repeat(64)
    )

    private fun cloudModel(
        id: String,
        format: CloudApiFormat,
        supportsTools: Boolean
    ): CloudModelRecord = CloudModelRecord(
        id = id,
        kind = CloudModelKind.CHAT,
        apiFormat = format,
        providerName = format.label,
        displayName = "Cloud $id",
        baseUrl = "https://example.test/v1",
        apiKey = "test-key",
        modelName = "gpt-test",
        supportsTools = supportsTools
    )
}
