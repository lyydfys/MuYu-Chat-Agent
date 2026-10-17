// mca/qnn/qnn_model_execution_img2img_encoder.cpp -- Shared img2img VAE encoder execution (whole function).
// (T2B17 / R7-C6 split).  Relocated byte-exact from mca/qnn/qnn_model_execution_pipeline.cpp (lines 796-1371).

#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"

#include <algorithm>
#include <cmath>
#include <random>
#include <utility>

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK


bool qnn_run_shared_img2img_encoder(
        const RuntimeProbe& runtime,
        const std::string& bundle_root,
        const std::string& params_json,
        const QnnSemanticExecutionContract& execution_contract,
        const QnnUltraFixRequest* ultra_fix,
        const QnnImageGenerationScope* generation,
        QnnSharedImg2ImgEncoderEvidence* evidence,
        std::string* failure_code,
        std::string* error) {
    if (evidence == nullptr || failure_code == nullptr || error == nullptr) return false;
    *evidence = QnnSharedImg2ImgEncoderEvidence{};
    *failure_code = "ENCODER_EXECUTION_FAILED";
    error->clear();

    const int width = execution_contract.width;
    const int height = execution_contract.height;
    const bool request_ultra_fix = ultra_fix != nullptr && ultra_fix->enabled;
    const bool request_inpaint = normalized_contract_enum(
        string_field(params_json, "taskMode")) == "inpaint";
    const std::string requested_inpaint_topology = normalized_contract_enum(
        string_field(params_json, "inpaintRequestedTopology"));
    const bool request_masked_image_latent = request_inpaint &&
        requested_inpaint_topology == "concatenated_latent_9";
    const std::string input_image_path = string_field(params_json, "inputImagePath");
    const std::string input_image_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageSha256"));
    const std::string input_tensor_path = string_field(params_json, "inputImageTensorPath");
    const std::string expected_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageTensorSha256"));
    const std::vector<uint32_t> declared_tensor_shape = uint_array_field(
        params_json, "inputImageTensorShape");
    const std::string masked_input_tensor_path = string_field(
        params_json, "maskedInputImageTensorPath");
    const std::string expected_masked_input_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "maskedInputImageTensorSha256"));
    const std::vector<uint32_t> declared_masked_input_shape = uint_array_field(
        params_json, "maskedInputImageTensorShape");
    const std::vector<uint32_t> expected_full_input_shape{
        1U,
        3U,
        static_cast<uint32_t>(height),
        static_cast<uint32_t>(width),
    };
    const std::vector<uint32_t> expected_full_output_shape{
        1U,
        4U,
        static_cast<uint32_t>(height / 8),
        static_cast<uint32_t>(width / 8),
    };
    const std::vector<uint32_t> expected_graph_input_shape = request_ultra_fix
        ? std::vector<uint32_t>{1U, 3U, 512U, 512U}
        : expected_full_input_shape;
    const std::vector<uint32_t> expected_graph_output_shape = request_ultra_fix
        ? std::vector<uint32_t>{1U, 4U, 64U, 64U}
        : expected_full_output_shape;
    const uint64_t expected_input_elements =
        static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 3U;
    const uint64_t expected_input_bytes = expected_input_elements * sizeof(float);
    const auto valid_sha256 = [](const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](unsigned char digit) {
                return (digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f');
            });
    };
    if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0 ||
        input_image_path.empty() || input_tensor_path.empty() ||
        !valid_sha256(input_image_sha256) || !valid_sha256(expected_tensor_sha256) ||
        declared_tensor_shape != expected_full_input_shape ||
        long_field(params_json, "inputImageSizeBytes") <= 0LL ||
        long_field(params_json, "inputImageTensorBytes") !=
            static_cast<long long>(expected_input_bytes) ||
        string_field(params_json, "inputImageTensorDtype") != "float32-le" ||
        string_field(params_json, "inputImageTensorLayout") != "NCHW" ||
        string_field(params_json, "inputImageTensorRange") != "NEGATIVE_ONE_TO_ONE" ||
        string_field(params_json, "inputImagePreprocess") !=
            "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1") {
        *failure_code = "INPUT_TENSOR_CONTRACT_INVALID";
        *error = "Shared QNN img2img requires the exact bounded RGB NCHW float32 preprocessing contract.";
        return false;
    }
    if (request_inpaint &&
        (requested_inpaint_topology != "latent_blend_4" &&
         requested_inpaint_topology != "concatenated_latent_9" &&
         requested_inpaint_topology != "separate_mask_input")) {
        *failure_code = "INPAINT_TOPOLOGY_CONTRACT_INVALID";
        *error = "Shared QNN inpaint requires one explicit topology derived from the loaded profile graph.";
        return false;
    }
    if (request_inpaint &&
        (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
         string_field(params_json, "inpaintMaskConvention") !=
            "white_repaint_black_preserve")) {
        *failure_code = "INPAINT_ARTIFACT_CONTRACT_INVALID";
        *error = "Shared QNN inpaint requires the versioned grayscale-mask preprocessing contract.";
        return false;
    }
    if (request_masked_image_latent &&
        (masked_input_tensor_path.empty() ||
         !valid_sha256(expected_masked_input_tensor_sha256) ||
         declared_masked_input_shape != expected_full_input_shape ||
         long_field(params_json, "maskedInputImageTensorBytes") !=
            static_cast<long long>(expected_input_bytes) ||
         string_field(params_json, "maskedInputImageTensorDtype") != "float32-le" ||
         string_field(params_json, "maskedInputImageTensorLayout") != "NCHW" ||
         string_field(params_json, "maskedInputImageTensorRange") != "NEGATIVE_ONE_TO_ONE" ||
         string_field(params_json, "maskedInputImageTensorPreprocess") !=
            "source_aligned_grayscale_masked_rgb_nchw_negative_one_to_one_v2")) {
        *failure_code = "INPAINT_MASKED_INPUT_CONTRACT_INVALID";
        *error = "Shared QNN inpaint requires the exact aligned masked RGB NCHW float32 preprocessing contract.";
        return false;
    }
    if (request_inpaint && !request_masked_image_latent &&
        (!masked_input_tensor_path.empty() ||
         !expected_masked_input_tensor_sha256.empty() ||
         !declared_masked_input_shape.empty() ||
         long_field(params_json, "maskedInputImageTensorBytes") != 0LL)) {
        *failure_code = "INPAINT_MASKED_INPUT_CONTRACT_INVALID";
        *error = "Only a concatenated nine-channel inpaint UNet may request a masked-image VAE latent.";
        return false;
    }

    std::vector<float> input_values;
    std::string actual_tensor_sha256;
    if (!read_float_binary_file(
            input_tensor_path,
            &input_values,
            error,
            &actual_tensor_sha256) ||
        actual_tensor_sha256 != expected_tensor_sha256 ||
        input_values.size() != static_cast<size_t>(expected_input_elements) ||
        !std::all_of(input_values.begin(), input_values.end(), [](float value) {
            return std::isfinite(value) && value >= -1.000001f && value <= 1.000001f;
        })) {
        *failure_code = "INPUT_TENSOR_IDENTITY_MISMATCH";
        if (error->empty()) {
            *error = "Prepared shared QNN encoder tensor changed or violates its declared range.";
        }
        return false;
    }
    std::vector<float> masked_input_values;
    if (request_masked_image_latent) {
        std::string actual_masked_tensor_sha256;
        if (!read_float_binary_file(
                masked_input_tensor_path,
                &masked_input_values,
                error,
                &actual_masked_tensor_sha256) ||
            actual_masked_tensor_sha256 != expected_masked_input_tensor_sha256 ||
            masked_input_values.size() != static_cast<size_t>(expected_input_elements) ||
            !std::all_of(masked_input_values.begin(), masked_input_values.end(), [](float value) {
                return std::isfinite(value) && value >= -1.000001f && value <= 1.000001f;
            })) {
            *failure_code = "INPAINT_MASKED_INPUT_IDENTITY_MISMATCH";
            if (error->empty()) {
                *error = "Prepared shared QNN masked RGB tensor changed or violates its declared range.";
            }
            return false;
        }
        evidence->masked_input_tensor_sha256 = actual_masked_tensor_sha256;
    }

    const std::string encoder_binary = string_field(params_json, "vaeEncoderContextBinary");
    const std::string encoder_graph_name = string_field(params_json, "vaeEncoderGraphName").empty()
        ? "model"
        : string_field(params_json, "vaeEncoderGraphName");
    const std::string expected_context_sha256 = normalized_contract_enum(
        string_field(params_json, "vaeEncoderContextSha256"));
    if (!is_safe_bundle_relative_path(encoder_binary) || encoder_graph_name.empty() ||
        !valid_sha256(expected_context_sha256)) {
        *failure_code = "ENCODER_CONTEXT_IDENTITY_INVALID";
        *error = "Shared QNN VAE encoder path, graph name, or context SHA-256 is invalid.";
        return false;
    }
    const std::string encoder_path = join_path(bundle_root, encoder_binary);
    if (!exists_file(encoder_path)) {
        *failure_code = "REQUIRED_GRAPH_MISSING";
        *error = "Shared QNN VAE encoder context is missing.";
        return false;
    }
    if (generation != nullptr && generation->cancelled()) {
        *failure_code = "CANCELLED";
        *error = "Image generation was cancelled before VAE encoding.";
        return false;
    }

    QnnExecutableGraph encoder;
    if (!encoder.load(
            runtime,
            encoder_path,
            encoder_graph_name,
            generation,
            false,
            true,
            expected_context_sha256,
            0)) {
        *failure_code = encoder.message.find("Provider-bound SHA-256") != std::string::npos
            ? "ENCODER_CONTEXT_IDENTITY_MISMATCH"
            : "ENCODER_LOAD_FAILED";
        *error = encoder.message;
        std::string release_error;
        if (!encoder.close_checked(&release_error)) {
            const std::string primary_error = *error;
            *failure_code = "ENCODER_RELEASE_FAILED";
            *error = release_error.empty()
                ? "Shared QNN VAE encoder resources did not release cleanly after load failure."
                : release_error;
            if (!primary_error.empty()) {
                *error = primary_error + " Cleanup also failed: " + *error;
            }
        }
        return false;
    }
    const auto fail_after_encoder_load = [&]() -> bool {
        std::string release_error;
        if (!encoder.close_checked(&release_error)) {
            const std::string primary_error = *error;
            *failure_code = "ENCODER_RELEASE_FAILED";
            *error = release_error.empty()
                ? "Shared QNN VAE encoder resources did not release cleanly."
                : release_error;
            if (!primary_error.empty()) {
                *error = primary_error + " Cleanup also failed: " + *error;
            }
        }
        return false;
    };
    if (encoder.graph_name != encoder_graph_name ||
        encoder.context_binary_sha256 != expected_context_sha256) {
        *failure_code = "ENCODER_CONTEXT_IDENTITY_MISMATCH";
        *error = "Loaded shared QNN encoder graph or mapped context identity differs from the request.";
        return fail_after_encoder_load();
    }

    auto exact_tensor_index = [](const std::vector<QnnTensorBinding>& tensors,
                                 const std::string& name) -> int {
        for (size_t index = 0; index < tensors.size(); ++index) {
            if (tensors[index].name == name) return static_cast<int>(index);
        }
        return -1;
    };
    const int input_index = exact_tensor_index(encoder.inputs, "input");
    const int mean_index = exact_tensor_index(encoder.outputs, "mean");
    const int std_index = exact_tensor_index(encoder.outputs, "std");
    if (encoder.inputs.size() != 1U || encoder.outputs.size() != 2U ||
        input_index != 0 || mean_index < 0 || std_index < 0 || mean_index == std_index ||
        encoder.inputs[0].dimensions != expected_graph_input_shape ||
        encoder.outputs[static_cast<size_t>(mean_index)].dimensions != expected_graph_output_shape ||
        encoder.outputs[static_cast<size_t>(std_index)].dimensions != expected_graph_output_shape ||
        qnn_smoke_storage_data_type(qnn_tensor_data_type(encoder.inputs[0].tensor)) != "uint16" ||
        qnn_smoke_storage_data_type(qnn_tensor_data_type(
            encoder.outputs[static_cast<size_t>(mean_index)].tensor)) != "uint16" ||
        qnn_smoke_storage_data_type(qnn_tensor_data_type(
            encoder.outputs[static_cast<size_t>(std_index)].tensor)) != "uint16" ||
        !qnn_has_supported_scale_offset_quantization(encoder.inputs[0].tensor) ||
        !qnn_has_supported_scale_offset_quantization(
            encoder.outputs[static_cast<size_t>(mean_index)].tensor) ||
        !qnn_has_supported_scale_offset_quantization(
            encoder.outputs[static_cast<size_t>(std_index)].tensor)) {
        *failure_code = "ENCODER_GRAPH_CONTRACT_INVALID";
        *error = "Shared QNN VAE encoder must expose exact uint16 input/mean/std tensors with finite positive per-tensor scale-offset quantization for the requested image size.";
        return fail_after_encoder_load();
    }
    const auto& mean_binding = encoder.outputs[static_cast<size_t>(mean_index)];
    const auto& std_binding = encoder.outputs[static_cast<size_t>(std_index)];
    evidence->encoder_input_dimensions = encoder.inputs[0].dimensions;
    evidence->encoder_output_dimensions = mean_binding.dimensions;
    if (request_ultra_fix) {
        if (request_inpaint || ultra_fix->target_width != width ||
            ultra_fix->target_height != height ||
            !mca::image::build_ultrafix_tile_plan(
                encoder.inputs[0].dimensions,
                mean_binding.dimensions,
                {1U, 4U, 64U, 64U},
                {1U, 4U, 64U, 64U},
                {1U, 4U, 64U, 64U},
                {1U, 3U, 512U, 512U},
                static_cast<size_t>(width),
                static_cast<size_t>(height),
                static_cast<size_t>(ultra_fix->tile_size),
                ultra_fix->overlap,
                8U,
                &evidence->ultra_fix_plan,
                error)) {
            *failure_code = "ULTRAFIX_TILE_PLAN_INVALID";
            if (error->empty()) {
                *error = "Shared QNN UltraFix encoder topology does not match its target tile plan.";
            }
            return fail_after_encoder_load();
        }
        evidence->ultra_fix = true;
    }
    const size_t expected_latent_elements = static_cast<size_t>(
        static_cast<uint64_t>(width / 8) * static_cast<uint64_t>(height / 8) * 4U);
    constexpr double kSd15EncoderLatentScalingFactor = 0.18215;
    if (execution_contract.vae_scaling_location != QnnVaeScalingLocation::HostBeforeGraph ||
        !std::isfinite(execution_contract.vae_scaling_factor) ||
        std::fabs(execution_contract.vae_scaling_factor -
            kSd15EncoderLatentScalingFactor) > 1.0e-9) {
        *failure_code = "ENCODER_SCALING_CONTRACT_INVALID";
        *error = "Shared QNN SD1.x img2img requires host VAE scaling factor 0.18215.";
        return fail_after_encoder_load();
    }
    std::seed_seq posterior_seed{
        execution_contract.seed,
        0x53443135U,
        0x454e4331U,
    };
    std::mt19937 posterior_rng(posterior_seed);
    std::normal_distribution<float> posterior_normal(0.0f, 1.0f);
    const auto encode_once = [&](const std::vector<float>& values,
                                 bool masked_input,
                                 std::vector<float>* latents,
                                 std::string* input_buffer_sha256,
                                 std::string* mean_buffer_sha256,
                                 std::string* std_buffer_sha256,
                                 std::string* latent_sha256,
                                 long long* execute_ms) -> bool {
        const std::string label = masked_input ? "masked-image" : "source-image";
        const auto set_failure = [&](const char* source_code, const char* masked_code) {
            *failure_code = masked_input ? masked_code : source_code;
        };
        if (generation != nullptr && generation->cancelled()) {
            *failure_code = "CANCELLED";
            *error = "Image generation was cancelled before the " + label + " VAE encode.";
            return false;
        }
        std::vector<float> mean;
        std::vector<float> stddev;
        if (request_ultra_fix && !masked_input) {
            const mca::image::SpatialTensorShape source_shape{
                1U,
                3U,
                static_cast<size_t>(height),
                static_cast<size_t>(width),
                mca::image::SpatialTensorLayout::Nchw,
            };
            std::vector<std::vector<float>> mean_tiles;
            std::vector<std::vector<float>> std_tiles;
            mean_tiles.reserve(evidence->ultra_fix_plan.tiles.size());
            std_tiles.reserve(evidence->ultra_fix_plan.tiles.size());
            std::string input_proofs;
            std::string mean_proofs;
            std::string std_proofs;
            *execute_ms = 0;
            for (const auto& tile : evidence->ultra_fix_plan.tiles) {
                if (generation != nullptr && generation->cancelled()) {
                    *failure_code = "CANCELLED";
                    *error = "Image generation was cancelled during tiled UltraFix VAE encoding.";
                    return false;
                }
                std::vector<float> tile_input;
                if (!mca::image::copy_spatial_tile(
                        values,
                        source_shape,
                        tile.pixel_x,
                        tile.pixel_y,
                        evidence->ultra_fix_plan.encoder_input,
                        &tile_input,
                        error) ||
                    !qnn_write_float_tensor(
                        &encoder.inputs[0], tile_input.data(), tile_input.size(), error)) {
                    *failure_code = "ULTRAFIX_ENCODER_INPUT_BIND_FAILED";
                    return false;
                }
                const std::string input_proof =
                    mca::qnn::controlnet::sha256_hex_bytes(
                        encoder.inputs[0].buffer.data(), encoder.inputs[0].buffer.size());
                if (!valid_sha256(input_proof)) {
                    *failure_code = "ULTRAFIX_ENCODER_INPUT_EVIDENCE_INVALID";
                    *error = "An UltraFix encoder tile lacks a SHA-256 input proof.";
                    return false;
                }
                long long tile_execute_ms = 0;
                if (generation != nullptr && generation->cancelled()) {
                    *failure_code = "CANCELLED";
                    *error = "Image generation was cancelled before an UltraFix encoder tile execution.";
                    return false;
                }
                if (!encoder.execute(&tile_execute_ms, error)) {
                    *failure_code = "ULTRAFIX_ENCODER_EXECUTION_FAILED";
                    return false;
                }
                *execute_ms += tile_execute_ms;
                if (generation != nullptr && generation->cancelled()) {
                    *failure_code = "CANCELLED";
                    *error = "Image generation was cancelled after an UltraFix encoder tile execution.";
                    return false;
                }
                const std::string mean_proof =
                    mca::qnn::controlnet::sha256_hex_bytes(
                        mean_binding.buffer.data(), mean_binding.buffer.size());
                const std::string std_proof =
                    mca::qnn::controlnet::sha256_hex_bytes(
                        std_binding.buffer.data(), std_binding.buffer.size());
                std::vector<float> tile_mean;
                std::vector<float> tile_std;
                if (!valid_sha256(mean_proof) || !valid_sha256(std_proof) ||
                    !qnn_read_float_tensor(mean_binding, &tile_mean, error) ||
                    !qnn_read_float_tensor(std_binding, &tile_std, error) ||
                    tile_mean.size() != evidence->ultra_fix_plan.encoder_output.element_count() ||
                    tile_std.size() != evidence->ultra_fix_plan.encoder_output.element_count()) {
                    *failure_code = "ULTRAFIX_ENCODER_OUTPUT_INVALID";
                    if (error->empty()) {
                        *error = "An UltraFix encoder tile returned invalid mean/std tensors.";
                    }
                    return false;
                }
                input_proofs += input_proof;
                input_proofs.push_back('|');
                mean_proofs += mean_proof;
                mean_proofs.push_back('|');
                std_proofs += std_proof;
                std_proofs.push_back('|');
                mean_tiles.push_back(std::move(tile_mean));
                std_tiles.push_back(std::move(tile_std));
            }
            if (generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled before UltraFix encoder tile blending.";
                return false;
            }
            if (!mca::image::blend_ultrafix_encoder_tiles(
                    evidence->ultra_fix_plan, mean_tiles, &mean, error) ||
                !mca::image::blend_ultrafix_encoder_tiles(
                    evidence->ultra_fix_plan, std_tiles, &stddev, error)) {
                *failure_code = "ULTRAFIX_ENCODER_TILE_BLEND_FAILED";
                return false;
            }
            const auto digest_text = [](const std::string& proofs) {
                return mca::qnn::controlnet::sha256_hex_bytes(
                    reinterpret_cast<const uint8_t*>(proofs.data()), proofs.size());
            };
            *input_buffer_sha256 = digest_text(input_proofs);
            *mean_buffer_sha256 = digest_text(mean_proofs);
            *std_buffer_sha256 = digest_text(std_proofs);
        } else {
            if (!qnn_write_float_tensor(
                    &encoder.inputs[0], values.data(), values.size(), error)) {
                set_failure("ENCODER_INPUT_BIND_FAILED", "INPAINT_MASKED_ENCODER_INPUT_BIND_FAILED");
                return false;
            }
            *input_buffer_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                encoder.inputs[0].buffer.data(), encoder.inputs[0].buffer.size());
            if (!valid_sha256(*input_buffer_sha256)) {
                set_failure("ENCODER_INPUT_EVIDENCE_INVALID", "INPAINT_MASKED_ENCODER_INPUT_EVIDENCE_INVALID");
                *error = "Shared QNN " + label + " encoder input lacks a SHA-256 execution proof.";
                return false;
            }
            if (generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled before the " + label + " VAE graph execution.";
                return false;
            }
            if (!encoder.execute(execute_ms, error)) {
                set_failure("ENCODER_EXECUTION_FAILED", "INPAINT_MASKED_ENCODER_EXECUTION_FAILED");
                return false;
            }
            if (generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled after the " + label + " VAE graph execution.";
                return false;
            }
            *mean_buffer_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                mean_binding.buffer.data(), mean_binding.buffer.size());
            *std_buffer_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                std_binding.buffer.data(), std_binding.buffer.size());
            if (!valid_sha256(*mean_buffer_sha256) || !valid_sha256(*std_buffer_sha256) ||
                !qnn_read_float_tensor(mean_binding, &mean, error) ||
                !qnn_read_float_tensor(std_binding, &stddev, error)) {
                set_failure("ENCODER_OUTPUT_INVALID", "INPAINT_MASKED_ENCODER_OUTPUT_INVALID");
                if (error->empty()) {
                    *error = "Shared QNN " + label + " encoder mean/std outputs are invalid.";
                }
                return false;
            }
        }
        if (!valid_sha256(*input_buffer_sha256) ||
            !valid_sha256(*mean_buffer_sha256) || !valid_sha256(*std_buffer_sha256) ||
            mean.size() != expected_latent_elements || stddev.size() != expected_latent_elements ||
            !std::all_of(mean.begin(), mean.end(), [](float value) { return std::isfinite(value); }) ||
            !std::all_of(stddev.begin(), stddev.end(), [](float value) { return std::isfinite(value); })) {
            set_failure("ENCODER_OUTPUT_INVALID", "INPAINT_MASKED_ENCODER_OUTPUT_INVALID");
            if (error->empty()) *error = "Shared QNN " + label + " encoder mean/std outputs are invalid.";
            return false;
        }
        latents->assign(expected_latent_elements, 0.0f);
        for (size_t index = 0; index < latents->size(); ++index) {
            if ((index % 4096U) == 0U && generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled during " + label + " posterior sampling.";
                return false;
            }
            (*latents)[index] = static_cast<float>(
                (mean[index] + stddev[index] * posterior_normal(posterior_rng)) *
                kSd15EncoderLatentScalingFactor);
        }
        if (!std::all_of(latents->begin(), latents->end(), [](float value) {
                return std::isfinite(value);
            })) {
            set_failure("ENCODER_POSTERIOR_INVALID", "INPAINT_MASKED_ENCODER_POSTERIOR_INVALID");
            *error = "Shared QNN " + label + " encoder posterior contains non-finite values.";
            return false;
        }
        *latent_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(latents->data()),
            latents->size() * sizeof(float));
        if (!valid_sha256(*latent_sha256)) {
            set_failure("ENCODER_POSTERIOR_INVALID", "INPAINT_MASKED_ENCODER_POSTERIOR_INVALID");
            *error = "Shared QNN " + label + " encoder posterior lacks a SHA-256 proof.";
            return false;
        }
        return true;
    };
    if (!encode_once(
            input_values,
            false,
            &evidence->latents,
            &evidence->input_buffer_sha256,
            &evidence->mean_buffer_sha256,
            &evidence->std_buffer_sha256,
            &evidence->latent_sha256,
            &evidence->execute_ms)) {
        return fail_after_encoder_load();
    }
    if (request_inpaint) evidence->source_pixels_nchw = input_values;
    evidence->execution_count = request_ultra_fix
        ? evidence->ultra_fix_plan.tiles.size()
        : 1U;
    if (request_masked_image_latent) {
        if (!encode_once(
                masked_input_values,
                true,
                &evidence->masked_latents,
                &evidence->masked_input_buffer_sha256,
                &evidence->masked_mean_buffer_sha256,
                &evidence->masked_std_buffer_sha256,
                &evidence->masked_latent_sha256,
                &evidence->masked_execute_ms)) {
            return fail_after_encoder_load();
        }
        evidence->execution_count = 2U;
        evidence->masked_execution_count = 1U;
    }

    evidence->context_sha256 = encoder.context_binary_sha256;
    evidence->graph_name = encoder.graph_name;
    evidence->input_name = encoder.inputs[0].name;
    evidence->mean_name = mean_binding.name;
    evidence->std_name = std_binding.name;
    evidence->input_dtype = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(encoder.inputs[0].tensor));
    evidence->mean_dtype = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(mean_binding.tensor));
    evidence->std_dtype = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(std_binding.tensor));
    evidence->context_load_ms = encoder.context_load_ms;
    std::string release_error;
    if (!encoder.close_checked(&release_error)) {
        *failure_code = "ENCODER_RELEASE_FAILED";
        *error = release_error.empty()
            ? "Shared QNN VAE encoder resources did not release cleanly."
            : release_error;
        return false;
    }
    evidence->context_released_before_shared_session = true;
    if (generation != nullptr && generation->cancelled()) {
        *failure_code = "CANCELLED";
        *error = "Image generation was cancelled after releasing the VAE encoder context.";
        return false;
    }
    failure_code->clear();
    error->clear();
    return true;
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
