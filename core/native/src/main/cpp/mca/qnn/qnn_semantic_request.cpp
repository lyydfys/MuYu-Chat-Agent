#include "mca/qnn/qnn_semantic_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

std::string QnnSemanticRun::request_phase(mca::diffusion::DiffusionScheduler& scheduler) {
    control_image_path = string_field(params_json, "controlImagePath");
    control_image_sha256 = string_field(params_json, "controlImageSha256");
    const bool control_strength_specified =
        params_json.find("\"controlStrength\"") != std::string::npos;
    const bool control_preprocess_specified =
        params_json.find("\"controlImagePreprocess\"") != std::string::npos;
    control_strength = double_field(params_json, "controlStrength", 1.0);
    const std::string control_image_preprocess_value =
        string_field(params_json, "controlImagePreprocess");
    control_image_preprocess =
        mca::qnn::controlnet::ControlImagePreprocessMode::Canny;
    if (!mca::qnn::controlnet::parse_control_image_preprocess_mode(
            control_image_preprocess_value,
            &control_image_preprocess,
            &error)) {
        return qnn_semantic_failure_json(
            "control_image_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    if (!std::isfinite(control_strength) || control_strength < 0.0 || control_strength > 2.0) {
        return qnn_semantic_failure_json(
            "control_strength_invalid",
            "EXECUTION_CONTRACT_INVALID",
            "controlStrength must be finite and in [0, 2].");
    }
    const long long batch_count = long_field(params_json, "batchCount");
    if (batch_count != 0 && batch_count != 1) {
        return qnn_semantic_failure_json(
            "batch_count_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            "QNN semantic generation currently executes exactly one output per native request.");
    }
    if (request_controlnet) {
        if (!input_image_path.empty() || !mask_image_path.empty() ||
            control_image_path.empty() || control_image_sha256.empty()) {
            return qnn_semantic_failure_json(
                "control_image_contract_invalid",
                "EXECUTION_CONTRACT_INVALID",
                "taskMode=control requires only controlImagePath and controlImageSha256.");
        }
    } else if (request_img2img) {
        if (input_image_path.empty() || !mask_image_path.empty() ||
            !control_image_path.empty() || !control_image_sha256.empty() ||
            control_strength_specified || control_preprocess_specified) {
            return qnn_semantic_failure_json(
                "img2img_input_contract_invalid",
                "EXECUTION_CONTRACT_INVALID",
                "taskMode=img2img requires exactly one input image and no mask or ControlNet input.");
        }
    } else if (request_inpaint) {
        if (input_image_path.empty() || mask_image_path.empty() ||
            !valid_inpaint_sha256(mask_image_sha256) ||
            !control_image_path.empty() || !control_image_sha256.empty() ||
            control_strength_specified || control_preprocess_specified) {
            return qnn_semantic_failure_json(
                "inpaint_input_contract_invalid",
                "EXECUTION_CONTRACT_INVALID",
                "taskMode=inpaint requires inputImagePath, maskImagePath, and maskImageSha256, with no ControlNet input.");
        }
    } else if (!input_image_path.empty() || !mask_image_path.empty() ||
               !control_image_path.empty() || !control_image_sha256.empty() ||
               control_strength_specified || control_preprocess_specified) {
        return qnn_semantic_failure_json(
            "text_image_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            "taskMode=text_to_image cannot carry image input paths or hashes.");
    }
    const std::string family = normalized_contract_enum(string_field(params_json, "family"));
    request_sdxl =
        family == "sdxl" || contains_lower(conditioning_format, "sdxl");
    if ((request_img2img || request_inpaint) && request_sdxl) {
        return qnn_semantic_failure_json(
            request_inpaint ? "shared_sdxl_inpaint_unsupported" : "shared_sdxl_img2img_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            request_inpaint
                ? "SDXL inpaint requires an isolated split-worker graph with an inspected mask-conditioned UNet; this shared path does not provide it."
                : "SDXL img2img requires the isolated split-worker encoder/UNet/VAE chain.");
    }
    preview_contract =
        mca::qnn::preview::resolve_contract(
            preview_request.requested,
            normalized_contract_enum(string_field(params_json, "workerStrategy")),
            normalized_contract_enum(preview_request.mode),
            preview_request.interval);
    if (!preview_contract.error_code.empty()) {
        return qnn_semantic_failure_json(
            "preview_contract_invalid",
            preview_contract.error_code,
            preview_contract.message);
    }
    if (request_controlnet && request_sdxl) {
        return qnn_semantic_failure_json(
            "controlnet_family_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            "This QNN ControlNet execution path requires the SD1.x four-graph contract.");
    }
    if (!scheduler.set_timesteps(execution_contract.scheduler.steps, &error)) {
        return qnn_semantic_failure_json(
            "scheduler_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    full_timetable_count = scheduler.timesteps().size();
    request_encoder_conditioned = request_img2img || request_inpaint;
    img2img_strength = request_encoder_conditioned
        ? double_field(params_json, "strength", 1.0)
        : 1.0;
    mca::diffusion::Img2ImgTailSchedule img2img_schedule;
    if (request_encoder_conditioned && !mca::diffusion::resolve_img2img_tail_schedule(
            execution_contract.scheduler.steps,
            img2img_strength,
            &img2img_schedule,
            &error)) {
        return qnn_semantic_failure_json(
            "img2img_strength_invalid",
            "IMG2IMG_STRENGTH_INVALID",
            error);
    }
    img2img_begin_index = request_encoder_conditioned ? img2img_schedule.begin_index : 0U;
    effective_timetable_count = request_encoder_conditioned
        ? img2img_schedule.effective_step_count
        : full_timetable_count;
    if (ultra_fix_request.enabled &&
        (std::fabs(img2img_strength - ultra_fix_request.strength) > 1.0e-12 ||
         full_timetable_count != static_cast<size_t>(ultra_fix_request.refinement_steps) ||
         effective_timetable_count !=
            static_cast<size_t>(ultra_fix_request.inversion_steps))) {
        return qnn_semantic_failure_json(
            "ultrafix_schedule_mismatch",
            "QNN_ULTRAFIX_SCHEDULE_MISMATCH",
            "QNN UltraFix strength, inversion steps, and refinement timetable do not describe one exact denoising tail.");
    }
    if (request_encoder_conditioned && !scheduler.set_begin_index(img2img_begin_index, &error)) {
        return qnn_semantic_failure_json(
            "img2img_scheduler_unsupported",
            "IMG2IMG_SCHEDULER_UNSUPPORTED",
            error);
    }
    if ((request_encoder_conditioned &&
            full_timetable_count != static_cast<size_t>(execution_contract.scheduler.steps)) ||
        effective_timetable_count != execution_contract.scheduler.expected_timetable_count ||
        (request_encoder_conditioned &&
            (long_field(params_json, "fullTimetableCount") !=
                    static_cast<long long>(full_timetable_count) ||
             long_field(params_json, "effectiveDenoiseSteps") !=
                    static_cast<long long>(effective_timetable_count) ||
             long_field(params_json, "img2imgBeginIndex") !=
                    static_cast<long long>(img2img_begin_index)))) {
        return qnn_execution_contract_mismatch_json(
            "timetableCount",
            execution_contract.scheduler.expected_timetable_count,
            effective_timetable_count);
    }
    const std::string unet_binary = string_field(params_json, "unetContextBinary").empty()
        ? "unet.bin"
        : string_field(params_json, "unetContextBinary");
    const std::string vae_binary = string_field(params_json, "vaeDecoderContextBinary").empty()
        ? "vae_decoder.bin"
        : string_field(params_json, "vaeDecoderContextBinary");
    const std::string text_encoder_binary =
        string_field(params_json, "textEncoderContextBinary").empty()
            ? "text_encoder.bin"
            : string_field(params_json, "textEncoderContextBinary");
    const std::string controlnet_binary =
        string_field(params_json, "controlNetContextBinary").empty()
            ? "controlnet.bin"
            : string_field(params_json, "controlNetContextBinary");
    const std::string encoder_binary = string_field(params_json, "vaeEncoderContextBinary");
    graph_name = execution_contract.graph_name;
    const std::string requested_text_encoder_graph_name =
        string_field(params_json, "textEncoderGraphName");
    text_encoder_graph_name =
        requested_text_encoder_graph_name.empty()
            ? graph_name
            : requested_text_encoder_graph_name;
    controlnet_graph_name =
        string_field(params_json, "controlNetGraphName").empty()
            ? graph_name
            : string_field(params_json, "controlNetGraphName");
    unet_path = join_path(bundle_root, unet_binary);
    vae_path = join_path(bundle_root, vae_binary);
    text_encoder_path = join_path(bundle_root, text_encoder_binary);
    controlnet_path = join_path(bundle_root, controlnet_binary);
    const bool prompt_requires_verified_multilingual_encoder =
        qnn_prompt_contains_non_ascii(execution_contract.prompt) ||
        qnn_prompt_contains_non_ascii(execution_contract.negative_prompt);
    if (!parse_qnn_text_encoder_asset_evidence(
            params_json,
            text_encoder_binary,
            requested_text_encoder_graph_name,
            prompt_requires_verified_multilingual_encoder,
            &text_encoder_asset_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "text_encoder_asset_evidence_invalid",
            "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
            error);
    }
    tokenizer_path =
        text_encoder_asset_evidence.multilingual_evidence_required
            ? join_path(bundle_root, text_encoder_asset_evidence.tokenizer_relative_path)
            : "";
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            execution_contract.prompt,
            execution_contract.negative_prompt)) {
        return qnn_semantic_failure_json(
            "unsupported_native_prompt_language",
            "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE",
            "Native multilingual image prompts must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax.");
    }
    if (prompt_requires_verified_multilingual_encoder &&
        (!text_encoder_asset_evidence.required ||
            !text_encoder_asset_evidence.multilingual_evidence_required ||
            text_encoder_asset_evidence.language_proof_sha256.empty() ||
            text_encoder_asset_evidence.graph_name.empty() ||
            text_encoder_asset_evidence.tokenizer_relative_path.empty() ||
            text_encoder_asset_evidence.prompt_to_encoder_closure_sha256.empty())) {
        return qnn_semantic_failure_json(
            "text_encoder_language_proof_required",
            "TEXT_ENCODER_LANGUAGE_PROOF_REQUIRED",
            "Non-ASCII image prompts require complete graph and tokenizer asset evidence plus a signed prompt-to-encoder closure.");
    }
    if (text_encoder_asset_evidence.required && !qnn_token_conditioning) {
        return qnn_semantic_failure_json(
            "text_encoder_asset_evidence_invalid",
            "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
            "Strict text encoder asset evidence requires QNN token conditioning.");
    }
    if (text_encoder_asset_evidence.required &&
        !capture_qnn_text_encoder_asset_evidence(
            text_encoder_path,
            &text_encoder_asset_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "text_encoder_asset_evidence_invalid",
            "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
            error);
    }
    if (text_encoder_asset_evidence.multilingual_evidence_required &&
        !capture_qnn_tokenizer_asset_evidence(
            bundle_root,
            tokenizer_path,
            &text_encoder_asset_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "tokenizer_asset_evidence_invalid",
            "TOKENIZER_ASSET_EVIDENCE_INVALID",
            error);
    }
    if (!exists_file(unet_path) || !exists_file(vae_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"context_missing\",\"message\":") +
            quote("QNN semantic generation context is missing. Expected " +
                unet_binary + " and " + vae_binary + " in the bundle root.") + "}";
    }
    if (qnn_token_conditioning && !exists_file(text_encoder_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_context_missing\",\"message\":") +
            quote("QNN token conditioning requires " + text_encoder_binary +
                " in the bundle root.") + "}";
    }
    if (request_controlnet && !exists_file(controlnet_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"controlnet_context_missing\",\"errorCode\":\"REQUIRED_GRAPH_MISSING\",\"message\":") +
            quote("QNN control generation requires " + controlnet_binary +
                " in the bundle root.") + "}";
    }
    if ((request_img2img || request_inpaint) &&
        (!is_safe_bundle_relative_path(encoder_binary) ||
         !exists_file(join_path(bundle_root, encoder_binary)))) {
        return qnn_semantic_failure_json(
            "encoder_context_missing",
            "REQUIRED_GRAPH_MISSING",
            request_inpaint
                ? "QNN inpaint requires a concrete VAE encoder context inside the installed bundle."
                : "QNN img2img requires a concrete VAE encoder context inside the installed bundle.");
    }

    return std::string();
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
