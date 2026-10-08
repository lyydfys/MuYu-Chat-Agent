#ifndef MCA_SD_LORA_ADAPTER_HPP
#define MCA_SD_LORA_ADAPTER_HPP

#include <cstdint>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "json.hpp"
#include "sd_contracts.hpp"
#include "stable-diffusion.h"

namespace mca {
namespace sd {

/**
 * Hard limits and validation constants for LoRA adapters.
 */
inline constexpr size_t kMaxLoraCount = 8u;
inline constexpr uint64_t kMaxLoraBytes = 2ull * 1024ull * 1024ull * 1024ull; // 2 GiB
inline constexpr uint64_t kMinLoraBytes = 16u;
inline constexpr double kMinLoraMultiplierAbs = 0.01;
inline constexpr double kMaxLoraMultiplierAbs = 4.0;

/**
 * Compatibility alias matching contract terminology.
 */
using ContractLoraAdapter = LoraAdapterSpec;

/**
 * Parsed LoRA specification bundle from request contract.
 */
struct LoraContractSpec {
    std::string lora_root_path;
    std::vector<LoraAdapterSpec> loras;
};

/**
 * LoRA execution evidence structure mirroring native runtime evidence fields.
 */
struct LoraExecutionEvidence {
    uint32_t requested_count{0};
    uint32_t loaded_count{0};
    uint32_t applied_count{0};
    uint64_t applied_tensor_count{0};
};

/**
 * Dedicated exception for LoRA contract validation errors.
 * Preserves error code, offending field name, and unsupported flag.
 */
class LoraContractException final : public std::runtime_error {
public:
    LoraContractException(std::string code,
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

[[noreturn]] inline void throw_invalid_lora(const std::string& field, const std::string& message) {
    throw LoraContractException("IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID", field, message, false);
}

[[noreturn]] inline void throw_unsupported_lora(const std::string& field, const std::string& message) {
    throw LoraContractException("IMAGE_NATIVE_EXECUTION_CONTRACT_UNSUPPORTED", field, message, true);
}

// ============================================================================
// Parameter Specification Parsing
// ============================================================================

/**
 * Parses a single LoRA adapter specification item from JSON.
 * Throws LoraContractException on validation failure.
 */
LoraAdapterSpec parse_lora_specification(const nlohmann::json& item);

/**
 * Parses complete LoRA specifications and root path from top-level params.
 * Throws LoraContractException on validation failure.
 */
LoraContractSpec parse_lora_specifications(const nlohmann::json& params);

/**
 * Convenience overload that writes directly to lora_root_path and loras vector.
 */
void parse_lora_specifications(
    const nlohmann::json& params,
    std::string& lora_root_path,
    std::vector<LoraAdapterSpec>& loras);

// ============================================================================
// File Validation
// ============================================================================

/**
 * Validates a single LoRA file on disk: existence, permissions, canonical path, size, extension.
 * Returns true if valid and sets canonical_path. Returns false and sets error otherwise.
 */
bool validate_lora_file(
    const std::string& raw_root,
    const LoraAdapterSpec& adapter,
    std::string& canonical_path,
    std::string& error);

/**
 * Batch-validates all LoRA adapters against the given root directory.
 * Returns true if all valid and fills canonical_paths. Returns false on first error.
 */
bool validate_lora_files(
    const std::string& raw_root,
    const std::vector<LoraAdapterSpec>& loras,
    std::vector<std::string>& canonical_paths,
    std::string& error);

// ============================================================================
// Native Descriptor Construction and Parameter Binding
// ============================================================================

/**
 * Constructs an array of native sd_lora_t descriptors pointing to canonical file paths.
 */
std::vector<sd_lora_t> build_native_loras(
    const std::vector<LoraAdapterSpec>& loras,
    const std::vector<std::string>& canonical_paths);

/**
 * Binds native LoRA array to a target struct exposing .loras and .lora_count fields.
 */
template <typename TParams>
inline void apply_loras_to_params(
    TParams& params,
    const std::vector<sd_lora_t>& native_loras) {
    params.loras = native_loras.empty() ? nullptr : native_loras.data();
    params.lora_count = static_cast<uint32_t>(native_loras.size());
}

// ============================================================================
// Execution Evidence Verification
// ============================================================================

/**
 * Verifies native LoRA execution evidence against requested adapter count.
 * Returns true on success, or false with error_code and error_message on discrepancy.
 */
bool verify_lora_execution_evidence(
    size_t expected_lora_count,
    const sd_image_execution_evidence_t& evidence,
    std::string& error_code,
    std::string& error_message);

bool verify_lora_execution_evidence(
    size_t expected_lora_count,
    const LoraExecutionEvidence& evidence,
    std::string& error_code,
    std::string& error_message);

// ============================================================================
// Effective Configuration and Evidence Serialization
// ============================================================================

nlohmann::json format_lora_effective_json(
    const std::vector<LoraAdapterSpec>& loras,
    const std::vector<sd_lora_t>& native_loras);

nlohmann::json format_lora_evidence_json(
    const sd_image_execution_evidence_t& evidence);

nlohmann::json format_lora_evidence_json(
    const LoraExecutionEvidence& evidence);

} // namespace sd
} // namespace mca

#endif // MCA_SD_LORA_ADAPTER_HPP
