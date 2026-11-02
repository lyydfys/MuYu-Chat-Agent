package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParamOverrideFieldsTest {
    private val model = GenerationParams(
        nPredict = 2048,
        temperature = 0.6f,
        topK = 20,
        topP = 0.95f,
        minP = 0.02f,
        repeatPenalty = 1.08f,
        presencePenalty = 0.1f,
        frequencyPenalty = 0.2f,
        seed = 42,
        stopWords = listOf("</s>"),
        reasoningMode = ReasoningMode.STANDARD,
        hideReasoning = false
    )
    private val card = model.copy(
        nPredict = 512,
        temperature = 1.1f,
        topK = 40,
        topP = 0.8f,
        minP = 0.0f,
        repeatPenalty = 1.2f,
        presencePenalty = 0.0f,
        frequencyPenalty = 0.5f,
        seed = 7,
        stopWords = listOf("\nUser:"),
        reasoningMode = ReasoningMode.OFF,
        hideReasoning = true
    )

    @Test
    fun emptyOverrideSetKeepsTheWholeModelPreset() {
        assertEquals(model, GenerationParamOverrideFields.apply(model, card, emptySet()))
    }

    @Test
    fun onlyTheNamedFieldsOverrideTheModelPreset() {
        val resolved = GenerationParamOverrideFields.apply(
            model,
            card,
            setOf(GenerationParamOverrideFields.TEMPERATURE, GenerationParamOverrideFields.STOP_WORDS)
        )

        assertEquals(1.1f, resolved.temperature)
        assertEquals(listOf("\nUser:"), resolved.stopWords)
        assertEquals(model.nPredict, resolved.nPredict)
        assertEquals(model.topK, resolved.topK)
        assertEquals(model.topP, resolved.topP)
        assertEquals(model.minP, resolved.minP)
        assertEquals(model.repeatPenalty, resolved.repeatPenalty)
        assertEquals(model.seed, resolved.seed)
        assertEquals(model.reasoningMode, resolved.reasoningMode)
    }

    @Test
    fun reasoningOverrideAlsoCarriesTheHideReasoningFlag() {
        val resolved = GenerationParamOverrideFields.apply(
            model,
            card,
            setOf(GenerationParamOverrideFields.REASONING_MODE)
        )

        assertEquals(ReasoningMode.OFF, resolved.reasoningMode)
        assertTrue(resolved.hideReasoning)
        assertEquals(model.temperature, resolved.temperature)
    }

    @Test
    fun legacyFieldsIncludeSeedAndHideReasoning() {
        val resolved = GenerationParamOverrideFields.apply(
            model,
            card,
            GenerationParamOverrideFields.legacyFields
        )

        assertEquals(card, resolved)
        assertTrue(GenerationParamOverrideFields.SEED in GenerationParamOverrideFields.legacyFields)
        assertFalse(GenerationParamOverrideFields.SEED in GenerationParamOverrideFields.semanticFields)
    }

    @Test
    fun changedSemanticFieldsIgnoresSeedAndHideReasoning() {
        val changed = GenerationParamOverrideFields.changedSemanticFields(model, card)

        assertTrue(GenerationParamOverrideFields.TEMPERATURE in changed)
        assertTrue(GenerationParamOverrideFields.STOP_WORDS in changed)
        assertTrue(GenerationParamOverrideFields.REASONING_MODE in changed)
        assertFalse(GenerationParamOverrideFields.SEED in changed)
        assertFalse(GenerationParamOverrideFields.HIDE_REASONING in changed)
    }
}
