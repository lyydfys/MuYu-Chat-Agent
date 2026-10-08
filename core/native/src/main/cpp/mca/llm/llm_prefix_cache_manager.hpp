#ifndef MCA_LLM_LLM_PREFIX_CACHE_MANAGER_HPP
#define MCA_LLM_LLM_PREFIX_CACHE_MANAGER_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "mca/request/llama_request_parser.hpp"

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
 * Outcome of persistent prefix cache resolution.
 */
struct PrefixCacheDecision {
    bool handled{false};
    size_t reused_tokens{0};
};

/**
 * Configuration and file paths for persistent prefix cache operations.
 */
struct PrefixCacheRequest {
    bool requested{false};
    std::string restore_state_path;
    std::string write_state_path;
    std::string fixed_system_prompt;
    bool full_session_state{false};
};

using PersistentPrefixCacheNativeRequest = PrefixCacheRequest;

/**
 * Chat template formatting options required during boundary probing.
 */
struct ChatTemplateOptions {
    bool use_jinja{true};
    bool enable_thinking{true};
    int thinking_budget{1024};
};

/**
 * Host environment bindings, mutable engine state references, and execution hooks
 * required by PrefixCacheManager to validate, restore, decode, and persist prefix state.
 */
struct PrefixCacheEnvironment {
    llama_context *ctx{nullptr};
    int n_ctx{0};
    long long context_shifts{0};

    llama_pos *current_position{nullptr};
    llama_tokens *context_tokens{nullptr};

    bool *cache_state_valid{nullptr};
    bool *cache_reuse_hit{nullptr};
    int *cache_reused_tokens{nullptr};
    long long *cache_reuse_hits{nullptr};
    std::string *cache_reuse_reason{nullptr};

    bool *persistent_prefix_cache_attempted{nullptr};
    bool *persistent_prefix_cache_hit{nullptr};
    int *persistent_prefix_cache_tokens{nullptr};
    bool *persistent_prefix_cache_saved{nullptr};
    std::string *persistent_prefix_cache_reason{nullptr};
    std::string *pending_full_session_write_path{nullptr};

    // Operation hooks
    std::function<void(const std::string &reason)> clear_target_context;
    std::function<bool(llama_pos pos, const char *label)> trim_context;
    std::function<std::string(const std::vector<mca::request::ParsedMessage> &messages,
                              const ChatTemplateOptions &options)> format_messages;
    std::function<llama_tokens(const std::string &text)> tokenize;
    std::function<int(const llama_tokens &tokens)> decode_tokens;
    std::function<size_t(const std::string &path, const llama_tokens &tokens)> save_state;
    std::function<size_t(const char *path, llama_token *tokens_out, size_t n_tokens_max, size_t *n_tokens_out)> load_state_file;
};

/**
 * Manager class encapsulating persistent prefix cache resolution, state restoration,
 * probe marker boundary detection, prefill execution, and state persistence.
 */
class PrefixCacheManager {
public:
    static PrefixCacheDecision resolve_persistent_prefix_cache_locked(
            const std::vector<mca::request::ParsedMessage> &messages,
            const ChatTemplateOptions &chat_options,
            const llama_tokens &full_tokens,
            const PrefixCacheRequest &request,
            PrefixCacheEnvironment &env);

    static bool restore_session_state(
            const PrefixCacheRequest &request,
            const llama_tokens &full_tokens,
            PrefixCacheEnvironment &env,
            PrefixCacheDecision &decision);

    static bool find_probe_boundary(
            const std::vector<mca::request::ParsedMessage> &messages,
            const ChatTemplateOptions &chat_options,
            const std::string &fixed_system_prompt,
            PrefixCacheEnvironment &env,
            std::string &out_probe_formatted);

    static bool restore_fixed_prefix_state(
            const std::string &restore_state_path,
            const llama_tokens &prefix_tokens,
            const llama_tokens &full_tokens,
            PrefixCacheEnvironment &env,
            PrefixCacheDecision &decision);

    static bool prefill_and_persist_fixed_prefix(
            const PrefixCacheRequest &request,
            const llama_tokens &prefix_tokens,
            PrefixCacheEnvironment &env,
            PrefixCacheDecision &decision);
};

/**
 * Functional entry point for persistent prefix cache resolution.
 */
PrefixCacheDecision resolve_persistent_prefix_cache_locked(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PrefixCacheRequest &request,
        PrefixCacheEnvironment &env);

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLM_PREFIX_CACHE_MANAGER_HPP
