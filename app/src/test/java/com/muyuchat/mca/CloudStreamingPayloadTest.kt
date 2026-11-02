package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud record can disable SSE for gateways whose stream never sends a finish marker. In that
 * mode the request must be a plain JSON call and must not advertise stream options; the default
 * record keeps the existing streaming contract.
 */
class CloudStreamingPayloadTest {

    @Test
    fun `non streaming record sends a plain json request`() {
        val json = buildOpenAiChatJson(config(streaming = false), request())
        assertFalse(json.getBoolean("stream"))
        assertFalse(json.has("stream_options"))
    }

    @Test
    fun `streaming record keeps the sse contract`() {
        val json = buildOpenAiChatJson(config(streaming = true), request())
        assertTrue(json.getBoolean("stream"))
        assertTrue(json.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    private fun request() = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "hi")),
        params = GenerationParams()
    )

    private fun config(streaming: Boolean) = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
        providerName = "test",
        displayName = "test",
        baseUrl = "https://example.test/v1",
        apiKey = "k",
        chatModel = "m",
        streaming = streaming
    )
}
