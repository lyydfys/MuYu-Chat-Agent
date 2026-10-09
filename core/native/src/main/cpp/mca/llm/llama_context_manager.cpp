#include "mca/llm/llama_context_manager.hpp"

#include <algorithm>
#include <limits>

namespace mca {
namespace llm {

bool LlamaContextManager::trim_context(
    llama_context *ctx,
    llama_pos position,
    const char *label,
    std::string *out_error) {
    if (ctx == nullptr) return false;
#if MCA_WITH_LLAMA_CPP
    if (!llama_memory_seq_rm(llama_get_memory(ctx), 0, position, -1)) {
        if (out_error != nullptr) {
            *out_error += std::string("\nFailed to trim ") + label +
                          " context at position " + std::to_string(position) + ".";
        }
        return false;
    }
    return true;
#else
    return false;
#endif
}

size_t LlamaContextManager::prepare_text_prefix(
    const llama_tokens& tokens,
    const LlamaContextEnvironment& env) {
    if (env.cache_reuse_hit) *env.cache_reuse_hit = false;
    if (env.cache_reused_tokens) *env.cache_reused_tokens = 0;

    const auto strategy = env.strategy;
    const size_t threshold = env.cache_reuse_threshold;

    if (strategy == mca::llama::PrefixCacheStrategy::Disabled) {
        if (env.clear_target_context) env.clear_target_context("disabled");
        return 0;
    }

    if (strategy == mca::llama::PrefixCacheStrategy::PartialStateCheckpoint) {
        const size_t live_matched = (env.cache_state_valid && *env.cache_state_valid && env.context_tokens)
                ? mca::llama::longestCommonTokenPrefix(tokens, *env.context_tokens)
                : 0;
        if (live_matched >= threshold &&
            env.context_tokens &&
            live_matched == env.context_tokens->size() &&
            live_matched < tokens.size() &&
            env.current_position &&
            *env.current_position == (llama_pos) live_matched) {
            if (env.cache_reuse_hit) *env.cache_reuse_hit = true;
            if (env.cache_reused_tokens) {
                *env.cache_reused_tokens = (int) std::min<size_t>(
                        live_matched,
                        static_cast<size_t>(std::numeric_limits<int>::max()));
            }
            if (env.cache_reuse_hits) (*env.cache_reuse_hits)++;
            if (env.cache_reuse_reason) *env.cache_reuse_reason = "live_turn_prefix_hit";
            return live_matched;
        }

        const bool had_turn_checkpoints = env.turn_checkpoints && !env.turn_checkpoints->empty();
        size_t turn_checkpoint_reuse = 0;
        if (env.restore_turn_checkpoint && env.restore_turn_checkpoint(tokens, turn_checkpoint_reuse)) {
            return turn_checkpoint_reuse;
        }
        if (had_turn_checkpoints && env.turn_checkpoint_misses) {
            (*env.turn_checkpoint_misses)++;
        }

        const bool checkpoint_shape_valid =
                env.checkpoint_valid && *env.checkpoint_valid &&
                env.checkpoint_data && !env.checkpoint_data->empty() &&
                env.checkpoint_threshold && *env.checkpoint_threshold == threshold &&
                env.checkpoint_tokens && *env.checkpoint_tokens == threshold &&
                env.checkpoint_prefix && env.checkpoint_prefix->size() == threshold;
        const bool context_prefix_valid =
                env.cache_state_valid && *env.cache_state_valid &&
                env.context_tokens && env.context_tokens->size() >= threshold &&
                env.checkpoint_prefix &&
                mca::llama::tokenPrefixMatches(*env.context_tokens, *env.checkpoint_prefix);
        const bool prompt_prefix_matches =
                checkpoint_shape_valid && env.checkpoint_prefix &&
                mca::llama::tokenPrefixMatches(tokens, *env.checkpoint_prefix);
        const size_t cp_tokens = env.checkpoint_tokens ? *env.checkpoint_tokens : 0;
        const bool can_reuse = mca::llama::canReusePartialStateCheckpoint(
                strategy,
                context_prefix_valid,
                checkpoint_shape_valid,
                cp_tokens,
                threshold,
                tokens.size(),
                prompt_prefix_matches);
        if (!can_reuse) {
            const char *reason = "partial_checkpoint_unavailable";
            if (!env.cache_state_valid || !*env.cache_state_valid ||
                !env.context_tokens || env.context_tokens->size() < threshold) {
                reason = "partial_checkpoint_context_invalid";
            } else if (!env.checkpoint_valid || !*env.checkpoint_valid ||
                       !env.checkpoint_data || env.checkpoint_data->empty()) {
                reason = "no_partial_checkpoint";
            } else if (!env.checkpoint_threshold || *env.checkpoint_threshold != threshold ||
                       !env.checkpoint_tokens || *env.checkpoint_tokens != threshold ||
                       !env.checkpoint_prefix || env.checkpoint_prefix->size() != threshold) {
                reason = "partial_checkpoint_threshold_mismatch";
            } else if (tokens.size() <= threshold) {
                reason = "prompt_not_longer_than_partial_checkpoint";
            } else if (!context_prefix_valid) {
                reason = "partial_checkpoint_context_mismatch";
            } else if (!prompt_prefix_matches) {
                reason = "partial_checkpoint_prefix_mismatch";
            }
            if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
            if (env.clear_target_context) env.clear_target_context(reason);
            return 0;
        }

        if (env.restore_partial_checkpoint && !env.restore_partial_checkpoint()) {
            if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
            if (env.clear_target_context) env.clear_target_context("partial_checkpoint_restore_failed");
            return 0;
        }

        if (env.trim_context && !env.trim_context((llama_pos) threshold, "target partial-checkpoint cache-reuse")) {
            if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
            if (env.clear_target_context) env.clear_target_context("partial_checkpoint_suffix_trim_failed");
            return 0;
        }

        if (env.current_position) *env.current_position = (llama_pos) threshold;
        if (env.context_tokens && env.checkpoint_prefix) *env.context_tokens = *env.checkpoint_prefix;
        if (env.cache_state_valid) *env.cache_state_valid = true;
        if (env.cache_reuse_hit) *env.cache_reuse_hit = true;
        if (env.cache_reused_tokens) *env.cache_reused_tokens = (int) threshold;
        if (env.cache_reuse_hits) (*env.cache_reuse_hits)++;
        if (env.cache_reuse_reason) *env.cache_reuse_reason = "text_prefix_hit";
        return threshold;
    }

    if (!env.cache_state_valid || !*env.cache_state_valid ||
        !env.context_tokens || env.context_tokens->empty()) {
        if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
        if (env.clear_target_context) env.clear_target_context("no_cached_prefix");
        return 0;
    }

    const size_t matched = mca::llama::longestCommonTokenPrefix(tokens, *env.context_tokens);
    if (matched < threshold) {
        if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
        if (env.clear_target_context) env.clear_target_context("common_prefix_below_threshold");
        return 0;
    }

    size_t reuse = matched;
    if (reuse == tokens.size() && reuse > 0) {
        reuse--;
    }
    if (reuse == 0) {
        if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
        if (env.clear_target_context) env.clear_target_context("no_reusable_token_after_logit_boundary");
        return 0;
    }

    if (env.context_tokens && reuse < env.context_tokens->size()) {
        if (env.trim_context && !env.trim_context((llama_pos) reuse, "target cache-reuse")) {
            if (env.cache_reuse_misses) (*env.cache_reuse_misses)++;
            if (env.clear_target_context) env.clear_target_context("backend_cannot_remove_cached_suffix");
            return 0;
        }
    }

    if (env.current_position) *env.current_position = (llama_pos) reuse;
    if (env.context_tokens) env.context_tokens->resize(reuse);
    if (env.cache_state_valid) *env.cache_state_valid = true;
    if (env.cache_reuse_hit) *env.cache_reuse_hit = true;
    if (env.cache_reused_tokens) *env.cache_reused_tokens = (int) reuse;
    if (env.cache_reuse_hits) (*env.cache_reuse_hits)++;
    if (env.cache_reuse_reason) *env.cache_reuse_reason = "text_prefix_hit";
    return reuse;
}

bool LlamaContextManager::shift_context(
        llama_context* ctx,
        llama_pos &current_position,
        int n_ctx,
        int n_keep_in,
        int overflow_headroom,
        long long &context_shifts,
        std::string &last_error) {
    if (ctx == nullptr) return false;
#if MCA_WITH_LLAMA_CPP
    const int n_keep = std::min(std::max(32, n_keep_in), std::max(32, n_ctx - 8));
    if (current_position <= n_keep + overflow_headroom + 16) {
        last_error += "\nPrompt is too large to shift context.";
        return false;
    }
    const int n_discard = (current_position - n_keep) / 2;
    if (n_discard <= 0) return false;
    if (!llama_memory_seq_rm(llama_get_memory(ctx), 0, n_keep, n_keep + n_discard)) {
        last_error += "\nFailed to remove tokens from KV cache during context shift.";
        return false;
    }
    llama_memory_seq_add(llama_get_memory(ctx), 0, n_keep + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    context_shifts++;
    return true;
#else
    (void) current_position; (void) n_ctx; (void) n_keep_in;
    (void) overflow_headroom; (void) context_shifts; (void) last_error;
    return false;
#endif
}

int LlamaContextManager::decode_text_prompt(
        const llama_tokens &tokens,
        size_t reused,
        mca::llama::PrefixCacheStrategy strategy,
        size_t threshold,
        std::function<int(const llama_tokens &batch, bool logits_last)> decode_fn,
        std::function<bool(const llama_tokens &tokens, size_t threshold)> save_partial_fn,
        std::function<void()> invalidate_checkpoints_fn,
        std::string *last_error,
        std::string *cache_reuse_reason) {
    if (reused > tokens.size()) {
        if (last_error) *last_error = "Prefix-cache reuse exceeds the prompt token count.";
        return 6;
    }

    if (reused == 0 && mca::llama::shouldCreatePartialStateCheckpoint(
            strategy, threshold, tokens.size())) {
        const auto checkpoint_end =
                tokens.begin() + (llama_tokens::difference_type) threshold;
        const llama_tokens checkpoint_prefix(tokens.begin(), checkpoint_end);
        const int prefix_rc = decode_fn ? decode_fn(checkpoint_prefix, false) : 0;
        if (prefix_rc != 0) {
            if (invalidate_checkpoints_fn) invalidate_checkpoints_fn();
            return prefix_rc;
        }

        if (save_partial_fn && !save_partial_fn(tokens, threshold)) {
            if (cache_reuse_reason) *cache_reuse_reason = "partial_checkpoint_save_failed";
        }

        const llama_tokens suffix(checkpoint_end, tokens.end());
        return decode_fn ? decode_fn(suffix, true) : 0;
    }

    const auto suffix_begin = tokens.begin() + (llama_tokens::difference_type) reused;
    const llama_tokens suffix(suffix_begin, tokens.end());
    return decode_fn ? decode_fn(suffix, true) : 0;
}

size_t LlamaContextManager::save_state_with_progress(
        llama_context *ctx,
        const char *path,
        uint32_t seq_id,
        const llama_token *tokens,
        size_t n_token_count,
        uint32_t flags,
        std::atomic<int> &stage,
        std::atomic<uint64_t> &written_bytes,
        std::atomic<uint64_t> &total_bytes) {
    stage.store(0, std::memory_order_release);
    written_bytes.store(0, std::memory_order_release);
#if MCA_WITH_LLAMA_CPP
    const size_t state_size = llama_state_seq_get_size_ext(ctx, (llama_seq_id) seq_id, (llama_state_seq_flags) flags);
    if (state_size == 0 || flags != 0 || n_token_count > std::numeric_limits<uint32_t>::max()) {
        total_bytes.store(0, std::memory_order_release);
        return 0;
    }
    const size_t total = state_size + sizeof(uint32_t) * 3 + n_token_count * sizeof(llama_token);
    total_bytes.store(total, std::memory_order_release);
    try {
        stage.store(1, std::memory_order_release); // Encoding
        const size_t written = llama_state_seq_save_file(
                ctx, path, (llama_seq_id) seq_id, tokens, n_token_count);
        if (written == 0) {
            stage.store(0, std::memory_order_release);
            written_bytes.store(0, std::memory_order_release);
            total_bytes.store(0, std::memory_order_release);
            return 0;
        }
        written_bytes.store(written, std::memory_order_release);
        total_bytes.store(written, std::memory_order_release);
        stage.store(3, std::memory_order_release); // Done
        return written;
    } catch (...) {
        stage.store(0, std::memory_order_release);
        written_bytes.store(0, std::memory_order_release);
        total_bytes.store(0, std::memory_order_release);
        return 0;
    }
#else
    (void) ctx; (void) path; (void) seq_id; (void) tokens;
    (void) n_token_count; (void) flags; (void) stage; (void) written_bytes; (void) total_bytes;
    return 0;
#endif
}

} // namespace llm
} // namespace mca

