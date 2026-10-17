#include "mca/qnn/qnn_semantic_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

std::string QnnSemanticRun::stage_runner_phase(mca::diffusion::DiffusionScheduler& scheduler, QnnImageGenerationScope& generation, QnnSharedPreviewPublisher& preview_publisher) {
    int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
    if (request_inpaint) sample_index = inpaint_contract.sample_index;
    int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep", "time"});
    if (timestep_index < 0 && unet.inputs.size() > 1) timestep_index = 1;
    int text_index = tensor_index_by_name(unet.inputs, {"text_embedding", "encoder_hidden", "hidden"});
    if (text_index < 0 && unet.inputs.size() > 2) text_index = 2;
    if (unet.inputs.size() < 3 || sample_index >= static_cast<int>(unet.inputs.size()) ||
        timestep_index < 0 || text_index < 0 || unet.outputs.empty() ||
        vae.inputs.empty() || vae.outputs.empty()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SD1.5 semantic generation expects UNet(sample,timestep,text) and VAE(latent) tensors.\"}";
    }

    mca::image::SpatialTensorShape graph_sample_shape;
    mca::image::SpatialTensorShape unet_output_shape;
    mca::image::SpatialTensorShape vae_input_shape;
    mca::image::SpatialTensorShape vae_output_shape;
    const uint32_t expected_sample_channels = request_inpaint
        ? inpaint_contract.sample_channels
        : 4U;
    if (!mca::image::resolve_spatial_tensor_shape(
                unet.inputs[sample_index].dimensions,
                expected_sample_channels,
                &graph_sample_shape,
                &error) ||
        !mca::image::resolve_spatial_tensor_shape(
                unet.outputs[0].dimensions,
                4U,
                &unet_output_shape,
                &error) ||
        !mca::image::resolve_spatial_tensor_shape(
                vae.inputs[0].dimensions,
                4U,
                &vae_input_shape,
                &error) ||
        !mca::image::resolve_spatial_tensor_shape(
                vae.outputs[0].dimensions,
                3U,
                &vae_output_shape,
                &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"latent_layout_unsupported\",\"message\":") +
            quote(error) + "}";
    }
    if (ultra_fix_request.enabled) {
        if (!img2img_encoder.ultra_fix || request_inpaint || request_controlnet ||
            !mca::image::build_ultrafix_tile_plan(
                img2img_encoder.encoder_input_dimensions,
                img2img_encoder.encoder_output_dimensions,
                unet.inputs[sample_index].dimensions,
                unet.outputs[0].dimensions,
                vae.inputs[0].dimensions,
                vae.outputs[0].dimensions,
                static_cast<size_t>(execution_contract.width),
                static_cast<size_t>(execution_contract.height),
                static_cast<size_t>(ultra_fix_request.tile_size),
                ultra_fix_request.overlap,
                8U,
                &ultra_fix_plan,
                &error) ||
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan) !=
                mca::image::ultrafix_tile_plan_descriptor(
                    img2img_encoder.ultra_fix_plan)) {
            return qnn_semantic_failure_json(
                "ultrafix_graph_topology_mismatch",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                error.empty()
                    ? "Loaded shared QNN encoder, UNet, and VAE graphs do not agree on one UltraFix tile plan."
                    : error);
        }
    } else if (graph_sample_shape.batch != 1U || unet_output_shape.batch != 1U ||
        vae_input_shape.batch != 1U || vae_output_shape.batch != 1U ||
        graph_sample_shape.height != unet_output_shape.height ||
        graph_sample_shape.width != unet_output_shape.width ||
        unet_output_shape.height != vae_input_shape.height ||
        unet_output_shape.width != vae_input_shape.width ||
        unet_output_shape.width * 8U != static_cast<size_t>(execution_contract.width) ||
        unet_output_shape.height * 8U != static_cast<size_t>(execution_contract.height) ||
        vae_output_shape.width != static_cast<size_t>(execution_contract.width) ||
        vae_output_shape.height != static_cast<size_t>(execution_contract.height)) {
            return qnn_semantic_failure_json(
                "latent_resolution_mismatch",
                "EXECUTION_CONTRACT_MISMATCH",
                "Resolved width and height must both match the native UNet latent and VAE tensors at the required 8x scale.");
    }
    // Diffusion state and VAE input are always four-channel NCHW. A dedicated
    // inpaint UNet may expose a nine-channel graph sample, assembled only at
    // the graph binding boundary from latent + mask + masked-image latent.
    const uint64_t latent_elements = ultra_fix_request.enabled
        ? ultra_fix_plan.full_latent.element_count()
        : unet_output_shape.element_count();
    text_elements = qnn_tensor_element_count(unet.inputs[text_index].tensor);
    if (latent_elements == 0 || text_elements == 0 ||
        (!ultra_fix_request.enabled && vae_input_shape.element_count() != latent_elements)) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN UNet and VAE latent tensor shapes are not compatible.\"}";
    }
    if (request_controlnet) {
        if (!qnn_resolve_controlnet_layout(
                controlnet,
                unet,
                sample_index,
                timestep_index,
                text_index,
                execution_contract.width,
                execution_contract.height,
                &controlnet_layout,
                &error)) {
            return qnn_semantic_failure_json(
                "controlnet_tensor_layout_unsupported",
                "CONTROLNET_GRAPH_CONTRACT_INVALID",
                error);
        }
        if (!mca::qnn::controlnet::load_prepared_control_image(
                control_image_path,
                control_image_sha256,
                static_cast<uint32_t>(controlnet_layout.image_shape.width),
                static_cast<uint32_t>(controlnet_layout.image_shape.height),
                control_image_preprocess,
                &prepared_control_image,
                &error)) {
            return qnn_semantic_failure_json(
                "control_image_decode_failed",
                "CONTROL_IMAGE_INVALID",
                error);
        }
        if (!qnn_control_image_tensor_for_layout(
                prepared_control_image,
                controlnet_layout.image_shape,
                &control_image_tensor,
                &error)) {
            return qnn_semantic_failure_json(
                "control_image_tensor_bind_failed",
                "CONTROLNET_GRAPH_CONTRACT_INVALID",
                error);
        }
    }
    if (request_inpaint) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        const uint64_t mask_elements =
            static_cast<uint64_t>(inpaint_contract.width) *
            static_cast<uint64_t>(inpaint_contract.height);
        const std::vector<uint32_t> expected_mask_shape{
            1U, 1U, inpaint_contract.height, inpaint_contract.width};
        const std::vector<uint32_t> expected_full_mask_shape{
            1U,
            1U,
            static_cast<uint32_t>(execution_contract.height),
            static_cast<uint32_t>(execution_contract.width),
        };
        const std::string mask_tensor_path = string_field(params_json, "maskImageTensorPath");
        const std::string expected_mask_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageTensorSha256"));
        std::string actual_mask_tensor_sha256;
        const std::string full_mask_tensor_path = string_field(
            params_json, "maskImageFullTensorPath");
        const std::string expected_full_mask_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageFullTensorSha256"));
        std::string actual_full_mask_tensor_sha256;
        const long long mask_source_width = long_field(params_json, "maskImageSourceWidth");
        const long long mask_source_height = long_field(params_json, "maskImageSourceHeight");
        const long long mask_oriented_width = long_field(params_json, "maskImageOrientedWidth");
        const long long mask_oriented_height = long_field(params_json, "maskImageOrientedHeight");
        const long long input_oriented_width = long_field(params_json, "inputImageOrientedWidth");
        const long long input_oriented_height = long_field(params_json, "inputImageOrientedHeight");
        const long long full_repaint_pixel_count = long_field(
            params_json, "maskImageRepaintPixelCount");
        const long long mask_exif_orientation = long_field(
            params_json, "maskImageExifOrientation");
        if (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
            string_field(params_json, "inpaintMaskConvention") !=
                "white_repaint_black_preserve" ||
            mask_image_path.empty() || !valid_inpaint_sha256(mask_image_sha256) ||
            long_field(params_json, "maskImageSizeBytes") <= 0LL ||
            mask_source_width <= 0LL || mask_source_height <= 0LL ||
            mask_oriented_width <= 0LL || mask_oriented_height <= 0LL ||
            mask_exif_orientation < 1LL || mask_exif_orientation > 8LL ||
            mask_oriented_width != input_oriented_width ||
            mask_oriented_height != input_oriented_height ||
            full_repaint_pixel_count < 0LL ||
            full_repaint_pixel_count >
                static_cast<long long>(execution_contract.width) * execution_contract.height ||
            mask_tensor_path.empty() || !valid_inpaint_sha256(expected_mask_tensor_sha256) ||
            uint_array_field(params_json, "maskImageTensorShape") != expected_mask_shape ||
            string_field(params_json, "maskImageTensorDtype") != "float32-le" ||
            string_field(params_json, "maskImageTensorLayout") != "NCHW" ||
            string_field(params_json, "maskImageTensorRange") != "ZERO_TO_ONE" ||
            string_field(params_json, "maskImageTensorPreprocess") !=
                "source_aligned_center_crop_linear_grayscale_area_latent_nchw_v2" ||
            long_field(params_json, "maskImageTensorBytes") !=
                static_cast<long long>(mask_elements * sizeof(float)) ||
            !read_float_binary_file(
                mask_tensor_path,
                &inpaint_mask,
                &error,
                &actual_mask_tensor_sha256) ||
            normalized_contract_enum(actual_mask_tensor_sha256) != expected_mask_tensor_sha256 ||
            inpaint_mask.size() != static_cast<size_t>(mask_elements) ||
            long_field(params_json, "maskImageLatentRepaintPixelCount") !=
                static_cast<long long>(std::count_if(
                    inpaint_mask.begin(), inpaint_mask.end(), [](float value) {
                        return value > 0.0f;
                    })) ||
            full_mask_tensor_path.empty() ||
            !valid_inpaint_sha256(expected_full_mask_tensor_sha256) ||
            uint_array_field(params_json, "maskImageFullTensorShape") !=
                expected_full_mask_shape ||
            string_field(params_json, "maskImageFullTensorDtype") != "float32-le" ||
            string_field(params_json, "maskImageFullTensorLayout") != "NCHW" ||
            string_field(params_json, "maskImageFullTensorRange") != "ZERO_TO_ONE" ||
            string_field(params_json, "maskImageFullTensorPreprocess") !=
                "source_aligned_center_crop_linear_grayscale_full_nchw_v1" ||
            long_field(params_json, "maskImageFullTensorBytes") !=
                static_cast<long long>(
                    static_cast<uint64_t>(execution_contract.width) *
                    static_cast<uint64_t>(execution_contract.height) * sizeof(float)) ||
            !read_float_binary_file(
                full_mask_tensor_path,
                &inpaint_full_mask,
                &error,
                &actual_full_mask_tensor_sha256) ||
            normalized_contract_enum(actual_full_mask_tensor_sha256) !=
                expected_full_mask_tensor_sha256 ||
            inpaint_full_mask.size() != static_cast<size_t>(
                static_cast<uint64_t>(execution_contract.width) *
                static_cast<uint64_t>(execution_contract.height)) ||
            !mca::qnn::inpaint::validate_mask_values(
                inpaint_full_mask,
                static_cast<uint32_t>(execution_contract.width),
                static_cast<uint32_t>(execution_contract.height),
                &error) ||
            full_repaint_pixel_count != static_cast<long long>(std::count_if(
                inpaint_full_mask.begin(), inpaint_full_mask.end(), [](float value) {
                    return value > 0.0f;
                })) ||
            !mca::qnn::inpaint::validate_mask_values(
                inpaint_mask,
                inpaint_contract.width,
                inpaint_contract.height,
                &error)) {
            if (error.empty()) {
                error = "Prepared QNN inpaint mask identity, geometry, or normalized tensor contract is invalid.";
            }
            return qnn_semantic_failure_json(
                "inpaint_mask_tensor_invalid",
                "QNN_INPAINT_MASK_TENSOR_INVALID",
                error);
        }
        for (uint32_t latent_y = 0U; latent_y < inpaint_contract.height; ++latent_y) {
            for (uint32_t latent_x = 0U; latent_x < inpaint_contract.width; ++latent_x) {
                double sum = 0.0;
                for (uint32_t offset_y = 0U; offset_y < 8U; ++offset_y) {
                    const size_t row = static_cast<size_t>(latent_y * 8U + offset_y) *
                        static_cast<size_t>(execution_contract.width) + latent_x * 8U;
                    for (uint32_t offset_x = 0U; offset_x < 8U; ++offset_x) {
                        sum += inpaint_full_mask[row + offset_x];
                    }
                }
                const float expected_value = static_cast<float>(sum / 64.0);
                const float actual_value = inpaint_mask[
                    static_cast<size_t>(latent_y) * inpaint_contract.width + latent_x];
                if (std::fabs(actual_value - expected_value) > 1.0e-6f) {
                    return qnn_semantic_failure_json(
                        "inpaint_mask_scale_mismatch",
                        "QNN_INPAINT_MASK_TENSOR_INVALID",
                        "The latent inpaint mask is not the deterministic area reduction of the committed full-resolution mask.");
                }
            }
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (inpaint_contract.requires_masked_latent()) {
            inpaint_masked_latent = img2img_encoder.masked_latents;
            if (img2img_encoder.masked_execution_count != 1U ||
                inpaint_masked_latent.size() != static_cast<size_t>(latent_elements) ||
                !valid_inpaint_sha256(img2img_encoder.masked_latent_sha256)) {
                return qnn_semantic_failure_json(
                    "inpaint_masked_latent_invalid",
                    "QNN_INPAINT_MASKED_LATENT_INVALID",
                    "The concatenated QNN inpaint graph did not obtain a real masked-image latent from the loaded VAE encoder.");
            }
        } else if (img2img_encoder.masked_execution_count != 0U ||
                   !img2img_encoder.masked_latents.empty()) {
            return qnn_semantic_failure_json(
                "inpaint_masked_latent_unexpected",
                "QNN_INPAINT_EXECUTION_EVIDENCE_INVALID",
                "A four-channel QNN inpaint graph performed an unnecessary masked-image VAE encode.");
        }
    }
    const size_t conditioning_branch_count = execution_contract.use_cfg ? 2u : 1u;
    const size_t required_embedding_elements =
        static_cast<size_t>(text_elements) * conditioning_branch_count;
    if (qnn_token_conditioning && embeddings.size() != required_embedding_elements) {
        std::ostringstream message;
        message << "QNN text encoder output does not match the UNet conditioning input. "
                << "Encoder produced " << embeddings.size()
                << " total elements across " << conditioning_branch_count << " branch(es)"
                << " (width=" << text_encoder_embedding_width
                << "), UNet expects " << text_elements << ".";
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_unet_shape_mismatch\",\"message\":") +
            quote(message.str()) + "}";
    }
    if (embeddings.size() < required_embedding_elements) {
        std::ostringstream message;
        message << "Prompt embeddings are too small. Need at least "
                << required_embedding_elements << " f32 elements, got " << embeddings.size() << ".";
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"embedding_shape_unsupported\",\"message\":") +
            quote(message.str()) + "}";
    }

    steps = execution_contract.scheduler.steps;
    timesteps = scheduler.timesteps();
    effective_vae_host_scale = qnn_effective_vae_host_scale(execution_contract);
    generation.set_steps(static_cast<int>(
        ultra_fix_request.enabled
            ? effective_timetable_count * 2U
            : effective_timetable_count));
    generation.set_phase(kQnnImageSampling);
    std::mt19937 rng(execution_contract.seed);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    latents = std::vector<float>(static_cast<size_t>(latent_elements), 0.0f);
    const float initial_noise_scale = static_cast<float>(scheduler.init_noise_sigma());
    img2img_add_noise_applied = false;
    img2img_add_noise_timestep = 0.0;
    img2img_noise_checksum = 0U;
    std::vector<float> diffusion_noise;
    std::vector<float> ultra_fix_clean_latents;
    std::vector<float> ultra_fix_equivalent_noise;
    inpaint_source_noise_use_count = 0U;
    if (request_encoder_conditioned) {
        if (img2img_encoder.latents.size() != latents.size()) {
            return qnn_semantic_failure_json(
                "encoder_latent_shape_invalid",
                "ENCODER_LATENT_LAYOUT_INVALID",
                "Shared QNN encoder latent shape does not match the loaded UNet input tensor.");
        }
        if (ultra_fix_request.enabled) {
            latents = img2img_encoder.latents;
            ultra_fix_clean_latents = latents;
        } else {
            diffusion_noise.assign(latents.size(), 0.0f);
            for (float& value : diffusion_noise) value = normal(rng);
            img2img_noise_checksum = checksum_float_vector(diffusion_noise);
            if (request_inpaint) {
                inpaint_source_noise_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                    reinterpret_cast<const uint8_t*>(diffusion_noise.data()),
                    diffusion_noise.size() * sizeof(float));
            }
            if (img2img_noise_checksum == 0U ||
                (request_inpaint && !valid_inpaint_sha256(inpaint_source_noise_sha256)) ||
                !scheduler.add_noise(
                    img2img_encoder.latents,
                    diffusion_noise,
                    img2img_begin_index,
                    &latents,
                    &error)) {
                return qnn_semantic_failure_json(
                    "img2img_add_noise_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    error.empty()
                        ? "Shared QNN img2img diffusion noise lacks execution evidence."
                        : error);
            }
            img2img_add_noise_applied = true;
            img2img_add_noise_timestep = timesteps[img2img_begin_index];
            if (request_inpaint) ++inpaint_source_noise_use_count;
        }
    } else {
        for (float& value : latents) value = normal(rng) * initial_noise_scale;
    }

    const float* negative_embedding = embeddings.data();
    const bool has_two_embedding_branches =
        embeddings.size() >= static_cast<size_t>(text_elements * 2u);
    const float* positive_embedding = embeddings.data() +
        ((execution_contract.use_cfg || has_two_embedding_branches)
            ? static_cast<size_t>(text_elements)
            : 0u);
    unet_execute_ms_total = 0;
    unet_execution_count = 0;
    controlnet_execute_ms_total = 0;
    controlnet_execution_count = 0;
    controlnet_residual_write_count = 0;
    controlnet_scaled_residual_checksum = 0U;
    inpaint_mask_bind_count = 0U;
    inpaint_preserve_step_count = 0U;
    inpaint_preserved_latent_checksum = 0U;
    ultra_fix_inversion_step_count = 0U;
    ultra_fix_inversion_graph_execution_count = 0U;
    ultra_fix_refinement_step_count = 0U;
    ultra_fix_refinement_positive_execution_count = 0U;
    ultra_fix_refinement_negative_execution_count = 0U;
    ultra_fix_physical_unet_execution_count = 0U;
    ultra_fix_quality_step_evaluation_count = 0U;
    ultra_fix_noise_injection_step_count = 0U;
    ultra_fix_noise_injection_checksum = 0U;
    ultra_fix_structure_guidance_step_count = 0U;
    ultra_fix_structure_guidance_checksum = 0U;
    ultra_fix_trajectory_noise_checksum = 0U;
    const auto run_task_unet_once = [&](const std::vector<float>& sample,
                                        double timestep,
                                        const float* embedding,
                                        std::vector<float>* output,
                                        long long* execute_ms) -> bool {
        if (request_inpaint) {
            const bool executed = qnn_run_inpaint_unet_once(
                unet,
                inpaint_contract,
                timestep_index,
                text_index,
                sample,
                inpaint_mask,
                inpaint_masked_latent,
                timestep,
                embedding,
                static_cast<size_t>(text_elements),
                output,
                execute_ms,
                &error);
            if (executed && inpaint_contract.requires_mask_binding()) {
                ++inpaint_mask_bind_count;
            }
            return executed;
        }
        return qnn_run_unet_once(
            unet,
            sample_index,
            timestep_index,
            text_index,
            sample,
            timestep,
            embedding,
            static_cast<size_t>(text_elements),
            output,
            execute_ms,
            &error);
    };
    if (ultra_fix_request.enabled) {
        mca::image::UltraFixNoiseLevel clean_level;
        for (size_t reverse = timesteps.size(); reverse > img2img_begin_index; --reverse) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            const size_t target_index = reverse - 1U;
            const bool first_hop = target_index + 1U == timesteps.size();
            const size_t evaluation_index = first_hop ? target_index : target_index + 1U;
            mca::image::UltraFixNoiseLevel target_level;
            mca::image::UltraFixNoiseLevel source_level;
            if (!qnn_ultrafix_noise_level(
                    scheduler, target_index, &target_level, &error) ||
                (!first_hop && !qnn_ultrafix_noise_level(
                    scheduler, evaluation_index, &source_level, &error))) {
                return qnn_semantic_failure_json(
                    "ultrafix_inversion_schedule_invalid",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            if (first_hop) source_level = clean_level;
            std::vector<float> model_input;
            if (!mca::image::scale_ultrafix_inversion_model_input(
                    latents,
                    source_level,
                    first_hop ? target_level : source_level,
                    &model_input,
                    &error)) {
                return qnn_semantic_failure_json(
                    "ultrafix_inversion_scale_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            std::vector<float> epsilon;
            if (!qnn_run_ultrafix_tiled_unet_branch(
                    unet,
                    sample_index,
                    timestep_index,
                    text_index,
                    ultra_fix_plan,
                    model_input,
                    timesteps[evaluation_index],
                    positive_embedding,
                    static_cast<size_t>(text_elements),
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
                    "ultrafix_inversion_execute_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            latents = std::move(model_input);
            ++ultra_fix_inversion_step_count;
            generation.set_step(static_cast<int>(ultra_fix_inversion_step_count));
        }
        if (ultra_fix_inversion_step_count != effective_timetable_count) {
            return qnn_semantic_failure_json(
                "ultrafix_inversion_incomplete",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix did not complete every DDIM inversion hop.");
        }
        const std::string seed_descriptor =
            mca::image::ultrafix_noise_seed_descriptor(
                execution_contract.seed,
                effective_timetable_count);
        ultra_fix_noise_injection_seed_fingerprint =
            mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(seed_descriptor.data()),
                seed_descriptor.size());
        if (ultra_fix_noise_injection_seed_fingerprint.size() != 64U) {
            return qnn_semantic_failure_json(
                "ultrafix_quality_seed_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix could not bind its deterministic quality-noise seed domain.");
        }
        if (effective_timetable_count > 1U) {
            mca::image::UltraFixNoiseLevel inverted_level;
            if (!qnn_ultrafix_noise_level(
                    scheduler,
                    img2img_begin_index,
                    &inverted_level,
                    &error) ||
                !mca::image::ultrafix_equivalent_noise(
                    ultra_fix_clean_latents,
                    latents,
                    inverted_level,
                    &ultra_fix_equivalent_noise,
                    &error)) {
                return qnn_semantic_failure_json(
                    "ultrafix_trajectory_noise_failed",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    error);
            }
            ultra_fix_trajectory_noise_checksum =
                mca::image::ultrafix_tensor_checksum(ultra_fix_equivalent_noise);
            if (ultra_fix_trajectory_noise_checksum == 0U) {
                return qnn_semantic_failure_json(
                    "ultrafix_trajectory_noise_evidence_invalid",
                    "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                    "QNN UltraFix trajectory noise lacks deterministic tensor evidence.");
            }
        }
    }
    for (size_t step = img2img_begin_index; step < timesteps.size(); ++step) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        const size_t physical_step = step - img2img_begin_index;
        generation.set_step(static_cast<int>(
            (ultra_fix_request.enabled ? effective_timetable_count : 0U) +
            physical_step));
        std::vector<float> model_input;
        if (!scheduler.scale_model_input(latents, step, &model_input, &error)) {
            return qnn_semantic_failure_json(
                "scheduler_scale_failed",
                "SCHEDULER_EXECUTION_FAILED",
                error);
        }
        long long execute_ms = 0;
        if (request_controlnet) {
            // The published ControlNet pipeline conditions the graph with the
            // positive embedding once per scheduler step, then supplies the
            // same residual set to both UNet branches. Running ControlNet again
            // for the unconditional branch changes the calibrated execution
            // and doubles the dominant graph cost.
            if (!qnn_run_controlnet_once(
                    controlnet,
                    controlnet_layout,
                    unet,
                    model_input,
                    timesteps[step],
                    positive_embedding,
                    static_cast<size_t>(text_elements),
                    control_image_tensor,
                    control_strength,
                    &controlnet_residual_write_count,
                    &controlnet_scaled_residual_checksum,
                    &controlnet_input_buffer_sha256,
                    &execute_ms,
                    &error)) {
                return qnn_semantic_failure_json(
                    "controlnet_execute_failed",
                    "CONTROLNET_EXECUTION_FAILED",
                    error);
            }
            controlnet_execute_ms_total += execute_ms;
            ++controlnet_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        if (execution_contract.use_cfg) {
            execute_ms = 0;
            const bool unconditioned_ok = ultra_fix_request.enabled
                ? qnn_run_ultrafix_tiled_unet_branch(
                    unet,
                    sample_index,
                    timestep_index,
                    text_index,
                    ultra_fix_plan,
                    model_input,
                    timesteps[step],
                    negative_embedding,
                    static_cast<size_t>(text_elements),
                    &noise_uncond,
                    &unet_execute_ms_total,
                    &ultra_fix_refinement_negative_execution_count,
                    &error)
                : run_task_unet_once(
                    model_input,
                    timesteps[step],
                    negative_embedding,
                    &noise_uncond,
                    &execute_ms);
            if (!unconditioned_ok) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_uncond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            if (!ultra_fix_request.enabled) {
                unet_execute_ms_total += execute_ms;
                ++unet_execution_count;
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
        const bool conditioned_ok = ultra_fix_request.enabled
            ? qnn_run_ultrafix_tiled_unet_branch(
                unet,
                sample_index,
                timestep_index,
                text_index,
                ultra_fix_plan,
                model_input,
                timesteps[step],
                positive_embedding,
                static_cast<size_t>(text_elements),
                &noise_cond,
                &unet_execute_ms_total,
                &ultra_fix_refinement_positive_execution_count,
                &error)
            : run_task_unet_once(
                model_input,
                timesteps[step],
                positive_embedding,
                &noise_cond,
                &execute_ms);
        if (!conditioned_ok) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (!ultra_fix_request.enabled) {
            unet_execute_ms_total += execute_ms;
            ++unet_execution_count;
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if ((execution_contract.use_cfg && noise_uncond.size() != latents.size()) ||
            noise_cond.size() != latents.size()) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_output_shape_unsupported\",\"message\":\"UNet output does not exactly match the latent tensor.\"}";
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
                "cfg_failed",
                "EXECUTION_CONTRACT_INVALID",
                error);
        }
        mca::image::UltraFixQualitySchedule ultra_fix_quality_schedule;
        const bool ultra_fix_has_next_step = ultra_fix_request.enabled &&
            step + 1U < timesteps.size();
        if (ultra_fix_request.enabled) {
            if (!mca::image::resolve_ultrafix_quality_schedule(
                    timesteps[step],
                    ultra_fix_has_next_step,
                    &ultra_fix_quality_schedule,
                    &error)) {
                return qnn_semantic_failure_json(
                    "ultrafix_quality_schedule_invalid",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    error);
            }
            if (ultra_fix_has_next_step) {
                ++ultra_fix_quality_step_evaluation_count;
            }
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
                        "ultrafix_noise_injection_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error);
                }
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
                "scheduler_step_failed",
                "SCHEDULER_EXECUTION_FAILED",
                error);
        }
        latents = std::move(step_result.previous_sample);
        if (ultra_fix_request.enabled) {
            ++ultra_fix_refinement_step_count;
            if (ultra_fix_quality_schedule.evaluate_structure_guidance) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> trajectory_reference;
                if (ultra_fix_equivalent_noise.empty() ||
                    !scheduler.add_noise(
                        ultra_fix_clean_latents,
                        ultra_fix_equivalent_noise,
                        step + 1U,
                        &trajectory_reference,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "ultrafix_structure_trajectory_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error.empty()
                            ? "QNN UltraFix structure guidance lacks its inverted trajectory noise."
                            : error);
                }
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> structured_latents;
                uint64_t structured_checksum = 0U;
                const size_t blur_radius =
                    mca::image::ultrafix_structure_blur_radius(
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
                        "ultrafix_structure_guidance_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error);
                }
                ultra_fix_structure_guidance_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_structure_guidance_checksum,
                        static_cast<uint32_t>(physical_step),
                        structured_checksum);
                latents = std::move(structured_latents);
                ++ultra_fix_structure_guidance_step_count;
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            }
        }
        if (request_inpaint) {
            std::vector<float> preserved_source;
            if (step + 1U < timesteps.size()) {
                if (!scheduler.add_noise(
                        img2img_encoder.latents,
                        diffusion_noise,
                        step + 1U,
                        &preserved_source,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "inpaint_source_noise_failed",
                        "QNN_INPAINT_PRESERVATION_FAILED",
                        error);
                }
                ++inpaint_source_noise_use_count;
            } else {
                preserved_source = img2img_encoder.latents;
            }
            std::vector<float> preserved_latents;
            if (!mca::qnn::inpaint::preserve_unmasked_latent(
                    latents,
                    preserved_source,
                    inpaint_mask,
                    inpaint_contract.width,
                    inpaint_contract.height,
                    &preserved_latents,
                    &error)) {
                return qnn_semantic_failure_json(
                    "inpaint_preservation_failed",
                    "QNN_INPAINT_PRESERVATION_FAILED",
                    error);
            }
            latents = std::move(preserved_latents);
            ++inpaint_preserve_step_count;
            inpaint_preserved_latent_checksum = checksum_float_vector(latents);
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        const int completed_step = static_cast<int>(physical_step + 1U);
        generation.set_step(static_cast<int>(
            (ultra_fix_request.enabled ? effective_timetable_count : 0U) +
            static_cast<size_t>(completed_step)));
        if (!ultra_fix_request.enabled) {
            const QnnPreviewPublishOutcome preview_outcome =
                preview_publisher.publish_if_due(
                    completed_step,
                    static_cast<int>(effective_timetable_count),
                    latents,
                    effective_vae_host_scale,
                    vae,
                    execution_contract.pixel_range);
            if (preview_outcome == QnnPreviewPublishOutcome::Cancelled) {
                return qnn_image_generation_cancelled_json();
            }
        }
        generation.set_phase(kQnnImageSampling);
    }
    return std::string();
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
