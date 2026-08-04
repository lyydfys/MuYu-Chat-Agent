#include "qnn_controlnet_execution.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <system_error>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

namespace mca::qnn::controlnet {
namespace {

constexpr uint64_t kMaxEncodedBytes = 32ULL * 1024ULL * 1024ULL;
constexpr uint32_t kMaxImageSide = 8192U;
constexpr uint64_t kMaxImagePixels = 64ULL * 1024ULL * 1024ULL;
constexpr uint16_t kCannyLowThreshold = 100U;
constexpr uint16_t kCannyHighThreshold = 200U;

constexpr std::array<uint32_t, 64> kSha256RoundConstants = {
    UINT32_C(0x428a2f98), UINT32_C(0x71374491), UINT32_C(0xb5c0fbcf), UINT32_C(0xe9b5dba5),
    UINT32_C(0x3956c25b), UINT32_C(0x59f111f1), UINT32_C(0x923f82a4), UINT32_C(0xab1c5ed5),
    UINT32_C(0xd807aa98), UINT32_C(0x12835b01), UINT32_C(0x243185be), UINT32_C(0x550c7dc3),
    UINT32_C(0x72be5d74), UINT32_C(0x80deb1fe), UINT32_C(0x9bdc06a7), UINT32_C(0xc19bf174),
    UINT32_C(0xe49b69c1), UINT32_C(0xefbe4786), UINT32_C(0x0fc19dc6), UINT32_C(0x240ca1cc),
    UINT32_C(0x2de92c6f), UINT32_C(0x4a7484aa), UINT32_C(0x5cb0a9dc), UINT32_C(0x76f988da),
    UINT32_C(0x983e5152), UINT32_C(0xa831c66d), UINT32_C(0xb00327c8), UINT32_C(0xbf597fc7),
    UINT32_C(0xc6e00bf3), UINT32_C(0xd5a79147), UINT32_C(0x06ca6351), UINT32_C(0x14292967),
    UINT32_C(0x27b70a85), UINT32_C(0x2e1b2138), UINT32_C(0x4d2c6dfc), UINT32_C(0x53380d13),
    UINT32_C(0x650a7354), UINT32_C(0x766a0abb), UINT32_C(0x81c2c92e), UINT32_C(0x92722c85),
    UINT32_C(0xa2bfe8a1), UINT32_C(0xa81a664b), UINT32_C(0xc24b8b70), UINT32_C(0xc76c51a3),
    UINT32_C(0xd192e819), UINT32_C(0xd6990624), UINT32_C(0xf40e3585), UINT32_C(0x106aa070),
    UINT32_C(0x19a4c116), UINT32_C(0x1e376c08), UINT32_C(0x2748774c), UINT32_C(0x34b0bcb5),
    UINT32_C(0x391c0cb3), UINT32_C(0x4ed8aa4a), UINT32_C(0x5b9cca4f), UINT32_C(0x682e6ff3),
    UINT32_C(0x748f82ee), UINT32_C(0x78a5636f), UINT32_C(0x84c87814), UINT32_C(0x8cc70208),
    UINT32_C(0x90befffa), UINT32_C(0xa4506ceb), UINT32_C(0xbef9a3f7), UINT32_C(0xc67178f2),
};

uint32_t rotate_right(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

class Sha256State {
public:
    bool update(const uint8_t* input, size_t input_size) {
        if ((input == nullptr && input_size != 0U) ||
            input_size > (std::numeric_limits<uint64_t>::max() / UINT64_C(8)) - total_bytes_) {
            return false;
        }
        if (input_size == 0U) return true;
        total_bytes_ += static_cast<uint64_t>(input_size);
        if (buffer_size_ != 0U) {
            const size_t copied = std::min(input_size, buffer_.size() - buffer_size_);
            std::memcpy(buffer_.data() + buffer_size_, input, copied);
            buffer_size_ += copied;
            input += copied;
            input_size -= copied;
            if (buffer_size_ == buffer_.size()) {
                transform(buffer_.data());
                buffer_size_ = 0U;
            }
        }
        while (input_size >= buffer_.size()) {
            transform(input);
            input += buffer_.size();
            input_size -= buffer_.size();
        }
        if (input_size != 0U) {
            std::memcpy(buffer_.data(), input, input_size);
            buffer_size_ = input_size;
        }
        return true;
    }

    std::array<uint8_t, 32> finish() {
        const uint64_t bit_length = total_bytes_ * UINT64_C(8);
        buffer_[buffer_size_++] = UINT8_C(0x80);
        if (buffer_size_ > 56U) {
            std::fill(buffer_.begin() + static_cast<std::ptrdiff_t>(buffer_size_), buffer_.end(), 0U);
            transform(buffer_.data());
            buffer_size_ = 0U;
        }
        std::fill(
            buffer_.begin() + static_cast<std::ptrdiff_t>(buffer_size_),
            buffer_.begin() + 56,
            0U);
        for (size_t index = 0U; index < sizeof(bit_length); ++index) {
            buffer_[63U - index] = static_cast<uint8_t>(bit_length >> (index * 8U));
        }
        transform(buffer_.data());

        std::array<uint8_t, 32> digest{};
        for (size_t index = 0; index < state_.size(); ++index) {
            digest[index * 4U] = static_cast<uint8_t>(state_[index] >> 24U);
            digest[index * 4U + 1U] = static_cast<uint8_t>(state_[index] >> 16U);
            digest[index * 4U + 2U] = static_cast<uint8_t>(state_[index] >> 8U);
            digest[index * 4U + 3U] = static_cast<uint8_t>(state_[index]);
        }
        return digest;
    }

private:
    void transform(const uint8_t* block) {
        std::array<uint32_t, 64> words{};
        for (size_t index = 0; index < 16U; ++index) {
            const size_t offset = index * 4U;
            words[index] = (static_cast<uint32_t>(block[offset]) << 24U) |
                           (static_cast<uint32_t>(block[offset + 1U]) << 16U) |
                           (static_cast<uint32_t>(block[offset + 2U]) << 8U) |
                           static_cast<uint32_t>(block[offset + 3U]);
        }
        for (size_t index = 16U; index < words.size(); ++index) {
            const uint32_t s0 = rotate_right(words[index - 15U], 7U) ^
                                rotate_right(words[index - 15U], 18U) ^
                                (words[index - 15U] >> 3U);
            const uint32_t s1 = rotate_right(words[index - 2U], 17U) ^
                                rotate_right(words[index - 2U], 19U) ^
                                (words[index - 2U] >> 10U);
            words[index] = words[index - 16U] + s0 + words[index - 7U] + s1;
        }

        uint32_t a = state_[0];
        uint32_t b = state_[1];
        uint32_t c = state_[2];
        uint32_t d = state_[3];
        uint32_t e = state_[4];
        uint32_t f = state_[5];
        uint32_t g = state_[6];
        uint32_t h = state_[7];
        for (size_t index = 0; index < words.size(); ++index) {
            const uint32_t sum1 = rotate_right(e, 6U) ^ rotate_right(e, 11U) ^ rotate_right(e, 25U);
            const uint32_t choose = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + sum1 + choose + kSha256RoundConstants[index] + words[index];
            const uint32_t sum0 = rotate_right(a, 2U) ^ rotate_right(a, 13U) ^ rotate_right(a, 22U);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
    }

    std::array<uint32_t, 8> state_ = {
        UINT32_C(0x6a09e667), UINT32_C(0xbb67ae85), UINT32_C(0x3c6ef372), UINT32_C(0xa54ff53a),
        UINT32_C(0x510e527f), UINT32_C(0x9b05688c), UINT32_C(0x1f83d9ab), UINT32_C(0x5be0cd19),
    };
    std::array<uint8_t, 64> buffer_{};
    size_t buffer_size_ = 0U;
    uint64_t total_bytes_ = 0U;
};

std::array<uint8_t, 32> sha256(const std::vector<uint8_t>& input) {
    Sha256State state;
    const bool updated = state.update(input.data(), input.size());
    if (!updated) return {};
    return state.finish();
}

std::string sha256_hex_digest(const std::array<uint8_t, 32>& digest) {
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (uint8_t value : digest) out << std::setw(2) << static_cast<unsigned>(value);
    return out.str();
}

std::string sha256_hex(const std::vector<uint8_t>& payload) {
    return sha256_hex_digest(sha256(payload));
}

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool valid_sha256(const std::string& value) {
    return value.size() == 64U && std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isxdigit(c) != 0;
    });
}

uint16_t read_tiff_u16(const uint8_t* bytes, bool little_endian) {
    return little_endian
        ? static_cast<uint16_t>(bytes[0] | (static_cast<uint16_t>(bytes[1]) << 8U))
        : static_cast<uint16_t>((static_cast<uint16_t>(bytes[0]) << 8U) | bytes[1]);
}

uint32_t read_tiff_u32(const uint8_t* bytes, bool little_endian) {
    if (little_endian) {
        return static_cast<uint32_t>(bytes[0]) |
               (static_cast<uint32_t>(bytes[1]) << 8U) |
               (static_cast<uint32_t>(bytes[2]) << 16U) |
               (static_cast<uint32_t>(bytes[3]) << 24U);
    }
    return (static_cast<uint32_t>(bytes[0]) << 24U) |
           (static_cast<uint32_t>(bytes[1]) << 16U) |
           (static_cast<uint32_t>(bytes[2]) << 8U) |
           static_cast<uint32_t>(bytes[3]);
}

int jpeg_exif_orientation(const std::vector<uint8_t>& bytes) {
    if (bytes.size() < 4U || bytes[0] != 0xffU || bytes[1] != 0xd8U) return 1;
    size_t cursor = 2U;
    while (cursor + 4U <= bytes.size()) {
        if (bytes[cursor] != 0xffU) break;
        while (cursor < bytes.size() && bytes[cursor] == 0xffU) ++cursor;
        if (cursor >= bytes.size()) break;
        const uint8_t marker = bytes[cursor++];
        if (marker == 0xd9U || marker == 0xdaU) break;
        if (marker == 0x01U || (marker >= 0xd0U && marker <= 0xd7U)) continue;
        if (cursor + 2U > bytes.size()) break;
        const size_t segment_length =
            (static_cast<size_t>(bytes[cursor]) << 8U) | bytes[cursor + 1U];
        if (segment_length < 2U || cursor + segment_length > bytes.size()) break;
        const size_t payload = cursor + 2U;
        const size_t payload_size = segment_length - 2U;
        cursor += segment_length;
        if (marker != 0xe1U || payload_size < 14U ||
            std::memcmp(bytes.data() + payload, "Exif\0\0", 6U) != 0) {
            continue;
        }
        const size_t tiff = payload + 6U;
        const bool little_endian = bytes[tiff] == 'I' && bytes[tiff + 1U] == 'I';
        const bool big_endian = bytes[tiff] == 'M' && bytes[tiff + 1U] == 'M';
        if ((!little_endian && !big_endian) ||
            read_tiff_u16(bytes.data() + tiff + 2U, little_endian) != 42U) {
            continue;
        }
        const uint32_t ifd_offset = read_tiff_u32(bytes.data() + tiff + 4U, little_endian);
        if (ifd_offset > payload_size - 8U || tiff + ifd_offset + 2U > payload + payload_size) {
            continue;
        }
        const size_t ifd = tiff + ifd_offset;
        const uint16_t count = read_tiff_u16(bytes.data() + ifd, little_endian);
        for (uint16_t index = 0; index < count; ++index) {
            const size_t entry = ifd + 2U + static_cast<size_t>(index) * 12U;
            if (entry + 12U > payload + payload_size) break;
            if (read_tiff_u16(bytes.data() + entry, little_endian) != 0x0112U ||
                read_tiff_u16(bytes.data() + entry + 2U, little_endian) != 3U ||
                read_tiff_u32(bytes.data() + entry + 4U, little_endian) < 1U) {
                continue;
            }
            const int orientation = static_cast<int>(
                read_tiff_u16(bytes.data() + entry + 8U, little_endian));
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
    }
    return 1;
}

void orient_rgb(std::vector<uint8_t>* pixels,
                uint32_t* width,
                uint32_t* height,
                int orientation) {
    if (pixels == nullptr || width == nullptr || height == nullptr ||
        orientation <= 1 || orientation > 8) {
        return;
    }
    const uint32_t source_width = *width;
    const uint32_t source_height = *height;
    const uint32_t output_width = orientation >= 5 ? source_height : source_width;
    const uint32_t output_height = orientation >= 5 ? source_width : source_height;
    std::vector<uint8_t> oriented(pixels->size());
    for (uint32_t y = 0; y < source_height; ++y) {
        for (uint32_t x = 0; x < source_width; ++x) {
            uint32_t output_x = x;
            uint32_t output_y = y;
            switch (orientation) {
                case 2: output_x = source_width - 1U - x; break;
                case 3:
                    output_x = source_width - 1U - x;
                    output_y = source_height - 1U - y;
                    break;
                case 4: output_y = source_height - 1U - y; break;
                case 5:
                    output_x = y;
                    output_y = x;
                    break;
                case 6:
                    output_x = source_height - 1U - y;
                    output_y = x;
                    break;
                case 7:
                    output_x = source_height - 1U - y;
                    output_y = source_width - 1U - x;
                    break;
                case 8:
                    output_x = y;
                    output_y = source_width - 1U - x;
                    break;
                default: break;
            }
            const size_t source = (static_cast<size_t>(y) * source_width + x) * 3U;
            const size_t target = (static_cast<size_t>(output_y) * output_width + output_x) * 3U;
            std::memcpy(oriented.data() + target, pixels->data() + source, 3U);
        }
    }
    pixels->swap(oriented);
    *width = output_width;
    *height = output_height;
}

bool checked_rgb_size(uint32_t width, uint32_t height, size_t* bytes) {
    if (width == 0U || height == 0U || bytes == nullptr) return false;
    const uint64_t pixels = static_cast<uint64_t>(width) * height;
    if (pixels > std::numeric_limits<size_t>::max() / 3U) return false;
    *bytes = static_cast<size_t>(pixels * 3U);
    return true;
}

struct LanczosAxisSample {
    uint32_t first = 0U;
    std::vector<int32_t> weights;
};

constexpr int kPillowPrecisionBits = 22;

double sinc(double value) {
    if (value == 0.0) return 1.0;
    constexpr double kPi = 3.141592653589793238462643383279502884;
    const double scaled = kPi * value;
    return std::sin(scaled) / scaled;
}

double pillow_lanczos(double value) {
    return value >= -3.0 && value < 3.0
        ? sinc(value) * sinc(value / 3.0)
        : 0.0;
}

std::vector<LanczosAxisSample> pillow_lanczos_axis_plan(uint32_t input_size,
                                                        uint32_t output_size) {
    std::vector<LanczosAxisSample> plan(output_size);
    const double scale = static_cast<double>(input_size) / output_size;
    const double filter_scale = std::max(1.0, scale);
    const double support = 3.0 * filter_scale;
    for (uint32_t output_index = 0U; output_index < output_size; ++output_index) {
        const double center = (static_cast<double>(output_index) + 0.5) * scale;
        int first = static_cast<int>(center - support + 0.5);
        int last = static_cast<int>(center + support + 0.5);
        first = std::max(0, first);
        last = std::min(static_cast<int>(input_size), last);
        auto& sample = plan[output_index];
        sample.first = static_cast<uint32_t>(first);
        const int count = std::max(0, last - first);
        std::vector<double> floating(static_cast<size_t>(count), 0.0);
        double weight_sum = 0.0;
        for (int tap = 0; tap < count; ++tap) {
            const double weight = pillow_lanczos(
                (static_cast<double>(tap + first) - center + 0.5) / filter_scale);
            floating[static_cast<size_t>(tap)] = weight;
            weight_sum += weight;
        }
        sample.weights.resize(floating.size(), 0);
        for (size_t tap = 0U; tap < floating.size(); ++tap) {
            const double normalized = weight_sum != 0.0
                ? floating[tap] / weight_sum
                : floating[tap];
            const double scaled = normalized * (1 << kPillowPrecisionBits);
            sample.weights[tap] = static_cast<int32_t>(
                scaled < 0.0 ? -0.5 + scaled : 0.5 + scaled);
        }
    }
    return plan;
}

uint8_t pillow_clip_fixed_point(int64_t value) {
    const int64_t rounded = value >> kPillowPrecisionBits;
    return static_cast<uint8_t>(std::clamp<int64_t>(rounded, 0, 255));
}

bool resize_rgb_pillow_lanczos(const std::vector<uint8_t>& input,
                               uint32_t input_width,
                               uint32_t input_height,
                               uint32_t output_width,
                               uint32_t output_height,
                               std::vector<uint8_t>* output,
                               std::string* error) {
    size_t output_bytes = 0U;
    size_t input_bytes = 0U;
    if (output == nullptr || error == nullptr) return false;
    if (!checked_rgb_size(input_width, input_height, &input_bytes) ||
        input.size() != input_bytes ||
        !checked_rgb_size(output_width, output_height, &output_bytes)) {
        *error = "Control image target dimensions overflow native memory.";
        return false;
    }
    if (input_width == output_width && input_height == output_height) {
        *output = input;
        return true;
    }
    const uint64_t horizontal_elements =
        static_cast<uint64_t>(input_height) * output_width * 3ULL;
    if (horizontal_elements > std::numeric_limits<size_t>::max()) {
        *error = "Control image Lanczos workspace overflows native memory.";
        return false;
    }
    std::vector<uint8_t> horizontal;
    if (input_width == output_width) {
        horizontal = input;
    } else {
        const auto horizontal_plan =
            pillow_lanczos_axis_plan(input_width, output_width);
        horizontal.assign(static_cast<size_t>(horizontal_elements), 0U);
        for (uint32_t y = 0U; y < input_height; ++y) {
            for (uint32_t x = 0U; x < output_width; ++x) {
                const auto& sample = horizontal_plan[x];
                for (size_t channel = 0U; channel < 3U; ++channel) {
                    int64_t value = INT64_C(1) << (kPillowPrecisionBits - 1);
                    for (size_t tap = 0U; tap < sample.weights.size(); ++tap) {
                        const size_t source =
                            (static_cast<size_t>(y) * input_width +
                             sample.first + tap) * 3U + channel;
                        value += static_cast<int64_t>(input[source]) *
                            sample.weights[tap];
                    }
                    const size_t target =
                        (static_cast<size_t>(y) * output_width + x) * 3U + channel;
                    horizontal[target] = pillow_clip_fixed_point(value);
                }
            }
        }
    }
    if (input_height == output_height) {
        *output = std::move(horizontal);
        return true;
    }
    const auto vertical_plan =
        pillow_lanczos_axis_plan(input_height, output_height);
    output->assign(output_bytes, 0U);
    for (uint32_t y = 0U; y < output_height; ++y) {
        const auto& sample = vertical_plan[y];
        for (uint32_t x = 0U; x < output_width; ++x) {
            for (size_t channel = 0U; channel < 3U; ++channel) {
                int64_t value = INT64_C(1) << (kPillowPrecisionBits - 1);
                for (size_t tap = 0U; tap < sample.weights.size(); ++tap) {
                    const size_t source =
                        (static_cast<size_t>(sample.first + tap) * output_width + x) *
                            3U + channel;
                    value += static_cast<int64_t>(horizontal[source]) *
                        sample.weights[tap];
                }
                const size_t target =
                    (static_cast<size_t>(y) * output_width + x) * 3U + channel;
                (*output)[target] = pillow_clip_fixed_point(value);
            }
        }
    }
    return true;
}

std::vector<uint8_t> canny_edges(const std::vector<uint8_t>& rgb,
    uint32_t width,
    uint32_t height) {
    const size_t pixel_count = static_cast<size_t>(width) * height;
    if (width == 0U || height == 0U) return std::vector<uint8_t>(pixel_count, 0U);

    std::vector<int16_t> dx(pixel_count, 0);
    std::vector<int16_t> dy(pixel_count, 0);
    std::vector<int32_t> magnitude(pixel_count, 0);
    const auto pixel = [&](int y, int x, size_t channel) {
        const uint32_t bounded_y = static_cast<uint32_t>(std::clamp(
            y, 0, static_cast<int>(height) - 1));
        const uint32_t bounded_x = static_cast<uint32_t>(std::clamp(
            x, 0, static_cast<int>(width) - 1));
        return static_cast<int>(
            rgb[(static_cast<size_t>(bounded_y) * width + bounded_x) * 3U + channel]);
    };
    for (uint32_t y = 0U; y < height; ++y) {
        for (uint32_t x = 0U; x < width; ++x) {
            int selected_dx = 0;
            int selected_dy = 0;
            int selected_magnitude = -1;
            for (size_t channel = 0U; channel < 3U; ++channel) {
                const int channel_dx =
                    -pixel(static_cast<int>(y) - 1, static_cast<int>(x) - 1, channel) +
                     pixel(static_cast<int>(y) - 1, static_cast<int>(x) + 1, channel) -
                    2 * pixel(static_cast<int>(y), static_cast<int>(x) - 1, channel) +
                    2 * pixel(static_cast<int>(y), static_cast<int>(x) + 1, channel) -
                     pixel(static_cast<int>(y) + 1, static_cast<int>(x) - 1, channel) +
                     pixel(static_cast<int>(y) + 1, static_cast<int>(x) + 1, channel);
                const int channel_dy =
                    -pixel(static_cast<int>(y) - 1, static_cast<int>(x) - 1, channel) -
                    2 * pixel(static_cast<int>(y) - 1, static_cast<int>(x), channel) -
                     pixel(static_cast<int>(y) - 1, static_cast<int>(x) + 1, channel) +
                     pixel(static_cast<int>(y) + 1, static_cast<int>(x) - 1, channel) +
                    2 * pixel(static_cast<int>(y) + 1, static_cast<int>(x), channel) +
                     pixel(static_cast<int>(y) + 1, static_cast<int>(x) + 1, channel);
                const int channel_magnitude =
                    std::abs(channel_dx) + std::abs(channel_dy);
                if (channel_magnitude > selected_magnitude) {
                    selected_magnitude = channel_magnitude;
                    selected_dx = channel_dx;
                    selected_dy = channel_dy;
                }
            }
            const size_t index = static_cast<size_t>(y) * width + x;
            dx[index] = static_cast<int16_t>(selected_dx);
            dy[index] = static_cast<int16_t>(selected_dy);
            magnitude[index] = selected_magnitude;
        }
    }

    // OpenCV Canny map values: 0=weak edge candidate, 1=not an edge,
    // 2=strong edge. The map has an outer sentinel border, while source
    // columns 0 and width-1 still participate in non-maximum suppression.
    std::vector<uint8_t> state(pixel_count, 1U);
    std::deque<size_t> frontier;
    constexpr int kTan22_5Fixed = 13573;
    const auto magnitude_at = [&](int y, int x) {
        return y < 0 || y >= static_cast<int>(height) ||
                x < 0 || x >= static_cast<int>(width)
            ? 0
            : magnitude[static_cast<size_t>(y) * width + static_cast<size_t>(x)];
    };
    for (uint32_t y = 0U; y < height; ++y) {
        for (uint32_t x = 0U; x < width; ++x) {
            const size_t index = static_cast<size_t>(y) * width + x;
            const int current = magnitude[index];
            if (current <= static_cast<int>(kCannyLowThreshold)) continue;
            const int x_gradient = std::abs(static_cast<int>(dx[index]));
            const int64_t y_gradient =
                static_cast<int64_t>(std::abs(static_cast<int>(dy[index]))) << 15;
            const int64_t tan22_x =
                static_cast<int64_t>(x_gradient) * kTan22_5Fixed;
            bool local_maximum = false;
            if (y_gradient < tan22_x) {
                local_maximum = current > magnitude_at(
                        static_cast<int>(y), static_cast<int>(x) - 1) &&
                    current >= magnitude_at(
                        static_cast<int>(y), static_cast<int>(x) + 1);
            } else {
                const int64_t tan67_x = tan22_x +
                    (static_cast<int64_t>(x_gradient) << 16);
                if (y_gradient > tan67_x) {
                    local_maximum = current > magnitude_at(
                            static_cast<int>(y) - 1, static_cast<int>(x)) &&
                        current >= magnitude_at(
                            static_cast<int>(y) + 1, static_cast<int>(x));
                } else {
                    const int diagonal =
                        (static_cast<int>(dx[index]) ^ static_cast<int>(dy[index])) < 0
                            ? -1
                            : 1;
                    local_maximum =
                        current > magnitude_at(
                            static_cast<int>(y) - 1,
                            static_cast<int>(x) - diagonal) &&
                        current > magnitude_at(
                            static_cast<int>(y) + 1,
                            static_cast<int>(x) + diagonal);
                }
            }
            if (!local_maximum) continue;
            if (current > static_cast<int>(kCannyHighThreshold)) {
                state[index] = 2U;
                frontier.push_back(index);
            } else {
                state[index] = 0U;
            }
        }
    }
    while (!frontier.empty()) {
        const size_t index = frontier.back();
        frontier.pop_back();
        const uint32_t y = static_cast<uint32_t>(index / width);
        const uint32_t x = static_cast<uint32_t>(index % width);
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0) continue;
                const int next_x = static_cast<int>(x) + dx;
                const int next_y = static_cast<int>(y) + dy;
                if (next_x < 0 || next_y < 0 || next_x >= static_cast<int>(width) ||
                    next_y >= static_cast<int>(height)) {
                    continue;
                }
                const size_t next = static_cast<size_t>(next_y) * width + static_cast<size_t>(next_x);
                if (state[next] == 0U) {
                    state[next] = 2U;
                    frontier.push_back(next);
                }
            }
        }
    }
    std::vector<uint8_t> edges(pixel_count, 0U);
    for (size_t index = 0U; index < pixel_count; ++index) {
        if (state[index] == 2U) edges[index] = 255U;
    }
    return edges;
}

std::string normalized_tensor_name(const std::string& name) {
    std::string normalized;
    normalized.reserve(name.size());
    for (unsigned char value : name) {
        if (std::isalnum(value) != 0) normalized.push_back(static_cast<char>(std::tolower(value)));
    }
    return normalized;
}

int residual_role_index(const std::string& name, bool unet_input) {
    const std::string normalized = normalized_tensor_name(name);
    if (normalized == "midblock" || normalized == "controlnetmidblock") return 12;
    const std::string prefix = unet_input ? "controlnetdownblock" : "downblock";
    if (normalized.rfind(prefix, 0U) != 0U) {
        if (!unet_input || normalized.rfind("downblock", 0U) != 0U) return -1;
    }
    const std::string effective_prefix = normalized.rfind(prefix, 0U) == 0U
        ? prefix
        : "downblock";
    const std::string suffix = normalized.substr(effective_prefix.size());
    if (suffix.empty() || !std::all_of(suffix.begin(), suffix.end(), [](unsigned char c) {
            return std::isdigit(c) != 0;
        })) {
        return -1;
    }
    unsigned long value = 0UL;
    try {
        value = std::stoul(suffix);
    } catch (...) {
        return -1;
    }
    return value < 12UL ? static_cast<int>(value) : -1;
}

std::string residual_role_name(size_t index) {
    return index == 12U ? "mid_block" : "down_block_" + std::to_string(index);
}

}  // namespace

std::string sha256_hex_bytes(const std::vector<uint8_t>& payload) {
    return sha256_hex(payload);
}

std::string sha256_hex_bytes(const uint8_t* payload, size_t size) {
    if (payload == nullptr && size != 0U) return {};
    Sha256State state;
    if (!state.update(payload, size)) return {};
    return sha256_hex_digest(state.finish());
}

bool sha256_hex_file(const std::string& path,
                     std::string* digest,
                     std::string* error) {
    if (digest == nullptr || error == nullptr) return false;
    digest->clear();
    error->clear();
    std::error_code size_error;
    const uintmax_t expected_size = std::filesystem::file_size(path, size_error);
    if (size_error || expected_size == 0U ||
        expected_size > std::numeric_limits<uint64_t>::max() / UINT64_C(8)) {
        *error = "Artifact is missing, empty, or too large for SHA-256 length encoding.";
        return false;
    }
    std::ifstream input(path, std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open the artifact for SHA-256 verification.";
        return false;
    }
    Sha256State state;
    std::array<uint8_t, 64U * 1024U> buffer{};
    uint64_t total_read = 0U;
    while (input.good()) {
        input.read(reinterpret_cast<char*>(buffer.data()),
                   static_cast<std::streamsize>(buffer.size()));
        const std::streamsize read = input.gcount();
        if (read > 0 && !state.update(buffer.data(), static_cast<size_t>(read))) {
            *error = "Artifact is too large for SHA-256 length encoding.";
            return false;
        }
        total_read += static_cast<uint64_t>(read);
    }
    if (!input.eof()) {
        *error = "Failed to read the complete artifact for SHA-256 verification.";
        return false;
    }
    std::error_code final_size_error;
    const uintmax_t final_size = std::filesystem::file_size(path, final_size_error);
    if (final_size_error || total_read != expected_size || final_size != expected_size) {
        *error = "Artifact changed or could not be read completely during SHA-256 verification.";
        return false;
    }
    *digest = sha256_hex_digest(state.finish());
    if (digest->size() != 64U) {
        *error = "Artifact SHA-256 could not be derived.";
        return false;
    }
    return true;
}

std::string control_image_preprocess_wire_name(ControlImagePreprocessMode mode) {
    switch (mode) {
        case ControlImagePreprocessMode::Canny: return "canny";
        case ControlImagePreprocessMode::PreprocessedCanny: return "preprocessed_canny";
    }
    return "unknown";
}

bool parse_control_image_preprocess_mode(const std::string& value,
                                         ControlImagePreprocessMode* mode,
                                         std::string* error) {
    if (mode == nullptr || error == nullptr) return false;
    std::string normalized = lower_copy(value);
    std::replace(normalized.begin(), normalized.end(), '-', '_');
    if (normalized.empty() || normalized == "canny") {
        *mode = ControlImagePreprocessMode::Canny;
    } else if (normalized == "preprocessed_canny" || normalized == "edge_map") {
        *mode = ControlImagePreprocessMode::PreprocessedCanny;
    } else {
        *error = "controlImagePreprocess must be canny or preprocessed_canny.";
        return false;
    }
    error->clear();
    return true;
}

bool prepare_control_image_pixels(const std::vector<uint8_t>& rgb,
                                  uint32_t width,
                                  uint32_t height,
                                  uint32_t target_width,
                                  uint32_t target_height,
                                  ControlImagePreprocessMode mode,
                                  std::vector<float>* tensor_hwc,
                                  size_t* edge_pixel_count,
                                  std::string* preprocessed_sha256,
                                  std::string* error) {
    if (tensor_hwc == nullptr || edge_pixel_count == nullptr ||
        preprocessed_sha256 == nullptr || error == nullptr) {
        return false;
    }
    size_t expected_bytes = 0U;
    if (!checked_rgb_size(width, height, &expected_bytes) || rgb.size() != expected_bytes ||
        target_width == 0U || target_height == 0U || target_width > kMaxImageSide ||
        target_height > kMaxImageSide) {
        *error = "Control image RGB buffer or target dimensions are invalid.";
        return false;
    }
    if (mode == ControlImagePreprocessMode::PreprocessedCanny) {
        const size_t source_pixels = static_cast<size_t>(width) * height;
        for (size_t index = 0U; index < source_pixels; ++index) {
            const size_t base = index * 3U;
            const uint8_t minimum = std::min({rgb[base], rgb[base + 1U], rgb[base + 2U]});
            const uint8_t maximum = std::max({rgb[base], rgb[base + 1U], rgb[base + 2U]});
            if (static_cast<unsigned>(maximum) - minimum > 2U) {
                *error = "preprocessed_canny requires a grayscale RGB edge map.";
                return false;
            }
        }
    }
    std::vector<uint8_t> prepared;
    const size_t target_pixels = static_cast<size_t>(target_width) * target_height;
    *edge_pixel_count = 0U;
    if (mode == ControlImagePreprocessMode::PreprocessedCanny) {
        if (!resize_rgb_pillow_lanczos(
                rgb,
                width,
                height,
                target_width,
                target_height,
                &prepared,
                error)) {
            return false;
        }
        for (size_t index = 0U; index < target_pixels; ++index) {
            const size_t base = index * 3U;
            const uint8_t minimum = std::min({prepared[base], prepared[base + 1U], prepared[base + 2U]});
            const uint8_t maximum = std::max({prepared[base], prepared[base + 1U], prepared[base + 2U]});
            if (static_cast<unsigned>(maximum) - minimum > 2U) {
                *error = "preprocessed_canny requires a grayscale RGB edge map.";
                return false;
            }
            if (maximum >= 128U) ++(*edge_pixel_count);
        }
    } else {
        const std::vector<uint8_t> edges = canny_edges(rgb, width, height);
        const size_t source_pixels = static_cast<size_t>(width) * height;
        std::vector<uint8_t> source_edges_rgb(source_pixels * 3U, 0U);
        for (size_t index = 0U; index < source_pixels; ++index) {
            const uint8_t value = edges[index];
            const size_t base = index * 3U;
            source_edges_rgb[base] = value;
            source_edges_rgb[base + 1U] = value;
            source_edges_rgb[base + 2U] = value;
        }
        if (!resize_rgb_pillow_lanczos(
                source_edges_rgb,
                width,
                height,
                target_width,
                target_height,
                &prepared,
                error)) {
            return false;
        }
        for (size_t index = 0U; index < target_pixels; ++index) {
            if (prepared[index * 3U] >= 128U) ++(*edge_pixel_count);
        }
    }
    tensor_hwc->resize(prepared.size());
    for (size_t index = 0U; index < prepared.size(); ++index) {
        (*tensor_hwc)[index] = static_cast<float>(prepared[index]) / 255.0F;
    }
    *preprocessed_sha256 = sha256_hex(prepared);
    error->clear();
    return true;
}

bool load_prepared_control_image(const std::string& raw_path,
                                 const std::string& expected_sha256,
                                 uint32_t target_width,
                                 uint32_t target_height,
                                 ControlImagePreprocessMode mode,
                                 PreparedControlImage* result,
                                 std::string* error) {
    if (result == nullptr || error == nullptr) return false;
    result->tensor_hwc.clear();
    const std::string expected_hash = lower_copy(expected_sha256);
    if (raw_path.empty() || !std::filesystem::path(raw_path).is_absolute()) {
        *error = "Control image path must be absolute and canonical.";
        return false;
    }
    if (!valid_sha256(expected_hash)) {
        *error = "controlImageSha256 must be a 64-character hexadecimal digest.";
        return false;
    }
    std::error_code fs_error;
    const std::filesystem::path path(raw_path);
    const auto link_status = std::filesystem::symlink_status(path, fs_error);
    if (fs_error || std::filesystem::is_symlink(link_status) ||
        !std::filesystem::is_regular_file(link_status)) {
        *error = "Control image must be a regular non-symlink worker file.";
        return false;
    }
    const std::filesystem::path canonical = std::filesystem::canonical(path, fs_error);
    if (fs_error || canonical.string() != raw_path) {
        *error = "Control image path must already be canonical with no symlink traversal.";
        return false;
    }
    const uintmax_t encoded_size = std::filesystem::file_size(canonical, fs_error);
    if (fs_error || encoded_size == 0U || encoded_size > kMaxEncodedBytes) {
        *error = "Control image must be between 1 byte and 32 MiB.";
        return false;
    }
    std::ifstream input(canonical, std::ios::binary);
    if (!input.good()) {
        *error = "Control image worker file could not be opened.";
        return false;
    }
    std::vector<uint8_t> encoded(static_cast<size_t>(encoded_size));
    input.read(reinterpret_cast<char*>(encoded.data()), static_cast<std::streamsize>(encoded.size()));
    if (input.gcount() != static_cast<std::streamsize>(encoded.size())) {
        *error = "Control image changed while native code was reading it.";
        return false;
    }
    char trailing = 0;
    if (input.get(trailing)) {
        *error = "Control image grew while native code was reading it.";
        return false;
    }
    const std::string actual_hash = sha256_hex(encoded);
    if (actual_hash != expected_hash) {
        *error = "Control image SHA-256 differs from the prepared worker input.";
        return false;
    }

    int width = 0;
    int height = 0;
    int source_channels = 0;
    if (encoded.size() > static_cast<size_t>(std::numeric_limits<int>::max()) ||
        stbi_info_from_memory(encoded.data(), static_cast<int>(encoded.size()),
                              &width, &height, &source_channels) == 0 ||
        width <= 0 || height <= 0) {
        *error = "Control image format is unsupported or corrupt.";
        return false;
    }
    const uint64_t pixels = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (static_cast<uint32_t>(width) > kMaxImageSide ||
        static_cast<uint32_t>(height) > kMaxImageSide || pixels > kMaxImagePixels) {
        *error = "Control image exceeds the 8192-pixel side or 64-megapixel limit.";
        return false;
    }
    const int inspected_width = width;
    const int inspected_height = height;
    stbi_uc* decoded = stbi_load_from_memory(
        encoded.data(), static_cast<int>(encoded.size()), &width, &height, &source_channels, 3);
    if (decoded == nullptr || width != inspected_width || height != inspected_height) {
        if (decoded != nullptr) stbi_image_free(decoded);
        *error = "Control image decode failed or changed dimensions during decode.";
        return false;
    }
    size_t decoded_bytes = 0U;
    if (!checked_rgb_size(static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                          &decoded_bytes)) {
        stbi_image_free(decoded);
        *error = "Control image decoded dimensions overflow native memory.";
        return false;
    }
    std::vector<uint8_t> rgb(decoded, decoded + decoded_bytes);
    stbi_image_free(decoded);
    const int orientation = jpeg_exif_orientation(encoded);
    uint32_t oriented_width = static_cast<uint32_t>(width);
    uint32_t oriented_height = static_cast<uint32_t>(height);
    orient_rgb(&rgb, &oriented_width, &oriented_height, orientation);

    PreparedControlImage prepared;
    prepared.canonical_path = canonical.string();
    prepared.encoded_sha256 = actual_hash;
    prepared.source_width = static_cast<uint32_t>(width);
    prepared.source_height = static_cast<uint32_t>(height);
    prepared.source_channels = static_cast<uint32_t>(source_channels);
    prepared.oriented_width = oriented_width;
    prepared.oriented_height = oriented_height;
    prepared.tensor_width = target_width;
    prepared.tensor_height = target_height;
    prepared.tensor_channels = 3U;
    prepared.exif_orientation = orientation;
    prepared.preprocess_mode = mode;
    if (!prepare_control_image_pixels(
            rgb,
            oriented_width,
            oriented_height,
            target_width,
            target_height,
            mode,
            &prepared.tensor_hwc,
            &prepared.edge_pixel_count,
            &prepared.preprocessed_sha256,
            error)) {
        return false;
    }
    *result = std::move(prepared);
    error->clear();
    return true;
}

bool build_residual_binding_plan(const std::vector<TensorDescriptor>& control_outputs,
                                 const std::vector<TensorDescriptor>& unet_inputs,
                                 std::vector<ResidualBinding>* bindings,
                                 std::string* error) {
    if (bindings == nullptr || error == nullptr) return false;
    bindings->clear();
    if (control_outputs.size() != 13U) {
        *error = "ControlNet graph must expose exactly 12 down-block residuals and one mid-block residual.";
        return false;
    }
    std::array<int, 13> output_indices{};
    std::array<int, 13> input_indices{};
    output_indices.fill(-1);
    input_indices.fill(-1);
    for (size_t index = 0U; index < control_outputs.size(); ++index) {
        const int role = residual_role_index(control_outputs[index].name, false);
        if (role < 0 || output_indices[static_cast<size_t>(role)] >= 0) {
            *error = "ControlNet output residual names are missing, duplicated, or unsupported: " +
                control_outputs[index].name;
            return false;
        }
        output_indices[static_cast<size_t>(role)] = static_cast<int>(index);
    }
    for (size_t index = 0U; index < unet_inputs.size(); ++index) {
        const int role = residual_role_index(unet_inputs[index].name, true);
        if (role < 0) continue;
        if (input_indices[static_cast<size_t>(role)] >= 0) {
            *error = "Control-UNet residual input is duplicated: " + unet_inputs[index].name;
            return false;
        }
        input_indices[static_cast<size_t>(role)] = static_cast<int>(index);
    }
    bindings->reserve(13U);
    for (size_t role = 0U; role < 13U; ++role) {
        if (output_indices[role] < 0 || input_indices[role] < 0) {
            *error = "ControlNet/UNet residual contract is missing " + residual_role_name(role) + ".";
            bindings->clear();
            return false;
        }
        const auto& output = control_outputs[static_cast<size_t>(output_indices[role])];
        const auto& input = unet_inputs[static_cast<size_t>(input_indices[role])];
        if (output.dimensions.empty() || output.dimensions != input.dimensions ||
            std::any_of(output.dimensions.begin(), output.dimensions.end(), [](uint32_t value) {
                return value == 0U;
            })) {
            *error = "ControlNet residual shape does not exactly match Control-UNet input " +
                residual_role_name(role) + ".";
            bindings->clear();
            return false;
        }
        bindings->push_back(ResidualBinding{
            static_cast<size_t>(output_indices[role]),
            static_cast<size_t>(input_indices[role]),
            residual_role_name(role),
        });
    }
    error->clear();
    return true;
}

bool scale_residual_in_place(std::vector<float>* residual,
                             double control_strength,
                             std::string* error) {
    if (residual == nullptr || error == nullptr) return false;
    if (!std::isfinite(control_strength) || control_strength < 0.0 || control_strength > 2.0) {
        *error = "controlStrength must be finite and in [0, 2].";
        return false;
    }
    if (residual->empty()) {
        *error = "ControlNet residual tensor is empty.";
        return false;
    }
    for (float& value : *residual) {
        if (!std::isfinite(value)) {
            *error = "ControlNet produced a non-finite residual value.";
            return false;
        }
        value = static_cast<float>(static_cast<double>(value) * control_strength);
        if (!std::isfinite(value)) {
            *error = "controlStrength produced a non-finite residual value.";
            return false;
        }
    }
    error->clear();
    return true;
}

}  // namespace mca::qnn::controlnet
