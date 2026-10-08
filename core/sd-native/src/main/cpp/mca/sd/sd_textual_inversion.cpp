#include "mca/sd/sd_textual_inversion.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <iomanip>
#include <limits.h>
#include <limits>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif

#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif

namespace mca {
namespace sd {

namespace {

inline uint32_t rotate_right(uint32_t value, uint32_t amount) noexcept {
    return (value >> amount) | (value << (32u - amount));
}

class ScopedFileDescriptor {
public:
    explicit ScopedFileDescriptor(int descriptor) : descriptor_(descriptor) {}

    ~ScopedFileDescriptor() {
        if (descriptor_ >= 0) {
            close(descriptor_);
        }
    }

    int get() const { return descriptor_; }

private:
    int descriptor_;
};

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

} // namespace

// ============================================================================
// RegularFileIdentity
// ============================================================================

RegularFileIdentity regular_file_identity(const struct stat &value) {
    return RegularFileIdentity{
            static_cast<uint64_t>(value.st_dev),
            static_cast<uint64_t>(value.st_ino),
            value.st_size >= 0 ? static_cast<uint64_t>(value.st_size) : 0u,
            static_cast<int64_t>(value.st_mtime),
            static_cast<int64_t>(value.st_ctime)};
}

bool same_regular_file_identity(const RegularFileIdentity &left,
                                const RegularFileIdentity &right) {
    return left.device == right.device &&
           left.inode == right.inode &&
           left.size == right.size &&
           left.modification_time == right.modification_time &&
           left.status_change_time == right.status_change_time;
}

bool read_regular_file_identity(const std::string &path,
                                uint64_t expected_size,
                                RegularFileIdentity &identity,
                                std::string &error) {
    struct stat value {};
    if (lstat(path.c_str(), &value) != 0 || !S_ISREG(value.st_mode)) {
        error = "upscaler model is no longer a regular non-symlink file";
        return false;
    }
    identity = regular_file_identity(value);
    if (identity.size != expected_size) {
        error = "upscaler model size changed during native execution";
        return false;
    }
    return true;
}

// ============================================================================
// Sha256Accumulator
// ============================================================================

Sha256Accumulator::Sha256Accumulator()
        : state_{{
                0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
          }},
          buffer_{},
          buffer_size_{0u},
          total_bytes_{0u} {}

void Sha256Accumulator::update(const uint8_t *bytes, size_t size) {
    if (size == 0u) {
        return;
    }
    total_bytes_ += static_cast<uint64_t>(size);
    if (buffer_size_ > 0u) {
        const size_t consumed = std::min(size, buffer_.size() - buffer_size_);
        std::memcpy(buffer_.data() + buffer_size_, bytes, consumed);
        buffer_size_ += consumed;
        bytes += consumed;
        size -= consumed;
        if (buffer_size_ == buffer_.size()) {
            transform(buffer_.data());
            buffer_size_ = 0u;
        }
    }
    while (size >= buffer_.size()) {
        transform(bytes);
        bytes += buffer_.size();
        size -= buffer_.size();
    }
    if (size > 0u) {
        std::memcpy(buffer_.data(), bytes, size);
        buffer_size_ = size;
    }
}

std::string Sha256Accumulator::finish_hex() {
    const uint64_t bit_count = total_bytes_ * 8u;
    buffer_[buffer_size_++] = 0x80u;
    if (buffer_size_ > 56u) {
        std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_), buffer_.end(), 0u);
        transform(buffer_.data());
        buffer_size_ = 0u;
    }
    std::fill(
            buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_),
            buffer_.begin() + 56,
            0u);
    for (size_t index = 0; index < 8u; ++index) {
        buffer_[63u - index] = static_cast<uint8_t>(bit_count >> (index * 8u));
    }
    transform(buffer_.data());

    static constexpr char hex[] = "0123456789abcdef";
    std::string result(64, '0');
    size_t output = 0;
    for (uint32_t word: state_) {
        for (int shift = 28; shift >= 0; shift -= 4) {
            result[output++] = hex[(word >> static_cast<uint32_t>(shift)) & 0x0fu];
        }
    }
    return result;
}

void Sha256Accumulator::transform(const uint8_t *block) {
    static constexpr std::array<uint32_t, 64> constants = {{
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
            0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
            0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
            0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
            0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
            0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
            0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
            0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
            0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
    }};
    std::array<uint32_t, 64> words = {};
    for (size_t i = 0; i < 16; ++i) {
        const size_t offset = i * 4;
        words[i] = (static_cast<uint32_t>(block[offset]) << 24u) |
                   (static_cast<uint32_t>(block[offset + 1]) << 16u) |
                   (static_cast<uint32_t>(block[offset + 2]) << 8u) |
                   static_cast<uint32_t>(block[offset + 3]);
    }
    for (size_t i = 16; i < words.size(); ++i) {
        const uint32_t s0 = rotate_right(words[i - 15], 7u) ^
                            rotate_right(words[i - 15], 18u) ^
                            (words[i - 15] >> 3u);
        const uint32_t s1 = rotate_right(words[i - 2], 17u) ^
                            rotate_right(words[i - 2], 19u) ^
                            (words[i - 2] >> 10u);
        words[i] = words[i - 16] + s0 + words[i - 7] + s1;
    }
    uint32_t a = state_[0];
    uint32_t b = state_[1];
    uint32_t c = state_[2];
    uint32_t d = state_[3];
    uint32_t e = state_[4];
    uint32_t f = state_[5];
    uint32_t g = state_[6];
    uint32_t h = state_[7];
    for (size_t i = 0; i < words.size(); ++i) {
        const uint32_t sigma1 = rotate_right(e, 6u) ^
                                rotate_right(e, 11u) ^
                                rotate_right(e, 25u);
        const uint32_t choose = (e & f) ^ ((~e) & g);
        const uint32_t temp1 = h + sigma1 + choose + constants[i] + words[i];
        const uint32_t sigma0 = rotate_right(a, 2u) ^
                                rotate_right(a, 13u) ^
                                rotate_right(a, 22u);
        const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        const uint32_t temp2 = sigma0 + majority;
        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }
    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
    state_[4] += e;
    state_[5] += f;
    state_[6] += g;
    state_[7] += h;
}

// ============================================================================
// SHA-256 and Fingerprinting Helpers
// ============================================================================

std::string sha256_hex(const std::vector<uint8_t> &bytes) {
    Sha256Accumulator accumulator;
    if (!bytes.empty()) {
        accumulator.update(bytes.data(), bytes.size());
    }
    return accumulator.finish_hex();
}

std::string sha256_utf8(const std::string &value) {
    Sha256Accumulator accumulator;
    if (!value.empty()) {
        accumulator.update(
                reinterpret_cast<const uint8_t *>(value.data()),
                value.size());
    }
    return accumulator.finish_hex();
}

std::string fixed_width_lower_hex_u64(uint64_t value) {
    std::ostringstream out;
    out << std::hex << std::nouppercase << std::setfill('0') << std::setw(16) << value;
    return out.str();
}

std::string textual_inversion_binding_fingerprint(
        const ContractTextualInversion &artifact) {
    std::ostringstream source;
    source << "textual-inversion-binding-v1" << '\x1f'
           << artifact.id << '\x1f'
           << artifact.sha256 << '\x1f'
           << lower_copy(artifact.trigger) << '\x1f'
           << lower_copy(artifact.model_fingerprint) << '\x1f'
           << lower_copy(artifact.tokenizer_fingerprint) << '\x1f'
           << artifact.profile_id << '\x1f'
           << artifact.profile_revision << '\x1f'
           << artifact.runtime;
    return sha256_utf8(source.str());
}

std::string textual_inversion_selection_fingerprint(
        const std::vector<ContractTextualInversion> &artifacts) {
    std::vector<std::pair<std::string, std::string>> ordered;
    ordered.reserve(artifacts.size());
    for (const auto &artifact: artifacts) {
        ordered.emplace_back(
                lower_copy(artifact.trigger),
                textual_inversion_binding_fingerprint(artifact));
    }
    std::sort(ordered.begin(), ordered.end(), [](const auto &left, const auto &right) {
        return left.first < right.first;
    });
    std::ostringstream source;
    source << "textual-inversion-selection-v1";
    for (const auto &entry: ordered) {
        source << '\x1f' << entry.second;
    }
    return sha256_utf8(source.str());
}

std::string image_prompt_execution_sha256(const std::string &prompt,
                                          const std::string &negative_prompt) {
    if (prompt.size() > static_cast<size_t>(std::numeric_limits<uint32_t>::max()) ||
        negative_prompt.size() > static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
        return {};
    }
    Sha256Accumulator accumulator;
    const auto update_non_null_utf8 = [&accumulator](const std::string &value) {
        const uint8_t present = 1u;
        accumulator.update(&present, 1u);
        const uint32_t size = static_cast<uint32_t>(value.size());
        const std::array<uint8_t, 4> length = {{
                static_cast<uint8_t>((size >> 24u) & UINT32_C(0xff)),
                static_cast<uint8_t>((size >> 16u) & UINT32_C(0xff)),
                static_cast<uint8_t>((size >> 8u) & UINT32_C(0xff)),
                static_cast<uint8_t>(size & UINT32_C(0xff))
        }};
        accumulator.update(length.data(), length.size());
        if (!value.empty()) {
            accumulator.update(
                    reinterpret_cast<const uint8_t *>(value.data()),
                    value.size());
        }
    };
    update_non_null_utf8(prompt);
    update_non_null_utf8(negative_prompt);
    return accumulator.finish_hex();
}

bool sha256_implementation_ready() {
    static const bool ready = [] {
        const std::vector<uint8_t> empty;
        const std::vector<uint8_t> abc = {'a', 'b', 'c'};
        const std::string chinese_prompt =
                "\xE4\xB8\x80\xE5\x8F\xAA\xE7\xBA\xA2\xE8\x89\xB2\xE6\x9D\xAF\xE5\xAD\x90"
                "\xE6\x94\xBE\xE5\x9C\xA8\xE8\x93\x9D\xE8\x89\xB2\xE6\xA1\x8C\xE5\xAD\x90"
                "\xE4\xB8\x8A\xEF\xBC\x8C\xE6\x9D\xAF\xE5\xAD\x90\xE5\xB7\xA6\xE4\xBE\xA7"
                "\xE6\x9C\x89\xE4\xB8\xA4\xE4\xB8\xAA\xE7\xBB\xBF\xE8\x89\xB2\xE8\x8B\xB9"
                "\xE6\x9E\x9C";
        const std::string chinese_negative_prompt =
                "\xE4\xB8\x8D\xE8\xA6\x81\xE4\xBA\xBA\xE7\x89\xA9\xEF\xBC\x8C\xE4\xB8\x8D"
                "\xE8\xA6\x81\xE6\x96\x87\xE5\xAD\x97\xEF\xBC\x8C\xE4\xB8\x8D\xE8\xA6\x81"
                "\xE5\xA4\x9A\xE4\xBD\x99\xE6\xB0\xB4\xE6\x9E\x9C";
        ContractTextualInversion textual_vector;
        textual_vector.id = "00000000-0000-0000-0000-000000000001";
        textual_vector.sha256 =
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        textual_vector.trigger = "cat";
        textual_vector.model_fingerprint =
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        textual_vector.tokenizer_fingerprint =
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        textual_vector.profile_id = "stable-test";
        textual_vector.profile_revision = 1;
        textual_vector.runtime = "STABLE_DIFFUSION_CPP";
        return sha256_hex(empty) ==
                       "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" &&
               sha256_hex(abc) ==
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" &&
               image_prompt_execution_sha256("a", "") ==
                       "7b2c4dab5874fc03fd088691ce5bea0ff34ded267b495fc5249853dd2110bed7" &&
               image_prompt_execution_sha256(
                       chinese_prompt,
                       chinese_negative_prompt) ==
                       "77debda388ee058e0ce51f89e799e2ca7828a06142fc77b2b19cff3844b93579" &&
               textual_inversion_binding_fingerprint(textual_vector) ==
                       "877205ad6c36ea77782b8c819e0859ec5b70a57541a3c91b357bbdb618867b97" &&
               textual_inversion_selection_fingerprint(
                       std::vector<ContractTextualInversion>{textual_vector}) ==
                       "a9bb38fd3ed3576e7ab41cef7d3aa687bb066bd55e699b7dcff4298c8ca75e2d";
    }();
    return ready;
}

// ============================================================================
// File Hashing and Validation
// ============================================================================

bool sha256_regular_file(const std::string &path,
                         uint64_t expected_size,
                         std::string &digest,
                         RegularFileIdentity &identity,
                         std::string &error,
                         const std::function<bool()> &is_cancelled) {
    if (!sha256_implementation_ready()) {
        error = "native SHA-256 self-test failed";
        return false;
    }
    ScopedFileDescriptor file(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (file.get() < 0) {
        error = "upscaler model could not be opened for native hashing";
        return false;
    }
    struct stat before {};
    if (fstat(file.get(), &before) != 0 || !S_ISREG(before.st_mode)) {
        error = "upscaler model descriptor is not a regular file";
        return false;
    }
    identity = regular_file_identity(before);
    if (identity.size != expected_size) {
        error = "upscaler model size changed before native hashing";
        return false;
    }

    Sha256Accumulator accumulator;
    std::array<uint8_t, 64u * 1024u> buffer = {};
    uint64_t bytes_read = 0u;
    while (true) {
        if (is_cancelled && is_cancelled()) {
            error = "cancelled";
            return false;
        }
        const ssize_t count = read(file.get(), buffer.data(), buffer.size());
        if (count == 0) {
            break;
        }
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            error = "upscaler model could not be read for native hashing";
            return false;
        }
        const size_t chunk = static_cast<size_t>(count);
        accumulator.update(buffer.data(), chunk);
        bytes_read += static_cast<uint64_t>(chunk);
        if (bytes_read > expected_size) {
            error = "upscaler model grew during native hashing";
            return false;
        }
    }

    struct stat after {};
    if (fstat(file.get(), &after) != 0 ||
        !same_regular_file_identity(identity, regular_file_identity(after)) ||
        bytes_read != expected_size) {
        error = "upscaler model changed while native was hashing it";
        return false;
    }
    digest = accumulator.finish_hex();
    return true;
}

bool validate_textual_inversion_file(const std::string &raw_root,
                                     const ContractTextualInversion &artifact,
                                     std::string &canonical_path,
                                     std::string &actual_sha256,
                                     std::string &error,
                                     const std::function<bool()> &is_cancelled) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr ||
        !dir_exists(root_buffer)) {
        error = "textual inversion root does not exist";
        return false;
    }
    struct stat link_stat {};
    if (artifact.path.empty() || lstat(artifact.path.c_str(), &link_stat) != 0 ||
        !S_ISREG(link_stat.st_mode)) {
        error = "textual inversion must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size < 0 || static_cast<uint64_t>(link_stat.st_size) != artifact.size_bytes) {
        error = "textual inversion size differs from the worker-verified contract";
        return false;
    }
    if (realpath(artifact.path.c_str(), path_buffer) == nullptr ||
        !file_exists(path_buffer)) {
        error = "textual inversion file does not exist";
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path != artifact.path) {
        error = "textual inversion path must already be canonical";
        return false;
    }
    const size_t separator = path.find_last_of('/');
    if (separator == std::string::npos || path.substr(0, separator) != root) {
        error = "textual inversion must be a direct child of its app-owned root";
        return false;
    }
    const std::string lower = lower_copy(path);
    const bool extension_ok =
            (artifact.format == "safetensors" && ends_with(lower, ".safetensors")) ||
            (artifact.format == "pytorch" && ends_with(lower, ".pt")) ||
            (artifact.format == "checkpoint" && ends_with(lower, ".ckpt")) ||
            (artifact.format == "binary" && ends_with(lower, ".bin"));
    if (!extension_ok) {
        error = "textual inversion format does not match its extension";
        return false;
    }
    RegularFileIdentity identity;
    if (!sha256_regular_file(
            path,
            artifact.size_bytes,
            actual_sha256,
            identity,
            error,
            is_cancelled)) {
        return false;
    }
    if (actual_sha256 != artifact.sha256) {
        error = "textual inversion SHA-256 differs from the worker-verified contract";
        return false;
    }
    canonical_path = path;
    return true;
}

} // namespace sd
} // namespace mca
