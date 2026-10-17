// T2B17 / R7-C6 (2026-10-14): internal split scaffold for stable_diffusion_bridge.cpp.
// Declarations only.  Every name declared here is hidden (visibility pragma below), so the
// shared object keeps exporting exactly the eight Java_com_muyuchat_core_sdnative_* entries.
#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"

#include "mca/sd/sd_contract_parser.hpp"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_textual_inversion.hpp"
#include "execution_asset_binding.hpp"

#pragma GCC visibility push(hidden)
namespace mca_sd_bridge_internal {

extern sd_ctx_t *g_ctx;
extern std::string g_ctx_key;
extern std::string g_last_error;

bool file_exists(const std::string &path);

void mark_generation_stage(uint32_t stage);

void set_progress(const std::string &phase,
                  const std::string &message,
                  int step = 0,
                  int steps = 0,
                  float seconds_per_step = 0.0f,
                  int width = 0,
                  int height = 0,
                  int threads = 0);

void set_progress_component_selection(const std::string &component_selection_json);

sd_ctx_t *ensure_context(const mca::sd::ComponentPaths &paths,
                         const std::vector<mca::sd::ContractTextualInversion> &textual_inversions,
                         const std::string &ctx_key,
                         int threads,
                         bool unload_after_stages = false,
                         sd_qwen_image_21_phase_t qwen_phase = SD_QWEN_IMAGE_21_PHASE_FULL);

nlohmann::json measure_clip_prompt_tokens(const std::string &prompt, int max_tokens);

// Shared per-request state carried across the four generate phases.  The destructor keeps the
// original release order of the bridge: the Qwen conditioning artifact is freed before the
// stage-unloaded native context (QwenConditionRelease ran before StageContextRelease).
struct SdGenerateContext {
    SdGenerateContext(const std::string &model_path,
                      const std::string &bundle_root,
                      const std::string &params_json,
                      const std::string &output_path,
                      std::atomic<uint64_t> &generation_sequence)
            : model_path(model_path),
              bundle_root(bundle_root),
              params_json(params_json),
              output_path(output_path),
              generation_sequence(generation_sequence) {}

    // Defined out-of-line in stable_diffusion_bridge.cpp: the member destructors (and the
    // weak template exports they pin) keep the exact emission shape of the pre-split bridge.
    ~SdGenerateContext();

    // Immutable request inputs owned by the JNI entry frame.
    const std::string &model_path;
    const std::string &bundle_root;
    const std::string &params_json;
    const std::string &output_path;
    std::atomic<uint64_t> &generation_sequence;

    // Request phase products.
    nlohmann::json params;
    mca::sd::StableDiffusionExecutionContract contract;
    std::vector<std::string> output_paths;
    mca::sd::LoadedInputImage input_image;
    mca::sd::LoadedInputImage mask_image;
    mca::sd::LoadedInputImage control_image;
    std::vector<std::string> canonical_lora_paths;
    std::vector<mca::sd::ContractTextualInversion> validated_textual_inversions;

    // Context phase products.
    mca::sd::ComponentPaths paths;
    mca::image::execution_assets::Binding execution_asset_binding;
    bool has_execution_asset_binding = false;
    std::string selected_components_json;
    bool split_qwen_image_21 = false;
    sd_ctx_t *ctx = nullptr;
    bool stage_context_release_enabled = false;
    bool actual_qwen_image_21 = false;
    std::string actual_model_version;
    std::vector<sd_lora_t> native_loras;
    sd_qwen_image_21_condition_t *qwen_condition = nullptr;

    // Execute phase products consumed by the publish phase.
    int observed_prediction = 0;
    std::string prediction_wire;
    bool flow_shift_capable = false;
    bool dynamic_flow_shift = false;
    bool distilled_guidance_capable = false;
    sd_img_gen_params_t gen{};
    std::vector<float> qwen_custom_sigmas;
    uint64_t sequence = 0;
    mca::sd::PreviewPublicationContext preview_context;
    preview_t native_preview_mode = PREVIEW_NONE;
    sd_image_t *images = nullptr;
    sd_ultrafix_execution_evidence_t ultrafix_evidence{};
    sd_image_execution_evidence_t execution_evidence{};
    bool native_evidence_available = false;
    std::string textual_inversion_failure_code;
    std::string textual_inversion_failure_message;
    bool textual_inversion_evidence_valid = false;
    bool input_image_wired = false;
    bool mask_image_wired = false;
    bool control_image_wired = false;
};

std::string sd_generate_request(SdGenerateContext &state);
std::string sd_generate_context(SdGenerateContext &state);
std::string sd_generate_execute(SdGenerateContext &state);
std::string sd_generate_publish(SdGenerateContext &state);

}  // namespace mca_sd_bridge_internal
#pragma GCC visibility pop
