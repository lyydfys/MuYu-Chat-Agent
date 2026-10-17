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
        val encoder = bodyOf(
            "bool qnn_run_shared_img2img_encoder(",
            "std::string qnn_semantic_generate_json("
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
            "write_qnn_image_png_atomic(",
        )
        assertTrue(source("core/native/src/main/cpp/qnn_inpaint_contract.hpp").contains(
            "laplacian_pyramid_blend_nchw("
        ))
    }

    @Test
    fun `split sdxl preserves source before preview and pixel blends before png`() {
        val split = source("core/native/src/main/cpp/qnn_sdxl_isolated_phases.hpp")
        // T2B18/R7-C7: the UNet phase body moved out of the header into three mca_qnn_runtime
        // translation units.  The isolated-phases contract block stays first (it carries the
        // scheduler/preserve ordering contract in its original spelling) and the real code
        // follows so every needle below keeps observing the implementation.
        val unet = split +
            runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_phase.cpp") }.getOrDefault("") +
            runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_tiling.cpp") }.getOrDefault("") +
            runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_execution.cpp") }.getOrDefault("") +
            runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_phase.hpp") }.getOrElse {
            split.substring(
                split.indexOf("std::string qnn_sdxl_unet_phase_json("),
                split.indexOf("std::string qnn_sdxl_vae_phase_json("),
            )
        }
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

        val vae = runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_sdxl_decoder_phase.hpp") }.getOrElse {
            split.substring(split.indexOf("std::string qnn_sdxl_vae_phase_json("))
        }
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

    // The QNN bridge was slimmed from ~11.5k lines to ~1k by extracting the graph execution,
    // conditioning, semantic and tensor-quantizer logic into mca/qnn modules (and leaving only the
    // no-SDK fallbacks inline).  Tests in this file assert on facts that now live in those modules,
    // so reading the bridge alone would make them assert against the fallback stubs.  Nothing about
    // what is asserted changes below -- only the set of files a "bridge" read is composed from.
    private val qnnExtractedModules = listOf(
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_pipeline.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_unet.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_ultrafix.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_sdxl.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_vae_decode.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_model_execution_img2img_encoder.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_pipeline.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_request.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_conditioning.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_sdxl.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_stage_runner.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_output.cpp",
        "core/native/src/main/cpp/mca/qnn/qnn_semantic_evidence.cpp",
    )

    // T2B22-T4: the inpaint verifier bodies moved to their own files.  Reading the contract
    // file unions those bodies back in, so every needle below keeps observing the same text;
    // nothing about what is asserted changes.
    private val qnnInpaintContractModules = listOf(
        "app/src/main/java/com/muyuchat/mca/QnnInpaintSharedValidation.kt",
        "app/src/main/java/com/muyuchat/mca/QnnInpaintSplitValidation.kt",
    )

    private fun source(relativePath: String): String {
        val primary = readSource(relativePath)
        if (relativePath.endsWith("QnnInpaintExecutionContract.kt")) {
            return primary + qnnInpaintContractModules.joinToString("") { module ->
                runCatching { readSource(module) }.getOrDefault("")
            }
        }
        if (!relativePath.endsWith("qnn_native_bridge.cpp")) return primary
        return primary + qnnExtractedModules.joinToString("") { module ->
            runCatching { readSource(module) }.getOrDefault("")
        }
    }

    private fun readSource(path: String): String {
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

    /**
     * Every QNN source that this contract now spans.
     *
     * The bridge used to hold all of this inline.  It was slimmed from ~11.5k lines to ~1k by
     * extracting graph execution, conditioning, semantic and quantizer logic into mca/qnn modules,
     * so a fact that used to be one `bridge.indexOf(...)` away may now live in a different file.
     * Body extraction therefore searches **within a single source**, which is where the code
     * actually is -- concatenating first would produce index ranges that span files and mean
     * nothing.
     */
    private val qnnSources: List<String> by lazy {
        buildList {
            add(source("core/native/src/main/cpp/qnn_native_bridge.cpp"))
            add(runCatching { source("core/native/src/main/cpp/mca/qnn/qnn_bridge_payloads.cpp") }.getOrDefault(""))
            listOf(
                "core/native/src/main/cpp/mca/qnn/qnn_system_symbol_loader.hpp",
                "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.hpp",
            ).forEach { add(runCatching { source(it) }.getOrDefault("")) }
            listOf(
                "core/native/src/main/cpp/mca/qnn/qnn_executable_graph.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_unet.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_ultrafix.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_sdxl.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_vae_decode.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_model_execution_img2img_encoder.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_token_payload.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_json_fields.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_asset_evidence.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_asset_capture.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_conditioning_handoff_binding.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_pipeline.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_request.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_conditioning.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_sdxl.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_stage_runner.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_output.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_semantic_evidence.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_graph_executor_helper.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_tensor_quantizer.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_tensor_inspect.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_system_symbol_loader.cpp",
                "core/native/src/main/cpp/mca/qnn/qnn_image_writer.cpp",
            ).forEach { add(runCatching { source(it) }.getOrDefault("")) }
        }
    }

    /** The single source that contains [anchor], or null. */
    private fun sourceContaining(anchor: String): String? =
        qnnSources.firstOrNull { it.contains(anchor) }

    /** True when no QNN source contains [fragment] -- the strong form of the old assertFalse. */
    private fun absentEverywhere(fragment: String): Boolean =
        qnnSources.none { it.contains(fragment) }

    /** The text between two anchors located **inside the same source file**. */
    private fun bodyOf(startAnchor: String, endAnchor: String): String {
        for (src in qnnSources) {
            val start = src.indexOf(startAnchor)
            if (start < 0) continue
            val end = src.indexOf(endAnchor, start)
            if (end > start) return src.substring(start, end)
        }
        error("Unable to locate a single source containing '$startAnchor' .. '$endAnchor'")
    }

}
