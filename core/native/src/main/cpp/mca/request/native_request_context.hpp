#ifndef MCA_REQUEST_NATIVE_REQUEST_CONTEXT_HPP
#define MCA_REQUEST_NATIVE_REQUEST_CONTEXT_HPP

#include <string>
#include <memory>
#include <atomic>
#include <mutex>
#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"

namespace mca {
namespace request {

/**
 * Request execution context for a single command invocation.
 *
 * Exclusively tracks request inputs, cancellation flags, and exactly-once terminal state.
 * Does NOT own long-lived engine or session resources.
 */
class NativeRequestContext {
public:
    explicit NativeRequestContext(contract::RequestIdentity identity);
    ~NativeRequestContext();

    [[nodiscard]] const contract::RequestIdentity& get_identity() const noexcept { return identity_; }
    [[nodiscard]] contract::RequestState get_state() const noexcept;

    void request_cancel() noexcept;
    [[nodiscard]] bool is_cancel_requested() const noexcept;

    /** Attempts a valid non-terminal lifecycle transition. */
    bool transition_to(contract::RequestState target_state) noexcept;

    /**
     * Attempts to transition to a terminal state.
     * Invariant: Terminal exactly-once. Repeated calls return false.
     */
    bool mark_terminal(contract::RequestState terminal_state) noexcept;
    [[nodiscard]] bool is_terminal() const noexcept;

    void update_sequence(int64_t seq) noexcept;
    [[nodiscard]] int64_t get_last_sequence() const noexcept;

private:
    contract::RequestIdentity identity_;
    std::atomic<contract::RequestState> state_{contract::RequestState::Created};
    std::atomic<bool> cancel_requested_{false};
    std::atomic<int64_t> last_sequence_{0};
    std::atomic<bool> is_terminal_{false};
    mutable std::mutex mutex_;
};

} // namespace request
} // namespace mca

#endif // MCA_REQUEST_NATIVE_REQUEST_CONTEXT_HPP
