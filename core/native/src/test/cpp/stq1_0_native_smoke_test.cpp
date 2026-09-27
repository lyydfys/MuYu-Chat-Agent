#include <ggml.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <vector>

int main() {
    const ggml_type type = GGML_TYPE_STQ1_0;
    if (std::strcmp(ggml_type_name(type), "stq1_0") != 0) {
        std::cerr << "unexpected STQ1_0 type name\n";
        return 1;
    }
    if (!ggml_is_quantized(type) || ggml_blck_size(type) != 256 || ggml_type_size(type) != 42) {
        std::cerr << "unexpected STQ1_0 type geometry\n";
        return 1;
    }

    constexpr int64_t kElements = 256;
    const size_t rowSize = ggml_row_size(type, kElements);
    if (rowSize != 42) {
        std::cerr << "unexpected STQ1_0 row size: " << rowSize << "\n";
        return 1;
    }

    std::vector<float> source(kElements);
    for (int64_t i = 0; i < kElements; ++i) {
        source[static_cast<size_t>(i)] = std::sin(static_cast<float>(i) * 0.071f) * 2.0f;
    }
    std::vector<std::uint8_t> encoded(rowSize, 0);
    const size_t written = ggml_quantize_chunk(
        type, source.data(), encoded.data(), 0, 1, kElements, nullptr);
    if (written != rowSize) {
        std::cerr << "STQ1_0 quantizer wrote " << written << " bytes, expected " << rowSize << "\n";
        return 1;
    }
    bool nonZero = false;
    for (std::uint8_t byte : encoded) {
        nonZero |= byte != 0;
    }
    if (!nonZero) {
        std::cerr << "STQ1_0 quantizer produced an empty block\n";
        return 1;
    }

    const ggml_type_traits * traits = ggml_get_type_traits(type);
    if (traits == nullptr || traits->to_float == nullptr) {
        std::cerr << "STQ1_0 has no dequantization function\n";
        return 1;
    }
    std::vector<float> restored(kElements, 0.0f);
    traits->to_float(encoded.data(), restored.data(), kElements);
    double squaredError = 0.0;
    for (int64_t i = 0; i < kElements; ++i) {
        const float value = restored[static_cast<size_t>(i)];
        if (!std::isfinite(value)) {
            std::cerr << "STQ1_0 dequantization produced a non-finite value\n";
            return 1;
        }
        const double delta = static_cast<double>(value) - source[static_cast<size_t>(i)];
        squaredError += delta * delta;
    }
    const double rmse = std::sqrt(squaredError / static_cast<double>(kElements));
    if (!std::isfinite(rmse) || rmse > 2.0) {
        std::cerr << "STQ1_0 dequantization error is out of bounds: " << rmse << "\n";
        return 1;
    }
    std::cout << "stq1_0_native_smoke_ok rowBytes=" << rowSize << " rmse=" << rmse << "\n";
    return 0;
}
