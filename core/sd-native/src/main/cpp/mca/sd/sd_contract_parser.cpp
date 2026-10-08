#include "mca/sd/sd_contract_parser.hpp"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_input_contract.hpp"
#include "mca/sd/sd_schedule_resolver.hpp"
#include "native_prompt_language_contract.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <unordered_set>
#include <vector>

using json = nlohmann::json;

namespace mca {
namespace sd {

namespace {
std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string &value, const std::string &suffix) {
    if (suffix.size() > value.size()) return false;
    return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin());
}
} // namespace

[[noreturn]] void execution_mismatch(const std::string &field,
                                     const std::string &message) {
    throw ContractError("EXECUTION_CONTRACT_MISMATCH", field, message);
}

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
                            bool allow_empty) {
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


bool is_textual_inversion_trigger(const std::string &value) {
    if (value.empty() || value.size() > 64u) return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9') ||
               c == '_' || c == ':' || c == '#' || c == '<' || c == '>' ||
               c == '|' || c == '.' || c == '-';
    });
}


bool task_uses_input_image(const StableDiffusionExecutionContract &contract) {
    return mca::sd::task_uses_input_image(contract.task_mode);
}

bool task_uses_init_image(const StableDiffusionExecutionContract &contract) {
    return mca::sd::task_uses_init_image(contract.task_mode);
}

bool task_uses_mask_image(const StableDiffusionExecutionContract &contract) {
    return mca::sd::task_uses_mask_image(contract.task_mode);
}

bool task_uses_control_image(const StableDiffusionExecutionContract &contract) {
    return mca::sd::task_uses_control_image(contract.task_mode);
}

int expected_denoising_step_count(const StableDiffusionExecutionContract &contract) {
    return mca::sd::expected_denoising_step_count(
            contract.steps, contract.strength, contract.task_mode);
}

float backend_denoising_strength(const StableDiffusionExecutionContract &contract) {
    return mca::sd::backend_denoising_strength(
            contract.steps, contract.strength, contract.task_mode);
}

void resolve_qwen_turbo_schedule(StableDiffusionExecutionContract &contract) {
    try {
        mca::sd::resolve_qwen_turbo_schedule_adapter(contract);
    } catch (const mca::sd::ScheduleContractException &ex) {
        if (ex.unsupported()) {
            unsupported_contract(ex.field(), ex.what());
        } else {
            invalid_contract(ex.field(), ex.what());
        }
    }
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
    if (!mca::image::prompt_language::is_safe_ascii_diffusion_prompt_pair(
            contract.prompt,
            contract.negative_prompt)) {
        unsupported_contract(
                "prompt,negativePrompt",
                "stable-diffusion.cpp has no evidence-bound multilingual text encoder; use canonical ASCII English tags");
    }
    if (!contract.use_cfg && !contract.negative_prompt.empty()) {
        unsupported_contract(
                "negativePrompt",
                "negativePrompt cannot affect pixels when useCfg=false");
    }
    contract.family = required_string(params, "family");
    contract.variant = optional_string(params, "variant", "");
    const auto qwen_turbo_schedule = params.find("qwenTurboSchedule");
    if (qwen_turbo_schedule != params.end()) {
        contract.qwen_turbo_schedule = required_string(params, "qwenTurboSchedule");
    }
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
    if (contract.strength < 0.0 || contract.strength > 1.0) {
        invalid_contract("strength", "must be in [0, 1]");
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
    try {
        mca::sd::parse_lora_specifications(
                params,
                contract.lora_root_path,
                contract.loras);
    } catch (const mca::sd::LoraContractException &ex) {
        if (ex.unsupported()) {
            unsupported_contract(ex.field(), ex.what());
        } else {
            invalid_contract(ex.field(), ex.what());
        }
    }

    // Textual inversion is a native CLIP custom-word path, not an arbitrary prompt embedding
    // override.  Keep the wire shape explicit so QNN/MNN requests cannot silently ignore it.
    const auto textual_inversions = params.find("textualInversions");
    const size_t textual_inversion_count = textual_inversions == params.end()
            ? 0u
            : (textual_inversions->is_array() ? textual_inversions->size() : 0u);
    if (textual_inversions != params.end() && !textual_inversions->is_array()) {
        invalid_contract("textualInversions", "must be an array when present");
    }
    const auto textual_count_field = params.find("textualInversionCount");
    if (textual_count_field != params.end()) {
        const int count = required_int32(params, "textualInversionCount");
        if (count < 0 || static_cast<size_t>(count) != textual_inversion_count) {
            invalid_contract("textualInversionCount", "must exactly match textualInversions");
        }
    }
    contract.textual_inversion_supported = optional_boolean(
            params,
            "textualInversionSupported",
            false);
    contract.textual_inversion_native_mode = optional_string(
            params,
            "textualInversionNativeMode",
            "");
    contract.textual_inversion_root_path = optional_string(
            params,
            "textualInversionRootPath",
            "");
    contract.textual_inversion_binding_fingerprint = lower_copy(optional_string(
            params,
            "textualInversionBindingFingerprint",
            ""));
    if (textual_inversion_count == 0u) {
        if (!contract.textual_inversion_native_mode.empty() ||
            !contract.textual_inversion_root_path.empty() ||
            !contract.textual_inversion_binding_fingerprint.empty()) {
            invalid_contract(
                    "textualInversions",
                    "textual inversion metadata must be omitted when no artifact is selected");
        }
    } else {
        if (!contract.textual_inversion_supported) {
            unsupported_contract(
                    "textualInversionSupported",
                    "the resolved graph does not expose a textual-inversion input path");
        }
        if (contract.textual_inversion_native_mode != "SDCPP_CUSTOM_WORDS") {
            unsupported_contract(
                    "textualInversionNativeMode",
                    "stable-diffusion.cpp requires the SDCPP_CUSTOM_WORDS custom-word path");
        }
        if (contract.textual_inversion_root_path.empty()) {
            invalid_contract(
                    "textualInversionRootPath",
                    "is required when textual inversion artifacts are selected");
        }
        if (!is_sha256(contract.textual_inversion_binding_fingerprint)) {
            invalid_contract(
                    "textualInversionBindingFingerprint",
                    "must be a lowercase 64-character SHA-256 value");
        }
        if (textual_inversion_count > kMaxTextualInversionCount) {
            invalid_contract("textualInversions", "at most 8 artifacts may be selected");
        }
        std::unordered_set<std::string> textual_ids;
        std::unordered_set<std::string> textual_paths;
        std::unordered_set<std::string> textual_triggers;
        std::string selected_tokenizer_fingerprint;
        uint64_t active_textual_inversion_bytes = 0u;
        constexpr uint64_t kMaxActiveTextualInversionBytes =
                UINT64_C(256) * 1024u * 1024u;
        contract.textual_inversions.reserve(textual_inversion_count);
        for (size_t index = 0; index < textual_inversion_count; ++index) {
            const json &item = (*textual_inversions)[index];
            if (!item.is_object()) {
                invalid_contract("textualInversions", "every item must be an object");
            }
            ContractTextualInversion artifact;
            artifact.id = required_string(item, "id");
            artifact.name = required_string(item, "name");
            artifact.trigger = required_string(item, "trigger");
            artifact.path = required_string(item, "path");
            artifact.sha256 = lower_copy(required_string(item, "sha256"));
            artifact.format = lower_copy(required_string(item, "format"));
            artifact.model_fingerprint = lower_copy(required_string(item, "modelFingerprint"));
            artifact.tokenizer_fingerprint = lower_copy(required_string(item, "tokenizerFingerprint"));
            artifact.profile_id = required_string(item, "profileId");
            artifact.runtime = required_string(item, "runtime");
            artifact.native_mode = required_string(item, "nativeMode");
            artifact.binding_fingerprint = lower_copy(required_string(item, "bindingFingerprint"));
            artifact.profile_revision = required_int32(item, "profileRevision");
            const int64_t size_bytes = required_integer(item, "sizeBytes");
            if (artifact.id.empty() || artifact.id.size() > 128u ||
                !std::all_of(artifact.id.begin(), artifact.id.end(), [](unsigned char c) {
                    return std::isalnum(c) != 0 || c == '-' || c == '_';
                })) {
                invalid_contract("textualInversions.id", "must be a bounded opaque identifier");
            }
            if (artifact.name.empty() || artifact.name.size() > 128u) {
                invalid_contract("textualInversions.name", "must be a bounded non-empty name");
            }
            if (!is_textual_inversion_trigger(artifact.trigger)) {
                invalid_contract("textualInversions.trigger", "contains unsupported token characters");
            }
            if (!is_sha256(artifact.sha256) || !is_sha256(artifact.model_fingerprint) ||
                !is_sha256(artifact.tokenizer_fingerprint) ||
                !is_sha256(artifact.binding_fingerprint)) {
                invalid_contract(
                        "textualInversions.sha256",
                        "artifact and binding fingerprints must be SHA-256 values");
            }
            if (artifact.runtime != "STABLE_DIFFUSION_CPP" ||
                artifact.native_mode != "SDCPP_CUSTOM_WORDS") {
                unsupported_contract(
                        "textualInversions.nativeMode",
                        "every artifact must use the stable-diffusion.cpp custom-word path");
            }
            if (artifact.profile_id != contract.profile_id ||
                artifact.profile_revision != contract.profile_revision ||
                artifact.model_fingerprint != contract.model_fingerprint) {
                execution_mismatch(
                        "textualInversions",
                        "artifact binding does not match the resolved model profile");
            }
            if (selected_tokenizer_fingerprint.empty()) {
                selected_tokenizer_fingerprint = artifact.tokenizer_fingerprint;
            } else if (artifact.tokenizer_fingerprint != selected_tokenizer_fingerprint) {
                execution_mismatch(
                        "textualInversions.tokenizerFingerprint",
                        "all textual inversion artifacts must bind to one tokenizer component");
            }
            if (artifact.profile_revision <= 0) {
                invalid_contract("textualInversions.profileRevision", "must be positive");
            }
            if (size_bytes < 16 || static_cast<uint64_t>(size_bytes) > kMaxTextualInversionBytes) {
                invalid_contract(
                        "textualInversions.sizeBytes",
                        "must be between 16 bytes and 100 MiB");
            }
            if (artifact.format != "safetensors" && artifact.format != "pytorch" &&
                artifact.format != "checkpoint" && artifact.format != "binary") {
                invalid_contract("textualInversions.format", "unsupported textual inversion format");
            }
            const std::string lower_path = lower_copy(artifact.path);
            const bool extension_ok =
                    (artifact.format == "safetensors" && ends_with(lower_path, ".safetensors")) ||
                    (artifact.format == "pytorch" && ends_with(lower_path, ".pt")) ||
                    (artifact.format == "checkpoint" && ends_with(lower_path, ".ckpt")) ||
                    (artifact.format == "binary" && ends_with(lower_path, ".bin"));
            if (!extension_ok) {
                invalid_contract("textualInversions.format", "format does not match artifact extension");
            }
            if (!textual_ids.insert(artifact.id).second ||
                !textual_paths.insert(artifact.path).second ||
                !textual_triggers.insert(lower_copy(artifact.trigger)).second) {
                invalid_contract(
                        "textualInversions",
                        "ids, paths, and triggers must be unique per request");
            }
            artifact.size_bytes = static_cast<uint64_t>(size_bytes);
            if (artifact.size_bytes >
                kMaxActiveTextualInversionBytes - active_textual_inversion_bytes) {
                invalid_contract(
                        "textualInversions.sizeBytes",
                        "selected artifacts exceed the 256 MiB active-request quota");
            }
            active_textual_inversion_bytes += artifact.size_bytes;
            contract.textual_inversions.push_back(std::move(artifact));
        }
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
    const auto ultrafix = params.find("ultraFix");
    if (ultrafix != params.end()) {
        if (!ultrafix->is_object()) {
            invalid_contract("ultraFix", "must be an object when present");
        }
        static const std::unordered_set<std::string> fields = {
            "targetWidth",
            "targetHeight",
            "strength",
            "inversionSteps",
            "refinementSteps",
            "tileSize",
            "overlap",
        };
        if (ultrafix->size() != fields.size()) {
            invalid_contract("ultraFix", "must contain exactly the structured UltraFix fields");
        }
        for (auto item = ultrafix->begin(); item != ultrafix->end(); ++item) {
            if (fields.find(item.key()) == fields.end()) {
                invalid_contract("ultraFix", "contains an unknown field: " + item.key());
            }
        }
        contract.ultrafix_enabled = true;
        contract.ultrafix_target_width = required_int32(*ultrafix, "targetWidth");
        contract.ultrafix_target_height = required_int32(*ultrafix, "targetHeight");
        contract.ultrafix_strength = required_number(*ultrafix, "strength");
        contract.ultrafix_inversion_steps = required_int32(*ultrafix, "inversionSteps");
        contract.ultrafix_refinement_steps = required_int32(*ultrafix, "refinementSteps");
        contract.ultrafix_tile_size = required_int32(*ultrafix, "tileSize");
        contract.ultrafix_overlap = required_number(*ultrafix, "overlap");
        if (contract.ultrafix_target_width < 64 ||
            contract.ultrafix_target_width > 8192 ||
            contract.ultrafix_target_height < 64 ||
            contract.ultrafix_target_height > 8192 ||
            contract.ultrafix_target_width % 8 != 0 ||
            contract.ultrafix_target_height % 8 != 0 ||
            static_cast<uint64_t>(contract.ultrafix_target_width) *
                static_cast<uint64_t>(contract.ultrafix_target_height) >
                64u * 1024u * 1024u) {
            invalid_contract("ultraFix", "target dimensions exceed the bounded aligned canvas");
        }
        if (contract.ultrafix_strength <= 0.0 || contract.ultrafix_strength > 1.0 ||
            contract.ultrafix_inversion_steps < 1 ||
            contract.ultrafix_inversion_steps > 100 ||
            contract.ultrafix_refinement_steps < 1 ||
            contract.ultrafix_refinement_steps > 100) {
            invalid_contract("ultraFix", "strength and step counts are outside their bounds");
        }
        const float ultrafix_wire_strength =
            static_cast<float>(contract.ultrafix_strength);
        const int begin = std::clamp(
            static_cast<int>(static_cast<float>(contract.ultrafix_refinement_steps) *
                             (1.0f - ultrafix_wire_strength)),
            0,
            contract.ultrafix_refinement_steps - 1);
        if (contract.ultrafix_inversion_steps !=
            contract.ultrafix_refinement_steps - begin) {
            invalid_contract(
                "ultraFix.inversionSteps",
                "must equal the strength-derived denoising tail length");
        }
        if (contract.ultrafix_tile_size < 128 ||
            contract.ultrafix_tile_size > 2048 ||
            contract.ultrafix_tile_size % 8 != 0 ||
            contract.ultrafix_tile_size > std::min(
                contract.ultrafix_target_width,
                contract.ultrafix_target_height) ||
            contract.ultrafix_overlap < 0.0 || contract.ultrafix_overlap > 0.5) {
            invalid_contract("ultraFix", "tile size or overlap is outside the bounded contract");
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
    if (contract.ultrafix_enabled) {
        if (contract.task_mode != "img2img" || contract.input_image_path.empty() ||
            !contract.mask_image_path.empty() || !contract.control_image_path.empty()) {
            invalid_contract(
                "ultraFix",
                "requires exactly one img2img source and no mask or control image");
        }
        if (contract.batch_count != 1 || contract.preview_mode != "none") {
            invalid_contract(
                "ultraFix",
                "supports one output and deliberately disables live preview");
        }
        if (contract.ultrafix_target_width != contract.width ||
            contract.ultrafix_target_height != contract.height ||
            contract.ultrafix_refinement_steps != contract.steps ||
            std::fabs(contract.ultrafix_strength - contract.strength) > 1.0e-12) {
            invalid_contract(
                "ultraFix",
                "target, strength, and refinement steps must match the outer execution contract");
        }
        if (!contract.vae_tiling_enabled ||
            contract.vae_tile_size != contract.ultrafix_tile_size ||
            std::fabs(contract.vae_tile_overlap - contract.ultrafix_overlap) > 1.0e-12) {
            invalid_contract(
                "ultraFix",
                "requires matching explicit VAE tiling parameters");
        }
    }
    const int expected_timetable_count = expected_denoising_step_count(contract);
    if (contract.timetable_count != expected_timetable_count) {
        execution_mismatch(
                "timetableCount",
                "resolved timetableCount must match the exact native img2img/inpaint strength schedule");
    }
    resolve_sampler_contract(contract);
    resolve_qwen_turbo_schedule(contract);
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

} // namespace sd
} // namespace mca
