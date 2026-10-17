// Guard + reproduction harness for P1-01 (GGUF draft-MTP persistent owner).
//
// The defect: native_engine.cpp moved the session-scoped `common_speculative_ptr g_speculative`
// into a temporary `SpeculativeEnvironment` on every generateNextChunk() and never moved it back,
// so the draft-MTP engine was destroyed at the end of the first chunk and every later chunk ran
// without a speculative context.
//
// This translation unit exercises the *production* mca/llm/llama_speculative_engine.cpp state
// machine (prepare -> draft -> verify -> accept -> chunk) against scripted llama.cpp doubles, so it
// runs without a GGUF model and still proves:
//   * one session-scoped owner survives >= 20 chunks of a long generation,
//   * every chunk borrows the same live handle (pointer identity),
//   * the handle is released exactly once, at session teardown, and never by a chunk,
//   * normal EOS, max tokens, stop, decode failure, helper exception, repeated begin,
//     prefix-cache target-context invalidation and unload/reload keep that invariant,
//   * the session glue in native_engine.cpp cannot take ownership of the session engine again
//     (source contract).
//
// Two builds of this same file are used as evidence:
//   green (default)                : the fixed tree -> every check must pass.
//   -DMCA_SPECULATIVE_LEGACY_MOVE_GLUE + the frozen pre-fix sources on the include path
//                                  : reproduces P1-01 -> chunk 2 fails with
//                                    "draft-mtp step requested without an active speculative
//                                     context." and the source contract fails.
//
// The llama.cpp side is a link seam only (fixtures/llama_test_doubles.{hpp,cpp}); the production
// MTP logic under test is unchanged.

#include "mca/llm/llama_speculative_engine.hpp"

#include "fixtures/llama_test_doubles.hpp"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

using llama_test_doubles::script;

int g_checks = 0;
int g_failures = 0;

void check(bool ok, const std::string &what) {
    ++g_checks;
    if (ok) {
        std::cout << "  [ok]   " << what << "\n";
    } else {
        ++g_failures;
        std::cout << "  [FAIL] " << what << "\n";
    }
}

void section(const std::string &name) {
    std::cout << "\n== " << name << " ==\n";
}

// ---------------------------------------------------------------------------------------------
// Stable fake llama handles: the doubles never dereference them.
// ---------------------------------------------------------------------------------------------
llama_model *const kFakeModel = reinterpret_cast<llama_model *>(0x2000);
llama_context *const kFakeTarget = reinterpret_cast<llama_context *>(0x3000);
llama_context *const kFakeMtp = reinterpret_cast<llama_context *>(0x4000);
common_sampler *const kFakeSampler = reinterpret_cast<common_sampler *>(0x5000);

// Batch storage shaped like the one native_engine.cpp prepares with llama_batch_init().
struct BatchBuffer {
    std::vector<llama_token> token;
    std::vector<llama_pos> pos;
    std::vector<int32_t> n_seq_id;
    std::vector<std::vector<llama_seq_id>> seq_ids;
    std::vector<llama_seq_id *> seq_id_ptrs;
    std::vector<int8_t> logits;
    llama_batch batch{};

    explicit BatchBuffer(int capacity)
        : token((size_t) capacity),
          pos((size_t) capacity),
          n_seq_id((size_t) capacity),
          seq_ids((size_t) capacity, std::vector<llama_seq_id>(1, 0)),
          seq_id_ptrs((size_t) capacity),
          logits((size_t) capacity) {
        for (int i = 0; i < capacity; ++i) seq_id_ptrs[(size_t) i] = seq_ids[(size_t) i].data();
        batch.n_tokens = 0;
        batch.token = token.data();
        batch.embd = nullptr;
        batch.pos = pos.data();
        batch.n_seq_id = n_seq_id.data();
        batch.seq_id = seq_id_ptrs.data();
        batch.logits = logits.data();
    }
};

const common_speculative *borrowed(const mca::llm::SpeculativeEnvironment &env) {
#if defined(MCA_SPECULATIVE_LEGACY_MOVE_GLUE)
    return env.speculative.get();
#else
    return env.speculative;
#endif
}

// Faithful transcription of the session-level speculative state in native_engine.cpp: the pointer
// semantics of make_speculative_environment_locked() / generateNextChunk() / unload are the code
// under test, so they are mirrored here one-to-one and pinned by the source contract below.
struct Session {
    BatchBuffer batch_buffer{64};

    common_speculative_ptr g_speculative;  // native_engine.cpp: the persistent session owner
    bool g_spec_requested = true;
    bool g_spec_request_active = false;
    bool g_spec_done = false;
    std::string g_spec_request_reason = "disabled";
    llama_token g_spec_pending_token = LLAMA_TOKEN_NULL;
    bool g_spec_pending_valid = false;
    llama_tokens g_spec_prompt_tokens;
    llama_tokens g_spec_draft_tokens;
    long long g_spec_drafted_tokens = 0;
    long long g_spec_accepted_tokens = 0;
    long long g_spec_steps = 0;
    llama_pos g_current_position = 0;
    llama_tokens g_context_tokens;
    int g_n_ctx = 4096;
    int g_n_batch = 64;
    int g_n_predict = 512;
    int spec_draft_n_max = 4;
    std::string g_last_error;
    std::string g_cached_token_chars;
    long long completion_tokens = 0;
    bool g_cache_state_valid = false;
    std::string g_cache_reuse_reason = "disabled";

    bool stop_requested = false;
    int invalidate_calls = 0;
    int trim_calls = 0;
    int gpu_execution_records = 0;
    int chunks = 0;
    std::vector<const common_speculative *> borrow_history;

    // Mirrors native_engine.cpp invalidate_speculative_contexts_locked(): the reference
    // (76348a5b) releases the session-scoped engine here, before the contexts are cleared.
    void invalidate_speculative_contexts_locked(const std::string &reason) {
        g_speculative.reset();
        g_spec_request_active = false;
        g_spec_request_reason = reason;
        g_spec_pending_token = LLAMA_TOKEN_NULL;
        g_spec_pending_valid = false;
        g_spec_done = true;
        g_spec_prompt_tokens.clear();
        g_spec_draft_tokens.clear();
        ++invalidate_calls;
    }

    bool stop_speculative_request_if_requested_locked() {
        if (!stop_requested) return false;
        invalidate_speculative_contexts_locked("stopped");
        return true;
    }

    // Mirrors native_engine.cpp clear_target_context_locked(): cache/context churn does NOT
    // release the session engine.
    void clear_target_context_locked(const std::string &reason) {
        g_current_position = 0;
        g_cache_state_valid = false;
        g_cache_reuse_reason = reason;
        g_context_tokens.clear();
    }

    mca::llm::SpeculativeEnvironment make_speculative_environment_locked() {
        mca::llm::SpeculativeEnvironment env;
        env.model = kFakeModel;
        env.context = kFakeTarget;
        env.mtp_context = kFakeMtp;
        env.batch = &batch_buffer.batch;
        env.sampler = kFakeSampler;
#if defined(MCA_SPECULATIVE_LEGACY_MOVE_GLUE)
        // Pre-fix P1-01 glue: the persistent owner is moved out of the session.
        env.speculative = std::move(g_speculative);
#else
        // Fixed glue: the session keeps the unique_ptr, the environment only borrows it.
        env.speculative_owner = &g_speculative;
        env.speculative = g_speculative.get();
#endif
        env.spec_requested = g_spec_requested;
        env.spec_request_active = &g_spec_request_active;
        env.spec_request_reason = &g_spec_request_reason;
        env.spec_done = &g_spec_done;
        env.spec_pending_token = &g_spec_pending_token;
        env.spec_pending_valid = &g_spec_pending_valid;
        env.spec_prompt_tokens = &g_spec_prompt_tokens;
        env.spec_draft_tokens = &g_spec_draft_tokens;
        env.spec_drafted_tokens = &g_spec_drafted_tokens;
        env.spec_accepted_tokens = &g_spec_accepted_tokens;
        env.spec_steps = &g_spec_steps;
        env.current_position = &g_current_position;
        env.context_tokens = &g_context_tokens;
        env.n_ctx = g_n_ctx;
        env.n_batch = g_n_batch;
        env.n_predict = g_n_predict;
        env.spec_draft_n_max = spec_draft_n_max;
        env.overflow_headroom = 4;
        env.completion_tokens = completion_tokens;
        env.last_error = &g_last_error;
        env.is_stopped = [this]() { return stop_speculative_request_if_requested_locked(); };
        env.trim_context = [this](llama_pos, const char *) {
            ++trim_calls;
            return true;
        };
        env.trim_speculative_contexts = [this](llama_pos, const char *) {
            ++trim_calls;
            return true;
        };
        env.invalidate_speculative_contexts = [this](const std::string &r) {
            invalidate_speculative_contexts_locked(r);
        };
        env.record_gpu_execution = [this]() { ++gpu_execution_records; };
        return env;
    }

    bool prepare_speculative_request_locked() {
        auto env = make_speculative_environment_locked();
        const bool ok = mca::llm::LlamaSpeculativeEngine::prepare_speculative_request(env);
#if defined(MCA_SPECULATIVE_LEGACY_MOVE_GLUE)
        g_speculative = std::move(env.speculative);  // pre-fix native_engine.cpp L827
#endif
        return ok;
    }

    // native_engine.cpp generateNextChunk() MTP branch.
    mca::llm::SpecChunkResult generate_next_chunk(int max_tokens) {
        ++chunks;
        auto spec_env = make_speculative_environment_locked();
        borrow_history.push_back(borrowed(spec_env));
        return mca::llm::LlamaSpeculativeEngine::generate_chunk(
                spec_env, g_cached_token_chars, completion_tokens, max_tokens);
    }

    void unload() { g_speculative.reset(); }
};

const common_speculative *owner_handle(const Session &s) { return s.g_speculative.get(); }

// Engine instance identity that survives address reuse (free + allocate can return the same
// address, which must not be mistaken for "the same engine is still installed").
int owner_serial(const Session &s) { return s.g_speculative != nullptr ? s.g_speculative->serial : -1; }

std::string ptr_text(const void *p) {
    std::ostringstream out;
    out << p;
    return out.str();
}

void script_single_token_first_chunk(llama_token first) {
    script().sampled.push_back(first);
}

void script_draft_round(llama_token base) {
    script().drafts.push_back({base, base + 1, base + 2, base + 3});
    script().verified.push_back({base, base + 1, base + 2, base + 3, base + 4});
}

// True when every recorded chunk borrow saw the same handle (kept readable in both builds).
bool borrowed_history_matches(const Session &session, const common_speculative *owner);

bool shared_owner_across_chunks(const Session &session, const common_speculative *owner) {
    return owner != nullptr && borrowed_history_matches(session, owner);
}

// ---------------------------------------------------------------------------------------------
// Scenario 1: long generation (24 chunks) on one persistent owner.
// ---------------------------------------------------------------------------------------------
void scenario_long_generation() {
    section("long_generation: 24 chunks, one session owner");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    for (int i = 0; i < 24; ++i) script_draft_round(201 + 10 * i);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    check(owner != nullptr, "session owns a live draft-MTP engine after prepare");
    check(script().init_calls == 1, "exactly one engine was created");

    int produced = 0;
    bool same_owner_every_chunk = owner != nullptr;
    for (int chunk = 0; chunk < 24; ++chunk) {
        const auto res = session.generate_next_chunk(0);
        if (res.is_failed) {
            std::cout << "  chunk " << (chunk + 1) << " failed: " << res.error_message << "\n";
        }
        check(!res.is_failed, "chunk " + std::to_string(chunk + 1) + " completed");
        if (res.is_failed) break;
        if (res.has_output) ++produced;
        if (!shared_owner_across_chunks(session, owner)) same_owner_every_chunk = false;
    }
    check(same_owner_every_chunk, "every chunk borrowed the same live owner handle");
    check(session.chunks == 24, "24 chunks were requested");
    check(produced == 24, "24 chunks returned output (long generation actually produced tokens)");
    check(script().free_calls == 0, "no chunk released the session engine");
    check(script().live_engines == 1, "exactly one live engine while the session runs");
    check(script().peak_live_engines == 1, "the session never held two engines at once");
    check(session.completion_tokens >= 100, "long generation advanced well past the first chunk");
    check(script().sample_and_accept_calls >= 20, "the MTP draft/verify path ran for every later chunk");
    check(script().accepted_history.size() >= 20, "common_speculative_accept() ran for every later chunk");
    check(script().max_batch_tokens >= 5, "the verification batch carried the draft tokens, not a plain decode");

    session.unload();
    check(script().free_calls == 1, "unload releases the engine exactly once");
    check(script().live_engines == 0, "no engine survives unload");
    session.unload();
    check(script().free_calls == 1, "repeated unload does not double free");
}

// In pre-fix builds the session owner disappears after the first chunk; this helper keeps the
// scenario readable in both builds.
bool borrowed_history_matches(const Session &session, const common_speculative *owner) {
    for (const common_speculative *seen : session.borrow_history) {
        if (seen != owner) return false;
    }
    return true;
}
// ---------------------------------------------------------------------------------------------
// Scenario 2: sensitivity check -- if the owner is lost, the next chunk must fail loudly.
// ---------------------------------------------------------------------------------------------
void scenario_owner_loss_is_detected() {
    section("owner_loss_is_detected: the guard notices a lost owner");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    for (int i = 0; i < 4; ++i) script_draft_round(301 + 10 * i);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const auto first = session.generate_next_chunk(0);
    check(!first.is_failed, "chunk 1 completed");

    // Reproduce the pre-fix lifetime from the caller side: the session drops its owner while the
    // request is still marked active.  A correct caller never does this, and the engine must
    // report a hard failure instead of silently decoding without a speculative context.
    session.g_speculative.reset();
    const auto second = session.generate_next_chunk(0);
    check(second.is_failed, "chunk 2 fails when the session owner is gone");
    check(second.error_message.find("without an active speculative context") != std::string::npos,
          "failure names the missing speculative context: " + second.error_message);
    check(session.g_spec_request_active, "request stays flagged active, so the loss is observable");
    session.unload();
}

// ---------------------------------------------------------------------------------------------
// Scenario 3: normal EOS must finish the chunk and keep the session engine alive.
// ---------------------------------------------------------------------------------------------
void scenario_normal_eos() {
    section("normal_eos");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script().drafts.push_back({201, 202, 203});
    script().verified.push_back({201, 2, 203});  // token 2 is EOG

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    const auto first = session.generate_next_chunk(0);
    check(!first.is_failed && first.has_output, "chunk 1 produced the first token");

    const auto eos = session.generate_next_chunk(0);
    check(!eos.is_failed, "EOS chunk is not a failure");
    check(eos.is_done, "EOS chunk reports done");
    check(eos.normal_finished, "EOS chunk reports normal_finished");
    check(!eos.max_tokens_finished, "EOS chunk is not a max-token stop");
    check(owner_handle(session) == owner, "EOS keeps the same session owner");
    check(script().free_calls == 0, "EOS does not release the session engine");
    session.unload();
    check(script().free_calls == 1, "unload after EOS releases the engine exactly once");
}

// ---------------------------------------------------------------------------------------------
// Scenario 4: max tokens boundary.
// ---------------------------------------------------------------------------------------------
void scenario_max_tokens() {
    section("max_tokens");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script_draft_round(401);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    const auto first = session.generate_next_chunk(6);
    check(!first.is_failed && !first.is_done, "chunk 1 stays below the max-token budget");

    const auto last = session.generate_next_chunk(6);
    check(!last.is_failed, "max-token chunk is not a failure");
    check(last.is_done, "max-token chunk reports done");
    check(last.max_tokens_finished, "max-token chunk reports max_tokens_finished");
    check(owner_handle(session) == owner, "max-token stop keeps the same session owner");
    session.unload();
    check(script().free_calls == 1, "unload after max tokens releases the engine exactly once");
}

// ---------------------------------------------------------------------------------------------
// Scenario 5: stop request.
// ---------------------------------------------------------------------------------------------
void scenario_stop_request() {
    section("stop_request");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script_draft_round(501);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    check(!session.generate_next_chunk(0).is_failed, "chunk 1 completed");

    session.stop_requested = true;
    const auto stopped = session.generate_next_chunk(0);
    check(stopped.is_stopped, "chunk 2 reports the stop request");
    check(!stopped.has_output, "stop chunk publishes no token");
    check(!stopped.is_failed, "stop is not reported as a failure");
    // Reference (76348a5b) owner semantics: the invalidation that a stop triggers releases the
    // session-scoped engine, and the engine that chunk 1 used is the one released.
    check(owner_handle(session) == nullptr, "stop leaves no session engine installed");
    check(owner != nullptr && owner->released, "the engine chunk 1 used is the one that was released");
    check(script().free_calls == 1, "stop released the engine exactly once");
    check(script().live_engines == 0, "no engine is live after the stop");
    check(session.g_spec_request_reason == "stopped", "stop records its invalidation reason");
    // Every stop check inside the step runs the (idempotent) invalidation again; the release must
    // still happen exactly once, which the free_calls checks above pin down.
    check(session.invalidate_calls >= 1,
          "stop invalidates the speculative contexts (idempotent, calls=" +
                  std::to_string(session.invalidate_calls) + ")");
    session.unload();
    check(script().free_calls == 1, "teardown after stop does not double free");
}

// ---------------------------------------------------------------------------------------------
// Scenario 5b: invalidation outside a chunk (prefill failure / caller-invalidated context)
// releases the engine exactly once and the next begin adopts a fresh one.
// ---------------------------------------------------------------------------------------------
void scenario_invalidate_releases_engine() {
    section("invalidate_releases_engine");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script_single_token_first_chunk(200);
    for (int i = 0; i < 2; ++i) script_draft_round(551 + 10 * i);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    const int first_serial = owner_serial(session);
    check(!session.generate_next_chunk(0).is_failed, "chunk 1 completed");

    // Mirrors native_engine.cpp's prefill failure / context_invalidated_by_caller paths.
    session.invalidate_speculative_contexts_locked("prefill_failed");
    check(owner_handle(session) == nullptr, "invalidation releases the session owner");
    check(owner != nullptr && owner->released, "the invalidation released the engine chunk 1 used");
    check(script().free_calls == 1, "invalidation released the engine exactly once");
    check(script().live_engines == 0, "no engine is live after invalidation");
    check(session.g_spec_request_reason == "prefill_failed", "invalidation reason is recorded");

    session.g_spec_pending_token = LLAMA_TOKEN_NULL;
    session.g_spec_pending_valid = false;
    session.g_spec_done = false;
    check(session.prepare_speculative_request_locked(), "the next begin adopts a fresh engine");
    check(owner_serial(session) != first_serial, "the next begin created a different engine");
    check(script().init_calls == 2 && script().free_calls == 1, "one create and one release so far");
    check(!session.generate_next_chunk(0).is_failed, "generation works again after invalidation");
    session.unload();
    check(script().free_calls == 2, "final teardown released the second engine exactly once");
    check(script().live_engines == 0, "no engine survives the final teardown");
}

// ---------------------------------------------------------------------------------------------
// Scenario 6: decode failure inside the verification step.
// ---------------------------------------------------------------------------------------------
void scenario_decode_failure() {
    section("decode_failure");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script_draft_round(601);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    check(!session.generate_next_chunk(0).is_failed, "chunk 1 completed");

    script().decode_rc = -7;
    const auto failed = session.generate_next_chunk(0);
    check(failed.is_failed, "decode failure fails the chunk");
    check(failed.error_message.find("llama_decode failed during draft-mtp verification") != std::string::npos,
          "decode failure message preserved: " + failed.error_message);
    check(script().free_calls == 0, "the failed chunk itself does not release the session engine");

    // native_engine.cpp tears the request down on failure (g_speculative.reset()).
    session.unload();
    check(script().free_calls == 1, "teardown after decode failure releases the engine exactly once");
}

// ---------------------------------------------------------------------------------------------
// Scenario 7: a helper throws while a chunk is in flight.
// ---------------------------------------------------------------------------------------------
void scenario_helper_exception() {
    section("helper_exception");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script_draft_round(701);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    check(!session.generate_next_chunk(0).is_failed, "chunk 1 completed");

    bool caught = false;
    llama_test_doubles::throw_on_next_double_call("scripted helper failure");
    try {
        (void) session.generate_next_chunk(0);
    } catch (const std::exception &e) {
        caught = true;
        std::cout << "  caught: " << e.what() << "\n";
    }
    check(caught, "helper exception propagates to the caller");
    check(owner_handle(session) == owner, "the session owner is still the same live engine");
    check(script().free_calls == 0, "the exception did not release the session engine");
    check(script().live_engines == 1, "exactly one engine is live after the exception");
    session.unload();
    check(script().free_calls == 1, "teardown after the exception releases the engine exactly once");
    check(script().peak_live_engines == 1, "no engine was leaked or duplicated");
}

// ---------------------------------------------------------------------------------------------
// Scenario 8: repeated begin() on the same session replaces the engine exactly once.
// ---------------------------------------------------------------------------------------------
void scenario_repeated_begin() {
    section("repeated_begin");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    for (int i = 0; i < 4; ++i) script_draft_round(801 + 10 * i);

    check(session.prepare_speculative_request_locked(), "first begin adopts an engine");
    const common_speculative *first_owner = owner_handle(session);
    const int first_serial = owner_serial(session);
    check(!session.generate_next_chunk(0).is_failed, "first generation chunk completed");
    check(borrowed_history_matches(session, first_owner), "the first phase borrowed one live owner");
    session.g_spec_pending_token = LLAMA_TOKEN_NULL;
    session.g_spec_pending_valid = false;
    session.g_spec_done = false;
    script().sampled.push_back(110);
    check(session.prepare_speculative_request_locked(), "second begin adopts an engine");
    const common_speculative *second_owner = owner_handle(session);
    check(second_owner != nullptr, "second begin leaves a live engine");
    check(owner_serial(session) != first_serial, "second begin replaced the engine instance");
    check(script().init_calls == 2, "exactly two engines were created");
    check(script().free_calls == 1, "the first engine was released exactly once");
    check(script().live_engines == 1, "only one engine is live after the replacement");
    check(!session.generate_next_chunk(0).is_failed, "chunk after the second begin completed");
    check(owner_handle(session) == second_owner && owner_serial(session) != first_serial,
          "later chunks keep borrowing the replacement owner");
    session.unload();
    check(script().free_calls == 2, "each engine was released exactly once");
    check(script().live_engines == 0, "no engine survives unload");
}

// ---------------------------------------------------------------------------------------------
// Scenario 9: prefix-cache target-context invalidation between chunks keeps the owner.
// ---------------------------------------------------------------------------------------------
void scenario_prefix_cache_reuse() {
    section("prefix_cache_reuse");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    for (int i = 0; i < 8; ++i) script_draft_round(901 + 10 * i);

    check(session.prepare_speculative_request_locked(), "prepare_speculative_request() succeeds");
    const common_speculative *owner = owner_handle(session);
    check(!session.generate_next_chunk(0).is_failed, "chunk 1 completed");

    // native_engine.cpp disables prefix-cache reuse for draft-MTP and clears/reuses the target
    // context around requests.  That is clear_target_context_locked(), not an invalidation: it
    // must not release the session-scoped engine.
    for (int round = 0; round < 4; ++round) {
        session.clear_target_context_locked("disabled_by_draft_mtp");
        session.g_spec_prompt_tokens.clear();
        const auto res = session.generate_next_chunk(0);
        check(!res.is_failed, "chunk after cache invalidation round " + std::to_string(round + 1) + " completed");
        check(owner_handle(session) == owner, "cache invalidation round " + std::to_string(round + 1) +
                                              " keeps the same live owner");
        check(script().free_calls == 0, "cache invalidation never releases the session engine");
    }
    session.unload();
    check(script().free_calls == 1, "unload after cache churn releases the engine exactly once");
}

// ---------------------------------------------------------------------------------------------
// Scenario 10: unload / reload cycle.
// ---------------------------------------------------------------------------------------------
void scenario_unload_reload() {
    section("unload_reload");
    script().reset();
    Session session;
    script_single_token_first_chunk(100);
    script_single_token_first_chunk(200);  // the reload's first chunk
    for (int i = 0; i < 2; ++i) script_draft_round(1001 + 10 * i);

    check(session.prepare_speculative_request_locked(), "first load adopts an engine");
    const common_speculative *first_owner = owner_handle(session);
    const int first_serial = owner_serial(session);
    check(!session.generate_next_chunk(0).is_failed, "chunk before unload completed");
    session.unload();
    check(script().free_calls == 1, "unload released the first engine exactly once");
    check(script().live_engines == 0, "no engine is live after unload");

    session.g_spec_pending_token = LLAMA_TOKEN_NULL;
    session.g_spec_pending_valid = false;
    session.g_spec_done = false;
    session.g_context_tokens.clear();
    session.g_current_position = 0;
    check(session.prepare_speculative_request_locked(), "reload adopts a new engine");
    const common_speculative *second_owner = owner_handle(session);
    check(second_owner != nullptr && owner_serial(session) != first_serial,
          "reload created a different live engine");
    check(second_owner != first_owner || owner_serial(session) != first_serial,
          "the pre-unload engine instance is gone");
    check(!session.generate_next_chunk(0).is_failed, "chunk after reload completed");
    check(script().peak_live_engines == 1, "no two engines were ever live at the same time");
    session.unload();
    check(script().free_calls == 2, "each engine was released exactly once across unload/reload");
    check(script().live_engines == 0, "no engine survives the final unload");
}

// ---------------------------------------------------------------------------------------------
// Source contract: the production session glue must keep borrowing the persistent owner.
// ---------------------------------------------------------------------------------------------
std::string read_text_file(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return std::string();
    std::ostringstream out;
    out << in.rdbuf();
    return out.str();
}

bool contains(const std::string &haystack, const std::string &needle) {
    return haystack.find(needle) != std::string::npos;
}

std::string dir_of(const std::string &path) {
    const std::size_t slash = path.find_last_of("/\\");
    return slash == std::string::npos ? std::string(".") : path.substr(0, slash);
}

void source_contract(int argc, char **argv) {
    // T2B16: the session-owner contract spans the split native_engine TUs; the union is
    // every supplied file, and dir_of() still anchors on native_engine.cpp (argv[1]).
    const std::string native_engine_path = argv[1];
    section("source_contract: " + native_engine_path);
    std::string source;
    for (int i = 1; i < argc; ++i) {
        source += read_text_file(argv[i]);
    }
    if (source.empty()) {
        check(false, "native_engine.cpp is readable at the given path");
        return;
    }
    const std::string header_path = dir_of(native_engine_path) + "/mca/llm/llama_speculative_engine.hpp";
    const std::string engine_path = dir_of(native_engine_path) + "/mca/llm/llama_speculative_engine.cpp";
    const std::string header = read_text_file(header_path);
    const std::string engine = read_text_file(engine_path);

    check(!source.empty(), "native_engine.cpp readable");
    check(!header.empty(), "llama_speculative_engine.hpp readable at " + header_path);
    check(!engine.empty(), "llama_speculative_engine.cpp readable at " + engine_path);

    check(contains(source, "env.speculative = g_speculative.get();"),
          "native_engine.cpp borrows the session owner (.get()) when building an environment");
    check(contains(source, "env.speculative_owner = &g_speculative;"),
          "native_engine.cpp hands the environment a non-owning owner slot");
    check(!contains(source, "std::move(g_speculative)"),
          "native_engine.cpp never moves the persistent session owner into a temporary");
    check(!contains(source, "g_speculative = std::move("),
          "native_engine.cpp never adopts an engine back out of a temporary environment");
    check(contains(source, "common_speculative_begin(g_speculative.get()"),
          "begin() is still driven from the live session owner");
    check(contains(source, "g_speculative.reset()"),
          "session teardown still releases the engine explicitly");
    // Reference (76348a5b) invalidation semantics: the owner is released inside
    // invalidate_speculative_contexts_locked(), before the target/MTP contexts are cleared.
    const std::size_t invalidate_at = source.find("void invalidate_speculative_contexts_locked");
    check(invalidate_at != std::string::npos &&
                  source.find("g_speculative.reset();", invalidate_at) != std::string::npos &&
                  source.find("g_speculative.reset();", invalidate_at) < invalidate_at + 700,
          "invalidation releases the session engine (reference owner semantics)");

    check(contains(header, "common_speculative *speculative{nullptr};"),
          "SpeculativeEnvironment borrows the engine through a raw pointer");
    check(contains(header, "common_speculative_ptr *speculative_owner{nullptr};"),
          "SpeculativeEnvironment publishes a created engine into the session owner slot");
    check(!contains(header, "common_speculative_ptr speculative;"),
          "SpeculativeEnvironment cannot own the engine");

    check(contains(engine, "env.speculative_owner->reset()"),
          "a new request releases the previous session engine before creating its replacement");
    check(contains(engine, "*env.speculative_owner = std::move("),
          "a created engine is published to the session owner slot");
    check(contains(engine, "env.speculative = env.speculative_owner->get();"),
          "the environment only borrows the engine it just published");
}

} // namespace

int main(int argc, char **argv) {
    std::cout << "mca draft-MTP session owner guard\n";
#if defined(MCA_SPECULATIVE_LEGACY_MOVE_GLUE)
    std::cout << "mode: LEGACY (pre-fix move glue; P1-01 reproduction)\n";
#else
    std::cout << "mode: FIXED (session keeps the unique_ptr owner)\n";
#endif

    scenario_long_generation();
    scenario_owner_loss_is_detected();
    scenario_normal_eos();
    scenario_max_tokens();
    scenario_stop_request();
    scenario_invalidate_releases_engine();
    scenario_decode_failure();
    scenario_helper_exception();
    scenario_repeated_begin();
    scenario_prefix_cache_reuse();
    scenario_unload_reload();

    // The session engine must never be touched after the session released it: every double call on
    // a released engine is counted by the link seam.
    const int uses_after_release =
            script().total_uses_after_release + script().uses_after_release;
    check(uses_after_release == 0,
          "no speculative engine was used after its release (uses_after_release=" +
                  std::to_string(uses_after_release) + ")");

    if (argc > 1) {
        source_contract(argc, argv);
    } else {
        std::cout << "\n(no native_engine.cpp path supplied; source contract skipped)\n";
    }

    std::cout << "\nchecks=" << g_checks << " failures=" << g_failures << "\n";
    if (g_failures != 0) {
        std::cout << "RESULT: FAIL\n";
        return 1;
    }
    std::cout << "RESULT: PASS\n";
    return 0;
}
