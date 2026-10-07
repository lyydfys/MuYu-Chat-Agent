#ifndef MCA_QNN_SDXL_CONTRACTS_HPP
#define MCA_QNN_SDXL_CONTRACTS_HPP

#include <cstdint>
#include <string>
#include <vector>
#include <optional>
#include <cmath>
#include <algorithm>
#include "mca/contract/stable_error_code.hpp"
#include "mca/contract/error_envelope.hpp"
#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"

namespace mca {
namespace qnn {

/**
 * Discrete lifecycle phases of a QNN SDXL generation pipeline.
 */
enum class SdxlPhase : uint8_t {
    Idle = 0,
    Preparing = 1,
    Loading = 2,
    ContextLock = 3,
    ContextCreate = 4,
    TextEncoder1 = 5,
    TextEncoder2 = 6,
    UnetLoop = 7,
    VaeDecode = 8,
    PostProcess = 9,
    OutputTransaction = 10,
    Cancelling = 11,
    Cancelled = 12,
    Completed = 13,
    Failed = 14
};

[[nodiscard]] inline const char* sdxl_phase_to_string(SdxlPhase phase) noexcept {
    switch (phase) {
        case SdxlPhase::Idle: return "Idle";
        case SdxlPhase::Preparing: return "Preparing";
        case SdxlPhase::Loading: return "Loading";
        case SdxlPhase::ContextLock: return "ContextLock";
        case SdxlPhase::ContextCreate: return "ContextCreate";
        case SdxlPhase::TextEncoder1: return "TextEncoder1";
        case SdxlPhase::TextEncoder2: return "TextEncoder2";
        case SdxlPhase::UnetLoop: return "UnetLoop";
        case SdxlPhase::VaeDecode: return "VaeDecode";
        case SdxlPhase::PostProcess: return "PostProcess";
        case SdxlPhase::OutputTransaction: return "OutputTransaction";
        case SdxlPhase::Cancelling: return "Cancelling";
        case SdxlPhase::Cancelled: return "Cancelled";
        case SdxlPhase::Completed: return "Completed";
        case SdxlPhase::Failed: return "Failed";
    }
    return "Unknown";
}

enum class VaeScalingLocation : uint8_t {
    HostBeforeGraph = 0,
    InsideGraph = 1,
    HostAfterGraph = 2
};

enum class InpaintTopology : uint8_t {
    None = 0,
    LatentBlend4 = 1,
    ConcatenatedLatent9 = 2,
    SeparateMaskInput = 3
};

enum class ControlNetMode : uint8_t {
    None = 0,
    Canny = 1,
    Depth = 2,
    Openpose = 3
};

/**
 * Semantic input contract for an SDXL image generation request.
 */
struct SdxlExecutionContract {
    std::string prompt;
    std::string negative_prompt;
    uint32_t width{1024};
    uint32_t height{1024};
    uint32_t steps{20};
    uint64_t seed{42};
    float guidance_scale{7.5f};
    double vae_scaling_factor{0.13025};
    VaeScalingLocation vae_scaling_location{VaeScalingLocation::HostBeforeGraph};
    std::string output_path;
    std::string journal_path;
    bool enable_preview{false};
    int32_t preview_interval{1};
    InpaintTopology inpaint_topology{InpaintTopology::None};
    std::string inpaint_source_path;
    std::string inpaint_mask_path;
    bool controlnet_enabled{false};
    ControlNetMode controlnet_mode{ControlNetMode::None};
    std::string controlnet_image_path;
    float controlnet_strength{1.0f};

    [[nodiscard]] bool validate(std::string* error = nullptr) const {
        if (prompt.empty()) {
            if (error) *error = "Prompt cannot be empty";
            return false;
        }
        if (width == 0 || height == 0 || (width % 8 != 0) || (height % 8 != 0)) {
            if (error) *error = "Dimensions must be positive multiples of 8";
            return false;
        }
        if (steps == 0 || steps > 150) {
            if (error) *error = "Steps must be in range [1, 150]";
            return false;
        }
        if (guidance_scale < 1.0f || guidance_scale > 30.0f) {
            if (error) *error = "Guidance scale must be in range [1.0, 30.0]";
            return false;
        }
        if (!std::isfinite(vae_scaling_factor) || vae_scaling_factor <= 0.0) {
            if (error) *error = "VAE scaling factor must be positive finite";
            return false;
        }
        if (enable_preview && (preview_interval < 1 || preview_interval > 10)) {
            if (error) *error = "Preview interval must be between 1 and 10";
            return false;
        }
        if (controlnet_enabled && controlnet_mode == ControlNetMode::None) {
            if (error) *error = "ControlNet mode cannot be None when enabled";
            return false;
        }
        return true;
    }
};

/**
 * Output artifact generated during a specific phase of execution.
 */
struct SdxlPhaseArtifact {
    std::string phase_name;
    std::string file_path;
    std::vector<uint32_t> tensor_shape;
    std::string data_sha256;
    int64_t byte_count{0};
};

/**
 * Inpaint pixel inputs and mask validation metadata.
 */
struct SdxlInpaintArtifacts {
    std::vector<float> source_nchw;
    std::vector<float> full_mask;
    std::string source_sha256;
    std::string mask_sha256;
    int64_t repaint_pixel_count{0};
};

/**
 * Progress snapshot emitted across phase boundaries and scheduler steps.
 */
struct QnnExecutionProgress {
    SdxlPhase phase{SdxlPhase::Idle};
    int32_t current_step{0};
    int32_t total_steps{0};
    int64_t elapsed_ms{0};
    uint32_t stage_mask{0};
    std::string stage_name;
    std::string preview_path;
};

/**
 * Intermediate preview image descriptor.
 */
struct QnnImagePreviewSpec {
    int32_t frame_index{0};
    int32_t step{0};
    int32_t width{0};
    int32_t height{0};
    std::string format{"image/png"};
    std::string file_path;
    std::string data_sha256;
};

/**
 * Error code mapping specification from QNN / system error codes to ErrorEnvelope.
 * Universal device admission invariant: unknown devices never trigger capability rejection.
 */
inline contract::ErrorEnvelope map_qnn_status_to_envelope(
    int32_t qnn_status,
    const std::string& context_message,
    const std::optional<contract::RequestIdentity>& identity = std::nullopt
) {
    contract::ErrorEnvelope envelope;
    envelope.identity = identity;
    envelope.native_code = qnn_status;
    envelope.public_message = context_message;

    switch (qnn_status) {
        case 0: // QNN_SUCCESS
            envelope.code = contract::StableErrorCode::INT_UNEXPECTED_EXCEPTION;
            envelope.category = contract::ErrorCategory::Internal;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::Never;
            break;
        case 1: // General error
            envelope.code = contract::StableErrorCode::INT_UNEXPECTED_EXCEPTION;
            envelope.category = contract::ErrorCategory::Internal;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
        case 2: // Invalid argument
            envelope.code = contract::StableErrorCode::VAL_ILLEGAL_ARGUMENT;
            envelope.category = contract::ErrorCategory::Validation;
            envelope.phase = contract::ErrorPhase::Validate;
            envelope.retryability = contract::Retryability::Never;
            break;
        case 3: // Invalid handle
            envelope.code = contract::StableErrorCode::NAT_EXECUTION_FAULT;
            envelope.category = contract::ErrorCategory::Native;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
        case 4: // Memory allocation failed
            envelope.code = contract::StableErrorCode::RT_OUT_OF_MEMORY;
            envelope.category = contract::ErrorCategory::Runtime;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
        case 5: // Unsupported feature
            envelope.code = contract::StableErrorCode::CAP_UNSUPPORTED_OPERATION;
            envelope.category = contract::ErrorCategory::Capability;
            envelope.phase = contract::ErrorPhase::Prepare;
            envelope.retryability = contract::Retryability::Never;
            break;
        case 100: // Backend initialization error
        case 101:
            envelope.code = contract::StableErrorCode::RT_INITIALIZATION_FAILED;
            envelope.category = contract::ErrorCategory::Runtime;
            envelope.phase = contract::ErrorPhase::Load;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
        case 200: // Context creation error
        case 201:
            envelope.code = contract::StableErrorCode::RT_INITIALIZATION_FAILED;
            envelope.category = contract::ErrorCategory::Runtime;
            envelope.phase = contract::ErrorPhase::Load;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
        case 300: // Graph execution error
        case 301:
            envelope.code = contract::StableErrorCode::NAT_EXECUTION_FAULT;
            envelope.category = contract::ErrorCategory::Native;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
        case 700: // Cancelled
            envelope.code = contract::StableErrorCode::CNC_USER_CANCELLED;
            envelope.category = contract::ErrorCategory::Cancel;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::Never;
            break;
        default:
            envelope.code = contract::StableErrorCode::INT_UNEXPECTED_EXCEPTION;
            envelope.category = contract::ErrorCategory::Internal;
            envelope.phase = contract::ErrorPhase::Execute;
            envelope.retryability = contract::Retryability::RetryAfterRecovery;
            break;
    }
    return envelope;
}

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_SDXL_CONTRACTS_HPP
