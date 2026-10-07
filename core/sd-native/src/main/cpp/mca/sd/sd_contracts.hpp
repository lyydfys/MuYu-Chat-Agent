#ifndef MCA_SD_CONTRACTS_HPP
#define MCA_SD_CONTRACTS_HPP

#include <algorithm>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "mca/contract/callback_event.hpp"
#include "mca/contract/error_envelope.hpp"
#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"
#include "mca/contract/stable_error_code.hpp"

namespace mca {
namespace sd {

/**
 * Task mode for Stable Diffusion generation.
 */
enum class ImageTaskMode : uint8_t {
    TextToImage = 0,
    ImageToImage = 1,
    Inpaint = 2,
    ControlNet = 3
};

/**
 * Sampling algorithms supported across backends.
 */
enum class SampleMethod : uint8_t {
    Euler = 0,
    EulerA = 1,
    Heun = 2,
    DPM2 = 3,
    DPMPP2M = 4,
    DPMPP2S_A = 5,
    LCM = 6,
    DDIM = 7,
    Auto = 8
};

/**
 * Noise scheduling timetable type.
 */
enum class SchedulerType : uint8_t {
    Default = 0,
    Discrete = 1,
    Karras = 2,
    Exponential = 3,
    Ays = 4,
    Gits = 5,
    Turbo = 6
};

/**
 * Specification for a LoRA adapter binding.
 */
struct LoraAdapterSpec {
    std::string id;
    std::string name;
    std::string path;
    std::string sha256;
    uint64_t size_bytes{0};
    double multiplier{1.0};
};

/**
 * Specification for an embedding / Textual Inversion token.
 */
struct TextualInversionSpec {
    std::string id;
    std::string name;
    std::string trigger;
    std::string path;
    std::string sha256;
    std::string format;
    std::string model_fingerprint;
    std::string tokenizer_fingerprint;
    std::string profile_id;
    int32_t profile_revision{0};
    uint64_t size_bytes{0};
};

/**
 * Tiling parameters for memory-bounded VAE encoding/decoding.
 */
struct VaeTilingParams {
    bool enabled{false};
    int32_t tile_size_x{512};
    int32_t tile_size_y{512};
    double target_overlap{0.125};
    double rel_size_x{1.0};
    double rel_size_y{1.0};
};

/**
 * Parameters for ControlNet conditioning.
 */
struct ControlNetParams {
    bool enabled{false};
    std::string control_image_path;
    std::string control_image_sha256;
    double control_strength{1.0};
};

/**
 * Ultrafix super-resolution refinement parameters.
 */
struct UltrafixParams {
    bool enabled{false};
    int32_t target_width{0};
    int32_t target_height{0};
    double strength{0.35};
    int32_t inversion_steps{10};
    int32_t refinement_steps{15};
    int32_t tile_size{512};
    double overlap{0.125};
};

/**
 * Complete immutable or validated input contract for image requests.
 * Universal device admission invariant: No allowlist / chipset restriction fields.
 */
struct ImageInputContract {
    std::string profile_id;
    int32_t profile_revision{0};
    std::string model_fingerprint;

    std::string prompt;
    std::string negative_prompt;

    int32_t width{512};
    int32_t height{512};
    int64_t seed{-1};
    int32_t steps{20};
    double cfg_scale{7.0};
    double strength{1.0};

    int32_t clip_skip{-1};
    int32_t batch_count{1};

    ImageTaskMode task_mode{ImageTaskMode::TextToImage};
    SampleMethod sample_method{SampleMethod::EulerA};
    SchedulerType scheduler{SchedulerType::Default};

    std::string input_image_path;
    std::string input_image_sha256;

    std::string mask_image_path;
    std::string mask_image_sha256;

    ControlNetParams control_net;
    VaeTilingParams vae_tiling;
    UltrafixParams ultrafix;

    std::vector<LoraAdapterSpec> loras;
    std::vector<TextualInversionSpec> textual_inversions;

    int32_t preview_interval{0};
    std::string preview_mode{"none"};

    int32_t threads{4};

    std::string model_path;
    std::string bundle_root;
    std::string output_path;
};

/**
 * Result data for one generated image in a batch.
 */
struct GeneratedImageResult {
    int32_t batch_index{0};
    int32_t width{0};
    int32_t height{0};
    int32_t channels{3};
    std::vector<uint8_t> pixel_data;
    std::string file_path;
    std::string sha256_hex;
    uint64_t file_size_bytes{0};
};

/**
 * Execution evidence documenting real native execution metrics.
 */
struct ImageExecutionEvidence {
    int32_t contract_version{5};
    int32_t actual_denoising_step_count{0};
    int32_t configured_sample_steps{0};
    int32_t actual_control_net_compute_attempt_count{0};
    int32_t actual_vae_encode_tiling_count{0};
    int32_t actual_vae_decode_tiling_count{0};
    float backend_strength{1.0f};
    int64_t elapsed_ms{0};
    uint64_t peak_memory_bytes{0};

    // Universal device admission proof
    bool device_admitted_without_allowlist{true};
};

// ============================================================================
// Helper Invariants
// ============================================================================

[[nodiscard]] inline bool task_uses_init_image(ImageTaskMode mode) noexcept {
    return mode == ImageTaskMode::ImageToImage || mode == ImageTaskMode::Inpaint;
}

[[nodiscard]] inline bool task_uses_mask_image(ImageTaskMode mode) noexcept {
    return mode == ImageTaskMode::Inpaint;
}

[[nodiscard]] inline bool task_uses_control_image(ImageTaskMode mode) noexcept {
    return mode == ImageTaskMode::ControlNet;
}

#ifndef NOMINMAX
#define NOMINMAX
#endif

[[nodiscard]] inline int32_t expected_denoising_step_count(
    int32_t steps,
    double strength,
    ImageTaskMode mode
) noexcept {
    if (!task_uses_init_image(mode) || strength >= 1.0) {
        return steps;
    }
    const float str = static_cast<float>(strength);
    const int32_t max_idx = steps > 1 ? steps - 1 : 0;
    const int32_t raw_begin = static_cast<int32_t>(static_cast<float>(steps) * (1.0f - str));
    const int32_t begin_index = (raw_begin < 0) ? 0 : ((raw_begin > max_idx) ? max_idx : raw_begin);
    const int32_t remaining = steps - begin_index;
    return (remaining > 1) ? remaining : 1;
}

[[nodiscard]] inline float backend_denoising_strength(
    int32_t steps,
    double strength,
    ImageTaskMode mode
) noexcept {
    if (!task_uses_init_image(mode)) {
        return static_cast<float>(strength);
    }
    const int32_t effective_steps = expected_denoising_step_count(steps, strength, mode);
    if (effective_steps >= steps) {
        return 1.0f;
    }
    return (static_cast<float>(effective_steps) - 0.5f) / static_cast<float>(steps);
}

[[nodiscard]] inline const char* image_task_mode_to_string(ImageTaskMode mode) noexcept {
    switch (mode) {
        case ImageTaskMode::TextToImage: return "text_to_image";
        case ImageTaskMode::ImageToImage: return "image_to_image";
        case ImageTaskMode::Inpaint: return "inpaint";
        case ImageTaskMode::ControlNet: return "controlnet";
    }
    return "unknown";
}

[[nodiscard]] inline ImageTaskMode parse_task_mode(const std::string& str) noexcept {
    if (str == "img2img" || str == "image_to_image") {
        return ImageTaskMode::ImageToImage;
    }
    if (str == "inpaint") {
        return ImageTaskMode::Inpaint;
    }
    if (str == "control" || str == "controlnet") {
        return ImageTaskMode::ControlNet;
    }
    return ImageTaskMode::TextToImage;
}

} // namespace sd
} // namespace mca

#endif // MCA_SD_CONTRACTS_HPP
