#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <dirent.h>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

#include "stable-diffusion.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {

std::mutex g_mutex;
std::mutex g_progress_mutex;
sd_ctx_t *g_ctx = nullptr;
std::string g_ctx_key;
std::string g_last_error;
std::string g_last_sd_error;
std::atomic<bool> g_generation_active{false};
std::atomic<bool> g_cancel_requested{false};

const char *sd_runtime_backend_label() {
    return "cpu";
}

struct ProgressState {
    int step = 0;
    int steps = 0;
    float seconds_per_step = 0.0f;
    long long started_ms = 0;
    long long updated_ms = 0;
    int width = 0;
    int height = 0;
    int threads = 0;
    std::string phase = "idle";
    std::string message;
};

ProgressState g_progress;

long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

void set_progress(const std::string &phase,
                  const std::string &message,
                  int step = 0,
                  int steps = 0,
                  float seconds_per_step = 0.0f,
                  int width = 0,
                  int height = 0,
                  int threads = 0) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress.phase = phase;
    g_progress.message = message;
    g_progress.step = step;
    g_progress.steps = steps;
    g_progress.seconds_per_step = seconds_per_step;
    g_progress.updated_ms = now_ms();
    if (width > 0) g_progress.width = width;
    if (height > 0) g_progress.height = height;
    if (threads > 0) g_progress.threads = threads;
    if (g_progress.started_ms == 0 || phase == "initializing") {
        g_progress.started_ms = g_progress.updated_ms;
    }
}

void set_progress_stage(const std::string &phase, const std::string &message) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress.phase = phase;
    g_progress.message = message;
    g_progress.updated_ms = now_ms();
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

void set_last_error(const std::string &message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, "MCA-SD", "%s", message.c_str());
}

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool contains(const std::string &value, const std::string &needle) {
    return value.find(needle) != std::string::npos;
}

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool is_model_file(const std::string &path) {
    const std::string lower = lower_copy(path);
    return ends_with(lower, ".gguf") ||
           ends_with(lower, ".safetensors") ||
           ends_with(lower, ".ckpt") ||
           ends_with(lower, ".pth") ||
           ends_with(lower, ".pt") ||
           ends_with(lower, ".sft");
}

void collect_model_files(const std::string &root, std::vector<std::string> &out) {
    DIR *dir = opendir(root.c_str());
    if (dir == nullptr) return;
    while (dirent *entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        const std::string path = root + "/" + name;
        struct stat st {};
        if (stat(path.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            collect_model_files(path, out);
        } else if (S_ISREG(st.st_mode) && is_model_file(path)) {
            out.push_back(path);
        }
    }
    closedir(dir);
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

int parse_int(const std::string &json, const std::string &key, int fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) pos++;
    std::string number;
    while (pos < json.size() && (std::isdigit(static_cast<unsigned char>(json[pos])) || json[pos] == '-')) {
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
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) pos++;
    std::string number;
    while (pos < json.size() &&
           (std::isdigit(static_cast<unsigned char>(json[pos])) || json[pos] == '-' || json[pos] == '.')) {
        number.push_back(json[pos++]);
    }
    if (number.empty()) return fallback;
    try { return std::stof(number); } catch (...) { return fallback; }
}

bool parse_bool(const std::string &json, const std::string &key, bool fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) pos++;
    if (json.compare(pos, 4, "true") == 0) return true;
    if (json.compare(pos, 5, "false") == 0) return false;
    if (pos < json.size() && json[pos] == '"') {
        const std::string value = lower_copy(parse_json_string_after(json, key, 0));
        if (value == "true" || value == "1" || value == "yes") return true;
        if (value == "false" || value == "0" || value == "no") return false;
    }
    return fallback;
}

std::string escape_json(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c: value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

const char *json_bool(bool value) {
    return value ? "true" : "false";
}

std::string progress_to_json() {
    ProgressState snapshot;
    {
        std::lock_guard<std::mutex> lock(g_progress_mutex);
        snapshot = g_progress;
    }
    const long long current_ms = now_ms();
    const long long elapsed_ms = snapshot.started_ms > 0 ? std::max(0LL, current_ms - snapshot.started_ms) : 0LL;
    const bool active = g_generation_active.load(std::memory_order_relaxed);
    const bool cancelled = g_cancel_requested.load(std::memory_order_relaxed);
    std::ostringstream out;
    out << "{\"active\":" << json_bool(active) << ","
        << "\"cancelRequested\":" << json_bool(cancelled) << ","
        << "\"phase\":\"" << escape_json(snapshot.phase) << "\","
        << "\"message\":\"" << escape_json(snapshot.message) << "\","
        << "\"step\":" << snapshot.step << ","
        << "\"steps\":" << snapshot.steps << ","
        << "\"elapsedMs\":" << elapsed_ms << ","
        << "\"secondsPerStep\":" << snapshot.seconds_per_step << ","
        << "\"width\":" << snapshot.width << ","
        << "\"height\":" << snapshot.height << ","
        << "\"threads\":" << snapshot.threads << "}";
    return out.str();
}

std::string runtime_config_json() {
#if defined(__aarch64__) || defined(_M_ARM64)
    const char *arch = "arm64";
#elif defined(__arm__) || defined(_M_ARM)
    const char *arch = "arm";
#elif defined(__x86_64__) || defined(_M_X64)
    const char *arch = "x86_64";
#else
    const char *arch = "unknown";
#endif
#if defined(__ARM_NEON) || defined(__aarch64__)
    const bool neon = true;
#else
    const bool neon = false;
#endif
#if defined(_OPENMP) || defined(MCA_SD_OPENMP)
    const bool openmp = true;
#else
    const bool openmp = false;
#endif
#if defined(GGML_USE_CPU_KLEIDIAI) || defined(MCA_SD_CPU_KLEIDIAI)
    const bool kleidiai = true;
#else
    const bool kleidiai = false;
#endif
    const int cores = std::max(1, sd_get_num_physical_cores());
    std::ostringstream out;
    out << "{\"ok\":true,"
        << "\"arch\":\"" << arch << "\","
        << "\"physicalCores\":" << cores << ","
        << "\"recommendedThreads\":" << std::max(1, std::min(cores, 6)) << ","
        << "\"neon\":" << json_bool(neon) << ","
        << "\"openMp\":" << json_bool(openmp) << ","
        << "\"kleidiAi\":" << json_bool(kleidiai) << ","
        << "\"gpu\":false,"
        << "\"gpuBackend\":\"CPU-only\","
        << "\"gpuRuntimeEnabled\":false,"
        << "\"runtimeBackend\":\"" << sd_runtime_backend_label() << "\","
        << "\"gpuRuntimeNote\":\"\","
        << "\"systemInfo\":\"" << escape_json(sd_get_system_info()) << "\"}";
    return out.str();
}

void set_progress_from_sd_log(const char *text) {
    if (text == nullptr || text[0] == '\0' ||
        !g_generation_active.load(std::memory_order_relaxed)) {
        return;
    }

    const std::string message(text);
    const std::string lower = lower_copy(message);
    if (contains(lower, "get_learned_condition")) {
        set_progress_stage("conditioning", message);
    } else if (contains(lower, "encode_first_stage")) {
        set_progress_stage("encoding", message);
    } else if (contains(lower, "decode_first_stage") ||
               contains(lower, "decode image") ||
               contains(lower, "decoding")) {
        set_progress_stage("decoding", message);
    } else if (contains(lower, "generating image") ||
               contains(lower, "denoise") ||
               contains(lower, "sample")) {
        set_progress_stage("sampling", message);
    } else if (contains(lower, "generate_image")) {
        set_progress_stage("preparing", message);
    } else if (contains(lower, "loading") ||
               contains(lower, "load ") ||
               contains(lower, "model") ||
               contains(lower, "vae") ||
               contains(lower, "clip") ||
               contains(lower, "t5") ||
               contains(lower, "llm")) {
        set_progress_stage("loading", message);
    }
}

void sd_log_callback(sd_log_level_t level, const char *text, void *) {
    const int android_level = level == SD_LOG_ERROR ? ANDROID_LOG_ERROR :
                              level == SD_LOG_WARN ? ANDROID_LOG_WARN :
                              level == SD_LOG_DEBUG ? ANDROID_LOG_DEBUG :
                              ANDROID_LOG_INFO;
    __android_log_print(android_level, "MCA-SD", "%s", text == nullptr ? "" : text);
    set_progress_from_sd_log(text);
    if ((level == SD_LOG_ERROR || level == SD_LOG_WARN) && text != nullptr && text[0] != '\0') {
        g_last_sd_error = text;
    }
}

void sd_progress_callback(int step, int steps, float time, void *) {
    set_progress(
            g_cancel_requested.load(std::memory_order_relaxed) ? "cancelling" : "sampling",
            g_cancel_requested.load(std::memory_order_relaxed)
            ? "cancel requested"
            : "sampling",
            step,
            steps,
            time);
    __android_log_print(ANDROID_LOG_INFO, "MCA-SD", "generation progress %d/%d %.2fs", step, steps, time);
}

bool sd_cancel_callback(void *) {
    return g_cancel_requested.load(std::memory_order_relaxed);
}

struct ComponentPaths {
    std::string model;
    std::string diffusion;
    std::string high_noise_diffusion;
    std::string vae;
    std::string clip_l;
    std::string clip_g;
    std::string clip_vision;
    std::string t5xxl;
    std::string llm;
    std::string llm_vision;
    std::string embeddings_connectors;
};

bool likely_split_diffusion_family(const std::string &family, const std::string &path) {
    const std::string key = lower_copy(family + " " + path);
    return contains(key, "z_image") ||
           contains(key, "z-image") ||
           contains(key, "qwen_image") ||
           contains(key, "qwen-image") ||
           contains(key, "flux") ||
           contains(key, "wan") ||
           contains(key, "chroma") ||
           contains(key, "ernie") ||
           contains(key, "longcat");
}

ComponentPaths infer_components(const std::string &model_path, const std::string &bundle_root, const std::string &family) {
    ComponentPaths paths;
    if (likely_split_diffusion_family(family, model_path)) {
        paths.diffusion = model_path;
    } else {
        paths.model = model_path;
    }

    std::vector<std::string> files;
    if (dir_exists(bundle_root)) {
        collect_model_files(bundle_root, files);
    }
    for (const std::string &path: files) {
        const std::string lower = lower_copy(path);
        if (path == model_path) continue;
        if (contains(lower, "high") && contains(lower, "noise") && contains(lower, "diffusion")) {
            paths.high_noise_diffusion = path;
        } else if (contains(lower, "clip_vision") || contains(lower, "clip-vision") || contains(lower, "vision_h")) {
            paths.clip_vision = path;
        } else if (contains(lower, "clip_g") || contains(lower, "clip-g")) {
            paths.clip_g = path;
        } else if (contains(lower, "clip_l") || contains(lower, "clip-l")) {
            paths.clip_l = path;
        } else if (contains(lower, "t5xxl") || contains(lower, "umt5") || contains(lower, "t5-xxl")) {
            paths.t5xxl = path;
        } else if (contains(lower, "vae") ||
                   ends_with(lower, "ae.sft") ||
                   ends_with(lower, "ae.safetensors") ||
                   ends_with(lower, "_ae.safetensors") ||
                   ends_with(lower, "-ae.safetensors") ||
                   ends_with(lower, "_ae.gguf") ||
                   ends_with(lower, "-ae.gguf") ||
                   contains(lower, "/ae.")) {
            paths.vae = path;
        } else if (contains(lower, "embeddings") && contains(lower, "connector")) {
            paths.embeddings_connectors = path;
        } else if (contains(lower, "llm_vision") || contains(lower, "llm-vision")) {
            paths.llm_vision = path;
        } else if (contains(lower, "qwen") || contains(lower, "mistral") || contains(lower, "gemma") || contains(lower, "llm")) {
            paths.llm = path;
        } else if (paths.diffusion.empty() &&
                   (contains(lower, "diffusion") || contains(lower, "unet") || contains(lower, "dit"))) {
            paths.diffusion = path;
        }
    }
    if (paths.diffusion.empty() && paths.model.empty()) {
        paths.model = model_path;
    }
    return paths;
}

std::string make_context_key(const ComponentPaths &paths, int threads) {
    std::ostringstream key;
    key << paths.model << "|"
        << paths.diffusion << "|"
        << paths.high_noise_diffusion << "|"
        << paths.vae << "|"
        << paths.clip_l << "|"
        << paths.clip_g << "|"
        << paths.clip_vision << "|"
        << paths.t5xxl << "|"
        << paths.llm << "|"
        << paths.llm_vision << "|"
        << paths.embeddings_connectors << "|"
        << threads << "|"
        << sd_runtime_backend_label();
    return key.str();
}

sd_ctx_t *ensure_context(const ComponentPaths &paths,
                         const std::string &ctx_key,
                         int threads) {
    if (g_ctx != nullptr && g_ctx_key == ctx_key) return g_ctx;
    g_last_sd_error.clear();
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = paths.model.empty() ? nullptr : paths.model.c_str();
    params.diffusion_model_path = paths.diffusion.empty() ? nullptr : paths.diffusion.c_str();
    params.high_noise_diffusion_model_path = paths.high_noise_diffusion.empty() ? nullptr : paths.high_noise_diffusion.c_str();
    params.vae_path = paths.vae.empty() ? nullptr : paths.vae.c_str();
    params.clip_l_path = paths.clip_l.empty() ? nullptr : paths.clip_l.c_str();
    params.clip_g_path = paths.clip_g.empty() ? nullptr : paths.clip_g.c_str();
    params.clip_vision_path = paths.clip_vision.empty() ? nullptr : paths.clip_vision.c_str();
    params.t5xxl_path = paths.t5xxl.empty() ? nullptr : paths.t5xxl.c_str();
    params.llm_path = paths.llm.empty() ? nullptr : paths.llm.c_str();
    params.llm_vision_path = paths.llm_vision.empty() ? nullptr : paths.llm_vision.c_str();
    params.embeddings_connectors_path = paths.embeddings_connectors.empty() ? nullptr : paths.embeddings_connectors.c_str();
    params.vae_decode_only = false;
    params.free_params_immediately = false;
    params.n_threads = threads;
    params.enable_mmap = true;
    params.rng_type = CPU_RNG;
    params.sampler_rng_type = CPU_RNG;
    params.offload_params_to_cpu = false;
    params.keep_clip_on_cpu = true;
    params.keep_vae_on_cpu = true;
    params.diffusion_flash_attn = true;
    params.flash_attn = true;
    params.backend = "cpu";
    params.params_backend = "cpu";
    params.max_vram = 0.0f;

    g_ctx = new_sd_ctx(&params);
    if (g_ctx == nullptr) {
        set_last_error(g_last_sd_error.empty()
                       ? "stable-diffusion.cpp failed to create context"
                       : "stable-diffusion.cpp failed to create context: " + g_last_sd_error);
        return nullptr;
    }
    if (!sd_ctx_supports_image_generation(g_ctx)) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        set_last_error("selected local model does not support image generation");
        return nullptr;
    }
    g_ctx_key = ctx_key;
    return g_ctx;
}

std::string generate_impl(const std::string &model_path,
                          const std::string &bundle_root,
                          const std::string &params_json,
                          const std::string &output_path) {
    if (!file_exists(model_path)) {
        return "{\"ok\":false,\"error\":\"model file does not exist\"}";
    }
    if (output_path.empty()) {
        return "{\"ok\":false,\"error\":\"output path is empty\"}";
    }

    const std::string prompt = parse_string(params_json, "prompt", "");
    if (prompt.empty()) {
        return "{\"ok\":false,\"error\":\"prompt is empty\"}";
    }

    const std::string family = parse_string(params_json, "family", "");
    const int width = std::max(64, parse_int(params_json, "width", 512));
    const int height = std::max(64, parse_int(params_json, "height", 512));
    const int steps = std::max(1, parse_int(params_json, "steps", 8));
    const int threads = std::max(1, parse_int(params_json, "threads", sd_get_num_physical_cores()));
    const int seed = parse_int(params_json, "seed", -1);
    const float cfg = parse_float(params_json, "cfgScale", 1.0f);
    const float distilled_guidance = parse_float(params_json, "distilledGuidance", 3.5f);
    const float flow_shift = parse_float(params_json, "flowShift", -1.0f);
    const std::string negative_prompt = parse_string(params_json, "negativePrompt", "");
    const std::string sample_method_name = parse_string(params_json, "sampleMethod", "euler");
    set_progress("initializing", "checking model bundle", 0, steps, 0.0f, width, height, threads);
    if (g_cancel_requested.load(std::memory_order_relaxed)) {
        return "{\"ok\":false,\"cancelled\":true,\"error\":\"cancelled\"}";
    }
    const ComponentPaths paths = infer_components(model_path, bundle_root, family);
    const std::string ctx_key = make_context_key(paths, threads);
    set_progress("loading", "loading stable-diffusion.cpp context", 0, steps, 0.0f, width, height, threads);
    sd_ctx_t *ctx = ensure_context(paths, ctx_key, threads);
    if (ctx == nullptr) {
        set_progress("failed", g_last_error, 0, steps, 0.0f, width, height, threads);
        return "{\"ok\":false,\"error\":\"" + escape_json(g_last_error) + "\"}";
    }
    if (g_cancel_requested.load(std::memory_order_relaxed)) {
        set_progress("cancelled", "cancelled before sampling", 0, steps, 0.0f, width, height, threads);
        return "{\"ok\":false,\"cancelled\":true,\"error\":\"cancelled\"}";
    }

    sd_img_gen_params_t gen;
    sd_img_gen_params_init(&gen);
    gen.prompt = prompt.c_str();
    gen.negative_prompt = negative_prompt.empty() ? nullptr : negative_prompt.c_str();
    gen.width = width;
    gen.height = height;
    gen.seed = seed;
    gen.batch_count = 1;
    gen.sample_params.sample_steps = steps;
    gen.sample_params.guidance.txt_cfg = cfg;
    gen.sample_params.guidance.distilled_guidance = distilled_guidance;
    if (flow_shift > 0.0f) {
        gen.sample_params.flow_shift = flow_shift;
    }
    const auto method = str_to_sample_method(sample_method_name.c_str());
    gen.sample_params.sample_method = method == SAMPLE_METHOD_COUNT ? sd_get_default_sample_method(ctx) : method;
    gen.sample_params.scheduler = sd_get_default_scheduler(ctx, gen.sample_params.sample_method);

    set_progress("preparing", "preparing image generation", 0, steps, 0.0f, width, height, threads);
    sd_image_t *images = generate_image(ctx, &gen);
    if (g_cancel_requested.load(std::memory_order_relaxed)) {
        if (images != nullptr) {
            if (images[0].data != nullptr) free(images[0].data);
            free(images);
        }
        set_progress("cancelled", "cancelled", 0, steps, 0.0f, width, height, threads);
        return "{\"ok\":false,\"cancelled\":true,\"error\":\"cancelled\"}";
    }
    if (images == nullptr || images[0].data == nullptr) {
        free(images);
        set_progress("failed", "stable-diffusion.cpp returned no image", 0, steps, 0.0f, width, height, threads);
        return "{\"ok\":false,\"error\":\"stable-diffusion.cpp returned no image\"}";
    }

    const sd_image_t first = images[0];
    set_progress("writing", "writing png", steps, steps, 0.0f, width, height, threads);
    const int stride = static_cast<int>(first.width * first.channel);
    const int write_ok = stbi_write_png(output_path.c_str(),
                                        static_cast<int>(first.width),
                                        static_cast<int>(first.height),
                                        static_cast<int>(first.channel),
                                        first.data,
                                        stride);
    free(first.data);
    free(images);
    if (write_ok == 0) {
        set_progress("failed", "failed to write png", steps, steps, 0.0f, width, height, threads);
        return "{\"ok\":false,\"error\":\"failed to write png\"}";
    }
    set_progress("completed", "image saved", steps, steps, 0.0f, width, height, threads);

    std::ostringstream out;
    out << "{\"ok\":true,"
        << "\"path\":\"" << escape_json(output_path) << "\","
        << "\"mimeType\":\"image/png\","
        << "\"width\":" << first.width << ","
        << "\"height\":" << first.height << ","
        << "\"backend\":\"stable-diffusion.cpp\","
        << "\"runtimeBackend\":\"" << sd_runtime_backend_label() << "\","
        << "\"systemInfo\":\"" << escape_json(sd_get_system_info()) << "\"}";
    return out.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_generate(
        JNIEnv *env,
        jobject,
        jstring model_path,
        jstring bundle_root,
        jstring params_json,
        jstring output_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel_requested.store(false, std::memory_order_relaxed);
    g_generation_active.store(true, std::memory_order_relaxed);
    set_progress("initializing", "starting local image generation");
    sd_set_log_callback(sd_log_callback, nullptr);
    sd_set_progress_callback(sd_progress_callback, nullptr);
    sd_set_cancel_callback(sd_cancel_callback, nullptr);
    const std::string result = generate_impl(
            jstring_to_string(env, model_path),
            jstring_to_string(env, bundle_root),
            jstring_to_string(env, params_json),
            jstring_to_string(env, output_path));
    g_generation_active.store(false, std::memory_order_relaxed);
    sd_set_cancel_callback(nullptr, nullptr);
    return string_to_jstring(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getSystemInfo(JNIEnv *env, jobject) {
    return string_to_jstring(env, sd_get_system_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getProgress(JNIEnv *env, jobject) {
    return string_to_jstring(env, progress_to_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getNativeConfig(JNIEnv *env, jobject) {
    return string_to_jstring(env, runtime_config_json());
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_cancel(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    set_progress("cancelling", "cancel requested");
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_shutdown(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
    g_generation_active.store(false, std::memory_order_relaxed);
    set_progress("idle", "native context released");
}
