#ifndef MCA_MNN_SESSION_MANAGER_HPP
#define MCA_MNN_SESSION_MANAGER_HPP

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
namespace mnn {

bool opt_bool(const nlohmann::json& root, const char* name, bool fallback);
int opt_int(const nlohmann::json& root, const char* name, int fallback);
std::string opt_string(const nlohmann::json& root, const char* name, const std::string& fallback);

bool optional_json_string(
        const nlohmann::json& object,
        const char* key,
        std::string& target,
        std::string& error);

bool optional_json_int(
        const nlohmann::json& object,
        const char* key,
        int& target,
        std::string& error);

bool optional_json_double(
        const nlohmann::json& object,
        const char* key,
        double& target,
        std::string& error);

bool optional_json_bool(
        const nlohmann::json& object,
        const char* key,
        bool& target,
        std::string& error);

void merge_json_object(nlohmann::json& target, const nlohmann::json& source);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_SESSION_MANAGER_HPP
