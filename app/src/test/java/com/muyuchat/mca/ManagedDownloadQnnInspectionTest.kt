package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ImageEngineQnnSmokeSpec
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-process coverage for the QNN package preflight owner (P6/M9). The preflight is metadata-only
 * by contract, so its three deterministic JVM-observable outcomes are pinned here without a device:
 * undeclared context specs, a missing declared context file, and an unreadable native preflight
 * (the native bridge cannot load inside a unit test). Every outcome must keep the install
 * non-blocking: unknown devices stay on the generic loader path and only real file damage or a real
 * load/graph failure may reject.
 */
class ManagedDownloadQnnInspectionTest {
    @Test
    fun `undeclared qnn context specs stay experimental and never device-block the install`() {
        val bundleDir = tempDirectory()
        try {
            val preflight = inspectQnnBundleCompatibility(
                bundleDir = bundleDir,
                installedBundle = bundle(),
                device = device(),
                qnnRuntimeDirectoriesJson = { "[]" }
            )

            assertEquals("EXPERIMENTAL", preflight.status)
            assertTrue(preflight.message.contains("包未声明可检查的 QNN context"))
            assertTrue(preflight.message.contains("安装不会被设备型号拦截"))
        } finally {
            bundleDir.deleteRecursively()
        }
    }

    @Test
    fun `missing declared context file is a failed file check not a device rejection`() {
        val bundleDir = tempDirectory()
        try {
            val preflight = inspectQnnBundleCompatibility(
                bundleDir = bundleDir,
                installedBundle = bundle(listOf(smokeSpec("vae_encoder.bin"))),
                device = device(),
                qnnRuntimeDirectoriesJson = { "[]" }
            )

            assertEquals("FAILED", preflight.status)
            assertTrue(preflight.message.contains("QNN context 文件缺失或为空"))
            assertTrue(preflight.message.contains("请重新校验或重新下载"))
        } finally {
            bundleDir.deleteRecursively()
        }
    }

    @Test
    fun `unreadable native preflight reports experimental instead of a broken package`() {
        val bundleDir = tempDirectory()
        try {
            File(bundleDir, "vae_encoder.bin").writeBytes(byteArrayOf(0x51))
            val preflight = inspectQnnBundleCompatibility(
                bundleDir = bundleDir,
                installedBundle = bundle(listOf(smokeSpec("vae_encoder.bin"))),
                device = device(),
                qnnRuntimeDirectoriesJson = { "[]" }
            )

            assertEquals("EXPERIMENTAL", preflight.status)
            assertTrue(preflight.message.contains("本机暂时无法读取 QNN context 元数据"))
            assertTrue(preflight.message.contains("首次真实加载仍会给出最终结果"))
        } finally {
            bundleDir.deleteRecursively()
        }
    }

    private fun bundle(
        qnnSmokeSpecs: List<ImageEngineQnnSmokeSpec> = emptyList()
    ): ImageEngineBundleSpec = ImageEngineBundleSpec(
        id = "qnn-preflight-test-bundle",
        title = "QNN preflight test bundle",
        components = emptyList(),
        qnnSmokeSpecs = qnnSmokeSpecs
    )

    private fun smokeSpec(contextBinary: String): ImageEngineQnnSmokeSpec =
        ImageEngineQnnSmokeSpec(
            contextBinary = contextBinary,
            inputs = emptyList(),
            outputs = emptyList()
        )

    private fun device(): DeviceProfile = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "SM8750P",
        socFamily = SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 4,
        totalRamBytes = 12L * GB,
        availableRamBytes = 8L * GB,
        storageFreeBytes = 64L * GB,
        androidApi = 35,
        thermalStatus = ThermalStatus.None,
        batteryPercent = 90,
        isCharging = true,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a"
    )

    private fun tempDirectory(): File = Files.createTempDirectory("qnn-preflight-test").toFile()

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
