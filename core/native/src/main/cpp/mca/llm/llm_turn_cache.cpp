#include "llm_turn_cache.hpp"

#include <algorithm>
#include <limits>
#include <utility>

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#endif

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace mca {
namespace llm {

size_t turn_cache_checkpoint_bytes(const TurnCacheCheckpoint &checkpoint) noexcept {
    return TurnCacheManager::checkpoint_bytes(checkpoint);
}

size_t turn_cache_checkpoint_bytes(size_t data_bytes, size_t prefix_tokens) noexcept {
    return TurnCacheManager::checkpoint_bytes(data_bytes, prefix_tokens);
}

size_t TurnCacheManager::checkpoint_bytes(const TurnCacheCheckpoint &checkpoint) noexcept {
    constexpr size_t token_bytes = sizeof(llama_tokens::value_type);
    if (checkpoint.prefix.size() >
            (std::numeric_limits<size_t>::max() - checkpoint.data.size()) / token_bytes) {
        return std::numeric_limits<size_t>::max();
    }
    return checkpoint.data.size() + checkpoint.prefix.size() * token_bytes;
}

size_t TurnCacheManager::checkpoint_bytes(size_t data_bytes, size_t prefix_tokens) noexcept {
    constexpr size_t token_bytes = sizeof(llama_tokens::value_type);
    if (prefix_tokens >
            (std::numeric_limits<size_t>::max() - data_bytes) / token_bytes) {
        return std::numeric_limits<size_t>::max();
    }
    return data_bytes + prefix_tokens * token_bytes;
}

void TurnCacheManager::invalidate_turn_cache(
        std::vector<TurnCacheCheckpoint> &checkpoints,
        size_t &count,
        size_t &bytes) noexcept {
    checkpoints.clear();
    count = 0;
    bytes = 0;
}

void TurnCacheManager::invalidate_turn_cache(TurnCacheEnvironment &env) noexcept {
    if (env.checkpoints) {
        env.checkpoints->clear();
    }
    if (env.checkpoint_count) {
        *env.checkpoint_count = 0;
    }
    if (env.checkpoint_bytes) {
        *env.checkpoint_bytes = 0;
    }
}

bool TurnCacheManager::save_partial_prefix_checkpoint(
        llama_context *ctx,
        llama_pos current_pos,
        const llama_tokens &tokens,
        size_t checkpoint_tokens,
        PartialPrefixCheckpointRef state) {
    state.invalidate();
    if (ctx == nullptr || checkpoint_tokens == 0 ||
        checkpoint_tokens > tokens.size() ||
        current_pos != static_cast<llama_pos>(checkpoint_tokens)) {
        return false;
    }

#if MCA_WITH_LLAMA_CPP
    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t checkpoint_size = llama_state_seq_get_size_ext(ctx, 0, flags);
    if (checkpoint_size == 0) {
        return false;
    }

    try {
        std::vector<uint8_t> checkpoint_data(checkpoint_size);
        const size_t written = llama_state_seq_get_data_ext(
                ctx,
                checkpoint_data.data(),
                checkpoint_data.size(),
                0,
                flags);
        if (written != checkpoint_data.size()) {
            return false;
        }

        llama_tokens checkpoint_prefix(
                tokens.begin(),
                tokens.begin() + static_cast<llama_tokens::difference_type>(checkpoint_tokens));
        state.data = std::move(checkpoint_data);
        state.prefix = std::move(checkpoint_prefix);
        state.threshold = checkpoint_tokens;
        state.valid = true;
        state.tokens = checkpoint_tokens;
        state.bytes = checkpoint_size;
        return true;
    } catch (const std::exception &) {
        return false;
    }
#else
    (void) tokens;
    return false;
#endif
}

bool TurnCacheManager::restore_partial_prefix_checkpoint(
        llama_context *ctx,
        bool valid,
        const std::vector<uint8_t> &data) {
    if (ctx == nullptr || !valid || data.empty()) {
        return false;
    }
#if MCA_WITH_LLAMA_CPP
    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t restored = llama_state_seq_set_data_ext(
            ctx,
            data.data(),
            data.size(),
            0,
            flags);
    return restored == data.size();
#else
    return false;
#endif
}

bool TurnCacheManager::save_turn_cache_checkpoint(
        mca::llama::PrefixCacheStrategy strategy,
        TurnCacheEnvironment &env) {
    if (env.ctx == nullptr ||
        env.cache_state_valid == nullptr || !*env.cache_state_valid ||
        strategy != mca::llama::PrefixCacheStrategy::PartialStateCheckpoint ||
        env.context_tokens == nullptr || env.context_tokens->empty() ||
        env.current_position == nullptr ||
        *env.current_position != static_cast<llama_pos>(env.context_tokens->size()) ||
        env.checkpoints == nullptr ||
        env.checkpoint_bytes == nullptr ||
        env.checkpoint_count == nullptr ||
        (env.stop_requested && env.stop_requested->load(std::memory_order_acquire))) {
        return false;
    }

#if MCA_WITH_LLAMA_CPP
    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t checkpoint_size = llama_state_seq_get_size_ext(env.ctx, 0, flags);
    const size_t incoming_bytes = checkpoint_bytes(
            checkpoint_size,
            env.context_tokens->size());
    if (checkpoint_size == 0 || incoming_bytes > MAX_TURN_CACHE_CHECKPOINT_BYTES) {
        return false;
    }

    // Reconcile and evict before allocating the new state or token prefix. The
    // budget therefore bounds the save-time peak as well as the retained set.
    *env.checkpoint_bytes = 0;
    for (const auto &checkpoint : *env.checkpoints) {
        const size_t cp_bytes = checkpoint_bytes(checkpoint);
        if (cp_bytes > std::numeric_limits<size_t>::max() - *env.checkpoint_bytes) {
            invalidate_turn_cache(env);
            break;
        }
        *env.checkpoint_bytes += cp_bytes;
    }
    *env.checkpoint_count = env.checkpoints->size();

    try {
        auto existing = std::find_if(
                env.checkpoints->begin(),
                env.checkpoints->end(),
                [&](const TurnCacheCheckpoint &checkpoint) {
                    return checkpoint.prefix == *env.context_tokens;
                });
        if (existing != env.checkpoints->end()) {
            *env.checkpoint_bytes -= checkpoint_bytes(*existing);
            env.checkpoints->erase(existing);
        }

        while ((!env.checkpoints->empty() &&
                env.checkpoints->size() >= MAX_TURN_CACHE_CHECKPOINTS) ||
               *env.checkpoint_bytes >
                       MAX_TURN_CACHE_CHECKPOINT_BYTES - incoming_bytes) {
            *env.checkpoint_bytes -= checkpoint_bytes(env.checkpoints->front());
            env.checkpoints->erase(env.checkpoints->begin());
        }
        *env.checkpoint_count = env.checkpoints->size();

        std::vector<uint8_t> checkpoint_data(checkpoint_size);
        const size_t written = llama_state_seq_get_data_ext(
                env.ctx,
                checkpoint_data.data(),
                checkpoint_data.size(),
                0,
                flags);
        if (written != checkpoint_data.size() ||
            (env.stop_requested && env.stop_requested->load(std::memory_order_acquire))) {
            return false;
        }

        llama_tokens checkpoint_prefix = *env.context_tokens;
        const size_t checkpoint_position = checkpoint_prefix.size();
        env.checkpoints->push_back(TurnCacheCheckpoint{
                std::move(checkpoint_prefix),
                std::move(checkpoint_data),
                checkpoint_position});
        // Account only after push_back succeeds. If the vector allocation
        // throws, the catch path below can safely recompute the snapshot.
        *env.checkpoint_bytes += checkpoint_bytes(env.checkpoints->back());
        *env.checkpoint_count = env.checkpoints->size();
        if (env.stop_requested && env.stop_requested->load(std::memory_order_acquire)) {
            *env.checkpoint_bytes -= checkpoint_bytes(env.checkpoints->back());
            env.checkpoints->pop_back();
            *env.checkpoint_count = env.checkpoints->size();
            return false;
        }
        return true;
    } catch (const std::exception &) {
        *env.checkpoint_bytes = 0;
        for (const auto &checkpoint : *env.checkpoints) {
            const size_t cp_bytes = checkpoint_bytes(checkpoint);
            if (cp_bytes > std::numeric_limits<size_t>::max() - *env.checkpoint_bytes) {
                invalidate_turn_cache(env);
                return false;
            }
            *env.checkpoint_bytes += cp_bytes;
        }
        *env.checkpoint_count = env.checkpoints->size();
        return false;
    }
#else
    return false;
#endif
}

bool TurnCacheManager::restore_turn_cache_checkpoint(
        const llama_tokens &tokens,
        size_t &reused_tokens,
        TurnCacheEnvironment &env) {
    if (env.ctx == nullptr ||
        env.cache_state_valid == nullptr || !*env.cache_state_valid ||
        env.context_tokens == nullptr || env.context_tokens->empty() ||
        env.checkpoints == nullptr || env.checkpoints->empty() ||
        env.current_position == nullptr ||
        *env.current_position != static_cast<llama_pos>(env.context_tokens->size())) {
        return false;
    }

    // Prefer the deepest checkpoint that is a prefix of both the new prompt
    // and the currently live context. The second check is essential because a
    // checkpoint from a different conversation branch contains recurrent state
    // that cannot reconstruct a missing attention prefix by itself.
    const TurnCacheCheckpoint *candidate = nullptr;
    for (auto it = env.checkpoints->rbegin(); it != env.checkpoints->rend(); ++it) {
        if (it->position == 0 || it->position >= tokens.size() ||
            it->position > env.context_tokens->size() ||
            !mca::llama::tokenPrefixMatches(tokens, it->prefix) ||
            !mca::llama::tokenPrefixMatches(*env.context_tokens, it->prefix)) {
            continue;
        }
        if (candidate == nullptr || it->position > candidate->position) {
            candidate = &*it;
        }
    }
    if (candidate == nullptr) {
        return false;
    }

#if MCA_WITH_LLAMA_CPP
    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t restored = llama_state_seq_set_data_ext(
            env.ctx,
            candidate->data.data(),
            candidate->data.size(),
            0,
            flags);
    if (restored != candidate->data.size()) {
        // A failed state read may already have mutated recurrent memory. Do
        // not attempt another cache path on a context whose state is unknown.
        if (env.clear_target_context) {
            env.clear_target_context("turn_checkpoint_restore_failed");
        }
        return false;
    }

    if (*env.current_position > static_cast<llama_pos>(candidate->position)) {
        const bool trimmed = env.trim_context
                ? env.trim_context(
                        static_cast<llama_pos>(candidate->position),
                        "target turn-checkpoint cache-reuse")
                : false;
        if (!trimmed) {
            std::string trim_error;
            if (env.take_last_error) {
                trim_error = env.take_last_error();
            }
            if (env.clear_target_context) {
                env.clear_target_context("turn_checkpoint_suffix_trim_failed");
            }
            if (env.log_warning && !trim_error.empty()) {
                env.log_warning(trim_error);
            }
#ifdef __ANDROID__
            else if (!trim_error.empty()) {
                __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", trim_error.c_str());
            }
#endif
            return false;
        }
    }

    *env.current_position = static_cast<llama_pos>(candidate->position);
    *env.context_tokens = candidate->prefix;
    *env.cache_state_valid = true;
    if (env.cache_reuse_hit) {
        *env.cache_reuse_hit = true;
    }
    if (env.cache_reused_tokens) {
        *env.cache_reused_tokens = static_cast<int>(std::min<size_t>(
                candidate->position,
                static_cast<size_t>(std::numeric_limits<int>::max())));
    }
    if (env.cache_reuse_hits) {
        (*env.cache_reuse_hits)++;
    }
    if (env.turn_checkpoint_hits) {
        (*env.turn_checkpoint_hits)++;
    }
    if (env.cache_reuse_reason) {
        *env.cache_reuse_reason = "turn_checkpoint_hit";
    }
    reused_tokens = candidate->position;
    return true;
#else
    return false;
#endif
}

} // namespace llm
} // namespace mca
