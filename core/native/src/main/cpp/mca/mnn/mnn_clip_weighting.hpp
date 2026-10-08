#ifndef MCA_MNN_CLIP_WEIGHTING_HPP
#define MCA_MNN_CLIP_WEIGHTING_HPP

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

#include "image_conditioning.hpp"
#include "mca/mnn/mnn_tensor_ops.hpp"

namespace mca {
namespace mnn {

/**
 * Checks whether the prompt contains attention weights different from 1.0 (non-unity).
 */
bool prompt_has_non_unity_attention_weight(
        const std::string& prompt,
        bool& weighted,
        std::string& error);

/**
 * Checks whether the bundle root path contains all assets for a community CLIP model.
 */
bool has_community_clip_bundle(
        const std::string& root,
        const std::string& graphPath = "");

/**
 * Writes a vector of float values as a binary file to path safely and atomically.
 */
bool write_float_file(
        const std::string& path,
        const std::vector<float>& values,
        std::string& error);

/**
 * Validates ClipTokenPair prompt weights for CLIP token-id graph compatibility.
 */
bool validate_clip_pair_weights_for_token_id_graph(
        const mca::image::ClipTokenPair& tokenPair,
        bool includeNegative,
        std::string& error);

/**
 * Appends prompt weighting metadata and SHA-256 fingerprint evidence to execution JSON target.
 */
bool append_clip_prompt_weighting_evidence(
        nlohmann::json& target,
        const mca::image::ClipTokenPair& tokenPair,
        bool supported,
        bool includeNegative,
        std::string& error);

/**
 * Delegate callback type for encoding community CLIP embeddings to file.
 */
using CommunityClipEncoderFn = std::function<nlohmann::json(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson)>;

/**
 * Delegate callback type for running text encoder graph directly.
 */
using TextEncoderDirectFn = std::function<bool(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error)>;

/**
 * Encodes SD1.5 prompt embeddings to file, handling prompt language verification,
 * tokenizer format selection (tokenizer.json vs Mtok), CLIP prompt weighting,
 * text encoder graph inference, and output serialization.
 */
nlohmann::json encode_sd15_prompt_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson,
        CommunityClipEncoderFn communityClipEncoder = nullptr,
        TextEncoderDirectFn textEncoderDirect = nullptr);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_CLIP_WEIGHTING_HPP
