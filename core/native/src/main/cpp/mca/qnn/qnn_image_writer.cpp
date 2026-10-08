#include "mca/qnn/qnn_image_writer.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include "mca/qnn/qnn_preview_manager.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_shared_preview.hpp"

namespace mca::qnn {

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

bool write_vae_tensor_png(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        mca::qnn::ImagePixelRange pixel_range,
        mca::qnn::ImagePixelRangeEvidence* pixel_range_evidence,
        int* width,
        int* height,
        std::string* error) {
    if (pixel_range_evidence == nullptr || width == nullptr || height == nullptr || error == nullptr) {
        return false;
    }
    if (tensor.dimensions.size() != 4) {
        *error = "VAE output tensor is expected to be rank 4.";
        return false;
    }
    const int n = static_cast<int>(tensor.dimensions[0]);
    (void)n;
    int channels = 0;
    bool nchw = false;
    if (tensor.dimensions[1] == 3) {
        nchw = true;
        channels = 3;
        *height = static_cast<int>(tensor.dimensions[2]);
        *width = static_cast<int>(tensor.dimensions[3]);
    } else if (tensor.dimensions[3] == 3) {
        nchw = false;
        channels = 3;
        *height = static_cast<int>(tensor.dimensions[1]);
        *width = static_cast<int>(tensor.dimensions[2]);
    } else {
        *error = "VAE output tensor does not expose 3 RGB channels.";
        return false;
    }
    if (*width <= 0 || *height <= 0 || channels != 3) {
        *error = "Invalid VAE output dimensions.";
        return false;
    }
    const size_t expected = static_cast<size_t>(*width) * static_cast<size_t>(*height) * 3;
    if (values.size() < expected) {
        *error = "VAE output float buffer is smaller than expected RGB size.";
        return false;
    }

    std::vector<uint8_t> rgb(expected, 0);
    *pixel_range_evidence = mca::qnn::ImagePixelRangeEvidence{};
    auto sample = [&](int c, int y, int x) -> float {
        if (nchw) {
            return values[static_cast<size_t>(c) * (*height) * (*width) +
                          static_cast<size_t>(y) * (*width) + x];
        }
        return values[(static_cast<size_t>(y) * (*width) + x) * 3 + c];
    };
    for (int y = 0; y < *height; ++y) {
        for (int x = 0; x < *width; ++x) {
            for (int c = 0; c < 3; ++c) {
                const float value = sample(c, y, x);
                uint8_t byte_value = 0;
                bool clamped = false;
                if (!mca::qnn::image_pixel_to_u8(
                        value,
                        pixel_range,
                        &byte_value,
                        &clamped,
                        error)) {
                    return false;
                }
                pixel_range_evidence->value_count += 1;
                if (clamped) pixel_range_evidence->clamped_value_count += 1;
                pixel_range_evidence->observed_min = std::min(
                    pixel_range_evidence->observed_min,
                    value);
                pixel_range_evidence->observed_max = std::max(
                    pixel_range_evidence->observed_max,
                    value);
                rgb[(static_cast<size_t>(y) * (*width) + x) * 3 + c] = byte_value;
            }
        }
    }
    if (stbi_write_png(output_path.c_str(), *width, *height, 3, rgb.data(), (*width) * 3) == 0) {
        *error = "Failed to write PNG output: " + output_path;
        return false;
    }
    return true;
}

#endif // MCA_QNN_TENSOR_INSPECT_HAS_SDK

bool fsync_regular_file(
        const std::string& path,
        std::string* error,
        uint64_t maximum_bytes) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Unable to open preview PNG for fsync.";
        return false;
    }
    struct stat st {};
    const bool valid = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        st.st_size > 0 && static_cast<uint64_t>(st.st_size) <= maximum_bytes;
    const bool synced = valid && ::fsync(fd) == 0;
    ::close(fd);
    if (!valid) {
        *error = "PNG is empty, non-regular, or exceeds the output byte budget.";
        return false;
    }
    if (!synced) {
        *error = "Unable to fsync preview PNG.";
        return false;
    }
    return true;
}

bool fsync_directory(const std::string& path, std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Unable to open preview directory for fsync.";
        return false;
    }
    const bool synced = ::fsync(fd) == 0;
    ::close(fd);
    if (!synced) {
        *error = "Unable to fsync preview directory.";
        return false;
    }
    return true;
}

uint32_t read_png_u32(const std::array<uint8_t, 29>& header, size_t offset) {
    return (static_cast<uint32_t>(header[offset]) << 24U) |
        (static_cast<uint32_t>(header[offset + 1U]) << 16U) |
        (static_cast<uint32_t>(header[offset + 2U]) << 8U) |
        static_cast<uint32_t>(header[offset + 3U]);
}

bool validate_preview_png(
        const std::string& path,
        int expected_width,
        int expected_height,
        std::string* error,
        uint64_t maximum_bytes) {
    if (!fsync_regular_file(path, error, maximum_bytes)) return false;
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Unable to reopen preview PNG for validation.";
        return false;
    }
    std::array<uint8_t, 29> header{};
    size_t consumed = 0U;
    while (consumed < header.size()) {
        const ssize_t read_count = ::read(
            fd,
            header.data() + consumed,
            header.size() - consumed);
        if (read_count <= 0) break;
        consumed += static_cast<size_t>(read_count);
    }
    ::close(fd);
    static constexpr std::array<uint8_t, 8> kPngSignature = {
        0x89U, 0x50U, 0x4eU, 0x47U, 0x0dU, 0x0aU, 0x1aU, 0x0aU,
    };
    const bool signature_matches = consumed == header.size() &&
        std::equal(kPngSignature.begin(), kPngSignature.end(), header.begin());
    const bool ihdr_matches = read_png_u32(header, 8U) == 13U &&
        header[12] == 'I' && header[13] == 'H' &&
        header[14] == 'D' && header[15] == 'R';
    const bool dimensions_match =
        read_png_u32(header, 16U) == static_cast<uint32_t>(expected_width) &&
        read_png_u32(header, 20U) == static_cast<uint32_t>(expected_height);
    const bool format_matches = header[24] == 8U && header[25] == 2U &&
        header[26] == 0U && header[27] == 0U && header[28] == 0U;
    if (!signature_matches || !ihdr_matches || !dimensions_match || !format_matches) {
        *error = "Preview output is not the expected non-interlaced 8-bit RGB PNG.";
        return false;
    }
    return true;
}

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

bool write_qnn_image_png_atomic(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        mca::qnn::ImagePixelRange pixel_range,
        mca::qnn::ImagePixelRangeEvidence* pixel_range_evidence,
        int* width,
        int* height,
        std::string* error) {
    if (output_path.empty() || error == nullptr) return false;
    const size_t separator = output_path.find_last_of('/');
    if (separator == std::string::npos || separator == 0U) {
        *error = "QNN image output must be inside an app-owned directory.";
        return false;
    }
    const std::string directory = output_path.substr(0U, separator);
    const std::string temporary = output_path + ".part";
    ::unlink(temporary.c_str());
    if (!write_vae_tensor_png(
            tensor,
            values,
            temporary,
            pixel_range,
            pixel_range_evidence,
            width,
            height,
            error) ||
        !validate_preview_png(temporary, *width, *height, error, 64U * 1024U * 1024U)) {
        ::unlink(temporary.c_str());
        return false;
    }
    if (qnn_image_generation_cancelled()) {
        ::unlink(temporary.c_str());
        *error = "Image generation was cancelled before QNN image output publication.";
        return false;
    }
    struct stat existing {};
    if (::lstat(output_path.c_str(), &existing) == 0 ||
        ::rename(temporary.c_str(), output_path.c_str()) != 0) {
        ::unlink(temporary.c_str());
        *error = "QNN image output atomic rename failed or would replace an existing file.";
        return false;
    }
    if (!fsync_directory(directory, error)) {
        ::unlink(output_path.c_str());
        std::string ignored;
        fsync_directory(directory, &ignored);
        return false;
    }
    error->clear();
    return true;
}

#endif // MCA_QNN_TENSOR_INSPECT_HAS_SDK

} // namespace mca::qnn
