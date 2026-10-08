#include "llm_prefix_cache_manager.hpp"

#include <algorithm>
#include <cctype>
#include <string>
#include <vector>

#include "llama_prefix_cache_policy.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#endif

namespace mca {
namespace llm {

namespace {

std::string to_lower_ascii(std::string value) {
    for (char &c : value) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return value;
}

} // namespace

bool PrefixCacheManager::restore_session_state(
        const PrefixCacheRequest &request,
        const llama_tokens &full_tokens,
        PrefixCacheEnvironment &env,
        PrefixCacheDecision &decision) {
    if (full_tokens.empty()) {
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "empty_session_prompt";
        }
        return false;
    }
    if (env.clear_target_context) {
        env.clear_target_context("persistent_session_prepare");
    }
    if (!request.restore_state_path.empty()) {
        try {
            size_t restored_count = 0;
            size_t header_size = 0;
            if (env.load_state_file) {
                header_size = env.load_state_file(
                        request.restore_state_path.c_str(),
                        nullptr,
                        0,
                        &restored_count);
            } else {
#if MCA_WITH_LLAMA_CPP
                header_size = llama_state_seq_load_file(
                        env.ctx,
                        request.restore_state_path.c_str(),
                        0,
                        nullptr,
                        0,
                        &restored_count);
#endif
            }
            if (header_size > 0 && restored_count > 0 &&
                restored_count <= static_cast<size_t>(env.n_ctx)) {
                llama_tokens restored(restored_count);
                size_t loaded_count = 0;
                size_t loaded = 0;
                if (env.load_state_file) {
                    loaded = env.load_state_file(
                            request.restore_state_path.c_str(),
                            restored.data(),
                            restored.size(),
                            &loaded_count);
                } else {
#if MCA_WITH_LLAMA_CPP
                    loaded = llama_state_seq_load_file(
                            env.ctx,
                            request.restore_state_path.c_str(),
                            0,
                            restored.data(),
                            restored.size(),
                            &loaded_count);
#endif
                }
                if (loaded > 0 && loaded_count == restored_count) {
                    const size_t reusable = mca::llama::reusableSessionTokenPrefix(
                            full_tokens, restored);
                    if (reusable > 0 &&
                        (reusable == restored_count || (env.trim_context && env.trim_context(
                                static_cast<llama_pos>(reusable),
                                "persistent session")))) {
                        restored.resize(reusable);
                        if (env.current_position) *env.current_position = static_cast<llama_pos>(reusable);
                        if (env.context_tokens) *env.context_tokens = std::move(restored);
                        if (env.cache_state_valid) *env.cache_state_valid = true;
                        if (env.cache_reuse_hit) *env.cache_reuse_hit = true;
                        if (env.cache_reused_tokens) *env.cache_reused_tokens = static_cast<int>(reusable);
                        if (env.cache_reuse_hits) (*env.cache_reuse_hits)++;
                        if (env.cache_reuse_reason) *env.cache_reuse_reason = "persistent_session_hit";
                        if (env.persistent_prefix_cache_hit) *env.persistent_prefix_cache_hit = true;
                        if (env.persistent_prefix_cache_tokens) *env.persistent_prefix_cache_tokens = static_cast<int>(reusable);
                        if (env.persistent_prefix_cache_reason) *env.persistent_prefix_cache_reason = "session_state_loaded";
                        if (env.pending_full_session_write_path) *env.pending_full_session_write_path = request.write_state_path;
                        decision.handled = true;
                        decision.reused_tokens = reusable;
                        return true;
                    }
                    if (env.persistent_prefix_cache_reason) {
                        *env.persistent_prefix_cache_reason = reusable > 0
                                ? "session_state_trim_failed"
                                : "session_state_token_mismatch";
                    }
                } else {
                    if (env.persistent_prefix_cache_reason) {
                        *env.persistent_prefix_cache_reason = "session_state_load_failed";
                    }
                }
            } else {
                if (env.persistent_prefix_cache_reason) {
                    *env.persistent_prefix_cache_reason = "session_state_load_failed";
                }
            }
        } catch (...) {
            if (env.persistent_prefix_cache_reason) {
                *env.persistent_prefix_cache_reason = "session_state_load_failed";
            }
        }
        if (env.clear_target_context) {
            env.clear_target_context("persistent_session_restore_failed");
        }
    } else {
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "session_state_cold_start";
        }
    }
    if (env.pending_full_session_write_path) {
        *env.pending_full_session_write_path = request.write_state_path;
    }
    decision.handled = true;
    decision.reused_tokens = 0;
    return true;
}

bool PrefixCacheManager::find_probe_boundary(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const std::string &fixed_system_prompt,
        PrefixCacheEnvironment &env,
        std::string &out_probe_formatted) {
    size_t marker_position = std::string::npos;
    for (int attempt = 0; attempt < 8 && marker_position == std::string::npos; ++attempt) {
        const std::string marker =
                "MCA_PREFIX_CACHE_BOUNDARY_V1_" + std::to_string(attempt) + "_9f2a";
        bool marker_in_input = false;
        for (const auto &message : messages) {
            if (message.content.find(marker) != std::string::npos) {
                marker_in_input = true;
                break;
            }
        }
        if (marker_in_input) continue;
        auto probe_messages = messages;
        probe_messages.front().content = fixed_system_prompt + marker;
        try {
            if (env.format_messages) {
                out_probe_formatted = env.format_messages(probe_messages, chat_options);
            }
        } catch (...) {
            out_probe_formatted.clear();
        }
        const size_t first = out_probe_formatted.find(marker);
        if (first != std::string::npos && out_probe_formatted.find(marker, first + marker.size()) == std::string::npos) {
            marker_position = first;
        }
    }
    return marker_position != std::string::npos && !out_probe_formatted.empty();
}

bool PrefixCacheManager::restore_fixed_prefix_state(
        const std::string &restore_state_path,
        const llama_tokens &prefix_tokens,
        const llama_tokens &full_tokens,
        PrefixCacheEnvironment &env,
        PrefixCacheDecision &decision) {
    if (restore_state_path.empty()) return false;
    try {
        llama_tokens restored(prefix_tokens.size());
        size_t restored_count = 0;
        size_t loaded = 0;
        if (env.load_state_file) {
            loaded = env.load_state_file(
                    restore_state_path.c_str(),
                    restored.data(),
                    restored.size(),
                    &restored_count);
        } else {
#if MCA_WITH_LLAMA_CPP
            loaded = llama_state_seq_load_file(
                    env.ctx,
                    restore_state_path.c_str(),
                    0,
                    restored.data(),
                    restored.size(),
                    &restored_count);
#endif
        }
        const bool restored_tokens_match =
                std::equal(restored.begin(), restored.end(), prefix_tokens.begin());
        const bool full_prompt_prefix_matches =
                mca::llama::tokenPrefixMatches(full_tokens, restored);
        if (loaded > 0 && mca::llama::canRestorePersistentPrefixState(
                prefix_tokens.size(),
                restored_count,
                restored_tokens_match,
                full_prompt_prefix_matches)) {
            if (env.current_position) *env.current_position = static_cast<llama_pos>(restored_count);
            if (env.context_tokens) *env.context_tokens = prefix_tokens;
            if (env.cache_state_valid) *env.cache_state_valid = true;
            if (env.cache_reuse_hit) *env.cache_reuse_hit = true;
            if (env.cache_reused_tokens) *env.cache_reused_tokens = static_cast<int>(restored_count);
            if (env.cache_reuse_hits) (*env.cache_reuse_hits)++;
            if (env.cache_reuse_reason) *env.cache_reuse_reason = "persistent_prefix_hit";
            if (env.persistent_prefix_cache_hit) *env.persistent_prefix_cache_hit = true;
            if (env.persistent_prefix_cache_tokens) *env.persistent_prefix_cache_tokens = static_cast<int>(restored_count);
            if (env.persistent_prefix_cache_reason) *env.persistent_prefix_cache_reason = "state_loaded";
            decision.handled = true;
            decision.reused_tokens = restored_count;
            return true;
        }
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "state_token_mismatch";
        }
    } catch (...) {
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "state_load_failed";
        }
    }
    if (env.clear_target_context) {
        env.clear_target_context("persistent_state_load_failed");
    }
    return false;
}

bool PrefixCacheManager::prefill_and_persist_fixed_prefix(
        const PrefixCacheRequest &request,
        const llama_tokens &prefix_tokens,
        PrefixCacheEnvironment &env,
        PrefixCacheDecision &decision) {
    const int prefix_rc = env.decode_tokens ? env.decode_tokens(prefix_tokens) : -1;
    if (prefix_rc != 0 || env.context_shifts != 0) {
        const std::string fail_reason = prefix_rc == 0
                                        ? "prefix_context_shifted"
                                        : "prefix_prefill_failed";
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = fail_reason;
        }
        if (env.clear_target_context) {
            env.clear_target_context(fail_reason);
        }
        return false;
    }

    if (env.persistent_prefix_cache_tokens) {
        *env.persistent_prefix_cache_tokens = static_cast<int>(prefix_tokens.size());
    }
    if (!request.write_state_path.empty()) {
        try {
            const size_t saved = env.save_state ? env.save_state(request.write_state_path, prefix_tokens) : 0;
            if (saved > 0) {
                if (env.persistent_prefix_cache_saved) *env.persistent_prefix_cache_saved = true;
                if (env.persistent_prefix_cache_reason) *env.persistent_prefix_cache_reason = "state_saved";
            } else {
                if (env.persistent_prefix_cache_reason) *env.persistent_prefix_cache_reason = "state_save_failed";
            }
        } catch (...) {
            if (env.persistent_prefix_cache_reason) *env.persistent_prefix_cache_reason = "state_save_failed";
        }
    } else {
        if (env.persistent_prefix_cache_reason) *env.persistent_prefix_cache_reason = "no_write_path";
    }
    decision.handled = true;
    decision.reused_tokens = prefix_tokens.size();
    return true;
}

PrefixCacheDecision PrefixCacheManager::resolve_persistent_prefix_cache_locked(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PrefixCacheRequest &request,
        PrefixCacheEnvironment &env) {
    PrefixCacheDecision decision;
    if (!request.requested) return decision;
    if (env.persistent_prefix_cache_attempted) {
        *env.persistent_prefix_cache_attempted = true;
    }

    if (request.full_session_state) {
        restore_session_state(request, full_tokens, env, decision);
        return decision;
    }

    if (request.fixed_system_prompt.empty() || messages.empty() ||
        to_lower_ascii(messages.front().role) != "system" ||
        messages.front().content.rfind(request.fixed_system_prompt, 0) != 0) {
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "fixed_system_prefix_unavailable";
        }
        return decision;
    }

    std::string probe_formatted;
    if (!find_probe_boundary(messages, chat_options, request.fixed_system_prompt, env, probe_formatted)) {
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "template_boundary_unavailable";
        }
        return decision;
    }

    llama_tokens probe_tokens;
    try {
        if (env.tokenize) {
            probe_tokens = env.tokenize(probe_formatted);
        }
    } catch (...) {
        probe_tokens.clear();
    }
    const size_t stable_prefix_tokens = mca::llama::longestCommonTokenPrefix(
            full_tokens,
            probe_tokens);
    if (stable_prefix_tokens == 0 || stable_prefix_tokens >= full_tokens.size()) {
        if (env.persistent_prefix_cache_reason) {
            *env.persistent_prefix_cache_reason = "token_prefix_mismatch";
        }
        return decision;
    }
    llama_tokens prefix_tokens(
            full_tokens.begin(),
            full_tokens.begin() + static_cast<llama_tokens::difference_type>(stable_prefix_tokens));

    if (env.clear_target_context) {
        env.clear_target_context("persistent_prefix_prepare");
    }
    if (restore_fixed_prefix_state(request.restore_state_path, prefix_tokens, full_tokens, env, decision)) {
        return decision;
    }

    prefill_and_persist_fixed_prefix(request, prefix_tokens, env, decision);
    return decision;
}

PrefixCacheDecision resolve_persistent_prefix_cache_locked(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PrefixCacheRequest &request,
        PrefixCacheEnvironment &env) {
    return PrefixCacheManager::resolve_persistent_prefix_cache_locked(
            messages, chat_options, full_tokens, request, env);
}

} // namespace llm
} // namespace mca
