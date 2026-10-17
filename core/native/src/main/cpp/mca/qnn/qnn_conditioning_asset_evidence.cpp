// mca/qnn/qnn_conditioning_asset_evidence.cpp -- strict text-encoder asset evidence parsing and prompt-language helpers.
// (T2B16 / R7-C5 split).  Relocated byte-exact from mca/qnn/qnn_conditioning_pipeline.cpp (lines 375-661).

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

bool parse_qnn_text_encoder_asset_evidence(
        const std::string& params_json,
        const std::string& text_encoder_binary,
        const std::string& requested_text_encoder_graph_name,
        bool prompt_requires_multilingual_evidence,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr) return false;
    *evidence = QnnTextEncoderAssetEvidence{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.is_object()) {
            *error = "QNN execution params must be one structured JSON object.";
            return false;
        }
        const auto path = root.find("textEncoderPath");
        const auto sha256 = root.find("textEncoderSha256");
        const auto size_bytes = root.find("textEncoderSizeBytes");
        const auto graph_name = root.find("textEncoderGraphName");
        const auto context_binary = root.find("textEncoderContextBinary");
        const auto tokenizer_path = root.find("tokenizerJsonPath");
        const auto tokenizer_sha256 = root.find("tokenizerJsonSha256");
        const auto tokenizer_size_bytes = root.find("tokenizerJsonSizeBytes");
        const auto mnn_prompt_handoff = root.find("mnnPromptHandoff");
        const auto tokenizer_receipt_canonical_path = root.find(
            "tokenizerReceiptCanonicalPath");
        const auto tokenizer_receipt_sha256 = root.find("tokenizerReceiptSha256");
        const auto tokenizer_receipt_size_bytes = root.find("tokenizerReceiptSizeBytes");
        const auto tokenizer_receipt_binding_stage = root.find(
            "tokenizerReceiptBindingStage");
        const auto tokenizer_asset_path = root.find("tokenizerAssetPath");
        const auto tokenizer_asset_sha256 = root.find("tokenizerAssetSha256");
        const auto tokenizer_asset_size_bytes = root.find("tokenizerAssetSizeBytes");
        const auto tokenizer_asset_binding_stage = root.find(
            "tokenizerAssetBindingStage");
        const auto prompt_to_encoder_closure_sha256 = root.find(
            "promptToEncoderClosureSha256");
        const auto language_proof_sha256 = root.find("languageProofSha256");
        const bool any_declared = path != root.end() || sha256 != root.end() ||
            size_bytes != root.end();
        const bool all_declared = path != root.end() && sha256 != root.end() &&
            size_bytes != root.end();
        const bool any_tokenizer_declared = tokenizer_path != root.end() ||
            tokenizer_sha256 != root.end() || tokenizer_size_bytes != root.end();
        const bool all_tokenizer_declared = tokenizer_path != root.end() &&
            tokenizer_sha256 != root.end() && tokenizer_size_bytes != root.end();
        const bool any_tokenizer_receipt_declared =
            tokenizer_receipt_canonical_path != root.end() ||
            tokenizer_receipt_sha256 != root.end() ||
            tokenizer_receipt_size_bytes != root.end() ||
            tokenizer_receipt_binding_stage != root.end();
        const bool any_legacy_tokenizer_asset_declared =
            tokenizer_asset_path != root.end() ||
            tokenizer_asset_sha256 != root.end() ||
            tokenizer_asset_size_bytes != root.end() ||
            tokenizer_asset_binding_stage != root.end();
        const bool signed_multilingual_claim = language_proof_sha256 != root.end();
        const bool multilingual_evidence_required =
            prompt_requires_multilingual_evidence || signed_multilingual_claim;
        evidence->multilingual_evidence_required = multilingual_evidence_required;

        const auto parse_size_bytes = [&](
                const nlohmann::json& value,
                const char* field_name,
                long long* output) {
            if (!value.is_number_integer() && !value.is_number_unsigned()) {
                *error = std::string(field_name) + " must be an exact integer.";
                return false;
            }
            if (value.is_number_unsigned()) {
                const uint64_t unsigned_size_bytes = value.get<uint64_t>();
                if (unsigned_size_bytes >
                    static_cast<uint64_t>(std::numeric_limits<long long>::max())) {
                    *error = std::string(field_name) +
                        " exceeds the native signed size range.";
                    return false;
                }
                *output = static_cast<long long>(unsigned_size_bytes);
            } else {
                *output = value.get<long long>();
            }
            if (*output <= 0) {
                *error = std::string(field_name) + " must be positive.";
                return false;
            }
            return true;
        };
        const auto normalize_sha256 = [](std::string* value) {
            std::transform(
                value->begin(),
                value->end(),
                value->begin(),
                [](unsigned char character) {
                    return static_cast<char>(std::tolower(character));
                });
            return value->size() == 64U && std::all_of(
                value->begin(),
                value->end(),
                [](unsigned char character) {
                    return (character >= '0' && character <= '9') ||
                        (character >= 'a' && character <= 'f');
                });
        };
        if (multilingual_evidence_required &&
            (any_tokenizer_receipt_declared ||
             any_legacy_tokenizer_asset_declared)) {
            *error = "Signed multilingual QNN evidence rejects request-side tokenizerReceipt* and tokenizerAsset* authorization fields.";
            return false;
        }
        if (any_declared && !all_declared) {
            *error = "textEncoderPath, textEncoderSha256, and textEncoderSizeBytes must be supplied together.";
            return false;
        }
        if (!all_declared) {
            if (multilingual_evidence_required) {
                *error = "Signed multilingual QNN prompts require complete text encoder asset evidence.";
                return false;
            }
            error->clear();
            return true;
        }
        if (!path->is_string() || !sha256->is_string()) {
            *error = "textEncoderPath and textEncoderSha256 must be strings.";
            return false;
        }
        long long requested_size_bytes = 0;
        if (!parse_size_bytes(*size_bytes, "textEncoderSizeBytes", &requested_size_bytes)) {
            return false;
        }
        std::string requested_path = path->get<std::string>();
        std::string requested_sha256 = sha256->get<std::string>();
        if (requested_path.find('\0') != std::string::npos ||
            !is_safe_bundle_relative_path(requested_path) ||
            requested_path != text_encoder_binary) {
            *error = "textEncoderPath must be a safe bundle-relative path matching textEncoderContextBinary.";
            return false;
        }
        if (!normalize_sha256(&requested_sha256)) {
            *error = "textEncoderSha256 must be a 64-character SHA-256 value.";
            return false;
        }
        std::string requested_language_proof_sha256;
        if (language_proof_sha256 != root.end()) {
            if (!language_proof_sha256->is_string()) {
                *error = "languageProofSha256 must be a 64-character SHA-256 string when supplied.";
                return false;
            }
            requested_language_proof_sha256 = language_proof_sha256->get<std::string>();
            if (!normalize_sha256(&requested_language_proof_sha256)) {
                *error = "languageProofSha256 must be a 64-character SHA-256 value.";
                return false;
            }
        }

        if (multilingual_evidence_required) {
            if (context_binary == root.end() || !context_binary->is_string()) {
                *error = "Signed multilingual QNN evidence requires textEncoderContextBinary.";
                return false;
            }
            const std::string requested_context_binary = context_binary->get<std::string>();
            if (requested_context_binary.find('\0') != std::string::npos ||
                !is_safe_bundle_relative_path(requested_context_binary) ||
                !is_safe_bundle_relative_path(text_encoder_binary) ||
                requested_context_binary != text_encoder_binary ||
                requested_path != requested_context_binary) {
                *error = "Signed multilingual QNN evidence must bind textEncoderPath to the exact textEncoderContextBinary asset.";
                return false;
            }
            if (graph_name == root.end() || !graph_name->is_string()) {
                *error = "Signed multilingual QNN evidence requires textEncoderGraphName.";
                return false;
            }
            std::string requested_graph_name = graph_name->get<std::string>();
            if (requested_graph_name.empty() ||
                requested_graph_name.find('\0') != std::string::npos ||
                requested_graph_name != requested_text_encoder_graph_name) {
                *error = "textEncoderGraphName must identify the exact graph selected from textEncoderContextBinary.";
                return false;
            }
            if (!all_tokenizer_declared || !tokenizer_path->is_string() ||
                !tokenizer_sha256->is_string()) {
                *error = "Signed multilingual QNN evidence requires tokenizerJsonPath, tokenizerJsonSha256, and tokenizerJsonSizeBytes.";
                return false;
            }
            long long requested_tokenizer_size_bytes = 0;
            if (!parse_size_bytes(
                    *tokenizer_size_bytes,
                    "tokenizerJsonSizeBytes",
                    &requested_tokenizer_size_bytes)) {
                return false;
            }
            std::string requested_tokenizer_path = tokenizer_path->get<std::string>();
            std::string requested_tokenizer_sha256 = tokenizer_sha256->get<std::string>();
            if (requested_tokenizer_path.find('\0') != std::string::npos ||
                !is_safe_bundle_relative_path(requested_tokenizer_path) ||
                requested_tokenizer_path == requested_path ||
                !ends_with(normalized_contract_enum(requested_tokenizer_path), ".json")) {
                *error = "tokenizerJsonPath must be a distinct safe bundle-relative JSON tokenizer asset.";
                return false;
            }
            if (!normalize_sha256(&requested_tokenizer_sha256)) {
                *error = "tokenizerJsonSha256 must be a 64-character SHA-256 value.";
                return false;
            }
            if (mnn_prompt_handoff == root.end() ||
                !mnn_prompt_handoff->is_string()) {
                *error = "Signed multilingual QNN evidence requires mnnPromptHandoff.";
                return false;
            }
            std::string requested_mnn_prompt_handoff =
                mnn_prompt_handoff->get<std::string>();
            if (requested_mnn_prompt_handoff.size() != 64U ||
                !std::all_of(
                    requested_mnn_prompt_handoff.begin(),
                    requested_mnn_prompt_handoff.end(),
                    [](unsigned char character) {
                        return (character >= '0' && character <= '9') ||
                            (character >= 'a' && character <= 'f');
                    })) {
                *error = "mnnPromptHandoff must be a 256-bit lowercase opaque handle.";
                return false;
            }
            if (prompt_to_encoder_closure_sha256 == root.end() ||
                !prompt_to_encoder_closure_sha256->is_string()) {
                *error = "Signed multilingual QNN evidence requires promptToEncoderClosureSha256.";
                return false;
            }
            std::string requested_prompt_to_encoder_closure_sha256 =
                prompt_to_encoder_closure_sha256->get<std::string>();
            if (!normalize_sha256(&requested_prompt_to_encoder_closure_sha256)) {
                *error = "promptToEncoderClosureSha256 must be a 64-character SHA-256 value.";
                return false;
            }
            if (requested_language_proof_sha256.empty()) {
                *error = "Signed multilingual QNN evidence requires languageProofSha256.";
                return false;
            }
            evidence->graph_name = std::move(requested_graph_name);
            evidence->tokenizer_relative_path = std::move(requested_tokenizer_path);
            evidence->tokenizer_sha256 = std::move(requested_tokenizer_sha256);
            evidence->tokenizer_size_bytes = requested_tokenizer_size_bytes;
            evidence->mnn_prompt_handoff =
                std::move(requested_mnn_prompt_handoff);
            evidence->prompt_to_encoder_closure_sha256 =
                std::move(requested_prompt_to_encoder_closure_sha256);
        } else if (any_tokenizer_declared || any_tokenizer_receipt_declared ||
                   any_legacy_tokenizer_asset_declared ||
                   mnn_prompt_handoff != root.end() ||
                   prompt_to_encoder_closure_sha256 != root.end()) {
            // Legacy English execution neither consumes nor receipts a closure. Keep it
            // compatible even when a newer caller includes optional closure fields.
        }
        evidence->required = true;
        evidence->relative_path = std::move(requested_path);
        evidence->sha256 = std::move(requested_sha256);
        evidence->size_bytes = requested_size_bytes;
        evidence->language_proof_sha256 = std::move(requested_language_proof_sha256);
        error->clear();
        return true;
    } catch (const nlohmann::json::exception&) {
        *error = "QNN text encoder asset evidence JSON is invalid.";
        return false;
    }
}

// English-dominant product paths pass only safe ASCII tags.  A native caller that supplies any
// UTF-8 bytes outside ASCII must therefore present the same strict text-encoder asset proof used
// by the evidence-bound multilingual Android path.
bool qnn_prompt_contains_non_ascii(const std::string& value) {
    return std::any_of(value.begin(), value.end(), [](unsigned char byte) {
        return byte >= 0x80U;
    });
}

bool qnn_is_safe_ascii_diffusion_prompt(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char byte) {
        return mca::image::prompt_language::is_safe_ascii_diffusion_prompt_code_point(byte);
    });
}

bool qnn_is_safe_ascii_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt) {
    return qnn_is_safe_ascii_diffusion_prompt(prompt) &&
        qnn_is_safe_ascii_diffusion_prompt(negative_prompt);
}



}  // namespace qnn
}  // namespace mca
