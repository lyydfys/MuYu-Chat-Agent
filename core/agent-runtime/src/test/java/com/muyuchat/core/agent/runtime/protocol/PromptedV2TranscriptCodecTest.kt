package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N05d: transcript schema v2 records are reversible, canonical and strict.
 *
 * Covered here: exact canonical JSON, round-trip, the one-way V1 bridge upcast, and every
 * fail-closed rejection (unknown marker/kind, missing/extra/ill-typed fields, unsupported version,
 * invalid call id, non-object arguments, prose, oversized input, legacy non-native V1 records).
 */
class PromptedV2TranscriptCodecTest {

    private val codec = AgentTranscriptV2Codec()
    private val json = StrictAgentJsonCodec()

    private fun arguments(vararg fields: Pair<String, AgentJsonValue>): AgentJsonValue =
        AgentJsonValues.obj(*fields)

    private fun call(
        callId: String = "call_1",
        toolKey: String = "workspace.read_text",
        arguments: AgentJsonValue = arguments("path" to AgentJsonValue.Str("inputs/a.txt"))
    ): AgentTranscriptV2Record.AssistantFunctionCall =
        AgentTranscriptV2Record.AssistantFunctionCall(callId, toolKey, arguments)

    private fun output(
        callId: String = "call_1",
        result: AgentJsonValue = AgentJsonValues.obj("status" to AgentJsonValue.Str("SUCCEEDED"))
    ): AgentTranscriptV2Record.FunctionCallOutput =
        AgentTranscriptV2Record.FunctionCallOutput(callId, result)

    private fun canonical(record: AgentTranscriptV2Record): AgentTranscriptV2Record = when (record) {
        is AgentTranscriptV2Record.AssistantFunctionCall ->
            record.copy(arguments = AgentJsonValues.canonical(record.arguments))
        is AgentTranscriptV2Record.FunctionCallOutput ->
            record.copy(result = AgentJsonValues.canonical(record.result))
    }

    /** A legacy V1 native bridge envelope, exactly as the earlier runtime recorded it. */
    private fun v1BridgeEnvelope(callId: String?, name: String = "workspace.read_text"): String {
        val fields = ArrayList<Pair<String, AgentJsonValue>>(4)
        fields += "protocol" to AgentJsonValue.Str("mca.agent.v1")
        fields += "kind" to AgentJsonValue.Str("tool")
        fields += "call" to AgentJsonValues.obj(
            "name" to AgentJsonValue.Str(name),
            "arguments" to arguments("path" to AgentJsonValue.Str("inputs/a.txt"))
        )
        callId?.let { fields += "callId" to AgentJsonValue.Str(it) }
        return json.write(AgentJsonValue.Obj(fields))
    }

    @Test
    fun assistantFunctionCallIsCanonicalAndRoundTripsExactly() {
        val record = call(
            arguments = arguments(
                "path" to AgentJsonValue.Str("inputs/a.txt"),
                "limit" to AgentJsonValue.Num("4096")
            )
        )
        val encoded = codec.encode(record)
        assertEquals(
            "{\"arguments\":{\"limit\":4096,\"path\":\"inputs/a.txt\"}," +
                "\"callId\":\"call_1\",\"kind\":\"assistant_function_call\"," +
                "\"record\":\"mca.agent.transcript.v2\",\"schemaVersion\":2," +
                "\"tool\":\"workspace.read_text\"}",
            encoded
        )
        val decoded = codec.decode(encoded)
        assertTrue(decoded is AgentTranscriptV2DecodeResult.Decoded)
        decoded as AgentTranscriptV2DecodeResult.Decoded
        assertEquals(AgentTranscriptV2Origin.V2, decoded.origin)
        assertEquals(canonical(record), decoded.record)
        assertEquals("encode(decode(encode(x))) must be stable", encoded, codec.encode(decoded.record))
    }

    @Test
    fun functionCallOutputIsCanonicalAndRoundTripsExactly() {
        val record = output(
            result = AgentJsonValues.obj(
                "status" to AgentJsonValue.Str("SUCCEEDED"),
                "data" to AgentJsonValues.obj("content" to AgentJsonValue.Str("hello"))
            )
        )
        val encoded = codec.encode(record)
        assertEquals(
            "{\"callId\":\"call_1\",\"kind\":\"user_function_call_output\"," +
                "\"record\":\"mca.agent.transcript.v2\"," +
                "\"result\":{\"data\":{\"content\":\"hello\"},\"status\":\"SUCCEEDED\"}," +
                "\"schemaVersion\":2}",
            encoded
        )
        val decoded = codec.decode(encoded)
        assertTrue(decoded is AgentTranscriptV2DecodeResult.Decoded)
        decoded as AgentTranscriptV2DecodeResult.Decoded
        assertEquals(canonical(record), decoded.record)
        assertEquals(encoded, codec.encode(decoded.record))
    }

    @Test
    fun aLegacyV1BridgeEnvelopeUpcastsToTheSameCallRecord() {
        val decoded = codec.decode(v1BridgeEnvelope("call_7"))
        assertTrue(decoded is AgentTranscriptV2DecodeResult.Decoded)
        decoded as AgentTranscriptV2DecodeResult.Decoded
        assertEquals(AgentTranscriptV2Origin.V1_UPCAST, decoded.origin)
        val record = decoded.record as AgentTranscriptV2Record.AssistantFunctionCall
        assertEquals("call_7", record.callId)
        assertEquals("workspace.read_text", record.toolKey)
        assertEquals(
            AgentJsonValues.obj("path" to AgentJsonValue.Str("inputs/a.txt")),
            record.arguments
        )
        // The upcast record is written in the new canonical shape, not the old envelope.
        assertTrue(codec.encode(record).startsWith("{\"arguments\":"))
    }

    @Test
    fun legacyV1RecordsWithoutAProviderCallIdAreNeverUpcast() {
        val finalEnvelope = """{"protocol":"mca.agent.v1","kind":"final","text":"done"}"""
        val finalRejected = codec.decode(finalEnvelope)
        assertTrue(finalRejected is AgentTranscriptV2DecodeResult.Rejected)
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_V1_NON_NATIVE,
            (finalRejected as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )

        val askUser = """{"protocol":"mca.agent.v1","kind":"ask_user","question":"q"}"""
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_V1_NON_NATIVE,
            (codec.decode(askUser) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun aV1BridgeEnvelopeWithoutCallIdCannotBecomeANativeRecord() {
        val rejected = codec.decode(v1BridgeEnvelope(callId = null))
        assertTrue(rejected is AgentTranscriptV2DecodeResult.Rejected)
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_V1_NON_NATIVE,
            (rejected as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun aV1ToolResultObservationIsReadableButNotANativeRecord() {
        val toolResult = json.write(
            AgentJsonValues.obj(
                "record" to AgentJsonValue.Str("tool_result"),
                "invocationId" to AgentJsonValue.Str("invocation-1"),
                "callHash" to AgentJsonValue.Str("a".repeat(64)),
                "tool" to AgentJsonValue.Str("workspace.read_text"),
                "result" to AgentJsonValues.obj("status" to AgentJsonValue.Str("SUCCEEDED"))
            )
        )
        val rejected = codec.decode(toolResult)
        assertTrue(rejected is AgentTranscriptV2DecodeResult.Rejected)
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
            (rejected as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun unknownKindsAndMarkersFailClosed() {
        val unknownKind = """{"record":"mca.agent.transcript.v2","schemaVersion":2,""" +
            """"kind":"assistant_function_call_v3","callId":"call_1"}"""
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_KIND,
            (codec.decode(unknownKind) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )

        val unknownMarker = """{"record":"mca.agent.transcript.v1","..."}"""
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
            (codec.decode(unknownMarker) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )

        val noMarker = """{"kind":"assistant_function_call","callId":"call_1"}"""
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
            (codec.decode(noMarker) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun missingRequiredFieldsFailClosed() {
        val valid = codec.encode(call())
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode(valid.replace("\"schemaVersion\":2,", "")) as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode(valid.replace("\"kind\":\"assistant_function_call\",", "")) as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode(valid.replace("\"callId\":\"call_1\",", "")) as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode(valid.replace(",\"tool\":\"workspace.read_text\"", "")) as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode(valid.replace("\"arguments\":{\"path\":\"inputs/a.txt\"},", "")) as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode(codec.encode(output()).replace(",\"result\":{\"status\":\"SUCCEEDED\"}", "")) as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun extraFieldsAndUnsupportedVersionsFailClosed() {
        val extra = codec.encode(call()).dropLast(1) + ",\"serverName\":\"x\"}"
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_FIELD,
            (codec.decode(extra) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        val version = codec.encode(call()).replace("\"schemaVersion\":2", "\"schemaVersion\":3")
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_VERSION_UNSUPPORTED,
            (codec.decode(version) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        val wrongType = codec.encode(call()).replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\"")
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_FIELD_TYPE,
            (codec.decode(wrongType) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun invalidCallIdsAndNonObjectArgumentsFailClosed() {
        val badId = codec.encode(call()).replace("\"call_1\"", "\"call 1\"")
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_CALL_ID_INVALID,
            (codec.decode(badId) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        val badArguments = codec.encode(call())
            .replace("{\"path\":\"inputs/a.txt\"}", "[1,2]")
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_ARGUMENTS_NOT_OBJECT,
            (codec.decode(badArguments) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentTranscriptV2Record.AssistantFunctionCall(
                callId = "call_1",
                toolKey = "workspace.read_text",
                arguments = AgentJsonValues.arr(AgentJsonValue.Str("x"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentTranscriptV2Record.FunctionCallOutput(
                callId = "not a valid id",
                result = AgentJsonValue.Str("x")
            )
        }
    }

    @Test
    fun prosePartialJsonAndOversizedInputFailClosed() {
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
            (codec.decode("I will read the file now.") as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
            (codec.decode("```json\n{}\n```") as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
            (codec.decode("{\"record\":\"mca.agent.transcript.v2\"") as
                AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            (codec.decode("") as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
        val oversized = codec.encode(
            output(result = AgentJsonValue.Str("x".repeat(AgentTranscriptV2Schema.MAX_RECORD_BYTES)))
        )
        assertEquals(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_TOO_LARGE,
            (codec.decode(oversized) as AgentTranscriptV2DecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun claimsV2RecognizesOnlyTheExactMarker() {
        assertTrue(codec.claimsV2(codec.encode(call())))
        assertFalse(codec.claimsV2(v1BridgeEnvelope("call_1")))
        assertFalse(codec.claimsV2("not json"))
        val parsed = json.parse(codec.encode(output())) as AgentJsonParseResult.Parsed
        assertTrue(codec.claimsV2(parsed.value))
    }
}
