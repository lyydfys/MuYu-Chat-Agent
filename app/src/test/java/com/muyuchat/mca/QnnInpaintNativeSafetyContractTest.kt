package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnInpaintNativeSafetyContractTest {
    @Test
    fun `native keeps four channel diffusion state and assembles nine channels only at bind`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val contract = source("core/native/src/main/cpp/qnn_inpaint_contract.hpp")

        assertTrue(bridge.contains(
            "const uint64_t latent_elements = ultra_fix_request.enabled\n" +
                "        ? ultra_fix_plan.full_latent.element_count()\n" +
                "        : unet_output_shape.element_count();"
        ))
        assertTrue(bridge.contains("build_concatenated_sample("))
        assertFalse(contract.contains("TensorLayout::Nhwc"))
        assertTrue(contract.contains("layout == TensorLayout::Nchw"))
    }

    @Test
    fun `masked rgb encode is reserved for the explicit nine channel topology`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val encoder = bridge.substring(
            bridge.indexOf("bool qnn_run_shared_img2img_encoder("),
            bridge.indexOf("std::string qnn_semantic_generate_json("),
        )

        assertOrdered(
            encoder,
            "if (!encode_once(\n            input_values,",
            "if (request_masked_image_latent)",
            "masked_input_values,",
            "evidence->masked_execution_count = 1U;",
            "if (!encoder.close_checked",
        )
        assertTrue(encoder.contains("INPAINT_MASKED_ENCODER_EXECUTION_FAILED"))
        assertTrue(encoder.contains("context_released_before_shared_session = true"))
    }

    @Test
    fun `source preservation completes before preview publication`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val loop = bridge.substring(
            bridge.indexOf("for (size_t step = img2img_begin_index; step < timesteps.size(); ++step)"),
            bridge.indexOf("if (unet_execution_count !=", bridge.indexOf("for (size_t step = img2img_begin_index")),
        )

        assertOrdered(
            loop,
            "scheduler.step(",
            "preserve_unmasked_latent(",
            "inpaint_preserve_step_count",
            "preview_publisher.publish_if_due(",
        )
        assertTrue(loop.contains("scheduler.add_noise(\n                        img2img_encoder.latents,\n                        diffusion_noise,"))
    }

    @Test
    fun `final vae decode is laplacian blended before png publication`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val finalDecode = bridge.substring(
            bridge.indexOf("std::vector<float> pixels;", bridge.indexOf("for (size_t step = img2img_begin_index")),
            bridge.indexOf("const auto elapsed", bridge.indexOf("std::vector<float> pixels;", bridge.indexOf("for (size_t step = img2img_begin_index"))),
        )

        assertOrdered(
            finalDecode,
            "qnn_read_float_tensor(vae.outputs[0]",
            "qnn_laplacian_blend_inpaint_vae_output(",
            "pixels = std::move(blended_pixels)",
            "write_vae_tensor_png(",
        )
        assertTrue(source("core/native/src/main/cpp/qnn_inpaint_contract.hpp").contains(
            "laplacian_pyramid_blend_nchw("
        ))
    }

    @Test
    fun `split sdxl preserves source before preview and pixel blends before png`() {
        val split = source("core/native/src/main/cpp/qnn_sdxl_isolated_phases.hpp")
        val unet = split.substring(
            split.indexOf("std::string qnn_sdxl_unet_phase_json("),
            split.indexOf("std::string qnn_sdxl_vae_phase_json("),
        )
        assertOrdered(
            unet,
            "scheduler.step(",
            "preserve_unmasked_latent(",
            "inpaint_preserve_step_count",
        )
        assertTrue(unet.contains("validate_sdxl_no_preview_transport(params_json, &error)"))
        assertFalse(unet.contains("projection_preview_publisher"))
        assertTrue(unet.contains("inpaint_contract.topology != mca::qnn::inpaint::MaskTopology::LatentBlend4"))
        assertFalse(unet.contains("ConcatenatedLatent9"))
        assertFalse(unet.contains("SeparateMaskInput"))

        val vae = split.substring(split.indexOf("std::string qnn_sdxl_vae_phase_json("))
        assertOrdered(
            vae,
            "load_sdxl_inpaint_pixel_artifacts(",
            "qnn_decode_vae_latents(",
            "qnn_laplacian_blend_inpaint_vae_output(",
            "write_vae_tensor_png(",
            "fsync_sdxl_artifact(",
            "::rename(",
        )
    }

    @Test
    fun `native result carries independently verifiable mask encode and preserve evidence`() {
        val bridge = source("core/native/src/main/cpp/qnn_native_bridge.cpp")
        listOf(
            "maskedInputImageTensorSha256",
            "maskedInputBufferSha256",
            "maskedInputLatentSha256",
            "maskedInputEncoderExecutionCount",
            "inpaintMaskUnetBindCount",
            "inpaintPreserveStepCount",
            "inpaintLatentBlendCount",
            "inpaintSourceEncoderExecutionCount",
            "inpaintSourceNoiseSha256",
            "inpaintSourceNoiseUseCount",
            "inpaintFinalMode",
            "maskImageFullTensorSha256",
            "inpaintPixelBlendLevels",
            "inpaintPixelBlendChecksum",
            "inpaintPixelBlendApplied",
            "inpaintUnmaskedPreservationApplied",
            "inpaintMaskConsumed",
        ).forEach { field -> assertTrue("Missing native inpaint evidence: $field", bridge.contains(field)) }
    }

    @Test
    fun `product verifier binds latent blend evidence and removes every private artifact path`() {
        val verifier = source("app/src/main/java/com/muyuchat/mca/QnnInpaintExecutionContract.kt")
        listOf(
            "verifyAndSanitizeSharedQnnInpaintProductInput",
            "verifyAndSanitizeSplitQnnInpaintProductInput",
            "inpaintSourceEncoderExecutionCount",
            "inpaintSourceNoiseSha256",
            "inpaintSourceNoiseUseCount",
            "inpaintLatentBlendCount",
            "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend",
            "inpaintPixelBlendLevels",
            "inpaintPixelBlendChecksum",
            "inpaintPixelBlendApplied",
            "result.remove(field)",
            "nativeEffective.remove(field)",
        ).forEach { fragment -> assertTrue("Missing verifier contract: $fragment", verifier.contains(fragment)) }
        listOf(
            "inputImageTensorPath",
            "maskImageTensorPath",
            "maskImageFullTensorPath",
            "maskedInputImageTensorPath",
        ).forEach { field -> assertTrue("Missing private-path cleanup: $field", verifier.contains(field)) }
        assertFalse(verifier.contains("Build.SOC_MODEL"))
        assertFalse(verifier.contains("recommendedModelId"))
    }

    private fun source(path: String): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            File(current, path).takeIf(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $path")
    }

    private fun assertOrdered(source: String, vararg fragments: String) {
        var cursor = 0
        fragments.forEach { fragment ->
            val index = source.indexOf(fragment, cursor)
            assertTrue("Missing or out-of-order fragment: $fragment", index >= cursor)
            cursor = index + fragment.length
        }
    }
}
