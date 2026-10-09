#ifndef MCA_LLM_LLM_STATS_COLLECTOR_HPP
#define MCA_LLM_LLM_STATS_COLLECTOR_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "mca/llm/llama_gpu_evaluator.hpp"

namespace mca {
namespace llm {

/**
 * Runtime configuration parameters for LLM inference.
 */
struct RuntimeConfig {
    int n_ctx = 4096;
    int n_threads = 1;
    int n_threads_batch = 1;
    int n_batch = 512;
    int n_ubatch = 512;
    int n_gpu_layers = -1;
    int main_gpu = 0;
    std::string split_mode = "layer";
    int n_cpu_moe = 0;
    std::string cache_type_k = "f16";
    std::string cache_type_v = "f16";
    std::string flash_attn = "auto";
    bool perf = false;
    int n_parallel = 1;
    int cache_reuse = 0;
    std::string spec_type = "none";
    int spec_draft_n_max = 0;
    bool mmap = true;
    bool mlock = false;
};

/**
 * Snapshot of OpenCL / GPU execution evidence telemetry.
 */
struct GpuExecutionEvidenceSnapshot {
    std::string source{"mca_opencl_dispatch:successful_enqueue_and_finish"};
    bool telemetry_available{false};
    uint64_t request_scope{0};
    uint64_t kernel_submissions{0};
    uint64_t kernel_completions{0};
};

/**
 * Snapshot of GPU offload allocation, memory breakdown, and execution status.
 */
struct GpuStatsSnapshot {
    bool offload_supported{false};
    size_t gpu_device_count{0};
    bool offload_active{false};
    bool allocation_observed{false};
    bool execution_observed{false};
    size_t offload_bytes{0};
    size_t offload_model_bytes{0};
    size_t offload_context_bytes{0};
    size_t offload_compute_bytes{0};
    int offload_layers{0};
    bool offload_layers_known{false};
    bool auto_fallback_applied{false};
    std::string auto_fallback_reason{"not_attempted"};
    GpuExecutionEvidenceSnapshot execution_evidence;
};

/**
 * Snapshot of KV cache reuse hit/miss statistics and turn checkpoints.
 */
struct CacheReuseStatsSnapshot {
    bool valid{false};
    bool hit{false};
    int reused_tokens{0};
    long long hits{0};
    long long misses{0};
    std::string reason{"not_attempted"};
    std::string strategy{"disabled"};
    bool checkpoint_valid{false};
    size_t checkpoint_tokens{0};
    size_t checkpoint_bytes{0};
    size_t turn_checkpoints{0};
    size_t turn_checkpoint_bytes{0};
    long long turn_checkpoint_hits{0};
    long long turn_checkpoint_misses{0};
    bool model_hybrid{false};
    bool model_recurrent{false};
};

/**
 * Snapshot of persistent prefix cache disk persistence and hit counters.
 */
struct PersistentPrefixCacheStatsSnapshot {
    bool attempted{false};
    bool hit{false};
    bool saved{false};
    int tokens{0};
    std::string reason{"not_requested"};
};

/**
 * Snapshot of speculative decoding (MTP/draft model) performance metrics.
 */
struct SpeculativeStatsSnapshot {
    bool requested{false};
    bool context_ready{false};
    bool active_for_request{false};
    long long drafted_tokens{0};
    long long accepted_tokens{0};
    long long steps{0};
    std::string reason{"disabled"};
};

/**
 * Snapshot of compiled and runtime backend capabilities.
 */
struct BackendCapabilitiesSnapshot {
    bool gpu_offload_supported{false};
    size_t gpu_device_count{0};
    bool gpu_offload_active{false};
    bool gpu_offload_allocation_observed{false};
    bool gpu_offload_execution_observed{false};
    size_t gpu_offload_bytes{0};
    int gpu_offload_layers{0};
    bool gpu_offload_layers_known{false};
    std::string model_architecture;
    int model_mtp_layers{0};
#if MCA_WITH_LLAMA_CPP
    bool dynamic_backend_loading{true};
    bool kv_cache_quantization{true};
    bool draft_mtp_compiled{true};
#else
    bool dynamic_backend_loading{false};
    bool kv_cache_quantization{false};
    bool draft_mtp_compiled{false};
#endif
};

/**
 * Snapshot of sampling parameters.
 */
struct SamplingStatsSnapshot {
    float temperature{0.0f};
    int top_k{0};
    float top_p{0.0f};
    float min_p{0.0f};
    float repeat_penalty{1.08f};
    float presence_penalty{0.0f};
    float frequency_penalty{0.2f};
    int repeat_last_n{0};
    bool use_jinja{true};
    bool enable_thinking{true};
    bool prompt_ends_inside_reasoning{false};
};

/**
 * Complete snapshot of LLM native engine state and execution metrics.
 */
struct LlmStatsSnapshot {
    std::string backend;
    bool loaded{false};
    std::string model_path;
    uint64_t model_file_size_bytes{0};
    bool mmap_fallback_allowed{false};
    bool mmap_prefetch_enabled{false};
    long long load_ms{0};
    long long prompt_tokens{0};
    long long prefill_computed_tokens{0};
    long long completion_tokens{0};
    uint64_t generation_sequence{0};
    bool generation_active{false};
    bool stop_requested{false};
    std::string generation_stop_reason;
    long long context_shifts{0};

    // Prefill and decode timing
    long long prefill_started_ms{0};
    long long prefill_finished_ms{0};
    long long decode_started_ms{0};
    long long decode_finished_ms{0};

    int n_threads{0};
    int n_threads_batch{0};
    int n_batch{0};
    int n_ubatch{0};
    int max_new_tokens{0};

    // Sub-structures
    GpuStatsSnapshot gpu;
    RuntimeConfig requested_config;
    RuntimeConfig effective_config;
    SamplingStatsSnapshot sampling;
    BackendCapabilitiesSnapshot capabilities;
    CacheReuseStatsSnapshot cache_reuse;
    PersistentPrefixCacheStatsSnapshot persistent_prefix_cache;
    SpeculativeStatsSnapshot speculative;

    bool vision_ready{false};
    std::string mmproj_path;
    bool backend_ready{false};
    size_t backend_device_count{0};
    std::string backend_devices_json;
    std::string backend_load_diagnostic;

    std::string native_lib_dir;
    std::string load_failure_code;
    std::string last_error;
    std::string last_warning;
};

// Formatting utilities
std::string format_runtime_config_json(const RuntimeConfig &config);
std::string format_backend_devices_json();
std::string format_gpu_execution_evidence_json(const GpuExecutionEvidenceSnapshot &evidence);
std::string format_llm_stats_json(const LlmStatsSnapshot &snapshot);

GpuStatsSnapshot make_gpu_stats_snapshot(
        const GpuEvidenceState &s,
        size_t gpu_device_count,
        bool auto_fallback_applied,
        const std::string &auto_fallback_reason);

SamplingStatsSnapshot make_sampling_stats_snapshot(
        float temp, int top_k, float top_p, float min_p,
        float rep_pen, float pres_pen, float freq_pen, int rep_last_n,
        bool jinja, bool thinking, bool reasoning_end);

BackendCapabilitiesSnapshot make_capabilities_snapshot(
        const GpuStatsSnapshot &gpu,
        const std::string &model_architecture,
        int model_mtp_layers);

CacheReuseStatsSnapshot make_cache_reuse_stats_snapshot(
        bool valid, bool hit, int reused_tokens,
        long long hits, long long misses,
        const std::string &reason, const std::string &strategy,
        bool checkpoint_valid, size_t checkpoint_tokens, size_t checkpoint_bytes,
        size_t turn_checkpoints, size_t turn_checkpoint_bytes,
        long long turn_checkpoint_hits, long long turn_checkpoint_misses,
        bool model_hybrid, bool model_recurrent);

PersistentPrefixCacheStatsSnapshot make_persistent_prefix_stats_snapshot(
        bool attempted, bool hit, bool saved, int tokens,
        const std::string &reason);

SpeculativeStatsSnapshot make_speculative_stats_snapshot(
        bool requested, bool context_ready, bool active_for_request,
        long long drafted_tokens, long long accepted_tokens, long long steps,
        const std::string &reason);

/**
 * Facade class encapsulating metric collection and diagnostic JSON construction.
 */
class LlmStatsCollector {
public:
    static std::string format(const LlmStatsSnapshot &snapshot);
    static std::string format_runtime_config(const RuntimeConfig &config);
    static std::string format_backend_devices();
    static std::string format_gpu_evidence(const GpuExecutionEvidenceSnapshot &evidence);
};

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLM_STATS_COLLECTOR_HPP
