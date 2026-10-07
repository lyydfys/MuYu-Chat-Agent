#include "sd_request.hpp"

#include <chrono>

#include "mca/common/logging.hpp"

namespace mca {
namespace sd {

SdRequest::SdRequest(
    mca::contract::RequestIdentity identity,
    ImageInputContract input_contract
)
    : identity_(std::move(identity)),
      input_contract_(std::move(input_contract)),
      sequence_(identity_.native_sequence),
      start_time_(std::chrono::steady_clock::now()) {
    evidence_.configured_sample_steps = input_contract_.steps;
    evidence_.backend_strength = static_cast<float>(input_contract_.strength);
    MCA_LOGI("SdRequest",
             "Constructed request '%s' (epoch: %lld, mode: %d, steps: %d)",
             identity_.request_id.c_str(),
             static_cast<long long>(identity_.runtime_epoch),
             static_cast<int>(input_contract_.task_mode),
             input_contract_.steps);
}

mca::contract::RequestState SdRequest::get_state() const noexcept {
    return state_.load(std::memory_order_acquire);
}

bool SdRequest::transition_to(mca::contract::RequestState target_state) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto current = state_.load(std::memory_order_acquire);

    if (current == target_state) {
        return true;
    }

    if (mca::contract::is_terminal_state(current)) {
        MCA_LOGW("SdRequest",
                 "Request '%s' cannot transition from terminal state %s to %s",
                 identity_.request_id.c_str(),
                 mca::contract::request_state_to_string(current),
                 mca::contract::request_state_to_string(target_state));
        return false;
    }

    if (!mca::contract::can_transition(current, target_state)) {
        MCA_LOGE("SdRequest",
                 "Illegal state transition for '%s': %s -> %s",
                 identity_.request_id.c_str(),
                 mca::contract::request_state_to_string(current),
                 mca::contract::request_state_to_string(target_state));
        return false;
    }

    state_.store(target_state, std::memory_order_release);
    MCA_LOGI("SdRequest",
             "Request '%s' state: %s -> %s",
             identity_.request_id.c_str(),
             mca::contract::request_state_to_string(current),
             mca::contract::request_state_to_string(target_state));

    if (mca::contract::is_terminal_state(target_state)) {
        is_terminal_.store(true, std::memory_order_release);
        evidence_.elapsed_ms = get_elapsed_ms();
    }

    return true;
}

bool SdRequest::mark_terminal(
    mca::contract::RequestState terminal_state,
    std::optional<mca::contract::ErrorEnvelope> error
) {
    if (!mca::contract::is_terminal_state(terminal_state)) {
        MCA_LOGE("SdRequest",
                 "mark_terminal called with non-terminal state %s for '%s'",
                 mca::contract::request_state_to_string(terminal_state),
                 identity_.request_id.c_str());
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    // Exactly-once terminal invariant
    if (is_terminal_.exchange(true, std::memory_order_acq_rel)) {
        MCA_LOGW("SdRequest",
                 "Duplicate terminal transition rejected for '%s' (current: %s, rejected: %s)",
                 identity_.request_id.c_str(),
                 mca::contract::request_state_to_string(state_.load(std::memory_order_relaxed)),
                 mca::contract::request_state_to_string(terminal_state));
        return false;
    }

    state_.store(terminal_state, std::memory_order_release);
    if (error.has_value()) {
        last_error_ = std::move(error);
    }
    evidence_.elapsed_ms = get_elapsed_ms();

    MCA_LOGI("SdRequest",
             "Request '%s' reached terminal state %s after %lld ms",
             identity_.request_id.c_str(),
             mca::contract::request_state_to_string(terminal_state),
             static_cast<long long>(evidence_.elapsed_ms));

    return true;
}

bool SdRequest::is_terminal() const noexcept {
    return is_terminal_.load(std::memory_order_acquire);
}

void SdRequest::request_cancel() noexcept {
    cancel_requested_.store(true, std::memory_order_release);
    MCA_LOGI("SdRequest", "Cancellation requested for '%s'", identity_.request_id.c_str());

    std::lock_guard<std::mutex> lock(mutex_);
    const auto current = state_.load(std::memory_order_acquire);
    if (mca::contract::is_cancellable_state(current)) {
        state_.store(mca::contract::RequestState::CancelRequested, std::memory_order_release);
    }
}

bool SdRequest::is_cancel_requested() const noexcept {
    return cancel_requested_.load(std::memory_order_acquire);
}

std::optional<mca::contract::ErrorEnvelope> SdRequest::get_last_error() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

void SdRequest::set_last_error(mca::contract::ErrorEnvelope error) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    last_error_ = std::move(error);
}

int64_t SdRequest::next_sequence() noexcept {
    return sequence_.fetch_add(1, std::memory_order_relaxed) + 1;
}

int64_t SdRequest::get_current_sequence() const noexcept {
    return sequence_.load(std::memory_order_relaxed);
}

int64_t SdRequest::get_elapsed_ms() const noexcept {
    const auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::milliseconds>(now - start_time_).count();
}

mca::contract::CallbackEvent SdRequest::create_progress_event(
    float current,
    float total,
    const std::string& stage,
    const std::string& detail
) {
    mca::contract::CallbackEvent event;
    event.identity = identity_;
    event.event_type = mca::contract::CallbackEventType::Progress;
    event.sequence = next_sequence();
    event.monotonic_time_ms = get_elapsed_ms();
    event.is_terminal = false;
    event.source = mca::contract::CallbackSource::Native;

    mca::contract::ProgressPayload payload;
    payload.current = current;
    payload.total = total;
    payload.stage = stage;
    payload.detail = detail;
    event.payload = payload;

    return event;
}

mca::contract::CallbackEvent SdRequest::create_preview_event(
    int32_t frame_index,
    int32_t width,
    int32_t height,
    const std::string& format,
    const std::string& data_sha256
) {
    mca::contract::CallbackEvent event;
    event.identity = identity_;
    event.event_type = mca::contract::CallbackEventType::Preview;
    event.sequence = next_sequence();
    event.monotonic_time_ms = get_elapsed_ms();
    event.is_terminal = false;
    event.source = mca::contract::CallbackSource::Native;

    mca::contract::PreviewPayload payload;
    payload.frame_index = frame_index;
    payload.width = width;
    payload.height = height;
    payload.format = format;
    payload.data_sha256 = data_sha256;
    event.payload = payload;

    return event;
}

mca::contract::CallbackEvent SdRequest::create_state_event(
    mca::contract::RequestState previous,
    mca::contract::RequestState current
) {
    mca::contract::CallbackEvent event;
    event.identity = identity_;
    event.event_type = mca::contract::CallbackEventType::StateChanged;
    event.sequence = next_sequence();
    event.monotonic_time_ms = get_elapsed_ms();
    event.is_terminal = mca::contract::is_terminal_state(current);
    event.source = mca::contract::CallbackSource::Native;

    mca::contract::StatePayload payload;
    payload.previous_state = previous;
    payload.new_state = current;
    event.payload = payload;

    return event;
}

mca::contract::CallbackEvent SdRequest::create_terminal_event(int64_t elapsed_ms) {
    mca::contract::CallbackEvent event;
    event.identity = identity_;
    event.event_type = mca::contract::CallbackEventType::Terminal;
    event.sequence = next_sequence();
    event.monotonic_time_ms = elapsed_ms;
    event.is_terminal = true;
    event.source = mca::contract::CallbackSource::Native;

    mca::contract::TerminalPayload payload;
    payload.final_state = get_state();
    payload.total_tokens = 0;
    payload.elapsed_ms = elapsed_ms;
    event.payload = payload;

    return event;
}

mca::contract::CallbackEvent SdRequest::create_error_event(
    const mca::contract::ErrorEnvelope& error
) {
    mca::contract::CallbackEvent event;
    event.identity = identity_;
    event.event_type = mca::contract::CallbackEventType::Error;
    event.sequence = next_sequence();
    event.monotonic_time_ms = get_elapsed_ms();
    event.is_terminal = true;
    event.source = mca::contract::CallbackSource::Native;

    mca::contract::ErrorPayload payload;
    payload.error = error;
    event.payload = payload;

    return event;
}

} // namespace sd
} // namespace mca
