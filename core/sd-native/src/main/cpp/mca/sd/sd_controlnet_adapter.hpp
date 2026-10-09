#ifndef MCA_SD_CONTROLNET_ADAPTER_HPP
#define MCA_SD_CONTROLNET_ADAPTER_HPP

#include <functional>
#include <string>

#include "json.hpp"
#include "stable-diffusion.h"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_contract_parser.hpp"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_input_contract.hpp"

namespace mca {
namespace sd {

/**
 * Parsed and per-image normalized ControlNet execution counters
 * representing physical model computes and residual consumption branches.
 */
struct ControlNetExecutionEvidence {
    int compute_attempt_count{0};
    int compute_success_count{0};
    int positive_compute_attempt_count{0};
    int positive_compute_success_count{0};
    int negative_compute_attempt_count{0};
    int negative_compute_success_count{0};
    int residual_consumption_count{0};
    int positive_residual_consumption_count{0};
    int negative_residual_consumption_count{0};
    int auxiliary_residual_consumption_count{0};
};

using ControlNetEvidence = ControlNetExecutionEvidence;

/**
 * Universal device admission policy:
 * ControlNet capability is verified strictly by model and component existence,
 * never by chipset/device allowlists or profile heuristics.
 */
bool validate_controlnet_component(bool uses_control_image,
                                   const std::string &controlnet_path,
                                   std::string &error_code,
                                   std::string &error_message);

/**
 * Wires the loaded control image and control strength into native generator parameters.
 */
void configure_controlnet_params(const StableDiffusionExecutionContract &contract,
                                 const LoadedInputImage &control_image,
                                 sd_img_gen_params_t &gen);

/**
 * Verifies that the native generator parameters correctly reflected the contract's
 * intent to execute ControlNet conditioning.
 */
bool verify_controlnet_wiring(const StableDiffusionExecutionContract &contract,
                              const ComponentPaths &paths,
                              const sd_img_gen_params_t &gen,
                              bool &control_image_wired_out,
                              std::string &error_field,
                              std::string &error_message);

/**
 * Extracts and normalizes ControlNet execution metrics from native context evidence.
 */
ControlNetExecutionEvidence extract_controlnet_evidence(
    const sd_image_execution_evidence_t &execution_evidence,
    int batch_count,
    const std::function<int(uint64_t, const std::string &)> &evidence_per_image);

/**
 * Validates that all ControlNet compute attempts, successes, and residual
 * consumptions are completely accounted for by positive, negative, and auxiliary branches.
 */
bool verify_controlnet_branch_classification(
    const sd_image_execution_evidence_t &execution_evidence,
    std::string &error_field,
    std::string &error_message);

/**
 * Validates ControlNet execution evidence against UNet and CFG branch execution counts.
 * Rejects silent omissions or extraneous work.
 */
bool verify_controlnet_execution_evidence(
    const ControlNetExecutionEvidence &evidence,
    bool control_image_wired,
    int actual_unet_execution_count,
    int actual_positive_execution_count,
    int actual_negative_execution_count,
    std::string &error_field,
    std::string &error_message);

inline bool verify_controlnet_evidence(
    const ControlNetExecutionEvidence &evidence,
    bool control_image_wired,
    int actual_unet_execution_count,
    int actual_positive_execution_count,
    int actual_negative_execution_count,
    std::string &error_field,
    std::string &error_message) {
    return verify_controlnet_execution_evidence(
        evidence, control_image_wired, actual_unet_execution_count,
        actual_positive_execution_count, actual_negative_execution_count,
        error_field, error_message);
}

/**
 * Serializes ControlNet execution evidence to JSON dictionary.
 */
nlohmann::json format_controlnet_evidence_json(const ControlNetExecutionEvidence &evidence);

/**
 * Populates nativeEffective JSON with ControlNet execution parameters and evidence.
 */
void populate_controlnet_effective_json(
    nlohmann::json &native_effective,
    const ControlNetExecutionEvidence &evidence,
    bool control_image_wired,
    bool actual_control_image_consumed,
    double contract_control_strength,
    float gen_control_strength,
    const LoadedInputImage &control_image);

/**
 * Populates top-level generation response JSON with ControlNet image metadata.
 */
void populate_controlnet_response_json(
    nlohmann::json &out,
    const nlohmann::json &native_effective,
    bool control_image_wired,
    bool actual_control_image_consumed,
    const LoadedInputImage &control_image);

} // namespace sd
} // namespace mca

#endif // MCA_SD_CONTROLNET_ADAPTER_HPP
