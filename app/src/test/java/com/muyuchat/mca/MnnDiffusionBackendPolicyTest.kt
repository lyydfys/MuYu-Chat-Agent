package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnDiffusionBackendPolicyTest {
    @Test
    fun `only native CPU NOT_SUPPORT is eligible for automatic OpenCL retry`() {
        assertTrue(
            isMnnCpuBackendUnsupported(
                JSONObject()
                    .put("backendMode", "cpu")
                    .put("errorCode", MNN_UNET_BACKEND_UNSUPPORTED_ERROR_CODE)
            )
        )
        assertTrue(
            isMnnCpuBackendUnsupported(
                JSONObject()
                    .put("backendMode", "cpu")
                    .put("error", "UNet resizeSession failed with status 2.")
            )
        )
        assertFalse(
            isMnnCpuBackendUnsupported(
                JSONObject()
                    .put("backendMode", "cpu")
                    .put("error", "MNN bundle is missing unet.mnn.weight")
            )
        )
        assertFalse(
            isMnnCpuBackendUnsupported(
                JSONObject()
                    .put("backendMode", "opencl")
                    .put("error", "UNet resizeSession failed with status 2.")
            )
        )
    }

    @Test
    fun `backend audit preserves auto request and actual fallback`() {
        val result = JSONObject()
            .put("ok", true)
            .withMnnBackendAudit(
                requested = "auto",
                effective = "opencl",
                attempts = listOf(
                    MnnDiffusionBackendAttempt(
                        backend = "cpu",
                        ok = false,
                        errorCode = MNN_UNET_BACKEND_UNSUPPORTED_ERROR_CODE,
                        error = "fused UNet op"
                    ),
                    MnnDiffusionBackendAttempt(backend = "opencl", ok = true)
                ),
                fallback = true,
                fallbackReason = "CPU graph returned NOT_SUPPORT"
            )

        assertEquals("auto", result.getString("requestedBackendMode"))
        assertEquals("opencl", result.getString("effectiveBackendMode"))
        assertEquals("opencl", result.getString("backendMode"))
        assertTrue(result.getBoolean("backendFallback"))
        assertEquals("CPU graph returned NOT_SUPPORT", result.getString("backendFallbackReason"))
        assertEquals(2, result.getJSONArray("backendAttempts").length())
        assertEquals("cpu", result.getJSONArray("backendAttempts").getJSONObject(0).getString("backend"))
        assertFalse(result.getJSONArray("backendAttempts").getJSONObject(0).getBoolean("ok"))
        assertTrue(result.getJSONArray("backendAttempts").getJSONObject(1).getBoolean("ok"))
    }

    @Test
    fun `omitted backend is auto while explicit blank remains invalid`() {
        assertEquals("auto", requestedMnnDiffusionBackendMode(null))
        assertEquals("cpu", requestedMnnDiffusionBackendMode(" CPU "))
        assertEquals("opencl", requestedMnnDiffusionBackendMode("gpu"))
        assertEquals("invalid", requestedMnnDiffusionBackendMode("  "))
    }
}
