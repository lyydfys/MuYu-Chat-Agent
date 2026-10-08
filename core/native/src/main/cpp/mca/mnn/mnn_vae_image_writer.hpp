#ifndef MCA_MNN_VAE_IMAGE_WRITER_HPP
#define MCA_MNN_VAE_IMAGE_WRITER_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/mnn/mnn_tensor_ops.hpp"

namespace mca {
namespace mnn {

/**
 * Normalizes and converts a VAE output FloatTensorData (shape [1, 3, height, width] NCHW)
 * to an interleaved 8-bit RGB byte buffer (height x width x 3 NHWC).
 *
 * Supported pixel ranges:
 * - "negative_one_to_one" ([-1, 1] -> [0, 255])
 * - "zero_to_one" ([0, 1] -> [0, 255])
 * - "zero_to_255" ([0, 255] -> [0, 255])
 */
bool convert_vae_tensor_to_rgb(
        const FloatTensorData& image,
        int height,
        int width,
        const std::string& pixelRange,
        std::vector<uint8_t>& rgb,
        std::string& error);

/**
 * Encodes an interleaved RGB byte buffer (NHWC format, height x width x 3) and writes
 * it to the specified file path using MNN Express and CV::imwrite.
 * Validates the file after write and removes any partial output on failure.
 */
bool write_rgb_image_to_file(
        const uint8_t* rgbData,
        int height,
        int width,
        const std::string& outputPath,
        std::string& error);

/**
 * Validates a VAE image tensor contract ([1, 3, 512, 512]), converts it to
 * interleaved RGB uint8 according to pixelRange, and writes the encoded image
 * (PNG/JPEG) to outputPath atomically.
 */
bool write_vae_image_to_file(
        const FloatTensorData& image,
        const std::string& outputPath,
        const std::string& pixelRange,
        std::string& error);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_VAE_IMAGE_WRITER_HPP
