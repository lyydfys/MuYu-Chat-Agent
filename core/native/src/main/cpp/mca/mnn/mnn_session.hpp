#ifndef MCA_MNN_SESSION_HPP
#define MCA_MNN_SESSION_HPP

#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include "mca/runtime/engine_session.hpp"
#include "mnn_contracts.hpp"

namespace mca {
namespace mnn {

class MnnEngine;
class MnnRequest;

/**
 * Session context governing conversation state, KV cache, and context boundaries.
 *
 * Inherits from mca::runtime::EngineSession.
 * Manages prefix cache isolation, generation advance, and serialized turn execution.
 */
class MnnSession : public mca::runtime::EngineSession {
public:
    MnnSession(
        std::shared_ptr<MnnEngine> engine,
        int64_t session_id,
        int64_t initial_generation = 1
    );
    ~MnnSession() override;

    [[nodiscard]] std::shared_ptr<MnnEngine> get_mnn_engine() const noexcept;

    /**
     * Creates an execution request bound to this session's active generation.
     */
    std::shared_ptr<MnnRequest> create_mnn_request(
        const std::string& request_id,
        const MnnChatRequest& chat_request,
        const std::string& trace_id = ""
    );

    /**
     * KV and prefix cache management.
     */
    void clear_kv_cache() noexcept;
    [[nodiscard]] size_t get_cached_token_count() const noexcept;
    void append_cached_tokens(const std::vector<int32_t>& tokens);

    /**
     * Checks if a new prompt fits within remaining context window.
     */
    [[nodiscard]] bool fits_in_context_window(size_t prompt_tokens, size_t max_new_tokens) const noexcept;

    /**
     * Advances generation and isolates caches from previous generation.
     */
    int64_t advance_generation() noexcept;

    void close() noexcept override;
    [[nodiscard]] bool is_valid() const noexcept;

    /**
     * Tries to acquire exclusive execution lease for serialized session generation.
     */
    [[nodiscard]] bool try_acquire_execution_lease() noexcept;
    void release_execution_lease() noexcept;

private:
    mutable std::mutex session_mutex_;
    std::vector<int32_t> kv_prefix_cache_;
    std::atomic<bool> is_executing_{false};
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_SESSION_HPP
