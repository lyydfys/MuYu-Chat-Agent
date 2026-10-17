#include "fixtures/llama_test_doubles.hpp"

#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace llama_test_doubles {
namespace {

Script g_script{};
bool g_throw_armed = false;
std::string g_throw_what{};

// The production engine never dereferences these handles itself; every access goes through the
// double below, so a stable sentinel address is enough.
constexpr uintptr_t kVocabSentinel = 0x1000;

} // namespace

static void maybe_throw() {
    if (!g_throw_armed) return;
    g_throw_armed = false;
    throw std::runtime_error(g_throw_what.empty() ? "scripted llama helper exception" : g_throw_what);
}

// Every double that receives a session engine calls this first: a call on an engine the session
// already released is the exact invariant the guard has to catch, and it must not be silent.
void note_engine_use(common_speculative *spec) {
    if (spec != nullptr && spec->released) ++g_script.uses_after_release;
}

Script & script() { return g_script; }

void throw_on_next_double_call(const std::string &what) {
    g_throw_armed = true;
    g_throw_what = what;
}

void Script::reset() {
    // Engines are never deleted by common_speculative_free() (so post-release uses are detectable),
    // therefore the registry owns them and releases them here, between scenarios.
    for (common_speculative *engine : registry) {
        delete engine;
    }
    registry.clear();
    total_uses_after_release += uses_after_release;
    uses_after_release = 0;
    sampled.clear();
    verified.clear();
    drafts.clear();
    pieces.clear();
    decode_rc = 0;
    seq_rm = COMMON_CONTEXT_SEQ_RM_TYPE_RS;
    n_rs_seq = 8;
    eog_tokens = {2};
    init_calls = 0;
    next_engine_serial = 1;
    free_calls = 0;
    live_engines = 0;
    peak_live_engines = 0;
    decode_calls = 0;
    sampler_sample_calls = 0;
    sample_and_accept_calls = 0;
    is_eog_calls = 0;
    token_to_piece_calls = 0;
    batch_add_calls = 0;
    max_batch_tokens = 0;
    batch_token_counts.clear();
    accepted_history.clear();
    prompt_sizes.clear();
    g_throw_armed = false;
    g_throw_what.clear();
}

} // namespace llama_test_doubles

// ---------------------------------------------------------------------------------------------
// llama.h surface used by the production engine
// ---------------------------------------------------------------------------------------------

uint32_t llama_n_rs_seq(const struct llama_context *) {
    llama_test_doubles::maybe_throw();
    return llama_test_doubles::script().n_rs_seq;
}

const struct llama_vocab * llama_model_get_vocab(const llama_model *) {
    return reinterpret_cast<const llama_vocab *>(llama_test_doubles::kVocabSentinel);
}

bool llama_vocab_is_eog(const struct llama_vocab *, llama_token token) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    ++s.is_eog_calls;
    for (const llama_token eog : s.eog_tokens) {
        if (eog == token) return true;
    }
    return false;
}

int32_t llama_decode(struct llama_context *, struct llama_batch batch) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    ++s.decode_calls;
    s.batch_token_counts.push_back(batch.n_tokens);
    if (batch.n_tokens > s.max_batch_tokens) s.max_batch_tokens = batch.n_tokens;
    return s.decode_rc;
}

// ---------------------------------------------------------------------------------------------
// common.h surface used by the production engine
// ---------------------------------------------------------------------------------------------

common_context_seq_rm_type common_context_can_seq_rm(llama_context *) {
    llama_test_doubles::maybe_throw();
    return llama_test_doubles::script().seq_rm;
}

void common_batch_clear(struct llama_batch & batch) {
    batch.n_tokens = 0;
}

void common_batch_add(
        struct llama_batch & batch,
        llama_token id,
        llama_pos pos,
        const std::vector<llama_seq_id> & seq_ids,
        bool logits) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    const int32_t i = batch.n_tokens;
    batch.token[i] = id;
    batch.pos[i] = pos;
    batch.n_seq_id[i] = (int32_t) seq_ids.size();
    for (std::size_t k = 0; k < seq_ids.size(); ++k) {
        batch.seq_id[i][k] = seq_ids[k];
    }
    batch.logits[i] = logits ? 1 : 0;
    batch.n_tokens = i + 1;
    ++s.batch_add_calls;
}

std::string common_token_to_piece(const struct llama_context *, llama_token token, bool) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    const std::size_t index = (std::size_t) s.token_to_piece_calls++;
    if (index < s.pieces.size()) return s.pieces[index];
    // Deterministic ASCII piece so the production UTF-8 boundary logic keeps flushing output.
    return std::string(1, (char) ('a' + (int) (token % 26)));
}

// ---------------------------------------------------------------------------------------------
// sampling.h surface used by the production engine
// ---------------------------------------------------------------------------------------------

llama_token common_sampler_sample(struct common_sampler *, struct llama_context *, int, bool) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    ++s.sampler_sample_calls;
    if (s.sampled.empty()) return 0;
    const llama_token token = s.sampled.front();
    s.sampled.pop_front();
    return token;
}

void common_sampler_accept(struct common_sampler *, llama_token, bool) {
    llama_test_doubles::maybe_throw();
}

std::vector<llama_token> common_sampler_sample_and_accept_n(
        struct common_sampler *, struct llama_context *, const llama_tokens & draft, bool) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    ++s.sample_and_accept_calls;
    s.batch_token_counts.push_back((int) draft.size());
    if (s.verified.empty()) {
        // Mirror the documented contract: draft.size() + 1 tokens are returned.
        std::vector<llama_token> fallback = draft;
        fallback.push_back(draft.empty() ? 0 : draft.back() + 1);
        return fallback;
    }
    std::vector<llama_token> tokens = s.verified.front();
    s.verified.pop_front();
    return tokens;
}

// ---------------------------------------------------------------------------------------------
// speculative.h surface used by the production engine
// ---------------------------------------------------------------------------------------------

common_speculative * common_speculative_init(common_params_speculative &, uint32_t) {
    llama_test_doubles::maybe_throw();
    auto &s = llama_test_doubles::script();
    ++s.init_calls;
    ++s.live_engines;
    if (s.live_engines > s.peak_live_engines) s.peak_live_engines = s.live_engines;
    auto *engine = new common_speculative{};
    engine->serial = s.next_engine_serial++;
    s.registry.push_back(engine);
    return engine;
}

void common_speculative_free(common_speculative * spec) {
    if (spec == nullptr) return;
    auto &s = llama_test_doubles::script();
    if (spec->released) {
        // Double release of the same engine: record it, never delete twice.
        ++s.uses_after_release;
        return;
    }
    spec->released = true;
    ++s.free_calls;
    --s.live_engines;
    // Deliberately not deleted: the registry in Script::reset() owns the memory so any later call
    // on this released engine is counted by note_engine_use() instead of touching freed memory.
}

common_speculative_draft_params & common_speculative_get_draft_params(
        common_speculative * spec, llama_seq_id) {
    llama_test_doubles::maybe_throw();
    llama_test_doubles::note_engine_use(spec);
    return spec->dparams;
}

void common_speculative_begin(common_speculative * spec, llama_seq_id seq_id, const llama_tokens & prompt) {
    llama_test_doubles::maybe_throw();
    if (spec == nullptr) return;
    llama_test_doubles::note_engine_use(spec);
    auto &s = llama_test_doubles::script();
    spec->begin_calls++;
    spec->last_seq_id = seq_id;
    spec->last_prompt_size = prompt.size();
    s.prompt_sizes.push_back(prompt.size());
}

void common_speculative_draft(common_speculative * spec) {
    llama_test_doubles::maybe_throw();
    if (spec == nullptr) return;
    llama_test_doubles::note_engine_use(spec);
    auto &s = llama_test_doubles::script();
    spec->draft_calls++;
    spec->dparams.drafting = false;
    if (spec->dparams.result != nullptr) {
        if (!s.drafts.empty()) {
            spec->scripted_draft = s.drafts.front();
            s.drafts.pop_front();
        }
        *spec->dparams.result = spec->scripted_draft;
    }
}

bool common_speculative_process(common_speculative * spec, const llama_batch &) {
    llama_test_doubles::maybe_throw();
    if (spec == nullptr) return false;
    llama_test_doubles::note_engine_use(spec);
    spec->process_calls++;
    return spec->process_ok && llama_test_doubles::script().decode_rc == 0;
}

void common_speculative_accept(common_speculative * spec, llama_seq_id seq_id, uint16_t n_accepted) {
    llama_test_doubles::maybe_throw();
    if (spec == nullptr) return;
    llama_test_doubles::note_engine_use(spec);
    auto &s = llama_test_doubles::script();
    spec->accept_calls++;
    spec->last_accepted = n_accepted;
    spec->last_seq_id = seq_id;
    s.accepted_history.push_back((llama_token) n_accepted);
}
