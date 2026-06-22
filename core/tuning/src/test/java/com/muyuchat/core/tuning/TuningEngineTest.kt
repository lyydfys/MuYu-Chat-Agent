package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.telemetry.SocFamily
import org.junit.Assert.assertEquals
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
