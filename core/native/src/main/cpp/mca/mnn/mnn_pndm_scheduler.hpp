#ifndef MCA_MNN_PNDM_SCHEDULER_HPP
#define MCA_MNN_PNDM_SCHEDULER_HPP

#include <cstddef>
#include <string>
#include <vector>

namespace mca {
namespace mnn {

/**
 * Calculates Adams-Bashforth linear multistep derivative extrapolation
 * based on history in ets (model outputs from previous steps).
 * Supports orders 1 through 4 (ets history size 1 through 4+).
 */
bool compute_adams_bashforth_derivative(
        const std::vector<std::vector<float>>& ets,
        int stepIndex,
        std::vector<float>& adjusted,
        std::string& error);

/**
 * Computes PNDM transfer integration coefficients:
 * - sampleCoeff = sqrt(alphaProdPrev / alphaProdT)
 * - modelCoeff = (alphaProdPrev - alphaProdT) / denom
 */
bool compute_pndm_transfer_coefficients(
        float alphaProdT,
        float alphaProdPrev,
        float& sampleCoeff,
        float& modelCoeff,
        std::string& error);

/**
 * Single step of Pseudo Numerical Methods for Diffusion Models (PNDM) scheduler.
 * Performs Adams-Bashforth multistep derivative estimation and Runge-Kutta / linear
 * multistep transfer to compute the previous sample latent vector.
 *
 * @param sample Current latent sample tensor vector.
 * @param ets Historical model outputs buffer (maintained across steps).
 * @param firstSample Initial latent sample saved on step 0, reused on step 1.
 * @param modelOutput Current UNet model noise prediction.
 * @param index Current step index in timesteps schedule.
 * @param timesteps Sequence of discrete diffusion timesteps.
 * @param alphas Precomputed cumulative alpha values (alphas_cumprod).
 * @param previous Output previous latent sample.
 * @param error Output error description upon failure.
 * @return True on success, false on failure with error populated.
 */
bool pndm_step(
        const std::vector<float>& sample,
        std::vector<std::vector<float>>& ets,
        std::vector<float>& firstSample,
        const std::vector<float>& modelOutput,
        int index,
        const std::vector<int>& timesteps,
        const std::vector<float>& alphas,
        std::vector<float>& previous,
        std::string& error);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_PNDM_SCHEDULER_HPP
