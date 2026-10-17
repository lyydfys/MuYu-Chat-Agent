#ifndef MCA_MNN_BACKEND_PREDICATES_HPP
#define MCA_MNN_BACKEND_PREDICATES_HPP

#include <string>
#include <vector>

namespace mca {
namespace mnn {

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
// True when any recorded MNN error matches the SD1.5 fused-operator backend
// capability signature (status 2 / NOT_SUPPORT / "Create execution error :
// 304" / GroupNorm).  The shipped SD1.5 bundle uses fused transformer
// operators the CPU backend does not register, so this is a capability
// mismatch, not a malformed bundle.  The predicate is deliberately narrow,
// is used only for the strict direct SD1.5 path, and never admits or rejects
// a device by chipset.
bool mnn_sd15_unet_backend_unsupported(const std::vector<std::string>& errors);
#endif

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_BACKEND_PREDICATES_HPP
