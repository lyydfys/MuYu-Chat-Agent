#ifndef MCA_SD_EFFECTIVE_CONFIG_HPP
#define MCA_SD_EFFECTIVE_CONFIG_HPP

#include <string>

#include "json.hpp"
#include "mca/sd/sd_contract_parser.hpp"

namespace mca {
namespace sd {

/**
 * Maps numeric prediction enum to wire contract prediction type string
 * ("EPSILON", "V_PREDICTION", "FLOW"), or throws unsupported_contract.
 */
std::string observed_prediction_wire(int prediction);

/**
 * Builds standard runtime failure JSON object with ok=false, errorCode, and error message.
 */
nlohmann::json runtime_failure(const std::string &code, const std::string &message);

/**
 * Constructs native effective generation configuration JSON describing the
 * contract parameters and verified runtime execution evidence.
 */
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
        int actual_height);

}  // namespace sd
}  // namespace mca

#endif  // MCA_SD_EFFECTIVE_CONFIG_HPP
