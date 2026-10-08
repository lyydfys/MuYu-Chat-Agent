#ifndef MCA_SD_SCHEDULE_RESOLVER_HPP
#define MCA_SD_SCHEDULE_RESOLVER_HPP

#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "sd_contracts.hpp"

namespace mca {
namespace sd {

/**
 * Constants for Viggle schedule resolution on Qwen Image 2.1.
 */
inline constexpr const char* kViggleR128Sha256 =
        "bafb91d0047df3f9b8a5a850b0c967f051164314d8aad778dfa34d9c24ec345b";
inline constexpr const char* kViggleR256Sha256 =
        "2a0148f5c73abbed5f97da5ea356e439318aadb281d01fce4af39cdf43728803";
inline constexpr const char* kQwenTargetVariant = "QWEN_IMAGE_21";
inline constexpr int kEulerSampleMethod = 0;

/**
 * Dedicated exception for schedule contract validation errors.
 * Preserves error code, offending field name, and unsupported flag.
 */
class ScheduleContractException final : public std::runtime_error {
public:
    ScheduleContractException(std::string code,
                              std::string field,
                              std::string message,
                              bool unsupported = false)
            : std::runtime_error(std::move(message)),
              code_(std::move(code)),
              field_(std::move(field)),
              unsupported_(unsupported) {}

    [[nodiscard]] const std::string& code() const noexcept { return code_; }
    [[nodiscard]] const std::string& field() const noexcept { return field_; }
    [[nodiscard]] bool unsupported() const noexcept { return unsupported_; }

private:
    std::string code_;
    std::string field_;
    bool unsupported_{false};
};

[[noreturn]] inline void throw_invalid_schedule(const std::string& field, const std::string& message) {
    throw ScheduleContractException("IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID", field, message, false);
}

[[noreturn]] inline void throw_unsupported_schedule(const std::string& field, const std::string& message) {
    throw ScheduleContractException("IMAGE_NATIVE_EXECUTION_CONTRACT_UNSUPPORTED", field, message, true);
}

// ============================================================================
// Input Image Predicates
// ============================================================================

[[nodiscard]] bool task_uses_input_image(const std::string& task_mode) noexcept;
[[nodiscard]] bool task_uses_init_image(const std::string& task_mode) noexcept;
[[nodiscard]] bool task_uses_mask_image(const std::string& task_mode) noexcept;
[[nodiscard]] bool task_uses_control_image(const std::string& task_mode) noexcept;

[[nodiscard]] bool task_uses_input_image(ImageTaskMode mode) noexcept;

// ============================================================================
// Denoising Step and Strength Calculations
// ============================================================================

[[nodiscard]] int expected_denoising_step_count(
        int steps,
        double strength,
        const std::string& task_mode) noexcept;

[[nodiscard]] float backend_denoising_strength(
        int steps,
        double strength,
        const std::string& task_mode) noexcept;

// ============================================================================
// Schedule Resolution Data Structures and Adapters
// ============================================================================

/**
 * LoRA adapter specification view for schedule resolution.
 */
struct ScheduleLoraSpec {
    std::string sha256;
    double multiplier{1.0};
};

/**
 * Complete contract view required for Qwen Image 2.1 Viggle schedule resolution.
 */
struct QwenScheduleContract {
    std::string qwen_turbo_schedule;
    std::string variant;
    std::vector<ScheduleLoraSpec> loras;
    double cfg_scale{1.0};
    bool use_cfg{false};
    std::string negative_prompt;
    std::string scheduler_wire;
    std::string prediction_wire;
    int sample_method{kEulerSampleMethod};
    std::string task_mode{"text_to_image"};
    double flow_shift{0.0};
    int steps{0};
    int timetable_count{0};
    int width{0};
    int height{0};

    // Output fields populated by resolve_qwen_turbo_schedule
    std::vector<float> qwen_raw_sigmas;
    std::vector<float> qwen_shifted_sigmas;
    std::vector<float> qwen_custom_sigmas;
    double qwen_sigma_mu{0.0};
    int qwen_sigma_image_seq_len{0};
};

/**
 * Checks whether the given SHA256 matches an official Viggle v0.2.1 adapter.
 */
[[nodiscard]] bool is_official_viggle_v021_adapter(const std::string& sha256) noexcept;

/**
 * Returns the raw sigmas defined by the specified Viggle schedule name.
 * Throws ScheduleContractException on unknown schedule name.
 */
[[nodiscard]] std::vector<float> get_viggle_raw_sigmas(const std::string& schedule_name);

/**
 * Computes mu for exponential time shift from the Qwen latent sequence length.
 */
[[nodiscard]] double compute_qwen_sigma_mu(int image_seq_len) noexcept;

/**
 * Resolves Viggle turbo schedule for Qwen Image 2.1.
 * Validates invariant constraints and computes shifted/custom sigmas.
 * Throws ScheduleContractException on contract violation.
 */
void resolve_qwen_turbo_schedule(QwenScheduleContract& contract);

/**
 * Adapter template to resolve turbo schedule on any contract type with compatible fields.
 */
template <typename TContract>
inline void resolve_qwen_turbo_schedule_adapter(TContract& contract) {
    if (contract.qwen_turbo_schedule.empty()) {
        return;
    }
    QwenScheduleContract adapted;
    adapted.qwen_turbo_schedule = contract.qwen_turbo_schedule;
    adapted.variant = contract.variant;
    adapted.loras.reserve(contract.loras.size());
    for (const auto& lora : contract.loras) {
        adapted.loras.push_back({lora.sha256, lora.multiplier});
    }
    adapted.cfg_scale = contract.cfg_scale;
    adapted.use_cfg = contract.use_cfg;
    adapted.negative_prompt = contract.negative_prompt;
    adapted.scheduler_wire = contract.scheduler_wire;
    adapted.prediction_wire = contract.prediction_wire;
    adapted.sample_method = static_cast<int>(contract.sample_method);
    adapted.task_mode = contract.task_mode;
    adapted.flow_shift = contract.flow_shift;
    adapted.steps = contract.steps;
    adapted.timetable_count = contract.timetable_count;
    adapted.width = contract.width;
    adapted.height = contract.height;

    resolve_qwen_turbo_schedule(adapted);

    contract.qwen_raw_sigmas = std::move(adapted.qwen_raw_sigmas);
    contract.qwen_shifted_sigmas = std::move(adapted.qwen_shifted_sigmas);
    contract.qwen_custom_sigmas = std::move(adapted.qwen_custom_sigmas);
    contract.qwen_sigma_mu = adapted.qwen_sigma_mu;
    contract.qwen_sigma_image_seq_len = adapted.qwen_sigma_image_seq_len;
}

} // namespace sd
} // namespace mca

#endif // MCA_SD_SCHEDULE_RESOLVER_HPP
