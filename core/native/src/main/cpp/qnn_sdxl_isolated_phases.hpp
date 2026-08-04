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
    if (!qnn_is_safe_ascii_diffusion_prompt_pair(
            contract.prompt,
            contract.negative_prompt)) {
        *error = "Split-worker QNN SDXL accepts only safe ASCII diffusion prompt syntax because it lacks descriptor- and receipt-backed direct-Chinese tokenizer topology.";
        return false;
    }
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

bool valid_sdxl_lower_sha256(const std::string& value) {
    return value.size() == 64U && std::all_of(
        value.begin(), value.end(), [](unsigned char character) {
            return (character >= '0' && character <= '9') ||
                (character >= 'a' && character <= 'f');
        });
}

struct SdxlInpaintPixelArtifacts {
    std::vector<float> source_nchw;
    std::vector<float> full_mask;
    std::string source_tensor_sha256;
    std::string full_mask_tensor_sha256;
};

bool load_sdxl_inpaint_pixel_artifacts(
        const std::string& params_json,
        const QnnSemanticExecutionContract& contract,
        SdxlInpaintPixelArtifacts* artifacts,
        std::string* error) {
    if (artifacts == nullptr || error == nullptr ||
        contract.width != 1024 || contract.height != 1024) {
        if (error != nullptr) {
            *error = "Split-SDXL inpaint pixel artifacts require an exact 1024x1024 contract.";
        }
        return false;
    }
    const std::string source_tensor_path = string_field(
        params_json, "inputImageTensorPath");
    const std::string expected_source_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageTensorSha256"));
    const std::string full_mask_tensor_path = string_field(
        params_json, "maskImageFullTensorPath");
    const std::string expected_full_mask_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "maskImageFullTensorSha256"));
    const std::vector<uint32_t> expected_source_shape{1U, 3U, 1024U, 1024U};
    const std::vector<uint32_t> expected_full_mask_shape{1U, 1U, 1024U, 1024U};
    constexpr long long kSourceBytes =
        static_cast<long long>(1U * 3U * 1024U * 1024U * sizeof(float));
    constexpr long long kFullMaskBytes =
        static_cast<long long>(1U * 1024U * 1024U * sizeof(float));
    const long long repaint_count = long_field(
        params_json, "maskImageRepaintPixelCount");
    if (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
        string_field(params_json, "inpaintRequestedTopology") != "latent_blend_4" ||
        string_field(params_json, "inpaintMaskConvention") !=
            "white_repaint_black_preserve" ||
        source_tensor_path.empty() ||
        !valid_sdxl_lower_sha256(expected_source_tensor_sha256) ||
        uint_array_field(params_json, "inputImageTensorShape") != expected_source_shape ||
        string_field(params_json, "inputImageTensorDtype") != "float32-le" ||
        string_field(params_json, "inputImageTensorLayout") != "NCHW" ||
        string_field(params_json, "inputImageTensorRange") != "NEGATIVE_ONE_TO_ONE" ||
        string_field(params_json, "inputImagePreprocess") !=
            "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1" ||
        long_field(params_json, "inputImageTensorBytes") != kSourceBytes ||
        file_size_or_zero(source_tensor_path) != kSourceBytes ||
        !read_float_binary_file(
            source_tensor_path,
            &artifacts->source_nchw,
            error,
            &artifacts->source_tensor_sha256) ||
        normalized_contract_enum(artifacts->source_tensor_sha256) !=
            expected_source_tensor_sha256 ||
        artifacts->source_nchw.size() != 1U * 3U * 1024U * 1024U ||
        !std::all_of(
            artifacts->source_nchw.begin(),
            artifacts->source_nchw.end(),
            [](float value) {
                return std::isfinite(value) &&
                    value >= -1.000001f && value <= 1.000001f;
            }) ||
        full_mask_tensor_path.empty() ||
        !valid_sdxl_lower_sha256(expected_full_mask_tensor_sha256) ||
        uint_array_field(params_json, "maskImageFullTensorShape") !=
            expected_full_mask_shape ||
        string_field(params_json, "maskImageFullTensorDtype") != "float32-le" ||
        string_field(params_json, "maskImageFullTensorLayout") != "NCHW" ||
        string_field(params_json, "maskImageFullTensorRange") != "ZERO_TO_ONE" ||
        string_field(params_json, "maskImageFullTensorPreprocess") !=
            "source_aligned_center_crop_linear_grayscale_full_nchw_v1" ||
        long_field(params_json, "maskImageFullTensorBytes") != kFullMaskBytes ||
        file_size_or_zero(full_mask_tensor_path) != kFullMaskBytes ||
        !read_float_binary_file(
            full_mask_tensor_path,
            &artifacts->full_mask,
            error,
            &artifacts->full_mask_tensor_sha256) ||
        normalized_contract_enum(artifacts->full_mask_tensor_sha256) !=
            expected_full_mask_tensor_sha256 ||
        !mca::qnn::inpaint::validate_mask_values(
            artifacts->full_mask, 1024U, 1024U, error) ||
        repaint_count != static_cast<long long>(std::count_if(
            artifacts->full_mask.begin(),
            artifacts->full_mask.end(),
            [](float value) { return value > 0.0f; }))) {
        if (error->empty()) {
            *error = "Split-SDXL source RGB or full mask tensor failed its exact identity and shape contract.";
        }
        return false;
    }
    error->clear();
    return true;
}

// Split-worker SDXL deliberately has no preview transport.  Keep this at the
// phase boundary so a direct JNI caller cannot bypass the product contract.
bool validate_sdxl_no_preview_transport(
        const std::string& params_json,
        std::string* error) {
    if (error == nullptr) return false;
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.is_object()) {
            *error = "Split-SDXL params must be a JSON object.";
            return false;
        }
        const auto preview = root.find("preview");
        if (preview != root.end()) {
            *error = "Split-worker QNN SDXL does not support preview transport.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("Split-SDXL preview transport JSON is invalid: ") +
            exception.what();
        return false;
    }
}

std::string sdxl_parent_directory(const std::string& path) {
    const size_t separator = path.find_last_of('/');
    if (separator == std::string::npos) return "";
    return separator == 0U ? "/" : path.substr(0U, separator);
}

std::string sdxl_disabled_preview_native_json_fields() {
    return "\"previewRequested\":false,"
        "\"previewMode\":\"none\","
        "\"previewInterval\":0,"
        "\"previewVaeExecutionAttemptCount\":0,"
        "\"previewVaeExecutionCount\":0,"
        "\"previewVaeExecutionMsTotal\":0,"
        "\"previewPublicationCount\":0,"
        "\"previewLastStep\":0,"
        "\"previewLastRevision\":0,"
        "\"previewFailureCode\":\"\","
        "\"projectionPreviewAttemptCount\":0,"
        "\"projectionPreviewPublicationCount\":0,"
        "\"projectionPreviewProjectionMsTotal\":0,"
        "\"projectionPreviewLastStep\":0,"
        "\"projectionPreviewLastRevision\":0,"
        "\"projectionPreviewFailureCode\":\"\","
        "\"previewDegraded\":false";
}

std::string qnn_sdxl_encoder_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& input_tensor_path,
        const std::string& latent_path,
        const std::string& expected_encoder_context_sha256_raw) {
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
        return std::string("{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    QnnSemanticExecutionContract execution_contract;
    if (!parse_qnn_semantic_execution_contract(params_json, &execution_contract, &error) ||
        !validate_sdxl_phase_execution_contract(execution_contract, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_execution_contract_invalid",
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
    const std::string input_image_path = string_field(params_json, "inputImagePath");
    const std::string expected_image_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageSha256"));
    const std::string declared_tensor_path = string_field(
        params_json, "inputImageTensorPath");
    const std::string expected_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageTensorSha256"));
    const std::string preprocess = string_field(params_json, "inputImagePreprocess");
    const std::string tensor_dtype = string_field(params_json, "inputImageTensorDtype");
    const std::string tensor_layout = string_field(params_json, "inputImageTensorLayout");
    const std::string tensor_range = string_field(params_json, "inputImageTensorRange");
    const size_t target_width = static_cast<size_t>(execution_contract.width);
    const size_t target_height = static_cast<size_t>(execution_contract.height);
    const size_t input_elements = target_width * target_height * 3U;
    const size_t latent_elements = (target_width / 8U) * (target_height / 8U) * 4U;
    const long long input_bytes = static_cast<long long>(input_elements * sizeof(float));
    const bool encoder_conditioned = task_mode == "img2img" || task_mode == "inpaint";
    if (!encoder_conditioned || (ultra_fix.enabled && task_mode != "img2img") ||
        (ultra_fix.enabled &&
            (ultra_fix.target_width != execution_contract.width ||
             ultra_fix.target_height != execution_contract.height ||
             ultra_fix.tile_size != 1024 ||
             ultra_fix.target_width < 1024 || ultra_fix.target_width > 2048 ||
             ultra_fix.target_height < 1024 || ultra_fix.target_height > 2048 ||
             ultra_fix.target_width % 64 != 0 ||
             ultra_fix.target_height % 64 != 0 ||
             long_field(params_json, "inputImageOrientedWidth") <= 0LL ||
             long_field(params_json, "inputImageOrientedHeight") <= 0LL)) ||
        input_image_path.empty() ||
        input_tensor_path.empty() || declared_tensor_path != input_tensor_path ||
        expected_image_sha256.size() != 64U || expected_tensor_sha256.size() != 64U ||
        long_field(params_json, "inputImageSizeBytes") <= 0LL ||
        preprocess != "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1" ||
        tensor_dtype != "float32-le" || tensor_layout != "NCHW" ||
        tensor_range != "NEGATIVE_ONE_TO_ONE" ||
        uint_array_field(params_json, "inputImageTensorShape") !=
            std::vector<uint32_t>{
                1U, 3U, static_cast<uint32_t>(target_height),
                static_cast<uint32_t>(target_width)} ||
        long_field(params_json, "inputImageTensorBytes") != input_bytes) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_input_contract_invalid",
            "INPUT_TENSOR_CONTRACT_INVALID",
            "SDXL VAE encoder requires one exact app-private RGB NCHW float32 tensor; "
            "UltraFix additionally requires a 1024-pixel graph tile and matching target canvas.");
    }
    std::vector<float> input_values;
    std::string actual_tensor_sha256;
    if (!read_float_binary_file(
            input_tensor_path,
            &input_values,
            &error,
            &actual_tensor_sha256) ||
        actual_tensor_sha256 != expected_tensor_sha256 ||
        input_values.size() != input_elements ||
        !std::all_of(input_values.begin(), input_values.end(), [](float value) {
            return std::isfinite(value) && value >= -1.000001f && value <= 1.000001f;
        })) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_input_tensor_changed",
            "INPUT_TENSOR_IDENTITY_MISMATCH",
            error.empty() ? "Prepared SDXL encoder tensor failed its native identity or range check." : error);
    }
    const std::string encoder_binary = string_field(
        params_json, "vaeEncoderContextBinary").empty()
        ? "vae_encoder.bin"
        : string_field(params_json, "vaeEncoderContextBinary");
    const std::string encoder_path = join_path(bundle_root, encoder_binary);
    const std::string expected_encoder_context_sha256 = normalized_contract_enum(
        expected_encoder_context_sha256_raw);
    const std::string params_encoder_context_sha256 = normalized_contract_enum(
        string_field(params_json, "vaeEncoderContextSha256"));
    const bool expected_encoder_context_sha256_valid =
        expected_encoder_context_sha256.size() == 64U &&
        std::all_of(
            expected_encoder_context_sha256.begin(),
            expected_encoder_context_sha256.end(),
            [](unsigned char value) {
                return (value >= '0' && value <= '9') ||
                    (value >= 'a' && value <= 'f');
            });
    if (!expected_encoder_context_sha256_valid ||
        params_encoder_context_sha256 != expected_encoder_context_sha256) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_context_identity_invalid",
            "ENCODER_CONTEXT_IDENTITY_INVALID",
            "VAE encoder context SHA-256 is missing or differs across the phase protocol and native params.");
    }
    if (!exists_file(encoder_path)) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"encoder_context_missing\",\"message\":\"QNN SDXL VAE encoder context is missing.\"}";
    }
    QnnImageGenerationScope generation(
        1,
        string_field(params_json, "progressJournalPath"));
    if (!generation.active()) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image phase is active in this process.\"}";
    }
    generation.record_stage(mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(
        g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
    auto* encoder = new QnnExecutableGraph();
    if (!encoder->load(
            runtime,
            encoder_path,
            "model",
            &generation,
            false,
            true,
            expected_encoder_context_sha256,
            expected_profile)) {
        const bool identity_mismatch =
            encoder->message.find("Provider-bound SHA-256") != std::string::npos;
        return qnn_semantic_failure_json(
            identity_mismatch
                ? "sdxl_encoder_context_identity_mismatch"
                : "sdxl_encoder_load_failed",
            identity_mismatch
                ? "ENCODER_CONTEXT_IDENTITY_MISMATCH"
                : "ENCODER_LOAD_FAILED",
            encoder->message);
    }
    const std::string& actual_encoder_context_sha256 = encoder->context_binary_sha256;
    if (expected_profile > 0 &&
        encoder->selected_runtime.htp_arch_version != expected_profile) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"encoder_profile_mismatch\",\"message\":\"VAE encoder selected a different physical HTP transport.\"}";
    }
    const std::vector<uint32_t> expected_input_shape{1U, 3U, 1024U, 1024U};
    const std::vector<uint32_t> expected_output_shape{1U, 4U, 128U, 128U};
    const int input_index = tensor_index_by_name(encoder->inputs, {"input"});
    const int mean_index = tensor_index_by_name(encoder->outputs, {"mean"});
    const int std_index = tensor_index_by_name(encoder->outputs, {"std"});
    if (encoder->inputs.size() != 1U || encoder->outputs.size() != 2U ||
        input_index != 0 || mean_index < 0 || std_index < 0 || mean_index == std_index ||
        encoder->inputs[0].name != "input" ||
        encoder->outputs[static_cast<size_t>(mean_index)].name != "mean" ||
        encoder->outputs[static_cast<size_t>(std_index)].name != "std" ||
        qnn_tensor_data_type(encoder->inputs[0].tensor) != QNN_DATATYPE_FLOAT_32 ||
        qnn_tensor_data_type(encoder->outputs[static_cast<size_t>(mean_index)].tensor) != QNN_DATATYPE_FLOAT_32 ||
        qnn_tensor_data_type(encoder->outputs[static_cast<size_t>(std_index)].tensor) != QNN_DATATYPE_FLOAT_32 ||
        encoder->inputs[0].dimensions != expected_input_shape ||
        encoder->outputs[static_cast<size_t>(mean_index)].dimensions != expected_output_shape ||
        encoder->outputs[static_cast<size_t>(std_index)].dimensions != expected_output_shape) {
        return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"encoder_tensor_contract_mismatch\",\"message\":\"VAE encoder graph metadata differs from the inspected input/mean/std float32 contract.\"}";
    }
    mca::image::UltraFixTilePlan ultra_fix_plan;
    std::string ultra_fix_tile_plan_sha256;
    if (ultra_fix.enabled) {
        if (!mca::image::build_ultrafix_tile_plan(
                encoder->inputs[0].dimensions,
                encoder->outputs[static_cast<size_t>(mean_index)].dimensions,
                {1U, 4U, 128U, 128U},
                {1U, 4U, 128U, 128U},
                {1U, 4U, 128U, 128U},
                {1U, 3U, 1024U, 1024U},
                target_width,
                target_height,
                static_cast<size_t>(ultra_fix.tile_size),
                ultra_fix.overlap,
                8U,
                &ultra_fix_plan,
                &error)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_encoder_tile_plan_invalid",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                error);
        }
        const std::string descriptor =
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        ultra_fix_tile_plan_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(descriptor.data()), descriptor.size());
        if (!valid_sdxl_lower_sha256(ultra_fix_tile_plan_sha256)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_encoder_tile_plan_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "Split-SDXL UltraFix encoder tile plan lacks a SHA-256 identity.");
        }
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    generation.set_phase(kQnnImageDecoding);
    long long encoder_execute_ms = 0;
    size_t encoder_execution_count = 0U;
    std::vector<float> mean;
    std::vector<float> stddev;
    std::string ultra_fix_encoder_input_proof_sha256;
    std::string ultra_fix_encoder_mean_proof_sha256;
    std::string ultra_fix_encoder_std_proof_sha256;
    if (ultra_fix.enabled) {
        const mca::image::SpatialTensorShape source_shape{
            1U, 3U, target_height, target_width,
            mca::image::SpatialTensorLayout::Nchw};
        std::vector<std::vector<float>> mean_tiles;
        std::vector<std::vector<float>> std_tiles;
        std::string input_proofs;
        std::string mean_proofs;
        std::string std_proofs;
        mean_tiles.reserve(ultra_fix_plan.tiles.size());
        std_tiles.reserve(ultra_fix_plan.tiles.size());
        for (const auto& tile : ultra_fix_plan.tiles) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            std::vector<float> tile_input;
            if (!mca::image::copy_spatial_tile(
                    input_values,
                    source_shape,
                    tile.pixel_x,
                    tile.pixel_y,
                    ultra_fix_plan.encoder_input,
                    &tile_input,
                    &error) ||
                !qnn_write_float_tensor(
                    &encoder->inputs[0], tile_input.data(), tile_input.size(), &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_encoder_input_bind_failed",
                    "QNN_ULTRAFIX_ENCODER_INPUT_BIND_FAILED",
                    error);
            }
            const std::string input_proof =
                mca::qnn::controlnet::sha256_hex_bytes(
                    encoder->inputs[0].buffer.data(), encoder->inputs[0].buffer.size());
            long long tile_execute_ms = 0;
            if (!valid_sdxl_lower_sha256(input_proof) || generation.cancelled() ||
                !encoder->execute(&tile_execute_ms, &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_encoder_execute_failed",
                    "QNN_ULTRAFIX_ENCODER_EXECUTION_FAILED",
                    error.empty()
                        ? "Split-SDXL UltraFix encoder tile execution lacks input proof."
                        : error);
            }
            encoder_execute_ms += tile_execute_ms;
            ++encoder_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            const auto& mean_binding = encoder->outputs[static_cast<size_t>(mean_index)];
            const auto& std_binding = encoder->outputs[static_cast<size_t>(std_index)];
            const std::string mean_proof = mca::qnn::controlnet::sha256_hex_bytes(
                mean_binding.buffer.data(), mean_binding.buffer.size());
            const std::string std_proof = mca::qnn::controlnet::sha256_hex_bytes(
                std_binding.buffer.data(), std_binding.buffer.size());
            std::vector<float> tile_mean;
            std::vector<float> tile_std;
            if (!valid_sdxl_lower_sha256(mean_proof) ||
                !valid_sdxl_lower_sha256(std_proof) ||
                !qnn_read_float_tensor(mean_binding, &tile_mean, &error) ||
                !qnn_read_float_tensor(std_binding, &tile_std, &error) ||
                tile_mean.size() != ultra_fix_plan.encoder_output.element_count() ||
                tile_std.size() != ultra_fix_plan.encoder_output.element_count()) {
                return qnn_semantic_failure_json(
                    "sdxl_ultrafix_encoder_output_invalid",
                    "QNN_ULTRAFIX_ENCODER_OUTPUT_INVALID",
                    error.empty()
                        ? "Split-SDXL UltraFix encoder tile returned invalid mean/std tensors."
                        : error);
            }
            input_proofs += input_proof + "|";
            mean_proofs += mean_proof + "|";
            std_proofs += std_proof + "|";
            mean_tiles.push_back(std::move(tile_mean));
            std_tiles.push_back(std::move(tile_std));
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!mca::image::blend_ultrafix_encoder_tiles(
                ultra_fix_plan, mean_tiles, &mean, &error) ||
            !mca::image::blend_ultrafix_encoder_tiles(
                ultra_fix_plan, std_tiles, &stddev, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_ultrafix_encoder_blend_failed",
                "QNN_ULTRAFIX_ENCODER_TILE_BLEND_FAILED",
                error);
        }
        const auto proof_sha256 = [](const std::string& proof) {
            return mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(proof.data()), proof.size());
        };
        ultra_fix_encoder_input_proof_sha256 = proof_sha256(input_proofs);
        ultra_fix_encoder_mean_proof_sha256 = proof_sha256(mean_proofs);
        ultra_fix_encoder_std_proof_sha256 = proof_sha256(std_proofs);
    } else {
        if (!qnn_write_float_tensor(
                &encoder->inputs[0], input_values.data(), input_values.size(), &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_input_bind_failed",
                "ENCODER_INPUT_BIND_FAILED",
                error);
        }
        if (!encoder->execute(&encoder_execute_ms, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_execute_failed",
                "ENCODER_EXECUTION_FAILED",
                error);
        }
        encoder_execution_count = 1U;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_read_float_tensor(
                encoder->outputs[static_cast<size_t>(mean_index)], &mean, &error) ||
            !qnn_read_float_tensor(
                encoder->outputs[static_cast<size_t>(std_index)], &stddev, &error)) {
            return qnn_semantic_failure_json(
                "sdxl_encoder_output_read_failed",
                "ENCODER_OUTPUT_INVALID",
                error);
        }
    }
    if (mean.size() != latent_elements || stddev.size() != latent_elements) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_output_read_failed",
            "ENCODER_OUTPUT_INVALID",
            "VAE encoder outputs have an invalid full-latent element count.");
    }
    constexpr float kEncoderLatentScalingFactor = 0.13025f;
    std::seed_seq posterior_seed{
        execution_contract.seed,
        0x5344584cU,
        0x454e4331U,
    };
    std::mt19937 posterior_rng(posterior_seed);
    std::normal_distribution<float> posterior_normal(0.0f, 1.0f);
    std::vector<float> latents(latent_elements, 0.0f);
    for (size_t index = 0; index < latents.size(); ++index) {
        latents[index] =
            (mean[index] + stddev[index] * posterior_normal(posterior_rng)) *
            kEncoderLatentScalingFactor;
    }
    if (!std::all_of(latents.begin(), latents.end(), [](float value) {
            return std::isfinite(value);
        }) ||
        generation.cancelled()) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_posterior_invalid",
            "ENCODER_POSTERIOR_INVALID",
            "VAE encoder posterior sampling produced a non-finite or cancelled latent.");
    }
    if (!write_sdxl_latent_atomic(latent_path, latents, &error)) {
        return qnn_semantic_failure_json(
            "sdxl_encoder_latent_publish_failed",
            "ENCODER_LATENT_PUBLISH_FAILED",
            error);
    }
    if (generation.cancelled()) {
        ::unlink(latent_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    std::string latent_sha256;
    if (!qnn_file_sha256(latent_path, &latent_sha256, &error)) {
        ::unlink(latent_path.c_str());
        return qnn_semantic_failure_json(
            "sdxl_encoder_latent_hash_failed",
            "ENCODER_LATENT_PUBLISH_FAILED",
            error);
    }
    if (generation.cancelled()) {
        ::unlink(latent_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    generation.set_step(1);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    const long long native_sequence = g_qnn_image_generation_sequence.load();
    const uint32_t native_stage_mask = g_qnn_image_generation_stage_mask.load();
    const uint64_t native_detail_stage_mask =
        g_qnn_image_generation_detail_stage_mask.load();
    const size_t source_width = static_cast<size_t>(std::max<long long>(
        0LL, long_field(params_json, "inputImageOrientedWidth")));
    const size_t source_height = static_cast<size_t>(std::max<long long>(
        0LL, long_field(params_json, "inputImageOrientedHeight")));
    size_t source_resized_width = target_width;
    size_t source_resized_height = target_height;
    if (ultra_fix.enabled && source_width > 0U && source_height > 0U) {
        if (static_cast<uint64_t>(target_width) * source_height >=
            static_cast<uint64_t>(target_height) * source_width) {
            source_resized_height = static_cast<size_t>(
                static_cast<uint64_t>(source_height) * target_width / source_width);
        } else {
            source_resized_width = static_cast<size_t>(
                static_cast<uint64_t>(source_width) * target_height / source_height);
        }
    }
    std::ostringstream out;
    out << std::setprecision(17)
        << "{\"ok\":true,\"phase\":\"encoder\",\"executionStage\":\"sdxl_encoder_phase_passed\","
        << "\"profileId\":" << quote(execution_contract.profile_id) << ","
        << "\"profileRevision\":" << execution_contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\",\"taskMode\":" << quote(task_mode) << ","
        << "\"processExitRequired\":true,\"contextReleased\":false,"
        << "\"runtimeProfile\":\"V" << encoder->selected_runtime.htp_arch_version << "\","
        << "\"htpArchVersion\":" << encoder->selected_runtime.htp_arch_version << ","
        << "\"encoderContextLoadCount\":1,\"encoderExecutionCount\":"
        << encoder_execution_count << ","
        << "\"encoderGraphName\":" << quote(encoder->graph_name) << ","
        << "\"encoderContextSha256\":" << quote(actual_encoder_context_sha256) << ","
        << "\"encoderContextLoadMs\":" << encoder->context_load_ms << ","
        << "\"encoderExecuteMs\":" << encoder_execute_ms << ","
        << "\"encoderInputName\":\"input\",\"encoderMeanOutputName\":\"mean\",\"encoderStdOutputName\":\"std\","
        << "\"encoderInputDtype\":\"float32\",\"encoderMeanDtype\":\"float32\",\"encoderStdDtype\":\"float32\","
        << "\"encoderInputShape\":[1,3,1024,1024],"
        << "\"encoderMeanShape\":[1,4,128,128],\"encoderStdShape\":[1,4,128,128],"
        << "\"posteriorSampling\":\"mean_plus_std_times_normal_mt19937_domain_v1\","
        << "\"posteriorSampleCount\":" << latents.size() << ","
        << "\"encoderLatentScalingFactor\":" << kEncoderLatentScalingFactor << ","
        << "\"inputImagePath\":" << quote(input_image_path) << ","
        << "\"inputImageSha256\":" << quote(expected_image_sha256) << ","
        << "\"inputImageSizeBytes\":" << long_field(params_json, "inputImageSizeBytes") << ","
        << "\"inputImageSourceReadByNative\":false,"
        << "\"inputImageSourceValidation\":\"android_preprocess_provenance\","
        << "\"inputImageTensorPath\":" << quote(input_tensor_path) << ","
        << "\"inputImageTensorSha256\":" << quote(actual_tensor_sha256) << ","
        << "\"inputImageTensorBytes\":" << file_size_or_zero(input_tensor_path) << ","
        << "\"inputImageTensorShape\":[1,3," << target_height << ","
        << target_width << "],"
        << "\"inputImageTensorDtype\":\"float32-le\",\"inputImageTensorLayout\":\"NCHW\","
        << "\"inputImageTensorRange\":\"NEGATIVE_ONE_TO_ONE\","
        << "\"inputImagePreprocess\":" << quote(preprocess) << ","
        << "\"latentPath\":" << quote(latent_path) << ",\"latentDtype\":\"float32-le\","
        << "\"latentShape\":[1,4," << (target_height / 8U) << ","
        << (target_width / 8U) << "],\"latentElements\":" << latents.size() << ","
        << "\"latentBytes\":" << file_size_or_zero(latent_path) << ","
        << "\"latentSha256\":" << quote(latent_sha256) << ",";
    if (ultra_fix.enabled) {
        out << "\"ultraFixTilePlanSha256\":" << quote(ultra_fix_tile_plan_sha256) << ","
            << "\"ultraFixTileCount\":" << ultra_fix_plan.tiles.size() << ","
            << "\"ultraFixTileSize\":" << ultra_fix.tile_size << ","
            << "\"ultraFixOverlap\":" << ultra_fix.overlap << ","
            << "\"ultraFixEncoderGraphExecutionCount\":" << encoder_execution_count << ","
            << "\"ultraFixEncoderTileSuccessCount\":" << encoder_execution_count << ","
            << "\"ultraFixEncoderInputProofSha256\":"
            << quote(ultra_fix_encoder_input_proof_sha256) << ","
            << "\"ultraFixEncoderMeanProofSha256\":"
            << quote(ultra_fix_encoder_mean_proof_sha256) << ","
            << "\"ultraFixEncoderStdProofSha256\":"
            << quote(ultra_fix_encoder_std_proof_sha256) << ","
            << "\"ultraFixSourceWidth\":" << source_width << ","
            << "\"ultraFixSourceHeight\":" << source_height << ","
            << "\"ultraFixSourceResizedWidth\":" << source_resized_width << ","
            << "\"ultraFixSourceResizedHeight\":" << source_resized_height << ","
            << "\"ultraFixSourceCropLeft\":"
            << ((source_resized_width - target_width) / 2U) << ","
            << "\"ultraFixSourceCropTop\":"
            << ((source_resized_height - target_height) / 2U) << ",";
    }
    out
        << sdxl_disabled_preview_native_json_fields() << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMaskHex\":\""
        << mca::qnn::image_stage_mask_hex(native_detail_stage_mask) << "\","
        << "\"runtimeEvidence\":" << runtime_probe_json(encoder->selected_runtime) << "}";
    return out.str();
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
    if (full_output == nullptr || execute_ms_total == nullptr ||
        graph_execution_count == nullptr || error == nullptr || hidden == nullptr ||
        full_sample.size() != plan.full_latent.element_count() ||
        time_ids == nullptr || pooled == nullptr ||
        time_ids_count != mca::image::kSdxlTimeIdCount ||
        plan.target_pixels.width == 0U || plan.target_pixels.height == 0U ||
        plan.tile_size_pixels == 0U || plan.spatial_scale == 0U) {
        if (error != nullptr) {
            *error = "Split-SDXL UltraFix tiled UNet inputs do not match the unified plan.";
        }
        return false;
    }
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(plan.tiles.size());
    for (const auto& tile : plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during split-SDXL UltraFix UNet tiling.";
            return false;
        }
        std::vector<float> tile_sample;
        if (!mca::image::copy_spatial_tile(
                full_sample,
                plan.full_latent,
                tile.latent_x,
                tile.latent_y,
                plan.unet_input,
                &tile_sample,
                error)) {
            return false;
        }
        std::vector<float> tile_output;
        long long execute_ms = 0;
        std::vector<float> tile_time_ids(time_ids, time_ids + time_ids_count);
        const double conditioning_scale = 1024.0 / static_cast<double>(
            std::max(plan.target_pixels.width, plan.target_pixels.height));
        tile_time_ids[0] = static_cast<float>(std::round(
            static_cast<double>(plan.target_pixels.height) * conditioning_scale));
        tile_time_ids[1] = static_cast<float>(std::round(
            static_cast<double>(plan.target_pixels.width) * conditioning_scale));
        tile_time_ids[2] = static_cast<float>(std::round(
            static_cast<double>(tile.latent_y * plan.spatial_scale) * conditioning_scale));
        tile_time_ids[3] = static_cast<float>(std::round(
            static_cast<double>(tile.latent_x * plan.spatial_scale) * conditioning_scale));
        tile_time_ids[4] = static_cast<float>(plan.tile_size_pixels);
        tile_time_ids[5] = static_cast<float>(plan.tile_size_pixels);
        if (!qnn_run_sdxl_unet_once(
                unet,
                sample_index,
                timestep_index,
                hidden_index,
                time_ids_index,
                pooled_index,
                tile_sample,
                timestep,
                hidden,
                hidden_count,
                tile_time_ids.data(),
                time_ids_count,
                pooled,
                pooled_count,
                &tile_output,
                &execute_ms,
                error) ||
            tile_output.size() != plan.unet_output.element_count()) {
            if (error->empty()) {
                *error = "Split-SDXL UltraFix UNet returned an invalid latent tile.";
            }
            return false;
        }
        *execute_ms_total += execute_ms;
        ++(*graph_execution_count);
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during split-SDXL UltraFix UNet tiling.";
            return false;
        }
        tile_outputs.push_back(std::move(tile_output));
    }
    if (qnn_image_generation_cancelled()) {
        *error = "Image generation was cancelled before split-SDXL UltraFix tile blending.";
        return false;
    }
    return mca::image::blend_ultrafix_latent_tiles(
        plan, tile_outputs, full_output, error);
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
    const std::string task_mode = normalized_contract_enum(
        string_field(params_json, "taskMode"));
    const bool inpaint = task_mode == "inpaint";
    SdxlInpaintPixelArtifacts inpaint_artifacts;
    if (inpaint && !load_sdxl_inpaint_pixel_artifacts(
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
    generation.record_stage(mca::qnn::ImageStage::ContextLock, kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> execution_lock(g_qnn_context_execution_mutex, std::defer_lock);
    while (!execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    const int expected_profile = static_cast<int>(long_field(params_json, "expectedHtpArch"));
    auto* vae = new QnnExecutableGraph();
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
    mca::image::UltraFixTilePlan ultra_fix_plan;
    std::string ultra_fix_tile_plan_sha256;
    bool decoded = false;
    if (ultra_fix.enabled) {
        if (!mca::image::build_ultrafix_tile_plan(
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
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        ultra_fix_tile_plan_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(descriptor.data()), descriptor.size());
        if (!valid_sdxl_lower_sha256(ultra_fix_tile_plan_sha256) ||
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
    if (!fsync_sdxl_artifact(temporary_output, &error)) {
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
    const std::string output_parent = sdxl_parent_directory(output_path);
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
        << sdxl_disabled_preview_native_json_fields() << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"nativeGenerationSequence\":" << native_sequence << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << native_stage_mask << ","
        << "\"nativeDetailStageMaskHex\":\""
        << mca::qnn::image_stage_mask_hex(native_detail_stage_mask) << "\","
        << "\"contextReleased\":false,"
        << "\"runtimeEvidence\":" << runtime_probe_json(vae->selected_runtime) << "}";
    return out.str();
}
