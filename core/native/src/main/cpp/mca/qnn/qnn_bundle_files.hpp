#ifndef MCA_QNN_BUNDLE_FILES_HPP
#define MCA_QNN_BUNDLE_FILES_HPP

#include <string>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace qnn {

struct SdxlExactRuntimeProfileRequest {
    std::string host_directory;
    std::string dsp_directory;
    int htp_arch_version = 0;
};

bool parse_sdxl_exact_runtime_profile(
        const std::string& json,
        SdxlExactRuntimeProfileRequest* profile,
        std::string* error);

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_BUNDLE_FILES_HPP
