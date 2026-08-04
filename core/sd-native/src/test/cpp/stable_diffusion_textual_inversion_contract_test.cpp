#include <cassert>
#include <fstream>
#include <sstream>
#include <string>

namespace {

std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void require_contains(const std::string& source, const std::string& needle) {
    assert(source.find(needle) != std::string::npos);
}

void require_absent(const std::string& source, const std::string& needle) {
    assert(source.find(needle) == std::string::npos);
}

void require_before(const std::string& source,
                    const std::string& first,
                    const std::string& second) {
    const size_t first_position = source.find(first);
    const size_t second_position = source.find(second);
    assert(first_position != std::string::npos);
    assert(second_position != std::string::npos);
    assert(first_position < second_position);
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string bridge = read_text(argv[1]);

    // The product contract is explicit and bounded; the bridge must not silently ignore an
    // embedding array on a graph that did not advertise the custom-word path.
    require_contains(bridge, "kMaxTextualInversionCount = 8u");
    require_contains(bridge, "kMaxTextualInversionBytes = 100ull * 1024ull * 1024ull");
    require_contains(bridge, "textualInversions");
    require_contains(bridge, "textualInversionSupported");
    require_contains(bridge, "SDCPP_CUSTOM_WORDS");
    require_contains(bridge, "TEXTUAL_INVERSION_FILE_INVALID");
    require_contains(bridge, "textual inversion must be a regular non-symlink file");
    require_contains(bridge, "sha256_regular_file(");
    require_contains(bridge, "textual inversion SHA-256 differs");
    require_contains(bridge, "textual inversion must be a direct child of its app-owned root");

    // The validated file paths must reach stable-diffusion.cpp's real sd_embedding_t API.
    require_contains(bridge, "std::vector<sd_embedding_t> native_embeddings");
    require_contains(bridge, "params.embeddings = native_embeddings.empty() ? nullptr : native_embeddings.data()");
    require_contains(bridge, "params.embedding_count = static_cast<uint32_t>(native_embeddings.size())");
    require_before(bridge, "validate_textual_inversion_file(", "ensure_context(");
    require_before(bridge, "params.embeddings = native_embeddings", "new_sd_ctx(&params)");

    // Host substring checks are not execution evidence. The bridge accepts success only after the
    // tokenizer, loader, custom-vector path, and every required CLIP graph publish complete masks.
    require_absent(bridge, "prompt_contains_textual_trigger");
    require_absent(bridge, "context_loaded_trigger_observed");
    require_contains(bridge, "validate_textual_inversion_execution_evidence(");
    require_contains(bridge, "textual_inversion_tokenizer_match_mask");
    require_contains(bridge, "textual_inversion_load_attempt_mask");
    require_contains(bridge, "textual_inversion_consumed_mask");
    require_contains(bridge, "textual_inversion_clip_g_required_mask");
    require_contains(bridge, "TEXTUAL_INVERSION_TRIGGER_NOT_MATCHED");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_LOAD_INCOMPLETE");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_CONDITIONING_INCOMPLETE");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_FILE_LOAD_FAILED");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_TENSOR_LOAD_FAILED");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_TENSOR_SCHEMA_INVALID");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_CLIP_PAIR_MISMATCH");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_CONDITIONING_FAILED");
    require_contains(bridge, "TEXTUAL_INVERSION_NATIVE_FAILURE_UNKNOWN");
    require_contains(bridge, "case SD_TEXTUAL_INVERSION_FAILURE_FILE_LOAD:");
    require_contains(bridge, "case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_LOAD:");
    require_contains(bridge, "case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_SCHEMA:");
    require_contains(bridge, "case SD_TEXTUAL_INVERSION_FAILURE_CLIP_PAIR_MISMATCH:");
    require_contains(bridge, "case SD_TEXTUAL_INVERSION_FAILURE_CONDITIONING:");
    require_contains(bridge, "remove_output_files(output_paths)");
    require_contains(bridge, "textualInversionEvidence");
    require_contains(bridge, "conditioning_consumed");
    require_contains(bridge, "textualInversionBindingFingerprint");
    require_contains(bridge, "textual_inversion_binding_fingerprint(");
    require_contains(bridge, "textual_inversion_selection_fingerprint(");
    return 0;
}
