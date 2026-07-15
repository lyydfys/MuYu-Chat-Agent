package com.muyuchat.core.advisor

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.TuningRuntime
import com.muyuchat.core.tuning.UserPreference
import org.json.JSONObject
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

    @Test
    fun qwen36A3bSeparatesTotalAndActiveParametersAndRemainsExperimentalCandidate() {
        val model = manifest(
            name = "Qwen3.6-35B-A3B-Claude-4.7-Opus-Reasoning-Distilled-APEX-MTP-I-Nano",
            size = 11_686_646_144L,
            quant = "APEX MTP I-Nano"
        )
        val profile = ModelProfile.fromLocal(model)

        assertEquals(35.0, profile.totalParametersB!!, 0.001)
        assertEquals(3.0, profile.activeParametersB!!, 0.001)
        assertEquals(3.0, profile.parametersB!!, 0.001)

        val result = advisor.recommend(
            device = device(totalGb = 16, availableGb = 5),
            localModels = listOf(model),
            remoteFiles = emptyList(),
            preference = UserPreference(PerformanceMode.Balanced)
        )

        assertNotNull(result.recommended)
        assertEquals(RiskLevel.High, result.recommended!!.risk)
        assertTrue(result.recommended!!.reason.contains("总 35.0B / 激活 3.0B"))
        assertEquals(4096, result.tuningPlan.nCtx)
        assertEquals("draft-mtp", JSONObject(result.tuningPlan.advancedJson).getString("spec_type"))
    }

    @Test
    fun adaptiveRecommendationExposesSeparatedOutputsWithoutBreakingLegacyRecommendation() {
        val result = advisor.recommendAdaptive(
            device = device(totalGb = 12, availableGb = 4),
            localModels = models(),
            remoteFiles = emptyList(),
            runtimeIdentity = ModelRuntimeIdentity(
                modelId = "recommended-model",
                artifactFingerprint = "real-model-runtime-fingerprint",
                runtime = LocalChatRuntime.LLAMA_CPP,
                runtimeVersion = "test",
                nativeLibrarySha256 = "native-test"
            ),
            capabilities = ModelTuningCapabilities(runtime = TuningRuntime.LLAMA_CPP),
            preference = UserPreference(PerformanceMode.Balanced)
        )

        assertNotNull(result.legacy.recommended)
        assertEquals(
            result.adaptive.executionProfile.runtimeIdentity.identityHash,
            result.adaptive.executionProfile.identityHash
        )
        assertTrue(result.adaptive.executionProfile.loadBound.nCtx > 0)
        assertTrue(result.adaptive.executionProfile.hotExecution.nThreads > 0)
        assertTrue(result.adaptive.generationRecommendation.explicitApplyRequired)
        assertEquals(0.0f, result.adaptive.canaryParams.temperature)
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
