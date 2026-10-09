#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "stable-diffusion.h"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"

namespace mca::sd {

struct GenerationEvidenceSummary {
    int actual_timetable_count = 0;
    int actual_sampling_pass_count = 0;
    int actual_unet_execution_count = 0;
    int actual_positive_execution_count = 0;
    int actual_negative_execution_count = 0;
    int actual_auxiliary_execution_count = 0;
    bool actual_flow_shift_applied = false;
    bool actual_dynamic_flow_shift = false;
    double actual_flow_shift = 0.0;
    bool actual_distilled_guidance_applied = false;
    double actual_distilled_guidance = 0.0;
    std::string actual_prompt_weight_fingerprint;
    int actual_vae_encode_tiling_invocation_count = 0;
    int actual_vae_encode_tiling_success_count = 0;
    int actual_vae_decode_tiling_invocation_count = 0;
    int actual_vae_decode_tiling_success_count = 0;
    int actual_vae_encode_planned_tile_count = 0;
    int actual_vae_decode_planned_tile_count = 0;
    int actual_vae_encode_tile_compute_attempt_count = 0;
    int actual_vae_encode_tile_compute_success_count = 0;
    int actual_vae_decode_tile_compute_attempt_count = 0;
    int actual_vae_decode_tile_compute_success_count = 0;
    int actual_vae_encode_tile_size_x = 0;
    int actual_vae_encode_tile_size_y = 0;
    int actual_vae_decode_tile_size_x = 0;
    int actual_vae_decode_tile_size_y = 0;
    double actual_vae_encode_tile_overlap_x = 0.0;
    double actual_vae_encode_tile_overlap_y = 0.0;
    double actual_vae_decode_tile_overlap_x = 0.0;
    double actual_vae_decode_tile_overlap_y = 0.0;
    bool actual_vae_tiling_enabled = false;
    bool actual_use_cfg = false;
    int actual_branches = 1;
    bool actual_control_image_consumed = false;
    int actual_token_count = 0;
    int actual_positive_weighted_token_count = 0;
    int actual_negative_weighted_token_count = 0;
    bool actual_prompt_weighting_applied = false;
    std::string actual_native_prompt_execution_sha256;
    int64_t total_unet_execution_count = 0;
};

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
    std::string &mismatch_message);

} // namespace mca::sd
