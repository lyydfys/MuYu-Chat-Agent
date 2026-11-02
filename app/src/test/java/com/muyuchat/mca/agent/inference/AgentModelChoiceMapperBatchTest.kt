package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N05d capability-truth projection: native only when the wire capability exists *and* the model
 * configuration opts in; every other case falls back to the prompted path (or NONE).
 */
class AgentModelChoiceMapperBatchTest {

    private fun cloudModel(
        id: String,
        format: CloudApiFormat,
        supportsTools: Boolean,
        kind: CloudModelKind = CloudModelKind.CHAT,
        modelName: String = "gpt-test",
        apiKey: String = ""
    ): CloudModelRecord = CloudModelRecord(
        id = id,
        kind = kind,
        apiFormat = format,
        providerName = format.label,
        displayName = "Cloud $id",
        baseUrl = "https://example.test/v1",
        apiKey = apiKey,
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
    fun nativeProtocolRequiresCapabilityAndExplicitConfiguration() {
        val local = AgentLoadedLocalModel(
            modelId = "local-m",
            runtimeBackendId = "llama_cpp",
            identityHash = "identity-local",
            displayName = "Local model"
        )
        val responsesTools = cloudModel("r1", CloudApiFormat.OPENAI_RESPONSES, supportsTools = true)
        val responsesNoTools = cloudModel("r2", CloudApiFormat.OPENAI_RESPONSES, supportsTools = false)
        val otherFormatTools = cloudModel("c1", CloudApiFormat.OPENAI_COMPATIBLE, supportsTools = true)
        val imageModel = cloudModel(
            "i1",
            CloudApiFormat.OPENAI_RESPONSES,
            supportsTools = true,
            kind = CloudModelKind.IMAGE
        )
        val catalog = FakeCatalog(
            local,
            listOf(responsesTools, responsesNoTools, otherFormatTools, imageModel)
        )

        val choices = AgentModelChoiceMapper.choices(catalog)

        assertEquals(4, choices.size)
        assertEquals(AgentProtocolMode.PROMPTED_V1, choices[0].binding.protocolMode)
        assertTrue(choices[0].isLocal)

        val native = choices.single { it.binding.modelDisplayName == "Cloud r1" }
        assertEquals(
            "the controlled-verified Responses wire speaks NATIVE when the user opts in",
            AgentProtocolMode.NATIVE,
            native.binding.protocolMode
        )
        assertEquals("cloud", native.binding.runtime)
        assertTrue(native.note.orEmpty().contains("受控"))
        assertTrue(native.note.orEmpty().contains("真实 provider"))
        assertTrue(native.binding.capabilityEvidenceVersion.orEmpty().startsWith("n05e-"))

        val prompted = choices.single { it.binding.modelDisplayName == "Cloud r2" }
        assertEquals(AgentProtocolMode.PROMPTED_V1, prompted.binding.protocolMode)
        assertTrue(prompted.note.orEmpty().contains("supportsTools"))

        val compatible = choices.single { it.binding.modelDisplayName == "Cloud c1" }
        assertEquals(
            "a compatible record runs the prompted chat wire, never NATIVE",
            AgentProtocolMode.PROMPTED_V1,
            compatible.binding.protocolMode
        )
        assertTrue(compatible.note.orEmpty().contains("chat/completions"))
        assertTrue(
            "an image record is never projected as a chat tool protocol",
            choices.none { it.binding.modelDisplayName == "Cloud i1" }
        )
        assertEquals(0, catalog.writes)
    }

    @Test
    fun aFormatWithoutDeclaredCapabilityNeverClaimsNative() {
        val catalog = FakeCatalog(
            null,
            listOf(
                cloudModel("c1", CloudApiFormat.OPENAI_COMPATIBLE, supportsTools = true),
                cloudModel("c2", CloudApiFormat.ANTHROPIC, supportsTools = true, apiKey = "k")
            )
        )
        val choices = AgentModelChoiceMapper.choices(catalog)
        assertEquals(2, choices.size)
        assertTrue(
            "no format other than Responses may claim NATIVE",
            choices.none { it.binding.protocolMode == AgentProtocolMode.NATIVE }
        )
        assertEquals(
            "the compatible record keeps the prompted escape hatch",
            AgentProtocolMode.PROMPTED_V1,
            choices.single { it.binding.modelDisplayName == "Cloud c1" }.binding.protocolMode
        )
        assertEquals(
            "Anthropic has neither wire and stays a typed rejection",
            AgentProtocolMode.NONE,
            choices.single { it.binding.modelDisplayName == "Cloud c2" }.binding.protocolMode
        )
    }

    @Test
    fun emptyCatalogProducesNoChoices() {
        val choices = AgentModelChoiceMapper.choices(FakeCatalog(null, emptyList()))
        assertTrue(choices.isEmpty())
    }
}
