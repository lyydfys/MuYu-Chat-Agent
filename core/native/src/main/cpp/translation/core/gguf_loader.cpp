// src/core/gguf_loader.cpp — implementation of core_gguf:: helpers.
// See gguf_loader.h for the interface contract.

#include "gguf_loader.h"

#include "ggml-backend-impl.h"
#include "ggml-cpu.h"
#ifdef GGML_USE_METAL
#include "ggml-metal.h"
#endif

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <mutex>

#if defined(_WIN32)
#include <io.h>
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace core_gguf {

// ---------------------------------------------------------------------------
// Pass 1: metadata
// ---------------------------------------------------------------------------

gguf_context* open_metadata(const char* path) {
    gguf_init_params gp = {/*.no_alloc=*/true, /*.ctx=*/nullptr};
    gguf_context* g = gguf_init_from_file(path, gp);
    if (!g) {
        fprintf(stderr, "core_gguf: failed to open '%s' for metadata read\n", path);
    }
    return g;
}

void free_metadata(gguf_context* gctx) {
    if (gctx)
        gguf_free(gctx);
}

// Type-checked scalar readers. The GGUF format stores types explicitly so
// we can validate; if the file has a mismatched type the reader silently
// returns the default rather than crashing, matching the existing inline
// helpers in each model.

uint32_t kv_u32(gguf_context* gctx, const char* key, uint32_t default_val) {
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return default_val;
    const gguf_type t = gguf_get_kv_type(gctx, k);
    switch (t) {
    case GGUF_TYPE_UINT32:
        return gguf_get_val_u32(gctx, k);
    case GGUF_TYPE_INT32:
        return (uint32_t)gguf_get_val_i32(gctx, k);
    case GGUF_TYPE_UINT64:
        return (uint32_t)gguf_get_val_u64(gctx, k);
    case GGUF_TYPE_INT64:
        return (uint32_t)gguf_get_val_i64(gctx, k);
    case GGUF_TYPE_UINT16:
        return gguf_get_val_u16(gctx, k);
    case GGUF_TYPE_INT16:
        return (uint32_t)gguf_get_val_i16(gctx, k);
    case GGUF_TYPE_UINT8:
        return gguf_get_val_u8(gctx, k);
    case GGUF_TYPE_INT8:
        return (uint32_t)gguf_get_val_i8(gctx, k);
    default:
        return default_val;
    }
}

int32_t kv_i32(gguf_context* gctx, const char* key, int32_t default_val) {
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return default_val;
    const gguf_type t = gguf_get_kv_type(gctx, k);
    switch (t) {
    case GGUF_TYPE_INT32:
        return gguf_get_val_i32(gctx, k);
    case GGUF_TYPE_UINT32:
        return (int32_t)gguf_get_val_u32(gctx, k);
    case GGUF_TYPE_INT64:
        return (int32_t)gguf_get_val_i64(gctx, k);
    case GGUF_TYPE_UINT64:
        return (int32_t)gguf_get_val_u64(gctx, k);
    default:
        return default_val;
    }
}

float kv_f32(gguf_context* gctx, const char* key, float default_val) {
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return default_val;
    const gguf_type t = gguf_get_kv_type(gctx, k);
    if (t == GGUF_TYPE_FLOAT32)
        return gguf_get_val_f32(gctx, k);
    if (t == GGUF_TYPE_FLOAT64)
        return (float)gguf_get_val_f64(gctx, k);
    return default_val;
}

bool kv_bool(gguf_context* gctx, const char* key, bool default_val) {
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return default_val;
    if (gguf_get_kv_type(gctx, k) != GGUF_TYPE_BOOL)
        return default_val;
    return gguf_get_val_bool(gctx, k);
}

std::string kv_str(gguf_context* gctx, const char* key, const char* default_val) {
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return default_val ? default_val : "";
    if (gguf_get_kv_type(gctx, k) != GGUF_TYPE_STRING)
        return default_val ? default_val : "";
    const char* s = gguf_get_val_str(gctx, k);
    return s ? std::string(s) : std::string(default_val ? default_val : "");
}

std::vector<std::string> kv_str_array(gguf_context* gctx, const char* key) {
    std::vector<std::string> out;
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return out;
    if (gguf_get_kv_type(gctx, k) != GGUF_TYPE_ARRAY)
        return out;
    if (gguf_get_arr_type(gctx, k) != GGUF_TYPE_STRING)
        return out;
    const int n = gguf_get_arr_n(gctx, k);
    out.reserve((size_t)n);
    for (int i = 0; i < n; i++) {
        out.emplace_back(gguf_get_arr_str(gctx, k, i));
    }
    return out;
}

std::vector<float> kv_f32_array(gguf_context* gctx, const char* key) {
    std::vector<float> out;
    const int k = gguf_find_key(gctx, key);
    if (k < 0)
        return out;
    if (gguf_get_kv_type(gctx, k) != GGUF_TYPE_ARRAY)
        return out;
    if (gguf_get_arr_type(gctx, k) != GGUF_TYPE_FLOAT32)
        return out;
    const size_t n = gguf_get_arr_n(gctx, k);
    const float* data = (const float*)gguf_get_arr_data(gctx, k);
    out.assign(data, data + n);
    return out;
}

// T2B19 / R7-C8: source lines 167-901 were relocated byte-exact to
// gguf_loader_mmap.cpp / gguf_loader_weights.cpp (+ gguf_loader_internal.hpp);
// lines 955-1203 to gguf_loader_split.cpp / gguf_loader_mmap.cpp.
// This spine keeps the public API blocks; see a-SPLIT-PLAN.json for the plan.

void free_weights(WeightLoad& wl) {
    if (wl.buf) {
        ggml_backend_buffer_free(wl.buf);
        wl.buf = nullptr;
    }
    if (wl.buf_cpu) {
        ggml_backend_buffer_free(wl.buf_cpu);
        wl.buf_cpu = nullptr;
    }
    // Issue #276: free any overflow chunk buffers from split allocation.
    for (auto* b : wl.split_bufs)
        ggml_backend_buffer_free(b);
    wl.split_bufs.clear();
    if (wl.ctx) {
        ggml_free(wl.ctx);
        wl.ctx = nullptr;
    }
    wl.tensors.clear();
}

int blk_layer_of_with_prefix(const char* tensor_name, const char* prefix) {
    if (!tensor_name || !prefix)
        return -1;
    const size_t plen = std::strlen(prefix);
    if (std::strncmp(tensor_name, prefix, plen) != 0)
        return -1;
    char* end = nullptr;
    long il = std::strtol(tensor_name + plen, &end, 10);
    if (!end || *end != '.' || il < 0)
        return -1;
    return (int)il;
}

int blk_layer_of(const char* tensor_name) {
    return blk_layer_of_with_prefix(tensor_name, "blk.");
}

bool is_gpu_tensor_with_prefix(const char* tensor_name, void* user) {
    const auto* cfg = static_cast<const LayerSplitConfig*>(user);
    const int il = blk_layer_of_with_prefix(tensor_name, cfg->prefix);
    if (il < 0)
        return true; // non-layered tensors stay on GPU
    return il < cfg->threshold;
}

bool is_gpu_tensor_blk(const char* tensor_name, void* user) {
    const int threshold = *static_cast<const int*>(user);
    const int il = blk_layer_of(tensor_name);
    if (il < 0)
        return true;
    return il < threshold;
}

// ---------------------------------------------------------------------------
// Tensor lookup helpers
// ---------------------------------------------------------------------------

// Signatures use `core_gguf::tensor_map` (see gguf_loader.h cross-repo contract).
ggml_tensor* try_get(const tensor_map& tensors, const char* name) {
    auto it = tensors.find(name);
    return it != tensors.end() ? it->second : nullptr;
}

ggml_tensor* require(const tensor_map& tensors, const char* name, const char* model_tag) {
    auto it = tensors.find(name);
    if (it == tensors.end()) {
        fprintf(stderr, "%s: required tensor '%s' not found in GGUF\n", model_tag ? model_tag : "core_gguf", name);
        return nullptr;
    }
    return it->second;
}

std::string format_layer_name(const char* fmt, int i) {
    char buf[256];
    snprintf(buf, sizeof(buf), fmt, i);
    return std::string(buf);
}

std::string format_layer_name(const char* fmt, int i, int j) {
    char buf[256];
    snprintf(buf, sizeof(buf), fmt, i, j);
    return std::string(buf);
}

} // namespace core_gguf
