#include "mca/mnn/mnn_backend_predicates.hpp"

#include <algorithm>

namespace mca {
namespace mnn {

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
bool mnn_sd15_unet_backend_unsupported(const std::vector<std::string>& errors) {
    return std::any_of(errors.begin(), errors.end(), [](const std::string& value) {
        return value.find("status 2") != std::string::npos ||
                value.find("NOT_SUPPORT") != std::string::npos ||
                value.find("Create execution error : 304") != std::string::npos ||
                value.find("GroupNorm") != std::string::npos;
    });
}

#endif

}  // namespace mnn
}  // namespace mca
