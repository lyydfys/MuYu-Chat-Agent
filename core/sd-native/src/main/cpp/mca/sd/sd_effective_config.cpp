#include "mca/sd/sd_effective_config.hpp"

#include <algorithm>
#include <cctype>

#include "stable-diffusion.h"

namespace mca {
namespace sd {

namespace {

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

}  // namespace

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

nlohmann::json runtime_failure(const std::string &code, const std::string &message) {
    return nlohmann::json({
            {"ok", false},
            {"errorCode", code},
            {"error", message}
    });
}

nlohmann::json native_effective_json(
        const StableDiffusionExecutionContract &contract,
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
    return nlohmann::json({
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

}  // namespace sd
}  // namespace mca
