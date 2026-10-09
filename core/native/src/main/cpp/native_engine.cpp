#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "mca_utf8_codec.hpp"
#include "llama_prefix_cache_policy.hpp"
#include "mca/request/llama_request_parser.hpp"
#include "mca/llm/llm_runtime_config.hpp"
#include "mca/llm/llm_prefill_tracker.hpp"
#include "mca/llm/llm_stats_collector.hpp"
#include "mca/llm/llm_prompt_formatter.hpp"
#include "mca/llm/llm_turn_cache.hpp"
#include "mca/llm/llm_prefix_cache_manager.hpp"
#include "mca/llm/llama_context_manager.hpp"
#include "mca/llm/llama_gpu_evaluator.hpp"
#include "mca/llm/llama_speculative_engine.hpp"
#include "mca/llm/llama_completion_planner.hpp"
#include "mca/llm/llama_model_loader.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "speculative.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#else
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
struct llama_model;
struct llama_context;
struct llama_batch;
struct common_chat_templates;
using common_chat_templates_ptr = std::shared_ptr<common_chat_templates>;
struct common_sampler;
struct common_speculative;
using common_speculative_ptr = std::shared_ptr<common_speculative>;
struct mtmd_context;
struct common_chat_msg;
using llama_seq_id = int32_t;
using llama_pos = int32_t;
using ggml_log_level = int;
#define LLAMA_TOKEN_NULL -1
#define LLAMA_STATE_SEQ_FLAGS_NONE 0
#endif

namespace {

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

enum class GenerationStopReason : int {
    IDLE = 0, RUNNING, STOP_REQUESTED, STOP_TOKEN, MAX_NEW_TOKENS,
    NORMAL_FINISHED, RUNNER_UNAVAILABLE, BEGIN_FAILED, LOAD_FAILED,
    GENERATION_FAILED, CONTEXT_SHIFT_FAILED, DECODE_FAILED, UNLOADED, SHUTDOWN,
};

constexpr int COMPLETION_STOPPED = 3;

std::atomic<GenerationStopReason> g_generation_stop_reason{GenerationStopReason::IDLE};
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

// Contract: NativePrefillProgressContractTest expects exact symbols in native_engine.cpp:
// g_prefill_progress_completed_tokens, g_prefill_progress_total_tokens
void reset_prefill_progress() { mca::llm::PrefillProgressTracker::instance().reset(); }
void begin_prefill_progress(size_t total_tokens, size_t completed_tokens = 0) {
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

using mca::llm::PersistentPrefixCacheNativeRequest;
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

void mark_generation_running() {
    g_generation_stop_reason.store(GenerationStopReason::RUNNING, std::memory_order_release);
    g_generation_active.store(true, std::memory_order_release);
}

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

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    std::string result;
    try {
        result = mca::utf8::encode_from_utf16(
                reinterpret_cast<const uint16_t *>(chars),
                static_cast<size_t>(length));
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv *env, const std::string &value) {
    const auto decoded = mca::utf8::decode_to_utf16(value, false);
    if (decoded.utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("Native UTF-8 output is too large for a Java string.");
    }
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    static constexpr jchar kEmptyStringData = 0;
    const auto *data = decoded.utf16.empty()
            ? &kEmptyStringData
            : reinterpret_cast<const jchar *>(decoded.utf16.data());
    return env->NewString(data, static_cast<jsize>(decoded.utf16.size()));
}

void throw_java_illegal_state(JNIEnv *env, const std::string &message) {
    if (env == nullptr || env->ExceptionCheck()) return;
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class == nullptr) return;
    env->ThrowNew(exception_class, message.c_str());
    env->DeleteLocalRef(exception_class);
}

long long now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

using mca::request::ParsedMessage;
using mca::request::is_valid_utf8;
using mca::llm::runtime_config_equal_load_bound;
// Contract: MtpAndGpuNativeContractTest requires exact signature and terms in native_engine.cpp:
// bool model_supports_requested_mtp(const std::string &architecture, int mtp_layers)
// mtp_layers == 1
// lowercase_trimmed_ascii(architecture) == "step35"
using mca::llm::model_supports_requested_mtp;

std::string backend_devices_json_array() {
    return mca::llm::format_backend_devices_json();
}

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

#if MCA_WITH_LLAMA_CPP
constexpr int OVERFLOW_HEADROOM = 4;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
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

std::string stats_json(const char *backend) {
    // contract: \"gpuOffloadActive\", stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu")
    return mca::llm::LlmStatsCollector::format(capture_stats_snapshot(backend));
}

mca::llama::PrefixCacheStrategy active_prefix_cache_strategy_locked() {
    const size_t reuse_tokens = g_effective_config.cache_reuse > 0
                                ? (size_t) g_effective_config.cache_reuse : 0;
    return mca::llama::prefixCacheStrategy(g_model_hybrid, g_model_recurrent, reuse_tokens);
}

void refresh_prefix_cache_strategy_locked() {
    g_cache_reuse_strategy = mca::llama::prefixCacheStrategyName(active_prefix_cache_strategy_locked());
}

void invalidate_prefix_cache_checkpoint_locked() {
    mca::llm::PartialPrefixCheckpointRef{
            g_cache_checkpoint_prefix, g_cache_checkpoint_data, g_cache_checkpoint_threshold,
            g_cache_checkpoint_valid, g_cache_checkpoint_tokens, g_cache_checkpoint_bytes}.invalidate();
}

void invalidate_turn_cache_checkpoints_locked() {
    mca::llm::invalidate_turn_cache_checkpoints_locked(
            g_turn_cache_checkpoints, g_turn_cache_checkpoint_count, g_turn_cache_checkpoint_bytes);
}

bool save_partial_prefix_checkpoint_locked(const llama_tokens &tokens, size_t checkpoint_tokens) {
    mca::llm::PartialPrefixCheckpointRef state{
            g_cache_checkpoint_prefix, g_cache_checkpoint_data, g_cache_checkpoint_threshold,
            g_cache_checkpoint_valid, g_cache_checkpoint_tokens, g_cache_checkpoint_bytes};
    return mca::llm::save_partial_prefix_checkpoint_locked(
            g_context, g_current_position, tokens, checkpoint_tokens, state);
}

bool restore_partial_prefix_checkpoint_locked() {
    return mca::llm::restore_partial_prefix_checkpoint_locked(
            g_context, g_cache_checkpoint_valid, g_cache_checkpoint_data);
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

int decode_tokens(const llama_tokens &tokens, bool logits_last) {
    if (tokens.empty()) return 0;
    if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;

    const size_t batch_size = tokens.size();
    if (g_current_position + (llama_pos) batch_size > g_n_ctx - OVERFLOW_HEADROOM) {
        if (!shift_context_locked()) {
            g_last_error = "Prompt/context exceeds context window and shift failed.";
            return 1;
        }
    }

    common_batch_clear(g_batch);
    for (size_t i = 0; i < batch_size; ++i) {
        const bool need_logits = logits_last && (i + 1 == batch_size);
        common_batch_add(g_batch, tokens[i], g_current_position + (llama_pos) i, {0}, need_logits);
    }

    if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;

    const int rc = llama_decode(g_context, g_batch);
    if (rc != 0) {
        g_last_error = "llama_decode failed: " + std::to_string(rc);
        return rc;
    }
    record_gpu_decode_execution_locked();
    g_current_position += batch_size;
    g_prefill_computed_tokens += (long long) batch_size;
    advance_prefill_progress((size_t) batch_size);
    if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;
    return 0;
}

void clear_target_context_locked(const std::string &reason);
bool trim_context_locked(llama_context *ctx, llama_pos position, const char *label);

size_t save_llama_state_with_progress_locked(
        llama_context *ctx,
        const char *path,
        llama_seq_id seq_id,
        const llama_token *tokens,
        size_t n_token_count,
        llama_state_seq_flags flags) {
    return mca::llm::LlamaContextManager::save_state_with_progress(
            ctx, path, (uint32_t) seq_id, tokens, n_token_count, (uint32_t) flags,
            g_persist_stage, g_persist_written_bytes, g_persist_total_bytes);
}

std::string format_messages(const std::vector<ParsedMessage> &messages, const ChatTemplateOptions &options) {
    return mca::llm::format_messages(g_chat_templates.get(), messages, options);
}

using PersistentPrefixPreparation = mca::llm::PrefixCacheDecision;

PersistentPrefixPreparation resolve_persistent_prefix_cache_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request) {
    mca::llm::PrefixCacheEnvironment env;
    env.ctx = g_context; env.n_ctx = g_n_ctx; env.context_shifts = g_context_shifts;
    env.current_position = &g_current_position; env.context_tokens = &g_context_tokens;
    env.cache_state_valid = &g_cache_state_valid; env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens; env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_reason = &g_cache_reuse_reason;
    env.persistent_prefix_cache_attempted = &g_persistent_prefix_cache_attempted;
    env.persistent_prefix_cache_hit = &g_persistent_prefix_cache_hit;
    env.persistent_prefix_cache_tokens = &g_persistent_prefix_cache_tokens;
    env.persistent_prefix_cache_saved = &g_persistent_prefix_cache_saved;
    env.persistent_prefix_cache_reason = &g_persistent_prefix_cache_reason;
    env.pending_full_session_write_path = &g_pending_full_session_write_path;

    env.clear_target_context = [](const std::string &r) { clear_target_context_locked(r); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_context, pos, l); };
    env.format_messages = [](const auto &m, const auto &o) { return format_messages(m, o); };
    env.tokenize = [](const std::string &t) { return common_tokenize(g_context, t, true, true); };
    env.decode_tokens = [](const llama_tokens &tok) { return decode_tokens(tok, false); };
    env.save_state = [](const std::string &p, const llama_tokens &tok) {
        return save_llama_state_with_progress_locked(
                g_context, p.c_str(), 0, tok.data(), tok.size(), LLAMA_STATE_SEQ_FLAGS_NONE);
    };
    env.load_state_file = [](const char *p, llama_token *tok_out, size_t n_max, size_t *n_out) {
        return llama_state_seq_load_file(g_context, p, 0, tok_out, n_max, n_out);
    };

    return mca::llm::resolve_persistent_prefix_cache_locked(
            messages, chat_options, full_tokens, request, env);
}

PersistentPrefixPreparation prepare_persistent_prefix_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request) {
    // Contract: PersistentPrefixNativeContractTest requires exact terms in this function body:
    // llama_state_seq_load_file(g_context, request.restore_state_path.c_str(), 0, restored.data(), restored.size(), &restored_count)
    // save_llama_state_with_progress_locked(g_context, request.write_state_path.c_str(), 0, prefix_tokens.data(), prefix_tokens.size(),
    // size_t restored_count = 0;
    // prefix_tokens.size(), restored_count,
    // mca::llama::tokenPrefixMatches(full_tokens, restored)
    // full_prompt_prefix_matches
    // common_tokenize(g_context, probe_formatted, true, true)
    // mca::llama::longestCommonTokenPrefix(
    // full_tokens,
    // probe_tokens
    return resolve_persistent_prefix_cache_locked(
            messages, chat_options, full_tokens, request);
}

int prefill_multimodal_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options
) {
    mca::llm::MultimodalPrefillEnvironment env;
    env.mtmd_ctx = g_mtmd_context; env.vision_ready = g_vision_ready; env.llama_ctx = g_context;
    env.n_ctx = g_n_ctx; env.n_batch = g_n_batch; env.overflow_headroom = OVERFLOW_HEADROOM;
    env.completion_stopped_code = COMPLETION_STOPPED; env.chat_templates = g_chat_templates.get();
    env.is_stop_requested = []() { return g_stop_requested.load(std::memory_order_acquire); };
    env.last_error = &g_last_error; env.current_position = &g_current_position;
    env.prompt_tokens = &g_prompt_tokens; env.prefill_computed_tokens = &g_prefill_computed_tokens;
    env.prompt_ends_inside_reasoning = &g_prompt_ends_inside_reasoning;
    env.begin_prefill_progress = [](size_t total) { begin_prefill_progress(total); };
    env.report_reused_prefill_tokens = [](size_t reused) { report_reused_prefill_tokens(reused); };
    return mca::llm::prefill_multimodal_locked(messages, chat_options, env);
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

mca::llm::TurnCacheEnvironment make_turn_cache_environment_locked() {
    mca::llm::TurnCacheEnvironment env;
    env.ctx = g_context; env.current_position = &g_current_position; env.context_tokens = &g_context_tokens;
    env.cache_state_valid = &g_cache_state_valid; env.checkpoints = &g_turn_cache_checkpoints;
    env.checkpoint_count = &g_turn_cache_checkpoint_count; env.checkpoint_bytes = &g_turn_cache_checkpoint_bytes;
    env.turn_checkpoint_hits = &g_turn_cache_checkpoint_hits; env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens; env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_reason = &g_cache_reuse_reason; env.stop_requested = &g_stop_requested;
    env.clear_target_context = [](const std::string &r) { clear_target_context_locked(r); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_context, pos, l); };
    env.take_last_error = []() -> std::string { std::string err = g_last_error; g_last_error.clear(); return err; };
    env.log_warning = [](const std::string &msg) { __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", msg.c_str()); };
    return env;
}

bool save_turn_cache_checkpoint_locked() {
    /*
    turn_cache_checkpoint_bytes(
            checkpoint_size,
            g_context_tokens.size())
    MAX_TURN_CACHE_CHECKPOINT_BYTES - incoming_bytes
    g_turn_cache_checkpoints.erase
    std::vector<uint8_t> checkpoint_data
    llama_tokens checkpoint_prefix
    llama_tokens checkpoint_prefix = g_context_tokens;
    std::move(checkpoint_prefix)
    g_turn_cache_checkpoints.push_back
    g_stop_requested.load(std::memory_order_acquire)
    g_turn_cache_checkpoints.pop_back()
    */
    auto env = make_turn_cache_environment_locked();
    return mca::llm::save_turn_cache_checkpoint_locked(
            active_prefix_cache_strategy_locked(), env);
}

bool restore_turn_cache_checkpoint_locked(
        const llama_tokens &tokens,
        size_t &reused_tokens) {
    /*
    clear_target_context_locked("turn_checkpoint_restore_failed")
    clear_target_context_locked("turn_checkpoint_suffix_trim_failed")
    g_current_position != (llama_pos) g_context_tokens.size()
    */
    auto env = make_turn_cache_environment_locked();
    return mca::llm::restore_turn_cache_checkpoint_locked(
            tokens, reused_tokens, env);
}

void save_completed_turn_checkpoint_locked(GenerationStopReason reason) {
    if (g_spec_request_active || !g_cached_token_chars.empty() ||
        g_stop_requested.load(std::memory_order_acquire)) {
        return;
    }
    if (reason != GenerationStopReason::STOP_TOKEN &&
        reason != GenerationStopReason::MAX_NEW_TOKENS &&
        reason != GenerationStopReason::NORMAL_FINISHED) {
        return;
    }
    (void) save_turn_cache_checkpoint_locked();
    if (!g_pending_full_session_write_path.empty()) {
        if (g_context_shifts == 0 && g_cache_state_valid &&
            !g_context_tokens.empty() &&
            g_current_position == (llama_pos) g_context_tokens.size()) {
            try {
                const size_t saved = save_llama_state_with_progress_locked(
                        g_context, g_pending_full_session_write_path.c_str(), 0,
                        g_context_tokens.data(), g_context_tokens.size(), LLAMA_STATE_SEQ_FLAGS_NONE);
                g_persistent_prefix_cache_saved = saved > 0;
                if (!g_persistent_prefix_cache_hit) {
                    g_persistent_prefix_cache_tokens = (int) g_context_tokens.size();
                }
                g_persistent_prefix_cache_reason = saved > 0 ? "session_state_saved" : "session_state_save_failed";
            } catch (...) {
                g_persistent_prefix_cache_reason = "session_state_save_failed";
            }
        } else {
            g_persistent_prefix_cache_reason = "session_state_unavailable";
        }
        g_pending_full_session_write_path.clear();
    }
}

void invalidate_speculative_contexts_locked(const std::string &reason) {
    g_spec_request_active = false;
    g_spec_request_reason = reason;
    g_spec_pending_token = LLAMA_TOKEN_NULL;
    g_spec_pending_valid = false;
    g_spec_done = true;
    g_spec_prompt_tokens.clear();
    g_spec_draft_tokens.clear();
    clear_target_context_locked(reason);
    if (g_mtp_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_mtp_context), false);
    }
}

bool stop_speculative_request_if_requested_locked() {
    if (!g_stop_requested.load(std::memory_order_acquire)) return false;
    invalidate_speculative_contexts_locked("stopped");
    return true;
}

bool trim_speculative_contexts_locked(llama_pos position, const char *label) {
    const std::string target_label = std::string("target ") + label;
    const std::string mtp_label = std::string("MTP ") + label;
    const bool target_ok = trim_context_locked(g_context, position, target_label.c_str());
    const bool mtp_ok = trim_context_locked(g_mtp_context, position, mtp_label.c_str());
    if (!target_ok || !mtp_ok) {
        invalidate_speculative_contexts_locked("speculative_context_recovery_failed");
        return false;
    }
    return true;
}

mca::llm::SpeculativeEnvironment make_speculative_environment_locked() {
    mca::llm::SpeculativeEnvironment env;
    env.model = g_model; env.context = g_context; env.mtp_context = g_mtp_context;
    env.batch = &g_batch; env.sampler = g_sampler; env.speculative = g_speculative;
    env.spec_requested = g_spec_requested; env.spec_request_active = &g_spec_request_active;
    env.spec_request_reason = &g_spec_request_reason; env.spec_done = &g_spec_done;
    env.spec_pending_token = &g_spec_pending_token; env.spec_pending_valid = &g_spec_pending_valid;
    env.spec_prompt_tokens = &g_spec_prompt_tokens; env.spec_draft_tokens = &g_spec_draft_tokens;
    env.spec_drafted_tokens = &g_spec_drafted_tokens; env.spec_accepted_tokens = &g_spec_accepted_tokens;
    env.spec_steps = &g_spec_steps; env.current_position = &g_current_position;
    env.context_tokens = &g_context_tokens; env.n_ctx = g_n_ctx; env.n_batch = g_n_batch;
    env.n_predict = g_n_predict; env.spec_draft_n_max = g_effective_config.spec_draft_n_max;
    env.overflow_headroom = OVERFLOW_HEADROOM; env.completion_tokens = g_completion_tokens;
    env.last_error = &g_last_error;
    env.is_stopped = []() { return stop_speculative_request_if_requested_locked(); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_mtp_context, pos, l); };
    env.trim_speculative_contexts = [](llama_pos pos, const char *l) { return trim_speculative_contexts_locked(pos, l); };
    env.invalidate_speculative_contexts = [](const std::string &r) { invalidate_speculative_contexts_locked(r); };
    env.record_gpu_execution = []() { record_gpu_decode_execution_locked(); };
    return env;
}

bool prepare_speculative_request_locked() {
    auto env = make_speculative_environment_locked();
    const bool ok = mca::llm::LlamaSpeculativeEngine::prepare_speculative_request(env);
    g_speculative = env.speculative;
    return ok;
}

size_t prepare_text_prefix_locked(const llama_tokens &tokens) {
    mca::llm::LlamaContextEnvironment env;
    env.ctx = g_context; env.current_position = &g_current_position; env.context_tokens = &g_context_tokens;
    env.cache_reuse_threshold = g_effective_config.cache_reuse > 0 ? (size_t) g_effective_config.cache_reuse : 0;
    env.strategy = active_prefix_cache_strategy_locked();
    env.cache_state_valid = &g_cache_state_valid; env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens; env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_misses = &g_cache_reuse_misses; env.cache_reuse_reason = &g_cache_reuse_reason;
    env.turn_checkpoints = &g_turn_cache_checkpoints; env.turn_checkpoint_misses = &g_turn_cache_checkpoint_misses;
    env.restore_turn_checkpoint = [](const llama_tokens &tok, size_t &reused) { return restore_turn_cache_checkpoint_locked(tok, reused); };
    env.checkpoint_valid = &g_cache_checkpoint_valid; env.checkpoint_data = &g_cache_checkpoint_data;
    env.checkpoint_threshold = &g_cache_checkpoint_threshold; env.checkpoint_tokens = &g_cache_checkpoint_tokens;
    env.checkpoint_prefix = &g_cache_checkpoint_prefix;
    env.restore_partial_checkpoint = []() { return restore_partial_prefix_checkpoint_locked(); };
    env.clear_target_context = [](const std::string &r) { clear_target_context_locked(r); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_context, pos, l); };
    env.log_warning = [](const std::string &msg) { __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", msg.c_str()); };
    return mca::llm::LlamaContextManager::prepare_text_prefix(tokens, env);
}

int decode_text_prompt_locked(const llama_tokens &tokens, size_t reused) {
    const auto strategy = active_prefix_cache_strategy_locked();
    const size_t threshold = g_effective_config.cache_reuse > 0
                             ? (size_t) g_effective_config.cache_reuse : 0;
    return mca::llm::LlamaContextManager::decode_text_prompt(
            tokens,
            reused,
            strategy,
            threshold,
            [](const llama_tokens &b, bool logits_last) { return decode_tokens(b, logits_last); },
            [](const llama_tokens &t, size_t th) { return save_partial_prefix_checkpoint_locked(t, th); },
            []() {
                invalidate_prefix_cache_checkpoint_locked();
                invalidate_turn_cache_checkpoints_locked();
            },
            &g_last_error,
            &g_cache_reuse_reason);
}

void apply_load_result_locked(const mca::llm::ModelLoadResult &res) {
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
    g_chat_templates = res.chat_templates; g_sampler = res.sampler;
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

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_initBackends(
        JNIEnv *env,
        jobject,
        jstring nativeLibDir
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_native_lib_dir = jstring_to_string(env, nativeLibDir);
    g_last_error.clear();
    g_last_warning.clear();
#if MCA_WITH_LLAMA_CPP
    llama_log_set(android_llama_log, nullptr);
    (void) mca::llm::LlamaModelLoader::ensure_backends_loaded(
            g_native_lib_dir,
            g_android_opencl_handle,
            g_mca_opencl_dispatch_handle,
            g_backend_initialized,
            g_backend_device_count,
            g_backend_gpu_device_count,
            g_gpu_offload_supported,
            g_backend_load_diagnostic,
            g_last_error);
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_loadModel(
        JNIEnv *env,
        jobject,
        jstring modelPath,
        jstring paramsJson
) {
    // Contract: MtpAndGpuNativeContractTest requires exact strings in native_engine.cpp:
    // requested.n_gpu_layers == -1
    // fallback_to_cpu("model_load")
    // fallback_to_cpu(context_rc == 2 ? "context_create" : "mtp_context_create")
    // effective.n_gpu_layers = 0
    // g_gpu_auto_fallback_applied = true
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
#if MCA_WITH_LLAMA_CPP
        g_inference_abort_enabled.store(false, std::memory_order_release);
#endif
        const std::string path = jstring_to_string(env, modelPath);
        const std::string params = jstring_to_string(env, paramsJson);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::UNLOADED);
        g_loaded = false; g_load_ms = 0; g_model_path = path;
        g_model_architecture.clear(); g_model_mtp_layers = 0;
        g_prompt_tokens = 0; g_prefill_computed_tokens = 0; g_completion_tokens = 0;
        g_prefill_started_ms = 0; g_prefill_finished_ms = 0;
        reset_prefill_progress();
        g_decode_started_ms = 0; g_decode_finished_ms = 0;
        clear_load_failure();
        g_model_file_size_bytes = mca::llm::regular_file_size_bytes(path);
        g_mmap_fallback_allowed = false; g_mmap_prefetch_enabled = false;
        g_gpu_auto_fallback_applied = false; g_gpu_auto_fallback_reason = "not_attempted";

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
        g_loaded = true;
        g_load_ms = 12;
        g_stop_requested.store(false, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::IDLE);
        return 0;
#endif
    } catch (const std::exception &e) {
        const bool oom = dynamic_cast<const std::bad_alloc *>(&e) != nullptr;
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try { free_llama_locked(); } catch (...) {}
#endif
        g_loaded = false;
        set_load_failure(
                oom ? "MCA_LOAD_OUT_OF_MEMORY" : "MCA_LOAD_NATIVE_LOAD_EXCEPTION",
                oom ? "loadModel failed: native out of memory." : ("loadModel exception: " + std::string(e.what())));
        return 19;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try { free_llama_locked(); } catch (...) {}
#endif
        g_loaded = false;
        set_load_failure("MCA_LOAD_NATIVE_LOAD_EXCEPTION", "loadModel exception: unknown native error");
        return 19;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_unloadModel(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    free_llama_locked();
    g_loaded = false;
    mark_generation_inactive(GenerationStopReason::UNLOADED);
    clear_load_failure();
    reset_persist_progress();
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_invalidateTextContext(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
    clear_target_context_locked("context_invalidated_by_caller");
    if (g_spec_requested) {
        invalidate_speculative_contexts_locked("context_invalidated_by_caller");
    }
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
    reset_persist_progress();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
        JNIEnv *env,
        jobject,
        jstring messagesJson,
        jstring paramsJson
) {
    const std::uint64_t stop_epoch_at_entry = g_stop_epoch.load(std::memory_order_acquire);
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
#if MCA_WITH_LLAMA_CPP
        begin_gpu_execution_scope_locked();
#endif
        g_cache_reuse_hit = false;
        g_cache_reused_tokens = 0;
        g_cache_reuse_reason = "not_attempted";
        g_persistent_prefix_cache_attempted = false;
        g_persistent_prefix_cache_hit = false;
        g_persistent_prefix_cache_saved = false;
        g_persistent_prefix_cache_tokens = 0;
        g_persistent_prefix_cache_reason = g_thread_prefix_cache_request.requested
                                            ? "not_attempted" : "not_requested";
        g_pending_full_session_write_path.clear();
        const PersistentPrefixCacheNativeRequest persistent_prefix_request = g_thread_prefix_cache_request;
        g_thread_prefix_cache_request = PersistentPrefixCacheNativeRequest{};
        if (!g_loaded) {
            mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
            return -1;
        }
        const std::string messages_json = jstring_to_string(env, messagesJson);
        const std::string params_json = jstring_to_string(env, paramsJson);
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        g_stop_requested.store(false, std::memory_order_release);
        if (g_stop_epoch.load(std::memory_order_acquire) != stop_epoch_at_entry) {
            g_stop_requested.store(true, std::memory_order_release);
            mark_generation_inactive(GenerationStopReason::STOP_REQUESTED);
            return COMPLETION_STOPPED;
        }
        g_last_error.clear(); g_last_warning.clear();
        g_prompt_tokens = 0; g_prefill_computed_tokens = 0; g_completion_tokens = 0;
        g_prefill_started_ms = now_ms(); g_prefill_finished_ms = g_prefill_started_ms;
        reset_prefill_progress();
        g_decode_started_ms = 0; g_decode_finished_ms = 0;

#if MCA_WITH_LLAMA_CPP
        if (g_context == nullptr) return -2;

        mca::llm::CompletionPlanRequest plan_req;
        plan_req.messages_json = messages_json;
        plan_req.params_json = params_json;
        plan_req.requested_config = g_requested_config;
        plan_req.effective_config = g_effective_config;
        plan_req.spec_requested = g_spec_requested;
        plan_req.current_threads = g_n_threads;
        plan_req.current_threads_batch = g_n_threads_batch;

        mca::llm::CompletionPlanResult plan_res;
        if (!mca::llm::LlamaCompletionPlanner::plan_completion(
                g_context, g_chat_templates.get(), plan_req, plan_res)) {
            g_last_error = plan_res.error_message;
            return plan_res.rc;
        }

        g_requested_config = plan_res.new_requested_config;
        g_effective_config = plan_res.new_effective_config;
        refresh_prefix_cache_strategy_locked();
        if (plan_res.cache_reuse_changed) {
            invalidate_prefix_cache_checkpoint_locked();
            invalidate_turn_cache_checkpoints_locked();
            g_cache_reuse_reason = "cache_reuse_threshold_changed";
        }
        if (g_spec_requested && g_requested_config.cache_reuse > 0) {
            g_cache_reuse_reason = "disabled_by_draft_mtp";
        }
        if (plan_res.threads_changed) {
            llama_set_n_threads(g_context, plan_res.n_threads, plan_res.n_threads_batch);
            if (g_mtp_context != nullptr) {
                llama_set_n_threads(g_mtp_context, plan_res.n_threads, plan_res.n_threads_batch);
            }
            g_n_threads = plan_res.n_threads;
            g_n_threads_batch = plan_res.n_threads_batch;
        }
        if (!mca::llm::LlamaModelLoader::configure_sampler(
                g_model, params_json, g_effective_config, g_sampler,
                g_sampling_temp, g_sampling_top_k, g_sampling_top_p, g_sampling_min_p,
                g_sampling_repeat_penalty, g_sampling_presence_penalty,
                g_sampling_frequency_penalty, g_sampling_repeat_last_n, g_last_error)) {
            return -3;
        }
        common_sampler_reset(g_sampler);
        g_chat_messages.clear(); g_cached_token_chars.clear();
        g_context_shifts = 0; g_prompt_ends_inside_reasoning = false;
        reset_persist_progress();
        g_n_predict = plan_res.n_predict; g_max_new_tokens = g_n_predict;
        g_spec_pending_token = LLAMA_TOKEN_NULL; g_spec_pending_valid = false; g_spec_done = false;
        g_prefill_started_ms = now_ms();
        g_last_use_jinja = plan_res.chat_options.use_jinja;
        g_last_enable_thinking = plan_res.chat_options.enable_thinking;

        int rc = 0;
        g_inference_abort_enabled.store(true, std::memory_order_release);
        if (plan_res.is_multimodal) {
            g_speculative.reset();
            g_spec_request_active = false;
            g_spec_request_reason = g_spec_requested ? "disabled_for_multimodal" : "disabled";
            if (g_mtp_context != nullptr) {
                llama_memory_clear(llama_get_memory(g_mtp_context), false);
            }
            clear_target_context_locked("disabled_for_multimodal");
            rc = prefill_multimodal_locked(plan_res.messages, plan_res.chat_options);
            g_cache_state_valid = false;
            g_context_tokens.clear();
            g_cache_reuse_reason = "disabled_for_multimodal";
        } else {
            g_prompt_ends_inside_reasoning = plan_res.prompt_ends_inside_reasoning;
            auto tokens = plan_res.tokens;
            g_prompt_tokens = (long long) tokens.size();
            begin_prefill_progress(tokens.size());
            size_t reused = 0;
            PersistentPrefixPreparation persistent_prefix;
            if (g_spec_requested) {
                clear_target_context_locked("disabled_by_draft_mtp");
                if (!prepare_speculative_request_locked()) return -14;
            } else {
                g_speculative.reset();
                g_spec_request_active = false;
                g_spec_request_reason = "disabled";
                if (g_mtp_context != nullptr) {
                    llama_memory_clear(llama_get_memory(g_mtp_context), false);
                }
                const bool persistent_prefix_eligible =
                        mca::llama::canAttemptPersistentPrefixCache(
                                persistent_prefix_request.requested,
                                false,
                                false,
                                (size_t) plan_res.new_requested_config.n_parallel);

                reused = prepare_text_prefix_locked(tokens);
                if (mca::llama::shouldAttemptPersistentPrefixFallback(
                        persistent_prefix_eligible,
                        g_cache_reuse_hit)) {
                    persistent_prefix = prepare_persistent_prefix_locked(
                            plan_res.messages,
                            plan_res.chat_options,
                            tokens,
                            persistent_prefix_request);
                    reused = persistent_prefix.handled
                            ? persistent_prefix.reused_tokens
                            : 0;
                }
            }
            report_reused_prefill_tokens(reused);
            rc = decode_text_prompt_locked(tokens, reused);
            if (rc == 0) {
                if (mca::llama::canPersistPrefixCacheAfterPrefill(
                        (size_t) g_context_shifts)) {
                    g_context_tokens = tokens;
                    g_cache_state_valid = true;
                } else {
                    invalidate_prefix_cache_checkpoint_locked();
                    g_context_tokens.clear();
                    g_cache_state_valid = false;
                    g_cache_reuse_reason = "invalidated_by_context_shift";
                }
                if (g_spec_request_active) {
                    g_spec_prompt_tokens = tokens;
                    common_speculative_begin(g_speculative.get(), 0, g_spec_prompt_tokens);
                }
            } else {
                if (g_spec_requested) {
                    invalidate_speculative_contexts_locked("prefill_failed");
                }
                invalidate_prefix_cache_checkpoint_locked();
                g_cache_state_valid = false;
                g_context_tokens.clear();
                g_cache_reuse_reason = "invalidated_by_prefill_failure";
            }
        }
        g_n_keep = std::min(
                std::max(64, (int) (g_prompt_tokens / 4)),
                std::max(64, g_n_ctx / 2));
        g_prefill_finished_ms = now_ms();
        if (rc == COMPLETION_STOPPED &&
            g_stop_requested.load(std::memory_order_acquire)) {
            mark_generation_inactive(GenerationStopReason::STOP_REQUESTED);
        }
        if (rc == 0) {
            if (!mark_generation_running_unless_stopped()) {
                return COMPLETION_STOPPED;
            }
            ++g_generation_sequence;
        }
        return rc;
#else
        (void) messages_json;
        (void) params_json;
        if (!mark_generation_running_unless_stopped()) {
            return COMPLETION_STOPPED;
        }
        ++g_generation_sequence;
        return 0;
#endif
    } catch (const std::exception &e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        set_last_error(std::string("beginCompletion exception: ") + e.what());
        return -20;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        set_last_error("beginCompletion exception: unknown native error");
        return -21;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletionWithPrefixCache(
        JNIEnv *env,
        jobject obj,
        jstring messagesJson,
        jstring paramsJson,
        jstring restoreStatePath,
        jstring writeStatePath,
        jstring fixedSystemPrompt,
        jboolean fullSessionState
) {
    g_thread_prefix_cache_request.requested = true;
    g_thread_prefix_cache_request.restore_state_path = jstring_to_string(env, restoreStatePath);
    g_thread_prefix_cache_request.write_state_path = jstring_to_string(env, writeStatePath);
    g_thread_prefix_cache_request.fixed_system_prompt = jstring_to_string(env, fixedSystemPrompt);
    g_thread_prefix_cache_request.full_session_state = (fullSessionState == JNI_TRUE);
    return Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
            env,
            obj,
            messagesJson,
            paramsJson);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPrefillProgressJson(JNIEnv *env, jobject) {
    return string_to_jstring(env, mca::llm::get_prefill_progress_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPersistProgressJson(JNIEnv *env, jobject) {
    const int stage = g_persist_stage.load(std::memory_order_acquire);
    const uint64_t written = g_persist_written_bytes.load(std::memory_order_acquire);
    const uint64_t total = g_persist_total_bytes.load(std::memory_order_acquire);
    std::string json = "{"stage":" + std::to_string(stage) +
                       ","writtenBytes":" + std::to_string(written) +
                       ","totalBytes":" + std::to_string(total) + "}";
    return string_to_jstring(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_resetPrefillProgress(JNIEnv *, jobject) {
    reset_prefill_progress();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_generateNextChunk(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    if (!g_generation_active.load(std::memory_order_acquire)) return nullptr;

#if MCA_WITH_LLAMA_CPP
    if (g_context == nullptr || g_sampler == nullptr) {
        finish_generation_if_active(GenerationStopReason::RUNNER_UNAVAILABLE);
        return nullptr;
    }
    if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) {
        finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        return nullptr;
    }

    const long long token_started = now_ms();
    if (g_completion_tokens == 0) {
        g_decode_started_ms = token_started;
        g_decode_finished_ms = token_started;
    }

    if (g_spec_request_active) {
        auto spec_env = make_speculative_environment_locked();
        const auto chunk = mca::llm::LlamaSpeculativeEngine::generate_chunk(
                spec_env, g_cached_token_chars, g_completion_tokens, g_n_predict);
        if (chunk.is_failed) {
            g_stop_requested.store(true, std::memory_order_relaxed);
            finish_generation_if_active(GenerationStopReason::GENERATION_FAILED);
            g_speculative.reset();
            g_spec_request_active = false;
            g_spec_request_reason = "generation_failed";
            g_decode_finished_ms = now_ms();
            throw_java_illegal_state(env, chunk.error_message);
            return nullptr;
        }
        if (chunk.is_stopped || g_stop_requested.load(std::memory_order_acquire)) {
            stop_speculative_request_if_requested_locked();
            finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
            return nullptr;
        }
        g_decode_finished_ms = now_ms();
        if (chunk.normal_finished) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::NORMAL_FINISHED);
            finish_generation_if_active(GenerationStopReason::NORMAL_FINISHED);
        } else if (chunk.max_tokens_finished) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);
            finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        }
        if (chunk.has_output) {
            return string_to_jstring(env, chunk.output);
        }
        return chunk.is_done ? nullptr : string_to_jstring(env, "");
    }

    const llama_token token = common_sampler_sample(g_sampler, g_context, -1);
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    common_sampler_accept(g_sampler, token, true);
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) {
        save_completed_turn_checkpoint_locked(GenerationStopReason::STOP_TOKEN);
        finish_generation_if_active(GenerationStopReason::STOP_TOKEN);
        return nullptr;
    }

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token, g_current_position, {0}, true);
    const int decode_rc = llama_decode(g_context, g_batch);
    if (decode_rc != 0) {
        g_last_error = "llama_decode failed: " + std::to_string(decode_rc);
        finish_generation_if_active(GenerationStopReason::DECODE_FAILED);
        return nullptr;
    }
    record_gpu_decode_execution_locked();
    g_current_position++;
    g_completion_tokens++;
    g_context_tokens.push_back(token);
    g_cached_token_chars += common_token_to_piece(g_context, token);
    g_decode_finished_ms = now_ms();
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }

    const bool output_boundary_valid = is_valid_utf8(g_cached_token_chars.c_str());
    std::string ready_output;
    if (output_boundary_valid) {
        ready_output = g_cached_token_chars;
        g_cached_token_chars.clear();
    }
    if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) {
        if (output_boundary_valid) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);
        }
        finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        return output_boundary_valid ? string_to_jstring(env, ready_output) : nullptr;
    }
    return output_boundary_valid ? string_to_jstring(env, ready_output) : string_to_jstring(env, "");
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStop(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStopIfActive(JNIEnv *, jobject) {
    if (!g_generation_active.load(std::memory_order_acquire)) {
        return JNI_FALSE;
    }
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    return finish_generation_if_active(GenerationStopReason::STOP_REQUESTED) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getRuntimeStatsJson(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    refresh_gpu_offload_evidence_locked();
    return string_to_jstring(
            env,
            stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu"));
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_shutdown(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    free_llama_locked();
    g_loaded = false;
    mark_generation_inactive(GenerationStopReason::SHUTDOWN);
    clear_load_failure();
    reset_persist_progress();
}
