#include "native_request_context.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace request {

NativeRequestContext::NativeRequestContext(contract::RequestIdentity identity)
    : identity_(std::move(identity)) {
    state_.store(contract::RequestState::Accepted, std::memory_order_relaxed);
    MCA_LOGD("RequestContext", "[%s] NativeRequestContext created (epoch=%lld, gen=%lld)",
             identity_.request_id.c_str(),
             static_cast<long long>(identity_.runtime_epoch),
             static_cast<long long>(identity_.session_generation));
}

NativeRequestContext::~NativeRequestContext() {
    MCA_LOGD("RequestContext", "[%s] NativeRequestContext destroyed", identity_.request_id.c_str());
}

contract::RequestState NativeRequestContext::get_state() const noexcept {
    return state_.load(std::memory_order_relaxed);
}

void NativeRequestContext::request_cancel() noexcept {
    if (!cancel_requested_.exchange(true, std::memory_order_relaxed)) {
        MCA_LOGI("RequestContext", "[%s] Cancellation requested", identity_.request_id.c_str());
        std::lock_guard<std::mutex> lock(mutex_);
        if (!is_terminal_.load(std::memory_order_relaxed)) {
            state_.store(contract::RequestState::CancelRequested, std::memory_order_relaxed);
        }
    }
}

bool NativeRequestContext::is_cancel_requested() const noexcept {
    return cancel_requested_.load(std::memory_order_relaxed);
}

bool NativeRequestContext::mark_terminal(contract::RequestState terminal_state) noexcept {
    if (!contract::is_terminal_state(terminal_state)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (is_terminal_.load(std::memory_order_relaxed)) {
        // Terminal exactly-once invariant
        MCA_LOGW("RequestContext", "[%s] Repeated terminal state %s ignored",
                 identity_.request_id.c_str(),
                 contract::request_state_to_string(terminal_state));
        return false;
    }

    is_terminal_.store(true, std::memory_order_relaxed);
    state_.store(terminal_state, std::memory_order_relaxed);
    MCA_LOGI("RequestContext", "[%s] Transitioned to terminal state %s",
             identity_.request_id.c_str(),
             contract::request_state_to_string(terminal_state));
    return true;
}

bool NativeRequestContext::is_terminal() const noexcept {
    return is_terminal_.load(std::memory_order_relaxed);
}

void NativeRequestContext::update_sequence(int64_t seq) noexcept {
    last_sequence_.store(seq, std::memory_order_relaxed);
}

int64_t NativeRequestContext::get_last_sequence() const noexcept {
    return last_sequence_.load(std::memory_order_relaxed);
}

} // namespace request
} // namespace mca
