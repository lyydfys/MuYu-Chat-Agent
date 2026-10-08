#include "mca/mnn/mnn_vae_image_writer.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include <MNN/HalideRuntime.h>
#include <MNN/expr/Expr.hpp>

#if (defined(MNN_IMGCODECS) || __has_include(<cv/cv.hpp>))
#if __has_include(<cv/cv.hpp>)
#include <cv/cv.hpp>
#elif __has_include("cv/cv.hpp")
#include "cv/cv.hpp"
#endif
#endif

#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_protocol_parser.hpp"
#include "mca/mnn/mnn_tensor_ops.hpp"

namespace mca {
namespace mnn {

bool convert_vae_tensor_to_rgb(
        const FloatTensorData& image,
        int height,
        int width,
        const std::string& pixelRange,
        std::vector<uint8_t>& rgb,
        std::string& error) {
    if (height <= 0 || width <= 0) {
        error = "Invalid dimensions for VAE RGB conversion.";
        return false;
    }
    const size_t plane = static_cast<size_t>(height) * static_cast<size_t>(width);
    if (image.values.size() != plane * 3) {
        error = "VAE image value count does not match expected planar dimensions.";
        return false;
    }
    const std::string normalizedRange = normalize_mnn_contract_enum(pixelRange);
    if (normalizedRange != "negative_one_to_one" &&
        normalizedRange != "zero_to_one" &&
        normalizedRange != "zero_to_255") {
        error = "Unsupported VAE output pixel range: " + pixelRange;
        return false;
    }
    rgb.resize(plane * 3);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t idx = static_cast<size_t>(y) * width + x;
            auto toByte = [&](float value) -> uint8_t {
                float normalized = 0.0f;
                if (normalizedRange == "negative_one_to_one") {
                    normalized = value * 0.5f + 0.5f;
                } else if (normalizedRange == "zero_to_one") {
                    normalized = value;
                } else {
                    normalized = value / 255.0f;
                }
                const float scaled = std::max(0.0f, std::min(1.0f, normalized)) * 255.0f;
                return static_cast<uint8_t>(std::round(scaled));
            };
            // The SD1.5 VAE contract is NCHW RGB.  Do not treat the first
            // plane as BGR: that silently swaps red/blue while still
            // producing a structurally valid PNG and makes visual quality
            // debugging misleading.
            const uint8_t r = toByte(image.values[idx]);
            const uint8_t g = toByte(image.values[plane + idx]);
            const uint8_t b = toByte(image.values[plane * 2 + idx]);
            rgb[idx * 3] = r;
            rgb[idx * 3 + 1] = g;
            rgb[idx * 3 + 2] = b;
        }
    }
    return true;
}

bool write_rgb_image_to_file(
        const uint8_t* rgbData,
        int height,
        int width,
        const std::string& outputPath,
        std::string& error) {
    if (rgbData == nullptr) {
        error = "RGB image data is null.";
        return false;
    }
    if (!validate_output_path_for_write(outputPath, error)) {
        return false;
    }
    if (!remove_existing_output_file(outputPath, error)) {
        return false;
    }
#if (defined(MNN_IMGCODECS) || __has_include(<cv/cv.hpp>))
    auto var = MNN::Express::_Const(
            rgbData,
            {height, width, 3},
            MNN::Express::NHWC,
            halide_type_of<uint8_t>());
    if (var.get() == nullptr) {
        error = "Failed to create image tensor.";
        return false;
    }
    var.fix(MNN::Express::VARP::CONSTANT);
    if (!MNN::CV::imwrite(outputPath, var)) {
        std::string cleanupError;
        remove_existing_output_file(outputPath, cleanupError);
        error = "Failed to write output image.";
        return false;
    }
    if (!nonempty_regular_file_exists(outputPath)) {
        std::string cleanupError;
        remove_existing_output_file(outputPath, cleanupError);
        error = "Output image is missing or empty after writing.";
        return false;
    }
    return true;
#else
    error = "MNN image codecs are not available in this build.";
    return false;
#endif
}

bool write_vae_image_to_file(
        const FloatTensorData& image,
        const std::string& outputPath,
        const std::string& pixelRange,
        std::string& error) {
    if (!validate_float_tensor_contract(image, {1, 3, 512, 512}, "VAE image", error)) {
        return false;
    }
    if (!validate_output_path_for_write(outputPath, error)) {
        return false;
    }
    if (!remove_existing_output_file(outputPath, error)) {
        return false;
    }
    constexpr int height = 512;
    constexpr int width = 512;
    std::vector<uint8_t> rgb;
    if (!convert_vae_tensor_to_rgb(image, height, width, pixelRange, rgb, error)) {
        return false;
    }
    return write_rgb_image_to_file(rgb.data(), height, width, outputPath, error);
}

}  // namespace mnn
}  // namespace mca
