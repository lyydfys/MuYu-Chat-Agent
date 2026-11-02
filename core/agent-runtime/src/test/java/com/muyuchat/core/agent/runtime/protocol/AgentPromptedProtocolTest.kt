package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentDecodeResult
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K02 strict protocol tests: envelope, JSON codec, repair hint and zero-executor guarantees.
 * P02-P09/P12/P13 decoder/codec portions at the memory level.
 */
class AgentPromptedProtocolTest {

    private val validator = SupportedToolSchemaValidator()
    private val decoder = AgentTestFixtures.decoder(validator)
    private val codec = StrictAgentJsonCodec()

    @Test
    fun decodesAllThreeLegalEnvelopes() {
        val tool = accepted(AgentTestFixtures.toolEnvelope())
        assertTrue(tool is AgentControlMessage.ToolCall)
        tool as AgentControlMessage.ToolCall
        assertEquals("workspace.read_text", tool.name)
        assertEquals("""{"path":"inputs/brief.txt"}""", tool.argumentsJson)

        val final = accepted(AgentTestFixtures.finalEnvelope("已整理完成。", listOf("artifact-1")))
        assertTrue(final is AgentControlMessage.Final)
        final as AgentControlMessage.Final
        assertEquals("已整理完成。", final.text)
        assertEquals(listOf("artifact-1"), final.artifactIds)

        val ask = accepted(AgentTestFixtures.askUserEnvelope())
        assertTrue(ask is AgentControlMessage.AskUser)
        ask as AgentControlMessage.AskUser
        assertEquals("报告需要面向哪类读者？", ask.question)
        assertEquals(listOf("开发者", "管理者"), ask.options)
    }

    @Test
    fun acceptsChineseUnicodeEmptyArtifactsAndJsonLikeFinalText() {
        val unicode = accepted(AgentTestFixtures.finalEnvelope("已完成：整理报告 ✅ — 12 files", emptyList()))
        assertEquals("已完成：整理报告 ✅ — 12 files", (unicode as AgentControlMessage.Final).text)

        val jsonLike = accepted(
            AgentTestFixtures.finalEnvelope("""{"summary":"done","count":12}""", emptyList())
        )
        assertEquals("""{"summary":"done","count":12}""", (jsonLike as AgentControlMessage.Final).text)

        val emptyWithArtifact = accepted(AgentTestFixtures.finalEnvelope("", listOf("artifact-1")))
        assertEquals("", (emptyWithArtifact as AgentControlMessage.Final).text)
    }

    @Test
    fun decodesLegalJsonEscapes() {
        val raw = """
            {"protocol":"mca.agent.v1","kind":"final","text":"line1\nline2\t\u4e2d\u6587 \"quoted\" \\backslash","artifactIds":[]}
        """.trimIndent()
        val message = accepted(raw) as AgentControlMessage.Final
        assertEquals("line1\nline2\t中文 \"quoted\" \\backslash", message.text)
    }

    @Test
    fun canonicalArgumentsAreStableSortedJson() {
        val envelope = AgentTestFixtures.toolEnvelope(
            name = "workspace.list",
            argumentsJson = """{"limit":25,"path":"inputs/","cursor":null}"""
        )
        val call = accepted(envelope) as AgentControlMessage.ToolCall
        assertEquals("""{"cursor":null,"limit":25,"path":"inputs/"}""", call.argumentsJson)
        val hash = AgentHashes.sha256HexUtf8(call.argumentsJson)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun rejectsEverySyntaxViolationWithTypedCodeAndZeroExecutions() {
        var executions = 0
        fun reject(text: String, expectedCode: String): AgentDecodeResult.Rejected {
            val result = decoder.decode(text)
            assertTrue(
                "expected rejection for ${text.take(80)}, got $result",
                result is AgentDecodeResult.Rejected
            )
            result as AgentDecodeResult.Rejected
            assertEquals("unexpected code for ${text.take(80)}: ${result.safeMessage}", expectedCode, result.errorCode)
            assertRepairHint(result)
            return result
        }

        reject("   ", AgentProtocolErrorCodes.EMPTY)
        reject("", AgentProtocolErrorCodes.EMPTY)
        reject("```json\n" + AgentTestFixtures.finalEnvelope() + "\n```", AgentProtocolErrorCodes.MARKDOWN_FENCE)
        reject("Here is the answer:\n" + AgentTestFixtures.finalEnvelope(), AgentProtocolErrorCodes.INVALID_JSON)
        reject(AgentTestFixtures.finalEnvelope() + "\nThat is all.", AgentProtocolErrorCodes.INVALID_JSON)
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"x\",\"text\":\"y\"}",
            AgentProtocolErrorCodes.DUPLICATE_KEY
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"x\",\"extra\":1}",
            AgentProtocolErrorCodes.UNKNOWN_FIELD
        )
        reject("[{\"protocol\":\"mca.agent.v1\"}]", AgentProtocolErrorCodes.NOT_OBJECT)
        reject("{\"kind\":\"final\",\"text\":\"x\"}", AgentProtocolErrorCodes.MISSING_FIELD)
        reject("{\"protocol\":\"mca.agent.v1\",\"text\":\"x\"}", AgentProtocolErrorCodes.MISSING_FIELD)
        reject(
            "{\"protocol\":\"mca.agent.v2\",\"kind\":\"final\",\"text\":\"x\"}",
            AgentProtocolErrorCodes.WRONG_PROTOCOL
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"plan\",\"text\":\"x\"}",
            AgentProtocolErrorCodes.UNKNOWN_KIND
        )
        reject(
            AgentTestFixtures.toolEnvelope(name = "workspace.delete", argumentsJson = "{}"),
            AgentProtocolErrorCodes.TOOL_NOT_FOUND
        )
        reject(
            AgentTestFixtures.toolEnvelope(argumentsJson = "[1,2]"),
            AgentProtocolErrorCodes.TOOL_ARGUMENTS_NOT_OBJECT
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"tool\",\"call\":{\"name\":\"workspace.list\"}}",
            AgentProtocolErrorCodes.MISSING_FIELD
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"tool\",\"call\":{\"arguments\":{}}}",
            AgentProtocolErrorCodes.MISSING_FIELD
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"tool\",\"call\":{\"name\":\"workspace.list\",\"arguments\":{},\"x\":1}}",
            AgentProtocolErrorCodes.UNKNOWN_FIELD
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"\"}",
            AgentProtocolErrorCodes.FINAL_EMPTY
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":null}",
            AgentProtocolErrorCodes.FIELD_TYPE
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"ask_user\",\"question\":\"   \"}",
            AgentProtocolErrorCodes.QUESTION_EMPTY
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"ask_user\",\"question\":\"q\",\"options\":[]}",
            AgentProtocolErrorCodes.OPTIONS_INVALID
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"ask_user\",\"question\":\"q\",\"options\":null}",
            AgentProtocolErrorCodes.OPTIONS_INVALID
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"ask_user\",\"question\":\"q\",\"options\":[\"a\",\"b\",\"c\",\"d\"]}",
            AgentProtocolErrorCodes.OPTIONS_INVALID
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"ask_user\",\"question\":\"q\",\"options\":[\"" + "x".repeat(129) + "\"]}",
            AgentProtocolErrorCodes.OPTIONS_INVALID
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"tool\",\"call\":{\"name\":\"workspace.list\",\"arguments\":{}},\"calls\":[{}]}",
            AgentProtocolErrorCodes.UNKNOWN_FIELD
        )
        reject(
            AgentTestFixtures.finalEnvelope().dropLast(1),
            AgentProtocolErrorCodes.TRUNCATED
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"ok\"} // trailing comment",
            AgentProtocolErrorCodes.INVALID_JSON
        )
        reject("{'protocol':'mca.agent.v1','kind':'final','text':'x'}", AgentProtocolErrorCodes.INVALID_JSON)
        reject("{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"x\",}", AgentProtocolErrorCodes.INVALID_JSON)
        reject("{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"x\",\"n\":NaN}", AgentProtocolErrorCodes.INVALID_JSON)
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"x\",\"n\":Infinity}",
            AgentProtocolErrorCodes.INVALID_JSON
        )
        reject(
            "{\"protocol\":\"mca.agent.v1\",\"kind\":\"final\",\"text\":\"\\uZZZZ\"}",
            AgentProtocolErrorCodes.INVALID_JSON
        )
        reject(
            "\uD800" + AgentTestFixtures.finalEnvelope(),
            AgentProtocolErrorCodes.UTF8_INVALID
        )
        reject(
            "\uFEFF" + AgentTestFixtures.finalEnvelope(),
            AgentProtocolErrorCodes.INVALID_JSON
        )
        reject(
            AgentTestFixtures.finalEnvelope() + AgentTestFixtures.finalEnvelope(),
            AgentProtocolErrorCodes.MULTIPLE_ROOTS
        )
        reject(
            "thought: {\"protocol\":\"mca.agent.v1\",\"kind\":\"tool\",\"call\":{\"name\":\"workspace.list\",\"arguments\":{}}}\nfinal: done",
            AgentProtocolErrorCodes.INVALID_JSON
        )
        reject(
            AgentTestFixtures.finalEnvelope("x".repeat(70_000), emptyList()),
            AgentProtocolErrorCodes.TOO_LARGE
        )
        reject(
            AgentTestFixtures.finalEnvelope("x".repeat(33_000), emptyList()),
            AgentProtocolErrorCodes.FINAL_TEXT_TOO_LARGE
        )
        assertEquals(0, executions)
        // Guard: an accepted tool call is the only branch that would increment an executor count.
        assertEquals(0, countExecutions(AgentTestFixtures.finalEnvelope()))
        assertEquals(1, countExecutions(AgentTestFixtures.toolEnvelope()))
    }

    @Test
    fun rejectsDepthBeyondSixteen() {
        fun nestedEnvelope(depth: Int): String =
            AgentTestFixtures.toolEnvelope(
                name = "workspace.list",
                argumentsJson = "{\"path\":\"inputs/\"}"
            ).let { envelope ->
                // Replace the arguments object with a deeply nested array value.
                envelope.replace(
                    "\"arguments\":{\"path\":\"inputs/\"}",
                    "\"arguments\":{\"nested\":" + "[".repeat(depth) + "]".repeat(depth) + "}"
                )
            }

        val allowed = decoder.decode(nestedEnvelope(13))
        assertTrue("13 nested arrays plus object envelopes stay within depth 16: $allowed", allowed is AgentDecodeResult.Accepted)
        val rejected = decoder.decode(nestedEnvelope(14))
        assertTrue(rejected is AgentDecodeResult.Rejected)
        assertEquals(AgentProtocolErrorCodes.DEPTH_EXCEEDED, (rejected as AgentDecodeResult.Rejected).errorCode)
    }

    @Test
    fun rejectsFinalLargerThanArtifactAndQuestionBudgets() {
        val ids = (1..21).map { "artifact-$it" }
        val tooMany = decoder.decode(AgentTestFixtures.finalEnvelope("ok", ids))
        assertTrue(tooMany is AgentDecodeResult.Rejected)
        assertEquals(AgentProtocolErrorCodes.ARTIFACT_LIMIT, (tooMany as AgentDecodeResult.Rejected).errorCode)

        val duplicates = decoder.decode(AgentTestFixtures.finalEnvelope("ok", listOf("artifact-1", "artifact-1")))
        assertEquals(
            AgentProtocolErrorCodes.ARTIFACT_INVALID,
            (duplicates as AgentDecodeResult.Rejected).errorCode
        )

        val invalidId = decoder.decode(AgentTestFixtures.finalEnvelope("ok", listOf("报告")))
        assertEquals(
            AgentProtocolErrorCodes.ARTIFACT_INVALID,
            (invalidId as AgentDecodeResult.Rejected).errorCode
        )

        val bigQuestion = decoder.decode(AgentTestFixtures.askUserEnvelope("x".repeat(5000)))
        assertEquals(
            AgentProtocolErrorCodes.QUESTION_TOO_LARGE,
            (bigQuestion as AgentDecodeResult.Rejected).errorCode
        )
    }

    @Test
    fun codecRejectsInvalidJsonWithoutLenientFallback() {
        assertCodecFailure("", AgentProtocolErrorCodes.EMPTY)
        assertCodecFailure("  \n\t", AgentProtocolErrorCodes.EMPTY)
        assertCodecFailure("{\"a\":1} {\"b\":2}", AgentProtocolErrorCodes.MULTIPLE_ROOTS)
        assertCodecFailure("{\"a\":1} trailing", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":1,\"a\":2}", AgentProtocolErrorCodes.DUPLICATE_KEY)
        assertCodecFailure("{/*c*/\"a\":1}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{'a':1}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":1,}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":NaN}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":Infinity}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":01}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":+1}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":.5}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("{\"a\":1.}", AgentProtocolErrorCodes.INVALID_JSON)
        assertCodecFailure("[".repeat(17) + "]".repeat(17), AgentProtocolErrorCodes.DEPTH_EXCEEDED)
        assertCodecFailure("{\"a\":1", AgentProtocolErrorCodes.TRUNCATED)
        assertCodecFailure("\uD800", AgentProtocolErrorCodes.UTF8_INVALID)
    }

    @Test
    fun codecRoundTripsEveryAstShapeAndValidatesNumbers() {
        val value = AgentJsonValue.Obj(
            listOf(
                "s" to AgentJsonValue.Str("中文 \"quoted\" \n line"),
                "i" to AgentJsonValue.Num("-12"),
                "f" to AgentJsonValue.Num("1.5e-3"),
                "b" to AgentJsonValue.Bool(true),
                "n" to AgentJsonValue.Null,
                "a" to AgentJsonValue.Arr(
                    listOf(AgentJsonValue.Str("x"), AgentJsonValue.Num("0"))
                )
            )
        )
        val encoded = codec.write(value)
        val parsed = codec.parse(encoded)
        assertTrue(parsed is AgentJsonParseResult.Parsed)
        assertEquals(value, (parsed as AgentJsonParseResult.Parsed).value)
        val canonical = codec.write(AgentJsonValues.canonical(value))
        assertTrue(canonical.startsWith("{\"a\":"))
        assertTrue(canonical.indexOf("\"b\"") < canonical.indexOf("\"f\""))
        assertTrue(codec.parse(codec.write(AgentJsonValue.Arr(emptyList()))) is AgentJsonParseResult.Parsed)
        val invalidNumber = runCatching { codec.write(AgentJsonValue.Num("01")) }
        assertTrue(invalidNumber.isFailure)
        val duplicateObject = runCatching {
            codec.write(
                AgentJsonValue.Obj(
                    listOf("a" to AgentJsonValue.Num("1"), "a" to AgentJsonValue.Num("2"))
                )
            )
        }
        assertTrue(duplicateObject.isFailure)
    }

    @Test
    fun repairHintIsMachineReadableAndBounded() {
        val rejected = decoder.decode("not json") as AgentDecodeResult.Rejected
        val parsed = codec.parse(rejected.repairHintJson)
        assertTrue(parsed is AgentJsonParseResult.Parsed)
        val hint = (parsed as AgentJsonParseResult.Parsed).value as AgentJsonValue.Obj
        val fields = hint.fields.toMap()
        assertEquals(
            AgentProtocolErrorCodes.INVALID_JSON,
            (fields["errorCode"] as AgentJsonValue.Str).value
        )
        assertEquals("mca.agent.v1", (fields["protocol"] as AgentJsonValue.Str).value)
        assertTrue(rejected.repairHintJson.toByteArray(Charsets.UTF_8).size <= AgentRepairHints.MAX_HINT_BYTES)
    }

    @Test
    fun visibleTextInsideReasoningOrToolResultIsNeverScannedByTheDecoder() {
        // The decoder only ever receives the visible assistant completion. A reasoning blob that
        // contains a perfectly valid envelope must remain an invalid visible response.
        val reasoningLike =
            "I should call the tool next.\n" +
                AgentTestFixtures.toolEnvelope() + "\nbut first let me explain."
        val result = decoder.decode(reasoningLike)
        assertTrue(result is AgentDecodeResult.Rejected)
        assertEquals(0, countExecutions(reasoningLike))
    }

    private fun accepted(text: String): AgentControlMessage {
        val result = decoder.decode(text)
        if (result is AgentDecodeResult.Rejected) {
            fail("expected accepted envelope but got ${result.errorCode}: ${result.safeMessage}")
        }
        return (result as AgentDecodeResult.Accepted).message
    }

    private fun countExecutions(text: String): Int {
        val result = decoder.decode(text)
        return if (result is AgentDecodeResult.Accepted &&
            result.message is AgentControlMessage.ToolCall
        ) {
            1
        } else {
            0
        }
    }

    private fun assertCodecFailure(text: String, expectedCode: String) {
        val result = codec.parse(text)
        assertTrue("expected failure for ${text.take(60)}, got $result", result is AgentJsonParseResult.Failed)
        assertEquals(expectedCode, (result as AgentJsonParseResult.Failed).errorCode)
    }

    private fun assertRepairHint(rejected: AgentDecodeResult.Rejected) {
        val parsed = codec.parse(rejected.repairHintJson)
        assertTrue("repair hint must be strict JSON: ${rejected.repairHintJson}", parsed is AgentJsonParseResult.Parsed)
        val hint = (parsed as AgentJsonParseResult.Parsed).value as AgentJsonValue.Obj
        assertEquals(
            rejected.errorCode,
            (hint.fields.toMap()["errorCode"] as AgentJsonValue.Str).value
        )
        assertFalse(rejected.safeMessage.isBlank())
        assertTrue(rejected.repairHintJson.toByteArray(Charsets.UTF_8).size <= AgentRepairHints.MAX_HINT_BYTES)
    }
}
