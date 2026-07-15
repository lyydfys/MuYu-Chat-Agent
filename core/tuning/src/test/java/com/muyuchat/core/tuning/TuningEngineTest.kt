package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.telemetry.SocFamily
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningEngineTest {
    private val engine = TuningEngine()

    @Test
    fun presetPlansAreStableForSameDeviceAndBenchmark() {
        val device = device(totalGb = 12, bigCores = 7, cores = 9)
        val first = engine.recommend(device, modelParametersB = 9.0, preference = UserPreference(PerformanceMode.Speed), lastDecodeTps = 12.0)
        val second = engine.recommend(device, modelParametersB = 9.0, preference = UserPreference(PerformanceMode.Speed), lastDecodeTps = 12.0)

        assertEquals(first, second)
    }

    @Test
    fun speedPresetUsesNoLessThreadsThanBalancedOnCoolDevice() {
        val device = device(totalGb = 12, bigCores = 7, cores = 9)
        val speed = engine.recommend(device, modelParametersB = 4.0, preference = UserPreference(PerformanceMode.Speed), lastDecodeTps = 12.0)
        val balanced = engine.recommend(device, modelParametersB = 4.0, preference = UserPreference(PerformanceMode.Balanced), lastDecodeTps = 12.0)

        assertTrue(speed.nThreads >= balanced.nThreads)
        assertTrue(speed.nPredict <= balanced.nPredict)
    }

    @Test
    fun qwenOptimizedSamplingIsUsedForDefaultTextModes() {
        val device = device(totalGb = 12, bigCores = 7, cores = 9)
        val plan = engine.recommend(device, modelParametersB = 2.0, preference = UserPreference(PerformanceMode.Balanced), lastDecodeTps = 18.0)

        assertEquals(0.7f, plan.temperature)
        assertEquals(20, plan.topK)
        assertEquals(0.8f, plan.topP)
        assertEquals(0.0f, plan.minP)
        assertEquals(1.0f, plan.repeatPenalty)
        assertEquals(0.0f, plan.presencePenalty)
    }

    @Test
    fun thinkingOrLongContextModesUseLowerPresencePenalty() {
        val device = device(totalGb = 12, bigCores = 7, cores = 9)
        val plan = engine.recommend(device, modelParametersB = 2.0, preference = UserPreference(PerformanceMode.Quality), lastDecodeTps = 18.0)

        assertEquals(0.95f, plan.topP)
        assertEquals(1.08f, plan.repeatPenalty)
        assertEquals(0.2f, plan.frequencyPenalty)
        assertEquals(0.0f, plan.presencePenalty)
    }

    @Test
    fun lowMeasuredSpeedShrinksContextAndOutputBudget() {
        val device = device(totalGb = 12, bigCores = 7, cores = 9)
        val plan = engine.recommend(device, modelParametersB = 4.0, preference = UserPreference(PerformanceMode.Quality), lastDecodeTps = 3.5)

        assertTrue(plan.nCtx <= 8192)
        assertTrue(plan.nPredict <= 4096)
        assertTrue(plan.nThreads <= 6)
    }

    @Test
    fun hotDeviceForcesConservativePlan() {
        val device = device(totalGb = 12, bigCores = 7, cores = 9, thermalStatus = ThermalStatus.Severe)
        val plan = engine.recommend(device, modelParametersB = 4.0, preference = UserPreference(PerformanceMode.Speed), lastDecodeTps = 12.0)

        assertTrue(plan.nThreads <= 4)
        assertTrue(plan.nCtx <= 8192)
        assertTrue(plan.nPredict <= 2048)
    }

    @Test
    fun qwen36A3bMtpUsesAndroidCpuSafePresetWithoutFakeGpuOffload() {
        val plan = engine.recommend(
            device = device(totalGb = 16, bigCores = 7, cores = 9),
            modelParametersB = 3.0,
            modelName = "Qwen3.6-35B-A3B-Claude-APEX-MTP-I-Nano.gguf",
            preference = UserPreference(PerformanceMode.Quality),
            lastDecodeTps = 8.0
        )
        val advanced = JSONObject(plan.advancedJson)

        assertEquals(4096, plan.nCtx)
        assertEquals(2048, advanced.getInt("n_batch"))
        assertEquals(256, advanced.getInt("n_ubatch"))
        assertEquals("q4_0", advanced.getString("cache_type_k"))
        assertEquals("q4_0", advanced.getString("cache_type_v"))
        assertEquals("on", advanced.getString("flash_attn"))
        assertEquals(256, advanced.getInt("cache_reuse"))
        assertEquals("draft-mtp", advanced.getString("spec_type"))
        assertEquals(2, advanced.getInt("spec_draft_n_max"))
        assertEquals(1, advanced.getInt("n_parallel"))
        assertTrue(advanced.getBoolean("perf"))
        assertTrue(advanced.getBoolean("use_jinja"))
        assertFalse(advanced.has("n_gpu_layers"))
        assertFalse(advanced.has("main_gpu"))
        assertFalse(advanced.has("split_mode"))
        assertFalse(advanced.has("n_cpu_moe"))
    }

    @Test
    fun qwen36A3bMtpKeepsReferenceSamplingAcrossPerformanceModes() {
        PerformanceMode.entries.forEach { mode ->
            val plan = engine.recommend(
                device = device(totalGb = 16, bigCores = 7, cores = 9),
                modelParametersB = 3.0,
                modelName = "Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf",
                preference = UserPreference(mode)
            )

            assertEquals("$mode temperature", 0.6f, plan.temperature)
            assertEquals("$mode topK", 20, plan.topK)
            assertEquals("$mode topP", 0.95f, plan.topP)
            assertEquals("$mode minP", 0.0f, plan.minP)
            assertEquals("$mode repeatPenalty", 1.05f, plan.repeatPenalty)
            assertEquals("$mode presencePenalty", 0.0f, plan.presencePenalty)
            assertEquals("$mode frequencyPenalty", 0.0f, plan.frequencyPenalty)
        }
    }

    @Test
    fun applyingPlanPreservesUserFieldsOutsideTheTuningSurface() {
        val base = GenerationParams(
            seed = 42,
            systemPrompt = "custom persona",
            stopWords = listOf("STOP"),
            chatTemplateMode = "jinja",
            advancedJson = """{
                "future_native":{"x":1},
                "n_threads_batch":3,
                "n_gpu_layers":24,
                "main_gpu":1,
                "split_mode":"layer",
                "n_cpu_moe":5
            }""".trimIndent(),
            reasoningMode = ReasoningMode.ADVANCED,
            hideReasoning = true
        )
        val plan = engine.recommend(
            device = device(totalGb = 16, bigCores = 7, cores = 9),
            modelParametersB = 3.0,
            modelName = "Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf"
        )

        val applied = plan.applyTo(base)
        val advanced = JSONObject(applied.advancedJson)

        assertEquals(42, applied.seed)
        assertEquals("custom persona", applied.systemPrompt)
        assertEquals(listOf("STOP"), applied.stopWords)
        assertEquals("jinja", applied.chatTemplateMode)
        assertEquals(ReasoningMode.ADVANCED, applied.reasoningMode)
        assertTrue(applied.hideReasoning)
        assertEquals(1, advanced.getJSONObject("future_native").getInt("x"))
        assertEquals(3, advanced.getInt("n_threads_batch"))
        assertEquals(2048, advanced.getInt("n_batch"))
        assertEquals("draft-mtp", advanced.getString("spec_type"))
        assertFalse(advanced.has("n_gpu_layers"))
        assertFalse(advanced.has("main_gpu"))
        assertFalse(advanced.has("split_mode"))
        assertFalse(advanced.has("n_cpu_moe"))
    }

    private fun device(
        totalGb: Int,
        bigCores: Int,
        cores: Int,
        thermalStatus: ThermalStatus = ThermalStatus.None
    ): DeviceProfile = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "Snapdragon Test",
        socFamily = SocFamily.Snapdragon,
        cpuCores = cores,
        estimatedBigCores = bigCores,
        totalRamBytes = totalGb * GB,
        availableRamBytes = (totalGb / 2) * GB,
        storageFreeBytes = 64L * GB,
        androidApi = 36,
        thermalStatus = thermalStatus,
        batteryPercent = 80,
        isCharging = false,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a"
    )

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
