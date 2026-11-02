package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelChoiceMapperTest {

    private fun cloudModel(
        id: String,
        format: CloudApiFormat,
        supportsTools: Boolean,
        kind: CloudModelKind = CloudModelKind.CHAT,
        modelName: String = "gpt-test"
    ): CloudModelRecord = CloudModelRecord(
        id = id,
        kind = kind,
        apiFormat = format,
        providerName = format.label,
        displayName = "Cloud $id",
        baseUrl = "https://example.test/v1",
        apiKey = "",
        modelName = modelName,
        supportsTools = supportsTools
    )

    private class FakeCatalog(
        private val local: AgentLoadedLocalModel?,
        private val cloud: List<CloudModelRecord>
    ) : AgentModelCatalogSnapshot {
        var writes = 0
        override fun loadedLocalModel(): AgentLoadedLocalModel? = local
        override fun cloudModels(): List<CloudModelRecord> = cloud
    }

    @Test
    fun mapsLocalAndCloudModelsWithoutMutatingSelection() {
        val local = AgentLoadedLocalModel(
            modelId = "local-m",
            runtimeBackendId = "llama_cpp",
            identityHash = "identity-local",
            displayName = "Local model"
        )
        val responsesNative = cloudModel("r1", CloudApiFormat.OPENAI_RESPONSES, supportsTools = true)
        val responsesPrompted = cloudModel("r2", CloudApiFormat.OPENAI_RESPONSES, supportsTools = false)
        val otherProtocol = cloudModel("c1", CloudApiFormat.OPENAI_COMPATIBLE, supportsTools = true)
        val imageModel = cloudModel("i1", CloudApiFormat.OPENAI_RESPONSES, supportsTools = true, kind = CloudModelKind.IMAGE)
        val catalog = FakeCatalog(local, listOf(responsesNative, responsesPrompted, otherProtocol, imageModel))

        val choices = AgentModelChoiceMapper.choices(catalog)

        assertEquals(4, choices.size)
        assertEquals(AgentProtocolMode.PROMPTED_V1, choices[0].binding.protocolMode)
        assertTrue(choices[0].isLocal)
        assertEquals("identity-local", choices[0].binding.assetFingerprint)

        // N05e: the Responses wire declares tools, rebuilds terminal provider calls and sends the
        // paired function_call_output; that whole round is verified against a controlled Responses
        // server, so an opted-in model speaks NATIVE. The note keeps the real-provider gap visible.
        val nativeFlagged = choices.single { it.binding.modelDisplayName == "Cloud r1" }
        assertEquals(AgentProtocolMode.NATIVE, nativeFlagged.binding.protocolMode)
        assertEquals("cloud", nativeFlagged.binding.runtime)
        assertTrue(nativeFlagged.note.orEmpty().contains("受控"))
        assertTrue(nativeFlagged.note.orEmpty().contains("真实 provider"))
        assertTrue(nativeFlagged.binding.capabilityEvidenceVersion.orEmpty().startsWith("n05e-"))

        // Configuration did not opt in: keep the prompted compatibility path.
        val prompted = choices.single { it.binding.modelDisplayName == "Cloud r2" }
        assertEquals(AgentProtocolMode.PROMPTED_V1, prompted.binding.protocolMode)

        val compatible = choices.single { it.binding.modelDisplayName == "Cloud c1" }
        assertEquals(
            "a compatible chat record runs the prompted envelope over chat/completions",
            AgentProtocolMode.PROMPTED_V1,
            compatible.binding.protocolMode
        )
        assertTrue(compatible.note.orEmpty().contains("chat/completions"))
        assertEquals("prompted-chat-2026-10-28", compatible.binding.capabilityEvidenceVersion)

        assertTrue(
            "only the declared native wire format may claim NATIVE",
            choices.filter { it.binding.protocolMode == AgentProtocolMode.NATIVE }
                .all { it.binding.providerId == "cloud:openai_responses" }
        )

        assertEquals(0, catalog.writes)
    }

    @Test
    fun emptyCatalogProducesNoChoices() {
        val choices = AgentModelChoiceMapper.choices(FakeCatalog(null, emptyList()))
        assertTrue(choices.isEmpty())
    }
}
