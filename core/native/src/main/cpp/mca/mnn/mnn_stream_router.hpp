#ifndef MCA_MNN_STREAM_ROUTER_HPP
#define MCA_MNN_STREAM_ROUTER_HPP

#include <memory>
#include <string>
#include <atomic>
#include <mutex>
#include "mca/callback/callback_router.hpp"
#include "mca/contract/callback_event.hpp"
#include "mnn_request.hpp"

namespace mca {
namespace mnn {

/**
 * Stream router managing token, progress, and terminal callback event ordering and backpressure.
 *
 * Enforces strictly monotonic sequence numbering and exactly-once terminal delivery.
 */
class MnnStreamRouter {
public:
    MnnStreamRouter(
        std::shared_ptr<mca::callback::CallbackRouter> router,
        std::shared_ptr<MnnRequest> request
    );
    ~MnnStreamRouter();

    /**
     * Emits a generated token payload.
     */
    bool emit_token(int64_t token_index, const std::string& text, bool is_special = false);

    /**
     * Emits generation progress (prefill / decode).
     */
    bool emit_progress(float current, float total, const std::string& stage, const std::string& detail = "");

    /**
     * Emits an execution error envelope.
     */
    bool emit_error(const contract::ErrorEnvelope& error);

    /**
     * Emits exactly-once terminal event.
     */
    bool emit_terminal(contract::RequestState final_state, int64_t total_tokens, int64_t elapsed_ms);

    /**
     * Blocks until all queued stream events are drained.
     */
    bool drain(int64_t timeout_ms = 3000);

    [[nodiscard]] size_t get_emitted_token_count() const noexcept {
        return emitted_token_count_.load(std::memory_order_relaxed);
    }

    [[nodiscard]] bool is_terminal_emitted() const noexcept {
        return terminal_emitted_.load(std::memory_order_relaxed);
    }

private:
    std::shared_ptr<mca::callback::CallbackRouter> router_;
    std::shared_ptr<MnnRequest> request_;
    std::atomic<size_t> emitted_token_count_{0};
    std::atomic<bool> terminal_emitted_{false};
    mutable std::mutex router_mutex_;
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_STREAM_ROUTER_HPP
