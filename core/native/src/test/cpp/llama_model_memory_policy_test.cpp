#include "../../main/cpp/llama_model_memory_policy.hpp"

#include <cassert>

int main() {
    using mca::llama::kMaximumSafeNonMmapRetryBytes;
    using mca::llama::shouldPrefetchModelMmap;
    using mca::llama::shouldRetryModelLoadWithoutMmap;

    assert(!shouldRetryModelLoadWithoutMmap(false, 1024));
    assert(!shouldRetryModelLoadWithoutMmap(true, 0));
    assert(shouldRetryModelLoadWithoutMmap(true, 1));
    assert(shouldRetryModelLoadWithoutMmap(true, kMaximumSafeNonMmapRetryBytes - 1));
    assert(shouldRetryModelLoadWithoutMmap(true, kMaximumSafeNonMmapRetryBytes));
    assert(!shouldRetryModelLoadWithoutMmap(true, kMaximumSafeNonMmapRetryBytes + 1));
    assert(!shouldRetryModelLoadWithoutMmap(true, 11'686'646'144ULL));
    assert(!shouldRetryModelLoadWithoutMmap(true, UINT64_MAX));
    assert(!shouldPrefetchModelMmap(0));
    assert(shouldPrefetchModelMmap(kMaximumSafeNonMmapRetryBytes));
    assert(!shouldPrefetchModelMmap(kMaximumSafeNonMmapRetryBytes + 1));
    assert(!shouldPrefetchModelMmap(11'686'646'144ULL));
    assert(!shouldPrefetchModelMmap(UINT64_MAX));
    return 0;
}
