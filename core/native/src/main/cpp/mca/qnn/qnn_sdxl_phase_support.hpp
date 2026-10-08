#ifndef MCA_QNN_SDXL_PHASE_SUPPORT_HPP
#define MCA_QNN_SDXL_PHASE_SUPPORT_HPP

#include <algorithm>
#include <cmath>
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

namespace mca {
namespace qnn {

struct SdxlInpaintPixelArtifacts {
    std::vector<float> source_nchw;
    std::vector<float> full_mask;
    std::string source_tensor_sha256;
    std::string full_mask_tensor_sha256;
};

bool write_sdxl_latent_atomic(
        const std::string& path,
        const std::vector<float>& values,
        std::string* error);

bool fsync_sdxl_artifact(
        const std::string& path,
        std::string* error);

bool valid_sdxl_lower_sha256(const std::string& value);

bool is_safe_ascii_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt);

bool validate_sdxl_phase_execution_contract(
        const std::string& prompt,
        const std::string& negative_prompt,
        int width,
        int height,
        bool is_host_before_graph_scaling,
        double vae_scaling_factor,
        std::string* error);

template <typename ContractT>
inline bool validate_sdxl_phase_execution_contract(
        const ContractT& contract,
        std::string* error) {
    const bool is_host_before_graph =
        (static_cast<int>(contract.vae_scaling_location) == 0);
    return validate_sdxl_phase_execution_contract(
        contract.prompt,
        contract.negative_prompt,
        contract.width,
        contract.height,
        is_host_before_graph,
        contract.vae_scaling_factor,
        error);
}

bool load_sdxl_inpaint_pixel_artifacts(
        const std::string& params_json,
        int contract_width,
        int contract_height,
        SdxlInpaintPixelArtifacts* artifacts,
        std::string* error);

template <typename ContractT>
inline bool load_sdxl_inpaint_pixel_artifacts(
        const std::string& params_json,
        const ContractT& contract,
        SdxlInpaintPixelArtifacts* artifacts,
        std::string* error) {
    return load_sdxl_inpaint_pixel_artifacts(
        params_json, contract.width, contract.height, artifacts, error);
}

bool validate_sdxl_no_preview_transport(
        const std::string& params_json,
        std::string* error);

std::string sdxl_parent_directory(const std::string& path);

std::string sdxl_disabled_preview_native_json_fields();

} // namespace qnn
} // namespace mca

using mca::qnn::SdxlInpaintPixelArtifacts;
using mca::qnn::write_sdxl_latent_atomic;
using mca::qnn::fsync_sdxl_artifact;
using mca::qnn::valid_sdxl_lower_sha256;
using mca::qnn::is_safe_ascii_diffusion_prompt_pair;
using mca::qnn::validate_sdxl_phase_execution_contract;
using mca::qnn::load_sdxl_inpaint_pixel_artifacts;
using mca::qnn::validate_sdxl_no_preview_transport;
using mca::qnn::sdxl_parent_directory;
using mca::qnn::sdxl_disabled_preview_native_json_fields;

#endif // MCA_QNN_SDXL_PHASE_SUPPORT_HPP
