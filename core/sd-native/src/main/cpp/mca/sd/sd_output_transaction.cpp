#include "sd_output_transaction.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "mca/common/logging.hpp"
#include "sd_input_contract.hpp"

#if defined(__GNUC__) || defined(__clang__)
extern "C" __attribute__((weak)) int stbi_write_png(
    char const *filename, int w, int h, int comp, const void *data, int stride_in_bytes);
#endif

namespace mca {
namespace sd {

namespace {

static constexpr std::array<uint8_t, 8> kPngMagic = {{
    0x89u, 0x50u, 0x4eu, 0x47u, 0x0du, 0x0au, 0x1au, 0x0au,
}};

static constexpr uint64_t kMaxOutputSizeBytes = 256u * 1024u * 1024u;

bool platform_atomic_rename(const std::string& from, const std::string& to) {
#if defined(_WIN32)
    return MoveFileExA(from.c_str(), to.c_str(),
                       MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED) != 0;
#else
    return std::rename(from.c_str(), to.c_str()) == 0;
#endif
}

bool output_parent_is_still_canonical(const std::string& target) {
    const size_t slash = target.find_last_of("/\\");
    if (slash == std::string::npos) return false;
    const std::string parent = slash == 0u ? "/" : target.substr(0, slash);
#if !defined(_WIN32)
    char canonical_parent[PATH_MAX] = {};
    if (realpath(parent.c_str(), canonical_parent) == nullptr) {
        return false;
    }
    struct stat st {};
    if (stat(canonical_parent, &st) != 0 || !S_ISDIR(st.st_mode)) {
        return false;
    }
    return parent == canonical_parent;
#else
    char full_parent[MAX_PATH] = {};
    if (GetFullPathNameA(parent.c_str(), MAX_PATH, full_parent, nullptr) == 0) {
        return false;
    }
    DWORD attrs = GetFileAttributesA(full_parent);
    return (attrs != INVALID_FILE_ATTRIBUTES) && (attrs & FILE_ATTRIBUTE_DIRECTORY);
#endif
}

bool fsync_output_parent(const std::string& target, std::string& error) {
    const size_t slash = target.find_last_of("/\\");
    if (slash == std::string::npos) {
        error = "output path has no parent directory";
        return false;
    }
    const std::string parent = slash == 0u ? "/" : target.substr(0, slash);
#if !defined(_WIN32)
    const int descriptor = open(
        parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        error = "output directory could not be opened for commit";
        return false;
    }
    const bool synced = fsync(descriptor) == 0;
    const int close_result = close(descriptor);
    if (!synced || close_result != 0) {
        error = "output directory commit could not be synchronized";
        return false;
    }
#else
    (void)parent;
    (void)error;
#endif
    return true;
}

bool is_hex_sha256(const std::string& value) {
    return value.size() == 64 && std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isxdigit(c) != 0;
    });
}

// CRC32 calculation for fallback PNG chunk framing
uint32_t update_crc32(uint32_t crc, const uint8_t* buf, size_t len) noexcept {
    static uint32_t table[256];
    static bool have_table = false;
    if (!have_table) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) {
                c = (c & 1) ? (0xedb88320L ^ (c >> 1)) : (c >> 1);
            }
            table[i] = c;
        }
        have_table = true;
    }
    uint32_t c = crc ^ 0xffffffffL;
    for (size_t i = 0; i < len; ++i) {
        c = table[(c ^ buf[i]) & 0xff] ^ (c >> 8);
    }
    return c ^ 0xffffffffL;
}

// Adler32 calculation for Zlib stream
uint32_t calc_adler32(const uint8_t* data, size_t len) noexcept {
    uint32_t s1 = 1;
    uint32_t s2 = 0;
    for (size_t i = 0; i < len; ++i) {
        s1 = (s1 + data[i]) % 65521;
        s2 = (s2 + s1) % 65521;
    }
    return (s2 << 16) | s1;
}

void write_be32(std::vector<uint8_t>& out, uint32_t val) {
    out.push_back(static_cast<uint8_t>((val >> 24) & 0xff));
    out.push_back(static_cast<uint8_t>((val >> 16) & 0xff));
    out.push_back(static_cast<uint8_t>((val >> 8) & 0xff));
    out.push_back(static_cast<uint8_t>(val & 0xff));
}

void write_chunk(std::vector<uint8_t>& out, const char type[4], const uint8_t* data, size_t len) {
    write_be32(out, static_cast<uint32_t>(len));
    const size_t type_start = out.size();
    for (int i = 0; i < 4; ++i) {
        out.push_back(static_cast<uint8_t>(type[i]));
    }
    if (len > 0 && data != nullptr) {
        out.insert(out.end(), data, data + len);
    }
    const uint32_t crc = update_crc32(0, out.data() + type_start, 4 + len);
    write_be32(out, crc);
}

std::vector<uint8_t> encode_basic_png(
    int32_t width,
    int32_t height,
    int32_t channels,
    const uint8_t* pixels
) {
    std::vector<uint8_t> png;
    // 1. PNG Signature
    png.insert(png.end(), kPngMagic.begin(), kPngMagic.end());

    // 2. IHDR Chunk
    std::vector<uint8_t> ihdr;
    write_be32(ihdr, static_cast<uint32_t>(width));
    write_be32(ihdr, static_cast<uint32_t>(height));
    ihdr.push_back(8); // Bit depth: 8
    ihdr.push_back(static_cast<uint8_t>(channels == 4 ? 6 : (channels == 3 ? 2 : 0))); // Color type
    ihdr.push_back(0); // Compression method (deflate)
    ihdr.push_back(0); // Filter method (standard)
    ihdr.push_back(0); // Interlace method (none)
    write_chunk(png, "IHDR", ihdr.data(), ihdr.size());

    // 3. Raw Scanlines with filter byte (0 = None)
    const size_t row_bytes = static_cast<size_t>(width) * channels;
    std::vector<uint8_t> uncompressed;
    uncompressed.reserve(static_cast<size_t>(height) * (row_bytes + 1));
    for (int32_t y = 0; y < height; ++y) {
        uncompressed.push_back(0); // Filter byte 0 (None)
        if (pixels != nullptr) {
            uncompressed.insert(uncompressed.end(),
                                pixels + y * row_bytes,
                                pixels + (y + 1) * row_bytes);
        } else {
            uncompressed.insert(uncompressed.end(), row_bytes, 0);
        }
    }

    // 4. IDAT Chunk: Store uncompressed blocks (deflate type 00)
    std::vector<uint8_t> idat;
    idat.push_back(0x78); // zlib header
    idat.push_back(0x01); // compression level 0

    size_t offset = 0;
    while (offset < uncompressed.size()) {
        const size_t rem = uncompressed.size() - offset;
        const size_t chunk_size = (rem < 65535) ? rem : 65535;
        const bool is_last = (offset + chunk_size == uncompressed.size());
        idat.push_back(is_last ? 0x01 : 0x00);
        idat.push_back(static_cast<uint8_t>(chunk_size & 0xff));
        idat.push_back(static_cast<uint8_t>((chunk_size >> 8) & 0xff));
        const uint16_t nlen = static_cast<uint16_t>(~chunk_size);
        idat.push_back(static_cast<uint8_t>(nlen & 0xff));
        idat.push_back(static_cast<uint8_t>((nlen >> 8) & 0xff));
        idat.insert(idat.end(),
                    uncompressed.data() + offset,
                    uncompressed.data() + offset + chunk_size);
        offset += chunk_size;
    }

    const uint32_t adler = calc_adler32(uncompressed.data(), uncompressed.size());
    write_be32(idat, adler);
    write_chunk(png, "IDAT", idat.data(), idat.size());

    // 5. IEND Chunk
    write_chunk(png, "IEND", nullptr, 0);
    return png;
}

} // namespace

SdOutputTransaction::SdOutputTransaction(
    std::string target_path,
    mca::contract::RequestIdentity identity
)
    : target_path_(std::move(target_path)),
      partial_path_(target_path_ + ".part"),
      identity_(std::move(identity)) {
}

SdOutputTransaction::~SdOutputTransaction() {
    rollback();
}

SdOutputTransaction::SdOutputTransaction(SdOutputTransaction&& other) noexcept
    : target_path_(std::move(other.target_path_)),
      partial_path_(std::move(other.partial_path_)),
      identity_(std::move(other.identity_)),
      is_active_(other.is_active_),
      is_committed_(other.is_committed_),
      bytes_written_(other.bytes_written_) {
    other.is_active_ = false;
    other.is_committed_ = true; // prevent rollback in moved-from instance
}

SdOutputTransaction& SdOutputTransaction::operator=(SdOutputTransaction&& other) noexcept {
    if (this != &other) {
        rollback();
        target_path_ = std::move(other.target_path_);
        partial_path_ = std::move(other.partial_path_);
        identity_ = std::move(other.identity_);
        is_active_ = other.is_active_;
        is_committed_ = other.is_committed_;
        bytes_written_ = other.bytes_written_;
        other.is_active_ = false;
        other.is_committed_ = true;
    }
    return *this;
}

bool SdOutputTransaction::begin() {
    if (target_path_.empty()) {
        MCA_LOGE("SdOutputTransaction", "Target path cannot be empty");
        return false;
    }

    if (!output_parent_is_still_canonical(target_path_)) {
        MCA_LOGE("SdOutputTransaction",
                 "Output parent is not canonical or directory does not exist: %s",
                 target_path_.c_str());
        return false;
    }

#if !defined(_WIN32)
    struct stat stale_stat {};
    if (lstat(partial_path_.c_str(), &stale_stat) == 0) {
        if (!S_ISREG(stale_stat.st_mode) || unlink(partial_path_.c_str()) != 0) {
            MCA_LOGE("SdOutputTransaction",
                     "Stale partial output is not a removable regular file: %s",
                     partial_path_.c_str());
            return false;
        }
    } else if (errno != ENOENT) {
        MCA_LOGE("SdOutputTransaction",
                 "Partial output path could not be inspected: %s",
                 partial_path_.c_str());
        return false;
    }
#else
    std::remove(partial_path_.c_str());
#endif

    is_active_ = true;
    is_committed_ = false;
    bytes_written_ = 0;
    MCA_LOGI("SdOutputTransaction",
             "Begun output transaction for '%s' (partial: '%s')",
             target_path_.c_str(), partial_path_.c_str());
    return true;
}

bool SdOutputTransaction::write_data(const uint8_t* data, size_t size) {
    if (!is_active_) {
        MCA_LOGE("SdOutputTransaction", "Cannot write data: transaction not active");
        return false;
    }
    if (data == nullptr || size == 0) {
        return true;
    }

    std::ofstream out(partial_path_, std::ios::binary | std::ios::app);
    if (!out.is_open()) {
        MCA_LOGE("SdOutputTransaction", "Failed to open partial file: %s", partial_path_.c_str());
        return false;
    }

    out.write(reinterpret_cast<const char*>(data), size);
    if (!out.good()) {
        MCA_LOGE("SdOutputTransaction", "Write error on partial file: %s", partial_path_.c_str());
        return false;
    }

    bytes_written_ += size;
    return true;
}

bool SdOutputTransaction::write_pixels_as_png(
    int32_t width,
    int32_t height,
    int32_t channels,
    const uint8_t* pixels
) {
    if (!is_active_) {
        MCA_LOGE("SdOutputTransaction", "Cannot write pixels: transaction not active");
        return false;
    }
    if (pixels == nullptr || width <= 0 || height <= 0 || channels <= 0 ||
        channels > 4 || width > std::numeric_limits<int>::max() / channels) {
        MCA_LOGE("SdOutputTransaction",
                 "Invalid image dimensions or parameters: %dx%dx%d",
                 width, height, channels);
        return false;
    }

    bool wrote = false;
#if defined(__GNUC__) || defined(__clang__)
    if (&stbi_write_png != nullptr) {
        wrote = stbi_write_png(
            partial_path_.c_str(),
            width,
            height,
            channels,
            pixels,
            width * channels) != 0;
        if (wrote) {
            std::ifstream in(partial_path_, std::ios::binary | std::ios::ate);
            if (in.is_open()) {
                bytes_written_ = static_cast<uint64_t>(in.tellg());
            }
        }
    } else {
        const auto png_bytes = encode_basic_png(width, height, channels, pixels);
        wrote = write_data(png_bytes.data(), png_bytes.size());
    }
#else
    const auto png_bytes = encode_basic_png(width, height, channels, pixels);
    wrote = write_data(png_bytes.data(), png_bytes.size());
#endif

    if (!wrote) {
        std::remove(partial_path_.c_str());
        MCA_LOGE("SdOutputTransaction",
                 "Failed to encode PNG for partial path: %s",
                 partial_path_.c_str());
        return false;
    }
    return true;
}

OutputTransactionResult SdOutputTransaction::commit(
    const std::function<bool()>& cancel_checker
) {
    OutputTransactionResult result;
    result.target_path = target_path_;

    if (!is_active_) {
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::REQ_INVALID_STATE;
        err.category = mca::contract::ErrorCategory::Request;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "Transaction is not active";
        err.identity = identity_;
        result.error = err;
        return result;
    }

    // Cancellation check before atomic rename
    if (cancel_checker && cancel_checker()) {
        MCA_LOGW("SdOutputTransaction",
                 "Transaction cancelled before atomic rename for '%s'",
                 target_path_.c_str());
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::CNC_USER_CANCELLED;
        err.category = mca::contract::ErrorCategory::Cancel;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "cancelled before atomic PNG publication";
        err.identity = identity_;
        result.error = err;
        return result;
    }

#if !defined(_WIN32)
    const int descriptor = open(
        partial_path_.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "partial PNG could not be opened without following links";
        err.identity = identity_;
        result.error = err;
        return result;
    }

    struct stat partial_stat {};
    std::array<uint8_t, 8> observed_magic = {};
    const bool valid_partial =
        fstat(descriptor, &partial_stat) == 0 &&
        S_ISREG(partial_stat.st_mode) && partial_stat.st_size > 0 &&
        static_cast<uint64_t>(partial_stat.st_size) <= kMaxOutputSizeBytes &&
        pread(descriptor, observed_magic.data(), observed_magic.size(), 0) ==
            static_cast<ssize_t>(observed_magic.size()) &&
        observed_magic == kPngMagic && fsync(descriptor) == 0;
    const int close_result = close(descriptor);
    if (!valid_partial || close_result != 0) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "partial PNG failed bounded format or durability validation";
        err.identity = identity_;
        result.error = err;
        return result;
    }
#else
    std::ifstream partial_check(partial_path_, std::ios::binary | std::ios::ate);
    if (!partial_check.is_open()) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "partial PNG could not be opened";
        err.identity = identity_;
        result.error = err;
        return result;
    }

    const auto sz = partial_check.tellg();
    if (sz <= 0 || static_cast<uint64_t>(sz) > kMaxOutputSizeBytes) {
        partial_check.close();
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "partial PNG failed bounded format or durability validation";
        err.identity = identity_;
        result.error = err;
        return result;
    }

    partial_check.seekg(0);
    std::array<uint8_t, 8> observed_magic = {};
    partial_check.read(reinterpret_cast<char*>(observed_magic.data()), observed_magic.size());
    partial_check.close();
    if (sz >= 8 && observed_magic != kPngMagic) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "partial PNG failed bounded format or durability validation";
        err.identity = identity_;
        result.error = err;
        return result;
    }
#endif

    // Re-check cancellation immediately before rename
    if (cancel_checker && cancel_checker()) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::CNC_USER_CANCELLED;
        err.category = mca::contract::ErrorCategory::Cancel;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "cancelled before atomic PNG publication";
        err.identity = identity_;
        result.error = err;
        return result;
    }

#if !defined(_WIN32)
    struct stat target_stat {};
    errno = 0;
    if (!output_parent_is_still_canonical(target_path_) ||
        lstat(target_path_.c_str(), &target_stat) == 0 || errno != ENOENT) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "atomic PNG target changed before publication";
        err.identity = identity_;
        result.error = err;
        return result;
    }
#else
    if (!output_parent_is_still_canonical(target_path_)) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "atomic PNG target changed before publication";
        err.identity = identity_;
        result.error = err;
        return result;
    }
#endif

    // Atomic rename
    if (!platform_atomic_rename(partial_path_, target_path_)) {
        rollback();
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "atomic PNG rename failed";
        err.identity = identity_;
        result.error = err;
        return result;
    }

    // Parent directory commit synchronization
    std::string fsync_error;
    if (!fsync_output_parent(target_path_, fsync_error)) {
        std::remove(target_path_.c_str());
        std::string ignored;
        fsync_output_parent(target_path_, ignored);
        is_active_ = false;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = fsync_error.empty()
            ? "output directory commit could not be synchronized"
            : fsync_error;
        err.identity = identity_;
        result.error = err;
        return result;
    }

    // Post-rename committed target validation
    uint64_t committed_bytes = 0;
#if !defined(_WIN32)
    struct stat committed_stat {};
    if (lstat(target_path_.c_str(), &committed_stat) != 0 ||
        !S_ISREG(committed_stat.st_mode) || committed_stat.st_size <= 0 ||
        static_cast<uint64_t>(committed_stat.st_size) > kMaxOutputSizeBytes) {
        std::remove(target_path_.c_str());
        std::string ignored;
        fsync_output_parent(target_path_, ignored);
        is_active_ = false;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "committed PNG failed bounded regular-file validation";
        err.identity = identity_;
        result.error = err;
        return result;
    }
    committed_bytes = static_cast<uint64_t>(committed_stat.st_size);
#else
    std::ifstream committed_check(target_path_, std::ios::binary | std::ios::ate);
    if (!committed_check.is_open()) {
        std::remove(target_path_.c_str());
        is_active_ = false;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "committed PNG failed bounded regular-file validation";
        err.identity = identity_;
        result.error = err;
        return result;
    }
    const auto committed_sz = committed_check.tellg();
    committed_check.close();
    if (committed_sz <= 0 || static_cast<uint64_t>(committed_sz) > kMaxOutputSizeBytes) {
        std::remove(target_path_.c_str());
        is_active_ = false;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "committed PNG failed bounded regular-file validation";
        err.identity = identity_;
        result.error = err;
        return result;
    }
    committed_bytes = static_cast<uint64_t>(committed_sz);
#endif

    // Checksum verification of committed file
    const std::string sha256 = SdInputValidator::compute_file_sha256(target_path_);
    if (sha256.empty() || !is_hex_sha256(sha256)) {
        std::remove(target_path_.c_str());
        std::string ignored;
        fsync_output_parent(target_path_, ignored);
        is_active_ = false;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_READ_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "Failed to compute checksum of committed output";
        err.identity = identity_;
        result.error = err;
        return result;
    }

    is_committed_ = true;
    is_active_ = false;

    result.success = true;
    result.sha256_hex = sha256;
    result.bytes_written = committed_bytes;
    result.target_path = target_path_;

    MCA_LOGI("SdOutputTransaction",
             "Committed output '%s' (%llu bytes, sha256: %s)",
             target_path_.c_str(),
             static_cast<unsigned long long>(result.bytes_written),
             result.sha256_hex.c_str());

    return result;
}

void SdOutputTransaction::rollback() noexcept {
    if (!is_committed_ && is_active_) {
        MCA_LOGI("SdOutputTransaction", "Rolling back partial file: %s", partial_path_.c_str());
        std::remove(partial_path_.c_str());
        is_active_ = false;
        bytes_written_ = 0;
    }
}

OutputTransactionResult SdOutputTransaction::write_png_atomic(
    const std::string& target,
    int32_t width,
    int32_t height,
    int32_t channels,
    const uint8_t* pixels,
    const mca::contract::RequestIdentity& identity,
    const std::function<bool()>& cancel_checker
) {
    SdOutputTransaction tx(target, identity);
    if (!tx.begin()) {
        OutputTransactionResult res;
        res.target_path = target;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "atomic PNG request or output parent is invalid";
        err.identity = identity;
        res.error = err;
        return res;
    }

    if (!tx.write_pixels_as_png(width, height, channels, pixels)) {
        tx.rollback();
        OutputTransactionResult res;
        res.target_path = target;
        mca::contract::ErrorEnvelope err;
        err.code = mca::contract::StableErrorCode::STR_WRITE_ERROR;
        err.category = mca::contract::ErrorCategory::Storage;
        err.phase = mca::contract::ErrorPhase::Persist;
        err.public_message = "native PNG encoder failed before atomic publication";
        err.identity = identity;
        res.error = err;
        return res;
    }

    return tx.commit(cancel_checker);
}

} // namespace sd
} // namespace mca
