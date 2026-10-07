#ifndef MCA_COMMON_FILE_GUARD_HPP
#define MCA_COMMON_FILE_GUARD_HPP

#include <cstdio>
#include <cstddef>
#include <utility>

#if defined(_WIN32)
#include <io.h>
#else
#include <unistd.h>
#include <sys/mman.h>
#endif

namespace mca {
namespace common {

/**
 * RAII guard for file descriptors.
 */
class FileDescriptorGuard {
public:
    explicit FileDescriptorGuard(int fd = -1) noexcept : fd_(fd) {}

    ~FileDescriptorGuard() noexcept {
        reset();
    }

    FileDescriptorGuard(const FileDescriptorGuard&) = delete;
    FileDescriptorGuard& operator=(const FileDescriptorGuard&) = delete;

    FileDescriptorGuard(FileDescriptorGuard&& other) noexcept : fd_(other.fd_) {
        other.fd_ = -1;
    }

    FileDescriptorGuard& operator=(FileDescriptorGuard&& other) noexcept {
        if (this != &other) {
            reset();
            fd_ = other.fd_;
            other.fd_ = -1;
        }
        return *this;
    }

    [[nodiscard]] int get() const noexcept { return fd_; }
    [[nodiscard]] bool is_valid() const noexcept { return fd_ >= 0; }

    int release() noexcept {
        int temp = fd_;
        fd_ = -1;
        return temp;
    }

    void reset(int new_fd = -1) noexcept {
        if (fd_ >= 0) {
#if defined(_WIN32)
            _close(fd_);
#else
            close(fd_);
#endif
        }
        fd_ = new_fd;
    }

private:
    int fd_{-1};
};

/**
 * RAII guard for memory-mapped regions.
 */
class MmapGuard {
public:
    MmapGuard(void* addr = nullptr, size_t length = 0) noexcept
        : addr_(addr), length_(length) {}

    ~MmapGuard() noexcept {
        reset();
    }

    MmapGuard(const MmapGuard&) = delete;
    MmapGuard& operator=(const MmapGuard&) = delete;

    MmapGuard(MmapGuard&& other) noexcept
        : addr_(other.addr_), length_(other.length_) {
        other.addr_ = nullptr;
        other.length_ = 0;
    }

    MmapGuard& operator=(MmapGuard&& other) noexcept {
        if (this != &other) {
            reset();
            addr_ = other.addr_;
            length_ = other.length_;
            other.addr_ = nullptr;
            other.length_ = 0;
        }
        return *this;
    }

    [[nodiscard]] void* get() const noexcept { return addr_; }
    [[nodiscard]] size_t size() const noexcept { return length_; }
    [[nodiscard]] bool is_valid() const noexcept { return addr_ != nullptr && length_ > 0; }

    void* release() noexcept {
        void* temp = addr_;
        addr_ = nullptr;
        length_ = 0;
        return temp;
    }

    void reset(void* new_addr = nullptr, size_t new_length = 0) noexcept {
        if (addr_ != nullptr && length_ > 0) {
#if !defined(_WIN32)
            munmap(addr_, length_);
#endif
        }
        addr_ = new_addr;
        length_ = new_length;
    }

private:
    void* addr_{nullptr};
    size_t length_{0};
};

/**
 * RAII guard for standard C FILE pointers.
 */
class FileStreamGuard {
public:
    explicit FileStreamGuard(FILE* fp = nullptr) noexcept : fp_(fp) {}

    ~FileStreamGuard() noexcept {
        reset();
    }

    FileStreamGuard(const FileStreamGuard&) = delete;
    FileStreamGuard& operator=(const FileStreamGuard&) = delete;

    FileStreamGuard(FileStreamGuard&& other) noexcept : fp_(other.fp_) {
        other.fp_ = nullptr;
    }

    FileStreamGuard& operator=(FileStreamGuard&& other) noexcept {
        if (this != &other) {
            reset();
            fp_ = other.fp_;
            other.fp_ = nullptr;
        }
        return *this;
    }

    [[nodiscard]] FILE* get() const noexcept { return fp_; }
    [[nodiscard]] bool is_valid() const noexcept { return fp_ != nullptr; }

    FILE* release() noexcept {
        FILE* temp = fp_;
        fp_ = nullptr;
        return temp;
    }

    void reset(FILE* new_fp = nullptr) noexcept {
        if (fp_ != nullptr) {
            fclose(fp_);
        }
        fp_ = new_fp;
    }

private:
    FILE* fp_{nullptr};
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_FILE_GUARD_HPP
