#pragma once

// This file is included from qnn_native_bridge.cpp after the typed QNN graph
// helpers are defined.  Each entry point intentionally leaks its one runtime
// graph: the Android phase worker is disposable and exits immediately after
// publishing its artifact, avoiding the RouterFastRPC unload hang observed
// when large SDXL UNet and VAE contexts were switched inside one process.

#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"
#include "mca/qnn/qnn_sdxl_encoder_phase.hpp"
#include "mca/qnn/qnn_sdxl_decoder_phase.hpp"
#include "mca/qnn/qnn_sdxl_unet_phase.hpp"

using ::mca::qnn::SdxlInpaintPixelArtifacts;
using ::mca::qnn::write_sdxl_latent_atomic;
using ::mca::qnn::fsync_sdxl_artifact;
using ::mca::qnn::valid_sdxl_lower_sha256;
using ::mca::qnn::is_safe_ascii_diffusion_prompt_pair;
using ::mca::qnn::validate_sdxl_phase_execution_contract;
using ::mca::qnn::load_sdxl_inpaint_pixel_artifacts;
using ::mca::qnn::validate_sdxl_no_preview_transport;
using ::mca::qnn::sdxl_parent_directory;
using ::mca::qnn::sdxl_disabled_preview_native_json_fields;
using ::mca::qnn::run_sdxl_ultrafix_tiled_unet_branch;

std::string qnn_sdxl_encoder_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& input_tensor_path,
        const std::string& latent_path,
        const std::string& expected_encoder_context_sha256_raw) {
    // contract: UNSUPPORTED_PREVIEW_TRANSPORT, encoderContextSha256, select_qnn_runtime_profile_for_context(..., expected_profile))
    return execute_sdxl_encoder_phase<QnnExecutableGraph>(
        bundle_root,
        runtime_profile_json,
        params_json,
        input_tensor_path,
        latent_path,
        expected_encoder_context_sha256_raw);
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
    return run_sdxl_ultrafix_tiled_unet_branch(
        unet,
        sample_index,
        timestep_index,
        hidden_index,
        time_ids_index,
        pooled_index,
        plan,
        full_sample,
        timestep,
        hidden,
        hidden_count,
        time_ids,
        time_ids_count,
        pooled,
        pooled_count,
        full_output,
        execute_ms_total,
        graph_execution_count,
        error);
}

std::string qnn_sdxl_unet_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& prompt_embeds_path,
        const std::string& pooled_embeds_path,
        const std::string& time_ids_path,
        const std::string& latent_path,
        const std::string& output_path,
        const std::string& expected_unet_context_sha256_raw) {
    // contract: UNSUPPORTED_PREVIEW_TRANSPORT, unetContextSha256, select_qnn_runtime_profile_for_context(..., expected_profile))
    /*
    native_evidence.conditioning_artifact_sha256 =
        conditioning_evidence.conditioning_artifact_sha256;
    native_evidence.conditioning_graph_sha256
    native_evidence.conditioning_artifact_consumed
    \"runtimeSessionMode\":\"isolated_unet_phase\"
    bind_qnn_consumed_prompt_evidence(
    \"nativePromptExecutionSha256\":
    \"nativePromptBindingStage\":
    quote(native_evidence.native_prompt_execution_sha256)
    validate_sdxl_no_preview_transport(params_json, &error)
    inpaint_contract.topology != mca::qnn::inpaint::MaskTopology::LatentBlend4
    scheduler.step(
    preserve_unmasked_latent(
    inpaint_preserve_step_count
    quality_noise_evidence_consistent
    quality_structure_evidence_consistent
    quality_coverage_complete
    quality_step_count
    ultra_fix_noise_injection_step_count <= quality_step_count
    */
    return execute_sdxl_unet_phase<QnnExecutableGraph>(
        bundle_root,
        runtime_profile_json,
        params_json,
        prompt_embeds_path,
        pooled_embeds_path,
        time_ids_path,
        latent_path,
        output_path,
        expected_unet_context_sha256_raw);
}

std::string qnn_sdxl_unet_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& initial_latent_path,
        const std::string& latent_path) {
    return execute_sdxl_unet_phase<QnnExecutableGraph>(
        bundle_root,
        runtime_profile_json,
        params_json,
        embeddings_path,
        initial_latent_path,
        latent_path);
}

std::string qnn_sdxl_vae_phase_json(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& latent_path,
        const std::string& output_path) {
    // contract: UNSUPPORTED_PREVIEW_TRANSPORT, select_qnn_runtime_profile_for_context(..., expected_profile))
    return execute_sdxl_vae_phase<QnnExecutableGraph>(
        bundle_root,
        runtime_profile_json,
        params_json,
        latent_path,
        output_path);
}
