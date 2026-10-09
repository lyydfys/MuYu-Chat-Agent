#ifndef MCA_LLM_LLAMA_CONTEXT_MANAGER_HPP
#define MCA_LLM_LLAMA_CONTEXT_MANAGER_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "llama_prefix_cache_policy.hpp"
#include "mca/llm/llm_turn_cache.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#else
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
struct llama_context;
using llama_pos = int32_t;
#endif

namespace mca {
namespace llm {

/**
 * Environment bindings passed to LlamaContextManager to interact with
 * native llama engine state and perform contextual operations.
 */
struct LlamaContextEnvironment {
    llama_context* ctx{nullptr};
    llama_pos* current_position{nullptr};
    llama_tokens* context_tokens{nullptr};

    size_t cache_reuse_threshold{0};
    mca::llama::PrefixCacheStrategy strategy{mca::llama::PrefixCacheStrategy::Disabled};

    bool* cache_state_valid{nullptr};
    bool* cache_reuse_hit{nullptr};
    int* cache_reused_tokens{nullptr};
    long long* cache_reuse_hits{nullptr};
    long long* cache_reuse_misses{nullptr};
    std::string* cache_reuse_reason{nullptr};

    // Turn checkpoints
    std::vector<TurnCacheCheckpoint>* turn_checkpoints{nullptr};
    long long* turn_checkpoint_misses{nullptr};
    std::function<bool(const llama_tokens& tokens, size_t& reused)> restore_turn_checkpoint;

    // Partial prefix checkpoint
    bool* checkpoint_valid{nullptr};
    std::vector<uint8_t>* checkpoint_data{nullptr};
    size_t* checkpoint_threshold{nullptr};
    size_t* checkpoint_tokens{nullptr};
    llama_tokens* checkpoint_prefix{nullptr};
    std::function<bool()> restore_partial_checkpoint;

    // Operation hooks
    std::function<void(const std::string& reason)> clear_target_context;
    std::function<bool(llama_pos pos, const char* label)> trim_context;
    std::function<void(const std::string& msg)> log_warning;
};

/**
 * Manages conversational context history, prefix cache matching, KV cache trimming,
 * and multi-turn state continuity for llama.cpp native engine.
 */
class LlamaContextManager {
public:
    /**
     * Resolves incoming prompt tokens against active context memory and checkpoints,
     * restoring reusable prefixes and trimming superseded context tails.
     *
     * @param tokens The prompt tokens to evaluate for prefix reuse.
     * @param env Environment references and callbacks for the active engine.
     * @return Number of tokens successfully reused from previous context/checkpoint.
     */
    static size_t prepare_text_prefix(
        const llama_tokens& tokens,
        const LlamaContextEnvironment& env);

    /**
     * Trims context sequence from the specified position onwards.
     */
    static bool trim_context(
        llama_context* ctx,
        llama_pos position,
        const char* label,
        std::string* out_error = nullptr);

    /**
     * Shifts KV cache when context window is about to be exhausted.
     */
    static bool shift_context(
        llama_context* ctx,
        llama_pos &current_position,
        int n_ctx,
        int n_keep,
        int overflow_headroom,
        long long &context_shifts,
        std::string &last_error);

    /**
     * Decodes text prompt, handling optional partial checkpoint creation.
     */
    static int decode_text_prompt(
        const llama_tokens &tokens,
        size_t reused,
        mca::llama::PrefixCacheStrategy strategy,
        size_t threshold,
        std::function<int(const llama_tokens &batch, bool logits_last)> decode_fn,
        std::function<bool(const llama_tokens &tokens, size_t threshold)> save_partial_fn,
        std::function<void()> invalidate_checkpoints_fn,
        std::string *last_error,
        std::string *cache_reuse_reason);

    /**
     * Saves sequence state file while tracking progress metrics.
     */
    static size_t save_state_with_progress(
        llama_context *ctx,
        const char *path,
        uint32_t seq_id,
        const llama_token *tokens,
        size_t n_token_count,
        uint32_t flags,
        std::atomic<int> &stage,
        std::atomic<uint64_t> &written_bytes,
        std::atomic<uint64_t> &total_bytes);
};

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLAMA_CONTEXT_MANAGER_HPP
