#include "sd_image_io.hpp"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#include "stb_image.h"
#include "stb_image_write.h"
#include "sd_input_contract.hpp"

namespace mca {
namespace sd {

namespace {

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

uint16_t read_tiff_u16(const uint8_t *data, bool little_endian) {
    if (little_endian) {
        return static_cast<uint16_t>(data[0]) |
               static_cast<uint16_t>(static_cast<uint16_t>(data[1]) << 8u);
    }
    return static_cast<uint16_t>(static_cast<uint16_t>(data[0]) << 8u) |
           static_cast<uint16_t>(data[1]);
}

uint32_t read_tiff_u32(const uint8_t *data, bool little_endian) {
    if (little_endian) {
        return static_cast<uint32_t>(data[0]) |
               (static_cast<uint32_t>(data[1]) << 8u) |
               (static_cast<uint32_t>(data[2]) << 16u) |
               (static_cast<uint32_t>(data[3]) << 24u);
    }
    return (static_cast<uint32_t>(data[0]) << 24u) |
           (static_cast<uint32_t>(data[1]) << 16u) |
           (static_cast<uint32_t>(data[2]) << 8u) |
           static_cast<uint32_t>(data[3]);
}

bool sha256_implementation_ready() {
    static const bool ready = [] {
        const std::vector<uint8_t> empty;
        const std::vector<uint8_t> abc = {'a', 'b', 'c'};
        return SdInputValidator::compute_sha256(empty) ==
                       "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" &&
               SdInputValidator::compute_sha256(abc) ==
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    }();
    return ready;
}

} // namespace

int jpeg_exif_orientation(const std::vector<uint8_t> &bytes) {
    if (bytes.size() < 4u || bytes[0] != 0xffu || bytes[1] != 0xd8u) return 1;
    size_t cursor = 2u;
    while (cursor + 4u <= bytes.size()) {
        if (bytes[cursor] != 0xffu) break;
        while (cursor < bytes.size() && bytes[cursor] == 0xffu) ++cursor;
        if (cursor >= bytes.size()) break;
        const uint8_t marker = bytes[cursor++];
        if (marker == 0xd9u || marker == 0xdau) break;
        if (marker == 0x01u || (marker >= 0xd0u && marker <= 0xd7u)) continue;
        if (cursor + 2u > bytes.size()) break;
        const size_t segment_length =
                (static_cast<size_t>(bytes[cursor]) << 8u) | bytes[cursor + 1u];
        if (segment_length < 2u || cursor + segment_length > bytes.size()) break;
        const size_t payload = cursor + 2u;
        const size_t payload_size = segment_length - 2u;
        cursor += segment_length;
        if (marker != 0xe1u || payload_size < 14u ||
            std::memcmp(bytes.data() + payload, "Exif\0\0", 6u) != 0) {
            continue;
        }
        const size_t tiff = payload + 6u;
        const bool little_endian = bytes[tiff] == 'I' && bytes[tiff + 1u] == 'I';
        const bool big_endian = bytes[tiff] == 'M' && bytes[tiff + 1u] == 'M';
        if ((!little_endian && !big_endian) ||
            read_tiff_u16(bytes.data() + tiff + 2u, little_endian) != 42u) {
            continue;
        }
        const uint32_t ifd_offset = read_tiff_u32(bytes.data() + tiff + 4u, little_endian);
        if (ifd_offset > payload_size - 8u || tiff + ifd_offset + 2u > payload + payload_size) {
            continue;
        }
        const size_t ifd = tiff + ifd_offset;
        const uint16_t count = read_tiff_u16(bytes.data() + ifd, little_endian);
        for (uint16_t index = 0; index < count; ++index) {
            const size_t entry = ifd + 2u + static_cast<size_t>(index) * 12u;
            if (entry + 12u > payload + payload_size) break;
            if (read_tiff_u16(bytes.data() + entry, little_endian) != 0x0112u ||
                read_tiff_u16(bytes.data() + entry + 2u, little_endian) != 3u ||
                read_tiff_u32(bytes.data() + entry + 4u, little_endian) < 1u) {
                continue;
            }
            const int orientation = static_cast<int>(
                    read_tiff_u16(bytes.data() + entry + 8u, little_endian));
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
    }
    return 1;
}

bool orient_pixels(std::vector<uint8_t> &pixels,
                   uint32_t &width,
                   uint32_t &height,
                   uint32_t channels,
                   int orientation) {
    if (orientation <= 1 || orientation > 8) return true;
    const uint32_t output_width = orientation >= 5 ? height : width;
    const uint32_t output_height = orientation >= 5 ? width : height;
    std::vector<uint8_t> oriented(pixels.size());
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            uint32_t output_x = x;
            uint32_t output_y = y;
            switch (orientation) {
                case 2: output_x = width - 1u - x; break;
                case 3:
                    output_x = width - 1u - x;
                    output_y = height - 1u - y;
                    break;
                case 4: output_y = height - 1u - y; break;
                case 5:
                    output_x = y;
                    output_y = x;
                    break;
                case 6:
                    output_x = height - 1u - y;
                    output_y = x;
                    break;
                case 7:
                    output_x = height - 1u - y;
                    output_y = width - 1u - x;
                    break;
                case 8:
                    output_x = y;
                    output_y = width - 1u - x;
                    break;
                default: break;
            }
            const size_t source =
                    (static_cast<size_t>(y) * width + x) * channels;
            const size_t target =
                    (static_cast<size_t>(output_y) * output_width + output_x) * channels;
            std::memcpy(oriented.data() + target, pixels.data() + source, channels);
        }
    }
    pixels.swap(oriented);
    width = output_width;
    height = output_height;
    return true;
}

bool load_canonical_input_image(const std::string &raw_path,
                                const std::string &expected_sha256,
                                int requested_channels,
                                LoadedInputImage &image,
                                std::string &error,
                                uint32_t maximum_side,
                                uint64_t maximum_pixels,
                                const char *size_limit_error) {
    if (raw_path.empty() || raw_path.front() != '/') {
        error = "worker input image path must be an absolute canonical path";
        return false;
    }
    if (requested_channels != 1 && requested_channels != 3) {
        error = "worker input image channel contract must request one or three channels";
        return false;
    }
    struct stat link_stat {};
    if (lstat(raw_path.c_str(), &link_stat) != 0 || !S_ISREG(link_stat.st_mode)) {
        error = "worker input image must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size <= 0 ||
        static_cast<uint64_t>(link_stat.st_size) > kMaxInputImageBytes) {
        error = "worker input image must be between 1 byte and 32 MiB";
        return false;
    }
    char path_buffer[PATH_MAX] = {};
    if (realpath(raw_path.c_str(), path_buffer) == nullptr || !file_exists(path_buffer)) {
        error = "worker input image file does not exist: " + raw_path;
        return false;
    }
    image.canonical_path = path_buffer;
    if (image.canonical_path != raw_path) {
        error = "worker input image path must already be canonical and contain no symlink traversal";
        return false;
    }
    FILE *file = std::fopen(image.canonical_path.c_str(), "rb");
    if (file == nullptr) {
        error = "worker input image could not be opened";
        return false;
    }
    std::vector<uint8_t> encoded(static_cast<size_t>(link_stat.st_size));
    const size_t read = std::fread(encoded.data(), 1u, encoded.size(), file);
    const int trailing = std::fgetc(file);
    std::fclose(file);
    if (read != encoded.size() || trailing != EOF) {
        error = "worker input image changed while native was reading it";
        return false;
    }
    if (!sha256_implementation_ready()) {
        error = "native SHA-256 self-test failed";
        return false;
    }
    image.sha256 = SdInputValidator::compute_sha256(encoded);
    if (image.sha256 != lower_copy(expected_sha256)) {
        error = "worker input image SHA-256 does not match the prepared input contract";
        return false;
    }
    int width = 0;
    int height = 0;
    int source_channels = 0;
    if (stbi_info_from_memory(
            encoded.data(),
            static_cast<int>(encoded.size()),
            &width,
            &height,
            &source_channels) == 0 || width <= 0 || height <= 0) {
        error = "failed to inspect worker input image";
        return false;
    }
    const uint64_t pixel_count = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (static_cast<uint32_t>(width) > maximum_side ||
        static_cast<uint32_t>(height) > maximum_side ||
        pixel_count > maximum_pixels) {
        error = size_limit_error;
        return false;
    }
    const int inspected_width = width;
    const int inspected_height = height;
    stbi_uc *decoded = stbi_load_from_memory(
            encoded.data(),
            static_cast<int>(encoded.size()),
            &width,
            &height,
            &source_channels,
            requested_channels);
    if (decoded == nullptr || width <= 0 || height <= 0) {
        if (decoded != nullptr) stbi_image_free(decoded);
        error = "failed to decode worker input image";
        return false;
    }
    if (width != inspected_width || height != inspected_height) {
        stbi_image_free(decoded);
        error = "worker input image dimensions changed between inspection and decode";
        return false;
    }
    const size_t byte_count = static_cast<size_t>(pixel_count) *
                              static_cast<size_t>(requested_channels);
    image.source_width = static_cast<uint32_t>(width);
    image.source_height = static_cast<uint32_t>(height);
    image.source_channels = static_cast<uint32_t>(source_channels);
    image.width = image.source_width;
    image.height = image.source_height;
    image.channels = static_cast<uint32_t>(requested_channels);
    image.pixels.assign(decoded, decoded + byte_count);
    stbi_image_free(decoded);
    image.exif_orientation = jpeg_exif_orientation(encoded);
    return orient_pixels(
            image.pixels,
            image.width,
            image.height,
            image.channels,
            image.exif_orientation);
}

bool validate_canonical_output_path(const std::string &raw_path,
                                    std::string &validated_path,
                                    std::string &error) {
    if (raw_path.empty() || raw_path.front() != '/' || raw_path.size() >= PATH_MAX) {
        error = "output path must be an absolute path shorter than PATH_MAX";
        return false;
    }
    const size_t slash = raw_path.find_last_of('/');
    if (slash == std::string::npos || slash + 1u >= raw_path.size()) {
        error = "output path must name a file in an existing directory";
        return false;
    }
    const std::string raw_parent = slash == 0u ? "/" : raw_path.substr(0, slash);
    const std::string file_name = raw_path.substr(slash + 1u);
    if (file_name == "." || file_name == ".." ||
        !ends_with(lower_copy(file_name), ".png")) {
        error = "output path must name a .png file";
        return false;
    }
    char parent_buffer[PATH_MAX] = {};
    if (realpath(raw_parent.c_str(), parent_buffer) == nullptr || !dir_exists(parent_buffer)) {
        error = "output directory does not exist";
        return false;
    }
    const std::string canonical_parent(parent_buffer);
    const std::string canonical_target = canonical_parent == "/"
            ? canonical_parent + file_name
            : canonical_parent + "/" + file_name;
    if (canonical_target != raw_path) {
        error = "output path must already be canonical and contain no symlink traversal";
        return false;
    }
    struct stat target_stat {};
    errno = 0;
    if (lstat(canonical_target.c_str(), &target_stat) == 0) {
        error = "output path must not already exist";
        return false;
    } else if (errno != ENOENT) {
        error = "output path could not be inspected";
        return false;
    }
    validated_path = canonical_target;
    return true;
}

bool build_output_paths(const std::string &raw_output_path,
                        int batch_count,
                        std::vector<std::string> &paths,
                        std::string &error) {
    std::string first;
    if (!validate_canonical_output_path(raw_output_path, first, error)) return false;
    const size_t extension = first.find_last_of('.');
    const std::string stem = first.substr(0, extension);
    const std::string suffix = first.substr(extension);
    paths.clear();
    paths.reserve(static_cast<size_t>(batch_count));
    for (int index = 0; index < batch_count; ++index) {
        const std::string candidate = index == 0
                ? first
                : stem + "-" + std::to_string(index + 1) + suffix;
        std::string validated;
        if (!validate_canonical_output_path(candidate, validated, error)) {
            paths.clear();
            return false;
        }
        paths.push_back(std::move(validated));
    }
    return true;
}

void free_generated_images(sd_image_t *images, int count) {
    if (images == nullptr) return;
    for (int index = 0; index < count; ++index) {
        free(images[index].data);
        images[index].data = nullptr;
    }
    free(images);
}

void remove_output_files(const std::vector<std::string> &paths) {
    for (const std::string &path: paths) std::remove(path.c_str());
}

} // namespace sd
} // namespace mca
