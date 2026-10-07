#ifndef MCA_SD_OUTPUT_TRANSACTION_HPP
#define MCA_SD_OUTPUT_TRANSACTION_HPP

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "mca/contract/error_envelope.hpp"
#include "mca/contract/request_identity.hpp"

namespace mca {
namespace sd {

/**
 * Result of an atomic output publication transaction.
 */
struct OutputTransactionResult {
    bool success{false};
    std::string target_path;
    std::string sha256_hex;
    uint64_t bytes_written{0};
    std::optional<mca::contract::ErrorEnvelope> error;
};

/**
 * Atomic output transaction manager for generated image artifacts.
 * Guarantees atomic publication via temp file + checksum + rename,
 * and automatic rollback on failure, cancellation, or crash.
 */
class SdOutputTransaction {
public:
    explicit SdOutputTransaction(
        std::string target_path,
        mca::contract::RequestIdentity identity
    );
    ~SdOutputTransaction();

    // Disable copy, allow move
    SdOutputTransaction(const SdOutputTransaction&) = delete;
    SdOutputTransaction& operator=(const SdOutputTransaction&) = delete;
    SdOutputTransaction(SdOutputTransaction&&) noexcept;
    SdOutputTransaction& operator=(SdOutputTransaction&&) noexcept;

    // Transaction lifecycle
    [[nodiscard]] bool begin();
    [[nodiscard]] bool write_data(const uint8_t* data, size_t size);
    [[nodiscard]] bool write_pixels_as_png(
        int32_t width,
        int32_t height,
        int32_t channels,
        const uint8_t* pixels
    );

    /**
     * Atomically commits the temporary file to target_path.
     * Checks cancellation flag before final rename.
     */
    [[nodiscard]] OutputTransactionResult commit(
        const std::function<bool()>& cancel_checker = nullptr
    );

    /**
     * Rolls back intermediate artifacts and deletes the partial file.
     * Leaves existing target intact.
     */
    void rollback() noexcept;

    [[nodiscard]] bool is_committed() const noexcept { return is_committed_; }
    [[nodiscard]] const std::string& get_target_path() const noexcept { return target_path_; }
    [[nodiscard]] const std::string& get_partial_path() const noexcept { return partial_path_; }

private:
    std::string target_path_;
    std::string partial_path_;
    mca::contract::RequestIdentity identity_;
    bool is_active_{false};
    bool is_committed_{false};
    uint64_t bytes_written_{0};
};

} // namespace sd
} // namespace mca

#endif // MCA_SD_OUTPUT_TRANSACTION_HPP
