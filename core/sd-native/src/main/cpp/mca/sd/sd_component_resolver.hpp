#ifndef MCA_SD_COMPONENT_RESOLVER_HPP
#define MCA_SD_COMPONENT_RESOLVER_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "execution_asset_binding.hpp"
#include "mca/sd/sd_lora_adapter.hpp"

namespace mca {
namespace sd {

struct ComponentPaths {
    std::string model;
    std::string diffusion;
    std::string high_noise_diffusion;
    std::string vae;
    std::string clip_l;
    std::string clip_g;
    std::string clip_vision;
    std::string t5xxl;
    std::string llm;
    std::string llm_vision;
    std::string embeddings_connectors;
    std::string control_net;
    std::string selection_mode = "compatibility_inference";
    bool selection_fallback = true;
    std::string bundle_root;
    std::string primary_slot;
    std::string primary_path;
    std::string text_encoder_path;
    std::string text_encoder_slot;
    std::string tokenizer_path;
    std::string manifest_path;
};

std::vector<std::string> textual_inversion_consumer_paths(const ComponentPaths& paths);

bool execution_assets_match_consumers(
        const ComponentPaths& paths,
        const mca::image::execution_assets::Binding& binding);

bool likely_split_diffusion_family(const std::string &family, const std::string &path);

ComponentPaths infer_components(
        const std::string &model_path,
        const std::string &bundle_root,
        const std::string &family);

bool canonical_existing_file_within_root(
        const std::string &raw_path,
        const std::string &raw_root,
        std::string &canonical_path,
        std::string &error);

bool validate_upscaler_file(
        const std::string &raw_root,
        const std::string &raw_path,
        uint64_t expected_size,
        std::string &canonical_path,
        std::string &error);

uint32_t mask_bit_count(uint64_t mask);

bool validate_textual_inversion_execution_evidence(
        const sd_image_execution_evidence_t &evidence,
        size_t selected_count,
        std::string &failure_code,
        std::string &failure_message);

std::string component_selection_json(const ComponentPaths &paths);

bool resolve_component_paths(
        const std::string &model_path,
        const std::string &bundle_root,
        const std::string &family,
        const nlohmann::json &params,
        ComponentPaths &paths,
        std::string &error);

}  // namespace sd
}  // namespace mca

#endif  // MCA_SD_COMPONENT_RESOLVER_HPP
