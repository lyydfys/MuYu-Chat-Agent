package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParamOverrideFields
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantGenerationOverrideResolutionTest {
    private val modelPreset = GenerationParams(
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
        hideReasoning = false,
        systemPrompt = "model prompt"
    )

    @Test
    fun legacyCardWithoutMetadataKeepsItsWholeSnapshot() {
        val card = modelPreset.copy(
            nPredict = 512,
            temperature = 1.2f,
            topK = 40,
            stopWords = listOf("\nUser:"),
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
        val assistant = AssistantRecord(
            systemPrompt = "card prompt",
            paramsJson = card.toAssistantGenerationJson()
        )

        assertNull(assistantGenerationOverrideFieldsOrNull(assistant.paramsJson))
        val resolved = assistant.toGenerationParams(modelPreset)
        assertEquals(512, resolved.nPredict)
        assertEquals(1.2f, resolved.temperature)
        assertEquals(40, resolved.topK)
        assertEquals(modelPreset.topP, resolved.topP)
        assertEquals(listOf("\nUser:"), resolved.stopWords)
        assertEquals(ReasoningMode.OFF, resolved.reasoningMode)
        assertTrue(resolved.hideReasoning)
        assertEquals("card prompt", resolved.systemPrompt)
    }

    @Test
    fun emptyMetadataFollowsTheModelPresetAndAlwaysKeepsThePersona() {
        val assistant = AssistantRecord(
            systemPrompt = "card prompt",
            paramsJson = modelPreset.copy(temperature = 1.2f).toAssistantGenerationJsonWithOverrides(emptySet())
        )

        assertEquals(emptySet<String>(), assistantGenerationOverrideFieldsOrNull(assistant.paramsJson))
        val resolved = assistant.toGenerationParams(modelPreset)
        assertEquals(modelPreset.nPredict, resolved.nPredict)
        assertEquals(modelPreset.temperature, resolved.temperature)
        assertEquals(modelPreset.seed, resolved.seed)
        assertEquals("card prompt", resolved.systemPrompt)
    }

    @Test
    fun onlyExplicitFieldsOverrideTheModelPreset() {
        val cardValues = modelPreset.copy(
            temperature = 1.2f,
            topK = 40,
            stopWords = listOf("\nUser:"),
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
        val assistant = AssistantRecord(
            systemPrompt = "card prompt",
            paramsJson = cardValues.toAssistantGenerationJsonWithOverrides(
                setOf(
                    GenerationParamOverrideFields.TEMPERATURE,
                    GenerationParamOverrideFields.STOP_WORDS,
                    GenerationParamOverrideFields.REASONING_MODE
                )
            )
        )

        val resolved = assistant.toGenerationParams(modelPreset)
        assertEquals(1.2f, resolved.temperature)
        assertEquals(listOf("\nUser:"), resolved.stopWords)
        assertEquals(ReasoningMode.OFF, resolved.reasoningMode)
        assertTrue(resolved.hideReasoning)
        assertEquals(modelPreset.nPredict, resolved.nPredict)
        assertEquals(modelPreset.topK, resolved.topK)
        assertEquals(modelPreset.seed, resolved.seed)
    }

    @Test
    fun modelPresetOverlayCopiesOnlyTheFieldsTheUserChanged() {
        val previous = modelPreset.copy(temperature = 1.2f)
        val candidate = previous.copy(temperature = 0.8f, nPredict = 512)

        val updated = modelPreset.withChangedFieldsFrom(previous, candidate)

        assertEquals(0.8f, updated.temperature)
        assertEquals(512, updated.nPredict)
        assertEquals(modelPreset.topK, updated.topK)
        assertEquals(modelPreset.topP, updated.topP)
        assertEquals(modelPreset.stopWords, updated.stopWords)
        assertEquals(modelPreset.systemPrompt, updated.systemPrompt)
    }

    @Test
    fun sanitizerKeepsOverrideMetadataButStillNormalizesValues() {
        val raw = modelPreset.toAssistantGenerationJsonWithOverrides(
            setOf(
                GenerationParamOverrideFields.TEMPERATURE,
                "not_a_field"
            )
        )

        val sanitized = sanitizeAssistantParamsJsonPreservingLegacyExecution(
            raw,
            modelPreset,
            "card prompt"
        )

        assertEquals(
            setOf(GenerationParamOverrideFields.TEMPERATURE),
            assistantGenerationOverrideFieldsOrNull(sanitized)
        )
    }
}
