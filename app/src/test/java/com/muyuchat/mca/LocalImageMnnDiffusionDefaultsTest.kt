package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalImageMnnDiffusionDefaultsTest {
    @Test
    fun `legacy worker request without options uses the SD15 product default`() {
        val model = sd15MnnModel()

        val legacyRequestJson = JSONObject(
            LocalImageWorkerProtocol.generateRequest(
                requestId = "empty-options",
                model = model,
                prompt = "a tiny ceramic robot"
            )
        ).apply { remove("options") }
        val request = LocalImageWorkerProtocol.parseGenerateRequest(legacyRequestJson.toString())

        assertNull(request.options.steps)
        assertEquals(20, resolveMnnProfile().layers.resolved.steps)
    }

    @Test
    fun `explicit worker steps override the SD15 product default`() {
        val model = sd15MnnModel()

        val request = LocalImageWorkerProtocol.parseGenerateRequest(
            LocalImageWorkerProtocol.generateRequest(
                requestId = "explicit-options",
                model = model,
                prompt = "a tiny ceramic robot",
                options = LocalImageGenerationOptions(steps = 12)
            )
        )

        assertEquals(12, resolveMnnProfile(steps = request.options.steps).layers.resolved.steps)
    }

    @Test
    fun `MNN validates exact dimensions and step bounds before native execution`() {
        assertEquals(
            512 to 512,
            resolveMnnDiffusionDimensions(
                defaultWidth = 512,
                defaultHeight = 512,
                requestedWidth = null,
                requestedHeight = null
            )
        )
        assertEquals(512 to 512, resolveMnnDiffusionDimensions(512, 512, 512, 512))
        assertEquals(512 to 512, resolveMnnDiffusionDimensions(512, 512, null, null))
        assertInvalid { resolveMnnDiffusionDimensions(512, 512, 384, 512) }
        assertInvalid { resolveMnnDiffusionDimensions(512, 512, 512, 384) }
    }

    @Test
    fun `MNN validates backend runner and thread options without silent fallback`() {
        assertEquals("opencl", resolveMnnDiffusionBackendMode("GPU"))
        assertEquals("cpu", resolveMnnDiffusionBackendMode(" cpu "))
        assertTrue(mnnDiffusionBackendMatches("opencl", "gpu"))
        assertTrue(mnnDiffusionBackendMatches("opencl", "opencl"))
        assertTrue(mnnDiffusionBackendMatches("cpu", "cpu"))
        assertFalse(mnnDiffusionBackendMatches("cpu", "opencl"))
        assertInvalid { resolveMnnDiffusionBackendMode("vulkan") }
        val blankBackend = roundTripOptions(LocalImageGenerationOptions(backendMode = ""))
        assertInvalid { resolveMnnDiffusionBackendMode(blankBackend.backendMode) }
        assertEquals("direct", resolveMnnDiffusionRunner(LocalImageModelFamily.SD15, null))
        assertEquals("module", resolveMnnDiffusionRunner(LocalImageModelFamily.SD15, "module"))
        assertInvalid { resolveMnnDiffusionRunner(LocalImageModelFamily.SD15, "sana") }
        assertEquals("sana_varp", resolveMnnDiffusionRunner(LocalImageModelFamily.SANA, null))
        assertEquals("sana_varp", resolveMnnDiffusionRunner(LocalImageModelFamily.SANA, "module"))
        assertEquals("sana_varp", resolveMnnDiffusionRunner(LocalImageModelFamily.SANA, "sana"))
        assertInvalid { resolveMnnDiffusionRunner(LocalImageModelFamily.SANA, "direct") }
        val blankRunner = roundTripOptions(LocalImageGenerationOptions(runner = ""))
        assertInvalid { resolveMnnDiffusionRunner(LocalImageModelFamily.SD15, blankRunner.runner) }
        assertEquals(4, resolveMnnDiffusionThreads(4, 5))
        assertEquals(5, resolveMnnDiffusionThreads(null, 5))
        assertInvalid { resolveMnnDiffusionThreads(0, 5) }
        assertInvalid { resolveMnnDiffusionThreads(65, 5) }
        assertEquals(0, resolveMnnDiffusionMemoryMode(null))
        assertEquals(2, resolveMnnDiffusionMemoryMode(2))
        assertInvalid { resolveMnnDiffusionMemoryMode(-1) }
        assertInvalid { resolveMnnDiffusionMemoryMode(3) }
    }

    @Test
    fun `present blank controls survive IPC parsing and fail closed`() {
        val parsed = roundTripOptions(
            LocalImageGenerationOptions(
                sampleMethod = "",
                tokenEmbeddingMode = "",
                memoryMode = 3
            )
        )

        assertEquals("", parsed.sampleMethod)
        assertEquals("", parsed.tokenEmbeddingMode)
        assertEquals(3, parsed.memoryMode)
        assertInvalid { resolveMnnDiffusionMemoryMode(parsed.memoryMode) }
    }

    @Test
    fun `MNN audit excludes prompt and obsolete token embedding mode`() {
        val audit = mnnDiffusionControlAuditJson(
            JSONObject()
                .put("prompt", "must not leak")
                .put("cfgScale", 7.0)
                .put("sampleMethod", "euler")
                .put("tokenEmbeddingMode", "auto")
                .put("memoryMode", 0)
        )
        assertFalse(audit.has("prompt"))
        assertFalse(audit.has("tokenEmbeddingMode"))
        assertEquals(7.0, audit.getDouble("cfgScale"), 0.0)
        assertEquals("euler", audit.getString("sampleMethod"))
        assertEquals(0, audit.getInt("memoryMode"))
    }

    @Test
    fun `MNN graph conditioning wins over valid legacy token tables`() {
        assertEquals(
            ImageEmbeddingDiskDataType.GRAPH_INTERNAL,
            resolveMnnConditioningDiskDataType(
                graphInternal = true,
                tokenEmbeddingByteSize = 75_890_688L
            )
        )
        assertEquals(
            ImageEmbeddingDiskDataType.GRAPH_INTERNAL,
            resolveMnnConditioningDiskDataType(
                graphInternal = true,
                tokenEmbeddingByteSize = 151_781_376L
            )
        )
    }

    @Test
    fun `MNN legacy token tables use exact disk precision contracts`() {
        assertEquals(
            ImageEmbeddingDiskDataType.FP16,
            resolveMnnConditioningDiskDataType(false, 75_890_688L)
        )
        assertEquals(
            ImageEmbeddingDiskDataType.FP32,
            resolveMnnConditioningDiskDataType(false, 151_781_376L)
        )
        assertEquals(
            ImageEmbeddingDiskDataType.RUNTIME_NATIVE,
            resolveMnnConditioningDiskDataType(false, null)
        )
        assertInvalid { resolveMnnConditioningDiskDataType(true, 75_890_687L) }
        assertInvalid { resolveMnnConditioningDiskDataType(false, 151_781_375L) }
    }

    @Test
    fun `PNG pixel smoke accepts varied images and rejects blank or striped corruption`() {
        val width = 64
        val height = 64
        val varied = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val red = x * 255 / (width - 1)
            val green = y * 255 / (height - 1)
            val blue = (x + y) * 255 / (width + height - 2)
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val blank = IntArray(width * height) { 0xff808080.toInt() }
        val striped = IntArray(width * height) { index ->
            val y = index / width
            if (y % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt()
        }

        assertTrue(evaluateLocalImageSmokePixels(width, height, varied).passed)
        assertFalse(evaluateLocalImageSmokePixels(width, height, blank).passed)
        assertFalse(evaluateLocalImageSmokePixels(width, height, striped).passed)
    }

    private fun sd15MnnModel() = LocalImageModelRecord(
        id = "mnn-sd15",
        displayName = "MNN Stable Diffusion 1.5",
        path = "/data/local/tmp/mnn-sd15/unet.mnn",
        fileName = "unet.mnn",
        sizeBytes = 1L,
        sha256 = "test",
        runtime = LocalImageRuntime.MNN_DIFFUSION,
        family = LocalImageModelFamily.SD15,
        bundleRoot = "/data/local/tmp/mnn-sd15"
    )

    private fun roundTripOptions(options: LocalImageGenerationOptions): LocalImageGenerationOptions =
        LocalImageWorkerProtocol.parseGenerateRequest(
            LocalImageWorkerProtocol.generateRequest(
                requestId = "options-round-trip",
                model = sd15MnnModel(),
                prompt = "a tiny ceramic robot",
                options = options
            )
        ).options

    private fun resolveMnnProfile(steps: Int? = null): ImageExecutionProfileResolution =
        ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = "a".repeat(64),
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.SD15,
                recommendationId = "sd15_mnn_512_quality",
                userOverrides = ImageGenerationOverrides(steps = steps)
            )
        )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: explicit worker controls must not be silently replaced.
        }
    }
}
