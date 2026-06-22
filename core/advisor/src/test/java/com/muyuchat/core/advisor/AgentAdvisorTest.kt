package com.muyuchat.core.advisor

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAdvisorTest {
    private val advisor = AgentAdvisor()

    @Test
    fun lowMemoryDevicePrefersSmallQ4Model() {
        val result = advisor.recommend(
            device = device(totalGb = 4, availableGb = 1),
            localModels = models(),
            remoteFiles = emptyList(),
            preference = UserPreference(PerformanceMode.Balanced)
        )

        assertNotNull(result.recommended)
        assertEquals("Qwen3.5-2B-Q4_K_M", result.recommended!!.model.displayName)
        assertTrue(result.tuningPlan.nCtx <= 4096)
    }

    @Test
    fun mainstreamDevicePrefersFourBModel() {
        val result = advisor.recommend(
            device = device(totalGb = 8, availableGb = 3),
            localModels = models(),
            remoteFiles = emptyList(),
            preference = UserPreference(PerformanceMode.Balanced)
        )

        assertNotNull(result.recommended)
        assertEquals("Qwen3.5-4B-Q4_K_M", result.recommended!!.model.displayName)
    }

    @Test
    fun flagshipQualityModeAllowsSevenBWithNonLowRisk() {
        val result = advisor.recommend(
            device = device(totalGb = 12, availableGb = 5),
            localModels = models(),
            remoteFiles = emptyList(),
            preference = UserPreference(PerformanceMode.Quality)
        )

        assertNotNull(result.recommended)
        assertEquals("Qwen3.5-9B-Q4_K_M", result.recommended!!.model.displayName)
        assertTrue(result.recommended!!.risk == RiskLevel.Medium || result.recommended!!.risk == RiskLevel.Low)
    }

    @Test
    fun lowBatteryForcesConservativeTuning() {
        val result = advisor.recommend(
            device = device(totalGb = 8, availableGb = 3, battery = 10, charging = false),
            localModels = models(),
            remoteFiles = emptyList(),
            preference = UserPreference(PerformanceMode.Speed)
        )

        assertTrue(result.tuningPlan.nThreads <= 4)
        assertTrue(result.tuningPlan.nPredict <= 2048)
    }

    private fun device(
        totalGb: Int,
        availableGb: Int,
        battery: Int = 80,
        charging: Boolean = false,
        thermalStatus: ThermalStatus = ThermalStatus.None
    ): DeviceProfile = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "Snapdragon Test",
        socFamily = SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 4,
        totalRamBytes = totalGb * GB,
        availableRamBytes = availableGb * GB,
        storageFreeBytes = 64L * GB,
        androidApi = 36,
        thermalStatus = thermalStatus,
        batteryPercent = battery,
        isCharging = charging,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a"
    )

    private fun models(): List<ModelManifest> = listOf(
        manifest("Qwen3.5-2B-Q4_K_M", 1_270_000_000L, "Q4_K_M"),
        manifest("Qwen3.5-4B-Q4_K_M", 2_707_000_000L, "Q4_K_M"),
        manifest("Qwen3.5-9B-Q4_K_M", 5_627_000_000L, "Q4_K_M")
    )

    private fun manifest(name: String, size: Long, quant: String): ModelManifest = ModelManifest(
        id = name,
        displayName = name,
        path = "/models/$name.gguf",
        source = ModelSource.LOCAL,
        fileName = "$name.gguf",
        sizeBytes = size,
        sha256 = "test",
        quant = quant,
        architecture = "qwen"
    )

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
