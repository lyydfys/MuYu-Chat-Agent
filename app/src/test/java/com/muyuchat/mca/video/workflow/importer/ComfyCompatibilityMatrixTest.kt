package com.muyuchat.mca.video.workflow.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyCompatibilityMatrixTest {

    @Test
    fun `pins the mapping version and supported class set`() {
        assertEquals("comfy-api/2026-10-25", ComfyCompatibilityMatrix.MAPPING_VERSION)
        assertEquals(
            setOf(
                "UNETLoader",
                "CLIPLoader",
                "VAELoader",
                "CLIPTextEncode",
                "EmptyHunyuanLatentVideo",
                "WanImageToVideo",
                "LoadImage",
                "ModelSamplingSD3",
                "KSampler",
                "VAEDecode",
                "CreateVideo",
                "SaveVideo",
            ),
            ComfyCompatibilityMatrix.supportedClassTypes,
        )
        assertTrue(ComfyCompatibilityMatrix.NODE_SPECS.all { it.role.isNotBlank() })
    }

    @Test
    fun `pins profile model files used for binding`() {
        val profiles = ComfyCompatibilityMatrix.modelProfiles
        assertEquals(3, profiles.size)
        assertTrue(profiles.any { it.ditFileName == "Wan2.2-TI2V-5B-Q4_0.gguf" })
        assertTrue(profiles.any { it.ditFileName == "Wan2.2-TI2V-5B-Q4_K_M.gguf" })
        assertTrue(profiles.any { it.encoderFileName == "umt5-xxl-encoder-Q8_0.gguf" })
        assertTrue(profiles.any { it.encoderFileName == "umt5-xxl-encoder-Q4_K_M.gguf" })
        assertTrue(profiles.all { it.vaeFileName == "Wan2.2_VAE.safetensors" })
    }
}
