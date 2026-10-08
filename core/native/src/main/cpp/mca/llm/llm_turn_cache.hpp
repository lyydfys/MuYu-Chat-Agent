#ifndef MCA_LLM_LLM_TURN_CACHE_HPP
#define MCA_LLM_LLM_TURN_CACHE_HPP

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "llama_prefix_cache_policy.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#else
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
struct llama_context;
using llama_pos = int32_t;
using llama_state_seq_flags = uint32_t;
inline constexpr llama_state_seq_flags LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY = 1;
#endif

namespace mca {
namespace llm {

/**
 * Partial-only snapshot of recurrent state for a single conversational turn.
 * Storing recurrent memory across turns enables instant rollback/branching
 * without full attention KV recalculation.
 */
struct TurnCacheCheckpoint {
    llama_tokens prefix;
    std::vector<uint8_t> data;
    size_t position = 0;
};

// Partial-only snapshots contain recurrent state (not the full attention KV),
// so keeping a small rolling window is inexpensive and lets a tail-pruned
// conversation restore the exact prior turn boundary.
inline constexpr size_t MAX_TURN_CACHE_CHECKPOINTS = 4;
inline constexpr size_t MAX_TURN_CACHE_CHECKPOINT_BYTES = 96U * 1024U * 1024U;

/**
 * Calculates byte size of a TurnCacheCheckpoint including token vector and raw state buffer.
 */
size_t turn_cache_checkpoint_bytes(const TurnCacheCheckpoint &checkpoint) noexcept;

/**
 * Calculates candidate byte size for a prospective turn cache entry.
 */
size_t turn_cache_checkpoint_bytes(size_t data_bytes, size_t prefix_tokens) noexcept;

/**
 * Storage representing partial prefix checkpoint state.
 */
struct PartialPrefixCheckpointState {
    llama_tokens prefix;
    std::vector<uint8_t> data;
    size_t threshold{0};
    bool valid{false};
    size_t tokens{0};
    size_t bytes{0};

    void invalidate() noexcept {
        prefix.clear();
        data.clear();
        threshold = 0;
        valid = false;
        tokens = 0;
        bytes = 0;
    }
};

/**
 * Non-owning reference wrapper for partial prefix checkpoint state fields,
 * allowing engines with separate global or member variables to bind seamlessly.
 */
struct PartialPrefixCheckpointRef {
    llama_tokens &prefix;
    std::vector<uint8_t> &data;
    size_t &threshold;
    bool &valid;
    size_t &tokens;
    size_t &bytes;

    PartialPrefixCheckpointRef(
            llama_tokens &p,
            std::vector<uint8_t> &d,
            size_t &th,
            bool &v,
            size_t &tok,
            size_t &b) noexcept
        : prefix(p), data(d), threshold(th), valid(v), tokens(tok), bytes(b) {}

    PartialPrefixCheckpointRef(PartialPrefixCheckpointState &s) noexcept
        : prefix(s.prefix),
          data(s.data),
          threshold(s.threshold),
          valid(s.valid),
          tokens(s.tokens),
          bytes(s.bytes) {}

    void invalidate() noexcept {
        prefix.clear();
        data.clear();
        threshold = 0;
        valid = false;
        tokens = 0;
        bytes = 0;
    }
};

/**
 * Environment bindings, mutable engine state references, and execution hooks
 * required to manage rolling turn checkpoints and recurrent prefix reuse.
 */
struct TurnCacheEnvironment {
    llama_context *ctx{nullptr};
    llama_pos *current_position{nullptr};
    llama_tokens *context_tokens{nullptr};
    bool *cache_state_valid{nullptr};

    std::vector<TurnCacheCheckpoint> *checkpoints{nullptr};
    size_t *checkpoint_count{nullptr};
    size_t *checkpoint_bytes{nullptr};
    long long *turn_checkpoint_hits{nullptr};

    bool *cache_reuse_hit{nullptr};
    int *cache_reused_tokens{nullptr};
    long long *cache_reuse_hits{nullptr};
    std::string *cache_reuse_reason{nullptr};

    const std::atomic<bool> *stop_requested{nullptr};

    // Operation hooks
    std::function<void(const std::string &reason)> clear_target_context;
    std::function<bool(llama_pos pos, const char *label)> trim_context;
    std::function<std::string()> take_last_error;
    std::function<void(const std::string &message)> log_warning;
};

/**
 * Manager class encapsulating turn-by-turn rolling cache checkpoints and
 * prefix checkpoint save/restore mechanisms for recurrent/hybrid models.
 */
class TurnCacheManager {
public:
    static size_t checkpoint_bytes(const TurnCacheCheckpoint &checkpoint) noexcept;
    static size_t checkpoint_bytes(size_t data_bytes, size_t prefix_tokens) noexcept;

    static void invalidate_turn_cache(
            std::vector<TurnCacheCheckpoint> &checkpoints,
            size_t &count,
            size_t &bytes) noexcept;

    static void invalidate_turn_cache(TurnCacheEnvironment &env) noexcept;

    static bool save_partial_prefix_checkpoint(
            llama_context *ctx,
            llama_pos current_pos,
            const llama_tokens &tokens,
            size_t checkpoint_tokens,
            PartialPrefixCheckpointRef state);

    static bool restore_partial_prefix_checkpoint(
            llama_context *ctx,
            bool valid,
            const std::vector<uint8_t> &data);

    static bool save_turn_cache_checkpoint(
            mca::llama::PrefixCacheStrategy strategy,
            TurnCacheEnvironment &env);

    static bool restore_turn_cache_checkpoint(
            const llama_tokens &tokens,
            size_t &reused_tokens,
            TurnCacheEnvironment &env);
};

// Convenience free-function wrappers

inline void invalidate_turn_cache_checkpoints_locked(
        std::vector<TurnCacheCheckpoint> &checkpoints,
        size_t &count,
        size_t &bytes) noexcept {
    TurnCacheManager::invalidate_turn_cache(checkpoints, count, bytes);
}

inline void invalidate_turn_cache_checkpoints_locked(TurnCacheEnvironment &env) noexcept {
    TurnCacheManager::invalidate_turn_cache(env);
}

inline bool save_partial_prefix_checkpoint_locked(
        llama_context *ctx,
        llama_pos current_pos,
        const llama_tokens &tokens,
        size_t checkpoint_tokens,
        PartialPrefixCheckpointRef state) {
    return TurnCacheManager::save_partial_prefix_checkpoint(
            ctx, current_pos, tokens, checkpoint_tokens, state);
}

inline bool restore_partial_prefix_checkpoint_locked(
        llama_context *ctx,
        bool valid,
        const std::vector<uint8_t> &data) {
    return TurnCacheManager::restore_partial_prefix_checkpoint(ctx, valid, data);
}

inline bool save_turn_cache_checkpoint_locked(
        mca::llama::PrefixCacheStrategy strategy,
        TurnCacheEnvironment &env) {
    return TurnCacheManager::save_turn_cache_checkpoint(strategy, env);
}

inline bool restore_turn_cache_checkpoint_locked(
        const llama_tokens &tokens,
        size_t &reused_tokens,
        TurnCacheEnvironment &env) {
    return TurnCacheManager::restore_turn_cache_checkpoint(tokens, reused_tokens, env);
}

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLM_TURN_CACHE_HPP
