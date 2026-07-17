#include "../../main/cpp/qnn_image_pixel_range.hpp"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace {

void assert_conversion(
        const std::vector<float>& input,
        mca::qnn::ImagePixelRange range,
        const std::vector<uint8_t>& expected) {
    std::vector<uint8_t> actual;
    mca::qnn::ImagePixelRangeEvidence evidence;
    std::string error;
    assert(mca::qnn::convert_image_pixels_to_u8(
        input, range, &actual, &evidence, &error));
    assert(error.empty());
    assert(actual == expected);
    assert(evidence.value_count == input.size());
    assert(evidence.clamped_value_count == 0);
}

}  // namespace

int main() {
    using mca::qnn::ImagePixelRange;

    assert_conversion(
        {-1.0f, 0.0f, 1.0f},
        ImagePixelRange::NegativeOneToOne,
        {0, 128, 255});
    assert_conversion(
        {0.0f, 0.5f, 1.0f},
        ImagePixelRange::ZeroToOne,
        {0, 128, 255});
    assert_conversion(
        {0.0f, 127.5f, 255.0f},
        ImagePixelRange::ZeroTo255,
        {0, 128, 255});

    std::vector<uint8_t> clamped;
    mca::qnn::ImagePixelRangeEvidence evidence;
    std::string error;
    assert(mca::qnn::convert_image_pixels_to_u8(
        {-2.0f, -1.0f, 1.0f, 2.0f},
        ImagePixelRange::NegativeOneToOne,
        &clamped,
        &evidence,
        &error));
    assert((clamped == std::vector<uint8_t>{0, 0, 255, 255}));
    assert(evidence.value_count == 4);
    assert(evidence.clamped_value_count == 2);
    assert(evidence.observed_min == -2.0f);
    assert(evidence.observed_max == 2.0f);

    std::vector<uint8_t> invalid;
    assert(!mca::qnn::convert_image_pixels_to_u8(
        {0.0f, std::numeric_limits<float>::quiet_NaN()},
        ImagePixelRange::ZeroToOne,
        &invalid,
        &evidence,
        &error));
    assert(!error.empty());
    error.clear();
    assert(!mca::qnn::convert_image_pixels_to_u8(
        {std::numeric_limits<float>::infinity()},
        ImagePixelRange::ZeroTo255,
        &invalid,
        &evidence,
        &error));

    // The same values intentionally produce different bytes under explicit
    // contracts. There is no data-dependent min/max range selection.
    std::vector<uint8_t> zero_to_one;
    std::vector<uint8_t> negative_one_to_one;
    error.clear();
    assert(mca::qnn::convert_image_pixels_to_u8(
        {0.0f, 1.0f}, ImagePixelRange::ZeroToOne,
        &zero_to_one, &evidence, &error));
    assert(mca::qnn::convert_image_pixels_to_u8(
        {0.0f, 1.0f}, ImagePixelRange::NegativeOneToOne,
        &negative_one_to_one, &evidence, &error));
    assert((zero_to_one == std::vector<uint8_t>{0, 255}));
    assert((negative_one_to_one == std::vector<uint8_t>{128, 255}));
    return 0;
}
