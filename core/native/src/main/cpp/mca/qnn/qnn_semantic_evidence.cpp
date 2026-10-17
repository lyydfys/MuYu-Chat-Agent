#include "mca/qnn/qnn_semantic_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

std::string QnnSemanticRun::evidence_phase(mca::diffusion::DiffusionScheduler& scheduler, QnnSharedPreviewPublisher& preview_publisher) {
    const std::vector<double> executed_timesteps(
        timesteps.begin() + static_cast<std::ptrdiff_t>(img2img_begin_index),
        timesteps.end());
    const std::vector<double> executed_sigmas = scheduler.sigmas().empty()
        ? std::vector<double>{}
        : std::vector<double>(
            scheduler.sigmas().begin() + static_cast<std::ptrdiff_t>(img2img_begin_index),
            scheduler.sigmas().end());
    const size_t semantic_unet_execution_count = ultra_fix_request.enabled
        ? execution_contract.scheduler.expected_unet_execution_count
        : unet_execution_count;
    QnnNativeEffectiveEvidence native_evidence{
        effective_timetable_count,
        semantic_unet_execution_count,
        conditioning_evidence.tokenizer_backend,
        conditioning_evidence.token_count,
        conditioning_evidence.embedding_disk_data_type,
        width,
        height,
        unet.graph_name,
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
    native_evidence.conditioning_execution_mode = qnn_token_conditioning
        ? "qnn_text_encoder"
        : conditioning_evidence.conditioning_execution_mode;
    native_evidence.conditioning_backend = qnn_token_conditioning
        ? "QNN"
        : conditioning_evidence.conditioning_backend;
    native_evidence.conditioning_graph = qnn_token_conditioning
        ? loaded_text_encoder_graph
        : conditioning_evidence.conditioning_graph;
    native_evidence.conditioning_graph_sha256 = conditioning_graph_sha256;
    // External MNN conditioning reports the branch layout serialized in the
    // artifact. Shared SDXL retains that dual layout for ABI compatibility
    // even when its UNet later executes only the positive branch.
    native_evidence.conditioning_order = qnn_token_conditioning
        ? (execution_contract.use_cfg ? "negative_then_positive" : "positive_only")
        : conditioning_evidence.conditioning_order;
    native_evidence.conditioning_encoder_execution_count = qnn_token_conditioning
        ? text_encoder_execute_count
        : conditioning_evidence.conditioning_encoder_execution_count;
    native_evidence.text_encoder_execution_count = text_encoder_execute_count;
    if (text_encoder_asset_evidence.required) {
        native_evidence.consumed_text_encoder_path = text_encoder_asset_evidence.relative_path;
        native_evidence.consumed_text_encoder_sha256 = text_encoder_asset_evidence.sha256;
        native_evidence.consumed_text_encoder_size_bytes = text_encoder_asset_evidence.size_bytes;
        native_evidence.consumed_text_encoder_asset_verified = true;
        native_evidence.language_proof_sha256 = text_encoder_asset_evidence.language_proof_sha256;
    }
    native_evidence.conditioning_artifact_consumed = conditioning_artifact_consumed;
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
    if (!bind_qnn_consumed_prompt_to_encoder_evidence(
            text_encoder_asset_evidence,
            loaded_text_encoder_graph,
            conditioning_evidence,
            &native_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "prompt_to_encoder_evidence_invalid",
            "PROMPT_TO_ENCODER_EVIDENCE_INVALID",
            error);
    }
    native_evidence.runtime_session_mode = request_controlnet
        ? (qnn_token_conditioning
            ? "shared_text_unet_controlnet_vae"
            : "shared_unet_controlnet_vae")
        : (qnn_token_conditioning ? "shared_text_unet_vae" : "shared_unet_vae");
    native_evidence.task_mode = task_mode;
    native_evidence.full_timetable_count = full_timetable_count;
    native_evidence.effective_denoise_steps = effective_timetable_count;
    native_evidence.control_strength = request_controlnet ? control_strength : 0.0;
    if (request_encoder_conditioned) {
        native_evidence.input_image_path = input_image_path;
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
        native_evidence.input_image_preprocess = string_field(
            params_json, "inputImagePreprocess");
        native_evidence.input_image_execution_count = 1U;
        native_evidence.input_image_source_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageSourceWidth")));
        native_evidence.input_image_source_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageSourceHeight")));
        native_evidence.input_image_oriented_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageOrientedWidth")));
        native_evidence.input_image_oriented_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageOrientedHeight")));
        native_evidence.input_image_exif_orientation = static_cast<int>(
            long_field(params_json, "inputImageExifOrientation"));
        native_evidence.input_image_tensor_width = static_cast<size_t>(width);
        native_evidence.input_image_tensor_height = static_cast<size_t>(height);
        native_evidence.input_image_tensor_channels = 3U;
        native_evidence.encoder_graph_input_height =
            img2img_encoder.encoder_input_dimensions.size() == 4U
            ? img2img_encoder.encoder_input_dimensions[2]
            : 0U;
        native_evidence.encoder_graph_input_width =
            img2img_encoder.encoder_input_dimensions.size() == 4U
            ? img2img_encoder.encoder_input_dimensions[3]
            : 0U;
        native_evidence.encoder_graph_output_height =
            img2img_encoder.encoder_output_dimensions.size() == 4U
            ? img2img_encoder.encoder_output_dimensions[2]
            : 0U;
        native_evidence.encoder_graph_output_width =
            img2img_encoder.encoder_output_dimensions.size() == 4U
            ? img2img_encoder.encoder_output_dimensions[3]
            : 0U;
        native_evidence.encoder_latent_sha256 = img2img_encoder.latent_sha256;
        native_evidence.encoder_context_sha256 = img2img_encoder.context_sha256;
        native_evidence.encoder_graph_name = img2img_encoder.graph_name;
        native_evidence.encoder_input_name = img2img_encoder.input_name;
        native_evidence.encoder_mean_output_name = img2img_encoder.mean_name;
        native_evidence.encoder_std_output_name = img2img_encoder.std_name;
        native_evidence.encoder_input_dtype = img2img_encoder.input_dtype;
        native_evidence.encoder_mean_dtype = img2img_encoder.mean_dtype;
        native_evidence.encoder_std_dtype = img2img_encoder.std_dtype;
        native_evidence.encoder_input_buffer_sha256 =
            img2img_encoder.input_buffer_sha256;
        native_evidence.encoder_mean_buffer_sha256 =
            img2img_encoder.mean_buffer_sha256;
        native_evidence.encoder_std_buffer_sha256 =
            img2img_encoder.std_buffer_sha256;
        native_evidence.encoder_runtime_mode = ultra_fix_request.enabled
            ? "standalone_tiled_encoder_then_shared_tiled_unet_vae"
            : request_inpaint && inpaint_contract.requires_masked_latent()
            ? "standalone_dual_encode_then_shared_unet_vae"
            : "standalone_encoder_then_shared_unet_vae";
        native_evidence.encoder_context_load_count = 1U;
        native_evidence.encoder_execution_count = img2img_encoder.execution_count;
        native_evidence.encoder_posterior_sample_count = img2img_encoder.latents.size();
        native_evidence.encoder_latent_scaling_factor = 0.18215;
        native_evidence.encoder_context_released_before_shared_session =
            img2img_encoder.context_released_before_shared_session;
        native_evidence.strength = img2img_strength;
        native_evidence.img2img_begin_index = img2img_begin_index;
        native_evidence.full_timetable_count = full_timetable_count;
        native_evidence.effective_denoise_steps = effective_timetable_count;
        native_evidence.img2img_add_noise_applied = img2img_add_noise_applied;
        native_evidence.img2img_add_noise_begin_index = img2img_begin_index;
        native_evidence.img2img_add_noise_timestep = img2img_add_noise_timestep;
        native_evidence.img2img_noise_checksum = fixed_width_lower_hex_u64(
            img2img_noise_checksum);
    }
    if (request_inpaint) {
        native_evidence.mask_image_path = mask_image_path;
        native_evidence.mask_image_sha256 = mask_image_sha256;
        native_evidence.mask_image_source_bytes = long_field(
            params_json, "maskImageSizeBytes");
        native_evidence.mask_image_tensor_path = string_field(
            params_json, "maskImageTensorPath");
        native_evidence.mask_image_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageTensorSha256"));
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
        native_evidence.mask_image_full_tensor_path = string_field(
            params_json, "maskImageFullTensorPath");
        native_evidence.mask_image_full_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageFullTensorSha256"));
        native_evidence.mask_image_full_tensor_bytes = long_field(
            params_json, "maskImageFullTensorBytes");
        native_evidence.mask_image_full_tensor_dtype = string_field(
            params_json, "maskImageFullTensorDtype");
        native_evidence.mask_image_full_tensor_layout = string_field(
            params_json, "maskImageFullTensorLayout");
        native_evidence.mask_image_full_tensor_range = string_field(
            params_json, "maskImageFullTensorRange");
        native_evidence.mask_image_full_tensor_preprocess = string_field(
            params_json, "maskImageFullTensorPreprocess");
        native_evidence.mask_image_source_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageSourceWidth")));
        native_evidence.mask_image_source_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageSourceHeight")));
        native_evidence.mask_image_oriented_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageOrientedWidth")));
        native_evidence.mask_image_oriented_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageOrientedHeight")));
        native_evidence.mask_image_exif_orientation = static_cast<int>(
            long_field(params_json, "maskImageExifOrientation"));
        native_evidence.mask_image_repaint_pixel_count = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageRepaintPixelCount")));
        native_evidence.mask_image_latent_repaint_pixel_count = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageLatentRepaintPixelCount")));
        native_evidence.masked_input_image_tensor_path = string_field(
            params_json, "maskedInputImageTensorPath");
        native_evidence.masked_input_image_tensor_sha256 =
            img2img_encoder.masked_input_tensor_sha256;
        native_evidence.masked_input_image_tensor_bytes = long_field(
            params_json, "maskedInputImageTensorBytes");
        native_evidence.masked_input_image_tensor_dtype = string_field(
            params_json, "maskedInputImageTensorDtype");
        native_evidence.masked_input_image_tensor_layout = string_field(
            params_json, "maskedInputImageTensorLayout");
        native_evidence.masked_input_image_tensor_range = string_field(
            params_json, "maskedInputImageTensorRange");
        native_evidence.masked_input_image_tensor_preprocess = string_field(
            params_json, "maskedInputImageTensorPreprocess");
        native_evidence.masked_input_buffer_sha256 =
            img2img_encoder.masked_input_buffer_sha256;
        native_evidence.masked_input_mean_buffer_sha256 =
            img2img_encoder.masked_mean_buffer_sha256;
        native_evidence.masked_input_std_buffer_sha256 =
            img2img_encoder.masked_std_buffer_sha256;
        native_evidence.masked_input_latent_sha256 =
            img2img_encoder.masked_latent_sha256;
        native_evidence.masked_input_encoder_execution_count =
            img2img_encoder.masked_execution_count;
        native_evidence.masked_input_posterior_sample_count =
            img2img_encoder.masked_latents.size();
        native_evidence.masked_input_encoder_execute_ms =
            img2img_encoder.masked_execute_ms;
        native_evidence.mask_image_execution_count = 1U;
        native_evidence.inpaint_topology = inpaint_contract.topology_name();
        native_evidence.inpaint_mask_unet_bind_count = inpaint_mask_bind_count;
        native_evidence.inpaint_preserve_step_count = inpaint_preserve_step_count;
        native_evidence.inpaint_latent_blend_count = inpaint_preserve_step_count;
        native_evidence.inpaint_source_encoder_execution_count = 1U;
        native_evidence.inpaint_preserved_latent_checksum = fixed_width_lower_hex_u64(
            inpaint_preserved_latent_checksum);
        native_evidence.inpaint_source_noise_sha256 = inpaint_source_noise_sha256;
        native_evidence.inpaint_source_noise_use_count = inpaint_source_noise_use_count;
        native_evidence.inpaint_final_mode =
            "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend";
        native_evidence.inpaint_pixel_blend_levels = static_cast<size_t>(
            std::max(0, inpaint_pixel_blend_levels));
        native_evidence.inpaint_pixel_blend_checksum = fixed_width_lower_hex_u64(
            inpaint_pixel_blend_checksum);
        native_evidence.inpaint_pixel_blend_applied = inpaint_pixel_blend_applied;
        const bool native_mask_binding_complete = inpaint_contract.requires_mask_binding()
            ? inpaint_mask_bind_count == unet_execution_count && unet_execution_count > 0U
            : inpaint_mask_bind_count == 0U;
        native_evidence.mask_image_consumed = native_mask_binding_complete &&
            inpaint_preserve_step_count == effective_timetable_count &&
            !inpaint_mask.empty() && !inpaint_full_mask.empty() &&
            inpaint_pixel_blend_applied;
        native_evidence.inpaint_unmasked_preservation_applied =
            inpaint_preserve_step_count == effective_timetable_count &&
            effective_timetable_count > 0U && inpaint_pixel_blend_applied;
    }
    if (request_controlnet) {
        native_evidence.control_image_path = prepared_control_image.canonical_path;
        native_evidence.control_image_sha256 = prepared_control_image.encoded_sha256;
        native_evidence.control_image_preprocessed_sha256 =
            prepared_control_image.preprocessed_sha256;
        native_evidence.control_image_preprocess =
            mca::qnn::controlnet::control_image_preprocess_wire_name(
                prepared_control_image.preprocess_mode);
        native_evidence.control_image_execution_count = 1U;
        native_evidence.controlnet_execution_count = controlnet_execution_count;
        native_evidence.controlnet_residual_tensor_count =
            controlnet_layout.residual_bindings.size();
        native_evidence.controlnet_residual_write_count =
            controlnet_residual_write_count;
        native_evidence.controlnet_residual_unet_reuse_count =
            execution_contract.use_cfg ? timesteps.size() : 0U;
        native_evidence.controlnet_conditioning_branch = "positive";
        const size_t expected_controlnet_executions = timesteps.size();
        native_evidence.controlnet_input_consumed =
            controlnet_execution_count == expected_controlnet_executions &&
            controlnet_residual_write_count ==
                expected_controlnet_executions * controlnet_layout.residual_bindings.size() &&
            controlnet_input_buffer_sha256.size() == 64U;
        native_evidence.controlnet_graph_name = controlnet.graph_name;
        native_evidence.control_image_source_width = prepared_control_image.source_width;
        native_evidence.control_image_source_height = prepared_control_image.source_height;
        native_evidence.control_image_source_channels = prepared_control_image.source_channels;
        native_evidence.control_image_tensor_width = prepared_control_image.tensor_width;
        native_evidence.control_image_tensor_height = prepared_control_image.tensor_height;
        native_evidence.control_image_tensor_channels = prepared_control_image.tensor_channels;
        native_evidence.control_image_exif_orientation = prepared_control_image.exif_orientation;
        native_evidence.control_image_edge_pixel_count = prepared_control_image.edge_pixel_count;
        native_evidence.control_image_tensor_checksum = checksum_float_vector(control_image_tensor);
        native_evidence.controlnet_scaled_residual_checksum =
            controlnet_scaled_residual_checksum;
        native_evidence.controlnet_input_buffer_sha256 =
            controlnet_input_buffer_sha256;
    }
    if (ultra_fix_request.enabled) {
        const size_t source_width = native_evidence.input_image_oriented_width;
        const size_t source_height = native_evidence.input_image_oriented_height;
        const size_t target_width = static_cast<size_t>(ultra_fix_request.target_width);
        const size_t target_height = static_cast<size_t>(ultra_fix_request.target_height);
        if (source_width == 0U || source_height == 0U ||
            final_vae_graph_execution_count != ultra_fix_plan.tiles.size() ||
            output_bytes <= 0LL || output_sha256.size() != 64U) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "ultrafix_final_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix source geometry, tiled decoder counts, or committed output evidence is invalid.");
        }
        const bool fit_by_width =
            static_cast<uint64_t>(target_width) * source_height >=
            static_cast<uint64_t>(target_height) * source_width;
        size_t resized_width = target_width;
        size_t resized_height = target_height;
        if (fit_by_width) {
            resized_height = static_cast<size_t>(
                static_cast<uint64_t>(source_height) * target_width / source_width);
        } else {
            resized_width = static_cast<size_t>(
                static_cast<uint64_t>(source_width) * target_height / source_height);
        }
        const std::string plan_descriptor =
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        QnnUltraFixExecutionEvidence ultra_fix_evidence;
        ultra_fix_evidence.request = ultra_fix_request;
        ultra_fix_evidence.plan = ultra_fix_plan;
        ultra_fix_evidence.tile_plan_sha256 =
            mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(plan_descriptor.data()),
                plan_descriptor.size());
        ultra_fix_evidence.source_width = source_width;
        ultra_fix_evidence.source_height = source_height;
        ultra_fix_evidence.source_resized_width = resized_width;
        ultra_fix_evidence.source_resized_height = resized_height;
        ultra_fix_evidence.source_crop_left = (resized_width - target_width) / 2U;
        ultra_fix_evidence.source_crop_top = (resized_height - target_height) / 2U;
        ultra_fix_evidence.encoder_context_load_count = 1U;
        ultra_fix_evidence.encoder_graph_execution_count =
            img2img_encoder.execution_count;
        ultra_fix_evidence.encoder_tile_success_count =
            img2img_encoder.execution_count;
        ultra_fix_evidence.inversion_step_count = ultra_fix_inversion_step_count;
        ultra_fix_evidence.inversion_graph_execution_count =
            ultra_fix_inversion_graph_execution_count;
        ultra_fix_evidence.inversion_tile_success_count =
            ultra_fix_inversion_graph_execution_count;
        ultra_fix_evidence.refinement_step_count = ultra_fix_refinement_step_count;
        ultra_fix_evidence.refinement_positive_graph_execution_count =
            ultra_fix_refinement_positive_execution_count;
        ultra_fix_evidence.refinement_negative_graph_execution_count =
            ultra_fix_refinement_negative_execution_count;
        ultra_fix_evidence.refinement_tile_success_count =
            ultra_fix_refinement_positive_execution_count +
            ultra_fix_refinement_negative_execution_count;
        ultra_fix_evidence.decoder_graph_execution_count =
            final_vae_graph_execution_count;
        ultra_fix_evidence.decoder_tile_success_count =
            final_vae_graph_execution_count;
        ultra_fix_evidence.physical_unet_graph_execution_count =
            ultra_fix_physical_unet_execution_count;
        ultra_fix_evidence.quality_step_evaluation_count =
            ultra_fix_quality_step_evaluation_count;
        ultra_fix_evidence.noise_injection_step_count =
            ultra_fix_noise_injection_step_count;
        ultra_fix_evidence.noise_injection_seed_fingerprint =
            ultra_fix_noise_injection_seed_fingerprint;
        ultra_fix_evidence.noise_injection_checksum =
            ultra_fix_noise_injection_checksum;
        ultra_fix_evidence.structure_guidance_step_count =
            ultra_fix_structure_guidance_step_count;
        ultra_fix_evidence.structure_guidance_checksum =
            ultra_fix_structure_guidance_checksum;
        ultra_fix_evidence.trajectory_noise_checksum =
            ultra_fix_trajectory_noise_checksum;
        ultra_fix_evidence.sample_method = qnn_scheduler_wire_name(
            execution_contract.scheduler.config.algorithm);
        ultra_fix_evidence.native_scheduler = ultra_fix_evidence.sample_method;
        ultra_fix_evidence.output_sha256 = output_sha256;
        ultra_fix_evidence.output_bytes = output_bytes;
        ultra_fix_evidence.output_atomic_commit = true;
        if (ultra_fix_evidence.tile_plan_sha256.size() != 64U ||
            resized_width < target_width || resized_height < target_height) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "ultrafix_tile_plan_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix tile plan or center-cover geometry lacks deterministic evidence.");
        }
        native_evidence.ultra_fix_json =
            qnn_ultrafix_evidence_json(ultra_fix_evidence);
        native_evidence.ultra_fix_output_sha256 = output_sha256;
        native_evidence.ultra_fix_output_bytes = output_bytes;
        native_evidence.ultra_fix_output_atomic_commit = true;
        native_evidence.ultra_fix_positive_diffusion_model_compute_count =
            ultra_fix_inversion_graph_execution_count +
            ultra_fix_refinement_positive_execution_count;
        native_evidence.ultra_fix_negative_diffusion_model_compute_count =
            ultra_fix_refinement_negative_execution_count;
        native_evidence.ultra_fix_auxiliary_diffusion_model_compute_count = 0U;
        native_evidence.ultra_fix_sampling_pass_count = 1U;
        native_evidence.ultra_fix_total_unet_execution_count =
            ultra_fix_physical_unet_execution_count;
    }
    const std::string native_effective = qnn_native_effective_json(
        execution_contract,
        native_evidence);
    const size_t unet_execute_average_count = ultra_fix_request.enabled
        ? ultra_fix_physical_unet_execution_count
        : unet_execution_count;
    std::ostringstream out;
    out << std::setprecision(17) << "{"
        << "\"ok\":true,"
        << "\"backend\":\"qnn_htp\","
        << "\"pipelineProbe\":false,"
        << "\"semanticReady\":true,"
        << "\"npuActive\":true,"
        << "\"qnnGraphExecution\":true,"
        << "\"nativeExecution\":true,"
        << "\"fallback\":false,"
        << "\"executionStage\":\"semantic_generation_passed\","
        << "\"profileId\":" << quote(execution_contract.profile_id) << ","
        << "\"profileRevision\":" << execution_contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\","
        << "\"scheduler\":" << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ","
        << "\"predictionType\":" << quote(qnn_prediction_wire_name(execution_contract.scheduler.config.prediction_type)) << ","
        << "\"steps\":" << steps << ","
        << "\"timetableCount\":" << effective_timetable_count << ","
        << "\"unetExecutionCount\":" << unet_execution_count << ","
        << "\"cfgScale\":" << execution_contract.cfg_scale << ","
        << "\"useCfg\":" << (execution_contract.use_cfg ? "true" : "false") << ","
        << "\"unconditionalBranch\":" << (execution_contract.use_cfg ? "true" : "false") << ","
        << "\"tokenizerBackend\":" << quote(conditioning_evidence.tokenizer_backend) << ","
        << "\"tokenCount\":" << conditioning_evidence.token_count << ","
        << "\"promptWeightingSupported\":" << (execution_contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingApplied\":" << (conditioning_evidence.prompt_weighting_applied ? "true" : "false") << ","
        << "\"promptWeightingExecutionSupported\":" << (!qnn_token_conditioning && execution_contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingExecutionMode\":" << quote(prompt_weighting_execution_mode) << ","
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
        << "\"embeddingDiskDataType\":" << quote(conditioning_evidence.embedding_disk_data_type) << ","
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(execution_contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << execution_contract.vae_scaling_factor << ","
        << qnn_pixel_range_evidence_json(
            execution_contract.pixel_range,
            pixel_range_evidence) << ","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << "\"seed\":" << execution_contract.seed << ","
        << "\"graphName\":" << quote(unet.graph_name) << ","
        << "\"taskMode\":" << quote(task_mode) << ","
        << "\"batchCount\":1,"
        << "\"inputImagePath\":" << quote(native_evidence.input_image_path) << ","
        << "\"maskImagePath\":" << quote(native_evidence.mask_image_path) << ","
        << "\"controlImagePath\":" << quote(native_evidence.control_image_path) << ","
        << "\"inputImageExecutionCount\":"
        << native_evidence.input_image_execution_count << ","
        << "\"maskImageExecutionCount\":"
        << native_evidence.mask_image_execution_count << ","
        << "\"maskImageSha256\":" << quote(native_evidence.mask_image_sha256) << ","
        << "\"maskImageSizeBytes\":"
        << native_evidence.mask_image_source_bytes << ","
        << "\"maskImageSourceWidth\":" << native_evidence.mask_image_source_width << ","
        << "\"maskImageSourceHeight\":" << native_evidence.mask_image_source_height << ","
        << "\"maskImageOrientedWidth\":" << native_evidence.mask_image_oriented_width << ","
        << "\"maskImageOrientedHeight\":" << native_evidence.mask_image_oriented_height << ","
        << "\"maskImageExifOrientation\":" << native_evidence.mask_image_exif_orientation << ","
        << "\"maskImageSourceReadByNative\":false,"
        << "\"maskImageSourceValidation\":"
        << quote(request_inpaint ? "android_preprocess_provenance" : "none") << ","
        << "\"maskImageTensorPath\":" << quote(native_evidence.mask_image_tensor_path) << ","
        << "\"maskImageTensorSha256\":" << quote(native_evidence.mask_image_tensor_sha256) << ","
        << "\"maskImageTensorBytes\":" << native_evidence.mask_image_tensor_bytes << ","
        << "\"maskImageTensorShape\":[1,1,"
        << (request_inpaint ? inpaint_contract.height : 0U) << ","
        << (request_inpaint ? inpaint_contract.width : 0U) << "],"
        << "\"maskImageTensorDtype\":" << quote(native_evidence.mask_image_tensor_dtype) << ","
        << "\"maskImageTensorLayout\":" << quote(native_evidence.mask_image_tensor_layout) << ","
        << "\"maskImageTensorRange\":" << quote(native_evidence.mask_image_tensor_range) << ","
        << "\"maskImageTensorPreprocess\":" << quote(native_evidence.mask_image_tensor_preprocess) << ","
        << "\"maskImageFullTensorPath\":" << quote(native_evidence.mask_image_full_tensor_path) << ","
        << "\"maskImageFullTensorSha256\":" << quote(native_evidence.mask_image_full_tensor_sha256) << ","
        << "\"maskImageFullTensorBytes\":" << native_evidence.mask_image_full_tensor_bytes << ","
        << "\"maskImageFullTensorShape\":[1,1,"
        << (request_inpaint ? height : 0) << "," << (request_inpaint ? width : 0) << "],"
        << "\"maskImageFullTensorDtype\":" << quote(native_evidence.mask_image_full_tensor_dtype) << ","
        << "\"maskImageFullTensorLayout\":" << quote(native_evidence.mask_image_full_tensor_layout) << ","
        << "\"maskImageFullTensorRange\":" << quote(native_evidence.mask_image_full_tensor_range) << ","
        << "\"maskImageFullTensorPreprocess\":"
        << quote(native_evidence.mask_image_full_tensor_preprocess) << ","
        << "\"maskImageRepaintPixelCount\":" << native_evidence.mask_image_repaint_pixel_count << ","
        << "\"maskImageLatentRepaintPixelCount\":"
        << native_evidence.mask_image_latent_repaint_pixel_count << ","
        << "\"maskedInputImageTensorPath\":"
        << quote(native_evidence.masked_input_image_tensor_path) << ","
        << "\"maskedInputImageTensorSha256\":"
        << quote(native_evidence.masked_input_image_tensor_sha256) << ","
        << "\"maskedInputImageTensorBytes\":"
        << native_evidence.masked_input_image_tensor_bytes << ","
        << "\"maskedInputImageTensorShape\":[1,3,"
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? height : 0)
        << ","
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? width : 0)
        << "],"
        << "\"maskedInputImageTensorDtype\":"
        << quote(native_evidence.masked_input_image_tensor_dtype) << ","
        << "\"maskedInputImageTensorLayout\":"
        << quote(native_evidence.masked_input_image_tensor_layout) << ","
        << "\"maskedInputImageTensorRange\":"
        << quote(native_evidence.masked_input_image_tensor_range) << ","
        << "\"maskedInputImageTensorPreprocess\":"
        << quote(native_evidence.masked_input_image_tensor_preprocess) << ","
        << "\"maskedInputBufferSha256\":" << quote(native_evidence.masked_input_buffer_sha256) << ","
        << "\"maskedInputMeanBufferSha256\":"
        << quote(native_evidence.masked_input_mean_buffer_sha256) << ","
        << "\"maskedInputStdBufferSha256\":"
        << quote(native_evidence.masked_input_std_buffer_sha256) << ","
        << "\"maskedInputLatentSha256\":" << quote(native_evidence.masked_input_latent_sha256) << ","
        << "\"maskedInputLatentShape\":[1,4,"
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? height / 8 : 0)
        << ","
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? width / 8 : 0)
        << "],"
        << "\"maskedInputEncoderExecutionCount\":"
        << native_evidence.masked_input_encoder_execution_count << ","
        << "\"maskedInputPosteriorSampleCount\":"
        << native_evidence.masked_input_posterior_sample_count << ","
        << "\"maskedInputEncoderExecuteMs\":" << native_evidence.masked_input_encoder_execute_ms << ","
        << "\"inpaintTopology\":" << quote(native_evidence.inpaint_topology) << ","
        << "\"inpaintMaskUnetBindCount\":" << native_evidence.inpaint_mask_unet_bind_count << ","
        << "\"inpaintPreserveStepCount\":" << native_evidence.inpaint_preserve_step_count << ","
        << "\"inpaintLatentBlendCount\":" << native_evidence.inpaint_latent_blend_count << ","
        << "\"inpaintSourceEncoderExecutionCount\":"
        << native_evidence.inpaint_source_encoder_execution_count << ","
        << "\"inpaintPreservedLatentChecksum\":"
        << quote(native_evidence.inpaint_preserved_latent_checksum) << ","
        << "\"inpaintSourceNoiseSha256\":"
        << quote(native_evidence.inpaint_source_noise_sha256) << ","
        << "\"inpaintSourceNoiseUseCount\":"
        << native_evidence.inpaint_source_noise_use_count << ","
        << "\"inpaintFinalMode\":" << quote(native_evidence.inpaint_final_mode) << ","
        << "\"inpaintPixelBlendLevels\":" << native_evidence.inpaint_pixel_blend_levels << ","
        << "\"inpaintPixelBlendChecksum\":"
        << quote(native_evidence.inpaint_pixel_blend_checksum) << ","
        << "\"inpaintPixelBlendApplied\":"
        << (native_evidence.inpaint_pixel_blend_applied ? "true" : "false") << ","
        << "\"inpaintMaskConsumed\":" << (native_evidence.mask_image_consumed ? "true" : "false") << ","
        << "\"inpaintUnmaskedPreservationApplied\":"
        << (native_evidence.inpaint_unmasked_preservation_applied ? "true" : "false") << ","
        << "\"controlImageExecutionCount\":"
        << native_evidence.control_image_execution_count << ","
        << "\"controlImageSha256\":" << quote(native_evidence.control_image_sha256) << ","
        << "\"controlImagePreprocessedSha256\":"
        << quote(native_evidence.control_image_preprocessed_sha256) << ","
        << "\"controlImagePreprocess\":" << quote(native_evidence.control_image_preprocess) << ","
        << "\"inputImageSha256\":" << quote(native_evidence.input_image_sha256) << ","
        << "\"inputImageSizeBytes\":" << (request_encoder_conditioned
            ? long_field(params_json, "inputImageSizeBytes")
            : 0LL) << ","
        << "\"inputImageSourceWidth\":" << native_evidence.input_image_source_width << ","
        << "\"inputImageSourceHeight\":" << native_evidence.input_image_source_height << ","
        << "\"inputImageOrientedWidth\":" << native_evidence.input_image_oriented_width << ","
        << "\"inputImageOrientedHeight\":" << native_evidence.input_image_oriented_height << ","
        << "\"inputImageExifOrientation\":" << native_evidence.input_image_exif_orientation << ","
        << "\"inputImageSourceReadByNative\":false,"
        << "\"inputImageSourceValidation\":"
        << quote(request_encoder_conditioned ? "android_preprocess_provenance" : "none") << ","
        << "\"inputImageTensorPath\":" << quote(native_evidence.input_image_tensor_path) << ","
        << "\"inputImageTensorSha256\":"
        << quote(native_evidence.input_image_tensor_sha256) << ","
        << "\"inputImageTensorBytes\":" << native_evidence.input_image_tensor_bytes << ","
        << "\"inputImageTensorShape\":[1,3," << native_evidence.input_image_tensor_height
        << "," << native_evidence.input_image_tensor_width << "],"
        << "\"inputImageTensorWidth\":" << native_evidence.input_image_tensor_width << ","
        << "\"inputImageTensorHeight\":" << native_evidence.input_image_tensor_height << ","
        << "\"inputImageTensorChannels\":" << native_evidence.input_image_tensor_channels << ","
        << "\"inputImageTensorDtype\":" << quote(native_evidence.input_image_tensor_dtype) << ","
        << "\"inputImageTensorLayout\":" << quote(native_evidence.input_image_tensor_layout) << ","
        << "\"inputImageTensorRange\":" << quote(native_evidence.input_image_tensor_range) << ","
        << "\"inputImagePreprocess\":" << quote(native_evidence.input_image_preprocess) << ","
        << "\"encoderContextLoadCount\":" << native_evidence.encoder_context_load_count << ","
        << "\"encoderExecutionCount\":" << native_evidence.encoder_execution_count << ","
        << "\"encoderGraphName\":" << quote(native_evidence.encoder_graph_name) << ","
        << "\"encoderContextSha256\":" << quote(native_evidence.encoder_context_sha256) << ","
        << "\"encoderInputName\":" << quote(native_evidence.encoder_input_name) << ","
        << "\"encoderMeanOutputName\":"
        << quote(native_evidence.encoder_mean_output_name) << ","
        << "\"encoderStdOutputName\":"
        << quote(native_evidence.encoder_std_output_name) << ","
        << "\"encoderInputDtype\":" << quote(native_evidence.encoder_input_dtype) << ","
        << "\"encoderMeanDtype\":" << quote(native_evidence.encoder_mean_dtype) << ","
        << "\"encoderStdDtype\":" << quote(native_evidence.encoder_std_dtype) << ","
        << "\"encoderInputShape\":[1,3," << native_evidence.encoder_graph_input_height
        << "," << native_evidence.encoder_graph_input_width << "],"
        << "\"encoderMeanShape\":[1,4," << native_evidence.encoder_graph_output_height
        << "," << native_evidence.encoder_graph_output_width << "],"
        << "\"encoderStdShape\":[1,4," << native_evidence.encoder_graph_output_height
        << "," << native_evidence.encoder_graph_output_width << "],"
        << "\"encoderInputBufferSha256\":"
        << quote(native_evidence.encoder_input_buffer_sha256) << ","
        << "\"encoderMeanBufferSha256\":"
        << quote(native_evidence.encoder_mean_buffer_sha256) << ","
        << "\"encoderStdBufferSha256\":"
        << quote(native_evidence.encoder_std_buffer_sha256) << ","
        << "\"encoderLatentSha256\":" << quote(native_evidence.encoder_latent_sha256) << ","
        << "\"posteriorSampling\":"
        << quote(request_encoder_conditioned
            ? "mean_plus_std_times_normal_mt19937_domain_v1"
            : "none") << ","
        << "\"posteriorSampleCount\":"
        << native_evidence.encoder_posterior_sample_count << ","
        << "\"encoderLatentScalingFactor\":"
        << native_evidence.encoder_latent_scaling_factor << ","
        << "\"encoderContextReleasedBeforeSharedSession\":"
        << (native_evidence.encoder_context_released_before_shared_session ? "true" : "false") << ","
        << "\"encoderRuntimeMode\":" << quote(native_evidence.encoder_runtime_mode) << ","
        << "\"controlImageSourceWidth\":" << prepared_control_image.source_width << ","
        << "\"controlImageSourceHeight\":" << prepared_control_image.source_height << ","
        << "\"controlImageSourceChannels\":" << prepared_control_image.source_channels << ","
        << "\"controlImageOrientedWidth\":" << prepared_control_image.oriented_width << ","
        << "\"controlImageOrientedHeight\":" << prepared_control_image.oriented_height << ","
        << "\"controlImageExifOrientation\":"
        << (request_controlnet ? prepared_control_image.exif_orientation : 0) << ","
        << "\"controlImageTensorWidth\":" << prepared_control_image.tensor_width << ","
        << "\"controlImageTensorHeight\":" << prepared_control_image.tensor_height << ","
        << "\"controlImageTensorChannels\":"
        << (request_controlnet ? prepared_control_image.tensor_channels : 0U) << ","
        << "\"controlImageTensorLayout\":"
        << quote(request_controlnet &&
                 controlnet_layout.image_shape.layout == mca::image::SpatialTensorLayout::Nchw
                     ? "NCHW"
                     : (request_controlnet ? "NHWC" : "NONE")) << ","
        << "\"controlImageEdgePixelCount\":" << prepared_control_image.edge_pixel_count << ","
        << "\"controlImageTensorChecksum\":" << checksum_float_vector(control_image_tensor) << ","
        << "\"strength\":" << native_evidence.strength << ","
        << "\"fullTimetableCount\":" << native_evidence.full_timetable_count << ","
        << "\"effectiveDenoiseSteps\":" << native_evidence.effective_denoise_steps << ","
        << "\"img2imgBeginIndex\":" << native_evidence.img2img_begin_index << ","
        << "\"img2imgAddNoiseApplied\":"
        << (native_evidence.img2img_add_noise_applied ? "true" : "false") << ","
        << "\"img2imgAddNoiseBeginIndex\":"
        << native_evidence.img2img_add_noise_begin_index << ","
        << "\"img2imgAddNoiseTimestep\":"
        << native_evidence.img2img_add_noise_timestep << ","
        << "\"img2imgNoiseChecksum\":" << quote(native_evidence.img2img_noise_checksum) << ","
        << "\"controlStrength\":" << native_evidence.control_strength << ","
        << "\"controlStrengthApplied\":" << (request_controlnet ? "true" : "false") << ","
        << "\"clipSkip\":-1,"
        << "\"controlNetExecutionCount\":" << controlnet_execution_count << ","
        << "\"controlNetResidualTensorCount\":"
        << native_evidence.controlnet_residual_tensor_count << ","
        << "\"controlNetResidualWriteCount\":" << controlnet_residual_write_count << ","
        << "\"controlNetResidualUnetReuseCount\":"
        << native_evidence.controlnet_residual_unet_reuse_count << ","
        << "\"controlNetConditioningBranch\":"
        << quote(native_evidence.controlnet_conditioning_branch) << ","
        << "\"controlNetInputConsumed\":"
        << (native_evidence.controlnet_input_consumed ? "true" : "false") << ","
        << "\"controlNetInputBufferSha256\":"
        << quote(native_evidence.controlnet_input_buffer_sha256) << ","
        << "\"controlNetScaledResidualChecksum\":"
        << controlnet_scaled_residual_checksum << ","
        << "\"controlNetGraph\":" << quote(native_evidence.controlnet_graph_name) << ",";
    if (ultra_fix_request.enabled) {
        out << "\"ultraFix\":" << native_evidence.ultra_fix_json << ","
            << "\"strengthMechanism\":\"ddim_inversion\","
            << "\"outputSizeBytes\":" << native_evidence.ultra_fix_output_bytes << ","
            << "\"actualDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_total_unet_execution_count << ","
            << "\"actualPositiveDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_positive_diffusion_model_compute_count << ","
            << "\"actualNegativeDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_negative_diffusion_model_compute_count << ","
            << "\"actualAuxiliaryDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_auxiliary_diffusion_model_compute_count << ",";
    }
    out << "\"nativeEffective\":" << native_effective << ","
        << "\"timesteps\":" << qnn_double_array_json(executed_timesteps) << ","
        << "\"sigmas\":" << qnn_double_array_json(executed_sigmas) << ","
        << "\"initNoiseSigma\":" << scheduler.init_noise_sigma() << ","
        << "\"scaleModelInput\":" << (execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
        << "\"textEncoderExecutionCount\":" << text_encoder_execute_count << ","
        << "\"vaeExecutionCount\":" << final_vae_graph_execution_count << ","
        << "\"finalVaeExecutionCount\":1,"
        << "\"finalVaeGraphExecutionCount\":" << final_vae_graph_execution_count << ","
        << "\"vaeTileCount\":" << final_vae_graph_execution_count << ","
        << "\"vaeTiled\":" << (final_vae_graph_execution_count > 1U ? "true" : "false") << ","
        << "\"previewRequested\":" << (preview_contract.requested ? "true" : "false") << ","
        << "\"previewMode\":" << quote(preview_contract.enabled ? "vae" : "none") << ","
        << "\"previewInterval\":" << preview_contract.interval << ","
        << "\"previewVaeExecutionAttemptCount\":"
        << preview_publisher.audit().vae_execution_attempt_count << ","
        << "\"previewVaeExecutionCount\":"
        << preview_publisher.audit().vae_execution_count << ","
        << "\"previewVaeExecutionMsTotal\":"
        << preview_publisher.audit().vae_execution_ms_total << ","
        << "\"previewPublicationCount\":"
        << preview_publisher.audit().publication_count << ","
        << "\"previewLastStep\":" << preview_publisher.audit().last_step << ","
        << "\"previewLastRevision\":" << preview_publisher.audit().last_revision << ","
        << "\"previewFailureCode\":"
        << quote(preview_publisher.audit().failure_code) << ","
        << "\"previewDegraded\":"
        << (preview_publisher.audit().stopped_after_failure() ? "true" : "false") << ","
        << "\"effectiveVaeHostScale\":" << effective_vae_host_scale << ","
        << "\"nativeGenerationSequence\":" << g_qnn_image_generation_sequence.load() << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << g_qnn_image_generation_stage_mask.load() << ","
        << "\"nativeDetailStageMaskHex\":\""
        << mca::qnn::image_stage_mask_hex(g_qnn_image_generation_detail_stage_mask.load())
        << "\","
        << "\"runtimeSessionMode\":" << quote(native_evidence.runtime_session_mode) << ","
        << "\"message\":" << quote(request_inpaint
            ? (inpaint_contract.requires_masked_latent()
                ? "QNN inpaint completed with source and masked-image VAE encodes, an explicit nine-channel UNet contract, strength-derived denoising, per-step source preservation, final QNN VAE decode, and a full-resolution Laplacian mask blend."
                : "QNN inpaint completed with one source VAE encode, a topology-checked four-channel UNet, strength-derived denoising, per-step source-latent blending with one shared noise tensor, final QNN VAE decode, and a full-resolution Laplacian mask blend.")
            : (request_img2img
            ? "QNN img2img completed with a strict standalone VAE encoder, released encoder context, strength-derived scheduler tail, add-noise, shared QNN UNet, and QNN VAE decoder."
            : (request_controlnet
            ? "QNN control generation completed with verified image preprocessing, one positive-conditioned ControlNet execution per scheduler step, residual reuse across CFG UNet branches, the resolved scheduler, Control-UNet, and QNN VAE decode."
            : (qnn_token_conditioning
                ? "QNN semantic generation completed with QNN CLIP text encoding, the resolved shared scheduler, QNN UNet, and QNN VAE decoder."
                : "QNN semantic generation completed with MNN text embeddings, the resolved shared scheduler, QNN UNet, and QNN VAE decoder.")))) << ","
        << "\"conditioningFormat\":" << quote(conditioning_format) << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
        << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
        << "\"textEncoderContextLoadMs\":" << text_encoder_context_load_ms << ","
        << "\"textEncoderExecuteMsTotal\":" << text_encoder_execute_ms_total << ","
        << "\"textEncoderEmbeddingWidth\":" << text_encoder_embedding_width << ","
        << "\"textEncoderInputTensor\":" << quote(text_encoder_input_tensor) << ","
        << "\"textEncoderInputDataType\":" << quote(text_encoder_input_data_type) << ","
        << "\"textEncoderInputElements\":" << text_encoder_input_elements << ","
        << "\"encoderContextLoadMs\":" << img2img_encoder.context_load_ms << ","
        << "\"encoderExecuteMs\":" << img2img_encoder.execute_ms << ","
        << "\"encoderExecuteMsTotal\":"
        << (img2img_encoder.execute_ms + img2img_encoder.masked_execute_ms) << ","
        << "\"conditioningArtifactConsumed\":" << (conditioning_artifact_consumed ? "true" : "false") << ","
        << "\"controlNetContextLoadMs\":" << (request_controlnet ? controlnet.context_load_ms : 0) << ","
        << "\"controlNetExecuteMsTotal\":" << controlnet_execute_ms_total << ","
        << "\"controlNetExecuteMsAvg\":"
        << (controlnet_execution_count > 0
            ? controlnet_execute_ms_total / static_cast<long long>(controlnet_execution_count)
            : 0) << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
        << "\"unetExecuteMsAvg\":"
        << (unet_execute_average_count > 0U
            ? unet_execute_ms_total /
                static_cast<long long>(unet_execute_average_count)
            : 0) << ","
        << "\"vaeExecuteMs\":" << vae_execute_ms << ","
        << "\"embeddingElements\":" << embeddings.size() << ","
        << "\"textElements\":" << text_elements << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"outputPath\":" << quote(output_path) << ","
        << "\"outputBytes\":" << output_bytes << ","
        << "\"outputSha256\":" << quote(output_sha256) << ","
        << "\"outputAtomicCommit\":true,"
        << "\"textEncoderGraph\":" << quote(loaded_text_encoder_graph) << ","
        << "\"controlNetLoadedGraph\":" << quote(native_evidence.controlnet_graph_name) << ","
        << "\"unetGraph\":" << quote(unet.graph_name) << ","
        << "\"vaeGraph\":" << quote(vae.graph_name) << ","
        << "\"debug\":{"
        << "\"timestepFirst\":"
        << (executed_timesteps.empty() ? 0.0 : executed_timesteps.front()) << ","
        << "\"timestepLast\":"
        << (executed_timesteps.empty() ? 0.0 : executed_timesteps.back()) << ","
        << "\"textEncoderInputs\":" << text_encoder_inputs_debug << ","
        << "\"textEncoderOutputs\":" << text_encoder_outputs_debug << ","
        << "\"controlNetInputs\":"
        << (request_controlnet ? qnn_tensor_list_debug_json(controlnet.inputs) : "[]") << ","
        << "\"controlNetOutputs\":"
        << (request_controlnet ? qnn_tensor_list_debug_json(controlnet.outputs) : "[]") << ","
        << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
        << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
        << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
        << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
        << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
        << "\"noiseUncondStats\":" << float_vector_stats_json(noise_uncond) << ","
        << "\"noiseCondStats\":" << float_vector_stats_json(noise_cond) << ","
        << "\"pixelStats\":" << float_vector_stats_json(pixels) << "},"
        << "\"executionRuntime\":" << runtime_probe_json(runtime_session.selected_runtime) << ","
        << "\"htpArchVersion\":" << runtime_session.selected_runtime.htp_arch_version << ","
        << "\"bundle\":" << bundle_probe_json(bundle)
        << "}";
    const std::string success_json = out.str();
    const auto release_failure_json = [&](const std::string& component,
                                          const std::string& detail) {
        ::unlink(output_path.c_str());
        return qnn_semantic_failure_json(
            component + "_release_failed",
            "NATIVE_RESOURCE_RELEASE_FAILED",
            detail.empty()
                ? "QNN resources did not release cleanly after final image generation."
                : detail);
    };
    std::string release_error;
    if (!vae.close_checked(&release_error)) {
        return release_failure_json("vae", release_error);
    }
    if (!controlnet.close_checked(&release_error)) {
        return release_failure_json("controlnet", release_error);
    }
    if (!unet.close_checked(&release_error)) {
        return release_failure_json("unet", release_error);
    }
    if (!runtime_session.close_checked(&release_error)) {
        return release_failure_json("runtime", release_error);
    }
    return success_json;
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
