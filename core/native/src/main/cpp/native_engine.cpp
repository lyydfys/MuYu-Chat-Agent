#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <exception>
#include <cctype>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#if MCA_WITH_LLAMA_CPP
#include <unistd.h>
#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"
#endif

namespace {

std::mutex g_mutex;
std::atomic_bool g_stop_requested{false};
bool g_loaded = false;
long long g_load_ms = 0;
long long g_prompt_tokens = 0;
long long g_completion_tokens = 0;
long long g_context_shifts = 0;
long long g_prefill_started_ms = 0;
long long g_prefill_finished_ms = 0;
long long g_decode_started_ms = 0;
long long g_decode_finished_ms = 0;
std::string g_model_path;
std::string g_last_error;
std::string g_native_lib_dir;
bool g_backend_initialized = false;
size_t g_backend_device_count = 0;
int g_n_threads = 0;
int g_n_threads_batch = 0;
int g_n_batch = 0;
int g_n_ubatch = 0;
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
bool g_vision_ready = false;
std::string g_mmproj_path;

void set_last_error(const std::string &message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, "MCA", "%s", message.c_str());
}

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
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
    std::ostringstream out;
    out << "{"
        << "\"backend\":\"" << backend << "\","
        << "\"loaded\":" << (g_loaded ? "true" : "false") << ","
        << "\"modelPath\":\"" << json_escape(g_model_path) << "\","
        << "\"loadMs\":" << g_load_ms << ","
        << "\"promptTokens\":" << g_prompt_tokens << ","
        << "\"completionTokens\":" << g_completion_tokens << ","
        << "\"contextShifts\":" << g_context_shifts << ","
        << "\"prefillMs\":" << prefill_ms << ","
        << "\"decodeMs\":" << decode_ms << ","
        << "\"decodeTps\":" << tps << ","
        << "\"nThreads\":" << g_n_threads << ","
        << "\"nThreadsBatch\":" << g_n_threads_batch << ","
        << "\"nBatch\":" << g_n_batch << ","
        << "\"nUbatch\":" << g_n_ubatch << ","
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
        << "\"visionReady\":" << (g_vision_ready ? "true" : "false") << ","
        << "\"mmprojPath\":\"" << json_escape(g_mmproj_path) << "\","
        << "\"backendReady\":" << (g_backend_device_count > 0 ? "true" : "false") << ","
        << "\"backendDeviceCount\":" << g_backend_device_count << ","
        << "\"backendDevices\":" << backend_devices_json_array() << ","
        << "\"nativeLibDir\":\"" << json_escape(g_native_lib_dir) << "\","
        << "\"lastError\":\"" << json_escape(g_last_error) << "\""
        << "}";
    return out.str();
}

#if MCA_WITH_LLAMA_CPP
constexpr int BATCH_SIZE = 512;
constexpr int OVERFLOW_HEADROOM = 4;

llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
llama_batch g_batch{};
bool g_batch_ready = false;
common_chat_templates_ptr g_chat_templates;
common_sampler *g_sampler = nullptr;
mtmd_context *g_mtmd_context = nullptr;
std::vector<common_chat_msg> g_chat_messages;
llama_pos g_current_position = 0;
int g_n_ctx = 4096;
int g_n_predict = 8192;
int g_n_keep = 128;
std::string g_cached_token_chars;

common_params_sampling build_sampling_params(const std::string &params_json) {
    common_params_sampling params;
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
    if (g_backend_device_count > 0) return true;

    g_last_error += "No llama.cpp ggml backend devices are registered. ";
    g_last_error += "nativeLibDir=" + g_native_lib_dir + ". ";
    g_last_error += "Backend files: " + list_ggml_backend_files(g_native_lib_dir) + ". ";
    g_last_error += "On Android, dynamic GGML backends require extracted native libraries; keep android:extractNativeLibs=\"true\" / jniLibs.useLegacyPackaging=true.";
    return false;
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
    g_current_position = 0;
    g_cached_token_chars.clear();
    g_n_threads = 0;
    g_n_threads_batch = 0;
    g_n_batch = 0;
    g_n_ubatch = 0;
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
}

bool shift_context_locked() {
    if (g_context == nullptr) return false;
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
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        if (g_stop_requested.load(std::memory_order_relaxed)) return 3;
        const int batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(g_batch);
        if (g_current_position + batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            if (!shift_context_locked()) return 4;
        }
        for (int j = 0; j < batch_size; j++) {
            const bool want_logits = logits_last && (i + j == (int) tokens.size() - 1);
            common_batch_add(g_batch, tokens[i + j], g_current_position + j, {0}, want_logits);
        }
        if (llama_decode(g_context, g_batch) != 0) return 2;
        g_current_position += batch_size;
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

        return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
    }

    std::string formatted;
    for (const auto &message: messages) {
        const std::string role = message.role.empty() ? "user" : message.role;
        formatted += role + ": " + message.content + "\n";
    }
    formatted += "assistant: ";
    return formatted;
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
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    for (const auto &message: messages) {
        for (const auto &path: message.image_paths) {
            mtmd_bitmap *bitmap = mtmd_helper_bitmap_init_from_file(g_mtmd_context, path.c_str());
            if (bitmap == nullptr) {
                for (auto *owned: owned_bitmaps) mtmd_bitmap_free(owned);
                g_last_error = "Failed to load local vision image: " + path;
                return -5;
            }
            owned_bitmaps.push_back(bitmap);
            bitmap_ptrs.push_back(bitmap);
        }
    }

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (chunks == nullptr) {
        for (auto *owned: owned_bitmaps) mtmd_bitmap_free(owned);
        g_last_error = "mtmd_input_chunks_init returned null.";
        return -6;
    }

    const auto marked_messages = with_media_markers(messages);
    const auto formatted = format_messages(marked_messages, chat_options);
    mtmd_input_text text{};
    text.text = formatted.c_str();
    text.add_special = true;
    text.parse_special = true;

    int tokenize_rc = mtmd_tokenize(
            g_mtmd_context,
            chunks,
            &text,
            bitmap_ptrs.empty() ? nullptr : bitmap_ptrs.data(),
            bitmap_ptrs.size());
    for (auto *owned: owned_bitmaps) mtmd_bitmap_free(owned);
    if (tokenize_rc != 0) {
        mtmd_input_chunks_free(chunks);
        g_last_error = "mtmd_tokenize failed: " + std::to_string(tokenize_rc);
        return -7;
    }

    llama_pos new_position = 0;
    const int eval_rc = mtmd_helper_eval_chunks(
            g_mtmd_context,
            g_context,
            chunks,
            0,
            0,
            BATCH_SIZE,
            true,
            &new_position);
    g_prompt_tokens = (long long) mtmd_helper_get_n_tokens(chunks);
    mtmd_input_chunks_free(chunks);
    if (eval_rc != 0) {
        g_last_error = "mtmd_helper_eval_chunks failed: " + std::to_string(eval_rc);
        return -8;
    }
    g_current_position = new_position;
    return 0;
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
#if MCA_WITH_LLAMA_CPP
    llama_log_set(android_llama_log, nullptr);
    mtmd_helper_log_set(android_llama_log, nullptr);
    const auto path = jstring_to_string(env, nativeLibDir);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_native_lib_dir = path;
    g_last_error.clear();
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
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_loadModel(
        JNIEnv *env,
        jobject,
        jstring modelPath,
        jstring paramsJson
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string path = jstring_to_string(env, modelPath);
    const std::string params = jstring_to_string(env, paramsJson);
    g_stop_requested.store(true, std::memory_order_relaxed);
    g_model_path = path;
    g_prompt_tokens = 0;
    g_completion_tokens = 0;
    g_prefill_started_ms = 0;
    g_prefill_finished_ms = 0;
    g_decode_started_ms = 0;
    g_decode_finished_ms = 0;
    g_last_error.clear();

#if MCA_WITH_LLAMA_CPP
    free_llama_locked();
    const long long started = now_ms();

    if (!ensure_backends_loaded_locked()) {
        return 11;
    }
    g_last_error.clear();

    {
        std::ifstream input(path, std::ios::binary);
        if (!input.good()) {
            g_last_error = "Model file is not readable: " + path;
            return 10;
        }
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = parse_bool(params, "mmap", true);
    model_params.use_mlock = parse_bool(params, "mlock", false);
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr && model_params.use_mmap) {
        g_last_error += "\nRetrying model load with mmap=false.";
        model_params.use_mmap = false;
        g_model = llama_model_load_from_file(path.c_str(), model_params);
    }
    if (g_model == nullptr) {
        if (g_last_error.empty()) {
            g_last_error = "llama_model_load_from_file returned null. The file may be incomplete, unsupported, or not a chat model GGUF.";
        }
        return 1;
    }

    g_n_ctx = parse_int(params, "n_ctx", 4096);
    const int n_threads_default = std::max(1, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2);
    const int n_threads = parse_int(params, "n_threads", n_threads_default);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = g_n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    g_n_threads = n_threads;
    g_n_threads_batch = n_threads;
    g_n_batch = BATCH_SIZE;
    g_n_ubatch = BATCH_SIZE;
    g_context = llama_init_from_model(g_model, ctx_params);
    if (g_context == nullptr) {
        g_last_error += "\nllama_init_from_model returned null. Try lower n_ctx or a smaller Q4 model.";
        free_llama_locked();
        return 2;
    }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_batch_ready = true;
    try {
        g_chat_templates = common_chat_templates_init(g_model, "");
    } catch (const std::exception &e) {
        set_last_error(std::string("common_chat_templates_init exception: ") + e.what());
        free_llama_locked();
        return 4;
    } catch (...) {
        set_last_error("common_chat_templates_init exception: unknown native error");
        free_llama_locked();
        return 4;
    }
    if (!configure_sampler_locked(params)) {
        free_llama_locked();
        return 3;
    }

    const std::string mmproj_path = parse_string(params, "mmproj_path", "");
    if (!mmproj_path.empty()) {
        std::ifstream projector_input(mmproj_path, std::ios::binary);
        if (!projector_input.good()) {
            g_last_error = "Vision projector file is not readable: " + mmproj_path;
            free_llama_locked();
            return 5;
        }
        mtmd_context_params vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.print_timings = false;
        vision_params.n_threads = n_threads;
        vision_params.warmup = false;
        g_mtmd_context = mtmd_init_from_file(mmproj_path.c_str(), g_model, vision_params);
        if (g_mtmd_context == nullptr) {
            if (g_last_error.empty()) {
                g_last_error = "mtmd_init_from_file returned null. The mmproj may not match this main model.";
            }
            free_llama_locked();
            return 6;
        }
        if (!mtmd_support_vision(g_mtmd_context)) {
            g_last_error = "The attached mtmd projector does not report vision support.";
            free_llama_locked();
            return 7;
        }
        g_vision_ready = true;
        g_mmproj_path = mmproj_path;
    }
    g_load_ms = now_ms() - started;
    g_loaded = true;
    g_stop_requested.store(false, std::memory_order_relaxed);
    return 0;
#else
    (void) params;
    g_loaded = true;
    g_load_ms = 12;
    g_stop_requested.store(false, std::memory_order_relaxed);
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_unloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop_requested.store(true, std::memory_order_relaxed);
#if MCA_WITH_LLAMA_CPP
    free_llama_locked();
#else
    g_stub_chunks.clear();
    g_stub_chunk_index = 0;
#endif
    g_loaded = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
        JNIEnv *env,
        jobject,
        jstring messagesJson,
        jstring paramsJson
) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_loaded) return -1;
        const std::string messages_json = jstring_to_string(env, messagesJson);
        const std::string params_json = jstring_to_string(env, paramsJson);
        g_stop_requested.store(false, std::memory_order_relaxed);
        g_prompt_tokens = 0;
        g_completion_tokens = 0;
        g_prefill_started_ms = now_ms();
        g_prefill_finished_ms = g_prefill_started_ms;
        g_decode_started_ms = 0;
        g_decode_finished_ms = 0;

#if MCA_WITH_LLAMA_CPP
        if (g_context == nullptr) return -2;
        if (!configure_sampler_locked(params_json)) return -3;
        const int requested_threads = parse_int(params_json, "n_threads", g_n_threads > 0 ? g_n_threads : 1);
        const int requested_threads_batch = parse_int(params_json, "n_threads_batch", requested_threads);
        if (requested_threads != g_n_threads || requested_threads_batch != g_n_threads_batch) {
            llama_set_n_threads(g_context, requested_threads, requested_threads_batch);
            g_n_threads = requested_threads;
            g_n_threads_batch = requested_threads_batch;
        }
        llama_memory_clear(llama_get_memory(g_context), false);
        common_sampler_reset(g_sampler);
        g_chat_messages.clear();
        g_current_position = 0;
        g_cached_token_chars.clear();
        g_context_shifts = 0;
        g_n_predict = parse_int(params_json, "n_predict", 8192);
        g_prefill_started_ms = now_ms();
        ChatTemplateOptions chat_options;
        chat_options.use_jinja = should_use_jinja(params_json);
        chat_options.enable_thinking = should_enable_thinking(params_json);
        chat_options.thinking_budget = thinking_budget_for_mode(params_json);
        g_last_use_jinja = chat_options.use_jinja;
        g_last_enable_thinking = chat_options.enable_thinking;
        const auto messages = parse_messages(messages_json);
        int rc = 0;
        if (messages_have_images(messages)) {
            rc = prefill_multimodal_locked(messages, chat_options);
        } else {
            const auto formatted = format_messages(messages, chat_options);
            auto tokens = common_tokenize(g_context, formatted, true, true);
            g_prompt_tokens = (long long) tokens.size();
            rc = decode_tokens(tokens, true);
        }
        g_n_keep = std::min(
                std::max(64, (int) (g_prompt_tokens / 4)),
                std::max(64, g_n_ctx / 2));
        g_prefill_finished_ms = now_ms();
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
        return 0;
#endif
    } catch (const std::exception &e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        set_last_error(std::string("beginCompletion exception: ") + e.what());
        return -20;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        set_last_error("beginCompletion exception: unknown native error");
        return -21;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_generateNextChunk(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_stop_requested.load(std::memory_order_relaxed)) return nullptr;

#if MCA_WITH_LLAMA_CPP
    if (g_context == nullptr || g_sampler == nullptr) return nullptr;
    if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) return nullptr;

    const long long token_started = now_ms();
    if (g_completion_tokens == 0) {
        g_decode_started_ms = token_started;
        g_decode_finished_ms = token_started;
    }
    const llama_token token = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, token, true);
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) return nullptr;

    if (g_current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        if (!shift_context_locked()) return nullptr;
    }

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token, g_current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) return nullptr;
    g_current_position++;
    g_completion_tokens++;
    g_decode_finished_ms = now_ms();

    g_cached_token_chars += common_token_to_piece(g_context, token);
    if (is_valid_utf8(g_cached_token_chars.c_str())) {
        const std::string out = g_cached_token_chars;
        g_cached_token_chars.clear();
        return string_to_jstring(env, out);
    }
    return string_to_jstring(env, "");
#else
    if (g_stub_chunk_index >= g_stub_chunks.size()) return nullptr;
    const long long token_started = now_ms();
    if (g_completion_tokens == 0) {
        g_decode_started_ms = token_started;
        g_decode_finished_ms = token_started;
    }
    const std::string chunk = g_stub_chunks[g_stub_chunk_index++];
    g_completion_tokens += 8;
    g_decode_finished_ms = now_ms();
    return string_to_jstring(env, chunk);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStop(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getRuntimeStatsJson(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
#if MCA_WITH_LLAMA_CPP
    return string_to_jstring(env, stats_json("llama.cpp-cpu"));
#else
    return string_to_jstring(env, stats_json("cpu-stub"));
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_shutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop_requested.store(true, std::memory_order_relaxed);
#if MCA_WITH_LLAMA_CPP
    free_llama_locked();
    llama_backend_free();
    g_backend_initialized = false;
    g_backend_device_count = 0;
#else
    g_stub_chunks.clear();
    g_stub_chunk_index = 0;
#endif
    g_loaded = false;
}

