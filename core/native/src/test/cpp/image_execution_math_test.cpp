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
using mca::image::UltraFixExecutionCounts;
using mca::image::UltraFixNoiseLevel;
using mca::image::UltraFixTilePlan;
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

UltraFixTilePlan build_sd15_ultrafix_plan(
        size_t width = 2048U,
        size_t height = 2048U,
        double overlap = 0.25) {
    std::string error;
    UltraFixTilePlan plan;
    assert(mca::image::build_ultrafix_tile_plan(
        {1U, 3U, 512U, 512U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 3U, 512U, 512U},
        width,
        height,
        512U,
        overlap,
        8U,
        &plan,
        &error));
    return plan;
}

void test_ultrafix_aligned_plan_and_blending() {
    const UltraFixTilePlan plan = build_sd15_ultrafix_plan();
    assert(plan.spatial_scale == 8U);
    assert(plan.tiles.size() == 25U);
    assert(plan.full_latent.width == 256U);
    assert(plan.full_latent.height == 256U);
    for (const auto& tile : plan.tiles) {
        assert(tile.pixel_x % 64U == 0U);
        assert(tile.pixel_y % 64U == 0U);
        assert(tile.latent_x % 8U == 0U);
        assert(tile.latent_y % 8U == 0U);
    }
    assert(plan.tiles.front().pixel_x == 0U);
    assert(plan.tiles.front().pixel_y == 0U);
    assert(plan.tiles.back().pixel_x == 1536U);
    assert(plan.tiles.back().pixel_y == 1536U);

    std::string error;
    std::vector<std::vector<float>> latent_tiles(
        plan.tiles.size(),
        std::vector<float>(plan.unet_output.element_count(), 0.375f));
    std::vector<float> latent;
    assert(mca::image::blend_ultrafix_latent_tiles(
        plan, latent_tiles, &latent, &error));
    assert(latent.size() == plan.full_latent.element_count());
    assert(std::all_of(latent.begin(), latent.end(), [](float value) {
        return std::fabs(value - 0.375f) <= kFloatTolerance;
    }));

    std::vector<std::vector<float>> pixel_tiles(
        plan.tiles.size(),
        std::vector<float>(plan.vae_output.element_count(), 0.625f));
    std::vector<float> pixels;
    assert(mca::image::blend_ultrafix_pixel_tiles(
        plan, pixel_tiles, &pixels, &error));
    assert(pixels.size() == plan.target_pixels.element_count());
    assert(std::all_of(pixels.begin(), pixels.end(), [](float value) {
        return std::fabs(value - 0.625f) <= kFloatTolerance;
    }));

    for (size_t index = 0U; index < latent_tiles.size(); ++index) {
        std::fill(latent_tiles[index].begin(), latent_tiles[index].end(),
                  static_cast<float>(index));
    }
    std::vector<float> first;
    std::vector<float> second;
    assert(mca::image::blend_ultrafix_latent_tiles(
        plan, latent_tiles, &first, &error));
    assert(mca::image::blend_ultrafix_latent_tiles(
        plan, latent_tiles, &second, &error));
    assert(first == second);
    assert(first.front() == 0.0f);
    assert(first.back() == 24.0f);
    assert(first[128U * 256U + 128U] > 0.0f);
    assert(first[128U * 256U + 128U] < 24.0f);
    const std::string descriptor = mca::image::ultrafix_tile_plan_descriptor(plan);
    assert(descriptor.find("ultrafix-tile-plan-v2|") == 0U);
    assert(descriptor.find("|overlapBits=3fd0000000000000|") != std::string::npos);
}

void test_ultrafix_plan_rejects_mismatched_topology() {
    std::string error;
    UltraFixTilePlan plan;
    assert(!mca::image::build_ultrafix_tile_plan(
        {1U, 3U, 512U, 512U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 32U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 3U, 512U, 512U},
        2048U, 2048U, 512U, 0.25, 8U, &plan, &error));
    assert(!mca::image::build_ultrafix_tile_plan(
        {1U, 3U, 512U, 512U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 3U, 512U, 512U},
        2000U, 2048U, 512U, 0.25, 8U, &plan, &error));
    assert(!mca::image::build_ultrafix_tile_plan(
        {1U, 3U, 512U, 512U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 4U, 64U, 64U},
        {1U, 3U, 512U, 512U},
        2048U, 2048U, 512U, 0.75, 8U, &plan, &error));
}

void test_ultrafix_ddim_inversion_math() {
    std::string error;
    std::vector<float> model_input;
    const UltraFixNoiseLevel clean{1.0, 0.0, 1.0};
    const UltraFixNoiseLevel first_level{0.8, 0.6, 1.0};
    assert(mca::image::scale_ultrafix_inversion_model_input(
        {2.0f}, clean, first_level, &model_input, &error));
    assert(std::fabs(model_input[0] - 1.6f) <= kFloatTolerance);

    std::vector<float> first;
    assert(mca::image::ultrafix_epsilon_inversion_step(
        {2.0f}, {0.5f}, clean, first_level, &first, &error));
    assert(std::fabs(first[0] - 1.9f) <= kFloatTolerance);
    const UltraFixNoiseLevel second_level{0.6, 0.8, 1.0};
    std::vector<float> second;
    assert(mca::image::ultrafix_epsilon_inversion_step(
        first, {0.5f}, first_level, second_level, &second, &error));
    assert(std::fabs(second[0] - 1.6f) <= kFloatTolerance);

    const UltraFixNoiseLevel euler_target{
        1.0, 2.0, 1.0 / std::sqrt(5.0)};
    assert(mca::image::scale_ultrafix_inversion_model_input(
        {2.0f}, clean, euler_target, &model_input, &error));
    assert(std::fabs(model_input[0] - static_cast<float>(2.0 / std::sqrt(5.0))) <=
        kFloatTolerance);
    assert(!mca::image::ultrafix_epsilon_inversion_step(
        second, {0.5f}, second_level, first_level, &first, &error));
}

void test_ultrafix_execution_count_arithmetic() {
    std::string error;
    UltraFixExecutionCounts counts;
    assert(mca::image::resolve_ultrafix_execution_counts(
        25U, 6U, 6U, true, &counts, &error));
    assert(counts.vae_encoder_graph_executions == 25U);
    assert(counts.inversion_positive_unet_graph_executions == 150U);
    assert(counts.refinement_positive_unet_graph_executions == 150U);
    assert(counts.refinement_negative_unet_graph_executions == 150U);
    assert(counts.total_unet_graph_executions == 450U);
    assert(counts.vae_decoder_graph_executions == 25U);
    assert(!mca::image::resolve_ultrafix_execution_counts(
        0U, 6U, 6U, true, &counts, &error));
}

void test_ultrafix_quality_schedule_and_deterministic_injection() {
    std::string error;
    mca::image::UltraFixQualitySchedule high_noise;
    mca::image::UltraFixQualitySchedule partial_tail;
    mca::image::UltraFixQualitySchedule midpoint;
    mca::image::UltraFixQualitySchedule low_noise;
    mca::image::UltraFixQualitySchedule final_step;
    assert(mca::image::resolve_ultrafix_quality_schedule(
        1000.0, true, &high_noise, &error));
    assert(mca::image::resolve_ultrafix_quality_schedule(
        333.0, true, &partial_tail, &error));
    assert(mca::image::resolve_ultrafix_quality_schedule(
        500.0, true, &midpoint, &error));
    assert(mca::image::resolve_ultrafix_quality_schedule(
        0.0, true, &low_noise, &error));
    assert(mca::image::resolve_ultrafix_quality_schedule(
        333.0, false, &final_step, &error));
    assert(high_noise.structure_guidance_weight > low_noise.structure_guidance_weight);
    assert(high_noise.noise_injection_fraction < low_noise.noise_injection_fraction);
    assert(std::fabs(high_noise.noise_injection_fraction) <= 1.0e-12);
    assert(std::fabs(high_noise.structure_guidance_weight - 1.0) <= 1.0e-12);
    assert(!high_noise.evaluate_noise_injection);
    assert(high_noise.evaluate_structure_guidance);
    assert(std::fabs(partial_tail.noise_injection_fraction -
        0.0600362650144284) <= 1.0e-12);
    assert(std::fabs(partial_tail.structure_guidance_weight -
        0.659394837586444) <= 1.0e-12);
    assert(partial_tail.evaluate_noise_injection);
    assert(partial_tail.evaluate_structure_guidance);
    assert(std::fabs(midpoint.noise_injection_fraction - 0.04) <= 1.0e-12);
    assert(std::fabs(midpoint.structure_guidance_weight -
        0.812252396356236) <= 1.0e-12);
    assert(midpoint.evaluate_noise_injection);
    assert(midpoint.evaluate_structure_guidance);
    assert(std::fabs(low_noise.noise_injection_fraction - 0.08) <= 1.0e-12);
    assert(std::fabs(low_noise.structure_guidance_weight) <= 1.0e-12);
    assert(low_noise.evaluate_noise_injection);
    assert(!low_noise.evaluate_structure_guidance);
    assert(std::fabs(final_step.noise_injection_fraction) <= 1.0e-12);
    assert(std::fabs(final_step.structure_guidance_weight) <= 1.0e-12);
    assert(!final_step.evaluate_noise_injection);
    assert(!final_step.evaluate_structure_guidance);

    const std::vector<float> prediction = {0.25f, -0.75f, 1.25f, 0.5f};
    std::vector<float> first;
    std::vector<float> second;
    std::vector<float> other_step;
    uint64_t gaussian_first = 0U;
    uint64_t mixed_first = 0U;
    uint64_t gaussian_second = 0U;
    uint64_t mixed_second = 0U;
    uint64_t gaussian_other = 0U;
    uint64_t mixed_other = 0U;
    assert(mca::image::ultrafix_inject_spherical_noise(
        prediction, 42U, 3U, 0.08, &first, &gaussian_first, &mixed_first, &error));
    assert(mca::image::ultrafix_inject_spherical_noise(
        prediction, 42U, 3U, 0.08, &second, &gaussian_second, &mixed_second, &error));
    assert(mca::image::ultrafix_inject_spherical_noise(
        prediction, 42U, 4U, 0.08, &other_step, &gaussian_other, &mixed_other, &error));
    assert(first == second);
    assert(gaussian_first == gaussian_second && mixed_first == mixed_second);
    assert(gaussian_first != gaussian_other && mixed_first != mixed_other);
    assert(first != prediction && first != other_step);
}

void test_ultrafix_equivalent_noise_and_structure_guidance() {
    std::string error;
    const std::vector<float> clean(16U, 0.25f);
    const UltraFixNoiseLevel level{0.8, 0.6, 1.0};
    std::vector<float> inverted(clean.size(), 0.0f);
    for (size_t index = 0U; index < inverted.size(); ++index) {
        const float noise = index % 2U == 0U ? 0.5f : -0.5f;
        inverted[index] = static_cast<float>(level.alpha * clean[index] + level.sigma * noise);
    }
    std::vector<float> equivalent;
    assert(mca::image::ultrafix_equivalent_noise(
        clean, inverted, level, &equivalent, &error));
    for (size_t index = 0U; index < equivalent.size(); ++index) {
        const float expected = index % 2U == 0U ? 0.5f : -0.5f;
        assert(std::fabs(equivalent[index] - expected) <= kFloatTolerance);
    }
    std::vector<float> rebuilt;
    assert(mca::image::ultrafix_add_noise(
        clean, equivalent, level, &rebuilt, &error));
    assert(rebuilt == inverted);

    const SpatialTensorShape shape{
        1U, 1U, 4U, 4U, SpatialTensorLayout::Nchw};
    std::vector<float> current(16U, 0.0f);
    std::vector<float> reference(16U, 1.0f);
    std::vector<float> guided;
    uint64_t checksum = 0U;
    assert(mca::image::ultrafix_apply_structure_guidance(
        current, reference, shape, 2U, 0.5, &guided, &checksum, &error));
    assert(checksum != 0U);
    assert(std::all_of(guided.begin(), guided.end(), [](float value) {
        return std::fabs(value - 0.5f) <= kFloatTolerance;
    }));

    const uint64_t first = mca::image::ultrafix_accumulate_checksum(0U, 1U, 7U);
    const uint64_t repeated = mca::image::ultrafix_accumulate_checksum(0U, 1U, 7U);
    const uint64_t other_step = mca::image::ultrafix_accumulate_checksum(0U, 2U, 7U);
    assert(first != 0U && first == repeated && first != other_step);
    assert(mca::image::ultrafix_noise_seed_descriptor(42U, 4U) ==
        "mca-ultrafix-quality-noise-v1|seed=42|steps=4");
    assert(mca::image::ultrafix_structure_blur_radius(0U) == 0U);
    assert(mca::image::ultrafix_structure_blur_radius(8U) == 2U);
    assert(mca::image::ultrafix_structure_blur_radius(64U) == 4U);
    assert(mca::image::ultrafix_structure_blur_radius(128U) == 8U);
    assert(mca::image::ultrafix_structure_blur_radius(256U) == 16U);
}

}  // namespace

int main() {
    test_spatial_layout_round_trip();
    test_tiled_sdxl_vae_decode_plan_and_blend();
    test_direct_sdxl_vae_decode_plan();
    test_exact_dual_clip_conditioning_contract();
    test_classifier_free_guidance_contract();
    test_sdxl_vae_host_scaling();
    test_ultrafix_aligned_plan_and_blending();
    test_ultrafix_plan_rejects_mismatched_topology();
    test_ultrafix_ddim_inversion_math();
    test_ultrafix_execution_count_arithmetic();
    test_ultrafix_quality_schedule_and_deterministic_injection();
    test_ultrafix_equivalent_noise_and_structure_guidance();
    return 0;
}
