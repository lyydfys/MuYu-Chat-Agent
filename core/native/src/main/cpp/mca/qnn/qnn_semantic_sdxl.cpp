#include "mca/qnn/qnn_semantic_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

std::string QnnSemanticRun::sdxl_phase(mca::diffusion::DiffusionScheduler& scheduler, QnnImageGenerationScope& generation, QnnSharedPreviewPublisher& preview_publisher) {
    if (request_sdxl) {
        const int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
        int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep"});
        if (timestep_index < 0 && unet.inputs.size() > 2) timestep_index = 2;
        int hidden_index = tensor_index_by_name(unet.inputs, {"encoder_hidden_states", "encoder_hidden", "hidden_states", "hidden"});
        int time_ids_index = tensor_index_by_name(unet.inputs, {"time_ids", "timeids"});
        int pooled_index = tensor_index_by_name(unet.inputs, {"text_embeds", "text_embed", "pooled"});
        if (unet.inputs.size() < 5 || sample_index >= static_cast<int>(unet.inputs.size()) ||
            timestep_index < 0 || hidden_index < 0 || time_ids_index < 0 || pooled_index < 0 ||
            unet.outputs.empty() || vae.inputs.empty() || vae.outputs.empty()) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SDXL semantic generation expects UNet(sample,encoder_hidden_states,timestamp,time_ids,text_embeds) and VAE(latent) tensors.\"}";
        }

        mca::image::SpatialTensorShape latent_shape;
        mca::image::SpatialTensorShape unet_output_shape;
        if (!mca::image::resolve_spatial_tensor_shape(
                    unet.inputs[sample_index].dimensions,
                    4U,
                    &latent_shape,
                    &error) ||
            !mca::image::resolve_spatial_tensor_shape(
                    unet.outputs[0].dimensions,
                    4U,
                    &unet_output_shape,
                    &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"latent_layout_unsupported\",\"message\":") +
                quote(error) + "}";
        }
        if (latent_shape.height != unet_output_shape.height ||
            latent_shape.width != unet_output_shape.width ||
            latent_shape.width * 8U != static_cast<size_t>(execution_contract.width) ||
            latent_shape.height * 8U != static_cast<size_t>(execution_contract.height)) {
            return qnn_semantic_failure_json(
                "latent_resolution_mismatch",
                "EXECUTION_CONTRACT_MISMATCH",
                "Resolved SDXL width/height do not match the native UNet latent tensor at the required 8x VAE scale.");
        }
        const uint64_t latent_elements = latent_shape.element_count();
        QnnSdxlConditioningBuffers conditioning;
        const bool sdxl_conditioning_has_negative_branch =
            worker_strategy != "split_unet_vae" || execution_contract.use_cfg;
        if (!qnn_prepare_sdxl_conditioning(
                    unet,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    embeddings,
                    sdxl_conditioning_has_negative_branch,
                    &conditioning,
                    &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"conditioning_shape_unsupported\",\"message\":") +
                quote(error) + "}";
        }
        constexpr size_t hidden_count =
            mca::image::kSdxlClipTokenCount * mca::image::kSdxlHiddenWidth;
        constexpr size_t pooled_count = mca::image::kSdxlPooledWidth;
        constexpr size_t time_ids_count = mca::image::kSdxlTimeIdCount;

        const int steps = execution_contract.scheduler.steps;
        const auto& timesteps = scheduler.timesteps();
        const double effective_vae_host_scale =
            qnn_effective_vae_host_scale(execution_contract);
        generation.set_steps(static_cast<int>(timesteps.size()));
        generation.set_phase(kQnnImageSampling);
        std::mt19937 rng(execution_contract.seed);
        std::normal_distribution<float> normal(0.0f, 1.0f);
        std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
        const float initial_noise_scale = static_cast<float>(scheduler.init_noise_sigma());
        for (float& value : latents) value = normal(rng) * initial_noise_scale;

        std::vector<float> noise_uncond;
        std::vector<float> noise_cond;
        long long unet_execute_ms_total = 0;
        size_t unet_execution_count = 0;
        for (size_t step = 0; step < timesteps.size(); ++step) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            generation.set_step(static_cast<int>(step));
            std::vector<float> model_input;
            if (!scheduler.scale_model_input(latents, step, &model_input, &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_scheduler_scale_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    error);
            }
            long long execute_ms = 0;
            if (execution_contract.use_cfg) {
                if (!qnn_run_sdxl_unet_once(
                        unet,
                        sample_index,
                        timestep_index,
                        hidden_index,
                        time_ids_index,
                        pooled_index,
                        model_input,
                        timesteps[step],
                        conditioning.negative_hidden.data(),
                        hidden_count,
                        conditioning.time_ids,
                        time_ids_count,
                        conditioning.negative_pooled,
                        pooled_count,
                        &noise_uncond,
                        &execute_ms,
                        &error)) {
                    return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
                        quote(error) + "}";
                }
                unet_execute_ms_total += execute_ms;
                ++unet_execution_count;
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            }
            execute_ms = 0;
            if (!qnn_run_sdxl_unet_once(
                    unet,
                    sample_index,
                    timestep_index,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    model_input,
                    timesteps[step],
                    conditioning.positive_hidden.data(),
                    hidden_count,
                    conditioning.time_ids,
                    time_ids_count,
                    conditioning.positive_pooled,
                    pooled_count,
                    &noise_cond,
                    &execute_ms,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            unet_execute_ms_total += execute_ms;
            ++unet_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            if ((execution_contract.use_cfg && noise_uncond.size() != latents.size()) ||
                noise_cond.size() != latents.size()) {
                return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_output_shape_unsupported\",\"message\":\"SDXL UNet output does not exactly match the latent tensor.\"}";
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
            const int completed_step = static_cast<int>(step + 1U);
            generation.set_step(completed_step);
            const QnnPreviewPublishOutcome preview_outcome =
                preview_publisher.publish_if_due(
                    completed_step,
                    static_cast<int>(timesteps.size()),
                    latents,
                    effective_vae_host_scale,
                    vae,
                    execution_contract.pixel_range);
            if (preview_outcome == QnnPreviewPublishOutcome::Cancelled) {
                return qnn_image_generation_cancelled_json();
            }
            generation.set_phase(kQnnImageSampling);
        }
        if (unet_execution_count !=
            execution_contract.scheduler.expected_unet_execution_count) {
            return qnn_execution_contract_mismatch_json(
                "unetExecutionCount",
                execution_contract.scheduler.expected_unet_execution_count,
                unet_execution_count);
        }

        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        generation.set_phase(kQnnImageDecoding);
        QnnVaeDecodeResult vae_decode;
        if (!qnn_decode_vae_latents(
                vae,
                unet.inputs[sample_index].dimensions,
                latents,
                execution_contract,
                &vae_decode,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_vae_decode_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        int width = 0;
        int height = 0;
        mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
        QnnTensorBinding final_output_binding;
        final_output_binding.name = "sdxl_tiled_vae_output_nchw";
        final_output_binding.dimensions = {
            1U,
            3U,
            static_cast<uint32_t>(vae_decode.plan.final_output.height),
            static_cast<uint32_t>(vae_decode.plan.final_output.width),
        };
        generation.record_stage(
            mca::qnn::ImageStage::PngWrite,
            kQnnImagePngWrite);
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!mca::qnn::write_qnn_image_png_atomic(
                final_output_binding,
                vae_decode.pixels_nchw,
                output_path,
                execution_contract.pixel_range,
                &pixel_range_evidence,
                &width,
                &height,
                &error)) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_png_write_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (generation.cancelled()) {
            ::unlink(output_path.c_str());
            return qnn_image_generation_cancelled_json();
        }
        if (width != execution_contract.width || height != execution_contract.height) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "sdxl_output_resolution_mismatch",
                "EXECUTION_CONTRACT_MISMATCH",
                "Decoded SDXL image dimensions do not match the requested execution contract.");
        }

        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started
        ).count();
        const long long output_bytes = file_size_or_zero(output_path);
        std::string output_sha256;
        if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "sdxl_png_sha256_failed",
                "OUTPUT_INTEGRITY_FAILED",
                error);
        }
        if (generation.cancelled()) {
            ::unlink(output_path.c_str());
            return qnn_image_generation_cancelled_json();
        }
        QnnNativeEffectiveEvidence native_evidence{
            timesteps.size(),
            unet_execution_count,
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
        native_evidence.conditioning_execution_mode =
            conditioning_evidence.conditioning_execution_mode;
        native_evidence.conditioning_backend = conditioning_evidence.conditioning_backend;
        native_evidence.conditioning_graph = conditioning_evidence.conditioning_graph;
        native_evidence.conditioning_graph_sha256 = conditioning_graph_sha256;
        native_evidence.conditioning_order = conditioning_evidence.conditioning_order;
        native_evidence.conditioning_encoder_execution_count =
            conditioning_evidence.conditioning_encoder_execution_count;
        native_evidence.text_encoder_execution_count = text_encoder_execute_count;
        if (text_encoder_asset_evidence.required) {
            native_evidence.consumed_text_encoder_path =
                text_encoder_asset_evidence.relative_path;
            native_evidence.consumed_text_encoder_sha256 =
                text_encoder_asset_evidence.sha256;
            native_evidence.consumed_text_encoder_size_bytes =
                text_encoder_asset_evidence.size_bytes;
            native_evidence.consumed_text_encoder_asset_verified = true;
            native_evidence.language_proof_sha256 =
                text_encoder_asset_evidence.language_proof_sha256;
        }
        native_evidence.conditioning_artifact_consumed =
            conditioning_artifact_consumed && unet_execution_count > 0U;
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
        native_evidence.runtime_session_mode = "shared_unet_vae";
        const std::string native_effective = qnn_native_effective_json(
            execution_contract,
            native_evidence);
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
            << "\"executionStage\":\"sdxl_semantic_generation_passed\","
            << "\"profileId\":" << quote(execution_contract.profile_id) << ","
            << "\"profileRevision\":" << execution_contract.profile_revision << ","
            << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
            << "\"runtime\":\"QNN_HTP\","
            << "\"scheduler\":" << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ","
            << "\"predictionType\":" << quote(qnn_prediction_wire_name(execution_contract.scheduler.config.prediction_type)) << ","
            << "\"steps\":" << steps << ","
            << "\"timetableCount\":" << timesteps.size() << ","
            << "\"unetExecutionCount\":" << unet_execution_count << ","
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
            << "\"nativeEffective\":" << native_effective << ","
            << "\"timesteps\":" << qnn_double_array_json(timesteps) << ","
            << "\"sigmas\":" << qnn_double_array_json(scheduler.sigmas()) << ","
            << "\"initNoiseSigma\":" << scheduler.init_noise_sigma() << ","
            << "\"scaleModelInput\":" << (execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
            << "\"textEncoderExecutionCount\":" << text_encoder_execute_count << ","
            << "\"conditioningArtifactConsumed\":"
            << (native_evidence.conditioning_artifact_consumed ? "true" : "false") << ","
            << "\"vaeExecutionCount\":" << vae_decode.execution_count << ","
            << "\"finalVaeExecutionCount\":1,"
            << "\"finalVaeGraphExecutionCount\":" << vae_decode.execution_count << ","
            << "\"vaeTileCount\":" << vae_decode.plan.tiles.size() << ","
            << "\"vaeTiled\":" << (vae_decode.plan.tiled() ? "true" : "false") << ","
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
            << "\"runtimeSessionMode\":\"shared_unet_vae\","
            << "\"message\":\"QNN SDXL semantic generation completed with the resolved shared scheduler, QNN UNet, and QNN VAE decoder.\","
            << "\"conditioningFormat\":\"sdxl_qnn_conditioning\","
            << "\"elapsedMs\":" << elapsed << ","
            << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
            << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
            << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
            << "\"unetExecuteMsAvg\":" << (unet_execution_count > 0 ? (unet_execute_ms_total / static_cast<long long>(unet_execution_count)) : 0) << ","
            << "\"vaeExecuteMs\":" << vae_decode.execute_ms_total << ","
            << "\"conditioningElements\":" << embeddings.size() << ","
            << "\"hiddenElements\":" << hidden_count << ","
            << "\"pooledElements\":" << pooled_count << ","
            << "\"timeIdElements\":" << time_ids_count << ","
            << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
            << "\"pixelChecksum\":" << checksum_float_vector(vae_decode.pixels_nchw) << ","
            << "\"outputPath\":" << quote(output_path) << ","
            << "\"outputBytes\":" << output_bytes << ","
            << "\"outputSha256\":" << quote(output_sha256) << ","
            << "\"outputAtomicCommit\":true,"
            << "\"unetGraph\":" << quote(unet.graph_name) << ","
            << "\"vaeGraph\":" << quote(vae.graph_name) << ","
            << "\"debug\":{"
            << "\"timestepFirst\":" << (timesteps.empty() ? 0.0 : timesteps.front()) << ","
            << "\"timestepLast\":" << (timesteps.empty() ? 0.0 : timesteps.back()) << ","
            << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
            << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
            << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
            << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
            << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
            << "\"noiseUncondStats\":" << float_vector_stats_json(noise_uncond) << ","
            << "\"noiseCondStats\":" << float_vector_stats_json(noise_cond) << ","
            << "\"pixelStats\":" << float_vector_stats_json(vae_decode.pixels_nchw) << "},"
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
                    ? "QNN SDXL resources did not release cleanly after final image generation."
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

    return std::string();
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
