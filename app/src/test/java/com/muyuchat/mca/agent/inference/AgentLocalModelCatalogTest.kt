package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for the local Agent model catalog projection.
 *
 * The catalog is the contract between the production chat model store (`MainUiState.models`) and the
 * Agent picker. These tests pin the three properties the picker relies on: installed models stay
 * visible when nothing is loaded, a genuinely loaded model is preferred and carries the engine
 * identity, and a committed profile without engine `loaded=true` is never presented as loaded.
 */
class AgentLocalModelCatalogTest {

    @Test
    fun installedModelsAreVisibleAsUnloadedWithoutAnEngineProfile() {
        val entries = buildAgentLocalModelEntries(
            installedModels = listOf(
                manifest(id = "mnn-qwen3-4b", displayName = "Qwen3 4B", runtime = ChatModelRuntime.MNN),
                manifest(id = "gguf-llama-3", displayName = "Llama 3", runtime = ChatModelRuntime.LLAMA_CPP)
            ),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false)
        )

        assertEquals(listOf("mnn-qwen3-4b", "gguf-llama-3"), entries.map { it.modelId })
        entries.forEach { entry ->
            assertEquals(AgentModelLoadState.UNLOADED, entry.loadState)
            assertFalse(entry.isLoaded)
            // Local models never need the network; the explicit load state, not offline capability,
            // is what keeps an unloaded model from being offered as runnable.
            assertTrue(entry.availableOffline)
            assertEquals(AgentProtocolMode.PROMPTED_V1, entry.protocolMode)
            assertTrue(entry.installed)
            assertTrue(entry.assetFingerprint.startsWith(AGENT_INSTALLED_FINGERPRINT_PREFIX))
        }
        assertEquals("Qwen3 4B", entries[0].displayName)
        assertEquals(LocalChatRuntime.MNN_CPU.backendId, entries[0].runtime)
        assertEquals(LocalChatRuntime.LLAMA_CPP.backendId, entries[1].runtime)
    }

    @Test
    fun loadedModelIsPreferredAndCarriesTheEngineIdentity() {
        val installed = listOf(
            manifest(id = "m-a", displayName = "Model A"),
            manifest(id = "m-b", displayName = "Model B")
        )
        val identity = testIdentity(
            modelId = "m-b",
            runtime = LocalChatRuntime.LLAMA_CPP,
            artifactFingerprint = "artifact-b"
        )

        val entries = buildAgentLocalModelEntries(
            installedModels = installed,
            snapshot = AgentEngineExecutionSnapshot(activeProfile = testProfile(identity), loaded = true)
        )

        val first = entries.first()
        assertEquals("m-b", first.modelId)
        assertEquals(AgentModelLoadState.LOADED, first.loadState)
        assertTrue(first.isLoaded)
        assertTrue(first.availableOffline)
        assertEquals(identity.identityHash, first.assetFingerprint)
        assertEquals(LocalChatRuntime.LLAMA_CPP.backendId, first.runtime)
        assertEquals("Model B", first.displayName)
        assertEquals(listOf("m-b", "m-a"), entries.map { it.modelId })
        assertEquals(1, entries.count { it.loadState == AgentModelLoadState.LOADED })
        assertEquals(AgentModelLoadState.UNLOADED, entries[1].loadState)
    }

    @Test
    fun committedProfileWithoutLoadedStatsIsNeverReportedLoaded() {
        // ParameterCoordinator.markUnloaded() keeps the committed profile; only engine stats.loaded
        // proves the native model is still resident. This mirrors unload/failed-reload/worker loss.
        val identity = testIdentity(modelId = "m-a", artifactFingerprint = "artifact-a")
        val entries = buildAgentLocalModelEntries(
            installedModels = listOf(manifest(id = "m-a", displayName = "Model A")),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = testProfile(identity), loaded = false)
        )

        assertEquals(1, entries.size)
        assertEquals(AgentModelLoadState.UNLOADED, entries.single().loadState)
        assertFalse(entries.single().isLoaded)
        assertTrue(entries.single().availableOffline)
        assertNotEquals(identity.identityHash, entries.single().assetFingerprint)
    }

    @Test
    fun loadedModelIsNotListedTwiceWhenItsInstalledIdDiffers() {
        // Legacy/renamed store id: the engine identity modelId no longer equals manifest.id, but the
        // artifact digest still identifies the same resident model.
        val digest = "c".repeat(64)
        val identity = testIdentity(
            modelId = "legacy-file-name",
            runtime = LocalChatRuntime.LLAMA_CPP,
            artifactFingerprint = digest
        )

        val entries = buildAgentLocalModelEntries(
            installedModels = listOf(manifest(id = "renamed-id", displayName = "Qwen3 4B", sha256 = digest)),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = testProfile(identity), loaded = true)
        )

        assertEquals(1, entries.size)
        assertEquals(AgentModelLoadState.LOADED, entries.single().loadState)
        assertEquals("Qwen3 4B", entries.single().displayName)
        assertTrue(entries.single().installed)
    }

    @Test
    fun loadedModelMissingFromTheStoreStillAppearsAsLoadedOnce() {
        val identity = testIdentity(modelId = "external-model", artifactFingerprint = "artifact-x")

        val entries = buildAgentLocalModelEntries(
            installedModels = listOf(manifest(id = "m-a", displayName = "Model A")),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = testProfile(identity), loaded = true),
            loadedDisplayName = "External Model"
        )

        assertEquals(listOf("external-model", "m-a"), entries.map { it.modelId })
        assertEquals("External Model", entries.first().displayName)
        assertFalse(entries.first().installed)
        assertEquals(AgentModelLoadState.LOADED, entries.first().loadState)
    }

    @Test
    fun localModelsAlwaysUsePromptedV1AndNeverNone() {
        val identity = testIdentity(modelId = "m-loaded")
        val loadedEntries = buildAgentLocalModelEntries(
            installedModels = listOf(manifest(id = "m-loaded")),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = testProfile(identity), loaded = true)
        )
        val unloadedEntries = buildAgentLocalModelEntries(
            installedModels = listOf(manifest(id = "m-idle")),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false)
        )

        (loadedEntries + unloadedEntries).forEach { entry ->
            assertEquals(AgentProtocolMode.PROMPTED_V1, entry.protocolMode)
        }
    }

    @Test
    fun duplicateInstalledIdsCollapseAndLoadedEntryWins() {
        val identity = testIdentity(modelId = "m-a")
        val entries = buildAgentLocalModelEntries(
            installedModels = listOf(
                manifest(id = "m-a", displayName = "Stale A"),
                manifest(id = "m-a", displayName = "Stale A copy"),
                manifest(id = "m-b", displayName = "Model B")
            ),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = testProfile(identity), loaded = true)
        )

        assertEquals(listOf("m-a", "m-b"), entries.map { it.modelId })
        assertEquals(AgentModelLoadState.LOADED, entries.first().loadState)
    }

    @Test
    fun manifestRuntimeMapsToEngineBackendId() {
        val entries = buildAgentLocalModelEntries(
            installedModels = listOf(
                manifest(id = "qairt-model", runtime = ChatModelRuntime.GENIEX_QAIRT),
                manifest(id = "litert-model", runtime = ChatModelRuntime.LITERT_LM)
            ),
            snapshot = AgentEngineExecutionSnapshot(activeProfile = null, loaded = false)
        )

        assertEquals(LocalChatRuntime.GENIEX_QAIRT.backendId, entries[0].runtime)
        assertEquals(LocalChatRuntime.LITERT_LM.backendId, entries[1].runtime)
    }

    private fun manifest(
        id: String,
        displayName: String = "Model $id",
        runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP,
        sha256: String = "a".repeat(64)
    ): ModelManifest = ModelManifest(
        id = id,
        displayName = displayName,
        path = "models/$id.gguf",
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = "$id.gguf",
        sizeBytes = 1_024L,
        sha256 = sha256
    )
}
