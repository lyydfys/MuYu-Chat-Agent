#ifndef MCA_SD_REQUEST_HPP
#define MCA_SD_REQUEST_HPP

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>

#include "mca/contract/callback_event.hpp"
#include "mca/contract/error_envelope.hpp"
#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"
#include "sd_contracts.hpp"

namespace mca {
namespace sd {

/**
 * Lifecycle and execution state manager for a Stable Diffusion image request.
 * Strictly decoupled from JNI objects (complies with MCA S-grade architecture invariant).
 */
class SdRequest {
public:
    explicit SdRequest(
        mca::contract::RequestIdentity identity,
        ImageInputContract input_contract
    );
    ~SdRequest() = default;

    // Identity and Input Contract
    [[nodiscard]] const mca::contract::RequestIdentity& get_identity() const noexcept {
        return identity_;
    }
    [[nodiscard]] const ImageInputContract& get_input_contract() const noexcept {
        return input_contract_;
    }
    [[nodiscard]] ImageExecutionEvidence& get_execution_evidence() noexcept {
        return evidence_;
    }
    [[nodiscard]] const ImageExecutionEvidence& get_execution_evidence() const noexcept {
        return evidence_;
    }

    // State Machine
    [[nodiscard]] mca::contract::RequestState get_state() const noexcept;
    bool transition_to(mca::contract::RequestState target_state);

    /**
     * Attempts exactly-once terminal transition.
     * Subsequent calls return false.
     */
    bool mark_terminal(
        mca::contract::RequestState terminal_state,
        std::optional<mca::contract::ErrorEnvelope> error = std::nullopt
    );
    [[nodiscard]] bool is_terminal() const noexcept;

    // Cancellation Management
    void request_cancel() noexcept;
    [[nodiscard]] bool is_cancel_requested() const noexcept;

    // Error Context
    [[nodiscard]] std::optional<mca::contract::ErrorEnvelope> get_last_error() const noexcept;
    void set_last_error(mca::contract::ErrorEnvelope error) noexcept;

    // Monotonic Event Sequence and Factory
    [[nodiscard]] int64_t next_sequence() noexcept;
    [[nodiscard]] int64_t get_current_sequence() const noexcept;

    [[nodiscard]] mca::contract::CallbackEvent create_progress_event(
        float current,
        float total,
        const std::string& stage,
        const std::string& detail
    );

    [[nodiscard]] mca::contract::CallbackEvent create_preview_event(
        int32_t frame_index,
        int32_t width,
        int32_t height,
        const std::string& format,
        const std::string& data_sha256
    );

    [[nodiscard]] mca::contract::CallbackEvent create_state_event(
        mca::contract::RequestState previous,
        mca::contract::RequestState current
    );

    [[nodiscard]] mca::contract::CallbackEvent create_terminal_event(
        int64_t elapsed_ms
    );

    [[nodiscard]] mca::contract::CallbackEvent create_error_event(
        const mca::contract::ErrorEnvelope& error
    );

    // Duration and Timestamp
    [[nodiscard]] int64_t get_elapsed_ms() const noexcept;

private:
    mca::contract::RequestIdentity identity_;
    ImageInputContract input_contract_;
    ImageExecutionEvidence evidence_;

    std::atomic<mca::contract::RequestState> state_{mca::contract::RequestState::Created};
    std::atomic<bool> cancel_requested_{false};
    std::atomic<bool> is_terminal_{false};
    std::atomic<int64_t> sequence_{0};

    std::chrono::steady_clock::time_point start_time_;
    std::optional<mca::contract::ErrorEnvelope> last_error_;
    mutable std::mutex mutex_;
};

} // namespace sd
} // namespace mca

#endif // MCA_SD_REQUEST_HPP
