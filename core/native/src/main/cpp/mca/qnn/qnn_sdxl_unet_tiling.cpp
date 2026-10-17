// core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_tiling.cpp -- latents/noise initialisation,
// encoder-conditioned img2img/inpaint tail schedule and the UltraFix inversion/trajectory
// precomputation.  (T2B18 / R7-C7 split).  Body lines 422-657 relocated from
// mca/qnn/qnn_sdxl_unet_phase.hpp; ctx rewrites are registered in a-SPLIT-PLAN.json.
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <random>
#include <string>
#include <utility>
#include <vector>

#include "mca/qnn/qnn_sdxl_unet_phase_internal.hpp"

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

std::string qnn_sdxl_unet_tiling_body(SdxlUnetPhaseContext& ctx) {
    auto& scheduler = ctx.scheduler;
    ctx.generation->set_phase(kQnnImageSampling);
    ctx.rng.seed(ctx.execution_contract.seed);
    ctx.normal = std::normal_distribution<float>(0.0f, 1.0f);
    ctx.latents.assign(static_cast<size_t>(ctx.latent_elements), 0.0f);
    ctx.initial_noise_scale = static_cast<float>(ctx.scheduler.get().init_noise_sigma());
    ctx.encoder_latent_sha256 = std::string{};
    ctx.source_latents = std::vector<float>{};
    ctx.diffusion_noise = std::vector<float>{};
    ctx.inpaint_source_noise_sha256 = std::string{};
    ctx.inpaint_source_noise_use_count = 0U;
    ctx.inpaint_preserve_step_count = 0U;
    ctx.inpaint_preserved_latent_checksum = 0U;
    ctx.img2img_noise_checksum = 0U;
    ctx.ultra_fix_clean_latents = std::vector<float>{};
    ctx.ultra_fix_equivalent_noise = std::vector<float>{};
    if (ctx.encoder_conditioned) {
        std::vector<float> canonical_encoder_latents;
        if (!read_float_binary_file(
                ctx.initial_latent_path,
                &canonical_encoder_latents,
                &ctx.error,
                &ctx.encoder_latent_sha256) ||
            ctx.encoder_latent_sha256 != normalized_contract_enum(
                string_field(ctx.params_json, "encoderLatentSha256"))) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_latent_changed",
                "ENCODER_LATENT_IDENTITY_MISMATCH",
                ctx.error.empty()
                    ? "Committed VAE encoder latent changed before UNet execution."
                    : ctx.error);
        }
        const mca::image::SpatialTensorShape canonical_encoder_shape{
            1U,
            4U,
            ctx.ultra_fix.enabled
                ? ctx.ultra_fix_plan.full_latent.height
                : ctx.latent_shape.height,
            ctx.ultra_fix.enabled
                ? ctx.ultra_fix_plan.full_latent.width
                : ctx.latent_shape.width,
            mca::image::SpatialTensorLayout::Nchw,
        };
        if (canonical_encoder_latents.size() != canonical_encoder_shape.element_count() ||
            !mca::image::copy_spatial_tile(
                canonical_encoder_latents,
                canonical_encoder_shape,
                0U,
                0U,
                ctx.ultra_fix.enabled ? ctx.ultra_fix_plan.full_latent : ctx.latent_shape,
                &ctx.source_latents,
                &ctx.error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_latent_layout_invalid",
                "ENCODER_LATENT_LAYOUT_INVALID",
                ctx.error.empty()
                    ? "Committed VAE encoder latent does not match the UNet input layout."
                    : ctx.error);
        }
        if (ctx.ultra_fix.enabled) {
            ctx.latents = ctx.source_latents;
        } else {
            ctx.diffusion_noise.assign(ctx.source_latents.size(), 0.0f);
            for (float& value : ctx.diffusion_noise) value = ctx.normal(ctx.rng);
            ctx.img2img_noise_checksum = checksum_float_vector(ctx.diffusion_noise);
            if (ctx.inpaint) {
                ctx.inpaint_source_noise_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                    reinterpret_cast<const uint8_t*>(ctx.diffusion_noise.data()),
                    ctx.diffusion_noise.size() * sizeof(float));
            }
            if (!ctx.scheduler.get().add_noise(
                    ctx.source_latents,
                    ctx.diffusion_noise,
                    ctx.begin_index,
                    &ctx.latents,
                    &ctx.error)) {
                return qnn_semantic_failure_json(
                    "sdxl_img2img_add_noise_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    ctx.error);
            }
            if (ctx.img2img_noise_checksum == 0U ||
                (ctx.inpaint && !valid_sdxl_lower_sha256(ctx.inpaint_source_noise_sha256))) {
                return qnn_semantic_failure_json(
                    "sdxl_image_noise_evidence_invalid",
                    "SCHEDULER_EXECUTION_FAILED",
                    "Split-SDXL image-conditioned noise lacks deterministic execution evidence.");
            }
            if (ctx.inpaint) ++ctx.inpaint_source_noise_use_count;
        }
    } else {
        for (float& value : ctx.latents) value = ctx.normal(ctx.rng) * ctx.initial_noise_scale;
    }
    ctx.noise_uncond = std::vector<float>{};
    ctx.noise_cond = std::vector<float>{};
    ctx.unet_execute_ms_total = 0;
    ctx.unet_execution_count = 0;
    ctx.ultra_fix_inversion_step_count = 0U;
    ctx.ultra_fix_inversion_graph_execution_count = 0U;
    ctx.ultra_fix_refinement_step_count = 0U;
    ctx.ultra_fix_refinement_positive_execution_count = 0U;
    ctx.ultra_fix_refinement_negative_execution_count = 0U;
    ctx.ultra_fix_quality_step_evaluation_count = 0U;
    ctx.ultra_fix_noise_injection_step_count = 0U;
    ctx.ultra_fix_noise_injection_seed_fingerprint = std::string{};
    ctx.ultra_fix_noise_injection_checksum = 0U;
    ctx.ultra_fix_structure_guidance_step_count = 0U;
    ctx.ultra_fix_structure_guidance_checksum = 0U;
    ctx.ultra_fix_trajectory_noise_checksum = 0U;
    if (ctx.ultra_fix.enabled) {
        if (!ctx.encoder_conditioned || ctx.source_latents.empty()) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_clean_latent_missing",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix requires the committed full clean VAE latent before inversion.");
        }
        ctx.ultra_fix_clean_latents = ctx.source_latents;
    }
    if (ctx.ultra_fix.enabled) {
        mca::image::UltraFixNoiseLevel clean_level;
        for (size_t reverse = ctx.scheduler.get().timesteps().size(); reverse > ctx.begin_index; --reverse) {
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            const size_t target_index = reverse - 1U;
            const bool first_hop = target_index + 1U == ctx.scheduler.get().timesteps().size();
            const size_t evaluation_index = first_hop ? target_index : target_index + 1U;
            mca::image::UltraFixNoiseLevel target_level;
            mca::image::UltraFixNoiseLevel source_level;
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            if (!qnn_ultrafix_noise_level(
                    ctx.scheduler, target_index, &target_level, &ctx.error) ||
                (!first_hop && !qnn_ultrafix_noise_level(
                    ctx.scheduler, evaluation_index, &source_level, &ctx.error))) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_inversion_schedule_invalid",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    ctx.error);
            }
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            if (first_hop) source_level = clean_level;
            std::vector<float> model_input;
            if (!mca::image::scale_ultrafix_inversion_model_input(
                    ctx.latents,
                    source_level,
                    first_hop ? target_level : source_level,
                    &model_input,
                    &ctx.error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_inversion_scale_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    ctx.error);
            }
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            std::vector<float> epsilon;
            if (!run_sdxl_ultrafix_tiled_unet_branch(
                    *ctx.unet,
                    ctx.sample_index,
                    ctx.timestep_index,
                    ctx.hidden_index,
                    ctx.time_ids_index,
                    ctx.pooled_index,
                    ctx.ultra_fix_plan,
                    model_input,
                    ctx.scheduler.get().timesteps()[evaluation_index],
                    ctx.conditioning.positive_hidden.data(),
                    ctx.hidden_count,
                    ctx.conditioning.time_ids,
                    ctx.time_ids_count,
                    ctx.conditioning.positive_pooled,
                    ctx.pooled_count,
                    &epsilon,
                    &ctx.unet_execute_ms_total,
                    &ctx.ultra_fix_inversion_graph_execution_count,
                    &ctx.error) ||
                !mca::image::ultrafix_epsilon_inversion_step(
                    ctx.latents,
                    epsilon,
                    source_level,
                    target_level,
                    &model_input,
                    &ctx.error)) {
                if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_inversion_execute_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    ctx.error);
            }
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            ctx.latents = std::move(model_input);
            ++ctx.ultra_fix_inversion_step_count;
            ctx.generation->set_step(static_cast<int>(ctx.ultra_fix_inversion_step_count));
        }
        if (ctx.ultra_fix_inversion_step_count != ctx.effective_denoise_steps) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_inversion_incomplete",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix did not complete every inversion hop.");
        }
        const std::string seed_descriptor = mca::image::ultrafix_noise_seed_descriptor(
            ctx.execution_contract.seed,
            ctx.effective_denoise_steps);
        ctx.ultra_fix_noise_injection_seed_fingerprint =
            mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(seed_descriptor.data()),
                seed_descriptor.size());
        if (!valid_sdxl_lower_sha256(ctx.ultra_fix_noise_injection_seed_fingerprint)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_quality_seed_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix could not bind its deterministic quality-noise seed domain.");
        }
        if (ctx.effective_denoise_steps > 1U) {
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            mca::image::UltraFixNoiseLevel inverted_level;
            if (!qnn_ultrafix_noise_level(
                    ctx.scheduler, ctx.begin_index, &inverted_level, &ctx.error) ||
                !mca::image::ultrafix_equivalent_noise(
                    ctx.ultra_fix_clean_latents,
                    ctx.latents,
                    inverted_level,
                    &ctx.ultra_fix_equivalent_noise,
                    &ctx.error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_trajectory_noise_failed",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    ctx.error);
            }
            if (ctx.generation->cancelled()) return qnn_image_generation_cancelled_json();
            ctx.ultra_fix_trajectory_noise_checksum =
                mca::image::ultrafix_tensor_checksum(ctx.ultra_fix_equivalent_noise);
            if (ctx.ultra_fix_trajectory_noise_checksum == 0U) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_trajectory_noise_evidence_invalid",
                    "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                    "Split-SDXL UltraFix trajectory noise lacks deterministic tensor evidence.");
            }
        }
    }
    return std::string();   // unreachable: every branch above returns explicitly
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
