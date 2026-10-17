#include "mca/mnn/mnn_prompt_cache_manager.hpp"

#include <algorithm>
#include <limits>
#include <utility>

#if (defined(MCA_WITH_MNN_LLM) && MCA_WITH_MNN_LLM) || __has_include(<llm/llm.hpp>) || __has_include("llm/llm.hpp")
#define MCA_MNN_PROMPT_CACHE_HAS_LLM 1
#if __has_include(<llm/llm.hpp>)
#include <llm/llm.hpp>
#elif __has_include("llm/llm.hpp")
#include "llm/llm.hpp"
#endif
#else
#define MCA_MNN_PROMPT_CACHE_HAS_LLM 0
#endif

namespace mca {
namespace mnn {

void MnnPromptCacheManager::clear_tracking(
        MnnPromptCacheRuntimeState& cache,
        const std::string& reason) {
    cache = MnnPromptCacheRuntimeState();
    cache.state = "cold";
    cache.reason = reason;
}

void MnnPromptCacheManager::clear_tracking(const std::string& reason) {
    if (ctx_.cache != nullptr) {
        clear_tracking(*ctx_.cache, reason);
    }
}

void MnnPromptCacheManager::reset_native_cache(
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle) {
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (llm == nullptr) return;
    // reset() clears both the vendor prompt text and the live KV sequence. It
    // is used only for a text request after an invalidated session, or when a
    // transaction cannot be rolled back to a known committed prefix. Never
    // call it immediately before a multimodal response: Qwen visual exports
    // have a separate executor lifetime contract.
    llm->reset();
    if (lifecycle != nullptr) {
        lifecycle->lastRequestReset = true;
    }
#else
    (void)llm;
    (void)lifecycle;
#endif
}

void MnnPromptCacheManager::reset_native_cache() {
    reset_native_cache(ctx_.llm, ctx_.lifecycle);
}

size_t MnnPromptCacheManager::effective_kv_history(const MNN::Transformer::Llm* llm) {
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (llm == nullptr || llm->getContext() == nullptr) return 0;
    // `all_seq_len` is the logical sequence length and can temporarily lead or
    // lag the backend while OpenCL commits a chunk or applies a lazy rollback.
    // The vendor's committed KV counter is the only safe basis for cache-hit
    // accounting and for deciding whether stale history must be cleared.
    return llm->getCurrentHistory();
#else
    (void)llm;
    return 0;
#endif
}

size_t MnnPromptCacheManager::effective_kv_history() const {
    return effective_kv_history(ctx_.llm);
}

std::string MnnPromptCacheManager::assistant_text(
        MNN::Transformer::Llm* llm,
        const MnnPromptCacheRuntimeState& cache,
        const std::vector<std::string>& active_stop_markers) {
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (llm == nullptr || llm->getContext() == nullptr) return {};
    const auto* context = llm->getContext();
    const size_t start = std::min(
            cache.generation_history_start,
            context->history_tokens.size());
    std::string response;
    for (size_t index = start; index < context->history_tokens.size(); ++index) {
        const int token = context->history_tokens[index];
        if (llm->is_stop(token)) continue;
        response += llm->tokenizer_decode(token);
    }
    // The stream path filters protocol framing before exposing text to Kotlin.
    // Re-run that exact filter on the decoded token transcript before storing
    // the assistant turn.  OpenCL/Qwen exports can emit a plain `user` line
    // after a valid answer instead of a tokenizer EOS; committing the raw
    // suffix makes the next ChatMessages prefix differ from what the caller
    // sends back and turns every follow-up into a full prefill.  A temporary
    // filter is intentional: the live stream filter is already stopped and
    // must not be mutated while its terminal chunk is being drained.
    StreamProtocolFilterState cacheFilter;
    cacheFilter.reset(active_stop_markers);
    auto filtered = filter_stream_protocol(
            cacheFilter,
            std::move(response),
            true);
    return std::move(filtered.visible);
#else
    (void)llm;
    (void)cache;
    (void)active_stop_markers;
    return {};
#endif
}

std::string MnnPromptCacheManager::assistant_text() const {
    if (ctx_.cache == nullptr) return {};
    static const std::vector<std::string> kEmptyMarkers;
    const auto& markers = ctx_.active_stop_markers != nullptr ? *ctx_.active_stop_markers : kEmptyMarkers;
    return assistant_text(ctx_.llm, *ctx_.cache, markers);
}

bool MnnPromptCacheManager::is_role_protocol_stop_reason(const std::string& reason) {
    return reason == "unexpected_role_token" ||
            reason == "plain_role_header_after_visible_text" ||
            reason == "role_header_after_visible_text" ||
            reason == "xml_response_end" ||
            reason == "xml_response_start_after_visible_text";
}

void MnnPromptCacheManager::trim_protocol_suffix_from_live_history(
        MNN::Transformer::Llm* llm,
        const MnnPromptCacheRuntimeState& cache,
        const std::string& generation_stop_reason,
        const std::vector<std::string>& active_stop_markers) {
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (!is_role_protocol_stop_reason(generation_stop_reason) ||
            llm == nullptr || llm->getContext() == nullptr) {
        return;
    }
    const auto* context = llm->getContext();
    const size_t generationStart = std::min(
            cache.generation_history_start,
            context->history_tokens.size());
    if (generationStart >= context->history_tokens.size()) return;

    // Locate the first byte of the role marker in the decoded completion and
    // map it back to its owning token.  This handles a marker split over
    // multiple stream writes/tokens while preserving all valid answer tokens.
    std::string decodedCompletion;
    std::vector<std::pair<size_t, size_t>> tokenRanges;
    tokenRanges.reserve(context->history_tokens.size() - generationStart);
    StreamProtocolFilterState filter;
    filter.reset(active_stop_markers);
    for (size_t index = generationStart; index < context->history_tokens.size(); ++index) {
        const auto piece = llm->tokenizer_decode(context->history_tokens[index]);
        const size_t begin = decodedCompletion.size();
        decodedCompletion.append(piece);
        tokenRanges.emplace_back(begin, decodedCompletion.size());
        const auto result = filter_stream_protocol(filter, piece, false);
        if (!result.stopped || filter.stop_reason != generation_stop_reason) continue;

        size_t markerPosition = std::string::npos;
        if (generation_stop_reason == "plain_role_header_after_visible_text") {
            markerPosition = detail::first_plain_role_line(decodedCompletion).position;
        } else if (generation_stop_reason == "unexpected_role_token") {
            markerPosition = detail::first_terminal_marker(
                    decodedCompletion,
                    unsafe_role_tokens()).position;
        } else if (generation_stop_reason == "xml_response_end") {
            markerPosition = filter.stop_marker_offset;
        } else if (generation_stop_reason == "xml_response_start_after_visible_text") {
            markerPosition = detail::first_terminal_marker(
                    decodedCompletion,
                    xml_protocol_start_markers()).position;
        } else {
            markerPosition = detail::first_role_header(decodedCompletion).position;
        }

        size_t trimToken = index;
        if (markerPosition != std::string::npos) {
            for (size_t rangeIndex = 0; rangeIndex < tokenRanges.size(); ++rangeIndex) {
                const auto [rangeBegin, rangeEnd] = tokenRanges[rangeIndex];
                if (markerPosition >= rangeBegin && markerPosition < rangeEnd) {
                    trimToken = generationStart + rangeIndex;
                    break;
                }
            }
        }
        // History is token-granular.  If the protocol marker shares a token
        // with visible answer text (a common tokenizer merge such as
        // "answer</response>"), erasing that token would also erase the last
        // visible fragment from the committed transcript.  Keep the token in
        // live KV; the next prompt-cache LCP pass will trim the protocol
        // suffix while preserving the complete caller-provided answer.
        bool markerStartsAtTokenBoundary = markerPosition == std::string::npos;
        if (markerPosition != std::string::npos && trimToken < tokenRanges.size() + generationStart) {
            const auto rangeBegin = tokenRanges[trimToken - generationStart].first;
            markerStartsAtTokenBoundary = markerPosition == rangeBegin;
        }
        if (markerStartsAtTokenBoundary &&
                trimToken > generationStart && trimToken < llm->getCurrentHistory()) {
            // eraseHistory() schedules the backend removal and also truncates
            // history_tokens for a plain suffix removal. The next prefill then
            // commits the exact visible-prefix state before token-LCP reuse.
            llm->eraseHistory(trimToken, 0);
            auto* mutableContext = const_cast<MNN::Transformer::LlmContext*>(context);
            if (mutableContext->history_tokens.size() > trimToken) {
                mutableContext->history_tokens.resize(trimToken);
            }
        }
        return;
    }
#else
    (void)llm;
    (void)cache;
    (void)generation_stop_reason;
    (void)active_stop_markers;
#endif
}

void MnnPromptCacheManager::trim_protocol_suffix_from_live_history() {
    if (ctx_.cache == nullptr) return;
    static const std::string kEmptyReason;
    static const std::vector<std::string> kEmptyMarkers;
    const auto& stop_reason = ctx_.generation_stop_reason != nullptr ? *ctx_.generation_stop_reason : kEmptyReason;
    const auto& markers = ctx_.active_stop_markers != nullptr ? *ctx_.active_stop_markers : kEmptyMarkers;
    trim_protocol_suffix_from_live_history(ctx_.llm, *ctx_.cache, stop_reason, markers);
}

void MnnPromptCacheManager::mark_disabled(
        MnnPromptCacheRuntimeState& cache,
        bool multimodal,
        const std::string& reason) {
    cache = MnnPromptCacheRuntimeState();
    cache.enabled = false;
    cache.last_request_multimodal = multimodal;
    cache.reset_before_next_text = multimodal;
    cache.state = multimodal ? "disabled_multimodal" : "disabled";
    cache.reason = reason;
}

void MnnPromptCacheManager::mark_disabled(bool multimodal, const std::string& reason) {
    if (ctx_.cache != nullptr) {
        mark_disabled(*ctx_.cache, multimodal, reason);
    }
}

void MnnPromptCacheManager::prepare_text_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle,
        std::vector<MNN::Transformer::ChatMessage>& messages) {
    if (cache.reset_before_next_text) {
        reset_native_cache(llm, lifecycle);
        cache.committed_messages.clear();
        cache.reset_before_next_text = false;
        cache.state = "cleared_after_invalidation";
    }

    bool hasCommittedTranscript = !cache.committed_messages.empty();
    bool extendsCommittedTranscript = hasCommittedTranscript &&
            mnn_chat_messages_prefix(cache.committed_messages, messages);
    // A caller may legitimately start a new conversation on the same loaded
    // MNN session.  Keeping the previous committed transcript in that case
    // makes the vendor's live KV look reusable even though the new request is
    // not its prefix; the next decode can then stop on a stale role marker.
    // Clear only this mismatched session.  Matching prefixes retain the normal
    // prompt-cache path and its longest-common-prefix reuse.
    if (hasCommittedTranscript && !extendsCommittedTranscript) {
        reset_native_cache(llm, lifecycle);
        cache.committed_messages.clear();
        hasCommittedTranscript = false;
        extendsCommittedTranscript = false;
    }
    if (extendsCommittedTranscript) {
        restore_mnn_committed_assistant_text_locked(
                cache.committed_messages,
                messages);
    }
    const size_t currentHistory = effective_kv_history(llm);
    // A live KV sequence without a matching transcript is never eligible for
    // reuse. This can occur after an interrupted multimodal request or an
    // external clear-history action; clear it before accepting a text turn.
    if (!hasCommittedTranscript && currentHistory > 0) {
        reset_native_cache(llm, lifecycle);
    }

    cache.request_messages = messages;
    cache.kv_history_before = effective_kv_history(llm);
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    cache.token_history_before =
            llm != nullptr && llm->getContext() != nullptr
            ? llm->getContext()->history_tokens.size()
            : 0;
#else
    cache.token_history_before = 0;
#endif
    cache.token_history_after_prefill = 0;
    cache.generation_history_start = 0;
    cache.prefilled_tokens = 0;
    cache.reused_tokens = 0;
    cache.request_active = true;
    cache.completion_tokens_before_rollback = -1;
    cache.enabled = true;
    cache.hit = false;
    cache.committed = false;
    cache.rolled_back = false;
    cache.prefix_extended = extendsCommittedTranscript;
    cache.last_request_multimodal = false;
    cache.state = extendsCommittedTranscript ? "pending_hit" : "pending_miss";
    cache.reason = extendsCommittedTranscript ? "prefix_candidate" : "cold_or_prefix_changed";
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (extendsCommittedTranscript && llm != nullptr) {
        // Align the vendor's rendered-text eligibility guard with the persisted transcript.
        // Its live token LCP still trims at the first real token mismatch, so this cannot reuse
        // stale KV when normalization removed a presentation-only character.
        // Keep the cached text anchored to the last committed turn.  `messages`
        // already contains the new user turn and must remain the pending delta;
        // syncing it here would make the vendor believe that delta was committed
        // before prefill, defeating the next-turn LCP calculation.
        llm->syncPromptCache(cache.committed_messages);
    }
#endif
}

void MnnPromptCacheManager::prepare_text_cache(std::vector<MNN::Transformer::ChatMessage>& messages) {
    if (ctx_.cache != nullptr) {
        prepare_text_cache(*ctx_.cache, ctx_.llm, ctx_.lifecycle, messages);
    }
}

bool MnnPromptCacheManager::capture_text_prefill(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        std::atomic<int>* prefill_total_tokens,
        std::atomic<int>* prefill_completed_tokens,
        long long* prompt_cache_hits,
        long long* prompt_cache_misses,
        std::string& error) {
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (llm == nullptr || llm->getContext() == nullptr) {
        error = "MNN text prefill produced no context.";
        return false;
    }
    const auto* context = llm->getContext();
    using MNN::Transformer::LlmStatus;
    if (context->status == LlmStatus::INTERNAL_ERROR ||
            context->status == LlmStatus::TIMEOUT ||
            context->status == LlmStatus::NOT_LOADED) {
        error = "MNN text prefill failed before decode.";
        const std::string mnnLog = mnn_context_log_snapshot(context);
        if (!mnnLog.empty()) {
            error += " MNN log: " + mnnLog;
        }
        error += " 当前 MNN 模型与运行时可能不兼容或模型文件不完整，请重新导出模型或改用 GGUF 兼容引擎。";
        return false;
    }
    cache.token_history_after_prefill = context->history_tokens.size();
    cache.generation_history_start = context->history_tokens.size();
    cache.prefilled_tokens = std::max(0, context->prompt_len);
    const size_t effectiveHistory = effective_kv_history(llm);
    const size_t retained = effectiveHistory >
                    static_cast<size_t>(cache.prefilled_tokens)
            ? effectiveHistory -
                    static_cast<size_t>(cache.prefilled_tokens)
            : 0;
    cache.reused_tokens = static_cast<int>(std::min<size_t>(
            retained, static_cast<size_t>(std::numeric_limits<int>::max())));
    cache.hit = cache.reused_tokens > 0;
    // Report the effective prompt total (reused + freshly prefilled) once it is
    // known; completed jumps to total because MNN exposes no intermediate batches.
    const int mnn_prompt_total = cache.prefilled_tokens + cache.reused_tokens;
    if (mnn_prompt_total > 0) {
        if (prefill_total_tokens != nullptr) {
            prefill_total_tokens->store(mnn_prompt_total, std::memory_order_release);
        }
        if (prefill_completed_tokens != nullptr) {
            prefill_completed_tokens->store(mnn_prompt_total, std::memory_order_release);
        }
    }
    cache.state = cache.hit ? "hit" : "miss";
    cache.reason = cache.hit
            ? "native_history_retained"
            : (cache.prefix_extended ? "prefix_not_reused" : "cold_or_prefix_changed");
    if (cache.hit) {
        if (prompt_cache_hits != nullptr) ++(*prompt_cache_hits);
    } else {
        if (prompt_cache_misses != nullptr) ++(*prompt_cache_misses);
    }
    return true;
#else
    (void)cache;
    (void)llm;
    (void)prefill_total_tokens;
    (void)prefill_completed_tokens;
    (void)prompt_cache_hits;
    (void)prompt_cache_misses;
    error = "MCA built without MNN LLM.";
    return false;
#endif
}

bool MnnPromptCacheManager::capture_text_prefill(std::string& error) {
    if (ctx_.cache == nullptr) {
        error = "No prompt cache state.";
        return false;
    }
    return capture_text_prefill(
            *ctx_.cache,
            ctx_.llm,
            ctx_.prefill_total_tokens,
            ctx_.prefill_completed_tokens,
            ctx_.hits,
            ctx_.misses,
            error);
}

void MnnPromptCacheManager::commit_text_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        const std::string& generation_stop_reason,
        const std::vector<std::string>& active_stop_markers,
        const std::string& reason) {
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (!cache.request_active || !cache.enabled || llm == nullptr) {
        return;
    }
    trim_protocol_suffix_from_live_history(llm, cache, generation_stop_reason, active_stop_markers);
    auto committed = cache.request_messages;
    const auto response = assistant_text(llm, cache, active_stop_markers);
    if (!response.empty()) {
        committed.emplace_back("assistant", response);
    }
    llm->syncPromptCache(committed);
    cache.committed_messages = std::move(committed);
    cache.request_messages.clear();
    cache.request_active = false;
    cache.committed = true;
    cache.rolled_back = false;
    cache.state = cache.hit ? "committed_hit" : "committed_miss";
    // Keep cache diagnostics about cache eligibility, while the native
    // generation stop reason remains available separately in
    // `generationStopReason`.  In particular, a successful hit that ended
    // on a plain role line must not be reported as a cache failure merely
    // because that role line was the model's protocol terminator.
    cache.reason = cache.hit
            ? "native_history_retained"
            : (cache.prefix_extended ? "prefix_not_reused" : reason);
#else
    (void)cache;
    (void)llm;
    (void)generation_stop_reason;
    (void)active_stop_markers;
    (void)reason;
#endif
}

void MnnPromptCacheManager::commit_text_cache(const std::string& reason) {
    if (ctx_.cache == nullptr) return;
    static const std::string kEmptyReason;
    static const std::vector<std::string> kEmptyMarkers;
    const auto& stop_reason = ctx_.generation_stop_reason != nullptr ? *ctx_.generation_stop_reason : kEmptyReason;
    const auto& markers = ctx_.active_stop_markers != nullptr ? *ctx_.active_stop_markers : kEmptyMarkers;
    commit_text_cache(*ctx_.cache, ctx_.llm, stop_reason, markers, reason);
}

void MnnPromptCacheManager::rollback_text_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle,
        const std::string& reason,
        bool forceClear) {
    if (!cache.request_active) return;
    bool restored = false;
    const bool canRestoreCommittedPrefix = !forceClear &&
            cache.prefix_extended &&
            cache.kv_history_before > 0 &&
            !cache.committed_messages.empty() &&
            llm != nullptr;
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (canRestoreCommittedPrefix) {
        const size_t currentHistory = effective_kv_history(llm);
        if (currentHistory >= cache.kv_history_before) {
            if (currentHistory > cache.kv_history_before) {
                llm->eraseHistory(cache.kv_history_before, 0);
            }
            llm->syncPromptCache(cache.committed_messages);
            restored = true;
        }
    }
#endif
    if (!restored) {
        // reset clears live history and gen_seq_len; retain completed request
        // accounting so rejected output is not reported as zero generated tokens.
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
        cache.completion_tokens_before_rollback = llm != nullptr && llm->getContext() != nullptr
                ? llm->getContext()->gen_seq_len : 0;
#else
        cache.completion_tokens_before_rollback = 0;
#endif
        reset_native_cache(llm, lifecycle);
        cache.committed_messages.clear();
        cache.reset_before_next_text = false;
    }
    cache.request_messages.clear();
    cache.request_active = false;
    cache.committed = false;
    cache.rolled_back = true;
    cache.state = restored ? "rolled_back" : "cleared_after_rollback";
    cache.reason = reason;
}

void MnnPromptCacheManager::rollback_text_cache(const std::string& reason, bool forceClear) {
    if (ctx_.cache == nullptr) return;
    rollback_text_cache(*ctx_.cache, ctx_.llm, ctx_.lifecycle, reason, forceClear);
}

void MnnPromptCacheManager::settle_text_cache(
        MnnPromptCacheRuntimeState& cache,
        MNN::Transformer::Llm* llm,
        MnnRequestLifecyclePolicy* lifecycle,
        const std::string& generation_stop_reason,
        const std::vector<std::string>& active_stop_markers,
        bool stop_requested,
        const std::string& reason) {
    if (!cache.request_active) return;
#if MCA_MNN_PROMPT_CACHE_HAS_LLM
    if (stop_requested || llm == nullptr || llm->getContext() == nullptr) {
        rollback_text_cache(cache, llm, lifecycle, reason, true);
        return;
    }
    using MNN::Transformer::LlmStatus;
    const auto status = llm->getContext()->status;
    if (status == LlmStatus::USER_CANCEL || status == LlmStatus::INTERNAL_ERROR ||
            status == LlmStatus::TIMEOUT || status == LlmStatus::NOT_LOADED) {
        rollback_text_cache(cache, llm, lifecycle, reason, true);
        return;
    }
    if (!detail::has_non_whitespace(assistant_text(llm, cache, active_stop_markers))) {
        rollback_text_cache(cache, llm, lifecycle, "empty_visible_response", true);
        return;
    }
    commit_text_cache(cache, llm, generation_stop_reason, active_stop_markers, reason);
#else
    (void)active_stop_markers;
    (void)generation_stop_reason;
    (void)stop_requested;
    rollback_text_cache(cache, llm, lifecycle, reason, true);
#endif
}

void MnnPromptCacheManager::settle_text_cache(const std::string& reason) {
    if (ctx_.cache == nullptr) return;
    static const std::string kEmptyReason;
    static const std::vector<std::string> kEmptyMarkers;
    const auto& stop_reason = ctx_.generation_stop_reason != nullptr ? *ctx_.generation_stop_reason : kEmptyReason;
    const auto& markers = ctx_.active_stop_markers != nullptr ? *ctx_.active_stop_markers : kEmptyMarkers;
    const bool stop_req = ctx_.stop_requested != nullptr && *ctx_.stop_requested;
    settle_text_cache(*ctx_.cache, ctx_.llm, ctx_.lifecycle, stop_reason, markers, stop_req, reason);
}

} // namespace mnn
} // namespace mca
