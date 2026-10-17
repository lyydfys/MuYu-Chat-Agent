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

std::string directory_of(const std::string& path) {
    const auto last_slash = path.find_last_of("/\\");
    return last_slash == std::string::npos ? std::string() : path.substr(0, last_slash);
}

// The textual-inversion validation, evidence and mask logic was extracted out of the bridge into
// mca/sd modules.  The `contains` assertions below therefore observe the bridge PLUS those modules;
// a missing module fails the test loudly instead of silently shrinking the read set (the previous
// revision only passed because the deleted comment block inside the bridge repeated the same strings).
std::string read_required_text(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
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
    const std::string dir = directory_of(std::string(argv[1]));
    // T2B17/R7-C6: the generate phases were split out of the bridge, so the read set gains
    // the request/execute phase files that now own the textual-inversion validation and
    // evidence needles this test pins.  A missing module still fails the test loudly.
    const std::string contract =
            bridge +
            read_required_text(dir + "/sd_generate_request.cpp") +
            read_required_text(dir + "/sd_generate_execute.cpp") +
            read_required_text(dir + "/mca/sd/sd_contract_parser.cpp") +
            read_required_text(dir + "/mca/sd/sd_component_resolver.cpp") +
            read_required_text(dir + "/mca/sd/sd_textual_inversion.cpp") +
            read_required_text(dir + "/mca/sd/sd_response_builder.cpp");

    // The product contract is explicit and bounded; the bridge must not silently ignore an
    // embedding array on a graph that did not advertise the custom-word path.
    require_contains(contract, "kMaxTextualInversionCount = 8u");
    require_contains(contract, "kMaxTextualInversionBytes = 100ull * 1024ull * 1024ull");
    require_contains(contract, "textualInversions");
    require_contains(contract, "textualInversionSupported");
    require_contains(contract, "SDCPP_CUSTOM_WORDS");
    require_contains(contract, "TEXTUAL_INVERSION_FILE_INVALID");
    require_contains(contract, "textual inversion must be a regular non-symlink file");
    require_contains(contract, "sha256_regular_file(");
    require_contains(contract, "textual inversion SHA-256 differs");
    require_contains(contract, "textual inversion must be a direct child of its app-owned root");

    // The validated file paths must reach stable-diffusion.cpp's real sd_embedding_t API.
    require_contains(contract, "std::vector<sd_embedding_t> native_embeddings");
    require_contains(contract, "params.embeddings = native_embeddings.empty() ? nullptr : native_embeddings.data()");
    require_contains(contract, "params.embedding_count = static_cast<uint32_t>(native_embeddings.size())");
    // The validation call must run before the context that consumes the embeddings is created. The
    // R7-C6 split moved those two call sites into the request phase (S1) and the context phase (S2);
    // the ordered union below concatenates request -> context so "validation precedes context
    // creation" still runs across the same real call sites (the old one-line declaration
    // "sd_ctx_t *ctx = ensure_context(" narrowed to the assignment "ctx = ensure_context(").
    const std::string generate_phases =
            read_required_text(dir + "/sd_generate_request.cpp") +
            read_required_text(dir + "/sd_generate_context.cpp");
    require_before(generate_phases, "if (!validate_textual_inversion_file(", "ctx = ensure_context(");
    require_before(bridge, "params.embeddings = native_embeddings", "new_sd_ctx(&params)");

    // Host substring checks are not execution evidence. The bridge accepts success only after the
    // tokenizer, loader, custom-vector path, and every required CLIP graph publish complete masks.
    require_absent(bridge, "prompt_contains_textual_trigger");
    require_absent(bridge, "context_loaded_trigger_observed");
    require_contains(contract, "validate_textual_inversion_execution_evidence(");
    require_contains(contract, "textual_inversion_tokenizer_match_mask");
    require_contains(contract, "textual_inversion_load_attempt_mask");
    require_contains(contract, "textual_inversion_consumed_mask");
    require_contains(contract, "textual_inversion_clip_g_required_mask");
    require_contains(contract, "TEXTUAL_INVERSION_TRIGGER_NOT_MATCHED");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_LOAD_INCOMPLETE");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_CONDITIONING_INCOMPLETE");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_FILE_LOAD_FAILED");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_TENSOR_LOAD_FAILED");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_TENSOR_SCHEMA_INVALID");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_CLIP_PAIR_MISMATCH");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_CONDITIONING_FAILED");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_FAILURE_UNKNOWN");
    require_contains(contract, "case SD_TEXTUAL_INVERSION_FAILURE_FILE_LOAD:");
    require_contains(contract, "case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_LOAD:");
    require_contains(contract, "case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_SCHEMA:");
    require_contains(contract, "case SD_TEXTUAL_INVERSION_FAILURE_CLIP_PAIR_MISMATCH:");
    require_contains(contract, "case SD_TEXTUAL_INVERSION_FAILURE_CONDITIONING:");
    require_contains(contract, "remove_output_files(output_paths)");
    require_contains(contract, "textualInversionEvidence");
    require_contains(contract, "conditioning_consumed");
    require_contains(contract, "textualInversionBindingFingerprint");
    require_contains(contract, "textual_inversion_binding_fingerprint(");
    require_contains(contract, "textual_inversion_selection_fingerprint(");
    return 0;
}
