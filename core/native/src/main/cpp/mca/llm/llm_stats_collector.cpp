#include "mca/llm/llm_stats_collector.hpp"

#include <iomanip>
#include <sstream>

#include "mca/request/llama_request_parser.hpp"

#if MCA_WITH_LLAMA_CPP
#include "ggml-backend.h"
#endif

namespace mca {
namespace llm {

std::string format_runtime_config_json(const RuntimeConfig &config) {
    std::ostringstream out;
    out << "{"
        << "\"n_ctx\":" << config.n_ctx << ","
        << "\"n_threads\":" << config.n_threads << ","
        << "\"n_threads_batch\":" << config.n_threads_batch << ","
        << "\"n_batch\":" << config.n_batch << ","
        << "\"n_ubatch\":" << config.n_ubatch << ","
        << "\"n_gpu_layers\":" << config.n_gpu_layers << ","
        << "\"main_gpu\":" << config.main_gpu << ","
        << "\"split_mode\":\"" << mca::request::json_escape(config.split_mode) << "\","
        << "\"n_cpu_moe\":" << config.n_cpu_moe << ","
        << "\"cache_type_k\":\"" << mca::request::json_escape(config.cache_type_k) << "\","
        << "\"cache_type_v\":\"" << mca::request::json_escape(config.cache_type_v) << "\","
        << "\"flash_attn\":\"" << mca::request::json_escape(config.flash_attn) << "\","
        << "\"perf\":" << (config.perf ? "true" : "false") << ","
        << "\"n_parallel\":" << config.n_parallel << ","
        << "\"cache_reuse\":" << config.cache_reuse << ","
        << "\"spec_type\":\"" << mca::request::json_escape(config.spec_type) << "\","
        << "\"spec_draft_n_max\":" << config.spec_draft_n_max << ","
        << "\"mmap\":" << (config.mmap ? "true" : "false") << ","
        << "\"mlock\":" << (config.mlock ? "true" : "false")
        << "}";
    return out.str();
}

std::string format_backend_devices_json() {
#if MCA_WITH_LLAMA_CPP
    std::ostringstream out;
    out << "[";
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; i++) {
        if (i > 0) out << ",";
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        out << "{"
            << "\"name\":\"" << mca::request::json_escape(ggml_backend_dev_name(dev) == nullptr ? "" : ggml_backend_dev_name(dev)) << "\","
            << "\"description\":\"" << mca::request::json_escape(ggml_backend_dev_description(dev) == nullptr ? "" : ggml_backend_dev_description(dev)) << "\","
            << "\"type\":" << (int) ggml_backend_dev_type(dev)
            << "}";
    }
    out << "]";
    return out.str();
#else
    return "[]";
#endif
}

std::string format_gpu_execution_evidence_json(const GpuExecutionEvidenceSnapshot &evidence) {
    std::ostringstream out;
    out << "{"
        << "\"source\":\"" << evidence.source << "\","
        << "\"status\":\"" << (evidence.telemetry_available ? "observed_counters" : "unknown") << "\","
        << "\"requestScope\":" << evidence.request_scope << ","
        << "\"kernelSubmissions\":" << (evidence.telemetry_available ? std::to_string(evidence.kernel_submissions) : "null") << ","
        << "\"kernelCompletions\":" << (evidence.telemetry_available ? std::to_string(evidence.kernel_completions) : "null")
        << "}";
    return out.str();
}

std::string format_llm_stats_json(const LlmStatsSnapshot &snapshot) {
    const long long prefill_ms = snapshot.prefill_finished_ms > snapshot.prefill_started_ms
                                  ? snapshot.prefill_finished_ms - snapshot.prefill_started_ms : 0;
    const long long decode_ms = snapshot.decode_finished_ms > snapshot.decode_started_ms
                                ? snapshot.decode_finished_ms - snapshot.decode_started_ms : 0;
    const double tps = decode_ms > 0 ? (double) snapshot.completion_tokens * 1000.0 / (double) decode_ms : 0.0;
    const double prefill_tps = prefill_ms > 0
                               ? (double) snapshot.prefill_computed_tokens * 1000.0 / (double) prefill_ms : 0.0;
    const double effective_prompt_tps = prefill_ms > 0
                               ? (double) snapshot.prompt_tokens * 1000.0 / (double) prefill_ms : 0.0;

    std::string backend_devices_str = snapshot.backend_devices_json;
    if (backend_devices_str.empty()) {
        backend_devices_str = format_backend_devices_json();
    }

    std::ostringstream out;
    out << "{"
        << "\"backend\":\"" << snapshot.backend << "\","
        << "\"loaded\":" << (snapshot.loaded ? "true" : "false") << ","
        << "\"modelPath\":\"" << mca::request::json_escape(snapshot.model_path) << "\","
        << "\"modelFileSizeBytes\":" << snapshot.model_file_size_bytes << ","
        << "\"mmapFallbackAllowed\":" << (snapshot.mmap_fallback_allowed ? "true" : "false") << ","
        << "\"mmapPrefetchEnabled\":" << (snapshot.mmap_prefetch_enabled ? "true" : "false") << ","
        << "\"mmap\":" << (snapshot.effective_config.mmap ? "true" : "false") << ","
        << "\"mlock\":" << (snapshot.effective_config.mlock ? "true" : "false") << ","
        << "\"loadMs\":" << snapshot.load_ms << ","
        << "\"promptTokens\":" << snapshot.prompt_tokens << ","
        << "\"prefillTokens\":" << snapshot.prefill_computed_tokens << ","
        << "\"completionTokens\":" << snapshot.completion_tokens << ","
        << "\"generationSequence\":" << snapshot.generation_sequence << ","
        << "\"generationActive\":"
        << (snapshot.generation_active ? "true" : "false") << ","
        << "\"stopRequested\":"
        << (snapshot.stop_requested ? "true" : "false") << ","
        << "\"generationStopReason\":\""
        << snapshot.generation_stop_reason
        << "\","
        << "\"contextShifts\":" << snapshot.context_shifts << ","
        << "\"prefillMs\":" << prefill_ms << ","
        << "\"prefillTps\":" << prefill_tps << ","
        << "\"effectivePromptTps\":" << effective_prompt_tps << ","
        << "\"decodeMs\":" << decode_ms << ","
        << "\"decodeTps\":" << tps << ","
        << "\"nThreads\":" << snapshot.n_threads << ","
        << "\"nThreadsBatch\":" << snapshot.n_threads_batch << ","
        << "\"nBatch\":" << snapshot.n_batch << ","
        << "\"nUbatch\":" << snapshot.n_ubatch << ","
        << "\"nCtx\":" << snapshot.effective_config.n_ctx << ","
        << "\"maxAllTokens\":" << snapshot.effective_config.n_ctx << ","
        << "\"maxNewTokens\":" << snapshot.max_new_tokens << ","
        << "\"nGpuLayers\":" << snapshot.effective_config.n_gpu_layers << ","
        << "\"gpuOffloadActive\":" << (snapshot.gpu.offload_active ? "true" : "false") << ","
        << "\"gpuOffloadAllocationObserved\":"
        << (snapshot.gpu.allocation_observed ? "true" : "false") << ","
        << "\"gpuOffloadExecutionObserved\":"
        << (snapshot.gpu.execution_observed ? "true" : "false") << ","
        << "\"gpuExecutionEvidence\":{"
        << "\"source\":\"" << snapshot.gpu.execution_evidence.source << "\","
        << "\"status\":\"" << (snapshot.gpu.execution_evidence.telemetry_available ? "observed_counters" : "unknown") << "\","
        << "\"requestScope\":" << snapshot.gpu.execution_evidence.request_scope << ","
        << "\"kernelSubmissions\":" << (snapshot.gpu.execution_evidence.telemetry_available ? std::to_string(snapshot.gpu.execution_evidence.kernel_submissions) : "null") << ","
        << "\"kernelCompletions\":" << (snapshot.gpu.execution_evidence.telemetry_available ? std::to_string(snapshot.gpu.execution_evidence.kernel_completions) : "null") << "},"
        << "\"gpuOffloadBytes\":" << snapshot.gpu.offload_bytes << ","
        << "\"gpuOffloadModelBytes\":" << snapshot.gpu.offload_model_bytes << ","
        << "\"gpuOffloadContextBytes\":" << snapshot.gpu.offload_context_bytes << ","
        << "\"gpuOffloadComputeBytes\":" << snapshot.gpu.offload_compute_bytes << ","
        << "\"gpuOffloadLayers\":" << snapshot.gpu.offload_layers << ","
        << "\"gpuOffloadLayersKnown\":"
        << (snapshot.gpu.offload_layers_known ? "true" : "false") << ","
        << "\"gpuAutoFallbackApplied\":"
        << (snapshot.gpu.auto_fallback_applied ? "true" : "false") << ","
        << "\"gpuAutoFallbackReason\":\""
        << mca::request::json_escape(snapshot.gpu.auto_fallback_reason) << "\","
        << "\"mainGpu\":" << snapshot.effective_config.main_gpu << ","
        << "\"splitMode\":\"" << mca::request::json_escape(snapshot.effective_config.split_mode) << "\","
        << "\"nCpuMoe\":" << snapshot.effective_config.n_cpu_moe << ","
        << "\"cacheTypeK\":\"" << mca::request::json_escape(snapshot.effective_config.cache_type_k) << "\","
        << "\"cacheTypeV\":\"" << mca::request::json_escape(snapshot.effective_config.cache_type_v) << "\","
        << "\"flashAttn\":\"" << mca::request::json_escape(snapshot.effective_config.flash_attn) << "\","
        << "\"perf\":" << (snapshot.effective_config.perf ? "true" : "false") << ","
        << "\"nParallel\":" << snapshot.effective_config.n_parallel << ","
        << "\"cacheReuseThreshold\":" << snapshot.effective_config.cache_reuse << ","
        << "\"specType\":\"" << mca::request::json_escape(snapshot.effective_config.spec_type) << "\","
        << "\"specDraftNMax\":" << snapshot.effective_config.spec_draft_n_max << ","
        << "\"temperature\":" << snapshot.sampling.temperature << ","
        << "\"topK\":" << snapshot.sampling.top_k << ","
        << "\"topP\":" << snapshot.sampling.top_p << ","
        << "\"minP\":" << snapshot.sampling.min_p << ","
        << "\"repeatPenalty\":" << snapshot.sampling.repeat_penalty << ","
        << "\"presencePenalty\":" << snapshot.sampling.presence_penalty << ","
        << "\"frequencyPenalty\":" << snapshot.sampling.frequency_penalty << ","
        << "\"repeatLastN\":" << snapshot.sampling.repeat_last_n << ","
        << "\"useJinja\":" << (snapshot.sampling.use_jinja ? "true" : "false") << ","
        << "\"enableThinking\":" << (snapshot.sampling.enable_thinking ? "true" : "false") << ","
        << "\"promptEndsInsideReasoning\":"
        << (snapshot.sampling.prompt_ends_inside_reasoning ? "true" : "false") << ","
        << "\"visionReady\":" << (snapshot.vision_ready ? "true" : "false") << ","
        << "\"mmprojPath\":\"" << mca::request::json_escape(snapshot.mmproj_path) << "\","
        << "\"backendReady\":" << (snapshot.backend_ready ? "true" : "false") << ","
        << "\"backendDeviceCount\":" << snapshot.backend_device_count << ","
        << "\"backendDevices\":" << backend_devices_str << ","
        << "\"backendLoadDiagnostic\":\"" << mca::request::json_escape(snapshot.backend_load_diagnostic) << "\","
        << "\"requestedConfig\":" << format_runtime_config_json(snapshot.requested_config) << ","
        << "\"effectiveConfig\":" << format_runtime_config_json(snapshot.effective_config) << ","
        << "\"backendCapabilities\":{"
        << "\"gpuOffloadSupported\":" << (snapshot.capabilities.gpu_offload_supported ? "true" : "false") << ","
        << "\"gpuDeviceCount\":" << snapshot.capabilities.gpu_device_count << ","
        << "\"gpuOffloadActive\":" << (snapshot.capabilities.gpu_offload_active ? "true" : "false") << ","
        << "\"gpuOffloadAllocationObserved\":"
        << (snapshot.capabilities.gpu_offload_allocation_observed ? "true" : "false") << ","
        << "\"gpuOffloadExecutionObserved\":"
        << (snapshot.capabilities.gpu_offload_execution_observed ? "true" : "false") << ","
        << "\"gpuOffloadBytes\":" << snapshot.capabilities.gpu_offload_bytes << ","
        << "\"gpuOffloadLayers\":" << snapshot.capabilities.gpu_offload_layers << ","
        << "\"gpuOffloadLayersKnown\":"
        << (snapshot.capabilities.gpu_offload_layers_known ? "true" : "false") << ","
        << "\"modelArchitecture\":\"" << mca::request::json_escape(snapshot.capabilities.model_architecture) << "\","
        << "\"modelMtpLayers\":" << snapshot.capabilities.model_mtp_layers << ","
        << "\"dynamicBackendLoading\":" << (snapshot.capabilities.dynamic_backend_loading ? "true" : "false") << ","
        << "\"kvCacheQuantization\":" << (snapshot.capabilities.kv_cache_quantization ? "true" : "false") << ","
        << "\"draftMtpCompiled\":" << (snapshot.capabilities.draft_mtp_compiled ? "true" : "false")
        << "},"
        << "\"cacheReuse\":{"
        << "\"valid\":" << (snapshot.cache_reuse.valid ? "true" : "false") << ","
        << "\"hit\":" << (snapshot.cache_reuse.hit ? "true" : "false") << ","
        << "\"reusedTokens\":" << snapshot.cache_reuse.reused_tokens << ","
        << "\"hits\":" << snapshot.cache_reuse.hits << ","
        << "\"misses\":" << snapshot.cache_reuse.misses << ","
        << "\"reason\":\"" << mca::request::json_escape(snapshot.cache_reuse.reason) << "\","
        << "\"strategy\":\"" << mca::request::json_escape(snapshot.cache_reuse.strategy) << "\","
        << "\"checkpointValid\":" << (snapshot.cache_reuse.checkpoint_valid ? "true" : "false") << ","
        << "\"checkpointTokens\":" << snapshot.cache_reuse.checkpoint_tokens << ","
        << "\"checkpointBytes\":" << snapshot.cache_reuse.checkpoint_bytes << ","
        << "\"turnCheckpoints\":" << snapshot.cache_reuse.turn_checkpoints << ","
        << "\"turnCheckpointBytes\":" << snapshot.cache_reuse.turn_checkpoint_bytes << ","
        << "\"turnCheckpointHits\":" << snapshot.cache_reuse.turn_checkpoint_hits << ","
        << "\"turnCheckpointMisses\":" << snapshot.cache_reuse.turn_checkpoint_misses << ","
        << "\"modelHybrid\":" << (snapshot.cache_reuse.model_hybrid ? "true" : "false") << ","
        << "\"modelRecurrent\":" << (snapshot.cache_reuse.model_recurrent ? "true" : "false")
        << "},"
        << "\"persistentPrefixCache\":{"
        << "\"attempted\":" << (snapshot.persistent_prefix_cache.attempted ? "true" : "false") << ","
        << "\"hit\":" << (snapshot.persistent_prefix_cache.hit ? "true" : "false") << ","
        << "\"saved\":" << (snapshot.persistent_prefix_cache.saved ? "true" : "false") << ","
        << "\"tokens\":" << snapshot.persistent_prefix_cache.tokens << ","
        << "\"reason\":\"" << mca::request::json_escape(snapshot.persistent_prefix_cache.reason) << "\""
        << "},"
        << "\"speculative\":{"
        << "\"requested\":" << (snapshot.speculative.requested ? "true" : "false") << ","
        << "\"contextReady\":" << (snapshot.speculative.context_ready ? "true" : "false") << ","
        << "\"activeForRequest\":" << (snapshot.speculative.active_for_request ? "true" : "false") << ","
        << "\"draftedTokens\":" << snapshot.speculative.drafted_tokens << ","
        << "\"acceptedTokens\":" << snapshot.speculative.accepted_tokens << ","
        << "\"steps\":" << snapshot.speculative.steps << ","
        << "\"acceptanceRate\":" << (snapshot.speculative.drafted_tokens > 0 ? (double) snapshot.speculative.accepted_tokens / (double) snapshot.speculative.drafted_tokens : 0.0) << ","
        << "\"reason\":\"" << mca::request::json_escape(snapshot.speculative.reason) << "\""
        << "},"
        << "\"nativeLibDir\":\"" << mca::request::json_escape(snapshot.native_lib_dir) << "\","
        << "\"loadFailureCode\":\"" << mca::request::json_escape(snapshot.load_failure_code) << "\","
        << "\"lastError\":\"" << mca::request::json_escape(snapshot.last_error) << "\","
        << "\"lastWarning\":\"" << mca::request::json_escape(snapshot.last_warning) << "\""
        << "}";
    return out.str();
}

std::string LlmStatsCollector::format(const LlmStatsSnapshot &snapshot) {
    return format_llm_stats_json(snapshot);
}

std::string LlmStatsCollector::format_runtime_config(const RuntimeConfig &config) {
    return format_runtime_config_json(config);
}

std::string LlmStatsCollector::format_backend_devices() {
    return format_backend_devices_json();
}

std::string LlmStatsCollector::format_gpu_evidence(const GpuExecutionEvidenceSnapshot &evidence) {
    return format_gpu_execution_evidence_json(evidence);
}

} // namespace llm
} // namespace mca
