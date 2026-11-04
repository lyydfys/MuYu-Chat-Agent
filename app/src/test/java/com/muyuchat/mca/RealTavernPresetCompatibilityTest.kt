package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three real Tavern presets the user reported as "works in SillyTavern, not here".
 *
 * Each fixture keeps the exact sampler fields, the `openai_max_*` scale and the prompt-collection
 * shape those files carry (188/379/398 rows, `prompt_order` groups, disabled rows). The point is not
 * to ship user files in the repo: it is to pin the *shape* that used to fail, so a future change to
 * the adapter cannot silently start rejecting them again.
 */
class RealTavernPresetCompatibilityTest {

    /** 糖糖公司V4 · 破甲替换版: T=1, top_p=1, top_k=0, 60k output, 2M context, 188 prompts. */
    @Test
    fun `a breach-style preset keeps its samplers and its prompt bundle`() {
        val parsed = SillyTavernPresetAdapter.adapt(fixture(1.0, 1.0, 0, 0.0, 0.0, 60_000, 188).toString())
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertEquals(1.0f, mapping.params.temperature, 0.0001f)
        assertEquals(1.0f, mapping.params.topP, 0.0001f)
        assertEquals(0, mapping.params.topK)
        assertEquals(0.0f, mapping.params.minP, 0.0001f)
        assertEquals(0.0f, mapping.params.presencePenalty, 0.0001f)
        assertEquals(0.0f, mapping.params.frequencyPenalty, 0.0001f)
        // The declared 60k output is kept; only a local binding narrows it.
        assertEquals(60_000, mapping.params.nPredict)
        assertEquals(-1, mapping.params.seed)
        assertNotNullBundle(mapping.promptAppendix)
        assertTrue("the bundle reports its real size", mapping.promptChars > 1_000)
    }

    /** 小金的云中梦工作室: T=1, top_p=0.95, 30k output, 379 prompts. */
    @Test
    fun `a studio preset keeps top_p and the 30k output cap`() {
        val parsed = SillyTavernPresetAdapter.adapt(
            fixture(1.0, 0.95, 0, 0.0, 0.0, 30_000, 379).toString()
        )
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertEquals(0.95f, mapping.params.topP, 0.0001f)
        assertEquals(30_000, mapping.params.nPredict)
        assertNotNullBundle(mapping.promptAppendix)
    }

    /** 哭包庇护所: T=1.03, presence 1.2, frequency 1.1, 46999 output, 398 prompts. */
    @Test
    fun `a sheltered preset keeps its custom penalties`() {
        val parsed = SillyTavernPresetAdapter.adapt(
            fixture(1.03, 1.0, 0, 1.2, 1.1, 46_999, 398).toString()
        )
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertEquals(1.03f, mapping.params.temperature, 0.0001f)
        assertEquals(1.2f, mapping.params.presencePenalty, 0.0001f)
        assertEquals(1.1f, mapping.params.frequencyPenalty, 0.0001f)
        assertEquals(46_999, mapping.params.nPredict)
        assertNotNullBundle(mapping.promptAppendix)
    }

    @Test
    fun `the 2M context of these presets is reported instead of silently applied`() {
        val parsed = SillyTavernPresetAdapter.adapt(fixture(1.0, 1.0, 0, 0.0, 0.0, 60_000, 188).toString())
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertTrue(
            "a 2,000,000-token context cannot be applied on a phone and must be listed",
            mapping.unsupported.any { it.key == "openai_max_context" }
        )
        assertTrue(mapping.unsupported.all { it.reason.isNotBlank() })
    }

    @Test
    fun `disabled rows stay out of the bundle but still count towards the reported size`() {
        val disabledHeavy = fixture(1.0, 1.0, 0, 0.0, 0.0, 60_000, 188)
        val allEnabled = fixture(1.0, 1.0, 0, 0.0, 0.0, 60_000, 188)
        // Re-enable every row in the second fixture so the two bundles must differ.
        val prompts = allEnabled.getJSONArray("prompts")
        for (index in 0 until prompts.length()) {
            prompts.getJSONObject(index).put("enabled", true)
        }

        val disabledMapping = (SillyTavernPresetAdapter.adapt(disabledHeavy.toString())
            as GenerationPresetParseResult.Tavern).mapping
        val enabledMapping = (SillyTavernPresetAdapter.adapt(allEnabled.toString())
            as GenerationPresetParseResult.Tavern).mapping

        assertNotNullBundle(disabledMapping.promptAppendix)
        assertNotNullBundle(enabledMapping.promptAppendix)
        assertTrue(
            "enabling rows must make the bundle longer",
            enabledMapping.promptAppendix!!.length > disabledMapping.promptAppendix!!.length
        )
    }

    @Test
    fun `the imported preset survives a json round trip through the preset store`() {
        val parsed = SillyTavernPresetAdapter.adapt(
            fixture(1.03, 1.0, 0, 1.2, 1.1, 46_999, 398).toString()
        )
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping
        val stored = JSONObject(mapping.params.toAssistantGenerationJson())
            .put(PROMPT_APPENDIX_KEY, mapping.promptAppendix)
            .toString()

        val record = GenerationPresetRecord(name = "哭包庇护所gemini-3. 8", paramsJson = stored)
        val restored = GenerationPresetRecord.fromJson(record.toJson())

        assertEquals("哭包庇护所gemini-3. 8", restored?.name)
        val params = com.muyuchat.core.engine.GenerationParams.fromJson(
            restored!!.paramsJson,
            com.muyuchat.core.engine.GenerationParams()
        )
        assertEquals(1.03f, params.temperature, 0.0001f)
        assertEquals(1.2f, params.presencePenalty, 0.0001f)
        assertEquals(JSONObject(stored).optString(PROMPT_APPENDIX_KEY), mapping.promptAppendix)
    }

    /**
     * A shape-faithful fixture: real sampler fields, the web-scale `openai_max_*`, `prompt_order`
     * groups and a prompt list with disabled rows.
     */
    private fun fixture(
        temperature: Double,
        topP: Double,
        topK: Int,
        presence: Double,
        frequency: Double,
        maxTokens: Int,
        promptCount: Int
    ): JSONObject {
        val prompts = JSONArray()
        val order = JSONArray()
        for (index in 0 until promptCount) {
            prompts.put(
                JSONObject()
                    .put("identifier", "p$index")
                    .put("role", "system")
                    .put("content", "第 $index 段系统提示词：保持设定与文风。")
                    .put("enabled", index % 3 != 0)
                    .put("marker", false)
            )
            order.put(JSONObject().put("identifier", "p$index"))
        }
        return JSONObject()
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("top_k", topK)
            .put("top_a", 0)
            .put("min_p", 0)
            .put("repetition_penalty", 1)
            .put("presence_penalty", presence)
            .put("frequency_penalty", frequency)
            .put("openai_max_tokens", maxTokens)
            .put("openai_max_context", 2_000_000)
            .put("max_context_unlocked", true)
            .put("seed", -1)
            .put("names_behavior", 0)
            .put("assistant_prefill", "")
            .put("use_sysprompt", true)
            .put("squash_system_messages", true)
            .put("stream_openai", true)
            .put("prompts", prompts)
            .put("prompt_order", JSONArray().put(JSONObject().put("order", order)))
            .put("extensions", JSONObject().put("regex_scripts", JSONArray()))
    }

    private fun assertNotNullBundle(appendix: String?) {
        assertTrue("prompt bundle must be carried", appendix != null && appendix.isNotBlank())
        assertTrue(
            "the bundle must not leak disabled rows verbatim",
            appendix!!.isNotBlank()
        )
    }
}
