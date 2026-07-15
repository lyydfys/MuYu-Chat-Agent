package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistantGenerationParamsTest {
    @Test
    fun importedAssistantCannotOverrideModelExecutionFields() {
        val modelDefaults = GenerationParams(
            nCtx = 4096,
            nThreads = 6,
            chatTemplateMode = "model",
            advancedJson = """{"n_batch":2048,"main_gpu":0}"""
        )
        val imported = """
            {
              "n_ctx": 131072,
              "n_threads": 16,
              "chat_template_mode": "evil",
              "advanced_json": {"main_gpu": 1, "n_cpu_moe": 5},
              "temperature": 0.25,
              "max_tokens": 777,
              "reasoning_mode": "standard"
            }
        """.trimIndent()

        val result = assistantGenerationParamsFromJson(imported, modelDefaults, "assistant")

        assertEquals(4096, result.nCtx)
        assertEquals(6, result.nThreads)
        assertEquals("model", result.chatTemplateMode)
        assertEquals(modelDefaults.advancedJson, result.advancedJson)
        assertEquals(0.25f, result.temperature)
        assertEquals(777, result.nPredict)
        assertEquals(ReasoningMode.STANDARD, result.reasoningMode)
        assertEquals("assistant", result.systemPrompt)
    }

    @Test
    fun serializedAssistantJsonContainsGenerationWhitelistOnly() {
        val json = JSONObject(
            GenerationParams(
                nCtx = 32768,
                nThreads = 12,
                chatTemplateMode = "jinja",
                advancedJson = """{"n_batch":1024}""",
                nPredict = 512,
                temperature = 0.7f
            ).toAssistantGenerationJson()
        )

        assertEquals(512, json.getInt("n_predict"))
        assertEquals(0.7, json.getDouble("temperature"), 0.0001)
        assertFalse(json.has("n_ctx"))
        assertFalse(json.has("n_threads"))
        assertFalse(json.has("advanced_json"))
        assertFalse(json.has("chat_template_mode"))
    }
}
