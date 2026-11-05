package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekImageProductContractTest {
    private fun aotProfile(
        width: Int = 512,
        height: Int = 512,
        steps: Set<Int> = setOf(6),
        defaultSteps: Int = 6,
        cfg: Float = 1.0f,
        fixedCfg: Float? = cfg
    ) = MediaTekImageGenerationProfile(
        width = width,
        height = height,
        supportedSteps = steps,
        defaultSteps = defaultSteps,
        defaultCfgScale = cfg,
        minimumCfgScale = cfg,
        maximumCfgScale = cfg,
        fixedCfgScale = fixedCfg
    )

    private fun aotContract(
        profile: MediaTekImageGenerationProfile = aotProfile(),
        supportsNegativePrompt: Boolean = false
    ) = MediaTekImageProductContract(
        schema = "mca.mediatek-image/4",
        manifestSha256 = "a".repeat(64),
        recommendationId = "mediatek_anima_npu_np9",
        recommendationRevision = null,
        supportsNegativePrompt = supportsNegativePrompt,
        profiles = listOf(profile),
        defaultProfile = profile
    )

    @Test
    fun `fixed sampler is cleared and the declared surface is accepted`() {
        val contract = aotContract()
        val normalized = normalizeMediaTekImageOptions(
            contract,
            LocalImageGenerationOptions(
                width = 512,
                height = 512,
                steps = 6,
                cfgScale = 1.0,
                seed = 7,
                sampleMethod = "euler"
            )
        )

        assertNull(normalized.sampleMethod)
        assertEquals(512, normalized.width)
        assertEquals(512, normalized.height)
        assertEquals(6, normalized.steps)
        assertEquals(1.0, normalized.cfgScale)
        assertEquals(7, normalized.seed)
    }

    @Test
    fun `sampler steps cfg size and negative branch fail closed with typed codes`() {
        val contract = aotContract()
        fun failure(options: LocalImageGenerationOptions): LocalImageProductContractException? =
            runCatching { normalizeMediaTekImageOptions(contract, options) }
                .exceptionOrNull() as? LocalImageProductContractException

        assertEquals(
            "unsupported_mediatek_anima_options",
            failure(LocalImageGenerationOptions(sampleMethod = "dpmpp_2m"))?.code
        )
        assertEquals(
            "unsupported_mediatek_anima_size",
            failure(LocalImageGenerationOptions(width = 768, height = 768))?.code
        )
        assertEquals(
            "unsupported_mediatek_anima_steps",
            failure(LocalImageGenerationOptions(width = 512, height = 512, steps = 20))?.code
        )
        assertEquals(
            "unsupported_mediatek_anima_cfg",
            failure(LocalImageGenerationOptions(width = 512, height = 512, cfgScale = 7.0))?.code
        )
        assertEquals(
            "unsupported_mediatek_anima_negative_prompt",
            failure(LocalImageGenerationOptions(negativePrompt = "text, watermark"))?.code
        )
        assertEquals(
            "unsupported_mediatek_anima_options",
            failure(LocalImageGenerationOptions(taskMode = LocalImageTaskMode.IMG2IMG))?.code
        )
        assertEquals(
            "invalid_image_seed",
            failure(LocalImageGenerationOptions(seed = -1))?.code
        )
        assertTrue(
            failure(LocalImageGenerationOptions(steps = 20))?.message.orEmpty()
                .contains("天玑 NPU 生成参数不可用")
        )
    }

    @Test
    fun `v3 style contract keeps its negative branch and declared step set`() {
        val profile = MediaTekImageGenerationProfile(
            width = 512,
            height = 512,
            supportedSteps = setOf(20, 30),
            defaultSteps = 20,
            defaultCfgScale = 7.0f,
            minimumCfgScale = 1.0f,
            maximumCfgScale = 12.0f
        )
        val contract = MediaTekImageProductContract(
            schema = "mca.image_engine.bundle.v3",
            manifestSha256 = "b".repeat(64),
            recommendationId = null,
            recommendationRevision = null,
            supportsNegativePrompt = true,
            profiles = listOf(profile),
            defaultProfile = profile
        )

        val accepted = normalizeMediaTekImageOptions(
            contract,
            LocalImageGenerationOptions(
                width = 512,
                height = 512,
                steps = 30,
                cfgScale = 9.5,
                negativePrompt = "low quality",
                sampleMethod = "euler"
            )
        )
        assertNull(accepted.sampleMethod)
        assertEquals("low quality", accepted.negativePrompt)

        val unsupportedSteps = runCatching {
            normalizeMediaTekImageOptions(contract, LocalImageGenerationOptions(steps = 25))
        }.exceptionOrNull() as? LocalImageProductContractException
        assertEquals("unsupported_mediatek_anima_steps", unsupportedSteps?.code)

        val unsupportedCfg = runCatching {
            normalizeMediaTekImageOptions(contract, LocalImageGenerationOptions(cfgScale = 20.0))
        }.exceptionOrNull() as? LocalImageProductContractException
        assertEquals("unsupported_mediatek_anima_cfg", unsupportedCfg?.code)
    }

    @Test
    fun `ui defaults follow the manifest profile instead of sd fallbacks`() {
        val contract = aotContract()
        val defaults = mediaTekImageExecutionDefaultsForUi(contract)

        assertEquals(512, defaults.width)
        assertEquals(512, defaults.height)
        assertEquals(6, defaults.steps)
        assertEquals(6, defaults.minSteps)
        assertEquals(6, defaults.maxSteps)
        assertEquals(1.0, defaults.cfgScale, 0.0)
        assertEquals(1.0, defaults.minCfgScale, 0.0)
        assertEquals(1.0, defaults.maxCfgScale, 0.0)
        assertEquals("euler", defaults.sampler)
        assertEquals(listOf("euler"), defaults.supportedSamplers)
        assertTrue(defaults.img2ImgSupportedSamplers.isEmpty())
        assertFalse(defaults.supportsCustomSize)
    }

    @Test
    fun `prompt execution bindings are stable manifest digests`() {
        val contract = aotContract()
        val first = contract.promptExecutionBindings()
        val second = aotContract().promptExecutionBindings()

        assertEquals(first, second)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first.imageProfileFingerprint))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first.promptLanguageFingerprint))
        assertTrue(
            first.imageProfileFingerprint != first.promptLanguageFingerprint
        )
    }
}
