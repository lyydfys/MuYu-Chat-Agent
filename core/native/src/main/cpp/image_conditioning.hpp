#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace mca::image {

/** SHA-256 over the exact byte payload, returned as lower-case hexadecimal. */
std::string sha256_hex_bytes(const std::vector<uint8_t>& payload);

/**
 * SHA-256 over the two effective prompt strings using the product wire framing:
 * one non-null marker, a four-byte big-endian UTF-8 length, then the UTF-8 bytes
 * for each of positive and negative prompt, in that order.
 */
std::string image_prompt_execution_sha256(const std::string& prompt,
                                          const std::string& negative_prompt);

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
    // Kept off by default so existing profiles continue to tokenize brackets
    // literally. A resolved execution profile must opt in explicitly.
    bool enable_prompt_weighting = false;
};

struct WeightedPromptFragment {
    std::string text;
    float weight = 1.0f;
};

struct ClipTokenSequence {
    std::vector<int32_t> ids;
    std::vector<float> weights;
    size_t untruncated_token_count = 0;
    bool truncated = false;
    bool weighting_applied = false;
    size_t weighted_token_count = 0;
    float min_weight = 1.0f;
    float max_weight = 1.0f;
    float mean_weight = 1.0f;
    // SHA-256 over a domain-separated, fixed-little-endian serialization of
    // token ids and weights quantized to six decimal places.
    std::string weighting_fingerprint;
};

struct ClipTokenPair {
    ClipTokenSequence negative;
    ClipTokenSequence positive;

    std::vector<int32_t> negative_then_positive() const;
    std::vector<float> negative_then_positive_weights() const;
    // Domain-separated SHA-256 over both complete fixed-length sequences.
    std::string weighting_fingerprint() const;
};

/** Exact, untruncated count for one prompt as seen by a CLIP tokenizer. */
struct ClipPromptTokenMeasurement {
    size_t token_count = 0;
    size_t max_length = 0;
    // Byte offset in the original UTF-8 prompt at which the first overflowing
    // prefix was observed. It is absent when the prompt fits or the backend
    // cannot provide a stable source offset.
    std::optional<size_t> overflow_byte_offset;
};

/** One already-loaded textual-inversion tensor in a CLIP input-embedding space. */
struct ClipTextualInversionEmbedding {
    size_t artifact_index = 0;
    std::string trigger;
    size_t embedding_width = 0;
    std::vector<float> values;
};

/**
 * Fixed-length token sequence plus rows that replace the ordinary token-table lookup.
 * override_mask is one byte per token. embedding_overrides is a dense
 * [maxLength, embeddingWidth] array so the graph input can be assembled without
 * retaining pointers into an imported artifact.
 */
struct ClipConditionedSequence {
    ClipTokenSequence tokens;
    size_t embedding_width = 0;
    std::vector<uint8_t> override_mask;
    std::vector<float> embedding_overrides;
    uint64_t tokenizer_match_mask = 0;
    uint64_t applied_mask = 0;
    size_t applied_vector_count = 0;
};

struct ClipConditionedPair {
    ClipConditionedSequence negative;
    ClipConditionedSequence positive;

    uint64_t tokenizer_match_mask() const;
    uint64_t applied_mask() const;
    size_t applied_vector_count() const;
};

struct ClipEmbeddingWeightStats {
    bool weighting_applied = false;
    size_t weighted_token_count = 0;
    float input_mean_amplitude = 0.0f;
    float weighted_mean_amplitude = 0.0f;
    float normalization_scale = 1.0f;
    float output_mean_amplitude = 0.0f;
};

using ClipFragmentEncoder = std::function<bool(
        const std::string& text, std::vector<int32_t>* token_ids, std::string* error)>;

/**
 * Parses the common prompt-attention notation without changing the text that
 * is sent to the tokenizer. Supported forms are (text), [text],
 * (text:1.5), nesting, and backslash escaping. Adjacent fragments with equal
 * effective weights are merged so whitespace at their boundary is retained.
 */
bool parse_clip_prompt_weighting(const std::string& prompt,
                                 std::vector<WeightedPromptFragment>* fragments,
                                 std::string* error);

/**
 * Builds one fixed-length CLIP sequence. When enable_prompt_weighting is true,
 * each weighted fragment is tokenized independently; otherwise the complete
 * prompt is sent to the encoder literally and all weights remain 1.0. The
 * supplied encoder must not add special tokens. BOS/EOS/PAD always carry
 * weight 1.0, including the EOS inserted after truncation.
 */
bool tokenize_weighted_clip_sequence_with_encoder(const std::string& prompt,
                                                  const ClipTokenizerConfig& config,
                                                  const ClipFragmentEncoder& encoder,
                                                  ClipTokenSequence* output, std::string* error);

/**
 * Applies one weight per token row to a flat [tokenCount, embeddingWidth]
 * tensor. Optional mean-amplitude normalization preserves the input tensor's
 * mean absolute magnitude, avoiding a global conditioning-strength drift.
 */
bool apply_clip_token_weights_to_embeddings(const std::vector<float>& embeddings,
                                            size_t token_count, size_t embedding_width,
                                            const std::vector<float>& weights,
                                            bool normalize_mean_amplitude,
                                            std::vector<float>* weighted_embeddings,
                                            ClipEmbeddingWeightStats* stats, std::string* error);

/**
 * Validates weights for a text-encoder graph whose only conditioning input is
 * an int32 token-id sequence. Such a graph owns both the token embedding lookup
 * and the transformer, so host-side weighting of its final hidden-state output
 * is not an exact substitute for weighting the pre-transformer token embedding.
 * Unity weights remain fully compatible and keep ordinary prompts on the
 * graph-native path; any effective non-unity weight fails closed.
 */
bool validate_clip_token_id_graph_prompt_weights(const std::vector<float>& weights,
                                                 size_t* weighted_token_count,
                                                 std::string* error);

/**
 * Loads a Hugging Face tokenizer.json and executes its complete normalizer,
 * pre-tokenizer and model through tokenizers-cpp. BOS/EOS/truncation/padding
 * remain explicit profile data so SD1.x and the two SDXL encoders do not share
 * an accidental global rule.
 */
bool tokenize_clip_pair_from_json(const std::string& tokenizer_json_path,
                                  const std::string& positive_prompt,
                                  const std::string& negative_prompt,
                                  const ClipTokenizerConfig& config, ClipTokenPair* output,
                                  std::string* error,
                                  bool include_negative = true);

/**
 * Measures one prompt with the same tokenizer and prompt-weighting parser used
 * by the generation path. The count includes explicitly configured BOS/EOS
 * rows and is never the padded graph length.
 */
bool measure_clip_prompt_from_json(const std::string& tokenizer_json_path,
                                   const std::string& prompt,
                                   const ClipTokenizerConfig& config,
                                   ClipPromptTokenMeasurement* output,
                                   std::string* error);

/**
 * Executes the same tokenizer and prompt-weight parser as tokenize_clip_pair_from_json,
 * but replaces complete trigger occurrences before tokenization with imported embedding
 * rows. This mirrors the Local Dream custom-word contract: the trigger is indivisible,
 * multi-vector embeddings consume multiple CLIP positions, and truncation fails closed
 * when a selected embedding would not reach the graph input.
 */
bool tokenize_clip_pair_with_textual_inversion_from_json(
        const std::string& tokenizer_json_path,
        const std::string& positive_prompt,
        const std::string& negative_prompt,
        const ClipTokenizerConfig& config,
        const std::vector<ClipTextualInversionEmbedding>& embeddings,
        ClipConditionedPair* output,
        std::string* error,
        bool include_negative = true);

}  // namespace mca::image
