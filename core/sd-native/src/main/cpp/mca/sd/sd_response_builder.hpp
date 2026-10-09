#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "execution_asset_binding.hpp"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_evidence_verifier.hpp"

namespace mca::sd {

nlohmann::json build_generation_effective_json(
    const StableDiffusionExecutionContract &contract,
    const std::string &prediction_wire,
    const sd_img_gen_params_t &gen,
    const GenerationEvidenceSummary &evidence,
    const std::string &actual_model_version,
    bool actual_qwen_image_21,
    bool split_qwen_image_21,
    uint32_t stage_mask,
    bool input_image_wired,
    const LoadedInputImage &input_image,
    bool mask_image_wired,
    const LoadedInputImage &mask_image,
    const ControlNetEvidence &controlnet_evidence,
    bool control_image_wired,
    const LoadedInputImage &control_image,
    const sd_ultrafix_execution_evidence_t &ultrafix_evidence,
    const std::vector<sd_lora_t> &native_loras,
    const sd_image_execution_evidence_t &execution_evidence,
    const std::vector<ContractTextualInversion> &validated_textual_inversions,
    bool has_execution_asset_binding,
    const mca::image::execution_assets::Binding &execution_asset_binding);

bool write_generation_png_batch(
    const StableDiffusionExecutionContract &contract,
    const std::string &ctx_key,
    const std::vector<std::string> &output_paths,
    sd_image_t *images,
    int actual_width,
    int actual_height,
    int actual_channels,
    const std::atomic<bool> &cancel_requested,
    std::vector<std::string> &output_sha256s,
    std::vector<uint64_t> &output_byte_counts,
    std::string &output_write_error);

nlohmann::json build_generation_success_response(
    nlohmann::json native_effective,
    const StableDiffusionExecutionContract &contract,
    const std::vector<std::string> &output_paths,
    const std::vector<std::string> &output_sha256s,
    const std::vector<uint64_t> &output_byte_counts,
    const sd_img_gen_params_t &gen,
    const GenerationEvidenceSummary &evidence,
    const sd_image_execution_evidence_t &execution_evidence,
    int actual_width,
    int actual_height,
    int actual_channels,
    int observed_prediction,
    int ui_progress_callback_count,
    int ui_progress_reported_steps,
    int ui_progress_max_step,
    preview_t native_preview_mode,
    const PreviewPublicationContext &preview_context,
    bool input_image_wired,
    const LoadedInputImage &input_image,
    bool mask_image_wired,
    const LoadedInputImage &mask_image,
    bool control_image_wired,
    const LoadedInputImage &control_image,
    const std::string &selected_components_json,
    uint64_t sequence);

} // namespace mca::sd
