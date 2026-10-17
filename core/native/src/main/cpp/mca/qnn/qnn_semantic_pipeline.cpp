#include "mca/qnn/qnn_semantic_pipeline.hpp"

#include <chrono>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <memory>
#include <cmath>
#include <algorithm>
#include <iomanip>
#include <limits>
#include <mutex>
#include <random>
#include <unistd.h>

#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "image_execution_math.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "qnn_controlnet_execution.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_image_stage_trace.hpp"
#include "qnn_inpaint_contract.hpp"
#include "qnn_soc_htp_mapping.hpp"
#include "qnn_shared_preview.hpp"

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_shared_preview_publisher.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_bundle_manifest.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_device_support.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_ultrafix_handler.hpp"
#include "mca/qnn/qnn_image_writer.hpp"
#include "mca/qnn/qnn_inpaint_blend.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_conditioning_pipeline.hpp"
#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_graph_executor_helper.hpp"

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

bool resolve_qnn_conditioning_evidence(
        const std::string& bundle_root,
        const std::string& consumed_artifact_sha256,
        const std::string& conditioning_format,
        size_t observed_token_ids,
        const std::string& params_json,
        const QnnSemanticExecutionContract& contract,
        const QnnTextEncoderAssetEvidence* text_encoder_asset_evidence,
        QnnConditioningEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr) {
        *error = "Conditioning evidence output is null.";
        return false;
    }
    std::string requested_artifact_sha256;
    if (!qnn_required_string_field(
            params_json,
            "conditioningArtifactSha256",
            &requested_artifact_sha256,
            error)) {
        return false;
    }
    requested_artifact_sha256 = normalized_contract_enum(requested_artifact_sha256);
    if (requested_artifact_sha256.size() != 64U ||
        !std::all_of(
            requested_artifact_sha256.begin(),
            requested_artifact_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "conditioningArtifactSha256 must be a 64-character SHA-256 value.";
        return false;
    }
    evidence->conditioning_artifact_sha256 = normalized_contract_enum(
        consumed_artifact_sha256);
    if (evidence->conditioning_artifact_sha256.size() != 64U ||
        !std::all_of(
            evidence->conditioning_artifact_sha256.begin(),
            evidence->conditioning_artifact_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "The consumed conditioning artifact bytes have no valid SHA-256 evidence.";
        return false;
    }
    if (evidence->conditioning_artifact_sha256 != requested_artifact_sha256) {
        *error = "The conditioning artifact bytes differ from conditioningArtifactSha256.";
        return false;
    }
    std::string native_prompt_execution_sha256 =
        contract.native_prompt_execution_sha256;
    const std::string& native_prompt_binding_stage =
        contract.native_prompt_binding_stage;
    native_prompt_execution_sha256 = normalized_contract_enum(
        native_prompt_execution_sha256);
    if (native_prompt_execution_sha256.size() != 64U ||
        !std::all_of(
            native_prompt_execution_sha256.begin(),
            native_prompt_execution_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "nativePromptExecutionSha256 must be a 64-character SHA-256 value.";
        return false;
    }
    if (native_prompt_binding_stage != "conditioning_encoded") {
        *error = "nativePromptBindingStage must be conditioning_encoded at the QNN conditioning handoff.";
        return false;
    }
    const std::string qnn_prompt_execution_sha256 =
        mca::image::image_prompt_execution_sha256(
            contract.prompt,
            contract.negative_prompt);
    if (qnn_prompt_execution_sha256.size() != 64U) {
        *error = "QNN could not derive native prompt execution SHA-256 from the structured prompt contract.";
        return false;
    }
    if (qnn_prompt_execution_sha256 != native_prompt_execution_sha256) {
        *error = "nativePromptExecutionSha256 differs from the structured prompt and negativePrompt consumed by QNN.";
        return false;
    }
    evidence->native_prompt_execution_sha256 = native_prompt_execution_sha256;
    evidence->native_prompt_binding_stage = native_prompt_binding_stage;
    const auto files = list_files_recursive(bundle_root);
    const bool signed_multilingual_tokenizer = text_encoder_asset_evidence != nullptr &&
        text_encoder_asset_evidence->multilingual_evidence_required;
    const std::string signed_tokenizer_path = signed_multilingual_tokenizer
        ? join_path(bundle_root, text_encoder_asset_evidence->tokenizer_relative_path)
        : "";
    const bool has_standard_tokenizer = signed_multilingual_tokenizer
        ? file_size_or_zero(signed_tokenizer_path) > 0
        : !qnn_nonempty_bundle_file_named(files, "tokenizer.json").empty();
    const bool has_mtok_tokenizer = !signed_multilingual_tokenizer &&
        !qnn_nonempty_bundle_file_named(files, "tokenizer.mtok").empty();
    if (has_standard_tokenizer) {
        evidence->tokenizer_backend = "TOKENIZERS_CPP";
    } else if (has_mtok_tokenizer) {
        evidence->tokenizer_backend = "MNN_MTOK";
    } else {
        *error = "The QNN bundle has no tokenizer.json or tokenizer.mtok proving the tokenizer backend.";
        return false;
    }
    if (signed_multilingual_tokenizer) {
        if (!text_encoder_asset_evidence->required ||
            text_encoder_asset_evidence->tokenizer_relative_path.empty() ||
            text_encoder_asset_evidence->tokenizer_sha256.empty() ||
            text_encoder_asset_evidence->tokenizer_size_bytes <= 0 ||
            text_encoder_asset_evidence->tokenizer_canonical_path.empty() ||
            !text_encoder_asset_evidence->tokenizer_asset_verified ||
            text_encoder_asset_evidence->mnn_prompt_handoff.empty() ||
            evidence->tokenizer_backend != "TOKENIZERS_CPP") {
            *error = "Signed multilingual QNN conditioning requires a captured tokenizerJsonPath asset and an opaque MNN prompt handoff.";
            return false;
        }
    }

    const std::string format = normalized_contract_enum(conditioning_format);
    const bool sdxl_conditioning = format == "sdxl_qnn_conditioning";
    const bool split_sdxl_conditioning = sdxl_conditioning &&
        normalized_contract_enum(string_field(params_json, "workerStrategy")) ==
            "split_unet_vae";
    // The split SDXL worker serializes only what its UNet executes. Existing
    // shared SDXL callers retain the fixed dual-branch artifact, even when a
    // distilled UNet later skips CFG, so their on-disk layout stays compatible.
    const bool conditioning_has_negative_branch = !sdxl_conditioning ||
        !split_sdxl_conditioning || contract.use_cfg;
    const size_t expected_conditioning_token_count = sdxl_conditioning &&
        !conditioning_has_negative_branch
        ? mca::image::kSdxlClipTokenCount
        : 2U * mca::image::kSdxlClipTokenCount;
    const bool strict_external_conditioning_contract =
        normalized_contract_enum(
            string_field(params_json, "conditioningContractMode")) ==
        "shared_unet_vae";
    const bool weighted_token_payload =
        format == "qnn_clip_token_ids_weights_v1";
    if (format == "qnn_clip_token_ids_i32" || weighted_token_payload) {
        if (observed_token_ids == 0) {
            *error = "qnn_clip_token_ids_i32 conditioning has no observed int32 token IDs.";
            return false;
        }
        evidence->token_count = observed_token_ids;
        evidence->embedding_disk_data_type = "GRAPH_INTERNAL";
    } else {
        if (observed_token_ids != 0) {
            *error = "Float embedding conditioning unexpectedly carried int32 token IDs.";
            return false;
        }
        constexpr uint64_t kClipVocabSize = 49408u;
        constexpr uint64_t kSd15EmbeddingWidth = 768u;
        constexpr uint64_t kSdxlSecondEmbeddingWidth = 1280u;
        const bool sd15 =
            format == "community_clip" ||
            format == "sd15_qnn_conditioning" ||
            format == "sd15_qnn_embeddings_f32";
        const bool graph_internal = format == "mnn_text_encoder_embeddings_f32";
        if (!sdxl_conditioning && !sd15 && !graph_internal) {
            *error = "Unsupported explicit float conditioningFormat: " + conditioning_format;
            return false;
        }
        evidence->token_count = expected_conditioning_token_count;
        if (graph_internal) {
            if (qnn_nonempty_bundle_file_named(files, "text_encoder.mnn").empty()) {
                *error = "mnn_text_encoder_embeddings_f32 requires a concrete text_encoder.mnn graph.";
                return false;
            }
            evidence->embedding_disk_data_type = "GRAPH_INTERNAL";
        } else {
            const std::string token_embedding = qnn_nonempty_bundle_file_named(
                files,
                "token_emb.bin");
            if (token_embedding.empty()) {
                *error = "Float CLIP conditioning lacks token_emb.bin dtype evidence.";
                return false;
            }
            const uint64_t expected_fp16 =
                kClipVocabSize * kSd15EmbeddingWidth * sizeof(uint16_t);
            const uint64_t token_embedding_bytes = static_cast<uint64_t>(
                file_size_or_zero(token_embedding));
            if (token_embedding_bytes == expected_fp16) {
                evidence->embedding_disk_data_type = "FP16";
            } else if (token_embedding_bytes == expected_fp16 * 2u) {
                // Headerless tables of this exact size use the explicit FP32 package contract;
                // no alternate byte interpretation is accepted without independent metadata.
                evidence->embedding_disk_data_type = "FP32";
            } else {
                std::ostringstream message;
                message << "token_emb.bin has " << token_embedding_bytes
                        << " bytes; expected " << expected_fp16
                        << " for FP16 or " << (expected_fp16 * 2u) << " for FP32.";
                *error = message.str();
                return false;
            }
            if (sdxl_conditioning) {
                const std::string second_token_embedding = qnn_nonempty_bundle_file_named(
                    files,
                    "token_emb_2.bin");
                if (second_token_embedding.empty()) {
                    *error = "SDXL conditioning lacks token_emb_2.bin dtype evidence.";
                    return false;
                }
                const uint64_t expected_second_fp16 =
                    kClipVocabSize * kSdxlSecondEmbeddingWidth * sizeof(uint16_t);
                const uint64_t second_bytes = static_cast<uint64_t>(
                    file_size_or_zero(second_token_embedding));
                const uint64_t expected_second = evidence->embedding_disk_data_type == "FP16"
                    ? expected_second_fp16
                    : expected_second_fp16 * 2u;
                if (second_bytes != expected_second) {
                    std::ostringstream message;
                    message << "SDXL token embedding dtypes conflict: token_emb_2.bin has "
                            << second_bytes << " bytes, expected " << expected_second << ".";
                    *error = message.str();
                    return false;
                }
            }
        }
    }

    if (contract.tokenizer_max_length != 77 ||
        evidence->token_count != expected_conditioning_token_count) {
        std::ostringstream message;
        message << "QNN CLIP conditioning requires "
                << (expected_conditioning_token_count / mca::image::kSdxlClipTokenCount)
                << " 77-token sequence(s); maxLength="
                << contract.tokenizer_max_length << ", observed tokenCount="
                << evidence->token_count << ".";
        *error = message.str();
        return false;
    }
    if (contract.tokenizer_backend != evidence->tokenizer_backend) {
        *error = "tokenizerBackend mismatch: resolved=" + contract.tokenizer_backend +
            ", conditioning evidence=" + evidence->tokenizer_backend + ".";
        return false;
    }
    if (contract.token_count != evidence->token_count) {
        std::ostringstream message;
        message << "tokenCount mismatch: resolved=" << contract.token_count
                << ", conditioning evidence=" << evidence->token_count << ".";
        *error = message.str();
        return false;
    }
    if (contract.embedding_disk_data_type != evidence->embedding_disk_data_type) {
        *error = "embeddingDiskDataType mismatch: resolved=" +
            contract.embedding_disk_data_type + ", conditioning evidence=" +
            evidence->embedding_disk_data_type + ".";
        return false;
    }
    if (weighted_token_payload && !contract.prompt_weighting_supported) {
        *error = "Weighted token payload requires promptWeightingSupported=true.";
        return false;
    }
    if (format == "qnn_clip_token_ids_i32" && contract.prompt_weighting_supported) {
        *error = "Prompt weighting requires the versioned qnn_clip_token_ids_weights_v1 payload.";
        return false;
    }

    const bool token_conditioning =
        format == "qnn_clip_token_ids_i32" || weighted_token_payload;
    bool reported_applied = false;
    long long reported_positive = 0;
    long long reported_negative = 0;
    std::string reported_fingerprint;
    if (!qnn_required_bool_field(
            params_json,
            "promptWeightingApplied",
            &reported_applied,
            error) ||
        !qnn_required_integer_field(
            params_json,
            "positiveWeightedTokenCount",
            &reported_positive,
            error) ||
        !qnn_required_integer_field(
            params_json,
            "negativeWeightedTokenCount",
            &reported_negative,
            error) ||
        !qnn_required_string_field(
            params_json,
            "promptWeightFingerprint",
            &reported_fingerprint,
            error)) {
        return false;
    }
    if (reported_positive < 0 || reported_positive > 4096 ||
        reported_negative < 0 || reported_negative > 4096) {
        *error = "Prompt weighting token counts must be in [0, 4096].";
        return false;
    }
    std::transform(
        reported_fingerprint.begin(),
        reported_fingerprint.end(),
        reported_fingerprint.begin(),
        [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
    if (reported_fingerprint.size() != 64U ||
        !std::all_of(
            reported_fingerprint.begin(),
            reported_fingerprint.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "Prompt weighting fingerprint must be a 64-character SHA-256 value.";
        return false;
    }
    if (reported_applied != (reported_positive + reported_negative > 0)) {
        *error = "Prompt weighting applied flag conflicts with weighted token counts.";
        return false;
    }
    if (!contract.prompt_weighting_supported &&
        (reported_applied || reported_positive != 0 || reported_negative != 0)) {
        *error = "A profile without prompt weighting reported applied conditioning weights.";
        return false;
    }
    if (!contract.use_cfg && reported_negative != 0) {
        *error = "Conditioning reported weights for a negative branch that was not executed.";
        return false;
    }
    if (token_conditioning) {
        evidence->conditioning_execution_mode = "qnn_text_encoder";
        evidence->conditioning_backend = "QNN";
        evidence->conditioning_graph = "text_encoder.bin";
        if (evidence->prompt_weight_fingerprint.size() != 64U ||
            !std::all_of(
                evidence->prompt_weight_fingerprint.begin(),
                evidence->prompt_weight_fingerprint.end(),
                [](unsigned char value) { return std::isxdigit(value) != 0; })) {
            *error = "Token conditioning did not produce a valid native SHA-256 fingerprint.";
            return false;
        }
        if (evidence->prompt_weighting_applied !=
            (evidence->positive_weighted_token_count +
                evidence->negative_weighted_token_count > 0U)) {
            *error = "Native token weighting evidence conflicts with the executed branch counts.";
            return false;
        }
        if (reported_applied != evidence->prompt_weighting_applied ||
            reported_positive != static_cast<long long>(
                evidence->positive_weighted_token_count) ||
            reported_negative != static_cast<long long>(
                evidence->negative_weighted_token_count) ||
            reported_fingerprint != evidence->prompt_weight_fingerprint) {
            *error = "Producer prompt weighting evidence differs from the consumed QNN token payload.";
            return false;
        }
    } else {
        // Legacy float conditioning cannot reconstruct token weights inside the
        // QNN process. Accept only complete evidence emitted by the native
        // encoder; a versioned, artifact-bound float container remains the
        // follow-up that will remove this handoff entirely.
        if ((strict_external_conditioning_contract || format == "sdxl_qnn_conditioning") &&
            reported_fingerprint != evidence->conditioning_artifact_sha256) {
            *error = "External MNN promptWeightFingerprint must identify the exact consumed conditioning artifact.";
            return false;
        }
        evidence->prompt_weighting_applied = reported_applied;
        evidence->positive_weighted_token_count =
            static_cast<size_t>(reported_positive);
        evidence->negative_weighted_token_count =
            static_cast<size_t>(reported_negative);
        evidence->prompt_weight_fingerprint = reported_fingerprint;
    }
    if (!token_conditioning && format == "sdxl_qnn_conditioning") {
        std::string conditioning_execution_mode;
        std::string conditioning_backend;
        std::string conditioning_graph;
        std::string conditioning_graph_sha256;
        std::string conditioning_order;
        long long conditioning_encoder_execution_count = 0;
        if (!qnn_required_string_field(
                params_json,
                "conditioningExecutionMode",
                &conditioning_execution_mode,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningBackend",
                &conditioning_backend,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraph",
                &conditioning_graph,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraphSha256",
                &conditioning_graph_sha256,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningOrder",
                &conditioning_order,
                error) ||
            !qnn_required_integer_field(
                params_json,
                "conditioningEncoderExecutionCount",
                &conditioning_encoder_execution_count,
                error)) {
            return false;
        }
        const size_t expected_encoder_execution_count =
            conditioning_has_negative_branch ? 4U : 2U;
        const std::string expected_conditioning_order = conditioning_has_negative_branch
            ? "negative_then_positive"
            : "positive_only";
        if (conditioning_execution_mode != "external_mnn_sdxl_embeddings" ||
            conditioning_backend != "MNN" ||
            conditioning_graph != "clip.mnn+clip_2.mnn" ||
            conditioning_order != expected_conditioning_order ||
            conditioning_encoder_execution_count !=
                static_cast<long long>(expected_encoder_execution_count)) {
            *error = "SDXL conditioning evidence does not match the resolved MNN CLIP branch contract.";
            return false;
        }
        conditioning_graph_sha256 = normalized_contract_enum(conditioning_graph_sha256);
        std::string executed_graph_sha256;
        if (!qnn_sdxl_conditioning_graph_sha256(
                files,
                &executed_graph_sha256,
                error)) {
            return false;
        }
        if (conditioning_graph_sha256 != executed_graph_sha256) {
            *error = "The installed MNN SDXL conditioning execution assets differ from conditioningGraphSha256.";
            return false;
        }
        evidence->conditioning_execution_mode = conditioning_execution_mode;
        evidence->conditioning_backend = conditioning_backend;
        evidence->conditioning_graph = conditioning_graph;
        evidence->conditioning_graph_sha256 = executed_graph_sha256;
        evidence->conditioning_order = conditioning_order;
        evidence->conditioning_encoder_execution_count = expected_encoder_execution_count;
    } else if (!token_conditioning && strict_external_conditioning_contract &&
        (format == "community_clip" || format == "sd15_qnn_conditioning" ||
         format == "sd15_qnn_embeddings_f32")) {
        std::string conditioning_execution_mode;
        std::string conditioning_backend;
        std::string conditioning_graph;
        std::string conditioning_graph_sha256;
        std::string conditioning_order;
        long long conditioning_encoder_execution_count = 0;
        if (!qnn_required_string_field(
                params_json,
                "conditioningExecutionMode",
                &conditioning_execution_mode,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningBackend",
                &conditioning_backend,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraph",
                &conditioning_graph,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraphSha256",
                &conditioning_graph_sha256,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningOrder",
                &conditioning_order,
                error) ||
            !qnn_required_integer_field(
                params_json,
                "conditioningEncoderExecutionCount",
                &conditioning_encoder_execution_count,
                error)) {
            return false;
        }
        const size_t expected_execution_count = contract.use_cfg ? 2U : 1U;
        const std::string expected_order = contract.use_cfg
            ? "negative_then_positive"
            : "positive_only";
        if (conditioning_execution_mode != "external_mnn_embeddings" ||
            conditioning_backend != "MNN" || conditioning_graph != "clip_v2.mnn" ||
            conditioning_graph_sha256.size() != 64U ||
            !std::all_of(
                conditioning_graph_sha256.begin(),
                conditioning_graph_sha256.end(),
                [](unsigned char value) { return std::isxdigit(value) != 0; }) ||
            conditioning_order != expected_order ||
            conditioning_encoder_execution_count !=
                static_cast<long long>(expected_execution_count)) {
            *error = "External SD1.5 conditioning evidence does not match the MNN clip_v2.mnn execution contract.";
            return false;
        }
        conditioning_graph_sha256 = normalized_contract_enum(conditioning_graph_sha256);
        const std::string conditioning_graph_path = qnn_nonempty_bundle_file_named(
            files,
            conditioning_graph);
        if (conditioning_graph_path.empty()) {
            *error = "The declared external MNN conditioning graph is missing or empty in the bundle.";
            return false;
        }
        std::string executed_graph_sha256;
        if (!qnn_file_sha256(conditioning_graph_path, &executed_graph_sha256, error)) {
            *error = "External MNN conditioning graph SHA-256 could not be derived: " + *error;
            return false;
        }
        if (conditioning_graph_sha256 != executed_graph_sha256) {
            *error = "The installed external MNN conditioning graph differs from conditioningGraphSha256.";
            return false;
        }
        evidence->conditioning_execution_mode = conditioning_execution_mode;
        evidence->conditioning_backend = conditioning_backend;
        evidence->conditioning_graph = conditioning_graph;
        evidence->conditioning_graph_sha256 = executed_graph_sha256;
        evidence->conditioning_order = conditioning_order;
        evidence->conditioning_encoder_execution_count = expected_execution_count;
    } else if (!token_conditioning &&
        (format == "community_clip" || format == "sd15_qnn_conditioning" ||
         format == "sd15_qnn_embeddings_f32")) {
        // Old community packages did not publish the new cross-process fields.
        // Preserve their real execution path, while deriving graph identity
        // independently from installed bytes instead of trusting request echo.
        std::string conditioning_graph_path = qnn_nonempty_bundle_file_named(
            files,
            "clip_v2.mnn");
        std::string conditioning_graph = "clip_v2.mnn";
        if (conditioning_graph_path.empty()) {
            conditioning_graph_path = qnn_nonempty_bundle_file_named(
                files,
                "text_encoder.mnn");
            conditioning_graph = "text_encoder.mnn";
        }
        if (conditioning_graph_path.empty()) {
            *error = "Float conditioning requires a concrete MNN text encoder graph in the bundle.";
            return false;
        }
        std::string executed_graph_sha256;
        if (!qnn_file_sha256(conditioning_graph_path, &executed_graph_sha256, error)) {
            *error = "Legacy external MNN conditioning graph SHA-256 could not be derived: " + *error;
            return false;
        }
        evidence->conditioning_execution_mode = "external_mnn_embeddings";
        evidence->conditioning_backend = "MNN";
        evidence->conditioning_graph = conditioning_graph;
        evidence->conditioning_graph_sha256 = executed_graph_sha256;
        evidence->conditioning_order = contract.use_cfg
            ? "negative_then_positive"
            : "positive_only";
        evidence->conditioning_encoder_execution_count = contract.use_cfg ? 2U : 1U;
    }
    return true;
}

std::string qnn_semantic_generate_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& output_path) {
    QnnSemanticRun run(bundle_root, runtime_dirs_json, params_json, embeddings_path, output_path);
    return run.run();
}

bool QnnSemanticRun::valid_inpaint_sha256(const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](unsigned char digit) {
                return (digit >= '0' && digit <= '9') ||
                    (digit >= 'a' && digit <= 'f');
            });
}

QnnSemanticRun::QnnSemanticRun(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& output_path)
    : bundle_root(bundle_root),
      runtime_dirs_json(runtime_dirs_json),
      params_json(params_json),
      embeddings_path(embeddings_path),
      output_path(output_path) {}

std::string QnnSemanticRun::run() {
    // A failed native release intentionally poisons this disposable worker.
    // Check before probing or dlopening any QNN runtime so a later request
    // cannot touch a library that may still own a live context.
    if (g_qnn_runtime_poisoned.load()) {
        return qnn_semantic_failure_json(
            "runtime_resource_release_failed",
            "NATIVE_RESOURCE_RELEASE_FAILED",
            "This disposable QNN image worker rejected reuse after a native resource release failure.");
    }
    started = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    runtime = inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned);
    if (g_qnn_runtime_poisoned.load()) {
        return qnn_semantic_failure_json(
            "runtime_resource_release_failed",
            "NATIVE_RESOURCE_RELEASE_FAILED",
            runtime.message.empty()
                ? "QNN runtime preflight resources did not release cleanly."
                : runtime.message);
    }
    bundle = inspect_bundle_internal(bundle_root);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    if (!bundle.root_present) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"bundle_missing\",\"message\":\"QNN bundle root is missing.\"}";
    }

    conditioning_format = string_field(params_json, "conditioningFormat");
    if (conditioning_format.empty()) {
        return qnn_semantic_failure_json(
            "execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            "conditioningFormat must be explicit for QNN semantic generation.");
    }
    const std::string normalized_conditioning_format =
        normalized_contract_enum(conditioning_format);
    qnn_weighted_token_conditioning =
        normalized_conditioning_format == "qnn_clip_token_ids_weights_v1";
    qnn_token_conditioning =
        normalized_conditioning_format == "qnn_clip_token_ids_i32" ||
        qnn_weighted_token_conditioning;
    if (!parse_qnn_semantic_execution_contract(
            params_json,
            &execution_contract,
            &error)) {
        return qnn_semantic_failure_json(
            "execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    if (!parse_qnn_ultrafix_request(params_json, &ultra_fix_request, &error)) {
        return qnn_semantic_failure_json(
            "ultrafix_contract_invalid",
            "QNN_ULTRAFIX_CONTRACT_INVALID",
            error);
    }
    if (!parse_qnn_preview_request(params_json, &preview_request, &error)) {
        return qnn_semantic_failure_json(
            "preview_contract_invalid",
            "INVALID_PREVIEW_CONTRACT",
            error);
    }
    task_mode = normalized_contract_enum(string_field(params_json, "taskMode"));
    if (task_mode.empty()) task_mode = "text_to_image";
    request_controlnet = task_mode == "control";
    request_img2img = task_mode == "img2img";
    request_inpaint = task_mode == "inpaint";
    requested_inpaint_topology = normalized_contract_enum(
        string_field(params_json, "inpaintRequestedTopology"));
    if (task_mode != "text_to_image" && !request_controlnet && !request_img2img &&
        !request_inpaint) {
        return qnn_semantic_failure_json(
            "task_mode_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            "QNN semantic generation supports text_to_image, control, encoder-backed img2img, or topology-checked inpaint for this execution path.");
    }
    worker_strategy = normalized_contract_enum(
        string_field(params_json, "workerStrategy"));
    if (ultra_fix_request.enabled &&
        (!request_img2img ||
         (worker_strategy != "shared_unet_vae" &&
          worker_strategy != "shared_text_unet_vae") ||
         execution_contract.width != ultra_fix_request.target_width ||
         execution_contract.height != ultra_fix_request.target_height ||
         execution_contract.scheduler.steps != ultra_fix_request.refinement_steps ||
         execution_contract.scheduler.config.prediction_type !=
            mca::diffusion::PredictionType::Epsilon ||
         execution_contract.scheduler.config.algorithm ==
            mca::diffusion::SchedulerAlgorithm::Pndm ||
         preview_request.requested ||
         long_field(params_json, "batchCount") != 1LL)) {
        return qnn_semantic_failure_json(
            "ultrafix_topology_unsupported",
            "QNN_ULTRAFIX_TOPOLOGY_UNSUPPORTED",
            "QNN UltraFix requires one shared_unet_vae img2img output, epsilon prediction, a reversible scheduler, exact target controls, and no live preview.");
    }
    if ((request_img2img || request_inpaint) && worker_strategy != "shared_unet_vae" &&
        worker_strategy != "shared_text_unet_vae") {
        return qnn_semantic_failure_json(
            request_inpaint ? "inpaint_topology_unsupported" : "img2img_topology_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            request_inpaint
                ? "QNN inpaint requires a concrete shared coherent UNet/VAE topology with a VAE encoder; a regular four-channel UNet uses per-step latent blending."
                : "QNN img2img requires a concrete shared coherent UNet/VAE topology with a VAE encoder.");
    }
    input_image_path = string_field(params_json, "inputImagePath");
    mask_image_path = string_field(params_json, "maskImagePath");
    mask_image_sha256 = normalized_contract_enum(
        string_field(params_json, "maskImageSha256"));
    mca::diffusion::DiffusionScheduler scheduler(execution_contract.scheduler.config);
    if (const std::string request_result = request_phase(scheduler); !request_result.empty()) {
        return request_result;
    }
    std::string consumed_conditioning_artifact_sha256;
    if (qnn_token_conditioning) {
        const bool read_ok = qnn_weighted_token_conditioning
            ? read_qnn_clip_token_weight_payload(
                embeddings_path,
                &token_ids,
                &token_weights,
                execution_contract.use_cfg,
                &conditioning_evidence,
                &error,
                &consumed_conditioning_artifact_sha256)
            : read_int32_binary_file(
                embeddings_path,
                &token_ids,
                &error,
                &consumed_conditioning_artifact_sha256);
        if (!read_ok) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"token_read_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (!qnn_weighted_token_conditioning) {
            const std::vector<float> unity_weights(token_ids.size(), 1.0f);
            if (!derive_qnn_token_execution_weight_evidence(
                    token_ids,
                    unity_weights,
                    execution_contract.use_cfg,
                    &conditioning_evidence,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"token_evidence_invalid\",\"message\":") +
                    quote(error) + "}";
            }
        }
    } else {
        if (!read_float_binary_file(
                embeddings_path,
                &embeddings,
                &error,
                &consumed_conditioning_artifact_sha256)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"embedding_read_failed\",\"message\":") +
            quote(error) + "}";
        }
    }
    if (!resolve_qnn_conditioning_evidence(
            bundle_root,
            consumed_conditioning_artifact_sha256,
            conditioning_format,
            token_ids.size(),
            params_json,
            execution_contract,
            &text_encoder_asset_evidence,
            &conditioning_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "conditioning_contract_invalid",
            "CONDITIONING_EVIDENCE_INVALID",
            error);
    }

    const std::string progress_journal_path = string_field(params_json, "progressJournalPath");
    QnnImageGenerationScope generation(
        static_cast<int>(effective_timetable_count),
        progress_journal_path);
    if (!generation.active()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image generation is still finishing.\"}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    QnnSharedPreviewPublisher preview_publisher(preview_contract, progress_journal_path);
    // Preview storage is auxiliary. A request-scoped directory failure is
    // audited by the publisher and disables later preview attempts, but it
    // must not substitute for or prevent the independent final VAE decode.
    if (!preview_publisher.initialize(&error)) error.clear();

    generation.record_stage(
        mca::qnn::ImageStage::ContextLock,
        kQnnImageContextLock);
    context_execution_lock = std::unique_lock<std::timed_mutex>(
        ::mca::qnn::g_qnn_context_execution_mutex,
        std::defer_lock
    );
    while (!context_execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    if (request_img2img || request_inpaint) {
        std::string encoder_failure_code;
        if (!qnn_run_shared_img2img_encoder(
                runtime,
                bundle_root,
                params_json,
                execution_contract,
                ultra_fix_request.enabled ? &ultra_fix_request : nullptr,
                &generation,
                &img2img_encoder,
                &encoder_failure_code,
                &error)) {
            if (encoder_failure_code == "CANCELLED" || generation.cancelled()) {
                return qnn_image_generation_cancelled_json();
            }
            return qnn_semantic_failure_json(
                "shared_img2img_encoder_failed",
                encoder_failure_code.empty()
                    ? "ENCODER_EXECUTION_FAILED"
                    : encoder_failure_code,
                error);
        }
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    if (!runtime_session.open(runtime, unet_path, &generation)) {
        const std::string primary_error = runtime_session.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"runtime_session_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (!unet.load_in_session(&runtime_session, runtime, unet_path, graph_name, false)) {
        const std::string primary_error = unet.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    if (request_inpaint) {
        std::vector<mca::qnn::inpaint::TensorDescriptor> inpaint_inputs;
        inpaint_inputs.reserve(unet.inputs.size());
        for (const auto& tensor : unet.inputs) {
            inpaint_inputs.push_back({tensor.name, tensor.dimensions});
        }
        inpaint_contract = mca::qnn::inpaint::inspect(inpaint_inputs);
        if (!inpaint_contract.supported()) {
            return qnn_semantic_failure_json(
                "inpaint_topology_unsupported",
                "QNN_INPAINT_GRAPH_TOPOLOGY_UNSUPPORTED",
                "QNN inpaint was rejected because the loaded UNet does not expose an executable four-channel or explicit mask-conditioned topology: " +
                    inpaint_contract.reason + " Graph inputs=" + qnn_tensor_list_debug_json(unet.inputs));
        }
        if (requested_inpaint_topology != inpaint_contract.topology_name()) {
            return qnn_semantic_failure_json(
                "inpaint_topology_identity_mismatch",
                "QNN_INPAINT_TOPOLOGY_IDENTITY_MISMATCH",
                "The prepared inpaint topology differs from the UNet graph loaded by native execution.");
        }
    }

    if (const std::string conditioning_result = conditioning_phase(generation); !conditioning_result.empty()) {
        return conditioning_result;
    }
    if (request_controlnet && !controlnet.load_in_session(
            &runtime_session,
            runtime,
            controlnet_path,
            controlnet_graph_name,
            false)) {
        const std::string primary_error = controlnet.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"controlnet_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    if (!vae.load_in_session(&runtime_session, runtime, vae_path, graph_name, true)) {
        const std::string primary_error = vae.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    if (const std::string sdxl_result = sdxl_phase(scheduler, generation, preview_publisher); !sdxl_result.empty()) {
        return sdxl_result;
    }
    if (const std::string stage_result = stage_runner_phase(scheduler, generation, preview_publisher); !stage_result.empty()) {
        return stage_result;
    }
    if (const std::string output_result = output_phase(scheduler, generation); !output_result.empty()) {
        return output_result;
    }
    return evidence_phase(scheduler, preview_publisher);
}

#else  // !MCA_QNN_SYSTEM_LOADER_HAS_SDK

std::string qnn_semantic_generate_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
