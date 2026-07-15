#include "../../main/cpp/llama_prefix_cache_policy.hpp"

#include <cassert>
#include <cstddef>
#include <string_view>
#include <vector>

int main() {
    using mca::llama::PrefixCacheStrategy;

    constexpr std::size_t threshold = 256;
    constexpr std::size_t longPrompt = 425;

    assert(mca::llama::prefixCacheStrategy(false, false, 0) == PrefixCacheStrategy::Disabled);
    assert(mca::llama::prefixCacheStrategy(false, false, threshold) == PrefixCacheStrategy::DirectTrim);
    assert(mca::llama::prefixCacheStrategy(true, false, threshold) == PrefixCacheStrategy::PartialStateCheckpoint);
    assert(mca::llama::prefixCacheStrategy(false, true, threshold) == PrefixCacheStrategy::PartialStateCheckpoint);

    assert(mca::llama::shouldCreatePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint, threshold, longPrompt));
    assert(!mca::llama::shouldCreatePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint, threshold, threshold));
    assert(!mca::llama::shouldCreatePartialStateCheckpoint(
            PrefixCacheStrategy::DirectTrim, threshold, longPrompt));

    std::vector<int> checkpointPrefix(threshold, 7);
    std::vector<int> repeatedPrompt(longPrompt, 7);
    assert(mca::llama::tokenPrefixMatches(repeatedPrompt, checkpointPrefix));

    repeatedPrompt[threshold - 1] = 8;
    assert(!mca::llama::tokenPrefixMatches(repeatedPrompt, checkpointPrefix));

    assert(mca::llama::canReusePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint,
            true,
            true,
            threshold,
            threshold,
            longPrompt,
            true));
    assert(!mca::llama::canReusePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint,
            true,
            true,
            threshold,
            threshold,
            longPrompt,
            false));
    assert(!mca::llama::canReusePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint,
            true,
            true,
            threshold,
            threshold + 1,
            longPrompt,
            true));
    assert(!mca::llama::canReusePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint,
            false,
            true,
            threshold,
            threshold,
            longPrompt,
            true));
    assert(!mca::llama::canReusePartialStateCheckpoint(
            PrefixCacheStrategy::PartialStateCheckpoint,
            true,
            true,
            threshold,
            threshold,
            threshold,
            true));

    assert(mca::llama::shouldInvalidateCheckpointForConfigChange(
            true, PrefixCacheStrategy::PartialStateCheckpoint, threshold, threshold + 1));
    assert(mca::llama::shouldInvalidateCheckpointForConfigChange(
            true, PrefixCacheStrategy::Disabled, threshold, 0));
    assert(!mca::llama::shouldInvalidateCheckpointForConfigChange(
            true, PrefixCacheStrategy::PartialStateCheckpoint, threshold, threshold));

    assert(mca::llama::canPersistPrefixCacheAfterPrefill(0));
    assert(!mca::llama::canPersistPrefixCacheAfterPrefill(1));

    assert(std::string_view(mca::llama::prefixCacheStrategyName(PrefixCacheStrategy::Disabled)) ==
           "disabled");
    assert(std::string_view(mca::llama::prefixCacheStrategyName(PrefixCacheStrategy::DirectTrim)) ==
           "direct_trim");
    assert(std::string_view(mca::llama::prefixCacheStrategyName(
            PrefixCacheStrategy::PartialStateCheckpoint)) == "partial_state_checkpoint");

    return 0;
}
