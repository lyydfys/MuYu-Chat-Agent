#include "mca/sd/sd_controlnet_adapter.hpp"

namespace mca {
namespace sd {

bool validate_controlnet_component(bool uses_control_image,
                                   const std::string &controlnet_path,
                                   std::string &error_code,
                                   std::string &error_message) {
    if (uses_control_image && controlnet_path.empty()) {
        error_code = "CONTROLNET_COMPONENT_MISSING";
        error_message = "control generation requires a concrete ControlNet model component";
        return false;
    }
    return true;
}

void configure_controlnet_params(const StableDiffusionExecutionContract &contract,
                                 const LoadedInputImage &control_image,
                                 sd_img_gen_params_t &gen) {
    gen.control_strength = static_cast<float>(contract.control_strength);
    if (!control_image.pixels.empty()) {
        gen.control_image = control_image.view();
    }
}

bool verify_controlnet_wiring(const StableDiffusionExecutionContract &contract,
                              const ComponentPaths &paths,
                              const sd_img_gen_params_t &gen,
                              bool &control_image_wired_out,
                              std::string &error_field,
                              std::string &error_message) {
    const bool uses_control = task_uses_control_image(contract);
    control_image_wired_out = uses_control &&
            gen.control_image.data != nullptr &&
            gen.control_image.width > 0u &&
            gen.control_image.height > 0u &&
            !paths.control_net.empty();
    if (uses_control != control_image_wired_out) {
        error_field = "controlImagePath";
        error_message = "the prepared control image was not wired into a concrete ControlNet execution";
        return false;
    }
    return true;
}

ControlNetExecutionEvidence extract_controlnet_evidence(
    const sd_image_execution_evidence_t &execution_evidence,
    int batch_count,
    const std::function<int(uint64_t, const std::string &)> &evidence_per_image) {
    (void)batch_count;
    ControlNetExecutionEvidence evidence;
    evidence.compute_attempt_count = evidence_per_image(
            execution_evidence.control_net_compute_attempt_count,
            "controlNetComputeAttemptCount");
    evidence.compute_success_count = evidence_per_image(
            execution_evidence.control_net_compute_success_count,
            "controlNetComputeSuccessCount");
    evidence.positive_compute_attempt_count = evidence_per_image(
            execution_evidence.positive_control_net_compute_attempt_count,
            "positiveControlNetComputeAttemptCount");
    evidence.positive_compute_success_count = evidence_per_image(
            execution_evidence.positive_control_net_compute_success_count,
            "positiveControlNetComputeSuccessCount");
    evidence.negative_compute_attempt_count = evidence_per_image(
            execution_evidence.negative_control_net_compute_attempt_count,
            "negativeControlNetComputeAttemptCount");
    evidence.negative_compute_success_count = evidence_per_image(
            execution_evidence.negative_control_net_compute_success_count,
            "negativeControlNetComputeSuccessCount");
    evidence.residual_consumption_count = evidence_per_image(
            execution_evidence.control_net_residual_consumption_count,
            "controlNetResidualConsumptionCount");
    evidence.positive_residual_consumption_count = evidence_per_image(
            execution_evidence.positive_control_net_residual_consumption_count,
            "positiveControlNetResidualConsumptionCount");
    evidence.negative_residual_consumption_count = evidence_per_image(
            execution_evidence.negative_control_net_residual_consumption_count,
            "negativeControlNetResidualConsumptionCount");
    evidence.auxiliary_residual_consumption_count = evidence_per_image(
            execution_evidence.auxiliary_control_net_residual_consumption_count,
            "auxiliaryControlNetResidualConsumptionCount");
    return evidence;
}

bool verify_controlnet_branch_classification(
    const sd_image_execution_evidence_t &execution_evidence,
    std::string &error_field,
    std::string &error_message) {
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
        error_field = "controlNetEvidence";
        error_message = "ControlNet execution evidence was not completely classified by branch";
        return false;
    }
    return true;
}

bool verify_controlnet_execution_evidence(
    const ControlNetExecutionEvidence &evidence,
    bool control_image_wired,
    int actual_unet_execution_count,
    int actual_positive_execution_count,
    int actual_negative_execution_count,
    std::string &error_field,
    std::string &error_message) {
    if (control_image_wired) {
        if (evidence.compute_attempt_count != actual_unet_execution_count ||
            evidence.compute_success_count != evidence.compute_attempt_count ||
            evidence.positive_compute_attempt_count != actual_positive_execution_count ||
            evidence.positive_compute_success_count != actual_positive_execution_count ||
            evidence.negative_compute_attempt_count != actual_negative_execution_count ||
            evidence.negative_compute_success_count != actual_negative_execution_count ||
            evidence.residual_consumption_count != actual_unet_execution_count ||
            evidence.positive_residual_consumption_count != actual_positive_execution_count ||
            evidence.negative_residual_consumption_count != actual_negative_execution_count ||
            evidence.auxiliary_residual_consumption_count != 0) {
            error_field = "controlNetEvidence";
            error_message = "ControlNet did not successfully compute and feed residuals into every physical diffusion branch";
            return false;
        }
    } else if (evidence.compute_attempt_count != 0 ||
               evidence.compute_success_count != 0 ||
               evidence.residual_consumption_count != 0 ||
               evidence.positive_compute_attempt_count != 0 ||
               evidence.positive_compute_success_count != 0 ||
               evidence.negative_compute_attempt_count != 0 ||
               evidence.negative_compute_success_count != 0 ||
               evidence.positive_residual_consumption_count != 0 ||
               evidence.negative_residual_consumption_count != 0 ||
               evidence.auxiliary_residual_consumption_count != 0) {
        error_field = "controlNetEvidence";
        error_message = "native execution reported ControlNet work for a request without a control image";
        return false;
    }
    return true;
}

nlohmann::json format_controlnet_evidence_json(const ControlNetExecutionEvidence &evidence) {
    return {
            {"computeAttemptCount", evidence.compute_attempt_count},
            {"computeSuccessCount", evidence.compute_success_count},
            {"positiveComputeAttemptCount", evidence.positive_compute_attempt_count},
            {"positiveComputeSuccessCount", evidence.positive_compute_success_count},
            {"negativeComputeAttemptCount", evidence.negative_compute_attempt_count},
            {"negativeComputeSuccessCount", evidence.negative_compute_success_count},
            {"residualConsumptionCount", evidence.residual_consumption_count},
            {"positiveResidualConsumptionCount", evidence.positive_residual_consumption_count},
            {"negativeResidualConsumptionCount", evidence.negative_residual_consumption_count},
            {"auxiliaryResidualConsumptionCount", evidence.auxiliary_residual_consumption_count}
    };
}

void populate_controlnet_effective_json(
    nlohmann::json &native_effective,
    const ControlNetExecutionEvidence &evidence,
    bool control_image_wired,
    bool actual_control_image_consumed,
    double contract_control_strength,
    float gen_control_strength,
    const LoadedInputImage &control_image) {
    native_effective["controlImagePath"] = control_image_wired
            ? control_image.canonical_path : "";
    native_effective["controlImageExecutionCount"] = actual_control_image_consumed ? 1 : 0;
    native_effective["controlImageSha256"] = control_image.sha256;
    native_effective["controlStrength"] = control_image_wired
            ? static_cast<double>(gen_control_strength) : contract_control_strength;
    native_effective["controlStrengthApplied"] = actual_control_image_consumed;
    native_effective["controlNetEvidence"] = format_controlnet_evidence_json(evidence);
}

void populate_controlnet_response_json(
    nlohmann::json &out,
    const nlohmann::json &native_effective,
    bool control_image_wired,
    bool actual_control_image_consumed,
    const LoadedInputImage &control_image) {
    out["controlImagePath"] = control_image_wired ? control_image.canonical_path : "";
    out["controlImageExecutionCount"] = actual_control_image_consumed ? 1 : 0;
    out["controlImageSha256"] = control_image.sha256;
    out["controlImageSourceWidth"] = control_image.source_width;
    out["controlImageSourceHeight"] = control_image.source_height;
    out["controlImageSourceChannels"] = control_image.source_channels;
    out["controlImageWidth"] = control_image.width;
    out["controlImageHeight"] = control_image.height;
    out["controlImageChannels"] = control_image.channels;
    out["controlImageExifOrientation"] = control_image.exif_orientation;
    out["controlStrength"] = native_effective["controlStrength"];
    out["controlNetEvidence"] = native_effective["controlNetEvidence"];
}

} // namespace sd
} // namespace mca
