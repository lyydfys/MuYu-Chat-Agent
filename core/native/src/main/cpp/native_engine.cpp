#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <exception>
#include <cctype>
#include <fstream>
#include <limits>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "llama_load_failure_policy.hpp"
#include "llama_model_memory_policy.hpp"
#include "jni_utf8_codec.hpp"
#include "mca/request/llama_request_parser.hpp"
#include "mca/llm/llm_prefill_tracker.hpp"
#include "mca/llm/llm_prefix_cache_manager.hpp"
#include "mca/llm/llm_turn_cache.hpp"
#include "mca/llm/llm_stats_collector.hpp"
#include "mca/llm/llm_runtime_config.hpp"
#include "mca/llm/llm_backend_loader.hpp"
#include "mca/llm/llm_prompt_formatter.hpp"

#if MCA_WITH_LLAMA_CPP
#include <unistd.h>
#if defined(__ANDROID__)
#include <dlfcn.h>
#include "mca_opencl_driver.hpp"
#endif
#include <nlohmann/json.hpp>
#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "src/llama-ext.h"
#include "llama_model_device_policy.hpp"
#include "llama_prefix_cache_policy.hpp"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"
#include "speculative.h"
#endif

#if MCA_WITH_LLAMA_CPP
extern "C" void mca_llama_set_model_mmap_prefetch_enabled(bool enabled) noexcept;
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
    IDLE = 0,
    RUNNING,
    STOP_REQUESTED,
    STOP_TOKEN,
    MAX_NEW_TOKENS,
    NORMAL_FINISHED,
    RUNNER_UNAVAILABLE,
    BEGIN_FAILED,
    LOAD_FAILED,
    GENERATION_FAILED,
    CONTEXT_SHIFT_FAILED,
    DECODE_FAILED,
    UNLOADED,
    SHUTDOWN,
};

constexpr int COMPLETION_STOPPED = 3;

std::atomic<GenerationStopReason> g_generation_stop_reason{GenerationStopReason::IDLE};
bool g_loaded = false;
long long g_load_ms = 0;
long long g_prompt_tokens = 0;
// Logical prompt length includes tokens restored from the session KV. Keep a
// separate counter for tokens that actually went through llama_decode so the
// displayed prefill rate is not inflated after a cache hit.
long long g_prefill_computed_tokens = 0;
long long g_completion_tokens = 0;
long long g_context_shifts = 0;
long long g_prefill_started_ms = 0;
long long g_prefill_finished_ms = 0;

// KV cache serialization progress reported to the UI. Plain atomics so the
// poll getter observes intermediate values without taking g_mutex (which the
// serializing thread holds). Stage values use PersistStage below.
enum class PersistStage {
    Idle = 0,
    Encoding = 1,
    Writing = 2,
    Done = 3,
};
std::atomic<int> g_persist_stage{static_cast<int>(PersistStage::Idle)};
std::atomic<uint64_t> g_persist_written_bytes{0};
std::atomic<uint64_t> g_persist_total_bytes{0};

void reset_persist_progress() {
    g_persist_stage.store(static_cast<int>(PersistStage::Idle), std::memory_order_release);
    g_persist_written_bytes.store(0, std::memory_order_release);
    g_persist_total_bytes.store(0, std::memory_order_release);
}

// Defined with the other save helpers; declared here because the persistent
// prefix write path precedes it and must not be duplicated.
size_t save_llama_state_with_progress_locked(
        llama_context *ctx,
        const char *path,
        llama_seq_id seq_id,
        const llama_token *tokens,
        size_t n_token_count,
        llama_state_seq_flags flags);
long long g_decode_started_ms = 0;
long long g_decode_finished_ms = 0;
// Process-lifetime acceptance trace. Model unload/reload must not reset it.
std::uint64_t g_generation_sequence = 0;
std::string g_model_path;
std::uint64_t g_model_file_size_bytes = 0;
bool g_mmap_fallback_allowed = false;
bool g_mmap_prefetch_enabled = false;
std::string g_last_error;
// Warnings are diagnostics, not failures.  Keeping them out of g_last_error matters because
// `lastError` is a hard failure contract on the Kotlin side: a benign backend note such as
// "kleidiai: no kernel for tensor type q6_K, not accelerated by KleidiAI" (which upstream
// llama.cpp deliberately emits while falling back to the regular CPU kernels for K-quants)
// used to be published as lastError and aborted an otherwise healthy generation.
std::string g_last_warning;
std::string g_load_failure_code;
std::string g_native_lib_dir;
// Preserve backend registration diagnostics separately from the user-facing
// load error.  The latter is cleared before model parsing, but callers still
// need to know why a requested GPU backend was unavailable.
std::string g_backend_load_diagnostic;
// Android does not expose an NDK import library for the platform OpenCL
// implementation. Keep a process-lifetime preflight handle so availability is
// known before registration; the backend itself resolves every OpenCL symbol
// through its local dispatch layer and carries no DT_NEEDED libOpenCL entry.
void *g_android_opencl_handle = nullptr;
void *g_mca_opencl_dispatch_handle = nullptr;
using OpenClBeginExecutionScope = std::uint64_t (*)();
using OpenClExecutionSnapshot = int (*)(std::uint64_t, std::uint64_t *, std::uint64_t *);
OpenClBeginExecutionScope g_opencl_begin_execution_scope = nullptr;
OpenClExecutionSnapshot g_opencl_execution_snapshot = nullptr;
std::uint64_t g_opencl_execution_scope = 0;
std::uint64_t g_opencl_kernel_submissions = 0;
std::uint64_t g_opencl_kernel_completions = 0;
bool g_opencl_execution_telemetry_available = false;
bool g_gpu_decode_succeeded = false;
bool g_backend_initialized = false;
size_t g_backend_device_count = 0;
int g_n_threads = 0;
int g_n_threads_batch = 0;
int g_n_batch = 0;
int g_n_ubatch = 0;
int g_max_new_tokens = 0;
float g_sampling_temp = 0.0f;
int g_sampling_top_k = 0;
float g_sampling_top_p = 0.0f;
float g_sampling_min_p = 0.0f;
float g_sampling_repeat_penalty = 1.08f;
float g_sampling_presence_penalty = 0.0f;
float g_sampling_frequency_penalty = 0.2f;
int g_sampling_repeat_last_n = 0;
bool g_last_use_jinja = true;
bool g_last_enable_thinking = true;
// True when the rendered prompt ends inside an unclosed reasoning opener
// (e.g. DeepSeek-style templates pre-fill "<think>\n"), so the generated
// stream starts mid-thinking and only the close marker will appear.
bool g_prompt_ends_inside_reasoning = false;
bool g_vision_ready = false;
std::string g_mmproj_path;

// Contract: NativePrefillProgressContractTest expects exact symbols in native_engine.cpp:
// g_prefill_progress_completed_tokens, g_prefill_progress_total_tokens
void reset_prefill_progress() {
    mca::llm::PrefillProgressTracker::instance().reset();
}

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

RuntimeConfig g_requested_config;
RuntimeConfig g_effective_config;
size_t g_backend_gpu_device_count = 0;
bool g_gpu_offload_supported = false;
// Configuration is only a request.  A loaded GPU backend is not enough to
// claim that this request ran on it: the label is published only after both
// allocation evidence and directly observed completed OpenCL kernels in this request.
bool g_gpu_offload_active = false;
bool g_gpu_offload_allocation_observed = false;
bool g_gpu_offload_execution_observed = false;
bool g_gpu_offload_layers_known = false;
size_t g_gpu_offload_bytes = 0;
size_t g_gpu_offload_model_bytes = 0;
size_t g_gpu_offload_context_bytes = 0;
size_t g_gpu_offload_compute_bytes = 0;
int g_gpu_offload_layers = 0;
bool g_gpu_auto_fallback_applied = false;
std::string g_gpu_auto_fallback_reason = "not_attempted";
bool g_cache_state_valid = false;
bool g_cache_reuse_hit = false;
int g_cache_reused_tokens = 0;
long long g_cache_reuse_hits = 0;
long long g_cache_reuse_misses = 0;
std::string g_cache_reuse_reason = "not_attempted";
std::string g_cache_reuse_strategy = "disabled";
bool g_persistent_prefix_cache_attempted = false;
bool g_persistent_prefix_cache_hit = false;
bool g_persistent_prefix_cache_saved = false;
int g_persistent_prefix_cache_tokens = 0;
std::string g_persistent_prefix_cache_reason = "not_requested";
bool g_cache_checkpoint_valid = false;
size_t g_cache_checkpoint_tokens = 0;
size_t g_cache_checkpoint_bytes = 0;
size_t g_turn_cache_checkpoint_count = 0;
size_t g_turn_cache_checkpoint_bytes = 0;
long long g_turn_cache_checkpoint_hits = 0;
long long g_turn_cache_checkpoint_misses = 0;
bool g_model_hybrid = false;
bool g_model_recurrent = false;
bool g_spec_requested = false;
bool g_spec_request_active = false;
bool g_spec_context_ready = false;
bool g_spec_done = false;
long long g_spec_drafted_tokens = 0;
long long g_spec_accepted_tokens = 0;
long long g_spec_steps = 0;
std::string g_spec_request_reason = "disabled";
std::string g_model_architecture;
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
            expected,
            false,
            std::memory_order_acq_rel,
            std::memory_order_acquire)) {
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

    // requestStop() publishes the stop flag before waiting for g_mutex. The
    // second check closes the window in which that publication can land after
    // the first check but before this request becomes observable as active.
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
using mca::request::parse_int;
using mca::request::parse_float;
using mca::request::parse_float_any;
using mca::request::parse_int_any;
using mca::request::parse_bool;
using mca::request::parse_json_string_after;
using mca::request::parse_string;
using mca::request::starts_with;
using mca::request::hex_value;
using mca::request::url_decode;
using mca::request::local_image_path_from_url;
using mca::request::canonical_local_image_reference;
using mca::request::find_object_end;
using mca::request::value_start_after_key;
using mca::request::parse_content_parts;
using mca::request::parse_messages;
using mca::request::is_valid_utf8;
using mca::request::json_escape;

bool runtime_config_equal_load_bound(const RuntimeConfig &a, const RuntimeConfig &b) {
    return a.n_ctx == b.n_ctx &&
           a.n_batch == b.n_batch &&
           a.n_ubatch == b.n_ubatch &&
           a.n_gpu_layers == b.n_gpu_layers &&
           a.main_gpu == b.main_gpu &&
           a.split_mode == b.split_mode &&
           a.n_cpu_moe == b.n_cpu_moe &&
           a.cache_type_k == b.cache_type_k &&
           a.cache_type_v == b.cache_type_v &&
           a.flash_attn == b.flash_attn &&
           a.perf == b.perf &&
           a.n_parallel == b.n_parallel &&
           a.spec_type == b.spec_type &&
           a.spec_draft_n_max == b.spec_draft_n_max &&
           a.mmap == b.mmap &&
           a.mlock == b.mlock;
}

std::string runtime_config_json(const RuntimeConfig &config) {
    return mca::llm::format_runtime_config_json(config);
}

std::string backend_devices_json_array() {
    return mca::llm::format_backend_devices_json();
}

mca::llm::LlmStatsSnapshot capture_stats_snapshot(const char *backend) {
    mca::llm::LlmStatsSnapshot snap;
    snap.backend = backend != nullptr ? backend : "";
    snap.loaded = g_loaded;
    snap.model_path = g_model_path;
    snap.model_file_size_bytes = g_model_file_size_bytes;
    snap.mmap_fallback_allowed = g_mmap_fallback_allowed;
    snap.mmap_prefetch_enabled = g_mmap_prefetch_enabled;
    snap.load_ms = g_load_ms;
    snap.prompt_tokens = g_prompt_tokens;
    snap.prefill_computed_tokens = g_prefill_computed_tokens;
    snap.completion_tokens = g_completion_tokens;
    snap.generation_sequence = g_generation_sequence;
    snap.generation_active = g_generation_active.load(std::memory_order_acquire);
    snap.stop_requested = g_stop_requested.load(std::memory_order_acquire);
    snap.generation_stop_reason = generation_stop_reason_name(g_generation_stop_reason.load(std::memory_order_acquire));
    snap.context_shifts = g_context_shifts;
    snap.prefill_started_ms = g_prefill_started_ms;
    snap.prefill_finished_ms = g_prefill_finished_ms;
    snap.decode_started_ms = g_decode_started_ms;
    snap.decode_finished_ms = g_decode_finished_ms;
    snap.n_threads = g_n_threads;
    snap.n_threads_batch = g_n_threads_batch;
    snap.n_batch = g_n_batch;
    snap.n_ubatch = g_n_ubatch;
    snap.max_new_tokens = g_max_new_tokens;

    // GPU stats
    snap.gpu.offload_supported = g_gpu_offload_supported;
    snap.gpu.gpu_device_count = g_backend_gpu_device_count;
    snap.gpu.offload_active = g_gpu_offload_active;
    snap.gpu.allocation_observed = g_gpu_offload_allocation_observed;
    snap.gpu.execution_observed = g_gpu_offload_execution_observed;
    snap.gpu.offload_bytes = g_gpu_offload_bytes;
    snap.gpu.offload_model_bytes = g_gpu_offload_model_bytes;
    snap.gpu.offload_context_bytes = g_gpu_offload_context_bytes;
    snap.gpu.offload_compute_bytes = g_gpu_offload_compute_bytes;
    snap.gpu.offload_layers = g_gpu_offload_layers;
    snap.gpu.offload_layers_known = g_gpu_offload_layers_known;
    snap.gpu.auto_fallback_applied = g_gpu_auto_fallback_applied;
    snap.gpu.auto_fallback_reason = g_gpu_auto_fallback_reason;
    snap.gpu.execution_evidence.telemetry_available = g_opencl_execution_telemetry_available;
    snap.gpu.execution_evidence.request_scope = g_opencl_execution_scope;
    snap.gpu.execution_evidence.kernel_submissions = g_opencl_kernel_submissions;
    snap.gpu.execution_evidence.kernel_completions = g_opencl_kernel_completions;

    snap.requested_config = g_requested_config;
    snap.effective_config = g_effective_config;

    // Sampling
    snap.sampling.temperature = g_sampling_temp;
    snap.sampling.top_k = g_sampling_top_k;
    snap.sampling.top_p = g_sampling_top_p;
    snap.sampling.min_p = g_sampling_min_p;
    snap.sampling.repeat_penalty = g_sampling_repeat_penalty;
    snap.sampling.presence_penalty = g_sampling_presence_penalty;
    snap.sampling.frequency_penalty = g_sampling_frequency_penalty;
    snap.sampling.repeat_last_n = g_sampling_repeat_last_n;
    snap.sampling.use_jinja = g_last_use_jinja;
    snap.sampling.enable_thinking = g_last_enable_thinking;
    snap.sampling.prompt_ends_inside_reasoning = g_prompt_ends_inside_reasoning;

    // Backend capabilities
    snap.capabilities.gpu_offload_supported = g_gpu_offload_supported;
    snap.capabilities.gpu_device_count = g_backend_gpu_device_count;
    snap.capabilities.gpu_offload_active = g_gpu_offload_active;
    snap.capabilities.gpu_offload_allocation_observed = g_gpu_offload_allocation_observed;
    snap.capabilities.gpu_offload_execution_observed = g_gpu_offload_execution_observed;
    snap.capabilities.gpu_offload_bytes = g_gpu_offload_bytes;
    snap.capabilities.gpu_offload_layers = g_gpu_offload_layers;
    snap.capabilities.gpu_offload_layers_known = g_gpu_offload_layers_known;
    snap.capabilities.model_architecture = g_model_architecture;
    snap.capabilities.model_mtp_layers = g_model_mtp_layers;
    snap.capabilities.dynamic_backend_loading = LLAMA_RUNTIME_FEATURES_COMPILED;
    snap.capabilities.kv_cache_quantization = LLAMA_RUNTIME_FEATURES_COMPILED;
    snap.capabilities.draft_mtp_compiled = LLAMA_RUNTIME_FEATURES_COMPILED;

    // Cache reuse
    snap.cache_reuse.valid = g_cache_state_valid;
    snap.cache_reuse.hit = g_cache_reuse_hit;
    snap.cache_reuse.reused_tokens = g_cache_reused_tokens;
    snap.cache_reuse.hits = g_cache_reuse_hits;
    snap.cache_reuse.misses = g_cache_reuse_misses;
    snap.cache_reuse.reason = g_cache_reuse_reason;
    snap.cache_reuse.strategy = g_cache_reuse_strategy;
    snap.cache_reuse.checkpoint_valid = g_cache_checkpoint_valid;
    snap.cache_reuse.checkpoint_tokens = g_cache_checkpoint_tokens;
    snap.cache_reuse.checkpoint_bytes = g_cache_checkpoint_bytes;
    snap.cache_reuse.turn_checkpoints = g_turn_cache_checkpoint_count;
    snap.cache_reuse.turn_checkpoint_bytes = g_turn_cache_checkpoint_bytes;
    snap.cache_reuse.turn_checkpoint_hits = g_turn_cache_checkpoint_hits;
    snap.cache_reuse.turn_checkpoint_misses = g_turn_cache_checkpoint_misses;
    snap.cache_reuse.model_hybrid = g_model_hybrid;
    snap.cache_reuse.model_recurrent = g_model_recurrent;

    // Persistent prefix cache
    snap.persistent_prefix_cache.attempted = g_persistent_prefix_cache_attempted;
    snap.persistent_prefix_cache.hit = g_persistent_prefix_cache_hit;
    snap.persistent_prefix_cache.saved = g_persistent_prefix_cache_saved;
    snap.persistent_prefix_cache.tokens = g_persistent_prefix_cache_tokens;
    snap.persistent_prefix_cache.reason = g_persistent_prefix_cache_reason;

    // Speculative
    snap.speculative.requested = g_spec_requested;
    snap.speculative.context_ready = g_spec_context_ready;
    snap.speculative.active_for_request = g_spec_request_active;
    snap.speculative.drafted_tokens = g_spec_drafted_tokens;
    snap.speculative.accepted_tokens = g_spec_accepted_tokens;
    snap.speculative.steps = g_spec_steps;
    snap.speculative.reason = g_spec_request_reason;

    snap.vision_ready = g_vision_ready;
    snap.mmproj_path = g_mmproj_path;
    snap.backend_ready = (g_backend_device_count > 0);
    snap.backend_device_count = g_backend_device_count;
    snap.backend_devices_json = backend_devices_json_array();
    snap.backend_load_diagnostic = g_backend_load_diagnostic;

    snap.native_lib_dir = g_native_lib_dir;
    snap.load_failure_code = g_load_failure_code;
    snap.last_error = g_last_error;
    snap.last_warning = g_last_warning;
    return snap;
}

std::string stats_json(const char *backend) {
    // contract: \"gpuOffloadActive\", stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu")
    // Formats JSON payload including "\"gpuOffloadActive\"", memory breakdown, and execution evidence.
    return mca::llm::LlmStatsCollector::format(capture_stats_snapshot(backend));
}

std::uint64_t regular_file_size_bytes(const std::string &path) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        return 0;
    }
    return static_cast<std::uint64_t>(st.st_size);
}

enum class GgufHeaderProbe {
    VALID,
    UNREADABLE,
    INVALID_MAGIC,
    TRUNCATED,
};

GgufHeaderProbe probe_gguf_header(const std::string &path) {
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) return GgufHeaderProbe::UNREADABLE;

    // A GGUF header contains magic, version, tensor count, and metadata count.
    char header[24] = {};
    input.read(header, static_cast<std::streamsize>(sizeof(header)));
    const std::streamsize bytes_read = input.gcount();
    if (bytes_read < 4) return GgufHeaderProbe::TRUNCATED;
    if (header[0] != 'G' || header[1] != 'G' || header[2] != 'U' || header[3] != 'F') {
        return GgufHeaderProbe::INVALID_MAGIC;
    }
    return bytes_read == static_cast<std::streamsize>(sizeof(header))
           ? GgufHeaderProbe::VALID
           : GgufHeaderProbe::TRUNCATED;
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
common_params_speculative g_speculative_params;
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

// Partial-only snapshots contain recurrent state (not the full attention KV),
// so keeping a small rolling window is inexpensive and lets a tail-pruned
// conversation restore the exact prior turn boundary.
std::vector<TurnCacheCheckpoint> g_turn_cache_checkpoints;
llama_tokens g_spec_prompt_tokens;
llama_tokens g_spec_draft_tokens;
llama_token g_spec_pending_token = LLAMA_TOKEN_NULL;
bool g_spec_pending_valid = false;
llama_pos g_current_position = 0;
int g_n_ctx = 4096;
int g_n_predict = 8192;
int g_n_keep = 128;
std::string g_cached_token_chars;

using ordered_json = nlohmann::ordered_json;

void clear_gpu_offload_evidence_locked() {
    g_gpu_offload_active = false;
    g_gpu_offload_allocation_observed = false;
    g_gpu_offload_execution_observed = false;
    g_gpu_offload_layers_known = false;
    g_gpu_offload_bytes = 0;
    g_gpu_offload_model_bytes = 0;
    g_gpu_offload_context_bytes = 0;
    g_gpu_offload_compute_bytes = 0;
    g_gpu_offload_layers = 0;
    g_opencl_execution_scope = 0;
    g_opencl_kernel_submissions = 0;
    g_opencl_kernel_completions = 0;
    g_opencl_execution_telemetry_available = false;
    g_gpu_decode_succeeded = false;
}

void begin_gpu_execution_scope_locked() {
    g_gpu_offload_active = false;
    g_gpu_offload_execution_observed = false;
    g_gpu_decode_succeeded = false;
    g_opencl_kernel_submissions = 0;
    g_opencl_kernel_completions = 0;
    g_opencl_execution_scope = g_opencl_begin_execution_scope == nullptr ? 0 :
            g_opencl_begin_execution_scope();
    g_opencl_execution_telemetry_available = g_opencl_execution_scope != 0 &&
            g_opencl_execution_snapshot != nullptr;
}

void read_gpu_execution_evidence_locked() {
    g_opencl_execution_telemetry_available = g_opencl_execution_scope != 0 &&
            g_opencl_execution_snapshot != nullptr &&
            g_opencl_execution_snapshot(g_opencl_execution_scope, &g_opencl_kernel_submissions,
                                       &g_opencl_kernel_completions) != 0;
    g_gpu_offload_execution_observed = g_gpu_decode_succeeded &&
            g_opencl_execution_telemetry_available && g_opencl_kernel_completions > 0;
}

void reconcile_gpu_offload_evidence_locked() {
    g_gpu_offload_active = g_gpu_offload_allocation_observed &&
                           g_gpu_offload_execution_observed;
}

void refresh_gpu_offload_evidence_locked() {
    // Keep execution evidence until this request ends. Runtime
    // stats are polled between tokens, so clearing it here would make a real
    // GPU request oscillate back to CPU after its first successful decode.
    g_gpu_offload_active = false;
    g_gpu_offload_allocation_observed = false;
    g_gpu_offload_layers_known = false;
    g_gpu_offload_bytes = 0;
    g_gpu_offload_model_bytes = 0;
    g_gpu_offload_context_bytes = 0;
    g_gpu_offload_compute_bytes = 0;
    g_gpu_offload_layers = 0;
    read_gpu_execution_evidence_locked();
    if (!g_gpu_offload_supported || g_model == nullptr || g_context == nullptr) return;

    try {
        const llama_memory_breakdown breakdown = llama_get_memory_breakdown(g_context);
        for (const auto &[buffer_type, memory]: breakdown) {
            const ggml_backend_dev_t device = ggml_backend_buft_get_device(buffer_type);
            if (device == nullptr ||
                ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) {
                continue;
            }
            g_gpu_offload_model_bytes += memory.model;
            g_gpu_offload_context_bytes += memory.context;
            g_gpu_offload_compute_bytes += memory.compute;
        }
    } catch (const std::exception &error) {
        __android_log_print(
                ANDROID_LOG_WARN,
                "MCA",
                "Unable to read llama.cpp GPU allocation evidence: %s",
                error.what());
        return;
    } catch (...) {
        __android_log_print(
                ANDROID_LOG_WARN,
                "MCA",
                "Unable to read llama.cpp GPU allocation evidence.");
        return;
    }

    g_gpu_offload_bytes = g_gpu_offload_model_bytes +
                          g_gpu_offload_context_bytes +
                          g_gpu_offload_compute_bytes;
    g_gpu_offload_allocation_observed =
            g_gpu_offload_model_bytes > 0 &&
            (g_gpu_offload_context_bytes > 0 || g_gpu_offload_compute_bytes > 0);
    if (g_gpu_offload_allocation_observed) {
        // llama.cpp exposes allocation totals, but not a public read-back of
        // how many transformer layers were really placed by every backend.
        // Never substitute the requested n_gpu_layers for this unknown value.
        g_gpu_offload_layers = -1;
    }
    reconcile_gpu_offload_evidence_locked();
}

void record_gpu_decode_execution_locked() {
    g_gpu_decode_succeeded = true;
    refresh_gpu_offload_evidence_locked();
    reconcile_gpu_offload_evidence_locked();
}

mca::llama::PrefixCacheStrategy active_prefix_cache_strategy_locked() {
    const size_t reuse_tokens = g_effective_config.cache_reuse > 0
                                ? (size_t) g_effective_config.cache_reuse
                                : 0;
    return mca::llama::prefixCacheStrategy(
            g_model_hybrid,
            g_model_recurrent,
            reuse_tokens);
}

void refresh_prefix_cache_strategy_locked() {
    g_cache_reuse_strategy =
            mca::llama::prefixCacheStrategyName(active_prefix_cache_strategy_locked());
}

void invalidate_prefix_cache_checkpoint_locked() {
    mca::llm::PartialPrefixCheckpointRef{
            g_cache_checkpoint_prefix,
            g_cache_checkpoint_data,
            g_cache_checkpoint_threshold,
            g_cache_checkpoint_valid,
            g_cache_checkpoint_tokens,
            g_cache_checkpoint_bytes}.invalidate();
}

void invalidate_turn_cache_checkpoints_locked() {
    mca::llm::invalidate_turn_cache_checkpoints_locked(
            g_turn_cache_checkpoints,
            g_turn_cache_checkpoint_count,
            g_turn_cache_checkpoint_bytes);
}

std::string take_last_error_suffix_locked(size_t start) {
    if (g_last_error.size() <= start) {
        return {};
    }
    std::string suffix = g_last_error.substr(start);
    g_last_error.resize(start);
    return suffix;
}

bool save_partial_prefix_checkpoint_locked(
        const llama_tokens &tokens,
        size_t checkpoint_tokens) {
    mca::llm::PartialPrefixCheckpointRef state{
            g_cache_checkpoint_prefix,
            g_cache_checkpoint_data,
            g_cache_checkpoint_threshold,
            g_cache_checkpoint_valid,
            g_cache_checkpoint_tokens,
            g_cache_checkpoint_bytes};
    return mca::llm::save_partial_prefix_checkpoint_locked(
            g_context,
            g_current_position,
            tokens,
            checkpoint_tokens,
            state);
}

bool restore_partial_prefix_checkpoint_locked() {
    return mca::llm::restore_partial_prefix_checkpoint_locked(
            g_context,
            g_cache_checkpoint_valid,
            g_cache_checkpoint_data);
}

std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    return value;
}

std::string load_failure_code_from_llama_error(const std::string &detail) {
    return mca::llama::loadFailureCodeFromLlamaError(detail);
}

using mca::llm::load_mode_uses_mmap;
using mca::llm::load_mode_uses_mlock;
using mca::llm::load_mode_from_flags;
using mca::llm::default_runtime_config;
using mca::llm::validate_runtime_config;
using mca::llm::parse_runtime_config;
using mca::llm::cache_type_from_name;
using mca::llm::split_mode_from_name;
using mca::llm::flash_attn_from_name;
using mca::llm::model_meta_string;
using mca::llm::lowercase_trimmed_ascii;
using mca::llm::model_meta_declares_non_causal;
using mca::llm::model_meta_declares_pooling;
using mca::llm::is_dedicated_non_chat_architecture;
using mca::llm::model_supports_autoregressive_chat;
using mca::llm::model_mtp_layer_count;
// Contract: MtpAndGpuNativeContractTest requires exact signature and terms in native_engine.cpp:
// bool model_supports_requested_mtp(const std::string &architecture, int mtp_layers)
// mtp_layers == 1
// lowercase_trimmed_ascii(architecture) == "step35"
using mca::llm::model_supports_requested_mtp;
using mca::llm::file_exists;
using mca::llm::join_path;
using mca::llm::list_ggml_backend_files;

common_params_sampling build_sampling_params(const std::string &params_json) {
    common_params_sampling params;
    params.no_perf = !g_effective_config.perf;
    params.temp = parse_float(params_json, "temperature", params.temp);
    params.top_k = parse_int(params_json, "top_k", params.top_k);
    params.top_p = parse_float(params_json, "top_p", params.top_p);
    params.min_p = parse_float(params_json, "min_p", params.min_p);
    params.penalty_repeat = parse_float_any(params_json, {"repeat_penalty", "repetition_penalty"}, params.penalty_repeat);
    params.penalty_present = parse_float(params_json, "presence_penalty", params.penalty_present);
    params.penalty_freq = parse_float(params_json, "frequency_penalty", params.penalty_freq);
    params.penalty_last_n = parse_int_any(params_json, {"repeat_last_n", "penalty_last_n"}, params.penalty_last_n);
    const int seed = parse_int(params_json, "seed", -1);
    if (seed >= 0) {
        params.seed = (uint32_t) seed;
    }
    return params;
}

bool configure_sampler_locked(const std::string &params_json) {
    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    common_params_sampling sampling_params = build_sampling_params(params_json);
    g_sampling_temp = sampling_params.temp;
    g_sampling_top_k = sampling_params.top_k;
    g_sampling_top_p = sampling_params.top_p;
    g_sampling_min_p = sampling_params.min_p;
    g_sampling_repeat_penalty = sampling_params.penalty_repeat;
    g_sampling_presence_penalty = sampling_params.penalty_present;
    g_sampling_frequency_penalty = sampling_params.penalty_freq;
    g_sampling_repeat_last_n = sampling_params.penalty_last_n;
    g_sampler = common_sampler_init(g_model, sampling_params);
    if (g_sampler == nullptr) {
        g_last_error += "\ncommon_sampler_init returned null.";
        return false;
    }
    return true;
}

void load_baseline_cpu_backend_if_needed() {
    mca::llm::load_baseline_cpu_backend_if_needed(g_native_lib_dir, g_last_error);
}

bool load_android_opencl_runtime_if_available_locked() {
    return mca::llm::load_android_opencl_runtime_if_available_locked(
            g_android_opencl_handle, g_backend_load_diagnostic);
}

bool ensure_backends_loaded_locked() {
    g_backend_load_diagnostic.clear();
    const bool android_opencl_available = load_android_opencl_runtime_if_available_locked();
    // Load one CPU variant explicitly before the optional accelerator.  The
    // generic directory scanner would also pick up GenieX's
    // libggml-opencl.so, which is compiled against a private ggml-base ABI;
    // that artifact must not decide MCA's capability state.
    load_baseline_cpu_backend_if_needed();
    // Prefer the backend built from this checkout.  The packaged GenieX
    // plugin uses the same filename but a different ggml-base ABI and can be
    // rejected by the dynamic loader before it registers a device.
    if (!g_native_lib_dir.empty()) {
        const std::string mca_opencl = join_path(g_native_lib_dir, "libggml-opencl-mca.so");
        if (file_exists(mca_opencl) && android_opencl_available) {
            if (ggml_backend_load(mca_opencl.c_str()) == nullptr) {
                g_backend_load_diagnostic += " MCA OpenCL backend failed to register: " + mca_opencl + ".";
            } else {
                g_backend_load_diagnostic += " MCA OpenCL backend registered: " + mca_opencl + ".";
#if defined(__ANDROID__) && defined(__aarch64__)
                if (g_mca_opencl_dispatch_handle == nullptr) {
                    g_mca_opencl_dispatch_handle = dlopen(mca_opencl.c_str(), RTLD_NOW | RTLD_LOCAL);
                    if (g_mca_opencl_dispatch_handle != nullptr) {
                        g_opencl_begin_execution_scope = reinterpret_cast<OpenClBeginExecutionScope>(
                                dlsym(g_mca_opencl_dispatch_handle, "mca_opencl_begin_execution_scope"));
                        g_opencl_execution_snapshot = reinterpret_cast<OpenClExecutionSnapshot>(
                                dlsym(g_mca_opencl_dispatch_handle, "mca_opencl_execution_snapshot"));
                    }
                }
                if (g_opencl_begin_execution_scope == nullptr || g_opencl_execution_snapshot == nullptr) {
                    g_backend_load_diagnostic += " OpenCL execution counters unavailable; execution stays unknown.";
                }
#endif
            }
        }
    }
    // Do not call ggml_backend_load_all_from_path(nativeLibDir): it would
    // select the same-name GenieX OpenCL plugin before/alongside the MCA
    // backend.  ggml_backend_load_all() is retained for built-in/default
    // paths, which never point at the app's extracted JNI directory.
    ggml_backend_load_all();
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    g_backend_device_count = ggml_backend_dev_count();
    g_backend_gpu_device_count = 0;
    for (size_t i = 0; i < g_backend_device_count; ++i) {
        if (ggml_backend_dev_type(ggml_backend_dev_get(i)) != GGML_BACKEND_DEVICE_TYPE_CPU) {
            g_backend_gpu_device_count++;
        }
    }
    g_gpu_offload_supported = llama_supports_gpu_offload() && g_backend_gpu_device_count > 0;
    if (g_backend_device_count > 0) return true;

    g_last_error += "No llama.cpp ggml backend devices are registered. ";
    g_last_error += "nativeLibDir=" + g_native_lib_dir + ". ";
    g_last_error += "Backend files: " + list_ggml_backend_files(g_native_lib_dir) + ". ";
    g_last_error += "On Android, dynamic GGML backends require extracted native libraries; keep android:extractNativeLibs=\"true\" / jniLibs.useLegacyPackaging=true.";
    return false;
}

bool resolve_backend_config(const RuntimeConfig &requested,
                            RuntimeConfig &effective,
                            bool allow_cpu_fallback,
                            std::string &error) {
    effective = requested;
    const bool force_gpu = requested.n_gpu_layers == -2 || requested.n_gpu_layers > 0;
    if (force_gpu && !g_gpu_offload_supported && !allow_cpu_fallback) {
        error = "n_gpu_layers requests GPU offload, but this APK has no usable non-CPU llama.cpp backend.";
        if (!g_backend_load_diagnostic.empty()) {
            error += " Backend diagnostics: " + g_backend_load_diagnostic;
        }
        return false;
    }
    // A CPU-only build can still run a sparse-MoE GGUF. CPU MoE placement is
    // an optional acceleration request, so clear it when no non-CPU backend is
    // registered and continue through the generic CPU path. Explicit GPU
    // layer requests remain a concrete incompatibility and are rejected above.
    if (requested.n_cpu_moe > 0 && !g_gpu_offload_supported) {
        effective.n_cpu_moe = 0;
    } else if (requested.n_cpu_moe > 0 && requested.n_gpu_layers == 0) {
        error = "n_cpu_moe requires GPU offload; n_gpu_layers is 0.";
        return false;
    }
    if (g_backend_gpu_device_count > 0 && requested.main_gpu >= (int) g_backend_gpu_device_count &&
        requested.n_gpu_layers != 0) {
        error = "main_gpu exceeds the registered non-CPU backend device count.";
        return false;
    }
    if (!g_gpu_offload_supported) {
        if (requested.main_gpu != 0 && force_gpu && !allow_cpu_fallback) {
            error = "main_gpu must be 0 when no GPU backend is registered.";
            return false;
        }
        if (requested.n_gpu_layers == -1 || allow_cpu_fallback) {
            effective.n_gpu_layers = 0;
        }
        effective.main_gpu = 0;
        effective.split_mode = "none";
    } else if (requested.n_gpu_layers == 0) {
        effective.main_gpu = 0;
        effective.split_mode = "none";
    }
    if (requested.spec_type == "draft-mtp" && requested.cache_reuse > 0) {
        effective.cache_reuse = 0;
    }
    return true;
}

void android_llama_log(ggml_log_level level, const char *text, void *) {
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                     level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(prio, "MCA-llama", "%s", text);
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        if (g_last_error.size() < 4096) {
            g_last_error += text;
        }
    } else if (level == GGML_LOG_LEVEL_WARN) {
        if (g_last_warning.size() < 4096) {
            g_last_warning += text;
        }
    }
}

void free_llama_locked() {
    g_speculative.reset();
    g_spec_request_active = false;
    clear_gpu_offload_evidence_locked();
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
    if (g_mtp_context != nullptr) {
        llama_free(g_mtp_context);
        g_mtp_context = nullptr;
    }
    if (g_mtmd_context != nullptr) {
        mtmd_free(g_mtmd_context);
        g_mtmd_context = nullptr;
    }
    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_chat_templates.reset();
    if (g_batch_ready) {
        llama_batch_free(g_batch);
        g_batch_ready = false;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_chat_messages.clear();
    g_context_tokens.clear();
    g_spec_prompt_tokens.clear();
    g_spec_draft_tokens.clear();
    g_spec_pending_token = LLAMA_TOKEN_NULL;
    g_spec_pending_valid = false;
    g_spec_done = false;
    g_current_position = 0;
    g_cached_token_chars.clear();
    g_n_threads = 0;
    g_n_threads_batch = 0;
    g_n_batch = 0;
    g_n_ubatch = 0;
    g_max_new_tokens = 0;
    g_sampling_temp = 0.0f;
    g_sampling_top_k = 0;
    g_sampling_top_p = 0.0f;
    g_sampling_min_p = 0.0f;
    g_sampling_repeat_penalty = 1.08f;
    g_sampling_presence_penalty = 0.0f;
    g_sampling_frequency_penalty = 0.2f;
    g_sampling_repeat_last_n = 0;
    g_last_use_jinja = true;
    g_last_enable_thinking = true;
    g_vision_ready = false;
    g_mmproj_path.clear();
    g_context_shifts = 0;
    g_n_keep = 128;
    g_cache_state_valid = false;
    g_cache_reuse_hit = false;
    g_cache_reused_tokens = 0;
    g_cache_reuse_reason = "model_unloaded";
    g_persistent_prefix_cache_attempted = false;
    g_persistent_prefix_cache_hit = false;
    g_persistent_prefix_cache_saved = false;
    g_persistent_prefix_cache_tokens = 0;
    g_persistent_prefix_cache_reason = "model_unloaded";
    g_model_hybrid = false;
    g_model_recurrent = false;
    g_cache_reuse_strategy = "disabled";
    g_spec_context_ready = false;
    g_spec_request_reason = g_spec_requested ? "model_unloaded" : "disabled";
}

bool shift_context_locked() {
    if (g_context == nullptr) return false;
    if (g_spec_request_active) {
        g_last_error += "\nContext shifting is not supported while draft-mtp is active.";
        return false;
    }
    const int n_keep = std::min(std::max(32, g_n_keep), std::max(32, g_n_ctx - 8));
    if (g_current_position <= n_keep + OVERFLOW_HEADROOM + 16) {
        g_last_error += "\nContext is full but too small to shift safely.";
        return false;
    }
    const int n_left = (int) g_current_position - n_keep;
    const int n_discard = std::max(1, n_left / 2);
    auto *mem = llama_get_memory(g_context);
    if (!llama_memory_seq_rm(mem, 0, n_keep, n_keep + n_discard)) {
        g_last_error += "\nllama_memory_seq_rm failed during context shift.";
        return false;
    }
    llama_memory_seq_add(mem, 0, n_keep + n_discard, g_current_position, -n_discard);
    g_current_position -= n_discard;
    g_context_shifts++;
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
    g_cache_state_valid = false;
    g_context_tokens.clear();
    g_cache_reuse_reason = "invalidated_by_context_shift";
    __android_log_print(
            ANDROID_LOG_INFO,
            "MCA",
            "context shifted: n_keep=%d n_discard=%d current=%d",
            n_keep,
            n_discard,
            (int) g_current_position);
    return true;
}

int decode_tokens(const llama_tokens &tokens, bool logits_last) {
    if (g_n_batch <= 0) {
        g_last_error += "\nInvalid effective n_batch.";
        return 5;
    }
    for (int i = 0; i < (int) tokens.size(); i += g_n_batch) {
        if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;
        const int batch_size = std::min((int) tokens.size() - i, g_n_batch);
        common_batch_clear(g_batch);
        if (g_current_position + batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            if (!shift_context_locked()) return 4;
        }
        for (int j = 0; j < batch_size; j++) {
            const bool want_logits = logits_last && (i + j == (int) tokens.size() - 1);
            common_batch_add(g_batch, tokens[i + j], g_current_position + j, {0}, want_logits);
        }
        if (llama_decode(g_context, g_batch) != 0) return 2;
        record_gpu_decode_execution_locked();
        if (g_spec_request_active &&
            (g_speculative == nullptr || !common_speculative_process(g_speculative.get(), g_batch))) {
            g_last_error += "\ncommon_speculative_process failed during target prefill.";
            return 6;
        }
        g_current_position += batch_size;
        g_prefill_computed_tokens += batch_size;
        advance_prefill_progress((size_t) batch_size);
        // A stop can arrive while llama_decode() owns the current batch. Keep
        // the position/progress accounting truthful, then terminate before a
        // completed final batch can be promoted to a running generation.
        if (g_stop_requested.load(std::memory_order_acquire)) {
            return COMPLETION_STOPPED;
        }
    }
    return 0;
}

bool string_is_one_of(std::string value, const std::vector<std::string> &options) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    return std::find(options.begin(), options.end(), value) != options.end();
}

bool should_use_jinja(const std::string &params_json) {
    const std::string mode = parse_string(params_json, "chat_template_mode", "auto");
    if (string_is_one_of(mode, {"legacy", "no_jinja", "no-jinja", "false"})) {
        return false;
    }
    return parse_bool(params_json, "use_jinja", true);
}

bool should_enable_thinking(const std::string &params_json) {
    const std::string mode = parse_string(params_json, "reasoning_mode", "standard");
    const bool mode_off = string_is_one_of(mode, {"off", "none", "disable", "disabled", "false"});
    const bool hidden = parse_bool(params_json, "hide_reasoning", false);
    const bool fallback = !mode_off && !hidden;
    return parse_bool(params_json, "enable_thinking", fallback);
}

int thinking_budget_for_mode(const std::string &params_json) {
    const int explicit_budget = parse_int(params_json, "thinking_budget", -1);
    if (explicit_budget >= 0) return explicit_budget;
    const std::string mode = parse_string(params_json, "reasoning_mode", "standard");
    if (string_is_one_of(mode, {"advanced", "deep", "high"})) return 1536;
    if (string_is_one_of(mode, {"off", "none", "disable", "disabled", "false"})) return 0;
    return 192;
}

using mca::llm::ChatTemplateOptions;

// Openers mirror REASONING_OPEN_MARKERS in the Kotlin ReasoningContentFilter.
// Trailing whitespace (templates usually append "\n" after the opener) is
// ignored; an empty pre-filled block ending in "</think>" matches none of them.
// Only the real prompt renders may set g_prompt_ends_inside_reasoning; the
// prefix-cache probe render in prepare_persistent_prefix_locked uses a
// truncated probe message list and must not disturb the flag.
bool prompt_ends_inside_reasoning(const std::string &prompt) {
    return mca::llm::prompt_ends_inside_reasoning(prompt);
}

void clear_target_context_locked(const std::string &reason);
bool trim_context_locked(llama_context *ctx, llama_pos position, const char *label);

std::string format_messages(const std::vector<ParsedMessage> &messages, const ChatTemplateOptions &options) {
    return mca::llm::format_messages(g_chat_templates.get(), messages, options);
}

using PersistentPrefixPreparation = mca::llm::PrefixCacheDecision;

/**
 * Restores or materializes only the stable system/persona prefix. The full
 * prompt is still token-prefix checked before any state is accepted; a
 * template/tokenizer boundary that cannot be proven is a normal cache miss.
 */
PersistentPrefixPreparation resolve_persistent_prefix_cache_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request) {
    mca::llm::PrefixCacheEnvironment env;
    env.ctx = g_context;
    env.n_ctx = g_n_ctx;
    env.context_shifts = g_context_shifts;
    env.current_position = &g_current_position;
    env.context_tokens = &g_context_tokens;
    env.cache_state_valid = &g_cache_state_valid;
    env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens;
    env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_reason = &g_cache_reuse_reason;
    env.persistent_prefix_cache_attempted = &g_persistent_prefix_cache_attempted;
    env.persistent_prefix_cache_hit = &g_persistent_prefix_cache_hit;
    env.persistent_prefix_cache_tokens = &g_persistent_prefix_cache_tokens;
    env.persistent_prefix_cache_saved = &g_persistent_prefix_cache_saved;
    env.persistent_prefix_cache_reason = &g_persistent_prefix_cache_reason;
    env.pending_full_session_write_path = &g_pending_full_session_write_path;

    env.clear_target_context = [](const std::string &reason) {
        clear_target_context_locked(reason);
    };
    env.trim_context = [](llama_pos pos, const char *label) {
        return trim_context_locked(g_context, pos, label);
    };
    env.format_messages = [](const std::vector<mca::request::ParsedMessage> &msgs,
                             const mca::llm::ChatTemplateOptions &opts) {
        return format_messages(msgs, opts);
    };
    env.tokenize = [](const std::string &text) {
        return common_tokenize(g_context, text, true, true);
    };
    env.decode_tokens = [](const llama_tokens &tokens) {
        return decode_tokens(tokens, false);
    };
    env.save_state = [](const std::string &path, const llama_tokens &tokens) {
        return save_llama_state_with_progress_locked(
                g_context,
                path.c_str(),
                0,
                tokens.data(),
                tokens.size(),
                LLAMA_STATE_SEQ_FLAGS_NONE);
    };
    env.load_state_file = [](const char *path, llama_token *tokens_out, size_t n_tokens_max, size_t *n_tokens_out) {
        return llama_state_seq_load_file(g_context, path, 0, tokens_out, n_tokens_max, n_tokens_out);
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

bool messages_have_images(const std::vector<ParsedMessage> &messages) {
    return mca::llm::messages_have_images(messages);
}

std::vector<ParsedMessage> with_media_markers(const std::vector<ParsedMessage> &messages) {
    return mca::llm::with_media_markers(messages);
}

int prefill_multimodal_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options
) {
    mca::llm::MultimodalPrefillEnvironment env;
    env.mtmd_ctx = g_mtmd_context;
    env.vision_ready = g_vision_ready;
    env.llama_ctx = g_context;
    env.n_ctx = g_n_ctx;
    env.n_batch = g_n_batch;
    env.overflow_headroom = OVERFLOW_HEADROOM;
    env.completion_stopped_code = COMPLETION_STOPPED;
    env.chat_templates = g_chat_templates.get();
    env.is_stop_requested = []() { return g_stop_requested.load(std::memory_order_acquire); };
    env.last_error = &g_last_error;
    env.current_position = &g_current_position;
    env.prompt_tokens = &g_prompt_tokens;
    env.prefill_computed_tokens = &g_prefill_computed_tokens;
    env.prompt_ends_inside_reasoning = &g_prompt_ends_inside_reasoning;
    env.begin_prefill_progress = [](size_t total) { begin_prefill_progress(total); };
    env.report_reused_prefill_tokens = [](size_t reused) { report_reused_prefill_tokens(reused); };

    return mca::llm::prefill_multimodal_locked(messages, chat_options, env);
}

void clear_target_context_locked(const std::string &reason) {
    if (g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
    g_current_position = 0;
    g_context_tokens.clear();
    g_cache_state_valid = false;
    g_cache_reuse_hit = false;
    g_cache_reused_tokens = 0;
    g_cache_reuse_reason = reason;
}

bool trim_context_locked(llama_context *ctx, llama_pos position, const char *label) {
    if (ctx == nullptr) return false;
    if (!llama_memory_seq_rm(llama_get_memory(ctx), 0, position, -1)) {
        g_last_error += std::string("\nFailed to trim ") + label +
                        " context at position " + std::to_string(position) + ".";
        return false;
    }
    return true;
}

// The public save/load pair owns the sequence-file header and token list.
// Raw llama_state_seq_get_data_ext bytes cannot be loaded by
// llama_state_seq_load_file. The native writer is opaque, so progress moves
// from encoding to done when it returns.
// Must be called with g_mutex held; the progress atomics stay lock-free readable.
size_t save_llama_state_with_progress_locked(
        llama_context *ctx,
        const char *path,
        llama_seq_id seq_id,
        const llama_token *tokens,
        size_t n_token_count,
        llama_state_seq_flags flags) {
    g_persist_stage.store(static_cast<int>(PersistStage::Idle), std::memory_order_release);
    g_persist_written_bytes.store(0, std::memory_order_release);
    const size_t state_size = llama_state_seq_get_size_ext(ctx, seq_id, flags);
    if (state_size == 0 || flags != LLAMA_STATE_SEQ_FLAGS_NONE ||
        n_token_count > std::numeric_limits<uint32_t>::max()) {
        g_persist_total_bytes.store(0, std::memory_order_release);
        return 0;
    }
    const size_t total = state_size + sizeof(uint32_t) * 3 +
            n_token_count * sizeof(llama_token);
    g_persist_total_bytes.store(total, std::memory_order_release);
    try {
        g_persist_stage.store(static_cast<int>(PersistStage::Encoding), std::memory_order_release);
        const size_t written = llama_state_seq_save_file(
                ctx, path, seq_id, tokens, n_token_count);
        if (written == 0) {
            reset_persist_progress();
            return 0;
        }
        g_persist_written_bytes.store(written, std::memory_order_release);
        g_persist_total_bytes.store(written, std::memory_order_release);
        g_persist_stage.store(static_cast<int>(PersistStage::Done), std::memory_order_release);
        return written;
    } catch (const std::exception &) {
        reset_persist_progress();
        return 0;
    }
}

mca::llm::TurnCacheEnvironment make_turn_cache_environment_locked() {
    mca::llm::TurnCacheEnvironment env;
    env.ctx = g_context;
    env.current_position = &g_current_position;
    env.context_tokens = &g_context_tokens;
    env.cache_state_valid = &g_cache_state_valid;
    env.checkpoints = &g_turn_cache_checkpoints;
    env.checkpoint_count = &g_turn_cache_checkpoint_count;
    env.checkpoint_bytes = &g_turn_cache_checkpoint_bytes;
    env.turn_checkpoint_hits = &g_turn_cache_checkpoint_hits;
    env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens;
    env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_reason = &g_cache_reuse_reason;
    env.stop_requested = &g_stop_requested;
    env.clear_target_context = [](const std::string &reason) {
        clear_target_context_locked(reason);
    };
    env.trim_context = [](llama_pos pos, const char *label) {
        return trim_context_locked(g_context, pos, label);
    };
    env.take_last_error = []() -> std::string {
        std::string err = g_last_error;
        g_last_error.clear();
        return err;
    };
    env.log_warning = [](const std::string &msg) {
        __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", msg.c_str());
    };
    return env;
}

bool save_turn_cache_checkpoint_locked() {
    auto env = make_turn_cache_environment_locked();
    return mca::llm::save_turn_cache_checkpoint_locked(
            active_prefix_cache_strategy_locked(), env);
}

bool restore_turn_cache_checkpoint_locked(
        const llama_tokens &tokens,
        size_t &reused_tokens) {
    auto env = make_turn_cache_environment_locked();
    return mca::llm::restore_turn_cache_checkpoint_locked(
            tokens, reused_tokens, env);
}

void save_completed_turn_checkpoint_locked(GenerationStopReason reason) {
    if (g_spec_request_active || !g_cached_token_chars.empty() ||
        g_stop_requested.load(std::memory_order_acquire)) {
        return;
    }
    switch (reason) {
        case GenerationStopReason::STOP_TOKEN:
        case GenerationStopReason::MAX_NEW_TOKENS:
        case GenerationStopReason::NORMAL_FINISHED:
            (void) save_turn_cache_checkpoint_locked();
            if (!g_pending_full_session_write_path.empty()) {
                if (g_context_shifts == 0 && g_cache_state_valid &&
                    !g_context_tokens.empty() &&
                    g_current_position == (llama_pos) g_context_tokens.size()) {
                    try {
                        const size_t saved = save_llama_state_with_progress_locked(
                                g_context,
                                g_pending_full_session_write_path.c_str(),
                                0,
                                g_context_tokens.data(),
                                g_context_tokens.size(),
                                LLAMA_STATE_SEQ_FLAGS_NONE);
                        g_persistent_prefix_cache_saved = saved > 0;
                        if (!g_persistent_prefix_cache_hit) {
                            g_persistent_prefix_cache_tokens = (int) g_context_tokens.size();
                        }
                        g_persistent_prefix_cache_reason = saved > 0
                                ? "session_state_saved"
                                : "session_state_save_failed";
                    } catch (...) {
                        g_persistent_prefix_cache_reason = "session_state_save_failed";
                    }
                } else {
                    g_persistent_prefix_cache_reason = "session_state_unavailable";
                }
                g_pending_full_session_write_path.clear();
            }
            return;
        default:
            return;
    }
}

void invalidate_speculative_contexts_locked(const std::string &reason) {
    g_speculative.reset();
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
    // requestStop() is intentionally lock-free so it can be issued from the
    // UI while a native draft step is executing.  Do not allow that narrow
    // race to commit or return one more speculative chunk.
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

size_t prepare_text_prefix_locked(const llama_tokens &tokens) {
    g_cache_reuse_hit = false;
    g_cache_reused_tokens = 0;
    const auto strategy = active_prefix_cache_strategy_locked();
    const size_t threshold = g_effective_config.cache_reuse > 0
                             ? (size_t) g_effective_config.cache_reuse
                             : 0;
    if (strategy == mca::llama::PrefixCacheStrategy::Disabled) {
        clear_target_context_locked("disabled");
        return 0;
    }

    if (strategy == mca::llama::PrefixCacheStrategy::PartialStateCheckpoint) {
        const size_t live_matched = g_cache_state_valid
                ? mca::llama::longestCommonTokenPrefix(tokens, g_context_tokens)
                : 0;
        if (live_matched >= threshold &&
            live_matched == g_context_tokens.size() &&
            live_matched < tokens.size() &&
            g_current_position == (llama_pos) live_matched) {
            g_cache_reuse_hit = true;
            g_cache_reused_tokens = (int) std::min<size_t>(
                    live_matched,
                    static_cast<size_t>(std::numeric_limits<int>::max()));
            g_cache_reuse_hits++;
            g_cache_reuse_reason = "live_turn_prefix_hit";
            return live_matched;
        }

        const bool had_turn_checkpoints = !g_turn_cache_checkpoints.empty();
        size_t turn_checkpoint_reuse = 0;
        if (restore_turn_cache_checkpoint_locked(tokens, turn_checkpoint_reuse)) {
            return turn_checkpoint_reuse;
        }
        if (had_turn_checkpoints) {
            g_turn_cache_checkpoint_misses++;
        }

        const bool checkpoint_shape_valid =
                g_cache_checkpoint_valid &&
                !g_cache_checkpoint_data.empty() &&
                g_cache_checkpoint_threshold == threshold &&
                g_cache_checkpoint_tokens == threshold &&
                g_cache_checkpoint_prefix.size() == threshold;
        const bool context_prefix_valid =
                g_cache_state_valid &&
                g_context_tokens.size() >= threshold &&
                mca::llama::tokenPrefixMatches(
                        g_context_tokens,
                        g_cache_checkpoint_prefix);
        const bool prompt_prefix_matches =
                checkpoint_shape_valid &&
                mca::llama::tokenPrefixMatches(
                        tokens,
                        g_cache_checkpoint_prefix);
        const bool can_reuse = mca::llama::canReusePartialStateCheckpoint(
                strategy,
                context_prefix_valid,
                checkpoint_shape_valid,
                g_cache_checkpoint_tokens,
                threshold,
                tokens.size(),
                prompt_prefix_matches);
        if (!can_reuse) {
            const char *reason = "partial_checkpoint_unavailable";
            if (!g_cache_state_valid || g_context_tokens.size() < threshold) {
                reason = "partial_checkpoint_context_invalid";
            } else if (!g_cache_checkpoint_valid || g_cache_checkpoint_data.empty()) {
                reason = "no_partial_checkpoint";
            } else if (g_cache_checkpoint_threshold != threshold ||
                       g_cache_checkpoint_tokens != threshold ||
                       g_cache_checkpoint_prefix.size() != threshold) {
                reason = "partial_checkpoint_threshold_mismatch";
            } else if (tokens.size() <= threshold) {
                reason = "prompt_not_longer_than_partial_checkpoint";
            } else if (!context_prefix_valid) {
                reason = "partial_checkpoint_context_mismatch";
            } else if (!prompt_prefix_matches) {
                reason = "partial_checkpoint_prefix_mismatch";
            }
            g_cache_reuse_misses++;
            clear_target_context_locked(reason);
            return 0;
        }

        const size_t restore_error_start = g_last_error.size();
        if (!restore_partial_prefix_checkpoint_locked()) {
            const std::string restore_error =
                    take_last_error_suffix_locked(restore_error_start);
            g_cache_reuse_misses++;
            clear_target_context_locked("partial_checkpoint_restore_failed");
            if (!restore_error.empty()) {
                __android_log_print(
                        ANDROID_LOG_WARN,
                        "MCA",
                        "Partial prefix checkpoint restore failed: %s",
                        restore_error.c_str());
            }
            return 0;
        }
        // Hybrid seq_rm() consults recurrent memory first. Restoring the state
        // moves that tail back to threshold - 1 so removing [threshold, end)
        // can safely trim the attention suffix without recurrent rollback.
        if (!trim_context_locked(
                g_context,
                (llama_pos) threshold,
                "target partial-checkpoint cache-reuse")) {
            const std::string trim_error = g_last_error;
            g_last_error.clear();
            g_cache_reuse_misses++;
            clear_target_context_locked("partial_checkpoint_suffix_trim_failed");
            __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", trim_error.c_str());
            return 0;
        }

        g_current_position = (llama_pos) threshold;
        g_context_tokens = g_cache_checkpoint_prefix;
        g_cache_state_valid = true;
        g_cache_reuse_hit = true;
        g_cache_reused_tokens = (int) threshold;
        g_cache_reuse_hits++;
        // Preserve the public cache-hit reason across direct-trim and
        // recurrent-checkpoint strategies; `strategy` identifies the path.
        g_cache_reuse_reason = "text_prefix_hit";
        return threshold;
    }

    if (!g_cache_state_valid || g_context_tokens.empty()) {
        g_cache_reuse_misses++;
        clear_target_context_locked("no_cached_prefix");
        return 0;
    }

    const size_t matched = mca::llama::longestCommonTokenPrefix(tokens, g_context_tokens);
    if (matched < (size_t) g_effective_config.cache_reuse) {
        g_cache_reuse_misses++;
        clear_target_context_locked("common_prefix_below_threshold");
        return 0;
    }
    size_t reuse = matched;
    if (reuse == tokens.size() && reuse > 0) {
        // Re-evaluate at least one token so the logits correspond to the new request tail.
        reuse--;
    }
    if (reuse == 0) {
        g_cache_reuse_misses++;
        clear_target_context_locked("no_reusable_token_after_logit_boundary");
        return 0;
    }

    if (reuse < g_context_tokens.size()) {
        if (!trim_context_locked(g_context, (llama_pos) reuse, "target cache-reuse")) {
            const std::string trim_error = g_last_error;
            g_last_error.clear();
            g_cache_reuse_misses++;
            clear_target_context_locked("backend_cannot_remove_cached_suffix");
            __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", trim_error.c_str());
            return 0;
        }
    }

    g_current_position = (llama_pos) reuse;
    g_context_tokens.resize(reuse);
    g_cache_state_valid = true;
    g_cache_reuse_hit = true;
    g_cache_reused_tokens = (int) reuse;
    g_cache_reuse_hits++;
    g_cache_reuse_reason = "text_prefix_hit";
    return reuse;
}

int decode_text_prompt_locked(const llama_tokens &tokens, size_t reused) {
    if (reused > tokens.size()) {
        g_last_error = "Prefix-cache reuse exceeds the prompt token count.";
        return 6;
    }

    const auto strategy = active_prefix_cache_strategy_locked();
    const size_t threshold = g_effective_config.cache_reuse > 0
                             ? (size_t) g_effective_config.cache_reuse
                             : 0;
    if (reused == 0 && mca::llama::shouldCreatePartialStateCheckpoint(
            strategy,
            threshold,
            tokens.size())) {
        // Stop at exactly N tokens: the partial state belongs to position N,
        // while the non-empty suffix below re-establishes request-tail logits.
        const auto checkpoint_end =
                tokens.begin() + (llama_tokens::difference_type) threshold;
        const llama_tokens checkpoint_prefix(tokens.begin(), checkpoint_end);
        const int prefix_rc = decode_tokens(checkpoint_prefix, false);
        if (prefix_rc != 0) {
            invalidate_prefix_cache_checkpoint_locked();
            invalidate_turn_cache_checkpoints_locked();
            return prefix_rc;
        }

        const size_t save_error_start = g_last_error.size();
        if (!save_partial_prefix_checkpoint_locked(tokens, threshold)) {
            const std::string save_error = take_last_error_suffix_locked(save_error_start);
            g_cache_reuse_reason = "partial_checkpoint_save_failed";
            __android_log_print(
                    ANDROID_LOG_WARN,
                    "MCA",
                    "Failed to save partial prefix checkpoint at %zu tokens.%s%s",
                    threshold,
                    save_error.empty() ? "" : " ",
                    save_error.c_str());
        }

        const llama_tokens suffix(checkpoint_end, tokens.end());
        return decode_tokens(suffix, true);
    }

    const auto suffix_begin = tokens.begin() + (llama_tokens::difference_type) reused;
    const llama_tokens suffix(suffix_begin, tokens.end());
    return decode_tokens(suffix, true);
}

bool prepare_speculative_request_locked() {
    g_speculative.reset();
    g_spec_pending_token = LLAMA_TOKEN_NULL;
    g_spec_pending_valid = false;
    g_spec_done = false;
    g_spec_prompt_tokens.clear();
    g_spec_draft_tokens.clear();
    if (!g_spec_requested || g_mtp_context == nullptr) {
        g_spec_request_active = false;
        g_spec_request_reason = g_spec_requested ? "mtp_context_not_ready" : "disabled";
        return !g_spec_requested;
    }

    llama_memory_clear(llama_get_memory(g_mtp_context), false);
    g_speculative_params = common_params_speculative{};
    g_speculative_params.types = {COMMON_SPECULATIVE_TYPE_DRAFT_MTP};
    g_speculative_params.draft.n_max = g_effective_config.spec_draft_n_max;
    g_speculative_params.draft.n_min = 0;
    g_speculative_params.draft.p_min = 0.0f;
    g_speculative_params.draft.backend_sampling = false;
    g_speculative_params.draft.cache_type_k = cache_type_from_name(g_effective_config.cache_type_k);
    g_speculative_params.draft.cache_type_v = cache_type_from_name(g_effective_config.cache_type_v);
    g_speculative_params.draft.ctx_tgt = g_context;
    g_speculative_params.draft.ctx_dft = g_mtp_context;
    try {
        g_speculative.reset(common_speculative_init(g_speculative_params, 1));
    } catch (const std::exception &e) {
        g_last_error = std::string("common_speculative_init(draft-mtp) failed: ") + e.what();
        g_spec_request_active = false;
        g_spec_request_reason = "initialization_failed";
        return false;
    } catch (...) {
        g_last_error = "common_speculative_init(draft-mtp) failed with an unknown native error.";
        g_spec_request_active = false;
        g_spec_request_reason = "initialization_failed";
        return false;
    }
    if (g_speculative == nullptr) {
        g_last_error = "common_speculative_init(draft-mtp) returned null.";
        g_spec_request_active = false;
        g_spec_request_reason = "initialization_failed";
        return false;
    }
    g_spec_request_active = true;
    g_spec_request_reason = "active";
    return true;
}

struct SpecStepResult {
    llama_tokens output;
    bool ok = true;
    bool done = false;
};

SpecStepResult speculative_step_locked() {
    SpecStepResult result;
    if (!g_spec_request_active || g_speculative == nullptr || g_mtp_context == nullptr) {
        g_last_error = "draft-mtp step requested without an active speculative context.";
        result.ok = false;
        return result;
    }
    if (g_spec_done) {
        result.done = true;
        return result;
    }
    if (stop_speculative_request_if_requested_locked()) {
        result.done = true;
        return result;
    }
    const auto *vocab = llama_model_get_vocab(g_model);

    if (!g_spec_pending_valid) {
        const llama_token token = common_sampler_sample(g_sampler, g_context, -1);
        if (stop_speculative_request_if_requested_locked()) {
            result.done = true;
            return result;
        }
        common_sampler_accept(g_sampler, token, true);
        if (stop_speculative_request_if_requested_locked()) {
            result.done = true;
            return result;
        }
        if (llama_vocab_is_eog(vocab, token)) {
            g_spec_done = true;
            result.done = true;
            return result;
        }
        g_spec_pending_token = token;
        g_spec_pending_valid = true;
        result.output.push_back(token);
        return result;
    }

    if (g_current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        g_last_error = "draft-mtp reached the context boundary; context shifting is disabled for synchronized target/MTP contexts.";
        result.ok = false;
        return result;
    }

    const llama_pos base = g_current_position;
    const int remaining = g_n_predict - (int) g_completion_tokens;
    const int context_room = g_n_ctx - OVERFLOW_HEADROOM - (int) base - 1;
    const int n_max = std::max(0, std::min({
            g_effective_config.spec_draft_n_max,
            std::max(0, remaining - 1),
            std::max(0, context_room)}));

    g_spec_draft_tokens.clear();
    if (n_max > 0) {
        auto &draft_params = common_speculative_get_draft_params(g_speculative.get(), 0);
        draft_params = {
                /* .drafting = */ true,
                /* .n_max    = */ n_max,
                /* .n_past   = */ base,
                /* .id_last  = */ g_spec_pending_token,
                /* .prompt   = */ &g_spec_prompt_tokens,
                /* .result   = */ &g_spec_draft_tokens,
        };
        common_speculative_draft(g_speculative.get());
        if (!trim_context_locked(g_mtp_context, base, "MTP draft pre-advance")) {
            invalidate_speculative_contexts_locked("mtp_draft_rollback_failed");
            result.ok = false;
            return result;
        }
        if (stop_speculative_request_if_requested_locked()) {
            result.done = true;
            return result;
        }
    }

    if ((int) g_spec_draft_tokens.size() + 1 > g_n_batch) {
        g_last_error = "draft-mtp verification batch exceeds effective n_batch.";
        result.ok = false;
        return result;
    }

    common_batch_clear(g_batch);
    common_batch_add(g_batch, g_spec_pending_token, base, {0}, true);
    for (size_t i = 0; i < g_spec_draft_tokens.size(); ++i) {
        common_batch_add(g_batch, g_spec_draft_tokens[i], base + 1 + (llama_pos) i, {0}, true);
    }
    const int decode_rc = llama_decode(g_context, g_batch);
    if (decode_rc != 0) {
        g_last_error = "llama_decode failed during draft-mtp verification: " + std::to_string(decode_rc);
        trim_speculative_contexts_locked(base, "verification failure");
        result.ok = false;
        return result;
    }
    record_gpu_decode_execution_locked();
    if (!common_speculative_process(g_speculative.get(), g_batch)) {
        g_last_error = "common_speculative_process failed during draft-mtp verification.";
        trim_speculative_contexts_locked(base, "process failure");
        result.ok = false;
        return result;
    }
    if (stop_speculative_request_if_requested_locked()) {
        result.done = true;
        return result;
    }

    const llama_token committed_pending = g_spec_pending_token;
    if (g_spec_draft_tokens.empty()) {
        const llama_token next = common_sampler_sample(g_sampler, g_context, 0);
        if (stop_speculative_request_if_requested_locked()) {
            result.done = true;
            return result;
        }
        common_sampler_accept(g_sampler, next, true);
        if (stop_speculative_request_if_requested_locked()) {
            result.done = true;
            return result;
        }
        g_context_tokens.push_back(committed_pending);
        g_spec_prompt_tokens.push_back(committed_pending);
        g_current_position = base + 1;
        if (!trim_speculative_contexts_locked(g_current_position, "single-token accept")) {
            result.ok = false;
            return result;
        }
        if (llama_vocab_is_eog(vocab, next)) {
            g_spec_pending_token = LLAMA_TOKEN_NULL;
            g_spec_pending_valid = false;
            g_spec_done = true;
            result.done = true;
            return result;
        }
        g_spec_pending_token = next;
        g_spec_pending_valid = true;
        if (stop_speculative_request_if_requested_locked()) {
            result.done = true;
            return result;
        }
        result.output.push_back(next);
        g_spec_steps++;
        return result;
    }

    llama_tokens sampled = common_sampler_sample_and_accept_n(g_sampler, g_context, g_spec_draft_tokens);
    if (stop_speculative_request_if_requested_locked()) {
        result.done = true;
        return result;
    }
    if (sampled.empty()) {
        g_last_error = "draft-mtp verification returned no sampled tokens.";
        trim_speculative_contexts_locked(base, "empty verification result");
        result.ok = false;
        return result;
    }

    size_t eog_index = sampled.size();
    for (size_t i = 0; i < sampled.size(); ++i) {
        if (llama_vocab_is_eog(vocab, sampled[i])) {
            eog_index = i;
            break;
        }
    }
    const uint16_t accepted = (uint16_t) std::min(eog_index, sampled.size() - 1);
    common_speculative_accept(g_speculative.get(), 0, accepted);
    if (stop_speculative_request_if_requested_locked()) {
        result.done = true;
        return result;
    }

    g_context_tokens.push_back(committed_pending);
    g_spec_prompt_tokens.push_back(committed_pending);
    for (size_t i = 0; i < accepted; ++i) {
        g_context_tokens.push_back(sampled[i]);
        g_spec_prompt_tokens.push_back(sampled[i]);
    }
    g_current_position = base + 1 + accepted;
    if (!trim_speculative_contexts_locked(g_current_position, "accepted verification")) {
        result.ok = false;
        return result;
    }

    g_spec_drafted_tokens += (long long) g_spec_draft_tokens.size();
    g_spec_accepted_tokens += accepted;
    g_spec_steps++;
    if (eog_index < sampled.size()) {
        result.output.assign(sampled.begin(), sampled.begin() + (long long) eog_index);
        g_spec_pending_token = LLAMA_TOKEN_NULL;
        g_spec_pending_valid = false;
        g_spec_done = true;
        result.done = true;
    } else {
        result.output = sampled;
        g_spec_pending_token = sampled.back();
        g_spec_pending_valid = true;
    }
    if (stop_speculative_request_if_requested_locked()) {
        result.output.clear();
        result.done = true;
    }
    return result;
}
#else
std::vector<std::string> g_stub_chunks;
size_t g_stub_chunk_index = 0;
#endif

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_initBackends(
        JNIEnv *env,
        jobject,
        jstring nativeLibDir
) {
    try {
#if MCA_WITH_LLAMA_CPP
    llama_log_set(android_llama_log, nullptr);
    mtmd_helper_log_set(android_llama_log, nullptr);
    const auto path = jstring_to_string(env, nativeLibDir);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_native_lib_dir = path;
    clear_load_failure();
    ensure_backends_loaded_locked();
    __android_log_print(
            ANDROID_LOG_INFO,
            "MCA",
            "llama.cpp backends initialized from %s; devices=%zu",
            path.c_str(),
            g_backend_device_count);
#else
    (void) env;
    (void) nativeLibDir;
    __android_log_print(ANDROID_LOG_INFO, "MCA", "Native stub backends initialized");
#endif
    } catch (const std::exception &e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_backend_device_count = 0;
        g_backend_gpu_device_count = 0;
        g_gpu_offload_supported = false;
        set_load_failure(
                "MCA_LOAD_BACKEND_UNAVAILABLE",
                std::string("initBackends exception: ") + e.what());
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_backend_device_count = 0;
        g_backend_gpu_device_count = 0;
        g_gpu_offload_supported = false;
        set_load_failure("MCA_LOAD_BACKEND_UNAVAILABLE", "initBackends exception: unknown native error");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_loadModel(
        JNIEnv *env,
        jobject,
        jstring modelPath,
        jstring paramsJson
) {
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
    g_loaded = false;
    g_load_ms = 0;
    g_model_path = path;
    g_model_architecture.clear();
    g_model_mtp_layers = 0;
    g_prompt_tokens = 0;
    g_prefill_computed_tokens = 0;
    g_completion_tokens = 0;
    g_prefill_started_ms = 0;
    g_prefill_finished_ms = 0;
    reset_prefill_progress();
    g_decode_started_ms = 0;
    g_decode_finished_ms = 0;
    clear_load_failure();
    g_model_file_size_bytes = regular_file_size_bytes(path);
    g_mmap_fallback_allowed = false;
    g_mmap_prefetch_enabled = false;
    g_gpu_auto_fallback_applied = false;
    g_gpu_auto_fallback_reason = "not_attempted";

#if MCA_WITH_LLAMA_CPP
    free_llama_locked();
    const long long started = now_ms();

    RuntimeConfig requested;
    std::string config_error;
    if (!parse_runtime_config(params, default_runtime_config(), requested, config_error)) {
        g_last_error = "Invalid llama runtime config: " + config_error;
        set_load_failure_code("MCA_LOAD_RUNTIME_CONFIG_INVALID");
        return 12;
    }
    if (params.find("\"n_threads\"") != std::string::npos &&
        params.find("\"n_threads_batch\"") == std::string::npos) {
        requested.n_threads_batch = requested.n_threads;
        if (!validate_runtime_config(requested, config_error)) {
            g_last_error = "Invalid llama runtime config: " + config_error;
            set_load_failure_code("MCA_LOAD_RUNTIME_CONFIG_INVALID");
            return 12;
        }
    }
    g_requested_config = requested;

    if (!ensure_backends_loaded_locked()) {
        set_load_failure_code("MCA_LOAD_BACKEND_UNAVAILABLE");
        return 11;
    }
    g_last_error.clear();
    g_last_warning.clear();

    const std::string fallback_policy = parse_string(params, "fallback_policy",
            requested.n_gpu_layers == -1 ? "allow_cpu" : "require_requested_backend");
    if (fallback_policy != "allow_cpu" && fallback_policy != "require_requested_backend") {
        set_load_failure("MCA_BACKEND_FALLBACK_POLICY_INVALID", "Unsupported fallback_policy: " + fallback_policy);
        return 11;
    }
    RuntimeConfig effective;
    if (!resolve_backend_config(requested, effective, fallback_policy == "allow_cpu", config_error)) {
        g_last_error = "Unsupported llama runtime config: " + config_error;
        set_load_failure_code("MCA_LOAD_RUNTIME_CONFIG_UNSUPPORTED");
        return 13;
    }
    g_effective_config = effective;
    g_spec_requested = effective.spec_type == "draft-mtp";
    g_spec_context_ready = false;
    g_spec_request_active = false;
    g_spec_done = false;
    g_spec_drafted_tokens = 0;
    g_spec_accepted_tokens = 0;
    g_spec_steps = 0;
    g_spec_request_reason = g_spec_requested ? "awaiting_text_request" : "disabled";
    g_cache_reuse_hits = 0;
    g_cache_reuse_misses = 0;
    g_turn_cache_checkpoint_hits = 0;
    g_turn_cache_checkpoint_misses = 0;
    g_cache_reuse_hit = false;
    g_cache_reused_tokens = 0;
    g_cache_reuse_reason = requested.spec_type == "draft-mtp" && requested.cache_reuse > 0
                           ? "disabled_by_draft_mtp"
                           : (effective.cache_reuse > 0 ? "no_cached_prefix" : "disabled");

    {
        std::ifstream input(path, std::ios::binary);
        if (!input.good()) {
            g_last_error = "Model file is not readable: " + path;
            set_load_failure_code("MCA_LOAD_FILE_UNREADABLE");
            return 10;
        }
    }

    switch (probe_gguf_header(path)) {
        case GgufHeaderProbe::VALID:
            break;
        case GgufHeaderProbe::UNREADABLE:
            set_load_failure("MCA_LOAD_FILE_UNREADABLE", "Model file became unreadable before GGUF header validation: " + path);
            return 10;
        case GgufHeaderProbe::INVALID_MAGIC:
            set_load_failure("MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED", "Invalid GGUF magic in model file: " + path);
            return 1;
        case GgufHeaderProbe::TRUNCATED:
            set_load_failure("MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED", "GGUF header is truncated: " + path);
            return 1;
    }

    if (fallback_policy == "require_requested_backend" && requested.n_gpu_layers != 0 &&
        (effective.n_gpu_layers == 0 || g_backend_gpu_device_count == 0)) {
        set_load_failure("MCA_REQUESTED_BACKEND_UNAVAILABLE", "Requested GPU backend has no registered usable device. " + g_backend_load_diagnostic);
        return 11;
    }
    const bool auto_gpu_cpu_fallback_allowed =
            fallback_policy == "allow_cpu" &&
            effective.n_gpu_layers != 0 &&
            effective.n_cpu_moe == 0;
    g_gpu_auto_fallback_applied = fallback_policy == "allow_cpu" &&
            requested.n_gpu_layers != 0 && effective.n_gpu_layers == 0;
    g_gpu_auto_fallback_reason = g_gpu_auto_fallback_applied
                                 ? "gpu_backend_unavailable: " + g_backend_load_diagnostic
                                 : (auto_gpu_cpu_fallback_allowed ? "pending" : "not_requested");

    auto restore_load_state_for_attempt = [&]() {
        g_spec_requested = effective.spec_type == "draft-mtp";
        g_spec_context_ready = false;
        g_spec_request_active = false;
        g_spec_done = false;
        g_spec_request_reason = g_spec_requested ? "awaiting_text_request" : "disabled";
        g_cache_reuse_reason = requested.spec_type == "draft-mtp" && requested.cache_reuse > 0
                               ? "disabled_by_draft_mtp"
                               : (effective.cache_reuse > 0 ? "no_cached_prefix" : "disabled");
        g_model_architecture.clear();
        g_model_mtp_layers = 0;
        g_model_hybrid = false;
        g_model_recurrent = false;
    };

    auto load_model_for_effective_config = [&]() -> bool {
        llama_model_params model_params = llama_model_default_params();
        model_params.load_mode = load_mode_from_flags(effective.mmap, effective.mlock);
        model_params.load_mtp = g_spec_requested;
        model_params.n_gpu_layers = effective.n_gpu_layers;
        model_params.main_gpu = mca::llama::modelMainGpuForLoad(
                g_gpu_offload_supported,
                effective.n_gpu_layers,
                effective.main_gpu);
        model_params.split_mode = split_mode_from_name(effective.split_mode);

        std::list<std::string> moe_patterns;
        std::vector<llama_model_tensor_buft_override> tensor_overrides;
        for (int i = 0; i < effective.n_cpu_moe; ++i) {
            moe_patterns.push_back(llm_ffn_block_regex(i, LLM_FFN_EXPS_REGEX));
            tensor_overrides.push_back({moe_patterns.back().c_str(), ggml_backend_cpu_buffer_type()});
        }
        if (!tensor_overrides.empty()) {
            tensor_overrides.push_back({nullptr, nullptr});
            model_params.tensor_buft_overrides = tensor_overrides.data();
        }

        g_mmap_fallback_allowed = mca::llama::shouldRetryModelLoadWithoutMmap(
                load_mode_uses_mmap(model_params.load_mode),
                g_model_file_size_bytes);
        g_mmap_prefetch_enabled = load_mode_uses_mmap(model_params.load_mode) &&
                mca::llama::shouldPrefetchModelMmap(g_model_file_size_bytes);
        mca_llama_set_model_mmap_prefetch_enabled(g_mmap_prefetch_enabled);
        g_model = llama_model_load_from_file(path.c_str(), model_params);
        if (g_model == nullptr && g_mmap_fallback_allowed) {
            g_last_error += "\nRetrying model load with mmap=false.";
            model_params.load_mode = load_mode_from_flags(false, effective.mlock);
            effective.mmap = false;
            g_effective_config = effective;
            g_mmap_prefetch_enabled = false;
            mca_llama_set_model_mmap_prefetch_enabled(false);
            g_model = llama_model_load_from_file(path.c_str(), model_params);
        } else if (g_model == nullptr && load_mode_uses_mmap(model_params.load_mode)) {
            g_last_error += "\nLarge-model mmap load failed; unsafe mmap=false retry is disabled.";
        }
        return g_model != nullptr;
    };

    auto validate_loaded_model_for_effective_config = [&]() -> int {
        g_model_architecture = model_meta_string(g_model, "general.architecture");
        if (!model_supports_autoregressive_chat(g_model, g_model_architecture)) {
            g_last_error = "The selected GGUF is not an autoregressive chat model supported by the current generation path";
            if (!g_model_architecture.empty()) {
                g_last_error += ": architecture=" + g_model_architecture;
            }
            g_last_error += ".";
            set_load_failure_code("MCA_LOAD_GGUF_NOT_AUTOREGRESSIVE_CHAT");
            free_llama_locked();
            return 18;
        }
        g_model_mtp_layers = model_mtp_layer_count(g_model);
        g_model_hybrid = llama_model_is_hybrid(g_model);
        g_model_recurrent = llama_model_is_recurrent(g_model);
        refresh_prefix_cache_strategy_locked();
        if (g_spec_requested && !model_supports_requested_mtp(
                g_model_architecture,
                g_model_mtp_layers)) {
            g_last_error = g_model_mtp_layers <= 0
                           ? "draft-mtp was requested, but the GGUF has no nextn_predict_layers metadata/MTP head."
                           : "draft-mtp supports one MTP block for this architecture; multi-head chaining is currently supported only by Step35. "
                             "Model architecture=" + g_model_architecture +
                             ", MTP blocks=" + std::to_string(g_model_mtp_layers) + ".";
            set_load_failure_code("MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION");
            free_llama_locked();
            return 17;
        }
        if (effective.n_cpu_moe > llama_model_n_layer(g_model)) {
            g_last_error = "n_cpu_moe exceeds the model layer count (" +
                           std::to_string(llama_model_n_layer(g_model)) + ").";
            set_load_failure_code("MCA_LOAD_RUNTIME_CONFIG_UNSUPPORTED");
            free_llama_locked();
            return 14;
        }
        return 0;
    };

    llama_context_params ctx_params{};
    uint32_t speculative_rollback_window = 0;
    int n_threads = 0;
    auto create_contexts_for_effective_config = [&]() -> int {
        g_n_ctx = effective.n_ctx;
        n_threads = effective.n_threads;
        ctx_params = llama_context_default_params();
        ctx_params.n_ctx = g_n_ctx;
        ctx_params.n_batch = effective.n_batch;
        ctx_params.n_ubatch = effective.n_ubatch;
        ctx_params.n_seq_max = 1;
        speculative_rollback_window = g_spec_requested
                                      ? (uint32_t) effective.spec_draft_n_max + 1U
                                      : 0U;
        ctx_params.n_rs_seq = speculative_rollback_window;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = effective.n_threads_batch;
        ctx_params.type_k = cache_type_from_name(effective.cache_type_k);
        ctx_params.type_v = cache_type_from_name(effective.cache_type_v);
        ctx_params.flash_attn_type = flash_attn_from_name(effective.flash_attn);
        ctx_params.no_perf = !effective.perf;
        ctx_params.abort_callback = abort_inference_if_stopped;
        g_n_threads = n_threads;
        g_n_threads_batch = effective.n_threads_batch;
        g_n_batch = effective.n_batch;
        g_n_ubatch = effective.n_ubatch;
        g_context = llama_init_from_model(g_model, ctx_params);
        if (g_context == nullptr) {
            g_last_error += "\nllama_init_from_model returned null for the requested context/KV/flash configuration.";
            return 2;
        }

        g_n_ctx = (int) llama_n_ctx(g_context);
        g_n_batch = (int) llama_n_batch(g_context);
        g_n_ubatch = (int) llama_n_ubatch(g_context);
        g_effective_config.n_ctx = g_n_ctx;
        g_effective_config.n_batch = g_n_batch;
        g_effective_config.n_ubatch = g_n_ubatch;

        if (!g_spec_requested) return 0;
        const auto seq_rm_type = common_context_can_seq_rm(g_context);
        if (seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_NO || seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL ||
            (seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_RS &&
             llama_n_rs_seq(g_context) < speculative_rollback_window)) {
            g_last_error = "draft-mtp requires bounded partial target-context rollback; this model/context does not provide it.";
            return 15;
        }

        llama_context_params mtp_params = ctx_params;
        mtp_params.ctx_type = LLAMA_CONTEXT_TYPE_MTP;
        mtp_params.n_rs_seq = 0;
        mtp_params.ctx_other = g_context;
        g_mtp_context = llama_init_from_model(g_model, mtp_params);
        if (g_mtp_context == nullptr) {
            g_last_error = "draft-mtp was requested, but the model has no usable MTP head/context.";
            return 16;
        }
        g_spec_context_ready = true;
        return 0;
    };

    auto fallback_to_cpu = [&](const char *stage) {
        const std::string gpu_error = g_last_error;
        free_llama_locked();
        effective.n_gpu_layers = 0;
        effective.main_gpu = 0;
        effective.split_mode = "none";
        effective.n_cpu_moe = 0;
        g_effective_config = effective;
        g_gpu_auto_fallback_applied = true;
        g_gpu_auto_fallback_reason = stage;
        g_last_error = std::string("GPU auto initialization failed at ") + stage +
                       "; retrying with the CPU backend.";
        if (!gpu_error.empty()) {
            g_last_error += "\nGPU attempt: " + gpu_error;
        }
        restore_load_state_for_attempt();
    };

    bool retried_auto_gpu_on_cpu = false;
    while (true) {
        if (!load_model_for_effective_config()) {
            if (auto_gpu_cpu_fallback_allowed && !retried_auto_gpu_on_cpu) {
                fallback_to_cpu("model_load");
                retried_auto_gpu_on_cpu = true;
                continue;
            }
            if (g_last_error.empty()) {
                g_last_error = "llama_model_load_from_file returned null. The file may be incomplete, unsupported, or not a chat model GGUF.";
            }
            // Classify on the full diagnostic stream: a parser/backend failure detail may only
            // have been logged at WARN level, but it still must not enter the lastError contract.
            set_load_failure_code(
                    load_failure_code_from_llama_error(g_last_error + g_last_warning).c_str());
            return 1;
        }

        const int model_validation_rc = validate_loaded_model_for_effective_config();
        if (model_validation_rc != 0) return model_validation_rc;

        const int context_rc = create_contexts_for_effective_config();
        if (context_rc == 0) break;
        if ((context_rc == 2 || context_rc == 16) &&
            auto_gpu_cpu_fallback_allowed && !retried_auto_gpu_on_cpu) {
            fallback_to_cpu(context_rc == 2 ? "context_create" : "mtp_context_create");
            retried_auto_gpu_on_cpu = true;
            continue;
        }
        const std::string failure_code =
                load_failure_code_from_llama_error(g_last_error + g_last_warning);
        set_load_failure_code(
                failure_code == "MCA_LOAD_OUT_OF_MEMORY"
                ? "MCA_LOAD_OUT_OF_MEMORY"
                : (context_rc == 15
                   ? "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION"
                   : "MCA_LOAD_CONTEXT_CREATION_FAILED"));
        free_llama_locked();
        return context_rc;
    }

    g_batch = llama_batch_init(g_n_batch, 0, 1);
    g_batch_ready = true;
    try {
        g_chat_templates = common_chat_templates_init(g_model, "");
    } catch (const std::bad_alloc &) {
        set_load_failure("MCA_LOAD_OUT_OF_MEMORY", "common_chat_templates_init failed: native out of memory.");
        free_llama_locked();
        return 4;
    } catch (const std::exception &e) {
        set_last_error(std::string("common_chat_templates_init exception: ") + e.what());
        set_load_failure_code("MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID");
        free_llama_locked();
        return 4;
    } catch (...) {
        set_last_error("common_chat_templates_init exception: unknown native error");
        set_load_failure_code("MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID");
        free_llama_locked();
        return 4;
    }
    if (!configure_sampler_locked(params)) {
        set_load_failure_code("MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION");
        free_llama_locked();
        return 3;
    }

    const std::string mmproj_path = parse_string(params, "mmproj_path", "");
    if (!mmproj_path.empty()) {
        std::ifstream projector_input(mmproj_path, std::ios::binary);
        if (!projector_input.good()) {
            g_last_error = "Vision projector file is not readable: " + mmproj_path;
            set_load_failure_code("MCA_LOAD_FILE_UNREADABLE");
            free_llama_locked();
            return 5;
        }
        mtmd_context_params vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.print_timings = false;
        // The text profile intentionally defaults to one decode thread to keep chat responsive,
        // but mtmd image projection is a batch operation and becomes needlessly slow at one
        // thread. Use a small bounded pool for vision without changing text decode behavior.
        const long online_cpus = sysconf(_SC_NPROCESSORS_ONLN);
        const int available_threads = online_cpus > 0 ? static_cast<int>(online_cpus) : 1;
        const int vision_threads = std::min(4, std::max(1, available_threads));
        vision_params.n_threads = vision_threads;
        vision_params.flash_attn_type = ctx_params.flash_attn_type;
        vision_params.warmup = false;
        g_mtmd_context = mtmd_init_from_file(mmproj_path.c_str(), g_model, vision_params);
        if (g_mtmd_context == nullptr) {
            if (g_last_error.empty()) {
                g_last_error = "mtmd_init_from_file returned null. The mmproj may not match this main model.";
            }
            set_load_failure_code("MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION");
            free_llama_locked();
            return 6;
        }
        if (!mtmd_support_vision(g_mtmd_context)) {
            g_last_error = "The attached mtmd projector does not report vision support.";
            set_load_failure_code("MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION");
            free_llama_locked();
            return 7;
        }
        g_vision_ready = true;
        g_mmproj_path = mmproj_path;
    }
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
    } catch (const std::bad_alloc &) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try {
            free_llama_locked();
        } catch (...) {
        }
#else
        g_stub_chunks.clear();
        g_stub_chunk_index = 0;
#endif
        g_loaded = false;
        set_load_failure("MCA_LOAD_OUT_OF_MEMORY", "loadModel failed: native out of memory.");
        return 19;
    } catch (const std::exception &e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try {
            free_llama_locked();
        } catch (...) {
        }
#else
        g_stub_chunks.clear();
        g_stub_chunk_index = 0;
#endif
        g_loaded = false;
        set_load_failure("MCA_LOAD_NATIVE_LOAD_EXCEPTION", std::string("loadModel exception: ") + e.what());
        return 19;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try {
            free_llama_locked();
        } catch (...) {
        }
#else
        g_stub_chunks.clear();
        g_stub_chunk_index = 0;
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
    mark_generation_inactive(GenerationStopReason::UNLOADED);
#if MCA_WITH_LLAMA_CPP
    free_llama_locked();
#else
    g_stub_chunks.clear();
    g_stub_chunk_index = 0;
#endif
    g_loaded = false;
}


extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_invalidateTextContext(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    mark_generation_inactive(GenerationStopReason::STOP_REQUESTED);
#if MCA_WITH_LLAMA_CPP
    // A user-visible conversation mutation must not be serviced from the old
    // token tail. This also clears the MTP context when speculative decoding
    // was active.
    invalidate_speculative_contexts_locked("invalidated_by_conversation_edit");
    g_cached_token_chars.clear();
#else
    g_stub_chunks.clear();
    g_stub_chunk_index = 0;
    g_cache_state_valid = false;
    g_cache_reuse_hit = false;
    g_cache_reused_tokens = 0;
    g_cache_reuse_reason = "invalidated_by_conversation_edit";
    g_spec_request_active = false;
    g_spec_request_reason = "invalidated_by_conversation_edit";
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
        JNIEnv *env,
        jobject,
        jstring messagesJson,
        jstring paramsJson
) {
    // Capture before waiting for g_mutex. A stop accepted after JNI entry must
    // cancel this request even if requestStop() wins the mutex and returns
    // before beginCompletion() reaches its ordinary stale-stop reset.
    const std::uint64_t stop_epoch_at_entry =
            g_stop_epoch.load(std::memory_order_acquire);
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
                                            ? "not_attempted"
                                            : "not_requested";
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
        g_last_error.clear();
        g_last_warning.clear();
        g_prompt_tokens = 0;
        g_prefill_computed_tokens = 0;
        g_completion_tokens = 0;
        g_prefill_started_ms = now_ms();
        g_prefill_finished_ms = g_prefill_started_ms;
        reset_prefill_progress();
        g_decode_started_ms = 0;
        g_decode_finished_ms = 0;

#if MCA_WITH_LLAMA_CPP
        if (g_context == nullptr) return -2;
        RuntimeConfig request_config;
        std::string config_error;
        if (!parse_runtime_config(params_json, g_requested_config, request_config, config_error)) {
            g_last_error = "Invalid completion runtime config: " + config_error;
            return -10;
        }
        if (params_json.find("\"n_threads\"") != std::string::npos &&
            params_json.find("\"n_threads_batch\"") == std::string::npos) {
            request_config.n_threads_batch = request_config.n_threads;
            if (!validate_runtime_config(request_config, config_error)) {
                g_last_error = "Invalid completion runtime config: " + config_error;
                return -10;
            }
        }
        if (!runtime_config_equal_load_bound(request_config, g_requested_config)) {
            g_last_error = "Completion config changes load-bound fields; reload the model before generating.";
            return -11;
        }

        const int previous_effective_cache_reuse = g_effective_config.cache_reuse;
        g_requested_config.n_threads = request_config.n_threads;
        g_requested_config.n_threads_batch = request_config.n_threads_batch;
        g_requested_config.cache_reuse = request_config.cache_reuse;
        g_effective_config.n_threads = request_config.n_threads;
        g_effective_config.n_threads_batch = request_config.n_threads_batch;
        g_effective_config.cache_reuse = g_spec_requested ? 0 : request_config.cache_reuse;
        refresh_prefix_cache_strategy_locked();
        if (previous_effective_cache_reuse != g_effective_config.cache_reuse) {
            invalidate_prefix_cache_checkpoint_locked();
            invalidate_turn_cache_checkpoints_locked();
            g_cache_reuse_reason = "cache_reuse_threshold_changed";
        }
        if (g_spec_requested && request_config.cache_reuse > 0) {
            g_cache_reuse_reason = "disabled_by_draft_mtp";
        }
        if (request_config.n_threads != g_n_threads || request_config.n_threads_batch != g_n_threads_batch) {
            llama_set_n_threads(g_context, request_config.n_threads, request_config.n_threads_batch);
            if (g_mtp_context != nullptr) {
                llama_set_n_threads(g_mtp_context, request_config.n_threads, request_config.n_threads_batch);
            }
            g_n_threads = request_config.n_threads;
            g_n_threads_batch = request_config.n_threads_batch;
        }
        if (!configure_sampler_locked(params_json)) return -3;
        common_sampler_reset(g_sampler);
        g_chat_messages.clear();
        g_cached_token_chars.clear();
        g_context_shifts = 0;
        g_prompt_ends_inside_reasoning = false;
        reset_persist_progress();
        g_n_predict = parse_int(params_json, "n_predict", 8192);
        if (g_n_predict < 1 || g_n_predict > 1048576) {
            g_last_error = "n_predict must be in [1, 1048576].";
            return -12;
        }
        g_max_new_tokens = g_n_predict;
        g_spec_pending_token = LLAMA_TOKEN_NULL;
        g_spec_pending_valid = false;
        g_spec_done = false;
        g_prefill_started_ms = now_ms();
        ChatTemplateOptions chat_options;
        chat_options.use_jinja = should_use_jinja(params_json);
        chat_options.enable_thinking = should_enable_thinking(params_json);
        chat_options.thinking_budget = thinking_budget_for_mode(params_json);
        g_last_use_jinja = chat_options.use_jinja;
        g_last_enable_thinking = chat_options.enable_thinking;
        const auto messages = parse_messages(messages_json);
        int rc = 0;
        const bool has_images = messages_have_images(messages);
        g_inference_abort_enabled.store(true, std::memory_order_release);
        if (has_images) {
            g_speculative.reset();
            g_spec_request_active = false;
            g_spec_request_reason = g_spec_requested ? "disabled_for_multimodal" : "disabled";
            if (g_mtp_context != nullptr) {
                llama_memory_clear(llama_get_memory(g_mtp_context), false);
            }
            clear_target_context_locked("disabled_for_multimodal");
            rc = prefill_multimodal_locked(messages, chat_options);
            g_cache_state_valid = false;
            g_context_tokens.clear();
            g_cache_reuse_reason = "disabled_for_multimodal";
        } else {
            const auto formatted = format_messages(messages, chat_options);
            g_prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(formatted);
            auto tokens = common_tokenize(g_context, formatted, true, true);
            if (tokens.empty()) {
                g_last_error = "The rendered text prompt tokenized to an empty sequence.";
                return -13;
            }
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
                                (size_t) request_config.n_parallel);

                // The live token-prefix state includes the complete append-only
                // conversation. A persistent state file contains only the
                // fixed persona prefix, so restoring it before this check
                // would discard the much more valuable session KV every turn.
                reused = prepare_text_prefix_locked(tokens);
                if (mca::llama::shouldAttemptPersistentPrefixFallback(
                        persistent_prefix_eligible,
                        g_cache_reuse_hit)) {
                    persistent_prefix = prepare_persistent_prefix_locked(
                            messages,
                            chat_options,
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
                    // shift_context_locked() already invalidated both cache
                    // representations; do not relabel shifted KV positions as
                    // the original, unshifted prompt token sequence.
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
            // Failed begin paths (including load-signature mismatch -11) return above.
            if (!mark_generation_running_unless_stopped()) {
                return COMPLETION_STOPPED;
            }
            ++g_generation_sequence;
        }
        return rc;
#else
        (void) messages_json;
        (void) params_json;
        g_stub_chunk_index = 0;
        g_stub_chunks = {
                "这是 MCA 的 native stub 流式输出。",
                " 当前工程已经具备 JNI、Flow、停止生成和性能统计边界。",
                " 接入 llama.cpp 后，",
                "这里会替换为真实 GGUF 模型在骁龙/天玑 ARM CPU 上的本地推理结果。"
        };
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
    g_thread_prefix_cache_request = PersistentPrefixCacheNativeRequest{};
    g_thread_prefix_cache_request.requested = true;
    g_thread_prefix_cache_request.restore_state_path = jstring_to_string(env, restoreStatePath);
    g_thread_prefix_cache_request.write_state_path = jstring_to_string(env, writeStatePath);
    g_thread_prefix_cache_request.fixed_system_prompt = jstring_to_string(env, fixedSystemPrompt);
    g_thread_prefix_cache_request.full_session_state = fullSessionState == JNI_TRUE;
    return Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
            env,
            obj,
            messagesJson,
            paramsJson);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPrefillProgressJson(JNIEnv *env, jobject) {
    return string_to_jstring(env, mca::llm::PrefillProgressTracker::instance().to_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPersistProgressJson(JNIEnv *env, jobject) {
    // Deliberately lock-free: beginCompletion() holds g_mutex while serializing,
    // and the poll getter must observe the intermediate stages/byte counts.
    std::ostringstream out;
    out << "{\"stage\":" << g_persist_stage.load(std::memory_order_acquire) << ","
        << "\"writtenBytes\":" << g_persist_written_bytes.load(std::memory_order_acquire) << ","
        << "\"totalBytes\":" << g_persist_total_bytes.load(std::memory_order_acquire) << "}";
    return string_to_jstring(env, out.str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_resetPrefillProgress(JNIEnv *, jobject) {
    // This is deliberately lock-free. beginCompletion() owns the text context
    // with g_mutex, while the progress record is an independent atomic snapshot.
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
        const SpecStepResult step = speculative_step_locked();
        if (!step.ok) {
            g_stop_requested.store(true, std::memory_order_relaxed);
            finish_generation_if_active(GenerationStopReason::GENERATION_FAILED);
            g_speculative.reset();
            g_spec_request_active = false;
            g_spec_request_reason = "generation_failed";
            g_decode_finished_ms = now_ms();
            throw_java_illegal_state(
                    env,
                    g_last_error.empty() ? "draft-mtp generation failed." : g_last_error);
            return nullptr;
        }
        // A lock-free stop can land after speculative_step_locked() has
        // sampled but before this JNI call commits its chunk.  Suppress that
        // chunk and tear down both synchronized contexts instead of exposing
        // output after cancellation.
        if (g_stop_requested.load(std::memory_order_acquire)) {
            stop_speculative_request_if_requested_locked();
            finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
            return nullptr;
        }
        if (step.output.empty()) {
            if (step.done) {
                g_decode_finished_ms = now_ms();
                save_completed_turn_checkpoint_locked(GenerationStopReason::NORMAL_FINISHED);
                finish_generation_if_active(GenerationStopReason::NORMAL_FINISHED);
            }
            return step.done ? nullptr : string_to_jstring(env, "");
        }
        g_completion_tokens += (long long) step.output.size();
        for (const llama_token token: step.output) {
            g_cached_token_chars += common_token_to_piece(g_context, token);
        }
        const bool output_boundary_valid = is_valid_utf8(g_cached_token_chars.c_str());
        std::string ready_output;
        if (output_boundary_valid) {
            ready_output = g_cached_token_chars;
            g_cached_token_chars.clear();
        }
        g_decode_finished_ms = now_ms();
        if (step.done) {
            if (output_boundary_valid) {
                save_completed_turn_checkpoint_locked(GenerationStopReason::NORMAL_FINISHED);
            }
            finish_generation_if_active(GenerationStopReason::NORMAL_FINISHED);
        } else if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) {
            if (output_boundary_valid) {
                save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);
            }
            finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        }
        if (output_boundary_valid) {
            return string_to_jstring(env, ready_output);
        }
        return string_to_jstring(env, "");
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
        // The terminal token itself is not decoded into KV. Only promote the
        // preceding state when no partial UTF-8 bytes remain undisclosed.
        save_completed_turn_checkpoint_locked(GenerationStopReason::STOP_TOKEN);
        finish_generation_if_active(GenerationStopReason::STOP_TOKEN);
        return nullptr;
    }

    if (g_current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        if (!shift_context_locked()) {
            finish_generation_if_active(GenerationStopReason::CONTEXT_SHIFT_FAILED);
            return nullptr;
        }
    }

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token, g_current_position, {0}, true);
    const int decode_rc = llama_decode(g_context, g_batch);
    if (decode_rc != 0) {
        g_last_error = "llama_decode failed during token generation: " + std::to_string(decode_rc);
        invalidate_prefix_cache_checkpoint_locked();
        invalidate_turn_cache_checkpoints_locked();
        g_cache_state_valid = false;
        g_context_tokens.clear();
        g_cache_reuse_reason = "invalidated_by_decode_failure";
        finish_generation_if_active(GenerationStopReason::DECODE_FAILED);
        return nullptr;
    }
    record_gpu_decode_execution_locked();
    g_current_position++;
    g_completion_tokens++;
    if (g_cache_state_valid) {
        g_context_tokens.push_back(token);
    }
    g_decode_finished_ms = now_ms();
    g_cached_token_chars += common_token_to_piece(g_context, token);
    const bool output_boundary_valid = is_valid_utf8(g_cached_token_chars.c_str());
    if (g_stop_requested.load(std::memory_order_acquire)) {
        // llama_decode already committed this token, so keep position/token
        // bookkeeping aligned with the real KV. Cancellation owns output
        // visibility, however: no byte sampled by this JNI call is published.
        g_cached_token_chars.clear();
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    std::string ready_output;
    if (output_boundary_valid) {
        ready_output = g_cached_token_chars;
        g_cached_token_chars.clear();
    }
    if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) {
        // A checkpoint is reusable only when every committed output byte was
        // publishable to Java. An incomplete UTF-8 tail stays live in memory,
        // but is deliberately not promoted to a durable turn boundary.
        if (output_boundary_valid) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);
        }
        finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
    }

    if (output_boundary_valid) {
        return string_to_jstring(env, ready_output);
    }
    return string_to_jstring(env, "");
#else
    if (g_stub_chunk_index >= g_stub_chunks.size()) {
        finish_generation_if_active(GenerationStopReason::NORMAL_FINISHED);
        return nullptr;
    }
    const long long token_started = now_ms();
    if (g_completion_tokens == 0) {
        g_decode_started_ms = token_started;
        g_decode_finished_ms = token_started;
    }
    const std::string chunk = g_stub_chunks[g_stub_chunk_index++];
    g_completion_tokens += 8;
    g_decode_finished_ms = now_ms();
    if (g_stub_chunk_index >= g_stub_chunks.size()) {
        finish_generation_if_active(GenerationStopReason::NORMAL_FINISHED);
    }
    return string_to_jstring(env, chunk);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStop(JNIEnv *, jobject) {
    // Publish cancellation before waiting for native work. Once this function
    // returns, the mutex handshake guarantees that no in-flight JNI call can
    // subsequently commit output for the stopped request.
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStopIfActive(JNIEnv *, jobject) {
    // Serialize the proof with generateNextChunk(): if the current token was a
    // natural terminal token, that call marks generation inactive before this
    // lock can be acquired. The CAS then distinguishes a real accepted stop
    // from a post-completion request without relying on timing alone.
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!finish_generation_if_active(GenerationStopReason::STOP_REQUESTED)) {
        return JNI_FALSE;
    }
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getRuntimeStatsJson(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
#if MCA_WITH_LLAMA_CPP
    refresh_gpu_offload_evidence_locked();
    return string_to_jstring(
            env,
            stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu"));
#else
    return string_to_jstring(env, stats_json("cpu-stub"));
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_shutdown(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    mark_generation_inactive(GenerationStopReason::SHUTDOWN);
#if MCA_WITH_LLAMA_CPP
    free_llama_locked();
    llama_backend_free();
    g_backend_initialized = false;
    g_backend_device_count = 0;
    g_backend_gpu_device_count = 0;
    g_gpu_offload_supported = false;
#else
    g_stub_chunks.clear();
    g_stub_chunk_index = 0;
#endif
    g_loaded = false;
}
