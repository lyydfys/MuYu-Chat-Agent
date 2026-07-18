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

int main(int argc, char** argv) {
    assert(argc == 6);
    const std::string public_header = read_text(argv[1]);
    const std::string conditioner = read_text(argv[2]);
    const std::string engine = read_text(argv[3]);
    const std::string bridge = read_text(argv[4]);
    const std::string vae = read_text(argv[5]);

    // Evidence is owned by the native context and copied only after generation.
    require_contains(public_header, "sd_image_execution_evidence_t");
    require_contains(public_header, "SD_IMAGE_EXECUTION_EVIDENCE_VERSION = 4");
    require_contains(public_header, "sizeof(sd_image_execution_evidence_t) == 352u");
    require_contains(public_header, "control_net_compute_attempt_count) == 160u");
    require_contains(public_header, "auxiliary_control_net_residual_consumption_count) == 232u");
    require_contains(public_header, "vae_encode_tiling_invocation_count) == 240u");
    require_contains(public_header, "vae_decode_tile_overlap_y) == 348u");
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
    require_contains(engine, "conditioning_artifact");

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
    require_contains(bridge, "sd_get_last_image_execution_evidence(ctx, &execution_evidence)");
    require_contains(bridge, "sha256_hex(conditioning_artifact)");
    require_contains(bridge, "const uint64_t total_conditioning_token_count =");
    require_contains(bridge, "execution_evidence.positive_conditioning_token_count +");
    require_contains(bridge, "resolvedTokenCount");
    require_contains(bridge, "positiveConditioningTokenCount");
    require_contains(bridge, "negativeConditioningTokenCount");
    require_contains(bridge, "actual physical diffusion model computes");
    require_contains(bridge, "actual negative diffusion execution");
    require_contains(bridge, "execution_evidence.control_net_compute_attempt_count");
    require_contains(bridge, "actual_control_net_residual_consumption_count");
    require_contains(bridge, "actual_positive_control_net_compute_attempt_count != actual_positive_execution_count");
    require_contains(bridge, "actual_negative_control_net_compute_attempt_count != actual_negative_execution_count");
    require_contains(bridge, "actual_positive_control_net_residual_consumption_count != actual_positive_execution_count");
    require_contains(bridge, "actual_negative_control_net_residual_consumption_count != actual_negative_execution_count");
    require_contains(bridge, "ControlNet did not successfully compute and feed residuals");
    require_contains(bridge, "controlNetEvidence");
    require_contains(bridge, "execution_evidence.vae_decode_tiling_invocation_count");
    require_contains(bridge, "actual_vae_decode_planned_tile_count");
    require_contains(bridge, "requested VAE tiling did not execute exactly once for every final image decode");
    require_contains(bridge, "actual_vae_decode_tiling_invocation_count != contract.batch_count");
    require_contains(bridge, "input_image_wired && actual_vae_encode_tiling_invocation_count <= 0");
    require_contains(bridge, "actual_flow_shift_applied");
    require_contains(bridge, "actual_distilled_guidance_applied");
    require_contains(bridge, "requestedFlowShift");
    require_contains(bridge, "requestedDistilledGuidance");
    require_contains(bridge, "void sd_preview_callback(");
    require_contains(bridge, "stbi_write_png(");
    require_contains(bridge, "std::rename(partial.c_str(), target.c_str())");
    require_contains(bridge, "sd_set_preview_callback(");
    require_contains(bridge, "previewPublicationCount");
    require_contains(bridge, "safe_sd_runtime_message(");
    require_contains(bridge, "running native text conditioning");
    require_absent(bridge, "set_progress_stage(\"conditioning\", message)");
    require_absent(bridge, "\"%s\", text == nullptr ? \"\" : text");
    require_contains(bridge, "contract.preview_mode != \"projection\"");
    require_absent(bridge, "preview publication is not active in the worker protocol");
    require_absent(bridge, "out[\"flowShift\"] = contract.flow_shift");
    require_absent(bridge, "const int actual_token_count = contract.token_count");
    require_absent(bridge, "actual_token_count != contract.token_count");
    require_absent(bridge, "observed_denoiser_callbacks");
    require_absent(bridge, "native_effective[\"controlStrengthApplied\"] = control_image_wired");
    require_absent(bridge, "{\"tileSize\", gen.vae_tiling_params.tile_size_x}");
    require_absent(bridge, "9b353b1ac542678089ce3d12ee96ddd6ba3b0252ec0675cdf0540e6aa6b1860e");
    return 0;
}
