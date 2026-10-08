#ifndef MCA_QNN_IMAGE_WRITER_HPP
#define MCA_QNN_IMAGE_WRITER_HPP

#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_shared_preview.hpp"

namespace mca::qnn {

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

/**
 * Encodes VAE output float buffer to PNG format using stb_image_write.
 */
bool write_vae_tensor_png(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        mca::qnn::ImagePixelRange pixel_range,
        mca::qnn::ImagePixelRangeEvidence* pixel_range_evidence,
        int* width,
        int* height,
        std::string* error);

/**
 * Atomically writes a VAE output tensor buffer to PNG via temporary file, validation, and atomic rename.
 */
bool write_qnn_image_png_atomic(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        mca::qnn::ImagePixelRange pixel_range,
        mca::qnn::ImagePixelRangeEvidence* pixel_range_evidence,
        int* width,
        int* height,
        std::string* error);

#endif // MCA_QNN_TENSOR_INSPECT_HAS_SDK

/**
 * Flushes regular file data to disk and validates size budget.
 */
bool fsync_regular_file(
        const std::string& path,
        std::string* error,
        uint64_t maximum_bytes = mca::qnn::preview::kMaximumPngBytes);

/**
 * Flushes directory metadata to disk.
 */
bool fsync_directory(const std::string& path, std::string* error);

/**
 * Decodes 32-bit big-endian unsigned integer from PNG header chunk.
 */
uint32_t read_png_u32(const std::array<uint8_t, 29>& header, size_t offset);

/**
 * Validates that file is a valid non-interlaced 8-bit RGB PNG matching expected dimensions.
 */
bool validate_preview_png(
        const std::string& path,
        int expected_width,
        int expected_height,
        std::string* error,
        uint64_t maximum_bytes = mca::qnn::preview::kMaximumPngBytes);

} // namespace mca::qnn

#endif // MCA_QNN_IMAGE_WRITER_HPP
