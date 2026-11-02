package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRole
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshGatewayFidelityIntegrationTest : DshGatewayServerTestBase() {

    @Test
    fun nonStreamTextCompletionCarriesUsageAndTheCallIdHeader() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("Hello "),
                DshModelEvent.TextDelta("world"),
                DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 11, completionTokens = 5)
            )
        }
        val response = chat(stream = false)
        assertEquals(200, response.status)
        assertTrue(response.headers["x-mca-call-id"].orEmpty().startsWith("dsh:7:"))
        assertEquals("application/json; charset=utf-8", response.headers["content-type"])

        val choice = response.json().getJSONArray("choices").getJSONObject(0)
        assertEquals(0, choice.getInt("index"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals("assistant", choice.getJSONObject("message").getString("role"))
        assertEquals("Hello world", choice.getJSONObject("message").getString("content"))
        val usage = response.json().getJSONObject("usage")
        assertEquals(11, usage.getInt("prompt_tokens"))
        assertEquals(5, usage.getInt("completion_tokens"))
        assertEquals(16, usage.getInt("total_tokens"))
        assertEquals("chat.completion", response.json().getString("object"))
    }

    @Test
    fun theFullDshTranscriptReachesTheBackendWithoutFieldLoss() {
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 2))
        }
        val body = """
            {
              "model": "fake-model",
              "messages": [
                {"role": "system", "content": "system prompt"},
                {"role": "user", "content": "first"},
                {"role": "assistant", "content": null, "tool_calls": [
                  {"id": "call_x", "type": "function",
                   "function": {"name": "read_file", "arguments": "{\"path\":\"a\"}"}}
                ]},
                {"role": "tool", "tool_call_id": "call_x", "content": "result"},
                {"role": "user", "content": "second"}
              ],
              "tools": [
                {"type": "function", "function": {
                  "name": "read_file", "description": "Read a file",
                  "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}
                }}
              ],
              "tool_choice": "auto",
              "temperature": 0.1,
              "max_completion_tokens": 128,
              "stream": false
            }
        """.trimIndent()
        val response = DshTestHttp.request(endpoint, "POST", "/v1/chat/completions", body = body)
        assertEquals(200, response.status)

        val request = backend.singleRequest()
        assertEquals("fake-model", request.modelId)
        assertTrue(request.callId.matches(Regex("dsh:7:\\d+")))
        assertFalse(request.stream)
        assertEquals(
            listOf(
                DshModelRole.SYSTEM,
                DshModelRole.USER,
                DshModelRole.ASSISTANT,
                DshModelRole.TOOL,
                DshModelRole.USER
            ),
            request.messages.map { it.role }
        )
        assertEquals("system prompt", request.messages[0].content)
        assertEquals("first", request.messages[1].content)
        assertNull(request.messages[2].content)
        assertEquals("call_x", request.messages[2].toolCalls.single().id)
        assertEquals("read_file", request.messages[2].toolCalls.single().name)
        assertEquals("""{"path":"a"}""", request.messages[2].toolCalls.single().argumentsJson)
        assertEquals("call_x", request.messages[3].toolCallId)
        assertEquals("result", request.messages[3].content)
        assertEquals(1, request.tools.size)
        assertEquals("read_file", request.tools[0].name)
        assertEquals("Read a file", request.tools[0].description)
        assertEquals(
            """{"properties":{"path":{"type":"string"}},"required":["path"],"type":"object"}""",
            request.tools[0].parametersJson
        )
        assertEquals(0.1, request.temperature!!, 0.0)
        assertEquals(128, request.maxTokens)
    }

    @Test
    fun nonStreamAssemblesInterleavedToolCallsInIndexOrder() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "read_file", "{\"path\":"),
                DshModelEvent.ToolCallDelta(1, "call_b", "shell", "{\"cmd\":"),
                DshModelEvent.ToolCallDelta(0, null, null, "\"a\"}"),
                DshModelEvent.ToolCallDelta(1, null, null, "\"ls\"}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS, promptTokens = 9, completionTokens = 4)
            )
        }
        val response = chat(stream = false)
        assertEquals(200, response.status)
        val choice = response.json().getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        val message = choice.getJSONObject("message")
        assertTrue(message.isNull("content"))
        val calls = message.getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        assertEquals("call_a", calls.getJSONObject(0).getString("id"))
        assertEquals("read_file", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals(
            """{"path":"a"}""",
            calls.getJSONObject(0).getJSONObject("function").getString("arguments")
        )
        assertEquals("call_b", calls.getJSONObject(1).getString("id"))
        assertEquals(
            """{"cmd":"ls"}""",
            calls.getJSONObject(1).getJSONObject("function").getString("arguments")
        )
    }

    @Test
    fun streamingSeparatesTextReasoningUsageAndTheSingleTerminalChunk() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ReasoningDelta("think "),
                DshModelEvent.TextDelta("Hi"),
                DshModelEvent.ReasoningDelta("done"),
                DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 3, completionTokens = 2)
            )
        }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertTrue(events.size >= 4)
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            frames.forEach {
                assertEquals("chat.completion.chunk", it.getString("object"))
                assertEquals("fake-model", it.getString("model"))
            }
            assertEquals(
                "assistant",
                frames.first().getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("delta").getString("role")
            )
            val content = frames.joinToString("") { frame ->
                val delta = frame.firstChoiceOrNull()?.optJSONObject("delta")
                delta?.optString("content").orEmpty()
            }
            assertEquals("Hi", content)
            val reasoning = frames.joinToString("") { frame ->
                val delta = frame.firstChoiceOrNull()?.optJSONObject("delta")
                delta?.optString("reasoning_content").orEmpty()
            }
            assertEquals("think done", reasoning)
            val finishReasons = frames.mapNotNull { frame ->
                val choice = frame.firstChoiceOrNull() ?: return@mapNotNull null
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("stop"), finishReasons)
            val usageFrame = frames.last()
            assertEquals(0, usageFrame.getJSONArray("choices").length())
            assertEquals(3, usageFrame.getJSONObject("usage").getInt("prompt_tokens"))
            assertEquals(2, usageFrame.getJSONObject("usage").getInt("completion_tokens"))
            assertEquals(5, usageFrame.getJSONObject("usage").getInt("total_tokens"))
        } finally {
            sse.close()
        }
    }

    @Test
    fun streamingToolFragmentsKeepStableIndexIdAndName() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "read_file", "{\"path\":"),
                DshModelEvent.ToolCallDelta(1, "call_b", "shell", "{\"cmd\":"),
                DshModelEvent.ToolCallDelta(0, null, null, "\"a\"}"),
                DshModelEvent.ToolCallDelta(1, null, null, "\"ls\"}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS, promptTokens = 4, completionTokens = 6)
            )
        }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            val deltas = frames.flatMap { frame ->
                val choice = frame.firstChoiceOrNull() ?: return@flatMap emptyList()
                val delta = choice.optJSONObject("delta") ?: return@flatMap emptyList()
                val toolCalls = delta.optJSONArray("tool_calls") ?: return@flatMap emptyList()
                (0 until toolCalls.length()).map { toolCalls.getJSONObject(it) }
            }
            assertEquals(4, deltas.size)
            assertEquals(listOf(0, 1, 0, 1), deltas.map { it.getInt("index") })
            assertEquals("call_a", deltas[0].getString("id"))
            assertEquals("read_file", deltas[0].getJSONObject("function").getString("name"))
            assertEquals("call_b", deltas[1].getString("id"))
            assertEquals("shell", deltas[1].getJSONObject("function").getString("name"))
            assertFalse(deltas[2].has("id"))
            assertFalse(deltas[2].getJSONObject("function").has("name"))
            val arguments = mutableMapOf<Int, String>()
            deltas.forEach { call ->
                val index = call.getInt("index")
                arguments[index] = arguments.getOrElse(index) { "" } +
                    call.getJSONObject("function").optString("arguments")
            }
            assertEquals("""{"path":"a"}""", arguments[0])
            assertEquals("""{"cmd":"ls"}""", arguments[1])
            val finishReasons = frames.mapNotNull { frame ->
                val choice = frame.firstChoiceOrNull() ?: return@mapNotNull null
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("tool_calls"), finishReasons)
        } finally {
            sse.close()
        }
    }

    @Test
    fun lengthTerminalIsNeverRewrittenToStop() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("cut"),
                DshModelEvent.Completed(DshFinishReason.LENGTH, promptTokens = 2, completionTokens = 1)
            )
        }
        val buffered = chat(stream = false)
        assertEquals(
            "length",
            buffered.json().getJSONArray("choices").getJSONObject(0).getString("finish_reason")
        )

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val finishReasons = events.dropLast(1).map { JSONObject(it) }.mapNotNull { frame ->
                val choice = frame.firstChoiceOrNull() ?: return@mapNotNull null
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("length"), finishReasons)
        } finally {
            sse.close()
        }
    }

    @Test
    fun backendFailureBecomesHttpErrorAndAnErrorFinishNotStop() {
        backend.streamFactory = {
            flowOf(DshModelEvent.Failed("context_length_exceeded", "too long", retryable = false))
        }
        val buffered = chat(stream = false)
        assertEquals(413, buffered.status)
        assertEquals(
            "context_length_exceeded",
            buffered.json().getJSONObject("error").getString("code")
        )

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            val finishReasons = frames.mapNotNull { frame ->
                val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
                if (choices.length() == 0) return@mapNotNull null
                val choice = choices.getJSONObject(0)
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("error"), finishReasons)
            val error = frames.mapNotNull { it.optJSONObject("error") }.single()
            assertEquals("context_length_exceeded", error.getString("code"))
            assertFalse(events.any { it.contains("\"finish_reason\":\"stop\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun eofWithoutTerminalIsAnErrorNeverStop() {
        backend.streamFactory = { flowOf(DshModelEvent.TextDelta("partial")) }
        val buffered = chat(stream = false)
        assertEquals(502, buffered.status)
        assertEquals("backend_stream_closed", buffered.json().getJSONObject("error").getString("code"))

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            val error = frames.mapNotNull { it.optJSONObject("error") }.single()
            assertEquals("backend_stream_closed", error.getString("code"))
            val finishReasons = frames.mapNotNull { frame ->
                val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
                if (choices.length() == 0) return@mapNotNull null
                val choice = choices.getJSONObject(0)
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("error"), finishReasons)
            assertFalse(events.any { it.contains("\"finish_reason\":\"stop\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun cancelledTerminalIsDistinctFromStopAndError() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("part"),
                DshModelEvent.Completed(DshFinishReason.CANCELLED)
            )
        }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            val finishReasons = frames.mapNotNull { frame ->
                val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
                if (choices.length() == 0) return@mapNotNull null
                val choice = choices.getJSONObject(0)
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("cancelled"), finishReasons)
            assertTrue(frames.none { it.has("error") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun aToolCallWithoutANameFailsInsteadOfClaimingToolCalls() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", null, "{}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            val error = frames.mapNotNull { it.optJSONObject("error") }.single()
            assertEquals("invalid_tool_call", error.getString("code"))
            assertFalse(events.any { it.contains("\"finish_reason\":\"tool_calls\"") })
            assertFalse(events.any { it.contains("\"finish_reason\":\"stop\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun theFirstTerminalEventEndsTheCallAndLaterEventsAreIgnored() {
        backend.streamFactory = {
            flow {
                emit(DshModelEvent.TextDelta("before"))
                emit(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
                emit(DshModelEvent.TextDelta("after-terminal"))
                awaitCancellation()
            }
        }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            assertFalse(events.any { it.contains("after-terminal") })
            assertEquals(1, events.count { it.contains("\"finish_reason\":\"stop\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun modelAndToolCapabilityValidationHappensBeforeTheBackendCall() {
        val missingModel = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions",
            body = chatRequestBody(model = "missing-model", stream = false)
        )
        assertEquals(404, missingModel.status)
        assertEquals("model_not_found", missingModel.json().getJSONObject("error").getString("code"))
        assertTrue(backend.requests.isEmpty())

        backend.models = listOf(fakeGatewayModel(supportsTools = false))
        val toolsRequest = chatRequestBody(
            stream = false,
            extraFieldsJson = """"tools":[{"type":"function","function":{"name":"read_file","parameters":{"type":"object"}}}]"""
        )
        val unsupported = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions", body = toolsRequest
        )
        assertEquals(400, unsupported.status)
        assertEquals("tools_not_supported", unsupported.json().getJSONObject("error").getString("code"))
        assertTrue(backend.requests.isEmpty())
    }

    private fun JSONObject.firstChoiceOrNull(): JSONObject? {
        val choices = optJSONArray("choices") ?: return null
        return if (choices.length() == 0) null else choices.getJSONObject(0)
    }
}
