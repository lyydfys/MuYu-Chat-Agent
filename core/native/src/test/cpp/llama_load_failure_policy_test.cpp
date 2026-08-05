#include "../../main/cpp/llama_load_failure_policy.hpp"

#include <cstdlib>
#include <iostream>
#include <string>
#include <string_view>

namespace {

void expectCode(std::string_view detail, std::string_view expected) {
    const std::string actual = mca::llama::loadFailureCodeFromLlamaError(detail);
    if (actual != expected) {
        std::cerr << "Expected load failure code '" << expected
                  << "', got '" << actual << "' for diagnostic:\n"
                  << detail << '\n';
        std::abort();
    }
}

}  // namespace

int main() {
    constexpr std::string_view unsupported =
            "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION";
    constexpr std::string_view invalidMetadata =
            "MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID";
    constexpr std::string_view corrupt =
            "MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED";

    // Exact diagnostics emitted by third_party/llama.cpp/ggml/src/gguf.cpp at b10262.
    expectCode(
            "gguf_init_from_reader: bad GGUF version: 0\n"
            "gguf_init_from_reader: failed to read header\n",
            corrupt);
    expectCode(
            "gguf_init_from_reader: GGUFv1 is no longer supported, please use a more up-to-date version\n"
            "gguf_init_from_reader: failed to read header\n",
            unsupported);
    expectCode(
            "gguf_init_from_reader: this GGUF file is version 4 but this software only supports up to version 3\n"
            "gguf_init_from_reader: failed to read header\n",
            unsupported);
    expectCode(
            "gguf_init_from_reader: failed to load model: this GGUF file version 50331648 is extremely large, "
            "is there a mismatch between the host and model endianness?\n"
            "gguf_init_from_reader: failed to read header\n",
            corrupt);
    expectCode(
            "gguf_init_from_reader: key 'general.architecture' has invalid GGUF type 13\n"
            "gguf_init_from_reader: failed to read key-value pairs\n",
            invalidMetadata);
    expectCode(
            "gguf_init_from_reader: tensor 'blk.0.attn_q.weight' has invalid ggml type 43. "
            "should be in [0, 43)\n",
            unsupported);

    expectCode("failed to allocate compute buffer", "MCA_LOAD_OUT_OF_MEMORY");
    expectCode("model file is not readable", "MCA_LOAD_FILE_UNREADABLE");
    expectCode("unknown model architecture: future-model", invalidMetadata);
    expectCode("invalid magic characters: 'ABCD', expected 'GGUF'", corrupt);
    expectCode("unclassified llama model load failure", "MCA_LOAD_GGUF_MODEL_LOAD_FAILED");
    return 0;
}
