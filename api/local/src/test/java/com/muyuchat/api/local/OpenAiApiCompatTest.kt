package com.muyuchat.api.local

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.json.JSONArray
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
    fun keepsInlineImageDataUrlUsableForMultimodalForwarding() {
        val dataUrl = "data:image/png;base64,abc123"
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "user", "content": [
                  {"type": "text", "text": "识别这张图"},
                  {"type": "image_url", "image_url": {"url": "$dataUrl"}}
                ]}
              ]
            }
            """.trimIndent()
        )
        val rendered = JSONArray(request.messagesJson(multimodal = true))
        val content = (0 until rendered.length())
            .asSequence()
            .map { rendered.getJSONObject(it) }
            .first { it.getString("role") == "user" }
            .getJSONArray("content")

        assertEquals(dataUrl, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun infersMimeTypeFromInlineImageDataUrl() {
        val dataUrl = "data:image/png;base64,abc123"
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "user", "content": [
                  {"type": "text", "text": "识别这张图"},
                  {"type": "image_url", "image_url": {"url": "$dataUrl"}}
                ]}
              ]
            }
            """.trimIndent()
        )
        val attachment = request.messages.single().imageAttachments.single()

        assertEquals("image/png", attachment.mimeType)
        assertEquals(dataUrl, attachment.dataBase64)
    }

    @Test
    fun decodesPercentEncodedTextFromThirdPartyClients() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "system", "content": "You%20are%20a%20saved%20character%20card."},
                {"role": "user", "content": "Please%20answer%20in%20Chinese%3A%20%E4%BD%A0%E5%A5%BD"}
              ],
              "system_prompt": "Top%20level%20persona"
            }
            """.trimIndent()
        )

        assertEquals("You are a saved character card.", request.messages[0].content)
        assertEquals("Please answer in Chinese: 你好", request.messages[1].content)
        assertEquals("Top level persona", request.params.systemPrompt)
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
    fun parsesResponsesStyleInputImageParts() {
        val dataUrl = "data:image/jpeg;base64,abc123"
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "input": [
                {
                  "role": "user",
                  "content": [
                    {"type": "input_text", "text": "这张图里有什么？"},
                    {"type": "input_image", "image_url": "$dataUrl"}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, request.messages.size)
        assertEquals(Role.USER, request.messages.single().role)
        assertEquals("这张图里有什么？", request.messages.single().content)
        assertEquals(dataUrl, request.messages.single().imageAttachments.single().dataBase64)

        val renderedMessages = JSONArray(request.messagesJson(multimodal = true))
        val rendered = (0 until renderedMessages.length())
            .asSequence()
            .map { renderedMessages.getJSONObject(it) }
            .first { it.getString("role") == "user" }
            .getJSONArray("content")
        assertEquals("text", rendered.getJSONObject(0).getString("type"))
        assertEquals("image_url", rendered.getJSONObject(1).getString("type"))
        assertEquals(dataUrl, rendered.getJSONObject(1).getJSONObject("image_url").getString("url"))
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
    fun inheritsCurrentMcaPersonaWhenClientDoesNotSendSystemMessage() {
        val request = OpenAiApiCompat.parseChatRequest(
            """{"messages":[{"role":"user","content":"你好"}]}""",
            baseParams = GenerationParams(
                systemPrompt = "你是用户在 MCA 中保存的角色设定。",
                temperature = 0.25f,
                nPredict = 1234
            )
        )
        val rendered = JSONArray(request.messagesJson())

        assertEquals("你是用户在 MCA 中保存的角色设定。\n\n请直接回答，不展示思考过程。", rendered.getJSONObject(0).getString("content"))
        assertEquals(0.25f, request.params.temperature)
        assertEquals(1234, request.params.nPredict)
    }

    @Test
    fun keepsThirdPartyCharacterCardSystemMessageAheadOfMcaPersona() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [
                {"role": "system", "content": "你是第三方客户端发送的角色卡。"},
                {"role": "user", "content": "自我介绍"}
              ]
            }
            """.trimIndent(),
            baseParams = GenerationParams(systemPrompt = "MCA 当前默认角色设定不应覆盖客户端角色卡。")
        )
        val renderedSystem = JSONArray(request.messagesJson())
            .getJSONObject(0)
            .getString("content")

        assertTrue(renderedSystem.contains("你是第三方客户端发送的角色卡。"))
        assertFalse(renderedSystem.contains("MCA 当前默认角色设定不应覆盖客户端角色卡。"))
    }

    @Test
    fun usesTopLevelSystemPromptWhenClientProvidesOne() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "system_prompt": "顶层角色设定",
              "messages": [
                {"role": "user", "content": "你好"}
              ]
            }
            """.trimIndent(),
            baseParams = GenerationParams(
                systemPrompt = "MCA 当前默认角色设定不应覆盖客户端顶层设定。",
                reasoningMode = ReasoningMode.STANDARD
            )
        )
        val rendered = JSONArray(request.messagesJson())

        assertEquals("顶层角色设定", request.params.systemPrompt)
        assertEquals("顶层角色设定", rendered.getJSONObject(0).getString("content"))
        assertFalse(rendered.getJSONObject(0).getString("content").contains("MCA 当前默认角色设定"))
    }

    @Test
    fun exposesCorsAndOpenAiStyleErrorJson() {
        val cors = OpenAiApiCompat.corsHeaders()
        val error = OpenAiApiCompat.errorJson("unauthorized", "bad key")

        assertTrue(cors.contains("Access-Control-Allow-Origin: *"))
        assertTrue(cors.contains("Access-Control-Allow-Methods: GET, POST, OPTIONS"))
        assertTrue(cors.contains("Idempotency-Key"))
        assertTrue(cors.contains("Access-Control-Expose-Headers: Retry-After, X-Retry-After-Ms"))
        assertTrue(cors.contains("Access-Control-Allow-Private-Network: true"))
        assertEquals("bad key", error.getJSONObject("error").getString("message"))
        assertEquals("mca_error", error.getJSONObject("error").getString("type"))
        assertEquals("unauthorized", error.getJSONObject("error").getString("code"))
    }

    @Test
    fun apiRequestGenerationParamsPreserveRuntimeOwnedFields() {
        val request = OpenAiApiCompat.parseChatRequest(
            """
            {
              "messages": [{"role": "user", "content": "测速"}],
              "max_tokens": 3072,
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
            """.trimIndent(),
            baseParams = GenerationParams(
                nCtx = 32768,
                nThreads = 8,
                chatTemplateMode = "runtime-owned-template",
                advancedJson = """{"n_batch":2048}"""
            )
        )

        val json = org.json.JSONObject(request.params.toJson())

        assertEquals(32768, json.getInt("n_ctx"))
        assertEquals(3072, json.getInt("n_predict"))
        assertEquals(8, json.getInt("n_threads"))
        assertEquals("runtime-owned-template", json.getString("chat_template_mode"))
        assertEquals(2048, json.getJSONObject("advanced_json").getInt("n_batch"))
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

    @Test
    fun rejectsRuntimeAndUnknownAdvancedFieldsInsteadOfPassingThemToNative() {
        val result = OpenAiApiCompat.parseChatRequestChecked(
            """
            {
              "messages": [{"role": "user", "content": "hello"}],
              "n_batch": 2048,
              "n_ubatch": 256,
              "flash_attn": "on",
              "spec_type": "draft-mtp",
              "spec_draft_n_max": 2,
              "n_parallel": 1,
              "advanced_json": {"future_native": {"enabled": true}}
            }
            """.trimIndent()
        )

        assertTrue(result is OpenAiChatParseResult.Rejected)
        val rejection = (result as OpenAiChatParseResult.Rejected).rejection
        val details = org.json.JSONObject(rejection.detailsJson)
        val fields = details.getJSONArray("restricted_fields").toString()

        assertEquals("parameter_scope_conflict", rejection.code)
        assertEquals("generation_only", details.getString("allowed_scope"))
        assertTrue(fields.contains("n_batch"))
        assertTrue(fields.contains("flash_attn"))
        assertTrue(fields.contains("spec_type"))
        assertTrue(fields.contains("advanced_json"))
    }

    @Test
    fun rejectsAllLoadExecutionTemplateGpuMoeAndMtpEntryPoints() {
        val restrictedFields = listOf(
            "n_ctx" to "32768",
            "n_threads" to "8",
            "chat_template_mode" to "\"jinja\"",
            "n_gpu_layers" to "99",
            "main_gpu" to "1",
            "n_cpu_moe" to "5",
            "spec_type" to "\"draft-mtp\"",
            "mnn_backend" to "\"opencl\"",
            "future_native_switch" to "true",
            "advanced_json" to "{\"future_native\":true}"
        )

        restrictedFields.forEach { (field, value) ->
            val result = OpenAiApiCompat.parseChatRequestChecked(
                """{"messages":[{"role":"user","content":"hi"}],"$field":$value}"""
            )
            assertTrue("Expected $field to be rejected", result is OpenAiChatParseResult.Rejected)
            val rejection = (result as OpenAiChatParseResult.Rejected).rejection
            assertEquals("parameter_scope_conflict", rejection.code)
            assertTrue(rejection.detailsJson.contains(field))
        }
    }

    @Test
    fun rejectsRestrictedFieldsNestedInsideExtraBody() {
        val result = OpenAiApiCompat.parseChatRequestChecked(
            """{"messages":[{"role":"user","content":"hi"}],"extra_body":{"n_ctx":16384}}"""
        )

        assertTrue(result is OpenAiChatParseResult.Rejected)
        val details = (result as OpenAiChatParseResult.Rejected).rejection.detailsJson
        assertTrue(details.contains("extra_body.n_ctx"))
    }
}
