#pragma once

#include <algorithm>
#include <cstddef>

namespace mca::llama {

enum class PrefixCacheStrategy {
    Disabled,
    DirectTrim,
    PartialStateCheckpoint,
};

inline constexpr PrefixCacheStrategy prefixCacheStrategy(
        bool modelHybrid,
        bool modelRecurrent,
        std::size_t cacheReuseTokens) noexcept {
    if (cacheReuseTokens == 0) {
        return PrefixCacheStrategy::Disabled;
    }
    if (modelHybrid || modelRecurrent) {
        return PrefixCacheStrategy::PartialStateCheckpoint;
    }
    return PrefixCacheStrategy::DirectTrim;
}

inline constexpr const char *prefixCacheStrategyName(PrefixCacheStrategy strategy) noexcept {
    switch (strategy) {
        case PrefixCacheStrategy::Disabled: return "disabled";
        case PrefixCacheStrategy::DirectTrim: return "direct_trim";
        case PrefixCacheStrategy::PartialStateCheckpoint: return "partial_state_checkpoint";
    }
    return "disabled";
}

inline constexpr bool shouldCreatePartialStateCheckpoint(
        PrefixCacheStrategy strategy,
        std::size_t cacheReuseTokens,
        std::size_t promptTokens) noexcept {
    return strategy == PrefixCacheStrategy::PartialStateCheckpoint &&
           cacheReuseTokens > 0 &&
           promptTokens > cacheReuseTokens;
}

inline constexpr bool shouldInvalidateCheckpointForConfigChange(
        bool checkpointValid,
        PrefixCacheStrategy strategy,
        std::size_t checkpointTokens,
        std::size_t cacheReuseTokens) noexcept {
    return checkpointValid &&
           (strategy != PrefixCacheStrategy::PartialStateCheckpoint ||
            checkpointTokens != cacheReuseTokens);
}

inline constexpr bool canPersistPrefixCacheAfterPrefill(
        std::size_t contextShifts) noexcept {
    return contextShifts == 0;
}

inline constexpr bool canAttemptPersistentPrefixCache(
        bool requested,
        bool hasImages,
        bool speculative,
        std::size_t parallelSequences) noexcept {
    return requested && !hasImages && !speculative && parallelSequences == 1;
}

/**
 * A live session KV state contains more of the current conversation than the
 * disk-backed fixed-system-prefix checkpoint. Keep it authoritative whenever
 * its token-prefix validation succeeded; the persistent checkpoint is a cold
 * start fallback, not a per-turn replacement for session state.
 */
inline constexpr bool shouldAttemptPersistentPrefixFallback(
        bool persistentPrefixEligible,
        bool sessionCacheHit) noexcept {
    return persistentPrefixEligible && !sessionCacheHit;
}

inline constexpr bool canRestorePersistentPrefixState(
        std::size_t expectedTokens,
        std::size_t restoredTokens,
        bool restoredTokensMatch,
        bool fullPromptPrefixMatches) noexcept {
    return expectedTokens > 0 &&
           restoredTokens == expectedTokens &&
           restoredTokensMatch &&
           fullPromptPrefixMatches;
}

inline constexpr bool canReusePartialStateCheckpoint(
        PrefixCacheStrategy strategy,
        bool contextStateValid,
        bool checkpointValid,
        std::size_t checkpointTokens,
        std::size_t cacheReuseTokens,
        std::size_t promptTokens,
        bool prefixMatches) noexcept {
    return strategy == PrefixCacheStrategy::PartialStateCheckpoint &&
           contextStateValid &&
           checkpointValid &&
           cacheReuseTokens > 0 &&
           checkpointTokens == cacheReuseTokens &&
           promptTokens > cacheReuseTokens &&
           prefixMatches;
}

template <typename TokenContainer>
inline bool tokenPrefixMatches(
        const TokenContainer &prompt,
        const TokenContainer &checkpointPrefix) {
    return checkpointPrefix.size() <= prompt.size() &&
           std::equal(checkpointPrefix.begin(), checkpointPrefix.end(), prompt.begin());
}

/**
 * Returns only the tokenizer-stable portion shared by two rendered prompts.
 * This is needed when a fixed textual prefix ends at a BPE merge boundary:
 * independently tokenizing the substring can otherwise produce a different
 * final token than tokenizing the full prompt.
 */
template <typename FirstTokenContainer, typename SecondTokenContainer>
inline std::size_t longestCommonTokenPrefix(
        const FirstTokenContainer &first,
        const SecondTokenContainer &second) {
    const std::size_t limit = std::min(first.size(), second.size());
    std::size_t matched = 0;
    while (matched < limit && first[matched] == second[matched]) ++matched;
    return matched;
}

}  // namespace mca::llama
