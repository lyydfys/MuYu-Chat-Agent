package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.MnnModelBundleInstallProfile
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.RecommendedModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPresentationTest {
    private val catalog = ModelScopeClient().recommendedModels().associateBy { it.id }
    private val turbo get() = catalog.getValue("sd_turbo_512_experimental")

    @Test
    fun optionalVisionFileDoesNotAdvertiseInstalledVisionAndTextOnlyProfileWins() {
        val vision = catalog.getValue("qwen35_08b_uncensored_mnn")
        val bundle = requireNotNull(vision.mnnModelBundle)
        assertEquals("文本聊天 · 图片理解", recommendationCapabilityLine(vision))
        val optionalVision = bundle.copy(components = bundle.components.map {
            if (it.fileName == "visual.mnn") it.copy(required = false) else it
        })
        assertEquals("文本聊天", recommendationCapabilityLine(vision.copy(mnnModelBundle = optionalVision)))
        val textOnly = bundle.copy(installProfile = MnnModelBundleInstallProfile.TEXT_ONLY)
        assertEquals("文本聊天", recommendationCapabilityLine(vision.copy(mnnModelBundle = textOnly)))
        assertEquals("文本聊天", recommendationCapabilityLine(catalog.getValue("qwen35_4b_uncensored_mnn")))
    }

    @Test
    fun separateVisionDownloadRemainsVisibleAsAUserRequirement() {
        val model = catalog.getValue("google_gemma4_26b_a4b_iq2_xxs")
        assertEquals("文本聊天 · 图片理解需另下视觉组件", recommendationCapabilityLine(model))
    }

    @Test
    fun completeDownloadSizeIncludesAdditionalComponents() {
        val bundle = requireNotNull(turbo.imageEngineBundle)
        assertEquals(5_214_561_328L, recommendationDownloadSizeBytes(turbo))
        val additional = bundle.components.single().copy(
            role = ImageEngineBundleComponentRole.OPTIONAL,
            required = false,
            fileName = "extra.bin",
            relativePath = "extra.bin",
            expectedSizeBytes = 100L
        )
        val model = turbo.copy(imageEngineBundle = bundle.copy(components = bundle.components + additional))
        assertEquals(5_214_561_428L, recommendationDownloadSizeBytes(model))
    }

    @Test
    fun qwenImage21SizeMatchesTheDefaultTextToImageDownloadPlan() {
        val qwen = catalog.getValue("qwen_image_21_mnn_opencl")
        val components = requireNotNull(qwen.imageEngineBundle).components
        val defaultSize = components.filter { it.downloadByDefault }.sumOf {
            requireNotNull(it.expectedSizeBytes)
        }

        assertEquals(10_141_479_006L, defaultSize)
        assertEquals(defaultSize, recommendationDownloadSizeBytes(qwen))
    }

    @Test
    fun qwenImage21ExplainsItsArm64RuntimeOnX8664WithoutRemovingDownload() {
        val qwen = catalog.getValue("qwen_image_21_mnn_opencl")
        assertEquals(setOf("arm64-v8a"), qwen.requiredAbis)
        val warning = recommendationRuntimeCompatibilityLine(qwen, listOf("x86_64"))
        assertTrue(requireNotNull(warning).contains("arm64-v8a"))
        assertTrue(warning.contains("x86_64"))
        val access = recommendationDownloadAccess(
            qwen,
            deviceChipsetCode = "",
            deviceIsSnapdragon = false,
            deviceSupportedAbis = listOf("x86_64")
        )
        assertTrue("ABI mismatch is advisory; download must remain available", access.canDownload)
        assertTrue("ABI mismatch should be visibly experimental", access.experimental)
    }

    @Test
    fun qwenImage21HasNoAbiWarningOnArm64() {
        val qwen = catalog.getValue("qwen_image_21_mnn_opencl")
        assertNull(recommendationRuntimeCompatibilityLine(qwen, listOf("arm64-v8a", "armeabi-v7a")))
        val access = recommendationDownloadAccess(
            qwen,
            deviceChipsetCode = "",
            deviceSupportedAbis = listOf("arm64-v8a")
        )
        assertTrue(access.canDownload)
    }

    @Test
    fun qwenImage21SeparatesUpstreamExampleSizesFromMcaBundleSize() {
        val qwen = catalog.getValue("qwen_image_21_mnn_opencl")
        val line = requireNotNull(recommendationImageSizeLine(qwen))
        assertTrue(line.contains("512×512"))
        assertTrue(line.contains("576×448"))
        assertTrue(line.contains("384×384"))
        assertTrue(line.contains("256×416"))
        assertTrue(line.contains("21 个组合"))
        assertTrue(line.contains("7 种比例"))
        assertTrue(line.contains("Standard"))
        assertTrue(line.contains("Fast："))
        assertTrue(line.contains("Tiny："))
        assertTrue(line.contains("高分辨率运行包"))
        assertNull(recommendationImageSizeLine(catalog.getValue("sd_turbo_512_experimental")))
    }

    @Test
    fun missingSourceSizeNeverProducesAMisleadingPartialTotal() {
        val bundle = requireNotNull(turbo.imageEngineBundle)
        val optional = bundle.components.single().copy(
            role = ImageEngineBundleComponentRole.OPTIONAL,
            required = false,
            fileName = "extra.bin",
            relativePath = "extra.bin",
            expectedSizeBytes = null
        )
        val model = turbo.copy(imageEngineBundle = bundle.copy(components = bundle.components + optional))
        assertNull(recommendationDownloadSizeBytes(model))
        assertNull(recommendationDownloadSizeBytes(catalog.getValue("qwen35_4b_uncensored_mnn")))
    }

    @Test
    fun sizeOverflowIsUnavailableInsteadOfNegative() {
        val bundle = requireNotNull(turbo.imageEngineBundle)
        val additional = bundle.components.single().copy(
            role = ImageEngineBundleComponentRole.OPTIONAL,
            required = false,
            fileName = "extra.bin",
            relativePath = "extra.bin",
            expectedSizeBytes = Long.MAX_VALUE
        )
        val model = turbo.copy(imageEngineBundle = bundle.copy(components = bundle.components + additional))
        assertNull(recommendationDownloadSizeBytes(model))
    }

    @Test
    fun pendingModelUsesPlainLanguageCapabilityCopy() {
        val pending = catalog.getValue("gemma4_e2b_litertlm_npu")
            .copy(status = RecommendedModelStatus.PENDING_INTEGRATION)

        assertEquals("MCA 暂不支持此模型", recommendationCapabilityLine(pending))
    }

    @Test
    fun unavailableQualcommVariantExplainsTheAvailableAlternatives() {
        assertEquals(
            "当前没有此型号的 Qualcomm NPU 版本，可选择 CPU 或 GPU 版本。",
            recommendationDownloadBlockLine("当前仓库没有可确认的 E4B LiteRT-LM Qualcomm .litertlm 专版；请使用 CPU/GPU 官方包或单独接入 GenieX QAIRT。")
        )
        assertEquals(
            "当前没有可下载的模型文件。",
            recommendationDownloadBlockLine(null)
        )
    }

}
