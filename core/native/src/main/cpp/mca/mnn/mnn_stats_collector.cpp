#include "mca/mnn/mnn_stats_collector.hpp"

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <sstream>

#if __has_include("jni_utf8_codec.hpp")
#include "jni_utf8_codec.hpp"
#elif __has_include("../../jni_utf8_codec.hpp")
#include "../../jni_utf8_codec.hpp"
#endif

namespace mca {
namespace mnn {

std::string escape_json(const std::string& input) {
    std::ostringstream out;
    static constexpr char kHex[] = "0123456789abcdef";
    for (const unsigned char ch : input) {
        switch (ch) {
            case '\\':
                out << "\\\\";
                break;
            case '"':
                out << "\\\"";
                break;
            case '\n':
                out << "\\n";
                break;
            case '\r':
                out << "\\r";
                break;
            case '\t':
                out << "\\t";
                break;
            default:
                if (ch < 0x20) {
                    out << "\\u00" << kHex[(ch >> 4) & 0x0F] << kHex[ch & 0x0F];
                } else {
                    out << static_cast<char>(ch);
                }
                break;
        }
    }
    return out.str();
}

std::string sanitize_utf8_for_diagnostics(const std::string& input) {
#if defined(MCA_UTF8_CODEC_AVAILABLE) || __has_include("jni_utf8_codec.hpp") || __has_include("../../jni_utf8_codec.hpp")
    const auto decoded = mca::utf8::decode_to_utf16(input, false);
    return mca::utf8::encode_from_utf16(decoded.utf16.data(), decoded.utf16.size());
#else
    return input;
#endif
}

std::string raw_bytes_to_hex(const std::string& input) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string encoded;
    encoded.reserve(input.size() * 2U);
    for (const unsigned char value : input) {
        encoded.push_back(kHex[value >> 4U]);
        encoded.push_back(kHex[value & 0x0fU]);
    }
    return encoded;
}

std::string token_ids_to_json(const std::vector<int>& ids) {
    std::ostringstream out;
    out << '[';
    for (size_t index = 0; index < ids.size(); ++index) {
        if (index > 0) out << ',';
        out << ids[index];
    }
    out << ']';
    return out.str();
}

nlohmann::json float_vector_stats_json(const std::vector<float>& values, size_t sampleLimit) {
    nlohmann::json samples = nlohmann::json::array();
    long double sum = 0.0L;
    long double absSum = 0.0L;
    float minValue = 0.0f;
    float maxValue = 0.0f;
    size_t finite = 0;
    for (float value : values) {
        if (!std::isfinite(value)) continue;
        if (finite == 0) {
            minValue = value;
            maxValue = value;
        } else {
            minValue = std::min(minValue, value);
            maxValue = std::max(maxValue, value);
        }
        sum += static_cast<long double>(value);
        absSum += std::fabs(static_cast<long double>(value));
        ++finite;
    }
    for (size_t i = 0; i < values.size() && samples.size() < sampleLimit; ++i) {
        const float value = values[i];
        if (std::isfinite(value)) {
            samples.push_back(static_cast<double>(value));
        } else if (std::isnan(value)) {
            samples.push_back("nan");
        } else if (value > 0.0f) {
            samples.push_back("inf");
        } else {
            samples.push_back("-inf");
        }
    }
    nlohmann::json out = {
            {"count", static_cast<long long>(values.size())},
            {"finite", static_cast<long long>(finite)},
            {"nonFinite", static_cast<long long>(values.size() - finite)},
            {"samples", samples}
    };
    if (finite == 0) {
        out["min"] = nullptr;
        out["max"] = nullptr;
        out["mean"] = nullptr;
        out["absMean"] = nullptr;
    } else {
        out["min"] = static_cast<double>(minValue);
        out["max"] = static_cast<double>(maxValue);
        out["mean"] = static_cast<double>(sum / static_cast<long double>(finite));
        out["absMean"] = static_cast<double>(absSum / static_cast<long double>(finite));
    }
    return out;
}

nlohmann::json float_vector_abs_diff_stats_json(const std::vector<float>& a, const std::vector<float>& b) {
    const size_t count = std::min(a.size(), b.size());
    std::vector<float> diff;
    diff.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        diff.push_back(std::fabs(a[i] - b[i]));
    }
    auto stats = float_vector_stats_json(diff);
    stats["compared"] = static_cast<long long>(count);
    stats["leftCount"] = static_cast<long long>(a.size());
    stats["rightCount"] = static_cast<long long>(b.size());
    return stats;
}

std::string format_mnn_stats_json(const MnnEngineStatsSnapshot& snapshot) {
    const std::string backend_type = snapshot.backend_type.empty() ? "cpu" : snapshot.backend_type;
    const bool opencl_backend = backend_type == "opencl";
    const std::string backend_id = std::string("mnn_") + backend_type;

    const std::string raw_output_hex = !snapshot.mnn_debug_raw_output_hex.empty()
            ? snapshot.mnn_debug_raw_output_hex
            : raw_bytes_to_hex(snapshot.mnn_debug_raw_output);

    const std::string token_ids_json = !snapshot.mnn_debug_generated_token_ids_json.empty()
            ? snapshot.mnn_debug_generated_token_ids_json
            : token_ids_to_json(snapshot.mnn_debug_generated_token_ids);

    std::ostringstream out;
    out << "{"
        << "\"backend\":\"" << escape_json(backend_id) << "\","
        << "\"loaded\":" << (snapshot.loaded ? "true" : "false") << ","
        << "\"runnerReady\":" << (snapshot.runner_ready ? "true" : "false") << ","
        << "\"loadGeneration\":" << snapshot.load_generation << ","
        << "\"loadedAtMs\":" << snapshot.loaded_at_ms << ","
        << "\"requestCountSinceLoad\":" << snapshot.request_count_since_load << ","
        << "\"lastRequestReset\":" << (snapshot.last_request_reset ? "true" : "false") << ","
        << "\"mnnModelType\":\"" << escape_json(snapshot.mnn_model_type) << "\","
        << "\"multimodalSystemPromptSuppressed\":"
        << (snapshot.multimodal_system_prompt_suppressed ? "true" : "false") << ","
        << "\"multimodalHistorySuppressed\":"
        << (snapshot.multimodal_history_suppressed ? "true" : "false") << ","
        << "\"promptEndsInsideReasoning\":"
        << (snapshot.prompt_ends_inside_reasoning ? "true" : "false") << ","
        << "\"modelPath\":\"" << escape_json(snapshot.model_path) << "\","
        << "\"originalModelPath\":\"" << escape_json(snapshot.original_model_path) << "\","
        << "\"nativeLibDir\":\"" << escape_json(snapshot.native_lib_dir) << "\","
        << "\"visionReady\":" << (snapshot.vision_ready ? "true" : "false") << ","
        << "\"visualModelPath\":\"" << escape_json(snapshot.visual_model_path) << "\","
        << "\"promptTokens\":" << snapshot.llm_context.prompt_tokens << ","
        << "\"prefillTokens\":" << snapshot.llm_context.prefill_tokens << ","
        << "\"completionTokens\":" << snapshot.llm_context.completion_tokens << ",";

    if (snapshot.prompt_cache.has_prompt_cache) {
        out << "\"promptCache\":{"
            << "\"enabled\":" << (snapshot.prompt_cache.enabled ? "true" : "false") << ","
            << "\"hit\":" << (snapshot.prompt_cache.hit ? "true" : "false") << ","
            << "\"reusedTokens\":" << snapshot.prompt_cache.reused_tokens << ","
            << "\"prefillTokens\":" << snapshot.prompt_cache.prefilled_tokens << ","
            << "\"kvHistoryBefore\":" << snapshot.prompt_cache.kv_history_before << ","
            << "\"tokenHistoryBefore\":" << snapshot.prompt_cache.token_history_before << ","
            << "\"tokenHistoryAfterPrefill\":"
            << snapshot.prompt_cache.token_history_after_prefill << ","
            << "\"prefixExtended\":" << (snapshot.prompt_cache.prefix_extended ? "true" : "false") << ","
            << "\"committed\":" << (snapshot.prompt_cache.committed ? "true" : "false") << ","
            << "\"multimodalDisabled\":"
            << (snapshot.prompt_cache.last_request_multimodal ? "true" : "false") << ","
            << "\"state\":\"" << escape_json(snapshot.prompt_cache.state) << "\","
            << "\"reason\":\"" << escape_json(snapshot.prompt_cache.reason) << "\"},"
            << "\"promptCacheEnabled\":" << (snapshot.prompt_cache.enabled ? "true" : "false") << ","
            << "\"promptCacheHit\":" << (snapshot.prompt_cache.hit ? "true" : "false") << ","
            << "\"promptCacheReusedTokens\":" << snapshot.prompt_cache.reused_tokens << ","
            << "\"promptCachePrefillTokens\":" << snapshot.prompt_cache.prefilled_tokens << ","
            << "\"promptCacheKvHistoryBefore\":" << snapshot.prompt_cache.kv_history_before << ","
            << "\"promptCacheTokenHistoryBefore\":" << snapshot.prompt_cache.token_history_before << ","
            << "\"promptCacheTokenHistoryAfterPrefill\":"
            << snapshot.prompt_cache.token_history_after_prefill << ","
            << "\"promptCacheState\":\"" << escape_json(snapshot.prompt_cache.state) << "\","
            << "\"promptCacheReason\":\"" << escape_json(snapshot.prompt_cache.reason) << "\","
            << "\"cacheReuse\":{"
            << "\"hit\":" << (snapshot.prompt_cache.hit ? "true" : "false") << ","
            << "\"reusedTokens\":" << snapshot.prompt_cache.reused_tokens << ","
            << "\"hits\":" << snapshot.prompt_cache.hits << ","
            << "\"misses\":" << snapshot.prompt_cache.misses << ","
            << "\"reason\":\"" << escape_json(snapshot.prompt_cache.reason) << "\"},";
    } else {
        out << "\"promptCache\":{\"enabled\":false,\"hit\":false,\"reusedTokens\":0,"
            << "\"prefillTokens\":0,\"kvHistoryBefore\":0,\"tokenHistoryBefore\":0,"
            << "\"tokenHistoryAfterPrefill\":0,\"state\":\"unavailable\","
            << "\"reason\":\"mnn_llm_not_linked\"},"
            << "\"promptCacheEnabled\":false,"
            << "\"promptCacheHit\":false,"
            << "\"promptCacheReusedTokens\":0,"
            << "\"promptCachePrefillTokens\":0,"
            << "\"promptCacheKvHistoryBefore\":0,"
            << "\"promptCacheTokenHistoryBefore\":0,"
            << "\"promptCacheTokenHistoryAfterPrefill\":0,"
            << "\"promptCacheState\":\"unavailable\","
            << "\"promptCacheReason\":\"mnn_llm_not_linked\","
            << "\"cacheReuse\":{\"hit\":false,\"reusedTokens\":0,\"hits\":0,\"misses\":0,"
            << "\"reason\":\"mnn_llm_not_linked\"},";
    }

    out << "\"generationSequence\":" << snapshot.generation_sequence << ","
        << "\"ttftMs\":" << snapshot.llm_context.ttft_ms << ","
        << "\"prefillMs\":" << snapshot.llm_context.prefill_ms << ","
        << "\"prefillTps\":" << snapshot.llm_context.prefill_tps << ","
        << "\"effectivePromptTps\":" << snapshot.llm_context.effective_prompt_tps << ","
        << "\"decodeMs\":" << snapshot.llm_context.decode_ms << ","
        << "\"decodeTps\":" << snapshot.llm_context.decode_tps << ","
        << "\"logitsCount\":" << snapshot.llm_context.logits_count << ","
        << "\"logitsFiniteCount\":" << snapshot.llm_context.logits_finite_count << ","
        << "\"logitsNonFiniteCount\":" << snapshot.llm_context.logits_non_finite_count << ","
        << "\"logitsAllFinite\":" << (snapshot.llm_context.logits_all_finite ? "true" : "false") << ","
        << "\"logitsDiagnostics\":" << snapshot.llm_context.logits_diagnostics << ","
        << "\"firstLogits\":" << snapshot.llm_context.first_logits_diagnostics << ","
        << "\"samplerConfig\":" << snapshot.llm_context.sampler_config << ","
        << "\"effectiveConfigJson\":\"" << escape_json(snapshot.llm_context.effective_config) << "\","
        << "\"logitsMin\":";
    if (snapshot.llm_context.logits_finite_count == 0) out << "null";
    else out << snapshot.llm_context.logits_min;
    out << ",\"logitsMax\":";
    if (snapshot.llm_context.logits_finite_count == 0) out << "null";
    else out << snapshot.llm_context.logits_max;
    out << ","
        << "\"generationActive\":" << (snapshot.generation_active ? "true" : "false") << ","
        << "\"generatedSteps\":" << snapshot.generated_steps << ","
        << "\"streamedBytes\":" << snapshot.streamed_bytes << ","
        << "\"generationStopReason\":\"" << escape_json(snapshot.generation_stop_reason) << "\","
        << "\"mnnDebugTraceEnabled\":" << (snapshot.mnn_debug_trace_enabled ? "true" : "false") << ","
        << "\"mnnDebugTraceTruncated\":" << (snapshot.mnn_debug_trace_truncated ? "true" : "false") << ","
        << "\"mnnDebugRawOutput\":\""
        << escape_json(sanitize_utf8_for_diagnostics(snapshot.mnn_debug_raw_output)) << "\","
        << "\"mnnDebugRawOutputHex\":\"" << raw_output_hex << "\","
        << "\"mnnDebugGeneratedTokenIds\":" << token_ids_json << ","
        << "\"promptCacheReuse\":" << snapshot.llm_context.prompt_cache_reuse << ","
        << "\"mnnDebugTokenIdsTruncated\":"
        << (snapshot.mnn_debug_token_ids_truncated ? "true" : "false") << ","
        << "\"mnnDebugPromptTruncated\":" << (snapshot.mnn_debug_prompt_truncated ? "true" : "false") << ","
        << "\"mnnDebugPrompt\":\""
        << escape_json(sanitize_utf8_for_diagnostics(snapshot.mnn_debug_prompt)) << "\","
        << "\"syncStepping\":" << (snapshot.sync_stepping ? "true" : "false") << ","
        << "\"nThreads\":" << snapshot.n_threads << ","
        << "\"nCtx\":" << snapshot.n_ctx << ","
        << "\"maxAllTokens\":" << snapshot.max_all_tokens << ","
        << "\"maxNewTokens\":" << snapshot.max_new_tokens << ","
        << "\"loadedConfigJson\":\"" << escape_json(snapshot.loaded_config_json) << "\","
        << "\"lastConfigJson\":\"" << escape_json(snapshot.last_config_json) << "\","
        << "\"backendType\":\"" << escape_json(backend_type) << "\","
        << "\"backendDevices\":[\"" << (opencl_backend ? "opencl" : "cpu") << "\"],"
        << "\"lastError\":\"" << escape_json(snapshot.last_error) << "\""
        << "}";
    return out.str();
}

std::string make_deferred_visual_stats_json(const std::string& stats_json, int64_t next_generation_sequence) {
    auto snapshot = nlohmann::json::parse(stats_json);
    for (const auto* key : {"promptTokens", "prefillTokens", "completionTokens",
            "prefillMs", "decodeMs", "ttftMs", "prefillTps", "effectivePromptTps", "decodeTps"}) {
        snapshot.erase(key);
    }
    snapshot["generationSequence"] = next_generation_sequence;
    snapshot["runtimeStatsDeferred"] = true;
    return snapshot.dump();
}

} // namespace mnn
} // namespace mca
