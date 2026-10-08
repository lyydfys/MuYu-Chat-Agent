#ifndef MCA_MNN_FILE_UTILS_HPP
#define MCA_MNN_FILE_UTILS_HPP

#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_template_parser.hpp"
#include "mnn_legacy_chat_template_policy.hpp"

namespace mca {
namespace mnn {

// File inspection and output validation helpers
bool file_exists(const std::string& path);
bool remove_existing_output_file(const std::string& path, std::string& error);
bool validate_output_path_for_write(const std::string& path, std::string& error);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_FILE_UTILS_HPP
