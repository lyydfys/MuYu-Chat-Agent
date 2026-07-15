package com.muyuchat.core.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParamsTest {
    @Test
    fun standardReasoningUsesCompactThinkingBudget() {
        val json = JSONObject(GenerationParams(reasoningMode = ReasoningMode.STANDARD).toJson())

        assertTrue(json.getBoolean("enable_thinking"))
        assertEquals(192, json.getInt("thinking_budget"))
        assertTrue(json.getInt("n_predict") >= 2048)
        assertEquals(1.08, json.getDouble("repeat_penalty"), 0.001)
        assertEquals(0.2, json.getDouble("frequency_penalty"), 0.001)
    }

    @Test
    fun offReasoningDisablesThinking() {
        val json = JSONObject(GenerationParams(reasoningMode = ReasoningMode.OFF).toJson())

        assertFalse(json.getBoolean("enable_thinking"))
        assertEquals(0, json.getInt("thinking_budget"))
        assertTrue(json.getBoolean("hide_reasoning"))
    }

    @Test
    fun reasoningInstructionDoesNotInjectScaffoldOrThinkSlashCommands() {
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "Who are you?")),
            params = GenerationParams(reasoningMode = ReasoningMode.STANDARD)
        )
        val messages = JSONArray(request.messagesJson())
        val system = messages.getJSONObject(0).getString("content")

        assertFalse(system.contains("Thinking Process"))
        assertFalse(system.contains("Output Format"))
        assertFalse(system.contains("System Requirements"))
        assertFalse(system.contains("Constraint"))
        assertFalse(system.contains("/think"))
        assertFalse(system.contains("/no_think"))
    }

    @Test
    fun multimodalMessagesUseOpenAiImageUrlPartsOnlyWhenRequested() {
        val message = ChatMessage(
            role = Role.USER,
            content = "Describe it",
            imageAttachments = listOf(
                ChatImageAttachment(
                    name = "photo.jpg",
                    mimeType = "image/jpeg",
                    dataBase64 = "abc123"
                )
            )
        )
        val request = ChatRequest(messages = listOf(message), params = GenerationParams(systemPrompt = ""))

        val textOnly = JSONArray(request.messagesJson())
            .userMessage()
            .get("content")
        val multimodal = JSONArray(request.messagesJson(multimodal = true))
            .userMessage()
            .getJSONArray("content")

        assertEquals("Describe it", textOnly)
        assertEquals("text", multimodal.getJSONObject(0).getString("type"))
        assertEquals("image_url", multimodal.getJSONObject(1).getString("type"))
        assertTrue(
            multimodal.getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/jpeg;base64,abc123")
        )
    }

    @Test
    fun mnnMultimodalMessagesPutAllImageTagsBeforeTextWithoutAddedWhitespace() {
        val message = ChatMessage(
            role = Role.USER,
            content = "Describe both images",
            imageAttachments = listOf(
                ChatImageAttachment(
                    name = "first.jpg",
                    uriString = "/images/first.jpg",
                    mimeType = "image/jpeg"
                ),
                ChatImageAttachment(
                    name = "second.png",
                    uriString = "/images/second.png",
                    mimeType = "image/png"
                )
            )
        )
        val request = ChatRequest(messages = listOf(message), params = GenerationParams(systemPrompt = ""))

        val content = JSONArray(
            request.messagesJson(
                multimodal = true,
                contentEncoding = MultimodalContentEncoding.MNN_IMAGE_TAGS_FIRST
            )
        ).userMessage().getString("content")

        assertEquals(
            "<img>/images/first.jpg</img><img>/images/second.png</img>Describe both images",
            content
        )
        assertFalse(content.contains("</img>\n"))
    }

    @Test
    fun mnnImageTagEncodingLeavesPureTextContentUnchanged() {
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "Text only")),
            params = GenerationParams(systemPrompt = "")
        )

        val content = JSONArray(
            request.messagesJson(
                multimodal = true,
                contentEncoding = MultimodalContentEncoding.MNN_IMAGE_TAGS_FIRST
            )
        ).userMessage().getString("content")

        assertEquals("Text only", content)
    }

    @Test
    fun multimodalMessagesPreserveInlineDataUrlsFromApiClients() {
        val dataUrl = "data:image/png;base64,iVBORw0KGgo="
        val message = ChatMessage(
            role = Role.USER,
            content = "What is in this image?",
            imageAttachments = listOf(
                ChatImageAttachment(
                    name = "api-image.png",
                    mimeType = "image/png",
                    dataBase64 = dataUrl
                )
            )
        )
        val multimodal = JSONArray(ChatRequest(listOf(message), GenerationParams(systemPrompt = "")).messagesJson(multimodal = true))
            .userMessage()
            .getJSONArray("content")
        val url = multimodal.getJSONObject(1).getJSONObject("image_url").getString("url")

        assertEquals(dataUrl, url)
        assertEquals("iVBORw0KGgo=", message.imageAttachments.single().plainBase64())
    }

    @Test
    fun generationParamsRoundTripKeepsPersonaAndSamplingSettings() {
        val original = GenerationParams(
            nCtx = 4096,
            nPredict = 2048,
            nThreads = 6,
            temperature = 0.42f,
            topK = 32,
            topP = 0.88f,
            minP = 0.03f,
            repeatPenalty = 1.12f,
            presencePenalty = 0.15f,
            frequencyPenalty = 0.25f,
            seed = 123,
            systemPrompt = "你是一张长期保存的角色卡。",
            stopWords = listOf("</s>", "<|end|>"),
            chatTemplateMode = "auto",
            advancedJson = """{"mirostat":0}""",
            reasoningMode = ReasoningMode.STANDARD,
            hideReasoning = false
        )

        val restored = GenerationParams.fromJson(original.toJson())

        assertEquals(original.nCtx, restored.nCtx)
        assertEquals(original.nPredict, restored.nPredict)
        assertEquals(original.nThreads, restored.nThreads)
        assertEquals(original.temperature, restored.temperature)
        assertEquals(original.topK, restored.topK)
        assertEquals(original.topP, restored.topP)
        assertEquals(original.minP, restored.minP)
        assertEquals(original.repeatPenalty, restored.repeatPenalty)
        assertEquals(original.presencePenalty, restored.presencePenalty)
        assertEquals(original.frequencyPenalty, restored.frequencyPenalty)
        assertEquals(original.seed, restored.seed)
        assertEquals(original.systemPrompt, restored.systemPrompt)
        assertEquals(original.stopWords, restored.stopWords)
        assertEquals(original.chatTemplateMode, restored.chatTemplateMode)
        assertEquals(0, JSONObject(restored.advancedJson).getInt("mirostat"))
        assertEquals(
            LlamaAdvancedParams.CURRENT_SCHEMA_VERSION,
            JSONObject(restored.advancedJson).getInt("schema_version")
        )
        assertEquals(original.reasoningMode, restored.reasoningMode)
        assertEquals(original.hideReasoning, restored.hideReasoning)
    }

    @Test
    fun loadParamsJsonCarriesContextLengthForNativeBackends() {
        val json = JSONObject(LoadParams(nCtx = 32768, nThreads = 7).toJson())

        assertEquals(32768, json.getInt("n_ctx"))
        assertEquals(7, json.getInt("n_threads"))
    }

    @Test
    fun advancedJsonIsSerializedAsNativeConfigObject() {
        val json = JSONObject(
            GenerationParams(
                advancedJson = """{"max_all_tokens":32768,"thread_num":6,"jinja":{"context":{"enable_thinking":false}}}"""
            ).toJson()
        )
        val advanced = json.getJSONObject("advanced_json")

        assertEquals(32768, advanced.getInt("max_all_tokens"))
        assertEquals(6, advanced.getInt("thread_num"))
        assertFalse(advanced.getJSONObject("jinja").getJSONObject("context").getBoolean("enable_thinking"))
    }

    private fun JSONArray.userMessage(): JSONObject =
        (0 until length())
            .asSequence()
            .map { getJSONObject(it) }
            .first { it.getString("role") == "user" }
}
