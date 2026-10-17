// core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_phase.cpp -- split SDXL UNet phase: entry,
// contract validation, tensor layout, UltraFix tile plan, inpaint mask and the phase ridge.
// (T2B18 / R7-C7 split).  Lines 41-49 and 50-421 are relocated from
// mca/qnn/qnn_sdxl_unet_phase.hpp; the phase calls and explicit instantiation are new.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

#include "mca/qnn/qnn_sdxl_unet_phase_internal.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

template <typename GraphT, typename... Args>
inline std::string execute_sdxl_unet_phase(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& initial_latent_path,
        const std::string& latent_path,
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
    // PNDM text-to-image includes warm-up entries beyond the requested step count.
    // Only encoder tail slicing assumes one timetable entry per inference step;
    // every task still has to match the explicit expected timetable below.
    if ((encoder_conditioned && full_timetable_count != static_cast<size_t>(steps)) ||
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
    std::unique_lock<std::timed_mutex> execution_lock(::mca::qnn::g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
    auto* unet = new GraphT();
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

    // ---- phase hand-off context (T2B18 split; fields mirror the original locals) ----
    mca::qnn::SdxlUnetPhaseContext ctx(scheduler);
    ctx.bundle_root = bundle_root;
    ctx.runtime = runtime;
    ctx.error = error;
    ctx.started = started;
    ctx.steps = steps;
    ctx.task_mode = task_mode;
    ctx.full_timetable_count = full_timetable_count;
    ctx.encoder_conditioned = encoder_conditioned;
    ctx.inpaint = inpaint;
    ctx.begin_index = begin_index;
    ctx.strength = strength;
    ctx.generation = &generation;
    ctx.sample_index = sample_index;
    ctx.timestep_index = timestep_index;
    ctx.hidden_index = hidden_index;
    ctx.time_ids_index = time_ids_index;
    ctx.pooled_index = pooled_index;
    ctx.inpaint_contract = inpaint_contract;
    ctx.latent_shape = latent_shape;
    ctx.ultra_fix = ultra_fix;
    ctx.ultra_fix_plan = ultra_fix_plan;
    ctx.ultra_fix_tile_plan_sha256 = ultra_fix_tile_plan_sha256;
    ctx.latent_elements = latent_elements;
    ctx.conditioning = conditioning;
    ctx.hidden_count = hidden_count;
    ctx.pooled_count = pooled_count;
    ctx.time_ids_count = time_ids_count;
    ctx.inpaint_mask = inpaint_mask;
    ctx.actual_mask_tensor_sha256 = actual_mask_tensor_sha256;
    ctx.conditioning_evidence = conditioning_evidence;
    ctx.effective_denoise_steps = effective_denoise_steps;
    ctx.execution_contract = execution_contract;
    ctx.embeddings_path = embeddings_path;
    ctx.initial_latent_path = initial_latent_path;
    ctx.latent_path = latent_path;
    ctx.params_json = params_json;
    ctx.unet = unet;

    std::string phase_result = mca::qnn::qnn_sdxl_unet_tiling_body(ctx);
    if (!phase_result.empty()) return phase_result;
    return mca::qnn::qnn_sdxl_unet_execution_body(ctx);
}

template std::string execute_sdxl_unet_phase<mca::qnn::QnnExecutableGraph>(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& initial_latent_path,
        const std::string& latent_path);

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
