// MCA builds upstream llama.cpp unchanged as a Git submodule. This narrow
// translation-unit overlay changes only the argument at the model tensor mmap
// call site, keeping the upstream source tree clean and reproducible.
#include "llama-model-loader.h"

#include <atomic>

namespace {
std::atomic_bool g_mca_model_mmap_prefetch_enabled{false};
}

extern "C" LLAMA_API void mca_llama_set_model_mmap_prefetch_enabled(bool enabled) noexcept;
extern "C" LLAMA_API bool mca_llama_model_mmap_prefetch_enabled() noexcept;

extern "C" LLAMA_API void mca_llama_set_model_mmap_prefetch_enabled(bool enabled) noexcept {
    g_mca_model_mmap_prefetch_enabled.store(enabled, std::memory_order_relaxed);
}

extern "C" LLAMA_API bool mca_llama_model_mmap_prefetch_enabled() noexcept {
    return g_mca_model_mmap_prefetch_enabled.load(std::memory_order_relaxed);
}

// llama-model.cpp has one init_mappings call. Its header was included above so
// this source-only macro cannot alter the method declaration.
#define init_mappings(prefetch, mlocks) \
    init_mappings((prefetch) && mca_llama_model_mmap_prefetch_enabled(), (mlocks))
#include "../../../../../third_party/llama.cpp/src/llama-model.cpp"
#undef init_mappings
