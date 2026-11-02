#include "mca/sd/sd_response_builder.hpp"

#include <cmath>
#include <limits>
#include <vector>
#include <string>

#include "stb_image_write.h"
#include "image_execution_math.hpp"
#include "mca/sd/sd_output_transaction.hpp"
#include "mca/sd/sd_effective_config.hpp"
#include "mca/sd/sd_lora_adapter.hpp"
#include "mca/sd/sd_inpaint_processor.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_runtime_diagnostics.hpp"
#include "mca/sd/sd_context_key.hpp"
#include "mca/sd/sd_schedule_resolver.hpp"

namespace mca::sd {

using json = nlohmann::json;

json build_generation_effective_json(
    const StableDiffusionExecutionContract &contract,
    const std::string &prediction_wire,
    const sd_img_gen_params_t &gen,
    const GenerationEvidenceSummary &evidence,
    const std::string &actual_model_version,
    bool actual_qwen_image_21,
    bool split_qwen_image_21,
    uint32_t stage_mask,
    bool input_image_wired,
    const LoadedInputImage &input_image,
    bool mask_image_wired,
    const LoadedInputImage &mask_image,
    const ControlNetEvidence &controlnet_evidence,
    bool control_image_wired,
    const LoadedInputImage &control_image,
    const sd_ultrafix_execution_evidence_t &ultrafix_evidence,
    const std::vector<sd_lora_t> &native_loras,
    const sd_image_execution_evidence_t &execution_evidence,
    const std::vector<ContractTextualInversion> &validated_textual_inversions,
    bool has_execution_asset_binding,
    const mca::image::execution_assets::Binding &execution_asset_binding) {

    const double actual_cfg_scale =
            static_cast<double>(gen.sample_params.guidance.txt_cfg);
    const int semantic_unet_execution_count = contract.ultrafix_enabled
            ? contract.unet_execution_count
            : evidence.actual_unet_execution_count;
    json native_effective = native_effective_json(
            contract,
            prediction_wire,
            gen.sample_params.sample_steps,
            evidence.actual_timetable_count,
            semantic_unet_execution_count,
            actual_cfg_scale,
            evidence.actual_use_cfg,
            evidence.actual_token_count,
            evidence.actual_prompt_weighting_applied,
            evidence.actual_positive_weighted_token_count,
            evidence.actual_negative_weighted_token_count,
            evidence.actual_prompt_weight_fingerprint,
            contract.width,
            contract.height);
    native_effective["actualModelVersion"] = actual_model_version;
    const bool actual_anima = actual_model_version == "Anima";
    native_effective["actualModelFamily"] = actual_anima ? "ANIMA" : "";
    native_effective["actualModelVariant"] =
            actual_qwen_image_21 ? "QWEN_IMAGE_21" : (actual_anima ? "ANIMA_PREVIEW" : "");
    if (actual_anima) {
        // Anima is a 16-channel DiT decoded by the Qwen-image VAE (8x).
        native_effective["actualLatentChannels"] = 16;
        native_effective["actualLatentDownsample"] = 8;
    }
    native_effective["nativeScheduler"] = sd_scheduler_name(gen.sample_params.scheduler);
    if (!contract.qwen_turbo_schedule.empty()) {
        native_effective["qwenTurboSchedule"] = contract.qwen_turbo_schedule;
        native_effective["qwenRawSigmas"] = contract.qwen_raw_sigmas;
        native_effective["qwenShiftedSigmas"] = contract.qwen_custom_sigmas;
        native_effective["qwenSigmaImageSeqLen"] = contract.qwen_sigma_image_seq_len;
        native_effective["qwenSigmaMu"] = contract.qwen_sigma_mu;
    }
    const bool qwen_text_encoded =
            (stage_mask & kStageQwenTextConditionEncoded) != 0u;
    const bool qwen_text_released =
            (stage_mask & kStageQwenTextContextReleased) != 0u;
    const bool qwen_diffusion_ready =
            (stage_mask & kStageQwenDiffusionContextReady) != 0u;
    native_effective["stageParametersReleased"] =
            split_qwen_image_21 && qwen_text_released;
    native_effective["qwenTwoStage"] =
            split_qwen_image_21 && qwen_text_encoded &&
            qwen_text_released && qwen_diffusion_ready;
    native_effective["qwenTextEncoderPhase"] =
            split_qwen_image_21 && qwen_text_encoded;
    native_effective["qwenTextConditionEncoded"] =
            split_qwen_image_21 && qwen_text_encoded;
    native_effective["qwenTextEncoderReleasedBeforeDiffusion"] =
            split_qwen_image_21 && qwen_text_released && qwen_diffusion_ready;
    native_effective["qwenDiffusionVaePhase"] =
            split_qwen_image_21 && qwen_diffusion_ready;
    native_effective["inputImagePath"] = input_image_wired
            ? input_image.canonical_path : "";
    native_effective["inputImageExecutionCount"] = input_image_wired ? 1 : 0;
    native_effective["inputImageSha256"] = input_image.sha256;
    native_effective["strength"] = contract.strength;
    native_effective["backendStrength"] = input_image_wired
            ? (contract.ultrafix_enabled
                ? contract.ultrafix_strength
                : static_cast<double>(gen.strength))
            : contract.strength;
    native_effective["strengthApplied"] = input_image_wired;
    populate_inpaint_effective_json(native_effective, mask_image_wired, mask_image);
    populate_controlnet_effective_json(
            native_effective, controlnet_evidence, control_image_wired,
            evidence.actual_control_image_consumed, contract.control_strength,
            gen.control_strength, control_image);
    native_effective["clipSkip"] = gen.clip_skip;
    native_effective["batchCount"] = gen.batch_count;
    native_effective["vaeTiling"] = {
            {"enabled", evidence.actual_vae_tiling_enabled},
            {"requestedTileSize", contract.vae_tiling_enabled ? contract.vae_tile_size : 0},
            {"requestedOverlap", contract.vae_tiling_enabled ? contract.vae_tile_overlap : 0.0},
            {"encode", {
                    {"invocationCount", evidence.actual_vae_encode_tiling_invocation_count},
                    {"successCount", evidence.actual_vae_encode_tiling_success_count},
                    {"plannedTileCount", evidence.actual_vae_encode_planned_tile_count},
                    {"tileComputeAttemptCount", evidence.actual_vae_encode_tile_compute_attempt_count},
                    {"tileComputeSuccessCount", evidence.actual_vae_encode_tile_compute_success_count},
                    {"tileSizeX", evidence.actual_vae_encode_tile_size_x},
                    {"tileSizeY", evidence.actual_vae_encode_tile_size_y},
                    {"overlapX", evidence.actual_vae_encode_tile_overlap_x},
                    {"overlapY", evidence.actual_vae_encode_tile_overlap_y}
            }},
            {"decode", {
                    {"invocationCount", evidence.actual_vae_decode_tiling_invocation_count},
                    {"successCount", evidence.actual_vae_decode_tiling_success_count},
                    {"plannedTileCount", evidence.actual_vae_decode_planned_tile_count},
                    {"tileComputeAttemptCount", evidence.actual_vae_decode_tile_compute_attempt_count},
                    {"tileComputeSuccessCount", evidence.actual_vae_decode_tile_compute_success_count},
                    {"tileSizeX", evidence.actual_vae_decode_tile_size_x},
                    {"tileSizeY", evidence.actual_vae_decode_tile_size_y},
                    {"overlapX", evidence.actual_vae_decode_tile_overlap_x},
                    {"overlapY", evidence.actual_vae_decode_tile_overlap_y}
            }}
    };
    if (contract.ultrafix_enabled) {
        native_effective["ultraFix"] = {
                {"version", ultrafix_evidence.version},
                {"generationCompleted", ultrafix_evidence.generation_completed == 1u},
                {"cancelled", ultrafix_evidence.cancelled == 1u},
                {"previewPublished", ultrafix_evidence.preview_published == 1u},
                {"sourceWidth", ultrafix_evidence.source_width},
                {"sourceHeight", ultrafix_evidence.source_height},
                {"targetWidth", ultrafix_evidence.target_width},
                {"targetHeight", ultrafix_evidence.target_height},
                {"sourceFit", "cover_center"},
                {"sourceResizedWidth", ultrafix_evidence.source_resized_width},
                {"sourceResizedHeight", ultrafix_evidence.source_resized_height},
                {"sourceCropLeft", ultrafix_evidence.source_crop_left},
                {"sourceCropTop", ultrafix_evidence.source_crop_top},
                {"tileSize", ultrafix_evidence.tile_size},
                {"overlap", ultrafix_evidence.tile_overlap},
                {"inversionSteps", ultrafix_evidence.inversion_steps},
                {"refinementSteps", ultrafix_evidence.refinement_steps},
                {"denoiseStepCount", ultrafix_evidence.denoise_step_count},
                {"sampleMethod", sd_sample_method_name(gen.sample_params.sample_method)},
                {"nativeScheduler", sd_scheduler_name(gen.sample_params.scheduler)},
                {"vaeEncode", {
                        {"invocationCount", ultrafix_evidence.vae_encode_invocation_count},
                        {"successCount", ultrafix_evidence.vae_encode_success_count},
                        {"tileInvocationCount", ultrafix_evidence.vae_encode_tile_count},
                        {"tileSuccessCount", ultrafix_evidence.vae_encode_tile_success_count},
                        {"stepCount", 1}
                }},
                {"ddimInversion", {
                        {"invocationCount", ultrafix_evidence.ddim_inversion_invocation_count},
                        {"successCount", ultrafix_evidence.ddim_inversion_success_count},
                        {"tileInvocationCount", ultrafix_evidence.ddim_inversion_tile_count},
                        {"tileSuccessCount", ultrafix_evidence.ddim_inversion_tile_success_count},
                        {"stepCount", ultrafix_evidence.ddim_inversion_step_count}
                }},
                {"tiledUnetRefinement", {
                        {"invocationCount", ultrafix_evidence.tiled_unet_invocation_count},
                        {"successCount", ultrafix_evidence.tiled_unet_success_count},
                        {"tileInvocationCount", ultrafix_evidence.tiled_unet_tile_count},
                        {"tileSuccessCount", ultrafix_evidence.tiled_unet_tile_success_count},
                        {"stepCount", ultrafix_evidence.tiled_unet_step_count}
                }},
                {"tiledVaeDecode", {
                        {"invocationCount", ultrafix_evidence.tiled_vae_decode_invocation_count},
                        {"successCount", ultrafix_evidence.tiled_vae_decode_success_count},
                        {"tileInvocationCount", ultrafix_evidence.tiled_vae_decode_tile_count},
                        {"tileSuccessCount", ultrafix_evidence.tiled_vae_decode_tile_success_count},
                        {"stepCount", 1}
                }},
                {"physicalDiffusionModelComputeCount",
                        execution_evidence.diffusion_model_compute_count},
                {"qualityStepEvaluationCount",
                        ultrafix_evidence.quality_step_evaluation_count},
                {"noiseInjectionStepCount",
                        ultrafix_evidence.noise_injection_step_count},
                {"noiseInjectionSeedFingerprint",
                        sha256_utf8(mca::image::ultrafix_noise_seed_descriptor(
                                ultrafix_evidence.noise_injection_seed,
                                ultrafix_evidence.denoise_step_count))},
                {"noiseInjectionChecksum",
                        fixed_width_lower_hex_u64(
                                ultrafix_evidence.noise_injection_checksum)},
                {"structureGuidanceStepCount",
                        ultrafix_evidence.structure_guidance_step_count},
                {"structureGuidanceChecksum",
                        fixed_width_lower_hex_u64(
                                ultrafix_evidence.structure_guidance_checksum)},
                {"trajectoryNoiseChecksum",
                        fixed_width_lower_hex_u64(
                                ultrafix_evidence.trajectory_noise_checksum)}
        };
        native_effective["strengthMechanism"] = "ddim_inversion";
    }
    native_effective["outputCount"] = contract.batch_count;
    native_effective["n"] = contract.batch_count;
    native_effective["totalUnetExecutionCount"] = evidence.total_unet_execution_count;
    native_effective["samplingPassCount"] = evidence.actual_sampling_pass_count;
    native_effective["positiveDiffusionModelComputeCount"] =
            evidence.actual_positive_execution_count;
    native_effective["negativeDiffusionModelComputeCount"] =
            evidence.actual_negative_execution_count;
    native_effective["auxiliaryDiffusionModelComputeCount"] =
            evidence.actual_auxiliary_execution_count;
    native_effective["positiveConditioningTokenCount"] =
            execution_evidence.positive_conditioning_token_count;
    native_effective["negativeConditioningTokenCount"] =
            execution_evidence.negative_conditioning_token_count;
    native_effective["conditioningArtifactSha256"] =
            evidence.actual_prompt_weight_fingerprint;
    native_effective["nativePromptExecutionSha256"] =
            evidence.actual_native_prompt_execution_sha256;
    native_effective["nativePromptBindingStage"] = "conditioning_consumed";
    native_effective["requestedDistilledGuidance"] = contract.distilled_guidance;
    native_effective["distilledGuidanceSpecified"] =
            contract.distilled_guidance_specified;
    native_effective["distilledGuidanceApplied"] =
            evidence.actual_distilled_guidance_applied;
    native_effective["distilledGuidance"] = evidence.actual_distilled_guidance_applied
            ? json(evidence.actual_distilled_guidance)
            : json(nullptr);
    native_effective["requestedFlowShift"] = contract.flow_shift;
    native_effective["flowShiftSpecified"] = contract.flow_shift_specified;
    native_effective["flowShiftApplied"] = evidence.actual_flow_shift_applied;
    native_effective["dynamicFlowShift"] = evidence.actual_dynamic_flow_shift;
    native_effective["flowShift"] = evidence.actual_flow_shift_applied
            ? json(evidence.actual_flow_shift)
            : json(nullptr);
    native_effective["imageInputConsumption"] = {
            {"input", input_image_wired ? "init_latent" : "none"},
            {"mask", mask_image_wired ? "denoise_mask" : "none"},
            {"control", evidence.actual_control_image_consumed ? "controlnet_residual" : "none"}
    };
    native_effective["loras"] =
            mca::sd::format_lora_effective_json(contract.loras, native_loras);
    native_effective["loraEvidence"] =
            mca::sd::format_lora_evidence_json(execution_evidence);
    native_effective["textualInversions"] = json::array();
    for (const auto &artifact: validated_textual_inversions) {
        native_effective["textualInversions"].push_back({
                {"id", artifact.id},
                {"trigger", artifact.trigger},
                {"sha256", artifact.sha256},
                {"sizeBytes", artifact.size_bytes},
                {"format", artifact.format},
                {"runtime", artifact.runtime},
                {"modelFingerprint", artifact.model_fingerprint},
                {"tokenizerFingerprint", artifact.tokenizer_fingerprint},
                {"profileId", artifact.profile_id},
                {"profileRevision", artifact.profile_revision},
                {"bindingFingerprint", artifact.binding_fingerprint}
        });
    }
    native_effective["textualInversionEvidence"] = {
            {"requestedCount", execution_evidence.textual_inversion_requested_count},
            {"validatedCount", validated_textual_inversions.size()},
            {"loadAttemptCount", execution_evidence.textual_inversion_load_attempt_count},
            {"loadedCount", execution_evidence.textual_inversion_loaded_count},
            {"tokenizerMatchCount", execution_evidence.textual_inversion_tokenizer_match_count},
            {"appliedCount", execution_evidence.textual_inversion_applied_count},
            {"appliedVectorCount", execution_evidence.textual_inversion_applied_vector_count},
            {"conditioningConsumptionCount",
                    execution_evidence.textual_inversion_conditioning_consumption_count},
            {"clipLAppliedCount", execution_evidence.textual_inversion_clip_l_applied_count},
            {"clipGAppliedCount", execution_evidence.textual_inversion_clip_g_applied_count},
            {"requestedMask", execution_evidence.textual_inversion_requested_mask},
            {"loadedMask", execution_evidence.textual_inversion_loaded_mask},
            {"tokenizerMatchMask", execution_evidence.textual_inversion_tokenizer_match_mask},
            {"appliedMask", execution_evidence.textual_inversion_applied_mask},
            {"consumedMask", execution_evidence.textual_inversion_consumed_mask},
            {"clipLMask", execution_evidence.textual_inversion_clip_l_mask},
            {"clipGMask", execution_evidence.textual_inversion_clip_g_mask},
            {"clipGRequiredMask",
                    execution_evidence.textual_inversion_clip_g_required_mask},
            {"failureCode", "none"},
            {"bindingFingerprint", contract.textual_inversion_binding_fingerprint},
            {"nativeMode", validated_textual_inversions.empty()
                    ? "none" : "SDCPP_CUSTOM_WORDS"},
            {"bindingStage", validated_textual_inversions.empty()
                    ? "none" : "conditioning_consumed"}
    };
    if (has_execution_asset_binding) {
        mca::image::execution_assets::append_evidence(
                native_effective,
                execution_asset_binding);
    }
    return native_effective;
}

bool write_generation_png_batch(
    const StableDiffusionExecutionContract &contract,
    const std::string &ctx_key,
    const std::vector<std::string> &output_paths,
    sd_image_t *images,
    int actual_width,
    int actual_height,
    int actual_channels,
    const std::atomic<bool> &cancel_requested,
    std::vector<std::string> &output_sha256s,
    std::vector<uint64_t> &output_byte_counts,
    std::string &output_write_error) {

    output_sha256s.assign(static_cast<size_t>(contract.batch_count), "");
    output_byte_counts.assign(static_cast<size_t>(contract.batch_count), 0u);
    const int stride = actual_width * actual_channels;

    for (int index = 0; index < contract.batch_count; ++index) {
        bool wrote = false;
        if (contract.ultrafix_enabled) {
            mca::contract::RequestIdentity tx_identity;
            tx_identity.request_id = ctx_key;
            const auto tx_result = mca::sd::SdOutputTransaction::write_png_atomic(
                output_paths[static_cast<size_t>(index)],
                actual_width,
                actual_height,
                actual_channels,
                images[index].data,
                tx_identity,
                [&]() { return cancel_requested.load(std::memory_order_relaxed); });
            if (tx_result.success) {
                output_sha256s[static_cast<size_t>(index)] = tx_result.sha256_hex;
                output_byte_counts[static_cast<size_t>(index)] = tx_result.bytes_written;
                wrote = true;
            } else {
                output_write_error = tx_result.error.has_value()
                        ? tx_result.error->public_message
                        : "atomic PNG publication failed";
                wrote = false;
            }
        } else {
            wrote = stbi_write_png(
                output_paths[static_cast<size_t>(index)].c_str(),
                actual_width,
                actual_height,
                actual_channels,
                images[index].data,
                stride) != 0;
        }
        if (!wrote) {
            return false;
        }
    }
    return true;
}

json build_generation_success_response(
    json native_effective,
    const StableDiffusionExecutionContract &contract,
    const std::vector<std::string> &output_paths,
    const std::vector<std::string> &output_sha256s,
    const std::vector<uint64_t> &output_byte_counts,
    const sd_img_gen_params_t &gen,
    const GenerationEvidenceSummary &evidence,
    const sd_image_execution_evidence_t &execution_evidence,
    int actual_width,
    int actual_height,
    int actual_channels,
    int observed_prediction,
    int ui_progress_callback_count,
    int ui_progress_reported_steps,
    int ui_progress_max_step,
    preview_t native_preview_mode,
    const PreviewPublicationContext &preview_context,
    bool input_image_wired,
    const LoadedInputImage &input_image,
    bool mask_image_wired,
    const LoadedInputImage &mask_image,
    bool control_image_wired,
    const LoadedInputImage &control_image,
    const std::string &selected_components_json,
    uint64_t sequence) {

    json out = native_effective;
    out["ok"] = true;
    out["nativeExecution"] = true;
    out["executionStage"] = "semantic_generation_passed";
    out["path"] = output_paths.front();
    out["mimeType"] = "image/png";
    out["outputs"] = json::array();
    for (int index = 0; index < contract.batch_count; ++index) {
        json output_entry = {
                {"index", index},
                {"path", output_paths[static_cast<size_t>(index)]},
                {"mimeType", "image/png"},
                {"seed", contract.seed + index},
                {"width", actual_width},
                {"height", actual_height},
                {"channels", actual_channels}
        };
        if (contract.ultrafix_enabled) {
            output_entry["sha256"] = output_sha256s[static_cast<size_t>(index)];
            output_entry["sizeBytes"] = output_byte_counts[static_cast<size_t>(index)];
            output_entry["atomicCommit"] = true;
        }
        out["outputs"].push_back(std::move(output_entry));
    }
    if (contract.ultrafix_enabled) {
        out["outputSha256"] = output_sha256s.front();
        out["outputSizeBytes"] = output_byte_counts.front();
        out["outputAtomicCommit"] = true;
        native_effective["outputSha256"] = output_sha256s.front();
        native_effective["outputSizeBytes"] = output_byte_counts.front();
        native_effective["outputAtomicCommit"] = true;
        out["nativeEffective"] = native_effective;
    }
    out["outputCount"] = contract.batch_count;
    out["n"] = contract.batch_count;
    out["threads"] = contract.threads;
    out["sampleMethod"] = sd_sample_method_name(gen.sample_params.sample_method);
    out["nativeScheduler"] = sd_scheduler_name(gen.sample_params.scheduler);
    out["nativePredictionMode"] = observed_prediction_mode(observed_prediction);
    out["actualDiffusionModelComputeCount"] =
            execution_evidence.diffusion_model_compute_count;
    out["actualPositiveDiffusionModelComputeCount"] =
            execution_evidence.positive_diffusion_model_compute_count;
    out["actualNegativeDiffusionModelComputeCount"] =
            execution_evidence.negative_diffusion_model_compute_count;
    out["actualAuxiliaryDiffusionModelComputeCount"] =
            execution_evidence.auxiliary_diffusion_model_compute_count;
    out["actualSamplingStepCount"] =
            execution_evidence.completed_sampling_step_count;
    out["actualSamplingPassCount"] = evidence.actual_sampling_pass_count;
    out["actualPositiveConditioningTokenCount"] =
            execution_evidence.positive_conditioning_token_count;
    out["actualNegativeConditioningTokenCount"] =
            execution_evidence.negative_conditioning_token_count;
    out["conditioningArtifactSha256"] = evidence.actual_prompt_weight_fingerprint;
    out["nativePromptExecutionSha256"] =
            evidence.actual_native_prompt_execution_sha256;
    out["nativePromptBindingStage"] = "conditioning_consumed";
    out["totalUnetExecutionCount"] = evidence.total_unet_execution_count;
    out["uiProgressCallbackCount"] = ui_progress_callback_count;
    out["uiProgressReportedSteps"] = ui_progress_reported_steps;
    out["uiProgressMaxStep"] = ui_progress_max_step;
    populate_preview_response_json(
            out, native_preview_mode, contract.preview_mode,
            contract.preview_interval, preview_context);
    out["configuredSampleSteps"] = gen.sample_params.sample_steps;
    out["actualDenoisingStepCount"] = evidence.actual_timetable_count;
    out["cfgBranchCount"] = evidence.actual_branches;
    out["negativePrompt"] = contract.negative_prompt;
    out["taskMode"] = contract.task_mode;
    out["inputImagePath"] = input_image_wired ? input_image.canonical_path : "";
    out["inputImageExecutionCount"] = input_image_wired ? 1 : 0;
    out["inputImageSha256"] = input_image.sha256;
    out["inputImageSourceWidth"] = input_image.source_width;
    out["inputImageSourceHeight"] = input_image.source_height;
    out["inputImageSourceChannels"] = input_image.source_channels;
    out["inputImageWidth"] = input_image.width;
    out["inputImageHeight"] = input_image.height;
    out["inputImageChannels"] = input_image.channels;
    out["inputImageExifOrientation"] = input_image.exif_orientation;
    populate_inpaint_response_json(out, mask_image_wired, mask_image);
    populate_controlnet_response_json(
            out, native_effective, control_image_wired,
            evidence.actual_control_image_consumed, control_image);
    out["imageInputConsumption"] = native_effective["imageInputConsumption"];
    out["strength"] = native_effective["strength"];
    out["backendStrength"] = native_effective["backendStrength"];
    out["clipSkip"] = native_effective["clipSkip"];
    out["batchCount"] = native_effective["batchCount"];
    out["vaeTiling"] = native_effective["vaeTiling"];
    out["loras"] = native_effective["loras"];
    out["loraEvidence"] = native_effective["loraEvidence"];
    out["textualInversions"] = native_effective["textualInversions"];
    out["textualInversionEvidence"] = native_effective["textualInversionEvidence"];
    out["backendMode"] = "cpu";
    out["backend"] = "stable-diffusion.cpp";
    out["runtimeBackend"] = sd_runtime_backend_label();
    out["componentSelection"] = json::parse(selected_components_json);
    out["systemInfo"] = sd_get_system_info();
    out["nativeEffective"] = native_effective;
    out["nativeGenerationSequence"] = sequence;
    return out;
}

} // namespace mca::sd
