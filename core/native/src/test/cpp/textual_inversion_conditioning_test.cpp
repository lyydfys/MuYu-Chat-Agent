#include "../../main/cpp/textual_inversion_conditioning.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace fs = std::filesystem;
using mca::image::textual_inversion::Audit;
using mca::image::textual_inversion::Selection;
using nlohmann::json;

constexpr std::size_t kClipLWidth = 768U;
constexpr std::size_t kClipGWidth = 1280U;

void append_u64_le(std::vector<std::uint8_t>* bytes, std::uint64_t value) {
    for (unsigned shift = 0U; shift < 64U; shift += 8U) {
        bytes->push_back(static_cast<std::uint8_t>((value >> shift) & UINT64_C(0xff)));
    }
}

void append_f32_le(std::vector<std::uint8_t>* bytes, float value) {
    std::uint32_t bits = 0U;
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&bits, &value, sizeof(bits));
    for (unsigned shift = 0U; shift < 32U; shift += 8U) {
        bytes->push_back(static_cast<std::uint8_t>((bits >> shift) & UINT32_C(0xff)));
    }
}

std::string sha256_text(const std::string& value) {
    return mca::image::sha256_hex_bytes(
        std::vector<std::uint8_t>(value.begin(), value.end()));
}

std::string ascii_lower(std::string value) {
    for (char& ch : value) {
        if (ch >= 'A' && ch <= 'Z') ch = static_cast<char>(ch - 'A' + 'a');
    }
    return value;
}

void refresh_binding_fingerprint(json* artifact) {
    assert(artifact != nullptr);
    std::string payload = "textual-inversion-binding-v1";
    for (const std::string& part : {
             artifact->at("id").get<std::string>(),
             artifact->at("sha256").get<std::string>(),
             ascii_lower(artifact->at("trigger").get<std::string>()),
             ascii_lower(artifact->at("modelFingerprint").get<std::string>()),
             ascii_lower(artifact->at("tokenizerFingerprint").get<std::string>()),
             artifact->at("profileId").get<std::string>(),
             std::to_string(artifact->at("profileRevision").get<int>()),
             artifact->at("runtime").get<std::string>(),
         }) {
        payload.push_back('\x1f');
        payload += part;
    }
    (*artifact)["bindingFingerprint"] = sha256_text(payload);
}

void refresh_selection_fingerprint(json* selection) {
    assert(selection != nullptr);
    auto& artifacts = selection->at("textualInversions");
    std::vector<std::pair<std::string, std::string>> ordered;
    for (auto& artifact : artifacts) {
        refresh_binding_fingerprint(&artifact);
        ordered.emplace_back(
            ascii_lower(artifact.at("trigger").get<std::string>()),
            artifact.at("bindingFingerprint").get<std::string>());
    }
    std::sort(ordered.begin(), ordered.end());
    std::string payload = "textual-inversion-selection-v1";
    for (const auto& entry : ordered) {
        payload.push_back('\x1f');
        payload += entry.second;
    }
    (*selection)["textualInversionCount"] = static_cast<int>(artifacts.size());
    (*selection)["textualInversionBindingFingerprint"] = sha256_text(payload);
}

std::vector<std::uint8_t> make_zero_safetensors(
        const json& header,
        std::size_t data_bytes) {
    const std::string encoded_header = header.dump();
    std::vector<std::uint8_t> result;
    append_u64_le(&result, encoded_header.size());
    result.insert(result.end(), encoded_header.begin(), encoded_header.end());
    result.resize(result.size() + data_bytes, UINT8_C(0));
    return result;
}

std::vector<std::uint8_t> make_safetensors(
        std::size_t rows,
        bool include_clip_g,
        bool non_finite = false,
        bool invalid_clip_l_span = false) {
    const std::uint64_t clip_l_bytes = rows * kClipLWidth * sizeof(float);
    const std::uint64_t clip_g_bytes = rows * kClipGWidth * sizeof(float);
    json header = json::object();
    header["clip_l"] = {
        {"dtype", "F32"},
        {"shape", {rows, kClipLWidth}},
        {"data_offsets", {0U, invalid_clip_l_span ? clip_l_bytes + 4U : clip_l_bytes}},
    };
    if (include_clip_g) {
        header["clip_g"] = {
            {"dtype", "F32"},
            {"shape", {rows, kClipGWidth}},
            {"data_offsets", {clip_l_bytes, clip_l_bytes + clip_g_bytes}},
        };
    }

    const std::string encoded_header = header.dump();
    std::vector<std::uint8_t> result;
    result.reserve(8U + encoded_header.size() + clip_l_bytes + clip_g_bytes);
    append_u64_le(&result, encoded_header.size());
    result.insert(result.end(), encoded_header.begin(), encoded_header.end());
    const std::size_t value_count = rows * (kClipLWidth + (include_clip_g ? kClipGWidth : 0U));
    for (std::size_t index = 0U; index < value_count; ++index) {
        append_f32_le(
            &result,
            non_finite && index == 0U
                ? std::numeric_limits<float>::quiet_NaN()
                : static_cast<float>((index % 31U) + 1U) / 32.0f);
    }
    return result;
}

void write_bytes(const fs::path& path, const std::vector<std::uint8_t>& bytes) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    assert(output.good());
    output.write(reinterpret_cast<const char*>(bytes.data()),
                 static_cast<std::streamsize>(bytes.size()));
    output.close();
    assert(output.good());
}

json make_selection(
        const fs::path& root,
        const fs::path& artifact_path,
        const std::vector<std::uint8_t>& artifact_bytes,
        const std::string& runtime = "QNN_HTP",
        const std::string& trigger = "<fixture>") {
    const std::string artifact_sha = mca::image::sha256_hex_bytes(artifact_bytes);
    const std::string model_fingerprint(64U, 'a');
    const std::string tokenizer_fingerprint(64U, 'b');
    const std::string id = "11111111-2222-3333-4444-555555555555";
    const std::string profile_id = "test.textual_inversion";
    std::string binding_payload = "textual-inversion-binding-v1";
    for (const std::string& part : {
             id,
             artifact_sha,
             trigger,
             model_fingerprint,
             tokenizer_fingerprint,
             profile_id,
             std::string("1"),
             runtime,
         }) {
        binding_payload.push_back('\x1f');
        binding_payload += part;
    }
    const std::string binding_fingerprint = sha256_text(binding_payload);
    std::string selection_payload = "textual-inversion-selection-v1";
    selection_payload.push_back('\x1f');
    selection_payload += binding_fingerprint;

    json artifact = {
        {"id", id},
        {"name", "Fixture"},
        {"trigger", trigger},
        {"path", artifact_path.generic_string()},
        {"sha256", artifact_sha},
        {"sizeBytes", artifact_bytes.size()},
        {"format", "safetensors"},
        {"modelFingerprint", model_fingerprint},
        {"tokenizerFingerprint", tokenizer_fingerprint},
        {"profileId", profile_id},
        {"profileRevision", 1},
        {"runtime", runtime},
        {"bindingFingerprint", binding_fingerprint},
    };
    return {
        {"textualInversions", json::array({artifact})},
        {"textualInversionCount", 1},
        {"textualInversionBindingFingerprint", sha256_text(selection_payload)},
        {"textualInversionNativeMode", "MNN_CLIP_INPUT_EMBEDDING"},
        {"textualInversionSupported", true},
        {"textualInversionRootPath", root.generic_string()},
    };
}

Selection require_load(
        const json& selection_json,
        const std::string& runtime,
        bool require_clip_g) {
    Selection selection;
    Audit audit;
    std::string error;
    const bool loaded = mca::image::textual_inversion::load_selection(
        selection_json.dump(), runtime, require_clip_g, &selection, &audit, &error);
    if (!loaded) {
        std::cerr << "Expected load_selection success, got: " << error << '\n';
    }
    assert(loaded);
    assert(error.empty());
    assert(audit.requested_count == 1U);
    assert(audit.validated_count == 1U);
    assert(audit.load_attempt_count == 1U);
    assert(audit.loaded_count == 1U);
    assert(audit.requested_mask == 1U);
    assert(audit.loaded_mask == 1U);
    assert(selection.artifacts.size() == 1U);
    return selection;
}

void require_rejected(
        const json& selection_json,
        const std::string& runtime,
        bool require_clip_g,
        const std::string& diagnostic_fragment) {
    Selection selection;
    Audit audit;
    std::string error;
    const bool loaded = mca::image::textual_inversion::load_selection(
        selection_json.dump(), runtime, require_clip_g, &selection, &audit, &error);
    if (loaded || error.find(diagnostic_fragment) == std::string::npos) {
        std::cerr << "Expected rejection containing '" << diagnostic_fragment
                  << "', got loaded=" << loaded << " error=" << error << '\n';
    }
    assert(!loaded);
    assert(error.find(diagnostic_fragment) != std::string::npos);
}

void write_and_test(const fs::path& root) {
    const auto single_bytes = make_safetensors(1U, false);
    const fs::path single_path = root / "single.safetensors";
    write_bytes(single_path, single_bytes);
    const json single_json = make_selection(root, single_path, single_bytes);
    const Selection single = require_load(single_json, "QNN_HTP", false);
    assert(single.artifacts.front().clip_l.size() == kClipLWidth);
    assert(single.artifacts.front().clip_g.empty());
    assert(std::fabs(single.artifacts.front().clip_l.front() - 1.0f / 32.0f) < 1.0e-7f);

    json invalid_metadata_header = {
        {"__metadata__", {{"source", 7}}},
        {"clip_l", {
            {"dtype", "F32"},
            {"shape", {1U, kClipLWidth}},
            {"data_offsets", {0U, kClipLWidth * sizeof(float)}},
        }},
    };
    const auto invalid_metadata_bytes = make_zero_safetensors(
        invalid_metadata_header, kClipLWidth * sizeof(float));
    const fs::path invalid_metadata_path = root / "invalid-metadata.safetensors";
    write_bytes(invalid_metadata_path, invalid_metadata_bytes);
    require_rejected(
        make_selection(root, invalid_metadata_path, invalid_metadata_bytes),
        "QNN_HTP",
        false,
        "metadata must be an object with string values");

    json overlap_header = {
        {"clip_l", {
            {"dtype", "F32"},
            {"shape", {1U, kClipLWidth}},
            {"data_offsets", {0U, kClipLWidth * sizeof(float)}},
        }},
        {"auxiliary", {
            {"dtype", "F32"},
            {"shape", {1U}},
            {"data_offsets", {0U, sizeof(float)}},
        }},
    };
    const auto overlap_bytes = make_zero_safetensors(
        overlap_header, kClipLWidth * sizeof(float));
    const fs::path overlap_path = root / "overlap.safetensors";
    write_bytes(overlap_path, overlap_bytes);
    require_rejected(
        make_selection(root, overlap_path, overlap_bytes),
        "QNN_HTP",
        false,
        "overlap or leave holes");

    json gap_header = {
        {"clip_l", {
            {"dtype", "F32"},
            {"shape", {1U, kClipLWidth}},
            {"data_offsets", {4U, 4U + kClipLWidth * sizeof(float)}},
        }},
    };
    const auto gap_bytes = make_zero_safetensors(
        gap_header, 4U + kClipLWidth * sizeof(float));
    const fs::path gap_path = root / "gap.safetensors";
    write_bytes(gap_path, gap_bytes);
    require_rejected(
        make_selection(root, gap_path, gap_bytes),
        "QNN_HTP",
        false,
        "overlap or leave holes");

    json trailing_header = {
        {"clip_l", {
            {"dtype", "F32"},
            {"shape", {1U, kClipLWidth}},
            {"data_offsets", {0U, kClipLWidth * sizeof(float)}},
        }},
    };
    const auto trailing_bytes = make_zero_safetensors(
        trailing_header, kClipLWidth * sizeof(float) + 4U);
    const fs::path trailing_path = root / "trailing.safetensors";
    write_bytes(trailing_path, trailing_bytes);
    require_rejected(
        make_selection(root, trailing_path, trailing_bytes),
        "QNN_HTP",
        false,
        "unreferenced trailing bytes");

    std::vector<std::uint8_t> invalid_utf8_header;
    append_u64_le(&invalid_utf8_header, 3U);
    invalid_utf8_header.push_back('{');
    invalid_utf8_header.push_back(UINT8_C(0xff));
    invalid_utf8_header.push_back('}');
    invalid_utf8_header.resize(16U, UINT8_C(0));
    const fs::path invalid_utf8_path = root / "invalid-utf8.safetensors";
    write_bytes(invalid_utf8_path, invalid_utf8_header);
    require_rejected(
        make_selection(root, invalid_utf8_path, invalid_utf8_header),
        "QNN_HTP",
        false,
        "header JSON is invalid");

    require_rejected(
        single_json,
        "QNN_HTP",
        true,
        "required CLIP embedding width");
    require_rejected(
        single_json,
        "MNN_DIFFUSION",
        false,
        "format, runtime, digest, trigger, or bounds");

    const auto dual_bytes = make_safetensors(1U, true);
    const fs::path dual_path = root / "dual.safetensors";
    write_bytes(dual_path, dual_bytes);
    const Selection dual = require_load(
        make_selection(root, dual_path, dual_bytes), "QNN_HTP", true);
    assert(dual.artifacts.front().clip_l.size() == kClipLWidth);
    assert(dual.artifacts.front().clip_g.size() == kClipGWidth);

    const auto max_rows_bytes = make_safetensors(75U, false);
    const fs::path max_rows_path = root / "max-rows.safetensors";
    write_bytes(max_rows_path, max_rows_bytes);
    const Selection max_rows = require_load(
        make_selection(root, max_rows_path, max_rows_bytes), "QNN_HTP", false);
    assert(max_rows.artifacts.front().clip_l.size() == 75U * kClipLWidth);

    const auto too_many_rows_bytes = make_safetensors(76U, false);
    const fs::path too_many_rows_path = root / "too-many-rows.safetensors";
    write_bytes(too_many_rows_path, too_many_rows_bytes);
    require_rejected(
        make_selection(root, too_many_rows_path, too_many_rows_bytes),
        "QNN_HTP",
        false,
        "invalid vector count");

    const auto bad_span_bytes = make_safetensors(1U, false, false, true);
    const fs::path bad_span_path = root / "bad-span.safetensors";
    write_bytes(bad_span_path, bad_span_bytes);
    require_rejected(
        make_selection(root, bad_span_path, bad_span_bytes),
        "QNN_HTP",
        false,
        "byte span");

    const auto non_finite_bytes = make_safetensors(1U, false, true);
    const fs::path non_finite_path = root / "non-finite.safetensors";
    write_bytes(non_finite_path, non_finite_bytes);
    require_rejected(
        make_selection(root, non_finite_path, non_finite_bytes),
        "QNN_HTP",
        false,
        "non-finite");

    std::vector<std::uint8_t> oversized_header(16U, 0U);
    const std::uint64_t declared_header = 1024U * 1024U + 1U;
    for (unsigned shift = 0U; shift < 64U; shift += 8U) {
        oversized_header[shift / 8U] =
            static_cast<std::uint8_t>((declared_header >> shift) & UINT64_C(0xff));
    }
    const fs::path oversized_header_path = root / "oversized-header.safetensors";
    write_bytes(oversized_header_path, oversized_header);
    require_rejected(
        make_selection(root, oversized_header_path, oversized_header),
        "QNN_HTP",
        false,
        "exceeds 1 MiB");

    json changed_json = single_json;
    {
        std::ofstream changed(single_path, std::ios::binary | std::ios::app);
        changed.put('\0');
    }
    require_rejected(
        changed_json,
        "QNN_HTP",
        false,
        "size or file type changed");

    const auto sha_mismatch_bytes = make_safetensors(1U, false);
    const fs::path sha_mismatch_path = root / "sha-mismatch.safetensors";
    write_bytes(sha_mismatch_path, sha_mismatch_bytes);
    const json sha_mismatch_json =
        make_selection(root, sha_mismatch_path, sha_mismatch_bytes);
    auto changed_sha_bytes = sha_mismatch_bytes;
    changed_sha_bytes.back() ^= UINT8_C(0x01);
    write_bytes(sha_mismatch_path, changed_sha_bytes);
    require_rejected(
        sha_mismatch_json,
        "QNN_HTP",
        false,
        "SHA-256 changed");

    const fs::path outside_path = root.parent_path() / "outside.safetensors";
    write_bytes(outside_path, dual_bytes);
    require_rejected(
        make_selection(root, outside_path, dual_bytes),
        "QNN_HTP",
        false,
        "direct child");
    fs::remove(outside_path);

    json duplicate_id = make_selection(root, dual_path, dual_bytes);
    json second_artifact = duplicate_id.at("textualInversions").at(0);
    second_artifact["trigger"] = "<second>";
    duplicate_id["textualInversions"].push_back(second_artifact);
    refresh_selection_fingerprint(&duplicate_id);
    require_rejected(
        duplicate_id,
        "QNN_HTP",
        false,
        "ids and triggers must be unique");

    json duplicate_path = make_selection(root, dual_path, dual_bytes);
    second_artifact = duplicate_path.at("textualInversions").at(0);
    second_artifact["id"] = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    second_artifact["trigger"] = "<second>";
    duplicate_path["textualInversions"].push_back(second_artifact);
    refresh_selection_fingerprint(&duplicate_path);
    require_rejected(
        duplicate_path,
        "QNN_HTP",
        false,
        "canonical artifact paths must be unique");

    json invalid_id = make_selection(root, dual_path, dual_bytes);
    invalid_id["textualInversions"][0]["id"] = "not-a-uuid";
    refresh_selection_fingerprint(&invalid_id);
    require_rejected(
        invalid_id,
        "QNN_HTP",
        false,
        "format, runtime, digest, trigger, or bounds");

    json too_many = make_selection(root, dual_path, dual_bytes);
    const json one_artifact = too_many.at("textualInversions").at(0);
    too_many["textualInversions"] = json::array();
    for (int index = 0; index < 9; ++index) {
        too_many["textualInversions"].push_back(one_artifact);
    }
    too_many["textualInversionCount"] = 9;
    require_rejected(too_many, "QNN_HTP", false, "metadata is incomplete");
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const fs::path root = fs::absolute(argv[1]).lexically_normal();
    std::error_code ignored;
    fs::remove_all(root, ignored);
    assert(fs::create_directories(root));
    write_and_test(root);
    fs::remove_all(root, ignored);
    return 0;
}
