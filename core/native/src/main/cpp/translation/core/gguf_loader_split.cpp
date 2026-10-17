// gguf_loader_split.cpp - T2B19 / R7-C8 split TU (station A).
// load_weights_split(): PLAN #69a layer-residency partition + issue #276
// 1.5 GiB chunked backend allocation (GPU + CPU partitions).
// Relocated byte-exact from core/gguf_loader.cpp [955-1166]; zero rewrites.

#include "gguf_loader_internal.hpp"
#include "gguf_loader.h"

#include <cstdio>
#include <vector>

#if defined(_WIN32)
#include <io.h>
#endif

namespace core_gguf {

// T2B19 / R7-C8 bridge: MappedFile lives in gguf_loader_internal.hpp
// (core_gguf::detail).
using detail::MappedFile;


bool load_weights_split(const char* path, ggml_backend_t gpu_backend, ggml_backend_t cpu_backend, IsGpuTensor is_gpu,
                        void* user, const char* model_tag, WeightLoad& out) {
    const char* tag = model_tag ? model_tag : "core_gguf";

    if (!gpu_backend || !cpu_backend) {
        fprintf(stderr, "%s: load_weights_split requires both gpu and cpu backends\n", tag);
        return false;
    }
    if (!is_gpu) {
        fprintf(stderr, "%s: load_weights_split requires a non-null is_gpu predicate\n", tag);
        return false;
    }

    // Open metadata + create ggml_context with all tensor metadata (no_alloc).
    gguf_init_params gp = {/*.no_alloc=*/true, /*.ctx=*/&out.ctx};
    gguf_context* gctx = gguf_init_from_file(path, gp);
    if (!gctx || !out.ctx) {
        fprintf(stderr, "%s: failed to load tensor metadata from '%s'\n", tag, path);
        if (gctx)
            gguf_free(gctx);
        return false;
    }

    // Pass 1: partition tensors by predicate, sum sizes per partition.
    std::vector<ggml_tensor*> gpu_tensors, cpu_tensors;
    size_t gpu_size = 0, cpu_size = 0;
    for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
        const char* tname = ggml_get_name(t);
        const bool to_gpu = is_gpu(tname, user);
        if (to_gpu) {
            gpu_tensors.push_back(t);
            gpu_size += ggml_nbytes(t);
        } else {
            cpu_tensors.push_back(t);
            cpu_size += ggml_nbytes(t);
        }
        out.tensors[tname] = t;
    }

    // Allocate per-partition backend buffers. Tensor alignment within the
    // buffer follows the backend buffer-type's alignment requirement;
    // pad each per-tensor offset up to that alignment.
    //
    // Issue #276: AMD Vulkan (proprietary driver on Windows) caps a single
    // device allocation at 2 GiB (maxMemoryAllocationSize). Models larger
    // than that need to be split across multiple backend buffers. We chunk
    // tensors into groups of <= 1.5 GiB each and allocate one buffer per
    // chunk; the 1.5 GiB limit leaves headroom for alignment padding.
    static constexpr size_t max_alloc_chunk = (size_t)1536 * 1024 * 1024; // 1.5 GiB

    auto round_up = [](size_t n, size_t a) { return (n + a - 1) & ~(a - 1); };
    auto bind_partition = [&](ggml_backend_t be, const std::vector<ggml_tensor*>& tensors,
                              std::vector<ggml_backend_buffer_t>& out_bufs) -> bool {
        if (tensors.empty())
            return true;
        const size_t align = ggml_backend_get_alignment(be);

        // Size tensors by the BUFFER TYPE's alloc size, not ggml_nbytes().
        //
        // ggml_backend_tensor_alloc() asserts that
        //   addr + ggml_backend_buffer_get_alloc_size(buf, t) <= base + buf_size
        // and CUDA's alloc size EXCEEDS ggml_nbytes for quantized tensors — it
        // pads each row up to MATRIX_ROW_PADDING so MMQ can over-read safely.
        // Sizing the buffer with ggml_nbytes therefore left the last tensor of
        // every chunk short by that padding, and the write went past the end.
        //
        // ggml only started asserting this in v0.17, so the overrun was silent
        // before: it surfaced as moonshine-tiny (q4_k) aborting at load on CUDA
        // while passing on Metal, whose alloc size equals ggml_nbytes.
        // Everything using load_weights_split with quantized weights on CUDA was
        // affected, not just moonshine.
        ggml_backend_buffer_type_t buft = ggml_backend_get_default_buffer_type(be);
        auto tensor_size = [&](const ggml_tensor* t) { return ggml_backend_buft_get_alloc_size(buft, t); };

        // Partition tensors into chunks that each fit under max_alloc_chunk.
        struct Chunk {
            std::vector<ggml_tensor*> ts;
            size_t aligned_total = 0;
        };
        std::vector<Chunk> chunks(1);
        for (ggml_tensor* t : tensors) {
            const size_t nb = tensor_size(t);
            const size_t next = round_up(chunks.back().aligned_total, align) + nb;
            if (next > max_alloc_chunk && !chunks.back().ts.empty()) {
                // Start a new chunk.
                chunks.push_back({});
                chunks.back().ts.push_back(t);
                chunks.back().aligned_total = nb;
            } else {
                chunks.back().ts.push_back(t);
                chunks.back().aligned_total = next;
            }
        }

        for (auto& chunk : chunks) {
            ggml_backend_buffer_t buf = ggml_backend_alloc_buffer(be, chunk.aligned_total);
            if (!buf) {
                fprintf(stderr, "%s: failed to allocate %zu MiB backend buffer\n", tag, chunk.aligned_total / 1048576);
                for (auto* b : out_bufs)
                    ggml_backend_buffer_free(b);
                out_bufs.clear();
                return false;
            }
            char* base = (char*)ggml_backend_buffer_get_base(buf);
            size_t cursor = 0;
            for (ggml_tensor* t : chunk.ts) {
                cursor = round_up(cursor, align);
                ggml_backend_tensor_alloc(buf, t, base + cursor);
                cursor += tensor_size(t);
            }
            out_bufs.push_back(buf);
        }
        return true;
    };

    std::vector<ggml_backend_buffer_t> gpu_bufs, cpu_bufs;
    if (!bind_partition(gpu_backend, gpu_tensors, gpu_bufs)) {
        gguf_free(gctx);
        ggml_free(out.ctx);
        out.ctx = nullptr;
        return false;
    }
    if (!bind_partition(cpu_backend, cpu_tensors, cpu_bufs)) {
        for (auto* b : gpu_bufs)
            ggml_backend_buffer_free(b);
        gguf_free(gctx);
        ggml_free(out.ctx);
        out.ctx = nullptr;
        return false;
    }

    // First buffer of each partition goes into the canonical fields;
    // any overflow chunks go into split_bufs for lifetime management.
    if (!gpu_bufs.empty()) {
        out.buf = gpu_bufs[0];
        for (size_t i = 1; i < gpu_bufs.size(); i++)
            out.split_bufs.push_back(gpu_bufs[i]);
    }
    if (!cpu_bufs.empty()) {
        out.buf_cpu = cpu_bufs[0];
        for (size_t i = 1; i < cpu_bufs.size(); i++)
            out.split_bufs.push_back(cpu_bufs[i]);
    }

    // Copy tensor data from the file. Use mmap when available for zero-
    // copy where the kernel will demand-page; fall back to pread.
    MappedFile mf(path);
    if (!mf.ok) {
        FILE* fp = fopen(path, "rb");
        if (!fp) {
            fprintf(stderr, "%s: cannot open '%s' for fread fallback\n", tag, path);
            free_weights(out);
            gguf_free(gctx);
            return false;
        }
        const size_t data_off = gguf_get_data_offset(gctx);
        std::vector<uint8_t> tbuf;
        for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
            const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
            if (tid < 0)
                continue;
            const size_t off = gguf_get_tensor_offset(gctx, tid);
            const size_t nbytes = ggml_nbytes(t);
            if (tbuf.size() < nbytes)
                tbuf.resize(nbytes);
#if defined(_WIN32)
            if (_fseeki64(fp, (int64_t)(data_off + off), SEEK_SET) != 0)
                break;
#else
            if (fseeko(fp, (off_t)(data_off + off), SEEK_SET) != 0)
                break;
#endif
            if (fread(tbuf.data(), 1, nbytes, fp) != nbytes)
                break;
            ggml_backend_tensor_set(t, tbuf.data(), 0, nbytes);
        }
        fclose(fp);
    } else {
        const size_t data_off = gguf_get_data_offset(gctx);
        for (ggml_tensor* t = ggml_get_first_tensor(out.ctx); t; t = ggml_get_next_tensor(out.ctx, t)) {
            const int64_t tid = gguf_find_tensor(gctx, ggml_get_name(t));
            if (tid < 0)
                continue;
            const size_t off = gguf_get_tensor_offset(gctx, tid);
            const size_t nbytes = ggml_nbytes(t);
            // Bounds check (mirrors load_weights' mmap paths): a crafted/truncated
            // GGUF whose tensor offset+size runs past the mapping would SIGBUS, or
            // copy adjacent memory into the tensor buffer (info leak). This split
            // mmap path was the only one of the four missing the guard.
            // Overflow-safe: a crafted GGUF can set off/nbytes near SIZE_MAX so the
            // additive data_off+off+nbytes wraps and passes the check. Compare
            // subtractively (each subtraction is valid once the prior guard holds).
            if (data_off > mf.size || off > mf.size - data_off || nbytes > mf.size - data_off - off) {
                fprintf(stderr,
                        "%s: split mmap path: tensor '%s' exceeds file bounds "
                        "(off=%zu + nbytes=%zu > file_size=%zu) — file truncated?\n",
                        tag, ggml_get_name(t), data_off + off, nbytes, mf.size);
                free_weights(out);
                gguf_free(gctx);
                return false;
            }
            ggml_backend_tensor_set(t, (const char*)mf.base + data_off + off, 0, nbytes);
        }
    }

    fprintf(stderr, "%s: weight residency: gpu=%zu MiB (%zu tensors), cpu=%zu MiB (%zu tensors)\n", tag,
            gpu_size / 1048576, gpu_tensors.size(), cpu_size / 1048576, cpu_tensors.size());

    gguf_free(gctx);
    return true;
}
} // namespace core_gguf
