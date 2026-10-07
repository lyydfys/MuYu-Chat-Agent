#ifndef MCA_CALLBACK_CALLBACK_ROUTER_HPP
#define MCA_CALLBACK_CALLBACK_ROUTER_HPP

#include <queue>
#include <mutex>
#include <condition_variable>
#include <functional>
#include <atomic>
#include "mca/contract/callback_event.hpp"

namespace mca {
namespace callback {

enum class DispatchResult : uint8_t {
    Delivered = 0,
    StaleRejected = 1,
    DuplicateTerminalIgnored = 2,
    QueueFullBackpressure = 3
};

/**
 * Thread-safe callback router managing stream routing, backpressure, and drain synchronization.
 */
class CallbackRouter {
public:
    using SinkFunction = std::function<void(const contract::CallbackEvent&)>;

    explicit CallbackRouter(size_t max_queue_capacity = 2048);
    ~CallbackRouter();

    void set_sink(SinkFunction sink);

    /**
     * Dispatches an event to the sink.
     * Enforces stale detection and exactly-once terminal delivery.
     */
    DispatchResult dispatch(
        const contract::CallbackEvent& event,
        int64_t active_epoch,
        int64_t active_generation,
        int64_t active_worker = 0
    );

    /**
     * Blocks until all queued events are drained or timeout expires.
     */
    bool drain(int64_t timeout_ms = 3000);

    [[nodiscard]] size_t pending_count() const noexcept;
    void reset();

private:
    size_t max_capacity_{2048};
    SinkFunction sink_;
    std::atomic<bool> terminal_delivered_{false};
    std::atomic<int64_t> last_sequence_{-1};
    mutable std::mutex mutex_;
    std::condition_variable cv_drain_;
    std::atomic<size_t> in_flight_{0};
};

} // namespace callback
} // namespace mca

#endif // MCA_CALLBACK_CALLBACK_ROUTER_HPP
