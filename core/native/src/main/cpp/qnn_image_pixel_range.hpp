#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace mca::qnn {

enum class ImagePixelRange {
    NegativeOneToOne,
    ZeroToOne,
    ZeroTo255,
};

struct ImagePixelRangeEvidence {
    size_t value_count = 0;
    size_t clamped_value_count = 0;
    float observed_min = std::numeric_limits<float>::infinity();
    float observed_max = -std::numeric_limits<float>::infinity();
};

inline const char* image_pixel_range_wire_name(ImagePixelRange range) {
    switch (range) {
        case ImagePixelRange::NegativeOneToOne: return "NEGATIVE_ONE_TO_ONE";
        case ImagePixelRange::ZeroToOne: return "ZERO_TO_ONE";
        case ImagePixelRange::ZeroTo255: return "ZERO_TO_255";
    }
    return "UNKNOWN";
}

inline const char* image_pixel_range_conversion_name(ImagePixelRange range) {
    switch (range) {
        case ImagePixelRange::NegativeOneToOne: return "negative_one_to_one_to_u8";
        case ImagePixelRange::ZeroToOne: return "zero_to_one_to_u8";
        case ImagePixelRange::ZeroTo255: return "zero_to_255_to_u8";
    }
    return "unknown_to_u8";
}

inline bool image_pixel_to_u8(
        float value,
        ImagePixelRange range,
        uint8_t* output,
        bool* clamped,
        std::string* error) {
    if (output == nullptr || clamped == nullptr || error == nullptr) return false;
    if (!std::isfinite(value)) {
        *error = "VAE output contains a non-finite pixel value.";
        return false;
    }

    double byte_value = 0.0;
    double lower_bound = 0.0;
    double upper_bound = 0.0;
    switch (range) {
        case ImagePixelRange::NegativeOneToOne:
            lower_bound = -1.0;
            upper_bound = 1.0;
            byte_value = (static_cast<double>(value) + 1.0) * 0.5 * 255.0;
            break;
        case ImagePixelRange::ZeroToOne:
            lower_bound = 0.0;
            upper_bound = 1.0;
            byte_value = static_cast<double>(value) * 255.0;
            break;
        case ImagePixelRange::ZeroTo255:
            lower_bound = 0.0;
            upper_bound = 255.0;
            byte_value = static_cast<double>(value);
            break;
    }

    *clamped = static_cast<double>(value) < lower_bound ||
        static_cast<double>(value) > upper_bound;
    byte_value = std::max(0.0, std::min(255.0, byte_value));
    *output = static_cast<uint8_t>(std::lround(byte_value));
    return true;
}

inline bool convert_image_pixels_to_u8(
        const std::vector<float>& values,
        ImagePixelRange range,
        std::vector<uint8_t>* output,
        ImagePixelRangeEvidence* evidence,
        std::string* error) {
    if (output == nullptr || evidence == nullptr || error == nullptr) return false;
    output->assign(values.size(), 0);
    *evidence = ImagePixelRangeEvidence{};
    for (size_t index = 0; index < values.size(); ++index) {
        bool clamped = false;
        if (!image_pixel_to_u8(values[index], range, &(*output)[index], &clamped, error)) {
            output->clear();
            return false;
        }
        evidence->value_count += 1;
        if (clamped) evidence->clamped_value_count += 1;
        evidence->observed_min = std::min(evidence->observed_min, values[index]);
        evidence->observed_max = std::max(evidence->observed_max, values[index]);
    }
    return true;
}

}  // namespace mca::qnn
