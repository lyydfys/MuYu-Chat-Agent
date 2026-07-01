package com.muyuchat.core.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParamsTest {
    @Test
    fun standardReasoningUsesCompactThinkingBudget() {
        val json = JSONObject(GenerationParams(reasoningMode = ReasoningMode.STANDARD).toJson())

        assertTrue(json.getBoolean("enable_thinking"))
        assertEquals(192, json.getInt("thinking_budget"))
        assertTrue(json.getInt("n_predict") >= 2048)
        assertEquals(1.08, json.getDouble("repeat_penalty"), 0.001)
        assertEquals(0.2, json.getDouble("frequency_penalty"), 0.001)
    }

    @Test
    fun offReasoningDisablesThinking() {
        val json = JSONObject(GenerationParams(reasoningMode = ReasoningMode.OFF).toJson())

        assertFalse(json.getBoolean("enable_thinking"))
        assertEquals(0, json.getInt("thinking_budget"))
        assertTrue(json.getBoolean("hide_reasoning"))
    }

    @Test
    fun reasoningInstructionDoesNotInjectScaffoldOrThinkSlashCommands() {
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "Who are you?")),
            params = GenerationParams(reasoningMode = ReasoningMode.STANDARD)
        )
        val messages = JSONArray(request.messagesJson())
        val system = messages.getJSONObject(0).getString("content")

        assertFalse(system.contains("Thinking Process"))
        assertFalse(system.contains("Output Format"))
        assertFalse(system.contains("System Requirements"))
        assertFalse(system.contains("Constraint"))
        assertFalse(system.contains("/think"))
        assertFalse(system.contains("/no_think"))
    }

    @Test
    fun multimodalMessagesUseOpenAiImageUrlPartsOnlyWhenRequested() {
        val message = ChatMessage(
            role = Role.USER,
            content = "Describe it",
            imageAttachments = listOf(
                ChatImageAttachment(
                    name = "photo.jpg",
                    mimeType = "image/jpeg",
                    dataBase64 = "abc123"
                )
            )
        )
        val request = ChatRequest(messages = listOf(message), params = GenerationParams(systemPrompt = ""))

        val textOnly = JSONArray(request.messagesJson())
            .userMessage()
            .get("content")
        val multimodal = JSONArray(request.messagesJson(multimodal = true))
            .userMessage()
            .getJSONArray("content")

        assertEquals("Describe it", textOnly)
        assertEquals("text", multimodal.getJSONObject(0).getString("type"))
        assertEquals("image_url", multimodal.getJSONObject(1).getString("type"))
        assertTrue(
            multimodal.getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/jpeg;base64,abc123")
        )
    }

    @Test
    fun generationParamsRoundTripKeepsPersonaAndSamplingSettings() {
        val original = GenerationParams(
            nCtx = 4096,
            nPredict = 2048,
            nThreads = 6,
            temperature = 0.42f,
            topK = 32,
            topP = 0.88f,
            minP = 0.03f,
            repeatPenalty = 1.12f,
            presencePenalty = 0.15f,
            frequencyPenalty = 0.25f,
            seed = 123,
            systemPrompt = "你是一张长期保存的角色卡。",
            stopWords = listOf("</s>", "<|end|>"),
            chatTemplateMode = "auto",
            advancedJson = """{"mirostat":0}""",
            reasoningMode = ReasoningMode.STANDARD,
            hideReasoning = false
        )

        val restored = GenerationParams.fromJson(original.toJson())

        assertEquals(original.nCtx, restored.nCtx)
        assertEquals(original.nPredict, restored.nPredict)
        assertEquals(original.nThreads, restored.nThreads)
        assertEquals(original.temperature, restored.temperature)
        assertEquals(original.topK, restored.topK)
        assertEquals(original.topP, restored.topP)
        assertEquals(original.minP, restored.minP)
        assertEquals(original.repeatPenalty, restored.repeatPenalty)
        assertEquals(original.presencePenalty, restored.presencePenalty)
        assertEquals(original.frequencyPenalty, restored.frequencyPenalty)
        assertEquals(original.seed, restored.seed)
        assertEquals(original.systemPrompt, restored.systemPrompt)
        assertEquals(original.stopWords, restored.stopWords)
        assertEquals(original.chatTemplateMode, restored.chatTemplateMode)
        assertEquals(original.advancedJson, restored.advancedJson)
        assertEquals(original.reasoningMode, restored.reasoningMode)
        assertEquals(original.hideReasoning, restored.hideReasoning)
    }

    private fun JSONArray.userMessage(): JSONObject =
        (0 until length())
            .asSequence()
            .map { getJSONObject(it) }
            .first { it.getString("role") == "user" }
}
