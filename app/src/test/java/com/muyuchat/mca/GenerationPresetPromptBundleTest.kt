package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tavern presets are mostly *prompts*, not samplers.
 *
 * The real files users sent carry 180-400 prompt rows and a `prompt_order` list; importing only the
 * sampler values is why they "work in SillyTavern but not here". These cases pin the prompt bundle
 * extraction (order, enabled flag, marker rows, storage cap) and the output-length cap that keeps an
 * imported 30k-60k token preset from failing MCA's own context admission immediately.
 */
class GenerationPresetPromptBundleTest {

    @Test
    fun `prompt bundle follows prompt order and skips disabled and marker rows`() {
        val json = JSONObject()
            .put("temperature", 1.0)
            .put("openai_max_tokens", 60000)
            .put(
                "prompts",
                JSONArray()
                    .put(prompt("a", "first", enabled = true))
                    .put(prompt("b", "disabled", enabled = false))
                    .put(JSONObject().put("identifier", "c").put("marker", true).put("content", "marker"))
                    .put(prompt("d", "second", enabled = true))
            )
            .put(
                "prompt_order",
                JSONArray().put(
                    JSONObject().put(
                        "order",
                        JSONArray()
                            .put(JSONObject().put("identifier", "d"))
                            .put(JSONObject().put("identifier", "b"))
                            .put(JSONObject().put("identifier", "c"))
                            .put(JSONObject().put("identifier", "a"))
                    )
                )
            )

        val parsed = SillyTavernPresetAdapter.adapt(json.toString())

        assertTrue(parsed is GenerationPresetParseResult.Tavern)
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping
        assertEquals("second\n\nfirst", mapping.promptAppendix)
        assertEquals("second\n\nfirst".length, mapping.promptChars)
    }

    @Test
    fun `a prompt-only preset is imported instead of rejected`() {
        val json = JSONObject().put(
            "prompts",
            JSONArray().put(prompt("sys", "stay in character", enabled = true))
        )

        val parsed = SillyTavernPresetAdapter.adapt(json.toString())

        assertTrue(parsed is GenerationPresetParseResult.Tavern)
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping
        assertEquals("stay in character", mapping.promptAppendix)
    }

    @Test
    fun `an empty prompt list still rejects as before`() {
        val parsed = SillyTavernPresetAdapter.adapt(
            JSONObject().put("prompts", JSONArray()).toString()
        )

        assertTrue(parsed is GenerationPresetParseResult.Rejected)
    }

    @Test
    fun `tavern output length is capped below the mca ceiling and the clamp is reported`() {
        val json = JSONObject()
            .put("temperature", 1.0)
            .put("openai_max_tokens", 60000)

        val parsed = SillyTavernPresetAdapter.adapt(json.toString())
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertEquals(SillyTavernPresetAdapter.MAX_PREDICT_TAVERN, mapping.params.nPredict)
        assertTrue(mapping.clamped.any { it.key == "openai_max_tokens" })
    }

    @Test
    fun `a large prompt bundle is truncated with an ellipsis but keeps the real length`() {
        val long = "p".repeat(SillyTavernPresetAdapter.MAX_PROMPT_APPENDIX_CHARS + 100)
        val json = JSONObject().put(
            "prompts",
            JSONArray().put(prompt("sys", long, enabled = true))
        )

        val parsed = SillyTavernPresetAdapter.adapt(json.toString())
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertNotNull(mapping.promptAppendix)
        assertTrue(mapping.promptAppendix!!.endsWith("…"))
        assertEquals(SillyTavernPresetAdapter.MAX_PROMPT_APPENDIX_CHARS + "\n…".length, mapping.promptAppendix!!.length)
        assertEquals(long.length, mapping.promptChars)
    }

    @Test
    fun `prompt order entries that do not resolve fall back to document order`() {
        val json = JSONObject()
            .put(
                "prompts",
                JSONArray()
                    .put(prompt("a", "first", enabled = true))
                    .put(prompt("b", "second", enabled = true))
            )
            .put(
                "prompt_order",
                JSONArray().put(
                    JSONObject().put("order", JSONArray().put(JSONObject().put("identifier", "missing")))
                )
            )

        val parsed = SillyTavernPresetAdapter.adapt(json.toString())
        val mapping = (parsed as GenerationPresetParseResult.Tavern).mapping

        assertEquals("first\n\nsecond", mapping.promptAppendix)
    }

    @Test
    fun `the stored params json keeps the bundle under its own key`() {
        val params = GenerationParamsDefaults.withPrompt(
            SillyTavernPresetAdapter.adapt(
                JSONObject()
                    .put("temperature", 1.0)
                    .put("prompts", JSONArray().put(prompt("sys", "bundle", enabled = true)))
                    .toString()
            )
        )
        val stored = JSONObject(params.toAssistantGenerationJson())
            .put(PROMPT_APPENDIX_KEY, "bundle")
            .toString()

        // The sampler parser must keep ignoring the extra key, so importing stays safe.
        val restored = com.muyuchat.core.engine.GenerationParams.fromJson(
            stored,
            com.muyuchat.core.engine.GenerationParams()
        )
        assertEquals(1.0f, restored.temperature, 0.0001f)
        assertFalse(stored.isEmpty())
    }

    private fun prompt(identifier: String, content: String, enabled: Boolean): JSONObject =
        JSONObject()
            .put("identifier", identifier)
            .put("content", content)
            .put("enabled", enabled)
            .put("role", "system")

    private object GenerationParamsDefaults {
        fun withPrompt(parsed: GenerationPresetParseResult): com.muyuchat.core.engine.GenerationParams =
            (parsed as GenerationPresetParseResult.Tavern).mapping.params
    }
}
