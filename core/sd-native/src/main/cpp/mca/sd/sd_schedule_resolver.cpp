#include "sd_schedule_resolver.hpp"

#include <algorithm>
#include <cmath>

namespace mca {
namespace sd {

bool task_uses_input_image(const std::string& task_mode) noexcept {
    return task_mode == "img2img" ||
           task_mode == "inpaint" ||
           task_mode == "edit";
}

bool task_uses_init_image(const std::string& task_mode) noexcept {
    return task_mode == "img2img" || task_mode == "inpaint";
}

bool task_uses_mask_image(const std::string& task_mode) noexcept {
    return task_mode == "inpaint";
}

bool task_uses_control_image(const std::string& task_mode) noexcept {
    return task_mode == "control";
}

bool task_uses_input_image(ImageTaskMode mode) noexcept {
    return mode == ImageTaskMode::ImageToImage ||
           mode == ImageTaskMode::Inpaint;
}

int expected_denoising_step_count(
        int steps,
        double strength,
        const std::string& task_mode) noexcept {
    if (!task_uses_init_image(task_mode) || strength >= 1.0) {
        return steps;
    }
    const float str = static_cast<float>(strength);
    const int max_index = steps > 1 ? steps - 1 : 0;
    const int raw_begin = static_cast<int>(static_cast<float>(steps) * (1.0f - str));
    const int begin_index = std::clamp(raw_begin, 0, max_index);
    return steps - begin_index;
}

float backend_denoising_strength(
        int steps,
        double strength,
        const std::string& task_mode) noexcept {
    if (!task_uses_init_image(task_mode)) {
        return static_cast<float>(strength);
    }
    const int effective_steps = expected_denoising_step_count(steps, strength, task_mode);
    if (effective_steps >= steps) return 1.0f;
    return (static_cast<float>(effective_steps) - 0.5f) / static_cast<float>(steps);
}

bool is_official_viggle_v021_adapter(const std::string& sha256) noexcept {
    return sha256 == kViggleR128Sha256 || sha256 == kViggleR256Sha256;
}

std::vector<float> get_viggle_raw_sigmas(const std::string& schedule_name) {
    if (schedule_name == "viggle_v021_4") {
        return {1.0f, 0.75f, 0.5f, 0.25f};
    } else if (schedule_name == "viggle_v021_5") {
        return {1.0f, 0.875f, 0.75f, 0.5f, 0.25f};
    } else if (schedule_name == "viggle_v021_6") {
        return {1.0f, 0.9375f, 0.875f, 0.75f, 0.5f, 0.25f};
    } else if (schedule_name == "viggle_v021_7") {
        return {1.0f, 0.9583f, 0.9167f, 0.875f, 0.75f, 0.5f, 0.25f};
    } else if (schedule_name == "viggle_v021_8") {
        // The publisher offers this denser eight-step sequence for text-heavy prompts.
        return {1.0f, 0.9375f, 0.875f, 0.75f, 0.625f, 0.5f, 0.25f, 0.125f};
    }
    throw_invalid_schedule(
            "qwenTurboSchedule",
            "unknown Viggle v0.2.1 schedule; expected viggle_v021_4 through viggle_v021_8");
}

double compute_qwen_sigma_mu(int image_seq_len) noexcept {
    constexpr double base_image_seq_len = 256.0;
    constexpr double max_image_seq_len = 8192.0;
    constexpr double base_shift = 0.5;
    constexpr double max_shift = 0.9;
    return base_shift +
            (max_shift - base_shift) *
                    (static_cast<double>(image_seq_len) - base_image_seq_len) /
                    (max_image_seq_len - base_image_seq_len);
}

void resolve_qwen_turbo_schedule(QwenScheduleContract& contract) {
    if (contract.qwen_turbo_schedule.empty()) {
        return;
    }
    if (contract.variant != kQwenTargetVariant) {
        throw_unsupported_schedule(
                "qwenTurboSchedule",
                "Viggle schedules are supported only for variant=QWEN_IMAGE_21");
    }
    if (contract.loras.size() != 1u) {
        throw_invalid_schedule(
                "qwenTurboSchedule",
                "Viggle v0.2.1 schedule requires exactly one selected LoRA adapter");
    }
    const ScheduleLoraSpec& adapter = contract.loras.front();
    if (!is_official_viggle_v021_adapter(adapter.sha256)) {
        throw_invalid_schedule(
                "qwenTurboSchedule",
                "Viggle v0.2.1 schedule requires the publisher's exact r128 or r256 adapter");
    }
    if (adapter.multiplier != 1.0) {
        throw_invalid_schedule(
                "loras.multiplier",
                "Viggle v0.2.1 schedule requires the published LoRA strength 1.0");
    }
    if (std::fabs(contract.cfg_scale - 1.0) > 1e-12 || contract.use_cfg ||
        !contract.negative_prompt.empty()) {
        throw_invalid_schedule(
                "qwenTurboSchedule",
                "Viggle schedules require cfgScale=1, useCfg=false, and an empty negativePrompt");
    }
    if (contract.scheduler_wire != "FLOW_MATCH" ||
        contract.prediction_wire != "FLOW" ||
        contract.sample_method != kEulerSampleMethod ||
        contract.task_mode != "text_to_image" ||
        contract.flow_shift >= 0.0) {
        throw_invalid_schedule(
                "qwenTurboSchedule",
                "Viggle schedules require Qwen text-to-image Euler Flow with dynamic flow shift");
    }

    contract.qwen_raw_sigmas = get_viggle_raw_sigmas(contract.qwen_turbo_schedule);

    const int expected_steps = static_cast<int>(contract.qwen_raw_sigmas.size());
    if (contract.steps != expected_steps || contract.timetable_count != expected_steps) {
        throw_invalid_schedule(
                "steps",
                "Viggle schedules require the exact step count declared by the schedule");
    }

    // Qwen Image 2.1 uses a 16x spatial compression for the DiT sequence. The
    // Viggle scheduler maps that sequence length to a logit shift, then applies
    // the same exponential time shift to each published raw sigma.
    const int image_seq_len = (contract.height / 16) * (contract.width / 16);
    if (image_seq_len <= 0) {
        throw_invalid_schedule("qwenTurboSchedule", "image dimensions produce no Qwen latent tokens");
    }

    const double mu = compute_qwen_sigma_mu(image_seq_len);
    const double exp_mu = std::exp(mu);
    contract.qwen_sigma_mu = mu;
    contract.qwen_sigma_image_seq_len = image_seq_len;
    contract.qwen_shifted_sigmas.clear();
    contract.qwen_shifted_sigmas.reserve(contract.qwen_raw_sigmas.size());
    for (const float raw_sigma : contract.qwen_raw_sigmas) {
        const double raw = static_cast<double>(raw_sigma);
        const double shifted = raw <= 0.0
                ? 0.0
                : exp_mu / (exp_mu + (1.0 / raw - 1.0));
        contract.qwen_shifted_sigmas.push_back(static_cast<float>(shifted));
    }
    contract.qwen_custom_sigmas = contract.qwen_shifted_sigmas;
    contract.qwen_custom_sigmas.push_back(0.0f);
}

} // namespace sd
} // namespace mca
