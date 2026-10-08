#ifndef MCA_MNN_BUNDLE_CONFIG_HPP
#define MCA_MNN_BUNDLE_CONFIG_HPP

#include <string>
#include <vector>
#include <cstdint>
#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif
#include "mnn_embedding_bundle_policy.hpp"

namespace mca {
namespace mnn {

struct MnnPublishedImageEvidence {
    long long bytes = 0;
    std::string sha256;
};

// Path helpers
std::string parent_dir(const std::string& path);
bool nonempty_regular_file_exists(const std::string& path);
bool directory_exists(const std::string& path);
std::string normalize_mnn_model_path(const std::string& path);
std::string join_path(const std::string& dir, const std::string& name);
std::string resolve_mnn_config_path(const std::string& path);

// JSON and configuration operations
nlohmann::json read_json_file_or_empty(const std::string& path);
bool read_mnn_config_object(
        const std::string& path,
        const std::string& label,
        nlohmann::json& output,
        std::string& error);
void merge_mnn_config(nlohmann::json& target, const nlohmann::json& source);
void relocate_mnn_component_declarations(
        nlohmann::json& config,
        const std::string& root,
        const std::string& prefix,
        size_t depth = 0);
void read_relocated_mnn_configs(
        const std::string& configPath,
        nlohmann::json& config,
        nlohmann::json& modelConfig);

// Component validation and resolution
bool mnn_configured_component_path(
        const nlohmann::json& config,
        const std::string& root,
        const char* key,
        const char* fallback,
        std::string& relative,
        std::string& absolute,
        std::string& error);
bool require_mnn_component(
        const nlohmann::json& config,
        const std::string& root,
        const char* key,
        const char* fallback,
        std::string& relative,
        std::string& absolute,
        std::string& error);
bool mnn_json_exact_int64(const nlohmann::json& value, int64_t& output);
bool parse_mnn_tie_embedding_descriptor(
        const nlohmann::json& value,
        TieEmbeddingDescriptor& descriptor,
        std::string& error);

// Bundle preflight verification
std::string mnn_chat_bundle_preflight_message(const std::string& configPath);

// Image evidence collection
bool collect_mnn_published_image_evidence(
        const std::string& path,
        MnnPublishedImageEvidence& evidence,
        std::string& error);

// Visual model path discovery
void collect_mnn_visual_path_declarations(
        const nlohmann::json& value,
        std::vector<std::string>& paths,
        int depth = 0,
        bool allowGenericModelPath = false);
std::vector<std::string> mnn_visual_path_declarations(
        const std::string& root,
        const nlohmann::json& config);
std::string select_mnn_visual_model_path(
        const std::string& root,
        const nlohmann::json& config,
        const std::string& configuredVisual);

// Stop marker inspection
bool is_mnn_stop_marker_key(const std::string& rawKey);
void append_mnn_stop_markers_from_json(
        const nlohmann::json& value,
        std::vector<std::string>& target,
        int depth = 0);
const std::vector<std::string>& default_mnn_stop_markers();
void append_stop_marker(std::vector<std::string>& target, const std::string& marker);
std::vector<std::string> mnn_model_stop_markers(const std::string& configPath);

// Config difference checking
std::vector<std::string> mnn_load_signature_differences(
        const nlohmann::json& loaded,
        const nlohmann::json& requested);

// Runtime config preparation and legacy graph validation
bool write_json_file(const std::string& path, const nlohmann::json& value);
std::string incompatible_legacy_mnn_visual_graph_message(const std::string& requestedPath);
std::string prepare_mnn_runtime_config(
        const std::string& requestedPath,
        std::string* visualModelPath = nullptr,
        bool* visionReady = nullptr);

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_BUNDLE_CONFIG_HPP
