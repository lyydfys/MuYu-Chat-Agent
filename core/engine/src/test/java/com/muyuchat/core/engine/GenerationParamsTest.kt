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

    private fun JSONArray.userMessage(): JSONObject =
        (0 until length())
            .asSequence()
            .map { getJSONObject(it) }
            .first { it.getString("role") == "user" }
}
