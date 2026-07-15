#pragma once

#include <cstdint>

namespace mca::llama {

// A failed mmap load of a multi-gigabyte model must not silently fall back to
// allocating/copying the whole tensor file into anonymous memory. Small models
// retain the compatibility retry; large models fail closed with a diagnostic.
constexpr std::uint64_t kMaximumSafeNonMmapRetryBytes = 4ULL * 1024ULL * 1024ULL * 1024ULL;

inline bool shouldRetryModelLoadWithoutMmap(
        bool mmapWasRequested,
        std::uint64_t modelFileBytes) noexcept {
    return mmapWasRequested &&
           modelFileBytes > 0ULL &&
           modelFileBytes <= kMaximumSafeNonMmapRetryBytes;
}

// Upstream llama.cpp normally maps model files with MAP_POPULATE and
// POSIX_MADV_WILLNEED. That is useful for small models, but pre-faulting an
// 11+ GiB sparse-MoE GGUF defeats the low-memory mmap strategy during load.
inline bool shouldPrefetchModelMmap(std::uint64_t modelFileBytes) noexcept {
    return modelFileBytes > 0ULL &&
           modelFileBytes <= kMaximumSafeNonMmapRetryBytes;
}

}  // namespace mca::llama
