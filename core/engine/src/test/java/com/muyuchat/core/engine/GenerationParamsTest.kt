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
}
