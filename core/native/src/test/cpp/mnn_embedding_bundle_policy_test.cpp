#include "../../main/cpp/mnn_embedding_bundle_policy.hpp"

#include <cassert>

int main() {
    using mca::mnn::TieEmbeddingDescriptor;
    using mca::mnn::validateTieEmbedding;

    // Two 8-wide token rows at 4 bit, followed by symmetric fp32 scales.
    const TieEmbeddingDescriptor valid{
        8,
        16,
        16,
        4,
        4,
        false,
    };
    const auto accepted = validateTieEmbedding(valid, 8, 32);
    assert(accepted.valid);
    assert(accepted.vocabulary_size == 2);

    auto out_of_bounds = valid;
    out_of_bounds.alpha_size = 17;
    assert(!validateTieEmbedding(out_of_bounds, 8, 32).valid);

    auto invalid_block = valid;
    invalid_block.quant_block = 3;
    assert(!validateTieEmbedding(invalid_block, 8, 32).valid);

    auto invalid_bit = valid;
    invalid_bit.quant_bit = 2;
    assert(!validateTieEmbedding(invalid_bit, 8, 32).valid);

    // Object-form exporters can store fp16 scales. Four symmetric scales use
    // eight bytes for the same two-token, two-block geometry.
    auto fp16 = valid;
    fp16.alpha_size = 8;
    fp16.alpha_fp16 = true;
    assert(validateTieEmbedding(fp16, 8, 24).valid);

    // weight_offset=0 is the supported external quantized embedding layout.
    auto external = valid;
    external.weight_offset = 0;
    external.alpha_offset = 8;
    assert(validateTieEmbedding(external, 8, 24).valid);
    return 0;
}
