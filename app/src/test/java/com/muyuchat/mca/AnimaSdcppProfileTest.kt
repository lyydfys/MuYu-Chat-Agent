package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnimaSdcppProfileTest {
    @Test
    fun `anima uses the 16 channel dit topology and flow prediction`() {
        val profile = animaSdcppProfile("sdcpp.anima", "fingerprint")

        assertEquals(LocalImageRuntime.STABLE_DIFFUSION_CPP, profile.runtime)
        assertEquals(LocalImageModelFamily.ANIMA, profile.family)
        assertEquals(ImageModelVariant.ANIMA_PREVIEW, profile.variant)
        assertEquals(16, profile.latent.channels)
        assertEquals(8, profile.latent.downsampleFactor)
        assertEquals(listOf(1, 16, 64, 64), profile.latent.initialShape)
        assertEquals(listOf(1, 16, 64, 64), profile.vae.inputShape)
        assertEquals(listOf(1, 3, 512, 512), profile.vae.outputShape)
        assertEquals(ImageSchedulerAlgorithm.EULER, profile.scheduler.algorithm)
        assertEquals(ImagePredictionType.FLOW, profile.scheduler.predictionType)
        assertEquals(6.0, profile.defaults.cfgScale, 0.0)
        assertEquals(8, profile.defaults.steps)
        assertEquals(64, profile.capabilities.widthMultiple)
        assertEquals(setOf(ImageSchedulerAlgorithm.EULER), profile.capabilities.supportedSchedulers)
        assertTrue(profile.capabilities.supportsNegativePrompt)
        assertTrue(!profile.capabilities.supportsTextualInversion)
        assertTrue(!profile.capabilities.supportsLora)
        assertEquals(ImageTokenizerBackend.SDCPP_NATIVE, profile.tokenizer.backend)
        assertEquals(512, profile.tokenizer.maxLength)
        assertEquals(null, profile.tokenizer.bosId)
        assertEquals(null, profile.tokenizer.eosId)
        assertEquals(null, profile.tokenizer.padId)
        assertEquals(ImageClipPadRule.MODEL_DECLARED, profile.tokenizer.clip1PadRule)
        assertTrue(!profile.tokenizer.supportsPromptWeighting)
    }

    @Test
    fun `generic anima fallback does not decay into the sd15 epsilon profile`() {
        val profile = genericProfile(
            LocalImageRuntime.STABLE_DIFFUSION_CPP,
            LocalImageModelFamily.ANIMA,
            "fingerprint"
        )

        assertEquals(LocalImageModelFamily.ANIMA, profile.family)
        assertEquals(ImageModelVariant.ANIMA_PREVIEW, profile.variant)
        assertEquals(16, profile.latent.channels)
        assertEquals(ImagePredictionType.FLOW, profile.scheduler.predictionType)
        assertEquals(ImageSchedulerAlgorithm.EULER, profile.scheduler.algorithm)
        assertEquals(ImageProfileSource.GENERIC_FALLBACK, profile.provenance.primarySource)
    }

    @Test
    fun `anima filenames are recognized without capturing animagine sdxl`() {
        assertEquals(
            LocalImageModelFamily.ANIMA,
            LocalImageModelFamily.infer("anima-preview.safetensors")
        )
        assertEquals(
            LocalImageModelFamily.ANIMA,
            LocalImageModelFamily.infer("anima-preview-Q4_K_M.gguf")
        )
        assertTrue(
            LocalImageModelFamily.infer("animagine_xl_v4.safetensors") !=
                LocalImageModelFamily.ANIMA
        )
    }

    @Test
    fun `anima request defaults stay inside the verified topology`() {
        assertEquals(8, defaultStepsFor(LocalImageModelFamily.ANIMA))
        assertEquals(6.0, defaultCfgFor(LocalImageModelFamily.ANIMA), 0.0)
        assertEquals("512x512", defaultImageSizeFor("anima-preview.safetensors"))
    }

    @Test
    fun `anima native evidence must prove family variant latent and cpu backend`() {
        requireAnimaSdcppExecutionEvidence(validEvidence())

        assertRejected(
            "architecture",
            evidence(family = "QWEN_IMAGE")
        )
        assertRejected(
            "variant",
            evidence(variant = "GENERIC_COMPATIBLE")
        )
        assertRejected(
            "latent",
            evidence(channels = 4)
        )
        assertRejected(
            "backend",
            evidence(backend = "npu")
        )
    }

    private fun validEvidence(): JSONObject = evidence()

    private fun evidence(
        family: String = "ANIMA",
        variant: String = "ANIMA_PREVIEW",
        channels: Int = 16,
        backend: String = "cpu"
    ): JSONObject = JSONObject()
        .put("runtimeBackend", backend)
        .put(
            "nativeEffective",
            JSONObject()
                .put("actualModelVersion", "Anima")
                .put("actualModelFamily", family)
                .put("actualModelVariant", variant)
                .put("actualLatentChannels", channels)
                .put("actualLatentDownsample", 8)
        )

    private fun assertRejected(field: String, result: JSONObject) {
        try {
            requireAnimaSdcppExecutionEvidence(result)
            fail("Expected Anima evidence rejection for $field")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "rejection should mention $field, got ${error.message}",
                error.message.orEmpty().lowercase().contains(field)
            )
        }
    }
}
