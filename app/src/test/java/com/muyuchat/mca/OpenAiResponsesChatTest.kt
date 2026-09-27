package com.muyuchat.mca

import android.content.SharedPreferences
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolDefinition
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiResponsesChatTest {
    private val config = CloudApiConfig(enabled = true, apiFormat = CloudApiFormat.OPENAI_RESPONSES,
        baseUrl = "https://example.test/v1", chatModel = "custom-model", apiKey = "test-key")
    private val request = ChatRequest(listOf(ChatMessage(Role.USER, "hi")), GenerationParams(systemPrompt = ""))
    private val completed = """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}"""

    @Test fun realProviderDispatchesResponsesAndPreservesWhitespaceThroughAllSnapshots() = runBlocking {
        val text = "hello null\n  world"
        val stream = listOf("hello", " ", "null", "\n", "  world").joinToString("") {
            sse(JSONObject().put("type", "response.output_text.delta").put("delta", it).toString())
        } + sse(JSONObject().put("type", "response.completed").put("response", JSONObject(completed)
            .apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text", text) }
            .put("usage", JSONObject().put("input_tokens", 42).put("output_tokens", 9))).toString())
        var sent: Request? = null
        val events = OpenAiCompatibleChatProvider(client(stream, "text/event-stream") { sent = it }).streamChat(config, request).toList()
        assertEquals("/v1/responses", sent!!.url.encodedPath)
        assertEquals("Bearer test-key", sent!!.header("Authorization"))
        val payload = payload(sent!!)
        assertTrue(payload.getBoolean("stream")); assertFalse(payload.getBoolean("store"))
        assertFalse(payload.has("messages"))
        assertEquals(text, visible(events))
        assertTrue(events.last() is GenerateEvent.Done)
        assertEquals(42, (events.last() as GenerateEvent.Done).stats.promptTokens)
        assertEquals(9, (events.last() as GenerateEvent.Done).stats.completionTokens)
        assertFalse((events.last() as GenerateEvent.Done).stats.promptTokensEstimated)
        assertFalse((events.last() as GenerateEvent.Done).stats.completionTokensEstimated)
    }

    @Test fun eventNameAndMultilineDataWorkWithoutDataType() = runBlocking {
        val wire = "\uFEFF: heartbeat\r\nevent: response.output_text.delta\r\ndata: {\r\ndata: \"delta\": \"OK\"}\r\n\r\n" +
            "event: response.completed\ndata: {\"response\":$completed}\n\n"
        val events = OpenAiCompatibleChatProvider(client(wire, "text/event-stream")).streamChat(config, request).toList()
        assertEquals("OK", visible(events)); assertTrue(events.last() is GenerateEvent.Done)
    }

    @Test fun terminalOnlyAndJsonFallbackWorkWithoutTextDuplication() = runBlocking {
        for ((body, media) in listOf(completed to "application/json", sse("""{"type":"response.completed","response":$completed}""") to "text/event-stream")) {
            val events = OpenAiCompatibleChatProvider(client(body, media)).streamChat(config, request).toList()
            assertEquals("OK", visible(events)); assertTrue(events.last() is GenerateEvent.Done)
            assertTrue((events.last() as GenerateEvent.Done).stats.promptTokensEstimated)
            assertTrue((events.last() as GenerateEvent.Done).stats.completionTokensEstimated)
        }
    }

    @Test fun structuredToolCallsAreReturnedOnlyAtDoneAndNeverAsAssistantText() = runBlocking {
        val arguments = "{\"prompt\":\"a blue fox\"}"
        val wire = sse(JSONObject().put("type", "response.output_item.added").put("output_index", 0)
            .put("item", JSONObject().put("id", "fc_item").put("type", "function_call")
                .put("call_id", "call_bluefox").put("name", "generate_image").put("arguments", "")).toString()) +
            sse(JSONObject().put("type", "response.function_call_arguments.delta").put("output_index", 0)
                .put("item_id", "fc_item").put("delta", "{\"prompt\":\"a blue fox\"").toString()) +
            sse(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
                .put("item_id", "fc_item").put("arguments", arguments).toString()) +
            sse(JSONObject().put("type", "response.completed").put("response", JSONObject()
                .put("status", "completed").put("output", org.json.JSONArray().put(JSONObject()
                    .put("id", "fc_item").put("type", "function_call").put("call_id", "call_bluefox")
                    .put("name", "generate_image").put("arguments", arguments)))).toString())
        var sent: Request? = null
        val definition = ChatToolDefinition("generate_image", "Generate an image", "{\"type\":\"object\",\"properties\":{}}")
        val events = OpenAiCompatibleChatProvider(client(wire, "text/event-stream") { sent = it })
            .streamChat(config.copy(supportsTools = true), request.copy(tools = listOf(definition))).toList()
        assertEquals("", visible(events))
        assertTrue(events.last() is GenerateEvent.Done)
        assertEquals(listOf(ChatToolCall("call_bluefox", "generate_image", arguments)), (events.last() as GenerateEvent.Done).toolCalls)
        val payload = payload(sent!!)
        assertEquals("auto", payload.getString("tool_choice"))
        assertEquals("generate_image", payload.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test fun undeclaredStructuredToolIsRejectedInsteadOfExposedToTheCaller() = runBlocking {
        val wire = sse(JSONObject().put("type", "response.completed").put("response", JSONObject()
            .put("status", "completed").put("output", org.json.JSONArray().put(JSONObject()
                .put("type", "function_call").put("call_id", "call_bad").put("name", "delete_everything")
                .put("arguments", "{}")))).toString())
        val events = OpenAiCompatibleChatProvider(client(wire, "text/event-stream"))
            .streamChat(config, request.copy(tools = listOf(ChatToolDefinition("generate_image", "", "{\"type\":\"object\"}"))))
            .toList()
        assertTrue(events.last() is GenerateEvent.Error)
        assertFalse(events.any { it is GenerateEvent.Done })
    }

    @Test fun stopWordEndingANonterminalStreamCannotExecuteAnAccumulatedToolCall() = runBlocking {
        val arguments = "{\"prompt\":\"do not run\"}"
        val wire = sse(JSONObject().put("type", "response.output_item.added").put("output_index", 0)
            .put("item", JSONObject().put("type", "function_call").put("call_id", "call_stopped")
                .put("name", "generate_image").put("arguments", "")).toString()) +
            sse(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
                .put("arguments", arguments).toString()) +
            sse(JSONObject().put("type", "response.output_text.delta").put("output_index", 1)
                .put("delta", "visible<STOP>hidden").toString())
        val events = OpenAiCompatibleChatProvider(client(wire, "text/event-stream"))
            .streamChat(config.copy(supportsTools = true), request.copy(tools = listOf(ChatToolDefinition("generate_image", "", "{\"type\":\"object\"}")),
                params = request.params.copy(stopWords = listOf("<STOP>")))).toList()
        assertEquals("visible", visible(events))
        assertTrue(events.last() is GenerateEvent.Done)
        assertTrue((events.last() as GenerateEvent.Done).toolCalls.isEmpty())
    }

    @Test fun partialStreamEofMalformedEventsAndProviderFailuresDoNotEmitDone() = runBlocking {
        val delta = sse("""{"type":"response.output_text.delta","delta":"partial"}""")
        val failures = listOf(delta, delta + "data: [DONE]\n\n", "data: {bad}\n\n",
            sse("""{"type":"error","message":"quota exceeded"}"""),
            sse("""{"type":"response.failed","response":{"status":"failed","error":{"message":"bad model"}}}"""),
            "{}")
        for (body in failures) {
            val events = OpenAiCompatibleChatProvider(client(body, "text/event-stream")).streamChat(config, request).toList()
            assertTrue(body, events.last() is GenerateEvent.Error)
            assertFalse(events.any { it is GenerateEvent.Done })
        }
    }

    @Test fun incompleteKeepsPartialAnswerAndExplainsLimit() = runBlocking {
        val body = JSONObject(completed).put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
        val events = OpenAiCompatibleChatProvider(client(body.toString())).streamChat(config, request).toList()
        assertEquals("OK", visible(events))
        assertTrue((events.last() as GenerateEvent.Error).message.contains("输出上限"))
    }

    @Test fun reasoningCanBeDisplayedOrHiddenWithoutBecomingAssistantBody() = runBlocking {
        val wire = sse("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"brief summary"}""") +
            sse("""{"type":"response.output_text.delta","output_index":1,"delta":"OK"}""") +
            sse("""{"type":"response.completed","response":{"status":"completed","output":[]}}""")
        for (hidden in listOf(false, true)) {
            val events = OpenAiCompatibleChatProvider(client(wire, "text/event-stream")).streamChat(config,
                request.copy(params = request.params.copy(reasoningMode = ReasoningMode.STANDARD, hideReasoning = hidden))).toList()
            assertEquals("OK", visible(events))
            assertEquals(if (hidden) "" else "brief summary", events.filterIsInstance<GenerateEvent.Chunk>().joinToString("") { it.reasoning })
        }
    }

    @Test fun localStopAcrossFramesFinishesAndUnmatchedPrefixIsFlushed() = runBlocking {
        for ((parts, expected) in listOf(listOf("hello<EN", "D>not visible") to "hello", listOf("hello<EN") to "hello<EN")) {
            val wire = parts.joinToString("") { sse(JSONObject().put("type", "response.output_text.delta").put("delta", it).toString()) } +
                sse("""{"type":"response.completed","response":{"status":"completed","output":[]}}""")
            val events = OpenAiCompatibleChatProvider(client(wire, "text/event-stream")).streamChat(config,
                request.copy(params = request.params.copy(stopWords = listOf("<END>")))).toList()
            assertEquals(expected, visible(events)); assertTrue(events.last() is GenerateEvent.Done)
        }
    }

    @Test fun quickProbeUsesNonStreamResponsesAndRejectsHtmlOrFalseSuccess() = runBlocking {
        var sent: Request? = null
        val provider = OpenAiCompatibleChatProvider(client(completed) { sent = it })
        assertTrue(provider.quickTest(config).isSuccess)
        assertEquals("/v1/responses", sent!!.url.encodedPath)
        assertFalse(payload(sent!!).getBoolean("stream"))
        assertTrue(payload(sent!!).getInt("max_output_tokens") >= 16)
        for (body in listOf("<html>gateway</html>", "{}", """{"status":"failed","error":{"message":"denied"}}""")) {
            assertTrue(OpenAiCompatibleChatProvider(client(body)).quickTest(config).isFailure)
        }
        val limited = JSONObject(completed).put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
        assertTrue(OpenAiCompatibleChatProvider(client(limited.toString())).quickTest(config).isSuccess)
    }

    @Test fun existingChatCompletionsAndAnthropicRemainOnTheirOwnEndpoints() = runBlocking {
        for ((format, path, body) in listOf(
            Triple(CloudApiFormat.OPENAI_COMPATIBLE, "/v1/chat/completions", "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"),
            Triple(CloudApiFormat.ANTHROPIC, "/v1/messages", "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n")
        )) {
            var sent: Request? = null
            val events = OpenAiCompatibleChatProvider(client(body, "text/event-stream") { sent = it })
                .streamChat(config.copy(apiFormat = format), request).toList()
            assertEquals(path, sent!!.url.encodedPath)
            assertTrue(payload(sent!!).has("messages")); assertFalse(payload(sent!!).has("input"))
            assertEquals("OK", visible(events)); assertTrue(events.last() is GenerateEvent.Done)
            if (format == CloudApiFormat.ANTHROPIC) assertEquals("test-key", sent!!.header("x-api-key"))
        }
    }

    @Test fun simultaneousRequestsDoNotShareDecoderOrStopState() = runBlocking {
        val provider = OpenAiCompatibleChatProvider(OkHttpClient.Builder().addInterceptor { chain ->
            val input = payload(chain.request()).getJSONArray("input")
            val text = input.getJSONObject(input.length() - 1).getString("content")
            response(chain.request(), JSONObject(completed).apply {
                getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text", text)
            }.toString(), "application/json")
        }.build())
        val first = async { provider.streamChat(config, request.copy(messages = listOf(ChatMessage(Role.USER, "one")))).toList() }
        val second = async { provider.streamChat(config, request.copy(messages = listOf(ChatMessage(Role.USER, "two")))).toList() }
        assertEquals("one", visible(first.await())); assertEquals("two", visible(second.await()))
    }

    @Test fun cancellingBeforeHeadersOrDuringIdleStreamClosesTheActualSocket() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val entered = CountDownLatch(1)
                val closed = CountDownLatch(1)
                val serving = thread(isDaemon = true) {
                    server.accept().use { peer ->
                        peer.soTimeout = 5000
                        val input = peer.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { /* request headers */ }
                        if (sendHeaders) {
                            peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n: idle\n\n".toByteArray())
                            peer.getOutputStream().flush()
                        }
                        entered.countDown()
                        try { while (input.read() >= 0) Unit } catch (_: java.io.IOException) { }
                        finally { closed.countDown() }
                    }
                }
                val events = java.util.Collections.synchronizedList(mutableListOf<GenerateEvent>())
                val provider = OpenAiCompatibleChatProvider()
                val job = launch { provider.streamChat(config.copy(baseUrl = "http://127.0.0.1:${server.localPort}/v1"), request).collect { events.add(it) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    withTimeout(2500) { job.cancelAndJoin() }
                    assertTrue("HTTP socket must close on stop", withContext(Dispatchers.IO) { closed.await(2, TimeUnit.SECONDS) })
                    assertFalse(events.any { it is GenerateEvent.Done || it is GenerateEvent.Error })
                } finally { job.cancelAndJoin(); serving.join(5500) }
            }
        }
    }

    @Test fun saveReloadAndLegacyDefaultsPreserveProtocolAndReasoningCapability() {
        val prefs = memoryPreferences()
        val store = CloudApiStore(prefs)
        val persisted = config.copy(apiKey = "", providerName = CloudApiFormat.OPENAI_RESPONSES.label,
            supportsVision = true, responsesReasoningEnabled = true)
        store.save(persisted)
        val reloaded = CloudApiStore(prefs).load()
        assertEquals(CloudApiFormat.OPENAI_RESPONSES, reloaded.apiFormat)
        assertEquals(CloudApiFormat.OPENAI_RESPONSES.label, reloaded.providerName)
        assertTrue(reloaded.responsesReasoningEnabled); assertTrue(reloaded.supportsVision)
        val record = CloudModelRecord(kind = CloudModelKind.CHAT, apiFormat = reloaded.apiFormat,
            providerName = "gateway", displayName = "test", baseUrl = reloaded.baseUrl, apiKey = "", modelName = "model",
            responsesReasoningEnabled = true, supportsVision = true)
        store.saveModels(listOf(record))
        assertEquals(record, CloudApiStore(prefs).loadModels().single())
        val legacy = CloudApiStore(memoryPreferences()).load()
        assertEquals(CloudApiFormat.OPENAI_COMPATIBLE, legacy.apiFormat)
        assertFalse(legacy.responsesReasoningEnabled)
    }

    @Test fun savingChatProtocolsNeverReclassifiesTheirProviderAsImages() {
        for (format in CloudApiFormat.entries) {
            val store = CloudApiStore(memoryPreferences())
            store.save(config.copy(apiKey = "", apiFormat = format, providerName = format.label))
            assertEquals(format.label, store.load().providerName)
        }
        val image = config.copy(providerName = CloudImageApiFormat.DASHSCOPE_IMAGE.label,
            imageApiFormat = CloudImageApiFormat.DASHSCOPE_IMAGE, baseUrl = "https://dashscope.aliyuncs.com")
        assertEquals(CloudImageApiFormat.DASHSCOPE_IMAGE.label, image.normalizedForImageRequest().providerName)
        assertEquals(CloudImageApiFormat.DASHSCOPE_IMAGE.defaultEndpointPath, image.normalizedForImageRequest().imageEndpointPath)
    }

    private fun client(body: String, media: String = "application/json", inspect: (Request) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain -> inspect(chain.request()); response(chain.request(), body, media) }.build()
    private fun response(request: Request, body: String, media: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody(media.toMediaType())).build()
    private fun payload(request: Request): JSONObject = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun sse(json: String) = "data: $json\n\n"
    private fun visible(events: List<GenerateEvent>) = events.filterIsInstance<GenerateEvent.Chunk>().joinToString("") { it.text }

    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args[0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args[0]); proxy }
                method.name == "apply" -> null
                method.name == "commit" -> true
                else -> error(method.name)
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(args[0])
                method.name.startsWith("get") -> values[args[0]] ?: args[1]
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
