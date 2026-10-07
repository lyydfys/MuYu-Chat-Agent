#ifndef MCA_SD_INPUT_CONTRACT_HPP
#define MCA_SD_INPUT_CONTRACT_HPP

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "mca/contract/error_envelope.hpp"
#include "mca/contract/request_identity.hpp"
#include "sd_contracts.hpp"

namespace mca {
namespace sd {

/**
 * Hard limits for input validation to protect memory and native engine stability.
 */
inline constexpr uint64_t kMaxInputImageBytes = 32u * 1024u * 1024u;   // 32 MB
inline constexpr uint64_t kMaxInputImagePixels = 64u * 1024u * 1024u; // 64 MP
inline constexpr int32_t kMaxBatchCount = 8;
inline constexpr int32_t kMaxDimension = 4096;
inline constexpr int32_t kMaxSteps = 150;
inline constexpr double kMaxCfgScale = 30.0;

/**
 * Validates image input contracts without performing device-level admission rejection.
 * Universal device admission invariant: unknown chipset or device profile is NOT an error.
 */
class SdInputValidator {
public:
    SdInputValidator() = default;
    ~SdInputValidator() = default;

    /**
     * Validates full ImageInputContract.
     * Returns nullopt on success, or an ErrorEnvelope on validation error.
     */
    [[nodiscard]] std::optional<mca::contract::ErrorEnvelope> validate(
        const ImageInputContract& contract,
        const mca::contract::RequestIdentity& identity
    ) const;

    /**
     * Validates prompt text and length limits.
     */
    [[nodiscard]] std::optional<mca::contract::ErrorEnvelope> validate_prompt(
        const std::string& prompt,
        const mca::contract::RequestIdentity& identity
    ) const;

    /**
     * Validates generation dimensions and alignment (multiples of 8).
     */
    [[nodiscard]] std::optional<mca::contract::ErrorEnvelope> validate_dimensions(
        int32_t width,
        int32_t height,
        const mca::contract::RequestIdentity& identity
    ) const;

    /**
     * Validates bounded file size, existence, and optional expected SHA-256.
     */
    [[nodiscard]] std::optional<mca::contract::ErrorEnvelope> validate_image_file(
        const std::string& path,
        const std::string& expected_sha256,
        const std::string& role_name,
        const mca::contract::RequestIdentity& identity
    ) const;

    /**
     * Computes SHA-256 hex string for an in-memory buffer.
     */
    [[nodiscard]] static std::string compute_sha256(const std::vector<uint8_t>& bytes);

    /**
     * Computes SHA-256 hex string for a file on disk.
     */
    [[nodiscard]] static std::string compute_file_sha256(const std::string& path);
};

} // namespace sd
} // namespace mca

#endif // MCA_SD_INPUT_CONTRACT_HPP
