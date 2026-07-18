#include "../../main/cpp/qnn_controlnet_execution.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <limits>
#include <string>
#include <vector>

namespace {

using mca::qnn::controlnet::ControlImagePreprocessMode;
using mca::qnn::controlnet::ResidualBinding;
using mca::qnn::controlnet::TensorDescriptor;

std::vector<uint32_t> residual_shape(size_t index) {
    if (index <= 2U) return {1U, 64U, 64U, 320U};
    if (index == 3U) return {1U, 32U, 32U, 320U};
    if (index <= 5U) return {1U, 32U, 32U, 640U};
    if (index == 6U) return {1U, 16U, 16U, 640U};
    if (index <= 8U) return {1U, 16U, 16U, 1280U};
    return {1U, 8U, 8U, 1280U};
}

void test_control_image_modes_and_real_canny_pixels() {
    std::string error;
    ControlImagePreprocessMode mode = ControlImagePreprocessMode::PreprocessedCanny;
    assert(mca::qnn::controlnet::parse_control_image_preprocess_mode("canny", &mode, &error));
    assert(mode == ControlImagePreprocessMode::Canny);
    assert(mca::qnn::controlnet::parse_control_image_preprocess_mode(
        "preprocessed-canny", &mode, &error));
    assert(mode == ControlImagePreprocessMode::PreprocessedCanny);
    assert(!mca::qnn::controlnet::parse_control_image_preprocess_mode(
        "silently_ignore_image", &mode, &error));

    constexpr uint32_t width = 16U;
    constexpr uint32_t height = 16U;
    std::vector<uint8_t> rgb(static_cast<size_t>(width) * height * 3U, 0U);
    for (uint32_t y = 0U; y < height; ++y) {
        for (uint32_t x = width / 2U; x < width; ++x) {
            const size_t base = (static_cast<size_t>(y) * width + x) * 3U;
            rgb[base] = 255U;
            rgb[base + 1U] = 255U;
            rgb[base + 2U] = 255U;
        }
    }
    std::vector<float> tensor;
    size_t edge_pixels = 0U;
    std::string hash;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        rgb, width, height, width, height, ControlImagePreprocessMode::Canny,
        &tensor, &edge_pixels, &hash, &error));
    assert(tensor.size() == rgb.size());
    assert(edge_pixels > 0U);
    assert(hash.size() == 64U);
    for (size_t index = 0U; index < tensor.size(); index += 3U) {
        assert(tensor[index] == tensor[index + 1U]);
        assert(tensor[index] == tensor[index + 2U]);
        assert(tensor[index] == 0.0F || tensor[index] == 1.0F);
    }

    std::vector<float> second;
    size_t second_edges = 0U;
    std::string second_hash;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        rgb, width, height, width, height, ControlImagePreprocessMode::Canny,
        &second, &second_edges, &second_hash, &error));
    assert(second == tensor);
    assert(second_edges == edge_pixels);
    assert(second_hash == hash);
}

void test_preprocessed_canny_is_strictly_grayscale() {
    std::string error;
    assert(mca::qnn::controlnet::sha256_hex_bytes({0U, 0U, 0U}) ==
           "709e80c88487a2411e1ee4dfb9f22a861492d20c4765150c0c794abd70f8147c");
    std::vector<float> black_tensor;
    size_t black_edges = 0U;
    std::string black_hash;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        {0U, 0U, 0U}, 1U, 1U, 1U, 1U,
        ControlImagePreprocessMode::PreprocessedCanny,
        &black_tensor, &black_edges, &black_hash, &error));
    assert(black_hash == "709e80c88487a2411e1ee4dfb9f22a861492d20c4765150c0c794abd70f8147c");

    std::vector<uint8_t> grayscale = {
        0U, 0U, 0U, 255U, 255U, 255U,
        64U, 64U, 64U, 192U, 192U, 192U,
    };
    std::vector<float> tensor;
    size_t edge_pixels = 0U;
    std::string hash;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        grayscale, 2U, 2U, 2U, 2U,
        ControlImagePreprocessMode::PreprocessedCanny,
        &tensor, &edge_pixels, &hash, &error));
    assert(edge_pixels == 2U);
    assert(*std::max_element(tensor.begin(), tensor.end()) >= 0.5F);

    grayscale[4U] = 240U;
    assert(!mca::qnn::controlnet::prepare_control_image_pixels(
        grayscale, 2U, 2U, 2U, 2U,
        ControlImagePreprocessMode::PreprocessedCanny,
        &tensor, &edge_pixels, &hash, &error));
    assert(error.find("grayscale") != std::string::npos);
}

void test_canny_runs_before_non_square_lanczos_resize() {
    constexpr uint32_t width = 13U;
    constexpr uint32_t height = 7U;
    std::vector<uint8_t> rgb(static_cast<size_t>(width) * height * 3U, 0U);
    for (uint32_t y = 0U; y < height; ++y) {
        for (uint32_t x = 0U; x < width; ++x) {
            const size_t base = (static_cast<size_t>(y) * width + x) * 3U;
            if (x >= 6U) {
                rgb[base] = 255U;
                rgb[base + 1U] = static_cast<uint8_t>(180U + y * 10U);
                rgb[base + 2U] = 64U;
            }
            if (x == y + 2U) rgb[base + 2U] = 255U;
        }
    }
    std::vector<float> tensor;
    size_t edge_pixels = 0U;
    std::string hash;
    std::string error;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        rgb, width, height, 19U, 11U, ControlImagePreprocessMode::Canny,
        &tensor, &edge_pixels, &hash, &error));
    assert(tensor.size() == 19U * 11U * 3U);
    assert(edge_pixels == 32U);
    assert(std::any_of(tensor.begin(), tensor.end(), [](float value) {
        return value > 0.0F && value < 1.0F;
    }));
    assert(hash == "dffe605127cf445dee51755e2a38055264088835514c2f40df2890a19f1cfa53");
}

void test_canny_keeps_source_border_pixels_like_opencv() {
    constexpr uint32_t width = 8U;
    constexpr uint32_t height = 8U;
    std::vector<uint8_t> rgb(static_cast<size_t>(width) * height * 3U, 0U);
    for (uint32_t y = 4U; y < height; ++y) {
        for (uint32_t x = 0U; x < width; ++x) {
            const size_t base = (static_cast<size_t>(y) * width + x) * 3U;
            rgb[base] = rgb[base + 1U] = rgb[base + 2U] = 255U;
        }
    }
    std::vector<float> tensor;
    size_t edge_pixels = 0U;
    std::string hash;
    std::string error;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        rgb, width, height, width, height, ControlImagePreprocessMode::Canny,
        &tensor, &edge_pixels, &hash, &error));
    assert(edge_pixels == width);
    assert(tensor[(3U * width) * 3U] == 1.0F);
    assert(tensor[(3U * width + width - 1U) * 3U] == 1.0F);
    assert(hash == "c60a7ca667ef4355206065c7cc3379bd3e9377d05d4f0e3ee43856a262f08365");
}

void test_lanczos_matches_pillow_11_3_fixed_point_rgb() {
    const std::vector<uint8_t> gray = {
        0U, 64U, 128U, 255U,
        255U, 192U, 96U, 0U,
        10U, 40U, 200U, 250U,
    };
    std::vector<uint8_t> rgb;
    rgb.reserve(gray.size() * 3U);
    for (uint8_t value : gray) {
        rgb.push_back(value);
        rgb.push_back(value);
        rgb.push_back(value);
    }
    std::vector<float> tensor;
    size_t edge_pixels = 0U;
    std::string hash;
    std::string error;
    assert(mca::qnn::controlnet::prepare_control_image_pixels(
        rgb, 4U, 3U, 7U, 5U, ControlImagePreprocessMode::PreprocessedCanny,
        &tensor, &edge_pixels, &hash, &error));
    assert(edge_pixels == 16U);
    assert(hash == "f129a148dc68e5d37c2f5f74bcceb74bff08f544fb86d3da54db33b2f5824827");
}

void test_exact_thirteen_residual_binding_plan() {
    std::vector<TensorDescriptor> outputs;
    std::vector<TensorDescriptor> inputs = {
        {"timestep", {1U, 1U}},
        {"latent", {1U, 64U, 64U, 4U}},
        {"text_emb", {1U, 77U, 768U}},
    };
    for (size_t index = 0U; index < 12U; ++index) {
        outputs.push_back({"down_block_" + std::to_string(index), residual_shape(index)});
        inputs.push_back({"controlnet_downblock" + std::to_string(index), residual_shape(index)});
    }
    outputs.push_back({"mid_block", residual_shape(12U)});
    inputs.push_back({"controlnet_midblock", residual_shape(12U)});
    std::reverse(outputs.begin(), outputs.end());

    std::vector<ResidualBinding> plan;
    std::string error;
    assert(mca::qnn::controlnet::build_residual_binding_plan(outputs, inputs, &plan, &error));
    assert(plan.size() == 13U);
    for (size_t index = 0U; index < 12U; ++index) {
        assert(plan[index].role == "down_block_" + std::to_string(index));
        assert(outputs[plan[index].control_output_index].name ==
               "down_block_" + std::to_string(index));
        assert(inputs[plan[index].unet_input_index].name ==
               "controlnet_downblock" + std::to_string(index));
    }
    assert(plan.back().role == "mid_block");
}

void test_residual_plan_rejects_suffix_collision_and_shape_mismatch() {
    std::vector<TensorDescriptor> outputs;
    std::vector<TensorDescriptor> inputs;
    for (size_t index = 0U; index < 12U; ++index) {
        outputs.push_back({"down_block_" + std::to_string(index), residual_shape(index)});
        inputs.push_back({"controlnet_downblock" + std::to_string(index), residual_shape(index)});
    }
    outputs.push_back({"mid_block", residual_shape(12U)});
    inputs.push_back({"controlnet_midblock", residual_shape(12U)});

    std::vector<ResidualBinding> plan;
    std::string error;
    outputs[1U].name = "down_block_10";
    assert(!mca::qnn::controlnet::build_residual_binding_plan(outputs, inputs, &plan, &error));
    assert(error.find("duplicated") != std::string::npos);

    outputs[1U].name = "down_block_1";
    inputs[1U].dimensions = {1U, 64U, 64U, 321U};
    assert(!mca::qnn::controlnet::build_residual_binding_plan(outputs, inputs, &plan, &error));
    assert(error.find("shape") != std::string::npos);
}

void test_control_strength_scales_actual_residuals_and_rejects_invalid_values() {
    std::vector<float> residual = {1.0F, -2.0F, 0.5F};
    std::string error;
    assert(mca::qnn::controlnet::scale_residual_in_place(&residual, 0.5, &error));
    assert(residual == std::vector<float>({0.5F, -1.0F, 0.25F}));
    assert(!mca::qnn::controlnet::scale_residual_in_place(&residual, 2.1, &error));
    residual[0] = std::numeric_limits<float>::infinity();
    assert(!mca::qnn::controlnet::scale_residual_in_place(&residual, 1.0, &error));
}

}  // namespace

int main() {
    test_control_image_modes_and_real_canny_pixels();
    test_preprocessed_canny_is_strictly_grayscale();
    test_canny_runs_before_non_square_lanczos_resize();
    test_canny_keeps_source_border_pixels_like_opencv();
    test_lanczos_matches_pillow_11_3_fixed_point_rgb();
    test_exact_thirteen_residual_binding_plan();
    test_residual_plan_rejects_suffix_collision_and_shape_mismatch();
    test_control_strength_scales_actual_residuals_and_rejects_invalid_values();
    return 0;
}
