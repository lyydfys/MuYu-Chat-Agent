#include "qnn_bundle_files.hpp"

namespace mca {
namespace qnn {

bool parse_sdxl_exact_runtime_profile(
        const std::string& json,
        SdxlExactRuntimeProfileRequest* profile,
        std::string* error) {
    if (profile == nullptr || error == nullptr) return false;
    try {
        const nlohmann::json root = nlohmann::json::parse(json);
        if (!root.is_object() || root.size() != 4U) {
            *error = "SDXL runtime profile must be one versioned object with exact host and DSP roles.";
            return false;
        }
        const auto version = root.find("version");
        const auto host = root.find("hostDirectory");
        const auto dsp = root.find("dspDirectory");
        const auto arch = root.find("htpArchVersion");
        if (version == root.end() || !version->is_number_integer() || version->get<int>() != 1 ||
            host == root.end() || !host->is_string() ||
            dsp == root.end() || !dsp->is_string() ||
            arch == root.end() || !arch->is_number_integer()) {
            *error = "SDXL runtime profile version or exact role fields are invalid.";
            return false;
        }
        profile->host_directory = host->get<std::string>();
        profile->dsp_directory = dsp->get<std::string>();
        profile->htp_arch_version = arch->get<int>();
        if (profile->host_directory.empty() || profile->dsp_directory.empty() ||
            profile->htp_arch_version <= 0) {
            *error = "SDXL runtime profile directories and HTP architecture must be explicit.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("SDXL runtime profile JSON is invalid: ") + exception.what();
        return false;
    }
}

} // namespace qnn
} // namespace mca
