#pragma once

// This file is included from qnn_native_bridge.cpp after the typed QNN graph
// helpers are defined.  Each entry point intentionally leaks its one runtime
// graph: the Android phase worker is disposable and exits immediately after
// publishing its artifact, avoiding the RouterFastRPC unload hang observed
// when large SDXL UNet and VAE contexts were switched inside one process.

#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"
#include "mca/qnn/qnn_sdxl_encoder_phase.hpp"
#include "mca/qnn/qnn_sdxl_decoder_phase.hpp"

using ::mca::qnn::SdxlInpaintPixelArtifacts;
using ::mca::qnn::write_sdxl_latent_atomic;
using ::mca::qnn::fsync_sdxl_artifact;
using ::mca::qnn::valid_sdxl_lower_sha256;
using ::mca::qnn::is_safe_ascii_diffusion_prompt_pair;
using ::mca::qnn::validate_sdxl_phase_execution_contract;
using ::mca::qnn::load_sdxl_inpaint_pixel_artifacts;
using ::mca::qnn::validate_sdxl_no_preview_transport;
using ::mca::qnn::sdxl_parent_directory;
using ::mca::qnn::sdxl_disabled_preview_native_json_fields;
using ::mca::qnn::run_sdxl_ultrafix_tiled_unet_branch;

std::string qnn_sdxl_encoder_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& input_tensor_path,
        const std::string& latent_path,
        const std::string& expected_encoder_context_sha256_raw) {
    // contract: UNSUPPORTED_PREVIEW_TRANSPORT, encoderContextSha256, select_qnn_runtime_profile_for_context(..., expected_profile))
    return execute_sdxl_encoder_phase<QnnExecutableGraph>(
        bundle_root,
        runtime_profile_json,
        params_json,
        input_tensor_path,
        latent_path,
        expected_encoder_context_sha256_raw);
}

bool qnn_run_sdxl_ultrafix_tiled_unet_branch(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& full_sample,
        double timestep,
        const float* hidden,
        size_t hidden_count,
        const float* time_ids,
        size_t time_ids_count,
        const float* pooled,
        size_t pooled_count,
        std::vector<float>* full_output,
        long long* execute_ms_total,
        size_t* graph_execution_count,
        std::string* error) {
    return run_sdxl_ultrafix_tiled_unet_branch(
        unet,
        sample_index,
        timestep_index,
        hidden_index,
        time_ids_index,
        pooled_index,
        plan,
        full_sample,
        timestep,
        hidden,
        hidden_count,
        time_ids,
        time_ids_count,
        pooled,
        pooled_count,
        full_output,
        execute_ms_total,
        graph_execution_count,
        error);
}

std::string qnn_sdxl_unet_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& initial_latent_path,
        const std::string& latent_path) {
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
        return std::string("{\"ok\":false,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    const std::string unet_binary = string_field(params_json, "unetContextBinary").empty()
        ? "unet.bin"
        : string_field(params_json, "unetContextBinary");
    const std::string unet_path = join_path(bundle_root, unet_binary);
    if (!exists_file(unet_path)) {
        return "{\"ok\":false,\"executionStage\":\"unet_context_missing\",\"message\":\"QNN SDXL UNet context is missing.\"}";
    }
    std::vector<float> embeddings;
    std::string consumed_conditioning_artifact_sha256;
    if (!read_float_binary_file(
            embeddings_path,
            &embeddings,
            &error,
            &consumed_conditioning_artifact_sha256)) {
        return std::string("{\"ok\":false,\"executionStage\":\"conditioning_read_failed\",\"message\":") +
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
    if (!validate_sdxl_phase_execution_contract(execution_contract, &error)) {
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
    const std::string& graph_name = execution_contract.graph_name;
    const std::string conditioning_format = string_field(params_json, "conditioningFormat");
    QnnConditioningEvidence conditioning_evidence;
    if (conditioning_format.empty() ||
        !resolve_qnn_conditioning_evidence(
            bundle_root,
            consumed_conditioning_artifact_sha256,
            conditioning_format,
            0u,
            params_json,
            execution_contract,
            nullptr,
            &conditioning_evidence,
            &error)) {
        if (error.empty()) error = "conditioningFormat must be explicit for isolated SDXL.";
        return qnn_semantic_failure_json(
            "conditioning_contract_invalid",
            "CONDITIONING_EVIDENCE_INVALID",
            error);
    }
    const int steps = execution_contract.scheduler.steps;
    mca::diffusion::DiffusionScheduler scheduler(execution_contract.scheduler.config);
    if (!scheduler.set_timesteps(steps, &error)) {
        return qnn_semantic_failure_json(
            "scheduler_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    const size_t full_timetable_count = scheduler.timesteps().size();
    const std::string task_mode = normalized_contract_enum(
        string_field(params_json, "taskMode"));
    const bool img2img = task_mode == "img2img";
    const bool inpaint = task_mode == "inpaint";
    const bool encoder_conditioned = img2img || inpaint;
    if ((ultra_fix.enabled && (!img2img || inpaint ||
            ultra_fix.target_width != execution_contract.width ||
            ultra_fix.target_height != execution_contract.height ||
            ultra_fix.tile_size != 1024 ||
            ultra_fix.target_width < 1024 || ultra_fix.target_width > 2048 ||
            ultra_fix.target_height < 1024 || ultra_fix.target_height > 2048 ||
            ultra_fix.target_width % 64 != 0 ||
            ultra_fix.target_height % 64 != 0)) ||
        (!encoder_conditioned && task_mode != "text_to_image")) {
        return qnn_semantic_failure_json(
            "sdxl_task_mode_unsupported",
            "TASK_MODE_UNSUPPORTED",
            "Split-worker SDXL UNet accepts text_to_image or a proven encoder-backed img2img/inpaint request.");
    }
    const double strength = encoder_conditioned
        ? double_field(params_json, "strength", 1.0)
        : 1.0;
    mca::diffusion::Img2ImgTailSchedule img2img_schedule;
    if (encoder_conditioned && !mca::diffusion::resolve_img2img_tail_schedule(
            steps,
            strength,
            &img2img_schedule,
            &error)) {
        return qnn_semantic_failure_json(
            "sdxl_img2img_strength_invalid",
            "IMG2IMG_STRENGTH_INVALID",
            error);
    }
    const size_t effective_denoise_steps = encoder_conditioned
        ? img2img_schedule.effective_step_count
        : full_timetable_count;
    const size_t begin_index = encoder_conditioned ? img2img_schedule.begin_index : 0U;
    if (full_timetable_count != static_cast<size_t>(steps) ||
        (ultra_fix.enabled &&
            (static_cast<size_t>(ultra_fix.inversion_steps) != effective_denoise_steps ||
             ultra_fix.refinement_steps != steps)) ||
        execution_contract.scheduler.expected_timetable_count !=
            effective_denoise_steps ||
        (encoder_conditioned &&
            (long_field(params_json, "fullTimetableCount") !=
                    static_cast<long long>(full_timetable_count) ||
             long_field(params_json, "effectiveDenoiseSteps") !=
                    static_cast<long long>(effective_denoise_steps) ||
             long_field(params_json, "img2imgBeginIndex") !=
                    static_cast<long long>(begin_index)))) {
        return qnn_execution_contract_mismatch_json(
            "timetableCount",
            execution_contract.scheduler.expected_timetable_count,
            effective_denoise_steps);
    }
    if (encoder_conditioned) {
        if (initial_latent_path.empty() ||
            string_field(params_json, "encoderLatentSha256").size() != 64U ||
            !scheduler.set_begin_index(begin_index, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_img2img_schedule_invalid",
                "IMG2IMG_SCHEDULER_UNSUPPORTED",
                error.empty()
                    ? "Encoder-backed img2img/inpaint requires a committed latent and a skip-safe scheduler."
                    : error);
        }
    } else if (!initial_latent_path.empty()) {
        return qnn_semantic_failure_json(
            "sdxl_text_to_image_initial_latent_rejected",
            "INPUT_CONTRACT_INVALID",
            "Text-to-image cannot consume an encoder latent.");
    }
    QnnImageGenerationScope generation(
        static_cast<int>(ultra_fix.enabled
            ? effective_denoise_steps * 2U
            : effective_denoise_steps),
        string_field(params_json, "progressJournalPath"));
    if (!generation.active()) {
        return "{\"ok\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image phase is active in this process.\"}";
    }
    generation.record_stage(mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
    auto* unet = new QnnExecutableGraph();
    if (!unet->load(
            runtime,
            unet_path,
            graph_name,
            &generation,
            false,
            false,
            "",
            expected_profile)) {
        return std::string("{\"ok\":false,\"executionStage\":\"unet_load_failed\",\"message\":") +
            quote(unet->message) + "}";
    }
    if (expected_profile > 0 && unet->selected_runtime.htp_arch_version != expected_profile) {
        std::ostringstream message;
        message << "UNet selected HTP V" << unet->selected_runtime.htp_arch_version
                << " but phase requires V" << expected_profile << ".";
        return std::string("{\"ok\":false,\"executionStage\":\"unet_profile_mismatch\",\"message\":") +
            quote(message.str()) + "}";
    }
    const int sample_index = std::max(0, tensor_index_by_name(unet->inputs, {"sample", "latent"}));
    int timestep_index = tensor_index_by_name(unet->inputs, {"timestamp", "timestep"});
    if (timestep_index < 0 && unet->inputs.size() > 2) timestep_index = 2;
    const int hidden_index = tensor_index_by_name(
        unet->inputs, {"encoder_hidden_states", "encoder_hidden", "hidden_states", "hidden"});
    const int time_ids_index = tensor_index_by_name(unet->inputs, {"time_ids", "timeids"});
    const int pooled_index = tensor_index_by_name(unet->inputs, {"text_embeds", "text_embed", "pooled"});
    if (unet->inputs.size() < 5 || sample_index >= static_cast<int>(unet->inputs.size()) ||
        timestep_index < 0 || hidden_index < 0 || time_ids_index < 0 || pooled_index < 0 ||
        unet->outputs.empty()) {
        return "{\"ok\":false,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SDXL UNet tensor layout is unsupported.\"}";
    }
    mca::qnn::inpaint::Contract inpaint_contract;
    if (inpaint) {
        std::vector<mca::qnn::inpaint::TensorDescriptor> descriptors;
        descriptors.reserve(unet->inputs.size());
        for (const auto& tensor : unet->inputs) {
            descriptors.push_back({tensor.name, tensor.dimensions});
        }
        inpaint_contract = mca::qnn::inpaint::inspect(descriptors);
        if (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
            string_field(params_json, "inpaintRequestedTopology") != "latent_blend_4" ||
            string_field(params_json, "inpaintMaskConvention") !=
                "white_repaint_black_preserve" ||
            !inpaint_contract.supported() ||
            inpaint_contract.topology != mca::qnn::inpaint::MaskTopology::LatentBlend4 ||
            inpaint_contract.sample_index != sample_index ||
            inpaint_contract.sample_channels != 4U ||
            inpaint_contract.width != 128U || inpaint_contract.height != 128U) {
            return qnn_semantic_failure_json(
                "sdxl_inpaint_topology_unsupported",
                "QNN_INPAINT_GRAPH_TOPOLOGY_UNSUPPORTED",
                "Split-SDXL inpaint requires the loaded graph to expose exactly one 128x128 NCHW four-channel latent sample and no native mask input.");
        }
    }
    mca::image::SpatialTensorShape latent_shape;
    mca::image::SpatialTensorShape unet_output_shape;
    if (!mca::image::resolve_spatial_tensor_shape(
            unet->inputs[sample_index].dimensions,
            4U,
            &latent_shape,
            &error) ||
        !mca::image::resolve_spatial_tensor_shape(
            unet->outputs[0].dimensions,
            4U,
            &unet_output_shape,
            &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"latent_layout_unsupported\",\"message\":") +
            quote(error) + "}";
    }
    if (latent_shape.height != unet_output_shape.height ||
        latent_shape.width != unet_output_shape.width) {
        return "{\"ok\":false,\"executionStage\":\"latent_shape_unsupported\",\"message\":\"QNN SDXL UNet output spatial shape must match its latent input.\"}";
    }
    mca::image::UltraFixTilePlan ultra_fix_plan;
    std::string ultra_fix_tile_plan_sha256;
    if (ultra_fix.enabled) {
        if (!mca::image::build_ultrafix_tile_plan(
                {1U, 3U, 1024U, 1024U},
                {1U, 4U, 128U, 128U},
                unet->inputs[sample_index].dimensions,
                unet->outputs[0].dimensions,
                {1U, 4U, 128U, 128U},
                {1U, 3U, 1024U, 1024U},
                static_cast<size_t>(execution_contract.width),
                static_cast<size_t>(execution_contract.height),
                static_cast<size_t>(ultra_fix.tile_size),
                ultra_fix.overlap,
                8U,
                &ultra_fix_plan,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_unet_tile_plan_invalid",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                error);
        }
        const std::string descriptor =
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        ultra_fix_tile_plan_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(descriptor.data()), descriptor.size());
        const std::string expected_plan_sha256 = normalized_contract_enum(
            string_field(params_json, "ultraFixTilePlanSha256"));
        if (!valid_sdxl_lower_sha256(ultra_fix_tile_plan_sha256) ||
            ultra_fix_tile_plan_sha256 != expected_plan_sha256 ||
            long_field(params_json, "ultraFixTileCount") !=
                static_cast<long long>(ultra_fix_plan.tiles.size())) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_unet_tile_plan_identity_mismatch",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                "Split-SDXL UltraFix encoder and UNet phases do not share one tile plan.");
        }
    }
    const uint64_t latent_elements = ultra_fix.enabled
        ? ultra_fix_plan.full_latent.element_count()
        : latent_shape.element_count();
    if (!ultra_fix.enabled &&
        (static_cast<uint64_t>(latent_shape.width) * 8u !=
            static_cast<uint64_t>(execution_contract.width) ||
        static_cast<uint64_t>(latent_shape.height) * 8u !=
            static_cast<uint64_t>(execution_contract.height))) {
        return qnn_semantic_failure_json(
            "latent_resolution_mismatch",
            "EXECUTION_CONTRACT_MISMATCH",
            "Resolved SDXL width/height do not match the native UNet latent tensor at the required 8x VAE scale.");
    }
    QnnSdxlConditioningBuffers conditioning;
    if (!qnn_prepare_sdxl_conditioning(
            *unet,
            hidden_index,
            time_ids_index,
            pooled_index,
            embeddings,
            execution_contract.use_cfg,
            &conditioning,
            &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"conditioning_shape_unsupported\",\"message\":") +
            quote(error) + "}";
    }
    constexpr size_t hidden_count =
        mca::image::kSdxlClipTokenCount * mca::image::kSdxlHiddenWidth;
    constexpr size_t pooled_count = mca::image::kSdxlPooledWidth;
    constexpr size_t time_ids_count = mca::image::kSdxlTimeIdCount;
    std::vector<float> inpaint_mask;
    std::string actual_mask_tensor_sha256;
    if (inpaint) {
        const std::string mask_tensor_path = string_field(params_json, "maskImageTensorPath");
        const std::string expected_mask_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageTensorSha256"));
        const std::vector<uint32_t> expected_mask_shape{1U, 1U, 128U, 128U};
        const long long expected_mask_bytes =
            static_cast<long long>(128U * 128U * sizeof(float));
        const long long latent_repaint_count = long_field(
            params_json, "maskImageLatentRepaintPixelCount");
        const long long mask_exif_orientation = long_field(
            params_json, "maskImageExifOrientation");
        if (string_field(params_json, "maskImagePath").empty() ||
            !valid_sdxl_lower_sha256(normalized_contract_enum(
                string_field(params_json, "maskImageSha256"))) ||
            long_field(params_json, "maskImageSizeBytes") <= 0LL ||
            long_field(params_json, "maskImageSourceWidth") <= 0LL ||
            long_field(params_json, "maskImageSourceHeight") <= 0LL ||
            long_field(params_json, "maskImageOrientedWidth") !=
                long_field(params_json, "inputImageOrientedWidth") ||
            long_field(params_json, "maskImageOrientedHeight") !=
                long_field(params_json, "inputImageOrientedHeight") ||
            mask_exif_orientation < 1LL || mask_exif_orientation > 8LL ||
            mask_tensor_path.empty() ||
            !valid_sdxl_lower_sha256(expected_mask_tensor_sha256) ||
            uint_array_field(params_json, "maskImageTensorShape") != expected_mask_shape ||
            string_field(params_json, "maskImageTensorDtype") != "float32-le" ||
            string_field(params_json, "maskImageTensorLayout") != "NCHW" ||
            string_field(params_json, "maskImageTensorRange") != "ZERO_TO_ONE" ||
            string_field(params_json, "maskImageTensorPreprocess") !=
                "source_aligned_center_crop_linear_grayscale_area_latent_nchw_v2" ||
            long_field(params_json, "maskImageTensorBytes") != expected_mask_bytes ||
            file_size_or_zero(mask_tensor_path) != expected_mask_bytes ||
            !read_float_binary_file(
                mask_tensor_path,
                &inpaint_mask,
                &error,
                &actual_mask_tensor_sha256) ||
            normalized_contract_enum(actual_mask_tensor_sha256) !=
                expected_mask_tensor_sha256 ||
            !mca::qnn::inpaint::validate_mask_values(
                inpaint_mask,
                inpaint_contract.width,
                inpaint_contract.height,
                &error) ||
            latent_repaint_count != static_cast<long long>(std::count_if(
                inpaint_mask.begin(), inpaint_mask.end(), [](float value) {
                    return value > 0.0f;
                }))) {
            return qnn_semantic_failure_json(
                "sdxl_inpaint_mask_tensor_invalid",
                "QNN_INPAINT_MASK_TENSOR_INVALID",
                error.empty()
                    ? "Split-SDXL latent mask identity, geometry, or normalized tensor contract is invalid."
                    : error);
        }
    }
    generation.set_phase(kQnnImageSampling);
    std::mt19937 rng(execution_contract.seed);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    const float initial_noise_scale = static_cast<float>(scheduler.init_noise_sigma());
    std::string encoder_latent_sha256;
    std::vector<float> source_latents;
    std::vector<float> diffusion_noise;
    std::string inpaint_source_noise_sha256;
    size_t inpaint_source_noise_use_count = 0U;
    size_t inpaint_preserve_step_count = 0U;
    uint64_t inpaint_preserved_latent_checksum = 0U;
    uint64_t img2img_noise_checksum = 0U;
    std::vector<float> ultra_fix_clean_latents;
    std::vector<float> ultra_fix_equivalent_noise;
    if (encoder_conditioned) {
        std::vector<float> canonical_encoder_latents;
        if (!read_float_binary_file(
                initial_latent_path,
                &canonical_encoder_latents,
                &error,
                &encoder_latent_sha256) ||
            encoder_latent_sha256 != normalized_contract_enum(
                string_field(params_json, "encoderLatentSha256"))) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_latent_changed",
                "ENCODER_LATENT_IDENTITY_MISMATCH",
                error.empty()
                    ? "Committed VAE encoder latent changed before UNet execution."
                    : error);
        }
        const mca::image::SpatialTensorShape canonical_encoder_shape{
            1U,
            4U,
            ultra_fix.enabled
                ? ultra_fix_plan.full_latent.height
                : latent_shape.height,
            ultra_fix.enabled
                ? ultra_fix_plan.full_latent.width
                : latent_shape.width,
            mca::image::SpatialTensorLayout::Nchw,
        };
        if (canonical_encoder_latents.size() != canonical_encoder_shape.element_count() ||
            !mca::image::copy_spatial_tile(
                canonical_encoder_latents,
                canonical_encoder_shape,
                0U,
                0U,
                ultra_fix.enabled ? ultra_fix_plan.full_latent : latent_shape,
                &source_latents,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_latent_layout_invalid",
                "ENCODER_LATENT_LAYOUT_INVALID",
                error.empty()
                    ? "Committed VAE encoder latent does not match the UNet input layout."
                    : error);
        }
        if (ultra_fix.enabled) {
            latents = source_latents;
        } else {
            diffusion_noise.assign(source_latents.size(), 0.0f);
            for (float& value : diffusion_noise) value = normal(rng);
            img2img_noise_checksum = checksum_float_vector(diffusion_noise);
            if (inpaint) {
                inpaint_source_noise_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                    reinterpret_cast<const uint8_t*>(diffusion_noise.data()),
                    diffusion_noise.size() * sizeof(float));
            }
            if (!scheduler.add_noise(
                    source_latents,
                    diffusion_noise,
                    begin_index,
                    &latents,
                    &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_img2img_add_noise_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    error);
            }
            if (img2img_noise_checksum == 0U ||
                (inpaint && !valid_sdxl_lower_sha256(inpaint_source_noise_sha256))) {
                return qnn_semantic_failure_json(
                    "sdxl_image_noise_evidence_invalid",
                    "SCHEDULER_EXECUTION_FAILED",
                    "Split-SDXL image-conditioned noise lacks deterministic execution evidence.");
            }
            if (inpaint) ++inpaint_source_noise_use_count;
        }
    } else {
        for (float& value : latents) value = normal(rng) * initial_noise_scale;
    }
    std::vector<float> noise_uncond;
    std::vector<float> noise_cond;
    long long unet_execute_ms_total = 0;
    size_t unet_execution_count = 0;
    size_t ultra_fix_inversion_step_count = 0U;
    size_t ultra_fix_inversion_graph_execution_count = 0U;
    size_t ultra_fix_refinement_step_count = 0U;
    size_t ultra_fix_refinement_positive_execution_count = 0U;
    size_t ultra_fix_refinement_negative_execution_count = 0U;
    size_t ultra_fix_quality_step_evaluation_count = 0U;
    size_t ultra_fix_noise_injection_step_count = 0U;
    std::string ultra_fix_noise_injection_seed_fingerprint;
    uint64_t ultra_fix_noise_injection_checksum = 0U;
    size_t ultra_fix_structure_guidance_step_count = 0U;
    uint64_t ultra_fix_structure_guidance_checksum = 0U;
    uint64_t ultra_fix_trajectory_noise_checksum = 0U;
    if (ultra_fix.enabled) {
        if (!encoder_conditioned || source_latents.empty()) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_clean_latent_missing",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix requires the committed full clean VAE latent before inversion.");
        }
        ultra_fix_clean_latents = source_latents;
    }
    if (ultra_fix.enabled) {
        mca::image::UltraFixNoiseLevel clean_level;
        for (size_t reverse = scheduler.timesteps().size(); reverse > begin_index; --reverse) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            const size_t target_index = reverse - 1U;
            const bool first_hop = target_index + 1U == scheduler.timesteps().size();
            const size_t evaluation_index = first_hop ? target_index : target_index + 1U;
            mca::image::UltraFixNoiseLevel target_level;
            mca::image::UltraFixNoiseLevel source_level;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            if (!qnn_ultrafix_noise_level(
                    scheduler, target_index, &target_level, &error) ||
                (!first_hop && !qnn_ultrafix_noise_level(
                    scheduler, evaluation_index, &source_level, &error))) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_inversion_schedule_invalid",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            if (first_hop) source_level = clean_level;
            std::vector<float> model_input;
            if (!mca::image::scale_ultrafix_inversion_model_input(
                    latents,
                    source_level,
                    first_hop ? target_level : source_level,
                    &model_input,
                    &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_inversion_scale_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            std::vector<float> epsilon;
            if (!qnn_run_sdxl_ultrafix_tiled_unet_branch(
                    *unet,
                    sample_index,
                    timestep_index,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    ultra_fix_plan,
                    model_input,
                    scheduler.timesteps()[evaluation_index],
                    conditioning.positive_hidden.data(),
                    hidden_count,
                    conditioning.time_ids,
                    time_ids_count,
                    conditioning.positive_pooled,
                    pooled_count,
                    &epsilon,
                    &unet_execute_ms_total,
                    &ultra_fix_inversion_graph_execution_count,
                    &error) ||
                !mca::image::ultrafix_epsilon_inversion_step(
                    latents,
                    epsilon,
                    source_level,
                    target_level,
                    &model_input,
                    &error)) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_inversion_execute_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            latents = std::move(model_input);
            ++ultra_fix_inversion_step_count;
            generation.set_step(static_cast<int>(ultra_fix_inversion_step_count));
        }
        if (ultra_fix_inversion_step_count != effective_denoise_steps) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_inversion_incomplete",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix did not complete every inversion hop.");
        }
        const std::string seed_descriptor = mca::image::ultrafix_noise_seed_descriptor(
            execution_contract.seed,
            effective_denoise_steps);
        ultra_fix_noise_injection_seed_fingerprint =
            mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(seed_descriptor.data()),
                seed_descriptor.size());
        if (!valid_sdxl_lower_sha256(ultra_fix_noise_injection_seed_fingerprint)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_quality_seed_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix could not bind its deterministic quality-noise seed domain.");
        }
        if (effective_denoise_steps > 1U) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            mca::image::UltraFixNoiseLevel inverted_level;
            if (!qnn_ultrafix_noise_level(
                    scheduler, begin_index, &inverted_level, &error) ||
                !mca::image::ultrafix_equivalent_noise(
                    ultra_fix_clean_latents,
                    latents,
                    inverted_level,
                    &ultra_fix_equivalent_noise,
                    &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_trajectory_noise_failed",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    error);
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            ultra_fix_trajectory_noise_checksum =
                mca::image::ultrafix_tensor_checksum(ultra_fix_equivalent_noise);
            if (ultra_fix_trajectory_noise_checksum == 0U) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_trajectory_noise_evidence_invalid",
                    "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                    "Split-SDXL UltraFix trajectory noise lacks deterministic tensor evidence.");
            }
        }
    }
    for (size_t step = begin_index; step < scheduler.timesteps().size(); ++step) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        const size_t physical_step = step - begin_index;
        generation.set_step(static_cast<int>(
            (ultra_fix.enabled ? effective_denoise_steps : 0U) + physical_step));
        std::vector<float> model_input;
        if (!scheduler.scale_model_input(latents, step, &model_input, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_scheduler_scale_failed",
                "SCHEDULER_EXECUTION_FAILED",
                error);
        }
        long long execute_ms = 0;
        if (execution_contract.use_cfg) {
            const bool unconditioned_ok = ultra_fix.enabled
                ? qnn_run_sdxl_ultrafix_tiled_unet_branch(
                    *unet,
                    sample_index,
                    timestep_index,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    ultra_fix_plan,
                    model_input,
                    scheduler.timesteps()[step],
                    conditioning.negative_hidden.data(),
                    hidden_count,
                    conditioning.time_ids,
                    time_ids_count,
                    conditioning.negative_pooled,
                    pooled_count,
                    &noise_uncond,
                    &unet_execute_ms_total,
                    &ultra_fix_refinement_negative_execution_count,
                    &error)
                : qnn_run_sdxl_unet_once(
                    *unet, sample_index, timestep_index, hidden_index, time_ids_index, pooled_index,
                    model_input, scheduler.timesteps()[step], conditioning.negative_hidden.data(), hidden_count,
                    conditioning.time_ids, time_ids_count, conditioning.negative_pooled, pooled_count,
                    &noise_uncond, &execute_ms, &error);
            if (!unconditioned_ok) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            if (!ultra_fix.enabled) {
                unet_execute_ms_total += execute_ms;
                ++unet_execution_count;
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
        const bool conditioned_ok = ultra_fix.enabled
            ? qnn_run_sdxl_ultrafix_tiled_unet_branch(
                *unet,
                sample_index,
                timestep_index,
                hidden_index,
                time_ids_index,
                pooled_index,
                ultra_fix_plan,
                model_input,
                scheduler.timesteps()[step],
                conditioning.positive_hidden.data(),
                hidden_count,
                conditioning.time_ids,
                time_ids_count,
                conditioning.positive_pooled,
                pooled_count,
                &noise_cond,
                &unet_execute_ms_total,
                &ultra_fix_refinement_positive_execution_count,
                &error)
            : qnn_run_sdxl_unet_once(
                *unet, sample_index, timestep_index, hidden_index, time_ids_index, pooled_index,
                model_input, scheduler.timesteps()[step], conditioning.positive_hidden.data(), hidden_count,
                conditioning.time_ids, time_ids_count, conditioning.positive_pooled, pooled_count,
                &noise_cond, &execute_ms, &error);
        if (!conditioned_ok) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!ultra_fix.enabled) {
            unet_execute_ms_total += execute_ms;
            ++unet_execution_count;
        }
        if ((execution_contract.use_cfg && noise_uncond.size() != latents.size()) ||
            noise_cond.size() != latents.size()) {
            return "{\"ok\":false,\"executionStage\":\"sdxl_unet_output_shape_unsupported\",\"message\":\"SDXL UNet output does not exactly match its latent input.\"}";
        }
        std::vector<float> guided;
        if (!mca::image::apply_classifier_free_guidance(
                noise_cond,
                noise_uncond,
                execution_contract.cfg_scale,
                execution_contract.use_cfg,
                &guided,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_cfg_failed",
                "EXECUTION_CONTRACT_INVALID",
                error);
        }
        mca::image::UltraFixQualitySchedule ultra_fix_quality_schedule;
        const bool ultra_fix_has_next_step = ultra_fix.enabled &&
            step + 1U < scheduler.timesteps().size();
        if (ultra_fix.enabled) {
            if (!mca::image::resolve_ultrafix_quality_schedule(
                    scheduler.timesteps()[step],
                    ultra_fix_has_next_step,
                    &ultra_fix_quality_schedule,
                    &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_quality_schedule_invalid",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    error);
            }
            if (ultra_fix_has_next_step) ++ultra_fix_quality_step_evaluation_count;
            if (ultra_fix_quality_schedule.evaluate_noise_injection) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> mixed_prediction;
                uint64_t gaussian_checksum = 0U;
                uint64_t mixed_checksum = 0U;
                if (!mca::image::ultrafix_inject_spherical_noise(
                        guided,
                        execution_contract.seed,
                        static_cast<uint32_t>(physical_step),
                        ultra_fix_quality_schedule.noise_injection_fraction,
                        &mixed_prediction,
                        &gaussian_checksum,
                        &mixed_checksum,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_ultrafix_noise_injection_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error);
                }
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                ultra_fix_noise_injection_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_noise_injection_checksum,
                        static_cast<uint32_t>(physical_step),
                        gaussian_checksum);
                ultra_fix_noise_injection_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_noise_injection_checksum,
                        static_cast<uint32_t>(physical_step),
                        mixed_checksum);
                guided = std::move(mixed_prediction);
                ++ultra_fix_noise_injection_step_count;
            }
        }
        mca::diffusion::SchedulerStepResult step_result;
        mca::diffusion::SchedulerStepOptions step_options;
        step_options.eta = execution_contract.scheduler.eta;
        if (!scheduler.step(guided, step, latents, &step_result, &error, step_options)) {
            return qnn_semantic_failure_json(
                "sdxl_scheduler_step_failed",
                "SCHEDULER_EXECUTION_FAILED",
                error);
        }
        latents = std::move(step_result.previous_sample);
        if (ultra_fix.enabled) {
            ++ultra_fix_refinement_step_count;
            if (ultra_fix_quality_schedule.evaluate_structure_guidance) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> trajectory_reference;
                mca::image::UltraFixNoiseLevel trajectory_level;
                if (ultra_fix_equivalent_noise.empty() ||
                    !qnn_ultrafix_noise_level(
                        scheduler,
                        step + 1U,
                        &trajectory_level,
                        &error) ||
                    !mca::image::ultrafix_add_noise(
                        ultra_fix_clean_latents,
                        ultra_fix_equivalent_noise,
                        trajectory_level,
                        &trajectory_reference,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_ultrafix_structure_trajectory_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error.empty()
                            ? "Split-SDXL UltraFix structure guidance lacks its inverted trajectory noise."
                            : error);
                }
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> structured_latents;
                uint64_t structured_checksum = 0U;
                const size_t blur_radius = mca::image::ultrafix_structure_blur_radius(
                    ultra_fix_plan.unet_input.width);
                if (blur_radius == 0U ||
                    !mca::image::ultrafix_apply_structure_guidance(
                        latents,
                        trajectory_reference,
                        ultra_fix_plan.full_latent,
                        blur_radius,
                        ultra_fix_quality_schedule.structure_guidance_weight,
                        &structured_latents,
                        &structured_checksum,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_ultrafix_structure_guidance_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error);
                }
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                ultra_fix_structure_guidance_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_structure_guidance_checksum,
                        static_cast<uint32_t>(physical_step),
                        structured_checksum);
                latents = std::move(structured_latents);
                ++ultra_fix_structure_guidance_step_count;
            }
        }
        if (inpaint) {
            std::vector<float> source_at_timestep;
            if (step + 1U < scheduler.timesteps().size()) {
                if (!scheduler.add_noise(
                        source_latents,
                        diffusion_noise,
                        step + 1U,
                        &source_at_timestep,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "sdxl_inpaint_source_noise_failed",
                        "QNN_INPAINT_PRESERVATION_FAILED",
                        error);
                }
                ++inpaint_source_noise_use_count;
            } else {
                source_at_timestep = source_latents;
            }
            std::vector<float> preserved_latents;
            if (!mca::qnn::inpaint::preserve_unmasked_latent(
                    latents,
                    source_at_timestep,
                    inpaint_mask,
                    inpaint_contract.width,
                    inpaint_contract.height,
                    &preserved_latents,
                    &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_inpaint_preservation_failed",
                    "QNN_INPAINT_PRESERVATION_FAILED",
                    error);
            }
            latents = std::move(preserved_latents);
            ++inpaint_preserve_step_count;
            inpaint_preserved_latent_checksum = checksum_float_vector(latents);
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        const int completed_step = static_cast<int>(
            (ultra_fix.enabled ? effective_denoise_steps : 0U) + physical_step + 1U);
        generation.set_step(completed_step);
        generation.set_phase(kQnnImageSampling);
    }
    if (ultra_fix.enabled) {
        mca::image::UltraFixExecutionCounts expected_counts;
        const size_t quality_step_count = ultra_fix_quality_step_evaluation_count;
        const bool quality_noise_evidence_consistent =
            ultra_fix_noise_injection_step_count <= quality_step_count &&
            ((ultra_fix_noise_injection_step_count == 0U) ==
             (ultra_fix_noise_injection_checksum == 0U));
        const bool quality_structure_evidence_consistent =
            ultra_fix_structure_guidance_step_count <= quality_step_count &&
            ((ultra_fix_structure_guidance_step_count == 0U) ==
             (ultra_fix_structure_guidance_checksum == 0U));
        const bool quality_coverage_complete = quality_step_count == 0U
            ? ultra_fix_trajectory_noise_checksum == 0U
            : ultra_fix_trajectory_noise_checksum != 0U &&
                  ultra_fix_noise_injection_step_count +
                          ultra_fix_structure_guidance_step_count >=
                      quality_step_count;
        if (!mca::image::resolve_ultrafix_execution_counts(
                ultra_fix_plan.tiles.size(),
                effective_denoise_steps,
                effective_denoise_steps,
                execution_contract.use_cfg,
                &expected_counts,
                &error) ||
            ultra_fix_inversion_step_count != effective_denoise_steps ||
            ultra_fix_refinement_step_count != effective_denoise_steps ||
            ultra_fix_inversion_graph_execution_count !=
                expected_counts.inversion_positive_unet_graph_executions ||
            ultra_fix_refinement_positive_execution_count !=
                expected_counts.refinement_positive_unet_graph_executions ||
            ultra_fix_refinement_negative_execution_count !=
                expected_counts.refinement_negative_unet_graph_executions ||
            ultra_fix_quality_step_evaluation_count !=
                (effective_denoise_steps > 0U ? effective_denoise_steps - 1U : 0U) ||
            !quality_noise_evidence_consistent ||
            !quality_structure_evidence_consistent ||
            !quality_coverage_complete) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_execution_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                error.empty()
                    ? "Split-SDXL UltraFix physical UNet counts do not match the unified tile plan."
                    : error);
        }
        unet_execution_count = expected_counts.total_unet_graph_executions;
    } else if (unet_execution_count !=
        execution_contract.scheduler.expected_unet_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "unetExecutionCount",
            execution_contract.scheduler.expected_unet_execution_count,
            unet_execution_count);
    }
    if (inpaint &&
        (inpaint_preserve_step_count != effective_denoise_steps ||
         inpaint_source_noise_use_count != effective_denoise_steps ||
         inpaint_preserved_latent_checksum == 0U ||
         !valid_sdxl_lower_sha256(inpaint_source_noise_sha256))) {
        return qnn_semantic_failure_json(
            "sdxl_inpaint_execution_evidence_invalid",
            "QNN_INPAINT_EXECUTION_EVIDENCE_INVALID",
            "Split-SDXL inpaint did not preserve the source latent with one deterministic noise tensor after every scheduler step.");
    }
    const mca::image::SpatialTensorShape canonical_latent_shape{
        1U,
        4U,
        ultra_fix.enabled ? ultra_fix_plan.full_latent.height : latent_shape.height,
        ultra_fix.enabled ? ultra_fix_plan.full_latent.width : latent_shape.width,
        mca::image::SpatialTensorLayout::Nchw,
    };
    std::vector<float> canonical_latents;
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    if (!mca::image::copy_spatial_tile(
            latents,
            ultra_fix.enabled ? ultra_fix_plan.full_latent : latent_shape,
            0U,
            0U,
            canonical_latent_shape,
            &canonical_latents,
            &error) ||
        !write_sdxl_latent_atomic(latent_path, canonical_latents, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"latent_publish_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(latent_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    const std::vector<double> executed_timesteps(
        scheduler.timesteps().begin() + static_cast<std::ptrdiff_t>(begin_index),
        scheduler.timesteps().end());
    const std::vector<double> executed_sigmas = scheduler.sigmas().empty()
        ? std::vector<double>{}
        : std::vector<double>(
            scheduler.sigmas().begin() + static_cast<std::ptrdiff_t>(begin_index),
            scheduler.sigmas().end());
    const size_t logical_unet_execution_count =
        execution_contract.scheduler.expected_unet_execution_count;
    QnnNativeEffectiveEvidence native_evidence{
        effective_denoise_steps,
        logical_unet_execution_count,
        conditioning_evidence.tokenizer_backend,
        conditioning_evidence.token_count,
        conditioning_evidence.embedding_disk_data_type,
        execution_contract.width,
        execution_contract.height,
        unet->graph_name,
    };
    native_evidence.prompt_weighting_applied =
        conditioning_evidence.prompt_weighting_applied;
    native_evidence.positive_weighted_token_count =
        conditioning_evidence.positive_weighted_token_count;
    native_evidence.negative_weighted_token_count =
        conditioning_evidence.negative_weighted_token_count;
    native_evidence.prompt_weight_fingerprint =
        conditioning_evidence.prompt_weight_fingerprint;
    native_evidence.conditioning_artifact_sha256 =
        conditioning_evidence.conditioning_artifact_sha256;
    native_evidence.conditioning_execution_mode =
        conditioning_evidence.conditioning_execution_mode;
    native_evidence.conditioning_backend = conditioning_evidence.conditioning_backend;
    native_evidence.conditioning_graph = conditioning_evidence.conditioning_graph;
    native_evidence.conditioning_graph_sha256 =
        conditioning_evidence.conditioning_graph_sha256;
    native_evidence.conditioning_order = conditioning_evidence.conditioning_order;
    native_evidence.conditioning_encoder_execution_count =
        conditioning_evidence.conditioning_encoder_execution_count;
    native_evidence.text_encoder_execution_count = 0U;
    native_evidence.conditioning_artifact_consumed = unet_execution_count > 0U;
    if (!bind_qnn_consumed_prompt_evidence(
            conditioning_evidence,
            native_evidence.conditioning_artifact_consumed,
            unet_execution_count,
            &native_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "native_prompt_evidence_invalid",
            "EXECUTION_EVIDENCE_INVALID",
            error);
    }
    native_evidence.runtime_session_mode = "isolated_unet_phase";
    native_evidence.task_mode = task_mode;
    native_evidence.strength = strength;
    native_evidence.full_timetable_count = full_timetable_count;
    native_evidence.img2img_begin_index = begin_index;
    native_evidence.effective_denoise_steps = effective_denoise_steps;
    if (encoder_conditioned) {
        native_evidence.input_image_path = string_field(params_json, "inputImagePath");
        native_evidence.input_image_sha256 = normalized_contract_enum(
            string_field(params_json, "inputImageSha256"));
        native_evidence.input_image_tensor_path = string_field(
            params_json, "inputImageTensorPath");
        native_evidence.input_image_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "inputImageTensorSha256"));
        native_evidence.input_image_tensor_bytes = long_field(
            params_json, "inputImageTensorBytes");
        native_evidence.input_image_tensor_dtype = string_field(
            params_json, "inputImageTensorDtype");
        native_evidence.input_image_tensor_layout = string_field(
            params_json, "inputImageTensorLayout");
        native_evidence.input_image_tensor_range = string_field(
            params_json, "inputImageTensorRange");
        native_evidence.encoder_latent_sha256 = encoder_latent_sha256;
        native_evidence.input_image_preprocess = string_field(
            params_json, "inputImagePreprocess");
        native_evidence.input_image_execution_count = 1U;
        native_evidence.input_image_source_width = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "inputImageSourceWidth")));
        native_evidence.input_image_source_height = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "inputImageSourceHeight")));
        native_evidence.input_image_oriented_width = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "inputImageOrientedWidth")));
        native_evidence.input_image_oriented_height = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "inputImageOrientedHeight")));
        native_evidence.input_image_exif_orientation = static_cast<int>(
            long_field(params_json, "inputImageExifOrientation"));
        native_evidence.input_image_tensor_width =
            static_cast<size_t>(execution_contract.width);
        native_evidence.input_image_tensor_height =
            static_cast<size_t>(execution_contract.height);
        native_evidence.input_image_tensor_channels = 3U;
        native_evidence.img2img_add_noise_applied = !ultra_fix.enabled;
        native_evidence.img2img_add_noise_begin_index = begin_index;
        native_evidence.img2img_add_noise_timestep = ultra_fix.enabled
            ? 0.0
            : scheduler.timesteps()[begin_index];
        native_evidence.img2img_noise_checksum = fixed_width_lower_hex_u64(
            img2img_noise_checksum);
    }
    if (inpaint) {
        native_evidence.mask_image_path = string_field(params_json, "maskImagePath");
        native_evidence.mask_image_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageSha256"));
        native_evidence.mask_image_source_bytes = long_field(
            params_json, "maskImageSizeBytes");
        native_evidence.mask_image_tensor_path = string_field(
            params_json, "maskImageTensorPath");
        native_evidence.mask_image_tensor_sha256 = normalized_contract_enum(
            actual_mask_tensor_sha256);
        native_evidence.mask_image_tensor_bytes = long_field(
            params_json, "maskImageTensorBytes");
        native_evidence.mask_image_tensor_dtype = string_field(
            params_json, "maskImageTensorDtype");
        native_evidence.mask_image_tensor_layout = string_field(
            params_json, "maskImageTensorLayout");
        native_evidence.mask_image_tensor_range = string_field(
            params_json, "maskImageTensorRange");
        native_evidence.mask_image_tensor_preprocess = string_field(
            params_json, "maskImageTensorPreprocess");
        native_evidence.mask_image_source_width = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "maskImageSourceWidth")));
        native_evidence.mask_image_source_height = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "maskImageSourceHeight")));
        native_evidence.mask_image_oriented_width = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "maskImageOrientedWidth")));
        native_evidence.mask_image_oriented_height = static_cast<size_t>(std::max<long long>(
            0, long_field(params_json, "maskImageOrientedHeight")));
        native_evidence.mask_image_exif_orientation = static_cast<int>(
            long_field(params_json, "maskImageExifOrientation"));
        native_evidence.mask_image_repaint_pixel_count = static_cast<size_t>(
            std::max<long long>(0, long_field(params_json, "maskImageRepaintPixelCount")));
        native_evidence.mask_image_latent_repaint_pixel_count = static_cast<size_t>(
            std::max<long long>(0, long_field(params_json, "maskImageLatentRepaintPixelCount")));
        native_evidence.mask_image_execution_count = 1U;
        native_evidence.inpaint_topology = inpaint_contract.topology_name();
        native_evidence.inpaint_mask_unet_bind_count = 0U;
        native_evidence.inpaint_preserve_step_count = inpaint_preserve_step_count;
        native_evidence.inpaint_latent_blend_count = inpaint_preserve_step_count;
        native_evidence.inpaint_source_encoder_execution_count = 1U;
        native_evidence.inpaint_preserved_latent_checksum = fixed_width_lower_hex_u64(
            inpaint_preserved_latent_checksum);
        native_evidence.inpaint_source_noise_sha256 = inpaint_source_noise_sha256;
        native_evidence.inpaint_source_noise_use_count = inpaint_source_noise_use_count;
        native_evidence.inpaint_final_mode =
            "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend";
        native_evidence.mask_image_consumed = true;
        native_evidence.inpaint_unmasked_preservation_applied = true;
    }
    const std::string native_effective = qnn_native_effective_json(
        execution_contract,
        native_evidence);
    const long long native_sequence = g_qnn_image_generation_sequence.load();
    const uint32_t native_stage_mask = g_qnn_image_generation_stage_mask.load();
    const uint64_t native_detail_stage_mask =
        g_qnn_image_generation_detail_stage_mask.load();
    std::ostringstream out;
    out << std::setprecision(17)
        << "{\"ok\":true,\"phase\":\"unet\",\"executionStage\":\"sdxl_unet_phase_passed\","
        << "\"profileId\":" << quote(execution_contract.profile_id) << ","
        << "\"profileRevision\":" << execution_contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\","
        << "\"scheduler\":" << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ","
        << "\"predictionType\":" << quote(qnn_prediction_wire_name(execution_contract.scheduler.config.prediction_type)) << ","
        << "\"steps\":" << execution_contract.scheduler.steps << ","
        << "\"timetableCount\":" << effective_denoise_steps << ","
        << "\"unetExecutionCount\":" << logical_unet_execution_count << ","
        << "\"cfgScale\":" << execution_contract.cfg_scale << ","
        << "\"useCfg\":" << (execution_contract.use_cfg ? "true" : "false") << ","
        << "\"unconditionalBranch\":" << (execution_contract.use_cfg ? "true" : "false") << ","
        << "\"tokenizerBackend\":" << quote(conditioning_evidence.tokenizer_backend) << ","
        << "\"tokenCount\":" << conditioning_evidence.token_count << ","
        << "\"promptWeightingSupported\":" << (execution_contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingApplied\":" << (conditioning_evidence.prompt_weighting_applied ? "true" : "false") << ","
        << "\"positiveWeightedTokenCount\":" << conditioning_evidence.positive_weighted_token_count << ","
        << "\"negativeWeightedTokenCount\":" << conditioning_evidence.negative_weighted_token_count << ","
        << "\"promptWeightFingerprint\":" << quote(conditioning_evidence.prompt_weight_fingerprint) << ","
        << "\"nativePromptExecutionSha256\":"
        << quote(native_evidence.native_prompt_execution_sha256) << ","
        << "\"nativePromptBindingStage\":"
        << quote(native_evidence.native_prompt_binding_stage) << ","
        << "\"conditioningArtifactSha256\":"
        << quote(conditioning_evidence.conditioning_artifact_sha256) << ","
        << "\"conditioningExecutionMode\":"
        << quote(native_evidence.conditioning_execution_mode) << ","
        << "\"conditioningBackend\":" << quote(native_evidence.conditioning_backend) << ","
        << "\"conditioningGraph\":" << quote(native_evidence.conditioning_graph) << ","
        << "\"conditioningGraphSha256\":"
        << quote(native_evidence.conditioning_graph_sha256) << ","
        << "\"conditioningOrder\":" << quote(native_evidence.conditioning_order) << ","
        << "\"conditioningEncoderExecutionCount\":"
        << native_evidence.conditioning_encoder_execution_count << ","
        << qnn_prompt_to_encoder_receipt_json_fields(native_evidence) << ","
        << "\"textEncoderExecutionCount\":0,"
        << "\"conditioningArtifactConsumed\":"
        << (native_evidence.conditioning_artifact_consumed ? "true" : "false") << ","
        << "\"runtimeSessionMode\":\"isolated_unet_phase\","
        << "\"embeddingDiskDataType\":" << quote(conditioning_evidence.embedding_disk_data_type) << ","
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(execution_contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << execution_contract.vae_scaling_factor << ","
        << "\"pixelRange\":"
        << quote(mca::qnn::image_pixel_range_wire_name(execution_contract.pixel_range)) << ","
        << "\"width\":" << execution_contract.width << ","
        << "\"height\":" << execution_contract.height << ","
        << "\"seed\":" << execution_contract.seed << ","
        << "\"graphName\":" << quote(unet->graph_name) << ","
        << "\"fallback\":false,"
        << "\"nativeEffective\":" << native_effective << ","
        << "\"processExitRequired\":true,\"runtimeProfile\":\"V" << unet->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << unet->selected_runtime.htp_arch_version << ","
        << "\"latentPath\":" << quote(latent_path) << ",\"latentDtype\":\"float32-le\","
        << "\"latentShape\":[1,4," << canonical_latent_shape.height << ","
        << canonical_latent_shape.width << "],"
        << "\"latentElements\":" << canonical_latents.size() << ",\"latentBytes\":" << canonical_latents.size() * sizeof(float) << ","
        << "\"latentChecksum\":" << checksum_float_vector(canonical_latents) << ","
        << "\"unetContextLoadCount\":1,"
        << "\"unetSamplingLoopCount\":1,"
        << "\"unetSamplingStepCount\":"
        << (ultra_fix.enabled ? effective_denoise_steps * 2U : effective_denoise_steps) << ","
        << "\"unetGraphExecutionCount\":" << unet_execution_count << ","
        << "\"unetContextReusedAcrossSteps\":true,"
        << "\"unetGraphName\":" << quote(unet->graph_name) << ","
        << "\"unetContextLoadMs\":" << unet->context_load_ms << ","
        << "\"timesteps\":" << qnn_double_array_json(executed_timesteps) << ","
        << "\"sigmas\":" << qnn_double_array_json(executed_sigmas) << ","
        << "\"initNoiseSigma\":" << scheduler.init_noise_sigma() << ","
        << "\"img2imgBeginIndex\":" << begin_index << ","
        << "\"fullTimetableCount\":" << full_timetable_count << ","
        << "\"effectiveDenoiseSteps\":" << effective_denoise_steps << ","
        << "\"encoderLatentSha256\":" << quote(encoder_latent_sha256) << ",";
    if (ultra_fix.enabled) {
        out << "\"ultraFixTilePlanSha256\":" << quote(ultra_fix_tile_plan_sha256) << ","
            << "\"ultraFixTileCount\":" << ultra_fix_plan.tiles.size() << ","
            << "\"ultraFixInversionStepCount\":" << ultra_fix_inversion_step_count << ","
            << "\"ultraFixInversionGraphExecutionCount\":"
            << ultra_fix_inversion_graph_execution_count << ","
            << "\"ultraFixInversionTileSuccessCount\":"
            << ultra_fix_inversion_graph_execution_count << ","
            << "\"ultraFixRefinementStepCount\":" << ultra_fix_refinement_step_count << ","
            << "\"ultraFixRefinementPositiveGraphExecutionCount\":"
            << ultra_fix_refinement_positive_execution_count << ","
            << "\"ultraFixRefinementNegativeGraphExecutionCount\":"
            << ultra_fix_refinement_negative_execution_count << ","
            << "\"ultraFixRefinementTileSuccessCount\":"
            << (ultra_fix_refinement_positive_execution_count +
                ultra_fix_refinement_negative_execution_count) << ","
            << "\"ultraFixPhysicalUnetGraphExecutionCount\":"
            << unet_execution_count << ","
            << "\"ultraFixQualityStepEvaluationCount\":"
            << ultra_fix_quality_step_evaluation_count << ","
            << "\"ultraFixNoiseInjectionStepCount\":"
            << ultra_fix_noise_injection_step_count << ","
            << "\"ultraFixNoiseInjectionSeedFingerprint\":"
            << quote(ultra_fix_noise_injection_seed_fingerprint) << ","
            << "\"ultraFixNoiseInjectionChecksum\":"
            << quote(fixed_width_lower_hex_u64(ultra_fix_noise_injection_checksum)) << ","
            << "\"ultraFixStructureGuidanceStepCount\":"
            << ultra_fix_structure_guidance_step_count << ","
            << "\"ultraFixStructureGuidanceChecksum\":"
            << quote(fixed_width_lower_hex_u64(ultra_fix_structure_guidance_checksum)) << ","
            << "\"ultraFixTrajectoryNoiseChecksum\":"
            << quote(fixed_width_lower_hex_u64(ultra_fix_trajectory_noise_checksum)) << ","
            << "\"ultraFixSampleMethod\":"
            << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ","
            << "\"ultraFixNativeScheduler\":"
            << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ",";
    }
    out
        << sdxl_disabled_preview_native_json_fields() << ","
        << "\"scaleModelInput\":" << (execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ",\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMaskHex\":\""
        << mca::qnn::image_stage_mask_hex(native_detail_stage_mask) << "\","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(unet->selected_runtime) << "}";
    return out.str();
}

std::string qnn_sdxl_vae_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& latent_path,
        const std::string& output_path) {
    // contract: UNSUPPORTED_PREVIEW_TRANSPORT, select_qnn_runtime_profile_for_context(..., expected_profile))
    return execute_sdxl_vae_phase<QnnExecutableGraph>(
        bundle_root,
        runtime_profile_json,
        params_json,
        latent_path,
        output_path);
}
