package com.muyuchat.core.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Both transcript serializers open with a SYSTEM message that carries [AgentModelRequest.systemPrompt]
 * verbatim. Adapters that send the prompt as their own SYSTEM message must send the transcript turns
 * only; echoing both doubled every request and, on an 8192-token local model, rejected every new
 * Agent task before generation.
 */
class AgentModelRequestConversationalMessagesTest {

    private val prompt = "MCA AGENT SYSTEM PROMPT v=mca.agent.system.v1\nAVAILABLE TOOLS:\n- workspace.list"

    @Test
    fun `drops the serialized system echo`() {
        val request = request(
            messages = listOf(
                message("agent-msg-0", AgentTranscriptRole.SYSTEM, prompt),
                message("agent-msg-1", AgentTranscriptRole.USER, """{"record":"user_input"}"""),
                message("agent-msg-2", AgentTranscriptRole.ASSISTANT, """{"kind":"final"}""")
            )
        )
        val turns = request.conversationalMessages
        assertEquals(2, turns.size)
        assertEquals(AgentTranscriptRole.USER, turns[0].role)
        assertEquals(AgentTranscriptRole.ASSISTANT, turns[1].role)
    }

    @Test
    fun `keeps every message when the transcript has no system echo`() {
        val messages = listOf(
            message("agent-msg-0", AgentTranscriptRole.USER, """{"record":"user_input"}"""),
            message("agent-msg-1", AgentTranscriptRole.ASSISTANT, """{"kind":"final"}""")
        )
        val request = request(messages)
        assertSame(messages, request.conversationalMessages)
    }

    @Test
    fun `keeps a system turn that is not the request prompt`() {
        val messages = listOf(
            message("agent-msg-0", AgentTranscriptRole.SYSTEM, "a different instruction block"),
            message("agent-msg-1", AgentTranscriptRole.USER, """{"record":"user_input"}""")
        )
        val request = request(messages)
        assertEquals(2, request.conversationalMessages.size)
    }

    private fun message(
        id: String,
        role: AgentTranscriptRole,
        content: String
    ): AgentModelMessage = AgentModelMessage(messageId = id, role = role, content = content)

    private fun request(messages: List<AgentModelMessage>): AgentModelRequest = AgentModelRequest(
        attempt = AgentModelAttemptRef(
            attemptId = "attempt-1",
            runId = "run-1",
            stepIndex = 0,
            ordinal = 1
        ),
        binding = AgentModelBinding(
            providerId = "local",
            modelId = "qwen3-0.6b",
            runtime = "geniex_qairt",
            assetFingerprint = "fp",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Qwen3-0.6B",
            contextWindowTokens = 8192
        ),
        systemPrompt = prompt,
        messages = messages,
        maxOutputTokens = 256
    )
}
