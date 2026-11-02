package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Park-token transcript codec: all four entry variants round-trip losslessly, the encoding is
 * deterministic, number literals stay verbatim, and every malformed/unknown/oversized document is
 * rejected with [AgentParkTranscriptRejectedException].
 */
class AgentParkTranscriptCodecTest {

    @Test
    fun allFourEntryVariantsRoundTripLosslessly() {
        val nestedResult = AgentJsonValues.obj(
            "status" to AgentJsonValue.Str("SUCCEEDED"),
            "content" to AgentJsonValues.obj(
                "lines" to AgentJsonValues.arr(
                    AgentJsonValue.Str("alpha\n\"beta\""),
                    AgentJsonValue.Num("1.50"),
                    AgentJsonValue.Bool(true),
                    AgentJsonValue.Null
                ),
                "count" to AgentJsonValue.Num("2"),
                "empty" to AgentJsonValues.obj()
            )
        )
        val transcript = AgentLoopTranscript(
            entries = listOf(
                AgentTranscriptEntry.AssistantEnvelope(
                    rawEnvelope = "{\"protocol\":\"mca.agent.v1\",\"kind\":\"tool\"}",
                    providerCallId = "call_1"
                ),
                AgentTranscriptEntry.ToolResult(
                    invocationId = "invocation-1",
                    callHash = "a".repeat(64),
                    toolKey = "workspace.read_text",
                    result = nestedResult,
                    providerCallId = "call_1"
                ),
                AgentTranscriptEntry.UserAnswer(
                    questionId = "question_1",
                    text = "Use option B"
                ),
                AgentTranscriptEntry.ProtocolError(
                    errorCode = "MODEL_PROTOCOL_ERROR",
                    schemaHintJson = "{\"expected\":\"one envelope\"}"
                ),
                AgentTranscriptEntry.AssistantEnvelope(
                    rawEnvelope = "plain assistant text",
                    providerCallId = null
                ),
                AgentTranscriptEntry.ToolResult(
                    invocationId = "invocation-2",
                    callHash = "b".repeat(64),
                    toolKey = "device.status",
                    result = AgentJsonValues.obj("ok" to AgentJsonValue.Bool(false)),
                    providerCallId = null
                )
            ),
            nextStepIndex = 4,
            repairsUsed = 2
        )

        val encoded = AgentParkTranscriptCodec.encode(transcript)

        assertEquals(transcript, AgentParkTranscriptCodec.decode(encoded))
        assertTrue(encoded.contains("\"providerCallId\":null"))
        assertTrue(encoded.contains("\"providerCallId\":\"call_1\""))
    }

    @Test
    fun encodingIsDeterministic() {
        val transcript = AgentLoopTranscript(
            entries = listOf(
                AgentTranscriptEntry.UserAnswer("question_1", "answer"),
                AgentTranscriptEntry.ProtocolError("MODEL_PROTOCOL_ERROR", "{}")
            ),
            nextStepIndex = 2,
            repairsUsed = 1
        )

        val first = AgentParkTranscriptCodec.encode(transcript)
        val second = AgentParkTranscriptCodec.encode(transcript)

        assertEquals(first, second)
        assertEquals(first, AgentParkTranscriptCodec.encode(AgentParkTranscriptCodec.decode(first)))
    }

    @Test
    fun numberLiteralsArePreservedExactly() {
        val transcript = AgentLoopTranscript(
            entries = listOf(
                AgentTranscriptEntry.ToolResult(
                    invocationId = "invocation-1",
                    callHash = "c".repeat(64),
                    toolKey = "pricing.lookup",
                    result = AgentJsonValues.obj(
                        "price" to AgentJsonValue.Num("1.50"),
                        "ratio" to AgentJsonValue.Num("0.001"),
                        "count" to AgentJsonValue.Num("2")
                    ),
                    providerCallId = null
                )
            ),
            nextStepIndex = 1,
            repairsUsed = 0
        )

        val encoded = AgentParkTranscriptCodec.encode(transcript)
        val decoded = AgentParkTranscriptCodec.decode(encoded)
        val value = (decoded.entries.single() as AgentTranscriptEntry.ToolResult).result
            as AgentJsonValue.Obj

        assertTrue(encoded.contains("\"price\":1.50"))
        assertEquals(AgentJsonValue.Num("1.50"), field(value, "price"))
        assertEquals(AgentJsonValue.Num("0.001"), field(value, "ratio"))
        assertEquals(transcript, decoded)
    }

    @Test
    fun emptyTranscriptAtZeroCountersIsAccepted() {
        val encoded = """{"entries":[],"nextStepIndex":0,"repairsUsed":0}"""

        assertEquals(AgentLoopTranscript(emptyList(), 0, 0), AgentParkTranscriptCodec.decode(encoded))
    }

    @Test
    fun unknownEntryTypesAreRejected() {
        assertRejected(
            """{"entries":[{"type":"mystery","value":1}],"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"TOOL_RESULT","invocationId":"i","callHash":"h","toolKey":"t",""" +
                """"result":{},"providerCallId":null}],"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"invocationId":"i","callHash":"h","toolKey":"t","result":{},""" +
                """"providerCallId":null}],"nextStepIndex":1,"repairsUsed":0}"""
        )
    }

    @Test
    fun missingExtraAndDuplicateFieldsAreRejected() {
        assertRejected("""{"entries":[],"nextStepIndex":0}""")
        assertRejected("""{"entries":[],"nextStepIndex":0,"repairsUsed":0,"extra":true}""")
        assertRejected(
            """{"entries":[{"type":"user_answer","questionId":"q"}],"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"assistant_envelope","rawEnvelope":"x"}],""" +
                """"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"protocol_error","errorCode":"E","schemaHintJson":"{}",""" +
                """"extra":1}],"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"user_answer","questionId":"q","questionId":"r","text":"t"}],""" +
                """"nextStepIndex":1,"repairsUsed":0}"""
        )
    }

    @Test
    fun wrongJsonTypesAreRejected() {
        assertRejected("""[]""")
        assertRejected("""null""")
        assertRejected("""{"entries":{},"nextStepIndex":1,"repairsUsed":0}""")
        assertRejected(
            """{"entries":[{"type":"user_answer","questionId":"q","text":42}],""" +
                """"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"user_answer","questionId":"q","text":null}],""" +
                """"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"assistant_envelope","rawEnvelope":null,""" +
                """"providerCallId":null}],"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"assistant_envelope","rawEnvelope":"x",""" +
                """"providerCallId":7}],"nextStepIndex":1,"repairsUsed":0}"""
        )
        assertRejected(
            """{"entries":[{"type":"tool_result","invocationId":"i","callHash":"h","toolKey":"t",""" +
                """"providerCallId":null}],"nextStepIndex":1,"repairsUsed":0}"""
        )
    }

    @Test
    fun negativeOrNonIntegerCountersAreRejected() {
        assertRejected("""{"entries":[],"nextStepIndex":-1,"repairsUsed":0}""")
        assertRejected("""{"entries":[],"nextStepIndex":0,"repairsUsed":-2}""")
        assertRejected("""{"entries":[],"nextStepIndex":1.5,"repairsUsed":0}""")
        assertRejected("""{"entries":[],"nextStepIndex":1e3,"repairsUsed":0}""")
        assertRejected(
            """{"entries":[],"nextStepIndex":"1","repairsUsed":0}"""
        )
    }

    @Test
    fun oversizedTranscriptsAreRejected() {
        val oversizedDocument = "{\"entries\":[{\"type\":\"assistant_envelope\"," +
            "\"rawEnvelope\":\"" + "a".repeat(AgentParkTranscriptCodec.MAX_TRANSCRIPT_CHARS) +
            "\",\"providerCallId\":null}],\"nextStepIndex\":1,\"repairsUsed\":0}"
        assertTrue(oversizedDocument.length > AgentParkTranscriptCodec.MAX_TRANSCRIPT_CHARS)
        assertRejected(oversizedDocument)

        val oversizedTranscript = AgentLoopTranscript(
            entries = listOf(
                AgentTranscriptEntry.AssistantEnvelope(
                    rawEnvelope = "a".repeat(AgentParkTranscriptCodec.MAX_TRANSCRIPT_CHARS)
                )
            ),
            nextStepIndex = 1,
            repairsUsed = 0
        )
        assertThrows(AgentParkTranscriptRejectedException::class.java) {
            AgentParkTranscriptCodec.encode(oversizedTranscript)
        }
    }

    @Test
    fun transcriptLargerThanTheControlResponseBudgetRoundTrips() {
        val transcript = AgentLoopTranscript(
            entries = listOf(
                AgentTranscriptEntry.AssistantEnvelope(rawEnvelope = "x".repeat(80 * 1024)),
                AgentTranscriptEntry.UserAnswer(questionId = "question_1", text = "y".repeat(10_000))
            ),
            nextStepIndex = 2,
            repairsUsed = 0
        )

        val encoded = AgentParkTranscriptCodec.encode(transcript)

        assertTrue(encoded.length > AgentContract.MAX_CONTROL_RESPONSE_BYTES)
        assertEquals(transcript, AgentParkTranscriptCodec.decode(encoded))
    }

    private fun assertRejected(encoded: String) {
        assertThrows(AgentParkTranscriptRejectedException::class.java) {
            AgentParkTranscriptCodec.decode(encoded)
        }
    }

    private fun field(value: AgentJsonValue.Obj, name: String): AgentJsonValue =
        value.fields.first { it.first == name }.second
}
