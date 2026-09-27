package com.muyuchat.mca

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolDefinition
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiResponsesProtocolTest {
    private val config = CloudApiConfig(enabled = true, apiFormat = CloudApiFormat.OPENAI_RESPONSES,
        baseUrl = "https://example.test/v1", chatModel = "custom-model", apiKey = "test-key")

    @Test fun assembledContextHistoryAndImagesUseResponsesSchema() {
        val request = ChatRequest(listOf(
            ChatMessage(Role.SYSTEM, "角色卡设定"), ChatMessage(Role.USER, "previous"),
            ChatMessage(Role.ASSISTANT, "previous answer"),
            ChatMessage(Role.USER, "看图", imageAttachments = listOf(ChatImageAttachment(dataBase64 = "eA==", mimeType = "image/png")))
        ), GenerationParams(systemPrompt = "不应覆盖角色", nPredict = 180, temperature = 0.4f, topP = 0.8f),
            runtimeSystemContext = "世界书与知识库检索内容")
        val json = buildOpenAiResponsesJson(config.copy(supportsVision = true), request)
        val input = json.getJSONArray("input")
        assertEquals(4, input.length())
        assertTrue(input.getJSONObject(0).getString("content").contains("角色卡设定"))
        assertTrue(input.getJSONObject(0).getString("content").contains("世界书与知识库检索内容"))
        assertEquals("assistant", input.getJSONObject(2).getString("role"))
        val image = input.getJSONObject(3).getJSONArray("content").getJSONObject(1)
        assertEquals("input_image", image.getString("type"))
        assertEquals("data:image/png;base64,eA==", image.getString("image_url"))
        assertFalse(input.getJSONObject(0).has("created_at"))
        assertFalse(json.getBoolean("store"))
        assertTrue(json.getBoolean("stream"))
        assertEquals(180, json.getInt("max_output_tokens"))
        assertEquals(0.4, json.getDouble("temperature"), 0.0001)
        assertEquals(0.8, json.getDouble("top_p"), 0.0001)
        listOf("messages", "max_tokens", "enable_thinking", "thinking_budget", "presence_penalty", "frequency_penalty", "seed", "top_k", "min_p", "stop", "previous_response_id").forEach { assertFalse(it, json.has(it)) }
    }

    @Test fun reasoningCapabilityControlsReasoningAndSamplingIndependentlyOfModelName() {
        val request = ChatRequest(listOf(ChatMessage(Role.USER, "hi")), GenerationParams(nPredict = 8, reasoningMode = ReasoningMode.ADVANCED))
        val reasoning = buildOpenAiResponsesJson(config.copy(responsesReasoningEnabled = true), request)
        assertEquals("high", reasoning.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", reasoning.getJSONObject("reasoning").getString("summary"))
        assertFalse(reasoning.has("temperature")); assertFalse(reasoning.has("top_p"))
        assertEquals(8192, reasoning.getInt("max_output_tokens")) // existing advanced-thinking output floor
        assertEquals(16, buildOpenAiResponsesJson(config, request.copy(params = request.params.copy(reasoningMode = ReasoningMode.OFF))).getInt("max_output_tokens"))
        assertFalse(buildOpenAiResponsesJson(config, request).has("reasoning"))
        assertFalse(buildOpenAiResponsesJson(config.copy(responsesReasoningEnabled = true),
            request.copy(params = request.params.copy(hideReasoning = true))).getJSONObject("reasoning").has("summary"))
    }

    @Test fun imagesRequireExplicitCapabilityAndUploadableData() {
        val request = ChatRequest(listOf(ChatMessage(Role.USER, "photo", imageAttachments = listOf(ChatImageAttachment(uriString = "content://private/photo")))))
        assertTrue(runCatching { buildOpenAiResponsesJson(config, request) }.isFailure)
        assertTrue(runCatching { buildOpenAiResponsesJson(config.copy(supportsVision = true), request) }.isFailure)
    }

    @Test fun toolDefinitionsUseResponsesFunctionSchemaAndEnableAutoChoice() {
        val definition = ChatToolDefinition("generate_image", "Generate an image", """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"],"additionalProperties":false}""")
        val json = buildOpenAiResponsesJson(config.copy(supportsTools = true), ChatRequest(listOf(ChatMessage(Role.USER, "draw")), tools = listOf(definition)))
        val tool = json.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("generate_image", tool.getString("name"))
        assertEquals("Generate an image", tool.getString("description"))
        assertEquals("object", tool.getJSONObject("parameters").getString("type"))
        assertEquals("auto", json.getString("tool_choice"))
        assertFalse(buildOpenAiResponsesJson(config, ChatRequest(emptyList())).has("tools"))
        assertTrue(runCatching {
            buildOpenAiResponsesJson(config.copy(supportsTools = true), ChatRequest(emptyList(), tools = listOf(definition.copy(parametersJson = "[]"))))
        }.isFailure)
    }

    @Test fun functionToolsRequireResponsesProtocolAndExplicitModelCapability() {
        val definition = ChatToolDefinition("generate_image", "Generate an image", """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"],"additionalProperties":false}""")
        val request = ChatRequest(listOf(ChatMessage(Role.USER, "draw")), tools = listOf(definition))
        assertTrue(runCatching { buildOpenAiResponsesJson(config, request) }.isFailure)
        assertTrue(runCatching {
            buildOpenAiResponsesJson(config.copy(apiFormat = CloudApiFormat.OPENAI_COMPATIBLE, supportsTools = true), request)
        }.isFailure)
        assertEquals(1, buildOpenAiResponsesJson(config.copy(supportsTools = true), request).getJSONArray("tools").length())
    }

    @Test fun toolExchangesBecomeAdjacentResponsesCallAndOutputInputItems() {
        val call = ChatToolCall("call_123", "generate_image", "{\"prompt\":\"blue fox\"}")
        val request = ChatRequest(listOf(ChatMessage(Role.USER, "Draw a blue fox")), toolExchanges = listOf(
            ChatToolExchange(call, "{\"status\":\"completed\",\"image_count\":1}")
        ))
        val input = buildOpenAiResponsesJson(config, request).getJSONArray("input")
        assertEquals(4, input.length())
        assertEquals("function_call", input.getJSONObject(2).getString("type"))
        assertEquals("call_123", input.getJSONObject(2).getString("call_id"))
        assertEquals(call.argumentsJson, input.getJSONObject(2).getString("arguments"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        assertEquals("call_123", input.getJSONObject(3).getString("call_id"))
        assertEquals("{\"status\":\"completed\",\"image_count\":1}", input.getJSONObject(3).getString("output"))
        assertFalse(buildOpenAiResponsesJson(config.copy(supportsTools = false), request).has("tools"))
    }

    @Test fun fullEndpointGatewayPrefixAndQueryArePreserved() {
        assertEquals("https://example.test/proxy/v1/responses?tenant=a", responsesEndpointUrl("https://example.test/proxy/v1/?tenant=a"))
        assertEquals("https://example.test/v1/responses", responsesEndpointUrl("https://example.test/v1/responses/"))
        assertEquals("https://example.test/v1/responses", responsesEndpointUrl("https://example.test/v1/chat/completions"))
        assertEquals("Bearer test-key", responsesHttpRequest(config, ChatRequest(emptyList())).header("Authorization"))
        assertNull(responsesHttpRequest(config.copy(apiKey = ""), ChatRequest(emptyList())).header("Authorization"))
    }

    @Test fun deltaDoneItemAndTerminalSnapshotsDoNotDuplicateTextOrWhitespace() {
        val decoder = ResponsesDecoder()
        val received = StringBuilder()
        listOf("hello", " ", "null", "\n", "  world").forEach {
            received.append(decoder.event(JSONObject().put("type", "response.output_text.delta").put("delta", it).toString()).joinToString("") { it.text })
        }
        val text = "hello null\n  world"
        assertEquals(text, received.toString())
        assertTrue(decoder.event(JSONObject().put("type", "response.output_text.done").put("text", text).toString()).isEmpty())
        assertTrue(decoder.event(JSONObject().put("type", "response.output_item.done").put("item", message(text)).toString()).isEmpty())
        assertTrue(decoder.finish(completed(text)).isEmpty())
        assertTrue(decoder.terminal); assertNull(decoder.error)
    }

    @Test fun terminalOnlyAndPartialStreamsFillInMissingSuffix() {
        val decoder = ResponsesDecoder()
        decoder.event("""{"type":"response.output_text.delta","delta":"he"}""")
        assertEquals("llo", decoder.finish(completed("hello")).single().text)
        assertEquals("hello", ResponsesDecoder().finish(completed("hello")).single().text)
    }

    @Test fun responseWithToolsReasoningRefusalAndMultipleTextPartsKeepsChannelsSeparate() {
        val response = completed("").put("output", JSONArray()
            .put(JSONObject().put("type", "function_call").put("call_id", "call_secret")
                .put("name", "generate_image").put("arguments", "{\"prompt\":\"secret tool payload\"}"))
            .put(JSONObject().put("type", "reasoning").put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "summary"))))
            .put(message("answer"))
            .put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "cannot answer")))))
        val decoder = ResponsesDecoder()
        val parts = decoder.finish(response)
        assertEquals("summary", parts.joinToString("") { it.reasoning })
        assertEquals("answercannot answer", parts.joinToString("") { it.text })
        assertEquals(listOf(ChatToolCall("call_secret", "generate_image", "{\"prompt\":\"secret tool payload\"}")), decoder.toolCalls)
    }

    @Test fun functionCallArgumentDeltasAndTerminalSnapshotsAreAccumulatedExactlyOnce() {
        val decoder = ResponsesDecoder()
        val visible = StringBuilder()
        val args = "{\"prompt\":\"red fox\"}"
        decoder.event(JSONObject().put("type", "response.output_item.added").put("output_index", 0)
            .put("item", JSONObject().put("id", "fc_item").put("type", "function_call").put("call_id", "call_redfox")
                .put("name", "generate_image").put("arguments", "")).toString())
        decoder.event(JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", 0)
            .put("item_id", "fc_item").put("delta", "{\"prompt\":\"red ").toString())
        decoder.event(JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", 0)
            .put("item_id", "fc_item").put("delta", "fox\"}").toString())
        decoder.event(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
            .put("item_id", "fc_item").put("arguments", args).toString())
        decoder.event(JSONObject().put("type", "response.output_item.done").put("output_index", 0)
            .put("item", JSONObject().put("id", "fc_item").put("type", "function_call").put("call_id", "call_redfox")
                .put("name", "generate_image").put("arguments", args)).toString())
        decoder.event(JSONObject().put("type", "response.output_text.delta").put("output_index", 1)
            .put("delta", "I made it.").toString()).forEach { visible.append(it.text) }
        val terminal = JSONObject().put("status", "completed").put("output", JSONArray()
            .put(JSONObject().put("id", "fc_item").put("type", "function_call").put("call_id", "call_redfox")
                .put("name", "generate_image").put("arguments", args))
            .put(message("I made it.")))
        decoder.finish(terminal)
        assertNull(decoder.error)
        assertEquals("I made it.", visible.toString())
        assertEquals(listOf(ChatToolCall("call_redfox", "generate_image", args)), decoder.toolCalls)
    }

    @Test fun partialOrInconsistentFunctionCallArgumentsCannotBecomeExecutable() {
        val partial = ResponsesDecoder()
        partial.event(JSONObject().put("type", "response.output_item.added").put("output_index", 0)
            .put("item", JSONObject().put("type", "function_call").put("call_id", "call_1")
                .put("name", "generate_image").put("arguments", "")).toString())
        partial.event(JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", 0)
            .put("delta", "{\"prompt\":").toString())
        partial.finish(JSONObject().put("status", "completed").put("output", JSONArray()))
        assertTrue(partial.toolCalls.isEmpty())
        assertNotNull(partial.error)

        val inconsistent = ResponsesDecoder()
        inconsistent.event(JSONObject().put("type", "response.output_item.added").put("output_index", 0)
            .put("item", JSONObject().put("type", "function_call").put("call_id", "call_1")
                .put("name", "generate_image").put("arguments", "")).toString())
        inconsistent.event(JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", 0)
            .put("delta", "{\"prompt\":\"x\"}").toString())
        assertTrue(runCatching {
            inconsistent.event(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
                .put("arguments", "{\"prompt\":\"y\"}").toString())
        }.isFailure)
        assertTrue(inconsistent.toolCalls.isEmpty())
    }

    @Test fun toolCallsAreNotExposedUntilACompleteSuccessfulTerminalSnapshot() {
        val args = "{\"prompt\":\"red fox\"}"
        val callItem = JSONObject().put("id", "fc_item").put("type", "function_call").put("call_id", "call_redfox")
            .put("name", "generate_image").put("arguments", args)
        val streaming = ResponsesDecoder()
        streaming.event(JSONObject().put("type", "response.output_item.added").put("output_index", 0).put("item", callItem).toString())
        streaming.event(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
            .put("item_id", "fc_item").put("arguments", args).toString())
        assertTrue(streaming.toolCalls.isEmpty())
        streaming.finish(JSONObject().put("status", "completed").put("output", JSONArray().put(callItem)))
        assertEquals(listOf(ChatToolCall("call_redfox", "generate_image", args)), streaming.toolCalls)

        val failed = decoderWithCompleteStreamCall(callItem, args)
        failed.finish(JSONObject().put("status", "failed").put("error", JSONObject().put("message", "provider failed")))
        assertTrue(failed.toolCalls.isEmpty())
        assertNotNull(failed.error)

        val incomplete = decoderWithCompleteStreamCall(callItem, args)
        incomplete.finish(JSONObject().put("status", "incomplete").put("output", JSONArray().put(callItem))
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")))
        assertTrue(incomplete.toolCalls.isEmpty())
        assertNotNull(incomplete.error)

        val missingTerminalItem = decoderWithCompleteStreamCall(callItem, args)
        missingTerminalItem.finish(JSONObject().put("status", "completed").put("output", JSONArray()))
        assertTrue(missingTerminalItem.toolCalls.isEmpty())
        assertNotNull(missingTerminalItem.error)

        val conflictingSnapshot = decoderWithCompleteStreamCall(callItem, args)
        assertTrue(runCatching {
            conflictingSnapshot.finish(JSONObject().put("status", "completed").put("output", JSONArray().put(
                JSONObject(callItem.toString()).put("arguments", "")
            )))
        }.isFailure)
        assertTrue(conflictingSnapshot.toolCalls.isEmpty())

        val conflictingTerminalEvent = ResponsesDecoder()
        val response = JSONObject().put("status", "completed").put("output", JSONArray().put(callItem))
        conflictingTerminalEvent.event(JSONObject().put("type", "response.failed").put("response", response).toString())
        assertTrue(conflictingTerminalEvent.toolCalls.isEmpty())
        assertNotNull(conflictingTerminalEvent.error)
    }

    @Test fun malformedTerminalFunctionArgumentsNeverBecomeExecutable() {
        val malformed = ResponsesDecoder()
        malformed.finish(JSONObject().put("status", "completed").put("output", JSONArray().put(
            JSONObject().put("type", "function_call").put("call_id", "call_bad").put("name", "generate_image").put("arguments", "[]")
        )))
        assertTrue(malformed.toolCalls.isEmpty())
        assertNotNull(malformed.error)

        val missingIndex = ResponsesDecoder()
        assertTrue(runCatching {
            missingIndex.event(JSONObject().put("type", "response.function_call_arguments.delta").put("delta", "{\"prompt\":\"x\"}").toString())
        }.isFailure)
        assertTrue(missingIndex.toolCalls.isEmpty())
    }
    @Test fun failedIncompleteAndMissingStatusesCannotBecomeSuccess() {
        val failed = ResponsesDecoder()
        failed.event("""{"type":"response.failed","response":{"status":"failed","error":{"code":"quota","message":"额度不足"}}}""")
        assertEquals("额度不足", failed.error)
        val incomplete = ResponsesDecoder()
        assertEquals("partial", incomplete.finish(completed("partial").put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))).single().text)
        assertEquals("max_output_tokens", incomplete.incompleteReason)
        assertNotNull(incomplete.error)
        val invalid = ResponsesDecoder(); invalid.finish(JSONObject("{}")); assertNotNull(invalid.error)
        assertTrue(runCatching { ResponsesDecoder().event("[DONE]") }.isFailure)
        assertTrue(runCatching { ResponsesDecoder().event("broken json") }.isFailure)
    }

    @Test fun usageUsesProviderCountsIncludingPromptCache() {
        val decoder = ResponsesDecoder()
        decoder.finish(completed("hello").put("usage", JSONObject().put("input_tokens", 900).put("output_tokens", 30)
            .put("input_tokens_details", JSONObject().put("cached_tokens", 800))))
        assertEquals(900, decoder.inputTokens); assertEquals(30, decoder.outputTokens); assertEquals(800, decoder.cachedTokens)
    }

    @Test fun stopWordsSplitAtEveryBoundaryNeverLeakIntoVisibleText() {
        val marker = "<END>"
        for (split in 0..marker.length) {
            val filter = ResponsesStopFilter(listOf(marker))
            val visible = filter.accept("hello" + marker.take(split)) + filter.accept(marker.drop(split) + "hidden") + filter.finish()
            assertEquals("hello", visible); assertTrue(filter.stopped)
        }
        val filter = ResponsesStopFilter(listOf(marker))
        assertEquals("hello<EN", filter.accept("hello<EN") + filter.finish())
    }

    @Test fun recordSelectionKeepsResponsesOptionsAndDoesNotEnableThemForImages() {
        val record = CloudModelRecord(kind = CloudModelKind.CHAT, apiFormat = CloudApiFormat.OPENAI_RESPONSES,
            providerName = "custom", displayName = "test", baseUrl = config.baseUrl, apiKey = "", modelName = "test",
            responsesReasoningEnabled = true, supportsVision = true)
        assertEquals(CloudApiFormat.OPENAI_RESPONSES, record.toChatConfig().apiFormat)
        assertTrue(record.toChatConfig().responsesReasoningEnabled)
        assertTrue(record.toChatConfig().supportsVision)
        assertFalse(record.toImageConfig().responsesReasoningEnabled)
    }

    private fun message(text: String) = JSONObject().put("type", "message").put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private fun completed(text: String) = JSONObject().put("status", "completed").put("output", JSONArray().put(message(text)))

    private fun decoderWithCompleteStreamCall(item: JSONObject, args: String): ResponsesDecoder = ResponsesDecoder().apply {
        event(JSONObject().put("type", "response.output_item.added").put("output_index", 0).put("item", item).toString())
        event(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
            .put("item_id", item.getString("id")).put("arguments", args).toString())
    }
}
