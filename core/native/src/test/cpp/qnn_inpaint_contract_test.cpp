#include "../../main/cpp/qnn_inpaint_contract.hpp"

#include <cassert>
#include <cmath>
#include <string>
#include <vector>

int main() {
    using mca::qnn::inpaint::Contract;
    using mca::qnn::inpaint::MaskTopology;
    using mca::qnn::inpaint::TensorDescriptor;
    using mca::qnn::inpaint::TensorLayout;

    const Contract concatenated = mca::qnn::inpaint::inspect({
        {"sample", {1U, 9U, 64U, 64U}},
        {"timestep", {1U}},
        {"text_embedding", {1U, 77U, 768U}},
    });
    assert(concatenated.supported());
    assert(concatenated.topology == MaskTopology::ConcatenatedLatent9);
    assert(concatenated.layout == TensorLayout::Nchw);

    const Contract separate = mca::qnn::inpaint::inspect({
        {"sample", {1U, 4U, 64U, 64U}},
        {"inpaint_mask", {1U, 1U, 64U, 64U}},
        {"timestep", {1U}},
        {"text_embedding", {1U, 77U, 768U}},
    });
    assert(separate.supported());
    assert(separate.topology == MaskTopology::SeparateMaskInput);
    assert(separate.mask_index == 1);

    const Contract latent_blend = mca::qnn::inpaint::inspect({
        {"sample", {1U, 4U, 64U, 64U}},
        {"timestep", {1U}},
        {"text_embedding", {1U, 77U, 768U}},
    });
    assert(latent_blend.supported());
    assert(latent_blend.topology == MaskTopology::LatentBlend4);
    assert(!latent_blend.requires_mask_binding());
    assert(!latent_blend.requires_masked_latent());

    const Contract nhwc = mca::qnn::inpaint::inspect({
        {"sample", {1U, 64U, 64U, 9U}},
        {"timestep", {1U}},
        {"text_embedding", {1U, 77U, 768U}},
    });
    assert(!nhwc.supported());

    const Contract ambiguous_masks = mca::qnn::inpaint::inspect({
        {"sample", {1U, 4U, 64U, 64U}},
        {"mask", {1U, 1U, 64U, 64U}},
        {"inpaint_mask", {1U, 1U, 64U, 64U}},
        {"conditioning_mask", {1U, 1U, 64U, 64U}},
    });
    assert(!ambiguous_masks.supported());

    const Contract mixed_topology = mca::qnn::inpaint::inspect({
        {"sample", {1U, 9U, 64U, 64U}},
        {"mask", {1U, 1U, 64U, 64U}},
    });
    assert(!mixed_topology.supported());

    std::string error;
    const std::vector<float> latent{1, 2, 3, 4, 5, 6, 7, 8};
    const std::vector<float> mask{0.25f, 0.75f};
    const std::vector<float> masked_latent{11, 12, 13, 14, 15, 16, 17, 18};
    std::vector<float> sample;
    assert(mca::qnn::inpaint::build_concatenated_sample(
        latent, mask, masked_latent, 2U, 1U, TensorLayout::Nchw, &sample, &error));
    assert(sample == std::vector<float>({
        1, 2, 3, 4, 5, 6, 7, 8,
        0.25f, 0.75f,
        11, 12, 13, 14, 15, 16, 17, 18,
    }));

    const std::vector<float> generated{10, 20, 30, 40, 50, 60, 70, 80};
    const std::vector<float> source{1, 2, 3, 4, 5, 6, 7, 8};
    std::vector<float> preserved;
    assert(mca::qnn::inpaint::preserve_unmasked_latent(
        generated, source, mask, 2U, 1U, &preserved, &error));
    assert(preserved == std::vector<float>({3.25f, 15.5f, 9.75f, 31.0f, 16.25f, 46.5f, 22.75f, 62.0f}));
    assert(!mca::qnn::inpaint::preserve_unmasked_latent(
        generated, source, {0.0f}, 2U, 1U, &preserved, &error));

    const std::vector<float> source_pixels(3U * 8U * 8U, -0.5f);
    const std::vector<float> generated_pixels(3U * 8U * 8U, 0.5f);
    std::vector<float> blended;
    int blend_levels = 0;
    assert(mca::qnn::inpaint::laplacian_pyramid_blend_nchw(
        source_pixels,
        generated_pixels,
        std::vector<float>(8U * 8U, 0.25f),
        8U,
        8U,
        &blended,
        &blend_levels,
        &error));
    assert(blend_levels == 1);
    assert(blended.size() == source_pixels.size());
    for (float value : blended) assert(std::fabs(value - (-0.25f)) < 1.0e-6f);
    return 0;
}
