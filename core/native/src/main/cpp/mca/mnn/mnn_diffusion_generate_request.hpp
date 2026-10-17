#ifndef MCA_MNN_DIFFUSION_GENERATE_REQUEST_HPP
#define MCA_MNN_DIFFUSION_GENERATE_REQUEST_HPP

#include <string>

namespace mca {
namespace mnn {
namespace diffusion_detail {

// Business owner for NativeMnnDiffusionBridge.generate. Returns the exact JSON
// response string; the JNI TU only performs UTF/JNI conversion. Capability-off
// builds retain the original unavailable response from this owner.
std::string generate_mnn_diffusion_image(
        const std::string& root,
        const std::string& params_raw,
        const std::string& output_path);

}  // namespace diffusion_detail
}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_DIFFUSION_GENERATE_REQUEST_HPP
