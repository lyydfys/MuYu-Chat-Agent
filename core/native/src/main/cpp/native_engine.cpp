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

#if MCA_WITH_LLAMA_CPP
#include <unistd.h>
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
// These snapshots are intentionally atomic: beginCompletion() holds g_mutex
// for the entire native prefill, while the UI polls this small progress record
// from another JVM thread. Do not use them to infer cache state or timing.
std::atomic<int> g_prefill_progress_completed_tokens{0};
std::atomic<int> g_prefill_progress_total_tokens{0};

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
std::string g_load_failure_code;
std::string g_native_lib_dir;
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

void reset_prefill_progress() {
    g_prefill_progress_completed_tokens.store(0, std::memory_order_release);
    g_prefill_progress_total_tokens.store(0, std::memory_order_release);
}

void begin_prefill_progress(size_t total_tokens, size_t completed_tokens = 0) {
    const int total = total_tokens > (size_t) std::numeric_limits<int>::max()
                      ? std::numeric_limits<int>::max()
                      : (int) total_tokens;
    const int completed = std::min(
            total,
            completed_tokens > (size_t) std::numeric_limits<int>::max()
            ? std::numeric_limits<int>::max()
            : (int) completed_tokens);
    g_prefill_progress_total_tokens.store(total, std::memory_order_release);
    g_prefill_progress_completed_tokens.store(completed, std::memory_order_release);
}

void advance_prefill_progress(size_t decoded_tokens) {
    const int total = g_prefill_progress_total_tokens.load(std::memory_order_acquire);
    if (total <= 0 || decoded_tokens == 0) return;
    const int increment = decoded_tokens > (size_t) std::numeric_limits<int>::max()
                          ? std::numeric_limits<int>::max()
                          : (int) decoded_tokens;
    int observed = g_prefill_progress_completed_tokens.load(std::memory_order_acquire);
    while (observed < total) {
        const int next = std::min(total, observed > total - increment ? total : observed + increment);
        if (g_prefill_progress_completed_tokens.compare_exchange_weak(
                observed,
                next,
                std::memory_order_release,
                std::memory_order_acquire)) {
            return;
        }
    }
}

void report_reused_prefill_tokens(size_t reused_tokens) {
    const int total = g_prefill_progress_total_tokens.load(std::memory_order_acquire);
    if (total <= 0) return;
    const int reused = reused_tokens > (size_t) std::numeric_limits<int>::max()
                       ? std::numeric_limits<int>::max()
                       : (int) reused_tokens;
    int observed = g_prefill_progress_completed_tokens.load(std::memory_order_acquire);
    const int target = std::min(total, reused);
    while (observed < target) {
        if (g_prefill_progress_completed_tokens.compare_exchange_weak(
                observed,
                target,
                std::memory_order_release,
                std::memory_order_acquire)) {
            return;
        }
    }
}

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

RuntimeConfig g_requested_config;
RuntimeConfig g_effective_config;
size_t g_backend_gpu_device_count = 0;
bool g_gpu_offload_supported = false;
// Configuration is only a request.  A loaded GPU backend is not enough to
// claim that this request ran on it: the label is published only after both
// allocation evidence and a successful llama_decode on this context.
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

struct PersistentPrefixCacheNativeRequest {
    bool requested = false;
    std::string restore_state_path;
    std::string write_state_path;
    std::string fixed_system_prompt;
    bool full_session_state = false;
};

std::string g_pending_full_session_write_path;

thread_local PersistentPrefixCacheNativeRequest g_thread_prefix_cache_request;

void set_last_error(const std::string &message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, "MCA", "%s", message.c_str());
}

void clear_load_failure() {
    g_last_error.clear();
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

int parse_int(const std::string &json, const std::string &key, int fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    std::string number;
    while (pos < json.size() && (std::isdigit((unsigned char) json[pos]) || json[pos] == '-')) {
        number.push_back(json[pos++]);
    }
    if (number.empty()) return fallback;
    try { return std::stoi(number); } catch (...) { return fallback; }
}

float parse_float(const std::string &json, const std::string &key, float fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    std::string number;
    while (pos < json.size() &&
           (std::isdigit((unsigned char) json[pos]) || json[pos] == '-' || json[pos] == '.')) {
        number.push_back(json[pos++]);
    }
    if (number.empty()) return fallback;
    try { return std::stof(number); } catch (...) { return fallback; }
}

float parse_float_any(const std::string &json, const std::vector<std::string> &keys, float fallback) {
    for (const auto &key: keys) {
        if (json.find("\"" + key + "\"") == std::string::npos) continue;
        return parse_float(json, key, fallback);
    }
    return fallback;
}

int parse_int_any(const std::string &json, const std::vector<std::string> &keys, int fallback) {
    for (const auto &key: keys) {
        if (json.find("\"" + key + "\"") == std::string::npos) continue;
        return parse_int(json, key, fallback);
    }
    return fallback;
}

bool parse_bool(const std::string &json, const std::string &key, bool fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    if (json.compare(pos, 4, "true") == 0) return true;
    if (json.compare(pos, 5, "false") == 0) return false;
    return fallback;
}

std::string parse_json_string_after(const std::string &json, const std::string &key, size_t start) {
    const auto key_pos = json.find("\"" + key + "\"", start);
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    auto pos = json.find('"', colon + 1);
    if (pos == std::string::npos) return "";
    pos++;
    std::string out;
    while (pos < json.size()) {
        const char c = json[pos++];
        if (c == '"') break;
        if (c == '\\' && pos < json.size()) {
            const char escaped = json[pos++];
            switch (escaped) {
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                default: out.push_back(escaped); break;
            }
        } else {
            out.push_back(c);
        }
    }
    return out;
}

std::string parse_string(const std::string &json, const std::string &key, const std::string &fallback) {
    const auto value = parse_json_string_after(json, key, 0);
    return value.empty() ? fallback : value;
}

struct ParsedMessage {
    std::string role;
    std::string content;
    std::vector<std::string> image_paths;
};

bool starts_with(const std::string &value, const std::string &prefix) {
    return value.rfind(prefix, 0) == 0;
}

int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string url_decode(const std::string &value) {
    std::string out;
    out.reserve(value.size());
    for (size_t i = 0; i < value.size(); i++) {
        if (value[i] == '%' && i + 2 < value.size()) {
            const int high = hex_value(value[i + 1]);
            const int low = hex_value(value[i + 2]);
            if (high >= 0 && low >= 0) {
                out.push_back((char) ((high << 4) | low));
                i += 2;
                continue;
            }
        }
        out.push_back(value[i]);
    }
    return out;
}

std::string local_image_path_from_url(const std::string &url) {
    if (starts_with(url, "file://")) {
        return url_decode(url.substr(7));
    }
    return url;
}

size_t find_object_end(const std::string &json, size_t start) {
    int depth = 0;
    bool in_string = false;
    bool escaped = false;
    for (size_t i = start; i < json.size(); i++) {
        const char c = json[i];
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
        } else if (c == '{') {
            depth++;
        } else if (c == '}') {
            depth--;
            if (depth == 0) return i + 1;
        }
    }
    return json.size();
}

size_t value_start_after_key(const std::string &json, const std::string &key, size_t start) {
    const auto key_pos = json.find("\"" + key + "\"", start);
    if (key_pos == std::string::npos) return std::string::npos;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return std::string::npos;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    return pos;
}

void parse_content_parts(const std::string &segment, ParsedMessage &message) {
    size_t pos = 0;
    while (true) {
        const auto type_pos = segment.find("\"type\"", pos);
        if (type_pos == std::string::npos) break;
        const auto part_start = segment.rfind('{', type_pos);
        if (part_start == std::string::npos) break;
        const auto part_end = find_object_end(segment, part_start);
        const auto part = segment.substr(part_start, part_end - part_start);
        const auto type = parse_json_string_after(part, "type", 0);
        if (type == "text") {
            const auto text = parse_json_string_after(part, "text", 0);
            if (!text.empty()) {
                if (!message.content.empty()) message.content += "\n";
                message.content += text;
            }
        } else if (type == "image_url") {
            const auto url = parse_json_string_after(part, "url", 0);
            if (!url.empty() && !starts_with(url, "data:")) {
                message.image_paths.push_back(local_image_path_from_url(url));
            }
        }
        pos = part_end;
    }
}

std::vector<ParsedMessage> parse_messages(const std::string &messages_json) {
    std::vector<ParsedMessage> messages;
    size_t pos = 0;
    while (true) {
        const auto role_pos = messages_json.find("\"role\"", pos);
        if (role_pos == std::string::npos) break;
        const auto object_start = messages_json.rfind('{', role_pos);
        const auto object_end = find_object_end(messages_json, object_start == std::string::npos ? role_pos : object_start);
        const auto segment = messages_json.substr(role_pos, object_end - role_pos);
        const auto role = parse_json_string_after(messages_json, "role", role_pos);
        ParsedMessage message{role.empty() ? "user" : role, ""};
        const auto content_start = value_start_after_key(segment, "content", 0);
        if (content_start != std::string::npos) {
            if (content_start < segment.size() && segment[content_start] == '"') {
                message.content = parse_json_string_after(segment, "content", 0);
            } else if (content_start < segment.size() && segment[content_start] == '[') {
                parse_content_parts(segment.substr(content_start), message);
            }
        }
        if (!message.content.empty() || !message.image_paths.empty()) {
            messages.push_back(message);
        }
        pos = object_end;
    }
    if (messages.empty()) messages.push_back({"user", messages_json});
    return messages;
}

bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const auto *bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

std::string json_escape(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c: value) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

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
    std::ostringstream out;
    out << "{"
        << "\"n_ctx\":" << config.n_ctx << ","
        << "\"n_threads\":" << config.n_threads << ","
        << "\"n_threads_batch\":" << config.n_threads_batch << ","
        << "\"n_batch\":" << config.n_batch << ","
        << "\"n_ubatch\":" << config.n_ubatch << ","
        << "\"n_gpu_layers\":" << config.n_gpu_layers << ","
        << "\"main_gpu\":" << config.main_gpu << ","
        << "\"split_mode\":\"" << json_escape(config.split_mode) << "\","
        << "\"n_cpu_moe\":" << config.n_cpu_moe << ","
        << "\"cache_type_k\":\"" << json_escape(config.cache_type_k) << "\","
        << "\"cache_type_v\":\"" << json_escape(config.cache_type_v) << "\","
        << "\"flash_attn\":\"" << json_escape(config.flash_attn) << "\","
        << "\"perf\":" << (config.perf ? "true" : "false") << ","
        << "\"n_parallel\":" << config.n_parallel << ","
        << "\"cache_reuse\":" << config.cache_reuse << ","
        << "\"spec_type\":\"" << json_escape(config.spec_type) << "\","
        << "\"spec_draft_n_max\":" << config.spec_draft_n_max << ","
        << "\"mmap\":" << (config.mmap ? "true" : "false") << ","
        << "\"mlock\":" << (config.mlock ? "true" : "false")
        << "}";
    return out.str();
}

std::string backend_devices_json_array() {
#if MCA_WITH_LLAMA_CPP
    std::ostringstream out;
    out << "[";
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; i++) {
        if (i > 0) out << ",";
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        out << "{"
            << "\"name\":\"" << json_escape(ggml_backend_dev_name(dev) == nullptr ? "" : ggml_backend_dev_name(dev)) << "\","
            << "\"description\":\"" << json_escape(ggml_backend_dev_description(dev) == nullptr ? "" : ggml_backend_dev_description(dev)) << "\","
            << "\"type\":" << (int) ggml_backend_dev_type(dev)
            << "}";
    }
    out << "]";
    return out.str();
#else
    return "[]";
#endif
}

std::string stats_json(const char *backend) {
    const long long prefill_ms = g_prefill_finished_ms > g_prefill_started_ms
                                  ? g_prefill_finished_ms - g_prefill_started_ms : 0;
    const long long decode_ms = g_decode_finished_ms > g_decode_started_ms
                                ? g_decode_finished_ms - g_decode_started_ms : 0;
    const double tps = decode_ms > 0 ? (double) g_completion_tokens * 1000.0 / (double) decode_ms : 0.0;
    const double prefill_tps = prefill_ms > 0
                               ? (double) g_prefill_computed_tokens * 1000.0 / (double) prefill_ms : 0.0;
    const double effective_prompt_tps = prefill_ms > 0
                               ? (double) g_prompt_tokens * 1000.0 / (double) prefill_ms : 0.0;
    std::ostringstream out;
    out << "{"
        << "\"backend\":\"" << backend << "\","
        << "\"loaded\":" << (g_loaded ? "true" : "false") << ","
        << "\"modelPath\":\"" << json_escape(g_model_path) << "\","
        << "\"modelFileSizeBytes\":" << g_model_file_size_bytes << ","
        << "\"mmapFallbackAllowed\":" << (g_mmap_fallback_allowed ? "true" : "false") << ","
        << "\"mmapPrefetchEnabled\":" << (g_mmap_prefetch_enabled ? "true" : "false") << ","
        << "\"mmap\":" << (g_effective_config.mmap ? "true" : "false") << ","
        << "\"mlock\":" << (g_effective_config.mlock ? "true" : "false") << ","
        << "\"loadMs\":" << g_load_ms << ","
        << "\"promptTokens\":" << g_prompt_tokens << ","
        << "\"prefillTokens\":" << g_prefill_computed_tokens << ","
        << "\"completionTokens\":" << g_completion_tokens << ","
        << "\"generationSequence\":" << g_generation_sequence << ","
        << "\"generationActive\":"
        << (g_generation_active.load(std::memory_order_acquire) ? "true" : "false") << ","
        << "\"stopRequested\":"
        << (g_stop_requested.load(std::memory_order_acquire) ? "true" : "false") << ","
        << "\"generationStopReason\":\""
        << generation_stop_reason_name(g_generation_stop_reason.load(std::memory_order_acquire))
        << "\","
        << "\"contextShifts\":" << g_context_shifts << ","
        << "\"prefillMs\":" << prefill_ms << ","
        << "\"prefillTps\":" << prefill_tps << ","
        << "\"effectivePromptTps\":" << effective_prompt_tps << ","
        << "\"decodeMs\":" << decode_ms << ","
        << "\"decodeTps\":" << tps << ","
        << "\"nThreads\":" << g_n_threads << ","
        << "\"nThreadsBatch\":" << g_n_threads_batch << ","
        << "\"nBatch\":" << g_n_batch << ","
        << "\"nUbatch\":" << g_n_ubatch << ","
        << "\"nCtx\":" << g_effective_config.n_ctx << ","
        << "\"maxAllTokens\":" << g_effective_config.n_ctx << ","
        << "\"maxNewTokens\":" << g_max_new_tokens << ","
        << "\"nGpuLayers\":" << g_effective_config.n_gpu_layers << ","
        << "\"gpuOffloadActive\":" << (g_gpu_offload_active ? "true" : "false") << ","
        << "\"gpuOffloadAllocationObserved\":"
        << (g_gpu_offload_allocation_observed ? "true" : "false") << ","
        << "\"gpuOffloadExecutionObserved\":"
        << (g_gpu_offload_execution_observed ? "true" : "false") << ","
        << "\"gpuOffloadBytes\":" << g_gpu_offload_bytes << ","
        << "\"gpuOffloadModelBytes\":" << g_gpu_offload_model_bytes << ","
        << "\"gpuOffloadContextBytes\":" << g_gpu_offload_context_bytes << ","
        << "\"gpuOffloadComputeBytes\":" << g_gpu_offload_compute_bytes << ","
        << "\"gpuOffloadLayers\":" << g_gpu_offload_layers << ","
        << "\"gpuOffloadLayersKnown\":"
        << (g_gpu_offload_layers_known ? "true" : "false") << ","
        << "\"gpuAutoFallbackApplied\":"
        << (g_gpu_auto_fallback_applied ? "true" : "false") << ","
        << "\"gpuAutoFallbackReason\":\""
        << json_escape(g_gpu_auto_fallback_reason) << "\","
        << "\"mainGpu\":" << g_effective_config.main_gpu << ","
        << "\"splitMode\":\"" << json_escape(g_effective_config.split_mode) << "\","
        << "\"nCpuMoe\":" << g_effective_config.n_cpu_moe << ","
        << "\"cacheTypeK\":\"" << json_escape(g_effective_config.cache_type_k) << "\","
        << "\"cacheTypeV\":\"" << json_escape(g_effective_config.cache_type_v) << "\","
        << "\"flashAttn\":\"" << json_escape(g_effective_config.flash_attn) << "\","
        << "\"perf\":" << (g_effective_config.perf ? "true" : "false") << ","
        << "\"nParallel\":" << g_effective_config.n_parallel << ","
        << "\"cacheReuseThreshold\":" << g_effective_config.cache_reuse << ","
        << "\"specType\":\"" << json_escape(g_effective_config.spec_type) << "\","
        << "\"specDraftNMax\":" << g_effective_config.spec_draft_n_max << ","
        << "\"temperature\":" << g_sampling_temp << ","
        << "\"topK\":" << g_sampling_top_k << ","
        << "\"topP\":" << g_sampling_top_p << ","
        << "\"minP\":" << g_sampling_min_p << ","
        << "\"repeatPenalty\":" << g_sampling_repeat_penalty << ","
        << "\"presencePenalty\":" << g_sampling_presence_penalty << ","
        << "\"frequencyPenalty\":" << g_sampling_frequency_penalty << ","
        << "\"repeatLastN\":" << g_sampling_repeat_last_n << ","
        << "\"useJinja\":" << (g_last_use_jinja ? "true" : "false") << ","
        << "\"enableThinking\":" << (g_last_enable_thinking ? "true" : "false") << ","
        << "\"promptEndsInsideReasoning\":"
        << (g_prompt_ends_inside_reasoning ? "true" : "false") << ","
        << "\"visionReady\":" << (g_vision_ready ? "true" : "false") << ","
        << "\"mmprojPath\":\"" << json_escape(g_mmproj_path) << "\","
        << "\"backendReady\":" << (g_backend_device_count > 0 ? "true" : "false") << ","
        << "\"backendDeviceCount\":" << g_backend_device_count << ","
        << "\"backendDevices\":" << backend_devices_json_array() << ","
        << "\"requestedConfig\":" << runtime_config_json(g_requested_config) << ","
        << "\"effectiveConfig\":" << runtime_config_json(g_effective_config) << ","
        << "\"backendCapabilities\":{"
        << "\"gpuOffloadSupported\":" << (g_gpu_offload_supported ? "true" : "false") << ","
        << "\"gpuDeviceCount\":" << g_backend_gpu_device_count << ","
        << "\"gpuOffloadActive\":" << (g_gpu_offload_active ? "true" : "false") << ","
        << "\"gpuOffloadAllocationObserved\":"
        << (g_gpu_offload_allocation_observed ? "true" : "false") << ","
        << "\"gpuOffloadExecutionObserved\":"
        << (g_gpu_offload_execution_observed ? "true" : "false") << ","
        << "\"gpuOffloadBytes\":" << g_gpu_offload_bytes << ","
        << "\"gpuOffloadLayers\":" << g_gpu_offload_layers << ","
        << "\"gpuOffloadLayersKnown\":"
        << (g_gpu_offload_layers_known ? "true" : "false") << ","
        << "\"modelArchitecture\":\"" << json_escape(g_model_architecture) << "\","
        << "\"modelMtpLayers\":" << g_model_mtp_layers << ","
        << "\"dynamicBackendLoading\":" << (LLAMA_RUNTIME_FEATURES_COMPILED ? "true" : "false") << ","
        << "\"kvCacheQuantization\":" << (LLAMA_RUNTIME_FEATURES_COMPILED ? "true" : "false") << ","
        << "\"draftMtpCompiled\":" << (LLAMA_RUNTIME_FEATURES_COMPILED ? "true" : "false")
        << "},"
        << "\"cacheReuse\":{"
        << "\"valid\":" << (g_cache_state_valid ? "true" : "false") << ","
        << "\"hit\":" << (g_cache_reuse_hit ? "true" : "false") << ","
        << "\"reusedTokens\":" << g_cache_reused_tokens << ","
        << "\"hits\":" << g_cache_reuse_hits << ","
        << "\"misses\":" << g_cache_reuse_misses << ","
        << "\"reason\":\"" << json_escape(g_cache_reuse_reason) << "\","
        << "\"strategy\":\"" << json_escape(g_cache_reuse_strategy) << "\","
        << "\"checkpointValid\":" << (g_cache_checkpoint_valid ? "true" : "false") << ","
        << "\"checkpointTokens\":" << g_cache_checkpoint_tokens << ","
        << "\"checkpointBytes\":" << g_cache_checkpoint_bytes << ","
        << "\"turnCheckpoints\":" << g_turn_cache_checkpoint_count << ","
        << "\"turnCheckpointBytes\":" << g_turn_cache_checkpoint_bytes << ","
        << "\"turnCheckpointHits\":" << g_turn_cache_checkpoint_hits << ","
        << "\"turnCheckpointMisses\":" << g_turn_cache_checkpoint_misses << ","
        << "\"modelHybrid\":" << (g_model_hybrid ? "true" : "false") << ","
        << "\"modelRecurrent\":" << (g_model_recurrent ? "true" : "false")
        << "},"
        << "\"persistentPrefixCache\":{"
        << "\"attempted\":" << (g_persistent_prefix_cache_attempted ? "true" : "false") << ","
        << "\"hit\":" << (g_persistent_prefix_cache_hit ? "true" : "false") << ","
        << "\"saved\":" << (g_persistent_prefix_cache_saved ? "true" : "false") << ","
        << "\"tokens\":" << g_persistent_prefix_cache_tokens << ","
        << "\"reason\":\"" << json_escape(g_persistent_prefix_cache_reason) << "\""
        << "},"
        << "\"speculative\":{"
        << "\"requested\":" << (g_spec_requested ? "true" : "false") << ","
        << "\"contextReady\":" << (g_spec_context_ready ? "true" : "false") << ","
        << "\"activeForRequest\":" << (g_spec_request_active ? "true" : "false") << ","
        << "\"draftedTokens\":" << g_spec_drafted_tokens << ","
        << "\"acceptedTokens\":" << g_spec_accepted_tokens << ","
        << "\"steps\":" << g_spec_steps << ","
        << "\"acceptanceRate\":" << (g_spec_drafted_tokens > 0 ? (double) g_spec_accepted_tokens / (double) g_spec_drafted_tokens : 0.0) << ","
        << "\"reason\":\"" << json_escape(g_spec_request_reason) << "\""
        << "},"
        << "\"nativeLibDir\":\"" << json_escape(g_native_lib_dir) << "\","
        << "\"loadFailureCode\":\"" << json_escape(g_load_failure_code) << "\","
        << "\"lastError\":\"" << json_escape(g_last_error) << "\""
        << "}";
    return out.str();
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
struct TurnCacheCheckpoint {
    llama_tokens prefix;
    std::vector<uint8_t> data;
    size_t position = 0;
};
// Partial-only snapshots contain recurrent state (not the full attention KV),
// so keeping a small rolling window is inexpensive and lets a tail-pruned
// conversation restore the exact prior turn boundary.
std::vector<TurnCacheCheckpoint> g_turn_cache_checkpoints;
constexpr size_t MAX_TURN_CACHE_CHECKPOINTS = 4;
constexpr size_t MAX_TURN_CACHE_CHECKPOINT_BYTES = 96U * 1024U * 1024U;

size_t turn_cache_checkpoint_bytes(const TurnCacheCheckpoint &checkpoint) {
    constexpr size_t token_bytes = sizeof(llama_tokens::value_type);
    if (checkpoint.prefix.size() >
            (std::numeric_limits<size_t>::max() - checkpoint.data.size()) / token_bytes) {
        return std::numeric_limits<size_t>::max();
    }
    return checkpoint.data.size() + checkpoint.prefix.size() * token_bytes;
}

size_t turn_cache_checkpoint_bytes(size_t data_bytes, size_t prefix_tokens) {
    constexpr size_t token_bytes = sizeof(llama_tokens::value_type);
    if (prefix_tokens >
            (std::numeric_limits<size_t>::max() - data_bytes) / token_bytes) {
        return std::numeric_limits<size_t>::max();
    }
    return data_bytes + prefix_tokens * token_bytes;
}
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
}

void reconcile_gpu_offload_evidence_locked() {
    g_gpu_offload_active = g_gpu_offload_allocation_observed &&
                           g_gpu_offload_execution_observed;
}

void refresh_gpu_offload_evidence_locked() {
    // Keep execution evidence until this model/context is released. Runtime
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
    refresh_gpu_offload_evidence_locked();
    if (g_gpu_offload_allocation_observed) {
        g_gpu_offload_execution_observed = true;
    }
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
    g_cache_checkpoint_prefix.clear();
    g_cache_checkpoint_data.clear();
    g_cache_checkpoint_threshold = 0;
    g_cache_checkpoint_valid = false;
    g_cache_checkpoint_tokens = 0;
    g_cache_checkpoint_bytes = 0;
}

void invalidate_turn_cache_checkpoints_locked() {
    g_turn_cache_checkpoints.clear();
    g_turn_cache_checkpoint_count = 0;
    g_turn_cache_checkpoint_bytes = 0;
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
    invalidate_prefix_cache_checkpoint_locked();
    if (g_context == nullptr || checkpoint_tokens == 0 ||
        checkpoint_tokens > tokens.size() ||
        g_current_position != (llama_pos) checkpoint_tokens) {
        return false;
    }

    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t checkpoint_size = llama_state_seq_get_size_ext(g_context, 0, flags);
    if (checkpoint_size == 0) {
        return false;
    }

    try {
        std::vector<uint8_t> checkpoint_data(checkpoint_size);
        const size_t written = llama_state_seq_get_data_ext(
                g_context,
                checkpoint_data.data(),
                checkpoint_data.size(),
                0,
                flags);
        if (written != checkpoint_data.size()) {
            return false;
        }

        llama_tokens checkpoint_prefix(
                tokens.begin(),
                tokens.begin() + (llama_tokens::difference_type) checkpoint_tokens);
        g_cache_checkpoint_data = std::move(checkpoint_data);
        g_cache_checkpoint_prefix = std::move(checkpoint_prefix);
        g_cache_checkpoint_threshold = checkpoint_tokens;
        g_cache_checkpoint_valid = true;
        g_cache_checkpoint_tokens = checkpoint_tokens;
        g_cache_checkpoint_bytes = checkpoint_size;
        return true;
    } catch (const std::exception &) {
        return false;
    }
}

bool restore_partial_prefix_checkpoint_locked() {
    if (g_context == nullptr || !g_cache_checkpoint_valid ||
        g_cache_checkpoint_data.empty()) {
        return false;
    }
    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t restored = llama_state_seq_set_data_ext(
            g_context,
            g_cache_checkpoint_data.data(),
            g_cache_checkpoint_data.size(),
            0,
            flags);
    return restored == g_cache_checkpoint_data.size();
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

bool load_mode_uses_mmap(llama_load_mode mode) {
    // llama.cpp b10590 defaults to AUTO. AUTO starts with mmap and only
    // disables it when a selected backend cannot map the model, so preserve
    // the MCA default mmap policy when deriving the runtime profile.
    return mode == LLAMA_LOAD_MODE_AUTO ||
           mode == LLAMA_LOAD_MODE_MMAP ||
           mode == LLAMA_LOAD_MODE_MMAP_MLOCK;
}

bool load_mode_uses_mlock(llama_load_mode mode) {
    return mode == LLAMA_LOAD_MODE_MLOCK || mode == LLAMA_LOAD_MODE_MMAP_MLOCK;
}

llama_load_mode load_mode_from_flags(bool use_mmap, bool use_mlock) {
    if (use_mmap) {
        return use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    }
    return use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
}

RuntimeConfig default_runtime_config() {
    RuntimeConfig config;
    const int threads_default = std::max(1, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2);
    config.n_threads = threads_default;
    config.n_threads_batch = threads_default;
    const auto model_defaults = llama_model_default_params();
    config.n_gpu_layers = model_defaults.n_gpu_layers;
    config.main_gpu = model_defaults.main_gpu;
    config.split_mode = "layer";
    config.mmap = load_mode_uses_mmap(model_defaults.load_mode);
    config.mlock = load_mode_uses_mlock(model_defaults.load_mode);
    return config;
}

bool validate_runtime_config(const RuntimeConfig &config, std::string &error) {
    auto fail = [&](const std::string &message) {
        error = message;
        return false;
    };
    if (config.n_ctx < 128 || config.n_ctx > 1048576) {
        return fail("n_ctx must be in [128, 1048576].");
    }
    if (config.n_threads < 1 || config.n_threads > 256) {
        return fail("n_threads must be in [1, 256].");
    }
    if (config.n_threads_batch < 1 || config.n_threads_batch > 256) {
        return fail("n_threads_batch must be in [1, 256].");
    }
    if (config.n_batch < 1 || config.n_batch > 65536) {
        return fail("n_batch must be in [1, 65536].");
    }
    if (config.n_ubatch < 1 || config.n_ubatch > config.n_batch) {
        return fail("n_ubatch must be in [1, n_batch].");
    }
    if (config.n_gpu_layers < -2 || config.n_gpu_layers > 100000) {
        return fail("n_gpu_layers must be -2 (all), -1 (auto), or a non-negative integer.");
    }
    if (config.main_gpu < 0 || config.main_gpu >= (int) llama_max_devices()) {
        return fail("main_gpu is outside llama_max_devices().");
    }
    if (config.split_mode != "none" && config.split_mode != "layer" &&
        config.split_mode != "row" && config.split_mode != "tensor") {
        return fail("split_mode must be one of: none, layer, row, tensor.");
    }
    if (config.n_cpu_moe < 0 || config.n_cpu_moe > 10000) {
        return fail("n_cpu_moe must be in [0, 10000].");
    }
    static const std::vector<std::string> cache_types = {
            "f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1"};
    if (std::find(cache_types.begin(), cache_types.end(), config.cache_type_k) == cache_types.end()) {
        return fail("cache_type_k is unsupported: " + config.cache_type_k);
    }
    if (std::find(cache_types.begin(), cache_types.end(), config.cache_type_v) == cache_types.end()) {
        return fail("cache_type_v is unsupported: " + config.cache_type_v);
    }
    if (config.flash_attn != "on" && config.flash_attn != "off" && config.flash_attn != "auto") {
        return fail("flash_attn must be one of: on, off, auto.");
    }
    if (config.n_parallel != 1) {
        return fail("n_parallel must be 1: the Android JNI engine is single-sequence.");
    }
    if (config.cache_reuse < 0 || config.cache_reuse > config.n_ctx) {
        return fail("cache_reuse must be in [0, n_ctx].");
    }
    if (config.spec_type != "none" && config.spec_type != "draft-mtp") {
        return fail("spec_type must be one of: none, draft-mtp.");
    }
    if (config.spec_type == "none" && config.spec_draft_n_max != 0) {
        return fail("spec_draft_n_max must be 0 when spec_type is none.");
    }
    if (config.spec_type == "draft-mtp" &&
        (config.spec_draft_n_max < 1 || config.spec_draft_n_max > 8)) {
        return fail("spec_draft_n_max must be in [1, 8] for draft-mtp.");
    }
    if (config.spec_type == "draft-mtp" && config.n_batch < config.spec_draft_n_max + 1) {
        return fail("n_batch must be at least spec_draft_n_max + 1 for draft-mtp verification.");
    }
    const bool quantized_k = config.cache_type_k != "f32" && config.cache_type_k != "f16" && config.cache_type_k != "bf16";
    const bool quantized_v = config.cache_type_v != "f32" && config.cache_type_v != "f16" && config.cache_type_v != "bf16";
    if (quantized_v && config.flash_attn == "off") {
        return fail("quantized cache_type_v requires flash_attn on or auto.");
    }
    if (config.split_mode == "tensor" && (quantized_k || quantized_v)) {
        return fail("split_mode=tensor cannot be combined with quantized KV cache.");
    }
    return true;
}

bool parse_runtime_config(const std::string &text,
                          const RuntimeConfig &defaults,
                          RuntimeConfig &config,
                          std::string &error) {
    config = defaults;
    ordered_json root;
    try {
        root = ordered_json::parse(text.empty() ? "{}" : text);
    } catch (const std::exception &e) {
        error = std::string("Invalid params JSON: ") + e.what();
        return false;
    }
    if (!root.is_object()) {
        error = "Params JSON must be an object.";
        return false;
    }

    ordered_json advanced = ordered_json::object();
    if (root.contains("advanced_json") && !root["advanced_json"].is_null()) {
        try {
            if (root["advanced_json"].is_object()) {
                advanced = root["advanced_json"];
            } else if (root["advanced_json"].is_string()) {
                advanced = ordered_json::parse(root["advanced_json"].get<std::string>());
                if (!advanced.is_object()) throw std::runtime_error("advanced_json must decode to an object");
            } else {
                throw std::runtime_error("advanced_json must be an object or JSON object string");
            }
        } catch (const std::exception &e) {
            error = std::string("Invalid advanced_json: ") + e.what();
            return false;
        }
    }

    auto value_for = [&](const char *key) -> const ordered_json * {
        if (root.contains(key) && !root[key].is_null()) return &root[key];
        if (advanced.contains(key) && !advanced[key].is_null()) return &advanced[key];
        return nullptr;
    };
    auto read_int = [&](const char *key, int &target) -> bool {
        const auto *value = value_for(key);
        if (value == nullptr) return true;
        if (!value->is_number_integer() && !value->is_number_unsigned()) {
            error = std::string(key) + " must be an integer.";
            return false;
        }
        try {
            const long long parsed = value->get<long long>();
            if (parsed < std::numeric_limits<int>::min() || parsed > std::numeric_limits<int>::max()) {
                error = std::string(key) + " is outside the 32-bit integer range.";
                return false;
            }
            target = (int) parsed;
            return true;
        } catch (const std::exception &e) {
            error = std::string("Invalid ") + key + ": " + e.what();
            return false;
        }
    };
    auto read_bool = [&](const char *key, bool &target) -> bool {
        const auto *value = value_for(key);
        if (value == nullptr) return true;
        if (!value->is_boolean()) {
            error = std::string(key) + " must be a boolean.";
            return false;
        }
        target = value->get<bool>();
        return true;
    };
    auto read_string = [&](const char *key, std::string &target) -> bool {
        const auto *value = value_for(key);
        if (value == nullptr) return true;
        if (!value->is_string()) {
            error = std::string(key) + " must be a string.";
            return false;
        }
        target = lower_ascii(value->get<std::string>());
        return true;
    };

    if (!read_int("n_ctx", config.n_ctx) ||
        !read_int("n_threads", config.n_threads) ||
        !read_int("n_threads_batch", config.n_threads_batch) ||
        !read_int("n_batch", config.n_batch) ||
        !read_int("n_ubatch", config.n_ubatch) ||
        !read_int("main_gpu", config.main_gpu) ||
        !read_int("n_cpu_moe", config.n_cpu_moe) ||
        !read_int("n_parallel", config.n_parallel) ||
        !read_int("cache_reuse", config.cache_reuse) ||
        !read_int("spec_draft_n_max", config.spec_draft_n_max) ||
        !read_bool("perf", config.perf) ||
        !read_bool("mmap", config.mmap) ||
        !read_bool("mlock", config.mlock) ||
        !read_string("split_mode", config.split_mode) ||
        !read_string("cache_type_k", config.cache_type_k) ||
        !read_string("cache_type_v", config.cache_type_v) ||
        !read_string("spec_type", config.spec_type)) {
        return false;
    }

    if (const auto *value = value_for("n_gpu_layers")) {
        if (value->is_string()) {
            const std::string mode = lower_ascii(value->get<std::string>());
            if (mode == "auto") config.n_gpu_layers = -1;
            else if (mode == "all") config.n_gpu_layers = -2;
            else {
                error = "n_gpu_layers string must be auto or all.";
                return false;
            }
        } else if (value->is_number_integer() || value->is_number_unsigned()) {
            try {
                config.n_gpu_layers = value->get<int>();
            } catch (const std::exception &e) {
                error = std::string("Invalid n_gpu_layers: ") + e.what();
                return false;
            }
        } else {
            error = "n_gpu_layers must be an integer, auto, or all.";
            return false;
        }
    }

    if (const auto *value = value_for("flash_attn")) {
        if (value->is_boolean()) {
            config.flash_attn = value->get<bool>() ? "on" : "off";
        } else if (value->is_string()) {
            config.flash_attn = lower_ascii(value->get<std::string>());
        } else {
            error = "flash_attn must be a boolean or one of: on, off, auto.";
            return false;
        }
    }

    return validate_runtime_config(config, error);
}

ggml_type cache_type_from_name(const std::string &name) {
    static const std::map<std::string, ggml_type> types = {
            {"f32", GGML_TYPE_F32}, {"f16", GGML_TYPE_F16}, {"bf16", GGML_TYPE_BF16},
            {"q8_0", GGML_TYPE_Q8_0}, {"q4_0", GGML_TYPE_Q4_0}, {"q4_1", GGML_TYPE_Q4_1},
            {"iq4_nl", GGML_TYPE_IQ4_NL}, {"q5_0", GGML_TYPE_Q5_0}, {"q5_1", GGML_TYPE_Q5_1}};
    const auto it = types.find(name);
    return it == types.end() ? GGML_TYPE_COUNT : it->second;
}

llama_split_mode split_mode_from_name(const std::string &name) {
    if (name == "none") return LLAMA_SPLIT_MODE_NONE;
    if (name == "row") return LLAMA_SPLIT_MODE_ROW;
    if (name == "tensor") return LLAMA_SPLIT_MODE_TENSOR;
    return LLAMA_SPLIT_MODE_LAYER;
}

llama_flash_attn_type flash_attn_from_name(const std::string &name) {
    if (name == "on") return LLAMA_FLASH_ATTN_TYPE_ENABLED;
    if (name == "off") return LLAMA_FLASH_ATTN_TYPE_DISABLED;
    return LLAMA_FLASH_ATTN_TYPE_AUTO;
}

std::string model_meta_string(const llama_model *model, const std::string &key) {
    if (model == nullptr) return "";
    const int32_t needed = llama_model_meta_val_str(model, key.c_str(), nullptr, 0);
    if (needed <= 0) return "";
    std::vector<char> buffer((size_t) needed + 1, '\0');
    if (llama_model_meta_val_str(model, key.c_str(), buffer.data(), buffer.size()) < 0) return "";
    return std::string(buffer.data());
}

std::string lowercase_trimmed_ascii(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    const auto last = value.find_last_not_of(" \t\r\n");
    value = value.substr(first, last - first + 1);
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    return value;
}

bool model_meta_declares_non_causal(
        const llama_model *model,
        const std::string &architecture) {
    if (architecture.empty()) return false;
    const auto raw = lowercase_trimmed_ascii(
            model_meta_string(model, architecture + ".attention.causal"));
    return raw == "false" || raw == "0";
}

bool model_meta_declares_pooling(
        const llama_model *model,
        const std::string &architecture) {
    if (architecture.empty()) return false;
    const auto raw = lowercase_trimmed_ascii(
            model_meta_string(model, architecture + ".pooling_type"));
    if (raw.empty() || raw == "0" || raw == "-1" || raw == "none" || raw == "unspecified") {
        return false;
    }
    try {
        return std::stoll(raw) > 0;
    } catch (...) {
        // Pooling metadata is not part of the autoregressive chat contract.
        // An unfamiliar non-empty representation is therefore rejected rather
        // than being silently routed into token generation.
        return true;
    }
}

bool is_dedicated_non_chat_architecture(const std::string &architecture) {
    static const std::vector<std::string> architectures = {
            "clip",
            "bert",
            "modern-bert",
            "nomic-bert",
            "nomic-bert-moe",
            "neo-bert",
            "jina-bert-v2",
            "jina-bert-v3",
            "eurobert",
            "gemma-embedding",
            "llama-embed",
            "wavtokenizer-dec",
    };
    const auto normalized = lowercase_trimmed_ascii(architecture);
    return std::find(architectures.begin(), architectures.end(), normalized) != architectures.end();
}

bool model_supports_autoregressive_chat(
        const llama_model *model,
        const std::string &architecture) {
    return model != nullptr &&
            llama_model_has_decoder(model) &&
            !llama_model_has_encoder(model) &&
            !llama_model_is_diffusion(model) &&
            !is_dedicated_non_chat_architecture(architecture) &&
            !model_meta_declares_non_causal(model, architecture) &&
            !model_meta_declares_pooling(model, architecture);
}

int model_mtp_layer_count(const llama_model *model) {
    return model == nullptr ? 0 : llama_model_n_layer_nextn(model);
}

bool model_supports_requested_mtp(
        const std::string &architecture,
        int mtp_layers) {
    if (mtp_layers <= 0) return false;
    if (mtp_layers == 1) return true;
    // The pinned llama.cpp speculative driver has a chained-head graph only
    // for Step35.  Other architectures still assert a single MTP block in
    // their graph builders, so accepting them here would turn a capability
    // request into a native crash.
    return lowercase_trimmed_ascii(architecture) == "step35";
}

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

bool file_exists(const std::string &path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string join_path(const std::string &dir, const std::string &name) {
    if (dir.empty()) return name;
    return dir.back() == '/' ? dir + name : dir + "/" + name;
}

std::string list_ggml_backend_files(const std::string &dir) {
    if (dir.empty()) return "nativeLibDir is empty";
    DIR *handle = opendir(dir.c_str());
    if (handle == nullptr) {
        return "nativeLibDir is not readable or native libs are not extracted: " + dir;
    }

    std::string result;
    int count = 0;
    while (dirent *entry = readdir(handle)) {
        const std::string name = entry->d_name;
        if (name.find("libggml") == 0 && name.size() >= 3 && name.rfind(".so") == name.size() - 3) {
            if (!result.empty()) result += ", ";
            result += name;
            count++;
            if (count >= 24) {
                result += ", ...";
                break;
            }
        }
    }
    closedir(handle);
    return result.empty() ? "no libggml*.so files found in " + dir : result;
}

void load_baseline_cpu_backend_if_needed() {
    if (ggml_backend_dev_count() > 0 || g_native_lib_dir.empty()) return;

    const char *candidates[] = {
            "libggml-cpu-android_armv8.0_1.so",
            "libggml-cpu-x64.so",
            "libggml-cpu-sse42.so",
            "libggml-cpu.so",
    };

    for (const char *candidate: candidates) {
        const std::string path = join_path(g_native_lib_dir, candidate);
        if (!file_exists(path)) continue;
        if (ggml_backend_load(path.c_str()) != nullptr) {
            g_last_error += "\nLoaded fallback backend: " + path;
            return;
        }
    }
}

bool ensure_backends_loaded_locked() {
    if (!g_native_lib_dir.empty()) {
        ggml_backend_load_all_from_path(g_native_lib_dir.c_str());
    }
    ggml_backend_load_all();
    load_baseline_cpu_backend_if_needed();
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
                            std::string &error) {
    effective = requested;
    const bool force_gpu = requested.n_gpu_layers == -2 || requested.n_gpu_layers > 0;
    if (force_gpu && !g_gpu_offload_supported) {
        error = "n_gpu_layers requests GPU offload, but this APK has no usable non-CPU llama.cpp backend.";
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
        if (requested.main_gpu != 0 && force_gpu) {
            error = "main_gpu must be 0 when no GPU backend is registered.";
            return false;
        }
        if (requested.n_gpu_layers == -1) {
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
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        if (g_last_error.size() < 4096) {
            g_last_error += text == nullptr ? "" : text;
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

struct ChatTemplateOptions {
    bool use_jinja = true;
    bool enable_thinking = true;
    int thinking_budget = 1024;
};

// Openers mirror REASONING_OPEN_MARKERS in the Kotlin ReasoningContentFilter.
// Trailing whitespace (templates usually append "\n" after the opener) is
// ignored; an empty pre-filled block ending in "</think>" matches none of them.
// Only the real prompt renders may set g_prompt_ends_inside_reasoning; the
// prefix-cache probe render in prepare_persistent_prefix_locked uses a
// truncated probe message list and must not disturb the flag.
bool prompt_ends_inside_reasoning(const std::string &prompt) {
    static const std::vector<std::string> reasoning_openers = {
            "<think>",
            "<|think|>",
            "<|channel>thought",
            "<|channel|>thought",
            "<|channel>analysis",
            "<|channel|>analysis",
    };
    size_t end = prompt.size();
    while (end > 0 && std::isspace((unsigned char) prompt[end - 1])) end--;
    for (const auto &opener: reasoning_openers) {
        const size_t n = opener.size();
        if (end >= n && prompt.compare(end - n, n, opener) == 0) {
            return true;
        }
    }
    return false;
}

void clear_target_context_locked(const std::string &reason);

std::string format_messages(const std::vector<ParsedMessage> &messages, const ChatTemplateOptions &options) {
    const bool has_template = g_chat_templates != nullptr;

    if (has_template) {
        common_chat_templates_inputs inputs;
        inputs.use_jinja = options.use_jinja;
        inputs.enable_thinking = options.enable_thinking;
        inputs.add_generation_prompt = true;
        // MCA only needs the rendered prompt. For Android in-app streaming we parse
        // reasoning ourselves, so avoid llama.cpp's PEG/autoparser path here; some
        // recent Jinja templates raise while the parser is being generated even
        // though direct prompt rendering is valid.
        inputs.force_pure_content = true;
        if (options.use_jinja && options.thinking_budget > 0) {
            inputs.chat_template_kwargs["thinking_budget"] = std::to_string(options.thinking_budget);
        }

        for (const auto &message: messages) {
            common_chat_msg msg;
            msg.role = message.role.empty() ? "user" : message.role;
            msg.content = message.content;
            inputs.messages.push_back(msg);
        }

        const std::string prompt = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
        return prompt;
    }

    std::string formatted;
    for (const auto &message: messages) {
        const std::string role = message.role.empty() ? "user" : message.role;
        formatted += role + ": " + message.content + "\n";
    }
    formatted += "assistant: ";
    return formatted;
}

struct PersistentPrefixPreparation {
    bool handled = false;
    size_t reused_tokens = 0;
};

/**
 * Restores or materializes only the stable system/persona prefix. The full
 * prompt is still token-prefix checked before any state is accepted; a
 * template/tokenizer boundary that cannot be proven is a normal cache miss.
 */
PersistentPrefixPreparation prepare_persistent_prefix_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request) {
    PersistentPrefixPreparation result;
    if (!request.requested) return result;
    g_persistent_prefix_cache_attempted = true;

    if (request.full_session_state) {
        if (full_tokens.empty()) {
            g_persistent_prefix_cache_reason = "empty_session_prompt";
            return result;
        }
        clear_target_context_locked("persistent_session_prepare");
        if (!request.restore_state_path.empty()) {
            try {
                llama_tokens restored(full_tokens.size());
                size_t restored_count = 0;
                const size_t loaded = llama_state_seq_load_file(
                        g_context,
                        request.restore_state_path.c_str(),
                        0,
                        restored.data(),
                        restored.size(),
                        &restored_count);
                const bool valid = loaded > 0 && restored_count > 0 &&
                        restored_count <= full_tokens.size() &&
                        mca::llama::tokenPrefixMatches(
                                full_tokens,
                                llama_tokens(restored.begin(),
                                             restored.begin() + restored_count));
                if (valid) {
                    restored.resize(restored_count);
                    g_current_position = (llama_pos) restored_count;
                    g_context_tokens = restored;
                    g_cache_state_valid = true;
                    g_cache_reuse_hit = true;
                    g_cache_reused_tokens = (int) restored_count;
                    g_cache_reuse_hits++;
                    g_cache_reuse_reason = "persistent_session_hit";
                    g_persistent_prefix_cache_hit = true;
                    g_persistent_prefix_cache_tokens = (int) restored_count;
                    g_persistent_prefix_cache_reason = "session_state_loaded";
                    g_pending_full_session_write_path = request.write_state_path;
                    result.handled = true;
                    result.reused_tokens = restored_count;
                    return result;
                }
                g_persistent_prefix_cache_reason = "session_state_token_mismatch";
            } catch (...) {
                g_persistent_prefix_cache_reason = "session_state_load_failed";
            }
        } else {
            g_persistent_prefix_cache_reason = "session_state_cold_start";
        }
        g_pending_full_session_write_path = request.write_state_path;
        result.handled = true;
        result.reused_tokens = 0;
        return result;
    }

    if (request.fixed_system_prompt.empty() || messages.empty() ||
        lower_ascii(messages.front().role) != "system" ||
        messages.front().content.rfind(request.fixed_system_prompt, 0) != 0) {
        g_persistent_prefix_cache_reason = "fixed_system_prefix_unavailable";
        return result;
    }

    // The marker is used only for a probe render and is never sent to the
    // model. Several candidates avoid treating user-supplied marker text as a
    // boundary; token-prefix equality remains the final acceptance check.
    std::string probe_formatted;
    size_t marker_position = std::string::npos;
    for (int attempt = 0; attempt < 8 && marker_position == std::string::npos; ++attempt) {
        const std::string marker =
                "MCA_PREFIX_CACHE_BOUNDARY_V1_" + std::to_string(attempt) + "_9f2a";
        bool marker_in_input = false;
        for (const auto &message : messages) {
            if (message.content.find(marker) != std::string::npos) {
                marker_in_input = true;
                break;
            }
        }
        if (marker_in_input) continue;
        auto probe_messages = messages;
        probe_messages.front().content = request.fixed_system_prompt + marker;
        try {
            probe_formatted = format_messages(probe_messages, chat_options);
        } catch (...) {
            probe_formatted.clear();
        }
        const size_t first = probe_formatted.find(marker);
        if (first != std::string::npos && probe_formatted.find(marker, first + marker.size()) == std::string::npos) {
            marker_position = first;
        }
    }
    if (marker_position == std::string::npos || probe_formatted.empty()) {
        g_persistent_prefix_cache_reason = "template_boundary_unavailable";
        return result;
    }

    // Tokenize the complete probe instead of the prefix substring. A BPE token
    // can span the textual boundary, so the substring's final token is not
    // necessarily a prefix of the full prompt. The common token prefix is the
    // largest portion proven to be both stable and exactly reusable.
    llama_tokens probe_tokens;
    try {
        probe_tokens = common_tokenize(g_context, probe_formatted, true, true);
    } catch (...) {
        probe_tokens.clear();
    }
    const size_t stable_prefix_tokens = mca::llama::longestCommonTokenPrefix(
            full_tokens,
            probe_tokens);
    if (stable_prefix_tokens == 0 || stable_prefix_tokens >= full_tokens.size()) {
        g_persistent_prefix_cache_reason = "token_prefix_mismatch";
        return result;
    }
    llama_tokens prefix_tokens(
            full_tokens.begin(),
            full_tokens.begin() + static_cast<llama_tokens::difference_type>(stable_prefix_tokens));

    // A sequence state file contains the complete attention/recurrent state;
    // it must be loaded into a clean sequence and validated against the exact
    // token prefix before the dynamic suffix is evaluated.
    clear_target_context_locked("persistent_prefix_prepare");
    if (!request.restore_state_path.empty()) {
        try {
            llama_tokens restored(prefix_tokens.size());
            size_t restored_count = 0;
            const size_t loaded = llama_state_seq_load_file(
                    g_context,
                    request.restore_state_path.c_str(),
                    0,
                    restored.data(),
                    restored.size(),
                    &restored_count);
            const bool restored_tokens_match =
                    std::equal(restored.begin(), restored.end(), prefix_tokens.begin());
            const bool full_prompt_prefix_matches =
                    mca::llama::tokenPrefixMatches(full_tokens, restored);
            if (loaded > 0 && mca::llama::canRestorePersistentPrefixState(
                    prefix_tokens.size(),
                    restored_count,
                    restored_tokens_match,
                    full_prompt_prefix_matches)) {
                g_current_position = (llama_pos) restored_count;
                g_context_tokens = prefix_tokens;
                g_cache_state_valid = true;
                g_cache_reuse_hit = true;
                g_cache_reused_tokens = (int) restored_count;
                g_cache_reuse_hits++;
                g_cache_reuse_reason = "persistent_prefix_hit";
                g_persistent_prefix_cache_hit = true;
                g_persistent_prefix_cache_tokens = (int) restored_count;
                g_persistent_prefix_cache_reason = "state_loaded";
                result.handled = true;
                result.reused_tokens = restored_count;
                return result;
            }
            g_persistent_prefix_cache_reason = "state_token_mismatch";
        } catch (...) {
            g_persistent_prefix_cache_reason = "state_load_failed";
        }
        clear_target_context_locked("persistent_state_load_failed");
    }

    const int prefix_rc = decode_tokens(prefix_tokens, false);
    if (prefix_rc != 0 || g_context_shifts != 0) {
        g_persistent_prefix_cache_reason = prefix_rc == 0
                                            ? "prefix_context_shifted"
                                            : "prefix_prefill_failed";
        clear_target_context_locked(g_persistent_prefix_cache_reason);
        return result;
    }

    g_persistent_prefix_cache_tokens = (int) prefix_tokens.size();
    if (!request.write_state_path.empty()) {
        try {
            const size_t saved = save_llama_state_with_progress_locked(
                    g_context,
                    request.write_state_path.c_str(),
                    0,
                    prefix_tokens.data(),
                    prefix_tokens.size(),
                    LLAMA_STATE_SEQ_FLAGS_NONE);
            if (saved > 0) {
                g_persistent_prefix_cache_saved = true;
                g_persistent_prefix_cache_reason = "state_saved";
            } else {
                g_persistent_prefix_cache_reason = "state_save_failed";
            }
        } catch (...) {
            g_persistent_prefix_cache_reason = "state_save_failed";
        }
    } else {
        g_persistent_prefix_cache_reason = "no_write_path";
    }
    result.handled = true;
    result.reused_tokens = prefix_tokens.size();
    return result;
}

bool messages_have_images(const std::vector<ParsedMessage> &messages) {
    for (const auto &message: messages) {
        if (!message.image_paths.empty()) return true;
    }
    return false;
}

std::vector<ParsedMessage> with_media_markers(const std::vector<ParsedMessage> &messages) {
    std::vector<ParsedMessage> out;
    out.reserve(messages.size());
    const char *marker = mtmd_default_marker();
    for (auto message: messages) {
        if (!message.image_paths.empty()) {
            std::string prefix;
            for (size_t i = 0; i < message.image_paths.size(); i++) {
                prefix += marker;
            }
            message.content = prefix + (message.content.empty() ? "请描述这张图片。" : message.content);
        }
        out.push_back(std::move(message));
    }
    return out;
}

int prefill_multimodal_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options
) {
    if (g_mtmd_context == nullptr || !g_vision_ready) {
        g_last_error = "Local vision is not ready. Attach a matching mmproj projector and reload the model.";
        return -4;
    }

    std::vector<mtmd_bitmap *> owned_bitmaps;
    std::vector<mtmd_helper_video *> owned_videos;
    const auto free_media = [&]() {
        for (auto *owned: owned_bitmaps) mtmd_bitmap_free(owned);
        for (auto *owned: owned_videos) mtmd_helper_video_free(owned);
    };
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    for (const auto &message: messages) {
        for (const auto &path: message.image_paths) {
            const auto loaded = mtmd_helper_bitmap_init_from_file(
                    g_mtmd_context,
                    path.c_str(),
                    false);
            mtmd_bitmap *bitmap = loaded.bitmap;
            if (bitmap == nullptr) {
                if (loaded.video_ctx != nullptr) mtmd_helper_video_free(loaded.video_ctx);
                free_media();
                g_last_error = "Failed to load local vision image: " + path;
                return -5;
            }
            owned_bitmaps.push_back(bitmap);
            if (loaded.video_ctx != nullptr) owned_videos.push_back(loaded.video_ctx);
            bitmap_ptrs.push_back(bitmap);
        }
    }

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (chunks == nullptr) {
        free_media();
        g_last_error = "mtmd_input_chunks_init returned null.";
        return -6;
    }

    const auto marked_messages = with_media_markers(messages);
    const auto formatted = format_messages(marked_messages, chat_options);
    g_prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(formatted);
    mtmd_input_text text{};
    text.text = formatted.c_str();
    text.text_len = formatted.size();
    text.add_special = true;
    text.parse_special = true;

    int tokenize_rc = mtmd_tokenize(
            g_mtmd_context,
            chunks,
            &text,
            bitmap_ptrs.empty() ? nullptr : bitmap_ptrs.data(),
            bitmap_ptrs.size());
    free_media();
    if (tokenize_rc != 0) {
        mtmd_input_chunks_free(chunks);
        g_last_error = "mtmd_tokenize failed: " + std::to_string(tokenize_rc);
        return -7;
    }

    const size_t multimodal_prompt_tokens = mtmd_helper_get_n_tokens(chunks);
    begin_prefill_progress(multimodal_prompt_tokens);

    llama_pos new_position = 0;
    const int eval_rc = mtmd_helper_eval_chunks(
            g_mtmd_context,
            g_context,
            chunks,
            0,
            0,
            g_n_batch,
            true,
            &new_position);
    g_prompt_tokens = (long long) multimodal_prompt_tokens;
    mtmd_input_chunks_free(chunks);
    if (eval_rc != 0) {
        g_last_error = "mtmd_helper_eval_chunks failed: " + std::to_string(eval_rc);
        return -8;
    }
    g_current_position = new_position;
    g_prefill_computed_tokens = (long long) multimodal_prompt_tokens;
    report_reused_prefill_tokens(multimodal_prompt_tokens);
    return 0;
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

// Serializes the seq_id KV state to `path` in two phases so the UI can report
// real-time progress. The in-memory encode is a single atomic llama.cpp call
// (no incremental interface exists), but the file write is chunked so bytes
// written/total are updated per chunk. The on-disk layout is identical to
// llama_state_seq_save_file(). Returns bytes written, or 0 on failure.
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
    const size_t total = llama_state_seq_get_size_ext(ctx, seq_id, flags);
    if (total == 0) {
        g_persist_total_bytes.store(0, std::memory_order_release);
        return 0;
    }
    g_persist_total_bytes.store(total, std::memory_order_release);
    try {
        std::vector<uint8_t> buf(total);
        g_persist_stage.store(static_cast<int>(PersistStage::Encoding), std::memory_order_release);
        const size_t encoded = llama_state_seq_get_data_ext(ctx, buf.data(), buf.size(), seq_id, flags);
        if (encoded != total) {
            reset_persist_progress();
            return 0;
        }
        g_persist_stage.store(static_cast<int>(PersistStage::Writing), std::memory_order_release);
        std::ofstream out(path, std::ios::binary);
        if (!out) {
            reset_persist_progress();
            return 0;
        }
        constexpr size_t kWriteChunk = 1 << 20; // 1 MiB
        size_t written_total = 0;
        for (size_t offset = 0; offset < total; offset += kWriteChunk) {
            const size_t n = std::min(kWriteChunk, total - offset);
            out.write(reinterpret_cast<const char *>(buf.data() + offset), static_cast<std::streamsize>(n));
            if (!out) {
                reset_persist_progress();
                return 0;
            }
            written_total += n;
            g_persist_written_bytes.store(written_total, std::memory_order_release);
        }
        out.flush();
        out.close();
        g_persist_stage.store(static_cast<int>(PersistStage::Done), std::memory_order_release);
        return written_total;
    } catch (const std::exception &) {
        reset_persist_progress();
        return 0;
    }
}

bool save_turn_cache_checkpoint_locked() {
    if (g_context == nullptr || !g_cache_state_valid ||
        active_prefix_cache_strategy_locked() !=
                mca::llama::PrefixCacheStrategy::PartialStateCheckpoint ||
        g_context_tokens.empty() ||
        g_current_position != (llama_pos) g_context_tokens.size() ||
        g_stop_requested.load(std::memory_order_acquire)) {
        return false;
    }

    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t checkpoint_size = llama_state_seq_get_size_ext(g_context, 0, flags);
    const size_t incoming_bytes = turn_cache_checkpoint_bytes(
            checkpoint_size,
            g_context_tokens.size());
    if (checkpoint_size == 0 || incoming_bytes > MAX_TURN_CACHE_CHECKPOINT_BYTES) {
        return false;
    }

    // Reconcile and evict before allocating the new state or token prefix. The
    // budget therefore bounds the save-time peak as well as the retained set.
    g_turn_cache_checkpoint_bytes = 0;
    for (const auto &checkpoint : g_turn_cache_checkpoints) {
        const size_t checkpoint_bytes = turn_cache_checkpoint_bytes(checkpoint);
        if (checkpoint_bytes >
                std::numeric_limits<size_t>::max() - g_turn_cache_checkpoint_bytes) {
            invalidate_turn_cache_checkpoints_locked();
            break;
        }
        g_turn_cache_checkpoint_bytes += checkpoint_bytes;
    }
    g_turn_cache_checkpoint_count = g_turn_cache_checkpoints.size();

    try {
        auto existing = std::find_if(
                g_turn_cache_checkpoints.begin(),
                g_turn_cache_checkpoints.end(),
                [&](const TurnCacheCheckpoint &checkpoint) {
                    return checkpoint.prefix == g_context_tokens;
                });
        if (existing != g_turn_cache_checkpoints.end()) {
            g_turn_cache_checkpoint_bytes -= turn_cache_checkpoint_bytes(*existing);
            g_turn_cache_checkpoints.erase(existing);
        }

        while ((!g_turn_cache_checkpoints.empty() &&
                g_turn_cache_checkpoints.size() >= MAX_TURN_CACHE_CHECKPOINTS) ||
               g_turn_cache_checkpoint_bytes >
                       MAX_TURN_CACHE_CHECKPOINT_BYTES - incoming_bytes) {
            g_turn_cache_checkpoint_bytes -=
                    turn_cache_checkpoint_bytes(g_turn_cache_checkpoints.front());
            g_turn_cache_checkpoints.erase(g_turn_cache_checkpoints.begin());
        }
        g_turn_cache_checkpoint_count = g_turn_cache_checkpoints.size();

        std::vector<uint8_t> checkpoint_data(checkpoint_size);
        const size_t written = llama_state_seq_get_data_ext(
                g_context,
                checkpoint_data.data(),
                checkpoint_data.size(),
                0,
                flags);
        if (written != checkpoint_data.size() ||
            g_stop_requested.load(std::memory_order_acquire)) {
            return false;
        }

        llama_tokens checkpoint_prefix = g_context_tokens;
        const size_t checkpoint_position = checkpoint_prefix.size();
        g_turn_cache_checkpoints.push_back(TurnCacheCheckpoint{
                std::move(checkpoint_prefix),
                std::move(checkpoint_data),
                checkpoint_position});
        // Account only after push_back succeeds. If the vector allocation
        // throws, the catch path below can safely recompute the snapshot.
        g_turn_cache_checkpoint_bytes +=
                turn_cache_checkpoint_bytes(g_turn_cache_checkpoints.back());
        g_turn_cache_checkpoint_count = g_turn_cache_checkpoints.size();
        if (g_stop_requested.load(std::memory_order_acquire)) {
            g_turn_cache_checkpoint_bytes -=
                    turn_cache_checkpoint_bytes(g_turn_cache_checkpoints.back());
            g_turn_cache_checkpoints.pop_back();
            g_turn_cache_checkpoint_count = g_turn_cache_checkpoints.size();
            return false;
        }
        return true;
    } catch (const std::exception &) {
        g_turn_cache_checkpoint_bytes = 0;
        for (const auto &checkpoint : g_turn_cache_checkpoints) {
            const size_t checkpoint_bytes = turn_cache_checkpoint_bytes(checkpoint);
            if (checkpoint_bytes >
                    std::numeric_limits<size_t>::max() - g_turn_cache_checkpoint_bytes) {
                invalidate_turn_cache_checkpoints_locked();
                return false;
            }
            g_turn_cache_checkpoint_bytes += checkpoint_bytes;
        }
        g_turn_cache_checkpoint_count = g_turn_cache_checkpoints.size();
        return false;
    }
}

bool restore_turn_cache_checkpoint_locked(
        const llama_tokens &tokens,
        size_t &reused_tokens) {
    if (g_context == nullptr || !g_cache_state_valid ||
        g_context_tokens.empty() || g_turn_cache_checkpoints.empty() ||
        g_current_position != (llama_pos) g_context_tokens.size()) {
        return false;
    }

    // Prefer the deepest checkpoint that is a prefix of both the new prompt
    // and the currently live context. The second check is essential because a
    // checkpoint from a different conversation branch contains recurrent state
    // that cannot reconstruct a missing attention prefix by itself.
    const TurnCacheCheckpoint *candidate = nullptr;
    for (auto it = g_turn_cache_checkpoints.rbegin();
         it != g_turn_cache_checkpoints.rend(); ++it) {
        if (it->position == 0 || it->position >= tokens.size() ||
            it->position > g_context_tokens.size() ||
            !mca::llama::tokenPrefixMatches(tokens, it->prefix) ||
            !mca::llama::tokenPrefixMatches(g_context_tokens, it->prefix)) {
            continue;
        }
        if (candidate == nullptr || it->position > candidate->position) {
            candidate = &*it;
        }
    }
    if (candidate == nullptr) return false;

    constexpr llama_state_seq_flags flags = LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY;
    const size_t restored = llama_state_seq_set_data_ext(
            g_context,
            candidate->data.data(),
            candidate->data.size(),
            0,
            flags);
    if (restored != candidate->data.size()) {
        // A failed state read may already have mutated recurrent memory. Do
        // not attempt another cache path on a context whose state is unknown.
        clear_target_context_locked("turn_checkpoint_restore_failed");
        return false;
    }

    if (g_current_position > (llama_pos) candidate->position &&
        !trim_context_locked(
                g_context,
                (llama_pos) candidate->position,
                "target turn-checkpoint cache-reuse")) {
        const std::string trim_error = g_last_error;
        g_last_error.clear();
        clear_target_context_locked("turn_checkpoint_suffix_trim_failed");
        if (!trim_error.empty()) {
            __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", trim_error.c_str());
        }
        return false;
    }
    g_current_position = (llama_pos) candidate->position;
    g_context_tokens = candidate->prefix;
    g_cache_state_valid = true;
    g_cache_reuse_hit = true;
    g_cache_reused_tokens = (int) std::min<size_t>(
            candidate->position,
            static_cast<size_t>(std::numeric_limits<int>::max()));
    g_cache_reuse_hits++;
    g_turn_cache_checkpoint_hits++;
    g_cache_reuse_reason = "turn_checkpoint_hit";
    reused_tokens = candidate->position;
    return true;
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

    RuntimeConfig effective;
    if (!resolve_backend_config(requested, effective, config_error)) {
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

    const bool auto_gpu_cpu_fallback_allowed =
            requested.n_gpu_layers == -1 &&
            effective.n_gpu_layers != 0 &&
            effective.n_cpu_moe == 0;
    g_gpu_auto_fallback_applied = false;
    g_gpu_auto_fallback_reason = auto_gpu_cpu_fallback_allowed
                                 ? "pending"
                                 : (requested.n_gpu_layers == -1
                                    ? "no_gpu_backend_cpu"
                                    : "not_requested");

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
            moe_patterns.push_back(llm_ffn_exps_block_regex(i));
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
            set_load_failure_code(load_failure_code_from_llama_error(g_last_error).c_str());
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
        const std::string failure_code = load_failure_code_from_llama_error(g_last_error);
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
        vision_params.n_threads = n_threads;
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
                if (!g_pending_full_session_write_path.empty() &&
                    g_context_shifts == 0 &&
                    !g_stop_requested.load(std::memory_order_acquire)) {
                    try {
                        const size_t saved = save_llama_state_with_progress_locked(
                                g_context,
                                g_pending_full_session_write_path.c_str(),
                                0,
                                tokens.data(),
                                tokens.size(),
                                LLAMA_STATE_SEQ_FLAGS_NONE);
                        if (saved > 0) {
                            g_persistent_prefix_cache_saved = true;
                            g_persistent_prefix_cache_tokens = (int) tokens.size();
                            g_persistent_prefix_cache_reason = "session_state_saved";
                        } else {
                            g_persistent_prefix_cache_reason = "session_state_save_failed";
                        }
                    } catch (...) {
                        g_persistent_prefix_cache_reason = "session_state_save_failed";
                    }
                    g_pending_full_session_write_path.clear();
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
    const int total = std::max(
            0,
            g_prefill_progress_total_tokens.load(std::memory_order_acquire));
    const int completed = std::clamp(
            g_prefill_progress_completed_tokens.load(std::memory_order_acquire),
            0,
            total);
    std::ostringstream out;
    out << "{\"completedTokens\":" << completed
        << ",\"totalTokens\":" << total << "}";
    return string_to_jstring(env, out.str());
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
