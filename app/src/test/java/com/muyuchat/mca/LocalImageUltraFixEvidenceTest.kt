package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageUltraFixEvidenceTest {
    @Test
    fun `complete native evidence accepts independent physical tile plans and binds bytes`() {
        val fixture = fixture()

        verifyStableDiffusionUltraFixEvidence(
            result = fixture.result,
            request = fixture.request,
            inputImage = fixture.input,
            requestedUseCfg = true,
            outputs = listOf(fixture.output)
        )
    }

    @Test
    fun `legacy equal physical tile plans remain compatible`() {
        val fixture = fixture(
            vaeEncodeTiles = 4,
            latentTilesPerStep = 4,
            vaeDecodeTiles = 4
        )

        verifyStableDiffusionUltraFixEvidence(
            result = fixture.result,
            request = fixture.request,
            inputImage = fixture.input,
            requestedUseCfg = true,
            outputs = listOf(fixture.output)
        )
    }

    @Test
    fun `ultrafix VAE stage counts must bind to detailed native plans`() {
        val fixture = fixture()
        listOf(
            fixture.result.getJSONObject("ultraFix"),
            fixture.result.getJSONObject("nativeEffective").getJSONObject("ultraFix")
        ).forEach { evidence ->
            evidence.getJSONObject("vaeEncode")
                .put("tileInvocationCount", 5)
                .put("tileSuccessCount", 5)
        }

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `DDIM and refinement cannot claim different latent plans`() {
        val fixture = fixture()
        val forgedRefinementTiles = 32
        val inversionTiles = fixture.result.getJSONObject("ultraFix")
            .getJSONObject("ddimInversion")
            .getInt("tileInvocationCount")
        val forgedPositive = inversionTiles + forgedRefinementTiles
        val forgedNegative = forgedRefinementTiles
        val forgedPhysical = forgedPositive + forgedNegative
        listOf(
            fixture.result.getJSONObject("ultraFix"),
            fixture.result.getJSONObject("nativeEffective").getJSONObject("ultraFix")
        ).forEach { evidence ->
            evidence.getJSONObject("tiledUnetRefinement")
                .put("tileInvocationCount", forgedRefinementTiles)
                .put("tileSuccessCount", forgedRefinementTiles)
            evidence.put("physicalDiffusionModelComputeCount", forgedPhysical)
        }
        fixture.result
            .put("actualDiffusionModelComputeCount", forgedPhysical)
            .put("actualPositiveDiffusionModelComputeCount", forgedPositive)
            .put("actualNegativeDiffusionModelComputeCount", forgedNegative)
            .put("totalUnetExecutionCount", forgedPhysical)
            .getJSONObject("nativeEffective")
            .put("positiveDiffusionModelComputeCount", forgedPositive)
            .put("negativeDiffusionModelComputeCount", forgedNegative)
            .put("totalUnetExecutionCount", forgedPhysical)

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `outer and native effective evidence cannot disagree`() {
        val fixture = fixture()
        fixture.result.getJSONObject("ultraFix")
            .getJSONObject("ddimInversion")
            .put("stepCount", 3)

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `center cover geometry must match the immutable source and target`() {
        val fixture = fixture()
        fixture.result.getJSONObject("ultraFix").put("sourceCropLeft", 171)
        fixture.result.getJSONObject("nativeEffective")
            .getJSONObject("ultraFix")
            .put("sourceCropLeft", 171)

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `physical diffusion counts cannot be replaced by semantic step counts`() {
        val fixture = fixture()
        fixture.result.put("actualDiffusionModelComputeCount", fixture.request.inversionSteps)

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `commit digest must match returned output bytes`() {
        val fixture = fixture()
        val forged = "f".repeat(64)
        fixture.result.put("outputSha256", forged)
        fixture.result.getJSONObject("nativeEffective").put("outputSha256", forged)

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `multi-step quality evidence accepts threshold-skipped noise with a zero-bound checksum`() {
        val fixture = fixture()
        listOf(
            fixture.result.getJSONObject("ultraFix"),
            fixture.result.getJSONObject("nativeEffective").getJSONObject("ultraFix")
        ).forEach { evidence ->
            evidence.put("noiseInjectionStepCount", 0)
            evidence.put("noiseInjectionChecksum", "0000000000000000")
        }

        verifyStableDiffusionUltraFixEvidence(
            fixture.result,
            fixture.request,
            fixture.input,
            requestedUseCfg = true,
            outputs = listOf(fixture.output)
        )
    }

    @Test
    fun `nonzero multi-step noise count cannot claim a zero checksum`() {
        val fixture = fixture()
        val zero = "0000000000000000"
        fixture.result.getJSONObject("ultraFix").put("noiseInjectionChecksum", zero)
        fixture.result.getJSONObject("nativeEffective")
            .getJSONObject("ultraFix")
            .put("noiseInjectionChecksum", zero)

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `multi-step quality evidence cannot omit every scheduled action`() {
        val fixture = fixture()
        listOf(
            fixture.result.getJSONObject("ultraFix"),
            fixture.result.getJSONObject("nativeEffective").getJSONObject("ultraFix")
        ).forEach { evidence ->
            evidence.put("noiseInjectionStepCount", 0)
            evidence.put("noiseInjectionChecksum", "0000000000000000")
            evidence.put("structureGuidanceStepCount", 0)
            evidence.put("structureGuidanceChecksum", "0000000000000000")
        }

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                fixture.request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `single-step tail rejects non-zero quality evidence`() {
        val fixture = fixture()
        val request = fixture.request.copy(strength = 0.1, inversionSteps = 1)
        val zero = "0000000000000000"
        val fingerprint = localImageUltraFixNoiseSeedFingerprint(7, 1)
        val expectedPhysical = 27

        fun JSONObject.updateTailOne() {
            put("inversionSteps", 1)
            put("denoiseStepCount", 1)
            put("qualityStepEvaluationCount", 0)
            // This is the tampered field: a one-step tail has no non-final step.
            put("noiseInjectionStepCount", 1)
            put("noiseInjectionSeedFingerprint", fingerprint)
            put("noiseInjectionChecksum", "0123456789abcdef")
            put("structureGuidanceStepCount", 0)
            put("structureGuidanceChecksum", zero)
            put("trajectoryNoiseChecksum", zero)
            getJSONObject("ddimInversion").apply {
                put("invocationCount", 1)
                put("successCount", 1)
                put("tileInvocationCount", 9)
                put("tileSuccessCount", 9)
                put("stepCount", 1)
            }
            getJSONObject("tiledUnetRefinement").apply {
                put("invocationCount", 1)
                put("successCount", 1)
                put("tileInvocationCount", 9)
                put("tileSuccessCount", 9)
                put("stepCount", 1)
            }
            put("physicalDiffusionModelComputeCount", expectedPhysical)
        }

        fixture.result.getJSONObject("ultraFix").updateTailOne()
        fixture.result.getJSONObject("nativeEffective").getJSONObject("ultraFix").updateTailOne()
        fixture.result.put("actualDiffusionModelComputeCount", expectedPhysical)
            .put("actualPositiveDiffusionModelComputeCount", 18)
            .put("actualNegativeDiffusionModelComputeCount", 9)
            .put("actualSamplingStepCount", 1)
            .put("totalUnetExecutionCount", expectedPhysical)
            .getJSONObject("nativeEffective")
            .put("positiveDiffusionModelComputeCount", 18)
            .put("negativeDiffusionModelComputeCount", 9)
            .put("totalUnetExecutionCount", expectedPhysical)
        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                request,
                fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `regular request rejects ultrafix only evidence`() {
        val fixture = fixture()

        assertInvalid {
            verifyStableDiffusionUltraFixEvidence(
                fixture.result,
                request = null,
                inputImage = fixture.input,
                requestedUseCfg = true,
                outputs = listOf(fixture.output)
            )
        }
    }

    @Test
    fun `ultrafix output array requires complete atomic commit evidence`() {
        val root = createTempDirectory("mca-ultrafix-output-").toFile()
        val outputFile = File(root, "output.png")
        try {
            outputFile.writeBytes(ByteArray(64) { it.toByte() })
            val result = JSONObject()
                .put("path", outputFile.canonicalPath)
                .put("mimeType", "image/png")
                .put("outputCount", 1)
                .put("n", 1)
                .put("outputs", JSONArray().put(JSONObject()
                    .put("index", 0)
                    .put("path", outputFile.canonicalPath)
                    .put("mimeType", "image/png")
                    .put("seed", 7)))

            assertInvalid {
                consumeStableDiffusionOutputs(
                    result = result,
                    expectedCount = 1,
                    expectedSeed = 7,
                    legacyOutputFile = outputFile,
                    requireCommittedEvidence = true
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val request: LocalImageUltraFixOptions,
        val input: LocalImagePreparedInput,
        val output: LocalImageOutput,
        val result: JSONObject
    )

    private fun fixture(
        vaeEncodeTiles: Int = 4,
        latentTilesPerStep: Int = 9,
        vaeDecodeTiles: Int = 9
    ): Fixture {
        val bytes = ByteArray(96) { index -> (index * 31).toByte() }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val request = LocalImageUltraFixOptions(
            targetWidth = 1_024,
            targetHeight = 1_024,
            strength = 0.4,
            inversionSteps = 4,
            refinementSteps = 10,
            tileSize = 512,
            overlap = 0.25
        )
        val input = LocalImagePreparedInput(
            path = "/data/user/0/com.muyuchat/files/source.png",
            mimeType = "image/png",
            sha256 = "a".repeat(64),
            sizeBytes = 1_024,
            width = 512,
            height = 384
        )
        val output = LocalImageOutput(
            bytes = bytes,
            mimeType = "image/png",
            seed = 7,
            index = 0
        )
        val inversionTileCount = latentTilesPerStep * request.inversionSteps
        val refinementTileCount = latentTilesPerStep * request.inversionSteps
        val positiveComputeCount = inversionTileCount + refinementTileCount
        val negativeComputeCount = refinementTileCount
        val physicalComputeCount = positiveComputeCount + negativeComputeCount
        val ultraFixEvidence = JSONObject()
            .put("version", 5)
            .put("generationCompleted", true)
            .put("cancelled", false)
            .put("previewPublished", false)
            .put("sourceWidth", input.width)
            .put("sourceHeight", input.height)
            .put("targetWidth", request.targetWidth)
            .put("targetHeight", request.targetHeight)
            .put("sourceFit", "cover_center")
            .put("sourceResizedWidth", 1_365)
            .put("sourceResizedHeight", 1_024)
            .put("sourceCropLeft", 170)
            .put("sourceCropTop", 0)
            .put("tileSize", request.tileSize)
            .put("overlap", request.overlap)
            .put("inversionSteps", request.inversionSteps)
            .put("refinementSteps", request.refinementSteps)
            .put("denoiseStepCount", request.inversionSteps)
            .put("sampleMethod", "euler")
            .put("nativeScheduler", "discrete")
            .put("vaeEncode", stage(invocations = 1, tiles = vaeEncodeTiles, steps = 1))
            .put(
                "ddimInversion",
                stage(
                    invocations = request.inversionSteps,
                    tiles = inversionTileCount,
                    steps = request.inversionSteps
                )
            )
            .put(
                "tiledUnetRefinement",
                stage(
                    invocations = request.inversionSteps,
                    tiles = refinementTileCount,
                    steps = request.inversionSteps
                )
            )
            .put("tiledVaeDecode", stage(invocations = 1, tiles = vaeDecodeTiles, steps = 1))
            .put("physicalDiffusionModelComputeCount", physicalComputeCount)
            .put("qualityStepEvaluationCount", 3)
            .put("noiseInjectionStepCount", 3)
            .put("noiseInjectionSeedFingerprint",
                "ded9d17d42fcea4cfbf805e2d2406db84f0c60bd7d2ff06e75939d2c7826e3d8")
            .put("noiseInjectionChecksum", "0123456789abcdef")
            .put("structureGuidanceStepCount", 3)
            .put("structureGuidanceChecksum", "fedcba9876543210")
            .put("trajectoryNoiseChecksum", "13579bdf2468ace0")
        val vaeTilingEvidence = JSONObject()
            .put("enabled", true)
            .put("requestedTileSize", request.tileSize)
            .put("requestedOverlap", request.overlap)
            .put(
                "encode",
                vaePhase(
                    tiles = vaeEncodeTiles,
                    tileSize = if (vaeEncodeTiles == 4) 83 else 64,
                    overlap = if (vaeEncodeTiles == 4) 0.45783132314682007 else 0.5
                )
            )
            .put(
                "decode",
                vaePhase(
                    tiles = vaeDecodeTiles,
                    tileSize = if (vaeDecodeTiles == 4) 83 else 64,
                    overlap = if (vaeDecodeTiles == 4) 0.45783132314682007 else 0.5
                )
            )
        val nativeEffective = JSONObject()
            .put("ultraFix", JSONObject(ultraFixEvidence.toString()))
            .put("vaeTiling", JSONObject(vaeTilingEvidence.toString()))
            .put("strengthMechanism", "ddim_inversion")
            .put("seed", 7)
            .put("positiveDiffusionModelComputeCount", positiveComputeCount)
            .put("negativeDiffusionModelComputeCount", negativeComputeCount)
            .put("auxiliaryDiffusionModelComputeCount", 0)
            .put("samplingPassCount", 1)
            .put("totalUnetExecutionCount", physicalComputeCount)
            .put("outputSha256", sha256)
            .put("outputSizeBytes", bytes.size)
            .put("outputAtomicCommit", true)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)
            .put("seed", 7)
            .put("sampleMethod", "euler")
            .put("nativeScheduler", "discrete")
            .put("actualDiffusionModelComputeCount", physicalComputeCount)
            .put("actualPositiveDiffusionModelComputeCount", positiveComputeCount)
            .put("actualNegativeDiffusionModelComputeCount", negativeComputeCount)
            .put("actualAuxiliaryDiffusionModelComputeCount", 0)
            .put("actualSamplingStepCount", 4)
            .put("actualSamplingPassCount", 1)
        return Fixture(request, input, output, result)
    }

    private fun stage(invocations: Int, tiles: Int, steps: Int): JSONObject = JSONObject()
        .put("invocationCount", invocations)
        .put("successCount", invocations)
        .put("tileInvocationCount", tiles)
        .put("tileSuccessCount", tiles)
        .put("stepCount", steps)

    private fun vaePhase(tiles: Int, tileSize: Int, overlap: Double): JSONObject = JSONObject()
        .put("invocationCount", 1)
        .put("successCount", 1)
        .put("plannedTileCount", tiles)
        .put("tileComputeAttemptCount", tiles)
        .put("tileComputeSuccessCount", tiles)
        .put("tileSizeX", tileSize)
        .put("tileSizeY", tileSize)
        .put("overlapX", overlap)
        .put("overlapY", overlap)

    private fun assertInvalid(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected strict UltraFix contract rejection.", failure is RuntimeException)
    }
}
