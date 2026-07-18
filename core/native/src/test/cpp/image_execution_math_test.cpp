#include "../../main/cpp/image_execution_math.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <string>
#include <vector>

namespace {

using mca::image::SequenceFeatureLayout;
using mca::image::SequenceFeatureShape;
using mca::image::SpatialTensorLayout;
using mca::image::SpatialTensorShape;
using mca::image::VaeDecodePlan;

constexpr float kFloatTolerance = 1.0e-5f;

void test_spatial_layout_round_trip() {
    const SpatialTensorShape nchw{1U, 4U, 2U, 2U, SpatialTensorLayout::Nchw};
    const SpatialTensorShape nhwc{1U, 4U, 2U, 2U, SpatialTensorLayout::Nhwc};
    const std::vector<float> canonical{
        0.0f, 1.0f, 2.0f, 3.0f,
        10.0f, 11.0f, 12.0f, 13.0f,
        20.0f, 21.0f, 22.0f, 23.0f,
        30.0f, 31.0f, 32.0f, 33.0f,
    };
    std::string error;
    std::vector<float> interleaved;
    assert(mca::image::copy_spatial_tile(
        canonical, nchw, 0U, 0U, nhwc, &interleaved, &error));
    const std::vector<float> expected_interleaved{
        0.0f, 10.0f, 20.0f, 30.0f,
        1.0f, 11.0f, 21.0f, 31.0f,
        2.0f, 12.0f, 22.0f, 32.0f,
        3.0f, 13.0f, 23.0f, 33.0f,
    };
    assert(interleaved == expected_interleaved);

    std::vector<float> round_trip;
    assert(mca::image::copy_spatial_tile(
        interleaved, nhwc, 0U, 0U, nchw, &round_trip, &error));
    assert(round_trip == canonical);
}

void test_tiled_sdxl_vae_decode_plan_and_blend() {
    std::string error;
    VaeDecodePlan plan;
    assert(mca::image::build_vae_decode_plan(
        {1U, 4U, 128U, 128U},
        {1U, 4U, 64U, 64U},
        {1U, 3U, 512U, 512U},
        1024U,
        1024U,
        &plan,
        &error));
    assert(plan.tiled());
    assert(plan.tiles.size() == 9U);
    assert(plan.spatial_scale == 8U);
    assert(plan.final_output.height == 1024U);
    assert(plan.final_output.width == 1024U);
    assert(plan.tiles.front().latent_x == 0U);
    assert(plan.tiles.front().latent_y == 0U);
    assert(plan.tiles[1].latent_x == 32U);
    assert(plan.tiles[1].overlap_left == 256U);
    assert(plan.tiles[1].overlap_right == 256U);
    assert(plan.tiles.back().latent_x == 64U);
    assert(plan.tiles.back().latent_y == 64U);

    std::vector<std::vector<float>> tile_outputs(
        plan.tiles.size(),
        std::vector<float>(plan.vae_output.element_count(), 0.25f));
    std::vector<float> blended;
    assert(mca::image::blend_vae_decode_tiles(plan, tile_outputs, &blended, &error));
    assert(blended.size() == 3U * 1024U * 1024U);
    assert(std::all_of(blended.begin(), blended.end(), [](float value) {
        return std::fabs(value - 0.25f) <= kFloatTolerance;
    }));
}

void test_direct_sdxl_vae_decode_plan() {
    std::string error;
    VaeDecodePlan plan;
    assert(mca::image::build_vae_decode_plan(
        {1U, 128U, 128U, 4U},
        {1U, 4U, 128U, 128U},
        {1U, 1024U, 1024U, 3U},
        1024U,
        1024U,
        &plan,
        &error));
    assert(!plan.tiled());
    assert(plan.tiles.size() == 1U);
    assert(plan.source_latent.layout == SpatialTensorLayout::Nhwc);
    assert(plan.vae_input.layout == SpatialTensorLayout::Nchw);
    assert(plan.vae_output.layout == SpatialTensorLayout::Nhwc);
}

void test_exact_dual_clip_conditioning_contract() {
    std::string error;
    assert(mca::image::validate_sdxl_conditioning_payload(
        mca::image::sdxl_conditioning_element_count(), &error));
    assert(!mca::image::validate_sdxl_conditioning_payload(
        mca::image::sdxl_conditioning_element_count() - 1U, &error));

    SequenceFeatureShape sequence_major;
    assert(mca::image::resolve_sequence_feature_shape(
        {1U, 77U, 2048U}, 77U, 2048U, &sequence_major, &error));
    assert(sequence_major.layout == SequenceFeatureLayout::SequenceMajor);

    SequenceFeatureShape feature_major;
    assert(mca::image::resolve_sequence_feature_shape(
        {1U, 2048U, 77U}, 77U, 2048U, &feature_major, &error));
    assert(feature_major.layout == SequenceFeatureLayout::FeatureMajor);
    assert(!mca::image::resolve_sequence_feature_shape(
        {1U, 76U, 2048U}, 77U, 2048U, &feature_major, &error));

    std::vector<float> canonical(77U * 2048U, 0.0f);
    canonical[3U * 2048U + 7U] = 37.0f;
    canonical[76U * 2048U + 2047U] = 99.0f;
    std::vector<float> transposed;
    assert(mca::image::reorder_sequence_feature_tensor(
        canonical.data(), canonical.size(),
        SequenceFeatureShape{1U, 77U, 2048U, SequenceFeatureLayout::FeatureMajor},
        &transposed, &error));
    assert(transposed[7U * 77U + 3U] == 37.0f);
    assert(transposed[2047U * 77U + 76U] == 99.0f);
}

void test_classifier_free_guidance_contract() {
    std::string error;
    const std::vector<float> conditional{2.0f, 4.0f};
    const std::vector<float> unconditional{1.0f, 3.0f};
    std::vector<float> guided;
    assert(mca::image::apply_classifier_free_guidance(
        conditional, unconditional, 2.0, true, &guided, &error));
    assert(guided == std::vector<float>({3.0f, 5.0f}));

    assert(mca::image::apply_classifier_free_guidance(
        conditional, {}, 1.0, false, &guided, &error));
    assert(guided == conditional);
    assert(!mca::image::apply_classifier_free_guidance(
        conditional, {}, 0.0, false, &guided, &error));
}

void test_sdxl_vae_host_scaling() {
    std::string error;
    std::vector<float> latents{0.13025f, -0.2605f};
    assert(mca::image::scale_vae_latents_in_place(
        &latents, 0.13025, true, &error));
    assert(std::fabs(latents[0] - 1.0f) <= kFloatTolerance);
    assert(std::fabs(latents[1] + 2.0f) <= kFloatTolerance);

    std::vector<float> graph_internal{0.13025f};
    assert(mca::image::scale_vae_latents_in_place(
        &graph_internal, 0.13025, false, &error));
    assert(std::fabs(graph_internal[0] - 0.13025f) <= kFloatTolerance);
}

}  // namespace

int main() {
    test_spatial_layout_round_trip();
    test_tiled_sdxl_vae_decode_plan_and_blend();
    test_direct_sdxl_vae_decode_plan();
    test_exact_dual_clip_conditioning_contract();
    test_classifier_free_guidance_contract();
    test_sdxl_vae_host_scaling();
    return 0;
}
