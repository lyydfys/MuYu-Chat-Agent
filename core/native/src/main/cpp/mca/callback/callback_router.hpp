#ifndef MCA_CALLBACK_CALLBACK_ROUTER_HPP
#define MCA_CALLBACK_CALLBACK_ROUTER_HPP

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
    SinkFailed = 3
};

/**
 * Synchronous callback router serializing event order and drain synchronization.
 */
class CallbackRouter {
public:
    using SinkFunction = std::function<void(const contract::CallbackEvent&)>;

    CallbackRouter() = default;
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
     * Blocks until all in-flight sink callbacks finish or timeout expires.
     */
    bool drain(int64_t timeout_ms = 3000);

    [[nodiscard]] size_t pending_count() const noexcept;
    void reset();

private:
    SinkFunction sink_;
    std::recursive_mutex dispatch_mutex_;
    std::string request_id_;
    int64_t attempt_id_{-1};
    int64_t runtime_epoch_{-1};
    int64_t session_generation_{-1};
    int64_t worker_incarnation_{-1};
    bool identity_initialized_{false};
    bool terminal_delivered_{false};
    int64_t last_sequence_{-1};
    mutable std::mutex mutex_;
    std::condition_variable cv_drain_;
    std::atomic<size_t> in_flight_{0};
};

} // namespace callback
} // namespace mca

#endif // MCA_CALLBACK_CALLBACK_ROUTER_HPP
