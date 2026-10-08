#ifndef MCA_SD_CONTRACT_PARSER_HPP
#define MCA_SD_CONTRACT_PARSER_HPP

#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "mca/sd/sd_lora_adapter.hpp"
#include "mca/sd/sd_textual_inversion.hpp"

namespace mca {
namespace sd {

inline constexpr size_t kMaxTextualInversionCount = 8u;
// stable-diffusion.cpp's custom-word loader allocates a bounded 100 MiB scratch context.
// Keep the Android import and native contract limits identical to avoid a late OOM.
inline constexpr uint64_t kMaxTextualInversionBytes = 100ull * 1024ull * 1024ull;

/**
 * Execution error indicating an invalid, unsupported, or mismatched native contract.
 */
struct ContractError final : std::runtime_error {
    std::string code;
    std::string field;
    bool unsupported;

    ContractError(std::string code_value,
                  std::string field_value,
                  std::string message,
                  bool unsupported_value = false)
            : std::runtime_error(std::move(message)),
              code(std::move(code_value)),
              field(std::move(field_value)),
              unsupported(unsupported_value) {}
};

[[noreturn]] void invalid_contract(const std::string &field, const std::string &message);
[[noreturn]] void unsupported_contract(const std::string &field, const std::string &message);
[[noreturn]] void execution_mismatch(const std::string &field, const std::string &message);

nlohmann::json contract_failure_json(const ContractError &error);

// JSON contract field validation helpers
const nlohmann::json &required_field(const nlohmann::json &object, const std::string &key);
bool blank_text(const std::string &value);
std::string required_string(const nlohmann::json &object,
                            const std::string &key,
                            bool allow_empty = false);
int64_t required_integer(const nlohmann::json &object, const std::string &key);
int required_int32(const nlohmann::json &object, const std::string &key);
double required_number(const nlohmann::json &object, const std::string &key);
bool required_boolean(const nlohmann::json &object, const std::string &key);
std::string optional_string(const nlohmann::json &object,
                            const std::string &key,
                            const std::string &fallback);
bool optional_boolean(const nlohmann::json &object, const std::string &key, bool fallback);
int optional_int32(const nlohmann::json &object, const std::string &key, int fallback);
double optional_number(const nlohmann::json &object, const std::string &key, double fallback);

bool is_textual_inversion_trigger(const std::string &value);

/**
 * Stable Diffusion execution contract representing fully validated generation parameters.
 */
struct StableDiffusionExecutionContract {
    std::string profile_id;
    int profile_revision = 0;
    std::string model_fingerprint;
    std::string scheduler_wire;
    std::string prediction_wire;
    int steps = 0;
    int timetable_count = 0;
    int unet_execution_count = 0;
    double cfg_scale = 0.0;
    bool use_cfg = false;
    bool unconditional_branch = false;
    int token_count = 0;
    int tokenizer_max_length = 0;
    bool prompt_weighting_supported = false;
    double vae_scaling_factor = 0.0;
    int width = 0;
    int height = 0;
    int64_t seed = 0;
    std::string prompt;
    std::string negative_prompt;
    std::string family;
    std::string variant;
    // Explicit Qwen Image 2.1 Viggle schedules.  The field is optional on the
    // wire so the ordinary 20-step Qwen profile keeps its existing scheduler.
    std::string qwen_turbo_schedule;
    std::vector<float> qwen_raw_sigmas;
    std::vector<float> qwen_shifted_sigmas;
    std::vector<float> qwen_custom_sigmas;
    double qwen_sigma_mu = 0.0;
    int qwen_sigma_image_seq_len = 0;
    int threads = 0;
    double distilled_guidance = 0.0;
    bool distilled_guidance_specified = false;
    double flow_shift = 0.0;
    bool flow_shift_specified = false;
    std::string requested_sample_method;
    sample_method_t sample_method = SAMPLE_METHOD_COUNT;
    scheduler_t native_scheduler = SCHEDULER_COUNT;
    std::string task_mode = "text_to_image";
    std::string input_image_path;
    std::string input_image_sha256;
    std::string mask_image_path;
    std::string mask_image_sha256;
    std::string control_image_path;
    std::string control_image_sha256;
    double strength = 1.0;
    double control_strength = 1.0;
    bool strength_specified = false;
    bool control_strength_specified = false;
    int clip_skip = -1;
    int batch_count = 1;
    std::string lora_root_path;
    std::vector<ContractLoraAdapter> loras;
    std::string textual_inversion_root_path;
    std::vector<ContractTextualInversion> textual_inversions;
    std::string textual_inversion_binding_fingerprint;
    std::string textual_inversion_native_mode;
    bool textual_inversion_supported = false;
    bool vae_tiling_enabled = false;
    int vae_tile_size = 0;
    double vae_tile_overlap = 0.0;
    int preview_interval = 0;
    std::string preview_mode = "none";
    bool ultrafix_enabled = false;
    int ultrafix_target_width = 0;
    int ultrafix_target_height = 0;
    double ultrafix_strength = 0.0;
    int ultrafix_inversion_steps = 0;
    int ultrafix_refinement_steps = 0;
    int ultrafix_tile_size = 0;
    double ultrafix_overlap = 0.0;
};

bool task_uses_input_image(const StableDiffusionExecutionContract &contract);
bool task_uses_init_image(const StableDiffusionExecutionContract &contract);
bool task_uses_mask_image(const StableDiffusionExecutionContract &contract);
bool task_uses_control_image(const StableDiffusionExecutionContract &contract);
int expected_denoising_step_count(const StableDiffusionExecutionContract &contract);
float backend_denoising_strength(const StableDiffusionExecutionContract &contract);

void resolve_qwen_turbo_schedule(StableDiffusionExecutionContract &contract);
void resolve_sampler_contract(StableDiffusionExecutionContract &contract);
StableDiffusionExecutionContract parse_execution_contract(const nlohmann::json &params);

} // namespace sd
} // namespace mca

#endif // MCA_SD_CONTRACT_PARSER_HPP
