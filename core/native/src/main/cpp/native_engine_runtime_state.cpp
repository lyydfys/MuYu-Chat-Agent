// Native-engine runtime state and lifecycle translation unit (T2B16 R7-C5 split).
// Owns every file-scope g_* object of the native_engine family; the JNI shell,
// backend loader, prefix cache, speculative and stats units reach them through
// native_engine_internal.hpp.

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "native_engine_internal.hpp"

#include "mca/llm/llm_prefill_tracker.hpp"
#include "mca/llm/llm_stats_collector.hpp"
#include "mca/llm/llm_turn_cache.hpp"
#include "mca/llm/llama_gpu_evaluator.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "speculative.h"

#include "mca/llm/llama_context_manager.hpp"
#include "mca/llm/llama_model_loader.hpp"
#endif

namespace mca::native_engine {

std::mutex g_mutex;
std::atomic_bool g_stop_requested{false};
std::atomic<std::uint64_t> g_stop_epoch{0};
std::atomic_bool g_generation_active{false};
#if MCA_WITH_LLAMA_CPP
std::atomic_bool g_inference_abort_enabled{false};

bool abort_inference_if_stopped(void *) {
    return g_inference_abort_enabled.load(std::memory_order_acquire) &&
           g_stop_requested.load(std::memory_order_acquire);
}
#endif

std::atomic<GenerationStopReason> g_generation_stop_reason{GenerationStopReason::IDLE};
#if MCA_WITH_LLAMA_CPP
// Declared before the GPU evidence helpers that read them, matching the pre-refactor layout
// (qnn_native_bridge.cpp had these handles immediately above their first use).
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
#endif

bool g_loaded = false;
long long g_load_ms = 0, g_prompt_tokens = 0, g_prefill_computed_tokens = 0, g_completion_tokens = 0;
long long g_context_shifts = 0, g_prefill_started_ms = 0, g_prefill_finished_ms = 0;
long long g_decode_started_ms = 0, g_decode_finished_ms = 0;
std::uint64_t g_generation_sequence = 0;
std::string g_model_path, g_last_error, g_last_warning, g_load_failure_code, g_native_lib_dir, g_backend_load_diagnostic;
std::uint64_t g_model_file_size_bytes = 0;
bool g_mmap_fallback_allowed = false, g_mmap_prefetch_enabled = false;

enum class PersistStage { Idle = 0, Encoding = 1, Writing = 2, Done = 3 };
std::atomic<int> g_persist_stage{static_cast<int>(PersistStage::Idle)};
std::atomic<uint64_t> g_persist_written_bytes{0}, g_persist_total_bytes{0};

void reset_persist_progress() {
    g_persist_stage.store(static_cast<int>(PersistStage::Idle), std::memory_order_release);
    g_persist_written_bytes.store(0, std::memory_order_release);
    g_persist_total_bytes.store(0, std::memory_order_release);
}

void *g_android_opencl_handle = nullptr, *g_mca_opencl_dispatch_handle = nullptr;
using mca::llm::OpenClBeginExecutionScope;
using mca::llm::OpenClExecutionSnapshot;
OpenClBeginExecutionScope g_opencl_begin_execution_scope = nullptr;
OpenClExecutionSnapshot g_opencl_execution_snapshot = nullptr;
std::uint64_t g_opencl_execution_scope = 0, g_opencl_kernel_submissions = 0, g_opencl_kernel_completions = 0;
bool g_opencl_execution_telemetry_available = false, g_gpu_decode_succeeded = false, g_backend_initialized = false;
size_t g_backend_device_count = 0;
int g_n_threads = 0, g_n_threads_batch = 0, g_n_batch = 0, g_n_ubatch = 0, g_max_new_tokens = 0;
float g_sampling_temp = 0.0f, g_sampling_top_p = 0.0f, g_sampling_min_p = 0.0f;
int g_sampling_top_k = 0, g_sampling_repeat_last_n = 0;
float g_sampling_repeat_penalty = 1.08f, g_sampling_presence_penalty = 0.0f, g_sampling_frequency_penalty = 0.2f;
bool g_last_use_jinja = true, g_last_enable_thinking = true, g_prompt_ends_inside_reasoning = false, g_vision_ready = false;
std::string g_mmproj_path;

void reset_prefill_progress() { mca::llm::PrefillProgressTracker::instance().reset(); }
void begin_prefill_progress(size_t total_tokens, size_t completed_tokens) {
    mca::llm::PrefillProgressTracker::instance().begin(total_tokens, completed_tokens);
}
void advance_prefill_progress(size_t decoded_tokens) {
    mca::llm::PrefillProgressTracker::instance().advance(decoded_tokens);
}
void report_reused_prefill_tokens(size_t reused_tokens) {
    mca::llm::PrefillProgressTracker::instance().report_reused(reused_tokens);
}

using mca::llm::RuntimeConfig;
RuntimeConfig g_requested_config, g_effective_config;
size_t g_backend_gpu_device_count = 0;
bool g_gpu_offload_supported = false, g_gpu_offload_active = false;
bool g_gpu_offload_allocation_observed = false, g_gpu_offload_execution_observed = false, g_gpu_offload_layers_known = false;
size_t g_gpu_offload_bytes = 0, g_gpu_offload_model_bytes = 0, g_gpu_offload_context_bytes = 0, g_gpu_offload_compute_bytes = 0;
int g_gpu_offload_layers = 0;
bool g_gpu_auto_fallback_applied = false;
std::string g_gpu_auto_fallback_reason = "not_attempted";
bool g_cache_state_valid = false, g_cache_reuse_hit = false;
int g_cache_reused_tokens = 0;
long long g_cache_reuse_hits = 0, g_cache_reuse_misses = 0;
std::string g_cache_reuse_reason = "not_attempted", g_cache_reuse_strategy = "disabled";
bool g_persistent_prefix_cache_attempted = false, g_persistent_prefix_cache_hit = false, g_persistent_prefix_cache_saved = false;
int g_persistent_prefix_cache_tokens = 0;
std::string g_persistent_prefix_cache_reason = "not_requested";
bool g_cache_checkpoint_valid = false;
size_t g_cache_checkpoint_tokens = 0, g_cache_checkpoint_bytes = 0;
size_t g_turn_cache_checkpoint_count = 0, g_turn_cache_checkpoint_bytes = 0;
long long g_turn_cache_checkpoint_hits = 0, g_turn_cache_checkpoint_misses = 0;
bool g_model_hybrid = false, g_model_recurrent = false;
bool g_spec_requested = false, g_spec_request_active = false, g_spec_context_ready = false, g_spec_done = false;
long long g_spec_drafted_tokens = 0, g_spec_accepted_tokens = 0, g_spec_steps = 0;
std::string g_spec_request_reason = "disabled", g_model_architecture;
int g_model_mtp_layers = 0;

#if MCA_WITH_LLAMA_CPP
constexpr bool LLAMA_RUNTIME_FEATURES_COMPILED = true;
#else
constexpr bool LLAMA_RUNTIME_FEATURES_COMPILED = false;
#endif

std::string g_pending_full_session_write_path;
thread_local PersistentPrefixCacheNativeRequest g_thread_prefix_cache_request;

void set_last_error(const std::string &message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, "MCA", "%s", message.c_str());
}

void clear_load_failure() {
    g_last_error.clear();
    g_last_warning.clear();
    g_load_failure_code.clear();
}

void set_load_failure_code(const char *code) {
    g_load_failure_code = code == nullptr ? "" : code;
}

void set_load_failure(const char *code, const std::string &message) {
    set_load_failure_code(code);
    set_last_error(message);
}

const char *generation_stop_reason_name(GenerationStopReason reason) {
    switch (reason) {
        case GenerationStopReason::IDLE: return "idle";
        case GenerationStopReason::RUNNING: return "running";
        case GenerationStopReason::STOP_REQUESTED: return "stop_requested";
        case GenerationStopReason::STOP_TOKEN: return "stop_token";
        case GenerationStopReason::MAX_NEW_TOKENS: return "max_new_tokens";
        case GenerationStopReason::NORMAL_FINISHED: return "normal_finished";
        case GenerationStopReason::RUNNER_UNAVAILABLE: return "runner_unavailable";
        case GenerationStopReason::BEGIN_FAILED: return "begin_failed";
        case GenerationStopReason::LOAD_FAILED: return "load_failed";
        case GenerationStopReason::GENERATION_FAILED: return "generation_failed";
        case GenerationStopReason::CONTEXT_SHIFT_FAILED: return "context_shift_failed";
        case GenerationStopReason::DECODE_FAILED: return "decode_failed";
        case GenerationStopReason::UNLOADED: return "unloaded";
        case GenerationStopReason::SHUTDOWN: return "shutdown";
    }
    return "unknown";
}

void mark_generation_inactive(GenerationStopReason reason) {
    g_generation_active.store(false, std::memory_order_release);
    g_generation_stop_reason.store(reason, std::memory_order_release);
}

namespace {
void mark_generation_running() {
    g_generation_stop_reason.store(GenerationStopReason::RUNNING, std::memory_order_release);
    g_generation_active.store(true, std::memory_order_release);
}
} // namespace

bool finish_generation_if_active(GenerationStopReason reason) {
    bool expected = true;
    if (!g_generation_active.compare_exchange_strong(
            expected, false, std::memory_order_acq_rel, std::memory_order_acquire)) {
        return false;
    }
    g_generation_stop_reason.store(reason, std::memory_order_release);
    return true;
}

bool mark_generation_running_unless_stopped() {
    if (g_stop_requested.load(std::memory_order_acquire)) {
        mark_generation_inactive(GenerationStopReason::STOP_REQUESTED);
        return false;
    }
    mark_generation_running();
    if (!g_stop_requested.load(std::memory_order_acquire)) {
        return true;
    }
    finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
    return false;
}

long long now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
#if MCA_WITH_LLAMA_CPP
llama_context *g_mtp_context = nullptr;
llama_batch g_batch{};
bool g_batch_ready = false;
common_chat_templates_ptr g_chat_templates;
common_sampler *g_sampler = nullptr;
common_speculative_ptr g_speculative;
mtmd_context *g_mtmd_context = nullptr;
std::vector<common_chat_msg> g_chat_messages;
llama_tokens g_context_tokens;
llama_tokens g_cache_checkpoint_prefix;
std::vector<uint8_t> g_cache_checkpoint_data;
size_t g_cache_checkpoint_threshold = 0;
using mca::llm::TurnCacheCheckpoint;
using mca::llm::MAX_TURN_CACHE_CHECKPOINTS;
using mca::llm::MAX_TURN_CACHE_CHECKPOINT_BYTES;
using mca::llm::turn_cache_checkpoint_bytes;
std::vector<TurnCacheCheckpoint> g_turn_cache_checkpoints;
llama_tokens g_spec_prompt_tokens, g_spec_draft_tokens;
llama_token g_spec_pending_token = LLAMA_TOKEN_NULL;
bool g_spec_pending_valid = false;
llama_pos g_current_position = 0;
int g_n_ctx = 4096, g_n_predict = 8192, g_n_keep = 128;
std::string g_cached_token_chars;
#endif
// Mode-0 compile boundary (restored): everything from the session-state reset down to the load-result
// transfer is backed by llama.cpp state and the llama-only mca/llm units
// (llama_model_loader / llama_gpu_evaluator / llama_completion_planner / llama_speculative_engine /
// llm_runtime_config / llm_backend_loader), which the stub configuration does not compile.  The
// #else branch below provides only the bookkeeping the remaining JNI entry points need, and it never
// fabricates a loaded session or a token stream.
#if MCA_WITH_LLAMA_CPP
namespace {
void reset_engine_runtime_state_locked() {
    g_chat_messages.clear(); g_context_tokens.clear();
    g_spec_prompt_tokens.clear(); g_spec_draft_tokens.clear();
    g_spec_pending_token = LLAMA_TOKEN_NULL; g_spec_pending_valid = false; g_spec_done = false;
    g_current_position = 0; g_cached_token_chars.clear();
    g_n_threads = 0; g_n_threads_batch = 0; g_n_batch = 0; g_n_ubatch = 0; g_max_new_tokens = 0;
    g_sampling_temp = 0.0f; g_sampling_top_k = 0; g_sampling_top_p = 0.0f; g_sampling_min_p = 0.0f;
    g_sampling_repeat_penalty = 1.08f; g_sampling_presence_penalty = 0.0f; g_sampling_frequency_penalty = 0.2f;
    g_sampling_repeat_last_n = 0; g_last_use_jinja = true; g_last_enable_thinking = true;
    g_vision_ready = false; g_mmproj_path.clear(); g_context_shifts = 0; g_n_keep = 128;
    g_cache_state_valid = false; g_cache_reuse_hit = false; g_cache_reused_tokens = 0;
    g_cache_reuse_reason = "model_unloaded";
    g_persistent_prefix_cache_attempted = false; g_persistent_prefix_cache_hit = false;
    g_persistent_prefix_cache_saved = false; g_persistent_prefix_cache_tokens = 0;
    g_persistent_prefix_cache_reason = "model_unloaded";
    g_model_hybrid = false; g_model_recurrent = false; g_cache_reuse_strategy = "disabled";
    g_spec_context_ready = false;
    g_spec_request_reason = g_spec_requested ? "model_unloaded" : "disabled";
}
} // namespace
void free_llama_locked() {
    g_speculative.reset();
    g_spec_request_active = false;
    clear_gpu_offload_evidence_locked();
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
#if MCA_WITH_LLAMA_CPP
    mca::llm::LlamaModelLoader::free_llama_resources(
            g_model, g_context, g_mtp_context,
            g_batch, g_batch_ready, g_chat_templates,
            g_sampler, g_mtmd_context);
#endif
    reset_engine_runtime_state_locked();
}
bool shift_context_locked() {
    if (g_context == nullptr) return false;
    if (g_spec_request_active) {
        g_last_error += "\nContext shifting is not supported while draft-mtp is active.";
        return false;
    }
    const bool ok = mca::llm::LlamaContextManager::shift_context(
            g_context, g_current_position, g_n_ctx, g_n_keep, OVERFLOW_HEADROOM,
            g_context_shifts, g_last_error);
    if (!ok) return false;
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
    g_context_tokens.clear();
    g_cache_state_valid = false;
    g_cache_reuse_reason = "invalidated_by_context_shift";
    return true;
}
void clear_target_context_locked(const std::string &reason) {
    if (g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
    g_current_position = 0;
    g_cache_state_valid = false;
    g_cache_reuse_reason = reason;
    g_context_tokens.clear();
}
bool trim_context_locked(llama_context *ctx, llama_pos position, const char *label) {
    return mca::llm::LlamaContextManager::trim_context(ctx, position, label, &g_last_error);
}
#else
// Stub build (MCA_WITH_LLAMA_CPP=0): the same session teardown contract, without llama state.
// Nothing is ever loaded, so these helpers only keep the reported bookkeeping consistent.
namespace {
void reset_engine_runtime_state_locked() {
    g_spec_request_reason = g_spec_requested ? "model_unloaded" : "disabled";
    g_spec_context_ready = false;
    g_spec_request_active = false; g_spec_done = false;
    g_cache_reuse_reason = "model_unloaded";
    g_persistent_prefix_cache_reason = "model_unloaded";
    g_cache_reuse_strategy = "disabled";
    g_cache_reuse_hit = false; g_cache_reused_tokens = 0;
    g_vision_ready = false; g_mmproj_path.clear();
    g_model_architecture.clear(); g_model_mtp_layers = 0;
}
} // namespace

void free_llama_locked() {
    reset_engine_runtime_state_locked();
}

bool shift_context_locked() { return false; }

void clear_target_context_locked(const std::string &) {}

bool trim_context_locked(llama_context *, llama_pos, const char *) { return false; }
#endif
} // namespace mca::native_engine
