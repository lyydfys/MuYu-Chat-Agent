#ifndef MCA_MNN_NUMERIC_CONVERT_HPP
#define MCA_MNN_NUMERIC_CONVERT_HPP

#include <cstdint>
#include <cstring>
#include <fstream>
#include <ios>
#include <string>
#include <vector>

namespace mca {
namespace mnn {

// E08a (pure migration, L1): the binary-read and half-precision conversion helpers were moved
// verbatim out of the anonymous namespace in mnn_native_engine.cpp so their contract has a single
// owner. Function bodies are byte-identical to the bridge definitions (source block sha256
// 54b312c5594279670613672b3bbf2beb8caca88670ed511689be9171211f5757); only the `inline` keyword was added.
// Callers keep the same unqualified names via using-declarations in the bridge.

inline std::vector<unsigned char> read_binary_bytes(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) return {};
    input.seekg(0, std::ios::end);
    const auto size = input.tellg();
    if (size <= 0) return {};
    input.seekg(0, std::ios::beg);
    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    input.read(reinterpret_cast<char*>(bytes.data()), size);
    if (!input.good()) return {};
    return bytes;
}

inline float fp16_to_float(uint16_t value) {
    const uint32_t sign = (value & 0x8000u) << 16u;
    uint32_t exponent = (value >> 10u) & 0x1fu;
    uint32_t mantissa = value & 0x03ffu;
    uint32_t bits = 0;
    if (exponent == 0) {
        if (mantissa == 0) {
            bits = sign;
        } else {
            exponent = 1;
            while ((mantissa & 0x0400u) == 0) {
                mantissa <<= 1u;
                --exponent;
            }
            mantissa &= 0x03ffu;
            bits = sign | ((exponent + 112u) << 23u) | (mantissa << 13u);
        }
    } else if (exponent == 31u) {
        bits = sign | 0x7f800000u | (mantissa << 13u);
    } else {
        bits = sign | ((exponent + 112u) << 23u) | (mantissa << 13u);
    }
    float out = 0.0f;
    std::memcpy(&out, &bits, sizeof(float));
    return out;
}

inline float bf16_to_float(uint16_t value) {
    uint32_t bits = static_cast<uint32_t>(value) << 16u;
    float out = 0.0f;
    std::memcpy(&out, &bits, sizeof(float));
    return out;
}

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_NUMERIC_CONVERT_HPP
