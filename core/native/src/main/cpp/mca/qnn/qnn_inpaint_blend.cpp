#include "mca/qnn/qnn_inpaint_blend.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "qnn_image_pixel_range.hpp"

namespace mca::qnn {

namespace {

bool validate_mask_values(
        const std::vector<float>& values,
        uint32_t width,
        uint32_t height,
        std::string* error) {
    if (width == 0U || height == 0U) {
        if (error != nullptr) *error = "Mask dimensions must be positive.";
        return false;
    }
    const uint64_t expected = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (expected > std::numeric_limits<size_t>::max() || values.size() != static_cast<size_t>(expected)) {
        if (error != nullptr) *error = "Mask tensor element count does not match the graph spatial shape.";
        return false;
    }
    for (float value : values) {
        if (!std::isfinite(value) || value < 0.0f || value > 1.0f) {
            if (error != nullptr) *error = "Mask tensor values must be finite and normalized to [0,1].";
            return false;
        }
    }
    if (error != nullptr) error->clear();
    return true;
}

uint64_t checksum_float_vector(const std::vector<float>& values) {
    uint64_t checksum = 0;
    for (float value : values) {
        uint32_t bits = 0;
        std::memcpy(&bits, &value, sizeof(bits));
        checksum += bits;
    }
    return checksum;
}

}  // namespace

PyramidImage pyramid_down(const PyramidImage& input) {
    static constexpr float kernel[5] = {
        1.0f / 16.0f, 4.0f / 16.0f, 6.0f / 16.0f, 4.0f / 16.0f, 1.0f / 16.0f};
    PyramidImage output;
    output.channels = input.channels;
    output.width = input.width / 2U;
    output.height = input.height / 2U;
    output.values.assign(output.channels * output.width * output.height, 0.0f);
    const size_t input_plane = input.width * input.height;
    const size_t output_plane = output.width * output.height;
    for (size_t channel = 0U; channel < output.channels; ++channel) {
        for (size_t y = 0U; y < output.height; ++y) {
            for (size_t x = 0U; x < output.width; ++x) {
                float value = 0.0f;
                for (int ky = -2; ky <= 2; ++ky) {
                    for (int kx = -2; kx <= 2; ++kx) {
                        const size_t source_y = static_cast<size_t>(std::clamp(
                            static_cast<int>(y * 2U) + ky,
                            0,
                            static_cast<int>(input.height) - 1));
                        const size_t source_x = static_cast<size_t>(std::clamp(
                            static_cast<int>(x * 2U) + kx,
                            0,
                            static_cast<int>(input.width) - 1));
                        value += input.values[channel * input_plane + source_y * input.width + source_x] *
                            kernel[ky + 2] * kernel[kx + 2];
                    }
                }
                output.values[channel * output_plane + y * output.width + x] = value;
            }
        }
    }
    return output;
}

PyramidImage pyramid_up(
        const PyramidImage& input,
        size_t target_width,
        size_t target_height) {
    static constexpr float kernel[5] = {
        1.0f / 16.0f, 4.0f / 16.0f, 6.0f / 16.0f, 4.0f / 16.0f, 1.0f / 16.0f};
    PyramidImage output;
    output.channels = input.channels;
    output.width = target_width;
    output.height = target_height;
    output.values.assign(output.channels * output.width * output.height, 0.0f);
    const size_t input_plane = input.width * input.height;
    const size_t output_plane = output.width * output.height;
    for (size_t channel = 0U; channel < output.channels; ++channel) {
        for (size_t y = 0U; y < output.height; ++y) {
            for (size_t x = 0U; x < output.width; ++x) {
                float value = 0.0f;
                for (int ky = -2; ky <= 2; ++ky) {
                    for (int kx = -2; kx <= 2; ++kx) {
                        const int source_y_numerator = static_cast<int>(y) - ky;
                        const int source_x_numerator = static_cast<int>(x) - kx;
                        if (source_y_numerator % 2 != 0 || source_x_numerator % 2 != 0) continue;
                        const int source_y = source_y_numerator / 2;
                        const int source_x = source_x_numerator / 2;
                        if (source_y < 0 || source_x < 0 ||
                            source_y >= static_cast<int>(input.height) ||
                            source_x >= static_cast<int>(input.width)) {
                            continue;
                        }
                        value += input.values[
                            channel * input_plane + static_cast<size_t>(source_y) * input.width +
                            static_cast<size_t>(source_x)] * kernel[ky + 2] * kernel[kx + 2] * 4.0f;
                    }
                }
                output.values[channel * output_plane + y * output.width + x] = value;
            }
        }
    }
    return output;
}

bool laplacian_pyramid_blend_nchw(
        const std::vector<float>& source,
        const std::vector<float>& generated,
        const std::vector<float>& mask,
        uint32_t width,
        uint32_t height,
        std::vector<float>* output,
        int* level_count,
        std::string* error) {
    if (output == nullptr || level_count == nullptr) {
        if (error != nullptr) *error = "Inpaint Laplacian blend output is null.";
        return false;
    }
    const uint64_t plane_u64 = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (width == 0U || height == 0U ||
        plane_u64 > std::numeric_limits<size_t>::max() / 3U ||
        source.size() != static_cast<size_t>(plane_u64 * 3U) ||
        generated.size() != source.size() || mask.size() != static_cast<size_t>(plane_u64) ||
        !validate_mask_values(mask, width, height, error) ||
        !std::all_of(source.begin(), source.end(), [](float value) { return std::isfinite(value); }) ||
        !std::all_of(generated.begin(), generated.end(), [](float value) { return std::isfinite(value); })) {
        if (error != nullptr && error->empty()) {
            *error = "Inpaint Laplacian blend requires finite source/generated RGB and one normalized mask.";
        }
        return false;
    }

    const int minimum_size = static_cast<int>(std::min(width, height));
    int levels = static_cast<int>(std::floor(std::log2(static_cast<double>(minimum_size)))) - 3;
    levels = std::max(levels, 2);
    while (levels > 0 && (minimum_size >> levels) < 4) --levels;
    levels = std::max(levels, 1);

    std::vector<PyramidImage> source_gaussian;
    std::vector<PyramidImage> generated_gaussian;
    std::vector<PyramidImage> mask_gaussian;
    source_gaussian.push_back({3U, width, height, source});
    generated_gaussian.push_back({3U, width, height, generated});
    mask_gaussian.push_back({1U, width, height, mask});
    for (int level = 1; level < levels; ++level) {
        source_gaussian.push_back(pyramid_down(source_gaussian.back()));
        generated_gaussian.push_back(pyramid_down(generated_gaussian.back()));
        mask_gaussian.push_back(pyramid_down(mask_gaussian.back()));
    }

    std::vector<PyramidImage> source_laplacian;
    std::vector<PyramidImage> generated_laplacian;
    for (int level = 0; level < levels - 1; ++level) {
        PyramidImage source_up = pyramid_up(
            source_gaussian[level + 1], source_gaussian[level].width, source_gaussian[level].height);
        PyramidImage generated_up = pyramid_up(
            generated_gaussian[level + 1], generated_gaussian[level].width,
            generated_gaussian[level].height);
        for (size_t index = 0U; index < source_up.values.size(); ++index) {
            source_up.values[index] = source_gaussian[level].values[index] - source_up.values[index];
            generated_up.values[index] = generated_gaussian[level].values[index] - generated_up.values[index];
        }
        source_laplacian.push_back(std::move(source_up));
        generated_laplacian.push_back(std::move(generated_up));
    }
    source_laplacian.push_back(source_gaussian.back());
    generated_laplacian.push_back(generated_gaussian.back());

    std::vector<PyramidImage> blended;
    for (int level = 0; level < levels; ++level) {
        PyramidImage value = source_laplacian[level];
        const size_t plane = value.width * value.height;
        for (size_t channel = 0U; channel < value.channels; ++channel) {
            for (size_t pixel = 0U; pixel < plane; ++pixel) {
                const float repaint = mask_gaussian[level].values[pixel];
                const size_t index = channel * plane + pixel;
                value.values[index] = source_laplacian[level].values[index] * (1.0f - repaint) +
                    generated_laplacian[level].values[index] * repaint;
            }
        }
        blended.push_back(std::move(value));
    }

    PyramidImage result = blended.back();
    for (int level = levels - 2; level >= 0; --level) {
        PyramidImage expanded = pyramid_up(result, blended[level].width, blended[level].height);
        for (size_t index = 0U; index < expanded.values.size(); ++index) {
            expanded.values[index] += blended[level].values[index];
        }
        result = std::move(expanded);
    }
    if (!std::all_of(result.values.begin(), result.values.end(), [](float value) {
            return std::isfinite(value);
        })) {
        if (error != nullptr) *error = "Inpaint Laplacian blend produced a non-finite pixel.";
        return false;
    }
    *output = std::move(result.values);
    *level_count = levels;
    if (error != nullptr) error->clear();
    return true;
}

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

bool qnn_laplacian_blend_inpaint_vae_output(
        const QnnTensorBinding& tensor,
        const std::vector<float>& generated_values,
        mca::qnn::ImagePixelRange pixel_range,
        const std::vector<float>& source_nchw,
        const std::vector<float>& full_mask,
        std::vector<float>* blended_values,
        int* blend_levels,
        uint64_t* blend_checksum,
        std::string* error) {
    if (blended_values == nullptr || blend_levels == nullptr ||
        blend_checksum == nullptr || error == nullptr || tensor.dimensions.size() != 4U) {
        if (error != nullptr) *error = "Inpaint VAE pixel blend arguments are invalid.";
        return false;
    }
    bool nchw = false;
    size_t width = 0U;
    size_t height = 0U;
    if (tensor.dimensions[0] == 1U && tensor.dimensions[1] == 3U) {
        nchw = true;
        height = tensor.dimensions[2];
        width = tensor.dimensions[3];
    } else if (tensor.dimensions[0] == 1U && tensor.dimensions[3] == 3U) {
        height = tensor.dimensions[1];
        width = tensor.dimensions[2];
    } else {
        *error = "Inpaint VAE pixel blend requires one RGB NCHW or NHWC output.";
        return false;
    }
    const uint64_t plane_u64 = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (width == 0U || height == 0U ||
        plane_u64 > std::numeric_limits<size_t>::max() / 3U) {
        *error = "Inpaint VAE pixel blend dimensions overflow the host buffer.";
        return false;
    }
    const size_t plane = static_cast<size_t>(plane_u64);
    const size_t expected = plane * 3U;
    if (generated_values.size() != expected || source_nchw.size() != expected ||
        full_mask.size() != plane) {
        *error = "Inpaint VAE pixel blend tensors do not share one full-resolution shape.";
        return false;
    }
    const auto to_canonical = [pixel_range](float value) -> float {
        switch (pixel_range) {
            case mca::qnn::ImagePixelRange::NegativeOneToOne: return value;
            case mca::qnn::ImagePixelRange::ZeroToOne: return value * 2.0f - 1.0f;
            case mca::qnn::ImagePixelRange::ZeroTo255: return value * (2.0f / 255.0f) - 1.0f;
        }
        return std::numeric_limits<float>::quiet_NaN();
    };
    const auto from_canonical = [pixel_range](float value) -> float {
        switch (pixel_range) {
            case mca::qnn::ImagePixelRange::NegativeOneToOne: return value;
            case mca::qnn::ImagePixelRange::ZeroToOne: return (value + 1.0f) * 0.5f;
            case mca::qnn::ImagePixelRange::ZeroTo255: return (value + 1.0f) * 127.5f;
        }
        return std::numeric_limits<float>::quiet_NaN();
    };
    std::vector<float> generated_nchw(expected, 0.0f);
    for (size_t channel = 0U; channel < 3U; ++channel) {
        for (size_t pixel = 0U; pixel < plane; ++pixel) {
            const size_t source_index = nchw ? channel * plane + pixel : pixel * 3U + channel;
            generated_nchw[channel * plane + pixel] = to_canonical(generated_values[source_index]);
        }
    }
    std::vector<float> blended_nchw;
    if (!laplacian_pyramid_blend_nchw(
            source_nchw,
            generated_nchw,
            full_mask,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            &blended_nchw,
            blend_levels,
            error)) {
        return false;
    }
    *blend_checksum = checksum_float_vector(blended_nchw);
    if (*blend_checksum == 0U) {
        *error = "Inpaint Laplacian blend produced no checksum evidence.";
        return false;
    }
    blended_values->assign(expected, 0.0f);
    for (size_t channel = 0U; channel < 3U; ++channel) {
        for (size_t pixel = 0U; pixel < plane; ++pixel) {
            const size_t target_index = nchw ? channel * plane + pixel : pixel * 3U + channel;
            (*blended_values)[target_index] = from_canonical(blended_nchw[channel * plane + pixel]);
        }
    }
    if (!std::all_of(blended_values->begin(), blended_values->end(), [](float value) {
            return std::isfinite(value);
        })) {
        blended_values->clear();
        *error = "Inpaint Laplacian blend could not be represented in the VAE output range.";
        return false;
    }
    error->clear();
    return true;
}

#endif  // MCA_QNN_TENSOR_INSPECT_HAS_SDK

}  // namespace mca::qnn
