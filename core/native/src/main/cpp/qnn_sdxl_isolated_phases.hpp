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
    if (!read_float_binary_file(embeddings_path, &embeddings, &error)) {
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
    const std::string& graph_name = execution_contract.graph_name;
    const std::string conditioning_format = string_field(params_json, "conditioningFormat");
    QnnConditioningEvidence conditioning_evidence;
    if (conditioning_format.empty() ||
        !resolve_qnn_conditioning_evidence(
            bundle_root,
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
    const uint64_t latent_elements = qnn_tensor_element_count(unet->inputs[sample_index].tensor);
    const auto& latent_dimensions = unet->inputs[sample_index].dimensions;
    uint32_t latent_height = 0;
    uint32_t latent_width = 0;
    if (latent_dimensions.size() == 4u && latent_dimensions[1] == 4u) {
        latent_height = latent_dimensions[2];
        latent_width = latent_dimensions[3];
    } else if (latent_dimensions.size() == 4u && latent_dimensions[3] == 4u) {
        latent_height = latent_dimensions[1];
        latent_width = latent_dimensions[2];
    } else {
        return "{\"ok\":false,\"executionStage\":\"latent_layout_unsupported\",\"message\":\"QNN SDXL latent tensor must be NCHW or NHWC with four channels.\"}";
    }
    if (latent_width == 0u || latent_height == 0u ||
        static_cast<uint64_t>(latent_width) * 8u !=
            static_cast<uint64_t>(execution_contract.width) ||
        static_cast<uint64_t>(latent_height) * 8u !=
            static_cast<uint64_t>(execution_contract.height)) {
        return qnn_semantic_failure_json(
            "latent_resolution_mismatch",
            "EXECUTION_CONTRACT_MISMATCH",
            "Resolved SDXL width/height do not match the native UNet latent tensor at the required 8x VAE scale.");
    }
    const size_t hidden_count = static_cast<size_t>(
        qnn_tensor_element_count(unet->inputs[hidden_index].tensor));
    const size_t pooled_count = static_cast<size_t>(
        qnn_tensor_element_count(unet->inputs[pooled_index].tensor));
    const size_t time_ids_count = static_cast<size_t>(
        qnn_tensor_element_count(unet->inputs[time_ids_index].tensor));
    if (latent_elements == 0 || hidden_count == 0 || pooled_count == 0 || time_ids_count == 0) {
        return "{\"ok\":false,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN SDXL UNet tensor shape is invalid.\"}";
    }
    const size_t needed = hidden_count * 2u + pooled_count * 2u + time_ids_count;
    if (embeddings.size() < needed) {
        std::ostringstream message;
        message << "SDXL conditioning needs " << needed << " f32 elements, got "
                << embeddings.size() << ".";
        return std::string("{\"ok\":false,\"executionStage\":\"conditioning_shape_unsupported\",\"message\":") +
            quote(message.str()) + "}";
    }
    generation.set_phase(kQnnImageSampling);
    std::mt19937 rng(execution_contract.seed);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    const float initial_noise_scale = static_cast<float>(scheduler.init_noise_sigma());
    for (float& value : latents) value = normal(rng) * initial_noise_scale;
    const float* negative_hidden = embeddings.data();
    const float* positive_hidden = embeddings.data() + hidden_count;
    const float* negative_pooled = embeddings.data() + hidden_count * 2u;
    const float* positive_pooled = negative_pooled + pooled_count;
    const float* time_ids = positive_pooled + pooled_count;
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
                    model_input, scheduler.timesteps()[step], negative_hidden, hidden_count,
                    time_ids, time_ids_count, negative_pooled, pooled_count,
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
                model_input, scheduler.timesteps()[step], positive_hidden, hidden_count,
                time_ids, time_ids_count, positive_pooled, pooled_count,
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
        std::vector<float> guided = noise_cond;
        if (execution_contract.use_cfg) {
            guided.resize(latents.size());
            for (size_t i = 0; i < latents.size(); ++i) {
                guided[i] = execution_contract.cfg_scale *
                    (noise_cond[i] - noise_uncond[i]) + noise_uncond[i];
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
        generation.set_step(static_cast<int>(step + 1u));
    }
    if (unet_execution_count !=
        execution_contract.scheduler.expected_unet_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "unetExecutionCount",
            execution_contract.scheduler.expected_unet_execution_count,
            unet_execution_count);
    }
    if (!write_sdxl_latent_atomic(latent_path, latents, &error)) {
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
        << "\"latentShape\":" << qnn_tensor_shape_json(unet->inputs[sample_index]) << ","
        << "\"latentElements\":" << latents.size() << ",\"latentBytes\":" << latents.size() * sizeof(float) << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
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
    const uint64_t expected_elements = qnn_tensor_element_count(vae->inputs[0].tensor);
    if (expected_elements == 0 || latents.size() != static_cast<size_t>(expected_elements)) {
        std::ostringstream message;
        message << "VAE expects " << expected_elements << " latent elements, got " << latents.size() << ".";
        return std::string("{\"ok\":false,\"executionStage\":\"latent_shape_mismatch\",\"message\":") +
            quote(message.str()) + "}";
    }
    generation.set_phase(kQnnImageDecoding);
    const double effective_vae_host_scale = qnn_effective_vae_host_scale(execution_contract);
    if (effective_vae_host_scale != 1.0) {
        for (float& value : latents) {
            value = static_cast<float>(value * effective_vae_host_scale);
        }
    }
    if (!qnn_write_float_tensor(&vae->inputs[0], latents.data(), latents.size(), &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_vae_input_bind_failed\",\"message\":") +
            quote(error) + "}";
    }
    long long execute_ms = 0;
    if (!vae->execute(&execute_ms, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_vae_execute_failed\",\"message\":") +
            quote(error) + "}";
    }
    std::vector<float> pixels;
    if (!qnn_read_float_tensor(vae->outputs[0], &pixels, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_vae_output_read_failed\",\"message\":") +
            quote(error) + "}";
    }
    generation.record_stage(mca::qnn::ImageStage::PngWrite, kQnnImagePngWrite);
    const std::string temporary_output = output_path + ".part";
    ::unlink(temporary_output.c_str());
    int width = 0;
    int height = 0;
    mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
    if (!write_vae_tensor_png(
            vae->outputs[0],
            pixels,
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
    if (!fsync_sdxl_artifact(temporary_output, &error)) {
        ::unlink(temporary_output.c_str());
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_sync_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (::rename(temporary_output.c_str(), output_path.c_str()) != 0) {
        ::unlink(temporary_output.c_str());
        return "{\"ok\":false,\"executionStage\":\"sdxl_png_publish_failed\",\"message\":\"Unable to atomically publish SDXL PNG.\"}";
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
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(execution_contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << execution_contract.vae_scaling_factor << ","
        << qnn_pixel_range_evidence_json(
            execution_contract.pixel_range,
            pixel_range_evidence) << ","
        << "\"effectiveVaeHostScale\":" << effective_vae_host_scale << ","
        << "\"vaeExecutionCount\":1,"
        << "\"vaeContextLoadMs\":" << vae->context_load_ms << ","
        << "\"vaeExecuteMs\":" << execute_ms << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMask\":" << native_detail_stage_mask << ","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(vae->selected_runtime) << "}";
    return out.str();
}
