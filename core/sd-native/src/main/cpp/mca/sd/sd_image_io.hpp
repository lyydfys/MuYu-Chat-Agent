#ifndef MCA_SD_IMAGE_IO_HPP
#define MCA_SD_IMAGE_IO_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "sd_input_contract.hpp"
#include "stable-diffusion.h"

namespace mca {
namespace sd {

/**
 * Image dimensions and size limits.
 */
inline constexpr uint32_t kMaxInputImageSide = 8192u;
inline constexpr uint32_t kMaxUpscaleInputSide = 2048u;
inline constexpr uint64_t kMaxUpscaleInputPixels = 4000000ull;

/**
 * Representation of a decoded input image with EXIF orientation applied.
 */
struct LoadedInputImage {
    std::string canonical_path;
    std::string sha256;
    uint32_t source_width = 0;
    uint32_t source_height = 0;
    uint32_t source_channels = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t channels = 0;
    int exif_orientation = 0;
    std::vector<uint8_t> pixels;

    sd_image_t view() const {
        return sd_image_t{width, height, channels, const_cast<uint8_t *>(pixels.empty() ? nullptr : pixels.data())};
    }

    sd_image_t view() {
        return sd_image_t{width, height, channels, pixels.empty() ? nullptr : pixels.data()};
    }
};

/**
 * Extracts EXIF orientation tag from encoded JPEG byte stream.
 * Returns orientation 1..8, or 1 if not present / not JPEG.
 */
int jpeg_exif_orientation(const std::vector<uint8_t> &bytes);

/**
 * Reorients an uncompressed pixel buffer in-place according to EXIF orientation.
 */
bool orient_pixels(std::vector<uint8_t> &pixels,
                   uint32_t &width,
                   uint32_t &height,
                   uint32_t channels,
                   int orientation);

/**
 * Decodes and canonicalizes an input image file from disk, verifying limits and checksum.
 */
bool load_canonical_input_image(
        const std::string &raw_path,
        const std::string &expected_sha256,
        int requested_channels,
        LoadedInputImage &image,
        std::string &error,
        uint32_t maximum_side = kMaxInputImageSide,
        uint64_t maximum_pixels = kMaxInputImagePixels,
        const char *size_limit_error =
                "worker input image exceeds the 8192-pixel side or 64-megapixel limit");

/**
 * Validates canonical output PNG file path constraints and parent directory existence.
 */
bool validate_canonical_output_path(
        const std::string &raw_path,
        std::string &validated_path,
        std::string &error);

/**
 * Expands and validates output paths for batch generation.
 */
bool build_output_paths(
        const std::string &raw_output_path,
        int batch_count,
        std::vector<std::string> &paths,
        std::string &error);

/**
 * Frees memory allocated for generated sd_image_t structures.
 */
void free_generated_images(sd_image_t *images, int count);

/**
 * Removes output files from the filesystem.
 */
void remove_output_files(const std::vector<std::string> &paths);

} // namespace sd
} // namespace mca

#endif // MCA_SD_IMAGE_IO_HPP
