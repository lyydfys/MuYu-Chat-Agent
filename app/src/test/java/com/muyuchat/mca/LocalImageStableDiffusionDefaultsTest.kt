package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalImageStableDiffusionDefaultsTest {
    @Test
    fun `stable diffusion native controls require exact finite echo`() {
        val result = JSONObject()
            .put("cfgScale", 1.0)
            .put("distilledGuidance", 3.5)
            .put("flowShift", -1.0)

        verifyStableDiffusionResultControl(result, "cfgScale", 1.0)
        verifyStableDiffusionResultControl(result, "distilledGuidance", 3.5)
        verifyStableDiffusionResultControl(result, "flowShift", -1.0)

        assertInvalid { verifyStableDiffusionResultControl(JSONObject(), "cfgScale", 1.0) }
        assertInvalid {
            verifyStableDiffusionResultControl(
                JSONObject().put("cfgScale", 1.25),
                "cfgScale",
                1.0
            )
        }
    }

    @Test
    fun `SD Turbo product defaults remain 512 one-step CFG one`() {
        assertEquals(
            512 to 512,
            resolveStableDiffusionDimensions(512, 512, null, null)
        )
        assertEquals(1, resolveStableDiffusionSteps(LocalImageModelFamily.SD_TURBO, null))
        assertEquals(1.0, resolveStableDiffusionFiniteControl("cfgScale", null, 1.0), 0.0)
        assertEquals("euler", resolveStableDiffusionSampleMethod(null))
        assertEquals("cpu", resolveStableDiffusionBackendMode(null))
    }

    @Test
    fun `LongCat uses its model-specific CFG default`() {
        assertEquals(5.0, defaultCfgFor(LocalImageModelFamily.LONGCAT_IMAGE), 0.0)
        assertEquals(7.0, defaultCfgFor(LocalImageModelFamily.SD15), 0.0)
    }

    @Test
    fun `explicit stable diffusion worker controls are preserved`() {
        assertEquals(
            384 to 512,
            resolveStableDiffusionDimensions(512, 512, 384, 512)
        )
        assertEquals(7, resolveStableDiffusionSteps(LocalImageModelFamily.SD_TURBO, 7))
        assertEquals(6, resolveStableDiffusionThreads(6, 4))
        assertEquals("lcm", resolveStableDiffusionSampleMethod(" LCM "))
        assertEquals("cpu", resolveStableDiffusionBackendMode(" CPU "))
        assertEquals(2.75, resolveStableDiffusionFiniteControl("cfgScale", 2.75, 1.0), 0.0)
    }

    @Test
    fun `invalid stable diffusion controls fail before native execution`() {
        assertInvalid { resolveStableDiffusionDimensions(512, 512, 385, 512) }
        assertInvalid { resolveStableDiffusionDimensions(512, 512, 512, 128) }
        assertInvalid { resolveStableDiffusionSteps(LocalImageModelFamily.SD_TURBO, 0) }
        assertInvalid { resolveStableDiffusionSteps(LocalImageModelFamily.SD_TURBO, 51) }
        assertInvalid { resolveStableDiffusionThreads(0, 4) }
        assertInvalid { resolveStableDiffusionThreads(65, 4) }
        assertInvalid { resolveStableDiffusionSampleMethod("not-a-sampler") }
        assertInvalid { resolveStableDiffusionBackendMode("opencl") }
        assertInvalid { resolveStableDiffusionFiniteControl("cfgScale", Double.NaN, 1.0) }
    }

    @Test
    fun `stable diffusion profile keeps custom pixel controls in the strict native contract`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = "a".repeat(64),
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD15,
                recommendationId = null,
                userOverrides = ImageGenerationOverrides(
                    scheduler = ImageSchedulerAlgorithm.DDIM,
                    steps = 12,
                    cfgScale = 1.0,
                    useCfg = false,
                    width = 384,
                    height = 512,
                    seed = 1_234L,
                    negativePrompt = "",
                    negativePromptSpecified = true
                )
            )
        )
        val resolved = resolution.layers.resolved
        val params = ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)

        assertEquals(LocalImageRuntime.STABLE_DIFFUSION_CPP, resolved.runtime)
        assertEquals(ImageTokenizerBackend.SDCPP_NATIVE, resolved.tokenizerBackend)
        assertEquals(ImageEmbeddingDiskDataType.RUNTIME_NATIVE, resolved.embeddingDiskDataType)
        assertEquals(ImageVaeScalingLocation.RUNTIME_NATIVE, resolved.vaeScalingLocation)
        assertEquals(ImageSchedulerAlgorithm.DDIM, resolved.scheduler)
        assertEquals(12, resolved.steps)
        assertEquals(384, resolved.width)
        assertEquals(512, resolved.height)
        assertEquals(1_234L, resolved.seed)
        assertFalse(resolved.useCfg)
        assertEquals("", resolution.profile.defaults.defaultNegativePrompt)
        assertEquals("ddim", params.getString("sampleMethod"))
    }

    @Test
    fun `stable diffusion verifies scheduler-specific native sampler names`() {
        assertTrue(stableDiffusionNativeSampleMethodMatches(ImageSchedulerAlgorithm.EULER, "euler"))
        assertTrue(stableDiffusionNativeSampleMethodMatches(ImageSchedulerAlgorithm.DPMPP_2M, "dpm++2m"))
        assertTrue(stableDiffusionNativeSampleMethodMatches(ImageSchedulerAlgorithm.DDIM, "ddim_trailing"))
        assertTrue(stableDiffusionNativeSampleMethodMatches(ImageSchedulerAlgorithm.FLOW_MATCH, "euler"))
        assertFalse(stableDiffusionNativeSampleMethodMatches(ImageSchedulerAlgorithm.PNDM_PLMS, "pndm"))
        assertFalse(stableDiffusionNativeSampleMethodMatches(ImageSchedulerAlgorithm.EULER_A, "euler"))
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: product controls must not silently fall back.
        }
    }
}
