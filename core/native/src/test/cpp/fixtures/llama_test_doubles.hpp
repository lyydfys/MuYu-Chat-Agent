#ifndef MCA_TEST_FIXTURES_LLAMA_TEST_DOUBLES_HPP
#define MCA_TEST_FIXTURES_LLAMA_TEST_DOUBLES_HPP

// Test doubles for the llama.cpp `common_*` / `llama_*` symbols that
// mca/llm/llama_speculative_engine.cpp calls.
//
// Why a link seam instead of a mock at the mca::llm boundary: the defect under test lives in the
// session/owner lifetime protocol *inside* the production translation unit.  A mock above
// LlamaSpeculativeEngine would replace exactly the code that has to be exercised, so this fixture
// only replaces the vendored llama.cpp functions (draft / verify / accept / decode / token pieces)
// and leaves the production MTP state machine, its environment borrow, and its owner slot intact.
//
// The doubles are deterministic and scripted; every function records what production asked for so
// the test can prove the MTP path was really taken (common_speculative_draft / _process /
// _accept counters) rather than a plain single-token decode.
//
// No llama.cpp library is linked into this test: these definitions satisfy the symbols that
// llama_speculative_engine.cpp references.  Keep the fake headers (llama.h/common.h/sampling.h/
// speculative.h) as the source of truth for every signature -- the compiler enforces them.

#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "speculative.h"

#include <cstdint>
#include <deque>
#include <string>
#include <vector>

// llama.cpp keeps `common_speculative` opaque in speculative.h; the production engine only ever
// holds `common_speculative *`, so this translation unit completes the type with the scripted
// state the doubles expose.  The real speculative.cpp is deliberately not linked here.
struct common_speculative {
    common_speculative_draft_params dparams{};

    // Monotonic identity: a freed engine's address can be reused by the next allocation, so the
    // test compares serials when it has to tell two engine instances apart.
    int serial{0};
    // Set by common_speculative_free().  Released engines are deliberately kept allocated (see the
    // registry in Script) so any later call on them is detected instead of being undefined.
    bool released{false};

    // Scripted drafts written by common_speculative_draft().
    std::vector<llama_token> scripted_draft{};
    bool process_ok{true};

    int begin_calls{0};
    int draft_calls{0};
    int process_calls{0};
    int accept_calls{0};
    uint16_t last_accepted{0};
    int last_seq_id{-1};
    std::size_t last_prompt_size{0};
};

namespace llama_test_doubles {

// Deterministic script + call ledger for one test scenario.
struct Script {
    // Tokens returned by common_sampler_sample(sampler, ctx, idx).
    std::deque<llama_token> sampled{};
    // Token sequences returned by common_sampler_sample_and_accept_n(sampler, ctx, draft).
    // A real call returns draft.size() + 1 tokens; keep the script consistent with that.
    std::deque<std::vector<llama_token>> verified{};
    // Drafts written by common_speculative_draft() for the next verification step.
    std::deque<std::vector<llama_token>> drafts{};
    // Pieces returned by common_token_to_piece(); token id -> piece when provided.
    std::vector<std::string> pieces{};

    // llama_decode() result for the verification batch.
    int decode_rc{0};
    // common_context_can_seq_rm() / llama_n_rs_seq() answers for the draft-MTP rollback gate.
    common_context_seq_rm_type seq_rm{COMMON_CONTEXT_SEQ_RM_TYPE_RS};
    uint32_t n_rs_seq{8};
    // Tokens that llama_vocab_is_eog() reports as end-of-generation.
    std::vector<llama_token> eog_tokens{2};

    // Ledger.
    int init_calls{0};
    int next_engine_serial{1};
    int free_calls{0};
    // Released engines stay allocated in `registry` so a call on a released engine is recorded
    // instead of silently touching freed memory.  This counter must stay 0: it is the
    // "never use the speculative engine after the session released it" invariant.
    int uses_after_release{0};
    // Cumulative across scenarios (not cleared by reset()).
    int total_uses_after_release{0};
    std::vector<common_speculative *> registry{};
    int live_engines{0};
    int peak_live_engines{0};
    int decode_calls{0};
    int sampler_sample_calls{0};
    int sample_and_accept_calls{0};
    int is_eog_calls{0};
    int token_to_piece_calls{0};
    int batch_add_calls{0};
    int max_batch_tokens{0};
    std::vector<int> batch_token_counts{};
    std::vector<llama_token> accepted_history{};
    std::vector<std::size_t> prompt_sizes{};

    void reset();
};

// Global scenario state for the doubles (single-threaded test process).
Script & script();

// Records a call on an engine the session already released (used by the doubles internally; the
// test asserts Script::total_uses_after_release stays 0).
void note_engine_use(common_speculative *spec);

// Throw from the next double call to model a helper/allocator exception crossing the engine.
void throw_on_next_double_call(const std::string &what);

} // namespace llama_test_doubles

#endif // MCA_TEST_FIXTURES_LLAMA_TEST_DOUBLES_HPP
