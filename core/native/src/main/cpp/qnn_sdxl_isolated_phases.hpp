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
    std::ofstream output(temporary.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) {
        *error = "Failed to open latent temporary file: " + temporary;
        return false;
    }
    output.write(
        reinterpret_cast<const char*>(values.data()),
        static_cast<std::streamsize>(values.size() * sizeof(float)));
    output.flush();
    const bool written = output.good();
    output.close();
    if (!written) {
        ::unlink(temporary.c_str());
        *error = "Failed to write latent temporary file: " + temporary;
        return false;
    }
    if (::rename(temporary.c_str(), path.c_str()) != 0) {
        ::unlink(temporary.c_str());
        *error = "Failed to atomically publish latent file: " + path;
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
    const std::string graph_name = string_field(params_json, "graphName").empty()
        ? "model"
        : string_field(params_json, "graphName");
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
    const int steps = static_cast<int>(std::max<long long>(1, long_field(params_json, "steps")));
    if (steps != 1) {
        return "{\"ok\":false,\"executionStage\":\"sdxl_multistep_disabled\",\"message\":\"Isolated QNN SDXL UNet currently accepts exactly one step.\"}";
    }
    QnnImageGenerationScope generation(steps, string_field(params_json, "progressJournalPath"));
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
    const long long seed_value = long_field(params_json, "seed");
    const int seed = static_cast<int>(seed_value == 0 ? 42 : seed_value);
    const float cfg_scale = static_cast<float>(double_field(params_json, "cfgScale", 7.0));
    std::mt19937 rng(static_cast<uint32_t>(seed));
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    for (float& value : latents) value = normal(rng);
    const float* negative_hidden = embeddings.data();
    const float* positive_hidden = embeddings.data() + hidden_count;
    const float* negative_pooled = embeddings.data() + hidden_count * 2u;
    const float* positive_pooled = negative_pooled + pooled_count;
    const float* time_ids = positive_pooled + pooled_count;
    std::vector<float> noise_uncond;
    std::vector<float> noise_cond;
    long long uncond_ms = 0;
    long long cond_ms = 0;
    if (!qnn_run_sdxl_unet_once(
            *unet, sample_index, timestep_index, hidden_index, time_ids_index, pooled_index,
            latents, 1, negative_hidden, hidden_count, time_ids, time_ids_count,
            negative_pooled, pooled_count, &noise_uncond, &uncond_ms, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    if (!qnn_run_sdxl_unet_once(
            *unet, sample_index, timestep_index, hidden_index, time_ids_index, pooled_index,
            latents, 1, positive_hidden, hidden_count, time_ids, time_ids_count,
            positive_pooled, pooled_count, &noise_cond, &cond_ms, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (noise_uncond.size() < latents.size() || noise_cond.size() < latents.size()) {
        return "{\"ok\":false,\"executionStage\":\"sdxl_unet_output_shape_unsupported\",\"message\":\"SDXL UNet output is smaller than its latent input.\"}";
    }
    std::vector<float> guided(latents.size(), 0.0f);
    for (size_t i = 0; i < latents.size(); ++i) {
        guided[i] = cfg_scale * (noise_cond[i] - noise_uncond[i]) + noise_uncond[i];
    }
    std::vector<std::vector<float>> ets;
    latents = qnn_pndm_step(
        latents, ets, guided, 0, std::vector<int>{1}, sd15_pndm_alphas());
    generation.set_step(1);
    if (!write_sdxl_latent_atomic(latent_path, latents, &error)) {
        return std::string("{\"ok\":false,\"executionStage\":\"latent_publish_failed\",\"message\":") +
            quote(error) + "}";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    std::ostringstream out;
    out << "{\"ok\":true,\"phase\":\"unet\",\"executionStage\":\"sdxl_unet_phase_passed\","
        << "\"processExitRequired\":true,\"runtimeProfile\":\"V" << unet->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << unet->selected_runtime.htp_arch_version << ","
        << "\"latentPath\":" << quote(latent_path) << ",\"latentDtype\":\"float32-le\","
        << "\"latentShape\":" << qnn_tensor_shape_json(unet->inputs[sample_index]) << ","
        << "\"latentElements\":" << latents.size() << ",\"latentBytes\":" << latents.size() * sizeof(float) << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
        << "\"unetContextLoadMs\":" << unet->context_load_ms << ","
        << "\"unetExecuteMsTotal\":" << (uncond_ms + cond_ms) << ",\"elapsedMs\":" << elapsed << ","
        << "\"runtime\":" << runtime_probe_json(unet->selected_runtime) << "}";
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
    const std::string graph_name = string_field(params_json, "graphName").empty()
        ? "model"
        : string_field(params_json, "graphName");
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
    const float scale = static_cast<float>(
        double_field(params_json, "vaeLatentScale", 1.0 / 0.13025));
    for (float& value : latents) value *= scale;
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
    if (!write_vae_tensor_png(
            vae->outputs[0], pixels, temporary_output, &width, &height, &error)) {
        ::unlink(temporary_output.c_str());
        return std::string("{\"ok\":false,\"executionStage\":\"sdxl_png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (::rename(temporary_output.c_str(), output_path.c_str()) != 0) {
        ::unlink(temporary_output.c_str());
        return "{\"ok\":false,\"executionStage\":\"sdxl_png_publish_failed\",\"message\":\"Unable to atomically publish SDXL PNG.\"}";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    std::ostringstream out;
    out << "{\"ok\":true,\"phase\":\"vae\",\"executionStage\":\"sdxl_vae_phase_passed\","
        << "\"processExitRequired\":true,\"runtimeProfile\":\"V" << vae->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << vae->selected_runtime.htp_arch_version << ","
        << "\"outputPath\":" << quote(output_path) << ",\"mimeType\":\"image/png\","
        << "\"width\":" << width << ",\"height\":" << height << ","
        << "\"outputBytes\":" << file_size_or_zero(output_path) << ","
        << "\"vaeContextLoadMs\":" << vae->context_load_ms << ","
        << "\"vaeExecuteMs\":" << execute_ms << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"runtime\":" << runtime_probe_json(vae->selected_runtime) << "}";
    return out.str();
}
