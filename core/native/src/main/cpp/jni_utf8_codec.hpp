#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mca::utf8 {

constexpr uint32_t kReplacementCharacter = 0xFFFD;

struct DecodeResult {
    std::vector<uint16_t> utf16;
    std::string incomplete_tail;
};

inline void append_utf16_code_point(std::vector<uint16_t>& output, uint32_t code_point) {
    if (code_point <= 0xFFFF) {
        output.push_back(static_cast<uint16_t>(code_point));
        return;
    }
    code_point -= 0x10000;
    output.push_back(static_cast<uint16_t>(0xD800 + (code_point >> 10)));
    output.push_back(static_cast<uint16_t>(0xDC00 + (code_point & 0x3FF)));
}

inline bool is_continuation_byte(uint8_t byte) {
    return (byte & 0xC0) == 0x80;
}

inline bool is_valid_second_byte(uint8_t lead, uint8_t second) {
    if (!is_continuation_byte(second)) return false;
    if (lead == 0xE0) return second >= 0xA0;
    if (lead == 0xED) return second <= 0x9F;
    if (lead == 0xF0) return second >= 0x90;
    if (lead == 0xF4) return second <= 0x8F;
    return true;
}

// Decodes standard UTF-8, never JNI modified UTF-8/CESU-8. Invalid input is
// replaced with U+FFFD. When preserve_incomplete_tail is true, a valid prefix
// of a multibyte sequence at the end is returned verbatim for the next chunk.
inline DecodeResult decode_to_utf16(
        const std::string& input,
        bool preserve_incomplete_tail) {
    DecodeResult result;
    result.utf16.reserve(input.size());
    size_t index = 0;
    while (index < input.size()) {
        const auto lead = static_cast<uint8_t>(input[index]);
        if (lead <= 0x7F) {
            result.utf16.push_back(lead);
            ++index;
            continue;
        }

        size_t sequence_size = 0;
        uint32_t code_point = 0;
        if (lead >= 0xC2 && lead <= 0xDF) {
            sequence_size = 2;
            code_point = lead & 0x1F;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            sequence_size = 3;
            code_point = lead & 0x0F;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            sequence_size = 4;
            code_point = lead & 0x07;
        } else {
            append_utf16_code_point(result.utf16, kReplacementCharacter);
            ++index;
            continue;
        }

        const size_t available = input.size() - index;
        const size_t prefix_size = available < sequence_size ? available : sequence_size;
        bool valid_prefix = true;
        for (size_t offset = 1; offset < prefix_size; ++offset) {
            const auto byte = static_cast<uint8_t>(input[index + offset]);
            if ((offset == 1 && !is_valid_second_byte(lead, byte)) ||
                (offset > 1 && !is_continuation_byte(byte))) {
                valid_prefix = false;
                break;
            }
        }
        if (available < sequence_size && valid_prefix) {
            if (preserve_incomplete_tail) {
                result.incomplete_tail.assign(input, index, std::string::npos);
                break;
            }
            append_utf16_code_point(result.utf16, kReplacementCharacter);
            // The remaining bytes are one well-formed but truncated prefix.
            index = input.size();
            continue;
        }
        if (!valid_prefix) {
            append_utf16_code_point(result.utf16, kReplacementCharacter);
            ++index;
            continue;
        }

        for (size_t offset = 1; offset < sequence_size; ++offset) {
            code_point = (code_point << 6) |
                    (static_cast<uint8_t>(input[index + offset]) & 0x3F);
        }
        append_utf16_code_point(result.utf16, code_point);
        index += sequence_size;
    }
    return result;
}

inline void append_utf8_code_point(std::string& output, uint32_t code_point) {
    if (code_point <= 0x7F) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
        output.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else if (code_point <= 0xFFFF) {
        output.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else {
        output.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    }
}

// Converts Java-style UTF-16 code units to standard UTF-8. Valid surrogate
// pairs become one four-byte scalar; unpaired surrogates become U+FFFD.
inline std::string encode_from_utf16(const uint16_t* input, size_t length) {
    if (input == nullptr || length == 0) return {};
    std::string output;
    output.reserve(length);
    size_t index = 0;
    while (index < length) {
        const uint16_t first = input[index++];
        uint32_t code_point = first;
        if (first >= 0xD800 && first <= 0xDBFF) {
            if (index < length) {
                const uint16_t second = input[index];
                if (second >= 0xDC00 && second <= 0xDFFF) {
                    ++index;
                    code_point = 0x10000 +
                            ((static_cast<uint32_t>(first) - 0xD800) << 10) +
                            (static_cast<uint32_t>(second) - 0xDC00);
                } else {
                    code_point = kReplacementCharacter;
                }
            } else {
                code_point = kReplacementCharacter;
            }
        } else if (first >= 0xDC00 && first <= 0xDFFF) {
            code_point = kReplacementCharacter;
        }
        append_utf8_code_point(output, code_point);
    }
    return output;
}

}  // namespace mca::utf8
