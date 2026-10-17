// mca/qnn/qnn_conditioning_handoff_binding.cpp -- conditioning closure sha256, MNN handoff consumption and bind validation.
// (T2B16 / R7-C5 split).  Relocated byte-exact from mca/qnn/qnn_conditioning_pipeline.cpp (lines 925-1159).

#include "mca/qnn/qnn_conditioning_pipeline.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "image_conditioning.hpp"
#include "native_prompt_language_contract.hpp"
// image_pixel_range_wire_name() is used by qnn_native_effective_json(); its declaration lives in the
// root QNN contract header, which this TU did not previously need to include.
#include "qnn_image_pixel_range.hpp"
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"

#if defined(_WIN32)
#include <io.h>
#include <direct.h>
#include <stdlib.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define realpath(N, R) _fullpath((R), (N), PATH_MAX)
#define lstat(P, S) stat((P), (S))
#ifndef S_ISREG
#define S_ISREG(m) (((m) & S_IFMT) == S_IFREG)
#endif
#else
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits.h>
#endif

namespace mca {
namespace qnn {

bool qnn_sdxl_conditioning_graph_sha256(
        const std::vector<std::string>& files,
        std::string* digest,
        std::string* error) {
    if (digest == nullptr || error == nullptr) return false;
    std::vector<std::string> execution_asset_names = {"clip.mnn"};
    if (!qnn_nonempty_bundle_file_named(files, "clip.mnn.weight").empty()) {
        execution_asset_names.emplace_back("clip.mnn.weight");
    }
    execution_asset_names.emplace_back("clip_2.mnn");
    execution_asset_names.emplace_back("clip_2.mnn.weight");
    std::ostringstream payload;
    for (const auto& asset_name : execution_asset_names) {
        const std::string asset_path = qnn_nonempty_bundle_file_named(files, asset_name);
        if (asset_path.empty()) {
            *error = "MNN SDXL conditioning execution asset is missing or empty: " +
                asset_name;
            return false;
        }
        std::string asset_sha256;
        if (!qnn_file_sha256(asset_path, &asset_sha256, error)) {
            *error = "MNN SDXL conditioning execution asset SHA-256 failed for " +
                asset_name + ": " + *error;
            return false;
        }
        payload << asset_name << "=" << asset_sha256 << "\n";
    }
    const std::string canonical_payload = payload.str();
    const std::vector<uint8_t> payload_bytes(
        canonical_payload.begin(),
        canonical_payload.end());
    *digest = mca::qnn::controlnet::sha256_hex_bytes(payload_bytes);
    if (digest->size() != 64U) {
        *error = "MNN SDXL conditioning execution-closure fingerprint could not be derived.";
        return false;
    }
    return true;
}

std::string qnn_prompt_to_encoder_closure_sha256(
        const QnnTextEncoderAssetEvidence& asset_evidence) {
    const auto canonical_path = [](std::string value) {
        std::replace(value.begin(), value.end(), '\\', '/');
        const size_t first = value.find_first_not_of(" \t\r\n");
        const size_t last = value.find_last_not_of(" \t\r\n");
        return first == std::string::npos ? "" : value.substr(first, last - first + 1U);
    };
    constexpr char kFieldSeparator = '\x1f';
    std::ostringstream payload;
    payload << "mca.image.prompt-to-encoder-assets.v1" << kFieldSeparator
        << "TEXT_ENCODER_GRAPH" << kFieldSeparator
        << canonical_path(asset_evidence.relative_path) << kFieldSeparator
        << asset_evidence.size_bytes << kFieldSeparator
        << asset_evidence.sha256 << kFieldSeparator
        << "TOKENIZER_JSON" << kFieldSeparator
        << canonical_path(asset_evidence.tokenizer_relative_path) << kFieldSeparator
        << asset_evidence.tokenizer_size_bytes << kFieldSeparator
        << asset_evidence.tokenizer_sha256;
    const std::string canonical_payload = payload.str();
    const std::vector<uint8_t> payload_bytes(
        canonical_payload.begin(), canonical_payload.end());
    return mca::qnn::controlnet::sha256_hex_bytes(payload_bytes);
}

bool consume_qnn_mnn_prompt_handoff(
        const QnnSemanticExecutionContract& contract,
        const QnnConditioningEvidence& conditioning,
        QnnTextEncoderAssetEvidence* asset_evidence,
        std::string* error) {
    if (asset_evidence == nullptr || error == nullptr) return false;
    if (!asset_evidence->multilingual_evidence_required) {
        error->clear();
        return true;
    }
    if (!asset_evidence->required ||
        !asset_evidence->tokenizer_asset_verified ||
        asset_evidence->tokenizer_canonical_path.empty() ||
        asset_evidence->tokenizer_size_bytes <= 0 ||
        asset_evidence->mnn_prompt_handoff.empty()) {
        *error = "The MNN prompt handoff has no captured QNN tokenizer identity.";
        return false;
    }

    mca::image::prompt_handoff::Record observed;
    observed.tokenizer_canonical_path = asset_evidence->tokenizer_canonical_path;
    observed.tokenizer_device = static_cast<uint64_t>(
        asset_evidence->tokenizer_device);
    observed.tokenizer_inode = static_cast<uint64_t>(
        asset_evidence->tokenizer_inode);
    observed.tokenizer_size_bytes = static_cast<uint64_t>(
        asset_evidence->tokenizer_size_bytes);
    observed.tokenizer_sha256 = asset_evidence->tokenizer_sha256;
    observed.prompt_pair_sha256 = mca::image::image_prompt_execution_sha256(
        contract.prompt,
        contract.negative_prompt);
    observed.payload_sha256 = conditioning.conditioning_artifact_sha256;
    observed.prompt_to_encoder_closure_sha256 =
        qnn_prompt_to_encoder_closure_sha256(*asset_evidence);

    const std::string handle = std::move(asset_evidence->mnn_prompt_handoff);
    asset_evidence->mnn_prompt_handoff.clear();
    mca::image::prompt_handoff::Record consumed;
    if (!mca::image::prompt_handoff::consume(
            handle,
            observed,
            consumed,
            *error)) {
        return false;
    }
    if (asset_evidence->prompt_to_encoder_closure_sha256 !=
        consumed.prompt_to_encoder_closure_sha256) {
        *error = "The signed prompt-to-encoder closure differs from the consumed MNN prompt handoff.";
        return false;
    }
    asset_evidence->consumed_prompt_handoff = std::move(consumed);
    asset_evidence->mnn_prompt_handoff_verified = true;
    error->clear();
    return true;
}

bool bind_qnn_consumed_prompt_evidence(
        const QnnConditioningEvidence& conditioning,
        bool conditioning_artifact_consumed,
        size_t unet_execution_count,
        QnnNativeEffectiveEvidence* native_evidence,
        std::string* error) {
    if (native_evidence == nullptr || error == nullptr) return false;
    if (!conditioning_artifact_consumed || unet_execution_count == 0U) {
        *error = "Native prompt evidence requires a consumed conditioning artifact and at least one successful UNet execution.";
        return false;
    }
    if (conditioning.native_prompt_execution_sha256.size() != 64U ||
        !std::all_of(
            conditioning.native_prompt_execution_sha256.begin(),
            conditioning.native_prompt_execution_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; }) ||
        conditioning.native_prompt_binding_stage != "conditioning_encoded") {
        *error = "Encoded native prompt evidence is incomplete at the QNN consumption boundary.";
        return false;
    }
    native_evidence->native_prompt_execution_sha256 =
        conditioning.native_prompt_execution_sha256;
    native_evidence->native_prompt_binding_stage = "conditioning_consumed";
    return true;
}

bool bind_qnn_consumed_prompt_to_encoder_evidence(
        const QnnTextEncoderAssetEvidence& asset_evidence,
        const std::string& loaded_text_encoder_graph_name,
        const QnnConditioningEvidence& conditioning,
        QnnNativeEffectiveEvidence* native_evidence,
        std::string* error) {
    if (native_evidence == nullptr || error == nullptr) return false;
    if (!asset_evidence.multilingual_evidence_required) {
        error->clear();
        return true;
    }
    const auto valid_lower_sha256 = [](const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](unsigned char character) {
                return (character >= '0' && character <= '9') ||
                    (character >= 'a' && character <= 'f');
            });
    };
    const std::string consumed_prompt_to_encoder_closure_sha256 =
        qnn_prompt_to_encoder_closure_sha256(asset_evidence);
    const auto& prompt_handoff = asset_evidence.consumed_prompt_handoff;
    if (!asset_evidence.required || asset_evidence.relative_path.empty() ||
        !valid_lower_sha256(asset_evidence.sha256) || asset_evidence.size_bytes <= 0 ||
        asset_evidence.graph_name.empty() ||
        loaded_text_encoder_graph_name != asset_evidence.graph_name ||
        asset_evidence.tokenizer_relative_path.empty() ||
        !valid_lower_sha256(asset_evidence.tokenizer_sha256) ||
        asset_evidence.tokenizer_size_bytes <= 0 ||
        !asset_evidence.tokenizer_asset_verified ||
        !asset_evidence.mnn_prompt_handoff_verified ||
        prompt_handoff.tokenizer_canonical_path !=
            asset_evidence.tokenizer_canonical_path ||
        prompt_handoff.tokenizer_device !=
            static_cast<uint64_t>(asset_evidence.tokenizer_device) ||
        prompt_handoff.tokenizer_inode !=
            static_cast<uint64_t>(asset_evidence.tokenizer_inode) ||
        prompt_handoff.tokenizer_size_bytes !=
            static_cast<uint64_t>(asset_evidence.tokenizer_size_bytes) ||
        prompt_handoff.tokenizer_sha256 != asset_evidence.tokenizer_sha256 ||
        !valid_lower_sha256(asset_evidence.prompt_to_encoder_closure_sha256) ||
        consumed_prompt_to_encoder_closure_sha256 !=
            asset_evidence.prompt_to_encoder_closure_sha256 ||
        prompt_handoff.prompt_to_encoder_closure_sha256 !=
            consumed_prompt_to_encoder_closure_sha256 ||
        prompt_handoff.prompt_pair_sha256 !=
            conditioning.native_prompt_execution_sha256 ||
        prompt_handoff.payload_sha256 !=
            conditioning.conditioning_artifact_sha256 ||
        !valid_lower_sha256(asset_evidence.language_proof_sha256) ||
        conditioning.tokenizer_backend != "TOKENIZERS_CPP" ||
        conditioning.tokenizer_binding_stage != "tokenizer_consumed" ||
        !native_evidence->conditioning_artifact_consumed ||
        !valid_lower_sha256(native_evidence->conditioning_artifact_sha256) ||
        native_evidence->conditioning_artifact_sha256 !=
            conditioning.conditioning_artifact_sha256 ||
        native_evidence->native_prompt_binding_stage != "conditioning_consumed" ||
        native_evidence->text_encoder_execution_count == 0U) {
        *error = "Signed multilingual QNN evidence did not bind the exact context graph, loaded graph, tokenizer asset, consumed conditioning artifact, and prompt-to-encoder closure.";
        return false;
    }
    native_evidence->text_encoder_graph_name = asset_evidence.graph_name;
    native_evidence->loaded_text_encoder_graph_name = loaded_text_encoder_graph_name;
    native_evidence->consumed_text_encoder_path = asset_evidence.relative_path;
    native_evidence->consumed_text_encoder_sha256 = asset_evidence.sha256;
    native_evidence->consumed_text_encoder_size_bytes = asset_evidence.size_bytes;
    native_evidence->consumed_text_encoder_asset_verified = true;
    native_evidence->consumed_tokenizer_path = asset_evidence.tokenizer_relative_path;
    native_evidence->consumed_tokenizer_sha256 = prompt_handoff.tokenizer_sha256;
    native_evidence->consumed_tokenizer_size_bytes =
        static_cast<long long>(prompt_handoff.tokenizer_size_bytes);
    native_evidence->consumed_tokenizer_asset_verified = true;
    native_evidence->tokenizer_binding_stage = conditioning.tokenizer_binding_stage;
    native_evidence->tokenizer_receipt_canonical_path =
        prompt_handoff.tokenizer_canonical_path;
    native_evidence->tokenizer_receipt_sha256 =
        prompt_handoff.tokenizer_sha256;
    native_evidence->tokenizer_receipt_size_bytes =
        static_cast<long long>(prompt_handoff.tokenizer_size_bytes);
    native_evidence->tokenizer_receipt_binding_stage = "tokenizer_consumed";
    native_evidence->mnn_prompt_handoff_verified = true;
    native_evidence->language_proof_sha256 = asset_evidence.language_proof_sha256;
    native_evidence->consumed_prompt_to_encoder_closure_sha256 =
        prompt_handoff.prompt_to_encoder_closure_sha256;
    native_evidence->consumed_prompt_to_encoder_binding_stage =
        "conditioning_consumed";
    error->clear();
    return true;
}



}  // namespace qnn
}  // namespace mca
