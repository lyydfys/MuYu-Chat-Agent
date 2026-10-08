#ifndef MCA_MNN_PROTOCOL_PARSER_HPP
#define MCA_MNN_PROTOCOL_PARSER_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "diffusion_scheduler_config.hpp"

namespace mca {
namespace mnn {

enum class MnnVaeScalingLocation {
    HostBeforeGraph,
    GraphInternal,
    None,
};

struct MnnSchedulerExecutionContract {
    mca::diffusion::DiffusionSchedulerConfig config;
    int steps = 0;
    size_t expected_timetable_count = 0;
    size_t expected_unet_execution_count = 0;
    bool scale_model_input = false;
    float eta = 0.0f;
};

struct MnnTextEncoderAssetRequest {
    bool declared = false;
    std::string path;
    std::string sha256;
    uint64_t size_bytes = 0U;
    bool has_weight = false;
    std::string weight_path;
    std::string weight_sha256;
    uint64_t weight_size_bytes = 0U;
};

struct MnnSemanticExecutionContract {
    std::string profile_id;
    int profile_revision = 0;
    std::string model_fingerprint;
    MnnSchedulerExecutionContract scheduler;
    bool use_cfg = false;
    float cfg_scale = 0.0f;
    uint32_t seed = 0;
    std::string tokenizer_backend;
    size_t token_count = 0;
    int tokenizer_bos_id = 0;
    int tokenizer_eos_id = 0;
    int tokenizer_pad_id = 0;
    int tokenizer_max_length = 0;
    bool prompt_weighting_supported = false;
    std::string embedding_disk_data_type;
    MnnVaeScalingLocation vae_scaling_location = MnnVaeScalingLocation::None;
    double vae_scaling_factor = 1.0;
    // Explicit VAE output range. Older bundles may omit this field; retain
    // the historical [-1,1] default in that case rather than guessing from
    // runtime values (which can make a valid dark image look washed out).
    std::string vae_output_range = "NEGATIVE_ONE_TO_ONE";
    int width = 0;
    int height = 0;
    std::string graph_name;
    bool fallback = false;
    std::string negative_prompt;
    std::string family;
    std::string backend_mode;
    std::string runner;
    int threads = 0;
    int memory_mode = 0;
    std::string textual_inversion_json;
    std::string language_proof_sha256;
    MnnTextEncoderAssetRequest text_encoder_asset;
};

inline bool mnn_contract_uses_community_clip(const MnnSemanticExecutionContract& contract) {
    return contract.embedding_disk_data_type == "FP16" ||
           contract.embedding_disk_data_type == "FP32";
}

void append_mnn_u32_little_endian(uint32_t value, std::vector<uint8_t>& payload);
void append_mnn_u64_little_endian(uint64_t value, std::vector<uint8_t>& payload);

std::string mnn_token_id_fingerprint(const std::vector<int>& ids);

std::string normalize_mnn_diffusion_identifier(std::string value);
std::string normalize_mnn_contract_enum(std::string value);

bool is_blank_text(const std::string& value);

bool canonical_mnn_diffusion_backend(
        const std::string& requested,
        std::string& canonical,
        std::string& error);

bool mnn_contract_value(
        const nlohmann::json& params,
        const char* key,
        const nlohmann::json*& value,
        std::string& error);

bool mnn_contract_string(
        const nlohmann::json& params,
        const char* key,
        std::string& value,
        std::string& error,
        bool allowEmpty = false);

bool mnn_contract_integer(
        const nlohmann::json& params,
        const char* key,
        int64_t& value,
        std::string& error);

bool parse_mnn_text_encoder_asset_descriptor(
        const nlohmann::json& source,
        const char* label,
        std::string& path,
        std::string& sha256,
        uint64_t& sizeBytes,
        std::string& error);

bool parse_mnn_text_encoder_asset_request(
        const nlohmann::json& params,
        MnnTextEncoderAssetRequest& request,
        std::string& error);

bool parse_mnn_language_proof_sha256(
        const nlohmann::json& params,
        std::string& proofSha256,
        std::string& error);

bool mnn_prompt_contains_non_ascii(const std::string& value);

bool mnn_contract_number(
        const nlohmann::json& params,
        const char* key,
        double& value,
        std::string& error);

bool mnn_contract_boolean(
        const nlohmann::json& params,
        const char* key,
        bool& value,
        std::string& error);

bool parse_mnn_scheduler_execution_contract(
        const nlohmann::json& params,
        MnnSchedulerExecutionContract& contract,
        std::string& error);

bool parse_mnn_semantic_execution_contract(
        const nlohmann::json& params,
        MnnSemanticExecutionContract& contract,
        std::string& error);

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_PROTOCOL_PARSER_HPP
