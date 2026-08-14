package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalImageStableDiffusionDefaultsTest {
    @Test
    fun `stable diffusion native controls distinguish requested and effective values`() {
        val result = JSONObject()
            .put("cfgScale", 1.0)
            .put("requestedDistilledGuidance", 3.5)
            .put("distilledGuidanceSpecified", false)
            .put("distilledGuidanceApplied", false)
            .put("distilledGuidance", JSONObject.NULL)
            .put("requestedFlowShift", -1.0)
            .put("flowShiftSpecified", false)
            .put("flowShiftApplied", true)
            .put("dynamicFlowShift", true)
            .put("flowShift", 1.1275)

        verifyStableDiffusionResultControl(result, "cfgScale", 1.0)
        verifyStableDiffusionDistilledGuidanceResult(result, 3.5, specified = false)
        verifyStableDiffusionFlowShiftResult(
            result,
            requested = -1.0,
            specified = false,
            expectApplied = true
        )

        assertInvalid { verifyStableDiffusionResultControl(JSONObject(), "cfgScale", 1.0) }
        assertInvalid {
            verifyStableDiffusionResultControl(
                JSONObject().put("cfgScale", 1.25),
                "cfgScale",
                1.0
            )
        }
        assertInvalid {
            verifyStableDiffusionFlowShiftResult(
                JSONObject(result.toString()).put("flowShift", -1.0),
                requested = -1.0,
                specified = false,
                expectApplied = true
            )
        }
    }

    @Test
    fun `local image output directory is canonical before native handoff`() {
        val root = Files.createTempDirectory("local-image-output-root").toFile()
        try {
            val cache = File(root, "cache").apply { assertTrue(mkdirs()) }
            val lexicalCache = File(root, "cache${File.separator}..${File.separator}cache")
            val outputDirectory = canonicalLocalImageOutputDirectory(
                lexicalCache,
                "local_image_outputs"
            )
            val outputFile = File(outputDirectory, "sdcpp-request.png").canonicalFile

            assertTrue(lexicalCache.path.contains(".."))
            assertEquals(File(cache, "local_image_outputs").canonicalFile, outputDirectory)
            assertEquals(outputDirectory.canonicalPath, outputDirectory.path)
            assertEquals(outputFile.canonicalPath, outputFile.path)
            assertTrue(outputDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion flow defaults preserve model math`() {
        fun profile(id: String, family: LocalImageModelFamily) =
            ImageExecutionProfileResolver.resolve(
                ImageExecutionProfileResolverInput(
                    modelFingerprint = "b".repeat(64),
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = family,
                    recommendationId = id
                )
            ).profile

        assertEquals(
            -1.0,
            defaultStableDiffusionFlowShiftFor(
                profile("sd_turbo_512_experimental", LocalImageModelFamily.SD_TURBO)
            ),
            0.0
        )
        assertEquals(
            -1.0,
            defaultStableDiffusionFlowShiftFor(
                profile("flux2_klein_4b_q4", LocalImageModelFamily.FLUX)
            ),
            0.0
        )
        assertEquals(
            3.0,
            defaultStableDiffusionFlowShiftFor(
                profile("z_image_turbo_q4", LocalImageModelFamily.Z_IMAGE)
            ),
            0.0
        )
        assertEquals(
            3.0,
            defaultStableDiffusionFlowShiftFor(
                profile("qwen_image_2512_q2", LocalImageModelFamily.QWEN_IMAGE)
            ),
            0.0
        )
        assertEquals(
            3.0,
            defaultStableDiffusionFlowShiftFor(
                profile("longcat_image_q4", LocalImageModelFamily.LONGCAT_IMAGE)
            ),
            0.0
        )
    }

    @Test
    fun `SD Turbo recommendation remains 512 four-step conditional-only`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = "a".repeat(64),
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO,
                recommendationId = "sd_turbo_512_experimental"
            )
        )
        val profile = resolution.profile

        assertEquals(
            512 to 512,
            resolveStableDiffusionDimensions(
                profile.defaults.width,
                profile.defaults.height,
                null,
                null
            )
        )
        assertEquals(4, profile.defaults.steps)
        assertEquals(
            4,
            resolveStableDiffusionSteps(LocalImageModelFamily.SD_TURBO, profile.defaults.steps)
        )
        assertEquals(1.0, profile.defaults.cfgScale, 0.0)
        assertFalse(profile.defaults.useCfg)
        assertFalse(profile.capabilities.supportsNegativePrompt)
        assertFalse(profile.tokenizer.separateNegativePrompt)
        assertEquals(77, resolution.layers.resolved.tokenCount)
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
        assertInvalid { resolveStableDiffusionFiniteControl("flowShift", -0.5, -1.0) }
        assertInvalid { resolveStableDiffusionFiniteControl("distilledGuidance", 31.0, 3.5) }
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
        assertEquals(77, resolved.tokenCount)
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

    @Test
    fun `stable diffusion consumes every ordered batch output and deletes every private file`() {
        val root = Files.createTempDirectory("sdcpp-batch-output").toFile()
        try {
            val firstBytes = stableDiffusionOutputBytes(marker = 1)
            val secondBytes = stableDiffusionOutputBytes(marker = 2)
            val first = File(root, "image.png").apply { writeBytes(firstBytes) }
            val second = File(root, "image-002.png").apply { writeBytes(secondBytes) }

            val outputs = consumeStableDiffusionOutputs(
                result = stableDiffusionBatchResult(first, second),
                expectedCount = 2,
                expectedSeed = 40L,
                legacyOutputFile = first
            )

            assertEquals(listOf(0, 1), outputs.map(LocalImageOutput::index))
            assertEquals(listOf(40L, 41L), outputs.map(LocalImageOutput::seed))
            assertArrayEquals(firstBytes, outputs[0].bytes)
            assertArrayEquals(secondBytes, outputs[1].bytes)
            assertFalse(first.exists())
            assertFalse(second.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy single output remains compatible and is deleted after consumption`() {
        val root = Files.createTempDirectory("sdcpp-legacy-output").toFile()
        try {
            val outputBytes = stableDiffusionOutputBytes(marker = 7)
            val output = File(root, "legacy.png").apply { writeBytes(outputBytes) }
            val result = JSONObject()
                .put("path", output.canonicalPath)
                .put("mimeType", "image/png")

            val outputs = consumeStableDiffusionOutputs(
                result = result,
                expectedCount = 1,
                expectedSeed = 77L,
                legacyOutputFile = output
            )

            assertEquals(1, outputs.size)
            assertEquals(77L, outputs.single().seed)
            assertArrayEquals(outputBytes, outputs.single().bytes)
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `batch output contract rejects count index seed mime and file mismatches without leaks`() {
        assertInvalidBatchOutput(expectedCount = 1) { _, _ -> Unit }
        assertInvalidBatchOutput(expectedCount = 1) { result, second ->
            result.put("outputs", JSONObject().put("path", second.canonicalPath))
        }
        assertInvalidBatchOutput { result, _ ->
            result.put("outputCount", 1)
        }
        assertInvalidBatchOutput { result, _ ->
            result.put("n", 1)
        }
        assertInvalidBatchOutput { result, _ ->
            result.getJSONArray("outputs").getJSONObject(1).put("index", 2)
        }
        assertInvalidBatchOutput { result, _ ->
            result.getJSONArray("outputs").getJSONObject(1).put("seed", 99)
        }
        assertInvalidBatchOutput { result, _ ->
            result.getJSONArray("outputs").getJSONObject(1).put("mimeType", "image/jpeg")
        }
        assertInvalidBatchOutput { _, second ->
            assertTrue(second.delete())
        }
    }

    @Test
    fun `stable diffusion request cleanup removes only files derived from its request`() {
        val root = Files.createTempDirectory("sdcpp-request-cleanup").toFile()
        try {
            val output = File(root, "sdcpp-123.png")
            val candidates = stableDiffusionRequestOutputCandidates(output)
            candidates.forEach { candidate ->
                candidate.writeBytes(byteArrayOf(1))
            }
            val unrelated = File(root, "sdcpp-1234.png").apply { writeBytes(byteArrayOf(2)) }
            val unrelatedPreview = File(root, "other.preview-0.png").apply {
                writeBytes(byteArrayOf(3))
            }

            cleanupStableDiffusionRequestOutputs(output)

            assertTrue(candidates.none(File::exists))
            assertTrue(unrelated.isFile)
            assertTrue(unrelatedPreview.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion stale sweep is age bounded and filename scoped`() {
        val root = Files.createTempDirectory("sdcpp-stale-sweep").toFile()
        try {
            val now = 10L * STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS
            val stale = File(root, "sdcpp-123.png").apply { writeBytes(byteArrayOf(1)) }
            val staleBatchPart = File(root, "sdcpp-123-8.png.part").apply {
                writeBytes(byteArrayOf(2))
            }
            val stalePreviewPart = File(root, "sdcpp-123.png.preview-1.png.part").apply {
                writeBytes(byteArrayOf(3))
            }
            val fresh = File(root, "sdcpp-456.png").apply { writeBytes(byteArrayOf(4)) }
            val unrelated = File(root, "sdcpp-123-9.png").apply { writeBytes(byteArrayOf(5)) }
            listOf(stale, staleBatchPart, stalePreviewPart, unrelated).forEach { file ->
                assertTrue(file.setLastModified(now - STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS - 1L))
            }
            assertTrue(fresh.setLastModified(now - STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS + 1L))

            pruneStaleStableDiffusionOutputs(root, nowMillis = now)

            assertFalse(stale.exists())
            assertFalse(staleBatchPart.exists())
            assertFalse(stalePreviewPart.exists())
            assertTrue(fresh.isFile)
            assertTrue(unrelated.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun stableDiffusionOutputBytes(marker: Int): ByteArray =
        ByteArray(57) { index -> (marker + index).toByte() }

    private fun stableDiffusionBatchResult(first: File, second: File): JSONObject = JSONObject()
        .put("path", first.canonicalPath)
        .put("mimeType", "image/png")
        .put("outputCount", 2)
        .put("n", 2)
        .put(
            "outputs",
            JSONArray()
                .put(
                    JSONObject()
                        .put("index", 0)
                        .put("path", first.canonicalPath)
                        .put("mimeType", "image/png")
                        .put("seed", 40L)
                )
                .put(
                    JSONObject()
                        .put("index", 1)
                        .put("path", second.canonicalPath)
                        .put("mimeType", "image/png")
                        .put("seed", 41L)
                )
        )

    private fun assertInvalidBatchOutput(
        expectedCount: Int = 2,
        mutate: (JSONObject, File) -> Unit
    ) {
        val root = Files.createTempDirectory("sdcpp-invalid-output").toFile()
        try {
            val first = File(root, "image.png").apply { writeBytes(byteArrayOf(1)) }
            val second = File(root, "image-002.png").apply { writeBytes(byteArrayOf(2)) }
            val result = stableDiffusionBatchResult(first, second)
            mutate(result, second)

            assertInvalid {
                consumeStableDiffusionOutputs(
                    result = result,
                    expectedCount = expectedCount,
                    expectedSeed = 40L,
                    legacyOutputFile = first
                )
            }
            assertFalse(first.exists())
            assertFalse(second.exists())
        } finally {
            root.deleteRecursively()
        }
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
