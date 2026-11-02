package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.contract.DshToolChoice
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure DSH -> MCA mapping: message/role fidelity, no MCA persona/history injection, bounded
 * transcripts, honest tool modes and native-Responses trailing-tool-round handling.
 */
class DshTranscriptMapperTest {

    private val limits = DshAdapterLimits.Default

    @Test
    fun `local mapping keeps DSH roles and ordering and appends no MCA persona or history`() {
        val request = dshRequest(
            messages = listOf(
                systemMessage("DSH SYSTEM"),
                userMessage("first"),
                assistantMessage("answer"),
                userMessage("second")
            )
        )
        val mapped = DshTranscriptMapper.mapLocal(request, localModel(), limits)
        val messages = mapped.chatRequest.messages

        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), messages.map { it.role })
        assertEquals("DSH SYSTEM", messages[0].content)
        assertEquals(listOf("first", "answer", "second"), messages.drop(1).map { it.content })
        assertEquals(DshToolMode.NONE, mapped.toolMode)
        assertTrue(mapped.offeredToolNames.isEmpty())

        val joined = messages.joinToString("\n") { it.content }
        assertFalse(joined.contains("霂榆"))
        assertFalse(joined.contains("你是"))
        assertFalse(joined.contains("MCA"))

        val params = mapped.chatRequest.params
        assertEquals(4096, params.nCtx)
        assertEquals("", params.systemPrompt)
        assertEquals(ReasoningMode.OFF, params.reasoningMode)
        assertTrue(params.hideReasoning)
        assertNull(mapped.chatRequest.persistentSessionId)
        assertNull(mapped.chatRequest.persistentPrefixSystemPrompt)
        assertNull(mapped.chatRequest.persistentRoleId)
        assertEquals("", mapped.chatRequest.runtimeSystemContext)
        assertTrue(mapped.chatRequest.tools.isEmpty())
        assertTrue(mapped.chatRequest.toolExchanges.isEmpty())
        assertFalse(mapped.chatRequest.appendReasoningInstruction)
        val serializedSystem = JSONArray(mapped.chatRequest.messagesJson())
            .getJSONObject(0)
            .getString("content")
        assertFalse(serializedSystem.contains("请直接回答"))
    }

    @Test
    fun `local prompted calls keep an exact protocol block next to the user turn`() {
        val mapped = DshTranscriptMapper.mapLocal(
            dshRequest(
                messages = listOf(userMessage("run a command")),
                tools = listOf(toolDefinition("bash"))
            ),
            localModel(runtimeValuesJson = "{\"n_ctx\":16384}"),
            limits
        )
        val lastUser = mapped.chatRequest.messages.last { it.role == Role.USER }.content
        assertTrue(lastUser.contains("Tool protocol reminder"))
        assertTrue(lastUser.contains(DshPromptedToolProtocol.XML_START))
        assertTrue(lastUser.contains("{\"name\":\"bash\",\"arguments\":{\"command\":\"pwd\"}}"))
        assertFalse(lastUser.contains("<function=bash>"))

        val instruction = mapped.chatRequest.messages
            .map { it.content }
            .single { it.contains("Call rules:") }
        assertTrue(instruction.contains("Available tools"))
        assertTrue(instruction.contains("{\"name\":\"bash\",\"arguments\":{\"command\":\"pwd\"}}"))
        assertFalse(instruction.contains(DshPromptedToolProtocol.START_MARKER))

        val cloud = DshTranscriptMapper.mapCloudPrompted(
            dshRequest(
                messages = listOf(userMessage("run a command")),
                tools = listOf(toolDefinition("bash"))
            ),
            limits
        )
        val cloudLastUser = cloud.chatRequest.messages.last { it.role == Role.USER }.content
        assertFalse(cloudLastUser.contains("Tool protocol reminder"))
    }

    @Test
    fun `hermes-style ggufs get the function-parameter envelope`() {
        val mapped = DshTranscriptMapper.mapLocal(
            dshRequest(
                messages = listOf(userMessage("run a command")),
                tools = listOf(toolDefinition("bash"))
            ),
            localModel().copy(toolEnvelopeStyle = DshToolEnvelopeStyle.XML),
            limits
        )
        val lastUser = mapped.chatRequest.messages.last { it.role == Role.USER }.content
        assertTrue(lastUser.contains("<tool_call>"))
        assertTrue(lastUser.contains("<function=bash>"))
        assertTrue(lastUser.contains("<parameter=command>pwd</parameter>"))
        assertFalse(lastUser.contains("{\"name\":\"bash\""))

        val instruction = mapped.chatRequest.messages
            .map { it.content }
            .single { it.contains("Call rules:") }
        assertTrue(instruction.contains("<parameter=command>pwd</parameter>"))
        assertFalse(instruction.contains(DshPromptedToolProtocol.START_MARKER))
    }

    @Test
    fun `a degenerate one-token budget is raised only for prompted tool calls`() {
        val request = dshRequest(
            messages = listOf(userMessage("run a command")),
            tools = listOf(toolDefinition("bash")),
            maxTokens = 1
        )
        val mapped = DshTranscriptMapper.mapLocal(
            request,
            localModel(runtimeValuesJson = "{\"n_ctx\":16384}"),
            limits
        )
        assertEquals(4096, mapped.chatRequest.params.nPredict)

        val larger = DshTranscriptMapper.mapLocal(
            request.copy(maxTokens = 512),
            localModel(runtimeValuesJson = "{\"n_ctx\":16384}"),
            limits
        )
        assertEquals(512, larger.chatRequest.params.nPredict)
    }

    @Test
    fun `local output budget never exceeds the advertised model maximum`() {
        val request = dshRequest(
            messages = listOf(userMessage("run a command")),
            tools = listOf(toolDefinition("bash")),
            maxTokens = 32_768
        )
        val mapped = DshTranscriptMapper.mapLocal(
            request,
            localModel(
                runtimeValuesJson = "{\"n_ctx\":16384}",
                contextWindow = 16384,
                maxOutputTokens = 4096
            ),
            limits
        )
        assertEquals(4096, mapped.chatRequest.params.nPredict)

        val unbounded = DshTranscriptMapper.mapLocal(
            request,
            localModel(runtimeValuesJson = "{\"n_ctx\":16384}", contextWindow = 16384),
            limits
        )
        assertEquals(32_768, unbounded.chatRequest.params.nPredict)
    }

    @Test
    fun `local base parameters use the exact active profile context length`() {
        val mnn = DshTranscriptMapper.mapLocal(
            dshRequest(),
            localModel(runtimeValuesJson = "{\"n_ctx\":2048,\"n_threads\":2}"),
            limits
        )
        assertEquals(2048, mnn.chatRequest.params.nCtx)

        val liteRt = DshTranscriptMapper.mapLocal(
            dshRequest(),
            localModel(runtimeValuesJson = "{\"max_num_tokens\":1024}"),
            limits
        )
        assertEquals(1024, liteRt.chatRequest.params.nCtx)
    }

    @Test
    fun `prompted catalog is added only when tools are offered`() {
        val offered = DshTranscriptMapper.mapLocal(
            dshRequest(tools = listOf(toolDefinition("read"))),
            localModel(),
            limits
        )
        assertEquals(DshToolMode.PROMPTED, offered.toolMode)
        assertEquals(listOf("read"), offered.offeredToolNames)
        val system = offered.chatRequest.messages.first { it.role == Role.SYSTEM }.content
        assertTrue(system.contains("<tools>"))
        assertTrue(system.contains("\"name\":\"read\""))
        assertTrue(system.contains("<tool_call>"))
        assertFalse(system.contains(DshPromptedToolProtocol.START_MARKER))
        assertTrue(offered.chatRequest.tools.isEmpty())

        val disabled = DshTranscriptMapper.mapLocal(
            dshRequest(tools = listOf(toolDefinition("read")), toolChoice = DshToolChoice.NONE),
            localModel(),
            limits
        )
        assertEquals(DshToolMode.NONE, disabled.toolMode)
        val disabledSystem = disabled.chatRequest.messages.firstOrNull { it.role == Role.SYSTEM }
        assertTrue(disabledSystem == null || !disabledSystem.content.contains(DshPromptedToolProtocol.START_MARKER))
    }

    @Test
    fun `assistant tool calls and tool results stay ordered with id and name`() {
        val request = dshRequest(
            messages = listOf(
                userMessage("read a"),
                assistantMessage(
                    text = "Checking.",
                    toolCalls = listOf(toolCall("call_1", "read", "{\"path\":\"a\"}"))
                ),
                toolMessage("call_1", "file body")
            )
        )
        val messages = DshTranscriptMapper.mapLocal(request, localModel(), limits).chatRequest.messages
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), messages.map { it.role })
        assertTrue(messages[1].content.contains("Checking."))
        assertTrue(messages[1].content.contains("Previously called tool \"read\" (id call_1) with arguments (context only, not a tool-call format):"))
        assertTrue(messages[1].content.contains("{\"path\":\"a\"}"))
        assertTrue(messages[2].content.contains("Tool \"read\" (id call_1) returned:"))
        assertTrue(messages[2].content.contains("file body"))
    }

    @Test
    fun `temperature and max tokens are mapped and clamped`() {
        val mapped = DshTranscriptMapper.mapLocal(
            dshRequest(temperature = 0.25, maxTokens = 123),
            localModel(),
            limits
        )
        assertEquals(0.25f, mapped.chatRequest.params.temperature, 0.0001f)
        assertEquals(123, mapped.chatRequest.params.nPredict)

        val clamped = DshTranscriptMapper.mapLocal(
            dshRequest(temperature = 9.0, maxTokens = 10_000_000),
            localModel(),
            limits
        )
        assertEquals(2.0f, clamped.chatRequest.params.temperature, 0.0001f)
        assertEquals(limits.maxOutputTokens, clamped.chatRequest.params.nPredict)
    }

    @Test
    fun `oversized user text fails honestly instead of being silently truncated`() {
        val tight = limits.copy(maxMessageChars = 256, maxToolResultChars = 64)
        try {
            DshTranscriptMapper.mapLocal(
                dshRequest(messages = listOf(userMessage("u".repeat(300)))),
                localModel(),
                tight
            )
            fail("Expected DshMappingException")
        } catch (expected: DshMappingException) {
            assertEquals(DshAdapterErrorCodes.REQUEST_TOO_LARGE, expected.code)
            assertFalse(expected.retryable)
        }
    }

    @Test
    fun `oversized tool results are bounded with an explicit marker`() {
        val tight = limits.copy(maxToolResultChars = 64)
        val messages = DshTranscriptMapper.mapLocal(
            dshRequest(
                messages = listOf(
                    userMessage("go"),
                    assistantMessage(null, listOf(toolCall("call_1", "read"))),
                    toolMessage("call_1", "x".repeat(200))
                )
            ),
            localModel(),
            tight
        ).chatRequest.messages
        val result = messages.last().content
        assertTrue(result.contains("[mca:truncated"))
        assertTrue(result.length < 200)
    }

    @Test
    fun `no system message is fabricated when DSH sends none and no tools are offered`() {
        val mapped = DshTranscriptMapper.mapLocal(
            dshRequest(messages = listOf(userMessage("hello"))),
            localModel(),
            limits
        )
        assertTrue(mapped.chatRequest.messages.none { it.role == Role.SYSTEM })
    }

    @Test
    fun `native Responses carries the trailing tool round natively`() {
        val request = dshRequest(
            messages = listOf(
                systemMessage("DSH"),
                userMessage("read a"),
                assistantMessage(null, listOf(toolCall("call_1", "read", "{\"path\":\"a\"}"))),
                toolMessage("call_1", "file body")
            ),
            tools = listOf(toolDefinition("read")),
            toolChoice = DshToolChoice.AUTO
        )
        val mapped = DshTranscriptMapper.mapCloudNativeResponses(request, limits)
        assertEquals(DshToolMode.NATIVE_RESPONSES, mapped.toolMode)
        assertEquals(listOf("read"), mapped.offeredToolNames)
        assertEquals(1, mapped.chatRequest.tools.size)
        assertEquals(1, mapped.chatRequest.toolExchanges.size)
        assertEquals("call_1", mapped.chatRequest.toolExchanges[0].call.id)
        assertEquals("file body", mapped.chatRequest.toolExchanges[0].output)
        assertEquals(listOf(Role.SYSTEM, Role.USER), mapped.chatRequest.messages.map { it.role })
    }

    @Test
    fun `native Responses transcribes older rounds and keeps only the trailing round native`() {
        val request = dshRequest(
            messages = listOf(
                userMessage("read a"),
                assistantMessage(null, listOf(toolCall("call_1", "read", "{\"path\":\"a\"}"))),
                toolMessage("call_1", "first body"),
                assistantMessage("Now read b.", listOf(toolCall("call_2", "read", "{\"path\":\"b\"}"))),
                toolMessage("call_2", "second body")
            ),
            tools = listOf(toolDefinition("read"))
        )
        val mapped = DshTranscriptMapper.mapCloudNativeResponses(request, limits)
        assertEquals(1, mapped.chatRequest.toolExchanges.size)
        assertEquals("call_2", mapped.chatRequest.toolExchanges[0].call.id)

        val roles = mapped.chatRequest.messages.map { it.role }
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), roles)
        assertTrue(mapped.chatRequest.messages[1].content.contains("call_1"))
        assertTrue(mapped.chatRequest.messages[2].content.contains("first body"))
        assertEquals("Now read b.", mapped.chatRequest.messages[3].content)
    }

    @Test
    fun `native Responses falls back to a text transcript when the trailing round is incomplete`() {
        val request = dshRequest(
            messages = listOf(
                userMessage("go"),
                assistantMessage(null, listOf(toolCall("call_1", "read"))),
                assistantMessage("later")
            ),
            tools = listOf(toolDefinition("read"))
        )
        val mapped = DshTranscriptMapper.mapCloudNativeResponses(request, limits)
        assertTrue(mapped.chatRequest.toolExchanges.isEmpty())
        assertTrue(mapped.chatRequest.messages.any { it.content.contains("call_1") })
    }

    @Test
    fun `native Responses normalizes an empty schema and adds the required-call note`() {
        val request = dshRequest(
            messages = listOf(systemMessage("DSH"), userMessage("go")),
            tools = listOf(toolDefinition("read", parametersJson = "{}")),
            toolChoice = DshToolChoice.REQUIRED
        )
        val mapped = DshTranscriptMapper.mapCloudNativeResponses(request, limits)
        val schema = JSONObject(mapped.chatRequest.tools.single().parametersJson)
        assertEquals("object", schema.optString("type"))
        val system = mapped.chatRequest.messages.first { it.role == Role.SYSTEM }.content
        assertTrue(system.contains(DshPromptedToolProtocol.REQUIRED_TOOL_NOTE))
    }

    @Test
    fun `invalid native schema and invalid tool names are rejected`() {
        try {
            DshTranscriptMapper.mapCloudNativeResponses(
                dshRequest(
                    messages = listOf(userMessage("go")),
                    tools = listOf(toolDefinition("read", parametersJson = "{\"type\":\"string\"}"))
                ),
                limits
            )
            fail("Expected DshMappingException")
        } catch (expected: DshMappingException) {
            assertEquals(DshAdapterErrorCodes.TOOL_SCHEMA_INVALID, expected.code)
        }

        try {
            DshTranscriptMapper.mapLocal(
                dshRequest(tools = listOf(toolDefinition("bad name"))),
                localModel(),
                limits
            )
            fail("Expected DshMappingException")
        } catch (expected: DshMappingException) {
            assertEquals(DshAdapterErrorCodes.TOOL_SCHEMA_INVALID, expected.code)
        }

        try {
            DshTranscriptMapper.mapLocal(
                dshRequest(tools = listOf(toolDefinition("read"), toolDefinition("read"))),
                localModel(),
                limits
            )
            fail("Expected DshMappingException")
        } catch (expected: DshMappingException) {
            assertEquals(DshAdapterErrorCodes.TOOL_SCHEMA_INVALID, expected.code)
        }
    }

    @Test
    fun `system messages are merged once, in order, in front of the transcript`() {
        val request = dshRequest(
            messages = listOf(
                systemMessage("S1"),
                userMessage("u"),
                systemMessage("S2")
            )
        )
        val messages = DshTranscriptMapper.mapLocal(request, localModel(), limits).chatRequest.messages
        assertEquals(Role.SYSTEM, messages.first().role)
        assertEquals("S1\n\nS2", messages.first().content)
        assertEquals(1, messages.count { it.role == Role.SYSTEM })
    }

    @Test
    fun `tool results must pair with an earlier call id and appear once`() {
        val orphan = dshRequest(messages = listOf(userMessage("go"), toolMessage("call_x", "out")))
        assertEquals(
            DshAdapterErrorCodes.TOOL_HISTORY_INVALID,
            mappingError { DshTranscriptMapper.mapLocal(orphan, localModel(), limits) }.code
        )

        val duplicateResult = dshRequest(
            messages = listOf(
                userMessage("go"),
                assistantMessage(null, listOf(toolCall("call_1", "read"))),
                toolMessage("call_1", "one"),
                toolMessage("call_1", "two")
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_HISTORY_INVALID,
            mappingError { DshTranscriptMapper.mapLocal(duplicateResult, localModel(), limits) }.code
        )

        val resultBeforeCall = dshRequest(
            messages = listOf(
                toolMessage("call_1", "out"),
                userMessage("go"),
                assistantMessage(null, listOf(toolCall("call_1", "read")))
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_HISTORY_INVALID,
            mappingError { DshTranscriptMapper.mapLocal(resultBeforeCall, localModel(), limits) }.code
        )
    }

    @Test
    fun `duplicate tool call ids and oversized historical arguments are rejected`() {
        val duplicateCallId = dshRequest(
            messages = listOf(
                assistantMessage(null, listOf(toolCall("call_1", "read"))),
                toolMessage("call_1", "one"),
                assistantMessage(null, listOf(toolCall("call_1", "read")))
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_HISTORY_INVALID,
            mappingError { DshTranscriptMapper.mapLocal(duplicateCallId, localModel(), limits) }.code
        )

        val tight = limits.copy(maxToolCallArgumentsChars = 64)
        val oversized = dshRequest(
            messages = listOf(
                assistantMessage(
                    null,
                    listOf(toolCall("call_1", "read", "{\"path\":\"${"x".repeat(80)}\"}"))
                )
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_ARGUMENTS_TOO_LARGE,
            mappingError { DshTranscriptMapper.mapLocal(oversized, localModel(), tight) }.code
        )
    }

    @Test
    fun `an over-budget prompted catalog degrades to the essential subset`() {
        val tight = limits.copy(maxPromptedToolCatalogChars = 2_000, maxToolParametersChars = 64)
        val request = dshRequest(
            tools = listOf(
                toolDefinition(
                    "huge",
                    // Fits the per-tool parameter limit but not the whole catalog budget.
                    parametersJson = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"description\":\"" +
                        "x".repeat(1_500) + "\"}}}"
                ),
                toolDefinition("read")
            )
        )
        val mapped = DshTranscriptMapper.mapLocal(request, localModel(), tight)
        assertEquals(DshToolMode.PROMPTED, mapped.toolMode)
        assertTrue("the core file tool must survive the budget", "read" in mapped.offeredToolNames)
        assertFalse("the oversized tool must not be offered", "huge" in mapped.offeredToolNames)
    }

    @Test
    fun `a catalog with no representable tool degrades to plain chat instead of failing the turn`() {
        val tight = limits.copy(maxPromptedToolCatalogChars = 1_200, maxToolParametersChars = 8)
        val request = dshRequest(
            tools = listOf(
                toolDefinition(
                    "huge",
                    parametersJson = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}"
                )
            )
        )
        val mapped = DshTranscriptMapper.mapLocal(request, localModel(), tight)
        assertEquals(DshToolMode.NONE, mapped.toolMode)
        assertTrue(mapped.offeredToolNames.isEmpty())
    }

    private fun mappingError(block: () -> Unit): DshMappingException {
        try {
            block()
            org.junit.Assert.fail("Expected DshMappingException")
            error("unreachable")
        } catch (expected: DshMappingException) {
            return expected
        }
    }
}
