#include "../../main/cpp/jni_utf8_codec.hpp"

#include <cassert>
#include <cstdint>
#include <string>
#include <vector>

int main() {
    using mca::utf8::decode_to_utf16;
    using mca::utf8::encode_from_utf16;

    const std::string mixed("A\0\xF0\x9F\x98\x80\xE4\xB8\xAD", 9);
    const auto decoded = decode_to_utf16(mixed, false);
    assert(decoded.incomplete_tail.empty());
    assert((decoded.utf16 == std::vector<uint16_t>{
            0x0041, 0x0000, 0xD83D, 0xDE00, 0x4E2D}));
    assert(encode_from_utf16(decoded.utf16.data(), decoded.utf16.size()) == mixed);

    const auto first = decode_to_utf16(std::string("x\xF0\x9F", 3), true);
    assert((first.utf16 == std::vector<uint16_t>{0x0078}));
    assert(first.incomplete_tail == std::string("\xF0\x9F", 2));
    const auto second = decode_to_utf16(first.incomplete_tail + std::string("\x98\x80", 2), true);
    assert(second.incomplete_tail.empty());
    assert((second.utf16 == std::vector<uint16_t>{0xD83D, 0xDE00}));

    const std::string streamed("A\xF0\x9F\x98\x80\xE4\xB8\xAD" "B", 9);
    const auto streamed_expected = decode_to_utf16(streamed, false).utf16;
    for (size_t split = 0; split <= streamed.size(); ++split) {
        const auto left = decode_to_utf16(streamed.substr(0, split), true);
        const auto right = decode_to_utf16(
                left.incomplete_tail + streamed.substr(split),
                true);
        auto combined = left.utf16;
        combined.insert(combined.end(), right.utf16.begin(), right.utf16.end());
        assert(right.incomplete_tail.empty());
        assert(combined == streamed_expected);
    }

    std::string byte_tail;
    std::vector<uint16_t> bytewise_output;
    for (char byte : streamed) {
        const auto part = decode_to_utf16(byte_tail + std::string(1, byte), true);
        bytewise_output.insert(
                bytewise_output.end(),
                part.utf16.begin(),
                part.utf16.end());
        byte_tail = part.incomplete_tail;
    }
    const auto bytewise_flush = decode_to_utf16(byte_tail, false);
    bytewise_output.insert(
            bytewise_output.end(),
            bytewise_flush.utf16.begin(),
            bytewise_flush.utf16.end());
    assert(bytewise_output == streamed_expected);

    const auto flushed = decode_to_utf16(std::string("\xE4\xB8", 2), false);
    assert(flushed.incomplete_tail.empty());
    assert((flushed.utf16 == std::vector<uint16_t>{0xFFFD}));

    const auto invalid = decode_to_utf16(std::string("\xF0\x28\x8C\x28", 4), false);
    assert((invalid.utf16 == std::vector<uint16_t>{0xFFFD, 0x0028, 0xFFFD, 0x0028}));

    const uint16_t malformed_utf16[] = {0xD83D, 0x0041, 0xDE00};
    assert(encode_from_utf16(malformed_utf16, 3) ==
           std::string("\xEF\xBF\xBD" "A" "\xEF\xBF\xBD", 7));
    return 0;
}
