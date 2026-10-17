// gguf_loader_mmap.cpp - T2B19 / R7-C8 split TU (station A).
// Anonymous-namespace mmap machinery (buffer iface + callbacks, GPU mmap
// side-map, env switches) and the PLAN #60g advise helper.
// Relocated byte-exact from core/gguf_loader.cpp [167-173] [271-451] [452]
// [1167-1203]; the 7 registered rewrites drop `static` on the definitions
// promoted to core_gguf::detail (a-SPLIT-PLAN.json::promotions). The
// g_gpu_mmap_mu / g_gpu_mmap registry stays single-instance in this TU.

#include "gguf_loader_internal.hpp"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>

namespace core_gguf {
namespace detail {


// ---------------------------------------------------------------------------
// Pass 2: tensor allocation + weight data copy.
// ---------------------------------------------------------------------------

namespace {

static void* mmap_buffer_get_base(ggml_backend_buffer_t buffer) {
    GGML_ASSERT(buffer);
    return ((mmap_buffer_ctx*)buffer->context)->tensor_base;
}

static void mmap_buffer_free(ggml_backend_buffer_t buffer) {
    GGML_ASSERT(buffer);
    auto* mctx = (mmap_buffer_ctx*)buffer->context;
    if (mctx->mmap_base) {
#if defined(_WIN32)
        UnmapViewOfFile(mctx->mmap_base);
#else
        ::munmap(mctx->mmap_base, mctx->mmap_size);
#endif
    }
    delete mctx;
}

static void mmap_buffer_memset_tensor(ggml_backend_buffer_t buffer, ggml_tensor* tensor, uint8_t value, size_t offset,
                                      size_t size) {
    GGML_ASSERT(tensor);
    memset((char*)tensor->data + offset, value, size);
    GGML_UNUSED(buffer);
}

static void mmap_buffer_set_tensor(ggml_backend_buffer_t buffer, ggml_tensor* tensor, const void* data, size_t offset,
                                   size_t size) {
    GGML_ASSERT(tensor);
    memcpy((char*)tensor->data + offset, data, size);
    GGML_UNUSED(buffer);
}

static void mmap_buffer_get_tensor(ggml_backend_buffer_t buffer, const ggml_tensor* tensor, void* data, size_t offset,
                                   size_t size) {
    GGML_ASSERT(tensor);
    memcpy(data, (const char*)tensor->data + offset, size);
    GGML_UNUSED(buffer);
}

static bool mmap_buffer_cpy_tensor(ggml_backend_buffer_t buffer, const ggml_tensor* src, ggml_tensor* dst) {
    GGML_ASSERT(src);
    if (ggml_backend_buffer_is_host(src->buffer)) {
        memcpy(dst->data, src->data, ggml_nbytes(src));
        return true;
    }
    return false;
    GGML_UNUSED(buffer);
}

static void mmap_buffer_clear(ggml_backend_buffer_t buffer, uint8_t value) {
    auto* mctx = (mmap_buffer_ctx*)buffer->context;
    memset(mctx->tensor_base, value, buffer->size);
}

} // namespace
const ggml_backend_buffer_i mmap_buffer_iface = {
    /* .free_buffer    = */ mmap_buffer_free,
    /* .get_base       = */ mmap_buffer_get_base,
    /* .init_tensor    = */ nullptr,
    /* .memset_tensor  = */ mmap_buffer_memset_tensor,
    /* .set_tensor     = */ mmap_buffer_set_tensor,
    /* .get_tensor     = */ mmap_buffer_get_tensor,
    /* .set_tensor_2d  = */ nullptr,
    /* .get_tensor_2d  = */ nullptr,
    /* .cpy_tensor     = */ mmap_buffer_cpy_tensor,
    /* .clear          = */ mmap_buffer_clear,
    /* .reset          = */ nullptr,
};

namespace {
// PLAN #51a (Metal variant): non-CPU backends use `buffer_from_host_ptr`
// to wrap our mmap region directly into a backend buffer (e.g. an
// MTLResourceStorageModeShared MTLBuffer on Apple-Silicon Metal). We
// CANNOT wrap that inner buffer with our own iface to attach a munmap
// callback: ggml-metal pierces the iface abstraction and casts
// `buffer->context` straight to its private `ggml_metal_buffer_t` (see
// `ggml_metal_get_buffer_id` in ggml-metal-context.m), so any wrapper
// makes Metal read garbage and emit "tensor 'X' buffer is nil" for
// every weight — the kokoro Metal gibberish-audio regression.
//
// Instead we hand the inner buffer back as-is and track the mmap region
// in this static side-map. When the buffer is freed elsewhere (model
// shutdown) the inner backend's free callback releases its device-side
// reference, but the host mmap stays mapped — Metal's
// `newBufferWithBytesNoCopy:options:deallocator:nil` doesn't own the
// host pages, so there's no MTLBuffer-side teardown that could munmap.
// We deliberately leak the mmap; on macOS the kernel can still evict
// file-backed pages under pressure (they're not anonymous), and process
// exit reclaims everything. Address-space-wise this costs nothing past
// the model's working set, which we'd be holding anyway.
struct gpu_mmap_handle {
    void* base = nullptr;
    size_t size = 0;
};
static std::mutex g_gpu_mmap_mu;
static std::map<ggml_backend_buffer_t, gpu_mmap_handle> g_gpu_mmap;
} // namespace

void register_gpu_mmap(ggml_backend_buffer_t buf, void* base, size_t size) {
    std::lock_guard<std::mutex> lk(g_gpu_mmap_mu);
    g_gpu_mmap[buf] = {base, size};
}
namespace {
static gpu_mmap_handle lookup_gpu_mmap(ggml_backend_buffer_t buf) {
    std::lock_guard<std::mutex> lk(g_gpu_mmap_mu);
    auto it = g_gpu_mmap.find(buf);
    return it != g_gpu_mmap.end() ? it->second : gpu_mmap_handle{};
}
} // namespace

// Issue #94 (chatterbox-turbo segfault during init on macOS / Apple
// Silicon): the legacy alloc+copy load path takes 30-60 s for the
// chatterbox-turbo T3 (658 MB Q8_0) on slow disks and reproducibly
// fails for some users. The zero-copy mmap path completes the same
// load in ~5-10 s and uses half the peak RSS. The CPU mmap path has
// been validated for every backend that goes through this loader
// (mimo-asr, voxtral, voxtral4b, chatterbox base + turbo, kokoro,
// qwen3-tts, vibevoice, parakeet, granite, …) since the PLAN #51a
// rollout in late April; flipping the default after a month of opt-in
// testing matches llama.cpp's behaviour and resolves the slow-load
// reports.
//
// Opt out with `CRISPASR_GGUF_MMAP=0` for users whose model files live
// on volumes that may disappear mid-run (network mounts, removable
// disks); mmap-backed weights SIGBUS if the underlying file vanishes.
bool mmap_loader_enabled() {
    const char* v = std::getenv("CRISPASR_GGUF_MMAP");
    if (!v || !*v)
        return true;
    return *v != '0';
}

// PLAN #60c: opt-in preload — page-walk the entire mmap region so every
// page is resident before we return. Trades cold-start *load* time for
// cold-start *prefill* time; useful for benchmarking and for users with
// enough RAM to keep the working set resident. POSIX path uses a 1-byte
// volatile read per page; Linux MADV_POPULATE_READ would be a cleaner
// single-syscall version when available.
bool preload_enabled() {
    const char* v = std::getenv("CRISPASR_GGUF_PRELOAD");
    return v && *v && *v != '0';
}
void preload_pages(void* base, size_t size) {
#if !defined(_WIN32)
    const long pg = sysconf(_SC_PAGESIZE);
    if (pg <= 0)
        return;
    volatile const unsigned char* p = (const unsigned char*)base;
    size_t touched = 0;
    for (size_t off = 0; off < size; off += (size_t)pg) {
        (void)p[off];
        touched++;
    }
    (void)touched;
#else
    (void)base;
    (void)size;
#endif
}

// PLAN #60f: opt-in mlock — pin the mmap region in physical RAM so the
// kernel can't evict pages under memory pressure. Risky on RAM-tight
// hosts (a 16 GB model on a 16 GB box would starve the rest of the
// system). Useful as opt-in for users with comfortable headroom (32+
// GB). Failure (typically RLIMIT_MEMLOCK exceeded) prints a warning
// and continues — mmap'd weights still work, just without the pin.
bool mlock_enabled() {
    const char* v = std::getenv("CRISPASR_MLOCK");
    return v && *v && *v != '0';
}
void try_mlock(const char* tag, void* base, size_t size) {
#if !defined(_WIN32)
    if (::mlock(base, size) != 0) {
        fprintf(stderr,
                "%s: mlock(%zu MiB) failed (errno=%d) — pages may still be evicted under "
                "memory pressure. Raise RLIMIT_MEMLOCK (`ulimit -l unlimited`) if you want "
                "the pin to take effect.\n",
                tag, size / (1024 * 1024), errno);
    }
#else
    (void)tag;
    (void)base;
    (void)size;
#endif
}

} // namespace detail
// T2B19 / R7-C8 bridge: detail-scope names used by mmap_advise_random.
using detail::mmap_buffer_ctx;
using detail::gpu_mmap_handle;
using detail::mmap_buffer_free;
using detail::lookup_gpu_mmap;


// PLAN #60g: switch a previously-WILLNEED-hinted region to MADV_RANDOM.
// Used by callers (e.g., mimo-asr's transcribe loop) to tell the kernel
// "I'm done with sequential prefill access; my next reads will be
// random-order layer revisits during decode — please stop wasting IO
// on speculative readahead."
//
// We dispatch on the buffer's iface fields to detect which of our two
// mmap paths the buffer came from, since the buffer types themselves
// aren't exposed publicly. No-op if the buffer wasn't allocated through
// either path (incl. the legacy alloc+copy fallback when MMAP=0 or
// mmap failed).
void mmap_advise_random(ggml_backend_buffer_t buf) {
#if !defined(_WIN32)
    if (!buf)
        return;
    void* base = nullptr;
    size_t size = 0;
    if (buf->iface.free_buffer == mmap_buffer_free) {
        // CPU mmap path — context is mmap_buffer_ctx (our own iface).
        auto* mctx = (mmap_buffer_ctx*)buf->context;
        base = mctx->mmap_base;
        size = mctx->mmap_size;
    } else {
        // Non-CPU (Metal) mmap path — buffer is the inner backend buffer,
        // its context belongs to that backend; look the mmap region up
        // in our side-map instead.
        gpu_mmap_handle h = lookup_gpu_mmap(buf);
        base = h.base;
        size = h.size;
    }
    if (base && size > 0)
        ::posix_madvise(base, size, POSIX_MADV_RANDOM);
#else
    (void)buf;
#endif
}
} // namespace core_gguf
