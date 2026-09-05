#pragma once

#include <cctype>
#include <initializer_list>
#include <string>
#include <string_view>

namespace mca::llama {

inline std::string loadFailureLowerAscii(std::string_view value) {
    std::string lower(value);
    for (char &character : lower) {
        character = static_cast<char>(
                std::tolower(static_cast<unsigned char>(character)));
    }
    return lower;
}

inline bool loadFailureContainsAny(
        std::string_view value,
        std::initializer_list<std::string_view> needles) noexcept {
    for (const std::string_view needle : needles) {
        if (value.find(needle) != std::string_view::npos) {
            return true;
        }
    }
    return false;
}

inline std::string loadFailureCodeFromLlamaError(std::string_view detail) {
    const std::string lower = loadFailureLowerAscii(detail);
    if (loadFailureContainsAny(lower, {
            "out of memory", "std::bad_alloc", "bad alloc", "cannot allocate",
            "failed to allocate", "failed to alloc"
    })) {
        return "MCA_LOAD_OUT_OF_MEMORY";
    }
    if (loadFailureContainsAny(lower, {
            "permission denied", "no such file", "file not found", "not readable"
    })) {
        return "MCA_LOAD_FILE_UNREADABLE";
    }
    if (loadFailureContainsAny(lower, {
            "unsupported quant", "unsupported tensor type", "unsupported ggml type",
            "unknown tensor type", "unsupported operation", "unsupported file version",
            "not implemented",
            // Exact GGUF parser diagnostics in llama.cpp b10590.
            "ggufv1 is no longer supported",
            "this software only supports up to version",
            "has invalid ggml type"
    })) {
        return "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION";
    }
    if (loadFailureContainsAny(lower, {
            "invalid metadata", "metadata key", "metadata value", "key-value",
            "unknown model architecture", "invalid architecture",
            "has invalid gguf type"
    })) {
        return "MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID";
    }
    if (loadFailureContainsAny(lower, {
            "invalid magic", "not a gguf", "truncated", "unexpected end",
            "end of file", "short read", "failed to read tensor", "read error",
            "bad gguf version:",
            "is extremely large, is there a mismatch between the host and model endianness?"
    })) {
        return "MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED";
    }
    return "MCA_LOAD_GGUF_MODEL_LOAD_FAILED";
}

}  // namespace mca::llama
