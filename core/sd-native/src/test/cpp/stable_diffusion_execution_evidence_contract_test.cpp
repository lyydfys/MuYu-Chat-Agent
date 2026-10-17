#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

namespace {

std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing execution-evidence contract needle: %s\n", needle.c_str());
    }
    assert(source.find(needle) != std::string::npos);
}

void require_absent(const std::string& source, const std::string& needle) {
    if (source.find(needle) != std::string::npos) {
        std::fprintf(stderr, "forbidden execution-evidence contract needle: %s\n", needle.c_str());
    }
    assert(source.find(needle) == std::string::npos);
}

void require_before(const std::string& source,
                    const std::string& first,
                    const std::string& second) {
    const size_t first_position = source.find(first);
    const size_t second_position = source.find(second);
    if (first_position == std::string::npos || second_position == std::string::npos ||
        first_position >= second_position) {
        std::fprintf(stderr,
                     "execution-evidence ordering mismatch: first=%s second=%s\n",
                     first.c_str(),
                     second.c_str());
    }
    assert(first_position != std::string::npos);
    assert(second_position != std::string::npos);
    assert(first_position < second_position);
}

}  // namespace

std::string directory_of(const std::string& path) {
    const auto last_slash = path.find_last_of("/\\");
    return last_slash == std::string::npos ? std::string() : path.substr(0, last_slash);
}

// The asserted text was extracted out of the bridge into mca/sd modules; the read set is composed so
// the checks observe the real implementations.  A missing module fails the test loudly instead of
// silently shrinking the read set (which is how a wrongly-wired test would appear to pass).
std::string read_required_text(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

int main(int argc, char** argv) {
    assert(argc == 6);
    const std::string public_header = read_text(argv[1]);
    const std::string conditioner = read_text(argv[2]);
    const std::string engine = read_text(argv[3]);
    const std::string dir = directory_of(argv[4]);
    const std::string bridge = read_text(argv[4]);
    // requires the real implementations the extracted code now lives in (T2B17/R7-C6: the
    // generate execute phase owns the context-local completion-evidence needle that moved out
    // of the bridge).
    const std::string contract = bridge
            + read_required_text(dir + "/sd_generate_execute.cpp")
            + read_required_text(dir + "/mca/sd/sd_evidence_verifier.cpp")
            + read_required_text(dir + "/mca/sd/sd_response_builder.cpp")
            + read_required_text(dir + "/mca/sd/sd_controlnet_adapter.cpp")
            + read_required_text(dir + "/mca/sd/sd_preview_encoder.cpp")
            + read_required_text(dir + "/mca/sd/sd_textual_inversion.cpp")
            + read_required_text(dir + "/mca/sd/sd_runtime_diagnostics.cpp")
            + read_required_text(dir + "/mca/sd/sd_component_resolver.cpp")
            + read_required_text(dir + "/mca/sd/sd_contract_parser.cpp")
            + read_required_text(dir + "/mca/sd/sd_effective_config.cpp");
    const std::string vae = read_text(argv[5]);

    // Evidence is owned by the native context and copied only after generation.
    require_contains(public_header, "sd_image_execution_evidence_t");
    require_contains(public_header, "SD_IMAGE_EXECUTION_EVIDENCE_VERSION = 5");
    require_contains(public_header, "sizeof(sd_image_execution_evidence_t) == 480u");
    require_contains(public_header, "control_net_compute_attempt_count) == 160u");
    require_contains(public_header, "auxiliary_control_net_residual_consumption_count) == 232u");
    require_contains(public_header, "vae_encode_tiling_invocation_count) == 240u");
    require_contains(public_header, "vae_decode_tile_overlap_y) == 348u");
    require_contains(public_header, "textual_inversion_requested_count) == 352u");
    require_contains(public_header, "textual_inversion_requested_mask) == 400u");
    require_contains(public_header, "textual_inversion_failure_code) == 472u");
    require_contains(public_header, "positive_conditioning_token_count");
    require_contains(public_header, "negative_conditioning_token_count");
    require_contains(public_header, "diffusion_model_compute_count");
    require_contains(public_header, "effective_flow_shift");
    require_contains(public_header, "effective_distilled_guidance");
    require_contains(public_header, "control_net_compute_attempt_count");
    require_contains(public_header, "control_net_compute_success_count");
    require_contains(public_header, "control_net_residual_consumption_count");
    require_contains(public_header, "vae_decode_tiling_invocation_count");
    require_contains(public_header, "vae_decode_planned_tile_count");
    require_contains(public_header, "vae_decode_tile_compute_success_count");
    require_contains(public_header, "sd_ctx_uses_dynamic_flow_shift");
    require_contains(public_header, "sd_ctx_uses_distilled_guidance");
    require_contains(public_header, "sd_get_last_image_execution_evidence");
    require_contains(public_header, "sd_copy_last_image_conditioning_artifact");
    require_contains(public_header, "sd_set_preview_callback");
    require_contains(public_header, "textual_inversion_tokenizer_match_mask");
    require_contains(public_header, "textual_inversion_consumed_mask");
    require_contains(public_header, "textual_inversion_clip_g_required_mask");
    require_contains(engine, "ImageExecutionEvidenceScope image_execution_scope");
    require_contains(engine, "mark_generation_completed(");
    require_contains(engine, "get_effective_flow_shift(&effective_flow_shift)");
    require_contains(engine, "request.guidance.distilled_guidance");

    // Token IDs and binary32 weights are captured only on paths that actually
    // produced conditioning, including distinct encoder input sequences.
    require_contains(conditioner, "ConditioningExecutionSequenceEvidence");
    require_contains(conditioner, "counts_toward_token_totals");
    require_contains(conditioner, "record_conditioning_execution_sequence");
    require_before(conditioner,
                   "apply_token_weights(std::move(chunk_hidden_states), chunk_weights)",
                   "record_conditioning_execution_sequence(execution_evidence, tokens, weights");
    require_contains(conditioner, "secondary_tokens");
    require_contains(conditioner, "token_embed_custom_l");
    require_contains(conditioner, "token_embed_custom_g");
    require_contains(conditioner, "num_custom_embeddings_2");
    require_contains(conditioner, "clip_g_tokens_from_clip_l");
    require_contains(conditioner, "const bool tensors_loaded = model_loader.load_tensors(on_load, 1)");
    require_contains(conditioner, "TextualInversionExecutionFailureCode::CLIP_PAIR_MISMATCH");
    require_contains(conditioner, "return consume_custom_token(str, bpe_tokens, execution_evidence)");
    require_contains(engine, "conditioning_artifact");
    require_contains(engine, "summary.textual_inversion_consumed_mask |=");
    require_contains(engine, "textual inversion did not reach every required conditioning graph");

    // Cache reuse is not a physical model invocation. The physical counter is
    // incremented only after a non-empty compute result is returned.
    require_before(engine,
                   "step_cache.before_condition(&condition, noised_input, &cached_output)",
                   "work_diffusion_model->compute(n_threads, diffusion_params)");
    require_before(engine,
                   "work_diffusion_model->compute(n_threads, diffusion_params)",
                   "record_diffusion_compute(execution_branch)");
    require_contains(engine, "DiffusionExecutionBranch::POSITIVE");
    require_contains(engine, "DiffusionExecutionBranch::NEGATIVE");
    require_contains(engine, "record_completed_sampling_step()");
    require_contains(engine, "record_control_net_compute_attempt(execution_branch)");
    require_contains(engine, "record_control_net_compute_success(execution_branch)");
    require_contains(engine, "record_control_net_residual_consumption(");
    require_contains(engine, "if (!compute_sample_controls(");
    require_contains(engine, "VaeImageExecutionEvidenceScope vae_execution_scope(");
    require_contains(engine, "set_image_execution_evidence(evidence)");
    require_contains(engine, "const bool record_preview_vae_execution = false");
    require_contains(engine, "record_preview_vae_execution);");
    require_contains(vae, "bool record_execution_evidence = true");
    require_contains(vae, "if (record_execution_evidence)");
    require_contains(vae, "record_tile_compute_attempt(decode_graph)");
    require_contains(vae, "record_tile_compute_success(decode_graph)");
    require_contains(vae, "if (!output.empty() && record_execution_evidence)");
    require_contains(vae, "record_execution_evidence);");

    // The bridge consumes native evidence and hashes the exact artifact. It
    // must never substitute request capacity or UI progress for actual work.
    require_contains(contract, "sd_get_last_image_execution_evidence(ctx, &execution_evidence)");
    require_contains(contract, "sha256_hex(conditioning_artifact)");
    require_contains(contract, "image_prompt_execution_sha256(");
    require_contains(contract, "7b2c4dab5874fc03fd088691ce5bea0ff34ded267b495fc5249853dd2110bed7");
    require_contains(contract, "77debda388ee058e0ce51f89e799e2ca7828a06142fc77b2b19cff3844b93579");
    require_contains(contract, "const uint64_t total_conditioning_token_count =");
    require_contains(contract, "execution_evidence.positive_conditioning_token_count +");
    require_contains(contract, "resolvedTokenCount");
    require_contains(contract, "positiveConditioningTokenCount");
    require_contains(contract, "negativeConditioningTokenCount");
    require_contains(contract, "nativePromptExecutionSha256");
    require_contains(contract, "nativePromptBindingStage\"] = \"conditioning_consumed\"");
    require_contains(contract, "validate_textual_inversion_execution_evidence(");
    require_contains(contract, "TEXTUAL_INVERSION_NATIVE_CONDITIONING_INCOMPLETE");
    require_contains(contract, "textual_inversion_selection_fingerprint(");
    require_contains(contract, "\"bindingStage\", validated_textual_inversions.empty()");
    require_before(contract,
                   "positive conditioning did not publish actual tokenizer evidence",
                   "summary.actual_native_prompt_execution_sha256 =");
    require_contains(contract, "actual physical diffusion model computes");
    require_contains(contract, "actual negative diffusion execution");
    require_contains(contract, "execution_evidence.control_net_compute_attempt_count");
    require_contains(contract, "controlNetResidualConsumptionCount");
    require_contains(contract, "evidence.residual_consumption_count");
    require_contains(contract, "evidence.positive_compute_attempt_count != actual_positive_execution_count");
    require_contains(contract, "evidence.negative_compute_attempt_count != actual_negative_execution_count");
    require_contains(contract, "evidence.positive_residual_consumption_count != actual_positive_execution_count");
    require_contains(contract, "evidence.negative_residual_consumption_count != actual_negative_execution_count");
    require_contains(contract, "ControlNet did not successfully compute and feed residuals");
    require_contains(contract, "controlNetEvidence");
    require_contains(contract, "execution_evidence.vae_decode_tiling_invocation_count");
    require_contains(contract, "actual_vae_decode_planned_tile_count");
    require_contains(contract, "requested VAE tiling did not execute exactly once for every final image decode");
    require_contains(contract, "actual_vae_decode_tiling_invocation_count != contract.batch_count");
    require_contains(contract, "input_image_wired && summary.actual_vae_encode_tiling_invocation_count <= 0");
    require_contains(contract, "actual_flow_shift_applied");
    require_contains(contract, "actual_distilled_guidance_applied");
    require_contains(contract, "requestedFlowShift");
    require_contains(contract, "requestedDistilledGuidance");
    require_contains(contract, "void sd_preview_callback(");
    require_contains(contract, "stbi_write_png(");
    require_contains(contract, "std::rename(partial.c_str(), target.c_str())");
    require_contains(contract, "sd_set_preview_callback(");
    require_contains(contract, "previewPublicationCount");
    require_contains(contract, "safe_sd_runtime_message(");
    require_contains(contract, "running native text conditioning");
    require_absent(bridge, "set_progress_stage(\"conditioning\", message)");
    require_absent(bridge, "\"%s\", text == nullptr ? \"\" : text");
    require_contains(contract, "contract.preview_mode != \"projection\"");
    require_absent(bridge, "preview publication is not active in the worker protocol");
    require_absent(bridge, "out[\"flowShift\"] = contract.flow_shift");
    require_absent(bridge, "const int actual_token_count = contract.token_count");
    require_absent(bridge, "actual_token_count != contract.token_count");
    require_absent(bridge, "observed_denoiser_callbacks");
    require_absent(bridge, "context_loaded_trigger_observed");
    require_absent(bridge, "prompt_contains_textual_trigger");
    require_absent(bridge, "native_effective[\"controlStrengthApplied\"] = control_image_wired");
    require_absent(bridge, "{\"tileSize\", gen.vae_tiling_params.tile_size_x}");
    require_absent(bridge, "9b353b1ac542678089ce3d12ee96ddd6ba3b0252ec0675cdf0540e6aa6b1860e");
    return 0;
}
