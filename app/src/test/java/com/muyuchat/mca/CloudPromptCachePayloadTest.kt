package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeMonotonicClock
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider prompt caching: Anthropic only caches a prefix that carries an explicit
 * `cache_control` breakpoint, OpenAI only accepts `prompt_cache_key` on its own endpoint, and the
 * reuse must be reported back as cache-hit stats instead of silently inflating prompt tokens.
 */
class CloudPromptCachePayloadTest {

    @Test
    fun anthropicSystemPromptIsSentAsOneCachedTextBlock() {
        val root = buildAnthropicChatJson(
            config = anthropicConfig(),
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "Continue the story.")),
                params = GenerationParams(systemPrompt = "Role: Mira", reasoningMode = ReasoningMode.STANDARD)
            )
        )

        val system = root.getJSONArray("system")
        assertEquals(1, system.length())
        val block = system.getJSONObject(0)
        assertEquals("text", block.getString("type"))
        assertTrue(block.getString("text").contains("Role: Mira"))
        assertEquals("ephemeral", block.getJSONObject("cache_control").getString("type"))
        // Message blocks stay unmarked so the request never exceeds four cache breakpoints.
        val message = root.getJSONArray("messages").getJSONObject(0)
        assertFalse(message.has("cache_control"))
        assertEquals("Continue the story.", message.getString("content"))
    }

    @Test
    fun anthropicRequestOmitsTheSystemFieldWhenNoSystemTextExists() {
        val root = buildAnthropicChatJson(
            config = anthropicConfig(),
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hi")),
                params = GenerationParams(systemPrompt = "", reasoningMode = ReasoningMode.STANDARD)
            )
        )

        assertFalse(root.has("system"))
        assertEquals("user", root.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun compatibleGatewayNeverReceivesPromptCacheKey() {
        val root = buildOpenAiChatJson(
            config = openAiConfig(baseUrl = "https://gateway.example.com/v1"),
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hi")),
                params = GenerationParams(systemPrompt = "sys"),
                persistentSessionId = "conversation-a"
            )
        )

        assertFalse(root.has("prompt_cache_key"))
    }

    @Test
    fun officialOpenAiEndpointReceivesOnlyTheHashedPromptCacheKey() {
        val sessionId = "conversation-a"
        val root = buildOpenAiChatJson(
            config = openAiConfig(baseUrl = "https://api.openai.com/v1"),
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hi")),
                params = GenerationParams(systemPrompt = "sys"),
                persistentSessionId = sessionId
            )
        )

        val key = root.getString("prompt_cache_key")
        assertFalse(key.contains(sessionId))
        assertTrue(key.matches(Regex("[0-9a-f]{32}")))
        assertEquals(sha256Prefix(sessionId), key)
        // Base-URL matching is case insensitive, as with the existing reasoning branch.
        val upperCaseBaseUrl = buildOpenAiChatJson(
            config = openAiConfig(baseUrl = "https://API.OpenAI.com/v1"),
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hi")),
                params = GenerationParams(systemPrompt = "sys"),
                persistentSessionId = sessionId
            )
        )
        assertEquals(key, upperCaseBaseUrl.getString("prompt_cache_key"))
    }

    @Test
    fun officialOpenAiEndpointWithoutSessionOmitsPromptCacheKey() {
        val root = buildOpenAiChatJson(
            config = openAiConfig(baseUrl = "https://api.openai.com/v1"),
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hi")),
                params = GenerationParams(systemPrompt = "sys")
            )
        )

        assertFalse(root.has("prompt_cache_key"))
    }

    @Test
    fun anthropicUsageExposesPromptCacheReadsSeparatelyFromInput() {
        val usage = CloudTokenUsage.parse(
            JSONObject(
                """{"input_tokens":20,"cache_read_input_tokens":80,"cache_creation_input_tokens":100,"output_tokens":1}"""
            ),
            anthropic = true
        )

        // Unchanged: total input still counts cache reads and cache writes.
        assertEquals(200, usage.input)
        assertEquals(1, usage.output)
        assertEquals(80, usage.cachedInput)
    }

    @Test
    fun negativeOrOverflowingCacheReadsAreTreatedAsMissing() {
        val negative = CloudTokenUsage.parse(
            JSONObject("""{"input_tokens":5,"cache_read_input_tokens":-3}"""),
            anthropic = true
        )
        assertNull(negative.cachedInput)
        assertEquals(5, negative.input)

        val overflowing = CloudTokenUsage.parse(
            JSONObject("""{"input_tokens":5,"cache_read_input_tokens":4294967296}"""),
            anthropic = true
        )
        assertNull(overflowing.cachedInput)
    }

    @Test
    fun openAiUsageExposesCachedPromptTokens() {
        val usage = CloudTokenUsage.parse(
            JSONObject(
                """{"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":64}}"""
            )
        )

        assertEquals(100, usage.input)
        assertEquals(7, usage.output)
        assertEquals(64, usage.cachedInput)
        assertNull(CloudTokenUsage.parse(JSONObject("""{"prompt_tokens":100}""")).cachedInput)
        assertNull(
            CloudTokenUsage.parse(
                JSONObject("""{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":-1}}""")
            ).cachedInput
        )
    }

    @Test
    fun mergeKeepsEarlierCachedInputWhenANewerEventOmitsIt() {
        val first = CloudTokenUsage(input = 200, output = 1, cachedInput = 80)

        val outputOnly = first.merge(CloudTokenUsage(output = 8))
        assertEquals(200, outputOnly.input)
        assertEquals(8, outputOnly.output)
        assertEquals(80, outputOnly.cachedInput)

        assertEquals(120, first.merge(CloudTokenUsage(cachedInput = 120)).cachedInput)
    }

    @Test
    fun doneStatsReportProviderPromptCacheReuse() = runBlocking {
        val body = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":20,\"cache_read_input_tokens\":80,\"cache_creation_input_tokens\":100,\"output_tokens\":1}}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n" +
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"
        val events = provider(body).streamChat(anthropicConfig(), request()).toList()
        val stats = (events.last() as GenerateEvent.Done).stats

        assertTrue(stats.cacheReuseHit)
        assertEquals(80, stats.cacheReusedTokens)
        assertEquals(1L, stats.cacheReuseHits)
        assertEquals(0L, stats.cacheReuseMisses)
        assertEquals("provider_prompt_cache", stats.cacheReuseReason)
        assertEquals(200, stats.promptTokens)
    }

    @Test
    fun doneStatsReportProviderPromptCacheMissWhenTheProviderReportsZero() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":0}}}\n\n" +
            "data: [DONE]\n\n"
        val stats = (provider(body)
            .streamChat(openAiConfig(baseUrl = "https://api.openai.com/v1"), request()).toList().last() as GenerateEvent.Done).stats

        assertFalse(stats.cacheReuseHit)
        assertEquals(0, stats.cacheReusedTokens)
        assertEquals(0L, stats.cacheReuseHits)
        assertEquals(1L, stats.cacheReuseMisses)
        assertEquals("provider_prompt_cache", stats.cacheReuseReason)
    }

    @Test
    fun doneStatsStayUnknownWithoutProviderCacheReporting() = runBlocking {
        val body = """{"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":42,"completion_tokens":9}}"""
        val stats = (provider(body)
            .streamChat(openAiConfig(baseUrl = "https://gateway.example.com/v1"), request()).toList().last() as GenerateEvent.Done).stats

        assertFalse(stats.cacheReuseHit)
        assertEquals(0, stats.cacheReusedTokens)
        assertEquals(0L, stats.cacheReuseHits)
        assertEquals(0L, stats.cacheReuseMisses)
        assertEquals("unknown", stats.cacheReuseReason)
    }

    private fun request() = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "question")),
        params = GenerationParams(systemPrompt = "system context"),
        runtimeSystemContext = "retrieved context"
    )

    private fun anthropicConfig() = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        apiKey = "test",
        chatModel = "claude-3-5-sonnet-latest"
    )

    private fun openAiConfig(baseUrl: String) = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKey = "test",
        chatModel = "model"
    )

    private fun sha256Prefix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)

    private fun provider(body: String) = OpenAiCompatibleChatProvider(
        OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, body)
        }.build(),
        RuntimeMonotonicClock { 1_000L }
    )

    private fun response(request: okhttp3.Request, code: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
