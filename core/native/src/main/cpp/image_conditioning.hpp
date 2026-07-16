#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mca::image {

enum class TokenizerBackend {
    TokenizersCpp,
    MnnMtok,
    StableDiffusionCpp,
};

struct ClipTokenizerConfig {
    int32_t bos_id = 49406;
    int32_t eos_id = 49407;
    int32_t pad_id = 49407;
    int max_length = 77;
    bool add_bos = true;
    bool add_eos = true;
};

struct ClipTokenSequence {
    std::vector<int32_t> ids;
    size_t untruncated_token_count = 0;
    bool truncated = false;
};

struct ClipTokenPair {
    ClipTokenSequence negative;
    ClipTokenSequence positive;

    std::vector<int32_t> negative_then_positive() const;
};

/**
 * Loads a Hugging Face tokenizer.json and executes its complete normalizer,
 * pre-tokenizer and model through tokenizers-cpp. BOS/EOS/truncation/padding
 * remain explicit profile data so SD1.x and the two SDXL encoders do not share
 * an accidental global rule.
 */
bool tokenize_clip_pair_from_json(
        const std::string& tokenizer_json_path,
        const std::string& positive_prompt,
        const std::string& negative_prompt,
        const ClipTokenizerConfig& config,
        ClipTokenPair* output,
        std::string* error);

}  // namespace mca::image
