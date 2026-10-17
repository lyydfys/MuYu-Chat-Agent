// MCA E08c (pure migration, L1) REAL BEHAVIOUR test for the MNN-Diffusion asset contract helpers.
//
// Why this file exists:
//   is_sana_family / is_sd15_family / mnn_execution_asset_failure / mnn_text_encoder_asset_failure /
//   mnn_diffusion_missing_component were moved verbatim out of the anonymous namespace in
//   mnn_native_engine.cpp into mca/mnn/mnn_diffusion_asset_contract.hpp. The move is only allowed
//   to touch text, never behaviour, so this test compiles the production header as-is and pins:
//     * family classification for every accepted spelling plus degenerate negatives;
//     * the exact failure payloads (execution-asset mismatch / cancelled, text-encoder mismatch);
//     * the missing-component contract on real temporary directories, including declaration
//       evidence, the community-CLIP alternative, ordering and the empty string on completeness.
//
// The real file_exists/directory_exists implementations live in POSIX TUs that cannot link on the
// MSVC host, so this test provides std::filesystem-backed definitions with the same contract
// (regular file / directory). That keeps the exercised production code (the inline helpers and the
// missing-component contract) real while the filesystem probes remain host-portable.

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "mca/mnn/mnn_diffusion_asset_contract.hpp"

namespace mca {
namespace mnn {

// Host-portable definitions mirroring the production contract of the POSIX implementations
// (stat + S_ISREG / S_ISDIR).
bool file_exists(const std::string& path) {
    return std::filesystem::is_regular_file(path);
}

bool directory_exists(const std::string& path) {
    return std::filesystem::is_directory(path);
}

// normalize_mnn_diffusion_identifier is pre-existing extracted code whose TU pulls Android assets
// bindings; this stub mirrors mnn_protocol_parser.cpp exactly (trim ASCII whitespace + lowercase)
// so the family predicates under test still classify real spellings.
std::string normalize_mnn_diffusion_identifier(std::string value) {
    const auto is_space = [](unsigned char ch) { return std::isspace(ch) != 0; };
    const auto begin = std::find_if_not(value.begin(), value.end(), is_space);
    const auto end = std::find_if_not(value.rbegin(), value.rend(), is_space).base();
    if (begin >= end) return "";
    value.assign(begin, end);
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
    return value;
}

}  // namespace mnn
}  // namespace mca

namespace {

int failures = 0;

void expect(bool condition, const std::string& what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

void expect_eq(const std::string& actual, const std::string& expected, const std::string& what) {
    if (actual != expected) {
        std::fprintf(stderr, "FAIL: %s (expected '%s', got '%s')\n",
                     what.c_str(), expected.c_str(), actual.c_str());
        ++failures;
    }
}

void expect_json_eq(const mca::mnn::json& actual,
                    const mca::mnn::json& expected,
                    const std::string& what) {
    if (actual != expected) {
        std::fprintf(stderr, "FAIL: %s (expected %s, got %s)\n",
                     what.c_str(), expected.dump().c_str(), actual.dump().c_str());
        ++failures;
    }
}

std::filesystem::path make_temp_dir(const std::string& tag) {
    const auto dir = std::filesystem::temp_directory_path() / ("mca_e08c_" + tag);
    std::error_code ignored;
    std::filesystem::remove_all(dir, ignored);
    std::filesystem::create_directories(dir, ignored);
    return dir;
}

void touch(const std::filesystem::path& path) {
    std::filesystem::create_directories(path.parent_path());
    std::ofstream out(path, std::ios::binary);
    out << "x";
}

void test_family_classification() {
    using mca::mnn::is_sana_family;
    using mca::mnn::is_sd15_family;

    expect(is_sana_family("sana"), "sana lowercase");
    expect(is_sana_family("SANA"), "sana uppercase");
    expect(is_sana_family("  sana_diffusion  "), "sana_diffusion trimmed");
    expect(!is_sana_family("sana-x"), "sana-x is not the sana family");
    expect(!is_sana_family(""), "empty family is not sana");
    expect(!is_sana_family("stable_diffusion"), "generic stable diffusion is not sana");

    expect(is_sd15_family("sd15"), "sd15");
    expect(is_sd15_family("SD1.5"), "SD1.5");
    expect(is_sd15_family(" stable_diffusion_1_5 "), "stable_diffusion_1_5 trimmed");
    expect(is_sd15_family("stable-diffusion-1.5"), "stable-diffusion-1.5");
    expect(!is_sd15_family("sdxl"), "sdxl is not sd15");
    expect(!is_sd15_family(""), "empty family is not sd15");
}

void test_failure_payloads() {
    using mca::mnn::mnn_execution_asset_failure;
    using mca::mnn::mnn_text_encoder_asset_failure;

    expect_json_eq(
            mnn_execution_asset_failure("size mismatch"),
            mca::mnn::json({{"ok", false},
                            {"errorCode", "TEXTUAL_INVERSION_EXECUTION_ASSET_MISMATCH"},
                            {"error", "size mismatch"}}),
            "execution asset failure payload");
    expect_json_eq(
            mnn_execution_asset_failure("size mismatch", "community_clip"),
            mca::mnn::json({{"ok", false},
                            {"errorCode", "TEXTUAL_INVERSION_EXECUTION_ASSET_MISMATCH"},
                            {"error", "size mismatch"},
                            {"format", "community_clip"}}),
            "execution asset failure payload with format");
    expect_json_eq(
            mnn_execution_asset_failure("cancelled", "sdxl_qnn_conditioning"),
            mca::mnn::json({{"ok", false},
                            {"cancelled", true},
                            {"error", "cancelled"},
                            {"format", "sdxl_qnn_conditioning"}}),
            "cancelled execution payload with format");
    expect_json_eq(
            mnn_execution_asset_failure("cancelled"),
            mca::mnn::json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}),
            "cancelled execution payload without format");

    expect_json_eq(
            mnn_text_encoder_asset_failure("hash mismatch"),
            mca::mnn::json({{"ok", false},
                            {"errorCode", "TEXT_ENCODER_ASSET_MISMATCH"},
                            {"error", "hash mismatch"}}),
            "text encoder failure payload");
    expect_json_eq(
            mnn_text_encoder_asset_failure("cancelled"),
            mca::mnn::json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}),
            "cancelled text encoder payload");
}

void test_missing_component_contract() {
    using mca::mnn::json;
    using mca::mnn::mnn_diffusion_missing_component;

    const auto root = make_temp_dir("missing");
    const std::string root_path = root.string();

    expect_eq(mnn_diffusion_missing_component(root_path + "_absent", "sana", nullptr),
              "MNN Sana requires a complete resource directory.",
              "absent sana root message");
    expect_eq(mnn_diffusion_missing_component(root_path + "_absent", "sd15", nullptr),
              "MNN-Diffusion requires a complete Stable Diffusion 1.5 resource directory.",
              "absent sd15 root message");
    expect_eq(mnn_diffusion_missing_component("", "", nullptr),
              "MNN-Diffusion requires a complete Stable Diffusion 1.5 resource directory.",
              "empty root falls back to the sd15 message");

    expect_eq(mnn_diffusion_missing_component(root_path, "sana", nullptr),
              "MNN Sana bundle is incomplete: connector.mnn, projector.mnn, transformer.mnn, "
              "vae_decoder.mnn, llm/config.json, llm/meta_queries.mnn",
              "empty sana dir lists every missing component in order");

    touch(root / "connector.mnn");
    touch(root / "vae_decoder.mnn");
    expect_eq(mnn_diffusion_missing_component(root_path, "sana", nullptr),
              "MNN Sana bundle is incomplete: projector.mnn, transformer.mnn, "
              "llm/config.json, llm/meta_queries.mnn",
              "partially filled sana dir reports the remaining components");

    touch(root / "projector.mnn");
    touch(root / "transformer.mnn");
    touch(root / "llm" / "config.json");
    touch(root / "llm" / "meta_queries.mnn");
    expect_eq(mnn_diffusion_missing_component(root_path, "sana", nullptr), "",
              "complete sana dir reports no missing component");

    const auto sd_root = make_temp_dir("sd15");
    const std::string sd_path = sd_root.string();
    expect_eq(mnn_diffusion_missing_component(sd_path, "sd15", nullptr),
              "MNN-Diffusion Stable Diffusion 1.5 bundle is incomplete: text_encoder.mnn, "
              "text_encoder.mnn.weight, unet.mnn, unet.mnn.weight, vae_decoder.mnn, "
              "vae_decoder.mnn.weight",
              "empty sd15 dir lists the CLIP/UNet/VAE components");

    mca::mnn::MnnTextEncoderAssetRequest declared;
    declared.declared = true;
    declared.path = (sd_root / "declared_encoder.mnn").string();
    declared.has_weight = true;
    declared.weight_path = (sd_root / "declared_encoder.mnn.weight").string();
    expect_eq(mnn_diffusion_missing_component(sd_path, "sd15", &declared),
              "MNN-Diffusion Stable Diffusion 1.5 bundle is incomplete: "
              "nativeTextEncoderEvidence.path, nativeTextEncoderEvidence.auxiliaryAssets[0], "
              "unet.mnn, unet.mnn.weight, vae_decoder.mnn, vae_decoder.mnn.weight",
              "declared encoder evidence replaces the community CLIP requirement");

    touch(sd_root / "unet.mnn");
    touch(sd_root / "unet.mnn.weight");
    touch(sd_root / "vae_decoder.mnn");
    touch(sd_root / "vae_decoder.mnn.weight");
    touch(sd_root / "clip_v2.mnn");
    touch(sd_root / "tokenizer.json");
    touch(sd_root / "token_emb.bin");
    touch(sd_root / "pos_emb.bin");
    expect_eq(mnn_diffusion_missing_component(sd_path, "sd15", nullptr), "",
              "community CLIP alternative satisfies the text encoder requirement");

    std::error_code ignored;
    std::filesystem::remove_all(root, ignored);
    std::filesystem::remove_all(sd_root, ignored);
}

}  // namespace

int main() {
    test_family_classification();
    test_failure_payloads();
    test_missing_component_contract();
    if (failures != 0) {
        std::fprintf(stderr, "mnn_diffusion_asset_contract_behavior_test: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("mnn_diffusion_asset_contract_behavior_test: PASS\n");
    return 0;
}