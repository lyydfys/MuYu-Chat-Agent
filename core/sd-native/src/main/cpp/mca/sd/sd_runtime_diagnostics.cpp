#include "mca/sd/sd_runtime_diagnostics.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <mutex>
#include <sstream>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace mca {
namespace sd {

namespace {

std::atomic<bool> g_generation_active{false};
std::atomic<bool> g_cancel_requested{false};
std::mutex g_sd_error_mutex;
std::string g_last_sd_error_storage;

inline const char* json_bool(bool value) {
    return value ? "true" : "false";
}

std::string escape_json(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c : value) {
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

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool contains(const std::string& value, const std::string& needle) {
    return value.find(needle) != std::string::npos;
}

} // namespace

std::atomic<bool>& generation_active() noexcept {
    return g_generation_active;
}

std::atomic<bool>& cancel_requested() noexcept {
    return g_cancel_requested;
}

std::string get_last_sd_error() {
    std::lock_guard<std::mutex> lock(g_sd_error_mutex);
    return g_last_sd_error_storage;
}

std::string& last_sd_error_storage() noexcept {
    return g_last_sd_error_storage;
}

void set_last_sd_error(const std::string& error) {
    std::lock_guard<std::mutex> lock(g_sd_error_mutex);
    g_last_sd_error_storage = error;
}

void clear_last_sd_error() {
    std::lock_guard<std::mutex> lock(g_sd_error_mutex);
    g_last_sd_error_storage.clear();
}

const char* sd_runtime_backend_label() noexcept {
    return "cpu";
}

std::string progress_to_json(bool active, bool cancelled) {
    const ProgressState snapshot = get_progress_snapshot();
    const long long current_ms = progress_now_ms();
    const long long elapsed_ms = snapshot.started_ms > 0 ? std::max(0LL, current_ms - snapshot.started_ms) : 0LL;
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
        << "\"threads\":" << snapshot.threads << ","
        << "\"previewPath\":\"" << escape_json(snapshot.preview_path) << "\","
        << "\"previewMimeType\":\"" << escape_json(snapshot.preview_mime_type) << "\","
        << "\"previewMode\":\"" << escape_json(snapshot.preview_mode) << "\","
        << "\"previewStep\":" << snapshot.preview_step << ","
        << "\"previewRevision\":" << snapshot.preview_revision << ","
        << "\"previewWidth\":" << snapshot.preview_width << ","
        << "\"previewHeight\":" << snapshot.preview_height << ","
        << "\"previewFrameCount\":" << snapshot.preview_frame_count << ","
        << "\"previewNoisy\":" << json_bool(snapshot.preview_noisy) << ","
        << "\"componentSelection\":" << snapshot.component_selection_json << "}";
    return out.str();
}

std::string progress_to_json() {
    return progress_to_json(
            g_generation_active.load(std::memory_order_relaxed),
            g_cancel_requested.load(std::memory_order_relaxed));
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
    if (text == nullptr || text[0] == '\0') {
        return;
    }

    const std::string message(text);
    const std::string lower = lower_copy(message);
    if (contains(lower, "running in v-prediction edm mode")) {
        observed_prediction().store(EDM_V_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in v-prediction mode")) {
        observed_prediction().store(V_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in eps-prediction mode")) {
        observed_prediction().store(EPS_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in flux2 flow mode")) {
        observed_prediction().store(FLUX2_FLOW_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in flux flow mode")) {
        observed_prediction().store(FLUX_FLOW_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in qwen image 2.1 flow mode") ||
               contains(lower, "running in flow mode") ||
               contains(lower, "running in ltxav flow mode")) {
        observed_prediction().store(FLOW_PRED, std::memory_order_relaxed);
    }
    if (!g_generation_active.load(std::memory_order_relaxed)) return;
    if (contains(lower, "get_learned_condition")) {
        set_progress_stage("conditioning", "running native text conditioning");
    } else if (contains(lower, "encode_first_stage")) {
        set_progress_stage("encoding", "encoding the native input latent");
    } else if (contains(lower, "decode_first_stage") ||
               contains(lower, "decode image") ||
               contains(lower, "decoding")) {
        set_progress_stage("decoding", "decoding the native image output");
    } else if (contains(lower, "generating image") ||
               contains(lower, "denoise") ||
               contains(lower, "sample")) {
        set_progress_stage("sampling", "running native diffusion sampling");
    } else if (contains(lower, "generate_image")) {
        set_progress_stage("preparing", "preparing native image generation");
    } else if (contains(lower, "loading") ||
               contains(lower, "load ") ||
               contains(lower, "model") ||
               contains(lower, "vae") ||
               contains(lower, "clip") ||
               contains(lower, "t5") ||
               contains(lower, "llm")) {
        set_progress_stage("loading", "loading native model components");
    }
}

std::string safe_sd_runtime_message(sd_log_level_t level, const char *text) {
    const std::string lower = lower_copy(text == nullptr ? "" : text);
    const char *severity = level == SD_LOG_ERROR ? "error" : "warning";
    if (contains(lower, "cancel")) {
        return std::string("native runtime ") + severity + ": operation cancelled";
    }
    if (contains(lower, "out of memory") || contains(lower, "alloc") ||
        contains(lower, "buffer")) {
        return std::string("native runtime ") + severity + ": memory allocation failed";
    }
    if (contains(lower, "backend") || contains(lower, "vulkan") ||
        contains(lower, "opencl")) {
        return std::string("native runtime ") + severity + ": backend initialization failed";
    }
    if (contains(lower, "token") || contains(lower, "condition") ||
        contains(lower, "clip") || contains(lower, "t5") || contains(lower, "llm")) {
        return std::string("native runtime ") + severity + ": text conditioning failed";
    }
    if (contains(lower, "vae") || contains(lower, "decode") ||
        contains(lower, "encode_first_stage")) {
        return std::string("native runtime ") + severity + ": image codec stage failed";
    }
    if (contains(lower, "lora")) {
        return std::string("native runtime ") + severity + ": LoRA application failed";
    }
    if (contains(lower, "control")) {
        return std::string("native runtime ") + severity + ": control conditioning failed";
    }
    if (contains(lower, "model") || contains(lower, "load") ||
        contains(lower, "file") || contains(lower, "mmap")) {
        return std::string("native runtime ") + severity + ": model loading failed";
    }
    if (contains(lower, "compute") || contains(lower, "sample") ||
        contains(lower, "denois")) {
        return std::string("native runtime ") + severity + ": model execution failed";
    }
    return std::string("native runtime reported a ") + severity;
}

void sd_log_callback(sd_log_level_t level, const char *text, void *) {
    set_progress_from_sd_log(text);
    if ((level == SD_LOG_ERROR || level == SD_LOG_WARN) && text != nullptr && text[0] != '\0') {
        const std::string message = safe_sd_runtime_message(level, text);
        set_last_sd_error(message);
#if defined(__ANDROID__)
        __android_log_print(
                level == SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                "MCA-SD",
                "%s",
                message.c_str());
#else
        fprintf(stderr, "[MCA-SD] %s\n", message.c_str());
#endif
    }
}

} // namespace sd
} // namespace mca
