#include "../../../../../third_party/MNN/transformers/llm/engine/src/prompt_cache_utils.hpp"
#include <cassert>
#include <numeric>
#include <vector>

int main() {
    using MNN::Transformer::trimPromptTokenHistory;
    using MNN::Transformer::promptCacheReusableTokenPrefix;
    std::vector<int> history(3609);
    std::iota(history.begin(), history.end(), 0);
    const auto original = history;
    trimPromptTokenHistory(history, 3606); // Pending three-token protocol suffix.
    assert(history.size() == 3606);
    trimPromptTokenHistory(history, 3589); // KV still has 3609 physical tokens.
    assert(history.size() == 3589);
    assert(history.back() == 3588);
    trimPromptTokenHistory(history, 3589);
    assert(history.size() == 3589);
    assert(promptCacheReusableTokenPrefix(history, 3589, original) == 3589);
    history.insert(history.end(), 32, 42);
    assert(history.size() == 3621); // Retained prefix + fresh prefill.

    history = {10, 11, 12, 99}; // Sampled EOS may not have entered physical KV.
    trimPromptTokenHistory(history, 2);
    assert((history == std::vector<int>{10, 11}));
    trimPromptTokenHistory(history, 3); // Never regrow an already trimmed vector.
    assert((history == std::vector<int>{10, 11}));
    trimPromptTokenHistory(history, 0);
    assert(history.empty());
    return 0;
}
