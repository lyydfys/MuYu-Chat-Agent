#include "mca/qnn/qnn_conditioning_pipeline.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "image_conditioning.hpp"
#include "native_prompt_language_contract.hpp"
// image_pixel_range_wire_name() is used by qnn_native_effective_json(); its declaration lives in the
// root QNN contract header, which this TU did not previously need to include.
#include "qnn_image_pixel_range.hpp"
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"

#if defined(_WIN32)
#include <io.h>
#include <direct.h>
#include <stdlib.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define realpath(N, R) _fullpath((R), (N), PATH_MAX)
#define lstat(P, S) stat((P), (S))
#ifndef S_ISREG
#define S_ISREG(m) (((m) & S_IFMT) == S_IFREG)
#endif
#else
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits.h>
#endif

namespace mca {
namespace qnn {

std::string qnn_double_array_json(const std::vector<double>& values) {
    std::ostringstream out;
    out << std::setprecision(17) << "[";
    for (size_t index = 0; index < values.size(); ++index) {
        if (index > 0) out << ",";
        out << values[index];
    }
    out << "]";
    return out.str();
}

std::string qnn_consumed_prompt_to_encoder_assets_json(
        const QnnNativeEffectiveEvidence& evidence) {
    if (evidence.consumed_prompt_to_encoder_binding_stage != "conditioning_consumed") {
        return "[]";
    }
    std::ostringstream out;
    out << "["
        << "{\"role\":\"TEXT_ENCODER_GRAPH\",\"path\":"
        << quote(evidence.consumed_text_encoder_path)
        << ",\"sha256\":" << quote(evidence.consumed_text_encoder_sha256)
        << ",\"sizeBytes\":" << evidence.consumed_text_encoder_size_bytes << "},"
        << "{\"role\":\"TOKENIZER_JSON\",\"path\":"
        << quote(evidence.consumed_tokenizer_path)
        << ",\"sha256\":" << quote(evidence.consumed_tokenizer_sha256)
        << ",\"sizeBytes\":" << evidence.consumed_tokenizer_size_bytes << "}"
        << "]";
    return out.str();
}

// Keep the flat receipt and nativeEffective receipt byte-for-byte aligned for the
// role-aware prompt-to-encoder proof. Empty values are intentional for legacy English
// requests, which do not opt into the multilingual closure contract.
std::string qnn_prompt_to_encoder_receipt_json_fields(
        const QnnNativeEffectiveEvidence& evidence) {
    std::ostringstream out;
    out << "\"textEncoderGraphName\":" << quote(evidence.text_encoder_graph_name) << ","
        << "\"loadedTextEncoderGraphName\":"
        << quote(evidence.loaded_text_encoder_graph_name) << ","
        << "\"consumedTextEncoderPath\":"
        << quote(evidence.consumed_text_encoder_path) << ","
        << "\"consumedTextEncoderSha256\":"
        << quote(evidence.consumed_text_encoder_sha256) << ","
        << "\"consumedTextEncoderSizeBytes\":"
        << evidence.consumed_text_encoder_size_bytes << ","
        << "\"consumedTextEncoderAssetVerified\":"
        << (evidence.consumed_text_encoder_asset_verified ? "true" : "false") << ","
        << "\"consumedTokenizerPath\":"
        << quote(evidence.consumed_tokenizer_path) << ","
        << "\"consumedTokenizerSha256\":"
        << quote(evidence.consumed_tokenizer_sha256) << ","
        << "\"consumedTokenizerSizeBytes\":"
        << evidence.consumed_tokenizer_size_bytes << ","
        << "\"consumedTokenizerAssetVerified\":"
        << (evidence.consumed_tokenizer_asset_verified ? "true" : "false") << ","
        << "\"tokenizerBindingStage\":"
        << quote(evidence.tokenizer_binding_stage) << ","
        << "\"mnnPromptHandoffVerified\":"
        << (evidence.mnn_prompt_handoff_verified ? "true" : "false") << ","
        << "\"tokenizerReceiptCanonicalPath\":"
        << quote(evidence.tokenizer_receipt_canonical_path) << ","
        << "\"tokenizerReceiptSha256\":"
        << quote(evidence.tokenizer_receipt_sha256) << ","
        << "\"tokenizerReceiptSizeBytes\":"
        << evidence.tokenizer_receipt_size_bytes << ","
        << "\"tokenizerReceiptBindingStage\":"
        << quote(evidence.tokenizer_receipt_binding_stage) << ","
        << "\"languageProofSha256\":"
        << quote(evidence.language_proof_sha256) << ","
        << "\"promptToEncoderClosureSha256\":"
        << quote(evidence.consumed_prompt_to_encoder_closure_sha256) << ","
        << "\"consumedPromptToEncoderClosureSha256\":"
        << quote(evidence.consumed_prompt_to_encoder_closure_sha256) << ","
        << "\"consumedPromptToEncoderBindingStage\":"
        << quote(evidence.consumed_prompt_to_encoder_binding_stage) << ","
        << "\"consumedPromptToEncoderAssets\":"
        << qnn_consumed_prompt_to_encoder_assets_json(evidence);
    return out.str();
}

// Migrated back from qnn_native_bridge.cpp (pre-refactor HEAD 76348a5b lines 6064-6336).  The
// declaration had been carried into mca/qnn/qnn_conditioning_pipeline.hpp and three call sites had
// been migrated (qnn_semantic_pipeline.cpp:1757/3435, qnn_sdxl_unet_phase.hpp:1155), but the
// definition itself was left behind in the removed bridge, so every nativeEffective receipt --
// including the inpaint / img2img / ControlNet / UltraFix evidence the Kotlin contract tests read --
// was an undefined symbol and mca_qnn_native could not link.
// Restored verbatim; the only difference from the bridge copy is that it now sits in namespace
// mca::qnn (the callers and the declaration already live there).
std::string qnn_native_effective_json(
        const QnnSemanticExecutionContract& contract,
        const QnnNativeEffectiveEvidence& evidence) {
    std::ostringstream out;
    out << std::setprecision(17)
        << "{"
        << "\"profileId\":" << quote(contract.profile_id) << ","
        << "\"profileRevision\":" << contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\","
        << "\"scheduler\":" << quote(qnn_scheduler_wire_name(contract.scheduler.config.algorithm)) << ","
        << "\"predictionType\":" << quote(qnn_prediction_wire_name(contract.scheduler.config.prediction_type)) << ","
        << "\"steps\":" << contract.scheduler.steps << ","
        << "\"timetableCount\":" << evidence.timetable_count << ","
        << "\"unetExecutionCount\":" << evidence.unet_execution_count << ","
        << "\"cfgScale\":" << contract.cfg_scale << ","
        << "\"useCfg\":" << (contract.use_cfg ? "true" : "false") << ","
        << "\"unconditionalBranch\":" << (contract.use_cfg ? "true" : "false") << ","
        << "\"tokenizerBackend\":" << quote(evidence.tokenizer_backend) << ","
        << "\"tokenCount\":" << evidence.token_count << ","
        << "\"promptWeightingSupported\":" << (contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingApplied\":" << (evidence.prompt_weighting_applied ? "true" : "false") << ","
        << "\"positiveWeightedTokenCount\":" << evidence.positive_weighted_token_count << ","
        << "\"negativeWeightedTokenCount\":" << evidence.negative_weighted_token_count << ","
        << "\"promptWeightFingerprint\":" << quote(evidence.prompt_weight_fingerprint) << ","
        << "\"nativePromptExecutionSha256\":"
        << quote(evidence.native_prompt_execution_sha256) << ","
        << "\"nativePromptBindingStage\":"
        << quote(evidence.native_prompt_binding_stage) << ","
        << "\"conditioningArtifactSha256\":"
        << quote(evidence.conditioning_artifact_sha256) << ","
        << "\"conditioningExecutionMode\":" << quote(evidence.conditioning_execution_mode) << ","
        << "\"conditioningBackend\":" << quote(evidence.conditioning_backend) << ","
        << "\"conditioningGraph\":" << quote(evidence.conditioning_graph) << ","
        << "\"conditioningGraphSha256\":"
        << quote(evidence.conditioning_graph_sha256) << ","
        << "\"conditioningOrder\":" << quote(evidence.conditioning_order) << ","
        << "\"conditioningEncoderExecutionCount\":"
        << evidence.conditioning_encoder_execution_count << ","
        << "\"textEncoderExecutionCount\":" << evidence.text_encoder_execution_count << ","
        << qnn_prompt_to_encoder_receipt_json_fields(evidence) << ","
        << "\"conditioningArtifactConsumed\":"
        << (evidence.conditioning_artifact_consumed ? "true" : "false") << ","
        << "\"runtimeSessionMode\":" << quote(evidence.runtime_session_mode) << ","
        << "\"embeddingDiskDataType\":" << quote(evidence.embedding_disk_data_type) << ","
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << contract.vae_scaling_factor << ","
        << "\"pixelRange\":" << quote(mca::qnn::image_pixel_range_wire_name(contract.pixel_range)) << ","
        << "\"width\":" << evidence.width << ","
        << "\"height\":" << evidence.height << ","
        << "\"seed\":" << contract.seed << ","
        << "\"graphName\":" << quote(evidence.graph_name) << ","
        << "\"taskMode\":" << quote(evidence.task_mode) << ","
        << "\"batchCount\":1,"
        << "\"inputImagePath\":" << quote(evidence.input_image_path) << ","
        << "\"maskImagePath\":" << quote(evidence.mask_image_path) << ","
        << "\"controlImagePath\":" << quote(evidence.control_image_path) << ","
        << "\"inputImageExecutionCount\":" << evidence.input_image_execution_count << ","
        << "\"maskImageExecutionCount\":" << evidence.mask_image_execution_count << ","
        << "\"maskImageSha256\":" << quote(evidence.mask_image_sha256) << ","
        << "\"maskImageSizeBytes\":" << evidence.mask_image_source_bytes << ","
        << "\"maskImageSourceReadByNative\":false,"
        << "\"maskImageSourceValidation\":"
        << quote(evidence.mask_image_execution_count == 1U
            ? "android_preprocess_provenance"
            : "none") << ","
        << "\"maskImageTensorPath\":" << quote(evidence.mask_image_tensor_path) << ","
        << "\"maskImageTensorSha256\":" << quote(evidence.mask_image_tensor_sha256) << ","
        << "\"maskImageTensorBytes\":" << evidence.mask_image_tensor_bytes << ","
        << "\"maskImageTensorShape\":[1,1,"
        << (evidence.mask_image_execution_count == 1U ? contract.height / 8 : 0)
        << ","
        << (evidence.mask_image_execution_count == 1U ? contract.width / 8 : 0)
        << "],"
        << "\"maskImageTensorDtype\":" << quote(evidence.mask_image_tensor_dtype) << ","
        << "\"maskImageTensorLayout\":" << quote(evidence.mask_image_tensor_layout) << ","
        << "\"maskImageTensorRange\":" << quote(evidence.mask_image_tensor_range) << ","
        << "\"maskImageTensorPreprocess\":" << quote(evidence.mask_image_tensor_preprocess) << ","
        << "\"maskImageFullTensorPath\":" << quote(evidence.mask_image_full_tensor_path) << ","
        << "\"maskImageFullTensorSha256\":" << quote(evidence.mask_image_full_tensor_sha256) << ","
        << "\"maskImageFullTensorBytes\":" << evidence.mask_image_full_tensor_bytes << ","
        << "\"maskImageFullTensorShape\":[1,1,"
        << (evidence.mask_image_execution_count == 1U ? contract.height : 0) << ","
        << (evidence.mask_image_execution_count == 1U ? contract.width : 0) << "],"
        << "\"maskImageFullTensorDtype\":" << quote(evidence.mask_image_full_tensor_dtype) << ","
        << "\"maskImageFullTensorLayout\":" << quote(evidence.mask_image_full_tensor_layout) << ","
        << "\"maskImageFullTensorRange\":" << quote(evidence.mask_image_full_tensor_range) << ","
        << "\"maskImageFullTensorPreprocess\":"
        << quote(evidence.mask_image_full_tensor_preprocess) << ","
        << "\"maskImageSourceWidth\":" << evidence.mask_image_source_width << ","
        << "\"maskImageSourceHeight\":" << evidence.mask_image_source_height << ","
        << "\"maskImageOrientedWidth\":" << evidence.mask_image_oriented_width << ","
        << "\"maskImageOrientedHeight\":" << evidence.mask_image_oriented_height << ","
        << "\"maskImageExifOrientation\":" << evidence.mask_image_exif_orientation << ","
        << "\"maskImageRepaintPixelCount\":" << evidence.mask_image_repaint_pixel_count << ","
        << "\"maskImageLatentRepaintPixelCount\":"
        << evidence.mask_image_latent_repaint_pixel_count << ","
        << "\"maskedInputImageTensorPath\":"
        << quote(evidence.masked_input_image_tensor_path) << ","
        << "\"maskedInputImageTensorSha256\":"
        << quote(evidence.masked_input_image_tensor_sha256) << ","
        << "\"maskedInputImageTensorBytes\":"
        << evidence.masked_input_image_tensor_bytes << ","
        << "\"maskedInputImageTensorShape\":[1,3,"
        << (evidence.masked_input_image_tensor_bytes > 0 ? contract.height : 0)
        << ","
        << (evidence.masked_input_image_tensor_bytes > 0 ? contract.width : 0)
        << "],"
        << "\"maskedInputImageTensorDtype\":"
        << quote(evidence.masked_input_image_tensor_dtype) << ","
        << "\"maskedInputImageTensorLayout\":"
        << quote(evidence.masked_input_image_tensor_layout) << ","
        << "\"maskedInputImageTensorRange\":"
        << quote(evidence.masked_input_image_tensor_range) << ","
        << "\"maskedInputImageTensorPreprocess\":"
        << quote(evidence.masked_input_image_tensor_preprocess) << ","
        << "\"maskedInputBufferSha256\":" << quote(evidence.masked_input_buffer_sha256) << ","
        << "\"maskedInputMeanBufferSha256\":"
        << quote(evidence.masked_input_mean_buffer_sha256) << ","
        << "\"maskedInputStdBufferSha256\":"
        << quote(evidence.masked_input_std_buffer_sha256) << ","
        << "\"maskedInputLatentSha256\":" << quote(evidence.masked_input_latent_sha256) << ","
        << "\"maskedInputLatentShape\":[1,4,"
        << (evidence.masked_input_encoder_execution_count == 1U ? contract.height / 8 : 0)
        << ","
        << (evidence.masked_input_encoder_execution_count == 1U ? contract.width / 8 : 0)
        << "],"
        << "\"maskedInputEncoderExecutionCount\":"
        << evidence.masked_input_encoder_execution_count << ","
        << "\"maskedInputPosteriorSampleCount\":"
        << evidence.masked_input_posterior_sample_count << ","
        << "\"maskedInputEncoderExecuteMs\":" << evidence.masked_input_encoder_execute_ms << ","
        << "\"inpaintTopology\":" << quote(evidence.inpaint_topology) << ","
        << "\"inpaintMaskUnetBindCount\":" << evidence.inpaint_mask_unet_bind_count << ","
        << "\"inpaintPreserveStepCount\":" << evidence.inpaint_preserve_step_count << ","
        << "\"inpaintLatentBlendCount\":" << evidence.inpaint_latent_blend_count << ","
        << "\"inpaintSourceEncoderExecutionCount\":"
        << evidence.inpaint_source_encoder_execution_count << ","
        << "\"inpaintPreservedLatentChecksum\":"
        << quote(evidence.inpaint_preserved_latent_checksum) << ","
        << "\"inpaintSourceNoiseSha256\":"
        << quote(evidence.inpaint_source_noise_sha256) << ","
        << "\"inpaintSourceNoiseUseCount\":"
        << evidence.inpaint_source_noise_use_count << ","
        << "\"inpaintFinalMode\":" << quote(evidence.inpaint_final_mode) << ","
        << "\"inpaintPixelBlendLevels\":" << evidence.inpaint_pixel_blend_levels << ","
        << "\"inpaintPixelBlendChecksum\":"
        << quote(evidence.inpaint_pixel_blend_checksum) << ","
        << "\"inpaintPixelBlendApplied\":"
        << (evidence.inpaint_pixel_blend_applied ? "true" : "false") << ","
        << "\"inpaintUnmaskedPreservationApplied\":"
        << (evidence.inpaint_unmasked_preservation_applied ? "true" : "false") << ","
        << "\"inpaintMaskConsumed\":"
        << (evidence.mask_image_consumed ? "true" : "false") << ","
        << "\"controlImageExecutionCount\":" << evidence.control_image_execution_count << ","
        << "\"controlImageSha256\":" << quote(evidence.control_image_sha256) << ","
        << "\"controlImagePreprocessedSha256\":"
        << quote(evidence.control_image_preprocessed_sha256) << ","
        << "\"controlImagePreprocess\":" << quote(evidence.control_image_preprocess) << ","
        << "\"inputImageSha256\":" << quote(evidence.input_image_sha256) << ","
        << "\"inputImageTensorPath\":" << quote(evidence.input_image_tensor_path) << ","
        << "\"inputImageTensorSha256\":" << quote(evidence.input_image_tensor_sha256) << ","
        << "\"inputImageTensorBytes\":" << evidence.input_image_tensor_bytes << ","
        << "\"inputImageTensorShape\":[1,3," << evidence.input_image_tensor_height
        << "," << evidence.input_image_tensor_width << "],"
        << "\"inputImageTensorDtype\":" << quote(evidence.input_image_tensor_dtype) << ","
        << "\"inputImageTensorLayout\":" << quote(evidence.input_image_tensor_layout) << ","
        << "\"inputImageTensorRange\":" << quote(evidence.input_image_tensor_range) << ","
        << "\"inputImageSourceReadByNative\":false,"
        << "\"inputImageSourceValidation\":"
        << quote(evidence.input_image_execution_count == 1U
            ? "android_preprocess_provenance"
            : "none") << ","
        << "\"encoderLatentSha256\":" << quote(evidence.encoder_latent_sha256) << ","
        << "\"encoderContextSha256\":" << quote(evidence.encoder_context_sha256) << ","
        << "\"encoderGraphName\":" << quote(evidence.encoder_graph_name) << ","
        << "\"encoderContextLoadCount\":" << evidence.encoder_context_load_count << ","
        << "\"encoderExecutionCount\":" << evidence.encoder_execution_count << ","
        << "\"encoderInputName\":" << quote(evidence.encoder_input_name) << ","
        << "\"encoderMeanOutputName\":" << quote(evidence.encoder_mean_output_name) << ","
        << "\"encoderStdOutputName\":" << quote(evidence.encoder_std_output_name) << ","
        << "\"encoderInputDtype\":" << quote(evidence.encoder_input_dtype) << ","
        << "\"encoderMeanDtype\":" << quote(evidence.encoder_mean_dtype) << ","
        << "\"encoderStdDtype\":" << quote(evidence.encoder_std_dtype) << ","
        << "\"encoderInputShape\":[1,3," << evidence.encoder_graph_input_height
        << "," << evidence.encoder_graph_input_width << "],"
        << "\"encoderMeanShape\":[1,4," << evidence.encoder_graph_output_height
        << "," << evidence.encoder_graph_output_width << "],"
        << "\"encoderStdShape\":[1,4," << evidence.encoder_graph_output_height
        << "," << evidence.encoder_graph_output_width << "],"
        << "\"encoderInputBufferSha256\":"
        << quote(evidence.encoder_input_buffer_sha256) << ","
        << "\"encoderMeanBufferSha256\":"
        << quote(evidence.encoder_mean_buffer_sha256) << ","
        << "\"encoderStdBufferSha256\":"
        << quote(evidence.encoder_std_buffer_sha256) << ","
        << "\"posteriorSampling\":"
        << quote(evidence.encoder_execution_count > 0U
            ? "mean_plus_std_times_normal_mt19937_domain_v1"
            : "none") << ","
        << "\"posteriorSampleCount\":" << evidence.encoder_posterior_sample_count << ","
        << "\"encoderLatentScalingFactor\":"
        << evidence.encoder_latent_scaling_factor << ","
        << "\"encoderContextReleasedBeforeSharedSession\":"
        << (evidence.encoder_context_released_before_shared_session ? "true" : "false") << ","
        << "\"encoderRuntimeMode\":" << quote(evidence.encoder_runtime_mode) << ","
        << "\"inputImagePreprocess\":" << quote(evidence.input_image_preprocess) << ","
        << "\"inputImageSourceWidth\":" << evidence.input_image_source_width << ","
        << "\"inputImageSourceHeight\":" << evidence.input_image_source_height << ","
        << "\"inputImageOrientedWidth\":" << evidence.input_image_oriented_width << ","
        << "\"inputImageOrientedHeight\":" << evidence.input_image_oriented_height << ","
        << "\"inputImageExifOrientation\":" << evidence.input_image_exif_orientation << ","
        << "\"inputImageTensorWidth\":" << evidence.input_image_tensor_width << ","
        << "\"inputImageTensorHeight\":" << evidence.input_image_tensor_height << ","
        << "\"inputImageTensorChannels\":" << evidence.input_image_tensor_channels << ","
        << "\"img2imgBeginIndex\":" << evidence.img2img_begin_index << ","
        << "\"fullTimetableCount\":" << evidence.full_timetable_count << ","
        << "\"effectiveDenoiseSteps\":" << evidence.effective_denoise_steps << ","
        << "\"img2imgAddNoiseApplied\":"
        << (evidence.img2img_add_noise_applied ? "true" : "false") << ","
        << "\"img2imgAddNoiseBeginIndex\":"
        << evidence.img2img_add_noise_begin_index << ","
        << "\"img2imgAddNoiseTimestep\":" << evidence.img2img_add_noise_timestep << ","
        << "\"img2imgNoiseChecksum\":" << quote(evidence.img2img_noise_checksum) << ","
        << "\"strength\":" << evidence.strength << ","
        << "\"controlStrength\":" << evidence.control_strength << ","
        << "\"controlStrengthApplied\":"
        << (evidence.control_image_execution_count == 1U ? "true" : "false") << ","
        << "\"clipSkip\":-1,"
        << "\"controlNetExecutionCount\":" << evidence.controlnet_execution_count << ","
        << "\"controlNetResidualTensorCount\":" << evidence.controlnet_residual_tensor_count << ","
        << "\"controlNetResidualWriteCount\":" << evidence.controlnet_residual_write_count << ","
        << "\"controlNetResidualUnetReuseCount\":"
        << evidence.controlnet_residual_unet_reuse_count << ","
        << "\"controlNetConditioningBranch\":"
        << quote(evidence.controlnet_conditioning_branch) << ","
        << "\"controlNetInputConsumed\":"
        << (evidence.controlnet_input_consumed ? "true" : "false") << ","
        << "\"controlNetGraph\":" << quote(evidence.controlnet_graph_name) << ","
        << "\"controlImageSourceWidth\":" << evidence.control_image_source_width << ","
        << "\"controlImageSourceHeight\":" << evidence.control_image_source_height << ","
        << "\"controlImageSourceChannels\":" << evidence.control_image_source_channels << ","
        << "\"controlImageTensorWidth\":" << evidence.control_image_tensor_width << ","
        << "\"controlImageTensorHeight\":" << evidence.control_image_tensor_height << ","
        << "\"controlImageTensorChannels\":" << evidence.control_image_tensor_channels << ","
        << "\"controlImageExifOrientation\":" << evidence.control_image_exif_orientation << ","
        << "\"controlImageEdgePixelCount\":" << evidence.control_image_edge_pixel_count << ","
        << "\"controlImageTensorChecksum\":" << evidence.control_image_tensor_checksum << ","
        << "\"controlNetInputBufferSha256\":"
        << quote(evidence.controlnet_input_buffer_sha256) << ","
        << "\"controlNetScaledResidualChecksum\":"
        << evidence.controlnet_scaled_residual_checksum << ","
        << "\"fallback\":false";
    if (!evidence.ultra_fix_json.empty()) {
        out << ",\"ultraFix\":" << evidence.ultra_fix_json
            << ",\"strengthMechanism\":\"ddim_inversion\""
            << ",\"outputSha256\":" << quote(evidence.ultra_fix_output_sha256)
            << ",\"outputSizeBytes\":" << evidence.ultra_fix_output_bytes
            << ",\"outputAtomicCommit\":"
            << (evidence.ultra_fix_output_atomic_commit ? "true" : "false")
            << ",\"positiveDiffusionModelComputeCount\":"
            << evidence.ultra_fix_positive_diffusion_model_compute_count
            << ",\"negativeDiffusionModelComputeCount\":"
            << evidence.ultra_fix_negative_diffusion_model_compute_count
            << ",\"auxiliaryDiffusionModelComputeCount\":"
            << evidence.ultra_fix_auxiliary_diffusion_model_compute_count
            << ",\"samplingPassCount\":" << evidence.ultra_fix_sampling_pass_count
            << ",\"totalUnetExecutionCount\":"
            << evidence.ultra_fix_total_unet_execution_count;
    }
    out << "}";
    return out.str();
}

std::string qnn_semantic_failure_json(
        const std::string& execution_stage,
        const std::string& error_code,
        const std::string& message) {
    std::ostringstream out;
    out << "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,"
        << "\"executionStage\":" << quote(execution_stage) << ","
        << "\"errorCode\":" << quote(error_code) << ","
        << "\"message\":" << quote(message) << "}";
    return out.str();
}

std::string qnn_execution_contract_mismatch_json(
        const std::string& field,
        size_t expected,
        size_t actual) {
    std::ostringstream message;
    message << "Native " << field << " mismatch: expected " << expected
            << ", actual " << actual << ".";
    return qnn_semantic_failure_json(
        "execution_contract_mismatch",
        "EXECUTION_CONTRACT_MISMATCH",
        message.str());
}


}  // namespace qnn
}  // namespace mca
