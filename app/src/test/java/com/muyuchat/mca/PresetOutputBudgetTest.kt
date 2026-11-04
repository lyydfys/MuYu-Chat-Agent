package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Tavern preset's declared output budget must survive import and only be narrowed per model.
 *
 * Web-authored presets ask for 30k-60k output. Clamping that at import silently down-grades a cloud
 * user; not clamping it at all makes every local request over-budget. The budget therefore lives at
 * the binding boundary, and these cases pin both sides.
 */
class PresetOutputBudgetTest {

    @Test
    fun `the importer keeps a web-scale output value`() {
        val json = org.json.JSONObject()
            .put("temperature", 1.0)
            .put("openai_max_tokens", 60_000)

        val mapping = (SillyTavernPresetAdapter.adapt(json.toString())
            as GenerationPresetParseResult.Tavern).mapping

        assertEquals(60_000, mapping.params.nPredict)
        assertTrue("no clamp should be reported for a value the sampler ceiling accepts", mapping.clamped.isEmpty())
    }

    @Test
    fun `a local model is clamped to the phone ceiling`() {
        val params = GenerationParams(nPredict = 60_000)

        val applied = PresetOutputBudget.apply(params, isLocal = true)

        assertEquals(PresetOutputBudget.LOCAL_MAX_OUTPUT_TOKENS, applied.nPredict)
        assertTrue(PresetOutputBudget.wasClamped(params, isLocal = true))
    }

    @Test
    fun `a cloud model keeps the requested output`() {
        val params = GenerationParams(nPredict = 60_000)

        val applied = PresetOutputBudget.apply(params, isLocal = false)

        assertEquals(60_000, applied.nPredict)
        assertFalse(PresetOutputBudget.wasClamped(params, isLocal = false))
    }

    @Test
    fun `a small output value is returned unchanged for both kinds`() {
        val params = GenerationParams(nPredict = 2_048)

        assertSame(params, PresetOutputBudget.apply(params, isLocal = true))
        assertSame(params, PresetOutputBudget.apply(params, isLocal = false))
        assertFalse(PresetOutputBudget.wasClamped(params, isLocal = true))
    }

    @Test
    fun `a cloud binding keeps even a very large declared value untouched`() {
        val params = GenerationParams(nPredict = 200_000)

        val applied = PresetOutputBudget.apply(params, isLocal = false)

        assertEquals(200_000, applied.nPredict)
        assertFalse(PresetOutputBudget.wasClamped(params, isLocal = false))
    }

    @Test
    fun `an importer never invents an output ceiling`() {
        val json = org.json.JSONObject()
            .put("temperature", 1.0)
            .put("openai_max_tokens", 200_000)

        val mapping = (SillyTavernPresetAdapter.adapt(json.toString())
            as GenerationPresetParseResult.Tavern).mapping

        assertEquals(200_000, mapping.params.nPredict)
    }

    @Test
    fun `a zero in the preset means let the server decide and falls back to the mca default`() {
        val defaults = GenerationParams(nPredict = 1_024)
        val json = org.json.JSONObject()
            .put("temperature", 1.0)
            .put("openai_max_tokens", 0)

        val mapping = (SillyTavernPresetAdapter.adapt(json.toString(), defaults)
            as GenerationPresetParseResult.Tavern).mapping

        assertEquals(1_024, mapping.params.nPredict)
        assertTrue(mapping.clamped.any { it.key == "openai_max_tokens" })
    }
}
