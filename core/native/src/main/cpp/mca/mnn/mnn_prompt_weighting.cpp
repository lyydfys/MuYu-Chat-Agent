#include "mca/mnn/mnn_prompt_weighting.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "image_conditioning.hpp"

namespace mca {
namespace mnn {

bool prompt_has_non_unity_attention_weight(
        const std::string& prompt,
        bool& weighted,
        std::string& error) {
    std::vector<mca::image::WeightedPromptFragment> fragments;
    if (!mca::image::parse_clip_prompt_weighting(prompt, &fragments, &error)) {
        return false;
    }
    weighted = std::any_of(
            fragments.begin(),
            fragments.end(),
            [](const mca::image::WeightedPromptFragment& fragment) {
                return std::fabs(fragment.weight - 1.0f) > 1.0e-6f;
            });
    return true;
}

} // namespace mnn
} // namespace mca
