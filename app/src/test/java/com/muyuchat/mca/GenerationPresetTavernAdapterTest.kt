package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the SillyTavern preset compatibility layer.
 *
 * The user report was "presets that work in SillyTavern all fail in MCA". The old assistant-card
 * codec threw on the first surprising value (a null, a negative `top_k`, a temperature above 2),
 * so one field aborted the whole file, and there was no way to tell which fields had been dropped.
 * These tests fix the four promises of [SillyTavernPresetAdapter]:
 *  1. a real Tavern chat-completion preset is recognised and every sampler field is mapped;
 *  2. out-of-range values are clamped *and recorded*, never fatal;
 *  3. prompt/behaviour fields and unknown keys are reported (unsupported / ignored), not silently
 *     swallowed;
 *  4. MCA presets and non-preset JSON are never misclassified.
 */
class GenerationPresetTavernAdapterTest {

    /** Trimmed copy of the public Tavern chat-completion preset shape (no user file is used). */
    private val tavernSample = """
        {
          "name": "Sample CoT",
          "temperature": 1.2,
          "frequency_penalty": 0.7,
          "presence_penalty": 0.3,
          "top_p": 0.9,
          "top_k": 60,
          "min_p": 0.05,
          "max_tokens": 400,
          "openai_max_context": 4095,
          "openai_max_tokens": 400,
          "stop": [["\n###", "\nUser:"]],
          "names_behavior": 0,
          "wrap_in_quotes": false,
          "stream_openai": true,
          "continue_prefill": true,
          "squash_system_messages": false,
          "use_sysprompt": true,
          "assistant_prefill": "",
          "wi_format": "{0}",
          "scenario_format": "{{scenario}}",
          "personality_format": "{{personality}}",
          "impersonation_prompt": "[Write your next reply as {{user}}.]",
          "new_chat_prompt": "[Start a new chat]",
          "new_group_chat_prompt": "[Start a new group chat]",
          "new_example_chat_prompt": "[Example Chat]",
          "system_prompt": "Stay in character.",
          "instruct": {"enabled": true, "input_sequence": "User:"},
          "prompts": [{"identifier": "main", "content": "Write {{char}}'s reply."}],
          "prompt_order": [{"character_id": 100001, "order": [{"identifier": "main", "enabled": true}]}]
        }
    """.trimIndent()

    private fun tavern(raw: String = tavernSample): TavernPresetMapping {
        val parsed = SillyTavernPresetAdapter.adapt(raw)
        assertTrue("expected a Tavern preset, was $parsed", parsed is GenerationPresetParseResult.Tavern)
        return (parsed as GenerationPresetParseResult.Tavern).mapping
    }

    private fun rejected(raw: String): String {
        val parsed = SillyTavernPresetAdapter.adapt(raw)
        assertTrue("expected a rejection, was $parsed", parsed is GenerationPresetParseResult.Rejected)
        return (parsed as GenerationPresetParseResult.Rejected).reason
    }

    @Test
    fun `real tavern chat completion preset maps every sampler field`() {
        val mapping = tavern()

        assertEquals("Sample CoT", mapping.declaredName)
        assertEquals(1.2f, mapping.params.temperature)
        assertEquals(0.9f, mapping.params.topP)
        assertEquals(60, mapping.params.topK)
        assertEquals(0.05f, mapping.params.minP)
        assertEquals(0.3f, mapping.params.presencePenalty)
        assertEquals(0.7f, mapping.params.frequencyPenalty)
        assertEquals(400, mapping.params.nPredict)
        assertEquals(listOf("\n###", "\nUser:"), mapping.params.stopWords)
        assertTrue(mapping.hasApplicableValue)
        assertTrue(mapping.clamped.isEmpty())
        assertTrue(mapping.mappedKeys.containsAll(listOf("temperature", "top_p", "top_k", "min_p")))
    }

    @Test
    fun `openai_max_tokens is mapped when max_tokens is absent`() {
        val mapping = tavern("""{"temperature":0.8,"top_p":0.95,"openai_max_tokens":300}""")
        assertEquals(300, mapping.params.nPredict)
    }

    @Test
    fun `null and unusable numbers are skipped instead of aborting the file`() {
        val mapping = tavern(
            """{"temperature":0.8,"top_p":null,"min_p":null,"top_k":"nope","max_tokens":null}"""
        )

        assertEquals(0.8f, mapping.params.temperature)
        // Null/absent keeps the MCA default rather than failing the import.
        assertEquals(GenerationParams().topP, mapping.params.topP)
        assertEquals(GenerationParams().topK, mapping.params.topK)
        assertEquals(GenerationParams().nPredict, mapping.params.nPredict)
    }

    @Test
    fun `numeric strings are accepted`() {
        val mapping = tavern("""{"temperature":"0.85","top_p":"0.92","top_k":"40"}""")
        assertEquals(0.85f, mapping.params.temperature)
        assertEquals(0.92f, mapping.params.topP)
        assertEquals(40, mapping.params.topK)
    }

    @Test
    fun `out of range values are clamped and every clamp is reported`() {
        val mapping = tavern(
            """{
              "temperature": 3.5,
              "top_p": 1.4,
              "top_k": 500,
              "max_tokens": 2,
              "presence_penalty": 5,
              "repeat_penalty": 3,
              "min_p": -1
            }""".trimIndent()
        )

        assertEquals(2f, mapping.params.temperature)
        assertEquals(1f, mapping.params.topP)
        assertEquals(SillyTavernPresetAdapter.MAX_TOP_K, mapping.params.topK)
        // The importer no longer invents an output floor either: 2 is the author's value.
        assertEquals(2, mapping.params.nPredict)
        assertEquals(2f, mapping.params.presencePenalty)
        assertEquals(2f, mapping.params.repeatPenalty)
        assertEquals(0f, mapping.params.minP)

        assertEquals(6, mapping.clamped.size)
        val temperatureClamp = mapping.clamped.first { it.key == "temperature" }
        assertEquals("3.5", temperatureClamp.requested)
        assertEquals("2.0", temperatureClamp.applied)
        assertTrue(temperatureClamp.warning.contains("已按 2.0 使用"))
    }

    @Test
    fun `negative top_k is treated as disabled`() {
        val mapping = tavern("""{"temperature":0.7,"top_p":0.9,"top_k":-1}""")
        assertEquals(0, mapping.params.topK)
        assertTrue(mapping.clamped.any { it.key == "top_k" })
    }

    @Test
    fun `grouped stop arrays are flattened and capped`() {
        val many = (1..40).joinToString(",") { "\"s$it\"" }
        val mapping = tavern("""{"temperature":0.7,"top_p":0.9,"stop":[["\n","###"],$many]}""")

        assertEquals("\n", mapping.params.stopWords.first())
        assertEquals(SillyTavernPresetAdapter.MAX_STOP_WORDS, mapping.params.stopWords.size)
        assertTrue("the cap must be observable", mapping.clamped.any { it.key == "stop" })
    }

    @Test
    fun `unknown keys are ignored but listed`() {
        val mapping = tavern(
            """{"temperature":0.7,"top_p":0.9,"my_custom_slider":7,"another_flag":true}"""
        )

        assertEquals(listOf("my_custom_slider", "another_flag"), mapping.ignoredKeys)
        assertTrue(mapping.unsupported.isEmpty())
    }

    @Test
    fun `prompt and behaviour fields are explicitly unsupported with a reason`() {
        val mapping = tavern()
        val keys = mapping.unsupported.associate { it.key to it.reason }

        listOf(
            "system_prompt", "instruct", "prompts", "prompt_order", "wi_format",
            "scenario_format", "personality_format", "impersonation_prompt",
            "new_chat_prompt", "new_group_chat_prompt", "new_example_chat_prompt",
            "wrap_in_quotes", "names_behavior", "continue_prefill",
            "squash_system_messages", "stream_openai", "use_sysprompt", "assistant_prefill"
        ).forEach { key ->
            assertTrue("missing unsupported entry for $key", keys.containsKey(key))
            assertTrue("empty reason for $key", !keys.getValue(key).isNullOrBlank())
        }
        assertTrue(keys.getValue("assistant_prefill").contains("prefill"))
        // Unsupported fields are not double-reported as unknown.
        assertTrue(mapping.unsupported.map { it.key }.intersect(mapping.ignoredKeys.toSet()).isEmpty())
    }

    @Test
    fun `context length is load bound and carries a suggestion`() {
        val mapping = tavern("""{"temperature":0.8,"top_p":0.9,"context_length":2048}""")

        val entry = mapping.unsupported.first { it.key == "context_length" }
        assertTrue(entry.reason.contains("n_ctx=2048"))
        assertTrue(entry.reason.contains("重新加载"))
        // It never reaches the applied params (the preset has no n_ctx slot).
        assertEquals(GenerationParams().nCtx, mapping.params.nCtx)
    }

    @Test
    fun `older settings wrapper is still recognised`() {
        val mapping = tavern("""{"settings":{"temperature":0.9,"top_p":0.8},"sampler":null}""")
        assertEquals(0.9f, mapping.params.temperature)
        assertEquals(0.8f, mapping.params.topP)
    }

    @Test
    fun `mca preset file is not misclassified as tavern`() {
        val raw = """
            {
              "schema": "mca.assistant.generation.v2",
              "n_predict": 512,
              "max_tokens": 512,
              "temperature": 0.8,
              "top_k": 40,
              "top_p": 0.9,
              "min_p": 0.0,
              "repeat_penalty": 1.08,
              "presence_penalty": 0.0,
              "frequency_penalty": 0.2,
              "system_prompt": "miaow",
              "stop_words": ["</s>"],
              "reasoning_mode": "off",
              "hide_reasoning": true
            }
        """.trimIndent()

        val parsed = SillyTavernPresetAdapter.adapt(raw)
        assertTrue("expected the MCA dialect, was $parsed", parsed is GenerationPresetParseResult.Mca)
        val mapping = (parsed as GenerationPresetParseResult.Mca).mapping
        assertEquals(512, mapping.params.nPredict)
        assertEquals(listOf("</s>"), mapping.params.stopWords)
        assertTrue(mapping.hasApplicableValue)
        assertTrue("system_prompt has no preset slot", mapping.unsupported.any { it.key == "system_prompt" })
    }

    @Test
    fun `mca n_ctx is reported as load bound`() {
        val raw = """{"n_predict":256,"temperature":0.8,"top_k":40,"top_p":0.9,
            "stop_words":["</s>"],"n_ctx":4096}""".trimIndent()

        val parsed = SillyTavernPresetAdapter.adapt(raw) as GenerationPresetParseResult.Mca
        val entry = parsed.mapping.unsupported.first { it.key == "n_ctx" }
        assertTrue(entry.reason.contains("n_ctx=4096"))
        assertTrue(entry.reason.contains("重新加载"))
    }

    @Test
    fun `a single shared key is not enough to call a file a preset`() {
        val reason = rejected("""{"temperature":1}""")
        assertTrue(reason.contains("既不是酒馆预设也不是 MCA 预设"))
    }

    @Test
    fun `unrelated json is rejected`() {
        assertTrue(rejected("""{"hello":"world"}""").contains("既不是酒馆预设"))
    }

    @Test
    fun `broken and empty json are rejected with a reason`() {
        assertTrue(rejected("{").contains("不是有效的 JSON"))
        assertTrue(rejected("{}").contains("既不是酒馆预设"))
        assertTrue(rejected("[1,2,3]").contains("不是有效的 JSON"))
    }

    @Test
    fun `prompt only preset is rejected instead of storing an empty preset`() {
        val reason = rejected(
            """{"system_prompt":"Stay in character.","new_chat_prompt":"[Start]"}"""
        )
        assertTrue(reason.contains("没有可应用的采样参数"))
    }

    @Test
    fun `file name becomes the preset name including spaces and chinese`() {
        val fallback = GenerationPresetNaming.fallbackName("  我的 酒馆 预设.json  ", null)
        assertEquals("我的 酒馆 预设", fallback)
        assertEquals("我的 酒馆 预设", GenerationPresetNaming.recordName(fallback))
        // A single-use file may be named by its author with digits only.
        assertEquals("2024", GenerationPresetNaming.recordName("2024"))
    }

    @Test
    fun `import report counts fields and keeps duplicate file names distinct`() {
        val report = GenerationPresetImportReport()
            .plus(
                ImportedGenerationPreset(
                    name = "Night Drive",
                    paramsJson = "{}",
                    appliedFields = 8,
                    clampedFields = 2,
                    ignoredFields = listOf("a", "b"),
                    unsupportedFields = listOf("system_prompt", "prompts")
                )
            )
            .plus(
                ImportedGenerationPreset(
                    name = "Night Drive",
                    paramsJson = "{}",
                    appliedFields = 7,
                    clampedFields = 0,
                    ignoredFields = emptyList(),
                    unsupportedFields = listOf("instruct")
                )
            )

        assertEquals(2, report.names.size)
        assertEquals(15, report.appliedFields)
        assertEquals(2, report.clampedFields)

        val suffix = report.detailSuffix()
        assertTrue(suffix.contains("应用 15 项"))
        assertTrue(suffix.contains("钳制 2 项"))
        assertTrue(suffix.contains("忽略 2 项（a、b）"))
        assertTrue(suffix.contains("不支持 3 项（system_prompt、prompts、instruct）"))

        val records = listOf(
            GenerationPresetRecord(name = "Night Drive", paramsJson = "{}"),
            GenerationPresetRecord(name = "Night Drive", paramsJson = "{}")
        )
        assertEquals("same file name is imported twice, not merged", 2, records.size)
        assertEquals(
            "each record keeps its own identity",
            2,
            records.map { it.id }.distinct().size
        )
    }

    @Test
    fun `preset reader caps the byte ceiling and decodes utf8`() {
        val text = """{"temperature":0.8,"label":"中文 预设"}"""
        val decoded = readGenerationPresetJson(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, decoded)

        try {
            readGenerationPresetJson(
                ByteArrayInputStream(ByteArray(64) { 'a'.code.toByte() }),
                maxBytes = 8
            )
            fail("an oversized preset must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("MiB"))
        }
    }

    @Test
    fun `mca default params are untouched by an absent field`() {
        val defaults = GenerationParams(temperature = 0.4f, topK = 12, topP = 0.5f)
        val parsed = SillyTavernPresetAdapter.adapt("""{"temperature":0.9,"top_p":0.8}""", defaults)
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertEquals(0.9f, mapping.params.temperature)
        assertEquals(0.8f, mapping.params.topP)
        assertEquals("unmapped sampler value keeps the caller default", 12, mapping.params.topK)
        assertFalse(mapping.params.systemPrompt.isBlank())
        assertNull(mapping.params.seed)
    }
}
