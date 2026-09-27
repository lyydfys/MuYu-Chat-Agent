package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudTokenUsageTest {
    private val config = CloudApiConfig(enabled = true, baseUrl = "https://example.test/v1", apiKey = "test", chatModel = "model")
    private val request = ChatRequest(listOf(ChatMessage(Role.USER, "question")),
        GenerationParams(systemPrompt = "system context"), runtimeSystemContext = "retrieved context")

    @Test fun openAiFinalUsageOnlyChunkReplacesEstimatesWithoutLosingText() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1245,\"completion_tokens\":86}}\n\n" +
            "data: [DONE]\n\n"
        val events = provider(body).streamChat(config, request).toList()
        val stats = (events.last() as GenerateEvent.Done).stats
        assertEquals("OK", events.filterIsInstance<GenerateEvent.Chunk>().joinToString("") { it.text })
        assertEquals(1245, stats.promptTokens)
        assertEquals(86, stats.completionTokens)
        assertFalse(stats.promptTokensEstimated)
        assertFalse(stats.completionTokensEstimated)
    }

    @Test fun anthropicMergesStartInputCacheAndCumulativeOutput() = runBlocking {
        val body = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":20,\"cache_read_input_tokens\":80,\"cache_creation_input_tokens\":100,\"output_tokens\":1}}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n" +
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}\n\n" +
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"
        val events = provider(body).streamChat(config.copy(apiFormat = CloudApiFormat.ANTHROPIC), request).toList()
        val streamed = events.filterIsInstance<GenerateEvent.Chunk>().single().stats
        assertFalse(streamed.promptTokensEstimated)
        assertTrue(streamed.completionTokensEstimated)
        val stats = (events.last() as GenerateEvent.Done).stats
        assertEquals(200, stats.promptTokens)
        assertEquals(8, stats.completionTokens)
        assertFalse(stats.promptTokensEstimated)
        assertFalse(stats.completionTokensEstimated)
    }

    @Test fun jsonFallbackReadsUsageInBothProtocols() = runBlocking {
        val bodies = listOf(
            CloudApiFormat.OPENAI_COMPATIBLE to """{"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":42,"completion_tokens":9}}""",
            CloudApiFormat.ANTHROPIC to """{"content":[{"type":"text","text":"OK"}],"usage":{"input_tokens":42,"output_tokens":9}}"""
        )
        for ((format, body) in bodies) {
            val stats = (provider(body).streamChat(config.copy(apiFormat = format), request).toList().last() as GenerateEvent.Done).stats
            assertEquals(42, stats.promptTokens)
            assertEquals(9, stats.completionTokens)
            assertFalse(stats.promptTokensEstimated)
            assertFalse(stats.completionTokensEstimated)
        }
    }

    @Test fun missingUsageIsExplicitlyEstimatedAndIncludesSystemAndRetrievedText() = runBlocking {
        val stats = (provider("""{"choices":[{"message":{"content":"Hi"}}]}""")
            .streamChat(config, request).toList().last() as GenerateEvent.Done).stats
        assertTrue(stats.promptTokensEstimated)
        assertTrue(stats.completionTokensEstimated)
        assertEquals(1, stats.completionTokens)
        assertTrue(stats.promptTokens > request.messages.sumOf { it.content.length } / 4)
    }

    @Test fun zeroPartialAndInvalidUsageAreNotConfusedWithMissing() {
        val partial = CloudTokenUsage.parse(JSONObject("""{"prompt_tokens":0,"completion_tokens":-1}"""))
        assertEquals(0, partial.input)
        assertNull(partial.output)
        assertNull(CloudTokenUsage.parse(JSONObject("""{"prompt_tokens":4294967296}""")).input)
        assertEquals(CloudTokenUsage(0, 9), partial.merge(CloudTokenUsage(output = 9)))
    }

    @Test fun requestsUsageButRetriesAnExplicitUnsupportedFieldRejectionOnce() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            calls++
            if (calls == 1) {
                assertTrue(json.getJSONObject("stream_options").getBoolean("include_usage"))
                response(chain.request(), 400, """{"error":{"message":"Unknown parameter: stream_options"}}""")
            } else {
                assertFalse(json.has("stream_options"))
                response(chain.request(), 200, """{"choices":[{"message":{"content":"OK"}}]}""")
            }
        }.build()
        val events = OpenAiCompatibleChatProvider(client).streamChat(config, request).toList()
        assertTrue(events.last() is GenerateEvent.Done)
        assertEquals(2, calls)
    }

    @Test fun unrelatedOrAuthenticationFailuresAreNeverRetriedForUsage() = runBlocking {
        for ((code, error) in listOf(400 to "Unknown model", 401 to "Unsupported stream_options for this key")) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                response(chain.request(), code, JSONObject().put("error", JSONObject().put("message", error)).toString())
            }.build()
            val events = OpenAiCompatibleChatProvider(client).streamChat(config, request).toList()
            assertTrue(events.last() is GenerateEvent.Error)
            assertEquals(1, calls)
        }
    }

    private fun provider(body: String) = OpenAiCompatibleChatProvider(OkHttpClient.Builder().addInterceptor { chain ->
        response(chain.request(), 200, body)
    }.build())

    private fun response(request: okhttp3.Request, code: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
