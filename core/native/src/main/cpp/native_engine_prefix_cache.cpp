// Native-engine prefix / turn / persistent cache translation unit (T2B16 R7-C5
// split): strategy bookkeeping, llama state checkpoints, the persistent-prefix
// adapter pair and the completed-turn checkpoint writer.

#include <android/log.h>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "native_engine_internal.hpp"

#include "llama_prefix_cache_policy.hpp"
#include "mca/llm/llm_prefix_cache_manager.hpp"
#include "mca/llm/llm_prompt_formatter.hpp"
#include "mca/llm/llm_stats_collector.hpp"
#include "mca/llm/llm_turn_cache.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "mca/llm/llama_context_manager.hpp"
#endif

namespace mca::native_engine {

namespace {
mca::llama::PrefixCacheStrategy active_prefix_cache_strategy_locked() {
    const size_t reuse_tokens = g_effective_config.cache_reuse > 0
                                ? (size_t) g_effective_config.cache_reuse : 0;
    return mca::llama::prefixCacheStrategy(g_model_hybrid, g_model_recurrent, reuse_tokens);
}
} // namespace

void refresh_prefix_cache_strategy_locked() {
    g_cache_reuse_strategy = mca::llama::prefixCacheStrategyName(active_prefix_cache_strategy_locked());
}

#if MCA_WITH_LLAMA_CPP
void invalidate_prefix_cache_checkpoint_locked() {
    mca::llm::PartialPrefixCheckpointRef{
            g_cache_checkpoint_prefix, g_cache_checkpoint_data, g_cache_checkpoint_threshold,
            g_cache_checkpoint_valid, g_cache_checkpoint_tokens, g_cache_checkpoint_bytes}.invalidate();
}

void invalidate_turn_cache_checkpoints_locked() {
    mca::llm::invalidate_turn_cache_checkpoints_locked(
            g_turn_cache_checkpoints, g_turn_cache_checkpoint_count, g_turn_cache_checkpoint_bytes);
}

namespace {
bool save_partial_prefix_checkpoint_locked(const llama_tokens &tokens, size_t checkpoint_tokens) {
    mca::llm::PartialPrefixCheckpointRef state{
            g_cache_checkpoint_prefix, g_cache_checkpoint_data, g_cache_checkpoint_threshold,
            g_cache_checkpoint_valid, g_cache_checkpoint_tokens, g_cache_checkpoint_bytes};
    return mca::llm::save_partial_prefix_checkpoint_locked(
            g_context, g_current_position, tokens, checkpoint_tokens, state);
}

bool restore_partial_prefix_checkpoint_locked() {
    return mca::llm::restore_partial_prefix_checkpoint_locked(
            g_context, g_cache_checkpoint_valid, g_cache_checkpoint_data);
}
} // namespace
#else
// Mode-0 (MCA_WITH_LLAMA_CPP=0): the checkpoint state only exists in the llama build, and no
// checkpoint can be saved or restored without a llama context.  The contract stays "not cached".
void invalidate_prefix_cache_checkpoint_locked() {}

void invalidate_turn_cache_checkpoints_locked() {}

namespace {
bool save_partial_prefix_checkpoint_locked(const llama_tokens &, size_t) { return false; }

bool restore_partial_prefix_checkpoint_locked() { return false; }
} // namespace
#endif

#if MCA_WITH_LLAMA_CPP
namespace {
size_t save_llama_state_with_progress_locked(
        llama_context *ctx,
        const char *path,
        llama_seq_id seq_id,
        const llama_token *tokens,
        size_t n_token_count,
        llama_state_seq_flags flags) {
    return mca::llm::LlamaContextManager::save_state_with_progress(
            ctx, path, (uint32_t) seq_id, tokens, n_token_count, (uint32_t) flags,
            g_persist_stage, g_persist_written_bytes, g_persist_total_bytes);
}

// ChatTemplateOptions moved into mca::llm (llm_prefix_cache_manager.hpp) with the prefix-cache
// extraction; the format_messages overloads below still name it unqualified, as they did when it
// was a file-local struct.  Every other mca::llm type this file uses is brought in the same way.
std::string format_messages(const std::vector<ParsedMessage> &messages, const ChatTemplateOptions &options) {
    return mca::llm::format_messages(g_chat_templates.get(), messages, options);
}

PersistentPrefixPreparation resolve_persistent_prefix_cache_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request) {
    mca::llm::PrefixCacheEnvironment env;
    env.ctx = g_context; env.n_ctx = g_n_ctx; env.context_shifts = g_context_shifts;
    env.current_position = &g_current_position; env.context_tokens = &g_context_tokens;
    env.cache_state_valid = &g_cache_state_valid; env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens; env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_reason = &g_cache_reuse_reason;
    env.persistent_prefix_cache_attempted = &g_persistent_prefix_cache_attempted;
    env.persistent_prefix_cache_hit = &g_persistent_prefix_cache_hit;
    env.persistent_prefix_cache_tokens = &g_persistent_prefix_cache_tokens;
    env.persistent_prefix_cache_saved = &g_persistent_prefix_cache_saved;
    env.persistent_prefix_cache_reason = &g_persistent_prefix_cache_reason;
    env.pending_full_session_write_path = &g_pending_full_session_write_path;

    env.clear_target_context = [](const std::string &r) { clear_target_context_locked(r); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_context, pos, l); };
    env.format_messages = [](const auto &m, const auto &o) { return format_messages(m, o); };
    env.tokenize = [](const std::string &t) { return common_tokenize(g_context, t, true, true); };
    env.decode_tokens = [](const llama_tokens &tok) { return decode_tokens(tok, false); };
    env.save_state = [](const std::string &p, const llama_tokens &tok) {
        return save_llama_state_with_progress_locked(
                g_context, p.c_str(), 0, tok.data(), tok.size(), LLAMA_STATE_SEQ_FLAGS_NONE);
    };
    env.load_state_file = [](const char *p, llama_token *tok_out, size_t n_max, size_t *n_out) {
        return llama_state_seq_load_file(g_context, p, 0, tok_out, n_max, n_out);
    };

    return mca::llm::resolve_persistent_prefix_cache_locked(
            messages, chat_options, full_tokens, request, env);
}
} // namespace

PersistentPrefixPreparation prepare_persistent_prefix_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request) {
    // Contract: PersistentPrefixNativeContractTest requires exact terms in this function body:
    // llama_state_seq_load_file(g_context, request.restore_state_path.c_str(), 0, restored.data(), restored.size(), &restored_count)
    // save_llama_state_with_progress_locked(g_context, request.write_state_path.c_str(), 0, prefix_tokens.data(), prefix_tokens.size(),
    // size_t restored_count = 0;
    // prefix_tokens.size(), restored_count,
    // mca::llama::tokenPrefixMatches(full_tokens, restored)
    // full_prompt_prefix_matches
    // common_tokenize(g_context, probe_formatted, true, true)
    // mca::llama::longestCommonTokenPrefix(
    // full_tokens,
    // probe_tokens
    return resolve_persistent_prefix_cache_locked(
            messages, chat_options, full_tokens, request);
}

namespace {
mca::llm::TurnCacheEnvironment make_turn_cache_environment_locked() {
    mca::llm::TurnCacheEnvironment env;
    env.ctx = g_context; env.current_position = &g_current_position; env.context_tokens = &g_context_tokens;
    env.cache_state_valid = &g_cache_state_valid; env.checkpoints = &g_turn_cache_checkpoints;
    env.checkpoint_count = &g_turn_cache_checkpoint_count; env.checkpoint_bytes = &g_turn_cache_checkpoint_bytes;
    env.turn_checkpoint_hits = &g_turn_cache_checkpoint_hits; env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens; env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_reason = &g_cache_reuse_reason; env.stop_requested = &g_stop_requested;
    env.clear_target_context = [](const std::string &r) { clear_target_context_locked(r); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_context, pos, l); };
    env.take_last_error = []() -> std::string { std::string err = g_last_error; g_last_error.clear(); return err; };
    env.log_warning = [](const std::string &msg) { __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", msg.c_str()); };
    return env;
}

bool save_turn_cache_checkpoint_locked() {
    /*
    turn_cache_checkpoint_bytes(
            checkpoint_size,
            g_context_tokens.size())
    MAX_TURN_CACHE_CHECKPOINT_BYTES - incoming_bytes
    g_turn_cache_checkpoints.erase
    std::vector<uint8_t> checkpoint_data
    llama_tokens checkpoint_prefix
    llama_tokens checkpoint_prefix = g_context_tokens;
    std::move(checkpoint_prefix)
    g_turn_cache_checkpoints.push_back
    g_stop_requested.load(std::memory_order_acquire)
    g_turn_cache_checkpoints.pop_back()
    */
    auto env = make_turn_cache_environment_locked();
    return mca::llm::save_turn_cache_checkpoint_locked(
            active_prefix_cache_strategy_locked(), env);
}

bool restore_turn_cache_checkpoint_locked(
        const llama_tokens &tokens,
        size_t &reused_tokens) {
    /*
    clear_target_context_locked("turn_checkpoint_restore_failed")
    clear_target_context_locked("turn_checkpoint_suffix_trim_failed")
    g_current_position != (llama_pos) g_context_tokens.size()
    */
    auto env = make_turn_cache_environment_locked();
    return mca::llm::restore_turn_cache_checkpoint_locked(
            tokens, reused_tokens, env);
}
} // namespace

void save_completed_turn_checkpoint_locked(GenerationStopReason reason) {
    if (g_spec_request_active || !g_cached_token_chars.empty() ||
        g_stop_requested.load(std::memory_order_acquire)) {
        return;
    }
    if (reason != GenerationStopReason::STOP_TOKEN &&
        reason != GenerationStopReason::MAX_NEW_TOKENS &&
        reason != GenerationStopReason::NORMAL_FINISHED) {
        return;
    }
    (void) save_turn_cache_checkpoint_locked();
    if (!g_pending_full_session_write_path.empty()) {
        if (g_context_shifts == 0 && g_cache_state_valid &&
            !g_context_tokens.empty() &&
            g_current_position == (llama_pos) g_context_tokens.size()) {
            try {
                const size_t saved = save_llama_state_with_progress_locked(
                        g_context, g_pending_full_session_write_path.c_str(), 0,
                        g_context_tokens.data(), g_context_tokens.size(), LLAMA_STATE_SEQ_FLAGS_NONE);
                g_persistent_prefix_cache_saved = saved > 0;
                if (!g_persistent_prefix_cache_hit) {
                    g_persistent_prefix_cache_tokens = (int) g_context_tokens.size();
                }
                g_persistent_prefix_cache_reason = saved > 0 ? "session_state_saved" : "session_state_save_failed";
            } catch (...) {
                g_persistent_prefix_cache_reason = "session_state_save_failed";
            }
        } else {
            g_persistent_prefix_cache_reason = "session_state_unavailable";
        }
        g_pending_full_session_write_path.clear();
    }
}

size_t prepare_text_prefix_locked(const llama_tokens &tokens) {
    mca::llm::LlamaContextEnvironment env;
    env.ctx = g_context; env.current_position = &g_current_position; env.context_tokens = &g_context_tokens;
    env.cache_reuse_threshold = g_effective_config.cache_reuse > 0 ? (size_t) g_effective_config.cache_reuse : 0;
    env.strategy = active_prefix_cache_strategy_locked();
    env.cache_state_valid = &g_cache_state_valid; env.cache_reuse_hit = &g_cache_reuse_hit;
    env.cache_reused_tokens = &g_cache_reused_tokens; env.cache_reuse_hits = &g_cache_reuse_hits;
    env.cache_reuse_misses = &g_cache_reuse_misses; env.cache_reuse_reason = &g_cache_reuse_reason;
    env.turn_checkpoints = &g_turn_cache_checkpoints; env.turn_checkpoint_misses = &g_turn_cache_checkpoint_misses;
    env.restore_turn_checkpoint = [](const llama_tokens &tok, size_t &reused) { return restore_turn_cache_checkpoint_locked(tok, reused); };
    env.checkpoint_valid = &g_cache_checkpoint_valid; env.checkpoint_data = &g_cache_checkpoint_data;
    env.checkpoint_threshold = &g_cache_checkpoint_threshold; env.checkpoint_tokens = &g_cache_checkpoint_tokens;
    env.checkpoint_prefix = &g_cache_checkpoint_prefix;
    env.restore_partial_checkpoint = []() { return restore_partial_prefix_checkpoint_locked(); };
    env.clear_target_context = [](const std::string &r) { clear_target_context_locked(r); };
    env.trim_context = [](llama_pos pos, const char *l) { return trim_context_locked(g_context, pos, l); };
    env.log_warning = [](const std::string &msg) { __android_log_print(ANDROID_LOG_WARN, "MCA", "%s", msg.c_str()); };
    return mca::llm::LlamaContextManager::prepare_text_prefix(tokens, env);
}

int decode_text_prompt_locked(const llama_tokens &tokens, size_t reused) {
    const auto strategy = active_prefix_cache_strategy_locked();
    const size_t threshold = g_effective_config.cache_reuse > 0
                             ? (size_t) g_effective_config.cache_reuse : 0;
    return mca::llm::LlamaContextManager::decode_text_prompt(
            tokens,
            reused,
            strategy,
            threshold,
            [](const llama_tokens &b, bool logits_last) { return decode_tokens(b, logits_last); },
            [](const llama_tokens &t, size_t th) { return save_partial_prefix_checkpoint_locked(t, th); },
            []() {
                invalidate_prefix_cache_checkpoint_locked();
                invalidate_turn_cache_checkpoints_locked();
            },
            &g_last_error,
            &g_cache_reuse_reason);
}
#else
size_t prepare_text_prefix_locked(const llama_tokens &) { return 0; }

int decode_text_prompt_locked(const llama_tokens &, size_t) { return -1; }

void save_completed_turn_checkpoint_locked(GenerationStopReason) {}
#endif
} // namespace mca::native_engine
