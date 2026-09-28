package com.muyuchat.core.engine

import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.SamplerConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationParameterApplicationTest {
    @Test
    fun nativeConfigEchoIsSubmissionAndMissingSamplerReadbackStaysUnknown() {
        val requested = GenerationParams(seed = 12, temperature = 0.4f).toJson()
        val trace = nativeGenerationParameterApplication(
            LocalChatRuntime.MNN_CPU,
            requested,
            JSONObject().put("lastConfigJson", JSONObject()
                .put("temperature", 0.4)
                .put("max_new_tokens", 99)
                .put("jinja", JSONObject().put("context", JSONObject().put("enable_thinking", false))))
        )

        assertEquals(0.4, trace.getJSONObject("submitted").getDouble("temperature"), 0.00001)
        assertEquals(99, trace.getJSONObject("submitted").getInt("n_predict"))
        assertEquals(0, trace.getJSONObject("nativeAcknowledged").length())
        assertFalse(trace.getJSONObject("submitted").has("seed"))
    }

    @Test
    fun nativeSamplerSnapshotAloneAcknowledgesReportedFields() {
        val trace = nativeGenerationParameterApplication(
            LocalChatRuntime.MNN_CPU,
            GenerationParams(temperature = 0.4f, repeatPenalty = 1.2f).toJson(),
            JSONObject().put("samplerConfig", JSONObject()
                .put("temperature", 0.4).put("repetition_penalty", 1.2))
        )

        assertEquals(1.2, trace.getJSONObject("nativeAcknowledged").getDouble("repeat_penalty"), 0.00001)
        assertFalse(trace.getJSONObject("nativeAcknowledged").has("thinking_budget"))
    }

    @Test
    fun floatSerializationNoiseDoesNotCountAsNormalization() {
        val requested = GenerationParams(repeatPenalty = 1.08f).toJson()
        val trace = generationParameterApplication(
            runtime = LocalChatRuntime.LITERT_LM,
            backend = "npu",
            requestedJson = requested,
            source = "test",
            submitted = JSONObject().put("repeat_penalty", 1.08)
        )
        val fields = trace.getJSONArray("fields")
        val penalty = (0 until fields.length()).map(fields::getJSONObject)
            .first { it.getString("field") == "repeat_penalty" }
        assertEquals("submitted", penalty.getString("disposition"))
    }

    @Test
    fun realNormalizationAndAdjacentLargeSeedsRemainDistinct() {
        val requested = GenerationParams(repeatPenalty = 0.8f, seed = Int.MAX_VALUE).toJson()
        val trace = generationParameterApplication(
            runtime = LocalChatRuntime.LITERT_LM,
            backend = "npu",
            requestedJson = requested,
            source = "test",
            submitted = JSONObject()
                .put("repeat_penalty", 1.0)
                .put("seed", Int.MAX_VALUE - 1)
        )
        val fields = trace.getJSONArray("fields")
        for (name in listOf("repeat_penalty", "seed")) {
            val field = (0 until fields.length()).map(fields::getJSONObject)
                .first { it.getString("field") == name }
            assertEquals("normalized", field.getString("disposition"))
        }
    }

    @Test
    fun genieXRescueTraceReadsTheActualSdkObject() {
        val requested = GenerationParams(temperature = 0f, topK = 1, topP = 1f).toJson()
        val config = GenerationConfig(
            maxTokens = 77,
            samplerConfig = SamplerConfig(temperature = 0.35f, topK = 32, topP = 0.9f),
            stopWords = arrayOf("STOP"), stopCount = 1
        )
        val trace = genieXGenerationParameterApplication(
            requested, config, templateEnableThinking = false, attempt = 2
        )

        val submitted = trace.getJSONObject("submitted")
        val sampler = requireNotNull(config.samplerConfig)
        assertEquals(sampler.temperature.toDouble(), submitted.getDouble("temperature"), 0.000001)
        assertEquals(sampler.topK, submitted.getInt("top_k"))
        assertEquals(config.maxTokens, submitted.getInt("n_predict"))
        assertFalse(submitted.getBoolean("enable_thinking"))
        assertEquals("STOP", submitted.getJSONArray("stop_words").getString(0))
        assertEquals(2, trace.getInt("attempt"))
        assertEquals(0, trace.getJSONObject("nativeAcknowledged").length())
    }

    @Test
    fun absentGenieXSamplerDoesNotInventSubmittedValues() {
        val trace = genieXGenerationParameterApplication(
            GenerationParams().toJson(), GenerationConfig(samplerConfig = null), false
        )
        assertFalse(trace.getJSONObject("submitted").has("temperature"))
        assertFalse(trace.getJSONObject("submitted").has("seed"))
    }

    @Test
    fun liteRtReasoningOffAndHiddenUseExplicitDisabledThinkingConfig() {
        for (params in listOf(
            GenerationParams(reasoningMode = ReasoningMode.OFF),
            GenerationParams(reasoningMode = ReasoningMode.ADVANCED, hideReasoning = true)
        )) {
            val configuration = liteRtGenerationConfiguration(params, "cpu")
            assertFalse(configuration.thinking.enableThinking)
            assertEquals(0, configuration.thinking.thinkingTokenBudget)
        }
    }

    @Test
    fun nonFiniteSamplerValuesAreRejectedBeforeSdkConfiguration() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(runCatching { liteRtGenerationConfiguration(GenerationParams(temperature = value), "cpu") }.isFailure)
            assertTrue(runCatching { GenerationParams(frequencyPenalty = value).requireFiniteSamplerValues() }.isFailure)
        }
    }
}
