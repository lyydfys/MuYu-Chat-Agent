#include "callback_router.hpp"
#include "mca/common/logging.hpp"
#include <chrono>
#include <exception>

namespace mca {
namespace callback {

CallbackRouter::~CallbackRouter() {
    reset();
}

void CallbackRouter::set_sink(SinkFunction sink) {
    std::lock_guard<std::mutex> lock(mutex_);
    sink_ = std::move(sink);
}

DispatchResult CallbackRouter::dispatch(
    const contract::CallbackEvent& event,
    int64_t active_epoch,
    int64_t active_generation,
    int64_t active_worker
) {
    std::lock_guard<std::recursive_mutex> dispatch_lock(dispatch_mutex_);

    if (!event.identity.is_valid() || event.sequence < 0) {
        return DispatchResult::StaleRejected;
    }

    // 1. Check stale epoch/generation/worker
    if (event.is_stale(active_epoch, active_generation, active_worker)) {
        MCA_LOGD("CallbackRouter", "Dropping stale event [ep=%lld, gen=%lld]",
                 static_cast<long long>(event.identity.runtime_epoch),
                 static_cast<long long>(event.identity.session_generation));
        return DispatchResult::StaleRejected;
    }

    if (!identity_initialized_) {
        request_id_ = event.identity.request_id;
        attempt_id_ = event.identity.attempt_id;
        runtime_epoch_ = event.identity.runtime_epoch;
        session_generation_ = event.identity.session_generation;
        worker_incarnation_ = event.identity.worker_incarnation;
        identity_initialized_ = true;
    } else if (event.identity.request_id != request_id_ ||
               event.identity.attempt_id != attempt_id_ ||
               event.identity.runtime_epoch != runtime_epoch_ ||
               event.identity.session_generation != session_generation_ ||
               event.identity.worker_incarnation != worker_incarnation_) {
        return DispatchResult::StaleRejected;
    }

    // 2. A repeated terminal is classified independently of its duplicate sequence.
    if (terminal_delivered_) {
        return event.is_terminal
            ? DispatchResult::DuplicateTerminalIgnored
            : DispatchResult::StaleRejected;
    }

    // 3. Check monotonic sequence
    if (event.sequence <= last_sequence_ && event.sequence >= 0) {
        MCA_LOGW("CallbackRouter", "Dropping non-monotonic sequence event %lld <= %lld",
                 static_cast<long long>(event.sequence),
                 static_cast<long long>(last_sequence_));
        return DispatchResult::StaleRejected;
    }

    if (event.is_terminal) {
        terminal_delivered_ = true;
    }

    SinkFunction sink_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sink_copy = sink_;
        in_flight_.fetch_add(1, std::memory_order_relaxed);
    }

    last_sequence_ = event.sequence;

    const auto finish_dispatch = [this]() {
        std::lock_guard<std::mutex> lock(mutex_);
        in_flight_.fetch_sub(1, std::memory_order_relaxed);
        cv_drain_.notify_all();
    };

    if (!sink_copy) {
        finish_dispatch();
        return DispatchResult::SinkFailed;
    }
    try {
        sink_copy(event);
    } catch (const std::exception& error) {
        MCA_LOGE("CallbackRouter", "Callback sink failed: %s", error.what());
        finish_dispatch();
        return DispatchResult::SinkFailed;
    } catch (...) {
        MCA_LOGE("CallbackRouter", "Callback sink failed with an unknown exception");
        finish_dispatch();
        return DispatchResult::SinkFailed;
    }
    finish_dispatch();

    return DispatchResult::Delivered;
}

bool CallbackRouter::drain(int64_t timeout_ms) {
    std::unique_lock<std::mutex> lock(mutex_);
    const auto timeout = std::chrono::milliseconds(timeout_ms);
    return cv_drain_.wait_for(lock, timeout, [this]() {
        return in_flight_.load(std::memory_order_relaxed) == 0;
    });
}

size_t CallbackRouter::pending_count() const noexcept {
    return in_flight_.load(std::memory_order_relaxed);
}

void CallbackRouter::reset() {
    std::lock_guard<std::recursive_mutex> dispatch_lock(dispatch_mutex_);
    std::lock_guard<std::mutex> lock(mutex_);
    terminal_delivered_ = false;
    last_sequence_ = -1;
    identity_initialized_ = false;
    request_id_.clear();
    attempt_id_ = -1;
    runtime_epoch_ = -1;
    session_generation_ = -1;
    worker_incarnation_ = -1;
    cv_drain_.notify_all();
}

} // namespace callback
} // namespace mca
