// MCA E08a (pure migration, L1) REAL BEHAVIOUR test for mca::mnn binary/half-precision helpers.
//
// Why this file exists:
//   read_binary_bytes / fp16_to_float / bf16_to_float were moved verbatim out of the anonymous
//   namespace in mnn_native_engine.cpp into mca/mnn/mnn_numeric_convert.hpp. The move is only
//   allowed to touch text, never behaviour, so this test compiles the production header as-is and
//   pins the value contract:
//     * fp16: exhaustive over all 65536 bit patterns against an independent ldexp-based decode
//       (different algorithm from the header), including subnormals, +-0, +-inf and NaN payloads;
//     * bf16: exhaustive sign-symmetry + exact `float(v << 16)` contract + specials;
//     * read_binary_bytes: real temp-file round trip, empty file and missing file controls.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "mca/mnn/mnn_numeric_convert.hpp"

namespace {

int failures = 0;

void expect(bool condition, const std::string& what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

// Independent reference decode for fp16, assembled with ldexp arithmetic instead of the
// header's integer bit-assembly, so an equality match is a real cross-check.
double reference_fp16(uint16_t value) {
    const bool negative = (value & 0x8000u) != 0;
    const uint32_t exponent = (value >> 10u) & 0x1fu;
    const uint32_t mantissa = value & 0x03ffu;
    if (exponent == 0x1fu) {
        return mantissa == 0u ? (negative ? -INFINITY : INFINITY) : NAN;
    }
    double magnitude = 0.0;
    if (exponent == 0u) {
        magnitude = std::ldexp(static_cast<double>(mantissa) / 1024.0, -14);
    } else {
        magnitude = std::ldexp(1.0 + static_cast<double>(mantissa) / 1024.0,
                               static_cast<int>(exponent) - 15);
    }
    return negative ? -magnitude : magnitude;
}

void test_fp16_exhaustive() {
    for (uint32_t raw = 0; raw <= 0xFFFFu; ++raw) {
        const uint16_t value = static_cast<uint16_t>(raw);
        const float actual = mca::mnn::fp16_to_float(value);
        const bool negative = (value & 0x8000u) != 0;
        const uint32_t exponent = (value >> 10u) & 0x1fu;
        const uint32_t mantissa = value & 0x03ffu;
        char what[80];
        std::snprintf(what, sizeof(what), "fp16 bit pattern 0x%04X", raw);
        if (exponent == 0x1fu) {
            if (mantissa == 0u) {
                expect(std::isinf(actual) && ((actual < 0.0f) == negative), what);
            } else {
                expect(std::isnan(actual), what);
            }
            continue;
        }
        if (exponent == 0u && mantissa == 0u) {
            expect(actual == 0.0f && std::signbit(actual) == negative, what);
            continue;
        }
        const double reference = reference_fp16(value);
        expect(actual == static_cast<float>(reference), what);
    }
}

void test_bf16_exhaustive() {
    for (uint32_t raw = 0; raw <= 0xFFFFu; ++raw) {
        const uint16_t value = static_cast<uint16_t>(raw);
        const float actual = mca::mnn::bf16_to_float(value);
        const uint32_t bits = static_cast<uint32_t>(value) << 16u;
        float reference = 0.0f;
        std::memcpy(&reference, &bits, sizeof(reference));
        char what[80];
        std::snprintf(what, sizeof(what), "bf16 bit pattern 0x%04X", raw);
        if (std::isnan(reference)) {
            expect(std::isnan(actual), what);
        } else {
            expect(actual == reference, what);
            const float mirrored = mca::mnn::bf16_to_float(static_cast<uint16_t>(raw ^ 0x8000u));
            expect(mirrored == -actual || (actual == 0.0f && std::signbit(mirrored)),
                   std::string(what) + " sign symmetry");
        }
    }
    expect(mca::mnn::bf16_to_float(0x3F80) == 1.0f, "bf16 0x3F80 is 1.0f");
    expect(mca::mnn::bf16_to_float(0xC000) == -2.0f, "bf16 0xC000 is -2.0f");
    expect(mca::mnn::bf16_to_float(0x7F80) == INFINITY, "bf16 0x7F80 is +inf");
    expect(mca::mnn::bf16_to_float(0xFF80) == -INFINITY, "bf16 0xFF80 is -inf");
    expect(std::isnan(mca::mnn::bf16_to_float(0x7FC0)), "bf16 0x7FC0 is NaN");
    expect(mca::mnn::bf16_to_float(0x0001) == std::ldexp(1.0f, -133), "bf16 0x0001 is 2^-133");
}

void test_fp16_known_values() {
    expect(mca::mnn::fp16_to_float(0x3C00) == 1.0f, "fp16 0x3C00 is 1.0f");
    expect(mca::mnn::fp16_to_float(0xBC00) == -1.0f, "fp16 0xBC00 is -1.0f");
    expect(mca::mnn::fp16_to_float(0x7C00) == INFINITY, "fp16 0x7C00 is +inf");
    expect(mca::mnn::fp16_to_float(0xFC00) == -INFINITY, "fp16 0xFC00 is -inf");
    expect(std::isnan(mca::mnn::fp16_to_float(0x7E00)), "fp16 0x7E00 is NaN");
    expect(mca::mnn::fp16_to_float(0x0001) == std::ldexp(1.0f, -24), "fp16 0x0001 is 2^-24");
    expect(mca::mnn::fp16_to_float(0x03FF) == static_cast<float>(1023.0 / 1024.0 * std::ldexp(1.0, -14)),
           "fp16 largest subnormal");
    expect(mca::mnn::fp16_to_float(0x7BFF) == 65504.0f, "fp16 largest finite");
}

void test_read_binary_bytes() {
    namespace fs = std::filesystem;
    const fs::path directory = fs::temp_directory_path();
    const fs::path good = directory / "mca_e08a_numeric_convert_roundtrip.bin";
    const fs::path empty = directory / "mca_e08a_numeric_convert_empty.bin";
    const fs::path missing = directory / "mca_e08a_numeric_convert_missing.bin";
    const std::vector<unsigned char> payload = {0x00, 0x01, 0x7F, 0x80, 0xFF, 0x42, 0x00, 0x9C};
    {
        std::ofstream out(good, std::ios::binary);
        out.write(reinterpret_cast<const char*>(payload.data()),
                  static_cast<std::streamsize>(payload.size()));
    }
    { std::ofstream out(empty, std::ios::binary); }
    expect(mca::mnn::read_binary_bytes(good.string()) == payload, "binary round trip");
    expect(mca::mnn::read_binary_bytes(empty.string()).empty(), "empty file yields empty bytes");
    expect(mca::mnn::read_binary_bytes(missing.string()).empty(), "missing file yields empty bytes");
    std::error_code ignored;
    fs::remove(good, ignored);
    fs::remove(empty, ignored);
}

}  // namespace

int main() {
    test_fp16_exhaustive();
    test_fp16_known_values();
    test_bf16_exhaustive();
    test_read_binary_bytes();
    if (failures != 0) {
        std::fprintf(stderr, "mnn_numeric_convert_behavior_test: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("mnn_numeric_convert_behavior_test: PASS\n");
    return 0;
}