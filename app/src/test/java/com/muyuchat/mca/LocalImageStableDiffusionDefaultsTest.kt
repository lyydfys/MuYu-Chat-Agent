package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: product controls must not silently fall back.
        }
    }
}
