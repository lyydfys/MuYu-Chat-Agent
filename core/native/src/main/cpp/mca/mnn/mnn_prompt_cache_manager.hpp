#ifndef MCA_MNN_PROMPT_CACHE_MANAGER_HPP
#define MCA_MNN_PROMPT_CACHE_MANAGER_HPP

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

#include "mnn_runtime_capability_policy.hpp"
#include "mnn_stream_protocol_filter.hpp"
#include "mca/mnn/mnn_template_parser.hpp"

#if defined(MCA_WITH_MNN_LLM) && MCA_WITH_MNN_LLM
#if __has_include(<llm/llm.hpp>)
#include <llm/llm.hpp>
#elif __has_include("llm/llm.hpp")
#include "llm/llm.hpp"
#endif
#endif

namespace MNN {
namespace Transformer {
class Llm;
struct LlmContext;
#if !((defined(MCA_WITH_MNN_LLM) && MCA_WITH_MNN_LLM) || __has_include(<llm/llm.hpp>) || __has_include("llm/llm.hpp"))
enum class LlmStatus : uint8_t {
    OK = 0,
    NOT_LOADED = 1,
    USER_CANCEL = 2,
    TIMEOUT = 3,
    INTERNAL_ERROR = 4
};
#endif
using ChatMessage = std::pair<std::string, std::string>;
using ChatMessages = std::vector<ChatMessage>;
} // namespace Transformer
} // namespace MNN

namespace mca {
namespace mnn {

template <typename T, typename = void>
struct MnnHasLogBuffer : std::false_type {};
template <typename T>
struct MnnHasLogBuffer<T, std::void_t<decltype(std::declval<const T&>().log_buffer)>> : std::true_type {};

// MNN 3.6.x captures Llm errors into context->log_buffer; older runtimes lack it.
template <typename ContextT>
inline std::string mnn_context_log_snapshot(const ContextT* context) {
    if (context == nullptr) return "";
    if constexpr (MnnHasLogBuffer<ContextT>::value) {
        return context->log_buffer.substr(0, 1536);
    }
    return "";
}

/**
 * Runtime state for MNN text prompt cache lifecycle, prefix tracking, and rollback bookkeeping.
 */
struct MnnPromptCacheRuntimeState {
    int completion_tokens_before_rollback = -1;
    std::vector<MNN::Transformer::ChatMessage> committed_messages;
    std::vector<MNN::Transformer::ChatMessage> request_messages;
    size_t kv_history_before = 0;
    size_t token_history_before = 0;
    size_t token_history_after_prefill = 0;
    size_t generation_history_start = 0;
    int prefilled_tokens = 0;
    int reused_tokens = 0;
    bool request_active = false;
    bool enabled = false;
    bool hit = false;
    bool committed = false;
    bool rolled_back = false;
    bool prefix_extended = false;
    bool last_request_multimodal = false;
    bool reset_before_next_text = false;
    std::string state = "cold";
    std::string reason = "model_not_loaded";
};

/**
 * Environmental bindings connecting prompt cache transactions to the active MNN session.
 */
struct MnnPromptCacheContext {
    MNN::Transformer::Llm* llm{nullptr};
    MnnPromptCacheRuntimeState* cache{nullptr};
    MnnRequestLifecyclePolicy* lifecycle{nullptr};
    const std::vector<std::string>* active_stop_markers{nullptr};
    const std::string* generation_stop_reason{nullptr};
    std::atomic<int>* prefill_total_tokens{nullptr};
    std::atomic<int>* prefill_completed_tokens{nullptr};
    long long* hits{nullptr};
    long long* misses{nullptr};
    const bool* stop_requested{nullptr};
};

/**
 * Manages prompt cache transactions, LCP prefix verification, KV rollback,
 * assistant text extraction, and protocol terminator trimming for MNN LLM.
 */
class MnnPromptCacheManager {
public:
    MnnPromptCacheManager() = default;
    explicit MnnPromptCacheManager(MnnPromptCacheContext ctx) : ctx_(std::move(ctx)) {}

    void set_context(MnnPromptCacheContext ctx) { ctx_ = std::move(ctx); }
    [[nodiscard]] const MnnPromptCacheContext& context() const noexcept { return ctx_; }

    void clear_tracking(const std::string& reason);
    void reset_native_cache();
    [[nodiscard]] size_t effective_kv_history() const;
    [[nodiscard]] std::string assistant_text() const;
    static bool is_role_protocol_stop_reason(const std::string& reason);
    void trim_protocol_suffix_from_live_history();
    void mark_disabled(bool multimodal, const std::string& reason);
    void prepare_text_cache(std::vector<MNN::Transformer::ChatMessage>& messages);
    bool capture_text_prefill(std::string& error);
    void commit_text_cache(const std::string& reason);
    void rollback_text_cache(const std::string& reason, bool forceClear = false);
    void settle_text_cache(const std::string& reason);

    // Static operations on explicit state & components
    static void clear_tracking(
            MnnPromptCacheRuntimeState& cache,
            const std::string& reason);
    static void reset_native_cache(
            MNN::Transformer::Llm* llm,
            MnnRequestLifecyclePolicy* lifecycle = nullptr);
    static size_t effective_kv_history(const MNN::Transformer::Llm* llm);
    static std::string assistant_text(
            const MNN::Transformer::Llm* llm,
            const MnnPromptCacheRuntimeState& cache,
            const std::vector<std::string>& active_stop_markers);
    static void trim_protocol_suffix_from_live_history(
            MNN::Transformer::Llm* llm,
            const MnnPromptCacheRuntimeState& cache,
            const std::string& generation_stop_reason,
            const std::vector<std::string>& active_stop_markers);
    static void mark_disabled(
            MnnPromptCacheRuntimeState& cache,
            bool multimodal,
            const std::string& reason);
    static void prepare_text_cache(
            MnnPromptCacheRuntimeState& cache,
            MNN::Transformer::Llm* llm,
            MnnRequestLifecyclePolicy* lifecycle,
            std::vector<MNN::Transformer::ChatMessage>& messages);
    static bool capture_text_prefill(
            MnnPromptCacheRuntimeState& cache,
            MNN::Transformer::Llm* llm,
            std::atomic<int>* prefill_total_tokens,
            std::atomic<int>* prefill_completed_tokens,
            long long* prompt_cache_hits,
            long long* prompt_cache_misses,
            std::string& error);
    static void commit_text_cache(
            MnnPromptCacheRuntimeState& cache,
            MNN::Transformer::Llm* llm,
            const std::string& generation_stop_reason,
            const std::vector<std::string>& active_stop_markers,
            const std::string& reason);
    static void rollback_text_cache(
            MnnPromptCacheRuntimeState& cache,
            MNN::Transformer::Llm* llm,
            MnnRequestLifecyclePolicy* lifecycle,
            const std::string& reason,
            bool forceClear = false);
    static void settle_text_cache(
            MnnPromptCacheRuntimeState& cache,
            MNN::Transformer::Llm* llm,
            MnnRequestLifecyclePolicy* lifecycle,
            const std::string& generation_stop_reason,
            const std::vector<std::string>& active_stop_markers,
            bool stop_requested,
            const std::string& reason);

private:
    MnnPromptCacheContext ctx_;
};

// Standalone functional API matching original engine entrypoints
inline void clear_mnn_prompt_cache_tracking(
        MnnPromptCacheRuntimeState& cache,
        const std::string& reason) {
    MnnPromptCacheManager::clear_tracking(cache, reason);
}

inline void reset_mnn_native_prompt_cache(
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle = nullptr) {
    MnnPromptCacheManager::reset_native_cache(llm, lifecycle);
}

inline size_t mnn_effective_kv_history(const MNN::Transformer::Llm* llm) {
    return MnnPromptCacheManager::effective_kv_history(llm);
}

inline std::string mnn_prompt_cache_assistant_text(
        const MNN::Transformer::Llm* llm,
        const MnnPromptCacheRuntimeState& cache,
        const std::vector<std::string>& active_stop_markers) {
    return MnnPromptCacheManager::assistant_text(llm, cache, active_stop_markers);
}

inline bool mnn_role_protocol_stop_reason(const std::string& reason) {
    return MnnPromptCacheManager::is_role_protocol_stop_reason(reason);
}

inline void trim_mnn_protocol_suffix_from_live_history(
        MNN::Transformer::Llm* llm,
        const MnnPromptCacheRuntimeState& cache,
        const std::string& generation_stop_reason,
        const std::vector<std::string>& active_stop_markers) {
    MnnPromptCacheManager::trim_protocol_suffix_from_live_history(
            llm, cache, generation_stop_reason, active_stop_markers);
}

inline void mark_mnn_prompt_cache_disabled(
        MnnPromptCacheRuntimeState& cache,
        bool multimodal,
        const std::string& reason) {
    MnnPromptCacheManager::mark_disabled(cache, multimodal, reason);
}

inline void prepare_mnn_text_prompt_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle,
        std::vector<MNN::Transformer::ChatMessage>& messages) {
    MnnPromptCacheManager::prepare_text_cache(cache, llm, lifecycle, messages);
}

inline bool capture_mnn_text_prefill(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        std::atomic<int>* prefill_total_tokens,
        std::atomic<int>* prefill_completed_tokens,
        long long* prompt_cache_hits,
        long long* prompt_cache_misses,
        std::string& error) {
    return MnnPromptCacheManager::capture_text_prefill(
            cache, llm, prefill_total_tokens, prefill_completed_tokens,
            prompt_cache_hits, prompt_cache_misses, error);
}

inline void commit_mnn_text_prompt_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        const std::string& generation_stop_reason,
        const std::vector<std::string>& active_stop_markers,
        const std::string& reason) {
    MnnPromptCacheManager::commit_text_cache(
            cache, llm, generation_stop_reason, active_stop_markers, reason);
}

inline void rollback_mnn_text_prompt_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle,
        const std::string& reason,
        bool forceClear = false) {
    MnnPromptCacheManager::rollback_text_cache(cache, llm, lifecycle, reason, forceClear);
}

inline void settle_mnn_text_prompt_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle,
        const std::string& generation_stop_reason,
        const std::vector<std::string>& active_stop_markers,
        bool stop_requested,
        const std::string& reason) {
    MnnPromptCacheManager::settle_text_cache(
            cache, llm, lifecycle, generation_stop_reason, active_stop_markers,
            stop_requested, reason);
}

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_PROMPT_CACHE_MANAGER_HPP
