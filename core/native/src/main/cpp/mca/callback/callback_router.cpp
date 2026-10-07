#include "callback_router.hpp"
#include "mca/common/logging.hpp"
#include <chrono>

namespace mca {
namespace callback {

CallbackRouter::CallbackRouter(size_t max_queue_capacity)
    : max_capacity_(max_queue_capacity) {}

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
    // 1. Check stale epoch/generation/worker
    if (event.is_stale(active_epoch, active_generation, active_worker)) {
        MCA_LOGD("CallbackRouter", "Dropping stale event [ep=%lld, gen=%lld]",
                 static_cast<long long>(event.identity.runtime_epoch),
                 static_cast<long long>(event.identity.session_generation));
        return DispatchResult::StaleRejected;
    }

    // 2. Check monotonic sequence
    const int64_t last_seq = last_sequence_.load(std::memory_order_relaxed);
    if (event.sequence <= last_seq && event.sequence > 0) {
        MCA_LOGW("CallbackRouter", "Dropping non-monotonic sequence event %lld <= %lld",
                 static_cast<long long>(event.sequence),
                 static_cast<long long>(last_seq));
        return DispatchResult::StaleRejected;
    }

    // 3. Check duplicate terminal
    if (event.is_terminal) {
        if (terminal_delivered_.exchange(true, std::memory_order_relaxed)) {
            MCA_LOGW("CallbackRouter", "Duplicate terminal event ignored");
            return DispatchResult::DuplicateTerminalIgnored;
        }
    } else if (terminal_delivered_.load(std::memory_order_relaxed)) {
        // Non-terminal event arrived after terminal was delivered
        return DispatchResult::StaleRejected;
    }

    SinkFunction sink_copy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sink_copy = sink_;
        in_flight_.fetch_add(1, std::memory_order_relaxed);
    }

    last_sequence_.store(event.sequence, std::memory_order_relaxed);

    if (sink_copy) {
        sink_copy(event);
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        in_flight_.fetch_sub(1, std::memory_order_relaxed);
    }
    cv_drain_.notify_all();

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
    std::lock_guard<std::mutex> lock(mutex_);
    terminal_delivered_.store(false, std::memory_order_relaxed);
    last_sequence_.store(-1, std::memory_order_relaxed);
    in_flight_.store(0, std::memory_order_relaxed);
    cv_drain_.notify_all();
}

} // namespace callback
} // namespace mca
