package com.muyuchat.core.agent.runtime.context

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** K03 context assembly tests: pairing, 16 KiB excerpts, no silent history loss, CONTEXT_LIMIT. */
class AgentContextAssemblerTest {

    private val codec = StrictAgentJsonCodec()
    private val assembler = AgentContextAssembler(AgentPromptedTranscriptSerializer(codec), codec = codec)
    private val callHash = "a".repeat(64)

    @Test
    fun keepsFullPairedTranscriptAndOnlyAddsSystemAndGoal() {
        val entries = listOf(
            AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
            AgentTranscriptEntry.ToolResult(
                "invocation-1",
                callHash,
                "workspace.read_text",
                AgentJsonValue.Obj(listOf("content" to AgentJsonValue.Str("short")))
            ),
            AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.askUserEnvelope()),
            AgentTranscriptEntry.UserAnswer("question-1", "开发者"),
            AgentTranscriptEntry.ProtocolError("PROTOCOL_UNKNOWN_FIELD", """{"errorCode":"X"}""")
        )
        val result = assembler.assemble(
            AgentContextAssemblyRequest(
                systemPrompt = "SYSTEM",
                userGoal = "goal",
                entries = entries,
                contextWindowTokens = 8_192,
                reservedOutputTokens = 2_048
            )
        )
        assertTrue(result is AgentContextAssemblyResult.Ready)
        result as AgentContextAssemblyResult.Ready
        assertEquals(entries.size + 2, result.serialization.messages.size)
        assertEquals(0, result.truncatedToolResults)
        assertEquals(entries, result.context.entries)
        assertTrue(result.estimatedTokens > 0)
    }

    @Test
    fun oversizedToolResultIsReplacedWithBoundedExcerptAndArtifactRefs() {
        val original = AgentJsonValue.Obj(
            listOf(
                "content" to AgentJsonValue.Str("x".repeat(20_000)),
                "artifactIds" to AgentJsonValue.Arr(
                    listOf(AgentJsonValue.Str("artifact-1"), AgentJsonValue.Str("artifact-2"))
                )
            )
        )
        val entries = listOf(
            AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
            AgentTranscriptEntry.ToolResult("invocation-1", callHash, "workspace.read_text", original)
        )
        val ready = ready(entries, window = 200_000)
        assertEquals(1, ready.truncatedToolResults)
        val truncated = ready.context.entries[1] as AgentTranscriptEntry.ToolResult
        val fields = (truncated.result as AgentJsonValue.Obj).fields.toMap()
        assertEquals(true, (fields["truncated"] as AgentJsonValue.Bool).value)
        assertTrue((fields["totalBytes"] as AgentJsonValue.Num).literal.toInt() > 16 * 1024)
        assertEquals(64, (fields["fullResultSha256"] as AgentJsonValue.Str).value.length)
        val refs = (fields["artifactRefs"] as AgentJsonValue.Arr).items
            .map { (it as AgentJsonValue.Str).value }
        assertEquals(listOf("artifact-1", "artifact-2"), refs)
        assertTrue((fields["excerpt"] as AgentJsonValue.Str).value.isNotEmpty())
        val serializedResult = codec.write(AgentJsonValuesCanonical.canonical(truncated.result))
        assertTrue(
            "truncated result must stay within the 16 KiB to-model budget",
            serializedResult.toByteArray(Charsets.UTF_8).size <= 16 * 1024
        )
        val toolObservation = ready.serialization.messages
            .single { it.content.contains("\"record\":\"tool_result\"") }
        assertTrue(toolObservation.content.contains("\"truncated\":true"))
    }

    @Test
    fun exactlyAtTheBudgetIsNotTruncated() {
        val result = AgentJsonValue.Str("x".repeat(16 * 1024 - 2))
        assertEquals(16 * 1024, codec.write(result).toByteArray(Charsets.UTF_8).size)
        val ready = ready(
            listOf(
                AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
                AgentTranscriptEntry.ToolResult("invocation-1", callHash, "workspace.read_text", result)
            ),
            window = 200_000
        )
        assertEquals(0, ready.truncatedToolResults)
        assertEquals(
            result,
            (ready.context.entries[1] as AgentTranscriptEntry.ToolResult).result
        )
    }

    @Test
    fun chineseExcerptIsCutOnACharacterBoundary() {
        val original = AgentJsonValue.Obj(
            listOf("content" to AgentJsonValue.Str("中".repeat(6_000)))
        )
        val ready = ready(
            listOf(
                AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
                AgentTranscriptEntry.ToolResult("invocation-1", callHash, "workspace.read_text", original)
            ),
            window = 200_000
        )
        val fields = (
            (ready.context.entries[1] as AgentTranscriptEntry.ToolResult).result as AgentJsonValue.Obj
            ).fields.toMap()
        val excerpt = (fields["excerpt"] as AgentJsonValue.Str).value
        assertTrue(excerpt.isNotEmpty())
        assertTrue(!excerpt.contains('\uFFFD'))
        assertTrue(codec.parse(codec.write(AgentJsonValue.Str(excerpt))) is AgentJsonParseResult.Parsed)
    }

    @Test
    fun unpairedHistoryIsRejectedInsteadOfSilentlyDropped() {
        val orphanResult = AgentContextAssemblyRequest(
            systemPrompt = "SYSTEM",
            userGoal = "goal",
            entries = listOf(
                AgentTranscriptEntry.ToolResult("invocation-1", callHash, "workspace.read_text", AgentJsonValue.Null)
            ),
            contextWindowTokens = 8_192,
            reservedOutputTokens = 0
        )
        assertInvalid(assembler.assemble(orphanResult), AgentErrorCodes.INTERNAL_INVARIANT)

        val danglingCall = AgentContextAssemblyRequest(
            systemPrompt = "SYSTEM",
            userGoal = "goal",
            entries = listOf(AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope())),
            contextWindowTokens = 8_192,
            reservedOutputTokens = 0
        )
        assertInvalid(assembler.assemble(danglingCall), AgentErrorCodes.INTERNAL_INVARIANT)

        val doubleAssistant = AgentContextAssemblyRequest(
            systemPrompt = "SYSTEM",
            userGoal = "goal",
            entries = listOf(
                AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
                AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.finalEnvelope())
            ),
            contextWindowTokens = 8_192,
            reservedOutputTokens = 0
        )
        assertInvalid(assembler.assemble(doubleAssistant), AgentErrorCodes.INTERNAL_INVARIANT)

        val duplicateInvocation = AgentContextAssemblyRequest(
            systemPrompt = "SYSTEM",
            userGoal = "goal",
            entries = listOf(
                AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
                AgentTranscriptEntry.ToolResult("invocation-1", callHash, "workspace.read_text", AgentJsonValue.Null),
                AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope()),
                AgentTranscriptEntry.ToolResult("invocation-1", callHash, "workspace.read_text", AgentJsonValue.Null)
            ),
            contextWindowTokens = 8_192,
            reservedOutputTokens = 0
        )
        assertInvalid(assembler.assemble(duplicateInvocation), AgentErrorCodes.INTERNAL_INVARIANT)
    }

    @Test
    fun contextOverflowPausesWithContextLimitAndNeverCompresses() {
        val entries = (1..8).map { index ->
            AgentTranscriptEntry.ProtocolError("PROTOCOL_UNKNOWN_FIELD", "{\"n\":\"$index\"}")
        }
        val overflow = assembler.assemble(
            AgentContextAssemblyRequest(
                systemPrompt = "SYSTEM",
                userGoal = "goal",
                entries = entries,
                contextWindowTokens = 20,
                reservedOutputTokens = 10
            )
        )
        assertTrue(overflow is AgentContextAssemblyResult.Overflow)
        overflow as AgentContextAssemblyResult.Overflow
        assertEquals(AgentErrorCodes.MODEL_CONTEXT_LIMIT, overflow.error.code)
        assertTrue(overflow.estimatedTokens > 20)
    }

    @Test
    fun reservedOutputLargerThanWindowOverflows() {
        val overflow = assembler.assemble(
            AgentContextAssemblyRequest(
                systemPrompt = "SYSTEM",
                userGoal = "goal",
                entries = emptyList(),
                contextWindowTokens = 100,
                reservedOutputTokens = 100
            )
        )
        assertTrue(overflow is AgentContextAssemblyResult.Overflow)
    }

    private fun ready(entries: List<AgentTranscriptEntry>, window: Int): AgentContextAssemblyResult.Ready {
        val result = assembler.assemble(
            AgentContextAssemblyRequest(
                systemPrompt = "SYSTEM",
                userGoal = "goal",
                entries = entries,
                contextWindowTokens = window,
                reservedOutputTokens = 0
            )
        )
        if (result is AgentContextAssemblyResult.Overflow) {
            fail("unexpected overflow at window $window")
        }
        if (result is AgentContextAssemblyResult.InvalidHistory) {
            fail("unexpected invalid history: ${result.safeMessage}")
        }
        return result as AgentContextAssemblyResult.Ready
    }

    private fun assertInvalid(result: AgentContextAssemblyResult, code: String) {
        assertTrue("expected InvalidHistory, got $result", result is AgentContextAssemblyResult.InvalidHistory)
        assertEquals(code, (result as AgentContextAssemblyResult.InvalidHistory).error.code)
    }

    private object AgentJsonValuesCanonical {
        fun canonical(value: AgentJsonValue): AgentJsonValue =
            com.muyuchat.core.agent.runtime.protocol.AgentJsonValues.canonical(value)
    }
}
