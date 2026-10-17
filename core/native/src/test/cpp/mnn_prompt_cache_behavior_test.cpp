// T07 A9-TierA draft: MNN prompt-cache BEHAVIOUR test (drives the real mca::mnn implementations).
//
// DRAFT LOCATION: artifacts/repair/20261009-parity/drafts/ (written during the final APK build window).
// Will be moved to core/native/src/test/cpp/mnn_prompt_cache_behavior_test.cpp and registered by T01.
//
// HONEST SCOPE (printed at runtime so coverage cannot be mistaken):
//   * No real MNN::Transformer::Llm is constructed (no real model available to a host test), so the
//     LLM-dependent paths (llm->syncPromptCache / llm->eraseHistory / real KV accounting) are NOT
//     exercised here. They remain covered by the textual contracts + device evidence, and are
//     reported as NOT_VERIFIED(no real model) rather than faked.
//   * What IS exercised for real: the prompt-cache state machine, the invalidation primitives, the
//     LCP prefix decision, and the published metrics JSON.
#include "mca/mnn/mnn_prompt_cache_manager.hpp"
#include "mca/mnn/mnn_stats_collector.hpp"

#include <cstdio>
#include <string>
#include <utility>
#include <vector>

using mca::mnn::ChatMessage;
using mca::mnn::MnnEngineStatsSnapshot;
using mca::mnn::MnnPromptCacheContext;
using mca::mnn::MnnPromptCacheManager;
using mca::mnn::MnnPromptCacheRuntimeState;

namespace {

int failures = 0;

void expect(bool condition, const std::string& what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

MnnPromptCacheContext make_context(MnnPromptCacheRuntimeState* state) {
    MnnPromptCacheContext ctx;
    ctx.cache = state;
    ctx.llm = nullptr;  // no real model: LLM-dependent paths deliberately not exercised
    return ctx;
}

std::string stats_json(const MnnPromptCacheRuntimeState& state) {
    MnnEngineStatsSnapshot snapshot;
    snapshot.prompt_cache.enabled = state.enabled;
    snapshot.prompt_cache.hit = state.hit;
    snapshot.prompt_cache.reused_tokens = state.reused_tokens;
    snapshot.prompt_cache.prefilled_tokens = state.prefilled_tokens;
    snapshot.prompt_cache.hits = 1;
    snapshot.prompt_cache.misses = 0;
    snapshot.prompt_cache.state = state.state;
    snapshot.prompt_cache.reason = state.reason;
    snapshot.prompt_cache.prefix_extended = state.prefix_extended;
    return mca::mnn::format_mnn_stats_json(snapshot);
}

}  // namespace

int main() {
    std::printf("NOTE: no real MNN Llm is constructed; LLM-dependent cache paths are NOT exercised "
                "and are reported as NOT_VERIFIED(no real model).\n");

    // 1. clear_tracking: the invalidation primitive must fully reset the transaction state.
    {
        MnnPromptCacheRuntimeState state;
        state.reused_tokens = 7;
        state.prefilled_tokens = 11;
        state.hit = true;
        state.committed = true;
        state.prefix_extended = true;
        state.state = "pending_hit";
        MnnPromptCacheManager manager(make_context(&state));

        manager.clear_tracking("explicit_reset");

        expect(state.reused_tokens == 0, "clear_tracking must zero reused_tokens");
        expect(state.prefilled_tokens == 0, "clear_tracking must zero prefilled_tokens");
        expect(!state.hit, "clear_tracking must clear hit");
        expect(!state.committed, "clear_tracking must clear committed");
        expect(!state.prefix_extended, "clear_tracking must clear prefix_extended");
        expect(state.state == "cold", "clear_tracking must return the state machine to cold");
        expect(state.reason == "explicit_reset", "clear_tracking must record the reason");
    }

    // 2. mark_disabled: the disable branch must be observable in the state.
    {
        MnnPromptCacheRuntimeState state;
        state.enabled = true;
        state.hit = true;
        state.reused_tokens = 5;
        MnnPromptCacheManager manager(make_context(&state));

        manager.mark_disabled(false, "multimodal_request_requires_fresh_visual_prefill");

        expect(!state.enabled, "mark_disabled must disable the cache");
        expect(!state.last_request_multimodal, "mark_disabled(false) must record a text request");
        expect(!state.hit, "mark_disabled must clear the hit flag");

        MnnPromptCacheRuntimeState multimodal;
        MnnPromptCacheManager multimodal_manager(make_context(&multimodal));
        multimodal_manager.mark_disabled(true, "multimodal_request_requires_fresh_visual_prefill");
        expect(multimodal.last_request_multimodal,
               "mark_disabled(true) must record a multimodal request");
        expect(!multimodal.enabled, "mark_disabled(true) must still disable the cache");
    }

    // 3. reset_native_cache without an Llm must be a safe no-op (documented null-llm contract).
    {
        MnnPromptCacheRuntimeState state;
        state.committed = true;
        MnnPromptCacheManager manager(make_context(&state));
        manager.reset_native_cache();  // llm == nullptr
        expect(state.committed, "reset_native_cache must not fabricate or destroy cache accounting "
                                "without an Llm (no crash, state untouched)");
    }

    // 4. metrics: the published JSON must carry the real counter values, not placeholders.
    {
        MnnPromptCacheRuntimeState state;
        state.enabled = true;
        state.hit = true;
        state.reused_tokens = 7;
        state.prefilled_tokens = 11;
        state.state = "hit";
        state.reason = "prefix_candidate";
        const std::string json = stats_json(state);
        for (const char* expected : {
                     "\"promptCacheReusedTokens\":7",
                     "\"promptCachePrefillTokens\":11",
                     "\"promptCacheHit\":true",
                     "\"promptCacheState\":\"hit\"",
                     "\"promptCacheReason\":\"prefix_candidate\"",
                     "\"cacheReuse\":",
             }) {
            expect(json.find(expected) != std::string::npos,
                   std::string("metrics must publish the real value ") + expected);
        }

        MnnPromptCacheRuntimeState cold;
        const std::string cold_json = stats_json(cold);
        expect(cold_json.find("\"promptCacheHit\":false") != std::string::npos,
               "cold metrics must publish hit=false");
        expect(cold_json.find("\"promptCacheReusedTokens\":0") != std::string::npos,
               "cold metrics must publish reusedTokens=0");
    }

    // 5. LCP prefix decision: a genuine extension must be recognised, a changed prefix must not.
    //    This is the reuse/non-reuse decision the cache makes before any Llm is involved.
    {
        const std::vector<ChatMessage> prefix = {{"user", "hello"}, {"assistant", "hi"}};
        const std::vector<ChatMessage> extended = {{"user", "hello"}, {"assistant", "hi"},
                                                  {"user", "next"}};
        const std::vector<ChatMessage> changed = {{"user", "HELLO"}, {"assistant", "hi"},
                                                 {"user", "next"}};
        expect(mca::mnn::mnn_chat_messages_prefix(prefix, extended),
               "an unchanged committed prefix followed by a new turn must count as an extension");
        expect(!mca::mnn::mnn_chat_messages_prefix(prefix, changed),
               "a changed first turn must NOT count as a prefix extension");
        expect(!mca::mnn::mnn_chat_messages_prefix(prefix, {}),
               "an empty continuation must not count as a prefix extension");
    }

    if (failures != 0) {
        std::fprintf(stderr, "mnn_prompt_cache_behavior_test: %d assertion(s) failed\n", failures);
        return 1;
    }
    std::printf("mnn_prompt_cache_behavior_test: OK\n");
    return 0;
}
