#include "../../main/cpp/image_conditioning.hpp"
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"

#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <utility>

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

// Read a file that the contract REQUIRES to exist. A future extraction that moves the code again must
// fail this test loudly instead of silently shrinking the read set (which is how the planted
// "contract needle" comment block in qnn_native_bridge.cpp used to keep this test green).
std::string read_required_text(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        std::fprintf(
            stderr,
            "QNN prompt-proof contract: required extracted module is MISSING: %s\n",
            path.c_str());
        assert(false);
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

// The QNN bridge was slimmed: the prompt-proof gate, the consumed-payload binding and the
// structured-prompt decoding now live in mca/qnn modules. This test asserts on code that those
// modules own, so the read set is composed from the bridge plus those modules rather than relying on
// a comment block inside the bridge that merely repeated the same strings.
std::string read_bridge_and_extracted_modules(const char* bridge_path) {
    const std::string bridge = read_text(bridge_path);
    const std::string dir = directory_of(std::string(bridge_path));
    std::string composed = bridge;
    for (const char* module : {
                 "mca/qnn/qnn_contract_parser.cpp",
                 "mca/qnn/qnn_semantic_pipeline.cpp",
                 "mca/qnn/qnn_semantic_sdxl.cpp",
                 "mca/qnn/qnn_semantic_evidence.cpp",
                 "mca/qnn/qnn_conditioning_pipeline.cpp",
                 "mca/qnn/qnn_conditioning_handoff_binding.cpp",
         }) {
        composed += read_required_text(dir + "/" + module);
    }
    return composed;
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing QNN prompt-proof needle: %s\n", needle.c_str());
    }
    assert(source.find(needle) != std::string::npos);
}

void require_count_at_least(
        const std::string& source,
        const std::string& needle,
        size_t expected) {
    size_t count = 0U;
    size_t position = 0U;
    while ((position = source.find(needle, position)) != std::string::npos) {
        ++count;
        position += needle.size();
    }
    if (count < expected) {
        std::fprintf(
            stderr,
            "QNN prompt-proof count mismatch: needle=%s expected-at-least=%zu actual=%zu\n",
            needle.c_str(),
            expected,
            count);
    }
    assert(count >= expected);
}

void require_sequence_from(
        const std::string& source,
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
        std::fprintf(
            stderr,
            "QNN prompt-proof sequence missing: anchor=%s middle=%s last=%s\n",
            anchor.c_str(),
            middle.c_str(),
            last.c_str());
    }
    assert(anchor_position != std::string::npos);
    assert(middle_position != std::string::npos);
    assert(last_position != std::string::npos);
}

std::pair<std::string, std::string> parse_prompt_pair(const std::string& json) {
    const nlohmann::json root = nlohmann::json::parse(json);
    assert(root.is_object());
    assert(root.contains("prompt") && root["prompt"].is_string());
    assert(root.contains("negativePrompt") && root["negativePrompt"].is_string());
    return {
        root["prompt"].get<std::string>(),
        root["negativePrompt"].get<std::string>(),
    };
}

bool prompt_json_rejected(const std::string& json) {
    try {
        static_cast<void>(parse_prompt_pair(json));
        return false;
    } catch (const nlohmann::json::exception&) {
        return true;
    }
}

void test_structured_prompt_decoding_and_hashing() {
    const std::string chinese_prompt =
        "\xe4\xb8\xad\xe5\x9b\xbd\xe5\xb1\xb1\xe6\xb0\xb4\xe7\x94\xbb";
    const std::string chinese_negative =
        "\xe6\xa8\xa1\xe7\xb3\x8a\xef\xbc\x8c\xe4\xbd\x8e\xe8\xb4\xa8\xe9\x87\x8f";
    const auto raw = parse_prompt_pair(
        std::string("{\"prompt\":\"") + chinese_prompt +
        "\",\"negativePrompt\":\"" + chinese_negative + "\"}");
    const auto escaped = parse_prompt_pair(
        "{\"prompt\":\"\\u4e2d\\u56fd\\u5c71\\u6c34\\u753b\","
        "\"negativePrompt\":\"\\u6a21\\u7cca\\uff0c\\u4f4e\\u8d28\\u91cf\"}");
    assert(raw == escaped);
    assert(mca::image::image_prompt_execution_sha256(raw.first, raw.second) ==
        mca::image::image_prompt_execution_sha256(escaped.first, escaped.second));

    const auto escaped_controls = parse_prompt_pair(
        "{\"prompt\":\"quote=\\\" slash=\\\\ solidus=\\/ back=\\b form=\\f "
        "line=\\n return=\\r tab=\\t\",\"negativePrompt\":\"\"}");
    assert(escaped_controls.first.find('"') != std::string::npos);
    assert(escaped_controls.first.find('\\') != std::string::npos);
    assert(escaped_controls.first.find('\b') != std::string::npos);
    assert(escaped_controls.first.find('\f') != std::string::npos);
    assert(escaped_controls.first.find('\n') != std::string::npos);
    assert(escaped_controls.first.find('\r') != std::string::npos);
    assert(escaped_controls.first.find('\t') != std::string::npos);

    const auto surrogate = parse_prompt_pair(
        "{\"prompt\":\"\\ud83d\\ude80\",\"negativePrompt\":\"\"}");
    assert(surrogate.first == "\xf0\x9f\x9a\x80");
    assert(prompt_json_rejected(
        "{\"prompt\":\"\\ud83d\",\"negativePrompt\":\"\"}"));
    assert(prompt_json_rejected(
        "{\"prompt\":\"\\ude80\",\"negativePrompt\":\"\"}"));
    assert(prompt_json_rejected(
        "{\"prompt\":\"\\ude80\\ud83d\",\"negativePrompt\":\"\"}"));

    std::string invalid_utf8 = "{\"prompt\":\"";
    invalid_utf8.push_back(static_cast<char>(0xc0));
    invalid_utf8.push_back(static_cast<char>(0xaf));
    invalid_utf8 += "\",\"negativePrompt\":\"\"}";
    assert(prompt_json_rejected(invalid_utf8));

    const std::string expected =
        mca::image::image_prompt_execution_sha256(raw.first, raw.second);
    assert(expected != mca::image::image_prompt_execution_sha256(raw.second, raw.first));
    assert(expected != mca::image::image_prompt_execution_sha256(
        raw.first + "!",
        raw.second));
    assert(expected != mca::image::image_prompt_execution_sha256(
        raw.first,
        raw.second + "!"));
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 3);
    test_structured_prompt_decoding_and_hashing();
    const std::string bridge = read_bridge_and_extracted_modules(argv[1]);
    const std::string split = read_text(argv[2]);

    // Missing fields fail through the required-field parser; malformed SHA
    // and a producer stage other than the exact encoded handoff fail closed.
    require_contains(bridge, "root.find(\"nativePromptExecutionSha256\")");
    require_contains(bridge, "root.find(\"nativePromptBindingStage\")");
    require_contains(bridge, "native_prompt_execution_sha256.size() != 64U");
    require_contains(bridge, "nativePromptExecutionSha256 must be a 64-character SHA-256 value.");
    require_contains(bridge, "native_prompt_binding_stage != \"conditioning_encoded\"");
    require_contains(bridge, "nativePromptBindingStage must be conditioning_encoded");
    require_contains(bridge, "const nlohmann::json root = nlohmann::json::parse(json)");
    require_contains(bridge, "mca::image::image_prompt_execution_sha256(");
    require_contains(bridge, "contract.prompt,");
    require_contains(bridge, "contract.negative_prompt);");
    require_contains(bridge, "nativePromptExecutionSha256 differs from the structured prompt and negativePrompt consumed by QNN.");

    // Token payload evidence is re-derived from consumed bytes. Every
    // producer-reported field must match before QNN accepts the handoff.
    require_contains(bridge, "reported_applied != evidence->prompt_weighting_applied");
    require_contains(bridge, "evidence->positive_weighted_token_count");
    require_contains(bridge, "evidence->negative_weighted_token_count");
    require_contains(bridge, "reported_fingerprint != evidence->prompt_weight_fingerprint");
    require_contains(bridge, "Producer prompt weighting evidence differs from the consumed QNN token payload.");
    require_contains(bridge, "The conditioning artifact bytes differ from conditioningArtifactSha256.");
    require_contains(bridge, "External MNN promptWeightFingerprint must identify the exact consumed conditioning artifact.");

    // The encoded digest is promoted only by the common consumption gate,
    // after both artifact consumption and a real UNet execution are proven.
    require_sequence_from(
        bridge,
        "bool bind_qnn_consumed_prompt_evidence(",
        "if (!conditioning_artifact_consumed || unet_execution_count == 0U)",
        "native_evidence->native_prompt_binding_stage = \"conditioning_consumed\";");
    require_count_at_least(bridge, "bind_qnn_consumed_prompt_evidence(", 3U);
    require_count_at_least(split, "bind_qnn_consumed_prompt_evidence(", 1U);

    // nativeEffective and each flat successful result publish the same
    // consumed-stage proof. Shared and isolated paths are both covered.
    require_count_at_least(bridge, "\\\"nativePromptExecutionSha256\\\":", 3U);
    require_count_at_least(bridge, "\\\"nativePromptBindingStage\\\":", 3U);
    require_count_at_least(split, "\\\"nativePromptExecutionSha256\\\":", 1U);
    require_count_at_least(split, "\\\"nativePromptBindingStage\\\":", 1U);
    require_contains(bridge, "quote(native_evidence.native_prompt_execution_sha256)");
    require_contains(split, "quote(native_evidence.native_prompt_execution_sha256)");
    return 0;
}
