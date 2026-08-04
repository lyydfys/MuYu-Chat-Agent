#include <cassert>
#include <cstdio>
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
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN prompt-proof needle: %s\n", needle.c_str());
    }
    assert(source.find(needle) != std::string::npos);
}

void require_not_contains_between(const std::string& source,
                                  const std::string& begin,
                                  const std::string& end,
                                  const std::string& needle) {
    const size_t begin_position = source.find(begin);
    const size_t end_position = begin_position == std::string::npos
            ? std::string::npos
            : source.find(end, begin_position + begin.size());
    assert(begin_position != std::string::npos);
    assert(end_position != std::string::npos);
    const std::string region = source.substr(begin_position, end_position - begin_position);
    if (region.find(needle) != std::string::npos) {
        std::fprintf(stderr, "unexpected MNN prompt-proof region needle: %s\n", needle.c_str());
    }
    assert(region.find(needle) == std::string::npos);
}

void require_occurrences(const std::string& source,
                         const std::string& needle,
                         size_t minimum_count) {
    size_t count = 0;
    size_t position = 0;
    while ((position = source.find(needle, position)) != std::string::npos) {
        ++count;
        position += needle.size();
    }
    assert(count >= minimum_count);
}

void require_before(const std::string& source,
                    const std::string& first,
                    const std::string& second) {
    const size_t first_position = source.find(first);
    const size_t second_position = source.find(second);
    if (first_position == std::string::npos || second_position == std::string::npos ||
        first_position >= second_position) {
        std::fprintf(stderr,
                     "MNN prompt-proof ordering mismatch: first=%s second=%s\n",
                     first.c_str(),
                     second.c_str());
    }
    assert(first_position != std::string::npos);
    assert(second_position != std::string::npos);
    assert(first_position < second_position);
}

void require_sequence_from(const std::string& source,
                           const std::string& anchor,
                           const std::string& middle,
                           const std::string& last) {
    const size_t anchor_position = source.find(anchor);
    const size_t middle_position = anchor_position == std::string::npos
            ? std::string::npos
            : source.find(middle, anchor_position + anchor.size());
    const size_t last_position = middle_position == std::string::npos
            ? std::string::npos
            : source.find(last, middle_position + middle.size());
    if (anchor_position == std::string::npos || middle_position == std::string::npos ||
        last_position == std::string::npos) {
        std::fprintf(stderr,
                     "MNN prompt-proof sequence missing: anchor=%s middle=%s last=%s\n",
                     anchor.c_str(),
                     middle.c_str(),
                     last.c_str());
    }
    assert(anchor_position != std::string::npos);
    assert(middle_position != std::string::npos);
    assert(last_position != std::string::npos);
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string source = read_text(argv[1]);

    require_contains(source, "image_prompt_execution_sha256(prompt, contract.negative_prompt)");
    require_contains(source, "nativePromptExecutionSha256");
    require_contains(source, "nativePromptBindingStage");
    require_contains(source, "conditioning_consumed");
    require_contains(source, "conditioning_encoded");
    require_contains(source, "bool mnn_execution_assets_match(");
    require_contains(source, "realpath(path.c_str(), canonical)");
    require_contains(source, "describedPaths != canonicalRequiredPaths");
    require_contains(source, "join_path(root, \"clip_v2.mnn\")");
    require_contains(source, "join_path(root, \"clip_2.mnn.weight\")");
    require_occurrences(source, "join_path(root, \"clip_v2.mnn.weight\")", 2U);
    require_occurrences(source, "nonempty_regular_file_exists(clipWeightPath)", 2U);
    require_occurrences(
            source,
            "interpreter->setExternalFile(pinnedExternalWeightPath.c_str())",
            2U);
    require_contains(source, "useClip1Weight ? clip1WeightPath : \"\"");
    require_occurrences(source, "useClipWeight ? clipWeightPath : \"\"", 2U);
    require_contains(source, "const bool useCommunityClip = mnn_contract_uses_community_clip(contract);");
    require_contains(source, "if (!useCommunityClip || !has_community_clip_bundle(root, selectedTextEncoderPath))");
    require_not_contains_between(
            source,
            "json run_mnn_sd15_interpreter_direct(",
            "const bool includeDebug =",
            "join_path(root, \"unet.mnn\")");
    require_not_contains_between(
            source,
            "json run_mnn_sd15_interpreter_direct(",
            "DirectMnnUnetSession unetSession",
            "!file_exists(root + \"/text_encoder.mnn\")");

    // SD1.5 publishes the raw-string binding only after an actual text encoder
    // or community CLIP path produced and validated the conditioning tensor.
    require_before(source,
                   "Text encoder output",
                   "evidence.native_prompt_execution_sha256 =");
    require_before(source,
                   "evidence.native_prompt_execution_sha256 =",
                   "DirectMnnUnetSession unetSession");

    // Sana publishes only after session.run and the tokenizer/conditioning
    // execution evidence validator have both succeeded.
    require_sequence_from(source,
                          "const bool sana_ok = session.run();",
                          "if (!mnn_sana_native_effective_json(",
                          "const std::string nativePromptExecutionSha256 =");

    // Both QNN float-conditioning producers bind the exact prompt pair only
    // after the graph outputs have been serialized and hashed as one artifact.
    require_contains(source, "SDXL conditioning artifact or native prompt SHA-256");
    require_contains(source, "Community CLIP conditioning artifact or native prompt SHA-256");
    require_contains(source, "Weighted CLIP token payload prompt SHA-256 could not be derived.");
    require_contains(source, "encode_prompt_token_ids_with_weights_from_json(");
    require_before(source,
                   "if (!write_float_file(outputPath, combined, error))",
                   "{\"nativePromptBindingStage\", \"conditioning_encoded\"}");
    require_before(source,
                   "Weighted CLIP token payload prompt SHA-256 could not be derived.",
                   "Failed to publish weighted CLIP token payload.");
    return 0;
}
