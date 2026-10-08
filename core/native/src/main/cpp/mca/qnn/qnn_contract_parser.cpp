#include "mca/qnn/qnn_contract_parser.hpp"

#include <sys/stat.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <limits>
#include <sstream>

#include "native_prompt_language_contract.hpp"

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace qnn {

namespace {

std::string json_escape(const std::string& value) {
    std::ostringstream out;
    for (size_t i = 0; i < value.size(); ++i) {
        const char c = value[i];
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u00";
                    const char* hex = "0123456789abcdef";
                    out << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string quote(const std::string& value) {
    return "\"" + json_escape(value) + "\"";
}

long long file_size_or_zero(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return 0;
    return static_cast<long long>(st.st_size);
}

std::string normalized_contract_enum(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        if (c == '-' || c == ' ' || c == '.') return '_';
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool qnn_json_value_start(
        const std::string& json,
        const std::string& key,
        size_t* value_start,
        std::string* error) {
    const auto key_position = json.find("\"" + key + "\"");
    if (key_position == std::string::npos) {
        *error = "Missing required native execution field '" + key + "'.";
        return false;
    }
    const auto colon = json.find(':', key_position + key.size() + 2u);
    if (colon == std::string::npos) {
        *error = "Native execution field '" + key + "' has no value.";
        return false;
    }
    const auto start = json.find_first_not_of(" \t\r\n", colon + 1u);
    if (start == std::string::npos) {
        *error = "Native execution field '" + key + "' has no value.";
        return false;
    }
    *value_start = start;
    return true;
}

bool qnn_json_token_ends_at(const std::string& json, size_t end) {
    const auto next = json.find_first_not_of(" \t\r\n", end);
    return next == std::string::npos || json[next] == ',' || json[next] == '}' || json[next] == ']';
}

bool qnn_required_string_field(
        const std::string& json,
        const std::string& key,
        std::string* value,
        std::string* error,
        bool allow_empty = false) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    if (json[start] != '"') {
        *error = "Native execution field '" + key + "' must be a string.";
        return false;
    }
    value->clear();
    bool escaped = false;
    for (size_t index = start + 1u; index < json.size(); ++index) {
        const char c = json[index];
        if (escaped) {
            switch (c) {
                case 'n': value->push_back('\n'); break;
                case 'r': value->push_back('\r'); break;
                case 't': value->push_back('\t'); break;
                default: value->push_back(c); break;
            }
            escaped = false;
            continue;
        }
        if (c == '\\') {
            escaped = true;
            continue;
        }
        if (c == '"') {
            if (value->empty() && !allow_empty) {
                *error = "Native execution field '" + key + "' must not be blank.";
                return false;
            }
            return true;
        }
        value->push_back(c);
    }
    *error = "Native execution string field '" + key + "' is unterminated.";
    return false;
}

bool qnn_required_bool_field(
        const std::string& json,
        const std::string& key,
        bool* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    if (json.compare(start, 4u, "true") == 0 && qnn_json_token_ends_at(json, start + 4u)) {
        *value = true;
        return true;
    }
    if (json.compare(start, 5u, "false") == 0 && qnn_json_token_ends_at(json, start + 5u)) {
        *value = false;
        return true;
    }
    *error = "Native execution field '" + key + "' must be a boolean.";
    return false;
}

bool qnn_required_integer_field(
        const std::string& json,
        const std::string& key,
        long long* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    errno = 0;
    char* end = nullptr;
    const char* begin = json.c_str() + start;
    const long long parsed = std::strtoll(begin, &end, 10);
    const size_t end_index = static_cast<size_t>(end - json.c_str());
    if (end == begin || errno == ERANGE || !qnn_json_token_ends_at(json, end_index)) {
        *error = "Native execution field '" + key + "' must be an integer.";
        return false;
    }
    *value = parsed;
    return true;
}

bool qnn_required_number_field(
        const std::string& json,
        const std::string& key,
        double* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    errno = 0;
    char* end = nullptr;
    const char* begin = json.c_str() + start;
    const double parsed = std::strtod(begin, &end);
    const size_t end_index = static_cast<size_t>(end - json.c_str());
    if (end == begin || errno == ERANGE || !std::isfinite(parsed) ||
        !qnn_json_token_ends_at(json, end_index)) {
        *error = "Native execution field '" + key + "' must be a finite number.";
        return false;
    }
    *value = parsed;
    return true;
}

} // namespace

bool parse_qnn_scheduler_contract(
        const std::string& json,
        QnnSchedulerContract* contract,
        std::string* error) {
    std::string scheduler;
    std::string sample_method;
    std::string prediction;
    std::string noise_schedule;
    std::string timestep_spacing;
    std::string final_sigma_type;
    long long integer_value = 0;
    double number_value = 0.0;
    if (!qnn_required_string_field(json, "scheduler", &scheduler, error) ||
        !qnn_required_string_field(json, "sampleMethod", &sample_method, error) ||
        !qnn_required_string_field(json, "predictionType", &prediction, error) ||
        !qnn_required_integer_field(json, "numTrainTimesteps", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0 || integer_value > std::numeric_limits<int>::max()) {
        *error = "numTrainTimesteps must be a positive int32 value.";
        return false;
    }
    contract->config.num_train_timesteps = static_cast<int>(integer_value);
    if (!qnn_required_string_field(json, "noiseSchedule", &noise_schedule, error) ||
        !qnn_required_number_field(json, "betaStart", &number_value, error)) {
        return false;
    }
    contract->config.beta_start = static_cast<float>(number_value);
    if (!qnn_required_number_field(json, "betaEnd", &number_value, error) ||
        !qnn_required_string_field(json, "timestepSpacing", &timestep_spacing, error) ||
        !qnn_required_integer_field(json, "stepsOffset", &integer_value, error)) {
        return false;
    }
    if (integer_value < std::numeric_limits<int>::min() ||
        integer_value > std::numeric_limits<int>::max()) {
        *error = "stepsOffset must be an int32 value.";
        return false;
    }
    contract->config.beta_end = static_cast<float>(number_value);
    contract->config.steps_offset = static_cast<int>(integer_value);
    if (!qnn_required_bool_field(json, "setAlphaToOne", &contract->config.set_alpha_to_one, error) ||
        !qnn_required_bool_field(json, "skipPrkSteps", &contract->config.skip_prk_steps, error) ||
        !qnn_required_string_field(json, "finalSigmaType", &final_sigma_type, error) ||
        !qnn_required_bool_field(json, "scaleModelInput", &contract->scale_model_input, error) ||
        !qnn_required_bool_field(json, "clipSample", &contract->config.clip_sample, error) ||
        !qnn_required_number_field(json, "clipSampleRange", &number_value, error)) {
        return false;
    }
    contract->config.clip_sample_range = static_cast<float>(number_value);
    if (!qnn_required_bool_field(json, "thresholding", &contract->config.thresholding, error) ||
        !qnn_required_number_field(json, "eta", &number_value, error)) {
        return false;
    }
    if (number_value != 0.0) {
        *error = "QNN scheduler execution currently requires eta=0 because no variance-noise tensor is supplied.";
        return false;
    }
    contract->eta = static_cast<float>(number_value);
    if (!qnn_required_bool_field(json, "lowerOrderFinal", &contract->config.lower_order_final, error) ||
        !qnn_required_integer_field(json, "steps", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0 || integer_value > contract->config.num_train_timesteps) {
        *error = "steps must be in [1, numTrainTimesteps]; native does not clamp it.";
        return false;
    }
    contract->steps = static_cast<int>(integer_value);
    if (!qnn_required_integer_field(json, "expectedTimetableCount", &integer_value, error) ||
        integer_value <= 0) {
        if (error->empty()) *error = "expectedTimetableCount must be positive.";
        return false;
    }
    contract->expected_timetable_count = static_cast<size_t>(integer_value);
    if (!qnn_required_integer_field(json, "timetableCount", &integer_value, error) ||
        integer_value <= 0 ||
        static_cast<size_t>(integer_value) != contract->expected_timetable_count) {
        if (error->empty()) {
            *error = "timetableCount must exactly match expectedTimetableCount.";
        }
        return false;
    }
    if (!qnn_required_integer_field(json, "expectedUnetExecutionCount", &integer_value, error) ||
        integer_value <= 0) {
        if (error->empty()) *error = "expectedUnetExecutionCount must be positive.";
        return false;
    }
    contract->expected_unet_execution_count = static_cast<size_t>(integer_value);
    if (!qnn_required_integer_field(json, "unetExecutionCount", &integer_value, error) ||
        integer_value <= 0 ||
        static_cast<size_t>(integer_value) != contract->expected_unet_execution_count) {
        if (error->empty()) {
            *error = "unetExecutionCount must exactly match expectedUnetExecutionCount.";
        }
        return false;
    }

    scheduler = normalized_contract_enum(scheduler);
    if (scheduler == "euler" || scheduler == "euler_discrete") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
    } else if (scheduler == "ddim") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::Ddim;
    } else if (scheduler == "pndm" || scheduler == "pndm_plms") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::Pndm;
    } else if (scheduler == "dpmpp_2m" || scheduler == "dpm++_2m") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::Dpmpp2m;
    } else {
        *error = "Unsupported QNN scheduler contract value: " + scheduler;
        return false;
    }
    sample_method = normalized_contract_enum(sample_method);
    const bool sample_method_matches =
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete &&
         (sample_method == "euler" || sample_method == "euler_discrete")) ||
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::Ddim &&
         sample_method == "ddim") ||
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::Pndm &&
         (sample_method == "pndm" || sample_method == "pndm_plms")) ||
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::Dpmpp2m &&
         (sample_method == "dpmpp_2m" || sample_method == "dpm++2m" ||
          sample_method == "dpm_plus_plus_2m"));
    if (!sample_method_matches) {
        *error = "sampleMethod conflicts with scheduler: sampleMethod=" + sample_method +
            ", scheduler=" + scheduler + ".";
        return false;
    }

    prediction = normalized_contract_enum(prediction);
    if (prediction == "epsilon") {
        contract->config.prediction_type = mca::diffusion::PredictionType::Epsilon;
    } else if (prediction == "v_prediction") {
        contract->config.prediction_type = mca::diffusion::PredictionType::VPrediction;
    } else if (prediction == "sample") {
        contract->config.prediction_type = mca::diffusion::PredictionType::Sample;
    } else {
        *error = "Unsupported QNN predictionType contract value: " + prediction;
        return false;
    }

    noise_schedule = normalized_contract_enum(noise_schedule);
    if (noise_schedule == "linear") {
        contract->config.beta_schedule = mca::diffusion::BetaSchedule::Linear;
    } else if (noise_schedule == "scaled_linear") {
        contract->config.beta_schedule = mca::diffusion::BetaSchedule::ScaledLinear;
    } else if (noise_schedule == "squaredcos_cap_v2" ||
               noise_schedule == "squared_cosine_cap_v2") {
        contract->config.beta_schedule = mca::diffusion::BetaSchedule::SquaredCosineCapV2;
    } else {
        *error = "Unsupported explicit QNN noiseSchedule contract value: " + noise_schedule;
        return false;
    }

    timestep_spacing = normalized_contract_enum(timestep_spacing);
    if (timestep_spacing == "linspace") {
        contract->config.timestep_spacing = mca::diffusion::TimestepSpacing::Linspace;
    } else if (timestep_spacing == "leading") {
        contract->config.timestep_spacing = mca::diffusion::TimestepSpacing::Leading;
    } else if (timestep_spacing == "trailing") {
        contract->config.timestep_spacing = mca::diffusion::TimestepSpacing::Trailing;
    } else {
        *error = "Unsupported QNN timestepSpacing contract value: " + timestep_spacing;
        return false;
    }

    final_sigma_type = normalized_contract_enum(final_sigma_type);
    if (final_sigma_type == "zero") {
        contract->config.final_sigma_type = mca::diffusion::FinalSigmaType::Zero;
    } else if (final_sigma_type == "sigma_min") {
        contract->config.final_sigma_type = mca::diffusion::FinalSigmaType::SigmaMin;
    } else {
        *error = "Unsupported QNN finalSigmaType contract value: " + final_sigma_type;
        return false;
    }

    const bool scheduler_scales_input =
        contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
    if (contract->scale_model_input != scheduler_scales_input) {
        *error = "scaleModelInput conflicts with the selected scheduler's actual native behavior.";
        return false;
    }
    return true;
}

bool parse_qnn_pixel_range_contract(
        const std::string& json,
        mca::qnn::ImagePixelRange* pixel_range,
        std::string* error) {
    std::string wire_value;
    if (pixel_range == nullptr ||
        !qnn_required_string_field(json, "pixelRange", &wire_value, error)) {
        return false;
    }
    wire_value = normalized_contract_enum(wire_value);
    if (wire_value == "negative_one_to_one") {
        *pixel_range = mca::qnn::ImagePixelRange::NegativeOneToOne;
    } else if (wire_value == "zero_to_one") {
        *pixel_range = mca::qnn::ImagePixelRange::ZeroToOne;
    } else if (wire_value == "zero_to_255") {
        *pixel_range = mca::qnn::ImagePixelRange::ZeroTo255;
    } else {
        *error = "Unsupported explicit QNN pixelRange contract value: " + wire_value;
        return false;
    }
    return true;
}

bool parse_qnn_prompt_execution_fields(
        const std::string& json,
        std::string* prompt,
        std::string* negative_prompt,
        std::string* native_prompt_execution_sha256,
        std::string* native_prompt_binding_stage,
        std::string* error) {
    if (prompt == nullptr || negative_prompt == nullptr ||
        native_prompt_execution_sha256 == nullptr ||
        native_prompt_binding_stage == nullptr || error == nullptr) {
        return false;
    }
    try {
        const nlohmann::json root = nlohmann::json::parse(json);
        if (!root.is_object()) {
            *error = "QNN execution params must be one structured JSON object.";
            return false;
        }
        const auto prompt_value = root.find("prompt");
        const auto negative_prompt_value = root.find("negativePrompt");
        const auto prompt_sha256_value = root.find("nativePromptExecutionSha256");
        const auto prompt_stage_value = root.find("nativePromptBindingStage");
        if (prompt_value == root.end() || !prompt_value->is_string()) {
            *error = "Native execution field 'prompt' must be a string.";
            return false;
        }
        if (negative_prompt_value == root.end() || !negative_prompt_value->is_string()) {
            *error = "Native execution field 'negativePrompt' must be a string.";
            return false;
        }
        if (prompt_sha256_value == root.end() || !prompt_sha256_value->is_string()) {
            *error = "Native execution field 'nativePromptExecutionSha256' must be a string.";
            return false;
        }
        if (prompt_stage_value == root.end() || !prompt_stage_value->is_string()) {
            *error = "Native execution field 'nativePromptBindingStage' must be a string.";
            return false;
        }
        *prompt = prompt_value->get<std::string>();
        *negative_prompt = negative_prompt_value->get<std::string>();
        *native_prompt_execution_sha256 = prompt_sha256_value->get<std::string>();
        *native_prompt_binding_stage = prompt_stage_value->get<std::string>();
        if (prompt->empty()) {
            *error = "Native execution field 'prompt' must not be blank.";
            return false;
        }
        if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
                *prompt,
                *negative_prompt)) {
            *error = "Native multilingual image prompts must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax.";
            return false;
        }
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN execution params JSON is invalid: ") + exception.what();
        return false;
    }
}

bool parse_qnn_semantic_execution_contract(
        const std::string& json,
        QnnSemanticExecutionContract* contract,
        std::string* error) {
    long long integer_value = 0;
    double number_value = 0.0;
    std::string runtime;
    std::string vae_scaling_location;
    bool unconditional_branch = false;
    if (!parse_qnn_prompt_execution_fields(
            json,
            &contract->prompt,
            &contract->negative_prompt,
            &contract->native_prompt_execution_sha256,
            &contract->native_prompt_binding_stage,
            error)) {
        return false;
    }
    if (!qnn_required_string_field(json, "profileId", &contract->profile_id, error) ||
        !qnn_required_integer_field(json, "profileRevision", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0 || integer_value > std::numeric_limits<int>::max()) {
        *error = "profileRevision must be a positive int32 value.";
        return false;
    }
    contract->profile_revision = static_cast<int>(integer_value);
    if (!qnn_required_string_field(json, "modelFingerprint", &contract->model_fingerprint, error) ||
        !qnn_required_string_field(json, "runtime", &runtime, error)) {
        return false;
    }
    if (normalized_contract_enum(runtime) != "qnn_htp") {
        *error = "QNN semantic generation requires runtime=QNN_HTP.";
        return false;
    }
    if (!parse_qnn_scheduler_contract(json, &contract->scheduler, error) ||
        !qnn_required_number_field(json, "cfgScale", &number_value, error)) {
        return false;
    }
    if (number_value < 0.0 || number_value > 30.0) {
        *error = "cfgScale must be in [0, 30].";
        return false;
    }
    contract->cfg_scale = static_cast<float>(number_value);
    if (!qnn_required_bool_field(json, "useCfg", &contract->use_cfg, error) ||
        !qnn_required_bool_field(json, "unconditionalBranch", &unconditional_branch, error)) {
        return false;
    }
    if (unconditional_branch != contract->use_cfg) {
        *error = "unconditionalBranch must exactly match useCfg.";
        return false;
    }
    if (!contract->use_cfg && std::fabs(number_value - 1.0) > 1.0e-12) {
        *error = "Conditional-only execution requires cfgScale=1 when useCfg=false.";
        return false;
    }
    if (!contract->use_cfg && !contract->negative_prompt.empty()) {
        *error = "A negativePrompt cannot affect pixels when useCfg=false.";
        return false;
    }
    if (!qnn_required_integer_field(json, "seed", &integer_value, error) ||
        integer_value < 0 ||
        static_cast<unsigned long long>(integer_value) > std::numeric_limits<uint32_t>::max()) {
        if (error->empty()) *error = "seed must fit the native uint32 RNG contract.";
        return false;
    }
    contract->seed = static_cast<uint32_t>(integer_value);
    if (!qnn_required_string_field(json, "tokenizerBackend", &contract->tokenizer_backend, error) ||
        !qnn_required_integer_field(json, "tokenCount", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0) {
        *error = "tokenCount must be positive.";
        return false;
    }
    contract->token_count = static_cast<size_t>(integer_value);
    if (!qnn_required_integer_field(json, "tokenizerMaxLength", &integer_value, error) ||
        integer_value <= 0 || integer_value > std::numeric_limits<int>::max()) {
        if (error->empty()) *error = "tokenizerMaxLength must be a positive int32 value.";
        return false;
    }
    contract->tokenizer_max_length = static_cast<int>(integer_value);
    contract->tokenizer_backend = normalized_contract_enum(contract->tokenizer_backend);
    if (contract->tokenizer_backend == "tokenizers_cpp") {
        contract->tokenizer_backend = "TOKENIZERS_CPP";
    } else if (contract->tokenizer_backend == "mnn_mtok") {
        contract->tokenizer_backend = "MNN_MTOK";
    } else {
        *error = "Unsupported tokenizerBackend contract value: " + contract->tokenizer_backend;
        return false;
    }
    if (!qnn_required_bool_field(
            json,
            "promptWeightingSupported",
            &contract->prompt_weighting_supported,
            error)) {
        return false;
    }
    if (contract->prompt_weighting_supported &&
        contract->tokenizer_backend != "TOKENIZERS_CPP") {
        *error = "Prompt weighting requires the complete TOKENIZERS_CPP contract.";
        return false;
    }
    if (!qnn_required_string_field(
            json,
            "embeddingDiskDataType",
            &contract->embedding_disk_data_type,
            error)) {
        return false;
    }
    contract->embedding_disk_data_type = normalized_contract_enum(
        contract->embedding_disk_data_type);
    if (contract->embedding_disk_data_type == "graph_internal") {
        contract->embedding_disk_data_type = "GRAPH_INTERNAL";
    } else if (contract->embedding_disk_data_type == "fp16") {
        contract->embedding_disk_data_type = "FP16";
    } else if (contract->embedding_disk_data_type == "fp32") {
        contract->embedding_disk_data_type = "FP32";
    } else {
        *error = "Unsupported embeddingDiskDataType contract value: " +
            contract->embedding_disk_data_type;
        return false;
    }
    if (!qnn_required_string_field(json, "vaeScalingLocation", &vae_scaling_location, error) ||
        !qnn_required_number_field(json, "vaeScalingFactor", &number_value, error)) {
        return false;
    }
    if (number_value <= 0.0) {
        *error = "vaeScalingFactor must be finite and positive.";
        return false;
    }
    contract->vae_scaling_factor = number_value;
    vae_scaling_location = normalized_contract_enum(vae_scaling_location);
    if (vae_scaling_location == "host_before_graph") {
        contract->vae_scaling_location = QnnVaeScalingLocation::HostBeforeGraph;
    } else if (vae_scaling_location == "graph_internal") {
        contract->vae_scaling_location = QnnVaeScalingLocation::GraphInternal;
    } else if (vae_scaling_location == "none") {
        contract->vae_scaling_location = QnnVaeScalingLocation::None;
    } else {
        *error = "Unsupported explicit QNN vaeScalingLocation contract value: " +
            vae_scaling_location;
        return false;
    }
    if (contract->vae_scaling_location == QnnVaeScalingLocation::None &&
        std::fabs(contract->vae_scaling_factor - 1.0) > 1e-12) {
        *error = "vaeScalingLocation=NONE requires vaeScalingFactor=1.";
        return false;
    }
    if (!parse_qnn_pixel_range_contract(json, &contract->pixel_range, error)) {
        return false;
    }
    if (!qnn_required_integer_field(json, "width", &integer_value, error) ||
        integer_value <= 0 || integer_value > 16384 || integer_value % 8 != 0) {
        if (error->empty()) *error = "width must be a positive multiple of 8 no larger than 16384.";
        return false;
    }
    contract->width = static_cast<int>(integer_value);
    if (!qnn_required_integer_field(json, "height", &integer_value, error) ||
        integer_value <= 0 || integer_value > 16384 || integer_value % 8 != 0) {
        if (error->empty()) *error = "height must be a positive multiple of 8 no larger than 16384.";
        return false;
    }
    contract->height = static_cast<int>(integer_value);
    bool fallback = true;
    if (!qnn_required_string_field(json, "graphName", &contract->graph_name, error) ||
        !qnn_required_bool_field(json, "fallback", &fallback, error)) {
        return false;
    }
    if (fallback) {
        *error = "QNN native execution requires fallback=false.";
        return false;
    }
    if (json.find("\"vaeLatentScale\"") != std::string::npos) {
        *error = "Deprecated vaeLatentScale conflicts with the explicit VAE scaling contract.";
        return false;
    }
    return true;
}

const char* qnn_scheduler_wire_name(mca::diffusion::SchedulerAlgorithm algorithm) {
    switch (algorithm) {
        case mca::diffusion::SchedulerAlgorithm::EulerDiscrete: return "EULER";
        case mca::diffusion::SchedulerAlgorithm::Ddim: return "DDIM";
        case mca::diffusion::SchedulerAlgorithm::Pndm: return "PNDM_PLMS";
        case mca::diffusion::SchedulerAlgorithm::Dpmpp2m: return "DPMPP_2M";
    }
    return "UNKNOWN";
}

const char* qnn_prediction_wire_name(mca::diffusion::PredictionType prediction) {
    switch (prediction) {
        case mca::diffusion::PredictionType::Epsilon: return "EPSILON";
        case mca::diffusion::PredictionType::VPrediction: return "V_PREDICTION";
        case mca::diffusion::PredictionType::Sample: return "SAMPLE";
    }
    return "UNKNOWN";
}

const char* qnn_vae_scaling_wire_name(QnnVaeScalingLocation location) {
    switch (location) {
        case QnnVaeScalingLocation::HostBeforeGraph: return "HOST_BEFORE_GRAPH";
        case QnnVaeScalingLocation::GraphInternal: return "GRAPH_INTERNAL";
        case QnnVaeScalingLocation::None: return "NONE";
    }
    return "NONE";
}

double qnn_effective_vae_host_scale(const QnnSemanticExecutionContract& contract) {
    return contract.vae_scaling_location == QnnVaeScalingLocation::HostBeforeGraph
        ? 1.0 / contract.vae_scaling_factor
        : 1.0;
}

std::string qnn_pixel_range_evidence_json(
        mca::qnn::ImagePixelRange range,
        const mca::qnn::ImagePixelRangeEvidence& evidence) {
    std::ostringstream out;
    out << std::setprecision(9)
        << "\"pixelRange\":" << quote(mca::qnn::image_pixel_range_wire_name(range)) << ","
        << "\"pixelRangeConversion\":"
        << quote(mca::qnn::image_pixel_range_conversion_name(range)) << ","
        << "\"pixelRangeValueCount\":" << evidence.value_count << ","
        << "\"pixelRangeClampedValueCount\":" << evidence.clamped_value_count << ","
        << "\"pixelRangeObservedMin\":" << evidence.observed_min << ","
        << "\"pixelRangeObservedMax\":" << evidence.observed_max;
    return out.str();
}

std::string qnn_nonempty_bundle_file_named(
        const std::vector<std::string>& files,
        const std::string& expected_name) {
    const std::string expected = normalized_contract_enum(expected_name);
    for (const auto& path : files) {
        const auto separator = path.find_last_of("/\\");
        const std::string basename = separator == std::string::npos
            ? path
            : path.substr(separator + 1u);
        if (normalized_contract_enum(basename) == expected && file_size_or_zero(path) > 0) {
            return path;
        }
    }
    return "";
}

} // namespace qnn
} // namespace mca
