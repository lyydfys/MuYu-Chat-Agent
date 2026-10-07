#ifndef MCA_MNN_CONTRACTS_HPP
#define MCA_MNN_CONTRACTS_HPP

#include <cstdint>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <optional>

#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"
#include "mca/contract/error_envelope.hpp"
#include "mca/contract/callback_event.hpp"

namespace mca {
namespace mnn {

/**
 * Supported execution backends for MNN native inference.
 */
enum class MnnBackendType : uint8_t {
    Cpu = 0,
    OpenCL = 1,
    Vulkan = 2,
    Auto = 3
};

/**
 * Numerical precision mode for tensor execution.
 */
enum class MnnPrecision : uint8_t {
    Normal = 0,
    High = 1,
    Low = 2
};

/**
 * Device power and scheduling preference.
 */
enum class MnnPowerMode : uint8_t {
    Normal = 0,
    HighPerformance = 1,
    PowerSave = 2
};

/**
 * Text sampling algorithms supported by the MNN engine.
 */
enum class MnnSamplerType : uint8_t {
    Greedy = 0,
    TopK = 1,
    TopP = 2,
    MinP = 3,
    Mixed = 4
};

/**
 * Configuration parameters for token generation and decoding.
 */
struct MnnSamplingConfig {
    float temperature{0.6f};
    int32_t top_k{20};
    float top_p{0.95f};
    float min_p{0.0f};
    float repetition_penalty{1.0f};
    float presence_penalty{0.0f};
    float frequency_penalty{0.0f};
    int64_t seed{-1};
    MnnSamplerType sampler_type{MnnSamplerType::Mixed};
    std::vector<std::string> mixed_samplers{
        "topK", "tfs", "typical", "topP", "min_p", "temperature"
    };

    [[nodiscard]] bool validate(std::string* error_msg = nullptr) const noexcept {
        if (temperature < 0.0f || temperature > 100.0f) {
            if (error_msg) *error_msg = "temperature out of range [0.0, 100.0]";
            return false;
        }
        if (top_k < 0) {
            if (error_msg) *error_msg = "top_k must be non-negative";
            return false;
        }
        if (top_p < 0.0f || top_p > 1.0f) {
            if (error_msg) *error_msg = "top_p out of range [0.0, 1.0]";
            return false;
        }
        if (min_p < 0.0f || min_p > 1.0f) {
            if (error_msg) *error_msg = "min_p out of range [0.0, 1.0]";
            return false;
        }
        if (repetition_penalty < 0.0f || repetition_penalty > 100.0f) {
            if (error_msg) *error_msg = "repetition_penalty out of range [0.0, 100.0]";
            return false;
        }
        return true;
    }
};

/**
 * Runtime execution settings and device tuning configuration.
 */
struct MnnRuntimeConfig {
    MnnBackendType backend_type{MnnBackendType::Auto};
    int32_t threads{4};
    MnnPrecision precision{MnnPrecision::High};
    MnnPowerMode power_mode{MnnPowerMode::Normal};
    int32_t memory_mode{0}; // 0 = standard, 1 = low_memory
    bool use_mmap{true};
    bool reuse_kv{true};
    // Device-local tuning values (strictly advisory, never admission blockers)
    std::map<std::string, std::string> device_local_tuning;
};

/**
 * Long-lived model metadata and structural capability declarations.
 */
struct MnnModelMetadata {
    std::string model_id;
    std::string model_path;
    std::string model_name;
    int32_t context_window{4096};
    int32_t vocab_size{32000};
    int32_t embedding_dim{4096};
    bool supports_vision{false};
    bool supports_audio{false}; // Disabled as per product capability policy
    std::string chat_template;
    std::vector<std::string> stop_tokens{
        "<|im_end|>", "<|endoftext|>", "</s>"
    };
};

/**
 * Chat conversation message roles.
 */
enum class MnnMessageRole : uint8_t {
    System = 0,
    User = 1,
    Assistant = 2,
    Tool = 3
};

/**
 * Single turn message in a conversation.
 */
struct MnnChatMessage {
    MnnMessageRole role{MnnMessageRole::User};
    std::string content;
    std::vector<std::string> image_paths;
    std::string tool_call_id;
};

/**
 * Incoming chat generation request parameters.
 */
struct MnnChatRequest {
    std::string prompt;
    std::vector<MnnChatMessage> messages;
    std::vector<std::string> image_paths;
    MnnSamplingConfig sampling;
    int32_t max_new_tokens{2048};
    int32_t chunk_size{512}; // 0 for multimodal to preserve atomic embeddings
    std::vector<std::string> stop_sequences;
    bool stream{true};

    [[nodiscard]] bool is_multimodal() const noexcept {
        if (!image_paths.empty()) return true;
        for (const auto& msg : messages) {
            if (!msg.image_paths.empty()) return true;
        }
        return false;
    }
};

/**
 * Execution metrics collected at the end of token generation.
 */
struct MnnGenerationStats {
    int64_t prompt_tokens{0};
    int64_t completion_tokens{0};
    int64_t prefill_time_ms{0};
    int64_t decode_time_ms{0};
    float tokens_per_second{0.0f};
    std::string stop_reason;
};

/**
 * Hardware capability probe results.
 * Note: Missing or unknown profiles are advisory only. Universal device admission
 * ensures fallback to generic compatible path (e.g. CPU) rather than rejection.
 */
struct HardwareProbeAdvisory {
    std::string chip_family;
    std::string gpu_renderer;
    std::string driver_version;
    MnnBackendType recommended_backend{MnnBackendType::Cpu};
    bool is_certified_profile{false};
    std::string advisory_notes;

    [[nodiscard]] static constexpr bool universal_device_admission_enforced() noexcept {
        return true;
    }
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_CONTRACTS_HPP
