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
    assert(argc == 5);
    const std::string public_header = read_text(argv[1]);
    const std::string conditioner = read_text(argv[2]);
    const std::string engine = read_text(argv[3]);
    const std::string bridge = read_text(argv[4]);

    // Evidence is owned by the native context and copied only after generation.
    require_contains(public_header, "sd_image_execution_evidence_t");
    require_contains(public_header, "positive_conditioning_token_count");
    require_contains(public_header, "negative_conditioning_token_count");
    require_contains(public_header, "diffusion_model_compute_count");
    require_contains(public_header, "effective_flow_shift");
    require_contains(public_header, "effective_distilled_guidance");
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
    require_contains(bridge, "actual_flow_shift_applied");
    require_contains(bridge, "actual_distilled_guidance_applied");
    require_contains(bridge, "requestedFlowShift");
    require_contains(bridge, "requestedDistilledGuidance");
    require_contains(bridge, "void sd_preview_callback(");
    require_contains(bridge, "stbi_write_png(");
    require_contains(bridge, "std::rename(partial.c_str(), target.c_str())");
    require_contains(bridge, "sd_set_preview_callback(");
    require_contains(bridge, "previewPublicationCount");
    require_contains(bridge, "contract.preview_mode != \"projection\"");
    require_absent(bridge, "preview publication is not active in the worker protocol");
    require_absent(bridge, "out[\"flowShift\"] = contract.flow_shift");
    require_absent(bridge, "const int actual_token_count = contract.token_count");
    require_absent(bridge, "actual_token_count != contract.token_count");
    require_absent(bridge, "observed_denoiser_callbacks");
    require_absent(bridge, "9b353b1ac542678089ce3d12ee96ddd6ba3b0252ec0675cdf0540e6aa6b1860e");
    return 0;
}
