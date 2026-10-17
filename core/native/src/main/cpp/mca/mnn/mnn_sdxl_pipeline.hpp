#ifndef MCA_MNN_SDXL_PIPELINE_HPP
#define MCA_MNN_SDXL_PIPELINE_HPP

#include <cstddef>
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
#include "mca/mnn/mnn_diffusion_assets.hpp"

namespace mca {
namespace mnn {

constexpr size_t kCommunityClipFp16TokenEmbeddingBytes = 75'890'688U;
constexpr size_t kCommunityClipFp32TokenEmbeddingBytes = 151'781'376U;

bool inspect_community_token_embedding_file(
        const std::string& root,
        size_t& tokenBytes,
        std::string& dataType,
        std::string& error);

bool build_community_clip_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        std::vector<float>& input,
        std::string& error,
        size_t* token_embedding_bytes = nullptr,
        std::string* token_embedding_data_type = nullptr,
        const mca::image::ClipConditionedSequence* conditioned = nullptr);

bool build_clip_embedding_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        std::vector<float>& input,
        std::string& error,
        const mca::image::ClipConditionedSequence* conditioned = nullptr);

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
bool run_community_clip_encoder_direct(
        const std::string& root,
        const std::string& pinnedExternalWeightPath,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        nlohmann::json* debug = nullptr,
        std::string* embedding_disk_data_type = nullptr,
        const mca::image::ClipConditionedSequence* conditioned = nullptr,
        const MnnOpenedTextEncoderBinding* textEncoderBinding = nullptr);
#endif

bool has_sdxl_qnn_clip_bundle(const std::string& root);

nlohmann::json encode_sdxl_prompt_conditioning_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        int width,
        int height,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_SDXL_PIPELINE_HPP
