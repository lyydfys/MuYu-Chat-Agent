package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentPromptedTranscriptContext
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N05c send side: native calls/results become provider items, prompted entries stay text. */
class AgentNativeTranscriptSerializerTest {

    private val serializer = AgentNativeTranscriptSerializer()
    private val codec = StrictAgentJsonCodec()

    private fun bridgeEnvelope(name: String, argumentsJson: String): String = codec.write(
        AgentJsonValues.obj(
            "protocol" to AgentJsonValue.Str("mca.agent.v1"),
            "kind" to AgentJsonValue.Str("tool"),
            "call" to AgentJsonValues.obj(
                "name" to AgentJsonValue.Str(name),
                "arguments" to (codec.parse(argumentsJson) as AgentJsonParseResult.Parsed).value
            )
        )
    )

    private fun result(): AgentJsonValue = AgentJsonValues.obj(
        "status" to AgentJsonValue.Str("SUCCEEDED"),
        "data" to AgentJsonValues.obj("content" to AgentJsonValue.Str("hello"))
    )

    private fun context(entries: List<AgentTranscriptEntry>): AgentPromptedTranscriptContext =
        AgentPromptedTranscriptContext(
            systemPrompt = "SYSTEM",
            userGoal = "整理 inputs/brief.txt",
            entries = entries
        )

    @Test
    fun aPairedNativeCallBecomesFunctionCallAndFunctionOutput() {
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope(
                        rawEnvelope = bridgeEnvelope(
                            "workspace.read_text",
                            """{"path":"inputs/brief.txt"}"""
                        ),
                        providerCallId = "call_1"
                    ),
                    AgentTranscriptEntry.ToolResult(
                        invocationId = "invocation-1",
                        callHash = "a".repeat(64),
                        toolKey = "workspace.read_text",
                        result = result(),
                        providerCallId = "call_1"
                    )
                )
            )
        )

        val messages = serialization.messages
        assertEquals(4, messages.size)
        assertEquals(AgentModelMessageKind.TEXT, messages[0].kind)
        assertEquals(AgentModelMessageKind.TEXT, messages[1].kind)
        val call = messages[2]
        assertEquals(AgentModelMessageKind.FUNCTION_CALL, call.kind)
        assertEquals("call_1", call.callId)
        assertEquals("workspace.read_text", call.toolName)
        assertTrue(call.content.contains("\"path\""))
        val output = messages[3]
        assertEquals(AgentModelMessageKind.FUNCTION_OUTPUT, output.kind)
        assertEquals("call_1", output.callId)
        assertTrue(output.content.contains("SUCCEEDED"))
    }

    @Test
    fun aPromptedEnvelopeStaysAssistantTextAndUnpairedResultsStayObservations() {
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope("""{"protocol":"mca.agent.v1","kind":"final","text":"done"}"""),
                    AgentTranscriptEntry.ToolResult(
                        invocationId = "invocation-1",
                        callHash = "a".repeat(64),
                        toolKey = "workspace.read_text",
                        result = result()
                    )
                )
            )
        )

        val messages = serialization.messages
        assertEquals(AgentModelMessageKind.TEXT, messages[2].kind)
        assertEquals("""{"protocol":"mca.agent.v1","kind":"final","text":"done"}""", messages[2].content)
        assertEquals(AgentModelMessageKind.TEXT, messages[3].kind)
        assertTrue(messages[3].content.contains("\"record\":\"tool_result\""))
        assertFalse(messages.any { it.kind != AgentModelMessageKind.TEXT })
    }
}
