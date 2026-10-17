// gguf_loader_weights.cpp - T2B19 / R7-C8 split TU (station A).
// The weight loader: file-static load_weights_impl() (CPU mmap / GPU
// host-ptr mmap / legacy alloc+copy) plus its public entry points
// load_weights() / load_weights_filtered().
// Relocated byte-exact from core/gguf_loader.cpp [453-901]; zero rewrites
// (`load_weights_impl` stays file-static, per T2B19-RECON.md 5.4).

#include "gguf_loader_internal.hpp"
#include "gguf_loader.h"

#include "ggml-cpu.h"

#include <cstdio>
#include <limits>
#include <vector>

#if defined(_WIN32)
#include <io.h>
#endif

namespace core_gguf {

// T2B19 / R7-C8 bridge: pull the gguf_loader_internal.hpp declarations
// (core_gguf::detail) into core_gguf scope for this TU only.
using detail::MappedFile;
using detail::mmap_buffer_ctx;
using detail::mmap_buffer_iface;
using detail::register_gpu_mmap;
using detail::mmap_loader_enabled;
using detail::preload_enabled;
using detail::preload_pages;
using detail::mlock_enabled;
using detail::try_mlock;


static bool load_weights_impl(const char* path, ggml_backend_t backend, IncludeTensor include_tensor, void* user,
                              const char* model_tag, WeightLoad& out) {
    const char* tag = model_tag ? model_tag : "core_gguf";

    ggml_context* source_ctx = nullptr;
    gguf_init_params gp = {/*.no_alloc=*/true, /*.ctx=*/&source_ctx};
    gguf_context* gctx = gguf_init_from_file(path, gp);
    if (!gctx || !source_ctx) {
        fprintf(stderr, "%s: failed to load tensor metadata from '%s'\n", tag, path);
        if (gctx)
            gguf_free(gctx);
        if (source_ctx)
            ggml_free(source_ctx);
        return false;
    }

    if (include_tensor) {
        size_t n_selected = 0;
        for (ggml_tensor* t = ggml_get_first_tensor(source_ctx); t; t = ggml_get_next_tensor(source_ctx, t)) {
            if (include_tensor(ggml_get_name(t), user))
                n_selected++;
        }
        if (n_selected == 0) {
            fprintf(stderr, "%s: tensor filter selected no weights from '%s'\n", tag, path);
            gguf_free(gctx);
            ggml_free(source_ctx);
            return false;
        }

        const size_t tensor_overhead = ggml_tensor_overhead();
        if (tensor_overhead != 0 && n_selected > (std::numeric_limits<size_t>::max() - 1024) / tensor_overhead) {
            fprintf(stderr, "%s: filtered tensor metadata size overflow\n", tag);
            gguf_free(gctx);
            ggml_free(source_ctx);
            return false;
        }
        ggml_init_params fp = {
            /*.mem_size   =*/n_selected * tensor_overhead + 1024,
            /*.mem_buffer =*/nullptr,
            /*.no_alloc   =*/true,
        };
        out.ctx = ggml_init(fp);
        if (!out.ctx) {
            fprintf(stderr, "%s: failed to create filtered tensor context\n", tag);
            gguf_free(gctx);
            ggml_free(source_ctx);
            return false;
        }
        for (ggml_tensor* t = ggml_get_first_tensor(source_ctx); t; t = ggml_get_next_tensor(source_ctx, t)) {
            if (!include_tensor(ggml_get_name(t), user))
                continue;
            ggml_tensor* selected = ggml_dup_tensor(out.ctx, t);
            if (!selected) {
                fprintf(stderr, "%s: failed to duplicate filtered tensor metadata for '%s'\n", tag, ggml_get_name(t));
                gguf_free(gctx);
                ggml_free(source_ctx);
                ggml_free(out.ctx);
                out.ctx = nullptr;
                return false;
            }
            ggml_set_name(selected, ggml_get_name(t));
        }
        ggml_free(source_ctx);
    } else {
        out.ctx = source_ctx;
    }

    // PLAN #51a: zero-copy CPU path. Skip ggml_backend_alloc_ctx_tensors
    // (which would allocate a fresh backend-side buffer) and instead bind
    // each tensor directly into the mmap'd file. Saves one full copy of
    // the weights — the difference between a 14.9 GB F16 GGUF loading on
    // a 16 GB Mac and thrashing swap. Default-on as of issue #94 (slow /
    // failing chatterbox-turbo load on macOS); opt out with
    // `CRISPASR_GGUF_MMAP=0`.
    if (mmap_loader_enabled() && ggml_backend_is_cpu(backend)) {
        MappedFile mf(path, /*writable=*/true);
        if (mf.ok) {
            const size_t data_off = gguf_get_data_offset(gctx);
            const size_t mmap_size = mf.size;
            if (data_off > mmap_size) {
                fprintf(stderr, "%s: GGUF data offset %zu exceeds file size %zu - file truncated?\n", tag, data_off,
                        mmap_size);
                gguf_free(gctx);
                ggml_free(out.ctx);
                out.ctx = nullptr;
                return false;
            }
            char* tensor_base = (char*)mf.base + data_off;
            const size_t buf_size = mmap_size > data_off ? (mmap_size - data_off) : 0;

            // cppcheck-suppress uninitvar
            auto* mctx = new mmap_buffer_ctx{};
            mctx->mmap_base = mf.base;
            mctx->mmap_size = mmap_size;
            mctx->tensor_base = tensor_base;
            mf.release();
            // Hint kernel to start async readahead of the entire weight
            // region. Without this we hit a synchronous page fault on every
            // first access during prefill (~5-10 ms each on the 99%-full
            // external disk we hit during PLAN #51c F16 testing). Mirrors
            // llama.cpp's `llama_mmap` populate path.
#if !defined(_WIN32)
            if (!include_tensor)
                ::posix_madvise(mctx->mmap_base, mctx->mmap_size, POSIX_MADV_WILLNEED);
#endif
            // PLAN #60c / #60f: optional preload + mlock, opt-in via env.
            // A filtered component may live inside a much larger parent GGUF.
            // Whole-file readahead/preload/mlock would defeat filtered loading
            // by making every unrelated tensor resident (or pinned), so let
            // ordinary page faults bring in only the selected tensor ranges.
            if (!include_tensor && preload_enabled())
                preload_pages(mctx->mmap_base, mctx->mmap_size);
            if (!include_tensor && mlock_enabled())
                try_mlock(tag, mctx->mmap_base, mctx->mmap_size);

            out.buf = ggml_backend_buffer_init(ggml_backend_cpu_buffer_type(), mmap_buffer_iface, mctx, buf_size);
            if (!out.buf) {
                fprintf(stderr, "%s: failed to wrap mmap in backend buffer\n", tag);
#if defined(_WIN32)
                UnmapViewOfFile(mctx->mmap_base);
#else
                ::munmap(mctx->mmap_base, mctx->mmap_size);
#endif
                delete mctx;
                gguf_free(gctx);
                ggml_free(out.ctx);
                out.ctx = nullptr;
                return false;
            }

            // Pre-validate tensor bounds before calling ggml_backend_tensor_alloc,
            // which fires a hard GGML_ASSERT when any tensor's range exceeds
            // buf_size.  Seen on macOS 26.x Tahoe (issue #94) — likely a
            // mismatched or truncated GGUF file.  Detect it here and fall back
            // gracefully to the legacy alloc+copy path instead of crashing.
            bool bounds_ok = true;
            for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
                const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
                if (tid < 0)
                    continue;
                const size_t off = gguf_get_tensor_offset(gctx, tid);
                const size_t nbytes = ggml_nbytes(t);
                if (off > buf_size || nbytes > buf_size - off) {
                    fprintf(stderr,
                            "%s: mmap bounds check failed for tensor '%s' "
                            "(off=%zu + nbytes=%zu > buf_size=%zu) - "
                            "falling back to legacy loader\n",
                            tag, ggml_get_name(t), off, nbytes, buf_size);
                    bounds_ok = false;
                    break;
                }
            }
            if (bounds_ok) {
                for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
                    out.tensors[ggml_get_name(t)] = t;
                    const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
                    if (tid < 0)
                        continue;
                    const size_t off = gguf_get_tensor_offset(gctx, tid);
                    ggml_backend_tensor_alloc(out.buf, t, tensor_base + off);
                }
                gguf_free(gctx);
                return true;
            }
            // Bounds check failed — release the mmap buffer and fall through to
            // the legacy alloc+copy path below.
            ggml_backend_buffer_free(out.buf);
            out.buf = nullptr;
        }
        // mmap failed or bounds check failed — fall through to the legacy
        // alloc + copy path. Functionally equivalent, just with more RSS.
    }

    // PLAN #51a (Metal variant): zero-copy GPU path via the device's
    // `buffer_from_host_ptr` capability. On Apple-Silicon Metal this maps
    // to `[MTLDevice newBufferWithBytesNoCopy:length:options:deallocator:]`
    // wrapping our mmap region in an MTLResourceStorageModeShared buffer
    // — same physical pages the CPU sees thanks to unified memory, no
    // device-side allocation, no copy. On discrete-Metal hosts (Intel +
    // eGPU) this lets the GPU page-fault from the file directly. The
    // device-cap probe means we silently fall through on backends that
    // don't advertise host-pointer support (CUDA without managed memory,
    // Vulkan, etc.).
    //
    // The inner buffer is returned as `out.buf` UNWRAPPED. We used to
    // wrap it to attach a munmap callback to free_buffer, but ggml-metal
    // pierces the iface abstraction and casts `buffer->context` straight
    // to its `ggml_metal_buffer_t` — wrapping made Metal read garbage
    // and emit "tensor 'X' buffer is nil" for every weight (kokoro
    // gibberish-audio regression). The mmap region is registered in
    // g_gpu_mmap and deliberately leaked when the buffer is freed: Metal
    // doesn't own the pages (deallocator=nil), and on macOS file-backed
    // pages can still be evicted under pressure. Process exit cleans up.
    if (mmap_loader_enabled() && !ggml_backend_is_cpu(backend)) {
        ggml_backend_dev_t dev = ggml_backend_get_device(backend);
        ggml_backend_dev_props props{};
        ggml_backend_dev_get_props(dev, &props);
        if (props.caps.buffer_from_host_ptr) {
            MappedFile mf(path, /*writable=*/true);
            if (mf.ok) {
                const size_t data_off = gguf_get_data_offset(gctx);
                if (data_off > mf.size) {
                    fprintf(stderr, "%s: GGUF data offset %zu exceeds file size %zu - file truncated?\n", tag, data_off,
                            mf.size);
                    gguf_free(gctx);
                    ggml_free(out.ctx);
                    out.ctx = nullptr;
                    return false;
                }
                char* tensor_base = (char*)mf.base + data_off;

                // Hand the entire mmap region (including GGUF header) to
                // the device. The backend's `buffer_from_host_ptr` uses
                // the size for its internal MTLBuffer view; tensor binds
                // below offset into this base.
                ggml_backend_buffer_t inner = ggml_backend_dev_buffer_from_host_ptr(dev, mf.base, mf.size,
                                                                                    /*max_tensor_size=*/0);
                if (inner) {
                    void* leaked_base = mf.base;
                    size_t leaked_size = mf.size;
                    mf.release();
                    // Hint kernel async readahead — same rationale as the
                    // CPU branch above. On Apple Silicon the unified-memory
                    // shared-storage MTLBuffer reads the same physical
                    // pages, so this readahead benefits both CPU and GPU
                    // accesses with one call.
#if !defined(_WIN32)
                    if (!include_tensor)
                        ::posix_madvise(leaked_base, leaked_size, POSIX_MADV_WILLNEED);
#endif
                    // PLAN #60c / #60f: optional preload + mlock, opt-in
                    // via env. mlock is particularly meaningful here —
                    // pinning prevents Metal's shared-storage reads from
                    // racing CPU page faults under memory pressure.
                    if (!include_tensor && preload_enabled())
                        preload_pages(leaked_base, leaked_size);
                    if (!include_tensor && mlock_enabled())
                        try_mlock(tag, leaked_base, leaked_size);

                    register_gpu_mmap(inner, leaked_base, leaked_size);
                    out.buf = inner;

                    // Pre-validate bounds (same rationale as CPU mmap path).
                    bool bounds_ok = true;
                    for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
                        const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
                        if (tid < 0)
                            continue;
                        const size_t off = gguf_get_tensor_offset(gctx, tid);
                        // Overflow-safe: a crafted GGUF can set off/nbytes near SIZE_MAX
                        // so the additive data_off+off+nbytes wraps and passes. Compare
                        // subtractively.
                        const size_t nb = ggml_nbytes(t);
                        // A mmap'd buffer binds tensors at their PACKED GGUF file
                        // offsets, so a backend whose alloc size exceeds
                        // ggml_nbytes (CUDA pads quantized rows) cannot use this
                        // path at all — there is nowhere to put the padding, and
                        // ggml_backend_tensor_alloc would assert. No such backend
                        // advertises buffer_from_host_ptr today (CUDA reports
                        // false; this is Metal's path), so this is a guard, not a
                        // live fix: refuse into the legacy copy path instead of
                        // silently overrunning if that ever changes.
                        if (ggml_backend_buft_get_alloc_size(ggml_backend_buffer_get_type(out.buf), t) != nb) {
                            fprintf(stderr,
                                    "%s: backend pads '%s' beyond its packed size — "
                                    "mmap binding impossible, using legacy loader\n",
                                    tag, ggml_get_name(t));
                            bounds_ok = false;
                            break;
                        }
                        if (data_off > leaked_size || off > leaked_size - data_off ||
                            nb > leaked_size - data_off - off) {
                            fprintf(stderr,
                                    "%s: GPU mmap bounds check failed for tensor '%s' "
                                    "(data_off=%zu + off=%zu + nbytes=%zu > file_size=%zu) — "
                                    "falling back to legacy loader\n",
                                    tag, ggml_get_name(t), data_off, off, ggml_nbytes(t), leaked_size);
                            bounds_ok = false;
                            break;
                        }
                    }
                    if (!bounds_ok) {
                        out.buf = nullptr;
                        // inner is registered in g_gpu_mmap and deliberately
                        // leaked (same as the normal teardown path).
                        // Fall through to legacy path.
                    } else {
                        for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
                            out.tensors[ggml_get_name(t)] = t;
                            const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
                            if (tid < 0)
                                continue;
                            const size_t off = gguf_get_tensor_offset(gctx, tid);
                            ggml_backend_tensor_alloc(out.buf, t, tensor_base + off);
                        }

                        gguf_free(gctx);
                        return true;
                    }
                }
                // buffer_from_host_ptr returned null — release mmap and fall
                // through to the legacy path. No warning; same rationale as
                // the CPU mmap branch.
            }
        }
    }

    out.buf = ggml_backend_alloc_ctx_tensors(out.ctx, backend);
    if (!out.buf) {
        // Almost always the weights simply don't fit in VRAM. The bare message
        // sent people hunting for a ggml bug; say how much was wanted and what
        // to do about it. (KugelAudio's F16 asks 17.3 GB — a hard stop on any
        // 16 GB card, with nothing in the old message to suggest a smaller
        // quant would work.)
        size_t wanted = 0;
        ggml_backend_buffer_type_t buft = ggml_backend_get_default_buffer_type(backend);
        for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t))
            wanted += ggml_backend_buft_get_alloc_size(buft, t);
        fprintf(stderr,
                "%s: failed to allocate %.2f GB on '%s' for the model weights.\n"
                "  If this is a GPU, the model does not fit in VRAM. Try a smaller quantization\n"
                "  (--model-quant q4_k), or run on CPU with --no-gpu.\n",
                tag, (double)wanted / 1e9, ggml_backend_name(backend));
        gguf_free(gctx);
        ggml_free(out.ctx);
        out.ctx = nullptr;
        return false;
    }

    MappedFile mf(path);
    if (mf.ok) {
        // Issue #94: the legacy alloc+copy path took 30-60 s for the
        // 658 MB chatterbox-turbo T3 GGUF on slow disks, because each
        // ggml_backend_tensor_set hit a synchronous page fault on
        // its first access to the mmap source region. The zero-copy
        // mmap path already hints WILLNEED (line 526) for the same
        // reason; doing it here brings the legacy path's load time
        // back in line for users who opt out of the zero-copy path
        // with CRISPASR_GGUF_MMAP=0 (e.g. model files on network
        // mounts where mmap would SIGBUS on disconnect).
#if !defined(_WIN32)
        if (!include_tensor)
            ::posix_madvise(mf.base, mf.size, POSIX_MADV_WILLNEED);
#endif
    }
    if (!mf.ok) {
        // Fallback: read via FILE* pread/fseek. This is the rare path —
        // most systems have working mmap. We implement it inline here so
        // models don't have to.
        FILE* fp = fopen(path, "rb");
        if (!fp) {
            fprintf(stderr, "%s: cannot open '%s' for fread fallback\n", tag, path);
            gguf_free(gctx);
            ggml_backend_buffer_free(out.buf);
            out.buf = nullptr;
            ggml_free(out.ctx);
            out.ctx = nullptr;
            return false;
        }
        const size_t data_off = gguf_get_data_offset(gctx);
        std::vector<uint8_t> tbuf;
        bool load_ok = true;
        for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
            out.tensors[ggml_get_name(t)] = t;
            const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
            if (tid < 0)
                continue;
            const size_t off = gguf_get_tensor_offset(gctx, tid);
            const size_t nbytes = ggml_nbytes(t);
            if (tbuf.size() < nbytes)
                tbuf.resize(nbytes);
#if defined(_WIN32)
            if (_fseeki64(fp, (int64_t)(data_off + off), SEEK_SET) != 0) {
                fprintf(stderr, "%s: fseek failed for tensor '%s' — file truncated?\n", tag, ggml_get_name(t));
                load_ok = false;
                break;
            }
#else
            if (fseeko(fp, (off_t)(data_off + off), SEEK_SET) != 0) {
                fprintf(stderr, "%s: fseek failed for tensor '%s' — file truncated?\n", tag, ggml_get_name(t));
                load_ok = false;
                break;
            }
#endif
            if (fread(tbuf.data(), 1, nbytes, fp) != nbytes) {
                fprintf(stderr, "%s: short read for tensor '%s' (%zu bytes expected) — file truncated?\n", tag,
                        ggml_get_name(t), nbytes);
                load_ok = false;
                break;
            }
            ggml_backend_tensor_set(t, tbuf.data(), 0, nbytes);
        }
        fclose(fp);
        if (!load_ok) {
            fprintf(stderr, "%s: legacy loader failed — model file may be truncated or corrupt\n", tag);
            gguf_free(gctx);
            ggml_backend_buffer_free(out.buf);
            out.buf = nullptr;
            ggml_free(out.ctx);
            out.ctx = nullptr;
            return false;
        }
    } else {
        const size_t data_off = gguf_get_data_offset(gctx);
        for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
            out.tensors[ggml_get_name(t)] = t;
            const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
            if (tid < 0)
                continue;
            const size_t off = gguf_get_tensor_offset(gctx, tid);
            const size_t nbytes = ggml_nbytes(t);
            // Bounds check: prevent segfault on truncated GGUF files
            // Overflow-safe: a crafted GGUF can set off/nbytes near SIZE_MAX so the
            // additive data_off+off+nbytes wraps and passes the check. Compare
            // subtractively (each subtraction is valid once the prior guard holds).
            if (data_off > mf.size || off > mf.size - data_off || nbytes > mf.size - data_off - off) {
                fprintf(stderr,
                        "%s: mmap legacy path: tensor '%s' exceeds file bounds "
                        "(off=%zu + nbytes=%zu > file_size=%zu) — file truncated?\n",
                        tag, ggml_get_name(t), data_off + off, nbytes, mf.size);
                gguf_free(gctx);
                ggml_backend_buffer_free(out.buf);
                out.buf = nullptr;
                ggml_free(out.ctx);
                out.ctx = nullptr;
                return false;
            }
            ggml_backend_tensor_set(t, (const char*)mf.base + data_off + off, 0, nbytes);
        }
    }

    gguf_free(gctx);
    return true;
}

bool load_weights(const char* path, ggml_backend_t backend, const char* model_tag, WeightLoad& out) {
    return load_weights_impl(path, backend, nullptr, nullptr, model_tag, out);
}

bool load_weights_filtered(const char* path, ggml_backend_t backend, IncludeTensor include_tensor, void* user,
                           const char* model_tag, WeightLoad& out) {
    if (!include_tensor) {
        fprintf(stderr, "%s: load_weights_filtered requires a non-null tensor predicate\n",
                model_tag ? model_tag : "core_gguf");
        return false;
    }
    return load_weights_impl(path, backend, include_tensor, user, model_tag, out);
}
} // namespace core_gguf
