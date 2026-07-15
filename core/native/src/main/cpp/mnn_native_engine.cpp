#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <ostream>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/stat.h>

#include "nlohmann/json.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_legacy_chat_template_policy.hpp"
#include "mnn_runtime_capability_policy.hpp"
#include "mnn_stream_protocol_filter.hpp"
#include "mnn_vision_path_policy.hpp"

#if MCA_WITH_MNN_LLM
#include "llm/llm.hpp"
#endif

#ifndef MCA_WITH_MNN_DIFFUSION
#define MCA_WITH_MNN_DIFFUSION 0
#endif

#ifndef MCA_MNN_RUNTIME_CACHE_NAMESPACE
// Keep this prefix aligned with vendor/mnn/mnn-vendor.properties. Bump it when
// the pinned MNN overlay changes so ABI-sensitive mmap/KV files are never
// reused across libllm revisions.
#define MCA_MNN_RUNTIME_CACHE_NAMESPACE "19dfc834ccb8"
#endif

#if (MCA_WITH_MNN_LLM || MCA_WITH_MNN_DIFFUSION) && defined(MNN_IMGCODECS)
#include <cv/cv.hpp>
#endif

#if MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/expr/Module.hpp>
#include "tokenizer.hpp"
#include "scheduler.hpp"
#include "diffusion/stable_diffusion.hpp"
#include "diffusion/sana_diffusion.hpp"
#include "mnn_sana_session.hpp"
#endif

namespace {

using json = nlohmann::json;
class MnnDiffusionCancelled final : public std::exception {
public:
    const char *what() const noexcept override {
        return "MNN-Diffusion generation was cancelled.";
    }
};

std::mutex g_mnn_mutex;
bool g_loaded = false;
bool g_generation_active = false;
bool g_stop_requested = false;
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

#if MCA_WITH_MNN_LLM
MNN::Transformer::Llm* g_llm = nullptr;

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
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (s != nullptr && n > 0) {
            g_pending_chunk.append(s, static_cast<size_t>(n));
            append_mnn_debug_raw_output(s, static_cast<size_t>(n));
            g_streamed_bytes += static_cast<size_t>(n);
            if (g_first_chunk_at_ms == 0) {
                g_first_chunk_at_ms = now_ms();
            }
        }
        return n;
    }

    int overflow(int ch) override {
        if (ch != EOF) {
            char c = static_cast<char>(ch);
            g_pending_chunk.append(&c, 1);
            append_mnn_debug_raw_output(&c, 1);
            g_streamed_bytes += 1;
            if (g_first_chunk_at_ms == 0) {
                g_first_chunk_at_ms = now_ms();
            }
        }
        return ch;
    }

private:
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

const std::vector<std::string>& default_mnn_stop_markers() {
    // These are true end-of-turn tokens. Role-start tokens are handled by the
    // stream protocol filter: they can prefix a valid assistant answer, so
    // treating them as unconditional EOS would drop the answer itself.
    static const std::vector<std::string> markers = {
            "<eop>",
            "<|endoftext|>",
            "<|im_end|>",
            "<|end_of_turn|>",
            "<end_of_turn>",
            "<|eot_id|>"
    };
    return markers;
}

void append_stop_marker(std::vector<std::string>& target, const std::string& marker) {
    // Model configuration is untrusted downloaded data. A bounded marker keeps
    // the streaming suffix scan linear and prevents a malformed package from
    // retaining an arbitrary amount of user-visible output.
    constexpr size_t kMaxStopMarkerBytes = 256;
    if (marker.empty() || marker.size() > kMaxStopMarkerBytes ||
            mca::mnn::is_role_header_marker(marker)) return;
    if (std::find(target.begin(), target.end(), marker) == target.end()) {
        target.push_back(marker);
    }
}

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
                // JSON strings cannot contain unescaped C0 controls. This is
                // especially important for the bounded native diagnostic
                // trace, which intentionally preserves the model's raw bytes.
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
    // Native model output is expected to be UTF-8, but an interrupted stream
    // can end in a partial sequence. Decode/encode with replacement before
    // putting it into JSON so a debug trace can never corrupt stats parsing.
    const auto decoded = mca::utf8::decode_to_utf16(input, false);
    return mca::utf8::encode_from_utf16(decoded.utf16.data(), decoded.utf16.size());
}

std::string parent_dir(const std::string& path) {
    const auto pos = path.find_last_of("/\\");
    if (pos == std::string::npos) return ".";
    if (pos == 0) return path.substr(0, 1);
    if (pos == 2 && path.size() > 2 && path[1] == ':' &&
        (path[2] == '/' || path[2] == '\\')) {
        return path.substr(0, 3);
    }
    return path.substr(0, pos);
}

bool file_exists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool nonempty_regular_file_exists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0;
}

bool remove_existing_output_file(const std::string& path, std::string& error) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) {
        if (errno == ENOENT) return true;
        error = "Unable to inspect output path: " + path + ": " + std::strerror(errno);
        return false;
    }
    if (!S_ISREG(st.st_mode)) {
        error = "Output path exists but is not a regular file: " + path;
        return false;
    }
    if (std::remove(path.c_str()) != 0) {
        error = "Unable to remove existing output file: " + path + ": " + std::strerror(errno);
        return false;
    }
    return true;
}

bool directory_exists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool validate_output_path_for_write(const std::string& path, std::string& error) {
    if (path.empty()) {
        error = "Output path is empty.";
        return false;
    }
    const auto parent = parent_dir(path);
    struct stat parent_stat {};
    if (stat(parent.c_str(), &parent_stat) != 0) {
        error = "Output parent directory does not exist or cannot be inspected: " + parent +
                ": " + std::strerror(errno);
        return false;
    }
    if (!S_ISDIR(parent_stat.st_mode)) {
        error = "Output parent path is not a directory: " + parent;
        return false;
    }

    struct stat output_stat {};
    if (stat(path.c_str(), &output_stat) != 0) {
        if (errno == ENOENT) {
            return true;
        }
        error = "Unable to inspect output path: " + path + ": " + std::strerror(errno);
        return false;
    }
    if (!S_ISREG(output_stat.st_mode)) {
        error = "Output path exists but is not a regular file: " + path;
        return false;
    }
    return true;
}

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

std::string normalize_mnn_model_path(const std::string& path) {
    if (path.empty()) return path;
    if (directory_exists(path) && path.back() != '/' && path.back() != '\\') {
        return path + "/";
    }
    return path;
}

std::string join_path(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/' || dir.back() == '\\') return dir + name;
    return dir + "/" + name;
}

std::string resolve_mnn_config_path(const std::string& path) {
    const auto normalized = normalize_mnn_model_path(path);
    if (directory_exists(normalized)) {
        return join_path(normalized, "config.json");
    }
    return normalized;
}

json read_json_file_or_empty(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) return json::object();
    json parsed = json::parse(input, nullptr, false);
    if (!parsed.is_object()) return json::object();
    return parsed;
}

void collect_mnn_visual_path_declarations(
        const json& value,
        std::vector<std::string>& paths,
        int depth = 0,
        bool allowGenericModelPath = false) {
    // Model-side configs occasionally nest visual_model under a `vision`,
    // `visual`, or `projector` object.  Walk JSON objects/arrays rather than
    // assuming one fixed exporter layout, but keep a depth bound so malformed
    // metadata cannot turn preflight into an unbounded traversal.
    if (depth > 8) return;
    if (value.is_array()) {
        for (const auto& item : value) {
            collect_mnn_visual_path_declarations(item, paths, depth + 1, allowGenericModelPath);
        }
        return;
    }
    if (!value.is_object()) return;
    for (auto it = value.begin(); it != value.end(); ++it) {
        const auto key = mca::mnn::lowerAscii(it.key());
        if ((mca::mnn::isMnnVisualPathKey(it.key()) ||
             (allowGenericModelPath && (key == "model" || key == "path"))) &&
            it.value().is_string()) {
            mca::mnn::appendUniqueMnnVisualPath(it.value().get<std::string>(), paths);
        }
        if (it.value().is_object() || it.value().is_array()) {
            const bool childAllowsGeneric = key == "vision" || key == "visual" ||
                    key == "image_encoder" ||
                    key == "vision_encoder" || key == "visual_encoder";
            collect_mnn_visual_path_declarations(
                    it.value(), paths, depth + 1, childAllowsGeneric || allowGenericModelPath);
        }
    }
}

std::vector<std::string> mnn_visual_path_declarations(
        const std::string& root,
        const json& config) {
    std::vector<std::string> paths;
    collect_mnn_visual_path_declarations(config, paths);

    // The root config points at the model-side llm_config.json in most MNN
    // bundles.  Inspect it too because several exporters keep the visual
    // encoder declaration there rather than in config.json.
    std::string llmConfigPath = "llm_config.json";
    const auto llmConfigIt = config.find("llm_config");
    if (llmConfigIt != config.end() && llmConfigIt->is_string()) {
        const auto normalized = mca::mnn::normalizeMnnVisualRelativePath(
                llmConfigIt->get<std::string>());
        if (!normalized.empty()) llmConfigPath = normalized;
    }
    const auto llmConfig = read_json_file_or_empty(join_path(root, llmConfigPath));
    collect_mnn_visual_path_declarations(llmConfig, paths);
    return paths;
}

std::string select_mnn_visual_model_path(
        const std::string& root,
        const json& config,
        const std::string& configuredVisual) {
    const auto declared = mnn_visual_path_declarations(root, config);
    return mca::mnn::selectMnnVisualModelPath(
            configuredVisual,
            declared,
            [&root](const std::string& relativePath) {
                return nonempty_regular_file_exists(join_path(root, relativePath));
            });
}

bool is_mnn_stop_marker_key(const std::string& rawKey) {
    std::string key;
    key.reserve(rawKey.size());
    for (const unsigned char ch : rawKey) {
        key.push_back(static_cast<char>(std::tolower(ch)));
    }
    return key == "eos_token" ||
           key == "eod_token" ||
           key == "end_token" ||
           key == "end_of_turn_token" ||
           key == "stop" ||
           key == "stops" ||
           key == "stop_word" ||
           key == "stop_words" ||
           key == "stop_token" ||
           key == "stop_tokens";
}

void append_mnn_stop_markers_from_json(const json& value, std::vector<std::string>& target, int depth = 0) {
    // Only inspect known stop-token fields rather than arbitrary template text:
    // chat templates contain user-facing role strings that are not stop tokens.
    if (depth > 4) return;
    if (value.is_string()) {
        append_stop_marker(target, value.get<std::string>());
        return;
    }
    if (value.is_array()) {
        for (const auto& item : value) {
            append_mnn_stop_markers_from_json(item, target, depth + 1);
        }
        return;
    }
    if (!value.is_object()) return;
    for (auto it = value.begin(); it != value.end(); ++it) {
        if (is_mnn_stop_marker_key(it.key())) {
            append_mnn_stop_markers_from_json(it.value(), target, depth + 1);
        } else if (it.value().is_object()) {
            append_mnn_stop_markers_from_json(it.value(), target, depth + 1);
        }
    }
}

std::vector<std::string> mnn_model_stop_markers(const std::string& configPath) {
    auto markers = default_mnn_stop_markers();
    append_mnn_stop_markers_from_json(read_json_file_or_empty(configPath), markers);
    return markers;
}

// The app links one coherent MNN 3.6 runtime.  Gemma 4's public MNN 3.5
// visual graph crashes in MNN::Transformer::Omni::load before the Java layer
// can recover, so keep a native preflight in addition to the repository gate.
// Text-only isolation packages are unaffected because they do not enable or
// contain a visual model.
std::string incompatible_legacy_mnn_visual_graph_message(const std::string& requestedPath) {
    constexpr const char* kLegacyGraphVersion = "3.5.0";
    constexpr const char* kProductRuntimeVersion = "3.6";
    const auto configPath = resolve_mnn_config_path(requestedPath);
    const auto root = parent_dir(configPath);
    const auto config = read_json_file_or_empty(configPath);
    if (!config.is_object()) return "";

    std::string configuredVisual;
    bool configuredVisualDeclared = false;
    const auto visualIt = config.find("visual_model");
    if (visualIt != config.end() && visualIt->is_string()) {
        configuredVisualDeclared = true;
        configuredVisual = mca::mnn::normalizeMnnVisualRelativePath(visualIt->get<std::string>());
    }
    const auto selectedVisual = select_mnn_visual_model_path(root, config, configuredVisual);
    const bool visualEnabled = config.value("is_visual", false) ||
            configuredVisualDeclared || !selectedVisual.empty();
    if (!visualEnabled) return "";
    const auto visualName = selectedVisual.empty()
            ? (configuredVisual.empty() ? std::string("visual.mnn") : configuredVisual)
            : selectedVisual;

    // llm.mnn.json is MNN's exporter metadata sidecar. A model may use a
    // differently named JSON embedding sidecar or a renamed llm_model, so
    // inspect each safe relative metadata name before the standard file. This
    // must mirror the repository gate: raw/debug native loads bypass Kotlin.
    std::vector<std::string> metadataPaths;
    const auto is_safe_relative = [](const std::string& name) {
        if (name.empty() || name.front() == '/' || name.find('\\') != std::string::npos) return false;
        size_t segmentStart = 0;
        while (segmentStart <= name.size()) {
            const size_t segmentEnd = name.find('/', segmentStart);
            const auto segment = name.substr(
                    segmentStart,
                    segmentEnd == std::string::npos ? std::string::npos : segmentEnd - segmentStart);
            if (segment.empty() || segment == "." || segment == "..") return false;
            if (segmentEnd == std::string::npos) break;
            segmentStart = segmentEnd + 1;
        }
        return true;
    };
    const auto add_metadata_path = [&metadataPaths, &root, &is_safe_relative](const std::string& name) {
        const bool isJson = name.size() >= 5 && name.compare(name.size() - 5, 5, ".json") == 0;
        if (isJson && is_safe_relative(name)) {
            metadataPaths.push_back(join_path(root, name));
        }
    };
    const auto embeddingIt = config.find("embedding_file");
    if (embeddingIt != config.end() && embeddingIt->is_string()) {
        add_metadata_path(embeddingIt->get<std::string>());
    }
    const auto llmModelIt = config.find("llm_model");
    if (llmModelIt != config.end() && llmModelIt->is_string()) {
        const auto modelName = llmModelIt->get<std::string>();
        const bool isMnn = modelName.size() >= 4 &&
                modelName.compare(modelName.size() - 4, 4, ".mnn") == 0;
        if (isMnn && is_safe_relative(modelName)) {
            add_metadata_path(modelName + ".json");
        }
    }
    const bool visualNameIsMnn = visualName.size() >= 4 &&
            visualName.compare(visualName.size() - 4, 4, ".mnn") == 0;
    if (visualNameIsMnn && is_safe_relative(visualName)) {
        add_metadata_path(visualName + ".json");
    }
    metadataPaths.push_back(join_path(root, "llm.mnn.json"));
    std::sort(metadataPaths.begin(), metadataPaths.end());
    metadataPaths.erase(std::unique(metadataPaths.begin(), metadataPaths.end()), metadataPaths.end());
    for (const auto& metadataPath : metadataPaths) {
        const auto metadata = read_json_file_or_empty(metadataPath);
        if (!metadata.is_object()) continue;
        const auto extraInfo = metadata.find("extraInfo");
        if (extraInfo == metadata.end() || !extraInfo->is_object()) continue;
        const auto version = extraInfo->value("version", std::string());
        if (version == kLegacyGraphVersion) {
            return std::string("MNN 多模态图版本 ") + kLegacyGraphVersion +
                    " 与当前 MNN " + kProductRuntimeVersion +
                    " runtime 不兼容，已安全阻止加载。请使用 MNN " +
                    kProductRuntimeVersion +
                    " 重新导出的多模态包，或移除视觉组件后仅作为文本模型使用。";
        }
    }
    return "";
}

bool write_json_file(const std::string& path, const json& value) {
    std::ofstream output(path.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) return false;
    output << value.dump(4);
    return output.good();
}

bool has_mnn_jinja_chat_template(const json& config) {
    if (!config.is_object()) return false;
    const auto jinjaIt = config.find("jinja");
    if (jinjaIt == config.end() || !jinjaIt->is_object()) return false;
    const auto templateIt = jinjaIt->find("chat_template");
    return templateIt != jinjaIt->end() && templateIt->is_string() &&
            !templateIt->get<std::string>().empty();
}

bool inject_legacy_mnn_chat_template(json& runtimeConfig, const json& modelConfig) {
    if (has_mnn_jinja_chat_template(runtimeConfig) || has_mnn_jinja_chat_template(modelConfig)) {
        return false;
    }
    std::string legacyPrompt;
    const auto readPrompt = [&legacyPrompt](const json& source) {
        if (!legacyPrompt.empty() || !source.is_object()) return;
        const auto promptIt = source.find("prompt_template");
        if (promptIt != source.end() && promptIt->is_string()) {
            legacyPrompt = promptIt->get<std::string>();
        }
    };
    readPrompt(modelConfig);
    readPrompt(runtimeConfig);
    if (!mca::mnn::isLegacyQwenChatMlPromptTemplate(legacyPrompt)) return false;

    runtimeConfig["jinja"]["chat_template"] =
            mca::mnn::legacyQwenChatMlJinjaTemplate();
    runtimeConfig["jinja"]["eos"] = "<|im_end|>";
    return true;
}

std::string prepare_mnn_runtime_config(const std::string& requestedPath) {
    const auto configPath = resolve_mnn_config_path(requestedPath);
    const auto root = parent_dir(configPath);
    auto config = read_json_file_or_empty(configPath);
    g_visual_model_path.clear();
    g_vision_ready = false;
    if (!config.is_object()) return configPath;

    const auto configuredLlmConfig = config.value("llm_config", std::string("llm_config.json"));
    const auto safeLlmConfig = mca::mnn::normalizeMnnVisualRelativePath(configuredLlmConfig);
    const auto modelConfig = safeLlmConfig.empty()
            ? json::object()
            : read_json_file_or_empty(join_path(root, safeLlmConfig));
    bool configChanged = inject_legacy_mnn_chat_template(config, modelConfig);

    std::string configuredVisual;
    bool configuredVisualDeclared = false;
    auto visualIt = config.find("visual_model");
    if (visualIt != config.end() && visualIt->is_string()) {
        configuredVisualDeclared = true;
        configuredVisual = mca::mnn::normalizeMnnVisualRelativePath(visualIt->get<std::string>());
    }
    const auto selectedVisual = select_mnn_visual_model_path(root, config, configuredVisual);
    const bool visualExists = !selectedVisual.empty();
    const bool shouldEnableVision = visualExists || config.value("is_visual", false) ||
            configuredVisualDeclared;
    if (shouldEnableVision) {
        configChanged = true;
        config["is_visual"] = true;
        if (visualExists) {
            // Always persist the resolved relative path.  This makes nested
            // exporter layouts visible to MNN's Omni loader and keeps the
            // g_vision_ready flag truthful for the Java/UI/API layers.
            config["visual_model"] = selectedVisual;
            g_visual_model_path = join_path(root, selectedVisual);
        } else if (configuredVisual.empty()) {
            config["visual_model"] = "visual.mnn";
            g_visual_model_path = join_path(root, "visual.mnn");
        } else {
            g_visual_model_path = configuredVisual;
        }
        g_vision_ready = visualExists;
    }

    if (!configChanged) return configPath;

    const auto runtimeConfigPath = join_path(root, "mca_runtime_config.json");
    if (!write_json_file(runtimeConfigPath, config)) {
        throw std::runtime_error(
                "MNN compatibility runtime config could not be written: " + runtimeConfigPath);
    }
    return runtimeConfigPath;
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
    g_mnn_debug_prompt_truncated = false;
    g_mnn_debug_prompt.clear();
}

struct MnnChatImageInput {
    std::string key;
    std::string path;
};

std::string text_from_content(
        const json& content,
        std::vector<MnnChatImageInput>* imageInputs = nullptr) {
    if (content.is_string()) return content.get<std::string>();
    if (!content.is_array()) return content.dump();
    std::vector<std::string> textParts;
    std::vector<std::string> imageTags;
    for (const auto& part : content) {
        if (!part.is_object()) continue;
        const auto type = part.value("type", "");
        if (type == "text") {
            textParts.push_back(part.value("text", ""));
        } else if (type == "image_url") {
            std::string url;
            const auto image_url = part.find("image_url");
            if (image_url != part.end()) {
                if (image_url->is_string()) {
                    url = image_url->get<std::string>();
                } else if (image_url->is_object()) {
                    const auto urlIt = image_url->find("url");
                    if (urlIt != image_url->end() && urlIt->is_string()) {
                        url = urlIt->get<std::string>();
                    }
                }
            }
            if (!url.empty()) {
                if (imageInputs == nullptr) {
                    imageTags.push_back("<img>" + url + "</img>");
                    continue;
                }
                const auto key = "mca_image_" + std::to_string(imageInputs->size());
                imageInputs->push_back({key, url});
                imageTags.push_back("<img>" + key + "</img>");
            }
        }
    }
    return mca::mnn::composeMnnImageFirstPromptContent(imageTags, textParts);
}

void reset_generation_state_locked() {
    g_generation_active = false;
    g_stop_requested = false;
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
        {"cancelRequested", g_mnn_diffusion_cancel_requested}
    });
}

std::string normalize_mnn_diffusion_identifier(std::string value) {
    const auto begin = std::find_if_not(value.begin(), value.end(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    });
    const auto end = std::find_if_not(value.rbegin(), value.rend(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    }).base();
    if (begin >= end) return "";
    value.assign(begin, end);
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool canonical_mnn_diffusion_backend(
        const std::string& requested,
        std::string& canonical,
        std::string& error) {
    const auto normalized = normalize_mnn_diffusion_identifier(requested);
    if (normalized.empty() || normalized == "cpu") {
        canonical = "cpu";
        return true;
    }
    if (normalized == "opencl" || normalized == "gpu") {
        canonical = "opencl";
        return true;
    }
    error = "Unsupported MNN-Diffusion backend '" + requested +
            "'. Supported values are cpu and opencl (gpu is an alias for opencl).";
    return false;
}

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

bool is_blank_text(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    });
}

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

std::string mnn_diffusion_missing_component(const std::string& root, const std::string& family) {
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
    if (!file_exists(root + "/text_encoder.mnn")) missing.emplace_back("text_encoder.mnn");
    if (!file_exists(root + "/text_encoder.mnn.weight")) missing.emplace_back("text_encoder.mnn.weight");
    if (!file_exists(root + "/unet.mnn")) missing.emplace_back("unet.mnn");
    if (!file_exists(root + "/unet.mnn.weight")) missing.emplace_back("unet.mnn.weight");
    if (!file_exists(root + "/vae_decoder.mnn")) missing.emplace_back("vae_decoder.mnn");
    if (!file_exists(root + "/vae_decoder.mnn.weight")) missing.emplace_back("vae_decoder.mnn.weight");
    if (!file_exists(root + "/tokenizer.mtok")) {
        missing.emplace_back("tokenizer.mtok (required by the linked MNN diffusion tokenizer)");
    }
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

struct UnetSmokeProfile {
    std::string name;
    bool resizeDefer = false;
    bool inputUser = false;
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

template <typename T>
bool copy_vector_to_tensor(MNN::Tensor* tensor, const std::vector<T>& values, std::string& error);

json run_unet_interpreter_smoke_once(
        const std::string& root,
        const std::string& backendMode,
        const UnetSmokeProfile& profile) {
    const auto path = root + "/unet.mnn";
    if (!file_exists(path)) {
        return json({{"ok", false}, {"error", "unet.mnn does not exist."}});
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
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
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
    // MNN's StableDiffusion wrapper feeds the UNet in NCHW with CFG batch=2:
    // sample=[2,4,64,64], timestep=[1], encoder_hidden_states=[2,77,768].
    // Using NHWC or batch=1 makes some converted SD1.5 UNets fail at resize
    // with COMPUTE_SIZE_ERROR before any operator executes.
    interpreter->resizeTensor(sample, {2, 4, 64, 64});
    interpreter->resizeTensor(timestep, {1});
    interpreter->resizeTensor(encoder, {2, 77, 768});
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
            {"error", "UNet resizeSession failed or invalidated an input tensor."},
            {"profile", profile.name},
            {"resizeStatusBefore", resizeStatusBefore},
            {"resizeStatusAfter", resizeStatusAfter},
            {"inputs", inputs}
        });
    }

    std::vector<float> sampleBuffer(static_cast<size_t>(2 * 4 * 64 * 64), 0.0f);
    std::vector<float> encoderBuffer(static_cast<size_t>(2 * 77 * 768), 0.0f);
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
        {"classifierFreeGuidanceBatch", 2}
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
    const std::vector<UnetSmokeProfile> profiles = {
            {"defer-inside", true, false}
    };
    json attempts = json::array();
    json best = json::object();
    for (const auto& profile : profiles) {
        auto attempt = run_unet_interpreter_smoke_once(root, backendMode, profile);
        attempts.push_back(attempt);
        if (attempt.value("ok", false)) {
            attempt["attempts"] = attempts;
            return attempt;
        }
        if (best.empty()) {
            best = attempt;
        }
    }
    if (best.empty()) {
        best = json({{"ok", false}, {"error", "UNet smoke did not run any profile."}});
    }
    best["attempts"] = attempts;
    return best;
}

struct FloatTensorData {
    std::vector<float> values;
    std::vector<int> shape;
};

size_t tensor_element_count(const std::vector<int>& shape);

std::string tensor_shape_string(const std::vector<int>& shape) {
    std::ostringstream out;
    out << "[";
    for (size_t index = 0; index < shape.size(); ++index) {
        if (index > 0) {
            out << ",";
        }
        out << shape[index];
    }
    out << "]";
    return out.str();
}

bool validate_float_tensor_contract(
        const FloatTensorData& tensor,
        const std::vector<int>& expected_shape,
        const std::string& label,
        std::string& error) {
    if (tensor.shape != expected_shape) {
        error = label + " shape mismatch: expected " + tensor_shape_string(expected_shape) +
                ", got " + tensor_shape_string(tensor.shape) + ".";
        return false;
    }
    const size_t expected_elements = tensor_element_count(expected_shape);
    if (expected_elements == 0 || tensor.values.size() != expected_elements) {
        std::ostringstream out;
        out << label << " element count mismatch: expected " << expected_elements
            << ", got " << tensor.values.size() << ".";
        error = out.str();
        return false;
    }
    return validate_finite_float_vector(tensor.values, label, error);
}

std::vector<int> mnn_tensor_shape(const MNN::Tensor* tensor) {
    std::vector<int> shape;
    if (tensor == nullptr) return shape;
    for (int i = 0; i < tensor->dimensions(); ++i) {
        shape.push_back(tensor->length(i));
    }
    return shape;
}

size_t tensor_element_count(const std::vector<int>& shape) {
    if (shape.empty()) return 0;
    size_t count = 1;
    for (int dim : shape) {
        if (dim <= 0) return 0;
        const size_t size = static_cast<size_t>(dim);
        if (count > std::numeric_limits<size_t>::max() / size) {
            return 0;
        }
        count *= size;
    }
    return count;
}

std::vector<float> nchw_to_nhwc(
        const std::vector<float>& input,
        int batch,
        int channels,
        int height,
        int width) {
    std::vector<float> output(input.size());
    const size_t expected = tensor_element_count({batch, channels, height, width});
    if (expected == 0 || input.size() != expected) return input;
    for (int n = 0; n < batch; ++n) {
        for (int c = 0; c < channels; ++c) {
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    const size_t src = ((static_cast<size_t>(n) * channels + c) * height + y) * width + x;
                    const size_t dst = ((static_cast<size_t>(n) * height + y) * width + x) * channels + c;
                    output[dst] = input[src];
                }
            }
        }
    }
    return output;
}

std::vector<float> nhwc_to_nchw(
        const std::vector<float>& input,
        int batch,
        int height,
        int width,
        int channels) {
    std::vector<float> output(input.size());
    const size_t expected = tensor_element_count({batch, height, width, channels});
    if (expected == 0 || input.size() != expected) return input;
    for (int n = 0; n < batch; ++n) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                for (int c = 0; c < channels; ++c) {
                    const size_t src = ((static_cast<size_t>(n) * height + y) * width + x) * channels + c;
                    const size_t dst = ((static_cast<size_t>(n) * channels + c) * height + y) * width + x;
                    output[dst] = input[src];
                }
            }
        }
    }
    return output;
}

bool configure_mnn_session(
        MNN::ScheduleConfig& config,
        MNN::BackendConfig& backendConfig,
        const std::string& backendMode,
        int threads,
        std::string& error,
        bool requireHighPrecision = false) {
    std::string canonical_backend;
    if (!validate_mnn_diffusion_runtime_options(backendMode, threads, canonical_backend, error)) {
        return false;
    }
    const bool useOpenCl = canonical_backend == "opencl";
    config.type = useOpenCl ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    config.numThread = useOpenCl ? 1 : threads;
    if (useOpenCl) {
        config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    }
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
    // SD1.5's converted text encoder / UNet can emit NaN on the CPU in
    // Precision_Low, and the same low-precision path is the leading suspect
    // for the striped OpenCL module output.  Keep the existing fast default
    // for unrelated probes, but make the direct diffusion pipeline explicit
    // about numerical correctness.
    backendConfig.precision = requireHighPrecision
            ? MNN::BackendConfig::Precision_High
            : MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    return true;
}

void configure_direct_interpreter(
        MNN::Interpreter* interpreter,
        bool resizeDefer = false,
        bool inputUser = false) {
    if (interpreter == nullptr) return;
    interpreter->setSessionMode(MNN::Interpreter::Session_Debug);
    interpreter->setSessionMode(inputUser
            ? MNN::Interpreter::Session_Input_User
            : MNN::Interpreter::Session_Input_Inside);
    interpreter->setSessionMode(resizeDefer
            ? MNN::Interpreter::Session_Resize_Defer
            : MNN::Interpreter::Session_Resize_Direct);
}

template <typename T>
bool copy_vector_to_tensor(MNN::Tensor* tensor, const std::vector<T>& values, std::string& error) {
    if (tensor == nullptr) {
        error = "Input tensor is missing.";
        return false;
    }
    if (tensor->elementSize() != static_cast<int>(values.size())) {
        std::ostringstream out;
        out << "Input tensor element mismatch: expected " << tensor->elementSize()
            << ", got " << values.size() << ".";
        error = out.str();
        return false;
    }
    auto* direct = tensor->host<T>();
    if (direct != nullptr) {
        std::copy(values.begin(), values.end(), direct);
        return true;
    }
    std::unique_ptr<MNN::Tensor> hostTensor(MNN::Tensor::createHostTensorFromDevice(tensor, false));
    if (!hostTensor) {
        error = "Failed to allocate host input tensor.";
        return false;
    }
    if (hostTensor->elementSize() != static_cast<int>(values.size())) {
        std::ostringstream out;
        out << "Host input tensor element mismatch: expected " << hostTensor->elementSize()
            << ", got " << values.size() << ".";
        error = out.str();
        return false;
    }
    auto* ptr = hostTensor->host<T>();
    if (ptr == nullptr) {
        error = "Failed to map host input tensor.";
        return false;
    }
    std::copy(values.begin(), values.end(), ptr);
    if (!tensor->copyFromHostTensor(hostTensor.get())) {
        error = "copyFromHostTensor failed.";
        return false;
    }
    return true;
}

float fp16_to_float(uint16_t value);
float bf16_to_float(uint16_t value);

json float_vector_stats_json(const std::vector<float>& values, size_t sampleLimit = 8) {
    json samples = json::array();
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
    json out = {
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

json float_vector_abs_diff_stats_json(const std::vector<float>& a, const std::vector<float>& b) {
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

template <typename Reader>
json raw_tensor_stats_json(size_t availableCount, size_t expectedCount, Reader reader) {
    const size_t count = std::min(availableCount, expectedCount);
    std::vector<float> values;
    values.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        values.push_back(reader(i));
    }
    auto stats = float_vector_stats_json(values);
    stats["available"] = static_cast<long long>(availableCount);
    stats["read"] = static_cast<long long>(count);
    return stats;
}

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

void append_utf8_codepoint(std::string& out, int codepoint) {
    if (codepoint <= 0x7f) {
        out.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | ((codepoint >> 6) & 0x1f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | ((codepoint >> 12) & 0x0f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | ((codepoint >> 18) & 0x07)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

std::vector<std::string> build_clip_byte_encoder() {
    std::vector<int> bytes;
    for (int i = 33; i <= 126; ++i) bytes.push_back(i);
    for (int i = 0xa1; i <= 0xac; ++i) bytes.push_back(i);
    for (int i = 0xae; i <= 0xff; ++i) bytes.push_back(i);
    std::vector<bool> present(256, false);
    for (int b : bytes) present[static_cast<size_t>(b)] = true;
    std::vector<int> codepoints = bytes;
    int n = 0;
    for (int b = 0; b < 256; ++b) {
        if (!present[static_cast<size_t>(b)]) {
            bytes.push_back(b);
            codepoints.push_back(256 + n);
            ++n;
        }
    }
    std::vector<std::string> out(256);
    for (size_t i = 0; i < bytes.size(); ++i) {
        append_utf8_codepoint(out[static_cast<size_t>(bytes[i])], codepoints[i]);
    }
    return out;
}

std::vector<std::string> clip_pretokenize(std::string text) {
    std::vector<std::string> tokens;
    std::string cleaned;
    bool last_space = true;
    for (unsigned char c : text) {
        if (std::isspace(c)) {
            if (!last_space) {
                cleaned.push_back(' ');
                last_space = true;
            }
        } else {
            cleaned.push_back(static_cast<char>(std::tolower(c)));
            last_space = false;
        }
    }
    auto flush = [&](std::string& current) {
        if (!current.empty()) {
            tokens.push_back(current);
            current.clear();
        }
    };
    std::string current;
    enum class Kind { None, Letter, Number, Other, Utf8 };
    Kind kind = Kind::None;
    for (size_t i = 0; i < cleaned.size();) {
        const unsigned char c = static_cast<unsigned char>(cleaned[i]);
        if (std::isspace(c)) {
            flush(current);
            kind = Kind::None;
            ++i;
            continue;
        }
        Kind next = Kind::Other;
        size_t count = 1;
        if (c < 0x80) {
            if (std::isalpha(c)) {
                next = Kind::Letter;
            } else if (std::isdigit(c)) {
                next = Kind::Number;
            } else {
                next = Kind::Other;
            }
        } else {
            next = Kind::Utf8;
            if ((c & 0xe0u) == 0xc0u) count = 2;
            else if ((c & 0xf0u) == 0xe0u) count = 3;
            else if ((c & 0xf8u) == 0xf0u) count = 4;
            count = std::min(count, cleaned.size() - i);
        }
        if (kind != Kind::None && next != kind) {
            flush(current);
        }
        kind = next;
        current.append(cleaned, i, count);
        i += count;
        if (next == Kind::Number) {
            flush(current);
            kind = Kind::None;
        }
    }
    flush(current);
    return tokens;
}

struct ClipBpeTokenizer {
    std::unordered_map<std::string, int> vocab;
    std::unordered_map<std::string, int> ranks;
    std::vector<std::string> byte_encoder;
    int bos_id = 49406;
    int eos_id = 49407;
    std::string end_suffix = "</w>";

    static std::string pair_key(const std::string& a, const std::string& b) {
        return a + "\x1f" + b;
    }

    bool load(const std::string& path, std::string& error) {
        std::ifstream input(path.c_str(), std::ios::binary);
        if (!input.good()) {
            error = "Failed to open tokenizer.json.";
            return false;
        }
        json parsed = json::parse(input, nullptr, false);
        if (!parsed.is_object() || !parsed.contains("model") || !parsed["model"].is_object()) {
            error = "tokenizer.json is not a Hugging Face BPE tokenizer.";
            return false;
        }
        const auto& model = parsed["model"];
        if (model.value("type", "") != "BPE" || !model.contains("vocab") || !model["vocab"].is_object()) {
            error = "tokenizer.json model is not BPE.";
            return false;
        }
        end_suffix = model.value("end_of_word_suffix", "</w>");
        for (auto it = model["vocab"].begin(); it != model["vocab"].end(); ++it) {
            if (it.value().is_number_integer()) {
                vocab[it.key()] = it.value().get<int>();
            }
        }
        if (model.contains("merges") && model["merges"].is_array()) {
            int rank = 0;
            for (const auto& merge : model["merges"]) {
                std::string first;
                std::string second;
                if (merge.is_array() && merge.size() >= 2) {
                    first = merge[0].get<std::string>();
                    second = merge[1].get<std::string>();
                } else if (merge.is_string()) {
                    std::istringstream parts(merge.get<std::string>());
                    parts >> first >> second;
                }
                if (!first.empty() && !second.empty()) {
                    ranks[pair_key(first, second)] = rank++;
                }
            }
        }
        auto bos = vocab.find("<|startoftext|>");
        auto eos = vocab.find("<|endoftext|>");
        if (bos != vocab.end()) bos_id = bos->second;
        if (eos != vocab.end()) eos_id = eos->second;
        byte_encoder = build_clip_byte_encoder();
        if (vocab.empty() || ranks.empty() || byte_encoder.size() != 256) {
            error = "tokenizer.json BPE vocab or merges are empty.";
            return false;
        }
        return true;
    }

    std::vector<int> encode_single(const std::string& text, int max_len) const {
        std::vector<int> ids;
        ids.push_back(bos_id);
        for (const auto& token : clip_pretokenize(text)) {
            std::vector<std::string> word;
            for (unsigned char c : token) {
                word.push_back(byte_encoder[static_cast<size_t>(c)]);
            }
            if (word.empty()) continue;
            word.back() += end_suffix;
            while (word.size() > 1) {
                int best_rank = std::numeric_limits<int>::max();
                size_t best_index = 0;
                for (size_t i = 0; i + 1 < word.size(); ++i) {
                    auto it = ranks.find(pair_key(word[i], word[i + 1]));
                    if (it != ranks.end() && it->second < best_rank) {
                        best_rank = it->second;
                        best_index = i;
                    }
                }
                if (best_rank == std::numeric_limits<int>::max()) break;
                word[best_index] += word[best_index + 1];
                word.erase(word.begin() + static_cast<std::ptrdiff_t>(best_index + 1));
            }
            for (const auto& piece : word) {
                auto it = vocab.find(piece);
                ids.push_back(it == vocab.end() ? eos_id : it->second);
            }
        }
        ids.push_back(eos_id);
        if (max_len > 0) {
            if (static_cast<int>(ids.size()) > max_len) {
                ids.resize(static_cast<size_t>(max_len));
                ids[static_cast<size_t>(max_len - 1)] = eos_id;
            } else {
                while (static_cast<int>(ids.size()) < max_len) {
                    ids.push_back(eos_id);
                }
            }
        }
        return ids;
    }

    std::vector<int> encode_pair(const std::string& text, int max_len) const {
        std::vector<int> out(static_cast<size_t>(max_len * 2), eos_id);
        auto neg = encode_single("", max_len);
        auto pos = encode_single(text, max_len);
        std::copy(neg.begin(), neg.end(), out.begin());
        std::copy(pos.begin(), pos.end(), out.begin() + max_len);
        return out;
    }
};

bool build_local_dream_clip_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        std::vector<float>& input,
        std::string& error,
        const std::string& token_embedding_mode = "auto",
        size_t* token_embedding_bytes = nullptr,
        std::string* token_embedding_mode_used = nullptr) {
    constexpr int kMaxTextLen = 77;
    constexpr int kEmbeddingSize = 768;
    constexpr int kVocabSize = 49408;
    if (token_ids.size() != kMaxTextLen) {
        error = "CLIP token id count must be 77.";
        return false;
    }
    const auto token_bytes = read_binary_bytes(root + "/token_emb.bin");
    const auto pos_bytes = read_binary_bytes(root + "/pos_emb.bin");
    const size_t expected_token_bytes = static_cast<size_t>(kVocabSize) * kEmbeddingSize * sizeof(uint16_t);
    const size_t expected_pos_bytes = static_cast<size_t>(kMaxTextLen) * kEmbeddingSize * sizeof(float);
    const bool token_emb_has_extra_slice = token_bytes.size() == expected_token_bytes * 2;
    if (token_bytes.size() != expected_token_bytes && !token_emb_has_extra_slice) {
        std::ostringstream out;
        out << "token_emb.bin has unexpected size: " << token_bytes.size()
            << ", expected " << expected_token_bytes
            << " or " << (expected_token_bytes * 2) << " for a dual-slice SD1.5 QNN bundle.";
        error = out.str();
        return false;
    }
    if (pos_bytes.size() != expected_pos_bytes) {
        std::ostringstream out;
        out << "pos_emb.bin has unexpected size: " << pos_bytes.size()
            << ", expected " << expected_pos_bytes << ".";
        error = out.str();
        return false;
    }
    if (token_embedding_bytes != nullptr) {
        *token_embedding_bytes = token_bytes.size();
    }
    size_t token_emb_slice_offset = 0;
    std::string mode_used = "standard";
    if (token_emb_has_extra_slice) {
        if (token_embedding_mode == "dual_slice_second_half" ||
            token_embedding_mode == "second_half" ||
            token_embedding_mode == "slice1") {
            token_emb_slice_offset = expected_token_bytes / sizeof(uint16_t);
            mode_used = "dual_slice_second_half";
        } else {
            mode_used = "dual_slice_first_half";
        }
    }
    if (token_embedding_mode_used != nullptr) {
        *token_embedding_mode_used = mode_used;
    }
    const auto* token_emb = reinterpret_cast<const uint16_t*>(token_bytes.data());
    const auto* pos_emb = reinterpret_cast<const float*>(pos_bytes.data());
    input.assign(static_cast<size_t>(kMaxTextLen) * kEmbeddingSize, 0.0f);
    for (int pos = 0; pos < kMaxTextLen; ++pos) {
        const int id = token_ids[static_cast<size_t>(pos)];
        if (id < 0 || id >= kVocabSize) {
            error = "CLIP token id at position " + std::to_string(pos) + " is outside the vocabulary range.";
            return false;
        }
        for (int dim = 0; dim < kEmbeddingSize; ++dim) {
            const size_t out_index = static_cast<size_t>(pos) * kEmbeddingSize + dim;
            const size_t token_index = token_emb_slice_offset + static_cast<size_t>(id) * kEmbeddingSize + dim;
            input[out_index] = fp16_to_float(token_emb[token_index]) + pos_emb[out_index];
        }
    }
    return true;
}

bool run_local_dream_clip_encoder_direct(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::string& backendMode,
        int threads,
        const std::string& token_embedding_mode,
        FloatTensorData& embeddings,
        std::string& error,
        json* debug) {
    std::vector<float> input_values;
    size_t token_embedding_bytes = 0;
    std::string token_embedding_mode_used = "standard";
    if (!build_local_dream_clip_input(
            root, token_ids, input_values, error,
            token_embedding_mode,
            &token_embedding_bytes, &token_embedding_mode_used)) {
        return false;
    }
    if (debug != nullptr) {
        *debug = json::object();
        (*debug)["inputValueStats"] = float_vector_stats_json(input_values);
        (*debug)["tokenEmbeddingBytes"] = token_embedding_bytes;
        (*debug)["tokenEmbeddingRequestedMode"] = token_embedding_mode;
        (*debug)["tokenEmbeddingMode"] = token_embedding_mode_used;
        json tokenSamples = json::array();
        for (size_t i = 0; i < token_ids.size() && tokenSamples.size() < 16; ++i) {
            tokenSamples.push_back(token_ids[i]);
        }
        (*debug)["tokenIdSamples"] = tokenSamples;
    }
    const auto path = root + "/clip_v2.mnn";
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create clip_v2.mnn interpreter.";
        return false;
    }
    configure_direct_interpreter(interpreter.get());
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = "clip_v2.mnn session configuration failed: " + config_error;
        return false;
    }
    // The LocalDream CLIP transformer is numerically fragile with low precision CPU
    // kernels: bad runs produce 1e37-scale hidden states, which collapse CFG.
    backendConfig.precision = MNN::BackendConfig::Precision_High;
    backendConfig.memory = MNN::BackendConfig::Memory_Normal;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create clip_v2.mnn session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = "clip_v2.mnn input_embedding tensor is missing.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 77, 768});
    if (debug != nullptr) {
        (*debug)["inputBeforeResizeSession"] = tensor_metadata_json(input);
    }
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = "clip_v2.mnn input copy failed: " + copyError;
        return false;
    }
    interpreter->resizeSession(session, 1);
    input = interpreter->getSessionInput(session, "input_embedding");
    if (debug != nullptr) {
        (*debug)["inputAfterResizeSession"] = tensor_metadata_json(input);
    }
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = "clip_v2.mnn input recopy failed after resize: " + copyError;
        return false;
    }
    if (debug != nullptr) {
        (*debug)["inputAfterCopy"] = tensor_buffer_debug_json(input);
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = "clip_v2.mnn runSession failed: " + std::to_string(static_cast<int>(code)) +
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
        error = "clip_v2.mnn output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {1, 77, 768}, "clip_v2.mnn output", error)) {
        return false;
    }
    if (debug != nullptr) {
        (*debug)["decodedEmbeddingStats"] = float_vector_stats_json(embeddings.values);
        (*debug)["decodedEmbeddingShape"] = embeddings.shape;
    }
    return true;
}

bool has_local_dream_clip_bundle(const std::string& root) {
    return file_exists(root + "/clip_v2.mnn") &&
            file_exists(root + "/tokenizer.json") &&
            file_exists(root + "/token_emb.bin") &&
            file_exists(root + "/pos_emb.bin");
}

bool build_clip_embedding_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        std::vector<float>& input,
        std::string& error) {
    constexpr int kMaxTextLen = 77;
    constexpr int kVocabSize = 49408;
    if (token_ids.size() != kMaxTextLen) {
        error = "CLIP token id count must be 77.";
        return false;
    }
    if (embeddingSize <= 0) {
        error = "CLIP embedding size must be positive.";
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
        if (id < 0 || id >= kVocabSize) {
            error = "CLIP token id at position " + std::to_string(pos) + " is outside the vocabulary range.";
            return false;
        }
        for (int dim = 0; dim < embeddingSize; ++dim) {
            const size_t out_index = static_cast<size_t>(pos) * static_cast<size_t>(embeddingSize) +
                    static_cast<size_t>(dim);
            const size_t token_index = static_cast<size_t>(id) * static_cast<size_t>(embeddingSize) +
                    static_cast<size_t>(dim);
            input[out_index] = fp16_to_float(token_emb[token_index]) + pos_emb[out_index];
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

bool run_sdxl_clip_encoder_direct(
        const std::string& root,
        const std::string& modelFile,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        const std::vector<int>& tokenIds,
        const std::string& backendMode,
        int threads,
        FloatTensorData& hidden,
        FloatTensorData* pooled,
        std::string& error,
        json* debug) {
    std::vector<float> input_values;
    if (!build_clip_embedding_input(root, tokenIds, tokenFile, positionFile, embeddingSize, input_values, error)) {
        return false;
    }
    const auto path = root + "/" + modelFile;
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create " + modelFile + " interpreter.";
        return false;
    }
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get());
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
        auto* pooledTensor = find_output_by_shape(
                outputs, pooledShape, {"pooler_output", "pooled_output", "text_embeds"});
        if (pooledTensor != nullptr) {
            if (!copy_tensor_to_float_vector(pooledTensor, *pooled, error)) {
                interpreter->releaseSession(session);
                error = modelFile + " pooled output copy failed: " + error;
                return false;
            }
            if (!validate_float_tensor_contract(*pooled, pooledShape, modelFile + " pooled output", error)) {
                interpreter->releaseSession(session);
                return false;
            }
        } else {
            int eosIndex = 0;
            for (size_t i = 0; i < tokenIds.size(); ++i) {
                if (tokenIds[i] == 49407) {
                    eosIndex = static_cast<int>(i);
                    break;
                }
            }
            const size_t base = static_cast<size_t>(eosIndex) * static_cast<size_t>(embeddingSize);
            if (hidden.values.size() < base + static_cast<size_t>(embeddingSize)) {
                interpreter->releaseSession(session);
                error = modelFile + " pooled fallback failed because hidden output is too small.";
                return false;
            }
            pooled->shape = {1, embeddingSize};
            pooled->values.assign(
                    hidden.values.begin() + base,
                    hidden.values.begin() + base + static_cast<size_t>(embeddingSize));
            if (!validate_float_tensor_contract(*pooled, pooledShape, modelFile + " pooled fallback", error)) {
                interpreter->releaseSession(session);
                return false;
            }
            if (debug != nullptr) {
                (*debug)["pooledFallback"] = "eos_hidden_state";
                (*debug)["pooledFallbackTokenIndex"] = eosIndex;
                (*debug)["pooledFallbackStats"] = float_vector_stats_json(pooled->values);
            }
        }
    }
    interpreter->releaseSession(session);
    return true;
}

bool write_float_file(const std::string& path, const std::vector<float>& values, std::string& error) {
    if (values.empty()) {
        error = "Float output is empty.";
        return false;
    }
    if (!validate_finite_float_vector(values, "Float output", error)) {
        return false;
    }
    if (!validate_output_path_for_write(path, error)) {
        return false;
    }
    if (values.size() > static_cast<size_t>(std::numeric_limits<std::streamsize>::max()) / sizeof(float)) {
        error = "Float output is too large to write safely.";
        return false;
    }
    if (!remove_existing_output_file(path, error)) {
        return false;
    }
    std::ofstream output(path.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) {
        error = "Failed to open float output file: " + path;
        return false;
    }
    output.write(
            reinterpret_cast<const char*>(values.data()),
            static_cast<std::streamsize>(values.size() * sizeof(float)));
    if (!output.good()) {
        error = "Failed to write float output file: " + path;
        return false;
    }
    output.flush();
    if (!output.good()) {
        error = "Failed to flush float output file: " + path;
        return false;
    }
    output.close();
    if (!output.good()) {
        error = "Failed to close float output file: " + path;
        return false;
    }
    if (!nonempty_regular_file_exists(path)) {
        error = "Float output file is missing or empty after writing: " + path;
        return false;
    }
    return true;
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

json encode_sdxl_prompt_conditioning_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        int width,
        int height,
        const std::string& backendMode,
        int threads) {
    if (is_blank_text(prompt)) {
        return json({{"ok", false}, {"error", "Prompt is empty."}, {"format", "sdxl_qnn_conditioning"}});
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
    ClipBpeTokenizer tokenizer;
    if (!tokenizer.load(root + "/tokenizer.json", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    const auto ids = tokenizer.encode_pair(prompt, 77);
    if (ids.size() != 154) {
        return json({{"ok", false}, {"error", "CLIP tokenizer returned invalid id count."}});
    }
    std::vector<int> negIds(ids.begin(), ids.begin() + 77);
    std::vector<int> posIds(ids.begin() + 77, ids.end());

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
    if (!run_sdxl_clip_encoder_direct(
            root, "clip.mnn", "token_emb.bin", "pos_emb.bin", 768, negIds,
            canonicalBackend, threads, negClip1, nullptr, error,
            includeDebug ? &debug["clip1_negative"] : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip.mnn", "token_emb.bin", "pos_emb.bin", 768, posIds,
            canonicalBackend, threads, posClip1, nullptr, error,
            includeDebug ? &debug["clip1_positive"] : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip_2.mnn", "token_emb_2.bin", "pos_emb_2.bin", 1280, negIds,
            canonicalBackend, threads, negClip2, &negPooled, error,
            includeDebug ? &debug["clip2_negative"] : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip_2.mnn", "token_emb_2.bin", "pos_emb_2.bin", 1280, posIds,
            canonicalBackend, threads, posClip2, &posPooled, error,
            includeDebug ? &debug["clip2_positive"] : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    constexpr int kTextLen = 77;
    constexpr int kClip1 = 768;
    constexpr int kClip2 = 1280;
    constexpr int kHidden = kClip1 + kClip2;
    if (!validate_float_tensor_contract(negClip1, {1, kTextLen, kClip1}, "SDXL negative clip1", error) ||
        !validate_float_tensor_contract(posClip1, {1, kTextLen, kClip1}, "SDXL positive clip1", error) ||
        !validate_float_tensor_contract(negClip2, {1, kTextLen, kClip2}, "SDXL negative clip2", error) ||
        !validate_float_tensor_contract(posClip2, {1, kTextLen, kClip2}, "SDXL positive clip2", error) ||
        !validate_float_tensor_contract(negPooled, {1, kClip2}, "SDXL negative pooled output", error) ||
        !validate_float_tensor_contract(posPooled, {1, kClip2}, "SDXL positive pooled output", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    std::vector<float> combined;
    combined.reserve(static_cast<size_t>(2 * kTextLen * kHidden + 2 * kClip2 + 6));
    auto appendHidden = [&](const FloatTensorData& clip1, const FloatTensorData& clip2) {
        for (int token = 0; token < kTextLen; ++token) {
            const size_t clip1Base = static_cast<size_t>(token) * kClip1;
            const size_t clip2Base = static_cast<size_t>(token) * kClip2;
            combined.insert(combined.end(), clip1.values.begin() + clip1Base, clip1.values.begin() + clip1Base + kClip1);
            combined.insert(combined.end(), clip2.values.begin() + clip2Base, clip2.values.begin() + clip2Base + kClip2);
        }
    };
    appendHidden(negClip1, negClip2);
    appendHidden(posClip1, posClip2);
    combined.insert(combined.end(), negPooled.values.begin(), negPooled.values.end());
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
    json result = json({
            {"ok", true},
            {"path", outputPath},
            {"bytes", static_cast<long long>(combined.size() * sizeof(float))},
            {"elements", static_cast<long long>(combined.size())},
            {"format", "sdxl_qnn_conditioning"},
            {"hiddenShape", {2, 77, 2048}},
            {"pooledShape", {2, 1280}},
            {"timeIdsShape", {1, 6}},
            {"negativeHiddenStats", float_vector_stats_json(std::vector<float>(combined.begin(), combined.begin() + kTextLen * kHidden))},
            {"positiveHiddenStats", float_vector_stats_json(std::vector<float>(combined.begin() + kTextLen * kHidden, combined.begin() + 2 * kTextLen * kHidden))},
            {"positiveNegativeAbsDiffStats", float_vector_abs_diff_stats_json(
                    std::vector<float>(combined.begin() + kTextLen * kHidden, combined.begin() + 2 * kTextLen * kHidden),
                    std::vector<float>(combined.begin(), combined.begin() + kTextLen * kHidden))},
            {"backendMode", canonicalBackend}
    });
    if (includeDebug) {
        result["debug"] = debug;
    }
    return result;
}

json encode_local_dream_clip_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        const std::string& token_embedding_mode = "auto") {
    std::string error;
    ClipBpeTokenizer tokenizer;
    if (!tokenizer.load(root + "/tokenizer.json", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "local_dream_clip"}});
    }
    auto ids = tokenizer.encode_pair(prompt, 77);
    if (ids.size() != 154) {
        return json({
            {"ok", false},
            {"error", "CLIP tokenizer returned invalid id count."},
            {"tokenCount", ids.size()},
            {"format", "local_dream_clip"}
        });
    }
    FloatTensorData negative;
    FloatTensorData positive;
    json negativeDebug = json::object();
    json positiveDebug = json::object();
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    std::vector<int> neg_ids(ids.begin(), ids.begin() + 77);
    std::vector<int> pos_ids(ids.begin() + 77, ids.end());
    if (!run_local_dream_clip_encoder_direct(
            root,
            neg_ids,
            backendMode,
            threads,
            token_embedding_mode,
            negative,
            error,
            includeDebug ? &negativeDebug : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "local_dream_clip"}});
    }
    if (!run_local_dream_clip_encoder_direct(
            root,
            pos_ids,
            backendMode,
            threads,
            token_embedding_mode,
            positive,
            error,
            includeDebug ? &positiveDebug : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "local_dream_clip"}});
    }
    if (!validate_float_tensor_contract(negative, {1, 77, 768}, "Local Dream negative CLIP output", error) ||
        !validate_float_tensor_contract(positive, {1, 77, 768}, "Local Dream positive CLIP output", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "local_dream_clip"}});
    }
    std::vector<float> combined;
    combined.reserve(negative.values.size() + positive.values.size());
    combined.insert(combined.end(), negative.values.begin(), negative.values.end());
    combined.insert(combined.end(), positive.values.begin(), positive.values.end());
    if (!write_float_file(outputPath, combined, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "local_dream_clip"}});
    }
    json result = {
        {"ok", true},
        {"path", outputPath},
        {"bytes", static_cast<long long>(combined.size() * sizeof(float))},
        {"elements", static_cast<long long>(combined.size())},
        {"shape", {2, 77, 768}},
        {"tokenCount", ids.size()},
        {"negativeShape", negative.shape},
        {"positiveShape", positive.shape},
        {"negativeStats", float_vector_stats_json(negative.values)},
        {"positiveStats", float_vector_stats_json(positive.values)},
        {"positiveNegativeAbsDiffStats", float_vector_abs_diff_stats_json(positive.values, negative.values)},
        {"format", "local_dream_clip"},
        {"backendMode", backendMode == "opencl" || backendMode == "gpu" ? "opencl" : "cpu"}
    };
    if (includeDebug) {
        result["debug"] = {
                {"negative", negativeDebug},
                {"positive", positiveDebug}
        };
    }
    return result;
}

bool run_text_encoder_direct(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error) {
    if (ids.size() != 2U * 77U) {
        error = "text_encoder requires exactly 154 token ids for CFG prompt encoding.";
        return false;
    }
    const auto path = root + "/text_encoder.mnn";
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create text_encoder interpreter.";
        return false;
    }
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get());
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error, true)) {
        error = "text_encoder session configuration failed: " + config_error;
        return false;
    }
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create text_encoder session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_ids");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = "text_encoder input_ids tensor is missing before resize.";
        return false;
    }
    interpreter->resizeTensor(input, {2, 77});
    interpreter->resizeSession(session, 1);
    input = interpreter->getSessionInput(session, "input_ids");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = "text_encoder input_ids tensor is missing after resize.";
        return false;
    }
    std::string copyError;
    if (!copy_vector_to_tensor<int>(input, ids, copyError)) {
        interpreter->releaseSession(session);
        error = "text_encoder input copy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = "text_encoder runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "last_hidden_state");
    const bool ok = copy_tensor_to_float_vector(tensor, embeddings, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = "text_encoder output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {2, 77, 768}, "text_encoder output", error)) {
        return false;
    }
    return true;
}

class DirectMnnUnetSession {
public:
    ~DirectMnnUnetSession() {
        close();
    }

    bool initialize(
            const std::string& root,
            const std::string& backendMode,
            int threads,
            std::string& error) {
        close();
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
        configure_direct_interpreter(interpreter_.get());
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
        interpreter_->resizeTensor(sample_input_, {2, 4, 64, 64});
        interpreter_->resizeTensor(timestep_input_, {1});
        interpreter_->resizeTensor(encoder_input_, {2, 77, 768});
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
        return true;
    }

    bool run(
            const std::vector<float>& sample,
            int timestep,
            const FloatTensorData& embeddings,
            FloatTensorData& noise,
            std::string& error) {
        constexpr int kCfgBatch = 2;
        constexpr int kLatentElements = 4 * 64 * 64;
        if (session_ == nullptr || interpreter_ == nullptr) {
            error = "UNet session is not initialized.";
            return false;
        }
        if (timestep < 0 || timestep >= 1000) {
            error = "UNet timestep must be in [0, 999].";
            return false;
        }
        if (sample.size() != static_cast<size_t>(kCfgBatch * kLatentElements)) {
            error = "UNet sample must have shape [2,4,64,64].";
            return false;
        }
        if (!validate_finite_float_vector(sample, "UNet sample", error) ||
            !validate_float_tensor_contract(embeddings, {2, 77, 768}, "UNet encoder_hidden_states", error)) {
            return false;
        }
        std::vector<int> timestepValues = {timestep};
        if (!copy_vector_to_tensor<float>(sample_input_, sample, error) ||
            !copy_vector_to_tensor<int>(timestep_input_, timestepValues, error) ||
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
        if (noise.shape == std::vector<int>({kCfgBatch, 64, 64, 4})) {
            noise.values = nhwc_to_nchw(noise.values, kCfgBatch, 64, 64, 4);
            noise.shape = {kCfgBatch, 4, 64, 64};
        }
        return validate_float_tensor_contract(noise, {kCfgBatch, 4, 64, 64}, "UNet output", error);
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
    }

    std::unique_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* sample_input_ = nullptr;
    MNN::Tensor* timestep_input_ = nullptr;
    MNN::Tensor* encoder_input_ = nullptr;
    std::string weight_path_;
};

bool run_unet_direct(
        const std::string& root,
        const std::vector<float>& sample,
        int timestep,
        const FloatTensorData& embeddings,
        const std::string& backendMode,
        int threads,
        FloatTensorData& noise,
        std::string& error) {
    constexpr int kCfgBatch = 2;
    constexpr int kLatentElements = 4 * 64 * 64;
    if (timestep < 0 || timestep >= 1000) {
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
    configure_direct_interpreter(interpreter.get());
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
    std::vector<int> timestepValues = {timestep};
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct copy_inputs_start");
    if (!copy_vector_to_tensor<float>(sampleInput, sample, copyError)) {
        interpreter->releaseSession(session);
        error = "UNet sample input copy failed: " + copyError;
        return false;
    }
    if (!copy_vector_to_tensor<int>(timestepInput, timestepValues, copyError)) {
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
    configure_direct_interpreter(interpreter.get());
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
    input = interpreter->getSessionInput(session, "latent_sample");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE latent_sample tensor is missing after resize.";
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
    if (index < 0 || static_cast<size_t>(index) >= timesteps.size()) {
        error = "PNDM scheduler step index is outside the timestep schedule.";
        return false;
    }
    if (sample.empty() || modelOutput.size() != sample.size()) {
        error = "PNDM scheduler sample and model output sizes must match and be non-empty.";
        return false;
    }
    if (!validate_finite_float_vector(sample, "PNDM sample", error) ||
        !validate_finite_float_vector(modelOutput, "PNDM model output", error)) {
        return false;
    }

    int timestep = timesteps[static_cast<size_t>(index)];
    int prevTimestep = 0;
    if (static_cast<size_t>(index + 1) < timesteps.size()) {
        prevTimestep = timesteps[static_cast<size_t>(index + 1)];
    }
    std::vector<float> adjusted = modelOutput;
    const std::vector<float>* sampleForStep = &sample;

    if (index == 0) {
        firstSample = sample;
    }
    if (index != 1) {
        if (ets.size() >= 4) {
            ets.erase(ets.begin());
        }
        ets.push_back(modelOutput);
    } else {
        if (timesteps.size() < 2 || ets.empty()) {
            error = "PNDM scheduler is missing the first model output for its second step.";
            return false;
        }
        if (firstSample.size() != sample.size()) {
            error = "PNDM scheduler is missing the first latent sample for its second step.";
            return false;
        }
        timestep = timesteps[0];
        prevTimestep = timesteps[1];
        sampleForStep = &firstSample;
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = (adjusted[i] + ets.back()[i]) * 0.5f;
        }
    }

    if (ets.empty()) {
        error = "PNDM scheduler has no model output history.";
        return false;
    }
    for (const auto& previousOutput : ets) {
        if (previousOutput.size() != sample.size() ||
            !validate_finite_float_vector(previousOutput, "PNDM model output history", error)) {
            return false;
        }
    }
    const int etsIndex = static_cast<int>(ets.size()) - 1;
    if (index != 1 && etsIndex == 1) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = (3.0f * ets[etsIndex][i] - ets[etsIndex - 1][i]) * 0.5f;
        }
    } else if (index != 1 && etsIndex == 2) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = (23.0f * ets[etsIndex][i] - 16.0f * ets[etsIndex - 1][i] +
                           5.0f * ets[etsIndex - 2][i]) / 12.0f;
        }
    } else if (index != 1 && etsIndex >= 3) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = (55.0f * ets[etsIndex][i] - 59.0f * ets[etsIndex - 1][i] +
                           37.0f * ets[etsIndex - 2][i] - 9.0f * ets[etsIndex - 3][i]) / 24.0f;
        }
    }

    if (timestep < 0 || prevTimestep < 0 ||
        static_cast<size_t>(timestep) >= alphas.size() ||
        static_cast<size_t>(prevTimestep) >= alphas.size()) {
        error = "PNDM scheduler timestep is outside the alpha schedule.";
        return false;
    }
    const float alphaProdT = alphas[static_cast<size_t>(timestep)];
    const float alphaProdPrev = alphas[static_cast<size_t>(prevTimestep)];
    if (!std::isfinite(alphaProdT) || !std::isfinite(alphaProdPrev) ||
        alphaProdT <= 0.0f || alphaProdT > 1.0f ||
        alphaProdPrev <= 0.0f || alphaProdPrev > 1.0f) {
        error = "PNDM scheduler received invalid alpha values.";
        return false;
    }
    const float betaProdT = 1.0f - alphaProdT;
    const float betaProdPrev = 1.0f - alphaProdPrev;
    if (betaProdT < 0.0f || betaProdPrev < 0.0f) {
        error = "PNDM scheduler received invalid beta values.";
        return false;
    }
    const float sampleCoeff = std::sqrt(alphaProdPrev / alphaProdT);
    const float denom = alphaProdT * std::sqrt(betaProdPrev) +
            std::sqrt(alphaProdT * betaProdT * alphaProdPrev);
    if (!std::isfinite(sampleCoeff) || !std::isfinite(denom) ||
        denom <= std::numeric_limits<float>::epsilon()) {
        error = "PNDM scheduler produced invalid integration coefficients.";
        return false;
    }
    const float modelCoeff = (alphaProdPrev - alphaProdT) / denom;
    if (!std::isfinite(modelCoeff)) {
        error = "PNDM scheduler produced a non-finite model coefficient.";
        return false;
    }
    previous.resize(sample.size());
    for (size_t i = 0; i < sample.size(); ++i) {
        previous[i] = sampleCoeff * (*sampleForStep)[i] - modelCoeff * adjusted[i];
    }
    return validate_finite_float_vector(previous, "PNDM previous sample", error);
}

bool write_vae_image_to_file(const FloatTensorData& image, const std::string& outputPath, std::string& error) {
    if (!validate_float_tensor_contract(image, {1, 3, 512, 512}, "VAE image", error)) {
        return false;
    }
    if (!validate_output_path_for_write(outputPath, error)) {
        return false;
    }
    if (!remove_existing_output_file(outputPath, error)) {
        return false;
    }
    constexpr int height = 512;
    constexpr int width = 512;
    constexpr size_t plane = static_cast<size_t>(height) * static_cast<size_t>(width);
    std::vector<uint8_t> rgb(plane * 3);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t idx = static_cast<size_t>(y) * width + x;
            auto toByte = [&](float value) -> uint8_t {
                const float scaled = std::max(0.0f, std::min(1.0f, value * 0.5f + 0.5f)) * 255.0f;
                return static_cast<uint8_t>(std::round(scaled));
            };
            const uint8_t b = toByte(image.values[idx]);
            const uint8_t g = toByte(image.values[plane + idx]);
            const uint8_t r = toByte(image.values[plane * 2 + idx]);
            rgb[idx * 3] = r;
            rgb[idx * 3 + 1] = g;
            rgb[idx * 3 + 2] = b;
        }
    }
    auto var = MNN::Express::_Const(rgb.data(), {height, width, 3}, MNN::Express::NHWC, halide_type_of<uint8_t>());
    if (var.get() == nullptr) {
        error = "Failed to create image tensor.";
        return false;
    }
    var.fix(MNN::Express::VARP::CONSTANT);
    if (!CV::imwrite(outputPath, var)) {
        std::string cleanupError;
        remove_existing_output_file(outputPath, cleanupError);
        error = "Failed to write output image.";
        return false;
    }
    if (!nonempty_regular_file_exists(outputPath)) {
        std::string cleanupError;
        remove_existing_output_file(outputPath, cleanupError);
        error = "Output image is missing or empty after writing.";
        return false;
    }
    return true;
}

json encode_sd15_prompt_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        const std::string& token_embedding_mode = "auto") {
    if (is_blank_text(prompt)) {
        return json({{"ok", false}, {"error", "Prompt is empty."}});
    }
    if (has_local_dream_clip_bundle(root) && !file_exists(root + "/text_encoder.mnn")) {
        return encode_local_dream_clip_embeddings_to_file(
                root,
                prompt,
                outputPath,
                backendMode,
                threads,
                token_embedding_mode);
    }
    std::string error;
    MNN::DIFFUSION::MtokTokenizer tokenizer(MNN::DIFFUSION::MtokTokenizer::Style::kPair, 49406, 49407);
    if (!tokenizer.load(root)) {
        if (has_local_dream_clip_bundle(root)) {
            return encode_local_dream_clip_embeddings_to_file(
                    root,
                    prompt,
                    outputPath,
                    backendMode,
                    threads,
                    token_embedding_mode);
        }
        return json({{"ok", false}, {"error", "Failed to load MNN-Diffusion tokenizer."}});
    }
    auto ids = tokenizer.encode(prompt, 77);
    if (ids.size() != 154) {
        return json({
            {"ok", false},
            {"error", "MNN-Diffusion tokenizer returned invalid id count."},
            {"tokenCount", ids.size()}
        });
    }
    FloatTensorData embeddings;
    if (!run_text_encoder_direct(root, ids, backendMode, threads, embeddings, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (!validate_float_tensor_contract(embeddings, {2, 77, 768}, "Text encoder output", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (!write_float_file(outputPath, embeddings.values, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    return json({
        {"ok", true},
        {"path", outputPath},
        {"bytes", static_cast<long long>(embeddings.values.size() * sizeof(float))},
        {"elements", static_cast<long long>(embeddings.values.size())},
        {"shape", embeddings.shape},
        {"tokenCount", ids.size()},
        {"backendMode", backendMode == "opencl" || backendMode == "gpu" ? "opencl" : "cpu"}
    });
}

json run_mnn_sd15_interpreter_direct(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        int steps,
        int seed,
        const std::string& backendMode,
        int threads,
        std::function<void(int)> progressCallback) {
    std::string error;
    if (steps < 1 || steps > 50) {
        return json({{"ok", false}, {"error", "SD15 direct runner supports 1 to 50 inference steps."}});
    }
    if (progressCallback) {
        progressCallback(0);
    }
    MNN::DIFFUSION::MtokTokenizer tokenizer(MNN::DIFFUSION::MtokTokenizer::Style::kPair, 49406, 49407);
    if (!tokenizer.load(root)) {
        return json({{"ok", false}, {"error", "Failed to load MNN-Diffusion tokenizer."}});
    }
    auto ids = tokenizer.encode(prompt, 77);
    if (ids.size() != 154) {
        return json({{"ok", false}, {"error", "MNN-Diffusion tokenizer returned invalid id count."}});
    }
    FloatTensorData embeddings;
    if (progressCallback) {
        progressCallback(1);
    }
    if (!run_text_encoder_direct(root, ids, backendMode, threads, embeddings, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (!validate_float_tensor_contract(embeddings, {2, 77, 768}, "Text encoder output", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    json debug = json::object();
    if (includeDebug) {
        const size_t rowSize = 77U * 768U;
        std::vector<float> negative(embeddings.values.begin(), embeddings.values.begin() + rowSize);
        std::vector<float> positive(embeddings.values.begin() + rowSize, embeddings.values.end());
        debug["tokenIds"] = ids;
        debug["negativeEmbeddingStats"] = float_vector_stats_json(negative);
        debug["positiveEmbeddingStats"] = float_vector_stats_json(positive);
        debug["positiveNegativeAbsDiffStats"] = float_vector_abs_diff_stats_json(positive, negative);
    }
    if (progressCallback) progressCallback(5);

    DirectMnnUnetSession unetSession;
    if (!unetSession.initialize(root, backendMode, threads, error)) {
        return json({{"ok", false}, {"error", error}});
    }

    MNN::DIFFUSION::PNDMScheduler scheduler;
    const auto alphas = scheduler.get_alphas();
    std::vector<int> timesteps(static_cast<size_t>(steps));
    const int stepSize = 1000 / steps;
    for (int i = steps - 1; i >= 0; --i) {
        timesteps[i] = 1 + (steps - 1 - i) * stepSize;
    }
    std::vector<float> latent(4 * 64 * 64);
    std::mt19937 rng(seed < 0 ? std::random_device{}() : static_cast<uint32_t>(seed));
    std::normal_distribution<float> normal(0.0f, 1.0f);
    for (auto& value : latent) {
        value = normal(rng);
    }
    std::vector<std::vector<float>> ets;
    std::vector<float> firstSample;
    for (int i = 0; i < steps; ++i) {
        if (progressCallback) {
            progressCallback(5 + i * 80 / steps);
        }
        std::vector<float> sampleBatch;
        sampleBatch.reserve(latent.size() * 2);
        sampleBatch.insert(sampleBatch.end(), latent.begin(), latent.end());
        sampleBatch.insert(sampleBatch.end(), latent.begin(), latent.end());

        FloatTensorData batchedNoise;
        if (!unetSession.run(sampleBatch, timesteps[i], embeddings, batchedNoise, error)) {
            return json({{"ok", false}, {"error", error}});
        }
        if (!validate_float_tensor_contract(
                batchedNoise, {2, 4, 64, 64}, "UNet output", error)) {
            return json({{"ok", false}, {"error", error}});
        }
        std::vector<float> guided(latent.size());
        const float cfgScale = 7.5f;
        for (size_t j = 0; j < latent.size(); ++j) {
            const float uncond = batchedNoise.values[j];
            const float cond = batchedNoise.values[latent.size() + j];
            guided[j] = cfgScale * (cond - uncond) + uncond;
        }
        if (!validate_finite_float_vector(guided, "CFG guided noise", error)) {
            return json({{"ok", false}, {"error", error}});
        }
        std::vector<float> nextLatent;
        if (!pndm_step(
                latent, ets, firstSample, guided, i, timesteps, alphas, nextLatent, error)) {
            return json({{"ok", false}, {"error", error}});
        }
        latent = std::move(nextLatent);
        if (progressCallback) {
            progressCallback(5 + (i + 1) * 80 / steps);
        }
    }
    std::vector<float> vaeInput(latent.size());
    for (size_t i = 0; i < latent.size(); ++i) {
        vaeInput[i] = latent[i] * (1.0f / 0.18215f);
    }
    if (!validate_finite_float_vector(vaeInput, "VAE decoder input", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    FloatTensorData image;
    if (progressCallback) progressCallback(90);
    if (!run_vae_decoder_direct(root, vaeInput, backendMode, threads, image, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (progressCallback) progressCallback(95);
    if (!write_vae_image_to_file(image, outputPath, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (progressCallback) progressCallback(100);
    json result = {{"ok", true}, {"path", outputPath}, {"mimeType", "image/png"}};
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
struct ParsedMnnChatMessages {
    std::vector<MNN::Transformer::ChatMessage> messages;
    std::vector<MnnChatImageInput> images;
    mca::mnn::MnnRawMediaTags rawMediaTags;

    bool hasMediaInputs() const {
        return !images.empty() || rawMediaTags.hasAny();
    }
};

void collect_mnn_raw_media_tags_from_content(
        const json& content,
        mca::mnn::MnnRawMediaTags& tags) {
    if (content.is_string()) {
        mca::mnn::collectMnnRawMediaTags(content.get<std::string>(), tags);
        return;
    }
    if (!content.is_array()) return;
    for (const auto& part : content) {
        if (!part.is_object() || part.value("type", "") != "text") continue;
        const auto textIt = part.find("text");
        if (textIt != part.end() && textIt->is_string()) {
            mca::mnn::collectMnnRawMediaTags(textIt->get<std::string>(), tags);
        }
    }
}

ParsedMnnChatMessages parse_chat_messages(const std::string& messages_json) {
    const auto root = json::parse(messages_json);
    if (!root.is_array()) {
        throw std::runtime_error("messagesJson must be an array.");
    }
    ParsedMnnChatMessages parsed;
    for (const auto& item : root) {
        if (!item.is_object()) continue;
        const auto role = item.value("role", "user");
        const auto content_it = item.find("content");
        if (content_it != item.end()) {
            collect_mnn_raw_media_tags_from_content(*content_it, parsed.rawMediaTags);
        }
        const auto content = content_it == item.end()
                ? std::string()
                : text_from_content(*content_it, &parsed.images);
        if (!content.empty() || role == "assistant" || role == "system") {
            parsed.messages.emplace_back(role, content);
        }
    }
    if (parsed.messages.empty()) {
        throw std::runtime_error("No valid chat messages for MNN generation.");
    }
    return parsed;
}

std::string local_mnn_image_path(const std::string& rawPath) {
    if (rawPath.rfind("file://", 0) == 0) return rawPath.substr(7);
    if (rawPath.rfind("file:", 0) == 0) return rawPath.substr(5);
    return rawPath;
}

bool begin_mnn_response(const ParsedMnnChatMessages& parsed) {
    if (!parsed.hasMediaInputs()) {
        g_multimodal_system_prompt_suppressed = false;
        g_multimodal_history_suppressed = false;
        capture_mnn_debug_prompt(g_llm->apply_chat_template(parsed.messages));
        g_llm->response(parsed.messages, g_output_stream.get(), "<eop>", 0);
        return false;
    }
    if (parsed.rawMediaTags.malformed) {
        throw std::runtime_error(
                "MNN local multimodal input contains an unterminated raw media tag.");
    }
    if (!parsed.rawMediaTags.audio.empty() || !parsed.rawMediaTags.video.empty()) {
        throw std::runtime_error(
                "MNN local chat currently supports image attachments only; raw audio/video tags are unavailable.");
    }
    if (!g_vision_ready || g_visual_model_path.empty()) {
        throw std::runtime_error(
                "This MNN package does not include a readable visual.mnn component. "
                "Load a complete local multimodal package before sending an image.");
    }
#if !defined(MNN_IMGCODECS)
    throw std::runtime_error(
            "This MCA build does not include MNN image codecs, so local image input is unavailable.");
#else
    // Let Omni decode each attachment inside its own ExecutorScope. Creating image VARPs here
    // binds them to the default runtime, which can make the later vision/text concat cross runtimes.
    // Keep media decoding inside Omni's official response path after validating
    // and normalizing paths. Reimplementing template -> tokenize -> embedding
    // here diverges from MNNChat and can lose the visual effect before decode.
    auto promptMessages = parsed.messages;
    // MNN 3.5's Qwen3.5 visual ChatMessages path is system-turn sensitive.
    // Apply the measured user-only compatibility path narrowly; Gemma and any
    // future model types retain the caller's explicit system message.
    g_multimodal_system_prompt_suppressed = false;
    if (mca::mnn::shouldSuppressMnnMultimodalSystemPrompt(g_mnn_model_type)) {
        const auto messageCountBeforeSystemFilter = promptMessages.size();
        promptMessages.erase(
                std::remove_if(
                        promptMessages.begin(),
                        promptMessages.end(),
                        [](const MNN::Transformer::ChatMessage& message) {
                            return message.first == "system";
                        }),
                promptMessages.end());
        g_multimodal_system_prompt_suppressed =
                promptMessages.size() != messageCountBeforeSystemFilter;
    }
    if (promptMessages.empty()) {
        throw std::runtime_error(
                "MNN local multimodal input has no user message after applying the system-turn compatibility policy.");
    }
    const auto checkedImagePath = [](const std::string& rawPath) {
        const auto imagePath = local_mnn_image_path(rawPath);
        if (imagePath.rfind("content://", 0) == 0 ||
                imagePath.rfind("http://", 0) == 0 ||
                imagePath.rfind("https://", 0) == 0 ||
                imagePath.rfind("data:", 0) == 0) {
            throw std::runtime_error(
                    "MNN local vision received an unresolved image URI. "
                    "Prepare the attachment as a local readable image file first.");
        }
        if (!file_exists(imagePath)) {
            throw std::runtime_error(
                    "MNN local vision image file is missing or unreadable: " + imagePath);
        }
        return imagePath;
    };
    const auto replaceTagInMessages = [&promptMessages](
            const std::string& originalTag,
            const std::string& replacementTag) {
        for (auto& message : promptMessages) {
            const auto offset = message.second.find(originalTag);
            if (offset == std::string::npos) continue;
            message.second.replace(offset, originalTag.size(), replacementTag);
            return true;
        }
        return false;
    };

    for (const auto& input : parsed.images) {
        const auto imagePath = checkedImagePath(input.path);
        const auto placeholder = "<img>" + input.key + "</img>";
        if (!replaceTagInMessages(
                    placeholder,
                    "<img>" + imagePath + "</img>")) {
            throw std::runtime_error(
                    "MNN local vision could not match an image placeholder in the message content.");
        }
    }
    for (const auto& rawImagePath : parsed.rawMediaTags.images) {
        const auto imagePath = checkedImagePath(rawImagePath);
        if (imagePath == rawImagePath) continue;
        if (!replaceTagInMessages(
                    "<img>" + rawImagePath + "</img>",
                    "<img>" + imagePath + "</img>")) {
            throw std::runtime_error(
                    "MNN local vision could not preserve a normalized raw image tag in the message content.");
        }
    }
    const auto latestUserMessage = std::find_if(
            promptMessages.rbegin(),
            promptMessages.rend(),
            [](const MNN::Transformer::ChatMessage& message) {
                return message.first == "user";
            });
    if (latestUserMessage == promptMessages.rend()) {
        throw std::runtime_error(
                "MNN local multimodal input has no user message after normalization.");
    }
    if (latestUserMessage->second.find("<img>") == std::string::npos) {
        throw std::runtime_error(
                "MNN local multimodal history contains an earlier image, but the latest user turn has no image. "
                "Attach the image again for a follow-up question; silent visual-history reuse is disabled.");
    }
    // The official llm_demo success path uses response(string), which renders
    // one image-first user turn and performs prefill+decode in a single native
    // call. Both response(ChatMessages) and Android's repeated generate(1)
    // stepping produced different, image-insensitive answers with the same
    // model, image and sampler. Use the proven path for visual requests. A
    // future asynchronous native worker can restore token-by-token delivery
    // without splitting MNN's generation lifecycle again.
    g_multimodal_history_suppressed = promptMessages.size() != 1;
    const auto& imageUserContent = latestUserMessage->second;
    capture_mnn_debug_prompt(g_llm->apply_chat_template(imageUserContent));
    g_llm->response(
            imageUserContent,
            g_output_stream.get(),
            "<eop>",
            g_max_new_tokens);
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
#endif
}

json build_mnn_config(const std::string& config_path, const std::string& params_json, bool for_load) {
    json params = json::object();
    if (!params_json.empty()) {
        params = json::parse(params_json, nullptr, false);
        if (params.is_discarded() || !params.is_object()) params = json::object();
    }
    json config = json::object();
    const int n_ctx = std::max(512, opt_int(params, "n_ctx", 8192));
    const int n_threads = std::max(1, opt_int(params, "n_threads", 4));
    config["backend_type"] = "cpu";
    config["thread_num"] = n_threads;
    config["precision"] = "low";
    config["memory"] = "low";
    config["power"] = "normal";
    config["use_mmap"] = opt_bool(params, "mmap", true);
    config["kvcache_mmap"] = opt_bool(params, "kvcache_mmap", true);
    config["reuse_kv"] = false;
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
        config["temperature"] = params.value("temperature", 0.6);
        // MNN creates its sampler from the runtime config. Always send a
        // numeric seed: -1 restores entropy for callers that did not request
        // determinism, while a non-negative value makes the request repeatable.
        config["seed"] = std::max(-1, opt_int(params, "seed", -1));
        // MNN's bundled configs use camelCase (`topK` / `topP`) while MCA's
        // public generation parameters use OpenAI-style snake_case. Preserve
        // both spellings so set_config reaches every MNN sampler variant.
        const int top_k = opt_int(params, "top_k", opt_int(params, "topK", 20));
        const double top_p = params.contains("top_p")
                ? params.value("top_p", 0.95)
                : params.value("topP", 0.95);
        config["top_k"] = top_k;
        config["topK"] = top_k;
        config["top_p"] = top_p;
        config["topP"] = top_p;
    }
    const auto advanced = advanced_json_from_params(params);
    const bool advancedHasNCtx = advanced.contains("n_ctx");
    const bool advancedHasMaxAllTokens = advanced.contains("max_all_tokens");
    const bool advancedHasNThreads = advanced.contains("n_threads");
    const bool advancedHasThreadNum = advanced.contains("thread_num");
    const bool advancedHasNPredict = advanced.contains("n_predict");
    const bool advancedHasMaxNewTokens = advanced.contains("max_new_tokens");
    merge_json_object(config, advanced);
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
    config["reuse_kv"] = false;
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
        if (!for_load && mca::mnn::shouldSuppressMnnMultimodalSystemPrompt(g_mnn_model_type)) {
            // response(string) consults LlmConfig::system_prompt even after the
            // explicit ChatMessages system turn was removed. Keep the measured
            // Qwen3.5 visual path genuinely user-only for the active request.
            config["system_prompt"] = "";
        }
    }
    config["max_all_tokens"] = std::max(512, opt_int(config, "max_all_tokens", n_ctx));
    config["n_ctx"] = std::max(512, opt_int(config, "n_ctx", config.value("max_all_tokens", n_ctx)));
    config["thread_num"] = std::max(1, opt_int(config, "thread_num", n_threads));
    if (!for_load) {
        config["max_new_tokens"] = std::max(1, opt_int(config, "max_new_tokens", 512));
        config["seed"] = std::max(-1, opt_int(config, "seed", opt_int(params, "seed", -1)));
        const double temperature = config.value("temperature", 0.6);
        const int top_k = opt_int(config, "top_k", opt_int(config, "topK", 20));
        const double top_p = config.contains("top_p")
                ? config.value("top_p", 0.95)
                : config.value("topP", 0.95);
        config["top_k"] = top_k;
        config["topK"] = top_k;
        config["top_p"] = top_p;
        config["topP"] = top_p;
        // MNN's sampler may still draw from top-k when temperature is zero. Product
        // callers expect temperature=0 to mean greedy decoding; leaving top_k=20 made
        // identical cold runs intermittently select EOP as the first token. Enforce
        // deterministic greedy semantics after advanced overrides are merged.
        if (!std::isfinite(temperature) || temperature <= 0.0) {
            config["temperature"] = 0.0;
            config["top_k"] = 1;
            config["topK"] = 1;
            config["top_p"] = 1.0;
            config["topP"] = 1.0;
            // MNN's temperature sampler divides by temperature, so a zero
            // temperature must select its explicit greedy pipeline instead.
            config["sampler_type"] = "greedy";
        } else {
            // A prior greedy request must not pin later creative requests to
            // greedy mode in the persistent native LLM instance.
            config["sampler_type"] = "mixed";
        }
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
    g_multimodal_system_prompt_suppressed = false;
    g_multimodal_history_suppressed = false;
    g_sync_stepping = true;
    g_mnn_request_lifecycle.onModelUnloaded();
}

std::vector<std::string> mnn_load_signature_differences(
        const json& loaded,
        const json& requested) {
    static const std::array<const char*, 9> kLoadBoundFields = {
            "backend_type",
            "precision",
            "memory",
            "power",
            "use_mmap",
            "kvcache_mmap",
            "max_all_tokens",
            "n_ctx",
            "visual_model"};
    std::vector<std::string> changed;
    for (const char* field : kLoadBoundFields) {
        const auto loaded_it = loaded.find(field);
        const auto requested_it = requested.find(field);
        if (loaded_it == loaded.end() && requested_it == requested.end()) continue;
        if (loaded_it == loaded.end() || requested_it == requested.end() || *loaded_it != *requested_it) {
            changed.emplace_back(field);
        }
    }
    return changed;
}

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
    }
}
#endif

std::string stats_json_locked() {
    int prompt_tokens = 0;
    int completion_tokens = 0;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    int64_t ttft_ms = 0;
    double decode_tps = 0.0;
#if MCA_WITH_MNN_LLM
    if (g_llm != nullptr && g_llm->getContext() != nullptr) {
        const auto* context = g_llm->getContext();
        prompt_tokens = context->prompt_len;
        completion_tokens = context->gen_seq_len;
        prefill_ms = context->prefill_us / 1000;
        decode_ms = context->decode_us / 1000;
        if (decode_ms > 0 && completion_tokens > 0) {
            decode_tps = completion_tokens * 1000.0 / decode_ms;
        }
        if (g_generation_started_at_ms > 0 && g_first_chunk_at_ms > 0) {
            ttft_ms = g_first_chunk_at_ms - g_generation_started_at_ms;
        }
    }
#endif
    std::ostringstream out;
    out << "{"
        << "\"backend\":\"mnn_cpu\","
        << "\"loaded\":" << (g_loaded ? "true" : "false") << ","
        << "\"runnerReady\":" << (g_runner_ready ? "true" : "false") << ","
        << "\"loadGeneration\":" << g_load_generation << ","
        << "\"loadedAtMs\":" << g_loaded_at_ms << ","
        << "\"requestCountSinceLoad\":" << g_mnn_request_lifecycle.requestCountSinceLoad << ","
        << "\"lastRequestReset\":" << (g_mnn_request_lifecycle.lastRequestReset ? "true" : "false") << ","
        << "\"mnnModelType\":\"" << escape_json(g_mnn_model_type) << "\","
        << "\"multimodalSystemPromptSuppressed\":"
        << (g_multimodal_system_prompt_suppressed ? "true" : "false") << ","
        << "\"multimodalHistorySuppressed\":"
        << (g_multimodal_history_suppressed ? "true" : "false") << ","
        << "\"modelPath\":\"" << escape_json(g_model_path) << "\","
        << "\"originalModelPath\":\"" << escape_json(g_original_model_path) << "\","
        << "\"nativeLibDir\":\"" << escape_json(g_native_lib_dir) << "\","
        << "\"visionReady\":" << (g_vision_ready ? "true" : "false") << ","
        << "\"visualModelPath\":\"" << escape_json(g_visual_model_path) << "\","
        << "\"promptTokens\":" << prompt_tokens << ","
        << "\"completionTokens\":" << completion_tokens << ","
        << "\"generationSequence\":" << g_generation_sequence << ","
        << "\"ttftMs\":" << ttft_ms << ","
        << "\"prefillMs\":" << prefill_ms << ","
        << "\"decodeMs\":" << decode_ms << ","
        << "\"decodeTps\":" << decode_tps << ","
        << "\"generationActive\":" << (g_generation_active ? "true" : "false") << ","
        << "\"generatedSteps\":" << g_generated_steps << ","
        << "\"streamedBytes\":" << g_streamed_bytes << ","
        << "\"generationStopReason\":\"" << escape_json(g_generation_stop_reason) << "\","
        << "\"mnnDebugTraceEnabled\":" << (g_mnn_debug_trace_enabled ? "true" : "false") << ","
        << "\"mnnDebugTraceTruncated\":" << (g_mnn_debug_trace_truncated ? "true" : "false") << ","
        << "\"mnnDebugRawOutput\":\""
        << escape_json(sanitize_utf8_for_diagnostics(g_mnn_debug_raw_output)) << "\","
        << "\"mnnDebugPromptTruncated\":" << (g_mnn_debug_prompt_truncated ? "true" : "false") << ","
        << "\"mnnDebugPrompt\":\""
        << escape_json(sanitize_utf8_for_diagnostics(g_mnn_debug_prompt)) << "\","
        << "\"syncStepping\":" << (g_sync_stepping ? "true" : "false") << ","
        << "\"nThreads\":" << g_n_threads << ","
        << "\"nCtx\":" << g_max_all_tokens << ","
        << "\"maxAllTokens\":" << g_max_all_tokens << ","
        << "\"maxNewTokens\":" << g_max_new_tokens << ","
        << "\"loadedConfigJson\":\"" << escape_json(g_loaded_config_json) << "\","
        << "\"lastConfigJson\":\"" << escape_json(g_last_config_json) << "\","
        << "\"backendDevices\":[\"cpu\"],"
        << "\"lastError\":\"" << escape_json(g_last_error) << "\""
        << "}";
    return out.str();
}

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
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "MNN CPU runner ready.");
#else
    set_error("MNN CPU runner adapter loaded, but official MNN-LLM runtime is not linked in this build.");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_isRunnerReady(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    return g_runner_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_loadModel(
        JNIEnv* env,
        jobject,
        jstring configPath,
        jstring paramsJson) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_original_model_path = normalize_mnn_model_path(jstring_to_std(env, configPath));
    g_model_path = resolve_mnn_config_path(g_original_model_path);
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
        if (const auto incompatibility = incompatible_legacy_mnn_visual_graph_message(g_original_model_path);
                !incompatibility.empty()) {
            set_error(incompatibility);
            return kMnnLoadFailed;
        }
        g_model_path = prepare_mnn_runtime_config(g_original_model_path);
        if (g_model_path.empty() || !file_exists(g_model_path)) {
            set_error("MNN config path does not exist: " + g_model_path);
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
        g_max_all_tokens = std::max(512, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_loaded_config_json = config.dump();
        g_last_config_json = g_loaded_config_json;
        g_llm->set_config(g_last_config_json);
        const bool ok = g_llm->load();
        if (!ok) {
            set_error("MNN load() failed. Check package components, memory headroom, and CPU backend compatibility.");
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
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_unloadModel(JNIEnv*, jobject) {
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
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    // A new request owns a fresh decoder boundary even if validation or setup
    // fails before MNN starts producing tokens.
    g_pending_chunk.clear();
    g_pending_utf8_tail.clear();
#if MCA_WITH_MNN_LLM
    if (!g_loaded || g_llm == nullptr) {
        set_error("MNN beginCompletion requested before a model is loaded.");
        return kMnnInvalidState;
    }
    try {
        const auto params = jstring_to_std(env, paramsJson);
        const auto parsed_params = json::parse(params, nullptr, false);
        configure_mnn_debug_trace_locked(parsed_params);
        const auto parsed = parse_chat_messages(jstring_to_std(env, messagesJson));
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
        mca::mnn::applyProductMnnMultimodalRequestPolicy(
                config,
                parsed.hasMediaInputs(),
                opt_int(config, "chunk", 0));
        configure_mnn_stop_markers_locked(parsed_params);
        g_max_new_tokens = std::max(1, config.value("max_new_tokens", 512));
        g_max_all_tokens = std::max(512, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_last_config_json = config.dump();
        g_generated_steps = 0;
        g_stop_requested = false;
        g_generation_active = true;
        g_eop_seen = false;
        g_streamed_bytes = 0;
        g_generation_stop_reason = "running";
        g_generation_started_at_ms = now_ms();
        g_first_chunk_at_ms = 0;
        g_sync_stepping = true;
        g_stream_buffer = std::make_unique<MnnStreamBuffer>();
        g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
        // response()/generate_init() owns context cleanup. The product bridge
        // must not call Llm::reset() here: a controlled MNN 3.5 differential
        // reproduced the Qwen3.5 vision failure by adding only that reset.
        const bool resetBeforeRequest = g_mnn_request_lifecycle.beginRequest();
        if (resetBeforeRequest) {
            g_llm->reset();
        }
        g_llm->set_config(g_last_config_json);
        const bool completedSynchronously = begin_mnn_response(parsed);
        g_sync_stepping = !completedSynchronously;
        if (completedSynchronously) {
            // Leave the buffered native output untouched here. The first
            // generateNextChunk() call owns protocol/EOS filtering and drains
            // it exactly once; pre-filtering would feed the visible text back
            // through an already-stopped filter and drop the whole answer.
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
        } else {
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
        g_generation_stop_reason = detail.find("mnn_multimodal_prefill_failed") != std::string::npos
                ? "prefill_failed"
                : "begin_failed";
        set_error(std::string("MNN beginCompletion exception: ") + detail);
        return kMnnBeginFailed;
    } catch (...) {
        g_generation_active = false;
        g_generation_stop_reason = "begin_failed";
        set_error("MNN beginCompletion exception: unknown native error");
        return kMnnBeginFailed;
    }
#else
    set_error("MNN generation requested before the official MNN-LLM executor is linked.");
    return kMnnRunnerUnavailable;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_generateNextChunk(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
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
        g_generated_steps += 1;
        filter_mnn_stop_markers_locked(false);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, g_eop_seen, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        if (context_finished_locked()) {
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
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
        set_error(std::string("MNN generateNextChunk exception: ") + e.what());
        return nullptr;
    } catch (...) {
        g_generation_active = false;
        set_error("MNN generateNextChunk exception: unknown native error");
        return nullptr;
    }
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_stop_requested = true;
    g_generation_active = false;
    g_generation_stop_reason = "stop_requested";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStopIfActive(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // A final visible chunk can be returned before generateNextChunk() reaches
    // its normal terminal bookkeeping. Reconcile the underlying MNN context
    // here so a stale generationActive flag cannot turn natural completion
    // into a successful cancellation proof.
    if (g_generation_active && context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
    }
#endif
    if (!g_generation_active) return JNI_FALSE;
    g_stop_requested = true;
    g_generation_active = false;
    g_generation_stop_reason = "stop_requested";
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    const auto stats = stats_json_locked();
    return utf8_to_jstring(env, stats);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_shutdown(JNIEnv*, jobject) {
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodeSd15PromptEmbeddings(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring outputPath,
        jstring backendMode,
        jint threads,
        jstring tokenEmbeddingMode) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto token_mode = jstring_to_std(env, tokenEmbeddingMode);
    const auto out = encode_sd15_prompt_embeddings_to_file(
            root,
            jstring_to_std(env, prompt),
            jstring_to_std(env, outputPath),
            jstring_to_std(env, backendMode),
            std::max(1, static_cast<int>(threads)),
            token_mode.empty() ? "auto" : token_mode).dump();
#else
    (void)bundleRoot;
    (void)prompt;
    (void)outputPath;
    (void)backendMode;
    (void)threads;
    (void)tokenEmbeddingMode;
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
        jstring outputPath,
        jint width,
        jint height,
        jstring backendMode,
        jint threads) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto out = encode_sdxl_prompt_conditioning_to_file(
            root,
            jstring_to_std(env, prompt),
            jstring_to_std(env, outputPath),
            std::max(256, static_cast<int>(width)),
            std::max(256, static_cast<int>(height)),
            jstring_to_std(env, backendMode),
            std::max(1, static_cast<int>(threads))).dump();
#else
    (void)bundleRoot;
    (void)prompt;
    (void)outputPath;
    (void)width;
    (void)height;
    (void)backendMode;
    (void)threads;
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
    if (params.is_discarded() || !params.is_object()) params = json::object();
    const auto prompt = opt_string(params, "prompt", "");
    const auto family = opt_string(params, "family", "SD15");
    const bool sana_family = is_sana_family(family);
    const auto backend_mode = opt_string(params, "backendMode", "cpu");
    const auto runner_mode = opt_string(params, "runner", "module");
    const int requested_steps = opt_int(params, "steps", 8);
    const int steps = sana_family
            ? std::max(2, std::min(50, requested_steps))
            : std::max(1, std::min(50, requested_steps));
    const int seed = opt_int(params, "seed", opt_int(params, "randomSeed", -1));
    const int width = std::max(256, opt_int(params, "width", 512));
    const int height = std::max(256, opt_int(params, "height", 512));
    const int threads = std::max(1, opt_int(params, "threads", 4));
    const int memory_mode = std::max(0, std::min(2, opt_int(params, "memoryMode", 0)));
    const bool use_cfg = opt_bool(params, "useCfg", false);
    const float cfg_scale = static_cast<float>(opt_double(params, "cfgScale", sana_family ? 4.5 : 7.5));

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
        g_mnn_diffusion_cancel_requested = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_started_at_ms = now_ms();
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
    const auto missing = mnn_diffusion_missing_component(root, family);
    if (!missing.empty()) {
        const auto out = fail(missing);
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
        if (sana_family) {
            mca::MnnSanaOptions options;
            options.bundle_root = root;
            options.prompt = prompt;
            options.output_path = output_path;
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
            result = json({
                {"ok", sana_ok},
                {"path", output_path},
                {"mimeType", "image/png"}
            });
        } else {
            result = runner_mode == "direct"
                    ? run_mnn_sd15_interpreter_direct(
                            root,
                            prompt,
                            output_path,
                            steps,
                            seed,
                            backend_mode,
                            threads,
                            progress_callback)
                    : run_mnn_sd15_module_runner(
                            root,
                            prompt,
                            output_path,
                            steps,
                            seed,
                            backend_mode,
                            memory_mode,
                            progress_callback);
        }
        const bool ok = result.value("ok", false);
        if (!ok) {
            const auto out = fail(result.value("error", "MNN-Diffusion direct interpreter failed."));
            return utf8_to_jstring(env, out);
        }
        if (!ok || !file_exists(output_path)) {
            const auto out = fail("MNN-Diffusion did not produce a valid output image.");
            return utf8_to_jstring(env, out);
        }
        {
            std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
            g_mnn_diffusion_generating = false;
            g_mnn_diffusion_loaded = false;
            g_mnn_diffusion_finished_at_ms = now_ms();
            set_mnn_diffusion_progress_locked("completed", "Image generation completed.", steps, steps, width, height, threads);
        }
        const auto out = json({
            {"ok", true},
            {"backend", "mnn_diffusion"},
            {"family", sana_family ? "SANA" : family},
            {"backendMode", backend_mode == "opencl" || backend_mode == "gpu" ? "opencl" : "cpu"},
            {"runner", sana_family ? "sana_varp" : (runner_mode == "direct" ? "direct" : "module")},
            {"path", output_path},
            {"mimeType", "image/png"},
            {"steps", steps},
            {"width", width},
            {"height", height}
        }).dump();
        return utf8_to_jstring(env, out);
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
