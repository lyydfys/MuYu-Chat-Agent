#include "mca/qnn/qnn_semantic_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

std::string QnnSemanticRun::output_phase(mca::diffusion::DiffusionScheduler& scheduler, QnnImageGenerationScope& generation) {
    if (ultra_fix_request.enabled) {
        mca::image::UltraFixExecutionCounts expected_counts;
        const size_t quality_step_count = ultra_fix_quality_step_evaluation_count;
        const bool quality_noise_evidence_consistent =
            ultra_fix_noise_injection_step_count <= quality_step_count &&
            ((ultra_fix_noise_injection_step_count == 0U) ==
             (ultra_fix_noise_injection_checksum == 0U));
        const bool quality_structure_evidence_consistent =
            ultra_fix_structure_guidance_step_count <= quality_step_count &&
            ((ultra_fix_structure_guidance_step_count == 0U) ==
             (ultra_fix_structure_guidance_checksum == 0U));
        const bool quality_coverage_complete = quality_step_count == 0U
            ? ultra_fix_trajectory_noise_checksum == 0U
            : ultra_fix_trajectory_noise_checksum != 0U &&
                  ultra_fix_noise_injection_step_count +
                          ultra_fix_structure_guidance_step_count >=
                      quality_step_count;
        if (!mca::image::resolve_ultrafix_execution_counts(
                ultra_fix_plan.tiles.size(),
                effective_timetable_count,
                effective_timetable_count,
                execution_contract.use_cfg,
                &expected_counts,
                &error) ||
            ultra_fix_inversion_step_count != effective_timetable_count ||
            ultra_fix_refinement_step_count != effective_timetable_count ||
            ultra_fix_inversion_graph_execution_count !=
                expected_counts.inversion_positive_unet_graph_executions ||
            ultra_fix_refinement_positive_execution_count !=
                expected_counts.refinement_positive_unet_graph_executions ||
            ultra_fix_refinement_negative_execution_count !=
                expected_counts.refinement_negative_unet_graph_executions ||
            img2img_encoder.execution_count !=
                expected_counts.vae_encoder_graph_executions ||
            execution_contract.scheduler.expected_unet_execution_count !=
                effective_timetable_count * (execution_contract.use_cfg ? 2U : 1U) ||
            ultra_fix_quality_step_evaluation_count !=
                (effective_timetable_count > 0U ? effective_timetable_count - 1U : 0U) ||
            !quality_noise_evidence_consistent ||
            !quality_structure_evidence_consistent ||
            !quality_coverage_complete) {
            return qnn_semantic_failure_json(
                "ultrafix_execution_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                error.empty()
                    ? "QNN UltraFix physical graph counts do not match its tile plan and global scheduler trajectory."
                    : error);
        }
        ultra_fix_physical_unet_execution_count =
            expected_counts.total_unet_graph_executions;
        unet_execution_count =
            execution_contract.scheduler.expected_unet_execution_count;
    } else if (unet_execution_count !=
        execution_contract.scheduler.expected_unet_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "unetExecutionCount",
            execution_contract.scheduler.expected_unet_execution_count,
            unet_execution_count);
    }
    const size_t expected_inpaint_mask_bind_count = request_inpaint &&
        inpaint_contract.requires_mask_binding() ? unet_execution_count : 0U;
    const size_t expected_masked_encoder_count = request_inpaint &&
        inpaint_contract.requires_masked_latent() ? 1U : 0U;
    if (request_inpaint &&
        (inpaint_mask_bind_count != expected_inpaint_mask_bind_count ||
         inpaint_preserve_step_count != effective_timetable_count ||
         inpaint_source_noise_use_count != effective_timetable_count ||
         !valid_inpaint_sha256(inpaint_source_noise_sha256) ||
         img2img_encoder.execution_count != 1U + expected_masked_encoder_count ||
         img2img_encoder.masked_execution_count != expected_masked_encoder_count ||
         inpaint_mask.empty() ||
         (inpaint_contract.requires_masked_latent() && inpaint_masked_latent.empty()))) {
        return qnn_semantic_failure_json(
            "inpaint_execution_evidence_invalid",
            "QNN_INPAINT_EXECUTION_EVIDENCE_INVALID",
            "QNN inpaint did not consume its mask, reuse one diffusion-noise tensor, and preserve the unmasked source on every scheduler step.");
    }
    conditioning_artifact_consumed = conditioning_artifact_consumed &&
        unet_execution_count > 0U;
    const size_t expected_text_encoder_execution_count = qnn_token_conditioning
        ? (execution_contract.use_cfg ? 2U : 1U)
        : 0U;
    if (text_encoder_execute_count != expected_text_encoder_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "textEncoderExecutionCount",
            expected_text_encoder_execution_count,
            text_encoder_execute_count);
    }
    if (conditioning_graph_sha256.size() != 64U) {
        return qnn_semantic_failure_json(
            "conditioning_graph_sha256_missing",
            "EXECUTION_EVIDENCE_INVALID",
            "The executed conditioning graph lacks a SHA-256 identity proof.");
    }
    if (!conditioning_artifact_consumed) {
        return qnn_semantic_failure_json(
            "conditioning_artifact_not_consumed",
            "EXECUTION_EVIDENCE_INVALID",
            "The prepared conditioning artifact was not consumed by a successful QNN UNet execution.");
    }
    if (request_controlnet) {
        const size_t expected_controlnet_executions = timesteps.size();
        const size_t expected_residual_writes =
            expected_controlnet_executions * controlnet_layout.residual_bindings.size();
        if (controlnet_execution_count != expected_controlnet_executions) {
            return qnn_execution_contract_mismatch_json(
                "controlNetExecutionCount",
                expected_controlnet_executions,
                controlnet_execution_count);
        }
        if (controlnet_residual_write_count != expected_residual_writes) {
            return qnn_execution_contract_mismatch_json(
                "controlNetResidualWriteCount",
                expected_residual_writes,
                controlnet_residual_write_count);
        }
        if (prepared_control_image.encoded_sha256 !=
                normalized_contract_enum(control_image_sha256) ||
            prepared_control_image.preprocessed_sha256.size() != 64U ||
            controlnet_input_buffer_sha256.size() != 64U ||
            control_image_tensor.empty()) {
            return qnn_semantic_failure_json(
                "control_input_evidence_invalid",
                "CONTROLNET_EXECUTION_EVIDENCE_INVALID",
                "ControlNet did not retain verifiable consumed image evidence.");
        }
    }

    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    generation.set_phase(kQnnImageDecoding);
    vae_execute_ms = 0;
    final_vae_graph_execution_count = 0U;
    // member of QnnSemanticRun: std::vector<float> pixels; (carried by qnn_semantic_pipeline.hpp)
    if (ultra_fix_request.enabled) {
        QnnUltraFixVaeDecodeResult ultra_fix_decode;
        if (!qnn_decode_ultrafix_vae_latents(
                vae,
                ultra_fix_plan,
                latents,
                execution_contract,
                &ultra_fix_decode,
                &error)) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            return qnn_semantic_failure_json(
                "ultrafix_vae_decode_failed",
                "QNN_ULTRAFIX_VAE_DECODE_FAILED",
                error);
        }
        pixels = std::move(ultra_fix_decode.pixels_nchw);
        vae_execute_ms = ultra_fix_decode.execute_ms_total;
        final_vae_graph_execution_count = ultra_fix_decode.execution_count;
    } else {
        std::vector<float> vae_latents = latents;
        if (effective_vae_host_scale != 1.0) {
            for (float& value : vae_latents) {
                value = static_cast<float>(value * effective_vae_host_scale);
            }
        }
        if (!qnn_write_float_tensor(&vae.inputs[0], vae_latents.data(), vae_latents.size(), &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_input_bind_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (!vae.execute(&vae_execute_ms, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_execute_failed\",\"message\":") +
                quote(error) + "}";
        }
        final_vae_graph_execution_count = 1U;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_output_read_failed\",\"message\":") +
                quote(error) + "}";
        }
    }
    inpaint_pixel_blend_levels = 0;
    inpaint_pixel_blend_checksum = 0U;
    inpaint_pixel_blend_applied = false;
    if (request_inpaint) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        std::vector<float> blended_pixels;
        if (!qnn_laplacian_blend_inpaint_vae_output(
                vae.outputs[0],
                pixels,
                execution_contract.pixel_range,
                img2img_encoder.source_pixels_nchw,
                inpaint_full_mask,
                &blended_pixels,
                &inpaint_pixel_blend_levels,
                &inpaint_pixel_blend_checksum,
                &error)) {
            return qnn_semantic_failure_json(
                "inpaint_pixel_blend_failed",
                "QNN_INPAINT_PIXEL_BLEND_FAILED",
                error);
        }
        pixels = std::move(blended_pixels);
        inpaint_pixel_blend_applied = true;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    width = 0;
    height = 0;
    generation.record_stage(
        mca::qnn::ImageStage::PngWrite,
        kQnnImagePngWrite);
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    QnnTensorBinding final_output_binding;
    final_output_binding.dimensions = ultra_fix_request.enabled
        ? std::vector<uint32_t>{
            1U,
            3U,
            static_cast<uint32_t>(ultra_fix_plan.target_pixels.height),
            static_cast<uint32_t>(ultra_fix_plan.target_pixels.width),
        }
        : vae.outputs[0].dimensions;
    const bool png_written = mca::qnn::write_qnn_image_png_atomic(
            final_output_binding,
            pixels,
            output_path,
            execution_contract.pixel_range,
            &pixel_range_evidence,
            &width,
            &height,
            &error);
    if (!png_written) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(output_path.c_str());
        return qnn_image_generation_cancelled_json();
    }

    // const auto elapsed (now a QnnSemanticRun member write below)
    elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    output_bytes = file_size_or_zero(output_path);
    if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"png_sha256_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(output_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    return std::string();
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
