#ifndef MCA_RUNTIME_ENGINE_SESSION_HPP
#define MCA_RUNTIME_ENGINE_SESSION_HPP

#include <string>
#include <memory>
#include <atomic>
#include <mutex>
#include "mca/contract/request_identity.hpp"

namespace mca {
namespace request {
class NativeRequestContext;
}

namespace runtime {

class Engine;

/**
 * Session context governing conversation state, prefix caches, and generation epochs.
 */
class EngineSession : public std::enable_shared_from_this<EngineSession> {
public:
    EngineSession(std::shared_ptr<Engine> engine, int64_t session_id, int64_t initial_generation);
    virtual ~EngineSession();

    [[nodiscard]] int64_t get_session_id() const noexcept { return session_id_; }
    [[nodiscard]] int64_t get_generation() const noexcept { return generation_.load(std::memory_order_relaxed); }
    [[nodiscard]] std::shared_ptr<Engine> get_engine() const noexcept { return engine_; }

    int64_t advance_generation() noexcept;
    [[nodiscard]] bool is_valid() const noexcept;
    virtual void close() noexcept;

    std::shared_ptr<request::NativeRequestContext> create_request(
        const std::string& request_id,
        const std::string& trace_id = ""
    );

private:
    std::shared_ptr<Engine> engine_;
    int64_t session_id_{0};
    std::atomic<int64_t> generation_{1};
    std::atomic<bool> is_active_{true};
    mutable std::mutex mutex_;
};

} // namespace runtime
} // namespace mca

#endif // MCA_RUNTIME_ENGINE_SESSION_HPP
