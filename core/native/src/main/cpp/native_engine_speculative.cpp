// Native-engine draft-MTP session and multimodal prefill translation unit
// (T2B16 R7-C5 split).

#include <cstddef>
#include <string>
#include <vector>

#include "native_engine_internal.hpp"

#include "mca/llm/llm_prompt_formatter.hpp"
#include "mca/llm/llama_speculative_engine.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "speculative.h"
#include "mtmd.h"

#include "mca/llm/llm_runtime_config.hpp"
#endif

namespace mca::native_engine {

#if MCA_WITH_LLAMA_CPP
using mca::llm::runtime_config_equal_load_bound;
// Contract: MtpAndGpuNativeContractTest requires exact signature and terms in native_engine.cpp:
// bool model_supports_requested_mtp(const std::string &architecture, int mtp_layers)
// mtp_layers == 1
// lowercase_trimmed_ascii(architecture) == "step35"
using mca::llm::model_supports_requested_mtp;
#endif

#if MCA_WITH_LLAMA_CPP
int prefill_multimodal_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options
) {
    mca::llm::MultimodalPrefillEnvironment env;
    env.mtmd_ctx = g_mtmd_context; env.vision_ready = g_vision_ready; env.llama_ctx = g_context;
    env.n_ctx = g_n_ctx; env.n_batch = g_n_batch; env.overflow_headroom = OVERFLOW_HEADROOM;
    env.completion_stopped_code = COMPLETION_STOPPED; env.chat_templates = g_chat_templates.get();
    env.is_stop_requested = []() { return g_stop_requested.load(std::memory_order_acquire); };
    env.last_error = &g_last_error; env.current_position = &g_current_position;
    env.prompt_tokens = &g_prompt_tokens; env.prefill_computed_tokens = &g_prefill_computed_tokens;
    env.prompt_ends_inside_reasoning = &g_prompt_ends_inside_reasoning;
    env.begin_prefill_progress = [](size_t total) { begin_prefill_progress(total); };
    env.report_reused_prefill_tokens = [](size_t reused) { report_reused_prefill_tokens(reused); };
    return mca::llm::prefill_multimodal_locked(messages, chat_options, env);
}

void invalidate_speculative_contexts_locked(const std::string &reason) {
#if MCA_WITH_LLAMA_CPP
    // Reference (76348a5b) owner semantics: invalidation releases the session-scoped draft-MTP
    // engine before the target/MTP contexts are cleared.  g_speculative is the only owner, and
    // every LlamaSpeculativeEngine path that triggers this callback returns immediately, so the
    // borrowed engine is never touched after the release.
    g_speculative.reset();
#endif
    g_spec_request_active = false;
    g_spec_request_reason = reason;
    g_spec_pending_token = LLAMA_TOKEN_NULL;
    g_spec_pending_valid = false;
    g_spec_done = true;
    g_spec_prompt_tokens.clear();
    g_spec_draft_tokens.clear();
    clear_target_context_locked(reason);
    if (g_mtp_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_mtp_context), false);
    }
}

bool stop_speculative_request_if_requested_locked() {
    if (!g_stop_requested.load(std::memory_order_acquire)) return false;
    invalidate_speculative_contexts_locked("stopped");
    return true;
}

namespace {
bool trim_speculative_contexts_locked(llama_pos position, const char *label) {
    const std::string target_label = std::string("target ") + label;
    const std::string mtp_label = std::string("MTP ") + label;
    const bool target_ok = trim_context_locked(g_context, position, target_label.c_str());
    const bool mtp_ok = trim_context_locked(g_mtp_context, position, mtp_label.c_str());
    if (!target_ok || !mtp_ok) {
        invalidate_speculative_contexts_locked("speculative_context_recovery_failed");
        return false;
    }
    return true;
}
} // namespace

mca::llm::SpeculativeEnvironment make_speculative_environment_locked() {
    mca::llm::SpeculativeEnvironment env;
    env.model = g_model; env.context = g_context; env.mtp_context = g_mtp_context;
    env.batch = &g_batch; env.sampler = g_sampler;
    // g_speculative stays the single session-scoped owner for the whole loaded-model lifetime.
    // The environment only borrows it: moving the unique_ptr in here would destroy the draft-MTP
    // engine when this temporary environment goes out of scope, which happens at the end of the
    // first generated chunk, leaving every later chunk without a speculative context (P1-01).
    env.speculative_owner = &g_speculative;
    env.speculative = g_speculative.get();
    env.spec_requested = g_spec_requested; env.spec_request_active = &g_spec_request_active;
    env.spec_request_reason = &g_spec_request_reason; env.spec_done = &g_spec_done;
    env.spec_pending_token = &g_spec_pending_token; env.spec_pending_valid = &g_spec_pending_valid;
    env.spec_prompt_tokens = &g_spec_prompt_tokens; env.spec_draft_tokens = &g_spec_draft_tokens;
    env.spec_drafted_tokens = &g_spec_drafted_tokens; env.spec_accepted_tokens = &g_spec_accepted_tokens;
    env.spec_steps = &g_spec_steps; env.current_position = &g_current_position;
    env.context_tokens = &g_context_tokens; env.n_ctx = g_n_ctx; env.n_batch = g_n_batch;
    env.n_predict = g_n_predict; env.spec_draft_n_max = g_effective_config.spec_draft_n_max;
    env.overflow_headroom = OVERFLOW_HEADROOM; env.completion_tokens = g_completion_tokens;
    env.last_error = &g_last_error;
    env.is_stopped = []() { return stop_speculative_request_if_requested_locked(); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_mtp_context, pos, l); };
    env.trim_speculative_contexts = [](llama_pos pos, const char *l) { return trim_speculative_contexts_locked(pos, l); };
    env.invalidate_speculative_contexts = [](const std::string &r) { invalidate_speculative_contexts_locked(r); };
    env.record_gpu_execution = []() { record_gpu_decode_execution_locked(); };
    return env;
}

bool prepare_speculative_request_locked() {
    // Ownership publication happens inside prepare_speculative_request() through
    // env.speculative_owner, so the session owner is never moved through this temporary
    // environment and no return path can drop it.
    auto env = make_speculative_environment_locked();
    return mca::llm::LlamaSpeculativeEngine::prepare_speculative_request(env);
}
#else
void invalidate_speculative_contexts_locked(const std::string &reason) {
    g_spec_request_active = false;
    g_spec_request_reason = reason;
    g_spec_done = true;
}

bool stop_speculative_request_if_requested_locked() { return false; }

bool prepare_speculative_request_locked() { return false; }
#endif
} // namespace mca::native_engine
