#include "mca/mnn/mnn_protocol_parser.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <limits>
#include <utility>

#include "execution_asset_binding.hpp"
#include "image_conditioning.hpp"

namespace mca {
namespace mnn {

using json = nlohmann::json;

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

bool is_blank_text(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    });
}

void append_mnn_u32_little_endian(uint32_t value, std::vector<uint8_t>& payload) {
    for (unsigned shift = 0U; shift < 32U; shift += 8U) {
        payload.push_back(static_cast<uint8_t>((value >> shift) & UINT32_C(0xff)));
    }
}

void append_mnn_u64_little_endian(uint64_t value, std::vector<uint8_t>& payload) {
    for (unsigned shift = 0U; shift < 64U; shift += 8U) {
        payload.push_back(static_cast<uint8_t>((value >> shift) & UINT64_C(0xff)));
    }
}

std::string mnn_token_id_fingerprint(const std::vector<int>& ids) {
    if (ids.empty()) return {};
    static const std::string domain = "mca.mnn.executed.token.ids.v1";
    std::vector<uint8_t> payload(domain.begin(), domain.end());
    append_mnn_u64_little_endian(static_cast<uint64_t>(ids.size()), payload);
    for (const int id : ids) {
        if (id < 0) return {};
        append_mnn_u32_little_endian(static_cast<uint32_t>(id), payload);
    }
    return mca::image::sha256_hex_bytes(payload);
}

std::string normalize_mnn_contract_enum(std::string value) {
    value = normalize_mnn_diffusion_identifier(std::move(value));
    std::string normalized;
    normalized.reserve(value.size());
    for (char ch : value) {
        if (ch == '-' || ch == '.' || ch == ' ') {
            normalized.push_back('_');
        } else if (ch == '+') {
            normalized.push_back('p');
        } else {
            normalized.push_back(ch);
        }
    }
    return normalized;
}

bool mnn_contract_value(
        const json& params,
        const char* key,
        const json*& value,
        std::string& error) {
    const auto it = params.find(key);
    if (it == params.end() || it->is_null()) {
        error = std::string("Missing required MNN image execution field '") + key + "'.";
        return false;
    }
    value = &(*it);
    return true;
}

bool mnn_contract_string(
        const json& params,
        const char* key,
        std::string& value,
        std::string& error,
        bool allowEmpty) {
    const json* raw = nullptr;
    if (!mnn_contract_value(params, key, raw, error)) return false;
    if (!raw->is_string()) {
        error = std::string("MNN image execution field '") + key + "' must be a string.";
        return false;
    }
    value = raw->get<std::string>();
    if (!allowEmpty && is_blank_text(value)) {
        error = std::string("MNN image execution field '") + key + "' must not be blank.";
        return false;
    }
    return true;
}

bool mnn_contract_integer(
        const json& params,
        const char* key,
        int64_t& value,
        std::string& error) {
    const json* raw = nullptr;
    if (!mnn_contract_value(params, key, raw, error)) return false;
    try {
        if (raw->is_number_unsigned()) {
            const auto parsed = raw->get<uint64_t>();
            if (parsed > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
                error = std::string("MNN image execution field '") + key + "' exceeds int64 range.";
                return false;
            }
            value = static_cast<int64_t>(parsed);
            return true;
        }
        if (raw->is_number_integer()) {
            value = raw->get<int64_t>();
            return true;
        }
    } catch (const std::exception&) {
        error = std::string("MNN image execution field '") + key + "' exceeds int64 range.";
        return false;
    }
    error = std::string("MNN image execution field '") + key + "' must be an integer.";
    return false;
}

bool parse_mnn_text_encoder_asset_descriptor(
        const json& source,
        const char* label,
        std::string& path,
        std::string& sha256,
        uint64_t& sizeBytes,
        std::string& error) {
    const auto pathIt = source.find("path");
    const auto shaIt = source.find("sha256");
    const auto sizeIt = source.find("sizeBytes");
    if (pathIt == source.end() || !pathIt->is_string() ||
        shaIt == source.end() || !shaIt->is_string() ||
        sizeIt == source.end() ||
            (!sizeIt->is_number_integer() && !sizeIt->is_number_unsigned())) {
        error = std::string(label) + " must contain path, sha256, and sizeBytes.";
        return false;
    }
    path = pathIt->get<std::string>();
    sha256 = shaIt->get<std::string>();
    if (path.empty() || !mca::image::execution_assets::lowercase_sha256(sha256)) {
        error = std::string(label) + " path or SHA-256 is invalid.";
        return false;
    }
    try {
        if (sizeIt->is_number_unsigned()) {
            sizeBytes = sizeIt->get<uint64_t>();
        } else {
            const int64_t signedSize = sizeIt->get<int64_t>();
            if (signedSize <= 0) {
                error = std::string(label) + " sizeBytes must be positive.";
                return false;
            }
            sizeBytes = static_cast<uint64_t>(signedSize);
        }
    } catch (const std::exception&) {
        error = std::string(label) + " sizeBytes exceeds uint64 range.";
        return false;
    }
    if (sizeBytes == 0U) {
        error = std::string(label) + " sizeBytes must be positive.";
        return false;
    }
    return true;
}

bool parse_mnn_text_encoder_asset_request(
        const json& params,
        MnnTextEncoderAssetRequest& request,
        std::string& error) {
    const auto evidenceIt = params.find("nativeTextEncoderEvidence");
    if (evidenceIt == params.end() || evidenceIt->is_null()) return true;
    if (!evidenceIt->is_object()) {
        error = "nativeTextEncoderEvidence must be an object when supplied.";
        return false;
    }
    if (!parse_mnn_text_encoder_asset_descriptor(
            *evidenceIt,
            "nativeTextEncoderEvidence",
            request.path,
            request.sha256,
            request.size_bytes,
            error)) {
        return false;
    }
    const auto auxiliaryIt = evidenceIt->find("auxiliaryAssets");
    if (auxiliaryIt != evidenceIt->end()) {
        if (!auxiliaryIt->is_array() || auxiliaryIt->size() > 1U) {
            error = "nativeTextEncoderEvidence.auxiliaryAssets must contain at most one MNN .weight asset.";
            return false;
        }
        if (!auxiliaryIt->empty()) {
            if (!auxiliaryIt->front().is_object() ||
                !parse_mnn_text_encoder_asset_descriptor(
                    auxiliaryIt->front(),
                    "nativeTextEncoderEvidence.auxiliaryAssets[0]",
                    request.weight_path,
                    request.weight_sha256,
                    request.weight_size_bytes,
                    error)) {
                return false;
            }
            request.has_weight = true;
        }
    }
    request.declared = true;
    return true;
}

bool parse_mnn_language_proof_sha256(
        const json& params,
        std::string& proofSha256,
        std::string& error) {
    const auto proofIt = params.find("languageProofSha256");
    if (proofIt == params.end() || proofIt->is_null()) return true;
    if (!proofIt->is_string()) {
        error = "languageProofSha256 must be a SHA-256 string when supplied.";
        return false;
    }
    proofSha256 = proofIt->get<std::string>();
    if (!mca::image::execution_assets::lowercase_sha256(proofSha256)) {
        error = "languageProofSha256 must be a 64-character SHA-256 value.";
        return false;
    }
    return true;
}

// English-dominant product paths pass safe ASCII tags only.  Native MNN execution admits other
// UTF-8 prompt bytes solely when the caller supplied the descriptor-backed text-encoder evidence
// that is rehashed before and after actual graph consumption.
bool mnn_prompt_contains_non_ascii(const std::string& value) {
    return std::any_of(value.begin(), value.end(), [](unsigned char byte) {
        return byte >= 0x80U;
    });
}

bool mnn_contract_number(
        const json& params,
        const char* key,
        double& value,
        std::string& error) {
    const json* raw = nullptr;
    if (!mnn_contract_value(params, key, raw, error)) return false;
    if (!raw->is_number()) {
        error = std::string("MNN image execution field '") + key + "' must be numeric.";
        return false;
    }
    try {
        value = raw->get<double>();
    } catch (const std::exception&) {
        error = std::string("MNN image execution field '") + key + "' is outside native numeric range.";
        return false;
    }
    if (!std::isfinite(value)) {
        error = std::string("MNN image execution field '") + key + "' must be finite.";
        return false;
    }
    return true;
}

bool mnn_contract_boolean(
        const json& params,
        const char* key,
        bool& value,
        std::string& error) {
    const json* raw = nullptr;
    if (!mnn_contract_value(params, key, raw, error)) return false;
    if (!raw->is_boolean()) {
        error = std::string("MNN image execution field '") + key + "' must be a boolean.";
        return false;
    }
    value = raw->get<bool>();
    return true;
}

bool parse_mnn_scheduler_execution_contract(
        const json& params,
        MnnSchedulerExecutionContract& contract,
        std::string& error) {
    std::string scheduler;
    std::string sampleMethod;
    std::string prediction;
    std::string noiseSchedule;
    std::string timestepSpacing;
    std::string finalSigmaType;
    int64_t integerValue = 0;
    double numberValue = 0.0;
    if (!mnn_contract_string(params, "scheduler", scheduler, error) ||
        !mnn_contract_string(params, "sampleMethod", sampleMethod, error) ||
        !mnn_contract_string(params, "predictionType", prediction, error) ||
        !mnn_contract_integer(params, "numTrainTimesteps", integerValue, error)) {
        return false;
    }
    if (integerValue <= 0 || integerValue > std::numeric_limits<int>::max()) {
        error = "numTrainTimesteps must be a positive int32 value.";
        return false;
    }
    contract.config.num_train_timesteps = static_cast<int>(integerValue);
    if (!mnn_contract_string(params, "noiseSchedule", noiseSchedule, error) ||
        !mnn_contract_number(params, "betaStart", numberValue, error)) {
        return false;
    }
    contract.config.beta_start = static_cast<float>(numberValue);
    if (!mnn_contract_number(params, "betaEnd", numberValue, error) ||
        !mnn_contract_string(params, "timestepSpacing", timestepSpacing, error) ||
        !mnn_contract_integer(params, "stepsOffset", integerValue, error)) {
        return false;
    }
    if (integerValue < std::numeric_limits<int>::min() ||
        integerValue > std::numeric_limits<int>::max()) {
        error = "stepsOffset must fit int32.";
        return false;
    }
    contract.config.beta_end = static_cast<float>(numberValue);
    contract.config.steps_offset = static_cast<int>(integerValue);
    if (!(contract.config.beta_start > 0.0f) ||
        !(contract.config.beta_end > contract.config.beta_start) ||
        contract.config.beta_end >= 1.0f) {
        error = "betaStart/betaEnd must satisfy 0 < betaStart < betaEnd < 1.";
        return false;
    }
    if (!mnn_contract_boolean(params, "setAlphaToOne", contract.config.set_alpha_to_one, error) ||
        !mnn_contract_boolean(params, "skipPrkSteps", contract.config.skip_prk_steps, error) ||
        !mnn_contract_string(params, "finalSigmaType", finalSigmaType, error) ||
        !mnn_contract_boolean(params, "scaleModelInput", contract.scale_model_input, error) ||
        !mnn_contract_boolean(params, "clipSample", contract.config.clip_sample, error) ||
        !mnn_contract_number(params, "clipSampleRange", numberValue, error)) {
        return false;
    }
    if (numberValue <= 0.0) {
        error = "clipSampleRange must be finite and positive.";
        return false;
    }
    contract.config.clip_sample_range = static_cast<float>(numberValue);
    if (!mnn_contract_boolean(params, "thresholding", contract.config.thresholding, error) ||
        !mnn_contract_number(params, "eta", numberValue, error)) {
        return false;
    }
    if (numberValue != 0.0) {
        error = "MNN direct scheduler requires eta=0 because no variance-noise tensor is supplied.";
        return false;
    }
    contract.eta = static_cast<float>(numberValue);
    if (!mnn_contract_boolean(params, "lowerOrderFinal", contract.config.lower_order_final, error) ||
        !mnn_contract_integer(params, "steps", integerValue, error)) {
        return false;
    }
    if (integerValue <= 0 || integerValue > contract.config.num_train_timesteps) {
        error = "steps must be in [1, numTrainTimesteps]; native does not clamp it.";
        return false;
    }
    contract.steps = static_cast<int>(integerValue);
    if (!mnn_contract_integer(params, "expectedTimetableCount", integerValue, error) ||
        integerValue <= 0) {
        if (error.empty()) error = "expectedTimetableCount must be positive.";
        return false;
    }
    contract.expected_timetable_count = static_cast<size_t>(integerValue);
    if (!mnn_contract_integer(params, "expectedUnetExecutionCount", integerValue, error) ||
        integerValue <= 0) {
        if (error.empty()) error = "expectedUnetExecutionCount must be positive.";
        return false;
    }
    contract.expected_unet_execution_count = static_cast<size_t>(integerValue);

    scheduler = normalize_mnn_contract_enum(std::move(scheduler));
    sampleMethod = normalize_mnn_contract_enum(std::move(sampleMethod));
    if (scheduler == "euler" || scheduler == "euler_discrete") {
        contract.config.algorithm = mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
        if (sampleMethod != "euler" && sampleMethod != "euler_discrete") {
            error = "sampleMethod conflicts with scheduler=EULER.";
            return false;
        }
    } else if (scheduler == "ddim") {
        contract.config.algorithm = mca::diffusion::SchedulerAlgorithm::Ddim;
        if (sampleMethod != "ddim") {
            error = "sampleMethod conflicts with scheduler=DDIM.";
            return false;
        }
    } else if (scheduler == "pndm" || scheduler == "pndm_plms") {
        contract.config.algorithm = mca::diffusion::SchedulerAlgorithm::Pndm;
        if (sampleMethod != "pndm" && sampleMethod != "pndm_plms" && sampleMethod != "plms") {
            error = "sampleMethod conflicts with scheduler=PNDM_PLMS.";
            return false;
        }
    } else if (scheduler == "dpmpp_2m" || scheduler == "dpmpp2m") {
        contract.config.algorithm = mca::diffusion::SchedulerAlgorithm::Dpmpp2m;
        if (sampleMethod != "dpmpp_2m" && sampleMethod != "dpmpp2m") {
            error = "sampleMethod conflicts with scheduler=DPMPP_2M.";
            return false;
        }
    } else {
        error = "Unsupported MNN direct scheduler contract value: " + scheduler;
        return false;
    }

    prediction = normalize_mnn_contract_enum(std::move(prediction));
    if (prediction == "epsilon") {
        contract.config.prediction_type = mca::diffusion::PredictionType::Epsilon;
    } else if (prediction == "v_prediction") {
        contract.config.prediction_type = mca::diffusion::PredictionType::VPrediction;
    } else if (prediction == "sample") {
        contract.config.prediction_type = mca::diffusion::PredictionType::Sample;
    } else {
        error = "Unsupported MNN direct predictionType contract value: " + prediction;
        return false;
    }

    noiseSchedule = normalize_mnn_contract_enum(std::move(noiseSchedule));
    if (noiseSchedule == "linear") {
        contract.config.beta_schedule = mca::diffusion::BetaSchedule::Linear;
    } else if (noiseSchedule == "scaled_linear") {
        contract.config.beta_schedule = mca::diffusion::BetaSchedule::ScaledLinear;
    } else if (noiseSchedule == "squaredcos_cap_v2" ||
               noiseSchedule == "squared_cosine_cap_v2") {
        contract.config.beta_schedule = mca::diffusion::BetaSchedule::SquaredCosineCapV2;
    } else {
        error = "Unsupported explicit MNN noiseSchedule contract value: " + noiseSchedule;
        return false;
    }

    timestepSpacing = normalize_mnn_contract_enum(std::move(timestepSpacing));
    if (timestepSpacing == "linspace") {
        contract.config.timestep_spacing = mca::diffusion::TimestepSpacing::Linspace;
    } else if (timestepSpacing == "leading") {
        contract.config.timestep_spacing = mca::diffusion::TimestepSpacing::Leading;
    } else if (timestepSpacing == "trailing") {
        contract.config.timestep_spacing = mca::diffusion::TimestepSpacing::Trailing;
    } else {
        error = "Unsupported MNN timestepSpacing contract value: " + timestepSpacing;
        return false;
    }

    finalSigmaType = normalize_mnn_contract_enum(std::move(finalSigmaType));
    if (finalSigmaType == "zero") {
        contract.config.final_sigma_type = mca::diffusion::FinalSigmaType::Zero;
    } else if (finalSigmaType == "sigma_min") {
        contract.config.final_sigma_type = mca::diffusion::FinalSigmaType::SigmaMin;
    } else {
        error = "Unsupported MNN finalSigmaType contract value: " + finalSigmaType;
        return false;
    }

    const bool schedulerScalesInput =
            contract.config.algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
    if (contract.scale_model_input != schedulerScalesInput) {
        error = "scaleModelInput conflicts with the selected scheduler's actual native behavior.";
        return false;
    }
    return true;
}

bool parse_mnn_semantic_execution_contract(
        const json& params,
        MnnSemanticExecutionContract& contract,
        std::string& error) {
    int64_t integerValue = 0;
    double numberValue = 0.0;
    std::string runtime;
    bool hasUnconditionalBranch = false;
    std::string vaeScalingLocation;
    if (!mnn_contract_string(params, "profileId", contract.profile_id, error) ||
        !mnn_contract_integer(params, "profileRevision", integerValue, error)) {
        return false;
    }
    if (integerValue <= 0 || integerValue > std::numeric_limits<int>::max()) {
        error = "profileRevision must be a positive int32 value.";
        return false;
    }
    contract.profile_revision = static_cast<int>(integerValue);
    if (!mnn_contract_string(params, "modelFingerprint", contract.model_fingerprint, error) ||
        !mnn_contract_string(params, "runtime", runtime, error)) {
        return false;
    }
    if (normalize_mnn_contract_enum(runtime) != "mnn_diffusion") {
        error = "MNN direct generation requires runtime=MNN_DIFFUSION.";
        return false;
    }
    if (!parse_mnn_scheduler_execution_contract(params, contract.scheduler, error) ||
        !mnn_contract_number(params, "cfgScale", numberValue, error)) {
        return false;
    }
    if (numberValue < 0.0 || numberValue > 30.0) {
        error = "cfgScale must be in [0, 30].";
        return false;
    }
    contract.cfg_scale = static_cast<float>(numberValue);
    if (!mnn_contract_boolean(params, "useCfg", contract.use_cfg, error) ||
        !mnn_contract_boolean(params, "unconditionalBranch", hasUnconditionalBranch, error)) {
        return false;
    }
    if (hasUnconditionalBranch != contract.use_cfg) {
        error = "unconditionalBranch must exactly match useCfg.";
        return false;
    }
    if (!mnn_contract_integer(params, "seed", integerValue, error) ||
        integerValue < 0 ||
        static_cast<uint64_t>(integerValue) > std::numeric_limits<uint32_t>::max()) {
        if (error.empty()) error = "seed must fit the native uint32 RNG contract.";
        return false;
    }
    contract.seed = static_cast<uint32_t>(integerValue);
    const auto randomSeedIt = params.find("randomSeed");
    if (randomSeedIt != params.end() && !randomSeedIt->is_null()) {
        int64_t randomSeed = 0;
        if (!mnn_contract_integer(params, "randomSeed", randomSeed, error)) return false;
        if (randomSeed != integerValue) {
            error = "randomSeed conflicts with the required seed field.";
            return false;
        }
    }
    if (!mnn_contract_string(params, "tokenizerBackend", contract.tokenizer_backend, error) ||
        !mnn_contract_integer(params, "tokenCount", integerValue, error)) {
        return false;
    }
    if (integerValue <= 0) {
        error = "tokenCount must be positive.";
        return false;
    }
    contract.token_count = static_cast<size_t>(integerValue);
    contract.tokenizer_backend = normalize_mnn_contract_enum(contract.tokenizer_backend);
    if (contract.tokenizer_backend == "tokenizers_cpp") {
        contract.tokenizer_backend = "TOKENIZERS_CPP";
    } else if (contract.tokenizer_backend == "mnn_mtok") {
        contract.tokenizer_backend = "MNN_MTOK";
    } else {
        error = "Unsupported MNN tokenizerBackend contract value: " + contract.tokenizer_backend;
        return false;
    }
    if (!mnn_contract_integer(params, "tokenizerBosId", integerValue, error) ||
        integerValue < std::numeric_limits<int>::min() ||
        integerValue > std::numeric_limits<int>::max()) {
        if (error.empty()) error = "tokenizerBosId must fit int32.";
        return false;
    }
    contract.tokenizer_bos_id = static_cast<int>(integerValue);
    if (!mnn_contract_integer(params, "tokenizerEosId", integerValue, error) ||
        integerValue < std::numeric_limits<int>::min() ||
        integerValue > std::numeric_limits<int>::max()) {
        if (error.empty()) error = "tokenizerEosId must fit int32.";
        return false;
    }
    contract.tokenizer_eos_id = static_cast<int>(integerValue);
    if (!mnn_contract_integer(params, "tokenizerPadId", integerValue, error) ||
        integerValue < std::numeric_limits<int>::min() ||
        integerValue > std::numeric_limits<int>::max()) {
        if (error.empty()) error = "tokenizerPadId must fit int32.";
        return false;
    }
    contract.tokenizer_pad_id = static_cast<int>(integerValue);
    if (!mnn_contract_integer(params, "tokenizerMaxLength", integerValue, error) ||
        integerValue <= 0 || integerValue > std::numeric_limits<int>::max()) {
        if (error.empty()) error = "tokenizerMaxLength must be a positive int32 value.";
        return false;
    }
    contract.tokenizer_max_length = static_cast<int>(integerValue);
    const size_t expectedTokenCount = static_cast<size_t>(contract.tokenizer_max_length) *
            (contract.use_cfg ? 2U : 1U);
    if (contract.tokenizer_max_length != 77 || contract.token_count != expectedTokenCount) {
        error = "The current MNN direct CLIP graph requires tokenizerMaxLength=77 and "
                "tokenCount=154 with CFG or tokenCount=77 without CFG.";
        return false;
    }
    if (!mnn_contract_boolean(
            params,
            "promptWeightingSupported",
            contract.prompt_weighting_supported,
            error)) {
        return false;
    }
    if (contract.prompt_weighting_supported &&
            contract.tokenizer_backend != "TOKENIZERS_CPP") {
        error = "Prompt weighting requires the complete TOKENIZERS_CPP contract.";
        return false;
    }
    if (!mnn_contract_string(
            params,
            "embeddingDiskDataType",
            contract.embedding_disk_data_type,
            error)) {
        return false;
    }
    contract.embedding_disk_data_type = normalize_mnn_contract_enum(
            contract.embedding_disk_data_type);
    if (contract.embedding_disk_data_type == "fp16") {
        contract.embedding_disk_data_type = "FP16";
    } else if (contract.embedding_disk_data_type == "fp32") {
        contract.embedding_disk_data_type = "FP32";
    } else if (contract.embedding_disk_data_type == "graph_internal") {
        contract.embedding_disk_data_type = "GRAPH_INTERNAL";
    } else if (contract.embedding_disk_data_type == "runtime_native") {
        contract.embedding_disk_data_type = "RUNTIME_NATIVE";
    } else {
        error = "Unsupported MNN embeddingDiskDataType contract value: " +
                contract.embedding_disk_data_type;
        return false;
    }
    if (!mnn_contract_string(params, "vaeScalingLocation", vaeScalingLocation, error) ||
        !mnn_contract_number(params, "vaeScalingFactor", numberValue, error)) {
        return false;
    }
    if (numberValue <= 0.0) {
        error = "vaeScalingFactor must be finite and positive.";
        return false;
    }
    contract.vae_scaling_factor = numberValue;
    vaeScalingLocation = normalize_mnn_contract_enum(std::move(vaeScalingLocation));
    if (vaeScalingLocation == "host_before_graph") {
        contract.vae_scaling_location = MnnVaeScalingLocation::HostBeforeGraph;
    } else if (vaeScalingLocation == "graph_internal") {
        contract.vae_scaling_location = MnnVaeScalingLocation::GraphInternal;
    } else if (vaeScalingLocation == "none") {
        contract.vae_scaling_location = MnnVaeScalingLocation::None;
    } else {
        error = "Unsupported MNN vaeScalingLocation contract value: " + vaeScalingLocation;
        return false;
    }
    if (contract.vae_scaling_location == MnnVaeScalingLocation::None &&
        std::fabs(contract.vae_scaling_factor - 1.0) > 1e-12) {
        error = "vaeScalingLocation=NONE requires vaeScalingFactor=1.";
        return false;
    }
    if (params.contains("pixelRange") && !params.at("pixelRange").is_null()) {
        if (!mnn_contract_string(params, "pixelRange", contract.vae_output_range, error)) {
            return false;
        }
        contract.vae_output_range = normalize_mnn_contract_enum(contract.vae_output_range);
        // normalize_mnn_contract_enum() deliberately emits the wire form in
        // lower snake_case (for example, negative_one_to_one).  Compare
        // against that normalized representation; the previous uppercase
        // comparison rejected valid Kotlin contracts before the graph ran.
        if (contract.vae_output_range != "negative_one_to_one" &&
            contract.vae_output_range != "zero_to_one" &&
            contract.vae_output_range != "zero_to_255") {
            error = "Unsupported MNN pixelRange contract value: " + contract.vae_output_range;
            return false;
        }
    }
    if (params.contains("vaeLatentScale")) {
        error = "Deprecated vaeLatentScale conflicts with the explicit VAE scaling contract.";
        return false;
    }
    if (!mnn_contract_integer(params, "width", integerValue, error) ||
        integerValue != 512) {
        if (error.empty()) error = "MNN direct width must be exactly 512.";
        return false;
    }
    contract.width = static_cast<int>(integerValue);
    if (!mnn_contract_integer(params, "height", integerValue, error) ||
        integerValue != 512) {
        if (error.empty()) error = "MNN direct height must be exactly 512.";
        return false;
    }
    contract.height = static_cast<int>(integerValue);
    if (!mnn_contract_string(params, "graphName", contract.graph_name, error) ||
        !mnn_contract_boolean(params, "fallback", contract.fallback, error)) {
        return false;
    }
    if (contract.fallback) {
        error = "MNN direct native execution cannot claim fallback before one occurs.";
        return false;
    }
    if (!mnn_contract_string(params, "negativePrompt", contract.negative_prompt, error, true) ||
        !mnn_contract_string(params, "family", contract.family, error) ||
        !mnn_contract_string(params, "backendMode", contract.backend_mode, error) ||
        !mnn_contract_string(params, "runner", contract.runner, error) ||
        !mnn_contract_integer(params, "threads", integerValue, error)) {
        return false;
    }
    if (is_blank_text(contract.negative_prompt)) contract.negative_prompt.clear();
    contract.family = normalize_mnn_contract_enum(contract.family);
    if (contract.family != "sd15" && contract.family != "sd1_5") {
        error = "The strict MNN direct contract currently supports SD15 graphs only.";
        return false;
    }
    std::string canonicalBackend;
    if (!canonical_mnn_diffusion_backend(contract.backend_mode, canonicalBackend, error)) {
        return false;
    }
    contract.backend_mode = canonicalBackend;
    contract.runner = normalize_mnn_contract_enum(contract.runner);
    if (contract.runner != "direct" && contract.runner != "module") {
        error = "Unsupported strict MNN runner contract value: " + contract.runner;
        return false;
    }
    if (integerValue <= 0 || integerValue > 64) {
        error = "MNN direct threads must be in [1, 64]; native does not clamp it.";
        return false;
    }
    contract.threads = static_cast<int>(integerValue);
    if (!mnn_contract_integer(params, "memoryMode", integerValue, error) ||
        integerValue < 0 || integerValue > 2) {
        if (error.empty()) error = "memoryMode must be 0, 1, or 2; native does not clamp it.";
        return false;
    }
    contract.memory_mode = static_cast<int>(integerValue);
    if (contract.runner == "direct" && contract.memory_mode != 0) {
        error = "MNN direct runner requires memoryMode=0; module memory policies are not silently applied.";
        return false;
    }
    if (params.contains("tokenEmbeddingMode")) {
        error = "tokenEmbeddingMode is obsolete; token_emb.bin dtype is determined only by its exact byte size.";
        return false;
    }
    if (!parse_mnn_text_encoder_asset_request(
            params, contract.text_encoder_asset, error)) {
        return false;
    }
    if (!parse_mnn_language_proof_sha256(
            params, contract.language_proof_sha256, error)) {
        return false;
    }
    if (!contract.language_proof_sha256.empty() &&
        !contract.text_encoder_asset.declared) {
        error = "languageProofSha256 requires complete nativeTextEncoderEvidence.";
        return false;
    }
    if (!contract.use_cfg && !contract.negative_prompt.empty()) {
        error = "A negativePrompt cannot affect pixels when useCfg=false.";
        return false;
    }
    const auto textualInversionCount = params.find("textualInversionCount");
    if (textualInversionCount != params.end()) {
        if (!textualInversionCount->is_number_integer() ||
            textualInversionCount->get<int64_t>() <= 0 ||
            textualInversionCount->get<int64_t>() > 8) {
            error = "textualInversionCount must be in [1, 8] when supplied.";
            return false;
        }
        if (contract.runner != "direct" ||
            contract.tokenizer_backend != "TOKENIZERS_CPP" ||
            !mnn_contract_uses_community_clip(contract)) {
            error = "Textual inversion requires a direct host-writable CLIP input_embedding topology.";
            return false;
        }
        contract.textual_inversion_json = params.dump();
    }
    if (!mnn_contract_integer(params, "timetableCount", integerValue, error) ||
        integerValue <= 0 ||
        static_cast<size_t>(integerValue) != contract.scheduler.expected_timetable_count) {
        if (error.empty()) error = "timetableCount must equal expectedTimetableCount.";
        return false;
    }
    if (!mnn_contract_integer(params, "unetExecutionCount", integerValue, error) ||
        integerValue <= 0 ||
        static_cast<size_t>(integerValue) != contract.scheduler.expected_unet_execution_count) {
        if (error.empty()) error = "unetExecutionCount must equal expectedUnetExecutionCount.";
        return false;
    }
    const size_t branchCount = contract.use_cfg ? 2U : 1U;
    if (contract.scheduler.expected_timetable_count >
        std::numeric_limits<size_t>::max() / branchCount ||
        contract.scheduler.expected_timetable_count * branchCount !=
            contract.scheduler.expected_unet_execution_count) {
        error = "expectedUnetExecutionCount does not match timetableCount and CFG branches.";
        return false;
    }
    return true;
}

} // namespace mnn
} // namespace mca
