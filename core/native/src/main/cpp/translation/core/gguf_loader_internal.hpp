// gguf_loader_internal.hpp - T2B19 / R7-C8 internal header (station A).
// NOT a public interface: included only by gguf_loader{,_mmap,_weights,_split}.cpp.
// Types (MappedFile, mmap_buffer_ctx) relocated byte-exact from
// core/gguf_loader.cpp [174-270]; the hidden pragma block declares the seven
// cross-TU symbols promoted out of the former anonymous namespace
// (necessity per name registered in a-SPLIT-PLAN.json::promotions).
// No file-scope `static` state lives here: the GPU mmap registry stays a
// single instance inside gguf_loader_mmap.cpp.

#pragma once

#include "ggml-backend-impl.h"  // ggml_backend_buffer_i / ggml_backend_buffer_t

#include <cstddef>

#if defined(_WIN32)
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
namespace detail {

#pragma GCC visibility push(hidden)

// Read a file slice into a backend tensor. Uses mmap on POSIX; falls back
// to pread/lseek+read when mmap is unavailable (rare in practice).
//
// On POSIX the mmap lives for the duration of one load call — we copy via
// ggml_backend_tensor_set then unmap. No mmap persists past load_weights().
struct MappedFile {
    int fd = -1;
    void* base = nullptr;
    size_t size = 0;
    bool ok = false;

    // When `writable` is true, the mapping is created with copy-on-write
    // semantics (POSIX MAP_PRIVATE + PROT_READ|PROT_WRITE / Win32
    // FILE_MAP_COPY). Reads share the file's page cache; writes get a
    // private anonymous duplicate of the touched page. This lets backends
    // that mutate weights post-load (e.g. parakeet's BN-into-conv fold) run
    // unchanged on the zero-copy path without modifying the underlying file.
    explicit MappedFile(const char* path, bool writable = false) {
#if defined(_WIN32)
        const DWORD page_protect = writable ? PAGE_WRITECOPY : PAGE_READONLY;
        const DWORD view_access = writable ? FILE_MAP_COPY : FILE_MAP_READ;
        HANDLE hFile = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
        if (hFile == INVALID_HANDLE_VALUE)
            return;
        LARGE_INTEGER fsize;
        if (!GetFileSizeEx(hFile, &fsize)) {
            CloseHandle(hFile);
            return;
        }
        size = (size_t)fsize.QuadPart;
        HANDLE hMap = CreateFileMappingA(hFile, nullptr, page_protect, 0, 0, nullptr);
        CloseHandle(hFile);
        if (!hMap)
            return;
        base = MapViewOfFile(hMap, view_access, 0, 0, 0);
        CloseHandle(hMap);
        if (!base)
            return;
        ok = true;
#else
        fd = ::open(path, O_RDONLY);
        if (fd < 0)
            return;
        struct stat st;
        if (fstat(fd, &st) != 0) {
            ::close(fd);
            fd = -1;
            return;
        }
        size = (size_t)st.st_size;
        const int prot = writable ? (PROT_READ | PROT_WRITE) : PROT_READ;
        const int flags = writable ? MAP_PRIVATE : MAP_SHARED;
        base = ::mmap(nullptr, size, prot, flags, fd, 0);
        ::close(fd);
        fd = -1;
        if (base == MAP_FAILED) {
            base = nullptr;
            return;
        }
        ok = true;
#endif
    }
    ~MappedFile() {
#if defined(_WIN32)
        if (base)
            UnmapViewOfFile(base);
#else
        if (base)
            ::munmap(base, size);
#endif
    }
    MappedFile(const MappedFile&) = delete;
    MappedFile& operator=(const MappedFile&) = delete;

    // Transfer ownership of the mmap region out of the RAII handle so it
    // outlives the destructor. Used by the CRISPASR_GGUF_MMAP=1 path to
    // hand the mapping to a backend buffer that owns it for the model's
    // lifetime.
    void release() {
        base = nullptr;
        size = 0;
    }
};

// PLAN #51a: a CPU backend buffer whose backing memory is an mmap'd file
// region. On free_buffer the mapping is unmapped — that's the entire
// reason this buffer type exists. Tensors must be bound with
// ggml_backend_tensor_alloc(); we do not provide an init_tensor path.
//
// We reuse ggml_backend_cpu_buffer_type() so ggml_backend_buffer_is_host()
// returns true on this buffer (some scheduler paths key off that).
struct mmap_buffer_ctx {
    void* mmap_base = nullptr;   // page-aligned start of the mmap
    size_t mmap_size = 0;        // length of the mmap
    void* tensor_base = nullptr; // mmap_base + data_off, 32-byte aligned
};

// Cross-TU declarations (all hidden). Definition home for the seven names:
// gguf_loader_mmap.cpp. Consumers: gguf_loader_weights.cpp at 528 / 564-569 /
// 647 / 688-693 (both mmap load paths). No other TU needs them.
extern const ggml_backend_buffer_i mmap_buffer_iface;
void register_gpu_mmap(ggml_backend_buffer_t buf, void* base, size_t size);
bool mmap_loader_enabled();
bool preload_enabled();
void preload_pages(void* base, size_t size);
bool mlock_enabled();
void try_mlock(const char* tag, void* base, size_t size);
#pragma GCC visibility pop

} // namespace detail
} // namespace core_gguf
