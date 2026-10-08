#ifndef MCA_QNN_SDXL_DECODER_PHASE_HPP
#define MCA_QNN_SDXL_DECODER_PHASE_HPP

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "image_execution_math.hpp"
#include "qnn_controlnet_execution.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"

#if __has_include("mca/qnn/qnn_sdxl_tiler_common.hpp")
#include "mca/qnn/qnn_sdxl_tiler_common.hpp"
#elif __has_include(<mca/qnn/qnn_sdxl_tiler_common.hpp>)
#include <mca/qnn/qnn_sdxl_tiler_common.hpp>
#endif

// namespace mca::qnn removed to prevent anonymous namespace shadowing

template <typename GraphT>
inline std::string execute_sdxl_vae_phase(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& latent_path,
        const std::string& output_path) {
    std::string error;
    if (!::mca::qnn::validate_sdxl_no_preview_transport(params_json, &error)) {
        return qnn_semantic_failure_json(
            "preview_transport_unsupported",
            "UNSUPPORTED_PREVIEW_TRANSPORT",
            error);
    }
    const auto started = std::chrono::steady_clock::now();
    const RuntimeProbe runtime = inspect_sdxl_exact_runtime_profile(
        runtime_profile_json, true);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    const std::string vae_binary = string_field(params_json, "vaeDecoderContextBinary").empty()
        ? "vae_decoder.bin"
        : string_field(params_json, "vaeDecoderContextBinary");
    const std::string vae_path = join_path(bundle_root, vae_binary);
    if (!exists_file(vae_path)) {
        return "{\"ok\":false,\"executionStage\":\"vae_context_missing\",\"message\":\"QNN SDXL VAE context is missing.\"}";
    }
    std::vector<float> latents;
    if (!read_float_binary_file(latent_path, &latents, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"latent_read_failed\",\"message\":") +
            quote(error) + "}";
    }
    QnnSemanticExecutionContract execution_contract;
    if (!parse_qnn_semantic_execution_contract(
            params_json,
            &execution_contract,
            &error)) {
        return qnn_semantic_failure_json(
            "execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    if (!::mca::qnn::validate_sdxl_phase_execution_contract(execution_contract, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_execution_contract_invalid",
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
    const bool inpaint = task_mode == "inpaint";
    ::mca::qnn::SdxlInpaintPixelArtifacts inpaint_artifacts;
    if (inpaint && !::mca::qnn::load_sdxl_inpaint_pixel_artifacts(
            params_json,
            execution_contract,
            &inpaint_artifacts,
            &error)) {
        return qnn_semantic_failure_json(
            "sdxl_inpaint_pixel_artifact_invalid",
            "QNN_INPAINT_PIXEL_ARTIFACT_INVALID",
            error);
    }
    if ((ultra_fix.enabled &&
            (task_mode != "img2img" ||
             ultra_fix.target_width != execution_contract.width ||
             ultra_fix.target_height != execution_contract.height ||
             ultra_fix.tile_size != 1024 ||
             ultra_fix.target_width < 1024 || ultra_fix.target_width > 2048 ||
             ultra_fix.target_height < 1024 || ultra_fix.target_height > 2048 ||
             ultra_fix.target_width % 64 != 0 ||
             ultra_fix.target_height % 64 != 0)) ||
        (!inpaint && task_mode != "text_to_image" && task_mode != "img2img")) {
        return qnn_semantic_failure_json(
            "sdxl_task_mode_unsupported",
            "TASK_MODE_UNSUPPORTED",
            "Split-worker SDXL VAE received an unsupported task mode.");
    }
    const std::string& graph_name = execution_contract.graph_name;
    QnnImageGenerationScope generation(1, string_field(params_json, "progressJournalPath"));
    if (!generation.active()) {
        return "{\"ok\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image phase is active in this process.\"}";
    }
    generation.record_stage(::mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
    auto* vae = new GraphT();
    if (!vae->load(
            runtime,
            vae_path,
            graph_name,
            &generation,
            true,
            false,
            "",
            expected_profile)) {
        return std::string("{\"ok\":false,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(vae->message) + "}";
    }
    if (expected_profile > 0 && vae->selected_runtime.htp_arch_version != expected_profile) {
        std::ostringstream message;
        message << "VAE selected HTP V" << vae->selected_runtime.htp_arch_version
                << " but phase requires V" << expected_profile << ".";
        return std::string("{\"ok\":false,\"executionStage\":\"vae_profile_mismatch\",\"message\":") +
            quote(message.str()) + "}";
    }
    if (vae->inputs.empty() || vae->outputs.empty()) {
        return "{\"ok\":false,\"executionStage\":\"vae_tensor_layout_unsupported\",\"message\":\"QNN SDXL VAE tensor layout is unsupported.\"}";
    }
    if (execution_contract.width <= 0 || execution_contract.height <= 0 ||
        execution_contract.width % 8 != 0 || execution_contract.height % 8 != 0) {
        return "{\"ok\":false,\"executionStage\":\"latent_shape_mismatch\",\"message\":\"SDXL output dimensions must define an integer 8x latent shape.\"}";
    }
    generation.set_phase(kQnnImageDecoding);
    const double effective_vae_host_scale = qnn_effective_vae_host_scale(execution_contract);
    const std::vector<uint32_t> source_latent_dimensions{
        1U,
        4U,
        static_cast<uint32_t>(execution_contract.height / 8),
        static_cast<uint32_t>(execution_contract.width / 8),
    };
    QnnVaeDecodeResult vae_decode;
    ::mca::image::UltraFixTilePlan ultra_fix_plan;
    std::string ultra_fix_tile_plan_sha256;
    bool decoded = false;
    if (ultra_fix.enabled) {
        if (!::mca::image::build_ultrafix_tile_plan(
                {1U, 3U, 1024U, 1024U},
                {1U, 4U, 128U, 128U},
                {1U, 4U, 128U, 128U},
                {1U, 4U, 128U, 128U},
                vae->inputs[0].dimensions,
                vae->outputs[0].dimensions,
                static_cast<size_t>(execution_contract.width),
                static_cast<size_t>(execution_contract.height),
                static_cast<size_t>(ultra_fix.tile_size),
                ultra_fix.overlap,
                8U,
                &ultra_fix_plan,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_vae_tile_plan_invalid",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                error);
        }
        const std::string descriptor =
            ::mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        ultra_fix_tile_plan_sha256 = ::mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(descriptor.data()), descriptor.size());
        if (!::mca::qnn::valid_sdxl_lower_sha256(ultra_fix_tile_plan_sha256) ||
            ultra_fix_tile_plan_sha256 != normalized_contract_enum(
                string_field(params_json, "ultraFixTilePlanSha256")) ||
            long_field(params_json, "ultraFixTileCount") !=
                static_cast<long long>(ultra_fix_plan.tiles.size())) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_vae_tile_plan_identity_mismatch",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                "Split-SDXL UltraFix VAE phase does not share the encoder/UNet tile plan.");
        }
        QnnUltraFixVaeDecodeResult ultra_fix_decode;
        decoded = qnn_decode_ultrafix_vae_latents(
            *vae,
            ultra_fix_plan,
            latents,
            execution_contract,
            &ultra_fix_decode,
            &error);
        if (decoded) {
            vae_decode.pixels_nchw = std::move(ultra_fix_decode.pixels_nchw);
            vae_decode.execution_count = ultra_fix_decode.execution_count;
            vae_decode.execute_ms_total = ultra_fix_decode.execute_ms_total;
            vae_decode.plan.source_latent = ultra_fix_plan.full_latent;
            vae_decode.plan.vae_input = ultra_fix_plan.vae_input;
            vae_decode.plan.vae_output = ultra_fix_plan.vae_output;
            vae_decode.plan.final_output = ultra_fix_plan.target_pixels;
            vae_decode.plan.spatial_scale = ultra_fix_plan.spatial_scale;
            vae_decode.plan.tiles.resize(ultra_fix_plan.tiles.size());
        }
    } else {
        decoded = qnn_decode_vae_latents(
            *vae,
            source_latent_dimensions,
            latents,
            execution_contract,
            &vae_decode,
            &error);
    }
    if (!decoded) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_vae_decode_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    generation.record_stage(::mca::qnn::ImageStage::PngWrite, kQnnImagePngWrite);
    const std::string temporary_output = output_path + ".part";
    ::unlink(temporary_output.c_str());
    int width = 0;
    int height = 0;
    ::mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
    QnnTensorBinding final_output_binding;
    final_output_binding.name = "sdxl_tiled_vae_output_nchw";
    final_output_binding.dimensions = {
        1U,
        3U,
        static_cast<uint32_t>(vae_decode.plan.final_output.height),
        static_cast<uint32_t>(vae_decode.plan.final_output.width),
    };
    std::vector<float> final_pixels = vae_decode.pixels_nchw;
    int inpaint_pixel_blend_levels = 0;
    uint64_t inpaint_pixel_blend_checksum = 0U;
    bool inpaint_pixel_blend_applied = false;
    if (inpaint) {
        std::vector<float> blended_pixels;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_laplacian_blend_inpaint_vae_output(
                final_output_binding,
                final_pixels,
                execution_contract.pixel_range,
                inpaint_artifacts.source_nchw,
                inpaint_artifacts.full_mask,
                &blended_pixels,
                &inpaint_pixel_blend_levels,
                &inpaint_pixel_blend_checksum,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_inpaint_pixel_blend_failed",
                "QNN_INPAINT_PIXEL_BLEND_FAILED",
                error);
        }
        final_pixels = std::move(blended_pixels);
        inpaint_pixel_blend_applied = true;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    if (!write_vae_tensor_png(
            final_output_binding,
            final_pixels,
            temporary_output,
            execution_contract.pixel_range,
            &pixel_range_evidence,
            &width,
            &height,
            &error)) {
        ::unlink(temporary_output.c_str());
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(temporary_output.c_str());
        return qnn_image_generation_cancelled_json();
    }
    if (width != execution_contract.width || height != execution_contract.height) {
        ::unlink(temporary_output.c_str());
        return "{\"ok\":false,\"executionStage\":\"sdxl_output_resolution_mismatch\",\"message\":\"Decoded SDXL image dimensions do not match the requested execution contract.\"}";
    }
    if (!::mca::qnn::fsync_sdxl_artifact(temporary_output, &error)) {
        ::unlink(temporary_output.c_str());
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_sync_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(temporary_output.c_str());
        return qnn_image_generation_cancelled_json();
    }
    if (::rename(temporary_output.c_str(), output_path.c_str()) != 0) {
        ::unlink(temporary_output.c_str());
        return "{\"ok\":false,\"executionStage\":\"sdxl_png_publish_failed\",\"message\":\"Unable to atomically publish SDXL PNG.\"}";
    }
    const std::string output_parent = ::mca::qnn::sdxl_parent_directory(output_path);
    if (output_parent.empty() || !fsync_directory(output_parent, &error)) {
        ::unlink(output_path.c_str());
        if (!output_parent.empty()) {
            std::string ignored;
            fsync_directory(output_parent, &ignored);
        }
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_directory_sync_failed\",\"message\":") +
            quote(error.empty() ? "Unable to durably publish the SDXL PNG directory entry." : error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(output_path.c_str());
        std::string ignored;
        fsync_directory(output_parent, &ignored);
        return qnn_image_generation_cancelled_json();
    }
    std::string output_sha256;
    if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
        ::unlink(output_path.c_str());
        std::string ignored;
        fsync_directory(output_parent, &ignored);
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_hash_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(output_path.c_str());
        std::string ignored;
        fsync_directory(output_parent, &ignored);
        return qnn_image_generation_cancelled_json();
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    const long long native_sequence = g_qnn_image_generation_sequence.load();
    const uint32_t native_stage_mask = g_qnn_image_generation_stage_mask.load();
    const uint64_t native_detail_stage_mask =
        g_qnn_image_generation_detail_stage_mask.load();
    std::ostringstream out;
    out << std::setprecision(17)
        << "{\"ok\":true,\"phase\":\"vae\",\"executionStage\":\"sdxl_vae_phase_passed\","
        << "\"runtime\":\"QNN_HTP\","
        << "\"processExitRequired\":true,\"runtimeProfile\":\"V" << vae->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << vae->selected_runtime.htp_arch_version << ","
        << "\"outputPath\":" << quote(output_path) << ",\"mimeType\":\"image/png\","
        << "\"width\":" << width << ",\"height\":" << height << ","
        << "\"outputBytes\":" << file_size_or_zero(output_path) << ","
        << "\"outputSha256\":" << quote(output_sha256) << ","
        << "\"outputAtomicCommit\":true,"
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(execution_contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << execution_contract.vae_scaling_factor << ","
        << qnn_pixel_range_evidence_json(
            execution_contract.pixel_range,
            pixel_range_evidence) << ","
        << "\"effectiveVaeHostScale\":" << effective_vae_host_scale << ","
        << "\"vaeExecutionCount\":" << vae_decode.execution_count << ","
        << "\"vaeTileCount\":" << vae_decode.plan.tiles.size() << ","
        << "\"vaeTiled\":" << (vae_decode.plan.tiled() ? "true" : "false") << ","
        << "\"vaeContextLoadCount\":1,"
        << "\"vaeGraphName\":" << quote(vae->graph_name) << ","
        << "\"vaeSourceLatentShape\":[1,4,"
        << vae_decode.plan.source_latent.height << ","
        << vae_decode.plan.source_latent.width << "],"
        << "\"vaeInputLatentShape\":[1,4,"
        << vae_decode.plan.vae_input.height << ","
        << vae_decode.plan.vae_input.width << "],"
        << "\"vaeOutputTileShape\":[1,3,"
        << vae_decode.plan.vae_output.height << ","
        << vae_decode.plan.vae_output.width << "],"
        << "\"vaeFinalOutputShape\":[1,3,"
        << vae_decode.plan.final_output.height << ","
        << vae_decode.plan.final_output.width << "],"
        << "\"vaeDecodeSpatialScale\":" << vae_decode.plan.spatial_scale << ","
        << "\"vaeContextLoadMs\":" << vae->context_load_ms << ","
        << "\"vaeExecuteMs\":" << vae_decode.execute_ms_total << ","
        << "\"pixelChecksum\":" << checksum_float_vector(final_pixels) << ","
        << "\"taskMode\":" << quote(task_mode) << ","
        << "\"inputImageTensorPath\":"
        << quote(inpaint ? string_field(params_json, "inputImageTensorPath") : "") << ","
        << "\"inputImageTensorSha256\":"
        << quote(inpaint ? normalized_contract_enum(inpaint_artifacts.source_tensor_sha256) : "") << ","
        << "\"inputImageTensorBytes\":"
        << (inpaint ? file_size_or_zero(string_field(params_json, "inputImageTensorPath")) : 0LL) << ","
        << "\"maskImageFullTensorPath\":"
        << quote(inpaint ? string_field(params_json, "maskImageFullTensorPath") : "") << ","
        << "\"maskImageFullTensorSha256\":"
        << quote(inpaint ? normalized_contract_enum(inpaint_artifacts.full_mask_tensor_sha256) : "") << ","
        << "\"maskImageFullTensorBytes\":"
        << (inpaint ? file_size_or_zero(string_field(params_json, "maskImageFullTensorPath")) : 0LL) << ","
        << "\"maskImageFullTensorShape\":[1,1,"
        << (inpaint ? execution_contract.height : 0) << ","
        << (inpaint ? execution_contract.width : 0) << "],"
        << "\"maskImageFullTensorDtype\":"
        << quote(inpaint ? string_field(params_json, "maskImageFullTensorDtype") : "") << ","
        << "\"maskImageFullTensorLayout\":"
        << quote(inpaint ? string_field(params_json, "maskImageFullTensorLayout") : "") << ","
        << "\"maskImageFullTensorRange\":"
        << quote(inpaint ? string_field(params_json, "maskImageFullTensorRange") : "") << ","
        << "\"maskImageFullTensorPreprocess\":"
        << quote(inpaint ? string_field(params_json, "maskImageFullTensorPreprocess") : "") << ","
        << "\"inpaintFinalMode\":"
        << quote(inpaint
            ? "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend"
            : "none") << ","
        << "\"inpaintPixelBlendLevels\":" << inpaint_pixel_blend_levels << ","
        << "\"inpaintPixelBlendChecksum\":\""
        << fixed_width_lower_hex_u64(inpaint_pixel_blend_checksum) << "\","
        << "\"inpaintPixelBlendApplied\":"
        << (inpaint_pixel_blend_applied ? "true" : "false") << ",";
    if (ultra_fix.enabled) {
        out << "\"ultraFixTilePlanSha256\":" << quote(ultra_fix_tile_plan_sha256) << ","
            << "\"ultraFixTileCount\":" << ultra_fix_plan.tiles.size() << ","
            << "\"ultraFixDecoderGraphExecutionCount\":"
            << vae_decode.execution_count << ","
            << "\"ultraFixDecoderTileSuccessCount\":"
            << vae_decode.execution_count << ","
            << "\"ultraFixOutputSha256\":" << quote(output_sha256) << ","
            << "\"ultraFixOutputBytes\":" << file_size_or_zero(output_path) << ","
            << "\"ultraFixOutputAtomicCommit\":true,";
    }
    out
        << ::mca::qnn::sdxl_disabled_preview_native_json_fields() << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMaskHex\":\""
        << ::mca::qnn::image_stage_mask_hex(native_detail_stage_mask) << "\","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(vae->selected_runtime) << "}";
    return out.str();
}

#endif // MCA_QNN_SDXL_DECODER_PHASE_HPP
