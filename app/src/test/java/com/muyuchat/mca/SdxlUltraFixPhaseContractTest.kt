package com.muyuchat.mca

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdxlUltraFixPhaseContractTest {
    @Test
    fun `split ultrafix contract binds target tile and denoising tail`() {
        val request = LocalImageUltraFixOptions(
            targetWidth = 1536,
            targetHeight = 1024,
            strength = 0.5,
            inversionSteps = localImageDenoisingTailStepCount(20, 0.5),
            refinementSteps = 20,
            tileSize = 1024,
            overlap = 0.25
        )
        val params = contractParams(request)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())

        assertEquals(request, contract.ultraFixRequestOrNull())
        assertEquals(request.inversionSteps, contract.expectedTimetableCount)
        assertEquals(request.inversionSteps * 2, contract.expectedUnetExecutionCount)
        assertEquals(listOf(1, 4, 128, 192), contract.expectedLatentShape())
        assertEquals(2, expectedSdxlUltraFixTileCount(request))
        assertEquals(
            9,
            expectedSdxlUltraFixTileCount(
                request.copy(targetWidth = 2048, targetHeight = 2048, overlap = 0.5)
            )
        )

        assertTrue(runCatching {
            SdxlImageExecutionContract.fromParams(
                JSONObject(params.toString()).apply {
                    getJSONObject("ultraFix").put("tileSize", 512)
                    getJSONObject("vaeTiling").put("tileSize", 512)
                }.toString()
            )
        }.isFailure)
        assertTrue(runCatching {
            SdxlImageExecutionContract.fromParams(
                JSONObject(params.toString()).put(
                    "preview",
                    JSONObject().put("mode", "projection").put("interval", 4)
                ).toString()
            )
        }.isFailure)
        assertTrue(runCatching {
            SdxlImageExecutionContract.fromParams(
                JSONObject(params.toString())
                    .put("taskMode", LocalImageTaskMode.TEXT_TO_IMAGE.wireName)
                    .toString()
            )
        }.isFailure)
    }

    @Test
    fun `isolated native ultrafix keeps all refinement steps in one unet worker`() {
        val native = repositoryFile("core/native/src/main/cpp/qnn_sdxl_isolated_phases.hpp")
            .readText()
        val coordinator = repositoryFile(
            "app/src/main/java/com/muyuchat/mca/SdxlTwoPhaseCoordinator.kt"
        ).readText()
        val protocol = repositoryFile(
            "app/src/main/java/com/muyuchat/mca/SdxlImagePhaseProtocol.kt"
        ).readText()
        val provider = repositoryFile(
            "app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt"
        ).readText()

        listOf(
            "parse_qnn_ultrafix_request",
            "blend_ultrafix_encoder_tiles",
            "qnn_run_sdxl_ultrafix_tiled_unet_branch",
            "scale_ultrafix_inversion_model_input",
            "ultrafix_epsilon_inversion_step",
            "resolve_ultrafix_execution_counts",
            "qnn_decode_ultrafix_vae_latents",
            "resolve_ultrafix_quality_schedule",
            "ultrafix_equivalent_noise",
            "ultrafix_inject_spherical_noise",
            "ultrafix_apply_structure_guidance",
            "ultra_fix_quality_step_evaluation_count",
            "cancelled before split-SDXL UltraFix tile blending",
            "ultra_fix.target_width < 1024",
            "ultra_fix.target_height % 64 != 0",
            "ultraFixTilePlanSha256",
            "ultraFixPhysicalUnetGraphExecutionCount",
            "ultraFixQualityStepEvaluationCount",
            "ultraFixNoiseInjectionStepCount",
            "ultraFixNoiseInjectionSeedFingerprint",
            "ultraFixNoiseInjectionChecksum",
            "ultraFixStructureGuidanceStepCount",
            "ultraFixStructureGuidanceChecksum",
            "ultraFixTrajectoryNoiseChecksum",
            "ultraFixOutputAtomicCommit"
        ).forEach { needle ->
            assertTrue("Missing split UltraFix native contract: $needle", native.contains(needle))
        }
        assertTrue(native.indexOf("write_vae_tensor_png") < native.indexOf("::rename(",
            startIndex = native.indexOf("std::string qnn_sdxl_vae_phase_json")))
        assertTrue(native.indexOf("::rename(",
            startIndex = native.indexOf("std::string qnn_sdxl_vae_phase_json")) <
            native.indexOf("qnn_file_sha256(",
                startIndex = native.indexOf("std::string qnn_sdxl_vae_phase_json")))

        listOf(
            "ultraFixTilePlanSha256",
            "ultraFixEncoderGraphExecutionCount",
            "ultraFixInversionGraphExecutionCount",
            "ultraFixDecoderGraphExecutionCount",
            "qualityStepEvaluationCount",
            "noiseInjectionStepCount",
            "noiseInjectionSeedFingerprint",
            "noiseInjectionChecksum",
            "structureGuidanceStepCount",
            "structureGuidanceChecksum",
            "trajectoryNoiseChecksum",
            "sdxl_three_phase_ultrafix_passed",
            "outputSizeBytes",
            "outputAtomicCommit"
        ).forEach { needle ->
            assertTrue("Missing split UltraFix coordinator closure: $needle", coordinator.contains(needle))
        }
        assertTrue(protocol.contains("SDXL_ISOLATED_ULTRAFIX_MODE"))
        assertTrue(protocol.contains("Split-SDXL UltraFix inversion/refinement evidence is incomplete."))
        assertTrue(
            provider.contains("usesSplitQnnWorkers && effectiveOptions.ultraFix == null")
        )
        assertTrue(provider.contains("QnnInputImageArtifact.prepare("))

        val forbiddenAdmission = listOf(
            "supportedDeviceIds",
            "allowedChipsets",
            "validationDevice",
            "ultraFixModelIds",
            "recommendedModelIds"
        )
        forbiddenAdmission.forEach { needle ->
            assertFalse("Split UltraFix introduced forbidden admission: $needle", native.contains(needle))
            assertFalse("Split UltraFix introduced forbidden admission: $needle", coordinator.contains(needle))
        }
    }

    private fun contractParams(request: LocalImageUltraFixOptions): JSONObject {
        val useCfg = true
        val timetableCount = request.inversionSteps
        return JSONObject()
            .put("profileId", "generic.sdxl.ultrafix")
            .put("profileRevision", 1)
            .put("modelFingerprint", "a".repeat(64))
            .put("runtime", LocalImageRuntime.QNN_HTP.name)
            .put("scheduler", ImageSchedulerAlgorithm.DDIM.name)
            .put("predictionType", ImagePredictionType.EPSILON.name)
            .put("steps", request.refinementSteps)
            .put("timetableCount", timetableCount)
            .put("unetExecutionCount", timetableCount * 2)
            .put("expectedTimetableCount", timetableCount)
            .put("expectedUnetExecutionCount", timetableCount * 2)
            .put("cfgScale", 7.0)
            .put("useCfg", useCfg)
            .put("unconditionalBranch", useCfg)
            .put("tokenizerBackend", ImageTokenizerBackend.TOKENIZERS_CPP.name)
            .put("tokenCount", 154)
            .put("conditioningOrder", "negative_then_positive")
            .put("conditioningEncoderExecutionCount", 4)
            .put("promptWeightingSupported", true)
            .put("embeddingDiskDataType", ImageEmbeddingDiskDataType.FP16.name)
            .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            .put("vaeScalingFactor", SDXL_QNN_VAE_SCALING_FACTOR)
            .put("pixelRange", ImagePixelRange.NEGATIVE_ONE_TO_ONE.name)
            .put("conditioningArtifactSha256", "b".repeat(64))
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
            .put("strength", request.strength)
            .put("width", request.targetWidth)
            .put("height", request.targetHeight)
            .put("seed", 42L)
            .put("graphName", "model")
            .put("fallback", false)
            .put("ultraFix", request.toJson())
            .put(
                "vaeTiling",
                JSONObject().put("tileSize", request.tileSize).put("overlap", request.overlap)
            )
    }

    private fun repositoryFile(path: String): File = sequenceOf(
        File(path),
        File("..", path),
        File("../..", path)
    ).first { it.isFile }
}
