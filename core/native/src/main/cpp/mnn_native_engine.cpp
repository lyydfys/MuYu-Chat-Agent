#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <fcntl.h>
#include <fstream>
#include <functional>
#include <limits.h>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <ostream>
#include <random>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#ifndef MCA_WITH_MNN_LLM
#define MCA_WITH_MNN_LLM 0
#endif

#ifndef MCA_WITH_MNN_DIFFUSION
#define MCA_WITH_MNN_DIFFUSION 0
#endif

#include "nlohmann/json.hpp"
#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_diffusion_batch_policy.hpp"
#include "mnn_legacy_chat_template_policy.hpp"
#include "mnn_embedding_bundle_policy.hpp"
#include "mnn_bundle_path_policy.hpp"
#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_template_parser.hpp"
#include "mca/mnn/mnn_vision_adapter.hpp"
#include "mca/mnn/mnn_stats_collector.hpp"
#include "mca/mnn/mnn_prompt_cache_manager.hpp"
#include "mca/mnn/mnn_protocol_parser.hpp"
#include "mca/mnn/mnn_clip_tokenizer.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_prompt_weighting.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "mnn_runtime_capability_policy.hpp"
#include "mnn_sampling_config.hpp"
#include "mnn_stream_protocol_filter.hpp"
#include "mnn_visual_stream_queue.hpp"
#include "textual_inversion_conditioning.hpp"
#include "mnn_vision_path_policy.hpp"
#include "execution_asset_binding.hpp"

#if MCA_WITH_MNN_LLM
#include "llm/llm.hpp"
#endif

#if MCA_WITH_MNN_DIFFUSION
#include "mca/mnn/mnn_tensor_ops.hpp"
#include "mca/mnn/mnn_vae_image_writer.hpp"
#include "mca/mnn/mnn_clip_weighting.hpp"
#include "mca/mnn/mnn_pndm_scheduler.hpp"
#endif

#ifndef MCA_WITH_TOKENIZERS_CPP
#define MCA_WITH_TOKENIZERS_CPP 0
#endif

#ifndef MCA_MNN_RUNTIME_CACHE_NAMESPACE
#if MCA_WITH_MNN_LLM
#error "MNN runtime cache namespace must come from the verified vendor patch"
#else
#define MCA_MNN_RUNTIME_CACHE_NAMESPACE "unlinked"
#endif
#endif

#if (MCA_WITH_MNN_LLM || MCA_WITH_MNN_DIFFUSION) && defined(MNN_IMGCODECS)
#include <cv/cv.hpp>
#endif

#if MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/expr/Module.hpp>
#include "mnn_diffusion_tokenizer.hpp"
#include "scheduler.hpp"
#include "diffusion/stable_diffusion.hpp"
#include "diffusion/sana_diffusion.hpp"
#include "mnn_sana_session.hpp"
#endif

namespace {

using json = nlohmann::json;

bool prompt_has_non_unity_attention_weight(
        const std::string& prompt,
        bool& weighted,
        std::string& error) {
    return mca::mnn::prompt_has_non_unity_attention_weight(prompt, weighted, error);
}

class MnnDiffusionCancelled final : public std::exception {
public:
    const char *what() const noexcept override {
        return "MNN-Diffusion generation was cancelled.";
    }
};

std::mutex g_mnn_mutex;
std::mutex g_mnn_lifecycle_mutex;
bool g_loaded = false;
bool g_generation_active = false;
bool g_stop_requested = false;
// Stop is published independently of g_mnn_mutex. MNN's generate(1) call can
// hold that mutex for a long time; the UI cancellation path must still be able
// to signal the in-flight call without waiting behind the same mutex.
std::atomic_bool g_stop_signal{false};

#if MCA_WITH_MNN_LLM
struct VisualStreamState {
    mca::mnn::VisualStreamQueue queue;
    std::mutex consumer_mutex;
    mca::mnn::StreamProtocolFilterState filter;
    std::string utf8_tail;
};

std::shared_ptr<VisualStreamState> g_visual_stream;
std::thread g_visual_producer;
std::atomic_bool g_visual_producer_running{false};
std::mutex g_visual_stats_mutex;
std::string g_visual_stats_json;

void update_visual_stats_locked();

bool join_visual_producer(JNIEnv* env) {
    if (!g_visual_producer.joinable()) return true;
    const auto stream = std::atomic_load(&g_visual_stream);
    if (stream != nullptr && !stream->queue.finished()) {
        g_stop_signal.store(true, std::memory_order_release);
        stream->queue.cancel();
    }
    const bool finished = stream != nullptr
            ? stream->queue.waitFinished(std::chrono::seconds(3))
            : !g_visual_producer_running.load(std::memory_order_acquire);
    if (!finished) {
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class,
                    "MNN visual generation has not stopped; isolated worker recovery is required.");
            env->DeleteLocalRef(exception_class);
        }
        return false;
    }
    // Never wait for the producer while holding g_mnn_mutex. A native stall is
    // recovered by the isolated worker watchdog; its model stays alive here.
    g_visual_producer.join();
    std::atomic_store(&g_visual_stream, std::shared_ptr<VisualStreamState>{});
    return true;
}
#endif
bool g_runner_ready =
#if MCA_WITH_MNN_LLM
        true;
#else
        false;
#endif

std::string g_model_path;
std::string g_original_model_path;
std::string g_visual_model_path;
std::string g_mnn_model_type;
// The MNN LLM backend is load-bound and may be selected through the resolved
// runtime profile ("cpu" or "opencl"). Keep an explicit native snapshot so
// diagnostics never report CPU after an OpenCL load merely because the JNI
// class is historically named NativeMnnBridge.
std::string g_mnn_backend_type = "cpu";
std::string g_native_lib_dir;
std::string g_pending_chunk;
std::string g_pending_utf8_tail;
std::vector<std::string> g_model_stop_markers;
std::vector<std::string> g_active_stop_markers;
mca::mnn::StreamProtocolFilterState g_stream_protocol_filter;
mca::mnn::MnnRequestLifecyclePolicy g_mnn_request_lifecycle;
bool g_mnn_debug_trace_enabled = false;
bool g_mnn_debug_trace_truncated = false;
std::string g_mnn_debug_raw_output;
bool g_mnn_debug_token_ids_truncated = false;
std::vector<int> g_mnn_debug_generated_token_ids;
bool g_mnn_debug_prompt_truncated = false;
std::string g_mnn_debug_prompt;
std::string g_last_config_json = "{}";
// Immutable load-time snapshot. g_last_config_json is request-scoped and may
// change at beginCompletion(), so it cannot prove which session is loaded.
std::string g_loaded_config_json = "{}";
std::string g_last_error =
#if MCA_WITH_MNN_LLM
        "";
#else
        "MNN CPU runner is not linked in this build. Build mca_mnn_native with official MNN-LLM to enable it.";
#endif

int g_max_new_tokens = 512;
int g_max_all_tokens = 8192;
int g_n_threads = 4;
int g_generated_steps = 0;
uint64_t g_load_generation = 0;
// Process-lifetime acceptance trace. Model unload/reload must not reset it.
uint64_t g_generation_sequence = 0;
int64_t g_loaded_at_ms = 0;
int64_t g_generation_started_at_ms = 0;
int64_t g_first_chunk_at_ms = 0;
bool g_eop_seen = false;
bool g_vision_ready = false;
bool g_multimodal_system_prompt_suppressed = false;
bool g_multimodal_history_suppressed = false;
bool g_sync_stepping = true;
size_t g_streamed_bytes = 0;
std::string g_generation_stop_reason = "idle";
// True when the rendered chat prompt ends inside an unclosed reasoning opener
// (e.g. a DeepSeek-style template pre-fills "<think>\n"), so the generated
// stream starts mid-thinking and only the close marker will appear.
bool g_prompt_ends_inside_reasoning = false;

// Prompt-prefill progress snapshots for the UI. MNN is a prebuilt imported
// runtime, so no intermediate per-batch events are available: the total is set
// once the prompt total is known and completed jumps to total when prefill
// finishes. Zero total means "cannot report an exact prefill".
std::atomic<int> g_mnn_prefill_completed_tokens{0};
std::atomic<int> g_mnn_prefill_total_tokens{0};

#if MCA_WITH_MNN_LLM
MNN::Transformer::Llm* g_llm = nullptr;

using mca::mnn::MnnPromptCacheRuntimeState;

MnnPromptCacheRuntimeState g_mnn_prompt_cache;
long long g_mnn_prompt_cache_hits = 0;
long long g_mnn_prompt_cache_misses = 0;

void capture_mnn_debug_generated_token_ids_locked() {
    if (!g_mnn_debug_trace_enabled || g_llm == nullptr || g_llm->getContext() == nullptr) return;
    constexpr size_t kMaxDebugGeneratedTokenIds = 64;
    const auto& history = g_llm->getContext()->history_tokens;
    const size_t start = std::min(g_mnn_prompt_cache.generation_history_start, history.size());
    const size_t available = history.size() - start;
    const size_t accepted = std::min(available, kMaxDebugGeneratedTokenIds);
    g_mnn_debug_generated_token_ids.assign(history.begin() + start, history.begin() + start + accepted);
    g_mnn_debug_token_ids_truncated = accepted < available;
}

void append_mnn_debug_raw_output(const char* data, size_t size) {
    if (!g_mnn_debug_trace_enabled || data == nullptr || size == 0) return;
    constexpr size_t kMaxDebugRawOutputBytes = 384;
    if (g_mnn_debug_raw_output.size() >= kMaxDebugRawOutputBytes) {
        g_mnn_debug_trace_truncated = true;
        return;
    }
    const size_t accepted = std::min(size, kMaxDebugRawOutputBytes - g_mnn_debug_raw_output.size());
    g_mnn_debug_raw_output.append(data, accepted);
    if (accepted < size) g_mnn_debug_trace_truncated = true;
}

// Openers mirror REASONING_OPEN_MARKERS in the Kotlin ReasoningContentFilter.
// Trailing whitespace (templates usually append "\n" after the opener) is
// ignored; an empty pre-filled block ending in "</think>" matches none of them.
bool prompt_ends_inside_reasoning(const std::string& prompt) {
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
    for (const auto& opener: reasoning_openers) {
        const size_t n = opener.size();
        if (end >= n && prompt.compare(end - n, n, opener) == 0) {
            return true;
        }
    }
    return false;
}

void capture_mnn_debug_prompt(const std::string& prompt) {
    if (!g_mnn_debug_trace_enabled) return;
    // Keep the test-only trace bounded: real prompts may contain long histories,
    // image data, or sensitive user text. The retained prefix is enough to verify
    // role ordering, system prompt injection, and the generation marker.
    constexpr size_t kMaxDebugPromptBytes = 4096;
    const size_t accepted = std::min(prompt.size(), kMaxDebugPromptBytes);
    g_mnn_debug_prompt.assign(prompt.data(), accepted);
    g_mnn_debug_prompt_truncated = accepted < prompt.size();
}

class MnnStreamBuffer : public std::streambuf {
public:
    explicit MnnStreamBuffer(std::shared_ptr<VisualStreamState> visual = {}) : visual_(std::move(visual)) {}

    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (s != nullptr && n > 0) {
            if (visual_) {
                if (!visual_->queue.write(s, static_cast<size_t>(n))) {
                    // The SDK owns a mutable context but exposes a const view.
                    // As in the text stepping adapter, mutate it only on the
                    // same thread that is executing MNN's generation loop.
                    if (g_llm != nullptr && g_llm->getContext() != nullptr) {
                        auto* context = const_cast<MNN::Transformer::LlmContext*>(g_llm->getContext());
                        context->status = MNN::Transformer::LlmStatus::USER_CANCEL;
                    }
                    return n;
                }
            } else {
                g_pending_chunk.append(s, static_cast<size_t>(n));
            }
            append_mnn_debug_raw_output(s, static_cast<size_t>(n));
            g_streamed_bytes += static_cast<size_t>(n);
            if (g_first_chunk_at_ms == 0) {
                g_first_chunk_at_ms = now_ms();
            }
            if (visual_) update_visual_stats_locked();
        }
        return n;
    }

    int overflow(int ch) override {
        if (ch != EOF) {
            char c = static_cast<char>(ch);
            if (xsputn(&c, 1) != 1) return traits_type::eof();
        }
        return ch;
    }

private:
    std::shared_ptr<VisualStreamState> visual_;

    static int64_t now_ms() {
        using namespace std::chrono;
        return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
    }
};

std::unique_ptr<MnnStreamBuffer> g_stream_buffer;
std::unique_ptr<std::ostream> g_output_stream;
#endif

std::mutex g_mnn_diffusion_mutex;
std::mutex g_mnn_diffusion_runtime_mutex;
bool g_mnn_diffusion_generating = false;
bool g_mnn_diffusion_cancel_requested = false;
bool g_mnn_diffusion_loaded = false;
int g_mnn_diffusion_step = 0;
int g_mnn_diffusion_steps = 0;
int g_mnn_diffusion_width = 512;
int g_mnn_diffusion_height = 512;
int g_mnn_diffusion_threads = 4;
int64_t g_mnn_diffusion_started_at_ms = 0;
int64_t g_mnn_diffusion_finished_at_ms = 0;
int64_t g_mnn_diffusion_generation_sequence = 0;
double g_mnn_diffusion_seconds_per_step = 0.0;
std::string g_mnn_diffusion_bundle_root;
std::string g_mnn_diffusion_output_path;
std::string g_mnn_diffusion_phase = "idle";
std::string g_mnn_diffusion_message = "MNN-Diffusion is idle.";
std::string g_mnn_diffusion_backend = "cpu";
std::string g_mnn_diffusion_runner = "none";
std::string g_mnn_diffusion_last_error =
#if MCA_WITH_MNN_DIFFUSION
        "";
#else
        "MNN-Diffusion native runner is not linked in this APK.";
#endif

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::string jstring_to_std(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    std::string out;
    try {
        out = mca::utf8::encode_from_utf16(
                reinterpret_cast<const uint16_t*>(chars),
                static_cast<size_t>(length));
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring utf16_to_jstring(JNIEnv* env, const std::vector<uint16_t>& value) {
    if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class, "Native UTF-8 output is too large for a Java string.");
            env->DeleteLocalRef(exception_class);
        }
        return nullptr;
    }
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    static constexpr jchar kEmptyStringData = 0;
    const auto* data = value.empty()
            ? &kEmptyStringData
            : reinterpret_cast<const jchar*>(value.data());
    return env->NewString(
            data,
            static_cast<jsize>(value.size()));
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& value) {
    return utf16_to_jstring(env, mca::utf8::decode_to_utf16(value, false).utf16);
}

using mca::mnn::default_mnn_stop_markers;
using mca::mnn::append_stop_marker;

void reset_active_stop_markers_locked() {
    g_model_stop_markers = default_mnn_stop_markers();
    g_active_stop_markers = g_model_stop_markers;
    g_stream_protocol_filter.reset(g_active_stop_markers);
}

jstring take_pending_utf8_locked(JNIEnv* env, bool flush, bool* emitted) {
    std::string bytes;
    bytes.reserve(g_pending_utf8_tail.size() + g_pending_chunk.size());
    bytes.append(g_pending_utf8_tail);
    bytes.append(g_pending_chunk);
    auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
    *emitted = !decoded.utf16.empty();
    jstring result = *emitted ? utf16_to_jstring(env, decoded.utf16) : nullptr;
    if (*emitted && (result == nullptr || env->ExceptionCheck())) {
        // Keep both byte buffers intact so a failed Java-string allocation does
        // not silently consume model output.
        return result;
    }
    g_pending_utf8_tail = std::move(decoded.incomplete_tail);
    g_pending_chunk.clear();
    return result;
}

using mca::mnn::escape_json;
using mca::mnn::sanitize_utf8_for_diagnostics;

using mca::mnn::parent_dir;
using mca::mnn::file_exists;
using mca::mnn::nonempty_regular_file_exists;
using mca::mnn::remove_existing_output_file;
using mca::mnn::directory_exists;
using mca::mnn::validate_output_path_for_write;

bool validate_finite_float_vector(
        const std::vector<float>& values,
        const std::string& label,
        std::string& error) {
    for (size_t index = 0; index < values.size(); ++index) {
        if (!std::isfinite(values[index])) {
            std::ostringstream out;
            out << label << " contains a non-finite value at index " << index << ".";
            error = out.str();
            return false;
        }
    }
    return true;
}

using mca::mnn::MnnPublishedImageEvidence;
using mca::mnn::normalize_mnn_model_path;
using mca::mnn::join_path;
using mca::mnn::resolve_mnn_config_path;
using mca::mnn::read_json_file_or_empty;
using mca::mnn::read_mnn_config_object;
using mca::mnn::merge_mnn_config;
using mca::mnn::relocate_mnn_component_declarations;
using mca::mnn::read_relocated_mnn_configs;
using mca::mnn::mnn_configured_component_path;
using mca::mnn::require_mnn_component;
using mca::mnn::mnn_json_exact_int64;
using mca::mnn::parse_mnn_tie_embedding_descriptor;
using mca::mnn::mnn_chat_bundle_preflight_message;
using mca::mnn::collect_mnn_published_image_evidence;
using mca::mnn::collect_mnn_visual_path_declarations;
using mca::mnn::mnn_visual_path_declarations;
using mca::mnn::select_mnn_visual_model_path;
using mca::mnn::is_mnn_stop_marker_key;
using mca::mnn::append_mnn_stop_markers_from_json;
using mca::mnn::mnn_model_stop_markers;

// The app links one coherent MNN 3.6 runtime.  Gemma 4's public MNN 3.5
// visual graph crashes in MNN::Transformer::Omni::load before the Java layer
// can recover, so keep a native preflight in addition to the repository gate.
// Text-only isolation packages are unaffected because they do not enable or
// contain a visual model.
std::string incompatible_legacy_mnn_visual_graph_message(const std::string& requestedPath) {
    return mca::mnn::incompatible_legacy_mnn_visual_graph_message(requestedPath);
}

using mca::mnn::write_json_file;
using mca::mnn::has_mnn_jinja_chat_template;
using mca::mnn::inject_legacy_mnn_chat_template;

std::string prepare_mnn_runtime_config(const std::string& requestedPath) {
    return mca::mnn::prepare_mnn_runtime_config(
            requestedPath,
            &g_visual_model_path,
            &g_vision_ready);
}

void set_error(const std::string& message) {
    g_last_error = message;
    if (!message.empty()) {
        __android_log_print(ANDROID_LOG_WARN, "mca_mnn_native", "%s", message.c_str());
    }
}

int opt_int(const json& root, const char* name, int fallback) {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        if (it->is_number_integer()) return it->get<int>();
        if (it->is_number_float()) {
            const double value = it->get<double>();
            if (!std::isfinite(value) || value < std::numeric_limits<int>::min() ||
                value > std::numeric_limits<int>::max()) {
                return fallback;
            }
            return static_cast<int>(value);
        }
        if (it->is_string()) {
            return std::stoi(it->get<std::string>());
        }
    } catch (...) {
        return fallback;
    }
    return fallback;
}

bool opt_bool(const json& root, const char* name, bool fallback) {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        if (it->is_boolean()) return it->get<bool>();
        if (it->is_number_integer()) return it->get<int>() != 0;
        if (it->is_string()) {
            auto value = it->get<std::string>();
            std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            if (value == "true" || value == "1" || value == "yes") return true;
            if (value == "false" || value == "0" || value == "no") return false;
        }
    } catch (...) {
        return fallback;
    }
    return fallback;
}

double opt_double(const json& root, const char* name, double fallback) {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        double value = fallback;
        if (it->is_number()) {
            value = it->get<double>();
        } else if (it->is_string()) {
            value = std::stod(it->get<std::string>());
        } else {
            return fallback;
        }
        return std::isfinite(value) ? value : fallback;
    } catch (...) {
        return fallback;
    }
    return fallback;
}

std::string opt_string(const json& root, const char* name, const std::string& fallback = "") {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        if (it->is_string()) return it->get<std::string>();
        if (it->is_number_integer()) return std::to_string(it->get<int64_t>());
        if (it->is_number_float()) return std::to_string(it->get<double>());
        if (it->is_boolean()) return it->get<bool>() ? "true" : "false";
    } catch (...) {
        return fallback;
    }
    return fallback;
}

bool optional_json_string(
        const json& root,
        const char *name,
        const std::string& fallback,
        std::string& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (!it->is_string()) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be a string.";
        return false;
    }
    value = it->get<std::string>();
    return true;
}

bool optional_json_int(
        const json& root,
        const char *name,
        int fallback,
        int& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (it->is_number_unsigned()) {
        const auto raw = it->get<uint64_t>();
        if (raw > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
            error = std::string("MNN-Diffusion parameter '") + name + "' is outside the supported integer range.";
            return false;
        }
        value = static_cast<int>(raw);
        return true;
    }
    if (it->is_number_integer()) {
        const auto raw = it->get<int64_t>();
        if (raw < static_cast<int64_t>(std::numeric_limits<int>::min()) ||
            raw > static_cast<int64_t>(std::numeric_limits<int>::max())) {
            error = std::string("MNN-Diffusion parameter '") + name + "' is outside the supported integer range.";
            return false;
        }
        value = static_cast<int>(raw);
        return true;
    }
    error = std::string("MNN-Diffusion parameter '") + name + "' must be an integer.";
    return false;
}

bool optional_json_double(
        const json& root,
        const char *name,
        double fallback,
        double& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (!it->is_number()) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be a number.";
        return false;
    }
    value = it->get<double>();
    if (!std::isfinite(value)) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be finite.";
        return false;
    }
    return true;
}

bool optional_json_bool(
        const json& root,
        const char *name,
        bool fallback,
        bool& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (!it->is_boolean()) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be a boolean.";
        return false;
    }
    value = it->get<bool>();
    return true;
}

void merge_json_object(json& target, const json& override) {
    if (!override.is_object()) return;
    for (auto it = override.begin(); it != override.end(); ++it) {
        if (it.value().is_object() && target.contains(it.key()) && target[it.key()].is_object()) {
            merge_json_object(target[it.key()], it.value());
        } else {
            target[it.key()] = it.value();
        }
    }
}

json advanced_json_from_params(const json& params) {
    try {
        if (!params.is_object()) return json::object();
        auto it = params.find("advanced_json");
        if (it == params.end() || it->is_null()) return json::object();
        if (it->is_object()) return *it;
        if (it->is_string()) {
            auto parsed = json::parse(it->get<std::string>(), nullptr, false);
            return parsed.is_object() ? parsed : json::object();
        }
    } catch (...) {
        return json::object();
    }
    return json::object();
}

void configure_mnn_debug_trace_locked(const json& params) {
    // This is intentionally opt-in through advanced_json rather than a public
    // generation setting. Debug smoke can capture enough raw bytes to classify
    // immediate EOS, template continuation, malformed output, or no emission;
    // normal UI/API requests retain no raw model text in runtime statistics.
    const auto advanced = advanced_json_from_params(params);
    const auto enabled = advanced.find("mca_debug_trace");
    g_mnn_debug_trace_enabled = enabled != advanced.end() &&
            enabled->is_boolean() && enabled->get<bool>();
    g_mnn_debug_trace_truncated = false;
    g_mnn_debug_raw_output.clear();
    g_mnn_debug_token_ids_truncated = false;
    g_mnn_debug_generated_token_ids.clear();
    g_mnn_debug_prompt_truncated = false;
    g_mnn_debug_prompt.clear();
}

std::string mnn_debug_raw_output_hex() {
    return mca::mnn::raw_bytes_to_hex(g_mnn_debug_raw_output);
}

std::string mnn_debug_generated_token_ids_json() {
    return mca::mnn::token_ids_to_json(g_mnn_debug_generated_token_ids);
}

using mca::mnn::MnnChatImageInput;
using mca::mnn::image_url_from_content_part;
using mca::mnn::text_from_content;

void reset_generation_state_locked() {
    g_generation_active = false;
    g_stop_requested = false;
    g_stop_signal.store(false, std::memory_order_release);
    g_pending_chunk.clear();
    g_pending_utf8_tail.clear();
    g_stream_protocol_filter.reset(g_active_stop_markers);
    g_generated_steps = 0;
    g_generation_started_at_ms = 0;
    g_first_chunk_at_ms = 0;
    g_eop_seen = false;
    g_streamed_bytes = 0;
    g_generation_stop_reason = "idle";
}

void set_mnn_diffusion_progress_locked(
        const std::string& phase,
        const std::string& message,
        int step,
        int steps,
        int width,
        int height,
        int threads) {
    g_mnn_diffusion_phase = phase;
    g_mnn_diffusion_message = message;
    g_mnn_diffusion_step = std::max(0, step);
    g_mnn_diffusion_steps = std::max(0, steps);
    g_mnn_diffusion_width = width;
    g_mnn_diffusion_height = height;
    g_mnn_diffusion_threads = threads;
    const auto elapsed_ms = g_mnn_diffusion_started_at_ms > 0 ? now_ms() - g_mnn_diffusion_started_at_ms : 0;
    if (g_mnn_diffusion_step > 0 && elapsed_ms > 0) {
        g_mnn_diffusion_seconds_per_step =
                static_cast<double>(elapsed_ms) / 1000.0 / static_cast<double>(g_mnn_diffusion_step);
    }
}

json mnn_diffusion_progress_json_locked() {
    const auto elapsed_ms = g_mnn_diffusion_started_at_ms > 0
            ? (g_mnn_diffusion_finished_at_ms > 0 ? g_mnn_diffusion_finished_at_ms : now_ms()) - g_mnn_diffusion_started_at_ms
            : 0;
    json effective_threads = nullptr;
    if (g_mnn_diffusion_runner == "direct") {
        effective_threads = g_mnn_diffusion_backend == "opencl" ? 1 : g_mnn_diffusion_threads;
    }
    return json({
        {"phase", g_mnn_diffusion_phase},
        {"message", g_mnn_diffusion_message},
        {"step", g_mnn_diffusion_step},
        {"steps", g_mnn_diffusion_steps},
        {"elapsedMs", elapsed_ms},
        {"secondsPerStep", g_mnn_diffusion_seconds_per_step},
        {"threads", g_mnn_diffusion_threads},
        {"requestedThreads", g_mnn_diffusion_threads},
        {"effectiveThreads", effective_threads},
        {"runner", g_mnn_diffusion_runner},
        {"width", g_mnn_diffusion_width},
        {"height", g_mnn_diffusion_height},
        {"cancelRequested", g_mnn_diffusion_cancel_requested},
        {"nativeGenerationSequence", g_mnn_diffusion_generation_sequence},
        {"nativeStartedAtMs", g_mnn_diffusion_started_at_ms}
    });
}

using mca::mnn::normalize_mnn_diffusion_identifier;
using mca::mnn::canonical_mnn_diffusion_backend;

bool validate_mnn_diffusion_runtime_options(
        const std::string& requested_backend,
        int threads,
        std::string& canonical_backend,
        std::string& error) {
    if (!canonical_mnn_diffusion_backend(requested_backend, canonical_backend, error)) {
        return false;
    }
    if (threads <= 0 || threads > 64) {
        error = "MNN-Diffusion threads must be between 1 and 64.";
        return false;
    }
    return true;
}

using mca::mnn::is_blank_text;

bool is_sana_family(const std::string& family) {
    const auto normalized = normalize_mnn_diffusion_identifier(family);
    return normalized == "sana" || normalized == "sana_diffusion";
}

bool is_sd15_family(const std::string& family) {
    const auto normalized = normalize_mnn_diffusion_identifier(family);
    return normalized == "sd15" || normalized == "sd1.5" ||
            normalized == "stable_diffusion_1_5" ||
            normalized == "stable_diffusion_1.5" ||
            normalized == "stable-diffusion-1.5";
}

bool resolve_mnn_diffusion_runner(
        bool sana_family,
        const std::string& requested_runner,
        std::string& runner,
        std::string& warning,
        std::string& error) {
    const auto normalized = normalize_mnn_diffusion_identifier(requested_runner);
    warning.clear();
    if (sana_family) {
        if (normalized.empty() || normalized == "sana" || normalized == "sana_varp") {
            runner = "sana_varp";
            return true;
        }
        if (normalized == "module") {
            runner = "sana_varp";
            warning = "runner=module is accepted as a legacy alias for Sana's sana_varp runner.";
            return true;
        }
        error = "Unsupported MNN Sana runner '" + requested_runner +
                "'. Supported values are sana_varp, sana, and the legacy module alias.";
        return false;
    }
    if (normalized.empty() || normalized == "module") {
        runner = "module";
        return true;
    }
    if (normalized == "direct") {
        runner = "direct";
        return true;
    }
    error = "Unsupported MNN Stable Diffusion runner '" + requested_runner +
            "'. Supported values are module and direct.";
    return false;
}

using mca::mnn::MnnVaeScalingLocation;
using mca::mnn::MnnSchedulerExecutionContract;
using mca::mnn::MnnTextEncoderAssetRequest;
using mca::mnn::MnnSemanticExecutionContract;
using mca::mnn::mnn_contract_uses_community_clip;
using mca::mnn::MnnTextEncoderAssetEvidence;
using mca::mnn::MnnNativeExecutionEvidence;
using mca::mnn::mnn_consumed_text_encoder_closure_sha256;
using mca::mnn::mnn_scheduler_wire_name;
using mca::mnn::mnn_scheduler_product_name;
using mca::mnn::mnn_prediction_wire_name;
using mca::mnn::mnn_vae_scaling_wire_name;
using mca::mnn::mnn_native_effective_json;
using mca::mnn::mnn_sana_native_effective_json;

std::function<bool()> mnn_asset_cancel_callback() {
    return [] {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        return g_mnn_diffusion_cancel_requested;
    };
}

bool prepare_mnn_execution_assets(
        const std::string& textualInversionJson,
        const std::string& runtime,
        mca::image::execution_assets::Binding& binding,
        std::string& error) {
    const auto parsed = json::parse(textualInversionJson, nullptr, false);
    if (!parsed.is_object() ||
        !mca::image::execution_assets::parse(parsed, runtime, binding, error) ||
        !mca::image::execution_assets::verify_initial(
                binding, mnn_asset_cancel_callback(), error)) {
        return false;
    }
    return true;
}

bool finalize_mnn_execution_assets(
        mca::image::execution_assets::Binding& binding,
        std::string& error) {
    return mca::image::execution_assets::verify_final(
            binding, mnn_asset_cancel_callback(), error);
}

bool mnn_selection_matches_execution_assets(
        const mca::image::textual_inversion::Selection& selection,
        const mca::image::execution_assets::Binding& binding,
        std::string& error) {
    const bool matches = std::all_of(
        selection.artifacts.begin(),
        selection.artifacts.end(),
        [&binding](const auto& artifact) {
            return artifact.tokenizer_fingerprint == binding.composite_sha256;
        });
    if (!matches) {
        error = "textual inversion artifact binding does not match the verified consumer assets";
    }
    return matches;
}

bool mnn_execution_assets_match(
        const mca::image::execution_assets::Binding& binding,
        const std::vector<std::string>& requiredPaths,
        std::string& error) {
    std::set<std::string> canonicalRequiredPaths;
    for (const auto& path : requiredPaths) {
        char canonical[PATH_MAX] = {};
        struct stat pathStat {};
        if (realpath(path.c_str(), canonical) == nullptr ||
            stat(canonical, &pathStat) != 0 || !S_ISREG(pathStat.st_mode)) {
            error = "native prompt-conditioning execution asset is missing or non-canonical";
            return false;
        }
        canonicalRequiredPaths.emplace(canonical);
    }
    std::set<std::string> describedPaths;
    for (const auto& asset : binding.assets) {
        describedPaths.emplace(asset.path);
    }
    if (describedPaths != canonicalRequiredPaths) {
        error = "execution asset descriptors do not exactly match native prompt consumers";
        return false;
    }
    return true;
}

json mnn_execution_asset_failure(const std::string& error, const char* format = nullptr) {
    if (error == "cancelled") {
        json cancelled = {{"ok", false}, {"cancelled", true}, {"error", "cancelled"}};
        if (format != nullptr) cancelled["format"] = format;
        return cancelled;
    }
    json failure = {
        {"ok", false},
        {"errorCode", "TEXTUAL_INVERSION_EXECUTION_ASSET_MISMATCH"},
        {"error", error}
    };
    if (format != nullptr) failure["format"] = format;
    return failure;
}

struct MnnOpenedTextEncoderAsset {
    int fd = -1;
    std::string canonical_path;
    std::string sha256;
    uint64_t size_bytes = 0U;
    mca::image::execution_assets::Identity identity;

    ~MnnOpenedTextEncoderAsset() {
        reset();
    }

    MnnOpenedTextEncoderAsset() = default;
    MnnOpenedTextEncoderAsset(const MnnOpenedTextEncoderAsset&) = delete;
    MnnOpenedTextEncoderAsset& operator=(const MnnOpenedTextEncoderAsset&) = delete;

    void reset() {
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
        canonical_path.clear();
        sha256.clear();
        size_bytes = 0U;
        identity = {};
    }

    std::string opened_path() const {
        return fd >= 0 ? "/proc/self/fd/" + std::to_string(fd) : std::string();
    }
};

struct MnnOpenedTextEncoderBinding {
    bool active = false;
    MnnOpenedTextEncoderAsset graph;
    MnnOpenedTextEncoderAsset weight;
};

bool mnn_text_encoder_path_is_inside_bundle(
        const std::string& bundleRoot,
        const std::string& path) {
    if (bundleRoot == "/") return path.size() > 1U && path.front() == '/';
    return path.size() > bundleRoot.size() &&
            path.compare(0, bundleRoot.size(), bundleRoot) == 0 &&
            path[bundleRoot.size()] == '/';
}

bool hash_opened_mnn_text_encoder_asset(
        MnnOpenedTextEncoderAsset& asset,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* label,
        std::string& error) {
    struct stat descriptorBefore {};
    struct stat pathBefore {};
    if (asset.fd < 0 || asset.canonical_path.empty() ||
        ::fstat(asset.fd, &descriptorBefore) != 0 ||
        ::lstat(asset.canonical_path.c_str(), &pathBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode) || !S_ISREG(pathBefore.st_mode) ||
        !mca::image::execution_assets::same_identity(
            asset.identity,
            mca::image::execution_assets::identity_of(descriptorBefore)) ||
        !mca::image::execution_assets::same_identity(
            asset.identity,
            mca::image::execution_assets::identity_of(pathBefore))) {
        error = std::string(label) + " identity changed before native MNN consumption.";
        return false;
    }
    if (asset.identity.size != expectedSizeBytes ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string(label) + " size or seek position is invalid.";
        return false;
    }
    mca::image::execution_assets::Sha256 digest;
    std::array<uint8_t, 64U * 1024U> bytes{};
    uint64_t readBytes = 0U;
    const auto cancelled = mnn_asset_cancel_callback();
    while (true) {
        if (cancelled()) {
            error = "cancelled";
            return false;
        }
        const ssize_t count = ::read(asset.fd, bytes.data(), bytes.size());
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            error = std::string(label) + " could not be streamed for SHA-256 verification.";
            return false;
        }
        readBytes += static_cast<uint64_t>(count);
        if (readBytes > expectedSizeBytes) {
            error = std::string(label) + " grew while native MNN verification was in progress.";
            return false;
        }
        digest.update(bytes.data(), static_cast<size_t>(count));
    }
    struct stat descriptorAfter {};
    struct stat pathAfter {};
    if (::fstat(asset.fd, &descriptorAfter) != 0 ||
        ::lstat(asset.canonical_path.c_str(), &pathAfter) != 0 ||
        !mca::image::execution_assets::same_identity(
            asset.identity,
            mca::image::execution_assets::identity_of(descriptorAfter)) ||
        !mca::image::execution_assets::same_identity(
            asset.identity,
            mca::image::execution_assets::identity_of(pathAfter)) ||
        readBytes != expectedSizeBytes) {
        error = std::string(label) + " changed while native MNN verification was in progress.";
        return false;
    }
    const std::string actualSha256 = digest.finish_hex();
    if (actualSha256 != expectedSha256 ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string(label) + " bytes do not match the profile-declared SHA-256.";
        return false;
    }
    asset.sha256 = actualSha256;
    asset.size_bytes = readBytes;
    return true;
}

bool open_mnn_text_encoder_asset(
        const std::string& canonicalBundleRoot,
        const std::string& requestedPath,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* label,
        MnnOpenedTextEncoderAsset& asset,
        std::string& error) {
    asset.reset();
    char canonical[PATH_MAX] = {};
    if (requestedPath.empty() || realpath(requestedPath.c_str(), canonical) == nullptr ||
        requestedPath != canonical ||
        !mnn_text_encoder_path_is_inside_bundle(canonicalBundleRoot, canonical)) {
        error = std::string(label) + " path must be canonical and stay inside the selected bundle.";
        return false;
    }
    struct stat pathBefore {};
    if (::lstat(canonical, &pathBefore) != 0 || !S_ISREG(pathBefore.st_mode)) {
        error = std::string(label) + " is not a regular non-symlink file.";
        return false;
    }
    asset.fd = ::open(canonical, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    struct stat descriptorBefore {};
    if (asset.fd < 0 || ::fstat(asset.fd, &descriptorBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode)) {
        asset.reset();
        error = std::string(label) + " could not be opened through a regular-file descriptor.";
        return false;
    }
    asset.canonical_path = canonical;
    asset.identity = mca::image::execution_assets::identity_of(descriptorBefore);
    if (!mca::image::execution_assets::same_identity(
            asset.identity,
            mca::image::execution_assets::identity_of(pathBefore)) ||
        asset.identity.size != expectedSizeBytes ||
        !hash_opened_mnn_text_encoder_asset(
            asset, expectedSha256, expectedSizeBytes, label, error)) {
        asset.reset();
        return false;
    }
    return true;
}


bool open_mnn_text_encoder_binding(
        const std::string& root,
        const MnnTextEncoderAssetRequest& request,
        MnnOpenedTextEncoderBinding& binding,
        MnnTextEncoderAssetEvidence& evidence,
        std::string& error) {
    if (!request.declared) return true;
    char canonicalRoot[PATH_MAX] = {};
    struct stat rootStat {};
    if (root.empty() || realpath(root.c_str(), canonicalRoot) == nullptr ||
        ::stat(canonicalRoot, &rootStat) != 0 || !S_ISDIR(rootStat.st_mode)) {
        error = "MNN text encoder evidence has no canonical bundle root.";
        return false;
    }
    if (!open_mnn_text_encoder_asset(
            canonicalRoot,
            request.path,
            request.sha256,
            request.size_bytes,
            "MNN text encoder graph",
            binding.graph,
            error)) {
        return false;
    }
    if (request.has_weight) {
        if (request.weight_path != request.path + ".weight" ||
            !open_mnn_text_encoder_asset(
                canonicalRoot,
                request.weight_path,
                request.weight_sha256,
                request.weight_size_bytes,
                "MNN text encoder weight",
                binding.weight,
                error)) {
            if (error.empty()) {
                error = "The declared MNN text encoder weight must match the graph's .weight path.";
            }
            return false;
        }
    } else {
        struct stat unexpectedWeight {};
        const std::string defaultWeightPath = binding.graph.canonical_path + ".weight";
        if (::lstat(defaultWeightPath.c_str(), &unexpectedWeight) == 0) {
            error = "The declared MNN text encoder has a .weight sidecar that is not pinned by nativeTextEncoderEvidence.";
            return false;
        }
        if (errno != ENOENT) {
            error = "The declared MNN text encoder .weight sidecar could not be inspected."
                " It must be explicitly pinned before native execution.";
            return false;
        }
    }
    binding.active = true;
    evidence.consumed = true;
    evidence.path = binding.graph.canonical_path;
    evidence.sha256 = binding.graph.sha256;
    evidence.size_bytes = binding.graph.size_bytes;
    evidence.expected_sha256 = request.sha256;
    evidence.expected_size_bytes = request.size_bytes;
    if (request.has_weight) {
        evidence.has_weight = true;
        evidence.weight_path = binding.weight.canonical_path;
        evidence.weight_sha256 = binding.weight.sha256;
        evidence.weight_size_bytes = binding.weight.size_bytes;
        evidence.expected_weight_sha256 = request.weight_sha256;
        evidence.expected_weight_size_bytes = request.weight_size_bytes;
    }
    return true;
}

bool verify_mnn_text_encoder_binding(
        const MnnTextEncoderAssetRequest& request,
        MnnOpenedTextEncoderBinding& binding,
        std::string& error) {
    if (!binding.active) return true;
    if (!hash_opened_mnn_text_encoder_asset(
            binding.graph,
            request.sha256,
            request.size_bytes,
            "MNN text encoder graph",
            error)) {
        return false;
    }
    return !request.has_weight || hash_opened_mnn_text_encoder_asset(
            binding.weight,
            request.weight_sha256,
            request.weight_size_bytes,
            "MNN text encoder weight",
            error);
}

json mnn_text_encoder_asset_failure(const std::string& error) {
    if (error == "cancelled") {
        return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}});
    }
    return json({
        {"ok", false},
        {"errorCode", "TEXT_ENCODER_ASSET_MISMATCH"},
        {"error", error}
    });
}

using mca::mnn::append_mnn_u32_little_endian;
using mca::mnn::append_mnn_u64_little_endian;
using mca::mnn::mnn_token_id_fingerprint;
using mca::mnn::normalize_mnn_contract_enum;
using mca::mnn::mnn_contract_value;
using mca::mnn::mnn_contract_string;
using mca::mnn::mnn_contract_integer;
using mca::mnn::parse_mnn_text_encoder_asset_descriptor;
using mca::mnn::parse_mnn_text_encoder_asset_request;
using mca::mnn::parse_mnn_language_proof_sha256;
using mca::mnn::mnn_prompt_contains_non_ascii;
using mca::mnn::mnn_contract_number;
using mca::mnn::mnn_contract_boolean;
using mca::mnn::parse_mnn_scheduler_execution_contract;
using mca::mnn::parse_mnn_semantic_execution_contract;



std::string mnn_diffusion_missing_component(
        const std::string& root,
        const std::string& family,
        const MnnTextEncoderAssetRequest* declaredTextEncoder = nullptr) {
    if (root.empty() || !directory_exists(root)) {
        return is_sana_family(family)
                ? "MNN Sana requires a complete resource directory."
                : "MNN-Diffusion requires a complete Stable Diffusion 1.5 resource directory.";
    }
    if (is_sana_family(family)) {
        std::vector<std::string> missing;
        if (!file_exists(root + "/connector.mnn")) missing.emplace_back("connector.mnn");
        if (!file_exists(root + "/projector.mnn")) missing.emplace_back("projector.mnn");
        if (!file_exists(root + "/transformer.mnn")) missing.emplace_back("transformer.mnn");
        if (!file_exists(root + "/vae_decoder.mnn")) missing.emplace_back("vae_decoder.mnn");
        if (!file_exists(root + "/llm/config.json")) missing.emplace_back("llm/config.json");
        if (!file_exists(root + "/llm/meta_queries.mnn")) missing.emplace_back("llm/meta_queries.mnn");
        if (missing.empty()) return "";
        std::ostringstream out;
        out << "MNN Sana bundle is incomplete: ";
        for (size_t i = 0; i < missing.size(); ++i) {
            if (i > 0) out << ", ";
            out << missing[i];
        }
        return out.str();
    }
    std::vector<std::string> missing;
    if (declaredTextEncoder != nullptr && declaredTextEncoder->declared) {
        if (!file_exists(declaredTextEncoder->path)) {
            missing.emplace_back("nativeTextEncoderEvidence.path");
        }
        if (declaredTextEncoder->has_weight && !file_exists(declaredTextEncoder->weight_path)) {
            missing.emplace_back("nativeTextEncoderEvidence.auxiliaryAssets[0]");
        }
    } else {
        const bool hasCommunityClip =
                file_exists(root + "/clip_v2.mnn") &&
                file_exists(root + "/tokenizer.json") &&
                file_exists(root + "/token_emb.bin") &&
                file_exists(root + "/pos_emb.bin");
        if (!hasCommunityClip) {
            if (!file_exists(root + "/text_encoder.mnn")) missing.emplace_back("text_encoder.mnn");
            if (!file_exists(root + "/text_encoder.mnn.weight")) {
                missing.emplace_back("text_encoder.mnn.weight");
            }
        }
    }
    if (!file_exists(root + "/unet.mnn")) missing.emplace_back("unet.mnn");
    if (!file_exists(root + "/unet.mnn.weight")) missing.emplace_back("unet.mnn.weight");
    if (!file_exists(root + "/vae_decoder.mnn")) missing.emplace_back("vae_decoder.mnn");
    if (!file_exists(root + "/vae_decoder.mnn.weight")) missing.emplace_back("vae_decoder.mnn.weight");
    if (missing.empty()) return "";
    std::ostringstream out;
    out << "MNN-Diffusion Stable Diffusion 1.5 bundle is incomplete: ";
    for (size_t i = 0; i < missing.size(); ++i) {
        if (i > 0) out << ", ";
        out << missing[i];
    }
    return out.str();
}

#if MCA_WITH_MNN_DIFFUSION
json tensor_metadata_json(const MNN::Tensor* tensor) {
    if (tensor == nullptr) {
        return json({{"available", false}});
    }
    json shape = json::array();
    for (int i = 0; i < tensor->dimensions(); ++i) {
        shape.push_back(tensor->length(i));
    }
    const auto type = tensor->getType();
    return json({
        {"available", true},
        {"shape", shape},
        {"dimensionType", static_cast<int>(tensor->getDimensionType())},
        {"typeCode", type.code},
        {"typeBits", type.bits},
        {"typeLanes", type.lanes}
    });
}

json inspect_mnn_graph(const std::string& path) {
    if (!file_exists(path)) {
        return json({{"ok", false}, {"error", "Model file does not exist."}});
    }
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        return json({{"ok", false}, {"error", "Interpreter::createFromFile failed."}});
    }
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 1;
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        return json({{"ok", false}, {"error", "Interpreter::createSession failed."}});
    }
    json inputs = json::array();
    for (const auto& entry : interpreter->getSessionInputAll(session)) {
        inputs.push_back(json({
            {"name", entry.first},
            {"tensor", tensor_metadata_json(entry.second)}
        }));
    }
    json outputs = json::array();
    for (const auto& entry : interpreter->getSessionOutputAll(session)) {
        outputs.push_back(json({
            {"name", entry.first},
            {"tensor", tensor_metadata_json(entry.second)}
        }));
    }
    interpreter->releaseSession(session);
    return json({
        {"ok", true},
        {"path", path},
        {"inputs", inputs},
        {"outputs", outputs}
    });
}

json inspect_mnn_diffusion_bundle(const std::string& root) {
    return json({
        {"textEncoder", inspect_mnn_graph(root + "/text_encoder.mnn")},
        {"unet", inspect_mnn_graph(root + "/unet.mnn")},
        {"vaeDecoder", inspect_mnn_graph(root + "/vae_decoder.mnn")}
    });
}

template <typename T>
bool fill_input_tensor(MNN::Tensor* tensor, T value) {
    if (tensor == nullptr) return false;
    auto* direct = tensor->host<T>();
    if (direct != nullptr) {
        std::fill(direct, direct + tensor->elementSize(), value);
        return true;
    }
    void* mapped = tensor->map(MNN::Tensor::MAP_TENSOR_WRITE, tensor->getDimensionType());
    if (mapped != nullptr) {
        auto* ptr = static_cast<T*>(mapped);
        std::fill(ptr, ptr + tensor->elementSize(), value);
        tensor->unmap(MNN::Tensor::MAP_TENSOR_WRITE, tensor->getDimensionType(), mapped);
        return true;
    }
    MNN::Tensor hostTensor(tensor, tensor->getDimensionType());
    auto* ptr = hostTensor.host<T>();
    if (ptr == nullptr) return false;
    std::fill(ptr, ptr + hostTensor.elementSize(), value);
    return tensor->copyFromHostTensor(&hostTensor);
}

template <typename T>
bool tensor_has_host(MNN::Tensor* tensor) {
    return tensor != nullptr && tensor->host<T>() != nullptr;
}

std::string mnn_error_code_name(MNN::ErrorCode code) {
    switch (code) {
        case MNN::NO_ERROR:
            return "NO_ERROR";
        case MNN::OUT_OF_MEMORY:
            return "OUT_OF_MEMORY";
        case MNN::NOT_SUPPORT:
            return "NOT_SUPPORT";
        case MNN::COMPUTE_SIZE_ERROR:
            return "COMPUTE_SIZE_ERROR";
        case MNN::NO_EXECUTION:
            return "NO_EXECUTION";
        case MNN::INVALID_VALUE:
            return "INVALID_VALUE";
        case MNN::INPUT_DATA_ERROR:
            return "INPUT_DATA_ERROR";
        case MNN::CALL_BACK_STOP:
            return "CALL_BACK_STOP";
        default:
            return "MNN_ERROR_" + std::to_string(static_cast<int>(code));
    }
}

// The SD1.5 MNN bundle currently shipped by the app contains fused transformer
// operators (GroupNorm/FmhaV2/SplitGeLU) that are registered by the OpenCL
// backend but not by this MNN CPU build.  A resize failure with NOT_SUPPORT is
// therefore a backend capability mismatch, not a malformed bundle or an
// invalid tensor shape.  Keep this predicate deliberately narrow: it is used
// only for the strict direct SD1.5 path and never admits a device by chipset.
bool mnn_sd15_unet_backend_unsupported(const std::vector<std::string>& errors) {
    return std::any_of(errors.begin(), errors.end(), [](const std::string& value) {
        return value.find("status 2") != std::string::npos ||
                value.find("NOT_SUPPORT") != std::string::npos ||
                value.find("Create execution error : 304") != std::string::npos ||
                value.find("GroupNorm") != std::string::npos;
    });
}

struct UnetSmokeProfile {
    std::string name;
    bool resizeDefer = false;
    bool inputUser = false;
    int batch = 2;
};

void configure_interpreter_profile(MNN::Interpreter* interpreter, const UnetSmokeProfile& profile) {
    if (interpreter == nullptr) return;
    interpreter->setSessionMode(MNN::Interpreter::Session_Debug);
    interpreter->setSessionMode(profile.inputUser
            ? MNN::Interpreter::Session_Input_User
            : MNN::Interpreter::Session_Input_Inside);
    interpreter->setSessionMode(profile.resizeDefer
            ? MNN::Interpreter::Session_Resize_Defer
            : MNN::Interpreter::Session_Resize_Direct);
}

using mca::mnn::copy_vector_to_tensor;

json run_unet_interpreter_smoke_once(
        const std::string& root,
        const std::string& backendMode,
        const UnetSmokeProfile& profile) {
    const auto path = root + "/unet.mnn";
    const auto weightPath = path + ".weight";
    if (!nonempty_regular_file_exists(path)) {
        return json({
            {"ok", false},
            {"errorCode", "MNN_DIFFUSION_BUNDLE_INCOMPLETE"},
            {"error", "unet.mnn is missing, empty, or not a regular file."}
        });
    }
    if (!nonempty_regular_file_exists(weightPath)) {
        return json({
            {"ok", false},
            {"errorCode", "MNN_DIFFUSION_BUNDLE_INCOMPLETE"},
            {"error", "unet.mnn.weight is missing, empty, or not a regular file."}
        });
    }
    const auto started = now_ms();
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_interpreter_start", profile.name.c_str());
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        return json({{"ok", false}, {"error", "Interpreter::createFromFile failed."}});
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_interpreter_completed", profile.name.c_str());
    interpreter->setExternalFile(weightPath.c_str());
    configure_interpreter_profile(interpreter.get(), profile);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    const bool useOpenCl = backendMode == "opencl" || backendMode == "gpu";
    config.type = useOpenCl ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    config.numThread = useOpenCl ? 1 : 4;
    if (useOpenCl) {
        config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    }
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_session_start backend=%s",
                        profile.name.c_str(), useOpenCl ? "opencl" : "cpu");
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        return json({{"ok", false}, {"error", "Interpreter::createSession failed."}});
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_session_completed", profile.name.c_str());
    auto* sample = interpreter->getSessionInput(session, "sample");
    auto* timestep = interpreter->getSessionInput(session, "timestep");
    auto* encoder = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (sample == nullptr || timestep == nullptr || encoder == nullptr) {
        interpreter->releaseSession(session);
        return json({
            {"ok", false},
            {"error", "UNet input tensor not found."},
            {"hasSample", sample != nullptr},
            {"hasTimestep", timestep != nullptr},
            {"hasEncoderHiddenStates", encoder != nullptr}
        });
    }
    // Prefer a batched CFG graph, while also supporting exporters that freeze
    // the leading dimension at one. Production executes the latter twice per
    // scheduler timestep, so one real batch-1 graph run is the matching smoke.
    interpreter->resizeTensor(sample, {profile.batch, 4, 64, 64});
    interpreter->resizeTensor(timestep, {1});
    interpreter->resizeTensor(encoder, {profile.batch, 77, 768});
    int resizeStatusBefore = -1;
    int resizeStatusAfter = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatusBefore);
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] resize_session_start status=%d",
                        profile.name.c_str(), resizeStatusBefore);
    interpreter->resizeSession(session, 1);
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatusAfter);
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] resize_session_completed status=%d",
                        profile.name.c_str(), resizeStatusAfter);
    sample = interpreter->getSessionInput(session, "sample");
    timestep = interpreter->getSessionInput(session, "timestep");
    encoder = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (resizeStatusAfter != 0 || sample == nullptr || timestep == nullptr || encoder == nullptr) {
        const auto inputs = json({
            {"sample", tensor_metadata_json(sample)},
            {"encoderHiddenStates", tensor_metadata_json(encoder)},
            {"timestep", tensor_metadata_json(timestep)}
        });
        interpreter->releaseSession(session);
        return json({
            {"ok", false},
            {"errorCode", "MNN_UNET_RESIZE_FAILED"},
            {"error", "UNet resizeSession failed or invalidated an input tensor."},
            {"backendMode", useOpenCl ? "opencl" : "cpu"},
            {"profile", profile.name},
            {"sessionBatch", profile.batch},
            {"cfgExecutionMode", profile.batch == 2 ? "batched" : "sequential"},
            {"resizeStatusBefore", resizeStatusBefore},
            {"resizeStatusAfter", resizeStatusAfter},
            {"inputs", inputs}
        });
    }

    std::vector<float> sampleBuffer(
            static_cast<size_t>(profile.batch * 4 * 64 * 64), 0.0f);
    std::vector<float> encoderBuffer(
            static_cast<size_t>(profile.batch * 77 * 768), 0.0f);
    std::vector<int> timestepBuffer = {1};
    std::string copyError;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] copy_inputs_start", profile.name.c_str());
    const bool sampleCopied = copy_vector_to_tensor<float>(sample, sampleBuffer, copyError);
    const std::string sampleCopyError = sampleCopied ? "" : copyError;
    copyError.clear();
    const bool encoderCopied = copy_vector_to_tensor<float>(encoder, encoderBuffer, copyError);
    const std::string encoderCopyError = encoderCopied ? "" : copyError;
    copyError.clear();
    const bool timestepCopied = copy_vector_to_tensor<int>(timestep, timestepBuffer, copyError);
    const std::string timestepCopyError = timestepCopied ? "" : copyError;
    json inputJson = {
        {"sample", tensor_metadata_json(sample)},
        {"encoderHiddenStates", tensor_metadata_json(encoder)},
        {"timestep", tensor_metadata_json(timestep)},
        {"sampleHasHost", tensor_has_host<float>(sample)},
        {"encoderHasHost", tensor_has_host<float>(encoder)},
        {"timestepHasHost", tensor_has_host<int>(timestep)},
        {"sampleCopied", sampleCopied},
        {"encoderCopied", encoderCopied},
        {"timestepCopied", timestepCopied},
        {"sampleCopyError", sampleCopyError},
        {"encoderCopyError", encoderCopyError},
        {"timestepCopyError", timestepCopyError},
        {"layout", "NCHW"},
        {"sessionBatch", profile.batch},
        {"logicalCfgBranches", 2},
        {"cfgExecutionMode", profile.batch == 2 ? "batched" : "sequential"}
    };
    if (!sampleCopied || !encoderCopied || !timestepCopied) {
        __android_log_print(ANDROID_LOG_ERROR, "mca_mnn_native",
                            "UNet smoke[%s] copy_inputs_failed sample=%d encoder=%d timestep=%d",
                            profile.name.c_str(), sampleCopied, encoderCopied, timestepCopied);
        interpreter->releaseSession(session);
        return json({
            {"ok", false},
            {"error", "UNet input copy failed."},
            {"backendMode", useOpenCl ? "opencl" : "cpu"},
            {"profile", profile.name},
            {"resizeStatusBefore", resizeStatusBefore},
            {"resizeStatusAfter", resizeStatusAfter},
            {"inputs", inputJson}
        });
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] copy_inputs_completed", profile.name.c_str());
    const auto runStarted = now_ms();
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] run_session_start", profile.name.c_str());
    const auto runCode = interpreter->runSession(session);
    const auto runMs = now_ms() - runStarted;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] run_session_completed code=%d runMs=%lld",
                        profile.name.c_str(), static_cast<int>(runCode),
                        static_cast<long long>(runMs));
    auto* output = interpreter->getSessionOutput(session, "out_sample");
    json outputJson = tensor_metadata_json(output);
    bool outputCopied = false;
    double firstValue = 0.0;
    if (output != nullptr) {
        if (output->host<float>() != nullptr) {
            outputCopied = true;
            firstValue = output->host<float>()[0];
        } else {
            std::unique_ptr<MNN::Tensor> outputHost(MNN::Tensor::createHostTensorFromDevice(output, false));
            outputCopied = outputHost != nullptr && output->copyToHostTensor(outputHost.get());
            if (outputCopied && outputHost->elementSize() > 0 && outputHost->host<float>() != nullptr) {
                firstValue = outputHost->host<float>()[0];
            }
        }
    }
    interpreter->releaseSession(session);
    const bool ok = runCode == MNN::NO_ERROR && output != nullptr && outputCopied;
    return json({
        {"ok", ok},
        {"error", ok ? "" : ("UNet smoke failed: runCode=" + std::to_string(static_cast<int>(runCode)) +
                " (" + mnn_error_code_name(runCode) + "), resizeStatusAfter=" + std::to_string(resizeStatusAfter))},
        {"backendMode", useOpenCl ? "opencl" : "cpu"},
        {"profile", profile.name},
        {"resizeMode", profile.resizeDefer ? "defer" : "direct"},
        {"inputMode", profile.inputUser ? "user" : "inside"},
        {"path", path},
        {"externalWeight", file_exists(weightPath)},
        {"resizeCalled", true},
        {"resizeStatusBefore", resizeStatusBefore},
        {"resizeStatusAfter", resizeStatusAfter},
        {"runCode", static_cast<int>(runCode)},
        {"runCodeName", mnn_error_code_name(runCode)},
        {"elapsedMs", now_ms() - started},
        {"runMs", runMs},
        {"inputs", inputJson},
        {"sampleCopied", sampleCopied},
        {"encoderCopied", encoderCopied},
        {"timestepCopied", timestepCopied},
        {"execution", "runSession"},
        {"output", outputJson},
        {"outputCopied", outputCopied},
        {"firstValue", firstValue}
    });
}

json run_unet_interpreter_smoke(const std::string& root, const std::string& backendMode) {
    std::vector<UnetSmokeProfile> profiles;
    const auto batches = mca::mnn::diffusionBatchCandidates(true);
    profiles.reserve(batches.count);
    for (size_t index = 0; index < batches.count; ++index) {
        const int batch = batches.values[index];
        profiles.push_back({
                batch == 2 ? "defer-inside-batch2" : "defer-inside-batch1",
                true,
                false,
                batch
        });
    }
    json attempts = json::array();
    json best = json::object();
    for (const auto& profile : profiles) {
        auto attempt = run_unet_interpreter_smoke_once(root, backendMode, profile);
        attempts.push_back(attempt);
        if (attempt.value("ok", false)) {
            attempt["attempts"] = attempts;
            return attempt;
        }
        // If every candidate fails, surface the final batch-1 compatibility
        // attempt while retaining all earlier attempts for diagnosis.
        best = attempt;
    }
    if (best.empty()) {
        best = json({{"ok", false}, {"error", "UNet smoke did not run any profile."}});
    }
    best["attempts"] = attempts;
    return best;
}

using mca::mnn::FloatTensorData;
using mca::mnn::tensor_shape_string;
using mca::mnn::validate_float_tensor_contract;
using mca::mnn::mnn_tensor_shape;
using mca::mnn::tensor_element_count;
using mca::mnn::nchw_to_nhwc;
using mca::mnn::nhwc_to_nchw;
using mca::mnn::configure_mnn_session;
using mca::mnn::configure_direct_interpreter;
using mca::mnn::copy_vector_to_tensor;

float fp16_to_float(uint16_t value);
float bf16_to_float(uint16_t value);

using mca::mnn::float_vector_stats_json;
using mca::mnn::float_vector_abs_diff_stats_json;
using mca::mnn::raw_tensor_stats_json;

bool assign_tensor_buffer_to_float_vector(
        MNN::Tensor* tensor,
        const void* raw,
        size_t count,
        FloatTensorData& output,
        std::string& error) {
    if (raw == nullptr) {
        error = "Tensor output buffer is null.";
        return false;
    }
    const auto type = tensor->getType();
    output.values.assign(count, 0.0f);
    if (type.code == halide_type_float && type.bits == 32) {
        const auto* ptr = static_cast<const float*>(raw);
        output.values.assign(ptr, ptr + count);
        return true;
    }
    if (type.code == halide_type_float && type.bits == 16) {
        const auto* ptr = static_cast<const uint16_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = fp16_to_float(ptr[i]);
        return true;
    }
    if (type.code == halide_type_uint && type.bits == 16) {
        const auto* ptr = static_cast<const uint16_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = static_cast<float>(ptr[i]);
        return true;
    }
    if (type.code == halide_type_int && type.bits == 32) {
        const auto* ptr = static_cast<const int32_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = static_cast<float>(ptr[i]);
        return true;
    }
    if (type.code == halide_type_uint && type.bits == 32) {
        const auto* ptr = static_cast<const uint32_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = static_cast<float>(ptr[i]);
        return true;
    }
    std::ostringstream out;
    out << "Unsupported output tensor type code=" << static_cast<int>(type.code)
        << " bits=" << static_cast<int>(type.bits)
        << " lanes=" << static_cast<int>(type.lanes) << ".";
    error = out.str();
    return false;
}

json tensor_buffer_debug_json(MNN::Tensor* tensor) {
    json out = {
            {"tensor", tensor_metadata_json(tensor)}
    };
    if (tensor == nullptr) {
        return out;
    }
    const size_t expectedCount = tensor_element_count(mnn_tensor_shape(tensor));
    auto* direct = tensor->host<uint8_t>();
    if (direct != nullptr) {
        json directBytes = json::array();
        const int bytesToShow = std::min(tensor->size(), 16);
        for (int i = 0; i < bytesToShow; ++i) {
            directBytes.push_back(static_cast<int>(direct[i]));
        }
        out["directAvailable"] = true;
        out["directBytes"] = directBytes;
        FloatTensorData decodedDirect;
        std::string directError;
        if (assign_tensor_buffer_to_float_vector(tensor, direct, expectedCount, decodedDirect, directError)) {
            out["directDeclaredStats"] = float_vector_stats_json(decodedDirect.values);
        } else {
            out["directDeclaredError"] = directError;
        }
    } else {
        out["directAvailable"] = false;
    }
    std::unique_ptr<MNN::Tensor> hostTensor(MNN::Tensor::createHostTensorFromDevice(tensor, true));
    if (!hostTensor) {
        out["hostCopyOk"] = false;
        out["hostCopyError"] = "createHostTensorFromDevice returned null.";
        return out;
    }
    out["hostCopyOk"] = true;
    out["hostTensor"] = tensor_metadata_json(hostTensor.get());
    out["hostBytes"] = hostTensor->size();
    const auto* raw = hostTensor->host<uint8_t>();
    if (raw == nullptr) {
        out["hostCopyOk"] = false;
        out["hostCopyError"] = "Host tensor buffer is null.";
        return out;
    }
    json firstBytes = json::array();
    const int bytesToShow = std::min(hostTensor->size(), 16);
    for (int i = 0; i < bytesToShow; ++i) {
        firstBytes.push_back(static_cast<int>(raw[i]));
    }
    out["firstBytes"] = firstBytes;
    const size_t hostExpectedCount = tensor_element_count(mnn_tensor_shape(hostTensor.get()));
    FloatTensorData decoded;
    std::string decodeError;
    if (assign_tensor_buffer_to_float_vector(hostTensor.get(), raw, hostExpectedCount, decoded, decodeError)) {
        out["declaredStats"] = float_vector_stats_json(decoded.values);
    } else {
        out["declaredError"] = decodeError;
    }
    const size_t bytes = static_cast<size_t>(std::max(hostTensor->size(), 0));
    out["asF32"] = raw_tensor_stats_json(bytes / sizeof(float), hostExpectedCount, [&](size_t index) {
        float value = 0.0f;
        std::memcpy(&value, raw + index * sizeof(float), sizeof(float));
        return value;
    });
    out["asF16"] = raw_tensor_stats_json(bytes / sizeof(uint16_t), hostExpectedCount, [&](size_t index) {
        uint16_t value = 0;
        std::memcpy(&value, raw + index * sizeof(uint16_t), sizeof(uint16_t));
        return fp16_to_float(value);
    });
    out["asBF16"] = raw_tensor_stats_json(bytes / sizeof(uint16_t), hostExpectedCount, [&](size_t index) {
        uint16_t value = 0;
        std::memcpy(&value, raw + index * sizeof(uint16_t), sizeof(uint16_t));
        return bf16_to_float(value);
    });
    return out;
}

bool copy_tensor_to_float_vector(MNN::Tensor* tensor, FloatTensorData& output, std::string& error) {
    if (tensor == nullptr) {
        error = "Output tensor is missing.";
        return false;
    }
    output.shape = mnn_tensor_shape(tensor);
    const size_t count = tensor_element_count(output.shape);
    if (count == 0) {
        error = "Output tensor shape is empty.";
        return false;
    }
    // OpenCL map() can expose a packed device buffer rather than logical NCHW
    // elements. Always use MNN's host-copy path so layout conversion is handled
    // by the backend before the scheduler or PNG writer reads the values.
    std::unique_ptr<MNN::Tensor> hostTensor(
            MNN::Tensor::createHostTensorFromDevice(tensor, false));
    if (!hostTensor || hostTensor->host<uint8_t>() == nullptr) {
        error = "Failed to allocate host output tensor.";
        return false;
    }
    if (!tensor->copyToHostTensor(hostTensor.get())) {
        error = "copyToHostTensor failed.";
        return false;
    }
    output.shape = mnn_tensor_shape(hostTensor.get());
    return assign_tensor_buffer_to_float_vector(
            hostTensor.get(),
            hostTensor->host<uint8_t>(),
            count,
            output,
            error);
}

std::vector<unsigned char> read_binary_bytes(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) return {};
    input.seekg(0, std::ios::end);
    const auto size = input.tellg();
    if (size <= 0) return {};
    input.seekg(0, std::ios::beg);
    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    input.read(reinterpret_cast<char*>(bytes.data()), size);
    if (!input.good()) return {};
    return bytes;
}

float fp16_to_float(uint16_t value) {
    const uint32_t sign = (value & 0x8000u) << 16u;
    uint32_t exponent = (value >> 10u) & 0x1fu;
    uint32_t mantissa = value & 0x03ffu;
    uint32_t bits = 0;
    if (exponent == 0) {
        if (mantissa == 0) {
            bits = sign;
        } else {
            exponent = 1;
            while ((mantissa & 0x0400u) == 0) {
                mantissa <<= 1u;
                --exponent;
            }
            mantissa &= 0x03ffu;
            bits = sign | ((exponent + 112u) << 23u) | (mantissa << 13u);
        }
    } else if (exponent == 31u) {
        bits = sign | 0x7f800000u | (mantissa << 13u);
    } else {
        bits = sign | ((exponent + 112u) << 23u) | (mantissa << 13u);
    }
    float out = 0.0f;
    std::memcpy(&out, &bits, sizeof(float));
    return out;
}

float bf16_to_float(uint16_t value) {
    uint32_t bits = static_cast<uint32_t>(value) << 16u;
    float out = 0.0f;
    std::memcpy(&out, &bits, sizeof(float));
    return out;
}

using mca::mnn::append_utf8_codepoint;
using mca::mnn::build_clip_byte_encoder;
using mca::mnn::clip_pretokenize;
using mca::mnn::ClipBpeTokenizer;

constexpr size_t kCommunityClipFp16TokenEmbeddingBytes = 75'890'688U;
constexpr size_t kCommunityClipFp32TokenEmbeddingBytes = 151'781'376U;
static_assert(
        kCommunityClipFp16TokenEmbeddingBytes == 49'408U * 768U * sizeof(uint16_t),
        "SD1.5 FP16 token embedding size must match the CLIP vocabulary contract.");
static_assert(
        kCommunityClipFp32TokenEmbeddingBytes == 49'408U * 768U * sizeof(float),
        "SD1.5 FP32 token embedding size must match the CLIP vocabulary contract.");

bool inspect_community_token_embedding_file(
        const std::string& root,
        size_t& tokenBytes,
        std::string& dataType,
        std::string& error) {
    const auto tokenPath = root + "/token_emb.bin";
    struct stat tokenStat {};
    if (stat(tokenPath.c_str(), &tokenStat) != 0 ||
        !S_ISREG(tokenStat.st_mode) || tokenStat.st_size < 0) {
        error = "token_emb.bin is missing or is not a regular file.";
        return false;
    }
    tokenBytes = static_cast<size_t>(tokenStat.st_size);
    if (tokenBytes == kCommunityClipFp16TokenEmbeddingBytes) {
        dataType = "FP16";
        return true;
    }
    if (tokenBytes == kCommunityClipFp32TokenEmbeddingBytes) {
        dataType = "FP32";
        return true;
    }
    std::ostringstream out;
    out << "PACKAGE_FORMAT_INVALID: token_emb.bin has " << tokenBytes
        << " bytes; expected exactly " << kCommunityClipFp16TokenEmbeddingBytes
        << " for FP16 or " << kCommunityClipFp32TokenEmbeddingBytes << " for FP32.";
    error = out.str();
    return false;
}

bool build_community_clip_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        std::vector<float>& input,
        std::string& error,
        size_t* token_embedding_bytes = nullptr,
        std::string* token_embedding_data_type = nullptr,
        const mca::image::ClipConditionedSequence* conditioned = nullptr) {
    constexpr int kMaxTextLen = 77;
    constexpr int kEmbeddingSize = 768;
    constexpr int kVocabSize = 49408;
    if (token_ids.size() != kMaxTextLen) {
        error = "CLIP token id count must be 77.";
        return false;
    }
    if (token_weights.size() != kMaxTextLen) {
        error = "CLIP token weight count must be 77.";
        return false;
    }
    if (conditioned != nullptr &&
        (conditioned->embedding_width != static_cast<size_t>(kEmbeddingSize) ||
         conditioned->tokens.ids.size() != token_ids.size() ||
         conditioned->tokens.weights.size() != token_weights.size() ||
         conditioned->override_mask.size() != token_ids.size() ||
         conditioned->embedding_overrides.size() !=
            token_ids.size() * static_cast<size_t>(kEmbeddingSize) ||
         !std::equal(conditioned->tokens.ids.begin(), conditioned->tokens.ids.end(), token_ids.begin()) ||
         !std::equal(conditioned->tokens.weights.begin(), conditioned->tokens.weights.end(), token_weights.begin()))) {
        error = "Textual inversion CLIP-L override contract does not match the executed token sequence.";
        return false;
    }
    const auto tokenPath = root + "/token_emb.bin";
    size_t tokenBytes = 0;
    std::string detectedDataType;
    if (!inspect_community_token_embedding_file(
            root,
            tokenBytes,
            detectedDataType,
            error)) {
        return false;
    }
    const bool tokenFp16 = detectedDataType == "FP16";
    const auto pos_bytes = read_binary_bytes(root + "/pos_emb.bin");
    const size_t expected_pos_bytes = static_cast<size_t>(kMaxTextLen) * kEmbeddingSize * sizeof(float);
    if (pos_bytes.size() != expected_pos_bytes) {
        std::ostringstream out;
        out << "pos_emb.bin has unexpected size: " << pos_bytes.size()
            << ", expected " << expected_pos_bytes << ".";
        error = out.str();
        return false;
    }
    if (token_embedding_bytes != nullptr) {
        *token_embedding_bytes = tokenBytes;
    }
    if (token_embedding_data_type != nullptr) {
        *token_embedding_data_type = detectedDataType;
    }
    const auto* pos_emb = reinterpret_cast<const float*>(pos_bytes.data());
    std::ifstream tokenInput(tokenPath.c_str(), std::ios::binary);
    if (!tokenInput.good()) {
        error = "Failed to open token_emb.bin for windowed reading.";
        return false;
    }
    std::vector<uint16_t> fp16Window(static_cast<size_t>(kEmbeddingSize));
    std::vector<float> fp32Window(static_cast<size_t>(kEmbeddingSize));
    input.assign(static_cast<size_t>(kMaxTextLen) * kEmbeddingSize, 0.0f);
    for (int pos = 0; pos < kMaxTextLen; ++pos) {
        const int id = token_ids[static_cast<size_t>(pos)];
        const float tokenWeight = token_weights[static_cast<size_t>(pos)];
        const bool overridden = conditioned != nullptr &&
                conditioned->override_mask[static_cast<size_t>(pos)] != 0U;
        if (id < 0 || id >= kVocabSize) {
            error = "CLIP token id at position " + std::to_string(pos) + " is outside the vocabulary range.";
            return false;
        }
        if (!std::isfinite(tokenWeight)) {
            error = "CLIP token weight at position " + std::to_string(pos) + " is not finite.";
            return false;
        }
        if (!overridden) {
            const size_t elementBytes = tokenFp16 ? sizeof(uint16_t) : sizeof(float);
            const size_t byteOffset = static_cast<size_t>(id) * kEmbeddingSize * elementBytes;
            tokenInput.clear();
            tokenInput.seekg(static_cast<std::streamoff>(byteOffset), std::ios::beg);
            if (!tokenInput.good()) {
                error = "Failed to seek token_emb.bin to vocabulary row " + std::to_string(id) + ".";
                return false;
            }
            if (tokenFp16) {
                tokenInput.read(
                        reinterpret_cast<char*>(fp16Window.data()),
                        static_cast<std::streamsize>(fp16Window.size() * sizeof(uint16_t)));
            } else {
                tokenInput.read(
                        reinterpret_cast<char*>(fp32Window.data()),
                        static_cast<std::streamsize>(fp32Window.size() * sizeof(float)));
            }
            if (!tokenInput.good()) {
                error = "Failed to read a complete token_emb.bin vocabulary row.";
                return false;
            }
        }
        for (int dim = 0; dim < kEmbeddingSize; ++dim) {
            const size_t out_index = static_cast<size_t>(pos) * kEmbeddingSize + dim;
            const float tokenValue = overridden
                    ? conditioned->embedding_overrides[out_index]
                    : (tokenFp16
                        ? fp16_to_float(fp16Window[static_cast<size_t>(dim)])
                        : fp32Window[static_cast<size_t>(dim)]);
            const float value = tokenValue * tokenWeight + pos_emb[out_index];
            if (!std::isfinite(value)) {
                error = "token_emb.bin or pos_emb.bin produced a non-finite CLIP input value.";
                return false;
            }
            input[out_index] = value;
        }
    }
    return true;
}

bool run_community_clip_encoder_direct(
        const std::string& root,
        const std::string& pinnedExternalWeightPath,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        json* debug,
        std::string* embedding_disk_data_type = nullptr,
        const mca::image::ClipConditionedSequence* conditioned = nullptr,
        const MnnOpenedTextEncoderBinding* textEncoderBinding = nullptr) {
    std::vector<float> input_values;
    size_t token_embedding_bytes = 0;
    std::string token_embedding_type;
    if (!build_community_clip_input(
            root, token_ids, token_weights, input_values, error,
            &token_embedding_bytes, &token_embedding_type, conditioned)) {
        return false;
    }
    if (embedding_disk_data_type != nullptr) {
        *embedding_disk_data_type = token_embedding_type;
    }
    if (debug != nullptr) {
        *debug = json::object();
        (*debug)["inputValueStats"] = float_vector_stats_json(input_values);
        (*debug)["tokenEmbeddingBytes"] = token_embedding_bytes;
        (*debug)["embeddingDiskDataType"] = token_embedding_type;
        (*debug)["tokenWeightStats"] = float_vector_stats_json(token_weights);
        json tokenSamples = json::array();
        for (size_t i = 0; i < token_ids.size() && tokenSamples.size() < 16; ++i) {
            tokenSamples.push_back(token_ids[i]);
        }
        (*debug)["tokenIdSamples"] = tokenSamples;
    }
    const bool hasDeclaredBinding =
            textEncoderBinding != nullptr && textEncoderBinding->active;
    const std::string path = hasDeclaredBinding
            ? textEncoderBinding->graph.opened_path()
            : root + "/clip_v2.mnn";
    const char* graphLabel = hasDeclaredBinding ? "declared MNN text encoder" : "clip_v2.mnn";
    if (path.empty()) {
        error = "The declared MNN text encoder descriptor is no longer open.";
        return false;
    }
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = std::string("Failed to create ") + graphLabel + " interpreter.";
        return false;
    }
    if (hasDeclaredBinding && textEncoderBinding->weight.fd >= 0) {
        const auto weightPath = textEncoderBinding->weight.opened_path();
        if (weightPath.empty()) {
            error = "The declared MNN text encoder weight descriptor is no longer open.";
            return false;
        }
        interpreter->setExternalFile(weightPath.c_str());
    } else if (!hasDeclaredBinding && !pinnedExternalWeightPath.empty()) {
        interpreter->setExternalFile(pinnedExternalWeightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = std::string(graphLabel) + " session configuration failed: " + config_error;
        return false;
    }
    // The community CLIP transformer is numerically fragile with low precision CPU
    // kernels: bad runs produce 1e37-scale hidden states, which collapse CFG.
    backendConfig.precision = MNN::BackendConfig::Precision_High;
    backendConfig.memory = MNN::BackendConfig::Memory_Normal;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = std::string("Failed to create ") + graphLabel + " session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input_embedding tensor is missing.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 77, 768});
    if (debug != nullptr) {
        (*debug)["inputBeforeResizeSession"] = tensor_metadata_json(input);
    }
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input copy failed: " + copyError;
        return false;
    }
    interpreter->resizeSession(session, 1);
    input = interpreter->getSessionInput(session, "input_embedding");
    if (debug != nullptr) {
        (*debug)["inputAfterResizeSession"] = tensor_metadata_json(input);
    }
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input recopy failed after resize: " + copyError;
        return false;
    }
    if (debug != nullptr) {
        (*debug)["inputAfterCopy"] = tensor_buffer_debug_json(input);
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    if (debug != nullptr) {
        json outputList = json::array();
        for (const auto& entry : interpreter->getSessionOutputAll(session)) {
            outputList.push_back(json({
                    {"name", entry.first},
                    {"debug", tensor_buffer_debug_json(entry.second)}
            }));
        }
        (*debug)["outputs"] = outputList;
    }
    auto* tensor = interpreter->getSessionOutput(session, "last_hidden_state");
    std::string selectedOutputName = "last_hidden_state";
    if (tensor == nullptr) {
        tensor = interpreter->getSessionOutput(session, nullptr);
        selectedOutputName = "<default>";
    }
    if (debug != nullptr) {
        (*debug)["selectedOutputName"] = selectedOutputName;
        (*debug)["selectedOutput"] = tensor_buffer_debug_json(tensor);
    }
    const bool ok = copy_tensor_to_float_vector(tensor, embeddings, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = std::string(graphLabel) + " output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {1, 77, 768}, std::string(graphLabel) + " output", error)) {
        return false;
    }
    if (debug != nullptr) {
        (*debug)["decodedEmbeddingStats"] = float_vector_stats_json(embeddings.values);
        (*debug)["decodedEmbeddingShape"] = embeddings.shape;
    }
    return true;
}

bool has_community_clip_bundle(
        const std::string& root,
        const std::string& graphPath = "") {
    return mca::mnn::has_community_clip_bundle(root, graphPath);
}

bool build_clip_embedding_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        std::vector<float>& input,
        std::string& error,
        const mca::image::ClipConditionedSequence* conditioned = nullptr) {
    constexpr int kMaxTextLen = 77;
    constexpr int kVocabSize = 49408;
    if (token_ids.size() != kMaxTextLen) {
        error = "CLIP token id count must be 77.";
        return false;
    }
    if (token_weights.size() != kMaxTextLen) {
        error = "CLIP token weight count must be 77.";
        return false;
    }
    if (embeddingSize <= 0) {
        error = "CLIP embedding size must be positive.";
        return false;
    }
    if (conditioned != nullptr &&
        (conditioned->embedding_width != static_cast<size_t>(embeddingSize) ||
         conditioned->tokens.ids.size() != token_ids.size() ||
         conditioned->tokens.weights.size() != token_weights.size() ||
         conditioned->override_mask.size() != token_ids.size() ||
         conditioned->embedding_overrides.size() !=
            token_ids.size() * static_cast<size_t>(embeddingSize) ||
         !std::equal(conditioned->tokens.ids.begin(), conditioned->tokens.ids.end(), token_ids.begin()) ||
         !std::equal(conditioned->tokens.weights.begin(), conditioned->tokens.weights.end(), token_weights.begin()))) {
        error = "Textual inversion CLIP override contract does not match the executed token sequence.";
        return false;
    }
    const auto token_bytes = read_binary_bytes(root + "/" + tokenFile);
    const auto pos_bytes = read_binary_bytes(root + "/" + positionFile);
    const size_t expected_token_bytes =
            static_cast<size_t>(kVocabSize) * static_cast<size_t>(embeddingSize) * sizeof(uint16_t);
    const size_t expected_pos_bytes =
            static_cast<size_t>(kMaxTextLen) * static_cast<size_t>(embeddingSize) * sizeof(float);
    if (token_bytes.size() != expected_token_bytes) {
        std::ostringstream out;
        out << tokenFile << " has unexpected size: " << token_bytes.size()
            << ", expected " << expected_token_bytes << ".";
        error = out.str();
        return false;
    }
    if (pos_bytes.size() != expected_pos_bytes) {
        std::ostringstream out;
        out << positionFile << " has unexpected size: " << pos_bytes.size()
            << ", expected " << expected_pos_bytes << ".";
        error = out.str();
        return false;
    }
    const auto* token_emb = reinterpret_cast<const uint16_t*>(token_bytes.data());
    const auto* pos_emb = reinterpret_cast<const float*>(pos_bytes.data());
    input.assign(static_cast<size_t>(kMaxTextLen) * static_cast<size_t>(embeddingSize), 0.0f);
    for (int pos = 0; pos < kMaxTextLen; ++pos) {
        const int id = token_ids[static_cast<size_t>(pos)];
        const float tokenWeight = token_weights[static_cast<size_t>(pos)];
        if (id < 0 || id >= kVocabSize) {
            error = "CLIP token id at position " + std::to_string(pos) + " is outside the vocabulary range.";
            return false;
        }
        if (!std::isfinite(tokenWeight)) {
            error = "CLIP token weight at position " + std::to_string(pos) + " is not finite.";
            return false;
        }
        for (int dim = 0; dim < embeddingSize; ++dim) {
            const size_t out_index = static_cast<size_t>(pos) * static_cast<size_t>(embeddingSize) +
                    static_cast<size_t>(dim);
            const size_t token_index = static_cast<size_t>(id) * static_cast<size_t>(embeddingSize) +
                    static_cast<size_t>(dim);
            const bool overridden = conditioned != nullptr &&
                    conditioned->override_mask[static_cast<size_t>(pos)] != 0U;
            const float tokenValue = overridden
                    ? conditioned->embedding_overrides[out_index]
                    : fp16_to_float(token_emb[token_index]);
            const float value = tokenValue * tokenWeight + pos_emb[out_index];
            if (!std::isfinite(value)) {
                error = tokenFile + " or " + positionFile +
                        " produced a non-finite CLIP input value.";
                return false;
            }
            input[out_index] = value;
        }
    }
    return true;
}

MNN::Tensor* find_output_by_element_count(
        const std::map<std::string, MNN::Tensor*>& outputs,
        size_t expectedCount,
        const std::vector<std::string>& preferredNames) {
    for (const auto& name : preferredNames) {
        auto it = outputs.find(name);
        if (it != outputs.end() &&
            tensor_element_count(mnn_tensor_shape(it->second)) == expectedCount) {
            return it->second;
        }
    }
    for (const auto& entry : outputs) {
        if (tensor_element_count(mnn_tensor_shape(entry.second)) == expectedCount) {
            return entry.second;
        }
    }
    return nullptr;
}

MNN::Tensor* find_output_by_shape(
        const std::map<std::string, MNN::Tensor*>& outputs,
        const std::vector<int>& expectedShape,
        const std::vector<std::string>& preferredNames) {
    for (const auto& name : preferredNames) {
        auto it = outputs.find(name);
        if (it != outputs.end() && mnn_tensor_shape(it->second) == expectedShape) {
            return it->second;
        }
    }
    for (const auto& entry : outputs) {
        if (mnn_tensor_shape(entry.second) == expectedShape) {
            return entry.second;
        }
    }
    return nullptr;
}

MNN::Tensor* find_named_output_by_shape(
        const std::map<std::string, MNN::Tensor*>& outputs,
        const std::vector<int>& expectedShape,
        const std::vector<std::string>& preferredNames) {
    for (const auto& name : preferredNames) {
        auto it = outputs.find(name);
        if (it != outputs.end() && mnn_tensor_shape(it->second) == expectedShape) {
            return it->second;
        }
    }
    return nullptr;
}

bool run_sdxl_clip_encoder_direct(
        const std::string& root,
        const std::string& modelFile,
        const std::string& pinnedExternalWeightPath,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        const std::vector<int>& tokenIds,
        const std::vector<float>& tokenWeights,
        const std::string& backendMode,
        int threads,
        FloatTensorData& hidden,
        FloatTensorData* pooled,
        std::string& error,
        json* debug,
        const mca::image::ClipConditionedSequence* conditioned = nullptr) {
    std::vector<float> input_values;
    if (!build_clip_embedding_input(
            root,
            tokenIds,
            tokenWeights,
            tokenFile,
            positionFile,
            embeddingSize,
            input_values,
            error,
            conditioned)) {
        return false;
    }
    const auto path = root + "/" + modelFile;
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create " + modelFile + " interpreter.";
        return false;
    }
    interpreter->setExternalFile(pinnedExternalWeightPath.c_str());
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = modelFile + " session configuration failed: " + config_error;
        return false;
    }
    backendConfig.precision = MNN::BackendConfig::Precision_High;
    backendConfig.memory = MNN::BackendConfig::Memory_Normal;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create " + modelFile + " session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        input = interpreter->getSessionInput(session, nullptr);
    }
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = modelFile + " input tensor is missing.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 77, embeddingSize});
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = modelFile + " input copy failed: " + copyError;
        return false;
    }
    interpreter->resizeSession(session, 1);
    input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        input = interpreter->getSessionInput(session, nullptr);
    }
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = modelFile + " input recopy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = modelFile + " runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    const auto outputs = interpreter->getSessionOutputAll(session);
    if (debug != nullptr) {
        json outputList = json::array();
        for (const auto& entry : outputs) {
            outputList.push_back(json({
                    {"name", entry.first},
                    {"debug", tensor_buffer_debug_json(entry.second)}
            }));
        }
        (*debug)["outputs"] = outputList;
        (*debug)["inputStats"] = float_vector_stats_json(input_values);
        (*debug)["tokenWeightStats"] = float_vector_stats_json(tokenWeights);
    }
    const std::vector<int> hiddenShape = {1, 77, embeddingSize};
    auto* hiddenTensor = find_output_by_shape(
            outputs, hiddenShape, {"last_hidden_state", "hidden_states", "output"});
    if (!copy_tensor_to_float_vector(hiddenTensor, hidden, error)) {
        interpreter->releaseSession(session);
        error = modelFile + " hidden output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(hidden, hiddenShape, modelFile + " hidden output", error)) {
        interpreter->releaseSession(session);
        return false;
    }
    if (pooled != nullptr) {
        const std::vector<int> pooledShape = {1, embeddingSize};
        const std::vector<std::string> pooledNames = {"text_embeds", "pooled_output"};
        auto* pooledTensor = find_named_output_by_shape(
                outputs, pooledShape, pooledNames);
        if (pooledTensor == nullptr) {
            // Diffusers-style CLIP-bigG exports may apply the shared text
            // projection to every position and publish the projected sequence
            // as [1, seq, emb]; the pooled vector is that sequence's EOS row.
            // An EOS row of an unprojected hidden state stays rejected: the
            // UNet requires the exact projected text_embeds contract.
            auto* pooledSequenceTensor = find_named_output_by_shape(
                    outputs,
                    {1, static_cast<int>(tokenIds.size()), embeddingSize},
                    pooledNames);
            if (pooledSequenceTensor == nullptr) {
                interpreter->releaseSession(session);
                error = modelFile +
                        " must expose a projected pooled output named text_embeds or "
                        "pooled_output with shape [1, " + std::to_string(embeddingSize) +
                        "] or [1, " + std::to_string(tokenIds.size()) + ", " +
                        std::to_string(embeddingSize) +
                        "]; refusing to substitute an EOS hidden-state row.";
                return false;
            }
            constexpr int kClipEosTokenId = 49407;
            const auto eosPosition = std::find(
                    tokenIds.begin(), tokenIds.end(), kClipEosTokenId);
            if (eosPosition == tokenIds.end()) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled sequence requires EOS token id " +
                        std::to_string(kClipEosTokenId) + " to select the pooled row.";
                return false;
            }
            FloatTensorData pooledSequence;
            if (!copy_tensor_to_float_vector(pooledSequenceTensor, pooledSequence, error)) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled sequence copy failed: " + error;
                return false;
            }
            const long eosRow = static_cast<long>(std::distance(tokenIds.begin(), eosPosition));
            const size_t sequenceElements = tensor_element_count({
                    1, static_cast<int>(tokenIds.size()), embeddingSize});
            if (sequenceElements == 0 || pooledSequence.values.size() != sequenceElements ||
                    eosRow < 0 ||
                    static_cast<size_t>(eosRow + 1) * static_cast<size_t>(embeddingSize) >
                            pooledSequence.values.size()) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled sequence has an invalid EOS row.";
                return false;
            }
            pooled->shape = pooledShape;
            pooled->values.assign(
                    pooledSequence.values.begin() + eosRow * embeddingSize,
                    pooledSequence.values.begin() + (eosRow + 1) * embeddingSize);
            if (!validate_float_tensor_contract(
                    *pooled, pooledShape, modelFile + " projected pooled output", error)) {
                interpreter->releaseSession(session);
                return false;
            }
            if (debug != nullptr) {
                (*debug)["pooledSource"] = "projected_sequence_eos_row";
                (*debug)["pooledSequenceEosRow"] = eosRow;
                (*debug)["pooledStats"] = float_vector_stats_json(pooled->values);
            }
        } else {
            if (!copy_tensor_to_float_vector(pooledTensor, *pooled, error)) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled output copy failed: " + error;
                return false;
            }
            if (!validate_float_tensor_contract(
                    *pooled, pooledShape, modelFile + " projected pooled output", error)) {
                interpreter->releaseSession(session);
                return false;
            }
            if (debug != nullptr) {
                const auto textEmbeds = outputs.find("text_embeds");
                (*debug)["pooledSource"] =
                        textEmbeds != outputs.end() && textEmbeds->second == pooledTensor
                                ? "text_embeds"
                                : "pooled_output";
                (*debug)["pooledStats"] = float_vector_stats_json(pooled->values);
            }
        }
    }
    interpreter->releaseSession(session);
    return true;
}

bool write_float_file(const std::string& path, const std::vector<float>& values, std::string& error) {
    return mca::mnn::write_float_file(path, values, error);
}

bool has_sdxl_qnn_clip_bundle(const std::string& root) {
    return file_exists(root + "/clip.mnn") &&
            file_exists(root + "/clip_2.mnn") &&
            file_exists(root + "/clip_2.mnn.weight") &&
            file_exists(root + "/tokenizer.json") &&
            file_exists(root + "/token_emb.bin") &&
            file_exists(root + "/token_emb_2.bin") &&
            file_exists(root + "/pos_emb.bin") &&
            file_exists(root + "/pos_emb_2.bin");
}

bool validate_clip_pair_weights_for_token_id_graph(
        const mca::image::ClipTokenPair& tokenPair,
        bool includeNegative,
        std::string& error) {
    return mca::mnn::validate_clip_pair_weights_for_token_id_graph(
            tokenPair, includeNegative, error);
}

bool append_clip_prompt_weighting_evidence(
        json& target,
        const mca::image::ClipTokenPair& tokenPair,
        bool supported,
        bool includeNegative,
        std::string& error) {
    return mca::mnn::append_clip_prompt_weighting_evidence(
            target, tokenPair, supported, includeNegative, error);
}

json encode_sdxl_prompt_conditioning_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        int width,
        int height,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson) {
    if (is_blank_text(prompt)) {
        return json({{"ok", false}, {"error", "Prompt is empty."}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!useCfg && !is_blank_text(negativePrompt)) {
        return json({
            {"ok", false},
            {"error", "SDXL no-CFG conditioning must not receive a negative prompt."},
            {"format", "sdxl_qnn_conditioning"}
        });
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negativePrompt)) {
        return json({
            {"ok", false},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"error", "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."},
            {"format", "sdxl_qnn_conditioning"}
        });
    }
    if (width < 256 || width > 2048 || height < 256 || height > 2048 ||
        width % 8 != 0 || height % 8 != 0) {
        return json({{"ok", false}, {"error", "SDXL width and height must be multiples of 8 between 256 and 2048."},
                     {"format", "sdxl_qnn_conditioning"}});
    }
    std::string canonicalBackend;
    std::string runtimeError;
    if (!validate_mnn_diffusion_runtime_options(backendMode, threads, canonicalBackend, runtimeError)) {
        return json({{"ok", false}, {"error", runtimeError}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!has_sdxl_qnn_clip_bundle(root)) {
        return json({
                {"ok", false},
                {"error", "SDXL QNN bundle requires clip.mnn, clip_2.mnn(+weight), tokenizer.json, token_emb*.bin and pos_emb*.bin."},
                {"format", "sdxl_qnn_conditioning"}
        });
    }
    std::string error;
    const bool textualInversionRequested = !textualInversionJson.empty();
    const auto clip1WeightPath = join_path(root, "clip.mnn.weight");
    const bool useClip1Weight = nonempty_regular_file_exists(clip1WeightPath);
    const std::string pinnedClip1WeightPath = useClip1Weight ? clip1WeightPath : "";
    const std::string pinnedClip2WeightPath = join_path(root, "clip_2.mnn.weight");
    mca::image::execution_assets::Binding executionAssets;
    if (textualInversionRequested && !prepare_mnn_execution_assets(
            textualInversionJson, "QNN_HTP", executionAssets, error)) {
        return mnn_execution_asset_failure(error, "sdxl_qnn_conditioning");
    }
    if (textualInversionRequested) {
        std::vector<std::string> requiredAssetPaths = {
            join_path(root, "clip.mnn"),
            join_path(root, "clip_2.mnn"),
            join_path(root, "clip_2.mnn.weight"),
            join_path(root, "tokenizer.json"),
            join_path(root, "token_emb.bin"),
            join_path(root, "token_emb_2.bin"),
            join_path(root, "pos_emb.bin"),
            join_path(root, "pos_emb_2.bin"),
        };
        if (useClip1Weight) {
            requiredAssetPaths.push_back(clip1WeightPath);
        }
        if (!mnn_execution_assets_match(executionAssets, requiredAssetPaths, error)) {
            return mnn_execution_asset_failure(error, "sdxl_qnn_conditioning");
        }
    }
    mca::image::ClipTokenizerConfig clip1TokenizerConfig;
    clip1TokenizerConfig.bos_id = 49406;
    clip1TokenizerConfig.eos_id = 49407;
    clip1TokenizerConfig.pad_id = 49407;
    clip1TokenizerConfig.max_length = 77;
    clip1TokenizerConfig.enable_prompt_weighting = promptWeightingEnabled;
    mca::image::ClipTokenizerConfig clip2TokenizerConfig = clip1TokenizerConfig;
    clip2TokenizerConfig.pad_id = 0;
    mca::image::ClipTokenPair clip1TokenPair;
    mca::image::ClipTokenPair clip2TokenPair;
    if (!mca::image::tokenize_clip_pair_from_json(
            root + "/tokenizer.json",
            prompt,
            negativePrompt,
            clip1TokenizerConfig,
            &clip1TokenPair,
            &error,
            useCfg) ||
        !mca::image::tokenize_clip_pair_from_json(
            root + "/tokenizer.json",
            prompt,
            negativePrompt,
            clip2TokenizerConfig,
            &clip2TokenPair,
            &error,
            useCfg)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    mca::image::textual_inversion::Selection textualInversionSelection;
    mca::image::textual_inversion::Audit textualInversionAudit;
    mca::image::ClipConditionedPair clip1ConditionedPair;
    mca::image::ClipConditionedPair clip2ConditionedPair;
    if (textualInversionRequested) {
        if (!mca::image::textual_inversion::load_selection(
                textualInversionJson,
                "QNN_HTP",
                true,
                &textualInversionSelection,
                &textualInversionAudit,
                &error) ||
            !mnn_selection_matches_execution_assets(
                textualInversionSelection, executionAssets, error) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                clip1TokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, false),
                &clip1ConditionedPair,
                &error,
                useCfg) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                clip2TokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, true),
                &clip2ConditionedPair,
                &error,
                useCfg)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_CONDITIONING_FAILED"},
                {"error", error},
                {"format", "sdxl_qnn_conditioning"}
            });
        }
        mca::image::textual_inversion::record_conditioned_pair(
            clip1ConditionedPair, false, &textualInversionAudit);
        mca::image::textual_inversion::record_conditioned_pair(
            clip2ConditionedPair, true, &textualInversionAudit);
        clip1TokenPair.negative = clip1ConditionedPair.negative.tokens;
        clip1TokenPair.positive = clip1ConditionedPair.positive.tokens;
        clip2TokenPair.negative = clip2ConditionedPair.negative.tokens;
        clip2TokenPair.positive = clip2ConditionedPair.positive.tokens;
    }
    const auto clip1StandardIds = useCfg
        ? clip1TokenPair.negative_then_positive()
        : clip1TokenPair.positive.ids;
    const auto clip2StandardIds = useCfg
        ? clip2TokenPair.negative_then_positive()
        : clip2TokenPair.positive.ids;
    const std::vector<int> clip1Ids(clip1StandardIds.begin(), clip1StandardIds.end());
    const std::vector<int> clip2Ids(clip2StandardIds.begin(), clip2StandardIds.end());
    const size_t expectedTokenIds = useCfg ? 154U : 77U;
    if (clip1Ids.size() != expectedTokenIds || clip2Ids.size() != expectedTokenIds) {
        return json({{"ok", false}, {"error", "CLIP tokenizer returned invalid id count."}});
    }
    std::vector<int> clip1NegIds;
    std::vector<int> clip1PosIds;
    std::vector<int> clip2NegIds;
    std::vector<int> clip2PosIds;
    if (useCfg) {
        clip1NegIds.assign(clip1Ids.begin(), clip1Ids.begin() + 77);
        clip1PosIds.assign(clip1Ids.begin() + 77, clip1Ids.end());
        clip2NegIds.assign(clip2Ids.begin(), clip2Ids.begin() + 77);
        clip2PosIds.assign(clip2Ids.begin() + 77, clip2Ids.end());
    } else {
        clip1PosIds = clip1Ids;
        clip2PosIds = clip2Ids;
    }

    FloatTensorData negClip1;
    FloatTensorData posClip1;
    FloatTensorData negClip2;
    FloatTensorData posClip2;
    FloatTensorData negPooled;
    FloatTensorData posPooled;
    json debug = json::object();
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    if (useCfg && !run_sdxl_clip_encoder_direct(
            root, "clip.mnn", pinnedClip1WeightPath,
            "token_emb.bin", "pos_emb.bin", 768, clip1NegIds,
            clip1TokenPair.negative.weights,
            canonicalBackend, threads, negClip1, nullptr, error,
            includeDebug ? &debug["clip1_negative"] : nullptr,
            textualInversionRequested ? &clip1ConditionedPair.negative : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip.mnn", pinnedClip1WeightPath,
            "token_emb.bin", "pos_emb.bin", 768, clip1PosIds,
            clip1TokenPair.positive.weights,
            canonicalBackend, threads, posClip1, nullptr, error,
            includeDebug ? &debug["clip1_positive"] : nullptr,
            textualInversionRequested ? &clip1ConditionedPair.positive : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (useCfg && !run_sdxl_clip_encoder_direct(
            root, "clip_2.mnn", pinnedClip2WeightPath,
            "token_emb_2.bin", "pos_emb_2.bin", 1280, clip2NegIds,
            clip2TokenPair.negative.weights,
            canonicalBackend, threads, negClip2, &negPooled, error,
            includeDebug ? &debug["clip2_negative"] : nullptr,
            textualInversionRequested ? &clip2ConditionedPair.negative : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip_2.mnn", pinnedClip2WeightPath,
            "token_emb_2.bin", "pos_emb_2.bin", 1280, clip2PosIds,
            clip2TokenPair.positive.weights,
            canonicalBackend, threads, posClip2, &posPooled, error,
            includeDebug ? &debug["clip2_positive"] : nullptr,
            textualInversionRequested ? &clip2ConditionedPair.positive : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    constexpr int kTextLen = 77;
    constexpr int kClip1 = 768;
    constexpr int kClip2 = 1280;
    constexpr int kHidden = kClip1 + kClip2;
    if ((useCfg && (!validate_float_tensor_contract(negClip1, {1, kTextLen, kClip1}, "SDXL negative clip1", error) ||
        !validate_float_tensor_contract(negClip2, {1, kTextLen, kClip2}, "SDXL negative clip2", error) ||
        !validate_float_tensor_contract(negPooled, {1, kClip2}, "SDXL negative pooled output", error))) ||
        !validate_float_tensor_contract(posClip1, {1, kTextLen, kClip1}, "SDXL positive clip1", error) ||
        !validate_float_tensor_contract(posClip2, {1, kTextLen, kClip2}, "SDXL positive clip2", error) ||
        !validate_float_tensor_contract(posPooled, {1, kClip2}, "SDXL positive pooled output", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    std::vector<float> combined;
    const size_t branchCount = useCfg ? 2U : 1U;
    combined.reserve(static_cast<size_t>(branchCount * kTextLen * kHidden +
        branchCount * kClip2 + 6));
    auto appendHidden = [&](const FloatTensorData& clip1, const FloatTensorData& clip2) {
        for (int token = 0; token < kTextLen; ++token) {
            const size_t clip1Base = static_cast<size_t>(token) * kClip1;
            const size_t clip2Base = static_cast<size_t>(token) * kClip2;
            combined.insert(combined.end(), clip1.values.begin() + clip1Base, clip1.values.begin() + clip1Base + kClip1);
            combined.insert(combined.end(), clip2.values.begin() + clip2Base, clip2.values.begin() + clip2Base + kClip2);
        }
    };
    if (useCfg) appendHidden(negClip1, negClip2);
    appendHidden(posClip1, posClip2);
    if (useCfg) {
        combined.insert(combined.end(), negPooled.values.begin(), negPooled.values.end());
    }
    combined.insert(combined.end(), posPooled.values.begin(), posPooled.values.end());
    combined.insert(combined.end(), {
            static_cast<float>(height),
            static_cast<float>(width),
            0.0f,
            0.0f,
            static_cast<float>(height),
            static_cast<float>(width)
    });
    if (!write_float_file(outputPath, combined, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    std::vector<uint8_t> artifactBytes(combined.size() * sizeof(float));
    std::memcpy(artifactBytes.data(), combined.data(), artifactBytes.size());
    const std::string conditioningArtifactSha256 =
            mca::image::sha256_hex_bytes(artifactBytes);
    const std::string nativePromptExecutionSha256 =
            mca::image::image_prompt_execution_sha256(prompt, negativePrompt);
    if (conditioningArtifactSha256.size() != 64U ||
        nativePromptExecutionSha256.size() != 64U) {
        return json({
                {"ok", false},
                {"error", "SDXL conditioning artifact or native prompt SHA-256 could not be derived."},
                {"format", "sdxl_qnn_conditioning"}
        });
    }
    if (textualInversionRequested &&
        !finalize_mnn_execution_assets(executionAssets, error)) {
        return mnn_execution_asset_failure(error, "sdxl_qnn_conditioning");
    }
    json result = json({
            {"ok", true},
            {"path", outputPath},
            {"bytes", static_cast<long long>(combined.size() * sizeof(float))},
            {"elements", static_cast<long long>(combined.size())},
            {"format", "sdxl_qnn_conditioning"},
            {"hiddenShape", {branchCount, 77, 2048}},
            {"pooledShape", {branchCount, 1280}},
            {"timeIdsShape", {1, 6}},
            {"conditioningExecutionMode", "external_mnn_sdxl_embeddings"},
            {"conditioningBackend", "MNN"},
            {"conditioningGraph", "clip.mnn+clip_2.mnn"},
            {"conditioningEncoderExecutionCount", useCfg ? 4 : 2},
            {"conditioningOrder", useCfg ? "negative_then_positive" : "positive_only"},
            {"conditioningArtifactSha256", conditioningArtifactSha256},
            {"nativePromptExecutionSha256", nativePromptExecutionSha256},
            {"nativePromptBindingStage", "conditioning_encoded"},
            {"clip1PadId", clip1TokenizerConfig.pad_id},
            {"clip2PadId", clip2TokenizerConfig.pad_id},
            {"clipPadRules", {
                {"clip1", "EOS"},
                {"clip2", "ZERO"}
            }},
            {"backendMode", canonicalBackend}
    });
    const size_t hiddenElementCount = static_cast<size_t>(kTextLen * kHidden);
    const size_t positiveHiddenOffset = useCfg ? hiddenElementCount : 0U;
    result["positiveHiddenStats"] = float_vector_stats_json(
        std::vector<float>(
            combined.begin() + positiveHiddenOffset,
            combined.begin() + positiveHiddenOffset + hiddenElementCount));
    if (useCfg) {
        result["negativeHiddenStats"] = float_vector_stats_json(
            std::vector<float>(combined.begin(), combined.begin() + hiddenElementCount));
        result["positiveNegativeAbsDiffStats"] = float_vector_abs_diff_stats_json(
            std::vector<float>(
                combined.begin() + hiddenElementCount,
                combined.begin() + 2U * hiddenElementCount),
            std::vector<float>(combined.begin(), combined.begin() + hiddenElementCount));
    }
    if (!append_clip_prompt_weighting_evidence(
            result, clip1TokenPair, promptWeightingEnabled, useCfg, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    // The isolated QNN consumer receives the serialized CLIP float artifact,
    // not either tokenizer sequence independently. Bind the
    // cross-process fingerprint to those exact bytes while retaining the
    // weighted-token counts as separate execution evidence.
    result["promptWeightFingerprint"] = conditioningArtifactSha256;
    if (textualInversionRequested) {
        result["textualInversions"] =
            mca::image::textual_inversion::artifacts_json(textualInversionSelection);
        result["textualInversionEvidence"] =
            mca::image::textual_inversion::evidence_json(
                textualInversionSelection,
                textualInversionAudit,
                false);
        mca::image::execution_assets::append_evidence(result, executionAssets);
    }
    if (includeDebug) {
        result["debug"] = debug;
    }
    return result;
}

json encode_community_clip_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled = true,
        const std::string& textualInversionJson = "") {
    std::string error;
    const bool textualInversionRequested = !textualInversionJson.empty();
    const auto clipWeightPath = join_path(root, "clip_v2.mnn.weight");
    const bool useClipWeight = nonempty_regular_file_exists(clipWeightPath);
    const std::string pinnedClipWeightPath = useClipWeight ? clipWeightPath : "";
    mca::image::execution_assets::Binding executionAssets;
    if (textualInversionRequested && !prepare_mnn_execution_assets(
            textualInversionJson, "QNN_HTP", executionAssets, error)) {
        return mnn_execution_asset_failure(error, "community_clip");
    }
    if (textualInversionRequested) {
        std::vector<std::string> requiredAssetPaths = {
            join_path(root, "clip_v2.mnn"),
            join_path(root, "tokenizer.json"),
            join_path(root, "token_emb.bin"),
            join_path(root, "pos_emb.bin"),
        };
        if (useClipWeight) {
            requiredAssetPaths.push_back(clipWeightPath);
        }
        if (!mnn_execution_assets_match(executionAssets, requiredAssetPaths, error)) {
            return mnn_execution_asset_failure(error, "community_clip");
        }
    }
    mca::image::ClipTokenizerConfig tokenizerConfig;
    tokenizerConfig.bos_id = 49406;
    tokenizerConfig.eos_id = 49407;
    tokenizerConfig.pad_id = 49407;
    tokenizerConfig.max_length = 77;
    tokenizerConfig.enable_prompt_weighting = promptWeightingEnabled;
    mca::image::ClipTokenPair tokenPair;
    if (!mca::image::tokenize_clip_pair_from_json(
            root + "/tokenizer.json",
            prompt,
            negativePrompt,
            tokenizerConfig,
            &tokenPair,
            &error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    mca::image::textual_inversion::Selection textualInversionSelection;
    mca::image::textual_inversion::Audit textualInversionAudit;
    mca::image::ClipConditionedPair conditionedPair;
    if (textualInversionRequested) {
        if (!mca::image::textual_inversion::load_selection(
                textualInversionJson,
                "QNN_HTP",
                false,
                &textualInversionSelection,
                &textualInversionAudit,
                &error) ||
            !mnn_selection_matches_execution_assets(
                textualInversionSelection, executionAssets, error) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                tokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, false),
                &conditionedPair,
                &error)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_CONDITIONING_FAILED"},
                {"error", error},
                {"format", "community_clip"}
            });
        }
        mca::image::textual_inversion::record_conditioned_pair(
            conditionedPair, false, &textualInversionAudit);
        tokenPair.negative = conditionedPair.negative.tokens;
        tokenPair.positive = conditionedPair.positive.tokens;
    }
    const auto standardIds = tokenPair.negative_then_positive();
    const std::vector<int> ids(standardIds.begin(), standardIds.end());
    if (ids.size() != 154) {
        return json({
            {"ok", false},
            {"error", "CLIP tokenizer returned invalid id count."},
            {"tokenCount", ids.size()},
            {"format", "community_clip"}
        });
    }
    FloatTensorData negative;
    FloatTensorData positive;
    std::string negativeEmbeddingType;
    std::string positiveEmbeddingType;
    json negativeDebug = json::object();
    json positiveDebug = json::object();
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    std::vector<int> neg_ids(ids.begin(), ids.begin() + 77);
    std::vector<int> pos_ids(ids.begin() + 77, ids.end());
    if (useCfg) {
        if (!run_community_clip_encoder_direct(
                root,
                pinnedClipWeightPath,
                neg_ids,
                tokenPair.negative.weights,
                backendMode,
                threads,
                negative,
                error,
                includeDebug ? &negativeDebug : nullptr,
                &negativeEmbeddingType,
                textualInversionRequested ? &conditionedPair.negative : nullptr)) {
            return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
        }
    }
    if (!run_community_clip_encoder_direct(
            root,
            pinnedClipWeightPath,
            pos_ids,
            tokenPair.positive.weights,
            backendMode,
            threads,
            positive,
            error,
            includeDebug ? &positiveDebug : nullptr,
            &positiveEmbeddingType,
            textualInversionRequested ? &conditionedPair.positive : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    if (useCfg && negativeEmbeddingType != positiveEmbeddingType) {
        return json({
            {"ok", false},
            {"error", "PACKAGE_FORMAT_INVALID: negative and positive CLIP reads reported different token embedding dtypes."},
            {"format", "community_clip"}
        });
    }
    if ((useCfg && !validate_float_tensor_contract(
            negative, {1, 77, 768}, "Community negative CLIP output", error)) ||
        !validate_float_tensor_contract(
            positive, {1, 77, 768}, "Community positive CLIP output", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    std::vector<float> combined;
    combined.reserve((useCfg ? negative.values.size() : 0U) + positive.values.size());
    if (useCfg) {
        combined.insert(combined.end(), negative.values.begin(), negative.values.end());
    }
    combined.insert(combined.end(), positive.values.begin(), positive.values.end());
    if (!write_float_file(outputPath, combined, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    std::vector<uint8_t> artifactBytes(combined.size() * sizeof(float));
    std::memcpy(artifactBytes.data(), combined.data(), artifactBytes.size());
    const std::string conditioningArtifactSha256 =
            mca::image::sha256_hex_bytes(artifactBytes);
    const std::string nativePromptExecutionSha256 =
            mca::image::image_prompt_execution_sha256(
                    prompt,
                    useCfg ? negativePrompt : std::string());
    if (conditioningArtifactSha256.size() != 64U ||
        nativePromptExecutionSha256.size() != 64U) {
        return json({
            {"ok", false},
            {"error", "Community CLIP conditioning artifact or native prompt SHA-256 could not be derived."},
            {"format", "community_clip"}
        });
    }
    if (textualInversionRequested &&
        !finalize_mnn_execution_assets(executionAssets, error)) {
        return mnn_execution_asset_failure(error, "community_clip");
    }
    json result = {
        {"ok", true},
        {"path", outputPath},
        {"bytes", static_cast<long long>(combined.size() * sizeof(float))},
        {"elements", static_cast<long long>(combined.size())},
        {"shape", {useCfg ? 2 : 1, 77, 768}},
        {"tokenCount", useCfg ? ids.size() : pos_ids.size()},
        {"positiveShape", positive.shape},
        {"positiveStats", float_vector_stats_json(positive.values)},
        {"format", "community_clip"},
        {"conditioningExecutionMode", "external_mnn_embeddings"},
        {"conditioningBackend", "MNN"},
        {"conditioningGraph", "clip_v2.mnn"},
        {"conditioningEncoderExecutionCount", useCfg ? 2 : 1},
        {"conditioningOrder", useCfg ? "negative_then_positive" : "positive_only"},
        {"conditioningArtifactSha256", conditioningArtifactSha256},
        {"nativePromptExecutionSha256", nativePromptExecutionSha256},
        {"nativePromptBindingStage", "conditioning_encoded"},
        {"embeddingDiskDataType", positiveEmbeddingType},
        {"backendMode", backendMode == "opencl" || backendMode == "gpu" ? "opencl" : "cpu"}
    };
    if (useCfg) {
        result["negativeShape"] = negative.shape;
        result["negativeStats"] = float_vector_stats_json(negative.values);
        result["positiveNegativeAbsDiffStats"] =
            float_vector_abs_diff_stats_json(positive.values, negative.values);
    }
    if (!append_clip_prompt_weighting_evidence(
            result, tokenPair, promptWeightingEnabled, useCfg, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    // The artifact digest is the cross-process identity consumed by QNN. Keep
    // weighting counts as separate evidence, but bind the fingerprint field
    // to the exact serialized float payload.
    result["promptWeightFingerprint"] = conditioningArtifactSha256;
    if (textualInversionRequested) {
        result["textualInversions"] =
            mca::image::textual_inversion::artifacts_json(textualInversionSelection);
        result["textualInversionEvidence"] =
            mca::image::textual_inversion::evidence_json(
                textualInversionSelection,
                textualInversionAudit,
                false);
        mca::image::execution_assets::append_evidence(result, executionAssets);
    }
    if (!useCfg) {
        result["negativeWeightedTokenCount"] = 0;
        result["promptWeightingApplied"] =
            result.value("positiveWeightedTokenCount", 0) > 0;
    }
    if (includeDebug) {
        result["debug"] = {{"positive", positiveDebug}};
        if (useCfg) result["debug"]["negative"] = negativeDebug;
    }
    return result;
}

std::string resolve_mnn_tokenizer_json_path(const std::string& root) {
    const std::array<std::string, 2> candidates = {
        root + "/tokenizer/tokenizer.json",
        root + "/tokenizer.json"
    };
    for (const auto& candidate : candidates) {
        if (file_exists(candidate)) return candidate;
    }
    return "";
}

bool tokenize_mnn_sd15_prompt(
        const std::string& root,
        const std::string& prompt,
        const MnnSemanticExecutionContract& contract,
        std::vector<int>& ids,
        mca::image::ClipTokenPair& executedTokenPair,
        bool& hasExecutedTokenPair,
        MnnNativeExecutionEvidence& evidence,
        std::string& error) {
    ids.clear();
    executedTokenPair = mca::image::ClipTokenPair{};
    hasExecutedTokenPair = false;
    if (contract.tokenizer_backend == "TOKENIZERS_CPP") {
        const auto tokenizerPath = resolve_mnn_tokenizer_json_path(root);
        if (tokenizerPath.empty()) {
            error = "tokenizerBackend=TOKENIZERS_CPP requires tokenizer/tokenizer.json or tokenizer.json.";
            return false;
        }
        mca::image::ClipTokenizerConfig tokenizerConfig;
        tokenizerConfig.bos_id = contract.tokenizer_bos_id;
        tokenizerConfig.eos_id = contract.tokenizer_eos_id;
        tokenizerConfig.pad_id = contract.tokenizer_pad_id;
        tokenizerConfig.max_length = contract.tokenizer_max_length;
        // Parse attention syntax even when this graph cannot execute it. That keeps ordinary
        // prompts on the normal path while allowing a non-unity request to fail explicitly
        // instead of becoming literal punctuation in the token-id graph.
        tokenizerConfig.enable_prompt_weighting = true;
        mca::image::ClipTokenPair pair;
        if (!mca::image::tokenize_clip_pair_from_json(
                tokenizerPath,
                prompt,
                contract.negative_prompt,
                tokenizerConfig,
                &pair,
                &error)) {
            return false;
        }
        if (!contract.prompt_weighting_supported &&
            (pair.negative.weighting_applied || pair.positive.weighting_applied)) {
            error = "This MNN text_encoder.mnn accepts int32 token IDs and cannot apply "
                    "non-unity prompt weights before the Transformer.";
            return false;
        }
        const auto standardIds = contract.use_cfg
                ? pair.negative_then_positive()
                : pair.positive.ids;
        ids.assign(standardIds.begin(), standardIds.end());
        executedTokenPair = pair;
        hasExecutedTokenPair = true;
        evidence.tokenizer_backend = "TOKENIZERS_CPP";
    } else if (contract.tokenizer_backend == "MNN_MTOK") {
        if (!file_exists(root + "/tokenizer.mtok")) {
            error = "tokenizerBackend=MNN_MTOK requires tokenizer.mtok in the MNN bundle root.";
            return false;
        }
        MNN::DIFFUSION::MtokTokenizer tokenizer(
                MNN::DIFFUSION::MtokTokenizer::Style::kSingle,
                contract.tokenizer_bos_id,
                contract.tokenizer_eos_id);
        if (!tokenizer.load(root)) {
            error = "Failed to load the explicitly selected MNN_MTOK tokenizer.";
            return false;
        }
        const auto positiveIds = tokenizer.encode(prompt, contract.tokenizer_max_length);
        if (positiveIds.size() != static_cast<size_t>(contract.tokenizer_max_length)) {
            error = "MNN_MTOK positive prompt did not produce tokenizerMaxLength token ids.";
            return false;
        }
        if (contract.use_cfg) {
            const auto negativeIds = tokenizer.encode(
                    contract.negative_prompt,
                    contract.tokenizer_max_length);
            if (negativeIds.size() != static_cast<size_t>(contract.tokenizer_max_length)) {
                error = "MNN_MTOK negative prompt did not produce tokenizerMaxLength token ids.";
                return false;
            }
            ids.reserve(negativeIds.size() + positiveIds.size());
            ids.insert(ids.end(), negativeIds.begin(), negativeIds.end());
        }
        ids.insert(ids.end(), positiveIds.begin(), positiveIds.end());
        evidence.tokenizer_backend = "MNN_MTOK";
    } else {
        error = "Unsupported MNN tokenizer backend after strict contract parsing.";
        return false;
    }
    if (ids.size() != contract.token_count) {
        error = "MNN tokenizer produced " + std::to_string(ids.size()) +
                " token ids; the resolved contract requires " +
                std::to_string(contract.token_count) + ".";
        return false;
    }
    evidence.token_count = ids.size();
    const std::vector<int> activeTokenIds = contract.use_cfg
            ? ids
            : std::vector<int>(
                    ids.end() - contract.tokenizer_max_length,
                    ids.end());
    evidence.prompt_weight_fingerprint = hasExecutedTokenPair
            ? (contract.use_cfg
                    ? executedTokenPair.weighting_fingerprint()
                    : executedTokenPair.positive.weighting_fingerprint)
            : mnn_token_id_fingerprint(activeTokenIds);
    if (evidence.prompt_weight_fingerprint.size() != 64U) {
        error = "Conditioning fingerprint could not be derived from executed token sequences.";
        return false;
    }
    if (contract.prompt_weighting_supported) {
        if (!hasExecutedTokenPair) {
            error = "Prompt weighting was enabled without executed TOKENIZERS_CPP sequences.";
            return false;
        }
        evidence.prompt_weighting_applied =
                (contract.use_cfg && executedTokenPair.negative.weighting_applied) ||
                executedTokenPair.positive.weighting_applied;
        evidence.positive_weighted_token_count =
                executedTokenPair.positive.weighted_token_count;
        evidence.negative_weighted_token_count = contract.use_cfg
                ? executedTokenPair.negative.weighted_token_count
                : 0U;
    }
    return true;
}

bool copy_mnn_timestep_to_tensor(
        MNN::Tensor* tensor,
        double timestep,
        std::string& error) {
    if (tensor == nullptr || tensor->elementSize() != 1) {
        error = "UNet timestep input must contain exactly one element.";
        return false;
    }
    if (!std::isfinite(timestep)) {
        error = "UNet timestep must be finite.";
        return false;
    }
    const auto type = tensor->getType();
    if (type.code == halide_type_float && type.bits == 32 && type.lanes == 1) {
        return copy_vector_to_tensor<float>(
                tensor,
                {static_cast<float>(timestep)},
                error);
    }
    if (type.code == halide_type_int && type.bits == 32 && type.lanes == 1) {
        if (timestep < static_cast<double>(std::numeric_limits<int>::min()) ||
            timestep > static_cast<double>(std::numeric_limits<int>::max())) {
            error = "UNet timestep exceeds int32 range.";
            return false;
        }
        // The official converted MNN graph exposes an int32 timestep. Preserve
        // the scheduler's nearest discrete timestep instead of truncating toward
        // zero, which biases every fractional schedule point downward.
        return copy_vector_to_tensor<int>(
                tensor,
                {static_cast<int>(std::llround(timestep))},
                error);
    }
    error = "UNet timestep input must be scalar float32 or int32.";
    return false;
}

bool run_text_encoder_direct_once(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        const MnnOpenedTextEncoderBinding* textEncoderBinding = nullptr) {
    if (ids.size() != 77U && ids.size() != 2U * 77U) {
        error = "text_encoder requires exactly 77 conditional ids or 154 CFG-pair ids.";
        return false;
    }
    const int batch = ids.size() == 77U ? 1 : 2;
    const bool hasDeclaredBinding =
            textEncoderBinding != nullptr && textEncoderBinding->active;
    const std::string path = hasDeclaredBinding
            ? textEncoderBinding->graph.opened_path()
            : root + "/text_encoder.mnn";
    const char* graphLabel = hasDeclaredBinding ? "declared MNN text encoder" : "text_encoder";
    if (path.empty()) {
        error = "The declared MNN text encoder descriptor is no longer open.";
        return false;
    }
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = std::string("Failed to create ") + graphLabel + " interpreter.";
        return false;
    }
    if (hasDeclaredBinding && textEncoderBinding->weight.fd >= 0) {
        const auto weightPath = textEncoderBinding->weight.opened_path();
        if (weightPath.empty()) {
            error = "The declared MNN text encoder weight descriptor is no longer open.";
            return false;
        }
        interpreter->setExternalFile(weightPath.c_str());
    } else if (!hasDeclaredBinding) {
        const auto weightPath = path + ".weight";
        if (file_exists(weightPath)) {
            interpreter->setExternalFile(weightPath.c_str());
        }
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error, true)) {
        error = std::string(graphLabel) + " session configuration failed: " + config_error;
        return false;
    }
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = std::string("Failed to create ") + graphLabel + " session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_ids");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input_ids tensor is missing before resize.";
        return false;
    }
    interpreter->resizeTensor(input, {batch, 77});
    interpreter->resizeSession(session, 1);
    int resizeStatus = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    input = interpreter->getSessionInput(session, "input_ids");
    if (resizeStatus != 0 || input == nullptr) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " resizeSession failed with status " +
                std::to_string(resizeStatus) + ".";
        return false;
    }
    std::string copyError;
    if (!copy_vector_to_tensor<int>(input, ids, copyError)) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input copy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "last_hidden_state");
    const bool ok = copy_tensor_to_float_vector(tensor, embeddings, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = std::string(graphLabel) + " output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {batch, 77, 768}, std::string(graphLabel) + " output", error)) {
        return false;
    }
    return true;
}

bool run_text_encoder_direct(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        const MnnOpenedTextEncoderBinding* textEncoderBinding = nullptr) {
    if (ids.size() != 2U * 77U) {
        return run_text_encoder_direct_once(
                root,
                ids,
                backendMode,
                threads,
                embeddings,
                error,
                textEncoderBinding);
    }

    std::string batchedError;
    if (run_text_encoder_direct_once(
            root,
            ids,
            backendMode,
            threads,
            embeddings,
            batchedError,
            textEncoderBinding)) {
        return true;
    }

    const std::vector<int> negativeIds(ids.begin(), ids.begin() + 77);
    const std::vector<int> positiveIds(ids.begin() + 77, ids.end());
    FloatTensorData negative;
    FloatTensorData positive;
    std::string negativeError;
    if (!run_text_encoder_direct_once(
            root,
            negativeIds,
            backendMode,
            threads,
            negative,
            negativeError,
            textEncoderBinding)) {
        error = "text_encoder batch-2 execution failed: " + batchedError +
                " Sequential negative branch also failed: " + negativeError;
        return false;
    }
    std::string positiveError;
    if (!run_text_encoder_direct_once(
            root,
            positiveIds,
            backendMode,
            threads,
            positive,
            positiveError,
            textEncoderBinding)) {
        error = "text_encoder batch-2 execution failed: " + batchedError +
                " Sequential positive branch failed: " + positiveError;
        return false;
    }

    embeddings.shape = {2, 77, 768};
    embeddings.values.clear();
    embeddings.values.reserve(negative.values.size() + positive.values.size());
    embeddings.values.insert(
            embeddings.values.end(), negative.values.begin(), negative.values.end());
    embeddings.values.insert(
            embeddings.values.end(), positive.values.begin(), positive.values.end());
    error.clear();
    return true;
}

class DirectMnnUnetSession {
public:
    ~DirectMnnUnetSession() {
        close();
    }

    int batch() const {
        return batch_;
    }

    bool initialize(
            const std::string& root,
            const std::string& backendMode,
            int threads,
            int batch,
            std::string& error) {
        close();
        if (batch != 1 && batch != 2) {
            error = "UNet direct batch must be 1 (conditional) or 2 (CFG pair).";
            return false;
        }
        batch_ = batch;
        const auto path = root + "/unet.mnn";
        interpreter_.reset(MNN::Interpreter::createFromFile(path.c_str()));
        if (!interpreter_) {
            error = "Failed to create UNet interpreter.";
            return false;
        }
        weight_path_ = path + ".weight";
        if (file_exists(weight_path_)) {
            interpreter_->setExternalFile(weight_path_.c_str());
        }
        configure_direct_interpreter(interpreter_.get(), false, false, false);
        MNN::ScheduleConfig config;
        MNN::BackendConfig backendConfig;
        std::string configError;
        if (!configure_mnn_session(config, backendConfig, backendMode, threads, configError, true)) {
            error = "UNet session configuration failed: " + configError;
            close();
            return false;
        }
        session_ = interpreter_->createSession(config);
        if (session_ == nullptr) {
            error = "Failed to create UNet session.";
            close();
            return false;
        }
        sample_input_ = interpreter_->getSessionInput(session_, "sample");
        timestep_input_ = interpreter_->getSessionInput(session_, "timestep");
        encoder_input_ = interpreter_->getSessionInput(session_, "encoder_hidden_states");
        if (sample_input_ == nullptr || timestep_input_ == nullptr || encoder_input_ == nullptr) {
            error = "UNet input tensor not found before resize.";
            close();
            return false;
        }
        interpreter_->resizeTensor(sample_input_, {batch_, 4, 64, 64});
        interpreter_->resizeTensor(timestep_input_, {1});
        interpreter_->resizeTensor(encoder_input_, {batch_, 77, 768});
        interpreter_->resizeSession(session_, 1);
        int resizeStatus = -1;
        interpreter_->getSessionInfo(session_, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
        sample_input_ = interpreter_->getSessionInput(session_, "sample");
        timestep_input_ = interpreter_->getSessionInput(session_, "timestep");
        encoder_input_ = interpreter_->getSessionInput(session_, "encoder_hidden_states");
        if (resizeStatus != 0 || sample_input_ == nullptr || timestep_input_ == nullptr || encoder_input_ == nullptr) {
            error = "UNet resizeSession failed with status " + std::to_string(resizeStatus) + ".";
            close();
            return false;
        }
        const std::vector<int> expectedSampleShape = {batch_, 4, 64, 64};
        const std::vector<int> expectedEncoderShape = {batch_, 77, 768};
        const std::vector<int> expectedTimestepShape = {1};
        const auto sampleShape = mnn_tensor_shape(sample_input_);
        const auto encoderShape = mnn_tensor_shape(encoder_input_);
        const auto timestepShape = mnn_tensor_shape(timestep_input_);
        if (sampleShape != expectedSampleShape ||
            encoderShape != expectedEncoderShape ||
            timestepShape != expectedTimestepShape) {
            error = "UNet input shape mismatch after resize: sample expected " +
                    tensor_shape_string(expectedSampleShape) + ", got " +
                    tensor_shape_string(sampleShape) + "; encoder_hidden_states expected " +
                    tensor_shape_string(expectedEncoderShape) + ", got " +
                    tensor_shape_string(encoderShape) + "; timestep expected " +
                    tensor_shape_string(expectedTimestepShape) + ", got " +
                    tensor_shape_string(timestepShape) + ".";
            close();
            return false;
        }
        return true;
    }

    bool run(
            const std::vector<float>& sample,
            double timestep,
            const FloatTensorData& embeddings,
            FloatTensorData& noise,
            std::string& error) {
        constexpr int kLatentElements = 4 * 64 * 64;
        if (session_ == nullptr || interpreter_ == nullptr) {
            error = "UNet session is not initialized.";
            return false;
        }
        if (!std::isfinite(timestep) || timestep < 0.0 || timestep >= 1000.0) {
            error = "UNet timestep must be finite and in [0, 999].";
            return false;
        }
        if (sample.size() != static_cast<size_t>(batch_ * kLatentElements)) {
            error = "UNet sample does not match the initialized conditional/CFG batch.";
            return false;
        }
        if (!validate_finite_float_vector(sample, "UNet sample", error) ||
            !validate_float_tensor_contract(
                embeddings,
                {batch_, 77, 768},
                "UNet encoder_hidden_states",
                error)) {
            return false;
        }
        if (!copy_vector_to_tensor<float>(sample_input_, sample, error) ||
            !copy_mnn_timestep_to_tensor(timestep_input_, timestep, error) ||
            !copy_vector_to_tensor<float>(encoder_input_, embeddings.values, error)) {
            return false;
        }
        const auto code = interpreter_->runSession(session_);
        if (code != MNN::NO_ERROR) {
            error = "UNet runSession failed: " + std::to_string(static_cast<int>(code)) +
                    " (" + mnn_error_code_name(code) + ")";
            return false;
        }
        auto* tensor = interpreter_->getSessionOutput(session_, "out_sample");
        if (!copy_tensor_to_float_vector(tensor, noise, error)) {
            error = "UNet output copy failed: " + error;
            return false;
        }
        if (noise.shape == std::vector<int>({batch_, 64, 64, 4})) {
            noise.values = nhwc_to_nchw(noise.values, batch_, 64, 64, 4);
            noise.shape = {batch_, 4, 64, 64};
        }
        return validate_float_tensor_contract(
                noise,
                {batch_, 4, 64, 64},
                "UNet output",
                error);
    }

private:
    void close() {
        if (interpreter_ && session_ != nullptr) {
            interpreter_->releaseSession(session_);
        }
        session_ = nullptr;
        sample_input_ = nullptr;
        timestep_input_ = nullptr;
        encoder_input_ = nullptr;
        interpreter_.reset();
        weight_path_.clear();
        batch_ = 0;
    }

    std::unique_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* sample_input_ = nullptr;
    MNN::Tensor* timestep_input_ = nullptr;
    MNN::Tensor* encoder_input_ = nullptr;
    std::string weight_path_;
    int batch_ = 0;
};

bool run_unet_direct(
        const std::string& root,
        const std::vector<float>& sample,
        double timestep,
        const FloatTensorData& embeddings,
        const std::string& backendMode,
        int threads,
        FloatTensorData& noise,
        std::string& error) {
    constexpr int kCfgBatch = 2;
    constexpr int kLatentElements = 4 * 64 * 64;
    if (!std::isfinite(timestep) || timestep < 0.0 || timestep >= 1000.0) {
        error = "UNet timestep must be in [0, 999].";
        return false;
    }
    if (sample.size() != static_cast<size_t>(kCfgBatch * kLatentElements)) {
        error = "UNet sample must have shape [2,4,64,64].";
        return false;
    }
    if (!validate_finite_float_vector(sample, "UNet sample", error)) {
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {2, 77, 768}, "UNet encoder_hidden_states", error)) {
        return false;
    }
    const auto path = root + "/unet.mnn";
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct create_interpreter_start");
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create UNet interpreter.";
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct create_interpreter_completed");
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = "UNet session configuration failed: " + config_error;
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet direct create_session_start backend=%s threads=%d",
                        backendMode.c_str(), threads);
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create UNet session.";
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct create_session_completed");
    auto* sampleInput = interpreter->getSessionInput(session, "sample");
    auto* timestepInput = interpreter->getSessionInput(session, "timestep");
    auto* encoderInput = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (sampleInput == nullptr || timestepInput == nullptr || encoderInput == nullptr) {
        interpreter->releaseSession(session);
        error = "UNet input tensor not found before resize.";
        return false;
    }
    interpreter->resizeTensor(sampleInput, {kCfgBatch, 4, 64, 64});
    interpreter->resizeTensor(timestepInput, {1});
    interpreter->resizeTensor(encoderInput, {kCfgBatch, 77, 768});
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct resize_session_start");
    interpreter->resizeSession(session, 1);
    int resizeStatus = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet direct resize_session_completed status=%d", resizeStatus);
    sampleInput = interpreter->getSessionInput(session, "sample");
    timestepInput = interpreter->getSessionInput(session, "timestep");
    encoderInput = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (resizeStatus != 0 || sampleInput == nullptr || timestepInput == nullptr || encoderInput == nullptr) {
        interpreter->releaseSession(session);
        error = "UNet resizeSession failed with status " + std::to_string(resizeStatus) + ".";
        return false;
    }
    std::string copyError;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct copy_inputs_start");
    if (!copy_vector_to_tensor<float>(sampleInput, sample, copyError)) {
        interpreter->releaseSession(session);
        error = "UNet sample input copy failed: " + copyError;
        return false;
    }
    // Exporters differ: some SD1.5 MNN graphs expose an int32 timestep while
    // others retain the scheduler's float32 value.  Always dispatch through the
    // type-aware helper; truncating a fractional DPM++ timestep silently turns
    // the denoiser into a noise generator even though every graph invocation
    // reports success.
    if (!copy_mnn_timestep_to_tensor(timestepInput, static_cast<double>(timestep), copyError)) {
        interpreter->releaseSession(session);
        error = "UNet timestep input copy failed: " + copyError;
        return false;
    }
    if (!copy_vector_to_tensor<float>(encoderInput, embeddings.values, copyError)) {
        interpreter->releaseSession(session);
        error = "UNet encoder_hidden_states input copy failed: " + copyError;
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct copy_inputs_completed");
    const auto runStarted = now_ms();
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct run_session_start");
    const auto code = interpreter->runSession(session);
    const auto runMs = now_ms() - runStarted;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet direct run_session_completed code=%d runMs=%lld",
                        static_cast<int>(code), static_cast<long long>(runMs));
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = "UNet runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "out_sample");
    const bool ok = copy_tensor_to_float_vector(tensor, noise, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = "UNet output copy failed: " + error;
        return false;
    }
    if (noise.shape == std::vector<int>({kCfgBatch, 64, 64, 4})) {
        noise.values = nhwc_to_nchw(noise.values, kCfgBatch, 64, 64, 4);
        noise.shape = {kCfgBatch, 4, 64, 64};
    }
    if (!validate_float_tensor_contract(noise, {kCfgBatch, 4, 64, 64}, "UNet output", error)) {
        return false;
    }
    return true;
}

bool run_vae_decoder_direct(
        const std::string& root,
        const std::vector<float>& latent,
        const std::string& backendMode,
        int threads,
        FloatTensorData& image,
        std::string& error) {
    constexpr int kLatentElements = 4 * 64 * 64;
    if (latent.size() != static_cast<size_t>(kLatentElements)) {
        error = "VAE latent input must have shape [1,4,64,64].";
        return false;
    }
    if (!validate_finite_float_vector(latent, "VAE latent input", error)) {
        return false;
    }
    const auto path = root + "/vae_decoder.mnn";
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create VAE decoder interpreter.";
        return false;
    }
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error, true)) {
        error = "VAE decoder session configuration failed: " + config_error;
        return false;
    }
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create VAE decoder session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "latent_sample");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE latent_sample tensor is missing before resize.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 4, 64, 64});
    interpreter->resizeSession(session, 1);
    int resizeStatus = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    input = interpreter->getSessionInput(session, "latent_sample");
    if (resizeStatus != 0 || input == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE resizeSession failed with status " + std::to_string(resizeStatus) + ".";
        return false;
    }
    const auto resizedShape = mnn_tensor_shape(input);
    if (resizedShape != std::vector<int>({1, 4, 64, 64})) {
        interpreter->releaseSession(session);
        error = "VAE latent_sample shape mismatch after resize: expected [1,4,64,64], got " +
                tensor_shape_string(resizedShape) + ".";
        return false;
    }
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, latent, copyError)) {
        interpreter->releaseSession(session);
        error = "VAE input copy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = "VAE runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "sample");
    if (tensor == nullptr) tensor = interpreter->getSessionOutput(session, "output");
    if (tensor == nullptr) tensor = interpreter->getSessionOutput(session, nullptr);
    if (tensor == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE decoder output tensor is missing (expected sample/output).";
        return false;
    }
    const bool ok = copy_tensor_to_float_vector(tensor, image, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = "VAE output copy failed: " + error;
        return false;
    }
    if (image.shape == std::vector<int>({1, 512, 512, 3})) {
        image.values = nhwc_to_nchw(image.values, 1, 512, 512, 3);
        image.shape = {1, 3, 512, 512};
    }
    if (!validate_float_tensor_contract(image, {1, 3, 512, 512}, "VAE output", error)) {
        return false;
    }
    return true;
}

#if 0
// Disabled legacy direct sampler. Production direct generation is driven only
// by mca::diffusion::DiffusionScheduler and its versioned execution contract.
bool pndm_step(
        const std::vector<float>& sample,
        std::vector<std::vector<float>>& ets,
        std::vector<float>& firstSample,
        const std::vector<float>& modelOutput,
        int index,
        const std::vector<int>& timesteps,
        const std::vector<float>& alphas,
        std::vector<float>& previous,
        std::string& error) {
    return mca::mnn::pndm_step(
            sample,
            ets,
            firstSample,
            modelOutput,
            index,
            timesteps,
            alphas,
            previous,
            error);
}
#endif

json encode_sd15_prompt_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson) {
    return mca::mnn::encode_sd15_prompt_embeddings_to_file(
            root,
            prompt,
            negativePrompt,
            outputPath,
            backendMode,
            threads,
            useCfg,
            promptWeightingEnabled,
            textualInversionJson,
            &encode_community_clip_embeddings_to_file,
            [](const std::string& r, const std::vector<int>& ids,
               const std::string& bm, int th, FloatTensorData& emb, std::string& err) {
                return run_text_encoder_direct(r, ids, bm, th, emb, err);
            });
}

json run_mnn_sd15_interpreter_direct(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        const MnnSemanticExecutionContract& contract,
        MnnNativeExecutionEvidence& evidence,
        std::function<void(int)> progressCallback) {
    std::string error;
    if (progressCallback) {
        progressCallback(0);
    }
    MnnOpenedTextEncoderBinding textEncoderBinding;
    if (!open_mnn_text_encoder_binding(
            root,
            contract.text_encoder_asset,
            textEncoderBinding,
            evidence.text_encoder_asset,
            error)) {
        return mnn_text_encoder_asset_failure(error);
    }
    std::vector<int> ids;
    mca::image::ClipTokenPair executedTokenPair;
    bool hasExecutedTokenPair = false;
    if (!tokenize_mnn_sd15_prompt(
            root,
            prompt,
            contract,
            ids,
            executedTokenPair,
            hasExecutedTokenPair,
            evidence,
            error)) {
        json failure = {{"ok", false}, {"error", error}};
        if (error.find("cannot apply non-unity prompt weights before the Transformer") !=
            std::string::npos) {
            failure["errorCode"] = "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED";
        }
        return failure;
    }
    mca::image::textual_inversion::Selection textualInversionSelection;
    mca::image::textual_inversion::Audit textualInversionAudit;
    mca::image::ClipConditionedPair conditionedPair;
    const bool textualInversionRequested = !contract.textual_inversion_json.empty();
    const bool useCommunityClip = mnn_contract_uses_community_clip(contract);
    const std::string selectedTextEncoderPath = textEncoderBinding.active
            ? textEncoderBinding.graph.canonical_path
            : join_path(root, "clip_v2.mnn");
    const std::string clipWeightPath = textEncoderBinding.active
            ? textEncoderBinding.weight.canonical_path
            : join_path(root, "clip_v2.mnn.weight");
    const bool useClipWeight = textEncoderBinding.active
            ? textEncoderBinding.weight.fd >= 0
            : nonempty_regular_file_exists(clipWeightPath);
    const std::string pinnedClipWeightPath =
            !textEncoderBinding.active && useClipWeight ? clipWeightPath : "";
    mca::image::execution_assets::Binding executionAssets;
    if (textualInversionRequested && !prepare_mnn_execution_assets(
            contract.textual_inversion_json, "MNN_DIFFUSION", executionAssets, error)) {
        return mnn_execution_asset_failure(error);
    }
    if (textualInversionRequested) {
        std::vector<std::string> requiredAssetPaths = {
            selectedTextEncoderPath,
            join_path(root, "tokenizer.json"),
            join_path(root, "token_emb.bin"),
            join_path(root, "pos_emb.bin"),
        };
        if (useClipWeight) {
            requiredAssetPaths.push_back(clipWeightPath);
        }
        if (!mnn_execution_assets_match(executionAssets, requiredAssetPaths, error)) {
            return mnn_execution_asset_failure(error);
        }
    }
    if (textualInversionRequested) {
        if (!useCommunityClip || !has_community_clip_bundle(root, selectedTextEncoderPath)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_GRAPH_INPUT_UNSUPPORTED"},
                {"error", "MNN textual inversion requires the declared host-writable input_embedding text encoder."}
            });
        }
        mca::image::ClipTokenizerConfig tokenizerConfig;
        tokenizerConfig.bos_id = contract.tokenizer_bos_id;
        tokenizerConfig.eos_id = contract.tokenizer_eos_id;
        tokenizerConfig.pad_id = contract.tokenizer_pad_id;
        tokenizerConfig.max_length = contract.tokenizer_max_length;
        tokenizerConfig.enable_prompt_weighting = contract.prompt_weighting_supported;
        if (!mca::image::textual_inversion::load_selection(
                contract.textual_inversion_json,
                "MNN_DIFFUSION",
                false,
                &textualInversionSelection,
                &textualInversionAudit,
                &error) ||
            !mnn_selection_matches_execution_assets(
                textualInversionSelection, executionAssets, error) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                contract.negative_prompt,
                tokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, false),
                &conditionedPair,
                &error)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_CONDITIONING_FAILED"},
                {"error", error}
            });
        }
        mca::image::textual_inversion::record_conditioned_pair(
            conditionedPair, false, &textualInversionAudit);
        executedTokenPair.negative = conditionedPair.negative.tokens;
        executedTokenPair.positive = conditionedPair.positive.tokens;
        hasExecutedTokenPair = true;
        const auto conditionedIds = executedTokenPair.negative_then_positive();
        ids.assign(conditionedIds.begin(), conditionedIds.end());
    }
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    json debug = json::object();
    FloatTensorData embeddings;
    const int conditioningBatch = contract.use_cfg ? 2 : 1;
    std::vector<int> positiveIds(ids.begin() + 77, ids.end());
    const std::vector<float> unitTokenWeights(77U, 1.0f);
    const auto& positiveTokenWeights = hasExecutedTokenPair
            ? executedTokenPair.positive.weights
            : unitTokenWeights;
    const auto& negativeTokenWeights = hasExecutedTokenPair
            ? executedTokenPair.negative.weights
            : unitTokenWeights;
    if (progressCallback) {
        progressCallback(1);
    }
    if (useCommunityClip) {
        size_t tokenEmbeddingBytes = 0;
        std::string detectedEmbeddingType;
        if (!inspect_community_token_embedding_file(
                root,
                tokenEmbeddingBytes,
                detectedEmbeddingType,
                error)) {
            return json({
                {"ok", false},
                {"errorCode", "PACKAGE_FORMAT_INVALID"},
                {"field", "token_emb.bin"},
                {"error", error}
            });
        }
        if (detectedEmbeddingType != contract.embedding_disk_data_type) {
            return json({
                {"ok", false},
                {"errorCode", "EXECUTION_CONTRACT_MISMATCH"},
                {"field", "embeddingDiskDataType"},
                {"error", "embeddingDiskDataType mismatch: resolved=" +
                        contract.embedding_disk_data_type + ", native=" +
                        detectedEmbeddingType + "."}
            });
        }
        if (includeDebug) debug["tokenEmbeddingBytes"] = tokenEmbeddingBytes;
        FloatTensorData positiveEmbeddings;
        std::string positiveType;
        json positiveDebug;
        if (!run_community_clip_encoder_direct(
                root,
                pinnedClipWeightPath,
                positiveIds,
                positiveTokenWeights,
                contract.backend_mode,
                contract.threads,
                positiveEmbeddings,
                error,
                includeDebug ? &positiveDebug : nullptr,
                &positiveType,
                textualInversionRequested ? &conditionedPair.positive : nullptr,
                &textEncoderBinding)) {
            return json({{"ok", false}, {"error", error}});
        }
        if (positiveType != detectedEmbeddingType) {
            return json({
                {"ok", false},
                {"errorCode", "PACKAGE_FORMAT_INVALID"},
                {"field", "token_emb.bin"},
                {"error", "PACKAGE_FORMAT_INVALID: community CLIP token embedding dtype changed during windowed reading."}
            });
        }
        if (contract.use_cfg) {
            std::vector<int> negativeIds(ids.begin(), ids.begin() + 77);
            FloatTensorData negativeEmbeddings;
            std::string negativeType;
            json negativeDebug;
            if (!run_community_clip_encoder_direct(
                    root,
                    pinnedClipWeightPath,
                    negativeIds,
                    negativeTokenWeights,
                    contract.backend_mode,
                    contract.threads,
                    negativeEmbeddings,
                    error,
                    includeDebug ? &negativeDebug : nullptr,
                    &negativeType,
                    textualInversionRequested ? &conditionedPair.negative : nullptr,
                    &textEncoderBinding)) {
                return json({{"ok", false}, {"error", error}});
            }
            if (negativeType != positiveType) {
                return json({
                    {"ok", false},
                    {"errorCode", "PACKAGE_FORMAT_INVALID"},
                    {"field", "token_emb.bin"},
                    {"error", "PACKAGE_FORMAT_INVALID: negative and positive CLIP reads reported different token embedding dtypes."}
                });
            }
            embeddings.shape = {2, 77, 768};
            embeddings.values.reserve(
                    negativeEmbeddings.values.size() + positiveEmbeddings.values.size());
            embeddings.values.insert(
                    embeddings.values.end(),
                    negativeEmbeddings.values.begin(),
                    negativeEmbeddings.values.end());
            embeddings.values.insert(
                    embeddings.values.end(),
                    positiveEmbeddings.values.begin(),
                    positiveEmbeddings.values.end());
            if (includeDebug) {
                debug["communityClip"] = {
                    {"negative", negativeDebug},
                    {"positive", positiveDebug}
                };
            }
        } else {
            embeddings = std::move(positiveEmbeddings);
            if (includeDebug) debug["communityClip"] = {{"positive", positiveDebug}};
        }
        evidence.embedding_disk_data_type = positiveType;
    } else {
        if (hasExecutedTokenPair && !validate_clip_pair_weights_for_token_id_graph(
                executedTokenPair, contract.use_cfg, error)) {
            return json({
                    {"ok", false},
                    {"errorCode", "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED"},
                    {"error", error}
            });
        }
        if (!run_text_encoder_direct(
                root,
                contract.use_cfg ? ids : positiveIds,
                contract.backend_mode,
                contract.threads,
                embeddings,
                error,
                &textEncoderBinding)) {
            return json({{"ok", false}, {"error", error}});
        }
        evidence.embedding_disk_data_type = "GRAPH_INTERNAL";
    }
    if (!validate_float_tensor_contract(
            embeddings,
            {conditioningBatch, 77, 768},
            "Text encoder output",
            error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (textEncoderBinding.active && !verify_mnn_text_encoder_binding(
            contract.text_encoder_asset, textEncoderBinding, error)) {
        return mnn_text_encoder_asset_failure(error);
    }
    if (evidence.embedding_disk_data_type != contract.embedding_disk_data_type) {
        return json({
            {"ok", false},
            {"errorCode", "EXECUTION_CONTRACT_MISMATCH"},
            {"field", "embeddingDiskDataType"},
            {"error", "embeddingDiskDataType mismatch: resolved=" +
                    contract.embedding_disk_data_type + ", native=" +
                    evidence.embedding_disk_data_type + "."}
        });
    }
    evidence.native_prompt_execution_sha256 =
            mca::image::image_prompt_execution_sha256(prompt, contract.negative_prompt);
    if (evidence.native_prompt_execution_sha256.size() != 64U) {
        return json({
            {"ok", false},
            {"errorCode", "EXECUTION_CONTRACT_MISMATCH"},
            {"field", "nativePromptExecutionSha256"},
            {"error", "The prompts consumed by native MNN conditioning could not be framed and hashed."}
        });
    }
    if (includeDebug) {
        const size_t rowSize = 77U * 768U;
        debug["tokenIds"] = contract.use_cfg ? ids : positiveIds;
        if (contract.use_cfg) {
            std::vector<float> negative(embeddings.values.begin(), embeddings.values.begin() + rowSize);
            std::vector<float> positive(embeddings.values.begin() + rowSize, embeddings.values.end());
            debug["negativeEmbeddingStats"] = float_vector_stats_json(negative);
            debug["positiveEmbeddingStats"] = float_vector_stats_json(positive);
            debug["positiveNegativeAbsDiffStats"] = float_vector_abs_diff_stats_json(positive, negative);
        } else {
            debug["positiveEmbeddingStats"] = float_vector_stats_json(embeddings.values);
        }
    }
    if (progressCallback) progressCallback(5);

    DirectMnnUnetSession unetSession;
    const auto unetBatchCandidates =
            mca::mnn::diffusionBatchCandidates(contract.use_cfg);
    std::vector<std::string> unetBatchErrors;
    bool unetInitialized = false;
    for (size_t index = 0; index < unetBatchCandidates.count; ++index) {
        const int candidate = unetBatchCandidates.values[index];
        std::string candidateError;
        if (unetSession.initialize(
                root,
                contract.backend_mode,
                contract.threads,
                candidate,
                candidateError)) {
            unetInitialized = true;
            break;
        }
        unetBatchErrors.push_back(
                "batch=" + std::to_string(candidate) + ": " + candidateError);
    }
    if (!unetInitialized) {
        std::ostringstream message;
        message << "UNet could not initialize a compatible CFG batch";
        for (size_t index = 0; index < unetBatchErrors.size(); ++index) {
            message << (index == 0 ? ": " : "; ") << unetBatchErrors[index];
        }
        const auto messageText = message.str();
        json failure = {
                {"ok", false},
                {"error", messageText},
                {"backendMode", contract.backend_mode}
        };
        // This is intentionally emitted only for a CPU SD1.5 graph whose
        // real resize/encode attempt returned NOT_SUPPORT.  Callers may then
        // choose an explicitly permitted OpenCL retry while preserving the
        // original CPU failure in execution evidence.
        if (contract.backend_mode == "cpu" &&
            mnn_sd15_unet_backend_unsupported(unetBatchErrors)) {
            failure["errorCode"] = "MNN_UNET_BACKEND_UNSUPPORTED";
            failure["nativeStatus"] = static_cast<int>(MNN::NOT_SUPPORT);
            failure["fallbackEligible"] = true;
            failure["unsupportedOps"] = {"GroupNorm", "FmhaV2", "SplitGeLU"};
            failure["error"] =
                    "MNN SD1.5 UNet cannot execute on the CPU backend: the graph contains "
                    "fused operators not registered by this MNN CPU build. " + messageText;
        }
        return failure;
    }
    bool sequentialCfg =
            mca::mnn::usesSequentialCfg(contract.use_cfg, unetSession.batch());
    size_t graphInvocationsPerTimestep =
            mca::mnn::graphInvocationsPerTimestep(contract.use_cfg, unetSession.batch());
    bool runtimeBatchFallbackAttempted = false;
    if (graphInvocationsPerTimestep == 0U) {
        return json({
                {"ok", false},
                {"error", "UNet batch policy selected an invalid execution plan."}
        });
    }
    if (includeDebug) {
        debug["unetSessionBatch"] = unetSession.batch();
        debug["cfgExecutionMode"] = sequentialCfg ? "sequential" : "batched";
        debug["graphInvocationsPerTimestep"] = graphInvocationsPerTimestep;
        debug["unetBatchFallbacks"] = unetBatchErrors;
    }

    mca::diffusion::DiffusionScheduler scheduler(contract.scheduler.config);
    if (!scheduler.set_timesteps(contract.scheduler.steps, &error)) {
        return json({{"ok", false}, {"error", "MNN direct scheduler setup failed: " + error}});
    }
    if (scheduler.timesteps().size() != contract.scheduler.expected_timetable_count) {
        return json({
            {"ok", false},
            {"error", "MNN direct scheduler timetableCount mismatch: resolved=" +
                    std::to_string(contract.scheduler.expected_timetable_count) +
                    ", native=" + std::to_string(scheduler.timesteps().size()) + "."}
        });
    }
    const size_t branchCount = contract.use_cfg ? 2U : 1U;
    if (scheduler.expected_unet_execution_count() >
        std::numeric_limits<size_t>::max() / branchCount ||
        scheduler.expected_unet_execution_count() * branchCount !=
            contract.scheduler.expected_unet_execution_count) {
        return json({
            {"ok", false},
            {"error", "MNN direct scheduler unetExecutionCount conflicts with the resolved CFG contract."}
        });
    }
    evidence.timetable_count = scheduler.timesteps().size();
    evidence.timesteps = scheduler.timesteps();
    evidence.sigmas = scheduler.sigmas();
    evidence.init_noise_sigma = scheduler.init_noise_sigma();

    std::vector<float> latent(4 * 64 * 64);
    std::mt19937 rng(contract.seed);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    for (auto& value : latent) {
        value = normal(rng) * static_cast<float>(scheduler.init_noise_sigma());
    }
    if (!validate_finite_float_vector(latent, "Initial scheduler latent", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    const size_t timetableCount = scheduler.timesteps().size();
    for (size_t i = 0; i < timetableCount; ++i) {
        if (progressCallback) {
            progressCallback(5 + static_cast<int>(i * 80U / timetableCount));
        }
        std::vector<float> modelInput;
        if (!scheduler.scale_model_input(latent, i, &modelInput, &error)) {
            return json({{"ok", false}, {"error", "MNN direct scheduler input scaling failed: " + error}});
        }
        FloatTensorData batchedNoise;
        bool executeSequentialCfg = sequentialCfg;
        if (!executeSequentialCfg) {
            std::vector<float> sampleBatch;
            sampleBatch.reserve(
                    modelInput.size() * static_cast<size_t>(conditioningBatch));
            sampleBatch.insert(sampleBatch.end(), modelInput.begin(), modelInput.end());
            if (contract.use_cfg) {
                sampleBatch.insert(sampleBatch.end(), modelInput.begin(), modelInput.end());
            }
            if (!unetSession.run(
                    sampleBatch,
                    scheduler.timesteps()[i],
                    embeddings,
                    batchedNoise,
                    error)) {
                if (!mca::mnn::shouldRetryFirstBatchedCfgExecution(
                        contract.use_cfg,
                        unetSession.batch(),
                        i,
                        runtimeBatchFallbackAttempted)) {
                    return json({{"ok", false}, {"error", error}});
                }
                runtimeBatchFallbackAttempted = true;
                const std::string batch2ExecutionError = error;
                std::string batch1InitializationError;
                if (!unetSession.initialize(
                        root,
                        contract.backend_mode,
                        contract.threads,
                        1,
                        batch1InitializationError)) {
                    return json({
                            {"ok", false},
                            {"error", "UNet batch-2 first execution failed: " +
                                    batch2ExecutionError +
                                    " Batch-1 compatibility reinitialization failed: " +
                                    batch1InitializationError}
                    });
                }
                sequentialCfg =
                        mca::mnn::usesSequentialCfg(contract.use_cfg, unetSession.batch());
                graphInvocationsPerTimestep =
                        mca::mnn::graphInvocationsPerTimestep(
                                contract.use_cfg, unetSession.batch());
                if (!sequentialCfg || graphInvocationsPerTimestep != 2U) {
                    return json({
                            {"ok", false},
                            {"error", "UNet batch-1 compatibility reinitialization selected an invalid execution plan."}
                    });
                }
                const std::string runtimeFallbackReason =
                        "batch=2 first execution: " + batch2ExecutionError;
                unetBatchErrors.push_back(runtimeFallbackReason);
                if (includeDebug) {
                    debug["unetSessionBatch"] = unetSession.batch();
                    debug["cfgExecutionMode"] = "sequential";
                    debug["graphInvocationsPerTimestep"] = graphInvocationsPerTimestep;
                    debug["unetBatchFallbacks"] = unetBatchErrors;
                    debug["unetRuntimeFallbackReason"] = batch2ExecutionError;
                    debug["unetRuntimeFallbackTimestepIndex"] = i;
                }
                error.clear();
                executeSequentialCfg = true;
            } else {
                ++evidence.graph_invocation_count;
            }
        }
        if (executeSequentialCfg) {
            constexpr size_t kEmbeddingBranchElements = 77U * 768U;
            batchedNoise.shape = {2, 4, 64, 64};
            batchedNoise.values.clear();
            batchedNoise.values.reserve(modelInput.size() * 2U);
            for (size_t branch = 0; branch < 2U; ++branch) {
                FloatTensorData branchEmbeddings;
                branchEmbeddings.shape = {1, 77, 768};
                const auto begin = embeddings.values.begin() +
                        static_cast<std::ptrdiff_t>(branch * kEmbeddingBranchElements);
                const auto end = begin + static_cast<std::ptrdiff_t>(kEmbeddingBranchElements);
                branchEmbeddings.values.assign(begin, end);
                FloatTensorData branchNoise;
                if (!unetSession.run(
                        modelInput,
                        scheduler.timesteps()[i],
                        branchEmbeddings,
                        branchNoise,
                        error)) {
                    return json({
                            {"ok", false},
                            {"error", "UNet sequential CFG branch " +
                                    std::to_string(branch) + " failed: " + error}
                    });
                }
                batchedNoise.values.insert(
                        batchedNoise.values.end(),
                        branchNoise.values.begin(),
                        branchNoise.values.end());
                ++evidence.graph_invocation_count;
                if (branch == 0U) {
                    const auto cancelled = mnn_asset_cancel_callback();
                    if (cancelled()) {
                        return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}});
                    }
                }
            }
        }
        evidence.unet_execution_count += branchCount;
        if (!validate_float_tensor_contract(
                batchedNoise,
                {conditioningBatch, 4, 64, 64},
                "UNet output",
                error)) {
            return json({{"ok", false}, {"error", error}});
        }
        std::vector<float> guided(latent.size());
        for (size_t j = 0; j < latent.size(); ++j) {
            if (contract.use_cfg) {
                const float uncond = batchedNoise.values[j];
                const float cond = batchedNoise.values[latent.size() + j];
                guided[j] = contract.cfg_scale * (cond - uncond) + uncond;
            } else {
                guided[j] = batchedNoise.values[j];
            }
        }
        if (!validate_finite_float_vector(guided, "Guided UNet noise", error)) {
            return json({{"ok", false}, {"error", error}});
        }
        mca::diffusion::SchedulerStepResult stepResult;
        mca::diffusion::SchedulerStepOptions stepOptions;
        stepOptions.eta = contract.scheduler.eta;
        if (!scheduler.step(guided, i, latent, &stepResult, &error, stepOptions)) {
            return json({{"ok", false}, {"error", "MNN direct scheduler step failed: " + error}});
        }
        latent = std::move(stepResult.previous_sample);
        if (includeDebug && i + 1U == timetableCount) {
            debug["finalLatentStats"] = float_vector_stats_json(latent);
        }
        if (progressCallback) {
            progressCallback(5 + static_cast<int>((i + 1U) * 80U / timetableCount));
        }
    }
    const size_t expectedGraphInvocationCount =
            timetableCount * graphInvocationsPerTimestep;
    if (scheduler.completed_step_count() != timetableCount ||
        evidence.unet_execution_count != contract.scheduler.expected_unet_execution_count ||
        evidence.graph_invocation_count != expectedGraphInvocationCount) {
        return json({
            {"ok", false},
            {"error", "MNN direct execution counts differ from the resolved scheduler contract."}
        });
    }
    std::vector<float> vaeInput = latent;
    if (contract.vae_scaling_location == MnnVaeScalingLocation::HostBeforeGraph) {
        const float inverseScale = static_cast<float>(1.0 / contract.vae_scaling_factor);
        for (float& value : vaeInput) value *= inverseScale;
    }
    if (!validate_finite_float_vector(vaeInput, "VAE decoder input", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    FloatTensorData image;
    if (progressCallback) progressCallback(90);
    if (!run_vae_decoder_direct(
            root,
            vaeInput,
            contract.backend_mode,
            contract.threads,
            image,
            error)) {
            return json({{"ok", false}, {"error", error}});
    }
    if (includeDebug) {
        debug["vaeOutputStats"] = float_vector_stats_json(image.values);
        debug["vaeOutputShape"] = image.shape;
    }
    if (progressCallback) progressCallback(95);
    if (!mca::mnn::write_vae_image_to_file(image, outputPath, contract.vae_output_range, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (textualInversionRequested &&
        !finalize_mnn_execution_assets(executionAssets, error)) {
        return mnn_execution_asset_failure(error);
    }
    if (textualInversionRequested) {
        evidence.textual_inversions =
            mca::image::textual_inversion::artifacts_json(textualInversionSelection);
        evidence.textual_inversion_evidence =
            mca::image::textual_inversion::evidence_json(
                textualInversionSelection,
                textualInversionAudit,
                true);
        evidence.execution_assets = executionAssets;
        evidence.has_execution_assets = true;
    }
    if (progressCallback) progressCallback(100);
    json result = {
        {"ok", true},
        {"path", outputPath},
        {"mimeType", "image/png"},
        {"graphInvocationCount", evidence.graph_invocation_count},
        {"initNoiseSigma", evidence.init_noise_sigma}
    };
    if (includeDebug) result["debug"] = debug;
    return result;
}

json run_mnn_sd15_module_runner(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        int steps,
        int seed,
        const std::string& backendMode,
        int memoryMode,
        std::function<void(int)> progressCallback) {
    std::string canonicalBackend;
    std::string error;
    if (!canonical_mnn_diffusion_backend(backendMode, canonicalBackend, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (memoryMode < 0 || memoryMode > 2) {
        return json({{"ok", false}, {"error", "MNN-Diffusion memoryMode must be 0, 1, or 2."}});
    }
    if (progressCallback) {
        progressCallback(0);
    }
    const auto backend = canonicalBackend == "opencl" ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    std::unique_ptr<MNN::DIFFUSION::Diffusion> diffusion(
            MNN::DIFFUSION::Diffusion::createDiffusion(
                    root,
                    MNN::DIFFUSION::STABLE_DIFFUSION_1_5,
                    backend,
                    memoryMode));
    if (!diffusion) {
        return json({{"ok", false}, {"error", "Failed to create MNN-Diffusion module runner."}});
    }
    if (progressCallback) {
        progressCallback(1);
    }
    if (!diffusion->load()) {
        return json({{"ok", false}, {"error", "MNN-Diffusion module runner failed to load."}});
    }
    if (progressCallback) {
        progressCallback(2);
    }
    const bool ok = diffusion->run(prompt, outputPath, steps, seed, progressCallback);
    if (!ok) {
        return json({{"ok", false}, {"error", "MNN-Diffusion module runner failed to generate image."}});
    }
    return json({{"ok", true}, {"path", outputPath}, {"mimeType", "image/png"}});
}
#endif

#if MCA_WITH_MNN_LLM
using mca::mnn::ParsedMnnChatMessages;
using mca::mnn::MnnPromptCacheRuntimeState;
using mca::mnn::MnnPromptCacheContext;
using mca::mnn::MnnPromptCacheManager;
using mca::mnn::MnnHasLogBuffer;
using mca::mnn::mnn_context_log_snapshot;
using mca::mnn::mnn_role_protocol_stop_reason;

inline mca::mnn::MnnPromptCacheContext mnn_prompt_cache_context_locked() {
    mca::mnn::MnnPromptCacheContext ctx;
    ctx.llm = g_llm;
    ctx.cache = &g_mnn_prompt_cache;
    ctx.lifecycle = &g_mnn_request_lifecycle;
    ctx.active_stop_markers = &g_active_stop_markers;
    ctx.generation_stop_reason = &g_generation_stop_reason;
    ctx.prefill_total_tokens = &g_mnn_prefill_total_tokens;
    ctx.prefill_completed_tokens = &g_mnn_prefill_completed_tokens;
    ctx.hits = &g_mnn_prompt_cache_hits;
    ctx.misses = &g_mnn_prompt_cache_misses;
    ctx.stop_requested = &g_stop_requested;
    return ctx;
}

void clear_mnn_prompt_cache_tracking_locked(const std::string& reason) {
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).clear_tracking(reason);
}

void reset_mnn_native_prompt_cache_locked() {
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).reset_native_cache();
}

size_t mnn_effective_kv_history_locked() {
    return mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).effective_kv_history();
}

std::string mnn_prompt_cache_assistant_text_locked() {
    return mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).assistant_text();
}

void trim_mnn_protocol_suffix_from_live_history_locked() {
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).trim_protocol_suffix_from_live_history();
}

// Contract: MnnPromptCacheNativeContractTest global assertions:
// g_llm->getCurrentHistory()
// g_mnn_prompt_cache.reused_tokens + g_mnn_prompt_cache.prefilled_tokens
// \"promptCache\", \"reusedTokens\", \"prefillTokens\", computed_prefill_tokens * 1000.0 / prefill_ms, \"effectivePromptTps\", \"promptCacheHit\"
// reset_before_next_text, multimodal_request_requires_fresh_visual_prefill, g_mnn_prompt_cache.prefix_extended

bool mnn_chat_messages_prefix(
        const std::vector<MNN::Transformer::ChatMessage>& prefix,
        const std::vector<MNN::Transformer::ChatMessage>& messages) {
    // contract: ZERO WIDTH SPACE, normalize(prefix[index].second)
    return mca::mnn::mnn_chat_messages_prefix(prefix, messages);
}

void mark_mnn_prompt_cache_disabled_locked(bool multimodal, const std::string& reason) {
    // contract: g_mnn_prompt_cache = MnnPromptCacheRuntimeState()
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).mark_disabled(multimodal, reason);
}

void prepare_mnn_text_prompt_cache_locked(
        std::vector<MNN::Transformer::ChatMessage>& messages) {
    // contract: mnn_effective_kv_history_locked()
    // contract: g_llm->syncPromptCache(g_mnn_prompt_cache.committed_messages)
    // contract: extendsCommittedTranscript
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).prepare_text_cache(messages);
}

bool capture_mnn_text_prefill_locked(std::string& error) {
    // contract: mnn_effective_kv_history_locked()
    // contract: g_mnn_prompt_cache.reused_tokens
    // contract: g_mnn_prompt_cache.prefilled_tokens
    return mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).capture_text_prefill(error);
}

void commit_mnn_text_prompt_cache_locked(const std::string& reason) {
    // contract: g_llm->syncPromptCache(committed)
    // contract: g_mnn_prompt_cache.committed = true
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).commit_text_cache(reason);
}

void rollback_mnn_text_prompt_cache_locked(
        const std::string& reason,
        bool forceClear) {
    // contract: g_llm->eraseHistory(g_mnn_prompt_cache.kv_history_before, 0)
    // contract: reset_mnn_native_prompt_cache_locked()
    // contract: completion_tokens_before_rollback
    // contract: mnn_effective_kv_history_locked()
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).rollback_text_cache(reason, forceClear);
}

void settle_mnn_text_prompt_cache_locked(const std::string& reason) {
    // contract: has_non_whitespace(mnn_prompt_cache_assistant_text_locked())
    // contract: rollback_mnn_text_prompt_cache_locked("empty_visible_response", true)
    // contract: commit_mnn_text_prompt_cache_locked(reason)
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).settle_text_cache(reason);
}

using mca::mnn::collect_mnn_raw_media_tags_from_content;
using mca::mnn::parse_chat_messages;
using mca::mnn::local_mnn_image_path;
using mca::mnn::prepareMnnVisualResponse;

MNN::Transformer::ChatMessages prepare_mnn_visual_response(const ParsedMnnChatMessages& parsed) {
    return prepareMnnVisualResponse(
            parsed,
            g_vision_ready,
            g_visual_model_path,
            g_llm,
            g_multimodal_system_prompt_suppressed,
            g_multimodal_history_suppressed,
            g_prompt_ends_inside_reasoning,
            capture_mnn_debug_prompt);
}

bool begin_mnn_response(
        const ParsedMnnChatMessages& parsed,
        const MNN::Transformer::ChatMessages* preparedVisualContent = nullptr) {
    if (!parsed.hasMediaInputs()) {
        g_multimodal_system_prompt_suppressed = false;
        g_multimodal_history_suppressed = false;
        const auto formatted_prompt = g_llm->apply_chat_template(parsed.messages);
        capture_mnn_debug_prompt(formatted_prompt);
        g_prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(formatted_prompt);
        g_llm->response(parsed.messages, g_output_stream.get(), "<eop>", 0);
        return false;
    }
    const auto visualMessages = preparedVisualContent == nullptr
            ? prepare_mnn_visual_response(parsed) : *preparedVisualContent;
    // Both overloads run a continuous native response. Repeated generate(1)
    // stepping is reserved for text; it must not discard Omni's visual prefill.
    if (mca::mnn::useMnnSingleUserVisualResponse(visualMessages)) {
        g_llm->response(visualMessages.front().second, g_output_stream.get(), "<eop>", g_max_new_tokens);
    } else {
        g_llm->response(visualMessages, g_output_stream.get(), "<eop>", g_max_new_tokens);
    }
    const auto* context = g_llm->getContext();
    if (context == nullptr || context->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) {
        // The visual component exists on disk, but this request proved that it
        // cannot produce a usable multimodal prefill. Keep later UI/API state
        // fail-closed until the model is explicitly reloaded and revalidated.
        g_vision_ready = false;
        throw std::runtime_error(
                "mnn_multimodal_prefill_failed: multimodal embedding or language prefill failed closed before decode.");
    }
    g_generated_steps = std::max(0, context->gen_seq_len);
    g_generation_active = false;
    return true;
}

json build_mnn_config(const std::string& config_path, const std::string& params_json, bool for_load) {
    json params = json::object();
    if (!params_json.empty()) {
        params = json::parse(params_json, nullptr, false);
        if (params.is_discarded() || !params.is_object()) params = json::object();
    }
    json config = json::object();
    const int n_ctx = std::max(1, opt_int(params, "n_ctx", 8192));
    const int n_threads = std::max(1, opt_int(params, "n_threads", 4));
    // Preserve the resolved MNN transport.  The JNI class is shared by CPU
    // and OpenCL runners, so hard-coding CPU here causes an OpenCL request to
    // load on CPU and then fail during prefill with the generic -203 code.
    std::string backendType = params.value("backend_type", std::string());
    if (backendType.empty()) backendType = params.value("backendType", std::string());
    if (backendType.empty()) backendType = params.value("backend", std::string());
    if (backendType.empty()) backendType = params.value("backendMode", std::string());
    if (backendType.empty()) backendType = params.value("backend_mode", std::string());
    const auto advancedForBackend = advanced_json_from_params(params);
    if (backendType.empty()) backendType = advancedForBackend.value("backend_type", std::string());
    if (backendType.empty()) backendType = advancedForBackend.value("backendType", std::string());
    if (backendType.empty()) backendType = advancedForBackend.value("backend", std::string());
    backendType = normalize_mnn_contract_enum(std::move(backendType));
    if (backendType == "gpu" || backendType == "open_cl") backendType = "opencl";
    if (backendType != "opencl") backendType = "cpu";
    config["backend_type"] = backendType;
    config["thread_num"] = n_threads;
    // CPU text inference must avoid the low-precision Arm kernels that can
    // yield non-finite logits on converted Qwen packages. OpenCL retains the
    // established low-precision path.
    config["precision"] = backendType == "cpu" ? "high" : "low";
    config["memory"] = "low";
    config["power"] = "normal";
    config["use_mmap"] = opt_bool(params, "mmap", true);
    config["kvcache_mmap"] = opt_bool(params, "kvcache_mmap", true);
    // The patched MNN ChatMessages path owns longest-common-prefix prompt
    // reuse. Keep it enabled for the loaded text runtime; beginCompletion()
    // switches it off for every request that carries media.
    config["reuse_kv"] = true;
    config["prompt_cache"] = true;
    // This bridge pre-fills in beginCompletion() and decodes one token per JNI call.
    // MNN's async default can leave the prefill output unreadable when the first
    // decode call arrives, producing a spurious immediate stop after a cold reload.
    config["async"] = false;
    config["max_all_tokens"] = n_ctx;
    config["n_ctx"] = n_ctx;
    config["chunk"] = std::max(32, opt_int(params, "chunk", 128));
    // MNN mmap/KV artifacts are ABI-sensitive. Reusing a cache written by a
    // different libllm build can preserve stale static/KV files even when
    // reuse_kv=false, producing image-dependent but semantically wrong output.
    // Namespace the cache by the verified vendor patch carried by this APK.
    config["tmp_path"] = parent_dir(config_path) +
            "/mca_mnn_mmap_" + MCA_MNN_RUNTIME_CACHE_NAMESPACE;
    const bool hide_reasoning = opt_bool(params, "hide_reasoning", false);
    // Loading receives LoadParams, which intentionally has no reasoning fields.
    // Defaulting to true here makes Qwen MNN packages enter hidden-thinking mode
    // before the per-request generation configuration can disable it.
    const bool enable_thinking = opt_bool(params, "enable_thinking", false);
    config["jinja"]["context"]["enable_thinking"] = enable_thinking && !hide_reasoning;
    const int thinking_budget = opt_int(params, "thinking_budget", -1);
    if (thinking_budget >= 0) {
        config["jinja"]["context"]["thinking_budget"] = thinking_budget;
    }
    if (!for_load) {
        config["max_new_tokens"] = std::max(1, opt_int(params, "n_predict", 512));
    }
    const auto advanced = advanced_json_from_params(params);
    const bool advancedHasNCtx = advanced.contains("n_ctx");
    const bool advancedHasMaxAllTokens = advanced.contains("max_all_tokens");
    const bool advancedHasNThreads = advanced.contains("n_threads");
    const bool advancedHasThreadNum = advanced.contains("thread_num");
    const bool advancedHasNPredict = advanced.contains("n_predict");
    const bool advancedHasMaxNewTokens = advanced.contains("max_new_tokens");
    merge_json_object(config, advanced);
    // Advanced parameters may use any of the public backend spellings.  Keep
    // one canonical value after merging so load and beginCompletion compare
    // the same signature and OpenCL is never reported as an unknown backend.
    std::string mergedBackend = config.value("backend_type", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backendType", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backend", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backendMode", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backend_mode", std::string());
    mergedBackend = normalize_mnn_contract_enum(std::move(mergedBackend));
    if (mergedBackend == "gpu" || mergedBackend == "open_cl") mergedBackend = "opencl";
    if (mergedBackend != "opencl") mergedBackend = "cpu";
    config["backend_type"] = mergedBackend;
    mca::mnn::applyCpuCacheSafety(config);
    if (advancedHasNCtx && !advancedHasMaxAllTokens) {
        config["max_all_tokens"] = opt_int(config, "n_ctx", n_ctx);
    } else if (advancedHasMaxAllTokens && !advancedHasNCtx) {
        config["n_ctx"] = opt_int(config, "max_all_tokens", n_ctx);
    }
    if (advancedHasNThreads && !advancedHasThreadNum) {
        config["thread_num"] = opt_int(config, "n_threads", n_threads);
    }
    if (!for_load && advancedHasNPredict && !advancedHasMaxNewTokens) {
        config["max_new_tokens"] = opt_int(config, "n_predict", 512);
    }
    // advanced_json is user-controlled, but this JNI bridge owns generation state:
    // it resets the full prompt, pre-fills synchronously, then decodes one token per
    // generateNextChunk() call. Keep the lifecycle invariants after merging
    // overrides. Do not overwrite jinja.context.enable_thinking here: the official
    // MNN runtime supports both modes and per-request A/B showed that silently
    // forcing no-thinking can materially change multimodal answer quality.
    config["async"] = false;
    // Do not allow an advanced override to silently disable the transaction
    // contract. The request-level multimodal guard below is the only path that
    // turns these flags off.
    config["reuse_kv"] = true;
    config["prompt_cache"] = true;
    // Apply after all user-controlled advanced config is merged.  The current
    // product has no audio attachment route, while Gemma 4's llm_config.json
    // advertises audio and MNN 3.6 crashes when that unused processor is
    // reconstructed after an unload.  Keep visual support intact.
    mca::mnn::applyProductMnnRuntimeCapabilityPolicy(config);
    if (g_vision_ready) {
        // Qwen3.5 visual A/B: the first cold load with a clean tmp directory
        // answers correctly, while the next load reusing MNN's generated
        // 0_0_0_0_0.static file produces a deterministic wrong visual answer.
        // reuse_kv=false does not protect this static mmap cache. Keep mmap for
        // text-only packages, but disable both persistent mmap paths for every
        // package that carries a validated visual graph.
        config["use_mmap"] = false;
        config["kvcache_mmap"] = false;
    }
    config["max_all_tokens"] = std::max(1, opt_int(config, "max_all_tokens", n_ctx));
    config["n_ctx"] = std::max(1, opt_int(config, "n_ctx", config.value("max_all_tokens", n_ctx)));
    config["thread_num"] = std::max(1, opt_int(config, "thread_num", n_threads));
    if (!for_load) {
        config["max_new_tokens"] = std::max(1, opt_int(config, "max_new_tokens", 512));
        mca::mnn::applySamplingConfig(config, params, advanced);
    }
    return config;
}

void destroy_llm_locked() {
    g_output_stream.reset();
    g_stream_buffer.reset();
    if (g_llm != nullptr) {
        MNN::Transformer::Llm::destroy(g_llm);
        g_llm = nullptr;
    }
    g_mnn_model_type.clear();
    g_mnn_backend_type = "cpu";
    g_multimodal_system_prompt_suppressed = false;
    g_multimodal_history_suppressed = false;
    g_sync_stepping = true;
    clear_mnn_prompt_cache_tracking_locked("model_unloaded");
    g_mnn_prompt_cache_hits = 0;
    g_mnn_prompt_cache_misses = 0;
    g_mnn_request_lifecycle.onModelUnloaded();
}

using mca::mnn::mnn_load_signature_differences;

bool context_finished_locked() {
    if (g_llm == nullptr || g_llm->getContext() == nullptr) return true;
    using MNN::Transformer::LlmStatus;
    const auto status = g_llm->getContext()->status;
    return g_eop_seen ||
           g_generated_steps >= g_max_new_tokens ||
           status == LlmStatus::USER_CANCEL ||
           status == LlmStatus::INTERNAL_ERROR ||
           status == LlmStatus::TIMEOUT ||
           g_llm->stoped();
}

std::string generation_stop_reason_locked() {
    if (g_stop_requested) return "stop_requested";
    if (g_llm == nullptr || g_llm->getContext() == nullptr) return "runner_unavailable";
    if (g_eop_seen) return "stop_marker";
    if (g_generated_steps >= g_max_new_tokens) return "max_new_tokens";
    using MNN::Transformer::LlmStatus;
    const auto status = g_llm->getContext()->status;
    switch (status) {
        case LlmStatus::NORMAL_FINISHED:
            return g_llm->stoped() ? "stop_token" : "normal_finished";
        case LlmStatus::MAX_TOKENS_FINISHED:
            return "mnn_max_tokens";
        case LlmStatus::USER_CANCEL:
            return "mnn_user_cancel";
        case LlmStatus::INTERNAL_ERROR:
            return "mnn_internal_error";
        case LlmStatus::TIMEOUT:
            return "mnn_timeout";
        case LlmStatus::NOT_LOADED:
            return "mnn_not_loaded";
        case LlmStatus::RUNNING:
            return g_llm->stoped() ? "stop_token" : "completed";
    }
    return "completed";
}

bool android_stepping_boundary_locked() {
    if (g_llm == nullptr || g_llm->getContext() == nullptr || g_stop_requested) return false;
    using MNN::Transformer::LlmStatus;
    const auto status = g_llm->getContext()->status;
    return !g_eop_seen &&
           g_generated_steps < g_max_new_tokens &&
           (status == LlmStatus::MAX_TOKENS_FINISHED || status == LlmStatus::NORMAL_FINISHED);
}

void restore_stepping_status_if_needed_locked() {
    if (!android_stepping_boundary_locked()) return;
    using MNN::Transformer::LlmStatus;
    auto* context = const_cast<MNN::Transformer::LlmContext*>(g_llm->getContext());
    context->status = LlmStatus::RUNNING;
}

void configure_mnn_stop_markers_locked(const json& params) {
    g_active_stop_markers = g_model_stop_markers.empty()
            ? default_mnn_stop_markers()
            : g_model_stop_markers;
    if (params.is_object()) {
        for (const char* key : {"stop_words", "stop", "stop_tokens"}) {
            const auto it = params.find(key);
            if (it != params.end()) {
                append_mnn_stop_markers_from_json(it.value(), g_active_stop_markers);
            }
        }
        const auto advanced = advanced_json_from_params(params);
        for (const char* key : {"stop_words", "stop", "stop_tokens"}) {
            const auto it = advanced.find(key);
            if (it != advanced.end()) {
                append_mnn_stop_markers_from_json(it.value(), g_active_stop_markers);
            }
        }
    }
    g_stream_protocol_filter.reset(g_active_stop_markers);
}

void filter_mnn_stop_markers_locked(bool flush) {
    auto filtered = mca::mnn::filter_stream_protocol(
            g_stream_protocol_filter,
            std::move(g_pending_chunk),
            flush);
    g_pending_chunk = std::move(filtered.visible);
    if (filtered.stopped) {
        g_eop_seen = true;
        g_generation_active = false;
        g_generation_stop_reason = g_stream_protocol_filter.stop_reason.empty()
                ? "stop_marker"
                : g_stream_protocol_filter.stop_reason;
        // The protocol filter has observed a complete, successful stop marker.
        // Commit the text transaction before the visible terminal chunk is
        // drained; cancellation/error paths never enter this branch.
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
    }
}
#endif

std::string stats_json_locked() {
    mca::mnn::MnnEngineStatsSnapshot snapshot;
    snapshot.backend_type = g_mnn_backend_type;
    snapshot.loaded = g_loaded;
    snapshot.runner_ready = g_runner_ready;
    snapshot.load_generation = g_load_generation;
    snapshot.loaded_at_ms = g_loaded_at_ms;
    snapshot.request_count_since_load = g_mnn_request_lifecycle.requestCountSinceLoad;
    snapshot.last_request_reset = g_mnn_request_lifecycle.lastRequestReset;
    snapshot.mnn_model_type = g_mnn_model_type;
    snapshot.multimodal_system_prompt_suppressed = g_multimodal_system_prompt_suppressed;
    snapshot.multimodal_history_suppressed = g_multimodal_history_suppressed;
    snapshot.prompt_ends_inside_reasoning = g_prompt_ends_inside_reasoning;
    snapshot.model_path = g_model_path;
    snapshot.original_model_path = g_original_model_path;
    snapshot.native_lib_dir = g_native_lib_dir;
    snapshot.vision_ready = g_vision_ready;
    snapshot.visual_model_path = g_visual_model_path;

#if MCA_WITH_MNN_LLM
    snapshot.llm_context = mca::mnn::extract_llm_context_stats(
            g_llm,
            g_mnn_prompt_cache,
            g_generation_started_at_ms,
            g_first_chunk_at_ms);
    snapshot.prompt_cache = mca::mnn::make_prompt_cache_snapshot(
            g_mnn_prompt_cache,
            g_mnn_prompt_cache_hits,
            g_mnn_prompt_cache_misses);
#else
    snapshot.prompt_cache.has_prompt_cache = false;
#endif

    snapshot.generation_sequence = g_generation_sequence;
    snapshot.generation_active = g_generation_active;
    snapshot.generated_steps = g_generated_steps;
    snapshot.streamed_bytes = g_streamed_bytes;
    snapshot.generation_stop_reason = g_generation_stop_reason;
    snapshot.mnn_debug_trace_enabled = g_mnn_debug_trace_enabled;
    snapshot.mnn_debug_trace_truncated = g_mnn_debug_trace_truncated;
    snapshot.mnn_debug_raw_output = g_mnn_debug_raw_output;
    snapshot.mnn_debug_generated_token_ids = g_mnn_debug_generated_token_ids;
    snapshot.mnn_debug_token_ids_truncated = g_mnn_debug_token_ids_truncated;
    snapshot.mnn_debug_prompt_truncated = g_mnn_debug_prompt_truncated;
    snapshot.mnn_debug_prompt = g_mnn_debug_prompt;
    snapshot.sync_stepping = g_sync_stepping;
    snapshot.n_threads = g_n_threads;
    snapshot.n_ctx = g_max_all_tokens;
    snapshot.max_all_tokens = g_max_all_tokens;
    snapshot.max_new_tokens = g_max_new_tokens;
    snapshot.loaded_config_json = g_loaded_config_json;
    snapshot.last_config_json = g_last_config_json;
    snapshot.last_error = g_last_error;

    return mca::mnn::format_mnn_stats_json(snapshot);
}

#if MCA_WITH_MNN_LLM
void update_visual_stats_locked() {
    std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
    g_visual_stats_json = stats_json_locked();
}
#endif

constexpr jint kMnnRunnerUnavailable = -200;
constexpr jint kMnnInvalidState = -201;
constexpr jint kMnnLoadFailed = -202;
constexpr jint kMnnBeginFailed = -203;
constexpr jint kMnnLoadSignatureMismatch = -11;

}  // namespace

#if MCA_WITH_MNN_DIFFUSION
namespace MNN {
namespace DIFFUSION {

Diffusion::Diffusion(std::string modelPath, DiffusionModelType modelType, MNNForwardType backendType, int memoryMode)
        : mModelPath(std::move(modelPath)),
          mModelType(modelType),
          mMemoryMode(memoryMode),
          mBackendType(backendType) {
}

Diffusion::~Diffusion() {
    mModules.clear();
    runtime_manager_.reset();
}

bool Diffusion::runVideo(
        const std::string& prompt,
        const std::string& outputDir,
        int width,
        int height,
        int frames,
        int steps,
        int seed,
        float cfgScale,
        std::function<void(int)> progressCallback) {
    (void)prompt;
    (void)outputDir;
    (void)width;
    (void)height;
    (void)frames;
    (void)steps;
    (void)seed;
    (void)cfgScale;
    (void)progressCallback;
    return false;
}

Diffusion* Diffusion::createDiffusion(
        std::string modelPath,
        DiffusionModelType modelType,
        MNNForwardType backendType,
        int memoryMode) {
    if (modelType == SANA_DIFFUSION) {
        return new SanaDiffusion(std::move(modelPath), modelType, backendType, memoryMode);
    }
    return new StableDiffusion(std::move(modelPath), modelType, backendType, memoryMode);
}

}  // namespace DIFFUSION
}  // namespace MNN
#endif

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_initBackends(
        JNIEnv* env,
        jobject,
        jstring nativeLibDir) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_native_lib_dir = jstring_to_std(env, nativeLibDir);
#if MCA_WITH_MNN_LLM
    set_error("");
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "MNN LLM runner ready (CPU/OpenCL selected by runtime profile).");
#else
    set_error("MNN runner adapter loaded, but official MNN-LLM runtime is not linked in this build.");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_isRunnerReady(JNIEnv*, jobject) {
    return g_runner_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_loadModel(
        JNIEnv* env,
        jobject,
        jstring configPath,
        jstring paramsJson) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return kMnnInvalidState;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_original_model_path = normalize_mnn_model_path(jstring_to_std(env, configPath));
    g_model_path = resolve_mnn_config_path(g_original_model_path);
    g_mnn_backend_type = "cpu";
    g_vision_ready = false;
    g_visual_model_path.clear();
    reset_active_stop_markers_locked();
    const auto params = jstring_to_std(env, paramsJson);
#if MCA_WITH_MNN_LLM
    try {
        if (g_original_model_path.empty() || (!file_exists(g_original_model_path) && !directory_exists(g_original_model_path))) {
            set_error("MNN model path does not exist: " + g_original_model_path);
            return kMnnLoadFailed;
        }
        g_model_path = prepare_mnn_runtime_config(g_original_model_path);
        if (const auto incompatibility = incompatible_legacy_mnn_visual_graph_message(g_model_path);
                !incompatibility.empty()) {
            set_error(incompatibility);
            return kMnnLoadFailed;
        }
        if (g_model_path.empty() || !file_exists(g_model_path)) {
            set_error("MNN config path does not exist: " + g_model_path);
            return kMnnLoadFailed;
        }
        if (const auto preflightFailure = mnn_chat_bundle_preflight_message(g_model_path);
                !preflightFailure.empty()) {
            set_error("MNN bundle preflight failed: " + preflightFailure);
            return kMnnLoadFailed;
        }
        const auto preparedConfig = read_json_file_or_empty(g_model_path);
        if (preparedConfig.is_object() &&
                preparedConfig.value("is_visual", false) &&
                !g_vision_ready) {
            set_error(
                    "MNN model bundle declares a visual component but is missing the visual model file "
                    "(visual.mnn). Load a complete multimodal package before loading this model.");
            return kMnnLoadFailed;
        }
        g_model_stop_markers = mnn_model_stop_markers(g_model_path);
        g_active_stop_markers = g_model_stop_markers;
        destroy_llm_locked();
        g_llm = MNN::Transformer::Llm::createLLM(g_model_path);
        if (g_llm == nullptr) {
            set_error("MNN createLLM failed for config: " + g_model_path);
            return kMnnLoadFailed;
        }
        const auto config = build_mnn_config(g_model_path, params, true);
        g_mnn_backend_type = config.value("backend_type", std::string("cpu"));
        g_max_all_tokens = std::max(1, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_loaded_config_json = config.dump();
        g_last_config_json = g_loaded_config_json;
        g_llm->set_config(g_last_config_json);
        const bool ok = g_llm->load();
        if (!ok) {
            const std::string mnnLog = mnn_context_log_snapshot(g_llm->getContext());
            set_error(
                    "MNN load() failed for backend '" + g_mnn_backend_type +
                    "'. Check package components, memory headroom, and backend compatibility." +
                    (mnnLog.empty() ? std::string() : " MNN log: " + mnnLog));
            g_vision_ready = false;
            destroy_llm_locked();
            return kMnnLoadFailed;
        }
        const auto mergedModelConfig = json::parse(g_llm->dump_config(), nullptr, false);
        if (g_vision_ready &&
                (!mergedModelConfig.is_object() || !mergedModelConfig.value("is_visual", false))) {
            set_error(
                    "MNN visual component exists, but the loaded runtime did not enable the Omni visual executor.");
            g_vision_ready = false;
            destroy_llm_locked();
            return kMnnLoadFailed;
        }
        g_mnn_model_type = mergedModelConfig.is_object()
                ? mergedModelConfig.value("model_type", std::string())
                : std::string();
        g_loaded = true;
        g_loaded_at_ms = now_ms();
        g_load_generation += 1;
        g_mnn_request_lifecycle.onModelLoaded();
        set_error("");
        return 0;
    } catch (const std::exception& e) {
        set_error(std::string("MNN loadModel exception: ") + e.what());
        g_vision_ready = false;
        destroy_llm_locked();
        return kMnnLoadFailed;
    } catch (...) {
        set_error("MNN loadModel exception: unknown native error");
        g_vision_ready = false;
        destroy_llm_locked();
        return kMnnLoadFailed;
    }
#else
    set_error("MNN model package is valid, but this APK has not linked the official MNN-LLM CPU executor yet.");
    return kMnnRunnerUnavailable;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_unloadModel(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_model_path.clear();
    g_original_model_path.clear();
    g_visual_model_path.clear();
    g_vision_ready = false;
    reset_active_stop_markers_locked();
    g_loaded_config_json = "{}";
    g_last_config_json = "{}";
#if MCA_WITH_MNN_LLM
    destroy_llm_locked();
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_beginCompletion(
        JNIEnv* env,
        jobject,
        jstring messagesJson,
        jstring paramsJson) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return kMnnInvalidState;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    // A new request owns a fresh decoder boundary even if validation or setup
    // fails before MNN starts producing tokens.
    g_pending_chunk.clear();
    g_pending_utf8_tail.clear();
    g_prompt_ends_inside_reasoning = false;
    g_mnn_prefill_completed_tokens.store(0, std::memory_order_release);
    g_mnn_prefill_total_tokens.store(0, std::memory_order_release);
#if MCA_WITH_MNN_LLM
    if (!g_loaded || g_llm == nullptr) {
        set_error("MNN beginCompletion requested before a model is loaded.");
        return kMnnInvalidState;
    }
    bool requestHasMedia = false;
    try {
        // A caller may start a replacement request after receiving the final
        // visible chunk but before asking for the terminal null chunk. Settle
        // that transaction first so its partial KV can never leak forward.
        if (g_mnn_prompt_cache.request_active) {
            if (g_generation_active && !g_stop_requested && context_finished_locked()) {
                g_generation_active = false;
                g_generation_stop_reason = generation_stop_reason_locked();
                settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            } else {
                rollback_mnn_text_prompt_cache_locked("superseded", true);
            }
        }
        const auto params = jstring_to_std(env, paramsJson);
        const auto parsed_params = json::parse(params, nullptr, false);
        configure_mnn_debug_trace_locked(parsed_params);
        auto parsed = parse_chat_messages(jstring_to_std(env, messagesJson));
        requestHasMedia = parsed.hasMediaInputs();
        auto config = build_mnn_config(g_model_path, params, false);
        const auto loaded_config = json::parse(g_loaded_config_json, nullptr, false);
        if (loaded_config.is_discarded() || !loaded_config.is_object()) {
            set_error("MNN loaded configuration snapshot is unavailable; reload the active committed profile.");
            return kMnnLoadSignatureMismatch;
        }
        const auto changed_load_fields = mnn_load_signature_differences(loaded_config, config);
        if (!changed_load_fields.empty()) {
            std::ostringstream detail;
            for (size_t index = 0; index < changed_load_fields.size(); ++index) {
                if (index > 0) detail << ',';
                detail << changed_load_fields[index];
            }
            set_error("MNN completion load signature mismatch; reload required. changed_fields=" + detail.str());
            return kMnnLoadSignatureMismatch;
        }
        // Prompt/KV reuse is a text-only capability. A visual request uses the
        // official multimodal response path and must not inherit stale text KV,
        // even when the loaded package itself is capable of vision.
        const bool textOnlyRequest = !parsed.hasMediaInputs();
        config["reuse_kv"] = textOnlyRequest;
        config["prompt_cache"] = textOnlyRequest;
        mca::mnn::applyProductMnnMultimodalRequestPolicy(
                config,
                parsed.hasMediaInputs(),
                opt_int(config, "chunk", 0));
        configure_mnn_stop_markers_locked(parsed_params);
        g_max_new_tokens = std::max(1, config.value("max_new_tokens", 512));
        g_max_all_tokens = std::max(1, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_last_config_json = config.dump();
        g_generated_steps = 0;
        g_stop_requested = false;
        g_stop_signal.store(false, std::memory_order_release);
        g_generation_active = true;
        g_eop_seen = false;
        g_streamed_bytes = 0;
        g_generation_stop_reason = "running";
        g_generation_started_at_ms = now_ms();
        g_first_chunk_at_ms = 0;
        g_sync_stepping = true;
        const auto visualStream = textOnlyRequest ? std::shared_ptr<VisualStreamState>{}
                : std::make_shared<VisualStreamState>();
        g_stream_buffer = std::make_unique<MnnStreamBuffer>(visualStream);
        g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
        // response()/generate_init() owns context cleanup. The product bridge
        // must not call Llm::reset() here: a controlled MNN 3.5 differential
        // reproduced the Qwen3.5 vision failure by adding only that reset.
        const bool resetBeforeRequest = g_mnn_request_lifecycle.beginRequest();
        if (resetBeforeRequest) {
            g_llm->reset();
        }
        if (textOnlyRequest) {
            prepare_mnn_text_prompt_cache_locked(parsed.messages);
        } else {
            mark_mnn_prompt_cache_disabled_locked(
                    true,
                    "multimodal_request_requires_fresh_visual_prefill");
        }
        g_llm->set_config(g_last_config_json);
        if (!textOnlyRequest) {
            auto visualContent = prepare_mnn_visual_response(parsed);
            set_error("");
            g_sync_stepping = false;
            {
                std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
                g_visual_stats_json = mca::mnn::make_deferred_visual_stats_json(
                        stats_json_locked(),
                        g_generation_sequence + 1);
            }
            visualStream->filter.reset(g_active_stop_markers);
            std::atomic_store(&g_visual_stream, visualStream);
            g_visual_producer_running.store(true, std::memory_order_release);
            try {
                g_visual_producer = std::thread([
                        parsed = std::move(parsed), visualContent = std::move(visualContent), visualStream]() mutable {
                    std::string producerError;
                    {
                        std::lock_guard<std::mutex> model_lock(g_mnn_mutex);
                        try {
                            if (!g_stop_signal.load(std::memory_order_acquire)) {
                                begin_mnn_response(parsed, &visualContent);
                            } else {
                                visualStream->queue.cancel();
                            }
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            g_generation_stop_reason = g_stop_requested
                                    ? "stop_requested"
                                    : visualStream->queue.stoppedAfterMarker()
                                            ? "stop_marker"
                                            : generation_stop_reason_locked();
                            mark_mnn_prompt_cache_disabled_locked(
                                    true, "multimodal_completed_cache_cleared");
                            if (!g_stop_requested && !visualStream->queue.stoppedAfterMarker() &&
                                (g_generation_stop_reason == "mnn_internal_error" ||
                                 g_generation_stop_reason == "mnn_timeout" ||
                                 g_generation_stop_reason == "mnn_user_cancel" ||
                                 g_generation_stop_reason == "mnn_not_loaded")) {
                                producerError = "MNN visual decode failed: " + g_generation_stop_reason;
                            }
                        } catch (const std::exception& e) {
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            if (g_stop_requested || visualStream->queue.stoppedAfterMarker()) {
                                g_generation_stop_reason = g_stop_requested ? "stop_requested" : "stop_marker";
                            } else {
                                producerError = std::string("MNN visual generation failed: ") + e.what();
                                g_generation_stop_reason = "generate_failed";
                            }
                            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
                        } catch (...) {
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            if (g_stop_requested || visualStream->queue.stoppedAfterMarker()) {
                                g_generation_stop_reason = g_stop_requested ? "stop_requested" : "stop_marker";
                            } else {
                                producerError = "MNN visual generation failed: unknown native error";
                                g_generation_stop_reason = "generate_failed";
                            }
                            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
                        }
                        if (!producerError.empty()) set_error(producerError);
                        update_visual_stats_locked();
                    }
                    g_visual_producer_running.store(false, std::memory_order_release);
                    visualStream->queue.finish(std::move(producerError));
                });
            } catch (...) {
                g_visual_producer_running.store(false, std::memory_order_release);
                std::atomic_store(&g_visual_stream, std::shared_ptr<VisualStreamState>{});
                throw;
            }
            ++g_generation_sequence;
            return 0;
        }
        bool completedSynchronously = false;
        try {
            completedSynchronously = begin_mnn_response(parsed);
        } catch (const std::exception& firstError) {
            // A stale MNN prompt/KV transaction can make the first prefill
            // fail with the generic -203 bridge code.  Text requests are
            // retryable because no visual executor state is involved: clear
            // only the cached transaction, reset the decoder, and perform one
            // clean prefill.  Visual requests remain fail-closed and are not
            // retried here because resetting Omni can invalidate image VARPs.
            if (!textOnlyRequest || g_llm == nullptr) {
                throw;
            }
            const std::string firstMessage = firstError.what();
            rollback_mnn_text_prompt_cache_locked("prefill_retry_after_cache_error", true);
            g_llm->reset();
            g_pending_chunk.clear();
            g_pending_utf8_tail.clear();
            g_stream_buffer = std::make_unique<MnnStreamBuffer>();
            g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
            g_llm->set_config(g_last_config_json);
            try {
                completedSynchronously = begin_mnn_response(parsed);
            } catch (const std::exception& retryError) {
                throw std::runtime_error(
                        "MNN text prefill failed after one clean KV retry. first=" +
                        firstMessage + "; retry=" + retryError.what());
            }
        }
        g_sync_stepping = !completedSynchronously;
        if (completedSynchronously) {
            // Leave the buffered native output untouched here. The first
            // generateNextChunk() call owns protocol/EOS filtering and drains
            // it exactly once; pre-filtering would feed the visible text back
            // through an already-stopped filter and drop the whole answer.
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
            if (requestHasMedia) {
                // response(string) has already performed the visual request;
                // invalidate any prior text transcript without calling reset()
                // on the visual executor in this lifecycle boundary.
                mark_mnn_prompt_cache_disabled_locked(
                        true,
                        "multimodal_completed_cache_cleared");
            } else {
                settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            }
        } else {
            if (textOnlyRequest) {
                std::string prefillError;
                if (!capture_mnn_text_prefill_locked(prefillError)) {
                    throw std::runtime_error(prefillError);
                }
            }
            filter_mnn_stop_markers_locked(false);
            restore_stepping_status_if_needed_locked();
        }
        set_error("");
        // No failing begin path remains after this point.
        ++g_generation_sequence;
        return 0;
    } catch (const std::exception& e) {
        g_generation_active = false;
        const std::string detail = e.what();
        if (requestHasMedia) {
            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
        } else {
            rollback_mnn_text_prompt_cache_locked("begin_failed", true);
        }
        g_generation_stop_reason = detail.find("mnn_multimodal_prefill_failed") != std::string::npos
                ? "prefill_failed"
                : "begin_failed";
        const std::string backend = g_mnn_backend_type.empty() ? "cpu" : g_mnn_backend_type;
        const std::string contextLog = mnn_context_log_snapshot(
                g_llm == nullptr ? nullptr : g_llm->getContext());
        set_error(
                "MNN beginCompletion exception (backend=" + backend + "): " + detail +
                (contextLog.empty() ? std::string() : " MNN log: " + contextLog));
        return kMnnBeginFailed;
    } catch (...) {
        g_generation_active = false;
        if (requestHasMedia) {
            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
        } else {
            rollback_mnn_text_prompt_cache_locked("begin_failed", true);
        }
        g_generation_stop_reason = "begin_failed";
        set_error(
                "MNN beginCompletion exception (backend=" +
                (g_mnn_backend_type.empty() ? std::string("cpu") : g_mnn_backend_type) +
                "): unknown native error");
        return kMnnBeginFailed;
    }
#else
    set_error("MNN generation requested before the official MNN-LLM executor is linked.");
    return kMnnRunnerUnavailable;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_generateNextChunk(JNIEnv* env, jobject) {
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) {
        std::lock_guard<std::mutex> stream_lock(stream->consumer_mutex);
        while (true) {
            auto result = stream->queue.read();
            if (result.kind == mca::mnn::VisualStreamQueue::ReadKind::Cancelled) {
                jclass exception_class = env->FindClass("java/lang/IllegalStateException");
                if (exception_class != nullptr) {
                    env->ThrowNew(exception_class, "MNN visual generation was cancelled.");
                    env->DeleteLocalRef(exception_class);
                }
                return nullptr;
            }
            if (result.kind == mca::mnn::VisualStreamQueue::ReadKind::Failed) {
                jclass exception_class = env->FindClass("java/lang/IllegalStateException");
                if (exception_class != nullptr) {
                    env->ThrowNew(exception_class, result.text.c_str());
                    env->DeleteLocalRef(exception_class);
                }
                return nullptr;
            }
            const bool flush = result.kind == mca::mnn::VisualStreamQueue::ReadKind::Finished;
            auto filtered = mca::mnn::filter_stream_protocol(
                    stream->filter, std::move(result.text), flush);
            if (filtered.stopped) {
                stream->queue.stopAfterMarker();
            }
            std::string bytes = std::move(stream->utf8_tail);
            bytes += filtered.visible;
            auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
            stream->utf8_tail = std::move(decoded.incomplete_tail);
            if (!decoded.utf16.empty()) {
                auto* result = utf16_to_jstring(env, decoded.utf16);
                if (result != nullptr || env->ExceptionCheck()) return result;
            }
            if (!flush) continue;
            return nullptr;
        }
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // requestStop() publishes g_stop_signal without taking g_mnn_mutex. Consume
    // it at the next safe native boundary before protocol filtering or another
    // decoder step can commit output/cache state.
    if (g_stop_signal.exchange(false, std::memory_order_acq_rel)) {
        g_stop_requested = true;
        g_generation_active = false;
        g_generation_stop_reason = "stop_requested";
        rollback_mnn_text_prompt_cache_locked("stop_requested", false);
        return nullptr;
    }
    if (!g_pending_chunk.empty() ||
            !g_pending_utf8_tail.empty() ||
            g_stream_protocol_filter.has_pending_protocol()) {
        const bool flush = !g_generation_active || g_stop_requested || g_eop_seen;
        filter_mnn_stop_markers_locked(flush);
        bool emitted = false;
        auto* out = take_pending_utf8_locked(
                env,
                flush,
                &emitted);
        if (emitted || env->ExceptionCheck()) return out;
    }
    if (!g_generation_active || g_llm == nullptr || g_stop_requested) {
        return nullptr;
    }
    restore_stepping_status_if_needed_locked();
    if (g_generated_steps >= g_max_new_tokens || context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
        filter_mnn_stop_markers_locked(true);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, true, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        return nullptr;
    }
    try {
        g_llm->generate(1);
        // A stop may arrive while MNN is inside generate(1). Do not publish the
        // token that was produced after the cancellation request; mark the
        // transaction rolled back on this same owning thread.
        if (g_stop_signal.exchange(false, std::memory_order_acq_rel)) {
            g_stop_requested = true;
            g_generation_active = false;
            g_generation_stop_reason = "stop_requested";
            rollback_mnn_text_prompt_cache_locked("stop_requested", false);
            return nullptr;
        }
        if (g_llm->getContext() != nullptr &&
                g_llm->getContext()->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) {
            g_generation_active = false;
            const auto reason = g_llm->getContext()->logits_error.empty()
                    ? std::string("mnn_internal_error") : g_llm->getContext()->logits_error;
            g_generation_stop_reason = reason;
            rollback_mnn_text_prompt_cache_locked(reason, true);
            set_error("MNN generation aborted: " + reason + ".");
            return nullptr;
        }
        g_generated_steps += 1;
        capture_mnn_debug_generated_token_ids_locked();
        filter_mnn_stop_markers_locked(false);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, g_eop_seen, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        if (context_finished_locked()) {
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
            settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            filter_mnn_stop_markers_locked(true);
            if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
                bool emitted = false;
                auto* out = take_pending_utf8_locked(env, true, &emitted);
                if (emitted || env->ExceptionCheck()) return out;
            }
            return nullptr;
        }
        return utf8_to_jstring(env, "");
    } catch (const std::exception& e) {
        g_generation_active = false;
        rollback_mnn_text_prompt_cache_locked("generate_failed", true);
        set_error(std::string("MNN generateNextChunk exception: ") + e.what());
        return nullptr;
    } catch (...) {
        g_generation_active = false;
        rollback_mnn_text_prompt_cache_locked("generate_failed", true);
        set_error("MNN generateNextChunk exception: unknown native error");
        return nullptr;
    }
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStop(JNIEnv*, jobject) {
    // Deliberately lock-free: generateNextChunk() may own g_mnn_mutex while
    // MNN is executing a long native step. The owning thread consumes this
    // signal and performs the cache/protocol rollback at a safe boundary.
    g_stop_signal.store(true, std::memory_order_release);
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) stream->queue.cancel();
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStopIfActive(JNIEnv*, jobject) {
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) {
        if (!g_visual_producer_running.load(std::memory_order_acquire)) return JNI_FALSE;
        g_stop_signal.store(true, std::memory_order_release);
        stream->queue.cancel();
        return JNI_TRUE;
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // A final visible chunk can be returned before generateNextChunk() reaches
    // its normal terminal bookkeeping. Reconcile the underlying MNN context
    // here so a stale generationActive flag cannot turn natural completion
    // into a successful cancellation proof.
    if (g_generation_active && context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
    }
#endif
    if (!g_generation_active) return JNI_FALSE;
    g_stop_requested = true;
    g_generation_active = false;
    g_generation_stop_reason = "stop_requested";
 #if MCA_WITH_MNN_LLM
    rollback_mnn_text_prompt_cache_locked("stop_requested", false);
 #endif
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_isGenerationRunning(JNIEnv*, jobject) {
#if MCA_WITH_MNN_LLM
    return g_visual_producer_running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
#if MCA_WITH_MNN_LLM
    if (g_visual_producer_running.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> model_lock(g_mnn_mutex, std::try_to_lock);
        if (!model_lock.owns_lock()) {
            std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
            return utf8_to_jstring(env, g_visual_stats_json);
        }
        return utf8_to_jstring(env, stats_json_locked());
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    const auto stats = stats_json_locked();
    return utf8_to_jstring(env, stats);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getPrefillProgressJson(JNIEnv* env, jobject) {
    // Deliberately lock-free: MNN beginCompletion holds g_mnn_mutex while
    // prefill runs, and the poll getter must observe the atomics independently.
    const int total = std::max(0, g_mnn_prefill_total_tokens.load(std::memory_order_acquire));
    const int completed = std::clamp(
            g_mnn_prefill_completed_tokens.load(std::memory_order_acquire),
            0,
            total);
    nlohmann::json out = {
            {"completedTokens", completed},
            {"totalTokens", total},
    };
    return utf8_to_jstring(env, out.dump());
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_resetPrefillProgress(JNIEnv*, jobject) {
    // Deliberately lock-free; same reasoning as the getter.
    g_mnn_prefill_completed_tokens.store(0, std::memory_order_release);
    g_mnn_prefill_total_tokens.store(0, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_shutdown(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_model_path.clear();
    g_original_model_path.clear();
    g_visual_model_path.clear();
    g_vision_ready = false;
    reset_active_stop_markers_locked();
#if MCA_WITH_MNN_LLM
    destroy_llm_locked();
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_isRunnerReady(JNIEnv*, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_inspectBundle(
        JNIEnv* env,
        jobject,
        jstring bundleRoot) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto out = inspect_mnn_diffusion_bundle(root).dump();
#else
    const auto out = json({
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_runUnetSmoke(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring backendMode) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto mode = opt_string(json({{"backendMode", jstring_to_std(env, backendMode)}}), "backendMode", "cpu");
    const auto out = run_unet_interpreter_smoke(root, mode).dump();
#else
    const auto out = json({
        {"ok", false},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_resetImageCancellation(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    g_mnn_diffusion_cancel_requested = false;
}

#if MCA_WITH_MNN_DIFFUSION
// Tokenizer-only bridge used by QNN Stable Diffusion bundles. QNN's
// text_encoder.bin consumes CLIP ids, while the MNN text encoder path can
// remain unchanged. The pair layout is [negative(77), positive(77)].
jintArray tokenize_prompt_token_ids(
        JNIEnv* env,
        const std::string& bundleRoot,
        const std::string& prompt,
        const std::string& tokenizerRoot,
        jint bosId,
        jint eosId,
        jint maxTokens) {
    if (env == nullptr || maxTokens <= 0 || maxTokens > 4096) {
        return env == nullptr ? nullptr : env->NewIntArray(0);
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt(prompt)) {
        return env->NewIntArray(0);
    }
    auto root = tokenizerRoot.empty() ? bundleRoot : tokenizerRoot;
    if (!file_exists(root + "/tokenizer.mtok") && file_exists(bundleRoot + "/tokenizer.mtok")) {
        root = bundleRoot;
    }
    if (root.empty()) {
        return env->NewIntArray(0);
    }
    MNN::DIFFUSION::MtokTokenizer tokenizer(
            MNN::DIFFUSION::MtokTokenizer::Style::kPair,
            static_cast<int>(bosId),
            static_cast<int>(eosId));
    if (!tokenizer.load(root)) {
        return env->NewIntArray(0);
    }
    const auto ids = tokenizer.encode(prompt, static_cast<int>(maxTokens));
    const size_t expectedCount = static_cast<size_t>(maxTokens) * 2U;
    if (ids.size() != expectedCount || expectedCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return env->NewIntArray(0);
    }
    const auto result = env->NewIntArray(static_cast<jsize>(expectedCount));
    if (result == nullptr) {
        return nullptr;
    }
    std::vector<jint> values;
    values.reserve(expectedCount);
    for (const int id : ids) {
        values.push_back(static_cast<jint>(id));
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

void write_u32_little_endian(std::ostream& output, uint32_t value) {
    const std::array<uint8_t, 4> bytes = {
        static_cast<uint8_t>(value & 0xffU),
        static_cast<uint8_t>((value >> 8U) & 0xffU),
        static_cast<uint8_t>((value >> 16U) & 0xffU),
        static_cast<uint8_t>((value >> 24U) & 0xffU),
    };
    output.write(
            reinterpret_cast<const char*>(bytes.data()),
            static_cast<std::streamsize>(bytes.size()));
}

// Keep tokenizer.json on the same descriptor-pinned lifecycle as verified MNN graphs.
using MnnOpenedTokenizerJsonAsset = MnnOpenedTextEncoderAsset;

bool hash_opened_qnn_tokenizer_json_asset(
        MnnOpenedTokenizerJsonAsset& asset,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* phase,
        std::string& error) {
    struct stat descriptorBefore {};
    struct stat pathBefore {};
    if (asset.fd < 0 || asset.canonical_path.empty() ||
        ::fstat(asset.fd, &descriptorBefore) != 0 ||
        ::lstat(asset.canonical_path.c_str(), &pathBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode) || !S_ISREG(pathBefore.st_mode) ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(descriptorBefore)) ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(pathBefore))) {
        error = std::string("QNN tokenizer JSON identity changed ") + phase + ".";
        return false;
    }
    if (expectedSizeBytes == 0U || asset.identity.size != expectedSizeBytes ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string("QNN tokenizer JSON size or seek position is invalid ") + phase + ".";
        return false;
    }

    mca::image::execution_assets::Sha256 digest;
    std::array<uint8_t, 64U * 1024U> bytes{};
    uint64_t readBytes = 0U;
    const auto cancelled = mnn_asset_cancel_callback();
    while (true) {
        if (cancelled()) {
            error = "cancelled";
            return false;
        }
        const ssize_t count = ::read(asset.fd, bytes.data(), bytes.size());
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            error = std::string("QNN tokenizer JSON could not be streamed ") + phase + ".";
            return false;
        }
        readBytes += static_cast<uint64_t>(count);
        if (readBytes > expectedSizeBytes) {
            error = std::string("QNN tokenizer JSON grew ") + phase + ".";
            return false;
        }
        digest.update(bytes.data(), static_cast<size_t>(count));
    }

    struct stat descriptorAfter {};
    struct stat pathAfter {};
    if (::fstat(asset.fd, &descriptorAfter) != 0 ||
        ::lstat(asset.canonical_path.c_str(), &pathAfter) != 0 ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(descriptorAfter)) ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(pathAfter)) ||
        readBytes != expectedSizeBytes) {
        error = std::string("QNN tokenizer JSON changed ") + phase + ".";
        return false;
    }

    const std::string actualSha256 = digest.finish_hex();
    if ((!expectedSha256.empty() && actualSha256 != expectedSha256) ||
        !mca::image::execution_assets::lowercase_sha256(actualSha256) ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string("QNN tokenizer JSON bytes do not match the consumed snapshot ") +
                phase + ".";
        return false;
    }
    asset.sha256 = actualSha256;
    asset.size_bytes = readBytes;
    return true;
}

bool open_qnn_tokenizer_json_asset(
        const std::string& requestedPath,
        MnnOpenedTokenizerJsonAsset& asset,
        std::string& error) {
    asset.reset();
    char canonical[PATH_MAX] = {};
    if (requestedPath.empty() || realpath(requestedPath.c_str(), canonical) == nullptr ||
        requestedPath != canonical) {
        error = "QNN tokenizer JSON path must be a canonical absolute path.";
        return false;
    }

    struct stat pathBefore {};
    if (::lstat(canonical, &pathBefore) != 0 || !S_ISREG(pathBefore.st_mode)) {
        error = "QNN tokenizer JSON must be a regular non-symlink file.";
        return false;
    }

    asset.fd = ::open(canonical, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    struct stat descriptorBefore {};
    if (asset.fd < 0 || ::fstat(asset.fd, &descriptorBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode)) {
        asset.reset();
        error = "QNN tokenizer JSON could not be opened through a regular-file descriptor.";
        return false;
    }

    asset.canonical_path = canonical;
    asset.identity = mca::image::execution_assets::identity_of(descriptorBefore);
    if (!mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(pathBefore)) ||
        asset.identity.size == 0U ||
        !hash_opened_qnn_tokenizer_json_asset(
                asset,
                "",
                asset.identity.size,
                "before native tokenizer consumption",
                error)) {
        asset.reset();
        return false;
    }
    return true;
}

json qnn_tokenizer_asset_failure(const std::string& error) {
    if (error == "cancelled") {
        return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}});
    }
    return json({
        {"ok", false},
        {"errorCode", "TOKENIZER_ASSET_MISMATCH"},
        {"error", error}
    });
}

bool hash_published_qnn_prompt_payload(
        const std::string& requestedPath,
        std::string& sha256,
        std::string& error) {
    char canonical[PATH_MAX] = {};
    struct stat pathBefore {};
    if (requestedPath.empty() ||
        ::realpath(requestedPath.c_str(), canonical) == nullptr ||
        requestedPath != canonical ||
        ::lstat(canonical, &pathBefore) != 0 ||
        !S_ISREG(pathBefore.st_mode) || pathBefore.st_size <= 0) {
        error = "Published QNN prompt payload is not a canonical nonempty regular file.";
        return false;
    }
    const int fd = ::open(canonical, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        error = "Published QNN prompt payload cannot be opened through a pinned descriptor.";
        return false;
    }
    const auto closeDescriptor = [&]() { ::close(fd); };
    struct stat descriptorBefore {};
    if (::fstat(fd, &descriptorBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode) ||
        !mca::image::execution_assets::same_identity(
            mca::image::execution_assets::identity_of(pathBefore),
            mca::image::execution_assets::identity_of(descriptorBefore))) {
        closeDescriptor();
        error = "Published QNN prompt payload identity changed before hashing.";
        return false;
    }

    mca::image::execution_assets::Sha256 digest;
    std::array<uint8_t, 64U * 1024U> bytes{};
    uint64_t readBytes = 0U;
    const auto cancelled = mnn_asset_cancel_callback();
    while (true) {
        if (cancelled()) {
            closeDescriptor();
            error = "cancelled";
            return false;
        }
        const ssize_t count = ::read(fd, bytes.data(), bytes.size());
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            closeDescriptor();
            error = "Published QNN prompt payload could not be streamed for hashing.";
            return false;
        }
        readBytes += static_cast<uint64_t>(count);
        if (readBytes > static_cast<uint64_t>(descriptorBefore.st_size)) {
            closeDescriptor();
            error = "Published QNN prompt payload grew while being hashed.";
            return false;
        }
        digest.update(bytes.data(), static_cast<size_t>(count));
    }

    struct stat descriptorAfter {};
    struct stat pathAfter {};
    const bool stable = ::fstat(fd, &descriptorAfter) == 0 &&
        ::lstat(canonical, &pathAfter) == 0 &&
        mca::image::execution_assets::same_identity(
            mca::image::execution_assets::identity_of(descriptorBefore),
            mca::image::execution_assets::identity_of(descriptorAfter)) &&
        mca::image::execution_assets::same_identity(
            mca::image::execution_assets::identity_of(descriptorBefore),
            mca::image::execution_assets::identity_of(pathAfter)) &&
        readBytes == static_cast<uint64_t>(descriptorBefore.st_size);
    closeDescriptor();
    if (!stable) {
        error = "Published QNN prompt payload changed while being hashed.";
        return false;
    }
    sha256 = digest.finish_hex();
    if (!mca::image::execution_assets::lowercase_sha256(sha256)) {
        error = "Published QNN prompt payload SHA-256 is invalid.";
        return false;
    }
    return true;
}

json encode_prompt_token_ids_with_weights_from_json(
        const std::string& tokenizerJsonPath,
        const std::string& prompt,
        const std::string& negativePrompt,
        int32_t bosId,
        int32_t eosId,
        int32_t padId,
        int maxTokens,
        const std::string& promptToEncoderClosureSha256,
        const std::string& outputPath) {
    if (outputPath.empty()) {
        return json({{"ok", false}, {"error", "Weighted CLIP token payload arguments are invalid."}});
    }
    if (!promptToEncoderClosureSha256.empty() &&
        !mca::image::execution_assets::lowercase_sha256(
            promptToEncoderClosureSha256)) {
        return json({
            {"ok", false},
            {"error", "Prompt-to-encoder closure SHA-256 is invalid."}
        });
    }
    const std::string temporaryPath = outputPath + ".part";
    if (tokenizerJsonPath == outputPath || tokenizerJsonPath == temporaryPath) {
        return json({
            {"ok", false},
            {"error", "Weighted CLIP token payload output must not alias tokenizer.json."}
        });
    }
    const auto discardOutput = [&]() {
        ::unlink(temporaryPath.c_str());
        ::unlink(outputPath.c_str());
    };
    if (maxTokens <= 0 || maxTokens > 4096) {
        discardOutput();
        return json({{"ok", false}, {"error", "Weighted CLIP token payload arguments are invalid."}});
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negativePrompt)) {
        discardOutput();
        return json({
            {"ok", false},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"error", "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."}
        });
    }

    MnnOpenedTokenizerJsonAsset tokenizerAsset;
    std::string error;
    if (!open_qnn_tokenizer_json_asset(tokenizerJsonPath, tokenizerAsset, error)) {
        discardOutput();
        return qnn_tokenizer_asset_failure(error);
    }
    char outputCanonical[PATH_MAX] = {};
    char temporaryCanonical[PATH_MAX] = {};
    if ((realpath(outputPath.c_str(), outputCanonical) != nullptr &&
            tokenizerAsset.canonical_path == outputCanonical) ||
        (realpath(temporaryPath.c_str(), temporaryCanonical) != nullptr &&
            tokenizerAsset.canonical_path == temporaryCanonical)) {
        return json({
            {"ok", false},
            {"error", "Weighted CLIP token payload output must not alias tokenizer.json."}
        });
    }
    discardOutput();

    mca::image::ClipTokenizerConfig config;
    config.bos_id = bosId;
    config.eos_id = eosId;
    config.pad_id = padId;
    config.max_length = maxTokens;
    config.enable_prompt_weighting = true;
    mca::image::ClipTokenPair pair;
    const bool tokenized = mca::image::tokenize_clip_pair_from_json(
            tokenizerAsset.opened_path(),
            prompt,
            negativePrompt,
            config,
            &pair,
            &error);
    const std::string tokenizationError = error;
    const std::string tokenizerSha256 = tokenizerAsset.sha256;
    const uint64_t tokenizerSizeBytes = tokenizerAsset.size_bytes;
    if (!hash_opened_qnn_tokenizer_json_asset(
            tokenizerAsset,
            tokenizerSha256,
            tokenizerSizeBytes,
            "after native tokenizer consumption",
            error)) {
        discardOutput();
        return qnn_tokenizer_asset_failure(error);
    }
    if (!tokenized) {
        discardOutput();
        return json({{"ok", false}, {"error", tokenizationError}});
    }
    const auto cancelled = mnn_asset_cancel_callback();
    if (cancelled()) {
        discardOutput();
        return qnn_tokenizer_asset_failure("cancelled");
    }
    const auto ids = pair.negative_then_positive();
    const auto weights = pair.negative_then_positive_weights();
    const size_t expectedCount = static_cast<size_t>(maxTokens) * 2U;
    const std::string fingerprint = pair.weighting_fingerprint();
    if (ids.size() != expectedCount || weights.size() != expectedCount ||
            fingerprint.size() != 64U ||
            expectedCount > static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
        discardOutput();
        return json({
            {"ok", false},
            {"error", "Weighted CLIP token payload is internally inconsistent."}
        });
    }
    const std::string nativePromptExecutionSha256 =
            mca::image::image_prompt_execution_sha256(prompt, negativePrompt);
    if (nativePromptExecutionSha256.size() != 64U) {
        discardOutput();
        return json({
            {"ok", false},
            {"error", "Weighted CLIP token payload prompt SHA-256 could not be derived."}
        });
    }

    json result = {
        {"ok", true},
        {"conditioningFormat", "qnn_clip_token_ids_weights_v1"},
        {"tokenizerBackend", "tokenizers_cpp"},
        {"tokenCount", ids.size()},
        {"nativePromptExecutionSha256", nativePromptExecutionSha256},
        {"nativePromptBindingStage", "conditioning_encoded"},
        {"outputPath", outputPath},
        {"tokenizerAssetPath", tokenizerAsset.canonical_path},
        {"tokenizerAssetSha256", tokenizerAsset.sha256},
        {"tokenizerAssetSizeBytes", tokenizerAsset.size_bytes},
        {"tokenizerAssetBindingStage", "tokenizer_consumed"}
    };
    if (!append_clip_prompt_weighting_evidence(result, pair, true, true, error)) {
        discardOutput();
        return json({{"ok", false}, {"error", error}});
    }
    if (cancelled()) {
        discardOutput();
        return qnn_tokenizer_asset_failure("cancelled");
    }

    std::ofstream output(temporaryPath.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) {
        output.close();
        discardOutput();
        return json({{"ok", false}, {"error", "Failed to open weighted CLIP token payload."}});
    }
    static constexpr std::array<char, 8> kMagic = {
        'M', 'C', 'A', 'Q', 'P', 'W', '0', '1'
    };
    output.write(kMagic.data(), static_cast<std::streamsize>(kMagic.size()));
    write_u32_little_endian(output, 1U);
    write_u32_little_endian(output, static_cast<uint32_t>(expectedCount));
    for (const int32_t id : ids) {
        write_u32_little_endian(output, static_cast<uint32_t>(id));
    }
    for (const float weight : weights) {
        uint32_t bits = 0;
        static_assert(sizeof(bits) == sizeof(weight), "float32 payload requires 32-bit float");
        std::memcpy(&bits, &weight, sizeof(bits));
        write_u32_little_endian(output, bits);
    }
    output.flush();
    const bool writeOk = output.good();
    output.close();
    if (!writeOk || !output.good() || cancelled() ||
            ::rename(temporaryPath.c_str(), outputPath.c_str()) != 0 ||
            !nonempty_regular_file_exists(outputPath)) {
        discardOutput();
        if (cancelled()) return qnn_tokenizer_asset_failure("cancelled");
        return json({{"ok", false}, {"error", "Failed to publish weighted CLIP token payload."}});
    }
    if (cancelled()) {
        discardOutput();
        return qnn_tokenizer_asset_failure("cancelled");
    }
    if (!promptToEncoderClosureSha256.empty()) {
        std::string payloadSha256;
        if (!hash_published_qnn_prompt_payload(outputPath, payloadSha256, error)) {
            discardOutput();
            return qnn_tokenizer_asset_failure(error);
        }
        mca::image::prompt_handoff::Record handoffRecord;
        handoffRecord.tokenizer_canonical_path = tokenizerAsset.canonical_path;
        handoffRecord.tokenizer_device = tokenizerAsset.identity.device;
        handoffRecord.tokenizer_inode = tokenizerAsset.identity.inode;
        handoffRecord.tokenizer_size_bytes = tokenizerAsset.size_bytes;
        handoffRecord.tokenizer_sha256 = tokenizerAsset.sha256;
        handoffRecord.prompt_pair_sha256 = nativePromptExecutionSha256;
        handoffRecord.payload_sha256 = payloadSha256;
        handoffRecord.prompt_to_encoder_closure_sha256 =
            promptToEncoderClosureSha256;
        if (cancelled()) {
            discardOutput();
            return qnn_tokenizer_asset_failure("cancelled");
        }
        std::string handoff;
        if (!mca::image::prompt_handoff::issue(handoffRecord, handoff, error)) {
            discardOutput();
            return json({
                {"ok", false},
                {"errorCode", "PROMPT_HANDOFF_ISSUE_FAILED"},
                {"error", error.empty()
                    ? "Failed to issue the native MNN-to-QNN prompt handoff."
                    : error}
            });
        }
        result["mnnPromptHandoff"] = handoff;
    }
    return result;
}
#endif

extern "C" JNIEXPORT jintArray JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_tokenizePromptTokenIds(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt) {
#if MCA_WITH_MNN_DIFFUSION
    return tokenize_prompt_token_ids(
            env,
            normalize_mnn_model_path(jstring_to_std(env, bundleRoot)),
            jstring_to_std(env, prompt),
            "",
            49406,
            49407,
            77);
#else
    return env == nullptr ? nullptr : env->NewIntArray(0);
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_tokenizePromptTokenIdsWithConfig(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring tokenizerRoot,
        jint bosId,
        jint eosId,
        jint maxTokens) {
#if MCA_WITH_MNN_DIFFUSION
    return tokenize_prompt_token_ids(
            env,
            normalize_mnn_model_path(jstring_to_std(env, bundleRoot)),
            jstring_to_std(env, prompt),
            normalize_mnn_model_path(jstring_to_std(env, tokenizerRoot)),
            bosId,
            eosId,
            maxTokens);
#else
    return env == nullptr ? nullptr : env->NewIntArray(0);
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_tokenizePromptTokenIdsFromJson(
        JNIEnv* env,
        jobject,
        jstring tokenizerJsonPath,
        jstring prompt,
        jstring negativePrompt,
        jint bosId,
        jint eosId,
        jint padId,
        jint maxTokens) {
    if (env == nullptr || maxTokens <= 0 || maxTokens > 4096) {
        return env == nullptr ? nullptr : env->NewIntArray(0);
    }
    const std::string promptText = jstring_to_std(env, prompt);
    const std::string negativePromptText = jstring_to_std(env, negativePrompt);
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            promptText,
            negativePromptText)) {
        return env->NewIntArray(0);
    }
    mca::image::ClipTokenizerConfig config;
    config.bos_id = static_cast<int32_t>(bosId);
    config.eos_id = static_cast<int32_t>(eosId);
    config.pad_id = static_cast<int32_t>(padId);
    config.max_length = static_cast<int>(maxTokens);
    mca::image::ClipTokenPair pair;
    std::string error;
    if (!mca::image::tokenize_clip_pair_from_json(
            normalize_mnn_model_path(jstring_to_std(env, tokenizerJsonPath)),
            promptText,
            negativePromptText,
            config,
            &pair,
            &error)) {
        __android_log_print(
                ANDROID_LOG_ERROR,
                "MCA-MNN",
                "Standard image tokenizer failed: %s",
                error.c_str());
        return env->NewIntArray(0);
    }
    const auto ids = pair.negative_then_positive();
    const size_t expectedCount = static_cast<size_t>(maxTokens) * 2U;
    if (ids.size() != expectedCount || expectedCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return env->NewIntArray(0);
    }
    const auto result = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (result == nullptr) return nullptr;
    std::vector<jint> values(ids.begin(), ids.end());
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_measurePromptTokens(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring tokenizerBackend,
        jstring tokenizerJsonPath,
        jstring prompt,
        jint bosId,
        jint eosId,
        jint padId,
        jint maxTokens,
        jboolean promptWeightingEnabled) {
    if (env == nullptr || maxTokens < 2 || maxTokens > 4096) {
        return env == nullptr ? nullptr : utf8_to_jstring(
                env,
                json({{"ok", false}, {"error", "Prompt tokenizer arguments are invalid."}}).dump());
    }
    const std::string backend = jstring_to_std(env, tokenizerBackend);
    const std::string promptText = jstring_to_std(env, prompt);
    json result = {
        {"ok", false},
        {"backend", backend},
    };
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt(
            promptText)) {
        result["errorCode"] = "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE";
        result["error"] =
            "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax.";
        return utf8_to_jstring(env, result.dump());
    }
    if (backend == "TOKENIZERS_CPP") {
        mca::image::ClipTokenizerConfig config;
        config.bos_id = static_cast<int32_t>(bosId);
        config.eos_id = static_cast<int32_t>(eosId);
        config.pad_id = static_cast<int32_t>(padId);
        config.max_length = static_cast<int>(maxTokens);
        config.enable_prompt_weighting = promptWeightingEnabled == JNI_TRUE;
        mca::image::ClipPromptTokenMeasurement measurement;
        std::string error;
        if (!mca::image::measure_clip_prompt_from_json(
                normalize_mnn_model_path(jstring_to_std(env, tokenizerJsonPath)),
                promptText,
                config,
                &measurement,
                &error)) {
            result["error"] = error;
        } else {
            result = {
                {"ok", true},
                {"backend", backend},
                {"count", measurement.token_count},
                {"maxTokens", measurement.max_length},
            };
            if (measurement.overflow_byte_offset.has_value()) {
                result["overflowByteOffset"] = *measurement.overflow_byte_offset;
            }
        }
    } else if (backend == "MNN_MTOK") {
#if MCA_WITH_MNN_DIFFUSION
        if (promptWeightingEnabled == JNI_TRUE) {
            // The production MNN_MTOK contract rejects weighted prompts because
            // this graph consumes token ids only. Do not present a literal
            // punctuation count as an exact executable count.
            result["error"] = "MNN_MTOK does not support exact prompt-weight measurement.";
            return utf8_to_jstring(env, result.dump());
        }
        try {
            const std::string root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
            // Production MNN_MTOK execution requires this artifact before it loads
            // the same pair tokenizer used below. Do not measure a root that the
            // generation contract would reject before tokenizer initialization.
            if (root.empty() || !file_exists(root + "/tokenizer.mtok")) {
                result["error"] = "MNN prompt tokenizer is unavailable.";
            } else {
                MNN::DIFFUSION::McaMtokPromptMeasurementTokenizer tokenizer(
                        static_cast<int>(bosId),
                        static_cast<int>(eosId));
                if (!tokenizer.load(root)) {
                    result["error"] = "MNN prompt tokenizer is unavailable.";
                } else {
                    std::vector<int> ids;
                    if (!tokenizer.encodeConditionalPromptForMeasurement(promptText, &ids)) {
                        result["error"] =
                                "MNN prompt tokenizer has no exact single-prompt measurement adapter.";
                    } else {
                        result = {
                            {"ok", true},
                            {"backend", backend},
                            {"count", ids.size()},
                            {"maxTokens", maxTokens},
                        };
                    }
                }
            }
        } catch (const std::exception&) {
            result["error"] = "MNN prompt tokenizer is unavailable.";
        } catch (...) {
            // Never permit a malformed local tokenizer asset to escape through JNI.
            result["error"] = "MNN prompt tokenizer is unavailable.";
        }
#else
        result["error"] = "MNN prompt tokenizer is not packaged in this build.";
#endif
    } else {
        result["error"] = "The selected image tokenizer has no exact measurement adapter.";
    }
    return utf8_to_jstring(env, result.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodePromptTokenIdsWithWeightsFromJson(
        JNIEnv* env,
        jobject,
        jstring tokenizerJsonPath,
        jstring prompt,
        jstring negativePrompt,
        jint bosId,
        jint eosId,
        jint padId,
        jint maxTokens,
        jstring promptToEncoderClosureSha256,
        jstring outputPath) {
#if MCA_WITH_MNN_DIFFUSION
    const auto result = encode_prompt_token_ids_with_weights_from_json(
            normalize_mnn_model_path(jstring_to_std(env, tokenizerJsonPath)),
            jstring_to_std(env, prompt),
            jstring_to_std(env, negativePrompt),
            static_cast<int32_t>(bosId),
            static_cast<int32_t>(eosId),
            static_cast<int32_t>(padId),
            static_cast<int>(maxTokens),
            jstring_to_std(env, promptToEncoderClosureSha256),
            normalize_mnn_model_path(jstring_to_std(env, outputPath)));
#else
    (void)tokenizerJsonPath;
    (void)prompt;
    (void)negativePrompt;
    (void)bosId;
    (void)eosId;
    (void)padId;
    (void)maxTokens;
    (void)promptToEncoderClosureSha256;
    (void)outputPath;
    const auto result = json({
        {"ok", false},
        {"error", "MNN-Diffusion tokenizer support is not linked in this APK."}
    });
#endif
    return utf8_to_jstring(env, result.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodeSd15PromptEmbeddings(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring negativePrompt,
        jstring outputPath,
        jstring backendMode,
        jint threads,
        jstring conditioningOrder,
        jboolean promptWeightingEnabled,
        jstring textualInversionJson) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    // The JNI slot is unchanged; its local name now reflects the active branch
    // contract. Dtype remains derived from token_emb.bin's exact size.
    const auto activeConditioningOrder = jstring_to_std(env, conditioningOrder);
    const bool useCfg = activeConditioningOrder != "positive_only";
    const auto out = encode_sd15_prompt_embeddings_to_file(
            root,
            jstring_to_std(env, prompt),
            jstring_to_std(env, negativePrompt),
            jstring_to_std(env, outputPath),
            jstring_to_std(env, backendMode),
            std::max(1, static_cast<int>(threads)),
            useCfg,
            promptWeightingEnabled == JNI_TRUE,
            jstring_to_std(env, textualInversionJson)).dump();
#else
    (void)bundleRoot;
    (void)prompt;
    (void)negativePrompt;
    (void)outputPath;
    (void)backendMode;
    (void)threads;
    (void)conditioningOrder;
    (void)promptWeightingEnabled;
    (void)textualInversionJson;
    const auto out = json({
        {"ok", false},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodeSdxlPromptConditioning(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring negativePrompt,
        jstring outputPath,
        jint width,
        jint height,
        jstring backendMode,
        jint threads,
        jboolean useCfg,
        jboolean promptWeightingEnabled,
        jstring textualInversionJson) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto out = encode_sdxl_prompt_conditioning_to_file(
            root,
            jstring_to_std(env, prompt),
            jstring_to_std(env, negativePrompt),
            jstring_to_std(env, outputPath),
            std::max(256, static_cast<int>(width)),
            std::max(256, static_cast<int>(height)),
            jstring_to_std(env, backendMode),
            std::max(1, static_cast<int>(threads)),
            useCfg == JNI_TRUE,
            promptWeightingEnabled == JNI_TRUE,
            jstring_to_std(env, textualInversionJson)).dump();
#else
    (void)bundleRoot;
    (void)prompt;
    (void)negativePrompt;
    (void)outputPath;
    (void)width;
    (void)height;
    (void)backendMode;
    (void)threads;
    (void)useCfg;
    (void)promptWeightingEnabled;
    (void)textualInversionJson;
    const auto out = json({
        {"ok", false},
        {"error", "MNN-Diffusion native runner is not linked in this APK."},
        {"format", "sdxl_qnn_conditioning"}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_generate(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring paramsJson,
        jstring outputPath) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto params_raw = jstring_to_std(env, paramsJson);
    const auto output_path = jstring_to_std(env, outputPath);
    json params = json::parse(params_raw, nullptr, false);
    if (params.is_discarded() || !params.is_object()) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", "MNN image generation params must be a valid JSON object."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    std::string prompt;
    std::string family;
    std::string contractError;
    if (!mnn_contract_string(params, "prompt", prompt, contractError) ||
        !mnn_contract_string(params, "family", family, contractError)) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", contractError}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    const bool sana_family = is_sana_family(family);
    MnnSemanticExecutionContract executionContract;
    if (!sana_family &&
        !parse_mnn_semantic_execution_contract(params, executionContract, contractError)) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", contractError}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (!sana_family && executionContract.runner != "direct") {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
            {"field", "runner"},
            {"error", "runner=module cannot faithfully execute the resolved scheduler, tokenizer, CFG, and VAE contract; use runner=direct."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    auto backend_mode = sana_family
            ? opt_string(params, "backendMode", "cpu")
            : executionContract.backend_mode;
    const int requested_steps = opt_int(params, "steps", 8);
    const int steps = sana_family
            ? requested_steps
            : executionContract.scheduler.steps;
    const int seed = sana_family
            ? opt_int(params, "seed", opt_int(params, "randomSeed", -1))
            : static_cast<int>(executionContract.seed);
    const int width = sana_family
            ? opt_int(params, "width", 512)
            : executionContract.width;
    const int height = sana_family
            ? opt_int(params, "height", 512)
            : executionContract.height;
    const int threads = sana_family
            ? opt_int(params, "threads", 4)
            : executionContract.threads;
    const int memory_mode = sana_family
            ? opt_int(params, "memoryMode", 0)
            : executionContract.memory_mode;
    const bool use_cfg = sana_family
            ? opt_bool(params, "useCfg", false)
            : executionContract.use_cfg;
    const float cfg_scale = sana_family
            ? static_cast<float>(opt_double(params, "cfgScale", 4.5))
            : executionContract.cfg_scale;
    std::string negative_prompt = executionContract.negative_prompt;
    std::string task_mode;
    std::string input_image_path;
    std::string input_image_sha256;
    std::string mask_image_path;
    std::string control_image_path;
    int64_t batch_count_value = 0;
    if (!mnn_contract_string(params, "taskMode", task_mode, contractError) ||
        !optional_json_string(params, "inputImagePath", "", input_image_path, contractError) ||
        !optional_json_string(params, "inputImageSha256", "", input_image_sha256, contractError) ||
        !optional_json_string(params, "maskImagePath", "", mask_image_path, contractError) ||
        !optional_json_string(params, "controlImagePath", "", control_image_path, contractError) ||
        !mnn_contract_integer(params, "batchCount", batch_count_value, contractError) ||
        (sana_family &&
         !mnn_contract_string(params, "negativePrompt", negative_prompt, contractError, true))) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", contractError}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (batch_count_value != 1) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
            {"field", "batchCount"},
            {"error", "MNN-Diffusion currently produces exactly one image per native request."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    const int batch_count = static_cast<int>(batch_count_value);
    if (sana_family) {
        std::string canonicalBackend;
        if (!canonical_mnn_diffusion_backend(backend_mode, canonicalBackend, contractError)) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"field", "backendMode"},
                {"error", contractError}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        backend_mode = canonicalBackend;
        if (steps < 2 || steps > 50 ||
            width < 256 || width > 2048 || height < 256 || height > 2048 ||
            width % 32 != 0 || height % 32 != 0 ||
            threads < 1 || threads > 64 ||
            memory_mode < 0 || memory_mode > 2 ||
            seed < 0 || !std::isfinite(cfg_scale) || cfg_scale < 0.0f || cfg_scale > 30.0f) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"error", "MNN Sana generation fields are outside their exact supported ranges."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (task_mode != "text_to_image" && task_mode != "edit") {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
                {"field", "taskMode"},
                {"error", "MNN Sana supports text_to_image or edit only."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        const bool imageEdit = task_mode == "edit";
        if (imageEdit != (!input_image_path.empty() && !input_image_sha256.empty()) ||
            (!imageEdit && (!input_image_path.empty() || !input_image_sha256.empty())) ||
            !mask_image_path.empty() || !control_image_path.empty()) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"field", "inputImagePath,inputImageSha256,maskImagePath,controlImagePath"},
                {"error", "MNN Sana edit requires one input image with its SHA-256 and does not accept mask/control images."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (!use_cfg && !negative_prompt.empty()) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
                {"field", "negativePrompt"},
                {"error", "MNN Sana cannot apply a negative prompt when useCfg=false."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        bool positiveWeighted = false;
        bool negativeWeighted = false;
        if (!prompt_has_non_unity_attention_weight(
                prompt, positiveWeighted, contractError) ||
            (use_cfg && !prompt_has_non_unity_attention_weight(
                negative_prompt, negativeWeighted, contractError))) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"field", "prompt,negativePrompt"},
                {"error", contractError}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (positiveWeighted || negativeWeighted) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED"},
                {"field", "prompt,negativePrompt"},
                {"error", "MNN Sana cannot apply non-unity prompt weights before its Transformer."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (params.contains("strength") ||
            params.contains("vaeTiling") || params.contains("preview") ||
            params.contains("clipSkip")) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
                {"field", "strength,vaeTiling,preview,clipSkip"},
                {"error", "MNN Sana edit does not expose strength, tiling, preview, or clip-skip controls through its native API."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        json preflightEffective;
        if (!mnn_sana_native_effective_json(
                params,
                steps,
                steps,
                256,
                use_cfg ? 2 : 1,
                use_cfg ? "negative_then_positive" : "positive_only",
                0,
                0,
                0,
                "",
                "",
                false,
                preflightEffective,
                contractError)) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"error", contractError}
            }).dump();
            return utf8_to_jstring(env, out);
        }
    } else if (task_mode != "text_to_image" ||
               !input_image_path.empty() || !input_image_sha256.empty() || !mask_image_path.empty() ||
               !control_image_path.empty()) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
            {"field", "taskMode"},
            {"error", "The current MNN SD1.5 direct graph package has no VAE encoder; only text_to_image is executable."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negative_prompt)) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"field", "prompt,negativePrompt"},
            {"error", "Native multilingual image prompts must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (mnn_prompt_contains_non_ascii(prompt) ||
        mnn_prompt_contains_non_ascii(negative_prompt)) {
        const bool has_verified_text_encoder =
            !sana_family && executionContract.text_encoder_asset.declared &&
                !executionContract.language_proof_sha256.empty();
        if (!has_verified_text_encoder) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "TEXT_ENCODER_LANGUAGE_PROOF_REQUIRED"},
                {"field", "nativeTextEncoderEvidence,languageProofSha256"},
                {"error", "Non-ASCII image prompts require complete text-encoder asset evidence and a signed semantic proof."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        if (g_mnn_diffusion_generating) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"error", "Another MNN-Diffusion generation is already running."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        g_mnn_diffusion_generating = true;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_started_at_ms = now_ms();
        const int64_t process_unique_sequence =
                (g_mnn_diffusion_started_at_ms << 16) |
                (static_cast<int64_t>(::getpid()) & 0xffffLL);
        g_mnn_diffusion_generation_sequence = std::max<int64_t>(
                process_unique_sequence,
                g_mnn_diffusion_generation_sequence + static_cast<int64_t>(1));
        g_mnn_diffusion_finished_at_ms = 0;
        g_mnn_diffusion_seconds_per_step = 0.0;
        g_mnn_diffusion_bundle_root = root;
        g_mnn_diffusion_output_path = output_path;
        g_mnn_diffusion_backend = backend_mode == "opencl" || backend_mode == "gpu" ? "opencl" : "cpu";
        g_mnn_diffusion_last_error.clear();
        set_mnn_diffusion_progress_locked(
                "loading",
                sana_family
                        ? "Loading MNN Sana text encoder and diffusion engine."
                        : "Loading MNN-Diffusion Stable Diffusion 1.5 engine.",
                0,
                steps,
                width,
                height,
                threads);
    }

    auto fail = [&](const std::string& message) {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        g_mnn_diffusion_generating = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_finished_at_ms = now_ms();
        g_mnn_diffusion_last_error = message;
        set_mnn_diffusion_progress_locked("failed", message, g_mnn_diffusion_step, steps, width, height, threads);
        return json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"error", message}
        }).dump();
    };

    if (prompt.empty()) {
        const auto out = fail("Prompt is empty.");
        return utf8_to_jstring(env, out);
    }
    if (output_path.empty()) {
        const auto out = fail("Output path is empty.");
        return utf8_to_jstring(env, out);
    }
    const auto missing = mnn_diffusion_missing_component(
            root,
            family,
            sana_family ? nullptr : &executionContract.text_encoder_asset);
    if (!missing.empty()) {
        const auto out = fail(missing);
        return utf8_to_jstring(env, out);
    }
    if (sana_family && task_mode == "edit" &&
        (!file_exists(root + "/vae_encoder.mnn") ||
         !file_exists(root + "/vae_encoder.mnn.weight"))) {
        const auto out = fail("MNN Sana edit bundle is missing vae_encoder.mnn or its weight file.");
        return utf8_to_jstring(env, out);
    }

    try {
        {
            std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
            g_mnn_diffusion_loaded = true;
            set_mnn_diffusion_progress_locked(
                    "generating",
                    "Generating image with MNN-Diffusion.",
                    0,
                    steps,
                    width,
                    height,
                    threads);
        }
        auto progress_callback = [steps, width, height, threads](int progress) {
                    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                    if (g_mnn_diffusion_cancel_requested) {
                        set_mnn_diffusion_progress_locked(
                                "cancelled",
                                "Cancelling MNN-Diffusion generation.",
                                g_mnn_diffusion_step,
                                steps,
                                width,
                                height,
                                threads);
                        throw MnnDiffusionCancelled();
                    }
                    const int pct = std::max(0, std::min(100, progress));
                    const int step = std::max(0, std::min(steps, static_cast<int>(std::round(pct * steps / 100.0))));
                    set_mnn_diffusion_progress_locked(
                            pct >= 100 ? "saving" : "generating",
                            pct >= 100 ? "Saving generated image." : "Generating image with MNN-Diffusion.",
                            step,
                            steps,
                            width,
                            height,
                            threads);
                };
        json result;
        MnnNativeExecutionEvidence executionEvidence;
        int sanaCompletedSteps = 0;
        int sanaGraphInvocationCount = 0;
        int sanaConditioningSequenceLength = 0;
        int sanaConditioningBatchSize = 0;
        int sanaTokenizerInputSequenceLength = 0;
        int sanaTokenizerInputBatchSize = 0;
        int sanaTokenizerNonPaddingTokenCount = 0;
        int sanaInputImageExecutionCount = 0;
        std::string sanaConditioningOrder;
        std::string sanaTokenizerInputOrder;
        std::string sanaConditioningArtifactSha256;
        std::string sanaExecutedInputImageSha256;
        if (sana_family) {
            mca::MnnSanaOptions options;
            options.bundle_root = root;
            options.prompt = prompt;
            options.negative_prompt = negative_prompt;
            options.output_path = output_path;
            options.task_mode = task_mode;
            options.input_image_path = input_image_path;
            options.input_image_sha256 = input_image_sha256;
            options.backend_mode = backend_mode;
            options.memory_mode = memory_mode;
            options.width = width;
            options.height = height;
            options.steps = steps;
            options.seed = seed;
            options.threads = threads;
            options.use_cfg = use_cfg;
            options.cfg_scale = cfg_scale;
            mca::MnnSanaSession session(
                    std::move(options),
                    progress_callback,
                    [steps, width, height, threads](const std::string& phase, const std::string& message) {
                        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                        set_mnn_diffusion_progress_locked(
                                phase,
                                message,
                                g_mnn_diffusion_step,
                                steps,
                                width,
                                height,
                                threads);
                    },
                    []() {
                        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                        return g_mnn_diffusion_cancel_requested;
                    });
            const bool sana_ok = session.run();
            sanaCompletedSteps = session.completed_steps();
            sanaGraphInvocationCount = session.graph_invocation_count();
            sanaConditioningSequenceLength = session.conditioning_sequence_length();
            sanaConditioningBatchSize = session.conditioning_batch_size();
            sanaConditioningOrder = session.conditioning_order();
            sanaTokenizerInputSequenceLength =
                    session.tokenizer_input_sequence_length();
            sanaTokenizerInputBatchSize = session.tokenizer_input_batch_size();
            sanaTokenizerNonPaddingTokenCount =
                    session.tokenizer_non_padding_token_count();
            sanaTokenizerInputOrder = session.tokenizer_input_order();
            sanaConditioningArtifactSha256 =
                    session.conditioning_artifact_sha256();
            sanaInputImageExecutionCount = session.input_image_execution_count();
            sanaExecutedInputImageSha256 = session.executed_input_image_sha256();
            result = json({
                {"ok", sana_ok},
                {"path", output_path},
                {"mimeType", "image/png"}
            });
        } else {
            result = run_mnn_sd15_interpreter_direct(
                    root,
                    prompt,
                    output_path,
                    executionContract,
                    executionEvidence,
                    progress_callback);
        }
        const bool ok = result.value("ok", false);
        if (!ok) {
            if (result.value("cancelled", false) ||
                result.value("error", std::string()) == "cancelled") {
                result["ok"] = false;
                result["cancelled"] = true;
                result["error"] = "cancelled";
                result["backend"] = "mnn_diffusion";
                return utf8_to_jstring(env, result.dump());
            }
            auto failed = json::parse(
                    fail(result.value("error", "MNN-Diffusion direct interpreter failed.")),
                    nullptr,
                    false);
            if (!failed.is_object()) failed = json::object();
            failed["ok"] = false;
            failed["backend"] = "mnn_diffusion";
            if (result.contains("errorCode")) failed["errorCode"] = result["errorCode"];
            if (result.contains("field")) failed["field"] = result["field"];
            return utf8_to_jstring(env, failed.dump());
        }
        if (!ok || !file_exists(output_path)) {
            const auto out = fail("MNN-Diffusion did not produce a valid output image.");
            return utf8_to_jstring(env, out);
        }
        MnnPublishedImageEvidence output_evidence;
        std::string output_evidence_error;
        if (!collect_mnn_published_image_evidence(
                output_path,
                output_evidence,
                output_evidence_error)) {
            ::unlink(output_path.c_str());
            const auto out = fail(output_evidence_error);
            return utf8_to_jstring(env, out);
        }
        {
            std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
            g_mnn_diffusion_generating = false;
            g_mnn_diffusion_loaded = false;
            g_mnn_diffusion_finished_at_ms = now_ms();
            set_mnn_diffusion_progress_locked("completed", "Image generation completed.", steps, steps, width, height, threads);
        }
        if (sana_family) {
            json nativeEffective;
            std::string evidenceError;
            if (!mnn_sana_native_effective_json(
                    params,
                    sanaCompletedSteps,
                    sanaGraphInvocationCount,
                    sanaConditioningSequenceLength,
                    sanaConditioningBatchSize,
                    sanaConditioningOrder,
                    sanaTokenizerInputSequenceLength,
                    sanaTokenizerInputBatchSize,
                    sanaTokenizerNonPaddingTokenCount,
                    sanaTokenizerInputOrder,
                    sanaConditioningArtifactSha256,
                    true,
                    nativeEffective,
                    evidenceError)) {
                const auto out = fail(evidenceError);
                return utf8_to_jstring(env, out);
            }
            const std::string nativePromptExecutionSha256 =
                    mca::image::image_prompt_execution_sha256(prompt, negative_prompt);
            if (nativePromptExecutionSha256.size() != 64U) {
                const auto out = fail(
                        "The prompts consumed by native MNN Sana conditioning could not be framed and hashed.");
                return utf8_to_jstring(env, out);
            }
            nativeEffective["nativePromptExecutionSha256"] =
                    nativePromptExecutionSha256;
            nativeEffective["nativePromptBindingStage"] =
                    "conditioning_consumed";
            nativeEffective["taskMode"] = task_mode;
            nativeEffective["inputImagePath"] = input_image_path;
            nativeEffective["maskImagePath"] = "";
            nativeEffective["controlImagePath"] = "";
            nativeEffective["inputImageExecutionCount"] =
                    sanaInputImageExecutionCount;
            nativeEffective["inputImageSha256"] = sanaExecutedInputImageSha256;
            nativeEffective["maskImageExecutionCount"] = 0;
            nativeEffective["controlImageExecutionCount"] = 0;
            nativeEffective["outputPath"] = output_path;
            nativeEffective["outputBytes"] = output_evidence.bytes;
            nativeEffective["outputSha256"] = output_evidence.sha256;
            nativeEffective["batchCount"] = batch_count;
            json out = json({
                {"ok", true},
                {"nativeExecution", true},
                {"executionStage", "semantic_generation_passed"},
                {"backend", "mnn_diffusion"},
                {"family", "SANA"},
                {"backendMode", backend_mode},
                {"runner", "sana_varp"},
                {"path", output_path},
                {"outputPath", output_path},
                {"outputBytes", output_evidence.bytes},
                {"outputSha256", output_evidence.sha256},
                {"mimeType", "image/png"},
                {"steps", steps},
                {"width", width},
                {"height", height},
                {"seed", seed},
                {"cfgScale", cfg_scale},
                {"useCfg", use_cfg},
                {"negativePromptSpecified", params.contains("negativePrompt")},
                {"sampleMethod", "flow_match"},
                {"memoryMode", memory_mode},
                {"batchCount", batch_count},
                {"taskMode", task_mode},
                {"inputImagePath", input_image_path},
                {"maskImagePath", ""},
                {"controlImagePath", ""},
                {"inputImageExecutionCount", sanaInputImageExecutionCount},
                {"inputImageSha256", sanaExecutedInputImageSha256},
                {"maskImageExecutionCount", 0},
                {"controlImageExecutionCount", 0},
                {"graphInvocationCount", sanaGraphInvocationCount},
                {"logicalBranchExecutionCount", nativeEffective["unetExecutionCount"]},
                {"conditioningSequenceLength", sanaConditioningSequenceLength},
                {"conditioningBatchSize", sanaConditioningBatchSize},
                {"conditioningOrder", sanaConditioningOrder},
                {"tokenizerInputSequenceLength", sanaTokenizerInputSequenceLength},
                {"tokenizerInputBatchSize", sanaTokenizerInputBatchSize},
                {"tokenizerNonPaddingTokenCount", sanaTokenizerNonPaddingTokenCount},
                {"tokenizerInputOrder", sanaTokenizerInputOrder},
                {"conditioningArtifactSha256", sanaConditioningArtifactSha256},
                {"nativePromptExecutionSha256", nativePromptExecutionSha256},
                {"nativePromptBindingStage", "conditioning_consumed"},
                {"nativeEffective", nativeEffective},
                {"outputs", json::array({json({
                    {"index", 0},
                    {"path", output_path},
                    {"outputBytes", output_evidence.bytes},
                    {"outputSha256", output_evidence.sha256},
                    {"mimeType", "image/png"},
                    {"seed", seed}
                })})},
                {"nativeGenerationSequence", g_mnn_diffusion_generation_sequence},
                {"nativeStartedAtMs", g_mnn_diffusion_started_at_ms}
            });
            return utf8_to_jstring(env, out.dump());
        }
        auto nativeEffective = mnn_native_effective_json(
                executionContract,
                executionEvidence);
        nativeEffective["outputPath"] = output_path;
        nativeEffective["outputBytes"] = output_evidence.bytes;
        nativeEffective["outputSha256"] = output_evidence.sha256;
        nativeEffective["batchCount"] = batch_count;
        json out = nativeEffective;
        out["ok"] = true;
        out["nativeExecution"] = true;
        out["executionStage"] = "semantic_generation_passed";
        out["backend"] = "mnn_diffusion";
        out["family"] = "SD15";
        out["backendMode"] = executionContract.backend_mode;
        out["runner"] = "direct";
        out["path"] = output_path;
        out["outputPath"] = output_path;
        out["outputBytes"] = output_evidence.bytes;
        out["outputSha256"] = output_evidence.sha256;
        out["mimeType"] = "image/png";
        out["taskMode"] = "text_to_image";
        out["inputImagePath"] = "";
        out["maskImagePath"] = "";
        out["controlImagePath"] = "";
        out["inputImageExecutionCount"] = 0;
        out["maskImageExecutionCount"] = 0;
        out["controlImageExecutionCount"] = 0;
        out["outputs"] = json::array({json({
            {"index", 0},
            {"path", output_path},
            {"outputBytes", output_evidence.bytes},
            {"outputSha256", output_evidence.sha256},
            {"mimeType", "image/png"},
            {"seed", executionContract.seed}
        })});
        out["nativeEffective"] = nativeEffective;
        out["timesteps"] = executionEvidence.timesteps;
        out["sigmas"] = executionEvidence.sigmas;
        out["initNoiseSigma"] = executionEvidence.init_noise_sigma;
        out["graphInvocationCount"] = executionEvidence.graph_invocation_count;
        out["logicalBranchExecutionCount"] = executionEvidence.unet_execution_count;
        out["negativePromptSpecified"] = !executionContract.negative_prompt.empty();
        out["nativeGenerationSequence"] = g_mnn_diffusion_generation_sequence;
        out["nativeStartedAtMs"] = g_mnn_diffusion_started_at_ms;
        out["sampleMethod"] = mnn_scheduler_product_name(
                executionContract.scheduler.config.algorithm);
        out["memoryMode"] = executionContract.memory_mode;
        if (result.contains("debug")) out["debug"] = result["debug"];
        const auto serialized = out.dump();
        return utf8_to_jstring(env, serialized);
    } catch (const mca::MnnSanaCancelled& e) {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        g_mnn_diffusion_generating = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_finished_at_ms = now_ms();
        g_mnn_diffusion_last_error = e.what();
        set_mnn_diffusion_progress_locked("cancelled", e.what(), g_mnn_diffusion_step, steps, width, height, threads);
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"family", "SANA"},
            {"cancelled", true},
            {"error", e.what()}
        }).dump();
        return utf8_to_jstring(env, out);
    } catch (const MnnDiffusionCancelled& e) {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        g_mnn_diffusion_generating = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_finished_at_ms = now_ms();
        g_mnn_diffusion_last_error = e.what();
        set_mnn_diffusion_progress_locked("cancelled", e.what(), g_mnn_diffusion_step, steps, width, height, threads);
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"cancelled", true},
            {"error", e.what()}
        }).dump();
        return utf8_to_jstring(env, out);
    } catch (const std::exception& e) {
        const auto out = fail(std::string("MNN-Diffusion exception: ") + e.what());
        return utf8_to_jstring(env, out);
    } catch (...) {
        const auto out = fail("MNN-Diffusion exception: unknown native error.");
        return utf8_to_jstring(env, out);
    }
#else
    const auto out = json({
        {"ok", false},
        {"backend", "mnn_diffusion"},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
    return utf8_to_jstring(env, out);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_getProgress(JNIEnv* env, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    const auto out = mnn_diffusion_progress_json_locked().dump();
#else
    const auto out = json({
        {"phase", "unavailable"},
        {"message", "MNN-Diffusion native runner is not linked in this APK."},
        {"step", 0},
        {"steps", 0},
        {"elapsedMs", 0},
        {"secondsPerStep", 0.0},
        {"cancelRequested", false}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_cancel(JNIEnv*, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    g_mnn_diffusion_cancel_requested = true;
    if (g_mnn_diffusion_generating) {
        g_mnn_diffusion_phase = "cancelling";
        g_mnn_diffusion_message = "Cancelling MNN-Diffusion generation.";
    }
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    const auto out = json({
        {"backend", "mnn_diffusion"},
        {"runnerReady", true},
        {"loaded", g_mnn_diffusion_loaded},
        {"generating", g_mnn_diffusion_generating},
        {"bundleRoot", g_mnn_diffusion_bundle_root},
        {"outputPath", g_mnn_diffusion_output_path},
        {"backendMode", g_mnn_diffusion_backend},
        {"backendDevices", json::array({"cpu", "opencl-experimental"})},
        {"progress", mnn_diffusion_progress_json_locked()},
        {"lastError", g_mnn_diffusion_last_error}
    }).dump();
#else
    const auto out = json({
        {"backend", "mnn_diffusion"},
        {"runnerReady", false},
        {"loaded", false},
        {"lastError", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}
