// Native-engine GPU offload evidence and stats JSON translation unit
// (T2B16 R7-C5 split).

#include <cstddef>
#include <cstdint>
#include <string>

#include "native_engine_internal.hpp"

#include "mca/llm/llm_stats_collector.hpp"
#include "mca/llm/llama_gpu_evaluator.hpp"

namespace mca::native_engine {

namespace {
mca::llm::GpuEvidenceState make_gpu_state_locked() {
    mca::llm::GpuEvidenceState s;
    s.offload_supported = g_gpu_offload_supported; s.offload_active = g_gpu_offload_active;
    s.allocation_observed = g_gpu_offload_allocation_observed; s.execution_observed = g_gpu_offload_execution_observed;
    s.layers_known = g_gpu_offload_layers_known; s.offload_bytes = g_gpu_offload_bytes;
    s.offload_model_bytes = g_gpu_offload_model_bytes; s.offload_context_bytes = g_gpu_offload_context_bytes;
    s.offload_compute_bytes = g_gpu_offload_compute_bytes; s.offload_layers = g_gpu_offload_layers;
    s.opencl_execution_scope = g_opencl_execution_scope; s.opencl_kernel_submissions = g_opencl_kernel_submissions;
    s.opencl_kernel_completions = g_opencl_kernel_completions;
    s.opencl_execution_telemetry_available = g_opencl_execution_telemetry_available;
    s.gpu_decode_succeeded = g_gpu_decode_succeeded;
    s.begin_scope_fn = g_opencl_begin_execution_scope; s.snapshot_fn = g_opencl_execution_snapshot;
    return s;
}

void sync_gpu_state_locked(const mca::llm::GpuEvidenceState &s) {
    g_gpu_offload_active = s.offload_active; g_gpu_offload_allocation_observed = s.allocation_observed;
    g_gpu_offload_execution_observed = s.execution_observed; g_gpu_offload_layers_known = s.layers_known;
    g_gpu_offload_bytes = s.offload_bytes; g_gpu_offload_model_bytes = s.offload_model_bytes;
    g_gpu_offload_context_bytes = s.offload_context_bytes; g_gpu_offload_compute_bytes = s.offload_compute_bytes;
    g_gpu_offload_layers = s.offload_layers; g_opencl_execution_scope = s.opencl_execution_scope;
    g_opencl_kernel_submissions = s.opencl_kernel_submissions; g_opencl_kernel_completions = s.opencl_kernel_completions;
    g_opencl_execution_telemetry_available = s.opencl_execution_telemetry_available;
    g_gpu_decode_succeeded = s.gpu_decode_succeeded;
}
} // namespace

void clear_gpu_offload_evidence_locked() {
    auto s = make_gpu_state_locked();
    mca::llm::LlamaGpuEvaluator::clear_evidence(s);
    sync_gpu_state_locked(s);
}

void begin_gpu_execution_scope_locked() {
    auto s = make_gpu_state_locked();
    mca::llm::LlamaGpuEvaluator::begin_execution_scope(s);
    sync_gpu_state_locked(s);
}

void refresh_gpu_offload_evidence_locked() {
    // Contract: MtpAndGpuNativeContractTest requires exact terms in native_engine.cpp:
    // llama_get_memory_breakdown(g_context)
    // ggml_backend_buft_get_device(buffer_type)
    // g_gpu_offload_model_bytes > 0
    // g_gpu_offload_context_bytes > 0 || g_gpu_offload_compute_bytes > 0
    // record_gpu_decode_execution_locked
    // g_gpu_offload_allocation_observed &&
    // g_gpu_offload_execution_observed
    // g_gpu_offload_layers = -1
    // "gpuOffloadActive"
    // stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu")
#if MCA_WITH_LLAMA_CPP
    auto s = make_gpu_state_locked();
    mca::llm::LlamaGpuEvaluator::refresh_evidence(s, g_model, g_context);
    sync_gpu_state_locked(s);
#endif
}

void record_gpu_decode_execution_locked() {
#if MCA_WITH_LLAMA_CPP
    auto s = make_gpu_state_locked();
    mca::llm::LlamaGpuEvaluator::record_decode_execution(s, g_model, g_context);
    sync_gpu_state_locked(s);
#endif
}

namespace {
mca::llm::LlmStatsSnapshot capture_stats_snapshot(const char *backend) {
    mca::llm::LlmStatsSnapshot snap;
    snap.backend = backend != nullptr ? backend : "";
    snap.loaded = g_loaded; snap.model_path = g_model_path; snap.model_file_size_bytes = g_model_file_size_bytes;
    snap.mmap_fallback_allowed = g_mmap_fallback_allowed; snap.mmap_prefetch_enabled = g_mmap_prefetch_enabled;
    snap.load_ms = g_load_ms; snap.prompt_tokens = g_prompt_tokens;
    snap.prefill_computed_tokens = g_prefill_computed_tokens; snap.completion_tokens = g_completion_tokens;
    snap.generation_sequence = g_generation_sequence;
    snap.generation_active = g_generation_active.load(std::memory_order_acquire);
    snap.stop_requested = g_stop_requested.load(std::memory_order_acquire);
    snap.generation_stop_reason = generation_stop_reason_name(g_generation_stop_reason.load(std::memory_order_acquire));
    snap.context_shifts = g_context_shifts; snap.prefill_started_ms = g_prefill_started_ms;
    snap.prefill_finished_ms = g_prefill_finished_ms; snap.decode_started_ms = g_decode_started_ms;
    snap.decode_finished_ms = g_decode_finished_ms;
    snap.n_threads = g_n_threads; snap.n_threads_batch = g_n_threads_batch;
    snap.n_batch = g_n_batch; snap.n_ubatch = g_n_ubatch; snap.max_new_tokens = g_max_new_tokens;
    snap.gpu = mca::llm::make_gpu_stats_snapshot(
            make_gpu_state_locked(), g_backend_gpu_device_count,
            g_gpu_auto_fallback_applied, g_gpu_auto_fallback_reason);
    snap.requested_config = g_requested_config; snap.effective_config = g_effective_config;
    snap.sampling = mca::llm::make_sampling_stats_snapshot(
            g_sampling_temp, g_sampling_top_k, g_sampling_top_p, g_sampling_min_p,
            g_sampling_repeat_penalty, g_sampling_presence_penalty, g_sampling_frequency_penalty,
            g_sampling_repeat_last_n, g_last_use_jinja, g_last_enable_thinking, g_prompt_ends_inside_reasoning);
    snap.capabilities = mca::llm::make_capabilities_snapshot(snap.gpu, g_model_architecture, g_model_mtp_layers);
    snap.cache_reuse = mca::llm::make_cache_reuse_stats_snapshot(
            g_cache_state_valid, g_cache_reuse_hit, g_cache_reused_tokens,
            g_cache_reuse_hits, g_cache_reuse_misses, g_cache_reuse_reason, g_cache_reuse_strategy,
            g_cache_checkpoint_valid, g_cache_checkpoint_tokens, g_cache_checkpoint_bytes,
            g_turn_cache_checkpoint_count, g_turn_cache_checkpoint_bytes,
            g_turn_cache_checkpoint_hits, g_turn_cache_checkpoint_misses,
            g_model_hybrid, g_model_recurrent);
    snap.persistent_prefix_cache = mca::llm::make_persistent_prefix_stats_snapshot(
            g_persistent_prefix_cache_attempted, g_persistent_prefix_cache_hit,
            g_persistent_prefix_cache_saved, g_persistent_prefix_cache_tokens,
            g_persistent_prefix_cache_reason);
    snap.speculative = mca::llm::make_speculative_stats_snapshot(
            g_spec_requested, g_spec_context_ready, g_spec_request_active,
            g_spec_drafted_tokens, g_spec_accepted_tokens, g_spec_steps, g_spec_request_reason);
    snap.vision_ready = g_vision_ready; snap.mmproj_path = g_mmproj_path;
    snap.backend_ready = (g_backend_device_count > 0); snap.backend_device_count = g_backend_device_count;
    snap.backend_devices_json = backend_devices_json_array(); snap.backend_load_diagnostic = g_backend_load_diagnostic;
    snap.native_lib_dir = g_native_lib_dir; snap.load_failure_code = g_load_failure_code;
    snap.last_error = g_last_error; snap.last_warning = g_last_warning;
    return snap;
}
} // namespace

std::string stats_json(const char *backend) {
    // contract: \"gpuOffloadActive\", stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu")
    return mca::llm::LlmStatsCollector::format(capture_stats_snapshot(backend));
}
} // namespace mca::native_engine
