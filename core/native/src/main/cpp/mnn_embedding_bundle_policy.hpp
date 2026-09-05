#pragma once

#include <cstdint>
#include <limits>
#include <string>

namespace mca::mnn {

struct TieEmbeddingDescriptor {
    int64_t weight_offset = 0;
    int64_t alpha_offset = 0;
    int64_t alpha_size = 0;
    int64_t quant_bit = 0;
    int64_t quant_block = 0;
    bool alpha_fp16 = false;
};

struct TieEmbeddingValidation {
    bool valid = false;
    uint64_t vocabulary_size = 0;
    std::string error;
};

inline bool checkedMultiply(uint64_t left, uint64_t right, uint64_t* product) {
    if (product == nullptr) return false;
    if (left != 0 && right > std::numeric_limits<uint64_t>::max() / left) return false;
    *product = left * right;
    return true;
}

/**
 * Validate the exact disk ranges consumed by MNN 3.6 DiskEmbedding.
 *
 * A positive weight_offset means the embedding is tied to llm.mnn.weight;
 * zero means a quantized external embedding_file. The same geometry applies
 * to both stores. Rejecting malformed offsets here prevents a delayed prefill
 * failure or an out-of-range FileLoader read in DiskEmbedding::embedding().
 */
inline TieEmbeddingValidation validateTieEmbedding(
        const TieEmbeddingDescriptor& descriptor,
        int64_t hidden_size,
        uint64_t storage_size) {
    TieEmbeddingValidation result;
    const auto fail = [&result](const std::string& message) {
        result.error = message;
        return result;
    };

    if (descriptor.weight_offset < 0 || descriptor.alpha_offset < 0 ||
        descriptor.alpha_size <= 0) {
        return fail("tie_embeddings offsets must be non-negative and alpha_size must be positive.");
    }
    if (hidden_size <= 0) {
        return fail("tie_embeddings requires a positive hidden_size.");
    }
    if (descriptor.quant_bit != 4 && descriptor.quant_bit != 8) {
        return fail("tie_embeddings quant_bit must be 4 or 8 for MNN DiskEmbedding.");
    }
    if (descriptor.quant_block < 0 || descriptor.quant_block > hidden_size ||
        (descriptor.quant_block > 0 && hidden_size % descriptor.quant_block != 0)) {
        return fail("tie_embeddings quant_block must be zero or divide hidden_size exactly.");
    }

    const uint64_t weight_offset = static_cast<uint64_t>(descriptor.weight_offset);
    const uint64_t alpha_offset = static_cast<uint64_t>(descriptor.alpha_offset);
    const uint64_t alpha_size = static_cast<uint64_t>(descriptor.alpha_size);
    if (alpha_offset <= weight_offset) {
        return fail("tie_embeddings alpha_offset must follow the quantized weight range.");
    }
    if (alpha_offset > storage_size || alpha_size > storage_size - alpha_offset) {
        return fail("tie_embeddings alpha range exceeds its backing file.");
    }

    uint64_t token_bits = 0;
    if (!checkedMultiply(
                static_cast<uint64_t>(hidden_size),
                static_cast<uint64_t>(descriptor.quant_bit),
                &token_bits) ||
        token_bits % 8U != 0U) {
        return fail("tie_embeddings token width overflows or is not byte-aligned.");
    }
    const uint64_t token_bytes = token_bits / 8U;
    const uint64_t weight_bytes = alpha_offset - weight_offset;
    if (token_bytes == 0U || weight_bytes == 0U || weight_bytes % token_bytes != 0U) {
        return fail("tie_embeddings quantized weight range is not a whole number of token rows.");
    }
    const uint64_t vocabulary_size = weight_bytes / token_bytes;
    const uint64_t block_count = descriptor.quant_block == 0
            ? 1U
            : static_cast<uint64_t>(hidden_size / descriptor.quant_block);
    const uint64_t alpha_element_bytes = descriptor.alpha_fp16 ? 2U : 4U;
    if (alpha_size % alpha_element_bytes != 0U) {
        return fail("tie_embeddings alpha_size is not aligned to alpha_dtype.");
    }

    uint64_t expected_symmetric_elements = 0;
    if (!checkedMultiply(vocabulary_size, block_count, &expected_symmetric_elements)) {
        return fail("tie_embeddings scale count overflows.");
    }
    const uint64_t actual_alpha_elements = alpha_size / alpha_element_bytes;
    uint64_t expected_asymmetric_elements = 0;
    if (!checkedMultiply(expected_symmetric_elements, 2U, &expected_asymmetric_elements) ||
        (actual_alpha_elements != expected_symmetric_elements &&
         actual_alpha_elements != expected_asymmetric_elements)) {
        return fail("tie_embeddings alpha range does not match the token and quant-block geometry.");
    }

    result.valid = true;
    result.vocabulary_size = vocabulary_size;
    return result;
}

}  // namespace mca::mnn
