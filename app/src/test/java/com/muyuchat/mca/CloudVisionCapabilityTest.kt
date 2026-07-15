package com.muyuchat.mca

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVisionCapabilityTest {
    @Test
    fun cloudModelRecordPreservesVisionSupportInChatConfig() {
        val record = CloudModelRecord(
            kind = CloudModelKind.CHAT,
            apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
            providerName = "Custom",
            displayName = "MiMo V2.5",
            baseUrl = "https://example.com/v1",
            apiKey = "secret",
            modelName = "mimo-v2.5",
            supportsVision = true
        )

        assertTrue(record.toChatConfig().supportsVision)
        assertFalse(record.toImageConfig().supportsVision)
    }

    @Test
    fun legacyCloudVisionSupportInferenceCoversCommonMultimodalNames() {
        assertTrue(guessCloudVisionSupport("mimo-v2.5", "https://example.com/v1"))
        assertTrue(guessCloudVisionSupport("qwen-vl-plus", "https://example.com/v1"))
        assertTrue(guessCloudVisionSupport("claude-3-5-sonnet-latest", "https://api.anthropic.com/v1"))
        assertTrue(guessCloudVisionSupport("custom-chat", "https://mimo.mi.com/v1"))
        assertFalse(guessCloudVisionSupport("qwen3.5-4b-instruct", "https://example.com/v1"))
    }

    @Test
    fun mimoCloudRequestsIncludeApiKeyHeaderAlongsideBearer() {
        val request = Request.Builder()
            .url("https://mimo.mi.com/v1/chat/completions")
            .addCloudApiKeyHeaders(
                CloudApiConfig(
                    enabled = true,
                    baseUrl = "https://mimo.mi.com/v1",
                    apiKey = "secret",
                    chatModel = "mimo-v2.5"
                )
            )
            .build()

        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("secret", request.header("api-key"))
    }

    @Test
    fun coalescingCloudMessagesKeepsImageAttachments() {
        val image = ChatImageAttachment(
            name = "photo.png",
            mimeType = "image/png",
            dataBase64 = "data:image/png;base64,iVBORw0KGgo="
        )

        val coalesced = coalesceCloudChatMessagesByRole(
            listOf(
                ChatMessage(Role.USER, "First text"),
                ChatMessage(Role.USER, "Second text", imageAttachments = listOf(image)),
                ChatMessage(Role.ASSISTANT, "Reply")
            )
        )

        assertEquals(2, coalesced.size)
        assertEquals("First text\n\nSecond text", coalesced.first().content)
        assertEquals(listOf(image), coalesced.first().imageAttachments)
    }

    @Test
    fun openAiCompatibleVisionPayloadUsesImageUrlParts() {
        val dataUrl = "data:image/png;base64,iVBORw0KGgo="
        val root = buildOpenAiChatJson(
            config = CloudApiConfig(
                enabled = true,
                apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://openai-compatible.example/v1",
                chatModel = "mimo-v2.5",
                supportsVision = true
            ),
            request = ChatRequest(
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        content = "识别这张图片",
                        imageAttachments = listOf(
                            ChatImageAttachment(
                                name = "photo.png",
                                mimeType = "image/png",
                                dataBase64 = dataUrl
                            )
                        )
                    )
                ),
                params = GenerationParams(systemPrompt = "", reasoningMode = ReasoningMode.OFF)
            )
        )

        val messages = root.getJSONArray("messages")
        val userMessage = (0 until messages.length())
            .asSequence()
            .map { messages.getJSONObject(it) }
            .first { it.getString("role") == "user" }
        val content = userMessage.getJSONArray("content")

        assertEquals("mimo-v2.5", root.getString("model"))
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("识别这张图片", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(dataUrl, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse(root.getBoolean("enable_thinking"))
    }

    @Test
    fun anthropicVisionPayloadUsesBase64ImageParts() {
        val root = buildAnthropicChatJson(
            config = CloudApiConfig(
                enabled = true,
                apiFormat = CloudApiFormat.ANTHROPIC,
                baseUrl = "https://api.anthropic.com/v1",
                apiKey = "secret",
                chatModel = "claude-3-5-sonnet-latest",
                supportsVision = true
            ),
            request = ChatRequest(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "系统设定"),
                    ChatMessage(
                        role = Role.USER,
                        content = "识别这张图片",
                        imageAttachments = listOf(
                            ChatImageAttachment(
                                name = "photo.png",
                                mimeType = "image/png",
                                dataBase64 = "data:image/png;base64,iVBORw0KGgo="
                            )
                        )
                    )
                ),
                params = GenerationParams(systemPrompt = "", reasoningMode = ReasoningMode.STANDARD)
            )
        )

        val messages = root.getJSONArray("messages")
        val content = messages.getJSONObject(0).getJSONArray("content")
        val source = content.getJSONObject(0).getJSONObject("source")

        assertEquals("系统设定", root.getString("system"))
        assertEquals("claude-3-5-sonnet-latest", root.getString("model"))
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("image", content.getJSONObject(0).getString("type"))
        assertEquals("base64", source.getString("type"))
        assertEquals("image/png", source.getString("media_type"))
        assertEquals("iVBORw0KGgo=", source.getString("data"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("识别这张图片", content.getJSONObject(1).getString("text"))
        assertEquals("enabled", root.getJSONObject("thinking").getString("type"))
    }
}
