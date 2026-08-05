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

}  // namespace mca::llama
