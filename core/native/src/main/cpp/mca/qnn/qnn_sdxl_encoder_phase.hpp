#ifndef MCA_QNN_SDXL_ENCODER_PHASE_HPP
#define MCA_QNN_SDXL_ENCODER_PHASE_HPP

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iomanip>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "image_execution_math.hpp"
#include "qnn_controlnet_execution.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"

// namespace mca::qnn removed to prevent anonymous namespace shadowing

template <typename GraphT, typename... Args>
inline std::string execute_sdxl_encoder_phase(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& input_tensor_path,
        const std::string& latent_path,
        const std::string& expected_encoder_context_sha256_raw,
        Args&&... /*extra_args*/) {
    std::string error;
    if (!validate_sdxl_no_preview_transport(params_json, &error)) {
        return qnn_semantic_failure_json(
            "preview_transport_unsupported",
            "UNSUPPORTED_PREVIEW_TRANSPORT",
            error);
    }
    const auto started = std::chrono::steady_clock::now();
    const RuntimeProbe runtime = inspect_sdxl_exact_runtime_profile(
        runtime_profile_json, true);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    QnnSemanticExecutionContract execution_contract;
    if (!parse_qnn_semantic_execution_contract(params_json, &execution_contract, &error) ||
        !validate_sdxl_phase_execution_contract(execution_contract, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    QnnUltraFixRequest ultra_fix;
    if (!parse_qnn_ultrafix_request(params_json, &ultra_fix, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_ultrafix_contract_invalid",
            "QNN_ULTRAFIX_CONTRACT_INVALID",
            error);
    }
    const std::string task_mode = normalized_contract_enum(
        string_field(params_json, "taskMode"));
    const std::string input_image_path = string_field(params_json, "inputImagePath");
    const std::string expected_image_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageSha256"));
    const std::string declared_tensor_path = string_field(
        params_json, "inputImageTensorPath");
    const std::string expected_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageTensorSha256"));
    const std::string preprocess = string_field(params_json, "inputImagePreprocess");
    const std::string tensor_dtype = string_field(params_json, "inputImageTensorDtype");
    const std::string tensor_layout = string_field(params_json, "inputImageTensorLayout");
    const std::string tensor_range = string_field(params_json, "inputImageTensorRange");
    const size_t target_width = static_cast<size_t>(execution_contract.width);
    const size_t target_height = static_cast<size_t>(execution_contract.height);
    const size_t input_elements = target_width * target_height * 3U;
    const size_t latent_elements = (target_width / 8U) * (target_height / 8U) * 4U;
    const long long input_bytes = static_cast<long long>(input_elements * sizeof(float));
    const bool encoder_conditioned = task_mode == "img2img" || task_mode == "inpaint";
    if (!encoder_conditioned || (ultra_fix.enabled && task_mode != "img2img") ||
        (ultra_fix.enabled &&
            (ultra_fix.target_width != execution_contract.width ||
             ultra_fix.target_height != execution_contract.height ||
             ultra_fix.tile_size != 1024 ||
             ultra_fix.target_width < 1024 || ultra_fix.target_width > 2048 ||
             ultra_fix.target_height < 1024 || ultra_fix.target_height > 2048 ||
             ultra_fix.target_width % 64 != 0 ||
             ultra_fix.target_height % 64 != 0 ||
             long_field(params_json, "inputImageOrientedWidth") <= 0LL ||
             long_field(params_json, "inputImageOrientedHeight") <= 0LL)) ||
        input_image_path.empty() ||
        input_tensor_path.empty() || declared_tensor_path != input_tensor_path ||
        expected_image_sha256.size() != 64U || expected_tensor_sha256.size() != 64U ||
        long_field(params_json, "inputImageSizeBytes") <= 0LL ||
        preprocess != "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1" ||
        tensor_dtype != "float32-le" || tensor_layout != "NCHW" ||
        tensor_range != "NEGATIVE_ONE_TO_ONE" ||
        uint_array_field(params_json, "inputImageTensorShape") !=
            std::vector<uint32_t>{
                1U, 3U, static_cast<uint32_t>(target_height),
                static_cast<uint32_t>(target_width)} ||
        long_field(params_json, "inputImageTensorBytes") != input_bytes) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_input_contract_invalid",
            "INPUT_TENSOR_CONTRACT_INVALID",
            "SDXL VAE encoder requires one exact app-private RGB NCHW float32 tensor; "
            "UltraFix additionally requires a 1024-pixel graph tile and matching target canvas.");
    }
    std::vector<float> input_values;
    std::string actual_tensor_sha256;
    if (!read_float_binary_file(
            input_tensor_path,
            &input_values,
            &error,
            &actual_tensor_sha256) ||
        actual_tensor_sha256 != expected_tensor_sha256 ||
        input_values.size() != input_elements ||
        !std::all_of(input_values.begin(), input_values.end(), [](float value) {
            return std::isfinite(value) && value >= -1.000001f && value <= 1.000001f;
        })) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_input_tensor_changed",
            "INPUT_TENSOR_IDENTITY_MISMATCH",
            error.empty() ? "Prepared SDXL encoder tensor failed its native identity or range check." : error);
    }
    const std::string encoder_binary = string_field(
        params_json, "vaeEncoderContextBinary").empty()
        ? "vae_encoder.bin"
        : string_field(params_json, "vaeEncoderContextBinary");
    const std::string encoder_path = join_path(bundle_root, encoder_binary);
    const std::string expected_encoder_context_sha256 = normalized_contract_enum(
        expected_encoder_context_sha256_raw);
    const std::string params_encoder_context_sha256 = normalized_contract_enum(
        string_field(params_json, "vaeEncoderContextSha256"));
    const bool expected_encoder_context_sha256_valid =
        expected_encoder_context_sha256.size() == 64U &&
        std::all_of(
            expected_encoder_context_sha256.begin(),
            expected_encoder_context_sha256.end(),
            [](unsigned char value) {
                return (value >= '0' && value <= '9') ||
                    (value >= 'a' && value <= 'f');
            });
    if (!expected_encoder_context_sha256_valid ||
        params_encoder_context_sha256 != expected_encoder_context_sha256) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_context_identity_invalid",
            "ENCODER_CONTEXT_IDENTITY_INVALID",
            "VAE encoder context SHA-256 is missing or differs across the phase protocol and native params.");
    }
    if (!exists_file(encoder_path)) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"encoder_context_missing\",\"message\":\"QNN SDXL VAE encoder context is missing.\"}";
    }
    QnnImageGenerationScope generation(
        1,
        string_field(params_json, "progressJournalPath"));
    if (!generation.active()) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image phase is active in this process.\"}";
    }
    generation.record_stage(::mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(
        g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
    auto* encoder = new GraphT();
    if (!encoder->load(
            runtime,
            encoder_path,
            "model",
            &generation,
            false,
            true,
            expected_encoder_context_sha256,
            expected_profile)) {
        const bool identity_mismatch =
            encoder->message.find("Provider-bound SHA-256") != std::string::npos;
        return qnn_semantic_failure_json(
            identity_mismatch
                ? "sdxl_encoder_context_identity_mismatch"
                : "sdxl_encoder_load_failed",
            identity_mismatch
                ? "ENCODER_CONTEXT_IDENTITY_MISMATCH"
                : "ENCODER_LOAD_FAILED",
            encoder->message);
    }
    const std::string& actual_encoder_context_sha256 = encoder->context_binary_sha256;
    if (expected_profile > 0 &&
        encoder->selected_runtime.htp_arch_version != expected_profile) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"encoder_profile_mismatch\",\"message\":\"VAE encoder selected a different physical HTP transport.\"}";
    }
    const std::vector<uint32_t> expected_input_shape{1U, 3U, 1024U, 1024U};
    const std::vector<uint32_t> expected_output_shape{1U, 4U, 128U, 128U};
    const int input_index = tensor_index_by_name(encoder->inputs, {"input"});
    const int mean_index = tensor_index_by_name(encoder->outputs, {"mean"});
    const int std_index = tensor_index_by_name(encoder->outputs, {"std"});
    if (encoder->inputs.size() != 1U || encoder->outputs.size() != 2U ||
        input_index != 0 || mean_index < 0 || std_index < 0 || mean_index == std_index ||
        encoder->inputs[0].name != "input" ||
        encoder->outputs[static_cast<size_t>(mean_index)].name != "mean" ||
        encoder->outputs[static_cast<size_t>(std_index)].name != "std" ||
        qnn_tensor_data_type(encoder->inputs[0].tensor) != QNN_DATATYPE_FLOAT_32 ||
        qnn_tensor_data_type(encoder->outputs[static_cast<size_t>(mean_index)].tensor) != QNN_DATATYPE_FLOAT_32 ||
        qnn_tensor_data_type(encoder->outputs[static_cast<size_t>(std_index)].tensor) != QNN_DATATYPE_FLOAT_32 ||
        encoder->inputs[0].dimensions != expected_input_shape ||
        encoder->outputs[static_cast<size_t>(mean_index)].dimensions != expected_output_shape ||
        encoder->outputs[static_cast<size_t>(std_index)].dimensions != expected_output_shape) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"encoder_tensor_contract_mismatch\",\"message\":\"VAE encoder graph metadata differs from the inspected input/mean/std float32 contract.\"}";
    }
    ::mca::image::UltraFixTilePlan ultra_fix_plan;
    std::string ultra_fix_tile_plan_sha256;
    if (ultra_fix.enabled) {
        if (!::mca::image::build_ultrafix_tile_plan(
                encoder->inputs[0].dimensions,
                encoder->outputs[static_cast<size_t>(mean_index)].dimensions,
                {1U, 4U, 128U, 128U},
                {1U, 4U, 128U, 128U},
                {1U, 4U, 128U, 128U},
                {1U, 3U, 1024U, 1024U},
                target_width,
                target_height,
                static_cast<size_t>(ultra_fix.tile_size),
                ultra_fix.overlap,
                8U,
                &ultra_fix_plan,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_encoder_tile_plan_invalid",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                error);
        }
        const std::string descriptor =
            ::mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        ultra_fix_tile_plan_sha256 = ::mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(descriptor.data()), descriptor.size());
        if (!valid_sdxl_lower_sha256(ultra_fix_tile_plan_sha256)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_encoder_tile_plan_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix encoder tile plan lacks a SHA-256 identity.");
        }
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    generation.set_phase(kQnnImageDecoding);
    long long encoder_execute_ms = 0;
    size_t encoder_execution_count = 0U;
    std::vector<float> mean;
    std::vector<float> stddev;
    std::string ultra_fix_encoder_input_proof_sha256;
    std::string ultra_fix_encoder_mean_proof_sha256;
    std::string ultra_fix_encoder_std_proof_sha256;
    if (ultra_fix.enabled) {
        const ::mca::image::SpatialTensorShape source_shape{
            1U, 3U, target_height, target_width,
            ::mca::image::SpatialTensorLayout::Nchw};
        std::vector<std::vector<float>> mean_tiles;
        std::vector<std::vector<float>> std_tiles;
        std::string input_proofs;
        std::string mean_proofs;
        std::string std_proofs;
        mean_tiles.reserve(ultra_fix_plan.tiles.size());
        std_tiles.reserve(ultra_fix_plan.tiles.size());
        for (const auto& tile : ultra_fix_plan.tiles) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            std::vector<float> tile_input;
            if (!::mca::image::copy_spatial_tile(
                    input_values,
                    source_shape,
                    tile.pixel_x,
                    tile.pixel_y,
                    ultra_fix_plan.encoder_input,
                    &tile_input,
                    &error) ||
                !qnn_write_float_tensor(
                    &encoder->inputs[0], tile_input.data(), tile_input.size(), &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_encoder_input_bind_failed",
                    "QNN_ULTRAFIX_ENCODER_INPUT_BIND_FAILED",
                    error);
            }
            const std::string input_proof =
                ::mca::qnn::controlnet::sha256_hex_bytes(
                    encoder->inputs[0].buffer.data(), encoder->inputs[0].buffer.size());
            long long tile_execute_ms = 0;
            if (!valid_sdxl_lower_sha256(input_proof) || generation.cancelled() ||
                !encoder->execute(&tile_execute_ms, &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_encoder_execute_failed",
                    "QNN_ULTRAFIX_ENCODER_EXECUTION_FAILED",
                    error.empty()
                        ? "Split-SDXL UltraFix encoder tile execution lacks input proof."
                        : error);
            }
            encoder_execute_ms += tile_execute_ms;
            ++encoder_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            const auto& mean_binding = encoder->outputs[static_cast<size_t>(mean_index)];
            const auto& std_binding = encoder->outputs[static_cast<size_t>(std_index)];
            const std::string mean_proof = ::mca::qnn::controlnet::sha256_hex_bytes(
                mean_binding.buffer.data(), mean_binding.buffer.size());
            const std::string std_proof = ::mca::qnn::controlnet::sha256_hex_bytes(
                std_binding.buffer.data(), std_binding.buffer.size());
            std::vector<float> tile_mean;
            std::vector<float> tile_std;
            if (!valid_sdxl_lower_sha256(mean_proof) ||
                !valid_sdxl_lower_sha256(std_proof) ||
                !qnn_read_float_tensor(mean_binding, &tile_mean, &error) ||
                !qnn_read_float_tensor(std_binding, &tile_std, &error) ||
                tile_mean.size() != ultra_fix_plan.encoder_output.element_count() ||
                tile_std.size() != ultra_fix_plan.encoder_output.element_count()) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_encoder_output_invalid",
                    "QNN_ULTRAFIX_ENCODER_OUTPUT_INVALID",
                    error.empty()
                        ? "Split-SDXL UltraFix encoder tile returned invalid mean/std tensors."
                        : error);
            }
            input_proofs += input_proof + "|";
            mean_proofs += mean_proof + "|";
            std_proofs += std_proof + "|";
            mean_tiles.push_back(std::move(tile_mean));
            std_tiles.push_back(std::move(tile_std));
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!::mca::image::blend_ultrafix_encoder_tiles(
                ultra_fix_plan, mean_tiles, &mean, &error) ||
            !::mca::image::blend_ultrafix_encoder_tiles(
                ultra_fix_plan, std_tiles, &stddev, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_encoder_blend_failed",
                "QNN_ULTRAFIX_ENCODER_TILE_BLEND_FAILED",
                error);
        }
        const auto proof_sha256 = [](const std::string& proof) {
            return ::mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(proof.data()), proof.size());
        };
        ultra_fix_encoder_input_proof_sha256 = proof_sha256(input_proofs);
        ultra_fix_encoder_mean_proof_sha256 = proof_sha256(mean_proofs);
        ultra_fix_encoder_std_proof_sha256 = proof_sha256(std_proofs);
    } else {
        if (!qnn_write_float_tensor(
                &encoder->inputs[0], input_values.data(), input_values.size(), &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_input_bind_failed",
                "ENCODER_INPUT_BIND_FAILED",
                error);
        }
        if (!encoder->execute(&encoder_execute_ms, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_execute_failed",
                "ENCODER_EXECUTION_FAILED",
                error);
        }
        encoder_execution_count = 1U;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_read_float_tensor(
                encoder->outputs[static_cast<size_t>(mean_index)], &mean, &error) ||
            !qnn_read_float_tensor(
                encoder->outputs[static_cast<size_t>(std_index)], &stddev, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_output_read_failed",
                "ENCODER_OUTPUT_INVALID",
                error);
        }
    }
    if (mean.size() != latent_elements || stddev.size() != latent_elements) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_output_read_failed",
            "ENCODER_OUTPUT_INVALID",
            "VAE encoder outputs have an invalid full-latent element count.");
    }
    constexpr float kEncoderLatentScalingFactor = 0.13025f;
    std::seed_seq posterior_seed{
        execution_contract.seed,
        0x5344584cU,
        0x454e4331U,
    };
    std::mt19937 posterior_rng(posterior_seed);
    std::normal_distribution<float> posterior_normal(0.0f, 1.0f);
    std::vector<float> latents(latent_elements, 0.0f);
    for (size_t index = 0; index < latents.size(); ++index) {
        latents[index] =
            (mean[index] + stddev[index] * posterior_normal(posterior_rng)) *
            kEncoderLatentScalingFactor;
    }
    if (!std::all_of(latents.begin(), latents.end(), [](float value) {
            return std::isfinite(value);
        }) ||
        generation.cancelled()) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_posterior_invalid",
            "ENCODER_POSTERIOR_INVALID",
            "VAE encoder posterior sampling produced a non-finite or cancelled latent.");
    }
    if (!write_sdxl_latent_atomic(latent_path, latents, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_latent_publish_failed",
            "ENCODER_LATENT_PUBLISH_FAILED",
            error);
    }
    if (generation.cancelled()) {
        ::unlink(latent_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    std::string latent_sha256;
    if (!qnn_file_sha256(latent_path, &latent_sha256, &error)) {
        ::unlink(latent_path.c_str());
        return qnn_semantic_failure_json(
            "sdxl_encoder_latent_hash_failed",
            "ENCODER_LATENT_PUBLISH_FAILED",
            error);
    }
    if (generation.cancelled()) {
        ::unlink(latent_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    generation.set_step(1);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    const long long native_sequence = g_qnn_image_generation_sequence.load();
    const uint32_t native_stage_mask = g_qnn_image_generation_stage_mask.load();
    const uint64_t native_detail_stage_mask =
        g_qnn_image_generation_detail_stage_mask.load();
    const size_t source_width = static_cast<size_t>(std::max<long long>(
        0LL, long_field(params_json, "inputImageOrientedWidth")));
    const size_t source_height = static_cast<size_t>(std::max<long long>(
        0LL, long_field(params_json, "inputImageOrientedHeight")));
    size_t source_resized_width = target_width;
    size_t source_resized_height = target_height;
    if (ultra_fix.enabled && source_width > 0U && source_height > 0U) {
        if (static_cast<uint64_t>(target_width) * source_height >=
            static_cast<uint64_t>(target_height) * source_width) {
            source_resized_height = static_cast<size_t>(
                static_cast<uint64_t>(source_height) * target_width / source_width);
        } else {
            source_resized_width = static_cast<size_t>(
                static_cast<uint64_t>(source_width) * target_height / source_height);
        }
    }
    std::ostringstream out;
    out << std::setprecision(17)
        << "{\"ok\":true,\"phase\":\"encoder\",\"executionStage\":\"sdxl_encoder_phase_passed\","
        << "\"profileId\":" << quote(execution_contract.profile_id) << ","
        << "\"profileRevision\":" << execution_contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\",\"taskMode\":" << quote(task_mode) << ","
        << "\"processExitRequired\":true,\"contextReleased\":false,"
        << "\"runtimeProfile\":\"V" << encoder->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << encoder->selected_runtime.htp_arch_version << ","
        << "\"encoderContextLoadCount\":1,\"encoderExecutionCount\":"
        << encoder_execution_count << ","
        << "\"encoderGraphName\":" << quote(encoder->graph_name) << ","
        << "\"encoderContextSha256\":" << quote(actual_encoder_context_sha256) << ","
        << "\"encoderContextLoadMs\":" << encoder->context_load_ms << ","
        << "\"encoderExecuteMs\":" << encoder_execute_ms << ","
        << "\"encoderInputName\":\"input\",\"encoderMeanOutputName\":\"mean\",\"encoderStdOutputName\":\"std\","
        << "\"encoderInputDtype\":\"float32\",\"encoderMeanDtype\":\"float32\",\"encoderStdDtype\":\"float32\","
        << "\"encoderInputShape\":[1,3,1024,1024],"
        << "\"encoderMeanShape\":[1,4,128,128],\"encoderStdShape\":[1,4,128,128],"
        << "\"posteriorSampling\":\"mean_plus_std_times_normal_mt19937_domain_v1\","
        << "\"posteriorSampleCount\":" << latents.size() << ","
        << "\"encoderLatentScalingFactor\":" << kEncoderLatentScalingFactor << ","
        << "\"inputImagePath\":" << quote(input_image_path) << ","
        << "\"inputImageSha256\":" << quote(expected_image_sha256) << ","
        << "\"inputImageSizeBytes\":" << long_field(params_json, "inputImageSizeBytes") << ","
        << "\"inputImageSourceReadByNative\":false,"
        << "\"inputImageSourceValidation\":\"android_preprocess_provenance\","
        << "\"inputImageTensorPath\":" << quote(input_tensor_path) << ","
        << "\"inputImageTensorSha256\":" << quote(actual_tensor_sha256) << ","
        << "\"inputImageTensorBytes\":" << file_size_or_zero(input_tensor_path) << ","
        << "\"inputImageTensorShape\":[1,3," << target_height << ","
        << target_width << "],"
        << "\"inputImageTensorDtype\":\"float32-le\",\"inputImageTensorLayout\":\"NCHW\","
        << "\"inputImageTensorRange\":\"NEGATIVE_ONE_TO_ONE\","
        << "\"inputImagePreprocess\":" << quote(preprocess) << ","
        << "\"latentPath\":" << quote(latent_path) << ",\"latentDtype\":\"float32-le\","
        << "\"latentShape\":[1,4," << (target_height / 8U) << ","
        << (target_width / 8U) << "],\"latentElements\":" << latents.size() << ","
        << "\"latentBytes\":" << file_size_or_zero(latent_path) << ","
        << "\"latentSha256\":" << quote(latent_sha256) << ",";
    if (ultra_fix.enabled) {
        out << "\"ultraFixTilePlanSha256\":" << quote(ultra_fix_tile_plan_sha256) << ","
            << "\"ultraFixTileCount\":" << ultra_fix_plan.tiles.size() << ","
            << "\"ultraFixTileSize\":" << ultra_fix.tile_size << ","
            << "\"ultraFixOverlap\":" << ultra_fix.overlap << ","
            << "\"ultraFixEncoderGraphExecutionCount\":" << encoder_execution_count << ","
            << "\"ultraFixEncoderTileSuccessCount\":" << encoder_execution_count << ","
            << "\"ultraFixEncoderInputProofSha256\":"
            << quote(ultra_fix_encoder_input_proof_sha256) << ","
            << "\"ultraFixEncoderMeanProofSha256\":"
            << quote(ultra_fix_encoder_mean_proof_sha256) << ","
            << "\"ultraFixEncoderStdProofSha256\":"
            << quote(ultra_fix_encoder_std_proof_sha256) << ","
            << "\"ultraFixSourceWidth\":" << source_width << ","
            << "\"ultraFixSourceHeight\":" << source_height << ","
            << "\"ultraFixSourceResizedWidth\":" << source_resized_width << ","
            << "\"ultraFixSourceResizedHeight\":" << source_resized_height << ","
            << "\"ultraFixSourceCropLeft\":"
            << ((source_resized_width - target_width) / 2U) << ","
            << "\"ultraFixSourceCropTop\":"
            << ((source_resized_height - target_height) / 2U) << ",";
    }
    out
        << sdxl_disabled_preview_native_json_fields() << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMaskHex\":\""
        << ::mca::qnn::image_stage_mask_hex(native_detail_stage_mask) << "\","
        << "\"runtimeEvidence\":" << runtime_probe_json(encoder->selected_runtime) << "}";
    return out.str();
}

// end namespace


#endif // MCA_QNN_SDXL_ENCODER_PHASE_HPP
