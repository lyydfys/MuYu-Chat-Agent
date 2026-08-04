#pragma once

#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <Windows.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <direct.h>
#include <fcntl.h>
#include <io.h>
#include <string>
#include <sys/stat.h>

#ifndef PATH_MAX
#define PATH_MAX 32768
#endif

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif

#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif

#ifndef S_ISREG
#define S_ISREG(mode) (((mode) & _S_IFMT) == _S_IFREG)
#endif

inline char* mca_test_realpath(const char* path, char* resolved) {
    if (path == nullptr || resolved == nullptr ||
        _fullpath(resolved, path, PATH_MAX) == nullptr) {
        return nullptr;
    }
    std::replace(resolved, resolved + std::char_traits<char>::length(resolved), '\\', '/');
    return resolved;
}

inline int mca_test_open(const char* path, int flags) {
    return _open(path, flags | _O_BINARY);
}

inline void* mca_test_mmap(
        void*,
        std::size_t length,
        int,
        int,
        int descriptor,
        std::int64_t offset) {
    if (length == 0U || offset < 0) {
        return reinterpret_cast<void*>(static_cast<std::intptr_t>(-1));
    }
    const intptr_t raw_handle = _get_osfhandle(descriptor);
    if (raw_handle == -1) {
        return reinterpret_cast<void*>(static_cast<std::intptr_t>(-1));
    }
    HANDLE mapping = CreateFileMappingW(
        reinterpret_cast<HANDLE>(raw_handle), nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (mapping == nullptr) {
        return reinterpret_cast<void*>(static_cast<std::intptr_t>(-1));
    }
    const auto unsigned_offset = static_cast<std::uint64_t>(offset);
    void* view = MapViewOfFile(
        mapping,
        FILE_MAP_READ,
        static_cast<DWORD>(unsigned_offset >> 32U),
        static_cast<DWORD>(unsigned_offset & UINT64_C(0xffffffff)),
        length);
    CloseHandle(mapping);
    return view != nullptr
        ? view
        : reinterpret_cast<void*>(static_cast<std::intptr_t>(-1));
}

inline int mca_test_munmap(void* address, std::size_t) {
    return address != nullptr && UnmapViewOfFile(address) != 0 ? 0 : -1;
}

#define realpath mca_test_realpath
#define open mca_test_open
#define close _close
#define fstat _fstat64
#define stat _stat64
#define mmap mca_test_mmap
#define munmap mca_test_munmap

#ifndef PROT_READ
#define PROT_READ 1
#endif

#ifndef MAP_PRIVATE
#define MAP_PRIVATE 2
#endif

#ifndef MAP_FAILED
#define MAP_FAILED reinterpret_cast<void*>(static_cast<std::intptr_t>(-1))
#endif
