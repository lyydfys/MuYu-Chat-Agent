#ifndef MCA_MNN_PROMPT_WEIGHTING_HPP
#define MCA_MNN_PROMPT_WEIGHTING_HPP

#include <string>

namespace mca {
namespace mnn {

bool prompt_has_non_unity_attention_weight(
        const std::string& prompt,
        bool& weighted,
        std::string& error);

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_PROMPT_WEIGHTING_HPP
