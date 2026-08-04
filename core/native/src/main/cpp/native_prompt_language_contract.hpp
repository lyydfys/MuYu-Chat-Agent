#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "jni_utf8_codec.hpp"

namespace mca::image::prompt_language {

// Bump this only with a deliberate, backwards-auditable grammar change. Kotlin includes the
// same value in the profile language-binding fingerprint before a request can reach JNI.
inline constexpr uint32_t kPromptLanguageContractVersion = 1U;

inline bool is_safe_ascii_diffusion_prompt_code_point(uint32_t code_point) {
    if (code_point == 0x20U || code_point == 0x0AU || code_point == 0x0DU ||
        (code_point >= static_cast<uint32_t>('A') &&
         code_point <= static_cast<uint32_t>('Z')) ||
        (code_point >= static_cast<uint32_t>('a') &&
         code_point <= static_cast<uint32_t>('z')) ||
        (code_point >= static_cast<uint32_t>('0') &&
         code_point <= static_cast<uint32_t>('9'))) {
        return true;
    }
    switch (code_point) {
        case static_cast<uint32_t>('_'):
        case static_cast<uint32_t>(','):
        case static_cast<uint32_t>('.'):
        case static_cast<uint32_t>(';'):
        case static_cast<uint32_t>(':'):
        case static_cast<uint32_t>('!'):
        case static_cast<uint32_t>('?'):
        case static_cast<uint32_t>('"'):
        case static_cast<uint32_t>('\''):
        case static_cast<uint32_t>('+'):
        case static_cast<uint32_t>('-'):
        case static_cast<uint32_t>('*'):
        case static_cast<uint32_t>('/'):
        case static_cast<uint32_t>('\\'):
        case static_cast<uint32_t>('('):
        case static_cast<uint32_t>(')'):
        case static_cast<uint32_t>('['):
        case static_cast<uint32_t>(']'):
        case static_cast<uint32_t>('{'):
        case static_cast<uint32_t>('}'):
        case static_cast<uint32_t>('<'):
        case static_cast<uint32_t>('>'):
        case static_cast<uint32_t>('|'):
        case static_cast<uint32_t>('='):
        case static_cast<uint32_t>('@'):
        case static_cast<uint32_t>('#'):
        case static_cast<uint32_t>('%'):
        case static_cast<uint32_t>('&'):
            return true;
        default:
            return false;
    }
}

inline bool is_safe_ascii_diffusion_prompt(const std::string& value) {
    for (const unsigned char byte : value) {
        if (!is_safe_ascii_diffusion_prompt_code_point(static_cast<uint32_t>(byte))) {
            return false;
        }
    }
    return true;
}

inline bool is_safe_ascii_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt) {
    return is_safe_ascii_diffusion_prompt(prompt) &&
        is_safe_ascii_diffusion_prompt(negative_prompt);
}

inline bool is_supported_chinese_diffusion_prompt_punctuation(uint32_t code_point) {
    switch (code_point) {
        case 0xFF0CU:  // full-width comma
        case 0xFF1BU:  // full-width semicolon
        case 0x3002U:  // ideographic full stop
        case 0xFF01U:  // full-width exclamation mark
        case 0xFF1FU:  // full-width question mark
        case 0xFF08U:  // full-width left parenthesis
        case 0xFF09U:  // full-width right parenthesis
        case 0xFF3BU:  // full-width left square bracket
        case 0xFF3DU:  // full-width right square bracket
        case 0xFF5BU:  // full-width left curly bracket
        case 0xFF5DU:  // full-width right curly bracket
        case 0xFF1CU:  // full-width less-than sign
        case 0xFF1EU:  // full-width greater-than sign
        case 0xFF1AU:  // full-width colon
            return true;
        default:
            return false;
    }
}

// Mirrors the Kotlin product contract for actual CJK unified ideographs and extensions. It
// deliberately excludes radicals, ideographic-description characters, and compatibility
// ideographs: those are not normal prompt text and must not become an accidental Unicode bypass.
// Shared Han code points cannot distinguish Simplified Chinese from Traditional Chinese or
// Japanese kanji; the evidence-bound native text-encoder execution contract is the capability
// proof.
inline bool is_han_code_point(uint32_t code_point) {
    return code_point == 0x3007U ||
        (code_point >= 0x3400U && code_point <= 0x4DBFU) ||
        (code_point >= 0x4E00U && code_point <= 0x9FFFU) ||
        (code_point >= 0x20000U && code_point <= 0x2A6DFU) ||
        (code_point >= 0x2A700U && code_point <= 0x2B73FU) ||
        (code_point >= 0x2B740U && code_point <= 0x2B81FU) ||
        (code_point >= 0x2B820U && code_point <= 0x2CEAFU) ||
        (code_point >= 0x2CEB0U && code_point <= 0x2EBEFU) ||
        (code_point >= 0x2EBF0U && code_point <= 0x2EE5DU) ||
        (code_point >= 0x30000U && code_point <= 0x3134AU) ||
        (code_point >= 0x31350U && code_point <= 0x323AFU);
}

inline bool is_supported_chinese_han_diffusion_prompt(const std::string& value) {
    const auto decoded = mca::utf8::decode_to_utf16(value, false);
    const auto& units = decoded.utf16;
    for (size_t index = 0; index < units.size(); ++index) {
        uint32_t code_point = units[index];
        if (code_point >= 0xD800U && code_point <= 0xDBFFU) {
            if (index + 1U >= units.size()) return false;
            const uint32_t low = units[++index];
            if (low < 0xDC00U || low > 0xDFFFU) return false;
            code_point = 0x10000U + ((code_point - 0xD800U) << 10U) +
                (low - 0xDC00U);
        } else if (code_point >= 0xDC00U && code_point <= 0xDFFFU) {
            return false;
        }
        if (!is_safe_ascii_diffusion_prompt_code_point(code_point) &&
            !is_han_code_point(code_point) &&
            !is_supported_chinese_diffusion_prompt_punctuation(code_point)) {
            return false;
        }
    }
    return true;
}

inline bool is_supported_chinese_han_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt) {
    return is_supported_chinese_han_diffusion_prompt(prompt) &&
        is_supported_chinese_han_diffusion_prompt(negative_prompt);
}

}  // namespace mca::image::prompt_language
