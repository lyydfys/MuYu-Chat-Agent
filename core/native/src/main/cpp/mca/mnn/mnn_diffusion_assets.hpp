#ifndef MCA_MNN_DIFFUSION_ASSETS_HPP
#define MCA_MNN_DIFFUSION_ASSETS_HPP

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "execution_asset_binding.hpp"
#include "textual_inversion_conditioning.hpp"
#include "mca/mnn/mnn_protocol_parser.hpp"
#include "mca/mnn/mnn_diffusion_asset_contract.hpp"

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#endif

namespace mca {
namespace mnn {

struct MnnOpenedTextEncoderAsset {
    int fd = -1;
    std::string canonical_path;
    std::string sha256;
    uint64_t size_bytes = 0U;
    mca::image::execution_assets::Identity identity;

    ~MnnOpenedTextEncoderAsset();
    MnnOpenedTextEncoderAsset() = default;
    MnnOpenedTextEncoderAsset(const MnnOpenedTextEncoderAsset&) = delete;
    MnnOpenedTextEncoderAsset& operator=(const MnnOpenedTextEncoderAsset&) = delete;

    void reset();
    std::string opened_path() const;
};

struct MnnOpenedTextEncoderBinding {
    bool active = false;
    MnnOpenedTextEncoderAsset graph;
    MnnOpenedTextEncoderAsset weight;
};

// Cancellation probe used while hashing/validating execution assets. The bridge installs its
// real probe (which reads the diffusion cancel flag) once at library load; without an installed
// probe asset verification treats itself as never cancelled (E08e interface step).
std::function<bool()> mnn_asset_cancel_probe();
void set_mnn_asset_cancel_probe(std::function<bool()> probe);
std::function<bool()> mnn_asset_cancel_callback();

bool validate_finite_float_vector(
        const std::vector<float>& values,
        const std::string& label,
        std::string& error);

bool prepare_mnn_execution_assets(
        const std::string& textualInversionJson,
        const std::string& runtime,
        mca::image::execution_assets::Binding& binding,
        std::string& error);

bool finalize_mnn_execution_assets(
        mca::image::execution_assets::Binding& binding,
        std::string& error);

bool mnn_selection_matches_execution_assets(
        const mca::image::textual_inversion::Selection& selection,
        const mca::image::execution_assets::Binding& binding,
        std::string& error);

bool mnn_text_encoder_path_is_inside_bundle(
        const std::string& bundleRoot,
        const std::string& path);

bool hash_opened_mnn_text_encoder_asset(
        MnnOpenedTextEncoderAsset& asset,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* label,
        std::string& error);

bool open_mnn_text_encoder_asset(
        const std::string& canonicalBundleRoot,
        const std::string& requestedPath,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* label,
        MnnOpenedTextEncoderAsset& asset,
        std::string& error);

bool open_mnn_text_encoder_binding(
        const std::string& root,
        const MnnTextEncoderAssetRequest& request,
        MnnOpenedTextEncoderBinding& binding,
        MnnTextEncoderAssetEvidence& evidence,
        std::string& error);

bool verify_mnn_text_encoder_binding(
        const MnnTextEncoderAssetRequest& request,
        MnnOpenedTextEncoderBinding& binding,
        std::string& error);

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
nlohmann::json tensor_metadata_json(const MNN::Tensor* tensor);
nlohmann::json inspect_mnn_graph(const std::string& path);
nlohmann::json inspect_mnn_diffusion_bundle(const std::string& root);
#endif

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_DIFFUSION_ASSETS_HPP
