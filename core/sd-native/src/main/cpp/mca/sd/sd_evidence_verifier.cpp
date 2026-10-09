#include "mca/sd/sd_evidence_verifier.hpp"

#include <cmath>
#include <limits>
#include <algorithm>
#include <vector>
#include <string>

#include "image_execution_math.hpp"
#include "mca/sd/sd_textual_inversion.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"

namespace mca::sd {

namespace {

int evidence_int_checked(uint64_t value, const std::string &field, std::string &err_field, std::string &err_msg) {
    if (value > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
        err_field = field;
        err_msg = "native execution evidence exceeds int32 range";
        return -1;
    }
    return static_cast<int>(value);
}

int evidence_per_image_checked(uint64_t value, int batch_count, const std::string &field, std::string &err_field, std::string &err_msg) {
    if (batch_count <= 0 || value % static_cast<uint64_t>(batch_count) != 0u) {
        err_field = field;
        err_msg = "native execution evidence is not divisible by the completed batch count";
        return -1;
    }
    return evidence_int_checked(value / static_cast<uint64_t>(batch_count), field, err_field, err_msg);
}

} // namespace

bool verify_generation_evidence(
    const StableDiffusionExecutionContract &contract,
    sd_ctx_t *ctx,
    const sd_img_gen_params_t &gen,
    int actual_width,
    int actual_height,
    bool input_image_wired,
    const LoadedInputImage &input_image,
    bool control_image_wired,
    const ControlNetEvidence &controlnet_evidence,
    const sd_ultrafix_execution_evidence_t &ultrafix_evidence,
    const sd_image_execution_evidence_t &execution_evidence,
    bool flow_shift_capable,
    bool dynamic_flow_shift,
    bool distilled_guidance_capable,
    GenerationEvidenceSummary &summary,
    std::string &mismatch_field,
    std::string &mismatch_message) {

    auto fail = [&](const std::string &field, const std::string &message) -> bool {
        mismatch_field = field;
        mismatch_message = message;
        return false;
    };

    if (contract.ultrafix_enabled) {
        const uint64_t expected_denoise_steps =
                static_cast<uint64_t>(contract.ultrafix_inversion_steps);
        const uint64_t expected_refinement_branches = contract.use_cfg ? 2u : 1u;
        const bool dimensions_match =
                ultrafix_evidence.source_width == input_image.width &&
                ultrafix_evidence.source_height == input_image.height &&
                ultrafix_evidence.target_width == static_cast<uint32_t>(contract.width) &&
                ultrafix_evidence.target_height == static_cast<uint32_t>(contract.height);
        const uint64_t source_width = ultrafix_evidence.source_width;
        const uint64_t source_height = ultrafix_evidence.source_height;
        const uint64_t target_width = ultrafix_evidence.target_width;
        const uint64_t target_height = ultrafix_evidence.target_height;
        uint64_t expected_resized_width = target_width;
        uint64_t expected_resized_height = target_height;
        bool source_fit_matches =
                source_width > 0u && source_height > 0u &&
                source_width <= target_width && source_height <= target_height;
        if (source_fit_matches) {
            if (target_width * source_height >= target_height * source_width) {
                expected_resized_height = source_height * target_width / source_width;
            } else {
                expected_resized_width = source_width * target_height / source_height;
            }
            source_fit_matches =
                    ultrafix_evidence.source_fit ==
                            static_cast<uint32_t>(SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER) &&
                    ultrafix_evidence.source_resized_width == expected_resized_width &&
                    ultrafix_evidence.source_resized_height == expected_resized_height &&
                    ultrafix_evidence.source_crop_left ==
                            (expected_resized_width - target_width) / 2u &&
                    ultrafix_evidence.source_crop_top ==
                            (expected_resized_height - target_height) / 2u;
        }
        const bool request_matches =
                ultrafix_evidence.version == SD_ULTRAFIX_EXECUTION_EVIDENCE_VERSION &&
                ultrafix_evidence.generation_completed == 1u &&
                ultrafix_evidence.cancelled == 0u &&
                ultrafix_evidence.preview_published == 0u &&
                dimensions_match && source_fit_matches &&
                ultrafix_evidence.tile_size ==
                        static_cast<uint32_t>(contract.ultrafix_tile_size) &&
                std::fabs(static_cast<double>(ultrafix_evidence.tile_overlap) -
                          contract.ultrafix_overlap) <= 1.0e-6 &&
                ultrafix_evidence.inversion_steps == expected_denoise_steps &&
                ultrafix_evidence.refinement_steps ==
                        static_cast<uint32_t>(contract.ultrafix_refinement_steps) &&
                ultrafix_evidence.denoise_step_count == expected_denoise_steps &&
                ultrafix_evidence.sample_method ==
                        static_cast<uint32_t>(gen.sample_params.sample_method) &&
                ultrafix_evidence.scheduler ==
                        static_cast<uint32_t>(gen.sample_params.scheduler);
        const bool vae_stages_complete =
                ultrafix_evidence.vae_encode_invocation_count == 1u &&
                ultrafix_evidence.vae_encode_success_count == 1u &&
                ultrafix_evidence.vae_encode_tile_count ==
                        ultrafix_evidence.vae_encode_tile_success_count &&
                ultrafix_evidence.tiled_vae_decode_invocation_count == 1u &&
                ultrafix_evidence.tiled_vae_decode_success_count == 1u &&
                ultrafix_evidence.tiled_vae_decode_tile_count ==
                        ultrafix_evidence.tiled_vae_decode_tile_success_count &&
                ultrafix_evidence.vae_encode_tile_count ==
                        execution_evidence.vae_encode_tile_compute_attempt_count &&
                ultrafix_evidence.vae_encode_tile_success_count ==
                        execution_evidence.vae_encode_tile_compute_success_count &&
                ultrafix_evidence.tiled_vae_decode_tile_count ==
                        execution_evidence.vae_decode_tile_compute_attempt_count &&
                ultrafix_evidence.tiled_vae_decode_tile_success_count ==
                        execution_evidence.vae_decode_tile_compute_success_count;
        const bool inversion_complete =
                ultrafix_evidence.ddim_inversion_invocation_count ==
                        expected_denoise_steps &&
                ultrafix_evidence.ddim_inversion_success_count ==
                        expected_denoise_steps &&
                ultrafix_evidence.ddim_inversion_step_count ==
                        expected_denoise_steps &&
                ultrafix_evidence.ddim_inversion_tile_count > 0u &&
                ultrafix_evidence.ddim_inversion_tile_success_count ==
                        ultrafix_evidence.ddim_inversion_tile_count;
        const bool refinement_complete =
                ultrafix_evidence.tiled_unet_invocation_count ==
                        expected_denoise_steps &&
                ultrafix_evidence.tiled_unet_success_count ==
                        expected_denoise_steps &&
                ultrafix_evidence.tiled_unet_step_count ==
                        expected_denoise_steps &&
                ultrafix_evidence.tiled_unet_tile_count > 0u &&
                ultrafix_evidence.tiled_unet_tile_success_count ==
                        ultrafix_evidence.tiled_unet_tile_count;
        const uint64_t expected_physical_diffusion_count =
                ultrafix_evidence.ddim_inversion_tile_count +
                ultrafix_evidence.tiled_unet_tile_count *
                        expected_refinement_branches;
        const bool physical_counts_match =
                execution_evidence.diffusion_model_compute_count ==
                        expected_physical_diffusion_count &&
                execution_evidence.positive_diffusion_model_compute_count ==
                        ultrafix_evidence.ddim_inversion_tile_count +
                        ultrafix_evidence.tiled_unet_tile_count &&
                execution_evidence.negative_diffusion_model_compute_count ==
                        (contract.use_cfg
                            ? ultrafix_evidence.tiled_unet_tile_count
                            : 0u) &&
                execution_evidence.auxiliary_diffusion_model_compute_count == 0u &&
                execution_evidence.sampling_pass_count == 1u &&
                execution_evidence.completed_sampling_step_count ==
                        expected_denoise_steps;
        const std::string quality_seed_descriptor =
                mca::image::ultrafix_noise_seed_descriptor(
                        static_cast<uint64_t>(gen.seed),
                        expected_denoise_steps);
        const uint64_t quality_step_count =
                ultrafix_evidence.quality_step_evaluation_count;
        const bool quality_noise_evidence_consistent =
                ultrafix_evidence.noise_injection_step_count <= quality_step_count &&
                ((ultrafix_evidence.noise_injection_step_count == 0u) ==
                 (ultrafix_evidence.noise_injection_checksum == 0u));
        const bool quality_structure_evidence_consistent =
                ultrafix_evidence.structure_guidance_step_count <= quality_step_count &&
                ((ultrafix_evidence.structure_guidance_step_count == 0u) ==
                 (ultrafix_evidence.structure_guidance_checksum == 0u));
        const bool quality_coverage_complete = quality_step_count == 0u
                ? ultrafix_evidence.trajectory_noise_checksum == 0u
                : ultrafix_evidence.trajectory_noise_checksum != 0u &&
                        ultrafix_evidence.noise_injection_step_count +
                                ultrafix_evidence.structure_guidance_step_count >=
                            quality_step_count;
        const bool quality_execution_match =
                ultrafix_evidence.noise_injection_seed ==
                        static_cast<uint64_t>(gen.seed) &&
                ultrafix_evidence.quality_step_evaluation_count ==
                        (expected_denoise_steps > 0u
                            ? expected_denoise_steps - 1u : 0u) &&
                quality_noise_evidence_consistent &&
                quality_structure_evidence_consistent &&
                quality_coverage_complete;
        if (!request_matches || !vae_stages_complete || !inversion_complete ||
            !refinement_complete || !physical_counts_match ||
            !quality_execution_match || quality_seed_descriptor.empty()) {
            return fail(
                    "ultraFixEvidence",
                    "native UltraFix stage evidence is incomplete or conflicts with physical execution counts");
        }
    }

    summary.actual_flow_shift_applied =
            execution_evidence.flow_shift_applied != 0u;
    summary.actual_dynamic_flow_shift =
            execution_evidence.dynamic_flow_shift != 0u;
    summary.actual_distilled_guidance_applied =
            execution_evidence.distilled_guidance_applied != 0u;
    if (summary.actual_flow_shift_applied != flow_shift_capable ||
        summary.actual_dynamic_flow_shift != dynamic_flow_shift) {
        return fail(
                "flowShift",
                "native flow-shift evidence does not match the loaded denoiser");
    }
    summary.actual_flow_shift =
            static_cast<double>(execution_evidence.effective_flow_shift);
    if (summary.actual_flow_shift_applied) {
        if (!std::isfinite(summary.actual_flow_shift) || summary.actual_flow_shift < 0.0) {
            return fail(
                    "flowShift",
                    "native flow denoising did not publish a finite effective shift");
        }
        if (!dynamic_flow_shift && contract.flow_shift >= 0.0 &&
            std::fabs(summary.actual_flow_shift - contract.flow_shift) > 1e-5) {
            return fail(
                    "flowShift",
                    "native flow denoising did not execute the configured shift");
        }
    }
    if (summary.actual_distilled_guidance_applied != distilled_guidance_capable) {
        return fail(
                "distilledGuidance",
                "native distilled-guidance evidence does not match the loaded diffusion graph");
    }
    summary.actual_distilled_guidance =
            static_cast<double>(execution_evidence.effective_distilled_guidance);
    if (summary.actual_distilled_guidance_applied &&
        (!std::isfinite(summary.actual_distilled_guidance) ||
         std::fabs(summary.actual_distilled_guidance - contract.distilled_guidance) > 1e-5)) {
        return fail(
                "distilledGuidance",
                "native diffusion execution did not consume the configured distilled guidance");
    }
    if (execution_evidence.conditioning_artifact_byte_count == 0u ||
        execution_evidence.conditioning_artifact_byte_count >
                static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        return fail(
                "promptWeightFingerprint",
                "native conditioning evidence did not expose a bounded artifact");
    }
    std::vector<uint8_t> conditioning_artifact(
            static_cast<size_t>(execution_evidence.conditioning_artifact_byte_count));
    if (!sd_copy_last_image_conditioning_artifact(
            ctx,
            conditioning_artifact.data(),
            conditioning_artifact.size())) {
        return fail(
                "promptWeightFingerprint",
                "native conditioning evidence artifact could not be copied exactly");
    }
    if (!sha256_implementation_ready()) {
        return fail(
                "promptWeightFingerprint",
                "native SHA-256 self-check failed before conditioning evidence hashing");
    }
    summary.actual_prompt_weight_fingerprint = sha256_hex(conditioning_artifact);

    std::string err_field, err_msg;
    summary.actual_timetable_count = evidence_per_image_checked(
            execution_evidence.completed_sampling_step_count,
            contract.batch_count,
            "timetableCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_sampling_pass_count = evidence_int_checked(
            execution_evidence.sampling_pass_count,
            "samplingPassCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_unet_execution_count = evidence_per_image_checked(
            execution_evidence.diffusion_model_compute_count,
            contract.batch_count,
            "unetExecutionCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_positive_execution_count = evidence_per_image_checked(
            execution_evidence.positive_diffusion_model_compute_count,
            contract.batch_count,
            "positiveDiffusionExecutionCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_negative_execution_count = evidence_per_image_checked(
            execution_evidence.negative_diffusion_model_compute_count,
            contract.batch_count,
            "negativeDiffusionExecutionCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_auxiliary_execution_count = evidence_per_image_checked(
            execution_evidence.auxiliary_diffusion_model_compute_count,
            contract.batch_count,
            "auxiliaryDiffusionExecutionCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tiling_invocation_count = evidence_int_checked(
            execution_evidence.vae_encode_tiling_invocation_count,
            "vaeEncodeTilingInvocationCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tiling_success_count = evidence_int_checked(
            execution_evidence.vae_encode_tiling_success_count,
            "vaeEncodeTilingSuccessCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_tiling_invocation_count = evidence_int_checked(
            execution_evidence.vae_decode_tiling_invocation_count,
            "vaeDecodeTilingInvocationCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_tiling_success_count = evidence_int_checked(
            execution_evidence.vae_decode_tiling_success_count,
            "vaeDecodeTilingSuccessCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_planned_tile_count = evidence_int_checked(
            execution_evidence.vae_encode_planned_tile_count,
            "vaeEncodePlannedTileCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_planned_tile_count = evidence_int_checked(
            execution_evidence.vae_decode_planned_tile_count,
            "vaeDecodePlannedTileCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tile_compute_attempt_count = evidence_int_checked(
            execution_evidence.vae_encode_tile_compute_attempt_count,
            "vaeEncodeTileComputeAttemptCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tile_compute_success_count = evidence_int_checked(
            execution_evidence.vae_encode_tile_compute_success_count,
            "vaeEncodeTileComputeSuccessCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_tile_compute_attempt_count = evidence_int_checked(
            execution_evidence.vae_decode_tile_compute_attempt_count,
            "vaeDecodeTileComputeAttemptCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_tile_compute_success_count = evidence_int_checked(
            execution_evidence.vae_decode_tile_compute_success_count,
            "vaeDecodeTileComputeSuccessCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tile_size_x = evidence_int_checked(
            execution_evidence.vae_encode_tile_size_x,
            "vaeEncodeTileSizeX", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tile_size_y = evidence_int_checked(
            execution_evidence.vae_encode_tile_size_y,
            "vaeEncodeTileSizeY", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_tile_size_x = evidence_int_checked(
            execution_evidence.vae_decode_tile_size_x,
            "vaeDecodeTileSizeX", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_decode_tile_size_y = evidence_int_checked(
            execution_evidence.vae_decode_tile_size_y,
            "vaeDecodeTileSizeY", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_vae_encode_tile_overlap_x =
            static_cast<double>(execution_evidence.vae_encode_tile_overlap_x);
    summary.actual_vae_encode_tile_overlap_y =
            static_cast<double>(execution_evidence.vae_encode_tile_overlap_y);
    summary.actual_vae_decode_tile_overlap_x =
            static_cast<double>(execution_evidence.vae_decode_tile_overlap_x);
    summary.actual_vae_decode_tile_overlap_y =
            static_cast<double>(execution_evidence.vae_decode_tile_overlap_y);

    const uint64_t classified_compute_count =
            execution_evidence.positive_diffusion_model_compute_count +
            execution_evidence.negative_diffusion_model_compute_count +
            execution_evidence.auxiliary_diffusion_model_compute_count;
    if (classified_compute_count != execution_evidence.diffusion_model_compute_count) {
        return fail(
                "unetExecutionCount",
                "physical diffusion computes were not completely classified by execution branch");
    }

    std::string cn_branch_field, cn_branch_error;
    if (!verify_controlnet_branch_classification(
                execution_evidence, cn_branch_field, cn_branch_error)) {
        return fail(cn_branch_field, cn_branch_error);
    }
    if (summary.actual_sampling_pass_count != contract.batch_count) {
        return fail(
                "samplingPassCount",
                "native image generation executed an unexpected number of sampling passes");
    }
    if (summary.actual_auxiliary_execution_count != 0) {
        return fail(
                "unetExecutionCount",
                "strict image execution does not support auxiliary diffusion branches");
    }
    if (!contract.ultrafix_enabled &&
        summary.actual_positive_execution_count != summary.actual_timetable_count) {
        return fail(
                "positiveDiffusionExecutionCount",
                "positive conditioning did not physically execute once per completed sampling step");
    }

    const bool requested_use_cfg =
            std::fabs(static_cast<double>(gen.sample_params.guidance.txt_cfg) - 1.0) > 1e-6;
    summary.actual_use_cfg = summary.actual_negative_execution_count > 0;
    summary.actual_branches = summary.actual_use_cfg ? 2 : 1;
    if (requested_use_cfg != summary.actual_use_cfg) {
        return fail(
                "useCfg",
                "actual negative diffusion execution does not match the configured CFG mode");
    }
    summary.actual_control_image_consumed =
            control_image_wired && controlnet_evidence.residual_consumption_count > 0;
    std::string cn_ev_field, cn_ev_error;
    if (!verify_controlnet_execution_evidence(
                controlnet_evidence,
                control_image_wired,
                summary.actual_unet_execution_count,
                summary.actual_positive_execution_count,
                summary.actual_negative_execution_count,
                cn_ev_field,
                cn_ev_error)) {
        return fail(cn_ev_field, cn_ev_error);
    }
    summary.actual_vae_tiling_enabled =
            summary.actual_vae_encode_tiling_invocation_count > 0 ||
            summary.actual_vae_decode_tiling_invocation_count > 0;
    auto valid_vae_overlap = [](double value) {
        return std::isfinite(value) && value >= 0.0 && value <= 0.5;
    };
    auto validate_vae_tiling_phase = [&](const char *phase,
                                         int invocation_count,
                                         int success_count,
                                         int planned_tile_count,
                                         int tile_compute_attempt_count,
                                         int tile_compute_success_count,
                                         int tile_size_x,
                                         int tile_size_y,
                                         double overlap_x,
                                         double overlap_y) -> bool {
        if (invocation_count == 0) {
            if (success_count != 0 || planned_tile_count != 0 ||
                tile_compute_attempt_count != 0 || tile_compute_success_count != 0 ||
                tile_size_x != 0 || tile_size_y != 0 || overlap_x != 0.0 ||
                overlap_y != 0.0) {
                return fail(
                        "vaeTiling",
                        std::string("native VAE ") + phase +
                            " evidence exists without a tiled invocation");
            }
            return true;
        }
        if (success_count != invocation_count || planned_tile_count <= 0 ||
            tile_compute_attempt_count != planned_tile_count ||
            tile_compute_success_count != tile_compute_attempt_count ||
            tile_size_x <= 0 || tile_size_y <= 0 ||
            !valid_vae_overlap(overlap_x) || !valid_vae_overlap(overlap_y)) {
            return fail(
                    "vaeTiling",
                    std::string("native VAE ") + phase +
                        " tiling did not complete its physical tile plan");
        }
        return true;
    };
    if (!validate_vae_tiling_phase(
            "encode",
            summary.actual_vae_encode_tiling_invocation_count,
            summary.actual_vae_encode_tiling_success_count,
            summary.actual_vae_encode_planned_tile_count,
            summary.actual_vae_encode_tile_compute_attempt_count,
            summary.actual_vae_encode_tile_compute_success_count,
            summary.actual_vae_encode_tile_size_x,
            summary.actual_vae_encode_tile_size_y,
            summary.actual_vae_encode_tile_overlap_x,
            summary.actual_vae_encode_tile_overlap_y)) {
        return false;
    }
    if (!validate_vae_tiling_phase(
            "decode",
            summary.actual_vae_decode_tiling_invocation_count,
            summary.actual_vae_decode_tiling_success_count,
            summary.actual_vae_decode_planned_tile_count,
            summary.actual_vae_decode_tile_compute_attempt_count,
            summary.actual_vae_decode_tile_compute_success_count,
            summary.actual_vae_decode_tile_size_x,
            summary.actual_vae_decode_tile_size_y,
            summary.actual_vae_decode_tile_overlap_x,
            summary.actual_vae_decode_tile_overlap_y)) {
        return false;
    }
    if (contract.vae_tiling_enabled) {
        if (!summary.actual_vae_tiling_enabled ||
            summary.actual_vae_decode_tiling_invocation_count != contract.batch_count) {
            return fail(
                    "vaeTiling",
                    "requested VAE tiling did not execute exactly once for every final image decode");
        }
        if (input_image_wired && summary.actual_vae_encode_tiling_invocation_count <= 0) {
            return fail(
                    "vaeTiling",
                    "requested VAE tiling did not execute while encoding the prepared input image");
        }
    } else if (summary.actual_vae_tiling_enabled) {
        return fail(
                "vaeTiling",
                "native VAE tiling executed even though the request disabled it");
    }
    if (execution_evidence.positive_conditioning_observed == 0u ||
        execution_evidence.positive_conditioning_token_count == 0u) {
        return fail(
                "tokenCount",
                "positive conditioning did not publish actual tokenizer evidence");
    }
    if (summary.actual_use_cfg) {
        if (execution_evidence.negative_conditioning_observed == 0u ||
            execution_evidence.negative_conditioning_token_count == 0u ||
            (!contract.ultrafix_enabled &&
                summary.actual_negative_execution_count != summary.actual_timetable_count) ||
            (contract.ultrafix_enabled &&
                static_cast<uint64_t>(summary.actual_negative_execution_count) !=
                    ultrafix_evidence.tiled_unet_tile_count)) {
            return fail(
                    "unconditionalBranch",
                    "CFG did not execute evidenced negative conditioning for every refinement evaluation");
        }
    } else if (execution_evidence.negative_conditioning_observed != 0u ||
               execution_evidence.negative_conditioning_token_count != 0u ||
               summary.actual_negative_execution_count != 0) {
        return fail(
                "unconditionalBranch",
                "non-CFG generation unexpectedly prepared or executed negative conditioning");
    }

    const uint64_t total_conditioning_token_count =
            execution_evidence.positive_conditioning_token_count +
            execution_evidence.negative_conditioning_token_count;
    summary.actual_token_count = evidence_int_checked(
            total_conditioning_token_count,
            "tokenCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_positive_weighted_token_count = evidence_int_checked(
            execution_evidence.positive_non_unity_weight_count,
            "positiveWeightedTokenCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_negative_weighted_token_count = evidence_int_checked(
            execution_evidence.negative_non_unity_weight_count,
            "negativeWeightedTokenCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    summary.actual_prompt_weighting_applied =
            execution_evidence.prompt_weighting_applied != 0u;
    const bool weighted_count_observed =
            summary.actual_positive_weighted_token_count > 0 ||
            summary.actual_negative_weighted_token_count > 0;
    if (summary.actual_prompt_weighting_applied != weighted_count_observed) {
        return fail(
                "promptWeightingApplied",
                "native weighting flag conflicts with actual non-unity token weights");
    }
    if (execution_evidence.positive_non_unity_weight_count >
                execution_evidence.positive_conditioning_token_count ||
        execution_evidence.negative_non_unity_weight_count >
                execution_evidence.negative_conditioning_token_count) {
        return fail(
                "promptWeightingApplied",
                "native non-unity weight counts exceed actual conditioning tokens");
    }
    if (summary.actual_prompt_weighting_applied && !contract.prompt_weighting_supported) {
        return fail(
                "promptWeightingSupported",
                "native prompt syntax applied non-unity weights while the resolved profile disables weighting");
    }
    if (contract.prompt_weighting_supported &&
        (execution_evidence.positive_prompt_weighting_capable == 0u ||
         (summary.actual_use_cfg && execution_evidence.negative_prompt_weighting_capable == 0u))) {
        return fail(
                "promptWeightingSupported",
                "the executed conditioner cannot prove token-level weighting for every active branch");
    }

    summary.actual_native_prompt_execution_sha256 =
            image_prompt_execution_sha256(
                    gen.prompt == nullptr ? std::string() : std::string(gen.prompt),
                    gen.negative_prompt == nullptr
                            ? std::string()
                            : std::string(gen.negative_prompt));
    if (!is_sha256(summary.actual_native_prompt_execution_sha256)) {
        return fail(
                "nativePromptExecutionSha256",
                "native prompt framing could not be hashed after conditioning execution");
    }

    summary.total_unet_execution_count = evidence_int_checked(
            execution_evidence.diffusion_model_compute_count,
            "totalUnetExecutionCount", err_field, err_msg);
    if (!err_field.empty()) return fail(err_field, err_msg);

    if (actual_width != contract.width || actual_height != contract.height) {
        return fail(
                "width,height",
                "stable-diffusion.cpp aligned the requested dimensions; strict execution rejects implicit alignment");
    }
    if (summary.actual_timetable_count != contract.timetable_count) {
        return fail(
                "timetableCount",
                "actual completed native sampling steps differ from the resolved timetableCount");
    }
    if (!contract.ultrafix_enabled &&
        summary.actual_unet_execution_count != contract.unet_execution_count) {
        return fail(
                "unetExecutionCount",
                "actual physical diffusion model computes differ from the resolved contract");
    }

    return true;
}

} // namespace mca::sd
