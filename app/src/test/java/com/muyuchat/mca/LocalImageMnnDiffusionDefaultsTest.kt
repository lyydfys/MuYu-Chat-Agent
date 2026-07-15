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
        assertEquals(20, resolveMnnDiffusionSteps(request.model.family, request.options.steps))
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

        assertEquals(12, resolveMnnDiffusionSteps(request.model.family, request.options.steps))
    }

    @Test
    fun `MNN defaults for other families retain their calibrated values`() {
        assertEquals(5, resolveMnnDiffusionSteps(LocalImageModelFamily.SANA, null))
        assertEquals(8, resolveMnnDiffusionSteps(LocalImageModelFamily.SDXL, null))
    }

    @Test
    fun `MNN validates exact dimensions and step bounds before native execution`() {
        val legacyRecord = sd15MnnModel().copy(imageSize = "384x384")
        val productDefault = resolveMnnDiffusionProductDimensions(legacyRecord, runner = "direct")
        assertEquals(512 to 512, productDefault)
        assertEquals(384 to 384, resolveMnnDiffusionProductDimensions(legacyRecord, runner = "module"))
        assertEquals(
            512 to 512,
            resolveMnnDiffusionDimensions(
                defaultWidth = productDefault.first,
                defaultHeight = productDefault.second,
                requestedWidth = null,
                requestedHeight = null
            )
        )
        assertEquals(512 to 512, resolveMnnDiffusionDimensions(512, 512, 512, 512))
        assertEquals(512 to 512, resolveMnnDiffusionDimensions(512, 512, null, null))
        assertInvalid { resolveMnnDiffusionDimensions(512, 512, 384, 512) }
        assertInvalid { resolveMnnDiffusionDimensions(512, 512, 512, 384) }
        assertEquals(1, resolveMnnDiffusionSteps(LocalImageModelFamily.SD15, 1))
        assertEquals(50, resolveMnnDiffusionSteps(LocalImageModelFamily.SD15, 50))
        assertInvalid { resolveMnnDiffusionSteps(LocalImageModelFamily.SD15, 0) }
        assertInvalid { resolveMnnDiffusionSteps(LocalImageModelFamily.SD15, 51) }
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
        assertEquals("euler", resolveMnnDiffusionSampleMethod(null))
        assertEquals("euler", resolveMnnDiffusionSampleMethod(" EULER "))
        assertInvalid { resolveMnnDiffusionSampleMethod("") }
        assertInvalid { resolveMnnDiffusionSampleMethod("dpm++") }
        assertEquals("auto", resolveMnnDiffusionTokenEmbeddingMode(null))
        assertInvalid { resolveMnnDiffusionTokenEmbeddingMode("") }
        assertInvalid { resolveMnnDiffusionTokenEmbeddingMode("second_half") }
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
        assertInvalid { resolveMnnDiffusionSampleMethod(parsed.sampleMethod) }
        assertInvalid { resolveMnnDiffusionTokenEmbeddingMode(parsed.tokenEmbeddingMode) }
        assertInvalid { resolveMnnDiffusionMemoryMode(parsed.memoryMode) }
    }

    @Test
    fun `native result controls are verified when present and audit remains explicit when absent`() {
        val absent = JSONObject().put("ok", true)
        verifyMnnDiffusionResultControlIfPresent(absent, "cfgScale", 7.0)

        val matching = JSONObject()
            .put("cfgScale", 7.0)
            .put("flowShift", 0.0)
            .put("useCfg", false)
            .put("sampleMethod", "EULER")
            .put("tokenEmbeddingMode", "auto")
            .put("memoryMode", 0)
        verifyMnnDiffusionResultControlIfPresent(matching, "cfgScale", 7.0)
        verifyMnnDiffusionResultControlIfPresent(matching, "flowShift", 0.0)
        verifyMnnDiffusionResultControlIfPresent(matching, "useCfg", false)
        verifyMnnDiffusionResultControlIfPresent(matching, "sampleMethod", "euler")
        verifyMnnDiffusionResultControlIfPresent(matching, "tokenEmbeddingMode", "auto")
        verifyMnnDiffusionResultControlIfPresent(matching, "memoryMode", 0)

        assertInvalid {
            verifyMnnDiffusionResultControlIfPresent(
                JSONObject().put("memoryMode", 2),
                "memoryMode",
                0
            )
        }

        val audit = mnnDiffusionControlAuditJson(
            JSONObject()
                .put("prompt", "must not leak")
                .put("cfgScale", 7.0)
                .put("sampleMethod", "euler")
                .put("memoryMode", 0)
        )
        assertFalse(audit.has("prompt"))
        assertEquals(7.0, audit.getDouble("cfgScale"), 0.0)
        assertEquals("euler", audit.getString("sampleMethod"))
        assertEquals(0, audit.getInt("memoryMode"))
    }

    @Test
    fun `MNN product smoke requires exact direct OpenCL 512 twenty-step execution`() {
        val matching = JSONObject()
            .put("ok", true)
            .put("runner", "direct")
            .put("backendMode", "opencl")
            .put("steps", 20)
            .put("width", 512)
            .put("height", 512)

        verifyMnnDiffusionExecutionContract(matching, "direct", "opencl", 20, 512, 512)

        assertInvalid {
            verifyMnnDiffusionExecutionContract(
                JSONObject(matching.toString()).put("runner", "module"),
                "direct",
                "opencl",
                20,
                512,
                512
            )
        }
        assertInvalid {
            verifyMnnDiffusionExecutionContract(
                JSONObject(matching.toString()).put("backendMode", "cpu"),
                "direct",
                "opencl",
                20,
                512,
                512
            )
        }
        assertInvalid {
            verifyMnnDiffusionExecutionContract(
                JSONObject(matching.toString()).put("steps", 8),
                "direct",
                "opencl",
                20,
                512,
                512
            )
        }
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

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: explicit worker controls must not be silently replaced.
        }
    }
}
