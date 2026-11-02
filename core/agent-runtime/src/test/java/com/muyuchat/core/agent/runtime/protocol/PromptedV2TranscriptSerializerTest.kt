package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentPromptedTranscriptContext
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N05d: the native serializer reads transcript v2 records, upcasts legacy V1 bridge envelopes and
 * fails closed on any record whose call pairing does not match the authoritative transcript.
 */
class PromptedV2TranscriptSerializerTest {

    private val serializer = AgentNativeTranscriptSerializer()
    private val transcript = AgentTranscriptV2Codec()
    private val json = StrictAgentJsonCodec()

    private fun callRecord(
        callId: String = "call_1",
        toolKey: String = "workspace.read_text",
        path: String = "inputs/a.txt"
    ): String = transcript.encode(
        AgentTranscriptV2Record.AssistantFunctionCall(
            callId = callId,
            toolKey = toolKey,
            arguments = AgentJsonValues.obj("path" to AgentJsonValue.Str(path))
        )
    )

    private fun outputRecord(
        callId: String = "call_1",
        result: AgentJsonValue = succeeded()
    ): AgentJsonValue = transcript.recordValue(
        AgentTranscriptV2Record.FunctionCallOutput(callId = callId, result = result)
    )

    private fun succeeded(content: String = "hello"): AgentJsonValue = AgentJsonValues.obj(
        "schemaVersion" to AgentJsonValue.Num("1"),
        "status" to AgentJsonValue.Str("SUCCEEDED"),
        "data" to AgentJsonValues.obj("content" to AgentJsonValue.Str(content))
    )

    private fun context(entries: List<AgentTranscriptEntry>): AgentPromptedTranscriptContext =
        AgentPromptedTranscriptContext(
            systemPrompt = "SYSTEM",
            userGoal = "read inputs/a.txt",
            entries = entries
        )

    @Test
    fun v2CallAndOutputRecordsBecomeProviderItems() {
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope(
                        rawEnvelope = callRecord(),
                        providerCallId = "call_1"
                    ),
                    AgentTranscriptEntry.ToolResult(
                        invocationId = "invocation-1",
                        callHash = "a".repeat(64),
                        toolKey = "workspace.read_text",
                        result = outputRecord(),
                        providerCallId = "call_1"
                    )
                )
            )
        )
        val messages = serialization.messages
        assertEquals(4, messages.size)
        val call = messages[2]
        assertEquals(AgentModelMessageKind.FUNCTION_CALL, call.kind)
        assertEquals("call_1", call.callId)
        assertEquals("workspace.read_text", call.toolName)
        assertEquals("{\"path\":\"inputs/a.txt\"}", call.content)
        val output = messages[3]
        assertEquals(AgentModelMessageKind.FUNCTION_OUTPUT, output.kind)
        assertEquals("call_1", output.callId)
        // The provider sees the tool result itself, not MCA's transcript envelope.
        assertEquals(json.write(AgentJsonValues.canonical(succeeded())), output.content)
        assertFalse(output.content.contains("user_function_call_output"))
    }

    @Test
    fun aLegacyV1BridgeEnvelopeIsStillReadable() {
        val legacyEnvelope = json.write(
            AgentJsonValues.obj(
                "protocol" to AgentJsonValue.Str("mca.agent.v1"),
                "kind" to AgentJsonValue.Str("tool"),
                "call" to AgentJsonValues.obj(
                    "name" to AgentJsonValue.Str("workspace.read_text"),
                    "arguments" to AgentJsonValues.obj("path" to AgentJsonValue.Str("inputs/a.txt"))
                ),
                "callId" to AgentJsonValue.Str("call_9")
            )
        )
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope(legacyEnvelope, providerCallId = "call_9"),
                    AgentTranscriptEntry.ToolResult(
                        invocationId = "invocation-1",
                        callHash = "a".repeat(64),
                        toolKey = "workspace.read_text",
                        result = succeeded(),
                        providerCallId = "call_9"
                    )
                )
            )
        )
        val call = serialization.messages[2]
        assertEquals(AgentModelMessageKind.FUNCTION_CALL, call.kind)
        assertEquals("call_9", call.callId)
        assertEquals("workspace.read_text", call.toolName)
        assertEquals(AgentModelMessageKind.FUNCTION_OUTPUT, serialization.messages[3].kind)
    }

    @Test
    fun aLegacyV1BridgeEnvelopeWithoutItsOwnCallIdPairsWithTheEntryId() {
        val legacyEnvelope = json.write(
            AgentJsonValues.obj(
                "protocol" to AgentJsonValue.Str("mca.agent.v1"),
                "kind" to AgentJsonValue.Str("tool"),
                "call" to AgentJsonValues.obj(
                    "name" to AgentJsonValue.Str("workspace.read_text"),
                    "arguments" to AgentJsonValues.obj("path" to AgentJsonValue.Str("inputs/a.txt"))
                )
            )
        )
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope(legacyEnvelope, providerCallId = "call_4"),
                    AgentTranscriptEntry.ToolResult(
                        invocationId = "invocation-1",
                        callHash = "a".repeat(64),
                        toolKey = "workspace.read_text",
                        result = succeeded(),
                        providerCallId = "call_4"
                    )
                )
            )
        )
        val call = serialization.messages[2]
        assertEquals(AgentModelMessageKind.FUNCTION_CALL, call.kind)
        assertEquals("call_4", call.callId)
        assertEquals("workspace.read_text", call.toolName)
        assertEquals("{\"path\":\"inputs/a.txt\"}", call.content)
    }

    @Test
    fun aPromptedEnvelopeWithoutACallIdStaysText() {
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope(
                        """{"protocol":"mca.agent.v1","kind":"final","text":"done"}"""
                    )
                )
            )
        )
        assertEquals(AgentModelMessageKind.TEXT, serialization.messages[2].kind)
        assertTrue(serialization.messages[2].content.contains("mca.agent.v1"))
    }

    @Test
    fun aTruncatedLegacyObservationKeepsItsPayload() {
        val truncated = AgentJsonValues.obj(
            "truncated" to AgentJsonValue.Bool(true),
            "totalBytes" to AgentJsonValue.Num("40960"),
            "excerpt" to AgentJsonValue.Str("partial")
        )
        val serialization = serializer.serialize(
            context(
                listOf(
                    AgentTranscriptEntry.AssistantEnvelope(callRecord(), providerCallId = "call_1"),
                    AgentTranscriptEntry.ToolResult(
                        invocationId = "invocation-1",
                        callHash = "a".repeat(64),
                        toolKey = "workspace.read_text",
                        result = truncated,
                        providerCallId = "call_1"
                    )
                )
            )
        )
        val output = serialization.messages[3]
        assertEquals(AgentModelMessageKind.FUNCTION_OUTPUT, output.kind)
        assertTrue(output.content.contains("\"truncated\":true"))
        assertTrue(output.content.contains("partial"))
    }

    @Test
    fun aCallRecordWhoseIdDisagreesWithTheEntryFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            serializer.serialize(
                context(
                    listOf(
                        AgentTranscriptEntry.AssistantEnvelope(
                            rawEnvelope = callRecord(callId = "call_1"),
                            providerCallId = "call_2"
                        )
                    )
                )
            )
        }
    }

    @Test
    fun anOutputRecordWhoseIdDisagreesWithTheEntryFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            serializer.serialize(
                context(
                    listOf(
                        AgentTranscriptEntry.AssistantEnvelope(callRecord(), providerCallId = "call_1"),
                        AgentTranscriptEntry.ToolResult(
                            invocationId = "invocation-1",
                            callHash = "a".repeat(64),
                            toolKey = "workspace.read_text",
                            result = outputRecord(callId = "call_2"),
                            providerCallId = "call_1"
                        )
                    )
                )
            )
        }
    }

    @Test
    fun aMalformedV2ClaimFailsClosed() {
        val malformed = """{"record":"mca.agent.transcript.v2","schemaVersion":2,""" +
            """"kind":"assistant_function_call","tool":"workspace.read_text"}"""
        assertThrows(IllegalArgumentException::class.java) {
            serializer.serialize(
                context(
                    listOf(
                        AgentTranscriptEntry.AssistantEnvelope(malformed, providerCallId = "call_1")
                    )
                )
            )
        }
    }

    @Test
    fun aMismatchedOutputRecordKindFailsClosed() {
        val wrongKind = transcript.recordValue(
            AgentTranscriptV2Record.AssistantFunctionCall(
                callId = "call_1",
                toolKey = "workspace.read_text",
                arguments = AgentJsonValues.obj("path" to AgentJsonValue.Str("inputs/a.txt"))
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            serializer.serialize(
                context(
                    listOf(
                        AgentTranscriptEntry.AssistantEnvelope(callRecord(), providerCallId = "call_1"),
                        AgentTranscriptEntry.ToolResult(
                            invocationId = "invocation-1",
                            callHash = "a".repeat(64),
                            toolKey = "workspace.read_text",
                            result = wrongKind,
                            providerCallId = "call_1"
                        )
                    )
                )
            )
        }
    }

    @Test
    fun anOutputWithoutItsCallFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            serializer.serialize(
                context(
                    listOf(
                        AgentTranscriptEntry.ToolResult(
                            invocationId = "invocation-1",
                            callHash = "a".repeat(64),
                            toolKey = "workspace.read_text",
                            result = outputRecord(),
                            providerCallId = "call_1"
                        )
                    )
                )
            )
        }
    }
}
