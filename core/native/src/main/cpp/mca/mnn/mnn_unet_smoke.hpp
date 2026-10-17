#ifndef MCA_MNN_UNET_SMOKE_HPP
#define MCA_MNN_UNET_SMOKE_HPP

#include <string>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#endif

namespace mca {
namespace mnn {

struct UnetSmokeProfile {
    std::string name;
    bool resizeDefer = false;
    bool inputUser = false;
    int batch = 2;
};

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
void configure_interpreter_profile(MNN::Interpreter* interpreter, const UnetSmokeProfile& profile);

nlohmann::json run_unet_interpreter_smoke_once(
        const std::string& root,
        const std::string& backendMode,
        const UnetSmokeProfile& profile);
#endif

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_UNET_SMOKE_HPP
