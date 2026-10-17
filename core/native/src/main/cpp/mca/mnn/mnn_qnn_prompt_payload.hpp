#ifndef MCA_MNN_QNN_PROMPT_PAYLOAD_HPP
#define MCA_MNN_QNN_PROMPT_PAYLOAD_HPP

#include <cstdint>
#include <ostream>
#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "mca/mnn/mnn_diffusion_assets.hpp"

namespace mca {
namespace mnn {

void write_u32_little_endian(std::ostream& output, uint32_t value);

using MnnOpenedTokenizerJsonAsset = MnnOpenedTextEncoderAsset;

bool hash_opened_qnn_tokenizer_json_asset(
        MnnOpenedTokenizerJsonAsset& asset,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* phase,
        std::string& error);

bool open_qnn_tokenizer_json_asset(
        const std::string& requestedPath,
        MnnOpenedTokenizerJsonAsset& asset,
        std::string& error);

nlohmann::json qnn_tokenizer_asset_failure(const std::string& error);

bool hash_published_qnn_prompt_payload(
        const std::string& requestedPath,
        std::string& sha256,
        std::string& error);

#if defined(MCA_WITH_TOKENIZERS_CPP) && MCA_WITH_TOKENIZERS_CPP
nlohmann::json encode_prompt_token_ids_with_weights_from_json(
        const std::string& tokenizerJsonPath,
        const std::string& prompt,
        const std::string& negativePrompt,
        int32_t bosId,
        int32_t eosId,
        int32_t padId,
        int maxTokens,
        const std::string& promptToEncoderClosureSha256,
        const std::string& outputPath);
#endif

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_QNN_PROMPT_PAYLOAD_HPP
