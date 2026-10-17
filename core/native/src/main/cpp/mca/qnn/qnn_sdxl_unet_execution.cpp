// core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_execution.cpp -- diffusion step loop,
// post-loop UltraFix/inpaint evidence gates, canonical latent publication, native evidence
// binding and the final JSON assembly.  (T2B18 / R7-C7 split).  Body lines 658-1288
// relocated from mca/qnn/qnn_sdxl_unet_phase.hpp; ctx rewrites are registered in
// a-SPLIT-PLAN.json.
#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

#include "mca/qnn/qnn_sdxl_unet_phase_internal.hpp"

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

std::string qnn_sdxl_unet_execution_body(SdxlUnetPhaseContext& ctx) {
    auto& scheduler = ctx.scheduler;
    for (size_t step = ctx.begin_index; step < ctx.scheduler.get().timesteps().size(); ++step) {
        if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
        const size_t physical_step = step - ctx.begin_index;
        ctx.generation->set_step(static_cast<int>(
            (ctx.ultra_fix.enabled ? ctx.effective_denoise_steps : 0U) + physical_step));
        std::vector<float> model_input;
        if (!ctx.scheduler.get().scale_model_input(ctx.latents, step, &model_input, &ctx.error)) {
            return qnn_semantic_failure_json(
                "sdxl_scheduler_scale_failed",
                "SCHEDULER_EXECUTION_FAILED",
                ctx.error);
        }
        long long execute_ms = 0;
        if (ctx.execution_contract.use_cfg) {
            const bool unconditioned_ok = ctx.ultra_fix.enabled
                ? run_sdxl_ultrafix_tiled_unet_branch(
                    *ctx.unet,
                    ctx.sample_index,
                    ctx.timestep_index,
                    ctx.hidden_index,
                    ctx.time_ids_index,
                    ctx.pooled_index,
                    ctx.ultra_fix_plan,
                    model_input,
                    ctx.scheduler.get().timesteps()[step],
                    ctx.conditioning.negative_hidden.data(),
                    ctx.hidden_count,
                    ctx.conditioning.time_ids,
                    ctx.time_ids_count,
                    ctx.conditioning.negative_pooled,
                    ctx.pooled_count,
                    &ctx.noise_uncond,
                    &ctx.unet_execute_ms_total,
                    &ctx.ultra_fix_refinement_negative_execution_count,
                    &ctx.error)
                : qnn_run_sdxl_unet_once(
                    *ctx.unet, ctx.sample_index, ctx.timestep_index, ctx.hidden_index, ctx.time_ids_index, ctx.pooled_index,
                    model_input, ctx.scheduler.get().timesteps()[step], ctx.conditioning.negative_hidden.data(), ctx.hidden_count,
                    ctx.conditioning.time_ids, ctx.time_ids_count, ctx.conditioning.negative_pooled, ctx.pooled_count,
                    &ctx.noise_uncond, &execute_ms, &ctx.error);
            if (!unconditioned_ok) {
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
                    quote(ctx.error) + "}";
            }
            if (!ctx.ultra_fix.enabled) {
                ctx.unet_execute_ms_total += execute_ms;
                ++ctx.unet_execution_count;
            }
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
        const bool conditioned_ok = ctx.ultra_fix.enabled
            ? run_sdxl_ultrafix_tiled_unet_branch(
                *ctx.unet,
                ctx.sample_index,
                ctx.timestep_index,
                ctx.hidden_index,
                ctx.time_ids_index,
                ctx.pooled_index,
                ctx.ultra_fix_plan,
                model_input,
                ctx.scheduler.get().timesteps()[step],
                ctx.conditioning.positive_hidden.data(),
                ctx.hidden_count,
                ctx.conditioning.time_ids,
                ctx.time_ids_count,
                ctx.conditioning.positive_pooled,
                ctx.pooled_count,
                &ctx.noise_cond,
                &ctx.unet_execute_ms_total,
                &ctx.ultra_fix_refinement_positive_execution_count,
                &ctx.error)
            : qnn_run_sdxl_unet_once(
                *ctx.unet, ctx.sample_index, ctx.timestep_index, ctx.hidden_index, ctx.time_ids_index, ctx.pooled_index,
                model_input, ctx.scheduler.get().timesteps()[step], ctx.conditioning.positive_hidden.data(), ctx.hidden_count,
                ctx.conditioning.time_ids, ctx.time_ids_count, ctx.conditioning.positive_pooled, ctx.pooled_count,
                &ctx.noise_cond, &execute_ms, &ctx.error);
        if (!conditioned_ok) {
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
                quote(ctx.error) + "}";
        }
        if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
        if (!ctx.ultra_fix.enabled) {
            ctx.unet_execute_ms_total += execute_ms;
            ++ctx.unet_execution_count;
        }
        if ((ctx.execution_contract.use_cfg && ctx.noise_uncond.size() != ctx.latents.size()) ||
            ctx.noise_cond.size() != ctx.latents.size()) {
            return "{\"ok\":false,\"executionStage\":\"sdxl_unet_output_shape_unsupported\",\"message\":\"SDXL UNet output does not exactly match its latent input.\"}";
        }
        std::vector<float> guided;
        if (!mca::image::apply_classifier_free_guidance(
                ctx.noise_cond,
                ctx.noise_uncond,
                ctx.execution_contract.cfg_scale,
                ctx.execution_contract.use_cfg,
                &guided,
                &ctx.error)) {
            return qnn_semantic_failure_json(
                "sdxl_cfg_failed",
                "EXECUTION_CONTRACT_INVALID",
                ctx.error);
        }
        mca::image::UltraFixQualitySchedule ultra_fix_quality_schedule;
        const bool ultra_fix_has_next_step = ctx.ultra_fix.enabled &&
            step + 1U < ctx.scheduler.get().timesteps().size();
        if (ctx.ultra_fix.enabled) {
            if (!mca::image::resolve_ultrafix_quality_schedule(
                    ctx.scheduler.get().timesteps()[step],
                    ultra_fix_has_next_step,
                    &ultra_fix_quality_schedule,
                    &ctx.error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_quality_schedule_invalid",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    ctx.error);
            }
            if (ultra_fix_has_next_step) ++ctx.ultra_fix_quality_step_evaluation_count;
            if (ultra_fix_quality_schedule.evaluate_noise_injection) {
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> mixed_prediction;
                uint64_t gaussian_checksum = 0U;
                uint64_t mixed_checksum = 0U;
                if (!mca::image::ultrafix_inject_spherical_noise(
                        guided,
                        ctx.execution_contract.seed,
                        static_cast<uint32_t>(physical_step),
                        ultra_fix_quality_schedule.noise_injection_fraction,
                        &mixed_prediction,
                        &gaussian_checksum,
                        &mixed_checksum,
                        &ctx.error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_ultrafix_noise_injection_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        ctx.error);
                }
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                ctx.ultra_fix_noise_injection_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ctx.ultra_fix_noise_injection_checksum,
                        static_cast<uint32_t>(physical_step),
                        gaussian_checksum);
                ctx.ultra_fix_noise_injection_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ctx.ultra_fix_noise_injection_checksum,
                        static_cast<uint32_t>(physical_step),
                        mixed_checksum);
                guided = std::move(mixed_prediction);
                ++ctx.ultra_fix_noise_injection_step_count;
            }
        }
        mca::diffusion::SchedulerStepResult step_result;
        mca::diffusion::SchedulerStepOptions step_options;
        step_options.eta = ctx.execution_contract.scheduler.eta;
        if (!ctx.scheduler.get().step(guided, step, ctx.latents, &step_result, &ctx.error, step_options)) {
            return qnn_semantic_failure_json(
                "sdxl_scheduler_step_failed",
                "SCHEDULER_EXECUTION_FAILED",
                ctx.error);
        }
        ctx.latents = std::move(step_result.previous_sample);
        if (ctx.ultra_fix.enabled) {
            ++ctx.ultra_fix_refinement_step_count;
            if (ultra_fix_quality_schedule.evaluate_structure_guidance) {
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> trajectory_reference;
                mca::image::UltraFixNoiseLevel trajectory_level;
                if (ctx.ultra_fix_equivalent_noise.empty() ||
                    !qnn_ultrafix_noise_level(
                        ctx.scheduler,
                        step + 1U,
                        &trajectory_level,
                        &ctx.error) ||
                    !mca::image::ultrafix_add_noise(
                        ctx.ultra_fix_clean_latents,
                        ctx.ultra_fix_equivalent_noise,
                        trajectory_level,
                        &trajectory_reference,
                        &ctx.error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_ultrafix_structure_trajectory_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        ctx.error.empty()
                            ? "Split-SDXL UltraFix structure guidance lacks its inverted trajectory noise."
                            : ctx.error);
                }
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> structured_latents;
                uint64_t structured_checksum = 0U;
                const size_t blur_radius = mca::image::ultrafix_structure_blur_radius(
                    ctx.ultra_fix_plan.unet_input.width);
                if (blur_radius == 0U ||
                    !mca::image::ultrafix_apply_structure_guidance(
                        ctx.latents,
                        trajectory_reference,
                        ctx.ultra_fix_plan.full_latent,
                        blur_radius,
                        ultra_fix_quality_schedule.structure_guidance_weight,
                        &structured_latents,
                        &structured_checksum,
                        &ctx.error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_ultrafix_structure_guidance_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        ctx.error);
                }
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                ctx.ultra_fix_structure_guidance_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ctx.ultra_fix_structure_guidance_checksum,
                        static_cast<uint32_t>(physical_step),
                        structured_checksum);
                ctx.latents = std::move(structured_latents);
                ++ctx.ultra_fix_structure_guidance_step_count;
            }
        }
        if (ctx.inpaint) {
            std::vector<float> source_at_timestep;
            if (step + 1U < ctx.scheduler.get().timesteps().size()) {
                if (!ctx.scheduler.get().add_noise(
                        ctx.source_latents,
                        ctx.diffusion_noise,
                        step + 1U,
                        &source_at_timestep,
                        &ctx.error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_inpaint_source_noise_failed",
                        "QNN_INPAINT_PRESERVATION_FAILED",
                        ctx.error);
                }
                ++ctx.inpaint_source_noise_use_count;
            } else {
                source_at_timestep = ctx.source_latents;
            }
            std::vector<float> preserved_latents;
            if (!mca::qnn::inpaint::preserve_unmasked_latent(
                    ctx.latents,
                    source_at_timestep,
                    ctx.inpaint_mask,
                    ctx.inpaint_contract.width,
                    ctx.inpaint_contract.height,
                    &preserved_latents,
                    &ctx.error)) {
                return qnn_semantic_failure_json(
                    "sdxl_inpaint_preservation_failed",
                    "QNN_INPAINT_PRESERVATION_FAILED",
                    ctx.error);
            }
            ctx.latents = std::move(preserved_latents);
            ++ctx.inpaint_preserve_step_count;
            ctx.inpaint_preserved_latent_checksum = checksum_float_vector(ctx.latents);
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
        }
        const int completed_step = static_cast<int>(
            (ctx.ultra_fix.enabled ? ctx.effective_denoise_steps : 0U) + physical_step + 1U);
        ctx.generation->set_step(completed_step);
        ctx.generation->set_phase(kQnnImageSampling);
    }
    if (ctx.ultra_fix.enabled) {
        mca::image::UltraFixExecutionCounts expected_counts;
        const size_t quality_step_count = ctx.ultra_fix_quality_step_evaluation_count;
        const bool quality_noise_evidence_consistent =
            ctx.ultra_fix_noise_injection_step_count <= quality_step_count &&
            ((ctx.ultra_fix_noise_injection_step_count == 0U) ==
             (ctx.ultra_fix_noise_injection_checksum == 0U));
        const bool quality_structure_evidence_consistent =
            ctx.ultra_fix_structure_guidance_step_count <= quality_step_count &&
            ((ctx.ultra_fix_structure_guidance_step_count == 0U) ==
             (ctx.ultra_fix_structure_guidance_checksum == 0U));
        const bool quality_coverage_complete = quality_step_count == 0U
            ? ctx.ultra_fix_trajectory_noise_checksum == 0U
            : ctx.ultra_fix_trajectory_noise_checksum != 0U &&
                  ctx.ultra_fix_noise_injection_step_count +
                          ctx.ultra_fix_structure_guidance_step_count >=
                      quality_step_count;
        if (!mca::image::resolve_ultrafix_execution_counts(
                ctx.ultra_fix_plan.tiles.size(),
                ctx.effective_denoise_steps,
                ctx.effective_denoise_steps,
                ctx.execution_contract.use_cfg,
                &expected_counts,
                &ctx.error) ||
            ctx.ultra_fix_inversion_step_count != ctx.effective_denoise_steps ||
            ctx.ultra_fix_refinement_step_count != ctx.effective_denoise_steps ||
            ctx.ultra_fix_inversion_graph_execution_count !=
                expected_counts.inversion_positive_unet_graph_executions ||
            ctx.ultra_fix_refinement_positive_execution_count !=
                expected_counts.refinement_positive_unet_graph_executions ||
            ctx.ultra_fix_refinement_negative_execution_count !=
                expected_counts.refinement_negative_unet_graph_executions ||
            ctx.ultra_fix_quality_step_evaluation_count !=
                (ctx.effective_denoise_steps > 0U ? ctx.effective_denoise_steps - 1U : 0U) ||
            !quality_noise_evidence_consistent ||
            !quality_structure_evidence_consistent ||
            !quality_coverage_complete) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_execution_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                ctx.error.empty()
                    ? "Split-SDXL UltraFix physical UNet counts do not match the unified tile plan."
                    : ctx.error);
        }
        ctx.unet_execution_count = expected_counts.total_unet_graph_executions;
    } else if (ctx.unet_execution_count !=
        ctx.execution_contract.scheduler.expected_unet_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "unetExecutionCount",
            ctx.execution_contract.scheduler.expected_unet_execution_count,
            ctx.unet_execution_count);
    }
    if (ctx.inpaint &&
        (ctx.inpaint_preserve_step_count != ctx.effective_denoise_steps ||
         ctx.inpaint_source_noise_use_count != ctx.effective_denoise_steps ||
         ctx.inpaint_preserved_latent_checksum == 0U ||
         !valid_sdxl_lower_sha256(ctx.inpaint_source_noise_sha256))) {
        return qnn_semantic_failure_json(
            "sdxl_inpaint_execution_evidence_invalid",
            "QNN_INPAINT_EXECUTION_EVIDENCE_INVALID",
            "Split-SDXL inpaint did not preserve the source latent with one deterministic noise tensor after every scheduler step.");
    }
    const mca::image::SpatialTensorShape canonical_latent_shape{
        1U,
        4U,
        ctx.ultra_fix.enabled ? ctx.ultra_fix_plan.full_latent.height : ctx.latent_shape.height,
        ctx.ultra_fix.enabled ? ctx.ultra_fix_plan.full_latent.width : ctx.latent_shape.width,
        mca::image::SpatialTensorLayout::Nchw,
    };
    std::vector<float> canonical_latents;
    if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
    if (!mca::image::copy_spatial_tile(
            ctx.latents,
            ctx.ultra_fix.enabled ? ctx.ultra_fix_plan.full_latent : ctx.latent_shape,
            0U,
            0U,
            canonical_latent_shape,
            &canonical_latents,
            &ctx.error) ||
        !write_sdxl_latent_atomic(ctx.latent_path, canonical_latents, &ctx.error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"latent_publish_failed\",\"message\":") +
            quote(ctx.error) + "}";
    }
    if (ctx.generation->cancelled()) {
        ::unlink(ctx.latent_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - ctx.started).count();
    const std::vector<double> executed_timesteps(
        ctx.scheduler.get().timesteps().begin() + static_cast<std::ptrdiff_t>(ctx.begin_index),
        ctx.scheduler.get().timesteps().end());
    const std::vector<double> executed_sigmas = ctx.scheduler.get().sigmas().empty()
        ? std::vector<double>{}
        : std::vector<double>(
            ctx.scheduler.get().sigmas().begin() + static_cast<std::ptrdiff_t>(ctx.begin_index),
            ctx.scheduler.get().sigmas().end());
    const size_t logical_unet_execution_count =
        ctx.execution_contract.scheduler.expected_unet_execution_count;
    QnnNativeEffectiveEvidence native_evidence{
        ctx.effective_denoise_steps,
        logical_unet_execution_count,
        ctx.conditioning_evidence.tokenizer_backend,
        ctx.conditioning_evidence.token_count,
        ctx.conditioning_evidence.embedding_disk_data_type,
        ctx.execution_contract.width,
        ctx.execution_contract.height,
        ctx.unet->graph_name,
    };
    native_evidence.prompt_weighting_applied =
        ctx.conditioning_evidence.prompt_weighting_applied;
    native_evidence.positive_weighted_token_count =
        ctx.conditioning_evidence.positive_weighted_token_count;
    native_evidence.negative_weighted_token_count =
        ctx.conditioning_evidence.negative_weighted_token_count;
    native_evidence.prompt_weight_fingerprint =
        ctx.conditioning_evidence.prompt_weight_fingerprint;
    native_evidence.conditioning_artifact_sha256 =
        ctx.conditioning_evidence.conditioning_artifact_sha256;
    native_evidence.conditioning_execution_mode =
        ctx.conditioning_evidence.conditioning_execution_mode;
    native_evidence.conditioning_backend = ctx.conditioning_evidence.conditioning_backend;
    native_evidence.conditioning_graph = ctx.conditioning_evidence.conditioning_graph;
    native_evidence.conditioning_graph_sha256 =
        ctx.conditioning_evidence.conditioning_graph_sha256;
    native_evidence.conditioning_order = ctx.conditioning_evidence.conditioning_order;
    native_evidence.conditioning_encoder_execution_count =
        ctx.conditioning_evidence.conditioning_encoder_execution_count;
    native_evidence.text_encoder_execution_count = 0U;
    native_evidence.conditioning_artifact_consumed = ctx.unet_execution_count > 0U;
    if (!bind_qnn_consumed_prompt_evidence(
            ctx.conditioning_evidence,
            native_evidence.conditioning_artifact_consumed,
            ctx.unet_execution_count,
            &native_evidence,
            &ctx.error)) {
        return qnn_semantic_failure_json(
            "native_prompt_evidence_invalid",
            "EXECUTION_EVIDENCE_INVALID",
            ctx.error);
    }
    native_evidence.runtime_session_mode = "isolated_unet_phase";
    native_evidence.task_mode = ctx.task_mode;
    native_evidence.strength = ctx.strength;
    native_evidence.full_timetable_count = ctx.full_timetable_count;
    native_evidence.img2img_begin_index = ctx.begin_index;
    native_evidence.effective_denoise_steps = ctx.effective_denoise_steps;
    if (ctx.encoder_conditioned) {
        native_evidence.input_image_path = string_field(ctx.params_json, "inputImagePath");
        native_evidence.input_image_sha256 = normalized_contract_enum(
            string_field(ctx.params_json, "inputImageSha256"));
        native_evidence.input_image_tensor_path = string_field(
            ctx.params_json, "inputImageTensorPath");
        native_evidence.input_image_tensor_sha256 = normalized_contract_enum(
            string_field(ctx.params_json, "inputImageTensorSha256"));
        native_evidence.input_image_tensor_bytes = long_field(
            ctx.params_json, "inputImageTensorBytes");
        native_evidence.input_image_tensor_dtype = string_field(
            ctx.params_json, "inputImageTensorDtype");
        native_evidence.input_image_tensor_layout = string_field(
            ctx.params_json, "inputImageTensorLayout");
        native_evidence.input_image_tensor_range = string_field(
            ctx.params_json, "inputImageTensorRange");
        native_evidence.encoder_latent_sha256 = ctx.encoder_latent_sha256;
        native_evidence.input_image_preprocess = string_field(
            ctx.params_json, "inputImagePreprocess");
        native_evidence.input_image_execution_count = 1U;
        native_evidence.input_image_source_width = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "inputImageSourceWidth")));
        native_evidence.input_image_source_height = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "inputImageSourceHeight")));
        native_evidence.input_image_oriented_width = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "inputImageOrientedWidth")));
        native_evidence.input_image_oriented_height = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "inputImageOrientedHeight")));
        native_evidence.input_image_exif_orientation = static_cast<int>(
            long_field(ctx.params_json, "inputImageExifOrientation"));
        native_evidence.input_image_tensor_width =
            static_cast<size_t>(ctx.execution_contract.width);
        native_evidence.input_image_tensor_height =
            static_cast<size_t>(ctx.execution_contract.height);
        native_evidence.input_image_tensor_channels = 3U;
        native_evidence.img2img_add_noise_applied = !ctx.ultra_fix.enabled;
        native_evidence.img2img_add_noise_begin_index = ctx.begin_index;
        native_evidence.img2img_add_noise_timestep = ctx.ultra_fix.enabled
            ? 0.0
            : ctx.scheduler.get().timesteps()[ctx.begin_index];
        native_evidence.img2img_noise_checksum = fixed_width_lower_hex_u64(
            ctx.img2img_noise_checksum);
    }
    if (ctx.inpaint) {
        native_evidence.mask_image_path = string_field(ctx.params_json, "maskImagePath");
        native_evidence.mask_image_sha256 = normalized_contract_enum(
            string_field(ctx.params_json, "maskImageSha256"));
        native_evidence.mask_image_source_bytes = long_field(
            ctx.params_json, "maskImageSizeBytes");
        native_evidence.mask_image_tensor_path = string_field(
            ctx.params_json, "maskImageTensorPath");
        native_evidence.mask_image_tensor_sha256 = normalized_contract_enum(
            ctx.actual_mask_tensor_sha256);
        native_evidence.mask_image_tensor_bytes = long_field(
            ctx.params_json, "maskImageTensorBytes");
        native_evidence.mask_image_tensor_dtype = string_field(
            ctx.params_json, "maskImageTensorDtype");
        native_evidence.mask_image_tensor_layout = string_field(
            ctx.params_json, "maskImageTensorLayout");
        native_evidence.mask_image_tensor_range = string_field(
            ctx.params_json, "maskImageTensorRange");
        native_evidence.mask_image_tensor_preprocess = string_field(
            ctx.params_json, "maskImageTensorPreprocess");
        native_evidence.mask_image_source_width = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "maskImageSourceWidth")));
        native_evidence.mask_image_source_height = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "maskImageSourceHeight")));
        native_evidence.mask_image_oriented_width = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "maskImageOrientedWidth")));
        native_evidence.mask_image_oriented_height = static_cast<size_t>(std::max<long long>(
            0, long_field(ctx.params_json, "maskImageOrientedHeight")));
        native_evidence.mask_image_exif_orientation = static_cast<int>(
            long_field(ctx.params_json, "maskImageExifOrientation"));
        native_evidence.mask_image_repaint_pixel_count = static_cast<size_t>(
            std::max<long long>(0, long_field(ctx.params_json, "maskImageRepaintPixelCount")));
        native_evidence.mask_image_latent_repaint_pixel_count = static_cast<size_t>(
            std::max<long long>(0, long_field(ctx.params_json, "maskImageLatentRepaintPixelCount")));
        native_evidence.mask_image_execution_count = 1U;
        native_evidence.inpaint_topology = ctx.inpaint_contract.topology_name();
        native_evidence.inpaint_mask_unet_bind_count = 0U;
        native_evidence.inpaint_preserve_step_count = ctx.inpaint_preserve_step_count;
        native_evidence.inpaint_latent_blend_count = ctx.inpaint_preserve_step_count;
        native_evidence.inpaint_source_encoder_execution_count = 1U;
        native_evidence.inpaint_preserved_latent_checksum = fixed_width_lower_hex_u64(
            ctx.inpaint_preserved_latent_checksum);
        native_evidence.inpaint_source_noise_sha256 = ctx.inpaint_source_noise_sha256;
        native_evidence.inpaint_source_noise_use_count = ctx.inpaint_source_noise_use_count;
        native_evidence.inpaint_final_mode =
            "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend";
        native_evidence.mask_image_consumed = true;
        native_evidence.inpaint_unmasked_preservation_applied = true;
    }
    const std::string native_effective = qnn_native_effective_json(
        ctx.execution_contract,
        native_evidence);
    const long long native_sequence = g_qnn_image_generation_sequence.load();
    const uint32_t native_stage_mask = g_qnn_image_generation_stage_mask.load();
    const uint64_t native_detail_stage_mask =
        g_qnn_image_generation_detail_stage_mask.load();
    std::ostringstream out;
    out << std::setprecision(17)
        << "{\"ok\":true,\"phase\":\"unet\",\"executionStage\":\"sdxl_unet_phase_passed\","
        << "\"profileId\":" << quote(ctx.execution_contract.profile_id) << ","
        << "\"profileRevision\":" << ctx.execution_contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(ctx.execution_contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\","
        << "\"scheduler\":" << quote(qnn_scheduler_wire_name(ctx.execution_contract.scheduler.config.algorithm)) << ","
        << "\"predictionType\":" << quote(qnn_prediction_wire_name(ctx.execution_contract.scheduler.config.prediction_type)) << ","
        << "\"steps\":" << ctx.execution_contract.scheduler.steps << ","
        << "\"timetableCount\":" << ctx.effective_denoise_steps << ","
        << "\"unetExecutionCount\":" << logical_unet_execution_count << ","
        << "\"cfgScale\":" << ctx.execution_contract.cfg_scale << ","
        << "\"useCfg\":" << (ctx.execution_contract.use_cfg ? "true" : "false") << ","
        << "\"unconditionalBranch\":" << (ctx.execution_contract.use_cfg ? "true" : "false") << ","
        << "\"tokenizerBackend\":" << quote(ctx.conditioning_evidence.tokenizer_backend) << ","
        << "\"tokenCount\":" << ctx.conditioning_evidence.token_count << ","
        << "\"promptWeightingSupported\":" << (ctx.execution_contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingApplied\":" << (ctx.conditioning_evidence.prompt_weighting_applied ? "true" : "false") << ","
        << "\"positiveWeightedTokenCount\":" << ctx.conditioning_evidence.positive_weighted_token_count << ","
        << "\"negativeWeightedTokenCount\":" << ctx.conditioning_evidence.negative_weighted_token_count << ","
        << "\"promptWeightFingerprint\":" << quote(ctx.conditioning_evidence.prompt_weight_fingerprint) << ","
        << "\"nativePromptExecutionSha256\":"
        << quote(native_evidence.native_prompt_execution_sha256) << ","
        << "\"nativePromptBindingStage\":"
        << quote(native_evidence.native_prompt_binding_stage) << ","
        << "\"conditioningArtifactSha256\":"
        << quote(ctx.conditioning_evidence.conditioning_artifact_sha256) << ","
        << "\"conditioningExecutionMode\":"
        << quote(native_evidence.conditioning_execution_mode) << ","
        << "\"conditioningBackend\":" << quote(native_evidence.conditioning_backend) << ","
        << "\"conditioningGraph\":" << quote(native_evidence.conditioning_graph) << ","
        << "\"conditioningGraphSha256\":"
        << quote(native_evidence.conditioning_graph_sha256) << ","
        << "\"conditioningOrder\":" << quote(native_evidence.conditioning_order) << ","
        << "\"conditioningEncoderExecutionCount\":"
        << native_evidence.conditioning_encoder_execution_count << ","
        << qnn_prompt_to_encoder_receipt_json_fields(native_evidence) << ","
        << "\"textEncoderExecutionCount\":0,"
        << "\"conditioningArtifactConsumed\":"
        << (native_evidence.conditioning_artifact_consumed ? "true" : "false") << ","
        << "\"runtimeSessionMode\":\"isolated_unet_phase\","
        << "\"embeddingDiskDataType\":" << quote(ctx.conditioning_evidence.embedding_disk_data_type) << ","
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(ctx.execution_contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << ctx.execution_contract.vae_scaling_factor << ","
        << "\"pixelRange\":"
        << quote(mca::qnn::image_pixel_range_wire_name(ctx.execution_contract.pixel_range)) << ","
        << "\"width\":" << ctx.execution_contract.width << ","
        << "\"height\":" << ctx.execution_contract.height << ","
        << "\"seed\":" << ctx.execution_contract.seed << ","
        << "\"graphName\":" << quote(ctx.unet->graph_name) << ","
        << "\"fallback\":false,"
        << "\"nativeEffective\":" << native_effective << ","
        << "\"processExitRequired\":true,\"runtimeProfile\":\"V" << ctx.unet->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << ctx.unet->selected_runtime.htp_arch_version << ","
        << "\"latentPath\":" << quote(ctx.latent_path) << ",\"latentDtype\":\"float32-le\","
        << "\"latentShape\":[1,4," << canonical_latent_shape.height << ","
        << canonical_latent_shape.width << "],"
        << "\"latentElements\":" << canonical_latents.size() << ",\"latentBytes\":" << canonical_latents.size() * sizeof(float) << ","
        << "\"latentChecksum\":" << checksum_float_vector(canonical_latents) << ","
        << "\"unetContextLoadCount\":1,"
        << "\"unetSamplingLoopCount\":1,"
        << "\"unetSamplingStepCount\":"
        << (ctx.ultra_fix.enabled ? ctx.effective_denoise_steps * 2U : ctx.effective_denoise_steps) << ","
        << "\"unetGraphExecutionCount\":" << ctx.unet_execution_count << ","
        << "\"unetContextReusedAcrossSteps\":true,"
        << "\"unetGraphName\":" << quote(ctx.unet->graph_name) << ","
        << "\"unetContextLoadMs\":" << ctx.unet->context_load_ms << ","
        << "\"timesteps\":" << qnn_double_array_json(executed_timesteps) << ","
        << "\"sigmas\":" << qnn_double_array_json(executed_sigmas) << ","
        << "\"initNoiseSigma\":" << ctx.scheduler.get().init_noise_sigma() << ","
        << "\"img2imgBeginIndex\":" << ctx.begin_index << ","
        << "\"fullTimetableCount\":" << ctx.full_timetable_count << ","
        << "\"effectiveDenoiseSteps\":" << ctx.effective_denoise_steps << ","
        << "\"encoderLatentSha256\":" << quote(ctx.encoder_latent_sha256) << ",";
    if (ctx.ultra_fix.enabled) {
        out << "\"ultraFixTilePlanSha256\":" << quote(ctx.ultra_fix_tile_plan_sha256) << ","
            << "\"ultraFixTileCount\":" << ctx.ultra_fix_plan.tiles.size() << ","
            << "\"ultraFixInversionStepCount\":" << ctx.ultra_fix_inversion_step_count << ","
            << "\"ultraFixInversionGraphExecutionCount\":"
            << ctx.ultra_fix_inversion_graph_execution_count << ","
            << "\"ultraFixInversionTileSuccessCount\":"
            << ctx.ultra_fix_inversion_graph_execution_count << ","
            << "\"ultraFixRefinementStepCount\":" << ctx.ultra_fix_refinement_step_count << ","
            << "\"ultraFixRefinementPositiveGraphExecutionCount\":"
            << ctx.ultra_fix_refinement_positive_execution_count << ","
            << "\"ultraFixRefinementNegativeGraphExecutionCount\":"
            << ctx.ultra_fix_refinement_negative_execution_count << ","
            << "\"ultraFixRefinementTileSuccessCount\":"
            << (ctx.ultra_fix_refinement_positive_execution_count +
                ctx.ultra_fix_refinement_negative_execution_count) << ","
            << "\"ultraFixPhysicalUnetGraphExecutionCount\":"
            << ctx.unet_execution_count << ","
            << "\"ultraFixQualityStepEvaluationCount\":"
            << ctx.ultra_fix_quality_step_evaluation_count << ","
            << "\"ultraFixNoiseInjectionStepCount\":"
            << ctx.ultra_fix_noise_injection_step_count << ","
            << "\"ultraFixNoiseInjectionSeedFingerprint\":"
            << quote(ctx.ultra_fix_noise_injection_seed_fingerprint) << ","
            << "\"ultraFixNoiseInjectionChecksum\":"
            << quote(fixed_width_lower_hex_u64(ctx.ultra_fix_noise_injection_checksum)) << ","
            << "\"ultraFixStructureGuidanceStepCount\":"
            << ctx.ultra_fix_structure_guidance_step_count << ","
            << "\"ultraFixStructureGuidanceChecksum\":"
            << quote(fixed_width_lower_hex_u64(ctx.ultra_fix_structure_guidance_checksum)) << ","
            << "\"ultraFixTrajectoryNoiseChecksum\":"
            << quote(fixed_width_lower_hex_u64(ctx.ultra_fix_trajectory_noise_checksum)) << ","
            << "\"ultraFixSampleMethod\":"
            << quote(qnn_scheduler_wire_name(ctx.execution_contract.scheduler.config.algorithm)) << ","
            << "\"ultraFixNativeScheduler\":"
            << quote(qnn_scheduler_wire_name(ctx.execution_contract.scheduler.config.algorithm)) << ",";
    }
    out
        << sdxl_disabled_preview_native_json_fields() << ","
        << "\"scaleModelInput\":" << (ctx.execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
        << "\"unetExecuteMsTotal\":" << ctx.unet_execute_ms_total << ",\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMaskHex\":\""
        << mca::qnn::image_stage_mask_hex(native_detail_stage_mask) << "\","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(ctx.unet->selected_runtime) << "}";
    return out.str();
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
