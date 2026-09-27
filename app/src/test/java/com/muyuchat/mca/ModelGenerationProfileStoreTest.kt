package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelGenerationProfileStoreTest {
    @Test
    fun profileKeepsSemanticFieldsButNeverReplacesAssistantOrLoadFields() {
        val defaults = GenerationParams(
            nCtx = 16_384,
            nThreads = 8,
            nPredict = 512,
            temperature = 0.2f,
            systemPrompt = "当前角色卡",
            chatTemplateMode = "model-template"
        )
        val stored = defaults.copy(
            nCtx = 4096,
            nThreads = 2,
            nPredict = 2048,
            temperature = 0.85f,
            topK = 64,
            topP = 0.8f,
            repeatPenalty = 1.15f,
            reasoningMode = ReasoningMode.ADVANCED,
            hideReasoning = false,
            systemPrompt = "模型不应覆盖角色卡"
        ).toModelGenerationProfileJson()

        val restored = modelGenerationParamsFromJson(stored, defaults)

        assertEquals(16_384, restored.nCtx)
        assertEquals(8, restored.nThreads)
        assertEquals("当前角色卡", restored.systemPrompt)
        assertEquals("model-template", restored.chatTemplateMode)
        assertEquals(2048, restored.nPredict)
        assertEquals(0.85f, restored.temperature)
        assertEquals(64, restored.topK)
        assertEquals(0.8f, restored.topP)
        assertEquals(1.15f, restored.repeatPenalty)
        assertEquals(ReasoningMode.ADVANCED, restored.reasoningMode)
    }

    @Test
    fun explicitlyClearedSeedDoesNotInheritAnotherModelSeed() {
        val defaults = GenerationParams(seed = 123)
        val profile = defaults.copy(seed = null).toModelGenerationProfileJson()
        val restored = modelGenerationParamsFromJson(profile, defaults)
        assertEquals(null, restored.seed)
    }

    @Test
    fun profileRoundTripsModelScopedLlamaBackendPolicy() {
        val defaults = GenerationParams(advancedJson = "{}")
        val stored = defaults.copy(
            advancedJson = "{\"n_gpu_layers\":-2,\"split_mode\":\"layer\"}"
        ).toModelGenerationProfileJson()

        val restored = modelGenerationParamsFromJson(stored, defaults)
        val advanced = org.json.JSONObject(restored.advancedJson)
        assertEquals(-2, advanced.optInt("n_gpu_layers"))
        assertEquals("layer", advanced.optString("split_mode"))
    }

    @Test
    fun localAndCloudIdentitiesCannotCollide() {
        assertNotEquals(
            ModelGenerationProfileKey.local("same-id"),
            ModelGenerationProfileKey.cloud("same-id")
        )
        assertEquals("local:same-id", ModelGenerationProfileKey.local("same-id"))
        assertEquals("cloud:same-id", ModelGenerationProfileKey.cloud("same-id"))
    }

    @Test
    fun malformedProfileFallsBackToCurrentDefaults() {
        val defaults = GenerationParams(temperature = 0.42f, nPredict = 123)
        val malformed = org.json.JSONObject().put("params", "not-an-object")
        val parsed = ModelGenerationProfileDocument.fromJson(malformed)
        assertEquals(null, parsed)
        assertEquals(defaults, modelGenerationParamsFromJson(org.json.JSONObject(), defaults))
    }

    @Test
    fun profileDocumentRoundTripsWithoutSecretOrPromptData() {
        val params = GenerationParams(
            systemPrompt = "private role prompt",
            temperature = 0.73f,
            stopWords = listOf("<end>")
        )
        val document = ModelGenerationProfileDocument(
            updatedAt = 99L,
            paramsJson = params.toModelGenerationProfileJson()
        )
        val raw = document.toJson().toString()
        val restored = ModelGenerationProfileDocument.fromJson(org.json.JSONObject(raw))

        assertTrue(restored != null)
        assertEquals(99L, restored?.updatedAt)
        assertEquals(0.73f, restored?.paramsJson?.getDouble("temperature")?.toFloat())
        assertFalse(raw.contains("private role prompt"))
        assertTrue(raw.contains("<end>"))
    }
}
