#ifndef MCA_SD_CONTEXT_KEY_HPP
#define MCA_SD_CONTEXT_KEY_HPP

#include <string>
#include <vector>

#include "stable-diffusion.h"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_textual_inversion.hpp"

namespace mca {
namespace sd {

/**
 * Builds a deterministic cache key for the stable-diffusion runtime context
 * based on model paths, component sub-paths, textual inversion tokens and hashes,
 * thread configuration, backend label, execution asset composite hash, and Qwen phase.
 */
std::string make_context_key(
        const ComponentPaths &paths,
        const std::vector<ContractTextualInversion> &textual_inversions,
        int threads,
        const std::string &execution_asset_composite,
        sd_qwen_image_21_phase_t qwen_phase = SD_QWEN_IMAGE_21_PHASE_FULL);

/**
 * Maps numeric prediction enum to internal stable-diffusion prediction name
 * string, or returns "unknown" if out of range.
 */
std::string observed_prediction_mode(int prediction);

}  // namespace sd
}  // namespace mca

#endif  // MCA_SD_CONTEXT_KEY_HPP
