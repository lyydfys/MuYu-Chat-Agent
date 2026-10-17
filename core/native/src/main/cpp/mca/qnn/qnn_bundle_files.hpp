#ifndef MCA_QNN_BUNDLE_FILES_HPP
#define MCA_QNN_BUNDLE_FILES_HPP

#include <string>
#include <vector>

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

bool exists_file(const std::string& path);
long long file_size_or_zero(const std::string& path);
bool exists_dir(const std::string& path);
std::string join_path(const std::string& dir, const std::string& name);
std::vector<std::string> parse_json_string_array(const std::string& json);
std::string files_json(const std::vector<std::string>& values);
std::vector<std::string> list_files_recursive(const std::string& root, int depth = 0);
bool ends_with(const std::string& value, const std::string& suffix);
bool contains_lower(std::string value, const std::string& token);
bool is_safe_bundle_relative_path(const std::string& value);

std::string json_escape(const std::string& value);
std::string quote(const std::string& value);



// Forward declaration only: a function *declaration* may name an incomplete return type, and
// qnn_capability_probe.hpp must stay out of this header (including it here previously broke
// three modules).  The definition in qnn_bundle_files.cpp includes the real header.
struct RuntimeProbe;

RuntimeProbe inspect_sdxl_exact_runtime_profile(
        const std::string& runtime_profile_json,
        bool probe);

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_BUNDLE_FILES_HPP
