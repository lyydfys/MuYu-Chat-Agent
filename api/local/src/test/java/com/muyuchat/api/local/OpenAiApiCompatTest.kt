package com.muyuchat.api.local

import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiApiCompatTest {
    @Test
    fun parsesOpenAiChatMessagesWithTextParts() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "system", "content": "你是 MCA"},
                {"role": "user", "content": [
                  {"type": "text", "text": "你好"},
                  {"type": "image_url", "image_url": {"url": "ignored"}},
                  {"type": "input_text", "text": "，介绍一下"}
                ]}
              ],
              "max_tokens": 1024,
              "temperature": 0.3,
              "top_k": 20,
              "top_p": 0.95,
              "min_p": 0.0,
              "repetition_penalty": 1.0,
              "presence_penalty": 1.5,
              "show_reasoning": true
            }
            """.trimIndent()
        )

        assertEquals(Role.SYSTEM, request.messages[0].role)
        assertEquals(Role.USER, request.messages[1].role)
        assertEquals("你好，介绍一下", request.messages[1].content)
        assertEquals(1, request.messages[1].imageAttachments.size)
        assertEquals("ignored", request.messages[1].imageAttachments.single().uriString)
        assertEquals(1024, request.params.nPredict)
        assertEquals(0.3f, request.params.temperature)
        assertEquals(20, request.params.topK)
        assertEquals(0.95f, request.params.topP)
        assertEquals(0.0f, request.params.minP)
        assertEquals(1.0f, request.params.repeatPenalty)
        assertEquals(1.5f, request.params.presencePenalty)
        assertEquals(0.2f, request.params.frequencyPenalty)
        assertEquals(ReasoningMode.STANDARD, request.params.reasoningMode)
        assertFalse(request.params.hideReasoning)
    }

    @Test
    fun parsesCompletionsPromptAndClampsMaxTokens() {
        val request = OpenAiApiCompat.parseChatRequest(
            """{"prompt":"写一句中文问候","max_tokens":99999,"stream":false}"""
        )

        assertEquals(1, request.messages.size)
        assertEquals("写一句中文问候", request.messages[0].content)
        assertEquals(65536, request.params.nPredict)
        assertFalse(OpenAiApiCompat.isStreamingRequest("""{"stream":false}"""))
    }

    @Test
    fun detectsStreamingFlagAcrossCommonClientEncodings() {
        assertTrue(OpenAiApiCompat.isStreamingRequest("""{"stream":true}"""))
        assertTrue(OpenAiApiCompat.isStreamingRequest("""{"stream":"true"}"""))
        assertTrue(OpenAiApiCompat.isStreamingRequest("""{"stream":1}"""))
        assertFalse(OpenAiApiCompat.isStreamingRequest("""{"stream":"false"}"""))
        assertFalse(OpenAiApiCompat.isStreamingRequest("""{"messages":[]}"""))
    }

    @Test
    fun parsesResponsesStyleInputArray() {
        val request = OpenAiApiCompat.parseChatRequest(
            """{"input":["第一段",{"content":[{"type":"input_text","text":"第二段"}]}],"reasoning_mode":"advanced"}"""
        )

        assertEquals("第一段\n第二段", request.messages[0].content)
        assertEquals(ReasoningMode.ADVANCED, request.params.reasoningMode)
    }

    @Test
    fun keepsInvalidJsonAsPlainUserPrompt() {
        val request = OpenAiApiCompat.parseChatRequest("plain prompt")

        assertEquals("plain prompt", request.messages.single().content)
    }

    @Test
    fun addsUserTurnForThirdPartyConnectionTestMessages() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "system", "content": "Connection test"},
                {"role": "assistant", "content": "Welcome"}
              ],
              "stream": true
            }
            """.trimIndent()
        )

        assertEquals(Role.SYSTEM, request.messages[0].role)
        assertEquals(Role.ASSISTANT, request.messages[1].role)
        assertEquals(Role.USER, request.messages[2].role)
        assertEquals("Continue.", request.messages[2].content)
    }

    @Test
    fun coalescesMultipleSystemMessagesForLocalChatTemplates() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "system", "content": "Write Assistant's next reply."},
                {"role": "system", "content": "[Start a new Chat]"},
                {"role": "user", "content": "Reply in Chinese"}
              ],
              "stream": true
            }
            """.trimIndent()
        )

        assertEquals(2, request.messages.size)
        assertEquals(Role.SYSTEM, request.messages[0].role)
        assertEquals("Write Assistant's next reply.\n\n[Start a new Chat]", request.messages[0].content)
        assertEquals(Role.USER, request.messages[1].role)
        assertEquals("Reply in Chinese", request.messages[1].content)
    }

    @Test
    fun exposesCorsAndOpenAiStyleErrorJson() {
        val cors = OpenAiApiCompat.corsHeaders()
        val error = OpenAiApiCompat.errorJson("unauthorized", "bad key")

        assertTrue(cors.contains("Access-Control-Allow-Origin: *"))
        assertTrue(cors.contains("Access-Control-Allow-Methods: GET, POST, OPTIONS"))
        assertTrue(cors.contains("Access-Control-Allow-Private-Network: true"))
        assertEquals("bad key", error.getJSONObject("error").getString("message"))
        assertEquals("mca_error", error.getJSONObject("error").getString("type"))
        assertEquals("unauthorized", error.getJSONObject("error").getString("code"))
    }

    @Test
    fun apiRequestParamsMapToNativeGenerationJson() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [{"role": "user", "content": "测速"}],
              "n_ctx": 4096,
              "max_tokens": 3072,
              "n_threads": 8,
              "temperature": 0.55,
              "top_k": 30,
              "top_p": 0.9,
              "min_p": 0.05,
              "repeat_penalty": 1.08,
              "presence_penalty": 0.1,
              "frequency_penalty": 0.2,
              "seed": 42,
              "stop": ["</s>"],
              "reasoning_mode": "standard",
              "show_reasoning": true
            }
            """.trimIndent()
        )

        val json = org.json.JSONObject(request.params.toJson())

        assertEquals(4096, json.getInt("n_ctx"))
        assertEquals(3072, json.getInt("n_predict"))
        assertEquals(8, json.getInt("n_threads"))
        assertEquals(0.55, json.getDouble("temperature"), 0.001)
        assertEquals(30, json.getInt("top_k"))
        assertEquals(0.9, json.getDouble("top_p"), 0.001)
        assertEquals(0.05, json.getDouble("min_p"), 0.001)
        assertEquals(1.08, json.getDouble("repeat_penalty"), 0.001)
        assertEquals(0.2, json.getDouble("frequency_penalty"), 0.001)
        assertEquals(42, json.getInt("seed"))
        assertEquals("</s>", json.getJSONArray("stop_words").getString(0))
        assertEquals("standard", json.getString("reasoning_mode"))
        assertFalse(json.getBoolean("hide_reasoning"))
    }
}
