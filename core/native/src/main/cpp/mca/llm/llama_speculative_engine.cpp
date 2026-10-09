#include "mca/llm/llama_speculative_engine.hpp"

#include <algorithm>
#include <string>

#include "mca/request/llama_request_parser.hpp"

namespace mca {
namespace llm {

bool LlamaSpeculativeEngine::prepare_speculative_request(SpeculativeEnvironment &env) {
    if (env.spec_pending_token) *env.spec_pending_token = LLAMA_TOKEN_NULL;
    if (env.spec_pending_valid) *env.spec_pending_valid = false;
    if (env.spec_done) *env.spec_done = false;
    if (env.spec_prompt_tokens) env.spec_prompt_tokens->clear();
    if (env.spec_draft_tokens) env.spec_draft_tokens->clear();

    if (!env.spec_requested || env.mtp_context == nullptr) {
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) {
            *env.spec_request_reason = env.spec_requested ? "mtp_context_missing" : "disabled";
        }
        return true;
    }

#if MCA_WITH_LLAMA_CPP
    const auto seq_rm_type = common_context_can_seq_rm(env.context);
    const uint32_t rollback_window = (uint32_t) env.spec_draft_n_max + 1U;
    if (seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_NO ||
        seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL ||
        (seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_RS &&
         llama_n_rs_seq(env.context) < rollback_window)) {
        if (env.last_error) {
            *env.last_error = "draft-mtp requires bounded partial target-context rollback; this model/context does not provide it.";
        }
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) *env.spec_request_reason = "insufficient_target_context_rollback_window";
        return false;
    }

    common_params_speculative spec_params = common_speculative_default_params();
    common_speculative_draft_params draft_params = common_speculative_draft_default_params();
    draft_params.n_draft = env.spec_draft_n_max;
    draft_params.ctx = env.mtp_context;
    spec_params.drafts.push_back(draft_params);
    spec_params.n_parallel = 1;
    env.speculative = common_speculative_init(spec_params);
    if (env.speculative == nullptr) {
        if (env.last_error) {
            *env.last_error = "common_speculative_init returned null for draft-mtp.";
        }
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) *env.spec_request_reason = "speculative_init_failed";
        return false;
    }
    if (env.spec_request_active) *env.spec_request_active = true;
    if (env.spec_request_reason) *env.spec_request_reason = "active";
    return true;
#else
    return true;
#endif
}

SpecStepResult LlamaSpeculativeEngine::speculative_step(SpeculativeEnvironment &env) {
    SpecStepResult result;
    if (!env.spec_request_active || !*env.spec_request_active ||
        env.speculative == nullptr || env.mtp_context == nullptr) {
        if (env.last_error) {
            *env.last_error = "draft-mtp step requested without an active speculative context.";
        }
        result.ok = false;
        return result;
    }
    if (env.spec_done && *env.spec_done) {
        result.done = true;
        return result;
    }
    if (env.is_stopped && env.is_stopped()) {
        result.done = true;
        return result;
    }

#if MCA_WITH_LLAMA_CPP
    const auto *vocab = llama_model_get_vocab(env.model);

    if (env.spec_pending_valid && !*env.spec_pending_valid) {
        const llama_token token = common_sampler_sample(env.sampler, env.context, -1);
        if (env.is_stopped && env.is_stopped()) {
            result.done = true;
            return result;
        }
        common_sampler_accept(env.sampler, token, true);
        if (env.is_stopped && env.is_stopped()) {
            result.done = true;
            return result;
        }
        if (llama_vocab_is_eog(vocab, token)) {
            if (env.spec_done) *env.spec_done = true;
            result.done = true;
            return result;
        }
        if (env.spec_pending_token) *env.spec_pending_token = token;
        if (env.spec_pending_valid) *env.spec_pending_valid = true;
        result.output.push_back(token);
        return result;
    }

    if (env.current_position && *env.current_position >= env.n_ctx - env.overflow_headroom) {
        if (env.last_error) {
            *env.last_error = "draft-mtp reached the context boundary; context shifting is disabled for synchronized target/MTP contexts.";
        }
        result.ok = false;
        return result;
    }

    const llama_pos base = env.current_position ? *env.current_position : 0;
    const int remaining = env.n_predict - (int) env.completion_tokens;
    const int context_room = env.n_ctx - env.overflow_headroom - (int) base - 1;
    const int n_max = std::max(0, std::min({
            env.spec_draft_n_max,
            std::max(0, remaining - 1),
            std::max(0, context_room)}));

    if (env.spec_draft_tokens) env.spec_draft_tokens->clear();
    if (n_max > 0) {
        auto &draft_params = common_speculative_get_draft_params(env.speculative.get(), 0);
        draft_params = {
                /* .drafting = */ true,
                /* .n_max    = */ n_max,
                /* .n_past   = */ base,
                /* .id_last  = */ env.spec_pending_token ? *env.spec_pending_token : LLAMA_TOKEN_NULL,
                /* .prompt   = */ env.spec_prompt_tokens,
                /* .result   = */ env.spec_draft_tokens,
        };
        common_speculative_draft(env.speculative.get());
        if (env.trim_context && !env.trim_context(base, "MTP draft pre-advance")) {
            if (env.invalidate_speculative_contexts) {
                env.invalidate_speculative_contexts("mtp_draft_rollback_failed");
            }
            result.ok = false;
            return result;
        }
        if (env.is_stopped && env.is_stopped()) {
            result.done = true;
            return result;
        }
    }

    const size_t draft_size = env.spec_draft_tokens ? env.spec_draft_tokens->size() : 0;
    if ((int) draft_size + 1 > env.n_batch) {
        if (env.last_error) {
            *env.last_error = "draft-mtp verification batch exceeds effective n_batch.";
        }
        result.ok = false;
        return result;
    }

    common_batch_clear(*env.batch);
    const llama_token pending_tok = env.spec_pending_token ? *env.spec_pending_token : LLAMA_TOKEN_NULL;
    common_batch_add(*env.batch, pending_tok, base, {0}, true);
    if (env.spec_draft_tokens) {
        for (size_t i = 0; i < env.spec_draft_tokens->size(); ++i) {
            common_batch_add(*env.batch, (*env.spec_draft_tokens)[i], base + 1 + (llama_pos) i, {0}, true);
        }
    }
    const int decode_rc = llama_decode(env.context, *env.batch);
    if (decode_rc != 0) {
        if (env.last_error) {
            *env.last_error = "llama_decode failed during draft-mtp verification: " + std::to_string(decode_rc);
        }
        if (env.trim_speculative_contexts) env.trim_speculative_contexts(base, "verification failure");
        result.ok = false;
        return result;
    }
    if (env.record_gpu_execution) env.record_gpu_execution();

    if (!common_speculative_process(env.speculative.get(), *env.batch)) {
        if (env.last_error) {
            *env.last_error = "common_speculative_process failed during draft-mtp verification.";
        }
        if (env.trim_speculative_contexts) env.trim_speculative_contexts(base, "process failure");
        result.ok = false;
        return result;
    }
    if (env.is_stopped && env.is_stopped()) {
        result.done = true;
        return result;
    }

    const llama_token committed_pending = pending_tok;
    if (draft_size == 0) {
        const llama_token next = common_sampler_sample(env.sampler, env.context, 0);
        if (env.is_stopped && env.is_stopped()) {
            result.done = true;
            return result;
        }
        common_sampler_accept(env.sampler, next, true);
        if (env.is_stopped && env.is_stopped()) {
            result.done = true;
            return result;
        }
        if (env.context_tokens) env.context_tokens->push_back(committed_pending);
        if (env.spec_prompt_tokens) env.spec_prompt_tokens->push_back(committed_pending);
        if (env.current_position) *env.current_position = base + 1;
        if (env.trim_speculative_contexts && !env.trim_speculative_contexts(base + 1, "single-token accept")) {
            result.ok = false;
            return result;
        }
        if (llama_vocab_is_eog(vocab, next)) {
            if (env.spec_pending_token) *env.spec_pending_token = LLAMA_TOKEN_NULL;
            if (env.spec_pending_valid) *env.spec_pending_valid = false;
            if (env.spec_done) *env.spec_done = true;
            result.done = true;
            return result;
        }
        if (env.spec_pending_token) *env.spec_pending_token = next;
        if (env.spec_pending_valid) *env.spec_pending_valid = true;
        if (env.is_stopped && env.is_stopped()) {
            result.done = true;
            return result;
        }
        result.output.push_back(next);
        if (env.spec_steps) (*env.spec_steps)++;
        return result;
    }

    llama_tokens sampled = common_sampler_sample_and_accept_n(
            env.sampler, env.context, *env.spec_draft_tokens);
    if (env.is_stopped && env.is_stopped()) {
        result.done = true;
        return result;
    }
    if (sampled.empty()) {
        if (env.last_error) {
            *env.last_error = "draft-mtp verification returned no sampled tokens.";
        }
        if (env.trim_speculative_contexts) env.trim_speculative_contexts(base, "empty verification result");
        result.ok = false;
        return result;
    }

    size_t eog_index = sampled.size();
    for (size_t i = 0; i < sampled.size(); ++i) {
        if (llama_vocab_is_eog(vocab, sampled[i])) {
            eog_index = i;
            break;
        }
    }
    const uint16_t accepted = (uint16_t) std::min(eog_index, sampled.size() - 1);
    common_speculative_accept(env.speculative.get(), 0, accepted);
    if (env.is_stopped && env.is_stopped()) {
        result.done = true;
        return result;
    }

    if (env.context_tokens) env.context_tokens->push_back(committed_pending);
    if (env.spec_prompt_tokens) env.spec_prompt_tokens->push_back(committed_pending);
    for (size_t i = 0; i < accepted; ++i) {
        if (env.context_tokens) env.context_tokens->push_back(sampled[i]);
        if (env.spec_prompt_tokens) env.spec_prompt_tokens->push_back(sampled[i]);
    }
    if (env.current_position) *env.current_position = base + 1 + accepted;
    if (env.trim_speculative_contexts && !env.trim_speculative_contexts(base + 1 + accepted, "accepted verification")) {
        result.ok = false;
        return result;
    }

    if (env.spec_drafted_tokens && env.spec_draft_tokens) {
        *env.spec_drafted_tokens += (long long) env.spec_draft_tokens->size();
    }
    if (env.spec_accepted_tokens) *env.spec_accepted_tokens += accepted;
    if (env.spec_steps) (*env.spec_steps)++;

    if (eog_index < sampled.size()) {
        result.output.assign(sampled.begin(), sampled.begin() + (long long) eog_index);
        if (env.spec_pending_token) *env.spec_pending_token = LLAMA_TOKEN_NULL;
        if (env.spec_pending_valid) *env.spec_pending_valid = false;
        if (env.spec_done) *env.spec_done = true;
        result.done = true;
    } else {
        result.output = sampled;
        if (env.spec_pending_token) *env.spec_pending_token = sampled.back();
        if (env.spec_pending_valid) *env.spec_pending_valid = true;
    }
    if (env.is_stopped && env.is_stopped()) {
        result.output.clear();
        result.done = true;
    }
    return result;
#else
    return result;
#endif
}

SpecChunkResult LlamaSpeculativeEngine::generate_chunk(
        SpeculativeEnvironment &env,
        std::string &cached_token_chars,
        long long &completion_tokens,
        int max_tokens) {
    SpecChunkResult res;
    const SpecStepResult step = speculative_step(env);
    if (!step.ok) {
        res.is_failed = true;
        res.error_message = env.last_error && !env.last_error->empty()
                ? *env.last_error
                : "draft-mtp generation failed.";
        return res;
    }
    if (env.is_stopped && env.is_stopped()) {
        res.is_stopped = true;
        return res;
    }
    if (step.output.empty()) {
        if (step.done) {
            res.is_done = true;
            res.normal_finished = true;
        }
        return res;
    }
    completion_tokens += (long long) step.output.size();
#if MCA_WITH_LLAMA_CPP
    for (const llama_token token : step.output) {
        cached_token_chars += common_token_to_piece(env.context, token);
    }
#endif
    const bool output_boundary_valid = mca::request::is_valid_utf8(cached_token_chars.c_str());
    if (output_boundary_valid) {
        res.has_output = true;
        res.output = cached_token_chars;
        cached_token_chars.clear();
    }
    if (step.done) {
        res.is_done = true;
        if (output_boundary_valid) {
            res.normal_finished = true;
        }
    } else if (max_tokens > 0 && completion_tokens >= max_tokens) {
        res.is_done = true;
        if (output_boundary_valid) {
            res.max_tokens_finished = true;
        }
    }
    return res;
}

} // namespace llm
} // namespace mca
