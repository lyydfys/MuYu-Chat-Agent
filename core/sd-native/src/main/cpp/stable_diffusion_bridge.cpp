#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <exception>
#include <limits.h>
#include <limits>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <utility>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"

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
std::atomic<int> g_observed_denoiser_callbacks{0};
std::atomic<int> g_observed_progress_steps{0};
std::atomic<int> g_observed_max_progress_step{0};

constexpr uint32_t kStageContractValidated = 1u << 0u;
constexpr uint32_t kStageContextReady = 1u << 1u;
constexpr uint32_t kStageGenerationInvoked = 1u << 2u;
constexpr uint32_t kStageSamplingObserved = 1u << 3u;
constexpr uint32_t kStageImageReturned = 1u << 4u;
constexpr uint32_t kStageOutputWritten = 1u << 5u;
constexpr uint32_t kStageContextReleased = 1u << 6u;

void mark_generation_stage(uint32_t stage) {
    g_generation_stage_mask.fetch_or(stage, std::memory_order_relaxed);
}

void reset_generation_evidence() {
    g_active_generation_sequence.store(0, std::memory_order_relaxed);
    g_generation_stage_mask.store(0, std::memory_order_relaxed);
    g_observed_prediction.store(PREDICTION_COUNT, std::memory_order_relaxed);
    g_observed_denoiser_callbacks.store(0, std::memory_order_relaxed);
    g_observed_progress_steps.store(0, std::memory_order_relaxed);
    g_observed_max_progress_step.store(0, std::memory_order_relaxed);
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

void set_progress_component_selection(const std::string &component_selection_json) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress.component_selection_json = component_selection_json.empty()
                                          ? "{}"
                                          : component_selection_json;
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

bool is_sha256(const std::string &value) {
    return value.size() == 64 && std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isxdigit(c) != 0;
    });
}

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
    double vae_scaling_factor = 0.0;
    int width = 0;
    int height = 0;
    int64_t seed = 0;
    std::string prompt;
    std::string negative_prompt;
    std::string family;
    int threads = 0;
    double distilled_guidance = 0.0;
    double flow_shift = 0.0;
    std::string requested_sample_method;
    sample_method_t sample_method = SAMPLE_METHOD_COUNT;
    scheduler_t native_scheduler = SCHEDULER_COUNT;
};

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
    if (contract.timetable_count <= 0 || contract.timetable_count != contract.steps) {
        unsupported_contract(
                "timetableCount",
                "the supported stable-diffusion.cpp samplers require timetableCount=steps");
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
    if (contract.tokenizer_max_length > std::numeric_limits<int>::max() / 2 ||
        contract.token_count != contract.tokenizer_max_length * 2) {
        invalid_contract(
                "tokenCount",
                "must cover the native positive and negative conditioning capacities");
    }
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
    if (contract.distilled_guidance < 0.0 || contract.distilled_guidance > 30.0) {
        invalid_contract("distilledGuidance", "must be in [0, 30]");
    }
    contract.flow_shift = required_number(params, "flowShift");
    if (contract.flow_shift < -1.0 || contract.flow_shift > 100.0) {
        invalid_contract("flowShift", "must be -1 (model default) or a value in [0, 100]");
    }
    contract.requested_sample_method = required_string(params, "sampleMethod");
    if (required_string(params, "backendMode") != "cpu") {
        unsupported_contract("backendMode", "Android stable-diffusion.cpp is built for backendMode=cpu");
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
    const bool generation_invoked =
            (g_generation_stage_mask.load(std::memory_order_relaxed) &
             kStageGenerationInvoked) != 0u;
    if (step > 0 && generation_invoked) {
        g_observed_denoiser_callbacks.fetch_add(1, std::memory_order_relaxed);
        g_observed_progress_steps.store(steps, std::memory_order_relaxed);
        int observed = g_observed_max_progress_step.load(std::memory_order_relaxed);
        while (step > observed &&
               !g_observed_max_progress_step.compare_exchange_weak(
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
            {"embeddingDiskDataType", "RUNTIME_NATIVE"},
            {"vaeScalingLocation", "RUNTIME_NATIVE"},
            {"vaeScalingFactor", 1.0},
            {"width", actual_width},
            {"height", actual_height},
            {"seed", contract.seed},
            {"graphName", "runtime-native"},
            {"fallback", false}
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
        if (output_path.empty()) {
            return runtime_failure("OUTPUT_PATH_INVALID", "output path is empty").dump();
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
        mark_generation_stage(kStageContractValidated);

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
        gen.prompt = contract.prompt.c_str();
        gen.negative_prompt = contract.use_cfg ? contract.negative_prompt.c_str() : nullptr;
        gen.width = contract.width;
        gen.height = contract.height;
        gen.seed = contract.seed;
        gen.batch_count = 1;
        gen.sample_params.sample_steps = contract.steps;
        gen.sample_params.guidance.txt_cfg = static_cast<float>(contract.cfg_scale);
        gen.sample_params.guidance.distilled_guidance =
                static_cast<float>(contract.distilled_guidance);
        if (contract.flow_shift >= 0.0) {
            gen.sample_params.flow_shift = static_cast<float>(contract.flow_shift);
        }
        gen.sample_params.sample_method = contract.sample_method;
        gen.sample_params.scheduler = contract.native_scheduler;

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
        sd_image_t *images = generate_image(ctx, &gen);
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            if (images != nullptr) {
                if (images[0].data != nullptr) free(images[0].data);
                free(images);
            }
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
        if (images == nullptr || images[0].data == nullptr) {
            free(images);
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
                    "stable-diffusion.cpp returned no image").dump();
        }
        mark_generation_stage(kStageImageReturned);

        const sd_image_t first = images[0];
        const int actual_width = static_cast<int>(first.width);
        const int actual_height = static_cast<int>(first.height);
        const int actual_timetable_count =
                g_observed_progress_steps.load(std::memory_order_relaxed);
        const int observed_max_step =
                g_observed_max_progress_step.load(std::memory_order_relaxed);
        const int observed_denoiser_callbacks =
                g_observed_denoiser_callbacks.load(std::memory_order_relaxed);
        const bool actual_use_cfg =
                std::fabs(static_cast<double>(gen.sample_params.guidance.txt_cfg) - 1.0) > 1e-6;
        const int actual_branches = actual_use_cfg ? 2 : 1;
        const int actual_token_count = contract.token_count;
        if (observed_denoiser_callbacks > std::numeric_limits<int>::max() / actual_branches) {
            free(first.data);
            free(images);
            execution_mismatch("unetExecutionCount", "observed native execution count overflowed int32");
        }
        const int actual_unet_execution_count =
                observed_denoiser_callbacks * actual_branches;

        auto release_images_and_mismatch = [&](const std::string &field,
                                               const std::string &message) -> void {
            free(first.data);
            free(images);
            execution_mismatch(field, message);
        };
        if (actual_width != contract.width || actual_height != contract.height) {
            release_images_and_mismatch(
                    "width,height",
                    "stable-diffusion.cpp aligned the requested dimensions; strict execution rejects implicit alignment");
        }
        if (actual_timetable_count != contract.timetable_count ||
            observed_max_step != contract.timetable_count) {
            release_images_and_mismatch(
                    "timetableCount",
                    "observed scheduler progress differs from the resolved timetableCount");
        }
        if (actual_unet_execution_count != contract.unet_execution_count) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "observed denoiser callbacks multiplied by actual CFG branches differ from the contract");
        }

        const double actual_cfg_scale =
                static_cast<double>(gen.sample_params.guidance.txt_cfg);
        json native_effective = native_effective_json(
                contract,
                prediction_wire,
                actual_timetable_count,
                actual_timetable_count,
                actual_unet_execution_count,
                actual_cfg_scale,
                actual_use_cfg,
                actual_token_count,
                actual_width,
                actual_height);

        set_progress(
                "writing",
                "writing png",
                actual_timetable_count,
                actual_timetable_count,
                0.0f,
                actual_width,
                actual_height,
                contract.threads);
        const int stride = static_cast<int>(first.width * first.channel);
        const int write_ok = stbi_write_png(
                output_path.c_str(),
                actual_width,
                actual_height,
                static_cast<int>(first.channel),
                first.data,
                stride);
        free(first.data);
        free(images);
        if (write_ok == 0) {
            std::remove(output_path.c_str());
            set_progress(
                    "failed",
                    "failed to write png",
                    actual_timetable_count,
                    actual_timetable_count,
                    0.0f,
                    actual_width,
                    actual_height,
                    contract.threads);
            return runtime_failure("OUTPUT_WRITE_FAILED", "failed to write png").dump();
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
        out["path"] = output_path;
        out["mimeType"] = "image/png";
        out["threads"] = contract.threads;
        out["distilledGuidance"] =
                static_cast<double>(gen.sample_params.guidance.distilled_guidance);
        out["flowShift"] = contract.flow_shift;
        out["sampleMethod"] = sd_sample_method_name(gen.sample_params.sample_method);
        out["nativeScheduler"] = sd_scheduler_name(gen.sample_params.scheduler);
        out["nativePredictionMode"] = observed_prediction_mode(observed_prediction);
        out["observedDenoiserCallbackCount"] = observed_denoiser_callbacks;
        out["observedProgressSteps"] = actual_timetable_count;
        out["observedMaxProgressStep"] = observed_max_step;
        out["cfgBranchCount"] = actual_branches;
        out["negativePrompt"] = contract.negative_prompt;
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
    mark_generation_stage(kStageContextReleased);
    g_generation_active.store(false, std::memory_order_relaxed);
    set_progress("idle", "native context released");
}
