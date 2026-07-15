package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationProfile
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.telemetry.SocFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelMemoryAdmissionPolicyTest {
    @Test
    fun exactSizeQwenMoeIsAdmittedOnTwelveGigabytes() {
        val result = LocalModelMemoryAdmissionPolicy.evaluate(
            model = model(
                name = "Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf",
                architecture = "qwen35moe",
                sizeBytes = 11_686_646_144L
            ),
            device = device(totalGiB = 12, availableGiB = 4)
        )

        assertTrue(result.allowed)
        assertEquals(LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP, result.mode)
        assertNull(result.blocker)
        assertTrue(result.advisory.orEmpty().contains("激活约 3B"))
    }

    @Test
    fun qwenMoeIsAlsoAdmittedOnSixteenGigabytesWithSystemOccupancy() {
        val result = LocalModelMemoryAdmissionPolicy.evaluate(
            model = model(
                name = "Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf",
                architecture = "qwen35moe",
                sizeBytes = 11_686_646_144L
            ),
            device = device(totalGiB = 16, availableGiB = 2, lowMemory = true)
        )

        assertTrue(result.allowed)
        assertEquals(LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP, result.mode)
        assertTrue(result.advisory.orEmpty().contains("低内存"))
    }

    @Test
    fun sparseGemmaArchitectureUsesTheA4bScaleForDiagnostics() {
        val result = LocalModelMemoryAdmissionPolicy.evaluate(
            model = model(
                name = "google-gemma-4-26B-A4B-it-IQ2_XXS.gguf",
                architecture = "gemma4_moe",
                sizeBytes = 10L * GIB
            ),
            device = device(totalGiB = 12, availableGiB = 3)
        )

        assertEquals(LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP, result.mode)
        assertTrue(result.advisory.orEmpty().contains("激活约 4B"))
    }

    @Test
    fun filenameScaleWithoutGgufMoeArchitectureDoesNotBypassDenseAdmission() {
        val result = LocalModelMemoryAdmissionPolicy.evaluate(
            model = model(
                name = "renamed-35B-A3B.gguf",
                architecture = null,
                sizeBytes = 11_686_646_144L
            ),
            device = device(totalGiB = 12, availableGiB = 6)
        )

        assertFalse(result.allowed)
        assertEquals(LocalModelMemoryAdmissionMode.DENY, result.mode)
    }

    @Test
    fun sameSizeDenseModelRemainsBlocked() {
        val result = LocalModelMemoryAdmissionPolicy.evaluate(
            model = model(
                name = "Dense-35B-IQ2_XXS.gguf",
                architecture = "qwen3",
                sizeBytes = 11_686_646_144L
            ),
            device = device(totalGiB = 12, availableGiB = 6)
        )

        assertFalse(result.allowed)
        assertEquals(LocalModelMemoryAdmissionMode.DENY, result.mode)
        assertTrue(result.blocker.orEmpty().contains("总内存"))
    }

    @Test
    fun qairtKeepsItsOwnAdmissionPath() {
        val result = LocalModelMemoryAdmissionPolicy.evaluate(
            model = model("bundle", "qwen35moe", 100L * GIB, ChatModelRuntime.GENIEX_QAIRT),
            device = device(totalGiB = 8, availableGiB = 1, lowMemory = true)
        )

        assertTrue(result.allowed)
        assertEquals(LocalModelMemoryAdmissionMode.ALLOW, result.mode)
    }

    private fun model(
        name: String,
        architecture: String?,
        sizeBytes: Long,
        runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP
    ) = ModelManifest(
        id = "model-id",
        displayName = name,
        path = "/models/$name",
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = name,
        sizeBytes = sizeBytes,
        sha256 = "a".repeat(64),
        architecture = architecture
    )

    private fun device(totalGiB: Int, availableGiB: Int, lowMemory: Boolean = false) = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "test",
        socFamily = SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 6,
        totalRamBytes = totalGiB * GIB,
        availableRamBytes = availableGiB * GIB,
        storageFreeBytes = 64L * GIB,
        androidApi = 36,
        thermalStatus = ThermalStatus.None,
        batteryPercent = 100,
        isCharging = true,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a",
        advertisedRamBytes = totalGiB * GIB,
        isLowMemory = lowMemory,
        modelMemoryBudgetBytes = (totalGiB * GIB * 0.70).toLong(),
        accelerationProfile = DeviceAccelerationProfile.CpuOnly
    )

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
