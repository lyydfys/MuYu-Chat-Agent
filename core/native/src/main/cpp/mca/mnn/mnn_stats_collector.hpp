#ifndef MCA_MNN_STATS_COLLECTOR_HPP
#define MCA_MNN_STATS_COLLECTOR_HPP

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace mnn {

/**
 * Snapshot of MNN prompt cache tracking state and hit/miss counters.
 */
struct MnnPromptCacheStatsSnapshot {
    bool has_prompt_cache{true};
    bool enabled{false};
    bool hit{false};
    int reused_tokens{0};
    int prefilled_tokens{0};
    int64_t kv_history_before{0};
    int64_t token_history_before{0};
    int64_t token_history_after_prefill{0};
    bool prefix_extended{false};
    bool committed{false};
    bool last_request_multimodal{false};
    std::string state{"unavailable"};
    std::string reason{"mnn_llm_not_linked"};
    int64_t hits{0};
    int64_t misses{0};
};

/**
 * Snapshot of MNN LLM context execution metrics and logits diagnostics.
 */
struct MnnLlmContextStatsSnapshot {
    int prompt_tokens{0};
    int prefill_tokens{0};
    int completion_tokens{0};
    int64_t prefill_ms{0};
    int64_t decode_ms{0};
    int64_t ttft_ms{0};
    double prefill_tps{0.0};
    double effective_prompt_tps{0.0};
    double decode_tps{0.0};
    size_t logits_count{0};
    size_t logits_finite_count{0};
    size_t logits_non_finite_count{0};
    float logits_min{0.0f};
    float logits_max{0.0f};
    bool logits_all_finite{false};
    std::string logits_diagnostics{"null"};
    std::string first_logits_diagnostics{"null"};
    std::string prompt_cache_reuse{"null"};
    std::string sampler_config{"null"};
    std::string effective_config;
};

/**
 * Complete snapshot of MNN native engine runtime state and execution counters.
 */
struct MnnEngineStatsSnapshot {
    std::string backend_type{"cpu"};
    bool loaded{false};
    bool runner_ready{false};
    int64_t load_generation{0};
    int64_t loaded_at_ms{0};
    int64_t request_count_since_load{0};
    bool last_request_reset{false};
    std::string mnn_model_type;
    bool multimodal_system_prompt_suppressed{false};
    bool multimodal_history_suppressed{false};
    bool prompt_ends_inside_reasoning{false};
    std::string model_path;
    std::string original_model_path;
    std::string native_lib_dir;
    bool vision_ready{false};
    std::string visual_model_path;

    // LLM context & prompt cache metrics
    MnnLlmContextStatsSnapshot llm_context;
    MnnPromptCacheStatsSnapshot prompt_cache;

    // Generation sequence and diagnostics
    int64_t generation_sequence{0};
    bool generation_active{false};
    int generated_steps{0};
    size_t streamed_bytes{0};
    std::string generation_stop_reason;
    bool mnn_debug_trace_enabled{false};
    bool mnn_debug_trace_truncated{false};
    std::string mnn_debug_raw_output;
    std::string mnn_debug_raw_output_hex;
    std::vector<int> mnn_debug_generated_token_ids;
    std::string mnn_debug_generated_token_ids_json;
    bool mnn_debug_token_ids_truncated{false};
    bool mnn_debug_prompt_truncated{false};
    std::string mnn_debug_prompt;
    bool sync_stepping{false};
    int n_threads{0};
    int n_ctx{0};
    int max_all_tokens{0};
    int max_new_tokens{0};
    std::string loaded_config_json;
    std::string last_config_json;
    std::string last_error;
};

// String escaping and diagnostic encoding utilities
std::string escape_json(const std::string& input);
std::string sanitize_utf8_for_diagnostics(const std::string& input);
std::string raw_bytes_to_hex(const std::string& bytes);
std::string token_ids_to_json(const std::vector<int>& ids);

// Tensor diagnostic statistics
nlohmann::json float_vector_stats_json(const std::vector<float>& values, size_t sampleLimit = 8);
nlohmann::json float_vector_abs_diff_stats_json(const std::vector<float>& a, const std::vector<float>& b);

template <typename Reader>
nlohmann::json raw_tensor_stats_json(size_t availableCount, size_t expectedCount, Reader reader) {
    const size_t count = std::min(availableCount, expectedCount);
    std::vector<float> values;
    values.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        values.push_back(reader(i));
    }
    auto stats = float_vector_stats_json(values);
    stats["available"] = static_cast<long long>(availableCount);
    stats["read"] = static_cast<long long>(count);
    return stats;
}

// Runtime stats JSON formatting
std::string format_mnn_stats_json(const MnnEngineStatsSnapshot& snapshot);
std::string make_deferred_visual_stats_json(const std::string& stats_json, int64_t next_generation_sequence);

// Extraction helper for MNN prompt cache state
template <typename TPromptCache>
inline MnnPromptCacheStatsSnapshot make_prompt_cache_snapshot(
        const TPromptCache& cache,
        int64_t hits,
        int64_t misses) {
    MnnPromptCacheStatsSnapshot snap;
    snap.has_prompt_cache = true;
    snap.enabled = cache.enabled;
    snap.hit = cache.hit;
    snap.reused_tokens = cache.reused_tokens;
    snap.prefilled_tokens = cache.prefilled_tokens;
    snap.kv_history_before = static_cast<int64_t>(cache.kv_history_before);
    snap.token_history_before = static_cast<int64_t>(cache.token_history_before);
    snap.token_history_after_prefill = static_cast<int64_t>(cache.token_history_after_prefill);
    snap.prefix_extended = cache.prefix_extended;
    snap.committed = cache.committed;
    snap.last_request_multimodal = cache.last_request_multimodal;
    snap.state = cache.state;
    snap.reason = cache.reason;
    snap.hits = hits;
    snap.misses = misses;
    return snap;
}

// Extraction helper for MNN LLM context
template <typename TLlm, typename TPromptCache>
inline MnnLlmContextStatsSnapshot extract_llm_context_stats(
        const TLlm* llm,
        const TPromptCache& prompt_cache,
        int64_t generation_started_at_ms,
        int64_t first_chunk_at_ms) {
    MnnLlmContextStatsSnapshot result;
    if (llm != nullptr && llm->getContext() != nullptr) {
        const auto* context = llm->getContext();
        result.prompt_tokens = prompt_cache.enabled
                ? prompt_cache.reused_tokens + prompt_cache.prefilled_tokens
                : context->prompt_len;
        const int computed_prefill_tokens = prompt_cache.enabled
                ? prompt_cache.prefilled_tokens
                : context->prompt_len;
        result.prefill_tokens = computed_prefill_tokens;
        result.completion_tokens = prompt_cache.completion_tokens_before_rollback >= 0
                ? prompt_cache.completion_tokens_before_rollback : context->gen_seq_len;
        result.prefill_ms = context->prefill_us / 1000;
        result.decode_ms = context->decode_us / 1000;
        if (result.prefill_ms > 0 && computed_prefill_tokens > 0) {
            result.prefill_tps = computed_prefill_tokens * 1000.0 / result.prefill_ms;
        }
        if (result.prefill_ms > 0 && result.prompt_tokens > 0) {
            result.effective_prompt_tps = result.prompt_tokens * 1000.0 / result.prefill_ms;
        }
        if (result.decode_ms > 0 && result.completion_tokens > 0) {
            result.decode_tps = result.completion_tokens * 1000.0 / result.decode_ms;
        }
        if (generation_started_at_ms > 0 && first_chunk_at_ms > 0) {
            result.ttft_ms = first_chunk_at_ms - generation_started_at_ms;
        }
        result.logits_count = context->logits_count;
        result.logits_finite_count = context->logits_finite_count;
        result.logits_non_finite_count = context->logits_non_finite_count;
        result.logits_min = context->logits_min;
        result.logits_max = context->logits_max;
        result.logits_all_finite = context->logits_all_finite;
        if (!context->logits_diagnostics_json.empty()) result.logits_diagnostics = context->logits_diagnostics_json;
        if (!context->first_logits_diagnostics_json.empty()) result.first_logits_diagnostics = context->first_logits_diagnostics_json;
        if (!context->prompt_cache_diagnostics_json.empty()) result.prompt_cache_reuse = context->prompt_cache_diagnostics_json;
        if (!context->sampler_config_json.empty()) result.sampler_config = context->sampler_config_json;
        result.effective_config = const_cast<TLlm*>(llm)->dump_config();
    }
    return result;
}

/**
 * Stat collector facade for MNN performance and diagnostic telemetry.
 */
class MnnStatsCollector {
public:
    static std::string format(const MnnEngineStatsSnapshot& snapshot) {
        return format_mnn_stats_json(snapshot);
    }

    static std::string makeDeferredVisualStats(
            const std::string& stats_json,
            int64_t next_generation_sequence) {
        return make_deferred_visual_stats_json(stats_json, next_generation_sequence);
    }
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_STATS_COLLECTOR_HPP
