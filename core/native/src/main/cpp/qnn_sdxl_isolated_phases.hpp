#pragma once

// This file is included from qnn_native_bridge.cpp after the typed QNN graph
// helpers are defined.  Each entry point intentionally leaks its one runtime
// graph: the Android phase worker is disposable and exits immediately after
// publishing its artifact, avoiding the RouterFastRPC unload hang observed
// when large SDXL UNet and VAE contexts were switched inside one process.

bool write_sdxl_latent_atomic(
        const std::string& path,
        const std::vector<float>& values,
        std::string* error) {
    const std::string temporary = path + ".part";
    ::unlink(temporary.c_str());
    const int fd = ::open(
        temporary.c_str(),
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
        0600);
    if (fd < 0) {
        *error = "Failed to open latent temporary file: " + temporary;
        return false;
    }
    const uint8_t* cursor = reinterpret_cast<const uint8_t*>(values.data());
    size_t remaining = values.size() * sizeof(float);
    bool written = true;
    while (remaining > 0u) {
        const ssize_t count = ::write(fd, cursor, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            written = false;
            break;
        }
        cursor += count;
        remaining -= static_cast<size_t>(count);
    }
    if (written && ::fsync(fd) != 0) written = false;
    if (::close(fd) != 0) written = false;
    if (!written) {
        ::unlink(temporary.c_str());
        *error = "Failed to durably write latent temporary file: " + temporary;
        return false;
    }
    if (::rename(temporary.c_str(), path.c_str()) != 0) {
        ::unlink(temporary.c_str());
        *error = "Failed to atomically publish latent file: " + path;
        return false;
    }
    return true;
}

bool fsync_sdxl_artifact(const std::string& path, std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Failed to open generated artifact for fsync: " + path;
        return false;
    }
    const bool synced = ::fsync(fd) == 0;
    const bool closed = ::close(fd) == 0;
    if (!synced || !closed) {
        *error = "Failed to durably sync generated artifact: " + path;
        return false;
    }
    return true;
}

bool validate_sdxl_phase_execution_contract(
        const QnnSemanticExecutionContract& contract,
        std::string* error) {
    constexpr double kSdxlVaeScalingFactor = 0.13025;
    if (error == nullptr) return false;
    if (contract.width <= 0 || contract.height <= 0 ||
        contract.width % 8 != 0 || contract.height % 8 != 0) {
        *error = "SDXL dimensions must define an exact 8x latent shape.";
        return false;
    }
    if (contract.vae_scaling_location != QnnVaeScalingLocation::HostBeforeGraph ||
        !std::isfinite(contract.vae_scaling_factor) ||
        std::fabs(contract.vae_scaling_factor - kSdxlVaeScalingFactor) > 1.0e-9) {
        *error = "SDXL VAE execution requires host-before-graph scaling with factor 0.13025.";
        return false;
    }
    error->clear();
    return true;
}

std::string qnn_sdxl_unet_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& latent_path) {
    const auto started = std::chrono::steady_clock::now();
    const RuntimeProbe runtime = inspect_runtime_internal(
        parse_json_string_array(runtime_dirs_json), true, false);
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
    std::string error;
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
    if (scheduler.timesteps().size() !=
        execution_contract.scheduler.expected_timetable_count) {
        return qnn_execution_contract_mismatch_json(
            "timetableCount",
            execution_contract.scheduler.expected_timetable_count,
            scheduler.timesteps().size());
    }
    QnnImageGenerationScope generation(
        static_cast<int>(scheduler.timesteps().size()),
        string_field(params_json, "progressJournalPath"));
    if (!generation.active()) {
        return "{\"ok\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image phase is active in this process.\"}";
    }
    generation.record_stage(mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    auto* unet = new QnnExecutableGraph();
    if (!unet->load(runtime, unet_path, graph_name, &generation, false)) {
        return std::string("{\"ok\":false,\"executionStage\":\"unet_load_failed\",\"message\":") +
            quote(unet->message) + "}";
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
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
    const uint64_t latent_elements = latent_shape.element_count();
    if (static_cast<uint64_t>(latent_shape.width) * 8u !=
            static_cast<uint64_t>(execution_contract.width) ||
        static_cast<uint64_t>(latent_shape.height) * 8u !=
            static_cast<uint64_t>(execution_contract.height)) {
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
            &conditioning,
            &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"conditioning_shape_unsupported\",\"message\":") +
            quote(error) + "}";
    }
    constexpr size_t hidden_count =
        mca::image::kSdxlClipTokenCount * mca::image::kSdxlHiddenWidth;
    constexpr size_t pooled_count = mca::image::kSdxlPooledWidth;
    constexpr size_t time_ids_count = mca::image::kSdxlTimeIdCount;
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
    for (size_t step = 0; step < scheduler.timesteps().size(); ++step) {
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
                    *unet, sample_index, timestep_index, hidden_index, time_ids_index, pooled_index,
                    model_input, scheduler.timesteps()[step], conditioning.negative_hidden.data(), hidden_count,
                    conditioning.time_ids, time_ids_count, conditioning.negative_pooled, pooled_count,
                    &noise_uncond, &execute_ms, &error)) {
                return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            unet_execute_ms_total += execute_ms;
            ++unet_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
        if (!qnn_run_sdxl_unet_once(
                *unet, sample_index, timestep_index, hidden_index, time_ids_index, pooled_index,
                model_input, scheduler.timesteps()[step], conditioning.positive_hidden.data(), hidden_count,
                conditioning.time_ids, time_ids_count, conditioning.positive_pooled, pooled_count,
                &noise_cond, &execute_ms, &error)) {
            return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        unet_execute_ms_total += execute_ms;
        ++unet_execution_count;
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
        generation.set_step(static_cast<int>(step + 1u));
    }
    if (unet_execution_count !=
        execution_contract.scheduler.expected_unet_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "unetExecutionCount",
            execution_contract.scheduler.expected_unet_execution_count,
            unet_execution_count);
    }
    const mca::image::SpatialTensorShape canonical_latent_shape{
        1U,
        4U,
        latent_shape.height,
        latent_shape.width,
        mca::image::SpatialTensorLayout::Nchw,
    };
    std::vector<float> canonical_latents;
    if (!mca::image::copy_spatial_tile(
            latents,
            latent_shape,
            0U,
            0U,
            canonical_latent_shape,
            &canonical_latents,
            &error) ||
        !write_sdxl_latent_atomic(latent_path, canonical_latents, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"latent_publish_failed\",\"message\":") +
            quote(error) + "}";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    QnnNativeEffectiveEvidence native_evidence{
        scheduler.timesteps().size(),
        unet_execution_count,
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
        << "\"timetableCount\":" << scheduler.timesteps().size() << ","
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
        << "\"conditioningArtifactSha256\":"
        << quote(conditioning_evidence.conditioning_artifact_sha256) << ","
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
        << "\"latentShape\":[1,4," << latent_shape.height << "," << latent_shape.width << "],"
        << "\"latentElements\":" << canonical_latents.size() << ",\"latentBytes\":" << canonical_latents.size() * sizeof(float) << ","
        << "\"latentChecksum\":" << checksum_float_vector(canonical_latents) << ","
        << "\"unetContextLoadCount\":1,"
        << "\"unetSamplingLoopCount\":1,"
        << "\"unetSamplingStepCount\":" << scheduler.timesteps().size() << ","
        << "\"unetGraphExecutionCount\":" << unet_execution_count << ","
        << "\"unetContextReusedAcrossSteps\":true,"
        << "\"unetGraphName\":" << quote(unet->graph_name) << ","
        << "\"unetContextLoadMs\":" << unet->context_load_ms << ","
        << "\"timesteps\":" << qnn_double_array_json(scheduler.timesteps()) << ","
        << "\"sigmas\":" << qnn_double_array_json(scheduler.sigmas()) << ","
        << "\"initNoiseSigma\":" << scheduler.init_noise_sigma() << ","
        << "\"scaleModelInput\":" << (execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ",\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMask\":" << native_detail_stage_mask << ","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(unet->selected_runtime) << "}";
    return out.str();
}

std::string qnn_sdxl_vae_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& latent_path,
        const std::string& output_path) {
    const auto started = std::chrono::steady_clock::now();
    const RuntimeProbe runtime = inspect_runtime_internal(
        parse_json_string_array(runtime_dirs_json), true, false);
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
    std::string error;
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
    if (!validate_sdxl_phase_execution_contract(execution_contract, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    const std::string& graph_name = execution_contract.graph_name;
    QnnImageGenerationScope generation(1, string_field(params_json, "progressJournalPath"));
    if (!generation.active()) {
        return "{\"ok\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image phase is active in this process.\"}";
    }
    generation.record_stage(mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    auto* vae = new QnnExecutableGraph();
    if (!vae->load(runtime, vae_path, graph_name, &generation, true)) {
        return std::string("{\"ok\":false,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(vae->message) + "}";
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
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
    if (!qnn_decode_vae_latents(
            *vae,
            source_latent_dimensions,
            latents,
            execution_contract,
            &vae_decode,
            &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_vae_decode_failed\",\"message\":") +
            quote(error) + "}";
    }
    generation.record_stage(mca::qnn::ImageStage::PngWrite, kQnnImagePngWrite);
    const std::string temporary_output = output_path + ".part";
    ::unlink(temporary_output.c_str());
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
    if (!write_vae_tensor_png(
            final_output_binding,
            vae_decode.pixels_nchw,
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
    if (width != execution_contract.width || height != execution_contract.height) {
        ::unlink(temporary_output.c_str());
        return "{\"ok\":false,\"executionStage\":\"sdxl_output_resolution_mismatch\",\"message\":\"Decoded SDXL image dimensions do not match the requested execution contract.\"}";
    }
    if (!fsync_sdxl_artifact(temporary_output, &error)) {
        ::unlink(temporary_output.c_str());
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_sync_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (::rename(temporary_output.c_str(), output_path.c_str()) != 0) {
        ::unlink(temporary_output.c_str());
        return "{\"ok\":false,\"executionStage\":\"sdxl_png_publish_failed\",\"message\":\"Unable to atomically publish SDXL PNG.\"}";
    }
    std::string output_sha256;
    if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_hash_failed\",\"message\":") +
            quote(error) + "}";
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
        << "\"pixelChecksum\":" << checksum_float_vector(vae_decode.pixels_nchw) << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMask\":" << native_detail_stage_mask << ","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(vae->selected_runtime) << "}";
    return out.str();
}
