#include "mca/mnn/mnn_pndm_scheduler.hpp"

#include <cmath>
#include <cstddef>
#include <limits>
#include <string>
#include <vector>

#include "mca/mnn/mnn_tensor_ops.hpp"

namespace mca {
namespace mnn {

bool compute_adams_bashforth_derivative(
        const std::vector<std::vector<float>>& ets,
        int stepIndex,
        std::vector<float>& adjusted,
        std::string& error) {
    if (ets.empty()) {
        error = "PNDM scheduler has no model output history.";
        return false;
    }
    const size_t targetSize = adjusted.size();
    for (const auto& previousOutput : ets) {
        if (previousOutput.size() != targetSize ||
            !validate_finite_float_vector(previousOutput, "PNDM model output history", error)) {
            return false;
        }
    }
    if (stepIndex == 1) {
        return true;
    }
    const int etsIndex = static_cast<int>(ets.size()) - 1;
    if (etsIndex == 1) {
        for (size_t i = 0; i < targetSize; ++i) {
            adjusted[i] = (3.0f * ets[etsIndex][i] - ets[etsIndex - 1][i]) * 0.5f;
        }
    } else if (etsIndex == 2) {
        for (size_t i = 0; i < targetSize; ++i) {
            adjusted[i] = (23.0f * ets[etsIndex][i] - 16.0f * ets[etsIndex - 1][i] +
                           5.0f * ets[etsIndex - 2][i]) / 12.0f;
        }
    } else if (etsIndex >= 3) {
        for (size_t i = 0; i < targetSize; ++i) {
            adjusted[i] = (55.0f * ets[etsIndex][i] - 59.0f * ets[etsIndex - 1][i] +
                           37.0f * ets[etsIndex - 2][i] - 9.0f * ets[etsIndex - 3][i]) / 24.0f;
        }
    }
    return true;
}

bool compute_pndm_transfer_coefficients(
        float alphaProdT,
        float alphaProdPrev,
        float& sampleCoeff,
        float& modelCoeff,
        std::string& error) {
    if (!std::isfinite(alphaProdT) || !std::isfinite(alphaProdPrev) ||
        alphaProdT <= 0.0f || alphaProdT > 1.0f ||
        alphaProdPrev <= 0.0f || alphaProdPrev > 1.0f) {
        error = "PNDM scheduler received invalid alpha values.";
        return false;
    }
    const float betaProdT = 1.0f - alphaProdT;
    const float betaProdPrev = 1.0f - alphaProdPrev;
    if (betaProdT < 0.0f || betaProdPrev < 0.0f) {
        error = "PNDM scheduler received invalid beta values.";
        return false;
    }
    sampleCoeff = std::sqrt(alphaProdPrev / alphaProdT);
    const float denom = alphaProdT * std::sqrt(betaProdPrev) +
            std::sqrt(alphaProdT * betaProdT * alphaProdPrev);
    if (!std::isfinite(sampleCoeff) || !std::isfinite(denom) ||
        denom <= std::numeric_limits<float>::epsilon()) {
        error = "PNDM scheduler produced invalid integration coefficients.";
        return false;
    }
    modelCoeff = (alphaProdPrev - alphaProdT) / denom;
    if (!std::isfinite(modelCoeff)) {
        error = "PNDM scheduler produced a non-finite model coefficient.";
        return false;
    }
    return true;
}

bool pndm_step(
        const std::vector<float>& sample,
        std::vector<std::vector<float>>& ets,
        std::vector<float>& firstSample,
        const std::vector<float>& modelOutput,
        int index,
        const std::vector<int>& timesteps,
        const std::vector<float>& alphas,
        std::vector<float>& previous,
        std::string& error) {
    if (index < 0 || static_cast<size_t>(index) >= timesteps.size()) {
        error = "PNDM scheduler step index is outside the timestep schedule.";
        return false;
    }
    if (sample.empty() || modelOutput.size() != sample.size()) {
        error = "PNDM scheduler sample and model output sizes must match and be non-empty.";
        return false;
    }
    if (!validate_finite_float_vector(sample, "PNDM sample", error) ||
        !validate_finite_float_vector(modelOutput, "PNDM model output", error)) {
        return false;
    }

    int timestep = timesteps[static_cast<size_t>(index)];
    int prevTimestep = 0;
    if (static_cast<size_t>(index + 1) < timesteps.size()) {
        prevTimestep = timesteps[static_cast<size_t>(index + 1)];
    }
    std::vector<float> adjusted = modelOutput;
    const std::vector<float>* sampleForStep = &sample;

    if (index == 0) {
        firstSample = sample;
    }
    if (index != 1) {
        if (ets.size() >= 4) {
            ets.erase(ets.begin());
        }
        ets.push_back(modelOutput);
    } else {
        if (timesteps.size() < 2 || ets.empty()) {
            error = "PNDM scheduler is missing the first model output for its second step.";
            return false;
        }
        if (firstSample.size() != sample.size()) {
            error = "PNDM scheduler is missing the first latent sample for its second step.";
            return false;
        }
        timestep = timesteps[0];
        prevTimestep = timesteps[1];
        sampleForStep = &firstSample;
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = (adjusted[i] + ets.back()[i]) * 0.5f;
        }
    }

    if (!compute_adams_bashforth_derivative(ets, index, adjusted, error)) {
        return false;
    }

    if (timestep < 0 || prevTimestep < 0 ||
        static_cast<size_t>(timestep) >= alphas.size() ||
        static_cast<size_t>(prevTimestep) >= alphas.size()) {
        error = "PNDM scheduler timestep is outside the alpha schedule.";
        return false;
    }
    const float alphaProdT = alphas[static_cast<size_t>(timestep)];
    const float alphaProdPrev = alphas[static_cast<size_t>(prevTimestep)];
    float sampleCoeff = 0.0f;
    float modelCoeff = 0.0f;
    if (!compute_pndm_transfer_coefficients(alphaProdT, alphaProdPrev, sampleCoeff, modelCoeff, error)) {
        return false;
    }

    previous.resize(sample.size());
    for (size_t i = 0; i < sample.size(); ++i) {
        previous[i] = sampleCoeff * (*sampleForStep)[i] - modelCoeff * adjusted[i];
    }
    return validate_finite_float_vector(previous, "PNDM previous sample", error);
}

}  // namespace mnn
}  // namespace mca
