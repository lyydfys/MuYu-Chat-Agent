package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelUsage
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the OPENAI_COMPATIBLE prompted wire: the request declares no provider tools and the
 * stream only succeeds on a real terminal (`[DONE]` or `finish_reason`), never on EOF.
 */
class AgentChatCompletionsWireTest {

    private fun config() = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test/v1/",
        chatModel = "model-x",
        apiKey = "k"
    )

    private fun chatRequest() = ChatRequest(
        messages = listOf(
            ChatMessage(Role.SYSTEM, "AGENT SYSTEM", id = "s"),
            ChatMessage(Role.USER, "hello", id = "u")
        ),
        params = GenerationParams(temperature = 0.1f, nPredict = 64)
    )

    private fun event(data: String) = AgentSseEvent("", data)

    @Test
    fun `the request streams json with no provider tools`() {
        val request = agentChatCompletionsHttpRequest(config(), chatRequest(), promptedJson = true)
        assertEquals("https://example.test/v1/chat/completions", request.url.toString())
        assertEquals("Bearer k", request.header("Authorization"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("model-x", body.getString("model"))
        assertFalse("this wire must never declare provider tools", body.has("tools"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun `a plain endpoint is normalised once`() {
        assertEquals(
            "https://example.test/v1/chat/completions",
            agentChatCompletionsUrl("https://example.test/v1")
        )
        assertEquals(
            "https://example.test/v1/chat/completions",
            agentChatCompletionsUrl("https://example.test/v1/chat/completions/")
        )
    }

    @Test
    fun `done terminates with text and usage`() {
        val decoder = AgentChatCompletionsStreamDecoder()
        decoder.accept(event("""{"choices":[{"delta":{"content":"{\"protocol\":"}}]}"""))
        decoder.accept(event("""{"choices":[{"delta":{"content":"\"mca.agent.v1\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""))
        decoder.accept(event("[DONE]"))
        assertTrue(decoder.finished)
        val terminal = decoder.terminal() as AgentCloudStreamTerminal.Completed
        assertEquals("""{"protocol":"mca.agent.v1"}""", terminal.visibleText)
        assertEquals(AgentModelUsage(9, 4, false), terminal.usage)
    }

    @Test
    fun `finish_reason alone is a terminal because some gateways omit done`() {
        val decoder = AgentChatCompletionsStreamDecoder()
        decoder.accept(event("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        assertFalse("only done or a failure ends reading early", decoder.finished)
        val terminal = decoder.terminal() as AgentCloudStreamTerminal.Completed
        assertEquals("ok", terminal.visibleText)
        assertNull(terminal.usage)
    }

    @Test
    fun `a truncated turn is a typed failure`() {
        val decoder = AgentChatCompletionsStreamDecoder()
        decoder.accept(event("""{"choices":[{"delta":{"content":"{\"protocol\":"},"finish_reason":"length"}]}"""))
        val failure = decoder.terminal() as AgentCloudStreamTerminal.Failed
        assertEquals(AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED, failure.code)
    }

    @Test
    fun `an error frame fails the turn`() {
        val decoder = AgentChatCompletionsStreamDecoder()
        decoder.accept(event("""{"error":{"message":"upstream exploded"}}"""))
        assertTrue(decoder.finished)
        val failure = decoder.terminal() as AgentCloudStreamTerminal.Failed
        assertEquals(AgentErrorCodes.MODEL_PROTOCOL_ERROR, failure.code)
        assertTrue(failure.safeMessage.contains("upstream exploded"))
    }

    @Test
    fun `eof without a terminal is never success`() {
        val decoder = AgentChatCompletionsStreamDecoder()
        decoder.accept(event("""{"choices":[{"delta":{"content":"partial"}}]}"""))
        val terminal = decoder.terminal() as AgentCloudStreamTerminal.EofBeforeTerminal
        assertEquals("partial", terminal.visibleText)
    }

    @Test
    fun `visible deltas are emitted exactly once`() {
        val decoder = AgentChatCompletionsStreamDecoder()
        val seen = StringBuilder()
        decoder.accept(event("""{"choices":[{"delta":{"content":"ab"}}]}"""))
        decoder.drainVisible { seen.append(it) }
        decoder.accept(event("""{"choices":[{"delta":{"reasoning_content":"ignored"}}]}"""))
        decoder.drainVisible { seen.append(it) }
        decoder.accept(event("""{"choices":[{"delta":{"content":"cd"}}]}"""))
        decoder.drainVisible { seen.append(it) }
        assertEquals("abcd", seen.toString())
    }
}
