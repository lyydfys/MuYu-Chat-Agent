#include "mca/llm/llama_speculative_engine.hpp"

#include <algorithm>
#include <string>

#include "mca/request/llama_request_parser.hpp"
#include <exception>
#include <string>
#include "sampling.h"
#include "llama_batch_compat.hpp"

namespace {

// llama.cpp v0.9.0 changed common_speculative_process() to take a common_batch instead of the raw
// llama_batch that MCA fills for llama_decode().  The speculative implementation only reads the
// token entries of that batch, so mirror them and leave the embedding view empty: the draft-mtp
// verification batches built here are token-only.
common_batch to_common_batch(const llama_batch & batch) {
    common_batch mirrored;
    mirrored.tokens.reserve((size_t) batch.n_tokens);
    for (int32_t i = 0; i < batch.n_tokens; ++i) {
        common_batch::token token{};
        token.id = batch.token != nullptr ? batch.token[i] : LLAMA_TOKEN_NULL;
        token.pos[0] = batch.pos != nullptr ? batch.pos[i] : 0;
        token.seq_id = (batch.n_seq_id != nullptr && batch.seq_id != nullptr && batch.n_seq_id[i] > 0)
                ? batch.seq_id[i][0]
                : 0;
        token.output = batch.logits != nullptr && batch.logits[i] != 0;
        mirrored.tokens.push_back(token);
    }
    return mirrored;
}

} // namespace

namespace mca {
namespace llm {

bool LlamaSpeculativeEngine::prepare_speculative_request(SpeculativeEnvironment &env) {
    if (env.spec_pending_token) *env.spec_pending_token = LLAMA_TOKEN_NULL;
    if (env.spec_pending_valid) *env.spec_pending_valid = false;
    if (env.spec_done) *env.spec_done = false;
    if (env.spec_prompt_tokens) env.spec_prompt_tokens->clear();
    if (env.spec_draft_tokens) env.spec_draft_tokens->clear();

    // A new request starts from a fresh engine: release the previous session engine before its
    // replacement is created, and only ever borrow from here on.  Ownership is never moved through
    // this environment, so no return/throw path below can leave the session without its owner.
    if (env.speculative_owner != nullptr) env.speculative_owner->reset();
    env.speculative = nullptr;

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

    // Mirrors qnn_native_bridge.cpp's prepare path (reference native_engine.cpp:3114-3125), which is
    // the working formulation for the vendored llama.cpp: the params are default-constructed and the
    // draft settings are written through `params.draft`, and common_speculative_init takes the
    // sequence count as a second argument.  The previous formulation called
    // common_speculative_default_params()/common_speculative_draft_default_params() and wrote
    // `draft_params.n_draft`/`.ctx`, none of which exist in this llama.cpp revision.
    common_params_speculative spec_params{};
    spec_params.types = {COMMON_SPECULATIVE_TYPE_DRAFT_MTP};
    spec_params.draft.n_max = env.spec_draft_n_max;
    spec_params.draft.n_min = 0;
    spec_params.draft.p_min = 0.0f;
    spec_params.draft.backend_sampling = false;
    spec_params.draft.ctx_tgt = env.context;
    spec_params.draft.ctx_dft = env.mtp_context;
    common_speculative_ptr created;
    try {
        created.reset(common_speculative_init(spec_params, 1));
    } catch (const std::exception &e) {
        if (env.last_error) {
            *env.last_error = std::string("common_speculative_init(draft-mtp) failed: ") + e.what();
        }
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) *env.spec_request_reason = "specification_init_failed";
        return false;
    } catch (...) {
        if (env.last_error) {
            *env.last_error = "common_speculative_init(draft-mtp) failed with an unknown native error.";
        }
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) *env.spec_request_reason = "specification_init_failed";
        return false;
    }
    if (created == nullptr) {
        if (env.last_error) {
            *env.last_error = "common_speculative_init returned null for draft-mtp.";
        }
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) *env.spec_request_reason = "speculative_init_failed";
        return false;
    }
    if (env.speculative_owner == nullptr) {
        // Without a session slot the engine cannot outlive this call, so fail loudly instead of
        // handing out a borrow of an engine that is about to be destroyed.
        if (env.last_error) {
            *env.last_error = "draft-mtp initialization requires a session owner slot for the speculative engine.";
        }
        if (env.spec_request_active) *env.spec_request_active = false;
        if (env.spec_request_reason) *env.spec_request_reason = "speculative_owner_missing";
        return false;
    }
    // Publish the engine to the session owner first, then borrow the live engine for this request.
    *env.speculative_owner = std::move(created);
    env.speculative = env.speculative_owner->get();
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
        auto &draft_params = common_speculative_get_draft_params(env.speculative, 0);
        draft_params = {
                /* .drafting = */ true,
                /* .n_max    = */ n_max,
                /* .n_past   = */ base,
                /* .id_last  = */ env.spec_pending_token ? *env.spec_pending_token : LLAMA_TOKEN_NULL,
                /* .prompt   = */ env.spec_prompt_tokens,
                /* .result   = */ env.spec_draft_tokens,
        };
        common_speculative_draft(env.speculative);
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

    mca::llama::batchClear(*env.batch);
    const llama_token pending_tok = env.spec_pending_token ? *env.spec_pending_token : LLAMA_TOKEN_NULL;
    mca::llama::batchAdd(*env.batch, pending_tok, base, {0}, true);
    if (env.spec_draft_tokens) {
        for (size_t i = 0; i < env.spec_draft_tokens->size(); ++i) {
            mca::llama::batchAdd(*env.batch, (*env.spec_draft_tokens)[i], base + 1 + (llama_pos) i, {0}, true);
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

    if (!common_speculative_process(env.speculative, to_common_batch(*env.batch))) {
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
    common_speculative_accept(env.speculative, 0, accepted);
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
