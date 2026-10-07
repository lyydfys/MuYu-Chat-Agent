#ifndef MCA_CONTRACT_REQUEST_STATE_HPP
#define MCA_CONTRACT_REQUEST_STATE_HPP

#include <cstdint>

namespace mca {
namespace contract {

/**
 * Lifecycle states of an MCA execution request.
 * Mirrors com.muyuchat.core.engine.contract.RequestState.
 */
enum class RequestState : uint8_t {
    Created = 0,
    Accepted = 1,
    Preparing = 2,
    Running = 3,
    CancelRequested = 4,
    DrainingCallbacks = 5,
    Succeeded = 6,
    Failed = 7,
    Cancelled = 8,
    TimedOut = 9,
    Aborted = 10
};

[[nodiscard]] constexpr bool is_terminal_state(RequestState state) noexcept {
    switch (state) {
        case RequestState::Succeeded:
        case RequestState::Failed:
        case RequestState::Cancelled:
        case RequestState::TimedOut:
        case RequestState::Aborted:
            return true;
        case RequestState::Created:
        case RequestState::Accepted:
        case RequestState::Preparing:
        case RequestState::Running:
        case RequestState::CancelRequested:
        case RequestState::DrainingCallbacks:
            return false;
    }
    return false;
}

[[nodiscard]] constexpr bool is_cancellable_state(RequestState state) noexcept {
    switch (state) {
        case RequestState::Created:
        case RequestState::Accepted:
        case RequestState::Preparing:
        case RequestState::Running:
            return true;
        default:
            return false;
    }
}

[[nodiscard]] constexpr bool can_transition(RequestState from, RequestState to) noexcept {
    if (from == to) return false;
    if (is_terminal_state(from)) return false;

    switch (from) {
        case RequestState::Created:
            return to == RequestState::Accepted ||
                   to == RequestState::Cancelled ||
                   to == RequestState::Aborted;
        case RequestState::Accepted:
            return to == RequestState::Preparing ||
                   to == RequestState::CancelRequested ||
                   to == RequestState::Failed ||
                   to == RequestState::Aborted;
        case RequestState::Preparing:
            return to == RequestState::Running ||
                   to == RequestState::CancelRequested ||
                   to == RequestState::Failed ||
                   to == RequestState::Aborted ||
                   to == RequestState::TimedOut;
        case RequestState::Running:
            return to == RequestState::CancelRequested ||
                   to == RequestState::DrainingCallbacks ||
                   to == RequestState::Succeeded ||
                   to == RequestState::Failed ||
                   to == RequestState::TimedOut ||
                   to == RequestState::Aborted;
        case RequestState::CancelRequested:
            return to == RequestState::DrainingCallbacks ||
                   to == RequestState::Cancelled ||
                   to == RequestState::Aborted ||
                   to == RequestState::TimedOut;
        case RequestState::DrainingCallbacks:
            return to == RequestState::Cancelled ||
                   to == RequestState::Succeeded ||
                   to == RequestState::Failed ||
                   to == RequestState::Aborted ||
                   to == RequestState::TimedOut;
        default:
            return false;
    }
}

[[nodiscard]] constexpr const char* request_state_to_string(RequestState state) noexcept {
    switch (state) {
        case RequestState::Created: return "Created";
        case RequestState::Accepted: return "Accepted";
        case RequestState::Preparing: return "Preparing";
        case RequestState::Running: return "Running";
        case RequestState::CancelRequested: return "CancelRequested";
        case RequestState::DrainingCallbacks: return "DrainingCallbacks";
        case RequestState::Succeeded: return "Succeeded";
        case RequestState::Failed: return "Failed";
        case RequestState::Cancelled: return "Cancelled";
        case RequestState::TimedOut: return "TimedOut";
        case RequestState::Aborted: return "Aborted";
    }
    return "Unknown";
}

} // namespace contract
} // namespace mca

#endif // MCA_CONTRACT_REQUEST_STATE_HPP
