// Native-engine backend/device enumeration, llama log bridge and model-load
// orchestration (T2B16 R7-C5 split).

#include <android/log.h>
#include <cstddef>
#include <cstdint>
#include <string>

#include "native_engine_internal.hpp"

#include "mca/llm/llm_stats_collector.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "mca/llm/llama_model_loader.hpp"
#endif

namespace mca::native_engine {

std::string backend_devices_json_array() {
#if MCA_WITH_LLAMA_CPP
    return mca::llm::format_backend_devices_json();
#else
    // Stub build: no llama/ggml backend is linked, so the device inventory is honestly empty.
    return "[]";
#endif
}

#if MCA_WITH_LLAMA_CPP
void android_llama_log(ggml_log_level level, const char *text, void *) {
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                     level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(prio, "MCA-llama", "%s", text);
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        if (g_last_error.size() < 4096) g_last_error += text;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        if (g_last_warning.size() < 4096) g_last_warning += text;
    }
}
#endif

#if MCA_WITH_LLAMA_CPP
namespace {
// Non-const: the load result owns the llama.cpp handles (common_chat_templates_ptr is a
// unique_ptr once the real llama.cpp headers are in play), and this function transfers that
// ownership to the module globals.  The single caller passes a local ModelLoadResult.
void apply_load_result_locked(mca::llm::ModelLoadResult &res) {
    g_requested_config = res.requested_config; g_effective_config = res.effective_config;
    g_spec_requested = res.spec_requested; g_spec_context_ready = res.spec_context_ready;
    g_spec_request_active = false; g_spec_done = false;
    g_spec_drafted_tokens = 0; g_spec_accepted_tokens = 0; g_spec_steps = 0;
    g_spec_request_reason = g_spec_requested ? "awaiting_text_request" : "disabled";
    g_cache_reuse_hits = 0; g_cache_reuse_misses = 0;
    g_turn_cache_checkpoint_hits = 0; g_turn_cache_checkpoint_misses = 0;
    g_cache_reuse_hit = false; g_cache_reused_tokens = 0;
    g_cache_reuse_reason = g_requested_config.spec_type == "draft-mtp" && g_requested_config.cache_reuse > 0
                           ? "disabled_by_draft_mtp"
                           : (g_effective_config.cache_reuse > 0 ? "no_cached_prefix" : "disabled");
    g_mmap_fallback_allowed = res.mmap_fallback_allowed; g_mmap_prefetch_enabled = res.mmap_prefetch_enabled;
    g_gpu_auto_fallback_applied = res.gpu_auto_fallback_applied; g_gpu_auto_fallback_reason = res.gpu_auto_fallback_reason;
    if (!res.error_message.empty()) g_last_error = res.error_message;
    if (!res.failure_code.empty()) set_load_failure_code(res.failure_code.c_str());
    if (res.model == nullptr) return;

    g_model = res.model; g_context = res.context; g_mtp_context = res.mtp_context;
    g_batch = res.batch; g_batch_ready = res.batch_ready;
    g_chat_templates = std::move(res.chat_templates); g_sampler = res.sampler;
    g_mtmd_context = res.mtmd_context; g_vision_ready = res.vision_ready;
    g_mmproj_path = res.mmproj_path; g_model_architecture = res.model_architecture;
    g_model_mtp_layers = res.model_mtp_layers; g_model_hybrid = res.model_hybrid;
    g_model_recurrent = res.model_recurrent;
    g_n_ctx = res.n_ctx; g_n_batch = res.n_batch; g_n_ubatch = res.n_ubatch;
    g_n_threads = res.n_threads; g_n_threads_batch = res.n_threads_batch;
    g_sampling_temp = res.sampling_temp; g_sampling_top_k = res.sampling_top_k;
    g_sampling_top_p = res.sampling_top_p; g_sampling_min_p = res.sampling_min_p;
    g_sampling_repeat_penalty = res.sampling_repeat_penalty;
    g_sampling_presence_penalty = res.sampling_presence_penalty;
    g_sampling_frequency_penalty = res.sampling_frequency_penalty;
    g_sampling_repeat_last_n = res.sampling_repeat_last_n;
}
} // namespace
#endif

int perform_model_load_locked(const std::string &path, const std::string &params) {
#if MCA_WITH_LLAMA_CPP
        free_llama_locked();
        const long long started = now_ms();

        if (!mca::llm::LlamaModelLoader::ensure_backends_loaded(
                g_native_lib_dir,
                g_android_opencl_handle,
                g_mca_opencl_dispatch_handle,
                g_backend_initialized,
                g_backend_device_count,
                g_backend_gpu_device_count,
                g_gpu_offload_supported,
                g_backend_load_diagnostic,
                g_last_error)) {
            set_load_failure_code("MCA_LOAD_BACKEND_UNAVAILABLE");
            return 11;
        }

        mca::llm::ModelLoadRequest req;
        req.model_path = path;
        req.params_json = params;
        req.native_lib_dir = g_native_lib_dir;
        req.gpu_offload_supported = g_gpu_offload_supported;
        req.backend_gpu_device_count = g_backend_gpu_device_count;
        req.backend_load_diagnostic = g_backend_load_diagnostic;
        req.abort_callback = abort_inference_if_stopped;

        mca::llm::ModelLoadResult res;
        const int load_rc = mca::llm::LlamaModelLoader::load_model(req, res);
        apply_load_result_locked(res);
        if (load_rc != 0) return load_rc;

        refresh_prefix_cache_strategy_locked();
        refresh_gpu_offload_evidence_locked();
        g_load_ms = now_ms() - started;
        g_loaded = true;
        g_stop_requested.store(false, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::IDLE);
        return 0;
#else
        (void) params;
        // Stub build: no llama/ggml backend is linked.  Report the original "backend unavailable"
        // load failure instead of pretending a model was loaded.
        g_loaded = false;
        g_load_ms = 0;
        g_stop_requested.store(false, std::memory_order_relaxed);
        set_load_failure("MCA_LOAD_BACKEND_UNAVAILABLE",
                         "loadModel failed: this build has no llama.cpp backend (MCA_WITH_LLAMA_CPP=0).");
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
        return 11;
#endif
}
} // namespace mca::native_engine
