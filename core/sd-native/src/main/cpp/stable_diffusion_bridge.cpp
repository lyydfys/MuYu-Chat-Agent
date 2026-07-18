#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <exception>
#include <fcntl.h>
#include <limits.h>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <unordered_set>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {

using json = nlohmann::json;

std::mutex g_mutex;
std::mutex g_progress_mutex;
sd_ctx_t *g_ctx = nullptr;
std::string g_ctx_key;
std::string g_last_error;
std::string g_last_sd_error;
std::atomic<bool> g_generation_active{false};
std::atomic<bool> g_cancel_requested{false};
std::atomic<uint64_t> g_generation_sequence{0};
std::atomic<uint64_t> g_active_generation_sequence{0};
std::atomic<uint32_t> g_generation_stage_mask{0};
std::atomic<int> g_observed_prediction{PREDICTION_COUNT};
std::atomic<int> g_ui_progress_callback_count{0};
std::atomic<int> g_ui_progress_reported_steps{0};
std::atomic<int> g_ui_progress_max_step{0};
std::atomic<uint64_t> g_preview_revision{0};

constexpr uint32_t kStageContractValidated = 1u << 0u;
constexpr uint32_t kStageContextReady = 1u << 1u;
constexpr uint32_t kStageGenerationInvoked = 1u << 2u;
constexpr uint32_t kStageSamplingObserved = 1u << 3u;
constexpr uint32_t kStageImageReturned = 1u << 4u;
constexpr uint32_t kStageOutputWritten = 1u << 5u;
constexpr uint32_t kStageContextReleased = 1u << 6u;
constexpr uint32_t kStageInputImageDecoded = 1u << 7u;
constexpr uint32_t kStageMaskImageDecoded = 1u << 8u;
constexpr uint32_t kStageControlImageDecoded = 1u << 9u;
constexpr uint32_t kStageLoraValidated = 1u << 10u;

constexpr size_t kMaxInputImageBytes = 32u * 1024u * 1024u;
constexpr uint32_t kMaxInputImageSide = 8192u;
constexpr uint64_t kMaxInputImagePixels = 64u * 1024u * 1024u;
constexpr int kMaxBatchCount = 8;
constexpr size_t kMaxLoraCount = 8u;
constexpr uint64_t kMaxLoraBytes = 2ull * 1024ull * 1024ull * 1024ull;
constexpr uint64_t kMaxUpscalerBytes = 2ull * 1024ull * 1024ull * 1024ull;
constexpr uint32_t kMaxUpscaleInputSide = 2048u;
constexpr uint64_t kMaxUpscaleInputPixels = 4000000ull;
constexpr uint32_t kMaxUpscaleProductOutputSide = 4096u;
constexpr uint64_t kMaxUpscaleProductOutputPixels = 16000000ull;
// A fixed-scale model may produce a larger intermediate before Android publishes
// the requested 2x/3x/4x result. Keep that intermediate bounded independently.
constexpr uint32_t kMaxUpscaleNativeOutputSide = 8192u;
constexpr uint64_t kMaxUpscaleNativeOutputPixels = 64000000ull;

void mark_generation_stage(uint32_t stage) {
    g_generation_stage_mask.fetch_or(stage, std::memory_order_relaxed);
}

void reset_generation_evidence() {
    g_active_generation_sequence.store(0, std::memory_order_relaxed);
    g_generation_stage_mask.store(0, std::memory_order_relaxed);
    g_observed_prediction.store(PREDICTION_COUNT, std::memory_order_relaxed);
    g_ui_progress_callback_count.store(0, std::memory_order_relaxed);
    g_ui_progress_reported_steps.store(0, std::memory_order_relaxed);
    g_ui_progress_max_step.store(0, std::memory_order_relaxed);
}

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
    std::string component_selection_json = "{}";
    std::string preview_path;
    std::string preview_mime_type;
    std::string preview_mode;
    int preview_step = 0;
    uint64_t preview_revision = 0;
    int preview_width = 0;
    int preview_height = 0;
    int preview_frame_count = 0;
    bool preview_noisy = false;
};

ProgressState g_progress;

void reset_progress_state() {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress = ProgressState{};
}

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

void set_progress_component_selection(const std::string &component_selection_json) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress.component_selection_json = component_selection_json.empty()
                                          ? "{}"
                                          : component_selection_json;
    g_progress.updated_ms = now_ms();
}

void append_utf8_code_point(std::string &target, uint32_t code_point) {
    if (code_point <= 0x7fu) {
        target.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ffu) {
        target.push_back(static_cast<char>(0xc0u | (code_point >> 6u)));
        target.push_back(static_cast<char>(0x80u | (code_point & 0x3fu)));
    } else if (code_point <= 0xffffu) {
        target.push_back(static_cast<char>(0xe0u | (code_point >> 12u)));
        target.push_back(static_cast<char>(0x80u | ((code_point >> 6u) & 0x3fu)));
        target.push_back(static_cast<char>(0x80u | (code_point & 0x3fu)));
    } else {
        target.push_back(static_cast<char>(0xf0u | (code_point >> 18u)));
        target.push_back(static_cast<char>(0x80u | ((code_point >> 12u) & 0x3fu)));
        target.push_back(static_cast<char>(0x80u | ((code_point >> 6u) & 0x3fu)));
        target.push_back(static_cast<char>(0x80u | (code_point & 0x3fu)));
    }
}

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3u);
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xd800u && code_point <= 0xdbffu) {
            if (index + 1 < length && chars[index + 1] >= 0xdc00u && chars[index + 1] <= 0xdfffu) {
                code_point = 0x10000u + ((code_point - 0xd800u) << 10u) +
                             (static_cast<uint32_t>(chars[++index]) - 0xdc00u);
            } else {
                code_point = 0xfffdu;
            }
        } else if (code_point >= 0xdc00u && code_point <= 0xdfffu) {
            code_point = 0xfffdu;
        }
        append_utf8_code_point(result, code_point);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv *env, const std::string &value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    size_t index = 0;
    while (index < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        uint32_t code_point = 0xfffdu;
        size_t width = 1;
        if (first <= 0x7fu) {
            code_point = first;
        } else if ((first & 0xe0u) == 0xc0u && index + 1 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[index + 1]);
            if ((second & 0xc0u) == 0x80u) {
                const uint32_t decoded = ((first & 0x1fu) << 6u) | (second & 0x3fu);
                if (decoded >= 0x80u) {
                    code_point = decoded;
                    width = 2;
                }
            }
        } else if ((first & 0xf0u) == 0xe0u && index + 2 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[index + 1]);
            const uint8_t third = static_cast<uint8_t>(value[index + 2]);
            if ((second & 0xc0u) == 0x80u && (third & 0xc0u) == 0x80u) {
                const uint32_t decoded = ((first & 0x0fu) << 12u) |
                                         ((second & 0x3fu) << 6u) |
                                         (third & 0x3fu);
                if (decoded >= 0x800u && !(decoded >= 0xd800u && decoded <= 0xdfffu)) {
                    code_point = decoded;
                    width = 3;
                }
            }
        } else if ((first & 0xf8u) == 0xf0u && index + 3 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[index + 1]);
            const uint8_t third = static_cast<uint8_t>(value[index + 2]);
            const uint8_t fourth = static_cast<uint8_t>(value[index + 3]);
            if ((second & 0xc0u) == 0x80u && (third & 0xc0u) == 0x80u &&
                (fourth & 0xc0u) == 0x80u) {
                const uint32_t decoded = ((first & 0x07u) << 18u) |
                                         ((second & 0x3fu) << 12u) |
                                         ((third & 0x3fu) << 6u) |
                                         (fourth & 0x3fu);
                if (decoded >= 0x10000u && decoded <= 0x10ffffu) {
                    code_point = decoded;
                    width = 4;
                }
            }
        }
        index += width;
        if (code_point <= 0xffffu) {
            utf16.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000u;
            utf16.push_back(static_cast<jchar>(0xd800u + (code_point >> 10u)));
            utf16.push_back(static_cast<jchar>(0xdc00u + (code_point & 0x3ffu)));
        }
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
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

struct ContractError final : std::runtime_error {
    std::string code;
    std::string field;
    bool unsupported;

    ContractError(std::string code_value,
                  std::string field_value,
                  std::string message,
                  bool unsupported_value = false)
            : std::runtime_error(std::move(message)),
              code(std::move(code_value)),
              field(std::move(field_value)),
              unsupported(unsupported_value) {}
};

[[noreturn]] void invalid_contract(const std::string &field, const std::string &message) {
    throw ContractError("IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID", field, message);
}

[[noreturn]] void unsupported_contract(const std::string &field, const std::string &message) {
    throw ContractError("IMAGE_NATIVE_EXECUTION_CONTRACT_UNSUPPORTED", field, message, true);
}

[[noreturn]] void execution_mismatch(const std::string &field,
                                     const std::string &message);

const json &required_field(const json &object, const std::string &key) {
    const auto found = object.find(key);
    if (found == object.end() || found->is_null()) {
        invalid_contract(key, "required field is missing");
    }
    return *found;
}

bool blank_text(const std::string &value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isspace(c) != 0;
    });
}

std::string required_string(const json &object,
                            const std::string &key,
                            bool allow_empty = false) {
    const json &value = required_field(object, key);
    if (!value.is_string()) invalid_contract(key, "field must be a string");
    const std::string result = value.get<std::string>();
    if (!allow_empty && (result.empty() || blank_text(result))) {
        invalid_contract(key, "field must be a non-blank string");
    }
    return result;
}

int64_t required_integer(const json &object, const std::string &key) {
    const json &value = required_field(object, key);
    try {
        if (value.is_number_unsigned()) {
            const uint64_t result = value.get<uint64_t>();
            if (result <= static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
                return static_cast<int64_t>(result);
            }
        } else if (value.is_number_integer()) {
            return value.get<int64_t>();
        }
    } catch (const std::exception &) {
        // Replaced by the stable field-specific error below.
    }
    invalid_contract(key, "field must be an exact signed 64-bit integer");
}

int required_int32(const json &object, const std::string &key) {
    const int64_t value = required_integer(object, key);
    if (value < std::numeric_limits<int>::min() ||
        value > std::numeric_limits<int>::max()) {
        invalid_contract(key, "field must fit a signed 32-bit integer");
    }
    return static_cast<int>(value);
}

double required_number(const json &object, const std::string &key) {
    const json &value = required_field(object, key);
    if (!value.is_number()) invalid_contract(key, "field must be numeric");
    const double result = value.get<double>();
    if (!std::isfinite(result)) invalid_contract(key, "field must be finite");
    return result;
}

bool required_boolean(const json &object, const std::string &key) {
    const json &value = required_field(object, key);
    if (!value.is_boolean()) invalid_contract(key, "field must be a boolean");
    return value.get<bool>();
}

std::string optional_string(const json &object,
                            const std::string &key,
                            const std::string &fallback) {
    const auto found = object.find(key);
    if (found == object.end()) return fallback;
    if (!found->is_string()) invalid_contract(key, "optional field must be a string when present");
    return found->get<std::string>();
}

bool optional_boolean(const json &object, const std::string &key, bool fallback) {
    const auto found = object.find(key);
    if (found == object.end()) return fallback;
    if (!found->is_boolean()) invalid_contract(key, "optional field must be a boolean when present");
    return found->get<bool>();
}

int optional_int32(const json &object, const std::string &key, int fallback) {
    const auto found = object.find(key);
    if (found == object.end()) return fallback;
    return required_int32(object, key);
}

double optional_number(const json &object, const std::string &key, double fallback) {
    const auto found = object.find(key);
    if (found == object.end()) return fallback;
    return required_number(object, key);
}

bool is_sha256(const std::string &value) {
    return value.size() == 64 && std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isxdigit(c) != 0;
    });
}

struct ContractLoraAdapter {
    std::string id;
    std::string name;
    std::string path;
    std::string sha256;
    uint64_t size_bytes = 0u;
    double multiplier = 1.0;
};

struct StableDiffusionExecutionContract {
    std::string profile_id;
    int profile_revision = 0;
    std::string model_fingerprint;
    std::string scheduler_wire;
    std::string prediction_wire;
    int steps = 0;
    int timetable_count = 0;
    int unet_execution_count = 0;
    double cfg_scale = 0.0;
    bool use_cfg = false;
    bool unconditional_branch = false;
    int token_count = 0;
    int tokenizer_max_length = 0;
    bool prompt_weighting_supported = false;
    double vae_scaling_factor = 0.0;
    int width = 0;
    int height = 0;
    int64_t seed = 0;
    std::string prompt;
    std::string negative_prompt;
    std::string family;
    int threads = 0;
    double distilled_guidance = 0.0;
    bool distilled_guidance_specified = false;
    double flow_shift = 0.0;
    bool flow_shift_specified = false;
    std::string requested_sample_method;
    sample_method_t sample_method = SAMPLE_METHOD_COUNT;
    scheduler_t native_scheduler = SCHEDULER_COUNT;
    std::string task_mode = "text_to_image";
    std::string input_image_path;
    std::string input_image_sha256;
    std::string mask_image_path;
    std::string mask_image_sha256;
    std::string control_image_path;
    std::string control_image_sha256;
    double strength = 1.0;
    double control_strength = 1.0;
    bool strength_specified = false;
    bool control_strength_specified = false;
    int clip_skip = -1;
    int batch_count = 1;
    std::string lora_root_path;
    std::vector<ContractLoraAdapter> loras;
    bool vae_tiling_enabled = false;
    int vae_tile_size = 0;
    double vae_tile_overlap = 0.0;
    int preview_interval = 0;
    std::string preview_mode = "none";
};

bool task_uses_input_image(const StableDiffusionExecutionContract &contract) {
    return contract.task_mode == "img2img" ||
           contract.task_mode == "inpaint" ||
           contract.task_mode == "edit";
}

bool task_uses_init_image(const StableDiffusionExecutionContract &contract) {
    return contract.task_mode == "img2img" || contract.task_mode == "inpaint";
}

bool task_uses_mask_image(const StableDiffusionExecutionContract &contract) {
    return contract.task_mode == "inpaint";
}

bool task_uses_control_image(const StableDiffusionExecutionContract &contract) {
    return contract.task_mode == "control";
}

int expected_denoising_step_count(const StableDiffusionExecutionContract &contract) {
    if (!task_uses_init_image(contract) || contract.strength >= 1.0) {
        return contract.steps;
    }
    const int encoded_steps = static_cast<int>(
            static_cast<double>(contract.steps) * contract.strength);
    return std::min(contract.steps, encoded_steps + 1);
}

void resolve_sampler_contract(StableDiffusionExecutionContract &contract) {
    const std::string &scheduler = contract.scheduler_wire;
    const std::string &method = contract.requested_sample_method;
    if (scheduler == "EULER" && method == "euler") {
        contract.sample_method = EULER_SAMPLE_METHOD;
        contract.native_scheduler = DISCRETE_SCHEDULER;
    } else if (scheduler == "EULER_A" && method == "euler_a") {
        contract.sample_method = EULER_A_SAMPLE_METHOD;
        contract.native_scheduler = DISCRETE_SCHEDULER;
    } else if (scheduler == "DPMPP_2M" &&
               (method == "dpmpp_2m" || method == "dpm++2m")) {
        contract.sample_method = DPMPP2M_SAMPLE_METHOD;
        contract.native_scheduler = DISCRETE_SCHEDULER;
    } else if (scheduler == "DDIM" &&
               (method == "ddim" || method == "ddim_trailing")) {
        contract.sample_method = DDIM_TRAILING_SAMPLE_METHOD;
        contract.native_scheduler = SIMPLE_SCHEDULER;
    } else if (scheduler == "LCM" && method == "lcm") {
        contract.sample_method = LCM_SAMPLE_METHOD;
        contract.native_scheduler = LCM_SCHEDULER;
    } else if (scheduler == "FLOW_MATCH" &&
               (method == "flow_match" || method == "euler")) {
        contract.sample_method = EULER_SAMPLE_METHOD;
        contract.native_scheduler = DISCRETE_SCHEDULER;
    } else if (scheduler == "PNDM_PLMS") {
        unsupported_contract(
                "scheduler",
                "stable-diffusion.cpp exposes no PNDM/PLMS sampler through its public image API");
    } else {
        unsupported_contract(
                "sampleMethod",
                "sampleMethod conflicts with scheduler or has no provable stable-diffusion.cpp mapping");
    }
}

StableDiffusionExecutionContract parse_execution_contract(const json &params) {
    if (!params.is_object()) invalid_contract("params", "native params must be a JSON object");
    StableDiffusionExecutionContract contract;
    contract.profile_id = required_string(params, "profileId");
    contract.profile_revision = required_int32(params, "profileRevision");
    if (contract.profile_revision <= 0) invalid_contract("profileRevision", "must be positive");
    contract.model_fingerprint = required_string(params, "modelFingerprint");
    if (!is_sha256(contract.model_fingerprint)) {
        invalid_contract("modelFingerprint", "must be a 64-character SHA-256 value");
    }
    if (required_string(params, "runtime") != "STABLE_DIFFUSION_CPP") {
        unsupported_contract("runtime", "stable-diffusion bridge requires runtime=STABLE_DIFFUSION_CPP");
    }

    contract.scheduler_wire = required_string(params, "scheduler");
    if (contract.scheduler_wire != "DPMPP_2M" &&
        contract.scheduler_wire != "EULER" &&
        contract.scheduler_wire != "EULER_A" &&
        contract.scheduler_wire != "DDIM" &&
        contract.scheduler_wire != "PNDM_PLMS" &&
        contract.scheduler_wire != "LCM" &&
        contract.scheduler_wire != "FLOW_MATCH") {
        invalid_contract("scheduler", "unknown scheduler enum value");
    }
    contract.prediction_wire = required_string(params, "predictionType");
    if (contract.prediction_wire == "SAMPLE") {
        unsupported_contract(
                "predictionType",
                "stable-diffusion.cpp exposes no SAMPLE prediction override for image generation");
    }
    if (contract.prediction_wire != "EPSILON" &&
        contract.prediction_wire != "V_PREDICTION" &&
        contract.prediction_wire != "FLOW") {
        invalid_contract("predictionType", "unknown predictionType enum value");
    }
    contract.steps = required_int32(params, "steps");
    contract.timetable_count = required_int32(params, "timetableCount");
    contract.unet_execution_count = required_int32(params, "unetExecutionCount");
    if (contract.steps < 1 || contract.steps > 100) {
        invalid_contract("steps", "must be in [1, 100]; native does not clamp it");
    }
    if (contract.timetable_count <= 0 || contract.timetable_count > contract.steps) {
        invalid_contract(
                "timetableCount",
                "must be positive and no greater than the configured sample steps");
    }
    const auto expected_timetable = params.find("expectedTimetableCount");
    if (expected_timetable != params.end() &&
        required_int32(params, "expectedTimetableCount") != contract.timetable_count) {
        invalid_contract("expectedTimetableCount", "conflicts with timetableCount");
    }

    contract.cfg_scale = required_number(params, "cfgScale");
    if (contract.cfg_scale < 0.0 || contract.cfg_scale > 30.0) {
        invalid_contract("cfgScale", "must be in [0, 30]");
    }
    contract.use_cfg = required_boolean(params, "useCfg");
    contract.unconditional_branch = required_boolean(params, "unconditionalBranch");
    if (contract.unconditional_branch != contract.use_cfg) {
        invalid_contract("unconditionalBranch", "must exactly match useCfg");
    }
    const bool runtime_uses_cfg = std::fabs(contract.cfg_scale - 1.0) > 1e-12;
    if (runtime_uses_cfg != contract.use_cfg) {
        unsupported_contract(
                "useCfg",
                "stable-diffusion.cpp executes an unconditional branch exactly when cfgScale differs from 1");
    }
    const int branches = contract.use_cfg ? 2 : 1;
    if (contract.timetable_count > std::numeric_limits<int>::max() / branches ||
        contract.unet_execution_count != contract.timetable_count * branches) {
        invalid_contract(
                "unetExecutionCount",
                "must equal timetableCount multiplied by the actual CFG branch count");
    }
    const auto expected_unet = params.find("expectedUnetExecutionCount");
    if (expected_unet != params.end() &&
        required_int32(params, "expectedUnetExecutionCount") != contract.unet_execution_count) {
        invalid_contract("expectedUnetExecutionCount", "conflicts with unetExecutionCount");
    }

    if (required_string(params, "tokenizerBackend") != "SDCPP_NATIVE") {
        unsupported_contract("tokenizerBackend", "stable-diffusion.cpp uses its native tokenizer");
    }
    contract.token_count = required_int32(params, "tokenCount");
    contract.tokenizer_max_length = required_int32(params, "tokenizerMaxLength");
    if (contract.tokenizer_max_length <= 0 || contract.tokenizer_max_length > 4096) {
        invalid_contract("tokenizerMaxLength", "must be in [1, 4096]");
    }
    if (contract.token_count <= 0 ||
        contract.tokenizer_max_length > std::numeric_limits<int>::max() / 2) {
        invalid_contract(
                "tokenCount",
                "must be positive and fit the native conditioning count range");
    }
    const int active_branch_count = contract.use_cfg ? 2 : 1;
    const int resolved_conditioning_capacity =
            contract.tokenizer_max_length * active_branch_count;
    if (contract.token_count != resolved_conditioning_capacity) {
        invalid_contract(
                "tokenCount",
                "must describe exactly the resolved conditioning branches");
    }
    contract.prompt_weighting_supported = required_boolean(
            params,
            "promptWeightingSupported");
    if (required_string(params, "embeddingDiskDataType") != "RUNTIME_NATIVE") {
        unsupported_contract(
                "embeddingDiskDataType",
                "stable-diffusion.cpp owns conditioning storage and exposes it as RUNTIME_NATIVE");
    }
    if (required_string(params, "vaeScalingLocation") != "RUNTIME_NATIVE") {
        unsupported_contract(
                "vaeScalingLocation",
                "stable-diffusion.cpp applies VAE scaling internally");
    }
    contract.vae_scaling_factor = required_number(params, "vaeScalingFactor");
    if (std::fabs(contract.vae_scaling_factor - 1.0) > 1e-12) {
        unsupported_contract(
                "vaeScalingFactor",
                "RUNTIME_NATIVE scaling requires the external contract factor to be 1");
    }

    contract.width = required_int32(params, "width");
    contract.height = required_int32(params, "height");
    if (contract.width < 64 || contract.height < 64 ||
        contract.width > 8192 || contract.height > 8192 ||
        contract.width % 8 != 0 || contract.height % 8 != 0) {
        invalid_contract(
                "width,height",
                "dimensions must be multiples of 8 in [64, 8192]; native does not clamp them");
    }
    contract.seed = required_integer(params, "seed");
    if (contract.seed < 0) invalid_contract("seed", "must be non-negative so the executed seed is exact");
    if (required_string(params, "graphName") != "runtime-native") {
        unsupported_contract("graphName", "stable-diffusion.cpp exposes graphName=runtime-native");
    }
    if (required_boolean(params, "fallback")) {
        unsupported_contract("fallback", "strict native execution cannot claim a fallback path");
    }

    contract.prompt = required_string(params, "prompt");
    contract.negative_prompt = required_string(params, "negativePrompt", true);
    if (!contract.use_cfg && !contract.negative_prompt.empty()) {
        unsupported_contract(
                "negativePrompt",
                "negativePrompt cannot affect pixels when useCfg=false");
    }
    contract.family = required_string(params, "family");
    contract.threads = required_int32(params, "threads");
    if (contract.threads < 1 || contract.threads > 64) {
        invalid_contract("threads", "must be in [1, 64]; native does not clamp it");
    }
    contract.distilled_guidance = required_number(params, "distilledGuidance");
    contract.distilled_guidance_specified = optional_boolean(
            params,
            "distilledGuidanceSpecified",
            false);
    if (contract.distilled_guidance < 0.0 || contract.distilled_guidance > 30.0) {
        invalid_contract("distilledGuidance", "must be in [0, 30]");
    }
    contract.flow_shift = required_number(params, "flowShift");
    contract.flow_shift_specified = optional_boolean(
            params,
            "flowShiftSpecified",
            false);
    if ((contract.flow_shift < 0.0 &&
         std::fabs(contract.flow_shift + 1.0) > 1e-12) ||
        contract.flow_shift > 100.0) {
        invalid_contract("flowShift", "must be -1 (model default) or a value in [0, 100]");
    }
    contract.requested_sample_method = required_string(params, "sampleMethod");
    if (required_string(params, "backendMode") != "cpu") {
        unsupported_contract("backendMode", "Android stable-diffusion.cpp is built for backendMode=cpu");
    }
    contract.task_mode = optional_string(params, "taskMode", "text_to_image");
    if (contract.task_mode != "text_to_image" &&
        contract.task_mode != "img2img" &&
        contract.task_mode != "inpaint" &&
        contract.task_mode != "control" &&
        contract.task_mode != "edit") {
        invalid_contract("taskMode", "unknown local image task mode");
    }
    contract.input_image_path = optional_string(params, "inputImagePath", "");
    contract.input_image_sha256 = lower_copy(optional_string(params, "inputImageSha256", ""));
    contract.mask_image_path = optional_string(params, "maskImagePath", "");
    contract.mask_image_sha256 = lower_copy(optional_string(params, "maskImageSha256", ""));
    contract.control_image_path = optional_string(params, "controlImagePath", "");
    contract.control_image_sha256 = lower_copy(optional_string(params, "controlImageSha256", ""));
    contract.strength_specified = params.find("strength") != params.end();
    contract.control_strength_specified = params.find("controlStrength") != params.end();
    contract.strength = optional_number(params, "strength", 1.0);
    contract.control_strength = optional_number(params, "controlStrength", 1.0);
    contract.clip_skip = optional_int32(params, "clipSkip", -1);
    const auto batch_count = params.find("batchCount");
    const auto n = params.find("n");
    contract.batch_count = batch_count != params.end()
            ? required_int32(params, "batchCount")
            : (n != params.end() ? required_int32(params, "n") : 1);
    if (batch_count != params.end() && n != params.end() &&
        required_int32(params, "n") != contract.batch_count) {
        invalid_contract("n", "must exactly match batchCount");
    }
    if (contract.strength <= 0.0 || contract.strength > 1.0) {
        invalid_contract("strength", "must be in (0, 1]");
    }
    if (contract.control_strength < 0.0 || contract.control_strength > 2.0) {
        invalid_contract("controlStrength", "must be in [0, 2]");
    }
    if (contract.clip_skip < -1 || contract.clip_skip > 32) {
        invalid_contract("clipSkip", "must be -1 (model default) or in [0, 32]");
    }
    if (contract.batch_count < 1 || contract.batch_count > kMaxBatchCount) {
        invalid_contract("batchCount", "must be in [1, 8]");
    }
    if (contract.seed > std::numeric_limits<int64_t>::max() - (contract.batch_count - 1)) {
        invalid_contract("seed", "seed plus batch index overflows signed 64-bit range");
    }
    const int lora_count = required_int32(params, "loraCount");
    if (lora_count < 0 || static_cast<size_t>(lora_count) > kMaxLoraCount) {
        invalid_contract("loraCount", "must be in [0, 8]");
    }
    contract.lora_root_path = required_string(params, "loraRootPath", true);
    const json &loras = required_field(params, "loras");
    if (!loras.is_array() || loras.size() != static_cast<size_t>(lora_count)) {
        invalid_contract("loras", "must be an array whose length exactly matches loraCount");
    }
    if ((lora_count == 0) != contract.lora_root_path.empty()) {
        invalid_contract(
                "loraRootPath",
                lora_count == 0
                ? "must be empty when no LoRA is requested"
                : "is required when LoRA adapters are requested");
    }
    std::unordered_set<std::string> lora_ids;
    std::unordered_set<std::string> lora_paths;
    contract.loras.reserve(static_cast<size_t>(lora_count));
    for (size_t index = 0; index < loras.size(); ++index) {
        const json &item = loras[index];
        if (!item.is_object()) invalid_contract("loras", "every LoRA item must be an object");
        ContractLoraAdapter adapter;
        adapter.id = required_string(item, "id");
        adapter.name = required_string(item, "name");
        adapter.path = required_string(item, "path");
        adapter.sha256 = lower_copy(required_string(item, "sha256"));
        const int64_t size_bytes = required_integer(item, "sizeBytes");
        adapter.multiplier = required_number(item, "multiplier");
        if (!is_sha256(adapter.sha256)) {
            invalid_contract("loras.sha256", "must be a 64-character SHA-256 value");
        }
        if (size_bytes < 16 || static_cast<uint64_t>(size_bytes) > kMaxLoraBytes) {
            invalid_contract("loras.sizeBytes", "must describe a bounded non-empty LoRA file");
        }
        if (adapter.multiplier < -4.0 || adapter.multiplier > 4.0 ||
            std::fabs(adapter.multiplier) < 0.01) {
            invalid_contract("loras.multiplier", "must be in [-4, -0.01] or [0.01, 4]");
        }
        if (!lora_ids.insert(adapter.id).second || !lora_paths.insert(adapter.path).second) {
            invalid_contract("loras", "LoRA ids and paths must be unique per request");
        }
        adapter.size_bytes = static_cast<uint64_t>(size_bytes);
        contract.loras.push_back(std::move(adapter));
    }
    const auto tiling = params.find("vaeTiling");
    if (tiling != params.end()) {
        if (!tiling->is_object()) invalid_contract("vaeTiling", "must be an object when present");
        contract.vae_tiling_enabled = true;
        contract.vae_tile_size = required_int32(*tiling, "tileSize");
        contract.vae_tile_overlap = required_number(*tiling, "overlap");
        if (contract.vae_tile_size < 64 || contract.vae_tile_size > 4096 ||
            contract.vae_tile_size % 8 != 0) {
            invalid_contract("vaeTiling.tileSize", "must be a multiple of 8 in [64, 4096]");
        }
        if (contract.vae_tile_overlap < 0.0 || contract.vae_tile_overlap > 0.5) {
            invalid_contract("vaeTiling.overlap", "must be a finite ratio in [0, 0.5]");
        }
    }
    const auto preview = params.find("preview");
    if (preview != params.end()) {
        if (!preview->is_object()) invalid_contract("preview", "must be an object when present");
        contract.preview_interval = optional_int32(*preview, "interval", 0);
        contract.preview_mode = optional_string(*preview, "mode", "none");
        if (contract.preview_interval < 1 || contract.preview_interval > 100) {
            invalid_contract("preview.interval", "must be in [1, 100]");
        }
        if (contract.preview_mode != "projection" &&
            contract.preview_mode != "tae" &&
            contract.preview_mode != "vae") {
            invalid_contract("preview.mode", "must be projection, tae, or vae");
        }
    }
    if (task_uses_input_image(contract) != !contract.input_image_path.empty()) {
        invalid_contract(
                "inputImagePath",
                task_uses_input_image(contract)
                ? "the selected task requires an input image"
                : "text/control generation must not carry an unused input image");
    }
    if (task_uses_mask_image(contract) != !contract.mask_image_path.empty()) {
        invalid_contract(
                "maskImagePath",
                task_uses_mask_image(contract)
                ? "the selected task requires a mask image"
                : "the selected task must not carry an unused mask image");
    }
    if (task_uses_control_image(contract) != !contract.control_image_path.empty()) {
        invalid_contract(
                "controlImagePath",
                task_uses_control_image(contract)
                ? "control generation requires a control image"
                : "the selected task must not carry an unused control image");
    }
    const auto validate_input_digest = [&](const std::string &path,
                                           const std::string &digest,
                                           const std::string &field) {
        if (path.empty() != digest.empty()) {
            invalid_contract(field, path.empty()
                    ? "must be omitted when its image path is omitted"
                    : "is required for every prepared image input");
        }
        if (!digest.empty() && !is_sha256(digest)) {
            invalid_contract(field, "must be a 64-character SHA-256 value");
        }
    };
    validate_input_digest(
            contract.input_image_path, contract.input_image_sha256, "inputImageSha256");
    validate_input_digest(
            contract.mask_image_path, contract.mask_image_sha256, "maskImageSha256");
    validate_input_digest(
            contract.control_image_path, contract.control_image_sha256, "controlImageSha256");
    if (contract.task_mode == "text_to_image" &&
        (contract.strength_specified || contract.control_strength_specified)) {
        invalid_contract(
                "strength,controlStrength",
                "text_to_image must not carry controls that cannot affect pixels");
    }
    if ((contract.task_mode == "img2img" || contract.task_mode == "inpaint" ||
         contract.task_mode == "edit") && contract.control_strength_specified) {
        invalid_contract(
                "controlStrength",
                "controlStrength is valid only when a control image is executed");
    }
    if (contract.task_mode == "control" && contract.strength_specified) {
        invalid_contract(
                "strength",
                "strength is valid only when an init image is executed");
    }
    if (contract.task_mode == "edit") {
        unsupported_contract(
                "taskMode",
                "reference-image edit capability is not exposed by the public native context API; refusing to claim pixel consumption");
    }
    const int expected_timetable_count = expected_denoising_step_count(contract);
    if (contract.timetable_count != expected_timetable_count) {
        execution_mismatch(
                "timetableCount",
                "resolved timetableCount must match the exact native img2img/inpaint strength schedule");
    }
    resolve_sampler_contract(contract);
    return contract;
}

json contract_failure_json(const ContractError &error) {
    return json({
            {"ok", false},
            {"errorCode", error.code},
            {"field", error.field},
            {"unsupported", error.unsupported},
            {"error", error.what()}
    });
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
        g_observed_prediction.store(EDM_V_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in v-prediction mode")) {
        g_observed_prediction.store(V_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in eps-prediction mode")) {
        g_observed_prediction.store(EPS_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in flux2 flow mode")) {
        g_observed_prediction.store(FLUX2_FLOW_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in flux flow mode")) {
        g_observed_prediction.store(FLUX_FLOW_PRED, std::memory_order_relaxed);
    } else if (contains(lower, "running in flow mode") ||
               contains(lower, "running in ltxav flow mode")) {
        g_observed_prediction.store(FLOW_PRED, std::memory_order_relaxed);
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
        g_last_sd_error = safe_sd_runtime_message(level, text);
        __android_log_print(
                level == SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                "MCA-SD",
                "%s",
                g_last_sd_error.c_str());
    }
}

void sd_progress_callback(int step, int steps, float time, void *) {
    const bool generation_invoked =
            (g_generation_stage_mask.load(std::memory_order_relaxed) &
             kStageGenerationInvoked) != 0u;
    if (step > 0 && generation_invoked) {
        g_ui_progress_callback_count.fetch_add(1, std::memory_order_relaxed);
        g_ui_progress_reported_steps.store(steps, std::memory_order_relaxed);
        int observed = g_ui_progress_max_step.load(std::memory_order_relaxed);
        while (step > observed &&
               !g_ui_progress_max_step.compare_exchange_weak(
                       observed,
                       step,
                       std::memory_order_relaxed)) {
        }
        mark_generation_stage(kStageSamplingObserved);
    }
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
    std::string control_net;
    std::string selection_mode = "compatibility_inference";
    bool selection_fallback = true;
    std::string bundle_root;
    std::string primary_slot;
    std::string primary_path;
    std::string text_encoder_path;
    std::string text_encoder_slot;
    std::string tokenizer_path;
    std::string manifest_path;
};

bool likely_split_diffusion_family(const std::string &family, const std::string &path) {
    const std::string key = lower_copy(family + " " + path);
    return contains(key, "z_image") ||
           contains(key, "z-image") ||
           contains(key, "qwen_image") ||
           contains(key, "qwen-image") ||
           contains(key, "glm_image") ||
           contains(key, "glm-image") ||
           contains(key, "dreamlite") ||
           contains(key, "flux") ||
           contains(key, "wan") ||
           contains(key, "chroma") ||
           contains(key, "ernie") ||
           contains(key, "longcat");
}

ComponentPaths infer_components(const std::string &model_path, const std::string &bundle_root, const std::string &family) {
    ComponentPaths paths;
    paths.selection_mode = "compatibility_inference";
    paths.selection_fallback = true;
    paths.bundle_root = bundle_root;
    if (likely_split_diffusion_family(family, model_path)) {
        paths.diffusion = model_path;
        paths.primary_slot = "diffusion";
    } else {
        paths.model = model_path;
        paths.primary_slot = "model";
    }
    paths.primary_path = model_path;

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
        } else if (contains(lower, "controlnet") || contains(lower, "control_net")) {
            paths.control_net = path;
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
    if (!paths.llm.empty()) {
        paths.text_encoder_path = paths.llm;
        paths.text_encoder_slot = "llm";
    } else if (!paths.t5xxl.empty()) {
        paths.text_encoder_path = paths.t5xxl;
        paths.text_encoder_slot = "t5xxl";
    } else if (!paths.clip_l.empty()) {
        paths.text_encoder_path = paths.clip_l;
        paths.text_encoder_slot = "clip_l";
    }
    return paths;
}

bool canonical_existing_file_within_root(const std::string &raw_path,
                                         const std::string &raw_root,
                                         std::string &canonical_path,
                                         std::string &error) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr || !dir_exists(root_buffer)) {
        error = "component bundle root does not exist";
        return false;
    }
    if (raw_path.empty() || realpath(raw_path.c_str(), path_buffer) == nullptr || !file_exists(path_buffer)) {
        error = "component file does not exist: " + raw_path;
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path.size() <= root.size() || path.compare(0, root.size(), root) != 0 || path[root.size()] != '/') {
        error = "component path escapes its bundle root: " + raw_path;
        return false;
    }
    canonical_path = path;
    return true;
}

bool validate_lora_file(const std::string &raw_root,
                        const ContractLoraAdapter &adapter,
                        std::string &canonical_path,
                        std::string &error) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr ||
        !dir_exists(root_buffer)) {
        error = "LoRA root does not exist";
        return false;
    }
    struct stat link_stat {};
    if (adapter.path.empty() || lstat(adapter.path.c_str(), &link_stat) != 0 ||
        !S_ISREG(link_stat.st_mode)) {
        error = "LoRA must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size < 0 ||
        static_cast<uint64_t>(link_stat.st_size) != adapter.size_bytes) {
        error = "LoRA size differs from the worker-verified contract";
        return false;
    }
    if (realpath(adapter.path.c_str(), path_buffer) == nullptr || !file_exists(path_buffer)) {
        error = "LoRA file does not exist";
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path != adapter.path) {
        error = "LoRA path must already be canonical";
        return false;
    }
    const size_t separator = path.find_last_of('/');
    if (separator == std::string::npos || path.substr(0, separator) != root) {
        error = "LoRA must be a direct child of the app-owned LoRA root";
        return false;
    }
    const std::string lower = lower_copy(path);
    if (!ends_with(lower, ".safetensors") && !ends_with(lower, ".ckpt")) {
        error = "LoRA file extension is unsupported";
        return false;
    }
    canonical_path = path;
    return true;
}

bool validate_upscaler_file(const std::string &raw_root,
                            const std::string &raw_path,
                            uint64_t expected_size,
                            std::string &canonical_path,
                            std::string &error) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr ||
        !dir_exists(root_buffer)) {
        error = "upscaler root does not exist";
        return false;
    }
    struct stat link_stat {};
    if (raw_path.empty() || lstat(raw_path.c_str(), &link_stat) != 0 ||
        !S_ISREG(link_stat.st_mode)) {
        error = "upscaler model must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size < 0 || static_cast<uint64_t>(link_stat.st_size) != expected_size) {
        error = "upscaler model size differs from the worker-verified contract";
        return false;
    }
    if (realpath(raw_path.c_str(), path_buffer) == nullptr || !file_exists(path_buffer)) {
        error = "upscaler model does not exist";
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path != raw_path) {
        error = "upscaler path must already be canonical";
        return false;
    }
    const size_t separator = path.find_last_of('/');
    if (separator == std::string::npos || path.substr(0, separator) != root) {
        error = "upscaler model must be a direct child of the app-owned upscaler root";
        return false;
    }
    const std::string lower = lower_copy(path);
    if (!ends_with(lower, ".pth") && !ends_with(lower, ".safetensors") &&
        !ends_with(lower, ".ckpt") && !ends_with(lower, ".bin")) {
        error = "upscaler model extension is unsupported";
        return false;
    }
    canonical_path = path;
    return true;
}

uint32_t rotate_right(uint32_t value, uint32_t amount) {
    return (value >> amount) | (value << (32u - amount));
}

class Sha256Accumulator {
public:
    void update(const uint8_t *bytes, size_t size) {
        if (size == 0u) {
            return;
        }
        total_bytes_ += static_cast<uint64_t>(size);
        if (buffer_size_ > 0u) {
            const size_t consumed = std::min(size, buffer_.size() - buffer_size_);
            std::memcpy(buffer_.data() + buffer_size_, bytes, consumed);
            buffer_size_ += consumed;
            bytes += consumed;
            size -= consumed;
            if (buffer_size_ == buffer_.size()) {
                transform(buffer_.data());
                buffer_size_ = 0u;
            }
        }
        while (size >= buffer_.size()) {
            transform(bytes);
            bytes += buffer_.size();
            size -= buffer_.size();
        }
        if (size > 0u) {
            std::memcpy(buffer_.data(), bytes, size);
            buffer_size_ = size;
        }
    }

    std::string finish_hex() {
        const uint64_t bit_count = total_bytes_ * 8u;
        buffer_[buffer_size_++] = 0x80u;
        if (buffer_size_ > 56u) {
            std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_), buffer_.end(), 0u);
            transform(buffer_.data());
            buffer_size_ = 0u;
        }
        std::fill(
                buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_),
                buffer_.begin() + 56,
                0u);
        for (size_t index = 0; index < 8u; ++index) {
            buffer_[63u - index] = static_cast<uint8_t>(bit_count >> (index * 8u));
        }
        transform(buffer_.data());

        static constexpr char hex[] = "0123456789abcdef";
        std::string result(64, '0');
        size_t output = 0;
        for (uint32_t word: state_) {
            for (int shift = 28; shift >= 0; shift -= 4) {
                result[output++] = hex[(word >> static_cast<uint32_t>(shift)) & 0x0fu];
            }
        }
        return result;
    }

private:
    void transform(const uint8_t *block) {
        static constexpr std::array<uint32_t, 64> constants = {{
                0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
                0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
                0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
                0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
                0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
                0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
                0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
                0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
                0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
                0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
                0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
                0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
                0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
                0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
                0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
                0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
        }};
        std::array<uint32_t, 64> words = {};
        for (size_t i = 0; i < 16; ++i) {
            const size_t offset = i * 4;
            words[i] = (static_cast<uint32_t>(block[offset]) << 24u) |
                       (static_cast<uint32_t>(block[offset + 1]) << 16u) |
                       (static_cast<uint32_t>(block[offset + 2]) << 8u) |
                       static_cast<uint32_t>(block[offset + 3]);
        }
        for (size_t i = 16; i < words.size(); ++i) {
            const uint32_t s0 = rotate_right(words[i - 15], 7u) ^
                                rotate_right(words[i - 15], 18u) ^
                                (words[i - 15] >> 3u);
            const uint32_t s1 = rotate_right(words[i - 2], 17u) ^
                                rotate_right(words[i - 2], 19u) ^
                                (words[i - 2] >> 10u);
            words[i] = words[i - 16] + s0 + words[i - 7] + s1;
        }
        uint32_t a = state_[0];
        uint32_t b = state_[1];
        uint32_t c = state_[2];
        uint32_t d = state_[3];
        uint32_t e = state_[4];
        uint32_t f = state_[5];
        uint32_t g = state_[6];
        uint32_t h = state_[7];
        for (size_t i = 0; i < words.size(); ++i) {
            const uint32_t sigma1 = rotate_right(e, 6u) ^
                                    rotate_right(e, 11u) ^
                                    rotate_right(e, 25u);
            const uint32_t choose = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + sigma1 + choose + constants[i] + words[i];
            const uint32_t sigma0 = rotate_right(a, 2u) ^
                                    rotate_right(a, 13u) ^
                                    rotate_right(a, 22u);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = sigma0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
    }

    std::array<uint32_t, 8> state_ = {{
            0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
            0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    }};
    std::array<uint8_t, 64> buffer_ = {};
    size_t buffer_size_ = 0u;
    uint64_t total_bytes_ = 0u;
};

std::string sha256_hex(const std::vector<uint8_t> &bytes) {
    Sha256Accumulator accumulator;
    if (!bytes.empty()) {
        accumulator.update(bytes.data(), bytes.size());
    }
    return accumulator.finish_hex();
}

bool sha256_implementation_ready() {
    static const bool ready = [] {
        const std::vector<uint8_t> empty;
        const std::vector<uint8_t> abc = {'a', 'b', 'c'};
        return sha256_hex(empty) ==
                       "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" &&
               sha256_hex(abc) ==
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    }();
    return ready;
}

struct RegularFileIdentity {
    uint64_t device = 0;
    uint64_t inode = 0;
    uint64_t size = 0;
    int64_t modification_time = 0;
    int64_t status_change_time = 0;
};

RegularFileIdentity regular_file_identity(const struct stat &value) {
    return RegularFileIdentity{
            static_cast<uint64_t>(value.st_dev),
            static_cast<uint64_t>(value.st_ino),
            value.st_size >= 0 ? static_cast<uint64_t>(value.st_size) : 0u,
            static_cast<int64_t>(value.st_mtime),
            static_cast<int64_t>(value.st_ctime)};
}

bool same_regular_file_identity(const RegularFileIdentity &left,
                                const RegularFileIdentity &right) {
    return left.device == right.device &&
           left.inode == right.inode &&
           left.size == right.size &&
           left.modification_time == right.modification_time &&
           left.status_change_time == right.status_change_time;
}

class ScopedFileDescriptor {
public:
    explicit ScopedFileDescriptor(int descriptor) : descriptor_(descriptor) {}

    ~ScopedFileDescriptor() {
        if (descriptor_ >= 0) {
            close(descriptor_);
        }
    }

    int get() const { return descriptor_; }

private:
    int descriptor_;
};

bool read_regular_file_identity(const std::string &path,
                                uint64_t expected_size,
                                RegularFileIdentity &identity,
                                std::string &error) {
    struct stat value {};
    if (lstat(path.c_str(), &value) != 0 || !S_ISREG(value.st_mode)) {
        error = "upscaler model is no longer a regular non-symlink file";
        return false;
    }
    identity = regular_file_identity(value);
    if (identity.size != expected_size) {
        error = "upscaler model size changed during native execution";
        return false;
    }
    return true;
}

bool sha256_regular_file(const std::string &path,
                         uint64_t expected_size,
                         std::string &digest,
                         RegularFileIdentity &identity,
                         std::string &error) {
    if (!sha256_implementation_ready()) {
        error = "native SHA-256 self-test failed";
        return false;
    }
    ScopedFileDescriptor file(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (file.get() < 0) {
        error = "upscaler model could not be opened for native hashing";
        return false;
    }
    struct stat before {};
    if (fstat(file.get(), &before) != 0 || !S_ISREG(before.st_mode)) {
        error = "upscaler model descriptor is not a regular file";
        return false;
    }
    identity = regular_file_identity(before);
    if (identity.size != expected_size) {
        error = "upscaler model size changed before native hashing";
        return false;
    }

    Sha256Accumulator accumulator;
    std::array<uint8_t, 64u * 1024u> buffer = {};
    uint64_t bytes_read = 0u;
    while (true) {
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            error = "cancelled";
            return false;
        }
        const ssize_t count = read(file.get(), buffer.data(), buffer.size());
        if (count == 0) {
            break;
        }
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            error = "upscaler model could not be read for native hashing";
            return false;
        }
        const size_t chunk = static_cast<size_t>(count);
        accumulator.update(buffer.data(), chunk);
        bytes_read += static_cast<uint64_t>(chunk);
        if (bytes_read > expected_size) {
            error = "upscaler model grew during native hashing";
            return false;
        }
    }

    struct stat after {};
    if (fstat(file.get(), &after) != 0 ||
        !same_regular_file_identity(identity, regular_file_identity(after)) ||
        bytes_read != expected_size) {
        error = "upscaler model changed while native was hashing it";
        return false;
    }
    digest = accumulator.finish_hex();
    return true;
}

preview_t preview_mode_from_contract(const std::string &mode) {
    if (mode == "projection") return PREVIEW_PROJ;
    if (mode == "tae") return PREVIEW_TAE;
    if (mode == "vae") return PREVIEW_VAE;
    return PREVIEW_NONE;
}

struct PreviewPublicationContext {
    std::string output_path;
    std::string mode;
    int interval = 0;
    int total_steps = 0;
    int publication_count = 0;
    int last_step = 0;
    uint64_t last_revision = 0;
};

void sd_preview_callback(int step,
                         int frame_count,
                         sd_image_t *frames,
                         bool is_noisy,
                         void *data) {
    auto *context = static_cast<PreviewPublicationContext *>(data);
    if (context == nullptr || context->interval <= 0 || frame_count <= 0 || frames == nullptr) {
        return;
    }
    const int normalized_step = step < 0 ? -step : step;
    if (normalized_step <= 0 ||
        (normalized_step % context->interval != 0 && normalized_step != context->total_steps)) {
        return;
    }
    const sd_image_t &frame = frames[0];
    if (frame.data == nullptr || frame.width == 0u || frame.height == 0u ||
        frame.channel == 0u || frame.channel > 4u ||
        frame.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        frame.height > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        frame.width > static_cast<uint32_t>(
            std::numeric_limits<int>::max() / static_cast<int>(frame.channel))) {
        return;
    }

    const uint64_t revision =
            g_preview_revision.fetch_add(1u, std::memory_order_relaxed) + 1u;
    const std::string target = context->output_path +
            ".preview-" + std::to_string(revision % 2u) + ".png";
    const std::string partial = target + ".part";
    std::remove(partial.c_str());
    const int stride = static_cast<int>(frame.width * frame.channel);
    if (stbi_write_png(
            partial.c_str(),
            static_cast<int>(frame.width),
            static_cast<int>(frame.height),
            static_cast<int>(frame.channel),
            frame.data,
            stride) == 0) {
        std::remove(partial.c_str());
        return;
    }
    if (std::rename(partial.c_str(), target.c_str()) != 0) {
        std::remove(partial.c_str());
        return;
    }

    context->publication_count += 1;
    context->last_step = normalized_step;
    context->last_revision = revision;
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress.preview_path = target;
    g_progress.preview_mime_type = "image/png";
    g_progress.preview_mode = context->mode;
    g_progress.preview_step = normalized_step;
    g_progress.preview_revision = revision;
    g_progress.preview_width = static_cast<int>(frame.width);
    g_progress.preview_height = static_cast<int>(frame.height);
    g_progress.preview_frame_count = frame_count;
    g_progress.preview_noisy = is_noisy;
    g_progress.updated_ms = now_ms();
}

uint16_t read_tiff_u16(const uint8_t *data, bool little_endian) {
    if (little_endian) {
        return static_cast<uint16_t>(data[0]) |
               static_cast<uint16_t>(static_cast<uint16_t>(data[1]) << 8u);
    }
    return static_cast<uint16_t>(static_cast<uint16_t>(data[0]) << 8u) |
           static_cast<uint16_t>(data[1]);
}

uint32_t read_tiff_u32(const uint8_t *data, bool little_endian) {
    if (little_endian) {
        return static_cast<uint32_t>(data[0]) |
               (static_cast<uint32_t>(data[1]) << 8u) |
               (static_cast<uint32_t>(data[2]) << 16u) |
               (static_cast<uint32_t>(data[3]) << 24u);
    }
    return (static_cast<uint32_t>(data[0]) << 24u) |
           (static_cast<uint32_t>(data[1]) << 16u) |
           (static_cast<uint32_t>(data[2]) << 8u) |
           static_cast<uint32_t>(data[3]);
}

int jpeg_exif_orientation(const std::vector<uint8_t> &bytes) {
    if (bytes.size() < 4u || bytes[0] != 0xffu || bytes[1] != 0xd8u) return 1;
    size_t cursor = 2u;
    while (cursor + 4u <= bytes.size()) {
        if (bytes[cursor] != 0xffu) break;
        while (cursor < bytes.size() && bytes[cursor] == 0xffu) ++cursor;
        if (cursor >= bytes.size()) break;
        const uint8_t marker = bytes[cursor++];
        if (marker == 0xd9u || marker == 0xdau) break;
        if (marker == 0x01u || (marker >= 0xd0u && marker <= 0xd7u)) continue;
        if (cursor + 2u > bytes.size()) break;
        const size_t segment_length =
                (static_cast<size_t>(bytes[cursor]) << 8u) | bytes[cursor + 1u];
        if (segment_length < 2u || cursor + segment_length > bytes.size()) break;
        const size_t payload = cursor + 2u;
        const size_t payload_size = segment_length - 2u;
        cursor += segment_length;
        if (marker != 0xe1u || payload_size < 14u ||
            std::memcmp(bytes.data() + payload, "Exif\0\0", 6u) != 0) {
            continue;
        }
        const size_t tiff = payload + 6u;
        const bool little_endian = bytes[tiff] == 'I' && bytes[tiff + 1u] == 'I';
        const bool big_endian = bytes[tiff] == 'M' && bytes[tiff + 1u] == 'M';
        if ((!little_endian && !big_endian) ||
            read_tiff_u16(bytes.data() + tiff + 2u, little_endian) != 42u) {
            continue;
        }
        const uint32_t ifd_offset = read_tiff_u32(bytes.data() + tiff + 4u, little_endian);
        if (ifd_offset > payload_size - 8u || tiff + ifd_offset + 2u > payload + payload_size) {
            continue;
        }
        const size_t ifd = tiff + ifd_offset;
        const uint16_t count = read_tiff_u16(bytes.data() + ifd, little_endian);
        for (uint16_t index = 0; index < count; ++index) {
            const size_t entry = ifd + 2u + static_cast<size_t>(index) * 12u;
            if (entry + 12u > payload + payload_size) break;
            if (read_tiff_u16(bytes.data() + entry, little_endian) != 0x0112u ||
                read_tiff_u16(bytes.data() + entry + 2u, little_endian) != 3u ||
                read_tiff_u32(bytes.data() + entry + 4u, little_endian) < 1u) {
                continue;
            }
            const int orientation = static_cast<int>(
                    read_tiff_u16(bytes.data() + entry + 8u, little_endian));
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
    }
    return 1;
}

bool orient_pixels(std::vector<uint8_t> &pixels,
                   uint32_t &width,
                   uint32_t &height,
                   uint32_t channels,
                   int orientation) {
    if (orientation <= 1 || orientation > 8) return true;
    const uint32_t output_width = orientation >= 5 ? height : width;
    const uint32_t output_height = orientation >= 5 ? width : height;
    std::vector<uint8_t> oriented(pixels.size());
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            uint32_t output_x = x;
            uint32_t output_y = y;
            switch (orientation) {
                case 2: output_x = width - 1u - x; break;
                case 3:
                    output_x = width - 1u - x;
                    output_y = height - 1u - y;
                    break;
                case 4: output_y = height - 1u - y; break;
                case 5:
                    output_x = y;
                    output_y = x;
                    break;
                case 6:
                    output_x = height - 1u - y;
                    output_y = x;
                    break;
                case 7:
                    output_x = height - 1u - y;
                    output_y = width - 1u - x;
                    break;
                case 8:
                    output_x = y;
                    output_y = width - 1u - x;
                    break;
                default: break;
            }
            const size_t source =
                    (static_cast<size_t>(y) * width + x) * channels;
            const size_t target =
                    (static_cast<size_t>(output_y) * output_width + output_x) * channels;
            std::memcpy(oriented.data() + target, pixels.data() + source, channels);
        }
    }
    pixels.swap(oriented);
    width = output_width;
    height = output_height;
    return true;
}

struct LoadedInputImage {
    std::string canonical_path;
    std::string sha256;
    uint32_t source_width = 0;
    uint32_t source_height = 0;
    uint32_t source_channels = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t channels = 0;
    int exif_orientation = 0;
    std::vector<uint8_t> pixels;

    sd_image_t view() {
        return sd_image_t{width, height, channels, pixels.empty() ? nullptr : pixels.data()};
    }
};

bool load_canonical_input_image(const std::string &raw_path,
                                const std::string &expected_sha256,
                                int requested_channels,
                                LoadedInputImage &image,
                                std::string &error,
                                uint32_t maximum_side = kMaxInputImageSide,
                                uint64_t maximum_pixels = kMaxInputImagePixels,
                                const char *size_limit_error =
                                        "worker input image exceeds the 8192-pixel side or 64-megapixel limit") {
    if (raw_path.empty() || raw_path.front() != '/') {
        error = "worker input image path must be an absolute canonical path";
        return false;
    }
    if (requested_channels != 1 && requested_channels != 3) {
        error = "worker input image channel contract must request one or three channels";
        return false;
    }
    struct stat link_stat {};
    if (lstat(raw_path.c_str(), &link_stat) != 0 || !S_ISREG(link_stat.st_mode)) {
        error = "worker input image must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size <= 0 ||
        static_cast<uint64_t>(link_stat.st_size) > kMaxInputImageBytes) {
        error = "worker input image must be between 1 byte and 32 MiB";
        return false;
    }
    char path_buffer[PATH_MAX] = {};
    if (realpath(raw_path.c_str(), path_buffer) == nullptr || !file_exists(path_buffer)) {
        error = "worker input image file does not exist: " + raw_path;
        return false;
    }
    image.canonical_path = path_buffer;
    if (image.canonical_path != raw_path) {
        error = "worker input image path must already be canonical and contain no symlink traversal";
        return false;
    }
    FILE *file = std::fopen(image.canonical_path.c_str(), "rb");
    if (file == nullptr) {
        error = "worker input image could not be opened";
        return false;
    }
    std::vector<uint8_t> encoded(static_cast<size_t>(link_stat.st_size));
    const size_t read = std::fread(encoded.data(), 1u, encoded.size(), file);
    const int trailing = std::fgetc(file);
    std::fclose(file);
    if (read != encoded.size() || trailing != EOF) {
        error = "worker input image changed while native was reading it";
        return false;
    }
    if (!sha256_implementation_ready()) {
        error = "native SHA-256 self-test failed";
        return false;
    }
    image.sha256 = sha256_hex(encoded);
    if (image.sha256 != lower_copy(expected_sha256)) {
        error = "worker input image SHA-256 does not match the prepared input contract";
        return false;
    }
    int width = 0;
    int height = 0;
    int source_channels = 0;
    if (stbi_info_from_memory(
            encoded.data(),
            static_cast<int>(encoded.size()),
            &width,
            &height,
            &source_channels) == 0 || width <= 0 || height <= 0) {
        error = "failed to inspect worker input image";
        return false;
    }
    const uint64_t pixel_count = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (static_cast<uint32_t>(width) > maximum_side ||
        static_cast<uint32_t>(height) > maximum_side ||
        pixel_count > maximum_pixels) {
        error = size_limit_error;
        return false;
    }
    const int inspected_width = width;
    const int inspected_height = height;
    stbi_uc *decoded = stbi_load_from_memory(
            encoded.data(),
            static_cast<int>(encoded.size()),
            &width,
            &height,
            &source_channels,
            requested_channels);
    if (decoded == nullptr || width <= 0 || height <= 0) {
        if (decoded != nullptr) stbi_image_free(decoded);
        error = "failed to decode worker input image";
        return false;
    }
    if (width != inspected_width || height != inspected_height) {
        stbi_image_free(decoded);
        error = "worker input image dimensions changed between inspection and decode";
        return false;
    }
    const size_t byte_count = static_cast<size_t>(pixel_count) *
                              static_cast<size_t>(requested_channels);
    image.source_width = static_cast<uint32_t>(width);
    image.source_height = static_cast<uint32_t>(height);
    image.source_channels = static_cast<uint32_t>(source_channels);
    image.width = image.source_width;
    image.height = image.source_height;
    image.channels = static_cast<uint32_t>(requested_channels);
    image.pixels.assign(decoded, decoded + byte_count);
    stbi_image_free(decoded);
    image.exif_orientation = jpeg_exif_orientation(encoded);
    return orient_pixels(
            image.pixels,
            image.width,
            image.height,
            image.channels,
            image.exif_orientation);
}

bool validate_canonical_output_path(const std::string &raw_path,
                                    std::string &validated_path,
                                    std::string &error) {
    if (raw_path.empty() || raw_path.front() != '/' || raw_path.size() >= PATH_MAX) {
        error = "output path must be an absolute path shorter than PATH_MAX";
        return false;
    }
    const size_t slash = raw_path.find_last_of('/');
    if (slash == std::string::npos || slash + 1u >= raw_path.size()) {
        error = "output path must name a file in an existing directory";
        return false;
    }
    const std::string raw_parent = slash == 0u ? "/" : raw_path.substr(0, slash);
    const std::string file_name = raw_path.substr(slash + 1u);
    if (file_name == "." || file_name == ".." ||
        !ends_with(lower_copy(file_name), ".png")) {
        error = "output path must name a .png file";
        return false;
    }
    char parent_buffer[PATH_MAX] = {};
    if (realpath(raw_parent.c_str(), parent_buffer) == nullptr || !dir_exists(parent_buffer)) {
        error = "output directory does not exist";
        return false;
    }
    const std::string canonical_parent(parent_buffer);
    const std::string canonical_target = canonical_parent == "/"
            ? canonical_parent + file_name
            : canonical_parent + "/" + file_name;
    if (canonical_target != raw_path) {
        error = "output path must already be canonical and contain no symlink traversal";
        return false;
    }
    struct stat target_stat {};
    errno = 0;
    if (lstat(canonical_target.c_str(), &target_stat) == 0) {
        error = "output path must not already exist";
        return false;
    } else if (errno != ENOENT) {
        error = "output path could not be inspected";
        return false;
    }
    validated_path = canonical_target;
    return true;
}

bool build_output_paths(const std::string &raw_output_path,
                        int batch_count,
                        std::vector<std::string> &paths,
                        std::string &error) {
    std::string first;
    if (!validate_canonical_output_path(raw_output_path, first, error)) return false;
    const size_t extension = first.find_last_of('.');
    const std::string stem = first.substr(0, extension);
    const std::string suffix = first.substr(extension);
    paths.clear();
    paths.reserve(static_cast<size_t>(batch_count));
    for (int index = 0; index < batch_count; ++index) {
        const std::string candidate = index == 0
                ? first
                : stem + "-" + std::to_string(index + 1) + suffix;
        std::string validated;
        if (!validate_canonical_output_path(candidate, validated, error)) {
            paths.clear();
            return false;
        }
        paths.push_back(std::move(validated));
    }
    return true;
}

void free_generated_images(sd_image_t *images, int count) {
    if (images == nullptr) return;
    for (int index = 0; index < count; ++index) {
        free(images[index].data);
        images[index].data = nullptr;
    }
    free(images);
}

void remove_output_files(const std::vector<std::string> &paths) {
    for (const std::string &path: paths) std::remove(path.c_str());
}

std::string component_selection_json(const ComponentPaths &paths) {
    std::ostringstream out;
    out << "{\"mode\":\"" << escape_json(paths.selection_mode) << "\","
        << "\"fallback\":" << json_bool(paths.selection_fallback) << ","
        << "\"bundleRoot\":\"" << escape_json(paths.bundle_root) << "\","
        << "\"primarySlot\":\"" << escape_json(paths.primary_slot) << "\","
        << "\"primaryPath\":\"" << escape_json(paths.primary_path) << "\","
        << "\"vaePath\":\"" << escape_json(paths.vae) << "\","
        << "\"textEncoderPath\":\"" << escape_json(paths.text_encoder_path) << "\","
        << "\"textEncoderSlot\":\"" << escape_json(paths.text_encoder_slot) << "\","
        << "\"controlNetPath\":\"" << escape_json(paths.control_net) << "\","
        << "\"tokenizerPath\":\"" << escape_json(paths.tokenizer_path) << "\","
        << "\"manifestPath\":\"" << escape_json(paths.manifest_path) << "\"}";
    return out.str();
}

bool resolve_component_paths(const std::string &model_path,
                             const std::string &bundle_root,
                             const std::string &family,
                             const json &params,
                             ComponentPaths &paths,
                             std::string &error) {
    const std::string mode = optional_string(
            params,
            "componentSelectionMode",
            "compatibility_inference");
    if (mode == "compatibility_inference") {
        paths = infer_components(model_path, bundle_root, family);
        paths.selection_mode = mode;
        paths.selection_fallback = true;
        return true;
    }
    if (mode != "manifest_roles") {
        error = "unsupported component selection mode: " + mode;
        return false;
    }

    paths = ComponentPaths{};
    paths.selection_mode = mode;
    paths.selection_fallback = false;
    const std::string requested_root = optional_string(
            params,
            "componentBundleRoot",
            bundle_root);
    char requested_root_buffer[PATH_MAX] = {};
    char jni_root_buffer[PATH_MAX] = {};
    if (realpath(requested_root.c_str(), requested_root_buffer) == nullptr ||
        !dir_exists(requested_root_buffer)) {
        error = "explicit component bundle root does not exist";
        return false;
    }
    if (realpath(bundle_root.c_str(), jni_root_buffer) == nullptr ||
        std::string(requested_root_buffer) != std::string(jni_root_buffer)) {
        error = "explicit component bundle root does not match the JNI bundle root";
        return false;
    }
    paths.bundle_root = requested_root_buffer;
    paths.primary_slot = optional_string(params, "componentPrimarySlot", "");
    const std::string requested_primary = optional_string(params, "componentPrimaryPath", "");
    if (paths.primary_slot != "model" && paths.primary_slot != "diffusion") {
        error = "explicit primary component slot must be model or diffusion";
        return false;
    }
    if (!canonical_existing_file_within_root(
            requested_primary,
            paths.bundle_root,
            paths.primary_path,
            error)) {
        return false;
    }
    char model_path_buffer[PATH_MAX] = {};
    if (realpath(model_path.c_str(), model_path_buffer) == nullptr ||
        paths.primary_path != std::string(model_path_buffer)) {
        error = "explicit primary component does not match the JNI model path";
        return false;
    }
    const bool split_family = likely_split_diffusion_family(family, paths.primary_path);
    if ((split_family && paths.primary_slot != "diffusion") ||
        (!split_family && paths.primary_slot != "model")) {
        error = "explicit primary component slot does not match the model family";
        return false;
    }
    if (paths.primary_slot == "model") {
        paths.model = paths.primary_path;
    } else {
        paths.diffusion = paths.primary_path;
    }

    const bool require_vae = split_family ||
                             optional_boolean(params, "componentRequireVae", false);
    const bool require_text_encoder = split_family || optional_boolean(
            params,
            "componentRequireTextEncoder",
            false);
    const bool require_tokenizer = optional_boolean(
            params,
            "componentRequireTokenizer",
            false);
    const std::string requested_vae = optional_string(params, "componentVaePath", "");
    if (!requested_vae.empty()) {
        if (!canonical_existing_file_within_root(
                requested_vae,
                paths.bundle_root,
                paths.vae,
                error)) {
            return false;
        }
    } else if (require_vae) {
        error = "explicit component selection is missing required VAE";
        return false;
    }

    const std::string requested_text_encoder = optional_string(
            params,
            "componentTextEncoderPath",
            "");
    paths.text_encoder_slot = optional_string(
            params,
            "componentTextEncoderSlot",
            "");
    if (!requested_text_encoder.empty()) {
        if (!canonical_existing_file_within_root(
                requested_text_encoder,
                paths.bundle_root,
                paths.text_encoder_path,
                error)) {
            return false;
        }
        if (paths.text_encoder_slot == "llm") {
            paths.llm = paths.text_encoder_path;
        } else if (paths.text_encoder_slot == "t5xxl") {
            paths.t5xxl = paths.text_encoder_path;
        } else if (paths.text_encoder_slot == "clip_l") {
            paths.clip_l = paths.text_encoder_path;
        } else if (paths.text_encoder_slot == "clip_g") {
            paths.clip_g = paths.text_encoder_path;
        } else {
            error = "unsupported explicit text encoder slot: " + paths.text_encoder_slot;
            return false;
        }
    } else if (require_text_encoder) {
        error = "explicit component selection is missing required text encoder";
        return false;
    } else if (!paths.text_encoder_slot.empty()) {
        error = "explicit text encoder slot was provided without a text encoder path";
        return false;
    }

    const std::string requested_tokenizer = optional_string(
            params,
            "componentTokenizerPath",
            "");
    if (!requested_tokenizer.empty()) {
        if (!canonical_existing_file_within_root(
                requested_tokenizer,
                paths.bundle_root,
                paths.tokenizer_path,
                error)) {
            return false;
        }
    } else if (require_tokenizer) {
        error = "explicit component selection is missing required tokenizer";
        return false;
    }

    const std::string requested_control_net = optional_string(
            params,
            "componentControlNetPath",
            "");
    if (!requested_control_net.empty() && !canonical_existing_file_within_root(
            requested_control_net,
            paths.bundle_root,
            paths.control_net,
            error)) {
        return false;
    }

    const std::string requested_manifest = optional_string(
            params,
            "componentManifestPath",
            "");
    if (requested_manifest.empty() || !canonical_existing_file_within_root(
            requested_manifest,
            paths.bundle_root,
            paths.manifest_path,
            error)) {
        if (requested_manifest.empty()) error = "explicit component selection is missing manifest path";
        return false;
    }
    return true;
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
        << paths.control_net << "|"
        << paths.selection_mode << "|"
        << paths.tokenizer_path << "|"
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
    params.control_net_path = paths.control_net.empty() ? nullptr : paths.control_net.c_str();
    params.vae_decode_only = false;
    params.free_params_immediately = false;
    params.n_threads = threads;
    params.enable_mmap = true;
    params.rng_type = CPU_RNG;
    params.sampler_rng_type = CPU_RNG;
    params.lora_apply_mode = LORA_APPLY_AT_RUNTIME;
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

std::string observed_prediction_wire(int prediction) {
    switch (prediction) {
        case EPS_PRED: return "EPSILON";
        case V_PRED:
        case EDM_V_PRED: return "V_PREDICTION";
        case FLOW_PRED:
        case FLUX_FLOW_PRED:
        case FLUX2_FLOW_PRED: return "FLOW";
        default:
            unsupported_contract(
                    "predictionType",
                    "stable-diffusion.cpp did not expose an observed prediction mode");
    }
}

std::string observed_prediction_mode(int prediction) {
    if (prediction < 0 || prediction >= PREDICTION_COUNT) return "unknown";
    return sd_prediction_name(static_cast<prediction_t>(prediction));
}

[[noreturn]] void execution_mismatch(const std::string &field,
                                     const std::string &message) {
    throw ContractError("EXECUTION_CONTRACT_MISMATCH", field, message);
}

json runtime_failure(const std::string &code, const std::string &message) {
    return json({
            {"ok", false},
            {"errorCode", code},
            {"error", message}
    });
}

json native_effective_json(const StableDiffusionExecutionContract &contract,
                           const std::string &prediction_wire,
                           int actual_steps,
                           int actual_timetable_count,
                           int actual_unet_execution_count,
                           double actual_cfg_scale,
                           bool actual_use_cfg,
                           int actual_token_count,
                           bool actual_prompt_weighting_applied,
                           int actual_positive_weighted_token_count,
                           int actual_negative_weighted_token_count,
                           const std::string &actual_prompt_weight_fingerprint,
                           int actual_width,
                           int actual_height) {
    return json({
            {"profileId", contract.profile_id},
            {"profileRevision", contract.profile_revision},
            {"modelFingerprint", lower_copy(contract.model_fingerprint)},
            {"runtime", "STABLE_DIFFUSION_CPP"},
            {"scheduler", contract.scheduler_wire},
            {"predictionType", prediction_wire},
            {"steps", actual_steps},
            {"timetableCount", actual_timetable_count},
            {"unetExecutionCount", actual_unet_execution_count},
            {"cfgScale", actual_cfg_scale},
            {"useCfg", actual_use_cfg},
            {"unconditionalBranch", actual_use_cfg},
            {"tokenizerBackend", "SDCPP_NATIVE"},
            {"tokenCount", actual_token_count},
            {"resolvedTokenCount", contract.token_count},
            {"tokenizerMaxLength", contract.tokenizer_max_length},
            {"promptWeightingSupported", contract.prompt_weighting_supported},
            {"promptWeightingApplied", actual_prompt_weighting_applied},
            {"positiveWeightedTokenCount", actual_positive_weighted_token_count},
            {"negativeWeightedTokenCount", actual_negative_weighted_token_count},
            {"promptWeightFingerprint", actual_prompt_weight_fingerprint},
            {"embeddingDiskDataType", "RUNTIME_NATIVE"},
            {"vaeScalingLocation", "RUNTIME_NATIVE"},
            {"vaeScalingFactor", 1.0},
            {"width", actual_width},
            {"height", actual_height},
            {"seed", contract.seed},
            {"graphName", "runtime-native"},
            {"fallback", false},
            {"taskMode", contract.task_mode},
            {"inputImagePath", ""},
            {"maskImagePath", ""},
            {"controlImagePath", ""},
            {"inputImageExecutionCount", 0},
            {"maskImageExecutionCount", 0},
            {"controlImageExecutionCount", 0},
            {"strength", contract.strength},
            {"controlStrength", contract.control_strength},
            {"clipSkip", contract.clip_skip},
            {"batchCount", contract.batch_count},
            {"vaeTiling", {
                    {"enabled", contract.vae_tiling_enabled},
                    {"tileSize", contract.vae_tile_size},
                    {"overlap", contract.vae_tile_overlap}
            }}
    });
}

std::string generate_impl(const std::string &model_path,
                          const std::string &bundle_root,
                          const std::string &params_json,
                          const std::string &output_path) {
    try {
        if (!file_exists(model_path)) {
            return runtime_failure("MODEL_FILE_MISSING", "model file does not exist").dump();
        }
        json params;
        try {
            params = json::parse(params_json);
        } catch (const json::exception &error) {
            return contract_failure_json(ContractError(
                    "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                    "params",
                    std::string("native params are not valid JSON: ") + error.what())).dump();
        }
        const StableDiffusionExecutionContract contract = parse_execution_contract(params);
        std::vector<std::string> output_paths;
        std::string output_error;
        if (!build_output_paths(
                output_path, contract.batch_count, output_paths, output_error)) {
            return runtime_failure("OUTPUT_PATH_INVALID", output_error).dump();
        }
        mark_generation_stage(kStageContractValidated);

        LoadedInputImage input_image;
        LoadedInputImage mask_image;
        LoadedInputImage control_image;
        std::string input_error;
        if (!contract.input_image_path.empty() && !load_canonical_input_image(
                contract.input_image_path,
                contract.input_image_sha256,
                3,
                input_image,
                input_error)) {
            return runtime_failure("INPUT_IMAGE_INVALID", input_error).dump();
        }
        if (!input_image.pixels.empty()) mark_generation_stage(kStageInputImageDecoded);
        if (!contract.mask_image_path.empty() && !load_canonical_input_image(
                contract.mask_image_path,
                contract.mask_image_sha256,
                1,
                mask_image,
                input_error)) {
            return runtime_failure("MASK_IMAGE_INVALID", input_error).dump();
        }
        if (!mask_image.pixels.empty()) mark_generation_stage(kStageMaskImageDecoded);
        if (!contract.control_image_path.empty() && !load_canonical_input_image(
                contract.control_image_path,
                contract.control_image_sha256,
                3,
                control_image,
                input_error)) {
            return runtime_failure("CONTROL_IMAGE_INVALID", input_error).dump();
        }
        if (!control_image.pixels.empty()) mark_generation_stage(kStageControlImageDecoded);
        if (!input_image.pixels.empty() && !mask_image.pixels.empty() &&
            (input_image.width != mask_image.width || input_image.height != mask_image.height)) {
            return runtime_failure(
                    "MASK_IMAGE_SHAPE_MISMATCH",
                    "inpaint mask dimensions must match the input image dimensions").dump();
        }
        std::vector<std::string> canonical_lora_paths;
        canonical_lora_paths.reserve(contract.loras.size());
        for (const auto &adapter: contract.loras) {
            std::string canonical_path;
            std::string lora_error;
            if (!validate_lora_file(
                    contract.lora_root_path,
                    adapter,
                    canonical_path,
                    lora_error)) {
                return runtime_failure("LORA_FILE_INVALID", lora_error).dump();
            }
            canonical_lora_paths.push_back(std::move(canonical_path));
        }
        if (!canonical_lora_paths.empty()) mark_generation_stage(kStageLoraValidated);

        set_progress(
                "initializing",
                "checking model bundle",
                0,
                contract.steps,
                0.0f,
                contract.width,
                contract.height,
                contract.threads);
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        ComponentPaths paths;
        std::string component_error;
        if (!resolve_component_paths(
                model_path,
                bundle_root,
                contract.family,
                params,
                paths,
                component_error)) {
            set_progress(
                    "failed",
                    component_error,
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return json({
                    {"ok", false},
                    {"errorCode", "COMPONENT_SELECTION_INVALID"},
                    {"error", component_error},
                    {"componentSelection", {
                            {"mode", optional_string(
                                    params,
                                    "componentSelectionMode",
                                    "compatibility_inference")},
                            {"fallback", false}
                    }}
            }).dump();
        }
        if (task_uses_control_image(contract) && paths.control_net.empty()) {
            return runtime_failure(
                    "CONTROLNET_COMPONENT_MISSING",
                    "control generation requires a concrete ControlNet model component").dump();
        }
        const std::string selected_components_json = component_selection_json(paths);
        set_progress_component_selection(selected_components_json);
        const std::string ctx_key = make_context_key(paths, contract.threads);
        set_progress(
                "loading",
                "loading stable-diffusion.cpp context",
                0,
                contract.steps,
                0.0f,
                contract.width,
                contract.height,
                contract.threads);
        sd_ctx_t *ctx = ensure_context(paths, ctx_key, contract.threads);
        if (ctx == nullptr) {
            set_progress(
                    "failed",
                    g_last_error,
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return runtime_failure("NATIVE_CONTEXT_LOAD_FAILED", g_last_error).dump();
        }
        mark_generation_stage(kStageContextReady);

        const int observed_prediction = g_observed_prediction.load(std::memory_order_relaxed);
        const std::string prediction_wire = observed_prediction_wire(observed_prediction);
        if (prediction_wire != contract.prediction_wire) {
            execution_mismatch(
                    "predictionType",
                    "resolved predictionType=" + contract.prediction_wire +
                    ", observed stable-diffusion.cpp prediction mode=" + prediction_wire);
        }
        const bool flow_shift_capable = sd_ctx_uses_flow_shift(ctx);
        const bool dynamic_flow_shift = sd_ctx_uses_dynamic_flow_shift(ctx);
        const bool distilled_guidance_capable = sd_ctx_uses_distilled_guidance(ctx);
        if (!flow_shift_capable && contract.flow_shift >= 0.0) {
            unsupported_contract(
                    "flowShift",
                    "the loaded checkpoint has no flow denoiser; a numeric flow shift would be inert");
        }
        if (dynamic_flow_shift && contract.flow_shift >= 0.0) {
            unsupported_contract(
                    "flowShift",
                    "the loaded Flux2 checkpoint derives flow shift from step count and image sequence length");
        }
        if (contract.distilled_guidance_specified && !distilled_guidance_capable) {
            unsupported_contract(
                    "distilledGuidance",
                    "the loaded checkpoint has no distilled-guidance graph input");
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            set_progress(
                    "cancelled",
                    "cancelled before sampling",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        sd_img_gen_params_t gen;
        sd_img_gen_params_init(&gen);
        std::vector<sd_lora_t> native_loras;
        native_loras.reserve(contract.loras.size());
        for (size_t index = 0; index < contract.loras.size(); ++index) {
            sd_lora_t native_lora{};
            native_lora.is_high_noise = false;
            native_lora.multiplier = static_cast<float>(contract.loras[index].multiplier);
            native_lora.path = canonical_lora_paths[index].c_str();
            native_loras.push_back(native_lora);
        }
        gen.loras = native_loras.empty() ? nullptr : native_loras.data();
        gen.lora_count = static_cast<uint32_t>(native_loras.size());
        gen.prompt = contract.prompt.c_str();
        gen.negative_prompt = contract.use_cfg ? contract.negative_prompt.c_str() : nullptr;
        gen.width = contract.width;
        gen.height = contract.height;
        gen.seed = contract.seed;
        gen.batch_count = contract.batch_count;
        gen.clip_skip = contract.clip_skip;
        gen.strength = static_cast<float>(contract.strength);
        gen.control_strength = static_cast<float>(contract.control_strength);
        if (!input_image.pixels.empty()) gen.init_image = input_image.view();
        if (!mask_image.pixels.empty()) gen.mask_image = mask_image.view();
        if (!control_image.pixels.empty()) gen.control_image = control_image.view();
        gen.vae_tiling_params.enabled = contract.vae_tiling_enabled;
        gen.vae_tiling_params.tile_size_x = 0;
        gen.vae_tiling_params.tile_size_y = 0;
        gen.vae_tiling_params.target_overlap = contract.vae_tiling_enabled
                ? static_cast<float>(contract.vae_tile_overlap)
                : 0.5f;
        gen.vae_tiling_params.rel_size_x = contract.vae_tiling_enabled
                ? std::min(1.0f,
                           static_cast<float>(contract.vae_tile_size) /
                               static_cast<float>(contract.width))
                : 0.0f;
        gen.vae_tiling_params.rel_size_y = contract.vae_tiling_enabled
                ? std::min(1.0f,
                           static_cast<float>(contract.vae_tile_size) /
                               static_cast<float>(contract.height))
                : 0.0f;
        gen.sample_params.sample_steps = contract.steps;
        gen.sample_params.guidance.txt_cfg = static_cast<float>(contract.cfg_scale);
        gen.sample_params.guidance.distilled_guidance =
                static_cast<float>(contract.distilled_guidance);
        if (contract.flow_shift >= 0.0) {
            gen.sample_params.flow_shift = static_cast<float>(contract.flow_shift);
        }
        gen.sample_params.sample_method = contract.sample_method;
        gen.sample_params.scheduler = contract.native_scheduler;
        const bool input_image_wired = task_uses_init_image(contract) &&
                gen.init_image.data != nullptr && gen.init_image.width > 0u && gen.init_image.height > 0u;
        const bool mask_image_wired = task_uses_mask_image(contract) &&
                gen.mask_image.data != nullptr && gen.mask_image.width > 0u && gen.mask_image.height > 0u;
        const bool control_image_wired = task_uses_control_image(contract) &&
                gen.control_image.data != nullptr && gen.control_image.width > 0u &&
                gen.control_image.height > 0u && !paths.control_net.empty();
        if (task_uses_init_image(contract) != input_image_wired) {
            execution_mismatch(
                    "inputImagePath",
                    "the prepared input image was not wired into native init-latent generation");
        }
        if (task_uses_mask_image(contract) != mask_image_wired) {
            execution_mismatch(
                    "maskImagePath",
                    "the prepared mask image was not wired into native denoise-mask generation");
        }
        if (task_uses_control_image(contract) != control_image_wired) {
            execution_mismatch(
                    "controlImagePath",
                    "the prepared control image was not wired into a concrete ControlNet execution");
        }

        const uint64_t sequence =
                g_generation_sequence.fetch_add(1, std::memory_order_relaxed) + 1u;
        g_active_generation_sequence.store(sequence, std::memory_order_relaxed);
        mark_generation_stage(kStageGenerationInvoked);
        set_progress(
                "preparing",
                "preparing image generation",
                0,
                contract.steps,
                0.0f,
                contract.width,
                contract.height,
                contract.threads);
        PreviewPublicationContext preview_context{
                output_path,
                contract.preview_mode,
                contract.preview_interval,
                contract.steps};
        const preview_t native_preview_mode = preview_mode_from_contract(contract.preview_mode);
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
        if (native_preview_mode != PREVIEW_NONE) {
            sd_set_preview_callback(
                    sd_preview_callback,
                    native_preview_mode,
                    contract.preview_interval,
                    true,
                    false,
                    &preview_context);
        }
        sd_image_t *images = nullptr;
        try {
            images = generate_image(ctx, &gen);
        } catch (...) {
            sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
            throw;
        }
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            free_generated_images(images, contract.batch_count);
            set_progress(
                    "cancelled",
                    "cancelled",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }
        bool complete_batch = images != nullptr;
        if (complete_batch) {
            for (int index = 0; index < contract.batch_count; ++index) {
                if (images[index].data == nullptr) {
                    complete_batch = false;
                    break;
                }
            }
        }
        if (!complete_batch) {
            free_generated_images(images, contract.batch_count);
            set_progress(
                    "failed",
                    "stable-diffusion.cpp returned no image",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return runtime_failure(
                    "NATIVE_GENERATION_FAILED",
                    "stable-diffusion.cpp returned an incomplete image batch").dump();
        }
        if (native_preview_mode != PREVIEW_NONE && preview_context.publication_count <= 0) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(
                    "preview",
                    "stable-diffusion.cpp did not publish a real preview frame for the requested mode");
        }
        mark_generation_stage(kStageImageReturned);

        const sd_image_t &first = images[0];
        const int actual_width = static_cast<int>(first.width);
        const int actual_height = static_cast<int>(first.height);
        const int actual_channels = static_cast<int>(first.channel);
        if (actual_width <= 0 || actual_height <= 0 ||
            actual_channels < 1 || actual_channels > 4) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(
                    "output",
                    "stable-diffusion.cpp returned an invalid output image shape");
        }
        for (int index = 1; index < contract.batch_count; ++index) {
            if (images[index].width != first.width ||
                images[index].height != first.height ||
                images[index].channel != first.channel) {
                free_generated_images(images, contract.batch_count);
                execution_mismatch(
                        "outputs",
                        "stable-diffusion.cpp returned inconsistent dimensions within the image batch");
            }
        }
        auto release_images_and_mismatch = [&](const std::string &field,
                                               const std::string &message) -> void {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(field, message);
        };
        auto release_images_and_lora_failure = [&](const std::string &code,
                                                   const std::string &message) -> std::string {
            free_generated_images(images, contract.batch_count);
            remove_output_files(output_paths);
            set_progress(
                    "failed",
                    message,
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return runtime_failure(code, message).dump();
        };

        sd_image_execution_evidence_t execution_evidence{};
        if (!sd_get_last_image_execution_evidence(ctx, &execution_evidence)) {
            release_images_and_mismatch(
                    "nativeExecutionEvidence",
                    "native image execution did not publish context-local completion evidence");
        }
        if (execution_evidence.version != SD_IMAGE_EXECUTION_EVIDENCE_VERSION ||
            execution_evidence.generation_completed == 0u) {
            release_images_and_mismatch(
                    "nativeExecutionEvidence",
                    "native image execution evidence is incomplete or has an unsupported version");
        }
        const uint32_t expected_lora_count = static_cast<uint32_t>(contract.loras.size());
        if (execution_evidence.lora_requested_count != expected_lora_count) {
            return release_images_and_lora_failure(
                    "LORA_NATIVE_REQUEST_MISMATCH",
                    "stable-diffusion.cpp did not receive the complete requested LoRA set");
        }
        if (execution_evidence.lora_loaded_count != expected_lora_count) {
            return release_images_and_lora_failure(
                    "LORA_NATIVE_LOAD_INCOMPLETE",
                    "stable-diffusion.cpp could not load every requested LoRA adapter");
        }
        if (execution_evidence.lora_applied_count != expected_lora_count) {
            return release_images_and_lora_failure(
                    "LORA_NATIVE_APPLY_INCOMPLETE",
                    "stable-diffusion.cpp did not apply every requested LoRA adapter to the executed graph");
        }
        if (expected_lora_count > 0u && execution_evidence.lora_applied_tensor_count == 0u) {
            return release_images_and_lora_failure(
                    "LORA_NATIVE_TENSOR_EVIDENCE_MISSING",
                    "stable-diffusion.cpp did not execute any tensor adapted by the requested LoRA set");
        }
        if (expected_lora_count == 0u && execution_evidence.lora_applied_tensor_count != 0u) {
            return release_images_and_lora_failure(
                    "LORA_NATIVE_UNEXPECTED_EVIDENCE",
                    "stable-diffusion.cpp reported applied LoRA tensors for a request without LoRA adapters");
        }
        const bool actual_flow_shift_applied =
                execution_evidence.flow_shift_applied != 0u;
        const bool actual_dynamic_flow_shift =
                execution_evidence.dynamic_flow_shift != 0u;
        const bool actual_distilled_guidance_applied =
                execution_evidence.distilled_guidance_applied != 0u;
        if (actual_flow_shift_applied != flow_shift_capable ||
            actual_dynamic_flow_shift != dynamic_flow_shift) {
            release_images_and_mismatch(
                    "flowShift",
                    "native flow-shift evidence does not match the loaded denoiser");
        }
        const double actual_flow_shift =
                static_cast<double>(execution_evidence.effective_flow_shift);
        if (actual_flow_shift_applied) {
            if (!std::isfinite(actual_flow_shift) || actual_flow_shift < 0.0) {
                release_images_and_mismatch(
                        "flowShift",
                        "native flow denoising did not publish a finite effective shift");
            }
            if (!dynamic_flow_shift && contract.flow_shift >= 0.0 &&
                std::fabs(actual_flow_shift - contract.flow_shift) > 1e-5) {
                release_images_and_mismatch(
                        "flowShift",
                        "native flow denoising did not execute the configured shift");
            }
        }
        if (actual_distilled_guidance_applied != distilled_guidance_capable) {
            release_images_and_mismatch(
                    "distilledGuidance",
                    "native distilled-guidance evidence does not match the loaded diffusion graph");
        }
        const double actual_distilled_guidance =
                static_cast<double>(execution_evidence.effective_distilled_guidance);
        if (actual_distilled_guidance_applied &&
            (!std::isfinite(actual_distilled_guidance) ||
             std::fabs(actual_distilled_guidance - contract.distilled_guidance) > 1e-5)) {
            release_images_and_mismatch(
                    "distilledGuidance",
                    "native diffusion execution did not consume the configured distilled guidance");
        }
        if (execution_evidence.conditioning_artifact_byte_count == 0u ||
            execution_evidence.conditioning_artifact_byte_count >
                    static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
            release_images_and_mismatch(
                    "promptWeightFingerprint",
                    "native conditioning evidence did not expose a bounded artifact");
        }
        std::vector<uint8_t> conditioning_artifact(
                static_cast<size_t>(execution_evidence.conditioning_artifact_byte_count));
        if (!sd_copy_last_image_conditioning_artifact(
                ctx,
                conditioning_artifact.data(),
                conditioning_artifact.size())) {
            release_images_and_mismatch(
                    "promptWeightFingerprint",
                    "native conditioning evidence artifact could not be copied exactly");
        }
        if (!sha256_implementation_ready()) {
            release_images_and_mismatch(
                    "promptWeightFingerprint",
                    "native SHA-256 self-check failed before conditioning evidence hashing");
        }
        const std::string actual_prompt_weight_fingerprint =
                sha256_hex(conditioning_artifact);

        auto evidence_int = [&](uint64_t value, const std::string &field) -> int {
            if (value > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
                release_images_and_mismatch(field, "native execution evidence exceeds int32 range");
            }
            return static_cast<int>(value);
        };
        auto evidence_per_image = [&](uint64_t value, const std::string &field) -> int {
            if (value % static_cast<uint64_t>(contract.batch_count) != 0u) {
                release_images_and_mismatch(
                        field,
                        "native execution evidence is not divisible by the completed batch count");
            }
            return evidence_int(
                    value / static_cast<uint64_t>(contract.batch_count),
                    field);
        };

        const int actual_timetable_count = evidence_per_image(
                execution_evidence.completed_sampling_step_count,
                "timetableCount");
        const int actual_sampling_pass_count = evidence_int(
                execution_evidence.sampling_pass_count,
                "samplingPassCount");
        const int actual_unet_execution_count = evidence_per_image(
                execution_evidence.diffusion_model_compute_count,
                "unetExecutionCount");
        const int actual_positive_execution_count = evidence_per_image(
                execution_evidence.positive_diffusion_model_compute_count,
                "positiveDiffusionExecutionCount");
        const int actual_negative_execution_count = evidence_per_image(
                execution_evidence.negative_diffusion_model_compute_count,
                "negativeDiffusionExecutionCount");
        const int actual_auxiliary_execution_count = evidence_per_image(
                execution_evidence.auxiliary_diffusion_model_compute_count,
                "auxiliaryDiffusionExecutionCount");
        const int actual_control_net_compute_attempt_count = evidence_per_image(
                execution_evidence.control_net_compute_attempt_count,
                "controlNetComputeAttemptCount");
        const int actual_control_net_compute_success_count = evidence_per_image(
                execution_evidence.control_net_compute_success_count,
                "controlNetComputeSuccessCount");
        const int actual_positive_control_net_compute_attempt_count = evidence_per_image(
                execution_evidence.positive_control_net_compute_attempt_count,
                "positiveControlNetComputeAttemptCount");
        const int actual_positive_control_net_compute_success_count = evidence_per_image(
                execution_evidence.positive_control_net_compute_success_count,
                "positiveControlNetComputeSuccessCount");
        const int actual_negative_control_net_compute_attempt_count = evidence_per_image(
                execution_evidence.negative_control_net_compute_attempt_count,
                "negativeControlNetComputeAttemptCount");
        const int actual_negative_control_net_compute_success_count = evidence_per_image(
                execution_evidence.negative_control_net_compute_success_count,
                "negativeControlNetComputeSuccessCount");
        const int actual_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.control_net_residual_consumption_count,
                "controlNetResidualConsumptionCount");
        const int actual_positive_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.positive_control_net_residual_consumption_count,
                "positiveControlNetResidualConsumptionCount");
        const int actual_negative_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.negative_control_net_residual_consumption_count,
                "negativeControlNetResidualConsumptionCount");
        const int actual_auxiliary_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.auxiliary_control_net_residual_consumption_count,
                "auxiliaryControlNetResidualConsumptionCount");
        const int actual_vae_encode_tiling_invocation_count = evidence_int(
                execution_evidence.vae_encode_tiling_invocation_count,
                "vaeEncodeTilingInvocationCount");
        const int actual_vae_encode_tiling_success_count = evidence_int(
                execution_evidence.vae_encode_tiling_success_count,
                "vaeEncodeTilingSuccessCount");
        const int actual_vae_decode_tiling_invocation_count = evidence_int(
                execution_evidence.vae_decode_tiling_invocation_count,
                "vaeDecodeTilingInvocationCount");
        const int actual_vae_decode_tiling_success_count = evidence_int(
                execution_evidence.vae_decode_tiling_success_count,
                "vaeDecodeTilingSuccessCount");
        const int actual_vae_encode_planned_tile_count = evidence_int(
                execution_evidence.vae_encode_planned_tile_count,
                "vaeEncodePlannedTileCount");
        const int actual_vae_decode_planned_tile_count = evidence_int(
                execution_evidence.vae_decode_planned_tile_count,
                "vaeDecodePlannedTileCount");
        const int actual_vae_encode_tile_compute_attempt_count = evidence_int(
                execution_evidence.vae_encode_tile_compute_attempt_count,
                "vaeEncodeTileComputeAttemptCount");
        const int actual_vae_encode_tile_compute_success_count = evidence_int(
                execution_evidence.vae_encode_tile_compute_success_count,
                "vaeEncodeTileComputeSuccessCount");
        const int actual_vae_decode_tile_compute_attempt_count = evidence_int(
                execution_evidence.vae_decode_tile_compute_attempt_count,
                "vaeDecodeTileComputeAttemptCount");
        const int actual_vae_decode_tile_compute_success_count = evidence_int(
                execution_evidence.vae_decode_tile_compute_success_count,
                "vaeDecodeTileComputeSuccessCount");
        const int actual_vae_encode_tile_size_x = evidence_int(
                execution_evidence.vae_encode_tile_size_x,
                "vaeEncodeTileSizeX");
        const int actual_vae_encode_tile_size_y = evidence_int(
                execution_evidence.vae_encode_tile_size_y,
                "vaeEncodeTileSizeY");
        const int actual_vae_decode_tile_size_x = evidence_int(
                execution_evidence.vae_decode_tile_size_x,
                "vaeDecodeTileSizeX");
        const int actual_vae_decode_tile_size_y = evidence_int(
                execution_evidence.vae_decode_tile_size_y,
                "vaeDecodeTileSizeY");
        const double actual_vae_encode_tile_overlap_x =
                static_cast<double>(execution_evidence.vae_encode_tile_overlap_x);
        const double actual_vae_encode_tile_overlap_y =
                static_cast<double>(execution_evidence.vae_encode_tile_overlap_y);
        const double actual_vae_decode_tile_overlap_x =
                static_cast<double>(execution_evidence.vae_decode_tile_overlap_x);
        const double actual_vae_decode_tile_overlap_y =
                static_cast<double>(execution_evidence.vae_decode_tile_overlap_y);
        const uint64_t classified_compute_count =
                execution_evidence.positive_diffusion_model_compute_count +
                execution_evidence.negative_diffusion_model_compute_count +
                execution_evidence.auxiliary_diffusion_model_compute_count;
        if (classified_compute_count != execution_evidence.diffusion_model_compute_count) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "physical diffusion computes were not completely classified by execution branch");
        }
        if (execution_evidence.control_net_compute_attempt_count !=
                    execution_evidence.positive_control_net_compute_attempt_count +
                    execution_evidence.negative_control_net_compute_attempt_count ||
            execution_evidence.control_net_compute_success_count !=
                    execution_evidence.positive_control_net_compute_success_count +
                    execution_evidence.negative_control_net_compute_success_count ||
            execution_evidence.control_net_residual_consumption_count !=
                    execution_evidence.positive_control_net_residual_consumption_count +
                    execution_evidence.negative_control_net_residual_consumption_count +
                    execution_evidence.auxiliary_control_net_residual_consumption_count) {
            release_images_and_mismatch(
                    "controlNetEvidence",
                    "ControlNet execution evidence was not completely classified by branch");
        }
        if (actual_sampling_pass_count != contract.batch_count) {
            release_images_and_mismatch(
                    "samplingPassCount",
                    "native image generation executed an unexpected number of sampling passes");
        }
        if (actual_auxiliary_execution_count != 0) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "strict image execution does not support auxiliary diffusion branches");
        }
        if (actual_positive_execution_count != actual_timetable_count) {
            release_images_and_mismatch(
                    "positiveDiffusionExecutionCount",
                    "positive conditioning did not physically execute once per completed sampling step");
        }

        const bool requested_use_cfg =
                std::fabs(static_cast<double>(gen.sample_params.guidance.txt_cfg) - 1.0) > 1e-6;
        const bool actual_use_cfg = actual_negative_execution_count > 0;
        const int actual_branches = actual_use_cfg ? 2 : 1;
        if (requested_use_cfg != actual_use_cfg) {
            release_images_and_mismatch(
                    "useCfg",
                    "actual negative diffusion execution does not match the configured CFG mode");
        }
        const bool actual_control_image_consumed =
                control_image_wired && actual_control_net_residual_consumption_count > 0;
        if (control_image_wired) {
            if (actual_control_net_compute_attempt_count != actual_unet_execution_count ||
                actual_control_net_compute_success_count != actual_control_net_compute_attempt_count ||
                actual_positive_control_net_compute_attempt_count != actual_positive_execution_count ||
                actual_positive_control_net_compute_success_count != actual_positive_execution_count ||
                actual_negative_control_net_compute_attempt_count != actual_negative_execution_count ||
                actual_negative_control_net_compute_success_count != actual_negative_execution_count ||
                actual_control_net_residual_consumption_count != actual_unet_execution_count ||
                actual_positive_control_net_residual_consumption_count != actual_positive_execution_count ||
                actual_negative_control_net_residual_consumption_count != actual_negative_execution_count ||
                actual_auxiliary_control_net_residual_consumption_count != 0) {
                release_images_and_mismatch(
                        "controlNetEvidence",
                        "ControlNet did not successfully compute and feed residuals into every physical diffusion branch");
            }
        } else if (actual_control_net_compute_attempt_count != 0 ||
                   actual_control_net_compute_success_count != 0 ||
                   actual_control_net_residual_consumption_count != 0 ||
                   actual_positive_control_net_compute_attempt_count != 0 ||
                   actual_positive_control_net_compute_success_count != 0 ||
                   actual_negative_control_net_compute_attempt_count != 0 ||
                   actual_negative_control_net_compute_success_count != 0 ||
                   actual_positive_control_net_residual_consumption_count != 0 ||
                   actual_negative_control_net_residual_consumption_count != 0 ||
                   actual_auxiliary_control_net_residual_consumption_count != 0) {
            release_images_and_mismatch(
                    "controlNetEvidence",
                    "native execution reported ControlNet work for a request without a control image");
        }
        const bool actual_vae_tiling_enabled =
                actual_vae_encode_tiling_invocation_count > 0 ||
                actual_vae_decode_tiling_invocation_count > 0;
        auto valid_vae_overlap = [](double value) {
            return std::isfinite(value) && value >= 0.0 && value <= 0.5;
        };
        auto validate_vae_tiling_phase = [&](const char *phase,
                                             int invocation_count,
                                             int success_count,
                                             int planned_tile_count,
                                             int tile_compute_attempt_count,
                                             int tile_compute_success_count,
                                             int tile_size_x,
                                             int tile_size_y,
                                             double overlap_x,
                                             double overlap_y) {
            if (invocation_count == 0) {
                if (success_count != 0 || planned_tile_count != 0 ||
                    tile_compute_attempt_count != 0 || tile_compute_success_count != 0 ||
                    tile_size_x != 0 || tile_size_y != 0 || overlap_x != 0.0 ||
                    overlap_y != 0.0) {
                    release_images_and_mismatch(
                            "vaeTiling",
                            std::string("native VAE ") + phase +
                                " evidence exists without a tiled invocation");
                }
                return;
            }
            if (success_count != invocation_count || planned_tile_count <= 0 ||
                tile_compute_attempt_count != planned_tile_count ||
                tile_compute_success_count != tile_compute_attempt_count ||
                tile_size_x <= 0 || tile_size_y <= 0 ||
                !valid_vae_overlap(overlap_x) || !valid_vae_overlap(overlap_y)) {
                release_images_and_mismatch(
                        "vaeTiling",
                        std::string("native VAE ") + phase +
                            " tiling did not complete its physical tile plan");
            }
        };
        validate_vae_tiling_phase(
                "encode",
                actual_vae_encode_tiling_invocation_count,
                actual_vae_encode_tiling_success_count,
                actual_vae_encode_planned_tile_count,
                actual_vae_encode_tile_compute_attempt_count,
                actual_vae_encode_tile_compute_success_count,
                actual_vae_encode_tile_size_x,
                actual_vae_encode_tile_size_y,
                actual_vae_encode_tile_overlap_x,
                actual_vae_encode_tile_overlap_y);
        validate_vae_tiling_phase(
                "decode",
                actual_vae_decode_tiling_invocation_count,
                actual_vae_decode_tiling_success_count,
                actual_vae_decode_planned_tile_count,
                actual_vae_decode_tile_compute_attempt_count,
                actual_vae_decode_tile_compute_success_count,
                actual_vae_decode_tile_size_x,
                actual_vae_decode_tile_size_y,
                actual_vae_decode_tile_overlap_x,
                actual_vae_decode_tile_overlap_y);
        if (contract.vae_tiling_enabled) {
            if (!actual_vae_tiling_enabled ||
                actual_vae_decode_tiling_invocation_count != contract.batch_count) {
                release_images_and_mismatch(
                        "vaeTiling",
                        "requested VAE tiling did not execute exactly once for every final image decode");
            }
            if (input_image_wired && actual_vae_encode_tiling_invocation_count <= 0) {
                release_images_and_mismatch(
                        "vaeTiling",
                        "requested VAE tiling did not execute while encoding the prepared input image");
            }
        } else if (actual_vae_tiling_enabled) {
            release_images_and_mismatch(
                    "vaeTiling",
                    "native VAE tiling executed even though the request disabled it");
        }
        if (execution_evidence.positive_conditioning_observed == 0u ||
            execution_evidence.positive_conditioning_token_count == 0u) {
            release_images_and_mismatch(
                    "tokenCount",
                    "positive conditioning did not publish actual tokenizer evidence");
        }
        if (actual_use_cfg) {
            if (execution_evidence.negative_conditioning_observed == 0u ||
                execution_evidence.negative_conditioning_token_count == 0u ||
                actual_negative_execution_count != actual_timetable_count) {
                release_images_and_mismatch(
                        "unconditionalBranch",
                        "CFG did not execute evidenced negative conditioning once per sampling step");
            }
        } else if (execution_evidence.negative_conditioning_observed != 0u ||
                   execution_evidence.negative_conditioning_token_count != 0u ||
                   actual_negative_execution_count != 0) {
            release_images_and_mismatch(
                    "unconditionalBranch",
                    "non-CFG generation unexpectedly prepared or executed negative conditioning");
        }

        const uint64_t total_conditioning_token_count =
                execution_evidence.positive_conditioning_token_count +
                execution_evidence.negative_conditioning_token_count;
        const int actual_token_count = evidence_int(
                total_conditioning_token_count,
                "tokenCount");
        const int actual_positive_weighted_token_count = evidence_int(
                execution_evidence.positive_non_unity_weight_count,
                "positiveWeightedTokenCount");
        const int actual_negative_weighted_token_count = evidence_int(
                execution_evidence.negative_non_unity_weight_count,
                "negativeWeightedTokenCount");
        const bool actual_prompt_weighting_applied =
                execution_evidence.prompt_weighting_applied != 0u;
        const bool weighted_count_observed =
                actual_positive_weighted_token_count > 0 ||
                actual_negative_weighted_token_count > 0;
        if (actual_prompt_weighting_applied != weighted_count_observed) {
            release_images_and_mismatch(
                    "promptWeightingApplied",
                    "native weighting flag conflicts with actual non-unity token weights");
        }
        if (execution_evidence.positive_non_unity_weight_count >
                    execution_evidence.positive_conditioning_token_count ||
            execution_evidence.negative_non_unity_weight_count >
                    execution_evidence.negative_conditioning_token_count) {
            release_images_and_mismatch(
                    "promptWeightingApplied",
                    "native non-unity weight counts exceed actual conditioning tokens");
        }
        if (actual_prompt_weighting_applied && !contract.prompt_weighting_supported) {
            release_images_and_mismatch(
                    "promptWeightingSupported",
                    "native prompt syntax applied non-unity weights while the resolved profile disables weighting");
        }
        if (contract.prompt_weighting_supported &&
            (execution_evidence.positive_prompt_weighting_capable == 0u ||
             (actual_use_cfg && execution_evidence.negative_prompt_weighting_capable == 0u))) {
            release_images_and_mismatch(
                    "promptWeightingSupported",
                    "the executed conditioner cannot prove token-level weighting for every active branch");
        }
        // The resolved value is the active-branch capacity, while the native
        // tokenizer may consume more slots for long prompts or a model-native
        // conditioner such as T5. The exact executed count is reported above
        // and is intentionally not replaced with the request capacity.

        const int64_t total_unet_execution_count = evidence_int(
                execution_evidence.diffusion_model_compute_count,
                "totalUnetExecutionCount");
        const int ui_progress_callback_count =
                g_ui_progress_callback_count.load(std::memory_order_relaxed);
        const int ui_progress_reported_steps =
                g_ui_progress_reported_steps.load(std::memory_order_relaxed);
        const int ui_progress_max_step =
                g_ui_progress_max_step.load(std::memory_order_relaxed);
        if (actual_width != contract.width || actual_height != contract.height) {
            release_images_and_mismatch(
                    "width,height",
                    "stable-diffusion.cpp aligned the requested dimensions; strict execution rejects implicit alignment");
        }
        if (actual_timetable_count != contract.timetable_count) {
            release_images_and_mismatch(
                    "timetableCount",
                    "actual completed native sampling steps differ from the resolved timetableCount");
        }
        if (actual_unet_execution_count != contract.unet_execution_count) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "actual physical diffusion model computes differ from the resolved contract");
        }

        const double actual_cfg_scale =
                static_cast<double>(gen.sample_params.guidance.txt_cfg);
        json native_effective = native_effective_json(
                contract,
                prediction_wire,
                gen.sample_params.sample_steps,
                actual_timetable_count,
                actual_unet_execution_count,
                actual_cfg_scale,
                actual_use_cfg,
                actual_token_count,
                actual_prompt_weighting_applied,
                actual_positive_weighted_token_count,
                actual_negative_weighted_token_count,
                actual_prompt_weight_fingerprint,
                actual_width,
                actual_height);
        native_effective["inputImagePath"] = input_image_wired
                ? input_image.canonical_path : "";
        native_effective["maskImagePath"] = mask_image_wired
                ? mask_image.canonical_path : "";
        native_effective["controlImagePath"] = control_image_wired
                ? control_image.canonical_path : "";
        native_effective["inputImageExecutionCount"] = input_image_wired ? 1 : 0;
        native_effective["maskImageExecutionCount"] = mask_image_wired ? 1 : 0;
        native_effective["controlImageExecutionCount"] = actual_control_image_consumed ? 1 : 0;
        native_effective["inputImageSha256"] = input_image.sha256;
        native_effective["maskImageSha256"] = mask_image.sha256;
        native_effective["controlImageSha256"] = control_image.sha256;
        native_effective["strength"] = input_image_wired
                ? static_cast<double>(gen.strength) : contract.strength;
        native_effective["controlStrength"] = control_image_wired
                ? static_cast<double>(gen.control_strength) : contract.control_strength;
        native_effective["strengthApplied"] = input_image_wired;
        native_effective["controlStrengthApplied"] = actual_control_image_consumed;
        native_effective["clipSkip"] = gen.clip_skip;
        native_effective["batchCount"] = gen.batch_count;
        native_effective["vaeTiling"] = {
                {"enabled", actual_vae_tiling_enabled},
                {"requestedTileSize", contract.vae_tiling_enabled ? contract.vae_tile_size : 0},
                {"requestedOverlap", contract.vae_tiling_enabled ? contract.vae_tile_overlap : 0.0},
                {"encode", {
                        {"invocationCount", actual_vae_encode_tiling_invocation_count},
                        {"successCount", actual_vae_encode_tiling_success_count},
                        {"plannedTileCount", actual_vae_encode_planned_tile_count},
                        {"tileComputeAttemptCount", actual_vae_encode_tile_compute_attempt_count},
                        {"tileComputeSuccessCount", actual_vae_encode_tile_compute_success_count},
                        {"tileSizeX", actual_vae_encode_tile_size_x},
                        {"tileSizeY", actual_vae_encode_tile_size_y},
                        {"overlapX", actual_vae_encode_tile_overlap_x},
                        {"overlapY", actual_vae_encode_tile_overlap_y}
                }},
                {"decode", {
                        {"invocationCount", actual_vae_decode_tiling_invocation_count},
                        {"successCount", actual_vae_decode_tiling_success_count},
                        {"plannedTileCount", actual_vae_decode_planned_tile_count},
                        {"tileComputeAttemptCount", actual_vae_decode_tile_compute_attempt_count},
                        {"tileComputeSuccessCount", actual_vae_decode_tile_compute_success_count},
                        {"tileSizeX", actual_vae_decode_tile_size_x},
                        {"tileSizeY", actual_vae_decode_tile_size_y},
                        {"overlapX", actual_vae_decode_tile_overlap_x},
                        {"overlapY", actual_vae_decode_tile_overlap_y}
                }}
        };
        native_effective["outputCount"] = contract.batch_count;
        native_effective["n"] = contract.batch_count;
        native_effective["totalUnetExecutionCount"] = total_unet_execution_count;
        native_effective["samplingPassCount"] = actual_sampling_pass_count;
        native_effective["positiveDiffusionModelComputeCount"] =
                actual_positive_execution_count;
        native_effective["negativeDiffusionModelComputeCount"] =
                actual_negative_execution_count;
        native_effective["auxiliaryDiffusionModelComputeCount"] =
                actual_auxiliary_execution_count;
        native_effective["positiveConditioningTokenCount"] =
                execution_evidence.positive_conditioning_token_count;
        native_effective["negativeConditioningTokenCount"] =
                execution_evidence.negative_conditioning_token_count;
        native_effective["conditioningArtifactSha256"] =
                actual_prompt_weight_fingerprint;
        native_effective["requestedDistilledGuidance"] = contract.distilled_guidance;
        native_effective["distilledGuidanceSpecified"] =
                contract.distilled_guidance_specified;
        native_effective["distilledGuidanceApplied"] =
                actual_distilled_guidance_applied;
        native_effective["distilledGuidance"] = actual_distilled_guidance_applied
                ? json(actual_distilled_guidance)
                : json(nullptr);
        native_effective["requestedFlowShift"] = contract.flow_shift;
        native_effective["flowShiftSpecified"] = contract.flow_shift_specified;
        native_effective["flowShiftApplied"] = actual_flow_shift_applied;
        native_effective["dynamicFlowShift"] = actual_dynamic_flow_shift;
        native_effective["flowShift"] = actual_flow_shift_applied
                ? json(actual_flow_shift)
                : json(nullptr);
        native_effective["imageInputConsumption"] = {
                {"input", input_image_wired ? "init_latent" : "none"},
                {"mask", mask_image_wired ? "denoise_mask" : "none"},
                {"control", actual_control_image_consumed ? "controlnet_residual" : "none"}
        };
        native_effective["controlNetEvidence"] = {
                {"computeAttemptCount", actual_control_net_compute_attempt_count},
                {"computeSuccessCount", actual_control_net_compute_success_count},
                {"positiveComputeAttemptCount", actual_positive_control_net_compute_attempt_count},
                {"positiveComputeSuccessCount", actual_positive_control_net_compute_success_count},
                {"negativeComputeAttemptCount", actual_negative_control_net_compute_attempt_count},
                {"negativeComputeSuccessCount", actual_negative_control_net_compute_success_count},
                {"residualConsumptionCount", actual_control_net_residual_consumption_count},
                {"positiveResidualConsumptionCount", actual_positive_control_net_residual_consumption_count},
                {"negativeResidualConsumptionCount", actual_negative_control_net_residual_consumption_count},
                {"auxiliaryResidualConsumptionCount", actual_auxiliary_control_net_residual_consumption_count}
        };
        native_effective["loras"] = json::array();
        for (size_t index = 0; index < contract.loras.size(); ++index) {
            native_effective["loras"].push_back({
                    {"id", contract.loras[index].id},
                    {"sha256", contract.loras[index].sha256},
                    {"multiplier", static_cast<double>(native_loras[index].multiplier)}
            });
        }
        native_effective["loraEvidence"] = {
                {"requestedCount", execution_evidence.lora_requested_count},
                {"loadedCount", execution_evidence.lora_loaded_count},
                {"appliedCount", execution_evidence.lora_applied_count},
                {"appliedTensorCount", execution_evidence.lora_applied_tensor_count}
        };

        set_progress(
                "writing",
                "writing png",
                actual_timetable_count,
                actual_timetable_count,
                0.0f,
                actual_width,
                actual_height,
                contract.threads);
        if (first.width > static_cast<uint32_t>(
                std::numeric_limits<int>::max() / actual_channels)) {
            free_generated_images(images, contract.batch_count);
            return runtime_failure(
                    "OUTPUT_WRITE_FAILED",
                    "output row stride exceeds the native PNG writer range").dump();
        }
        const int stride = actual_width * actual_channels;
        bool write_ok = true;
        for (int index = 0; index < contract.batch_count; ++index) {
            if (stbi_write_png(
                    output_paths[static_cast<size_t>(index)].c_str(),
                    actual_width,
                    actual_height,
                    actual_channels,
                    images[index].data,
                    stride) == 0) {
                write_ok = false;
                break;
            }
        }
        free_generated_images(images, contract.batch_count);
        if (!write_ok) {
            remove_output_files(output_paths);
            set_progress(
                    "failed",
                    "failed to write png",
                    actual_timetable_count,
                    actual_timetable_count,
                    0.0f,
                    actual_width,
                    actual_height,
                    contract.threads);
            return runtime_failure("OUTPUT_WRITE_FAILED", "failed to write the complete PNG batch").dump();
        }
        mark_generation_stage(kStageOutputWritten);
        set_progress(
                "completed",
                "image saved",
                actual_timetable_count,
                actual_timetable_count,
                0.0f,
                actual_width,
                actual_height,
                contract.threads);

        json out = native_effective;
        out["ok"] = true;
        out["nativeExecution"] = true;
        out["executionStage"] = "semantic_generation_passed";
        out["path"] = output_paths.front();
        out["mimeType"] = "image/png";
        out["outputs"] = json::array();
        for (int index = 0; index < contract.batch_count; ++index) {
            out["outputs"].push_back({
                    {"index", index},
                    {"path", output_paths[static_cast<size_t>(index)]},
                    {"mimeType", "image/png"},
                    {"seed", contract.seed + index},
                    {"width", actual_width},
                    {"height", actual_height},
                    {"channels", actual_channels}
            });
        }
        out["outputCount"] = contract.batch_count;
        out["n"] = contract.batch_count;
        out["threads"] = contract.threads;
        out["sampleMethod"] = sd_sample_method_name(gen.sample_params.sample_method);
        out["nativeScheduler"] = sd_scheduler_name(gen.sample_params.scheduler);
        out["nativePredictionMode"] = observed_prediction_mode(observed_prediction);
        out["actualDiffusionModelComputeCount"] =
                execution_evidence.diffusion_model_compute_count;
        out["actualPositiveDiffusionModelComputeCount"] =
                execution_evidence.positive_diffusion_model_compute_count;
        out["actualNegativeDiffusionModelComputeCount"] =
                execution_evidence.negative_diffusion_model_compute_count;
        out["actualAuxiliaryDiffusionModelComputeCount"] =
                execution_evidence.auxiliary_diffusion_model_compute_count;
        out["actualSamplingStepCount"] =
                execution_evidence.completed_sampling_step_count;
        out["actualSamplingPassCount"] = actual_sampling_pass_count;
        out["actualPositiveConditioningTokenCount"] =
                execution_evidence.positive_conditioning_token_count;
        out["actualNegativeConditioningTokenCount"] =
                execution_evidence.negative_conditioning_token_count;
        out["conditioningArtifactSha256"] = actual_prompt_weight_fingerprint;
        out["totalUnetExecutionCount"] = total_unet_execution_count;
        out["uiProgressCallbackCount"] = ui_progress_callback_count;
        out["uiProgressReportedSteps"] = ui_progress_reported_steps;
        out["uiProgressMaxStep"] = ui_progress_max_step;
        out["previewRequested"] = native_preview_mode != PREVIEW_NONE;
        out["previewMode"] = native_preview_mode == PREVIEW_NONE
                ? "none"
                : contract.preview_mode;
        out["previewInterval"] = contract.preview_interval;
        out["previewPublicationCount"] = preview_context.publication_count;
        out["previewLastStep"] = preview_context.last_step;
        out["previewLastRevision"] = preview_context.last_revision;
        out["configuredSampleSteps"] = gen.sample_params.sample_steps;
        out["actualDenoisingStepCount"] = actual_timetable_count;
        out["cfgBranchCount"] = actual_branches;
        out["negativePrompt"] = contract.negative_prompt;
        out["taskMode"] = contract.task_mode;
        out["inputImagePath"] = input_image_wired ? input_image.canonical_path : "";
        out["maskImagePath"] = mask_image_wired ? mask_image.canonical_path : "";
        out["controlImagePath"] = control_image_wired ? control_image.canonical_path : "";
        out["inputImageExecutionCount"] = input_image_wired ? 1 : 0;
        out["maskImageExecutionCount"] = mask_image_wired ? 1 : 0;
        out["controlImageExecutionCount"] = actual_control_image_consumed ? 1 : 0;
        out["inputImageSha256"] = input_image.sha256;
        out["maskImageSha256"] = mask_image.sha256;
        out["controlImageSha256"] = control_image.sha256;
        out["inputImageSourceWidth"] = input_image.source_width;
        out["inputImageSourceHeight"] = input_image.source_height;
        out["inputImageSourceChannels"] = input_image.source_channels;
        out["inputImageWidth"] = input_image.width;
        out["inputImageHeight"] = input_image.height;
        out["inputImageChannels"] = input_image.channels;
        out["inputImageExifOrientation"] = input_image.exif_orientation;
        out["maskImageSourceWidth"] = mask_image.source_width;
        out["maskImageSourceHeight"] = mask_image.source_height;
        out["maskImageSourceChannels"] = mask_image.source_channels;
        out["maskImageWidth"] = mask_image.width;
        out["maskImageHeight"] = mask_image.height;
        out["maskImageChannels"] = mask_image.channels;
        out["maskImageExifOrientation"] = mask_image.exif_orientation;
        out["controlImageSourceWidth"] = control_image.source_width;
        out["controlImageSourceHeight"] = control_image.source_height;
        out["controlImageSourceChannels"] = control_image.source_channels;
        out["controlImageWidth"] = control_image.width;
        out["controlImageHeight"] = control_image.height;
        out["controlImageChannels"] = control_image.channels;
        out["controlImageExifOrientation"] = control_image.exif_orientation;
        out["imageInputConsumption"] = native_effective["imageInputConsumption"];
        out["controlNetEvidence"] = native_effective["controlNetEvidence"];
        out["strength"] = native_effective["strength"];
        out["controlStrength"] = native_effective["controlStrength"];
        out["clipSkip"] = native_effective["clipSkip"];
        out["batchCount"] = native_effective["batchCount"];
        out["vaeTiling"] = native_effective["vaeTiling"];
        out["loras"] = native_effective["loras"];
        out["loraEvidence"] = native_effective["loraEvidence"];
        out["backendMode"] = "cpu";
        out["backend"] = "stable-diffusion.cpp";
        out["runtimeBackend"] = sd_runtime_backend_label();
        out["componentSelection"] = json::parse(selected_components_json);
        out["systemInfo"] = sd_get_system_info();
        out["nativeEffective"] = native_effective;
        out["nativeGenerationSequence"] = sequence;
        return out.dump();
    } catch (const ContractError &error) {
        set_progress("failed", error.what());
        return contract_failure_json(error).dump();
    } catch (const json::exception &error) {
        set_progress("failed", error.what());
        return contract_failure_json(ContractError(
                "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                "params",
                error.what())).dump();
    } catch (const std::exception &error) {
        set_progress("failed", error.what());
        return runtime_failure("NATIVE_BRIDGE_FAILURE", error.what()).dump();
    }
}

std::string upscale_impl(const std::string &raw_upscaler_path,
                         const std::string &raw_upscaler_root,
                         const std::string &raw_input_path,
                         const std::string &raw_params,
                         const std::string &raw_output_path) {
    try {
        const json params = json::parse(raw_params);
        const std::string upscaler_id = required_string(params, "upscalerId");
        const std::string requested_upscaler_sha256 =
                lower_copy(required_string(params, "upscalerSha256"));
        const int64_t upscaler_size_signed = required_integer(params, "upscalerSizeBytes");
        const std::string input_sha256 =
                lower_copy(required_string(params, "inputImageSha256"));
        const int target_scale = required_int32(params, "targetScale");
        const int tile_size = required_int32(params, "tileSize");
        const int threads = required_int32(params, "threads");
        if (!is_sha256(requested_upscaler_sha256)) {
            invalid_contract("upscalerSha256", "must be a 64-character SHA-256 value");
        }
        if (!is_sha256(input_sha256)) {
            invalid_contract("inputImageSha256", "must be a 64-character SHA-256 value");
        }
        if (upscaler_size_signed < 16 ||
            static_cast<uint64_t>(upscaler_size_signed) > kMaxUpscalerBytes) {
            invalid_contract("upscalerSizeBytes", "must describe a bounded non-empty model file");
        }
        if (target_scale != 2 && target_scale != 3 && target_scale != 4) {
            invalid_contract("targetScale", "must be 2, 3, or 4");
        }
        if (tile_size < 32 || tile_size > 1024 || tile_size % 8 != 0) {
            invalid_contract("tileSize", "must be a multiple of 8 in [32, 1024]");
        }
        if (threads < 1 || threads > 64) {
            invalid_contract("threads", "must be in [1, 64]");
        }

        std::string upscaler_path;
        std::string validation_error;
        if (!validate_upscaler_file(
                raw_upscaler_root,
                raw_upscaler_path,
                static_cast<uint64_t>(upscaler_size_signed),
                upscaler_path,
                validation_error)) {
            return runtime_failure("UPSCALER_FILE_INVALID", validation_error).dump();
        }
        const size_t upscaler_leaf_separator = upscaler_path.find_last_of('/');
        const std::string upscaler_file_name = upscaler_leaf_separator == std::string::npos
                ? upscaler_path
                : upscaler_path.substr(upscaler_leaf_separator + 1u);
        const std::string expected_upscaler_name_prefix = "upscaler-" + upscaler_id + ".";
        if (upscaler_file_name.rfind(expected_upscaler_name_prefix, 0u) != 0u) {
            return runtime_failure(
                    "UPSCALER_IDENTITY_MISMATCH",
                    "upscaler id does not match the app-owned model file identity").dump();
        }
        std::string output_path;
        if (!validate_canonical_output_path(raw_output_path, output_path, validation_error)) {
            return runtime_failure("OUTPUT_PATH_INVALID", validation_error).dump();
        }
        mark_generation_stage(kStageContractValidated);

        set_progress(
                "verifying_upscaler",
                "verifying ESRGAN model bytes",
                0,
                1,
                0.0f,
                0,
                0,
                threads);
        const uint64_t upscaler_size = static_cast<uint64_t>(upscaler_size_signed);
        std::string actual_upscaler_sha256;
        RegularFileIdentity hashed_upscaler_identity;
        if (!sha256_regular_file(
                upscaler_path,
                upscaler_size,
                actual_upscaler_sha256,
                hashed_upscaler_identity,
                validation_error)) {
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            return runtime_failure("UPSCALER_NATIVE_HASH_FAILED", validation_error).dump();
        }
        if (actual_upscaler_sha256 != requested_upscaler_sha256) {
            return runtime_failure(
                    "UPSCALER_SHA256_MISMATCH",
                    "native SHA-256 does not match the worker-verified upscaler contract").dump();
        }

        LoadedInputImage input;
        if (!load_canonical_input_image(
                raw_input_path,
                input_sha256,
                3,
                input,
                validation_error,
                kMaxUpscaleInputSide,
                kMaxUpscaleInputPixels,
                "upscale input exceeds the 2048-pixel side or 4-megapixel execution limit")) {
            return runtime_failure(
                    validation_error ==
                            "upscale input exceeds the 2048-pixel side or 4-megapixel execution limit"
                            ? "UPSCALE_INPUT_TOO_LARGE"
                            : "UPSCALE_INPUT_INVALID",
                    validation_error).dump();
        }
        mark_generation_stage(kStageInputImageDecoded);
        const uint64_t requested_width =
                static_cast<uint64_t>(input.width) * static_cast<uint64_t>(target_scale);
        const uint64_t requested_height =
                static_cast<uint64_t>(input.height) * static_cast<uint64_t>(target_scale);
        const uint64_t requested_pixels = requested_width * requested_height;
        if (requested_width > kMaxUpscaleProductOutputSide ||
            requested_height > kMaxUpscaleProductOutputSide ||
            requested_pixels > kMaxUpscaleProductOutputPixels) {
            return runtime_failure(
                    "UPSCALE_OUTPUT_TOO_LARGE",
                    "requested upscale output exceeds the bounded 4096-pixel side or 16-megapixel product limit").dump();
        }
        set_progress(
                "loading_upscaler",
                "loading ESRGAN upscaler",
                0,
                1,
                0.0f,
                static_cast<int>(input.width),
                static_cast<int>(input.height),
                threads);

        std::unique_ptr<upscaler_ctx_t, decltype(&free_upscaler_ctx)> upscaler_ctx(
                new_upscaler_ctx(
                        upscaler_path.c_str(),
                        false,
                        false,
                        threads,
                        tile_size,
                        "cpu",
                        "cpu"),
                &free_upscaler_ctx);
        if (!upscaler_ctx) {
            return runtime_failure(
                    "UPSCALER_NATIVE_LOAD_FAILED",
                    "stable-diffusion.cpp could not load the selected ESRGAN model").dump();
        }
        mark_generation_stage(kStageContextReady);
        RegularFileIdentity loaded_upscaler_identity;
        if (!read_regular_file_identity(
                upscaler_path,
                upscaler_size,
                loaded_upscaler_identity,
                validation_error) ||
            !same_regular_file_identity(hashed_upscaler_identity, loaded_upscaler_identity)) {
            return runtime_failure(
                    "UPSCALER_FILE_CHANGED_DURING_LOAD",
                    validation_error.empty()
                            ? "upscaler model identity changed between hashing and native load"
                            : validation_error).dump();
        }
        const int native_scale = get_upscale_factor(upscaler_ctx.get());
        if (native_scale < target_scale || native_scale < 2 || native_scale > 8) {
            return runtime_failure(
                    "UPSCALER_SCALE_UNSUPPORTED",
                    "the selected ESRGAN model cannot produce the requested target scale").dump();
        }
        const uint64_t expected_width =
                static_cast<uint64_t>(input.width) * static_cast<uint64_t>(native_scale);
        const uint64_t expected_height =
                static_cast<uint64_t>(input.height) * static_cast<uint64_t>(native_scale);
        const uint64_t expected_pixels = expected_width * expected_height;
        if (expected_width > kMaxUpscaleNativeOutputSide ||
            expected_height > kMaxUpscaleNativeOutputSide ||
            expected_pixels > kMaxUpscaleNativeOutputPixels) {
            return runtime_failure(
                    "UPSCALE_OUTPUT_TOO_LARGE",
                    "native ESRGAN intermediate exceeds the bounded 8192-pixel side or 64-megapixel limit").dump();
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        const uint64_t sequence =
                g_generation_sequence.fetch_add(1u, std::memory_order_relaxed) + 1u;
        g_active_generation_sequence.store(sequence, std::memory_order_relaxed);
        mark_generation_stage(kStageGenerationInvoked);
        set_progress(
                "upscaling",
                "running ESRGAN upscale",
                0,
                1,
                0.0f,
                static_cast<int>(expected_width),
                static_cast<int>(expected_height),
                threads);
        sd_image_t upscaled = upscale(
                upscaler_ctx.get(),
                input.view(),
                static_cast<uint32_t>(native_scale));
        sd_upscaler_execution_evidence_t execution_evidence{};
        const bool has_execution_evidence = sd_get_upscaler_execution_evidence(
                upscaler_ctx.get(),
                &execution_evidence);
        upscaler_ctx.reset();
        mark_generation_stage(kStageContextReleased);
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            if (upscaled.data != nullptr) std::free(upscaled.data);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }
        const bool expected_tiled = input.width > static_cast<uint32_t>(tile_size) ||
                                    input.height > static_cast<uint32_t>(tile_size);
        const bool compute_counts_valid =
                execution_evidence.compute_invocation_count > 0u &&
                execution_evidence.compute_success_count ==
                        execution_evidence.compute_invocation_count;
        const bool tile_counts_valid = expected_tiled
                ? execution_evidence.tiled == 1u &&
                  execution_evidence.tile_compute_invocation_count ==
                          execution_evidence.compute_invocation_count &&
                  execution_evidence.tile_compute_success_count ==
                          execution_evidence.compute_success_count
                : execution_evidence.tiled == 0u &&
                  execution_evidence.compute_invocation_count == 1u &&
                  execution_evidence.tile_compute_invocation_count == 0u &&
                  execution_evidence.tile_compute_success_count == 0u;
        if (!has_execution_evidence ||
            execution_evidence.version != SD_UPSCALER_EXECUTION_EVIDENCE_VERSION ||
            execution_evidence.completed != 1u || execution_evidence.cancelled != 0u ||
            execution_evidence.requested_scale != static_cast<uint32_t>(native_scale) ||
            execution_evidence.native_scale != static_cast<uint32_t>(native_scale) ||
            execution_evidence.source_width != input.width ||
            execution_evidence.source_height != input.height ||
            execution_evidence.output_width != static_cast<uint32_t>(expected_width) ||
            execution_evidence.output_height != static_cast<uint32_t>(expected_height) ||
            !compute_counts_valid || !tile_counts_valid) {
            if (upscaled.data != nullptr) std::free(upscaled.data);
            return runtime_failure(
                    "UPSCALER_EXECUTION_EVIDENCE_INVALID",
                    "ESRGAN physical compute evidence is missing or inconsistent").dump();
        }
        if (upscaled.data == nullptr ||
            upscaled.width != static_cast<uint32_t>(expected_width) ||
            upscaled.height != static_cast<uint32_t>(expected_height) ||
            upscaled.channel != 3u) {
            if (upscaled.data != nullptr) std::free(upscaled.data);
            return runtime_failure(
                    "UPSCALER_NATIVE_OUTPUT_INVALID",
                    "stable-diffusion.cpp returned an invalid ESRGAN output tensor").dump();
        }
        mark_generation_stage(kStageSamplingObserved);
        mark_generation_stage(kStageImageReturned);
        if (upscaled.width > static_cast<uint32_t>(
                std::numeric_limits<int>::max() / static_cast<int>(upscaled.channel))) {
            std::free(upscaled.data);
            return runtime_failure("OUTPUT_WRITE_FAILED", "upscale output row stride is too large").dump();
        }
        set_progress(
                "writing",
                "writing upscaled PNG",
                1,
                1,
                0.0f,
                static_cast<int>(upscaled.width),
                static_cast<int>(upscaled.height),
                threads);
        const int write_ok = stbi_write_png(
                output_path.c_str(),
                static_cast<int>(upscaled.width),
                static_cast<int>(upscaled.height),
                static_cast<int>(upscaled.channel),
                upscaled.data,
                static_cast<int>(upscaled.width * upscaled.channel));
        std::free(upscaled.data);
        if (write_ok == 0) {
            std::remove(output_path.c_str());
            return runtime_failure("OUTPUT_WRITE_FAILED", "failed to write the upscaled PNG").dump();
        }
        mark_generation_stage(kStageOutputWritten);
        set_progress(
                "completed",
                "upscale completed",
                1,
                1,
                0.0f,
                static_cast<int>(expected_width),
                static_cast<int>(expected_height),
                threads);

        json native_effective = {
                {"operation", "ESRGAN_UPSCALE"},
                {"runtime", "STABLE_DIFFUSION_CPP"},
                {"backendMode", "cpu"},
                {"fallback", false},
                {"upscalerId", upscaler_id},
                {"upscalerFileName", upscaler_file_name},
                {"upscalerSha256", actual_upscaler_sha256},
                {"upscalerSizeBytes", upscaler_size},
                {"modelHashVerified", true},
                {"modelFileIdentityStable", true},
                {"inputImageSha256", input.sha256},
                {"sourceWidth", input.width},
                {"sourceHeight", input.height},
                {"nativeScale", native_scale},
                {"requestedTargetScale", target_scale},
                {"width", expected_width},
                {"height", expected_height},
                {"channels", 3},
                {"tileSize", tile_size},
                {"threads", threads},
                {"physicalComputeCount", execution_evidence.compute_invocation_count},
                {"physicalComputeSuccessCount", execution_evidence.compute_success_count},
                {"physicalTileComputeCount", execution_evidence.tile_compute_invocation_count},
                {"physicalTileComputeSuccessCount", execution_evidence.tile_compute_success_count},
                {"tiledExecution", execution_evidence.tiled == 1u},
                {"executionCompleted", execution_evidence.completed == 1u},
                {"nativeGenerationSequence", sequence}
        };
        json result = native_effective;
        result["ok"] = true;
        result["nativeExecution"] = true;
        result["path"] = output_path;
        result["mimeType"] = "image/png";
        result["nativeEffective"] = native_effective;
        result["nativeGenerationSequence"] = sequence;
        return result.dump();
    } catch (const ContractError &error) {
        set_progress("failed", error.what());
        return contract_failure_json(error).dump();
    } catch (const json::exception &error) {
        set_progress("failed", error.what());
        return contract_failure_json(ContractError(
                "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                "upscaleParams",
                error.what())).dump();
    } catch (const std::exception &error) {
        set_progress("failed", error.what());
        return runtime_failure("UPSCALER_NATIVE_FAILURE", error.what()).dump();
    }
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
    reset_generation_evidence();
    reset_progress_state();
    set_progress("initializing", "starting local image generation");
    set_progress_component_selection("{}");
    sd_set_log_callback(sd_log_callback, nullptr);
    sd_set_progress_callback(sd_progress_callback, nullptr);
    sd_set_cancel_callback(sd_cancel_callback, nullptr);
    std::string result = generate_impl(
            jstring_to_string(env, model_path),
            jstring_to_string(env, bundle_root),
            jstring_to_string(env, params_json),
            jstring_to_string(env, output_path));
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
    mark_generation_stage(kStageContextReleased);
    try {
        json result_json = json::parse(result);
        result_json["contextReleased"] = true;
        const uint64_t sequence =
                g_active_generation_sequence.load(std::memory_order_relaxed);
        if (sequence > 0) result_json["nativeGenerationSequence"] = sequence;
        result_json["nativeStageMask"] =
                g_generation_stage_mask.load(std::memory_order_relaxed);
        result_json["nativeDetailStageMask"] =
                static_cast<uint64_t>(g_generation_stage_mask.load(std::memory_order_relaxed));
        result = result_json.dump();
    } catch (const json::exception &) {
        if (!result.empty() && result.back() == '}') {
            result.insert(result.size() - 1, ",\"contextReleased\":true");
        }
    }
    g_generation_active.store(false, std::memory_order_relaxed);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
    sd_set_cancel_callback(nullptr, nullptr);
    return string_to_jstring(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_upscale(
        JNIEnv *env,
        jobject,
        jstring upscaler_path,
        jstring upscaler_root,
        jstring input_path,
        jstring params_json,
        jstring output_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel_requested.store(false, std::memory_order_relaxed);
    g_generation_active.store(true, std::memory_order_relaxed);
    reset_generation_evidence();
    reset_progress_state();
    set_progress("initializing", "starting local image upscale");
    set_progress_component_selection("{}");
    sd_set_log_callback(sd_log_callback, nullptr);
    sd_set_cancel_callback(sd_cancel_callback, nullptr);
    std::string result = upscale_impl(
            jstring_to_string(env, upscaler_path),
            jstring_to_string(env, upscaler_root),
            jstring_to_string(env, input_path),
            jstring_to_string(env, params_json),
            jstring_to_string(env, output_path));
    mark_generation_stage(kStageContextReleased);
    try {
        json result_json = json::parse(result);
        result_json["contextReleased"] = true;
        const uint64_t sequence =
                g_active_generation_sequence.load(std::memory_order_relaxed);
        if (sequence > 0u) result_json["nativeGenerationSequence"] = sequence;
        result_json["nativeStageMask"] =
                g_generation_stage_mask.load(std::memory_order_relaxed);
        result_json["nativeDetailStageMask"] =
                static_cast<uint64_t>(g_generation_stage_mask.load(std::memory_order_relaxed));
        result = result_json.dump();
    } catch (const json::exception &) {
        if (!result.empty() && result.back() == '}') {
            result.insert(result.size() - 1, ",\"contextReleased\":true");
        }
    }
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
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
    mark_generation_stage(kStageContextReleased);
    g_generation_active.store(false, std::memory_order_relaxed);
    set_progress("idle", "native context released");
}
